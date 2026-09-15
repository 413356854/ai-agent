package ai.agent.agent.limit;

import ai.agent.config.AgentConcurrencyProperties;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 熔断器：当一把工具（或它依赖的下游）已经<b>大面积坏了</b>时，别再让每个请求都去撞一次。
 *
 * <h2>它解决的问题与"重试"正好相反</h2>
 * 重试是"再试一次，说不定就好了"；熔断是"已经确定它坏了，别试了"。
 * 没有它，一个坏掉的下游会被每个请求都去撞一次，而每次都要等超时才失败 ——
 * 一个 6 轮的任务就是 6 次纯等待（比如每次 30 秒），<b>而那些等待没有任何成功可能</b>。
 *
 * <h2>三个状态，以及为什么必须有第三个</h2>
 * <pre>
 *   CLOSED  ──失败率达到阈值→  OPEN  ──冷却期到→  HALF_OPEN
 *     ↑                                              │
 *     └────────── 试探成功 ──────────────────────────┘
 *                    │
 *                    └── 试探失败 → 回到 OPEN（重新计时）
 * </pre>
 * 只有 CLOSED/OPEN 两个状态的话，打开之后就<b>永远没有恢复的路</b>：
 * 要么永久不可用（那不如不熔断），要么靠人工重启。
 * HALF_OPEN 就是"给它一次机会"——而且<b>只给有限次</b>，因为没恢复时并发去撞只是白挨。
 *
 * <h2>"失败"的定义是本类最关键的地方（也是最容易做错的地方）</h2>
 * 只有 {@link ai.agent.tool.ToolCallStage#EXECUTION_ERROR} 算失败。
 * <b>模型把参数写错、以及被限流拒掉，都不算。</b>
 *
 * <p>理由不是洁癖，而是算错方向会让熔断器做出与初衷相反的事：
 * <ul>
 *   <li>把参数错算失败 → 模型偶尔连写几个错参数，就把一个完全健康的工具熔断掉。
 *       <b>模型的错不该由工具的可用性来买单</b>；</li>
 *   <li>把限流拒绝算失败 → 限流期间被拒的请求累积成"高失败率" → 熔断打开 →
 *       于是既限流又熔断，<b>保护措施自己制造了一次故障</b>。</li>
 * </ul>
 *
 * <h2>为什么统计窗口用"最近 N 次"而不是"最近 N 秒"</h2>
 * 两种都常见，这里选"次数"是因为它<b>与请求量无关</b>：
 * 时间窗口在低流量下可能只装进 1~2 次调用，于是 {@code minimumCalls} 永远凑不够、
 * 熔断永不生效；而那个现象是"熔断配了但从来没打开过"，很难联想到"是流量太低"。
 * 次数窗口在低流量下会"保守地不熔断"，那是可接受的失败方向。
 *
 * <h2>并发安全</h2>
 * 所有状态变更都在 {@code synchronized} 里。这是少数几个我主动选 {@code synchronized}
 * 而不是 {@link java.util.concurrent.locks.ReentrantLock} 的地方：
 * 临界区只有几步算术、没有 IO、没有阻塞调用，<b>不可能把载体线程钉住</b>
 * （阶段一实测过 {@code synchronized} 里做阻塞会毁掉虚拟线程的并发能力 ——
 * 那条规则的关键是"里面有没有阻塞"，不是"能不能用 synchronized"）。
 */
public final class CircuitBreaker {

	/** 熔断器的状态。 */
	public enum State {
		/** 正常放行，同时统计失败率。 */
		CLOSED,
		/** 已熔断：直接拒绝，不发出调用。 */
		OPEN,
		/** 冷却期结束，放行少量试探请求来判断是否恢复。 */
		HALF_OPEN
	}

	private final String name;

	private final AgentConcurrencyProperties.Circuit config;

	/** 最近若干次结果（true = 成功）。用 Deque 当滑动窗口。 */
	private final Deque<Boolean> window = new ArrayDeque<>();

	private State state = State.CLOSED;

	/** 进入 OPEN 的时刻（毫秒）。冷却期从它开始算。 */
	private long openedAtMs;

	/** 半开状态下已经放行出去的试探数。 */
	private int halfOpenInFlight;

	/** 统计：累计打开过几次（报告里用它证明"熔断真的动作过"）。 */
	private long openCount;

	/** 统计：打开期间被直接拒掉的调用数（= 省下的等待次数）。 */
	private long shortCircuited;

	public CircuitBreaker(String name, AgentConcurrencyProperties.Circuit config) {
		this.name = name;
		this.config = config;
	}

	/**
	 * 撤销一次已经放行、但<b>最终没执行</b>的许可。
	 *
	 * <h2>为什么需要它（这是一个真实的顺序问题）</h2>
	 * 调用链的顺序是"先问熔断，再抢闸门"。如果熔断放行了（尤其是它在
	 * {@code OPEN → HALF_OPEN} 转换时放出的那<b>唯一一个</b>试探名额），
	 * 而闸门随后拒绝 —— 那么这次试探名额就被<b>永久占着</b>：
	 * {@code halfOpenInFlight} 加了 1，但不会有 {@link #record} 来把它减掉
	 * （因为调用根本没发生）。后果是半开状态再也放不出试探请求，
	 * 于是熔断器<b>永远恢复不了</b> —— 而现象是"那把工具一直不可用"，
	 * 排查方向会跑到下游是否真的坏了上面去。
	 *
	 * <p>同理，在 {@code HALF_OPEN} 里放行的名额也要还回去。
	 * 在 {@code CLOSED} 下它是空操作（那个状态没有"名额"这个概念）。
	 */
	public synchronized void undoAcquire() {
		if (!config.enabled()) {
			return;
		}
		if (state == State.HALF_OPEN && halfOpenInFlight > 0) {
			halfOpenInFlight--;
		}
	}

	/**
	 * 现在允不允许发出这次调用。
	 *
	 * @return {@code null} 表示放行；否则返回<b>拒绝原因</b>（给模型/人看的一句话）
	 */
	public synchronized String tryAcquire() {
		if (!config.enabled()) {
			return null;
		}
		switch (state) {
			case CLOSED -> {
				return null;
			}
			case OPEN -> {
				if (elapsedSinceOpened() >= config.openDurationMs()) {
					// 冷却期到了 → 转半开，并放行这一次（作为试探）
					state = State.HALF_OPEN;
					halfOpenInFlight = 1;
					return null;
				}
				shortCircuited++;
				long remain = config.openDurationMs() - elapsedSinceOpened();
				return ("「%s」最近失败率过高，熔断器已打开，本次调用**没有发出去**（约 %d 毫秒后才会开始试探恢复）。"
						+ "请**换用其它工具**，或者直接用已有信息回答 —— 用同样的参数重试只会在冷却期内被再次拒绝。")
						.formatted(name, remain);
			}
			case HALF_OPEN -> {
				if (halfOpenInFlight < config.halfOpenProbes()) {
					halfOpenInFlight++;
					return null;
				}
				shortCircuited++;
				return ("「%s」正在试探恢复中（已放行 %d 个试探请求），本次调用没有发出。"
						+ "请稍后再试或换用其它工具。").formatted(name, halfOpenInFlight);
			}
			default -> {
				return null;
			}
		}
	}

	/**
	 * 记一次结果，并按需要切换状态。
	 *
	 * <p><b>调用方必须先 {@link #tryAcquire()} 拿到放行</b>，再在结束时调本方法 ——
	 * 否则会把"没发出去的调用"也记进窗口里，让失败率变成假的。
	 *
	 * @param toolFailure 是不是<b>工具侧的失败</b>。
	 *                    注意：这个布尔应该来自 {@code stage.countsAsToolFailure()}，
	 *                    而不是"这次调用没成功"
	 */
	public synchronized void record(boolean toolFailure) {
		if (!config.enabled()) {
			return;
		}

		window.addLast(!toolFailure);
		while (window.size() > config.windowSize()) {
			window.removeFirst();
		}

		switch (state) {
			case CLOSED -> {
				// 只有样本够了才评估：没有 minimumCalls 的话，首次失败就是 100% 失败率，
				// 一个刚启动的健康工具会被立刻熔断
				if (window.size() >= config.minimumCalls() && failureRate() >= config.failureRateThreshold()) {
					open();
				}
			}
			case HALF_OPEN -> {
				if (toolFailure) {
					// 试探失败 → 回到 OPEN，冷却重新计时。
					// 注意这里**不清空窗口**：清空会让"刚恢复失败就立刻又被判为健康"，
					// 保留历史才能让"反复不行"的下游被更快地再次熔断
					open();
				}
				else {
					// 试探成功 → 恢复。
					// 【为什么要清空窗口】半开期间的窗口里还留着熔断前的失败记录，
					// 不清掉的话它会立刻又触发阈值，于是状态在 OPEN/HALF_OPEN 之间抖动，
					// 而表现是"一直都在试探、几乎不放行" —— 比直接熔断还难查
					window.clear();
					halfOpenInFlight = 0;
					state = State.CLOSED;
				}
			}
			case OPEN -> {
				// OPEN 期间正常不会走到这里（tryAcquire 已经拒了）。
				// 但有一个例外：冷却期刚好在请求发出后到期，那次请求的结果现在才回来 ——
				// 它其实是半开的试探，按试探规则处理
				if (toolFailure) {
					openedAtMs = System.currentTimeMillis();
				}
				else {
					window.clear();
					halfOpenInFlight = 0;
					state = State.CLOSED;
				}
			}
		}
	}

	private void open() {
		state = State.OPEN;
		openedAtMs = System.currentTimeMillis();
		halfOpenInFlight = 0;
		openCount++;
	}

	/** 当前失败率（窗口内失败数 / 窗口大小）。窗口为空时返回 0。 */
	public synchronized double failureRate() {
		if (window.isEmpty()) {
			return 0.0;
		}
		long failures = window.stream().filter(ok -> !ok).count();
		return (double) failures / window.size();
	}

	public synchronized State state() {
		// 快照时顺手做一次"冷却期是否已到"的判断：报告上显示的还是 OPEN、
		// 而实际上已经可以试探了，会让人以为熔断没恢复
		if (state == State.OPEN && config.enabled() && elapsedSinceOpened() >= config.openDurationMs()) {
			return State.HALF_OPEN;
		}
		return state;
	}

	public String name() {
		return name;
	}

	public synchronized int windowSize() {
		return window.size();
	}

	public synchronized long openCount() {
		return openCount;
	}

	public synchronized long shortCircuitedCount() {
		return shortCircuited;
	}

	private long elapsedSinceOpened() {
		return System.currentTimeMillis() - openedAtMs;
	}

	/** 一份快照（报告与诊断端点用）。 */
	public record Snapshot(String name, State state, int windowSize, double failureRate, long openCount,
			long shortCircuited) {
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(name, state(), window.size(), failureRate(), openCount, shortCircuited);
	}

	/** 仅供测试：把状态强制设成某个值（用来确定性地构造"已经打开"的场景）。 */
	synchronized void forceStateForTesting(State forced) {
		this.state = forced;
		if (forced == State.OPEN) {
			this.openedAtMs = System.currentTimeMillis();
		}
	}

}
