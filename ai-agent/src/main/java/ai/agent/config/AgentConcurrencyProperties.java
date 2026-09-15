package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 侧的并发保护配置（阶段三第 3 件）。
 *
 * <h2>为什么是"三种保护"而不是"一个限流"</h2>
 * 这三种拦的是<b>三种不同的东西</b>，各有一个另外两种拦不住的情况。
 * 混着做成一个"限流"，结果通常是某一种被实现、另外两种被假装实现：
 * <table border="1">
 *   <caption>三种保护各自回答什么</caption>
 *   <tr><th>机制</th><th>它回答的问题</th><th>只有另外两种时会发生什么</th></tr>
 *   <tr>
 *     <td><b>并发闸门</b>（{@code maxConcurrent*Requests}）</td>
 *     <td>"同时最多几个请求在下游"</td>
 *     <td>没有它，虚拟线程会安静地开出成千上万个在途请求 ——
 *         而下游的连接池/配额是有上限的，超出的部分会在<b>下游</b>排队或直接被拒，
 *         我们在这一侧完全看不见，只能看到"上游变慢了"</td>
 *   </tr>
 *   <tr>
 *     <td><b>排队超时</b>（{@code acquireTimeoutMs}）</td>
 *     <td>"等不到闸门就等多久"</td>
 *     <td>没有它，请求会<b>无限期排队</b>。表现是最糟的一种：
 *         用户界面一直转圈、没有任何错误、日志里也没有失败 ——
 *         因为确实没失败，只是在等。而虚拟线程让"等"变得极其便宜，所以队列能长到看不见头</td>
 *   </tr>
 *   <tr>
 *     <td><b>熔断</b>（{@code circuit.*}）</td>
 *     <td>"这把工具的下游是不是已经坏了"</td>
 *     <td>没有它，一个坏掉的下游会被每个请求都去撞一次：
 *         每次都要等超时（比如 30 秒）才失败。一个任务 6 轮就是 3 分钟纯等待，
 *         而那些等待<b>没有任何成功可能</b></td>
 *   </tr>
 * </table>
 *
 * <h2>为什么闸门用信号量而不是"每秒 N 个请求"的速率限制</h2>
 * 因为真正的约束是<b>下游能同时扛住多少在途请求</b>，而不是"每秒能收多少"。
 * 速率限制（如令牌桶）只数"发起"，不数"还没回来"——
 * 于是当下游变慢时，同样的 QPS 会让在途请求越堆越多，
 * 而那正是需要保护的时候。信号量直接把"在途"这个量按住。
 *
 * <p>顺带一个很划算的性质：信号量上的阻塞在虚拟线程里<b>几乎不占资源</b>
 * （这正是阶段一虚拟线程改造换来的能力）——
 * 所以"排队"在这个架构下是一个可接受的策略，而不是像平台线程时代那样等于浪费线程。
 *
 * <h2>为什么这些数字必须可配、且默认值要写清依据</h2>
 * 它们全部取决于<b>部署环境</b>：模型服务的配额、工具有没有连接池、机器多大。
 * 写死之后要么过松（保护等于没有），要么过紧（正常流量都跑不动）。
 * 默认值给的是"单机开发/小规模"的合理起点，依据见每个参数的注释。
 *
 * @param maxConcurrentModelRequests 同时打给模型的在途请求上限
 * @param maxConcurrentToolRequests 同时执行工具的请求上限（**每把工具各自一份**，理由见注释）
 * @param acquireTimeoutMs 等闸门的最长时间，超时即拒绝（**必须有值**，0 或负数表示不允许）
 * @param circuit 熔断相关参数
 */
