package ai.agent.service.agent.limit;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 并发闸门：把"同时有多少个请求在下游"这个量<b>按住</b>。
 *
 * <h2>为什么是信号量，而不是"每秒 N 个"的速率限制</h2>
 * 因为真正的约束是<b>下游能同时扛住多少在途请求</b>，不是"每秒能收多少"。
 * 速率限制（令牌桶之类）只数"发起"，<b>不数"还没回来"</b> ——
 * 于是下游一变慢，同样的 QPS 会让在途请求越堆越多，而<b>那恰恰是最需要保护的时候</b>。
 * 信号量直接限制在途数量：下游变慢时许可被占着不还，闸门自动收紧。
 *
 * <h2>"排队"在这个架构下可接受，但它必须带超时</h2>
 * 虚拟线程让阻塞在信号量上的代价极低（正是阶段一那次改造换来的能力），
 * 所以"等一会儿"比"立刻拒绝"更友好 —— 多数情况下前面的请求几百毫秒就结束了。
 *
 * <p>但<b>超时不可省</b>：没有它，队列可以长到看不见头，而表现是最糟的一种 ——
 * 界面一直转圈、没有任何错误、日志里也没有失败（因为确实没失败，只是在等）。
 * 所以 {@link #tryAcquire()} 失败时返回一个<b>明确的拒绝原因</b>，
 * 由上层变成一条可回填的 observation。
 *
 * <h2>为什么要统计"排队等了多久"</h2>
 * 这是本类最容易被忽略、却最有用的一部分：<b>限流本身不产生错误，它产生延迟</b>。
 * 一个只有"拒绝次数"的埋点，在"闸门一直没满、但每次都等 2 秒"的情况下会显示一切正常——
 * 而用户体感已经变差了。所以 {@link #averageWaitMs()} 与 {@link #maxWaitMs()} 是必须的：
 * 它们把"限流造成的延迟"变成可观测的数字，而不是"最近怎么有点慢"。
 */
public final class ConcurrencyGate {

	private final String name;

	private final int maxConcurrent;

	private final Semaphore permits;

	private final long acquireTimeoutMs;

	/** 当前在途数量（自己数，因为 {@link Semaphore} 不暴露"已用多少个"）。 */
	private final AtomicInteger inFlight = new AtomicInteger();

	/** 在途峰值 —— "闸门到底有没有真的顶到上限"的唯一直接证据。 */
	private final AtomicInteger peakInFlight = new AtomicInteger();

	private final AtomicLong acquired = new AtomicLong();

	private final AtomicLong rejected = new AtomicLong();

	private final AtomicLong totalWaitMs = new AtomicLong();

	private final AtomicLong maxWaitMs = new AtomicLong();

	public ConcurrencyGate(String name, int maxConcurrent, long acquireTimeoutMs) {
		if (maxConcurrent < 1) {
			throw new IllegalArgumentException("并发上限必须 >= 1，实际 " + maxConcurrent);
		}
		if (acquireTimeoutMs <= 0) {
			// 与配置类里同一条理由：无限等待在虚拟线程下太便宜，会把故障模式变成"静默挂起"
			throw new IllegalArgumentException("排队超时必须为正，实际 " + acquireTimeoutMs);
		}
		this.name = name;
		this.maxConcurrent = maxConcurrent;
		// 公平模式：先到先得。非公平在有大量短请求时吞吐略高，但会让个别请求饿死
		// （一直抢不到）。"饿死"在限流场景下极难排查 —— 表现是"偶发某个请求特别慢"，
		// 看起来像下游抖动。可预测性比那点吞吐重要
		this.permits = new Semaphore(maxConcurrent, true);
		this.acquireTimeoutMs = acquireTimeoutMs;
	}

	/**
	 * 拿一个许可（会等）。
	 *
	 * @return 拿到就返回 {@code null}；等不到返回<b>拒绝原因</b>（要给模型/人看的一句话）。
	 *         返回字符串而不是抛异常：拒绝是<b>预期内的结果</b>（限流就是用来拒绝的），
	 *         用异常表达会让它混进"故障"那一类，而两者对模型要引导出相反的动作。
	 */
	public String tryAcquire() {
		long startedAt = System.nanoTime();
		try {
			boolean ok = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
			long waited = elapsedMs(startedAt);
			if (!ok) {
				rejected.incrementAndGet();
				return ("「%s」当前并发已满（上限 %d，在途 %d），等待 %d 毫秒仍未拿到名额，本次调用被拒绝。"
						+ "这是**暂时的**：稍后用同样的参数重试即可，不需要换参数或换工具。")
						.formatted(name, maxConcurrent, inFlight.get(), acquireTimeoutMs);
			}
			acquired.incrementAndGet();
			recordWait(waited);
			int current = inFlight.incrementAndGet();
			updatePeak(current);
			return null;
		}
		catch (InterruptedException e) {
			// 中断要恢复标志位：吞掉它会让上层的取消/超时逻辑失效
			Thread.currentThread().interrupt();
			rejected.incrementAndGet();
			return "「%s」的等待被中断（通常是上层已超时或已取消），本次调用未执行。".formatted(name);
		}
	}

	/**
	 * 释放许可。
	 *
	 * <p><b>必须在 finally 里调用</b>：漏掉一次，闸门就永久少一个名额，
	 * 而且不会有任何报错 —— 表现是"系统越跑越慢，最后完全卡住"，
	 * 而重启之后又好了（因为计数归零）。这类泄漏最难查，所以调用点一律用 try/finally。
	 */
	public void release() {
		inFlight.decrementAndGet();
		permits.release();
	}

	private void recordWait(long waitedMs) {
		totalWaitMs.addAndGet(waitedMs);
		// 用 CAS 循环更新最大值：不能 get 之后再 set，并发下会丢掉较大的那个值，
		// 而"最长排队"恰恰是用来发现最坏情况的
		long current;
		do {
			current = maxWaitMs.get();
			if (waitedMs <= current) {
				return;
			}
		}
		while (!maxWaitMs.compareAndSet(current, waitedMs));
	}

	private void updatePeak(int current) {
		int peak;
		do {
			peak = peakInFlight.get();
			if (current <= peak) {
				return;
			}
		}
		while (!peakInFlight.compareAndSet(peak, current));
	}

	public String name() {
		return name;
	}

	/** 当前在途数量。 */
	public int inFlight() {
		return inFlight.get();
	}

	public int maxConcurrent() {
		return maxConcurrent;
	}

	/** 在途峰值。**它是"闸门真的顶到过上限"的直接证据** —— 报告里靠它判断限流有没有生效。 */
	public int peakInFlight() {
		return peakInFlight.get();
	}

	public long acquiredCount() {
		return acquired.get();
	}

	public long rejectedCount() {
		return rejected.get();
	}

	public long totalWaitMs() {
		return totalWaitMs.get();
	}

	public long maxWaitMs() {
		return maxWaitMs.get();
	}

	/** 平均排队时长（毫秒）。没有成功过就返回 0 —— 不是无穷大，那会让报表显示 NaN。 */
	public double averageWaitMs() {
		long count = acquired.get();
		return count == 0 ? 0.0 : (double) totalWaitMs.get() / count;
	}

	/** 一份快照（报告与诊断端点用）。 */
	public record Snapshot(String name, int maxConcurrent, int inFlight, int peakInFlight, long acquired,
			long rejected, double averageWaitMs, long maxWaitMs) {
	}

	public Snapshot snapshot() {
		return new Snapshot(name, maxConcurrent, inFlight.get(), peakInFlight.get(), acquired.get(), rejected.get(),
				averageWaitMs(), maxWaitMs.get());
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

}