@ConfigurationProperties(prefix = "ai.agent.concurrency")
public record AgentConcurrencyProperties(int maxConcurrentModelRequests, int maxConcurrentToolRequests,
		long acquireTimeoutMs, Circuit circuit) {

	/**
	 * 熔断参数。
	 *
	 * @param enabled 是否启用熔断（关掉它只用于对照实验：证明"开着确实少等了"）
	 * @param failureRateThreshold 失败率阈值（0~1）。达到它就打开
	 * @param minimumCalls 统计窗口内至少要有多少次调用才评估失败率。
	 *        <b>这个参数不能省</b>：只有 1 次调用失败时失败率是 100%，
	 *        按 100% 判会把一个刚启动的工具立刻熔断掉
	 * @param windowSize 滑动窗口大小（最近多少次调用参与统计）
	 * @param openDurationMs 打开后保持多久才允许试探（冷却期）
	 * @param halfOpenProbes 半开状态下允许几个试探请求。
	 *        通常 1 个就够：试探的目的是"是不是恢复了"，不需要并发去撞
	 */
	public record Circuit(boolean enabled, double failureRateThreshold, int minimumCalls, int windowSize,
			long openDurationMs, int halfOpenProbes) {

		public Circuit {
			if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
				throw new IllegalArgumentException("failure-rate-threshold 必须在 (0,1] 之间，实际 " + failureRateThreshold);
			}
			if (minimumCalls < 1) {
				throw new IllegalArgumentException("minimum-calls 必须 >= 1 —— 否则首次失败就会被判成 100% 失败率并立刻熔断");
			}
			if (windowSize < minimumCalls) {
				throw new IllegalArgumentException(
						"window-size(%d) 不能小于 minimum-calls(%d) —— 否则窗口里永远凑不够评估所需的最小样本数，熔断永不生效"
								.formatted(windowSize, minimumCalls));
			}
			if (openDurationMs <= 0) {
				throw new IllegalArgumentException("open-duration-ms 必须为正");
			}
			if (halfOpenProbes < 1) {
				throw new IllegalArgumentException("half-open-probes 必须 >= 1");
			}
		}
	}

	/**
	 * 默认值，以及每一条的依据。
	 *
	 * <ul>
	 *   <li>{@code maxConcurrentModelRequests = 8}：模型调用是<b>IO 等待型</b>（一个请求几秒），
	 *       而下游通常按"账号并发"计费/限流。8 是"本地开发够用、又不至于把免费额度打满"的起点。
	 *       生产要按你的配额调 —— 而<b>调它的依据应该是下游的配额，不是本机的 CPU</b>
	 *       （CPU 核数在虚拟线程架构下已经不再是这个量的约束了，这正是一个容易搞错的地方）。</li>
	 *   <li>{@code maxConcurrentToolRequests = 4}：<b>每把工具各自 4 个</b>。
	 *       按工具隔离而不是全局共用一个池，理由见 {@code AgentConcurrencyProperties} 的
	 *       {@code maxConcurrentToolRequests} 参数说明与 {@code PerToolGate}：
	 *       全局共享会让"一把慢工具"占满名额，把其它健康工具也一起饿死 ——
	 *       那是把一个局部故障放大成全局故障。</li>
	 *   <li>{@code acquireTimeoutMs = 5000}：等 5 秒。依据是量级：
	 *       模型调用本身是 1~4 秒，工具是毫秒级。等 5 秒意味着"前面的请求快结束了"，
	 *       而不是"排在一个看不到头的队尾"。<b>超过它就该拒绝并让模型稍后重试</b> ——
	 *       拒绝是廉价的（一轮工具调用），而无限等待会烧掉整个任务的时间预算。</li>
	 *   <li>{@code circuit.failureRateThreshold = 0.5}：一半失败就认为下游病了。
	 *       定得比"0.1"松，是因为工具的失败可能来自数据本身（某个查询就是会超时），
	 *       而我们要拦的是"大面积不行了"。</li>
	 *   <li>{@code circuit.minimumCalls = 5}：最少 5 次才评估。没有它，
	 *       <b>首次失败就是 100% 失败率</b>，一个健康工具会被立刻熔断。</li>
	 *   <li>{@code circuit.windowSize = 20}：最近 20 次。窗口太大的话，
	 *       下游早就恢复了，窗口里还留着旧失败；太小则对抖动过敏。</li>
	 *   <li>{@code circuit.openDurationMs = 10000}：冷却 10 秒。太短会"刚打开就去撞"，
	 *       太长会让已经恢复的下游白白闲置。</li>
	 *   <li>{@code circuit.halfOpenProbes = 1}：半开只放 1 个试探。
	 *       试探的目的是拿一个样本判断"好了没有"，并发去撞只会在没好时白挨几下。</li>
	 * </ul>
	 */
	public static AgentConcurrencyProperties defaults() {
		return new AgentConcurrencyProperties(8, 4, 5_000L,
				new Circuit(true, 0.5, 5, 20, 10_000L, 1));
	}

	public AgentConcurrencyProperties {
		if (maxConcurrentModelRequests < 1) {
			throw new IllegalArgumentException("max-concurrent-model-requests 必须 >= 1");
		}
		if (maxConcurrentToolRequests < 1) {
			throw new IllegalArgumentException("max-concurrent-tool-requests 必须 >= 1");
		}
		// 【为什么不允许 0 或负数表示"无限等"】
		// "无限等"在虚拟线程下太便宜了，便宜到会写满整块时间预算而没有任何失败。
		// 想表达"不要限制"应该去调大 maxConcurrent*，而不是把等待变成无限 ——
		// 这两件事看起来都能"让请求最终跑起来"，但后者会把故障模式变成"静默挂起"
		if (acquireTimeoutMs <= 0) {
			throw new IllegalArgumentException(
					"acquire-timeout-ms 必须为正。0/负数表示无限等待，而虚拟线程让无限等待的成本极低 —— "
							+ "那会把故障模式变成「静默挂起」：界面一直转圈、没有错误、日志里也没有失败。"
							+ "想放宽应该调大 max-concurrent-*，而不是取消超时。");
		}
		if (circuit == null) {
			throw new IllegalArgumentException("circuit 配置不能为空");
		}
	}

	/** 熔断是否启用（关掉它只用于对照实验）。 */
	public boolean circuitEnabled() {
		return circuit != null && circuit.enabled();
	}

}