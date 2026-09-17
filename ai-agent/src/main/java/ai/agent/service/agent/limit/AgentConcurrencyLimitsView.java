package ai.agent.service.agent.limit;

import ai.agent.config.AgentConcurrencyProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"四层保护"的当前状态组装成界面能直接渲染的形状（阶段三第 5 件）。
 *
 * <h2>为什么单独一个类，而不是在 controller 里拼</h2>
 * 两个理由：
 * <ol>
 *   <li><b>controller 只该做"读 + 组装"</b>（见 {@code AgentController} 的类注释）。
 *       而这里要读三个不同的来源（工具闸门、工具熔断、模型闸门、配置），
 *       组装逻辑有一定体量，混进 controller 会让那个类同时承担两种职责；</li>
 *   <li><b>它是"给界面看的视图"</b>，与"限流本身"是两件事。分开之后，
 *       将来界面要改展示口径（比如合并某些行、加派生指标）不会碰到限流代码。</li>
 * </ol>
 *
 * <h2>一个刻意的取舍：这里<b>不</b>做任何判断</h2>
 * 不计算"失败率算不算高"、不判断"闸门算不算挤"、不给"健康/不健康"的结论 ——
 * 这些判据已经在 {@code CircuitBreaker}（阈值）与配置（上限）里各有一份。
 * 界面侧再判一次，就是**第二份判据**，而两份判据必然漂移：
 * 漂移之后的现象是"页面说健康，但熔断其实已经打开了"，
 * 而排查方向会跑到页面上，而不是判据不一致上。
 *
 * <p>所以这里只把**原始数字与阈值一起**给出去，让页面能显示"0.6 / 阈值 0.5"——
 * 读者自己就能看出"超了"，而不需要页面替他说。
 */
@Component
public class AgentConcurrencyLimitsView {

	private final PerToolGate perToolGate;

	private final AgentConcurrencyProperties properties;

	/**
	 * 模型侧闸门。
	 *
	 * <p><b>它是一个 {@code ObjectProvider}，而且必须在"用到的时候"解析（不能提前收集）</b>——
	 * 这是真后端上实测出来的一个坑：
	 * 第一版在<b>构造期</b>就 {@code modelTurnProvider.stream().toList()} 把类型收下来了，
	 * 结果真后端上拿到的始终是**裸的 {@code OpenAiModelTurn}**，
	 * `/limits` 一直报 {@code modelGate.available=false} —— 也就是
	 * <b>并发闸门在真实路径上压根没生效</b>，而接口照样 200、字段照样有。
	 *
	 * <p>根因是初始化顺序：这个视图是个 {@code @Component}，它可能在
	 * {@code AgentConfig} 里那个 {@code ModelTurn} {@code @Bean} 完成之前就被构造了 ——
	 * 于是"构造期收集"看到的是那一刻的容器状态（还没有那个 bean），而它再也不会更新。
	 * 改成"每次需要时解析"之后，拿到的就是最终装配结果。
	 *
	 * <p><b>为什么值得单独记下来</b>：这个 bug 的失效方向很隐蔽 ——
	 * 它不报错、不抛异常，只是把一个"已启用"的能力显示成"未启用"。
	 * 而更糟的是反过来想：**如果当初那个布尔被当作"没事"忽略掉，
	 * 这一层限流就会一直没生效而没人知道**。抓住它的方式是"在真后端上跑一遍并核对返回值"，
	 * 而不是只看测试（测试里恰好先构造了 LimitedModelTurn，顺序不同，所以看不到这个问题）。
	 */
	private final org.springframework.beans.factory.ObjectProvider<ai.agent.service.agent.ModelTurn> modelTurnProvider;

	public AgentConcurrencyLimitsView(PerToolGate perToolGate, AgentConcurrencyProperties properties,
			org.springframework.beans.factory.ObjectProvider<ai.agent.service.agent.ModelTurn> modelTurnProvider) {
		this.perToolGate = perToolGate;
		this.properties = properties;
		this.modelTurnProvider = modelTurnProvider;
	}

	/** 每次调用都重新解析（理由见字段注释里的初始化顺序坑）。 */
	private java.util.Optional<LimitedModelTurn> resolveLimited() {
		// getIfAvailable 而不是 stream().toList()：前者按类型精确取，
		// 而且**每次调用都重新问一次容器** —— 这一点是关键，见下面 wiringChecked 的注释
		ai.agent.service.agent.ModelTurn turn = modelTurnProvider.getIfAvailable();
		org.slf4j.LoggerFactory.getLogger(AgentConcurrencyLimitsView.class)
				.info("解析 ModelTurn：{} -> {}", turn == null ? "null" : turn.getClass().getName(),
						turn instanceof LimitedModelTurn ? "闸门已启用" : "不是 LimitedModelTurn");
		return turn instanceof LimitedModelTurn limited ? java.util.Optional.of(limited) : java.util.Optional.empty();
	}

	/**
	 * 是否已经就装配情况告警过一次。
	 *
	 * <h2>为什么告警要延迟到"第一次真的有人问"</h2>
	 * 这一条是实测逼出来的。<b>第一版在 {@code @PostConstruct} 里解析并告警，结果真后端上
	 * 一直报"未启用"，而实际上装配是对的</b> —— 原因是初始化顺序：
	 *
	 * <ol>
	 *   <li>这个视图组件被创建（它只需要 {@code ObjectProvider}，所以很早就满足依赖）；</li>
	 *   <li>那一刻 {@code AgentConfig} 里的 {@code ModelTurn} {@code @Bean} <b>还没被定义</b>，
	 *       于是 {@code getIfAvailable()} 拿到的是<b>自动配置</b>直接提供的裸 {@code OpenAiModelTurn}
	 *       （Spring AI 的自动配置会自己提供一个 {@code ChatModel}，
	 *       而它是否提供一个 {@code ModelTurn} 取决于装配顺序）；</li>
	 *   <li>容器继续启动，{@code AgentConfig} 的 bean 定义注册进来，
	 *       {@code @ConditionalOnMissingBean} 此时才生效，最终容器里的是包装好的
	 *       {@code LimitedModelTurn} —— 但那个 {@code @PostConstruct} 早就跑完了，
	 *       它看到并缓存下来的结论是错的。</li>
	 * </ol>
	 *
	 * <p><b>修法是两件事一起</b>：① 解析必须"每次都重新问"（{@code getIfAvailable} 每次调用都走容器）；
	 * ② 告警推迟到第一次真的有人问的时候（那时启动已完成，答案是对的）。
	 *
	 * <p><b>这个坑的教训</b>：{@code ObjectProvider} 解决的是"依赖可能不存在"，
	 * <b>它不解决"我要的东西还没被定义"</b>。要在启动彻底完成后再判断装配，
	 * 正确的位置是 {@code ApplicationReadyEvent} 或者"第一次使用时"——而不是 {@code @PostConstruct}。
	 * 同类陷阱在框架里很常见（{@code @ConditionalOnMissingBean} 与自动配置的顺序敏感性）。
	 */
	private volatile boolean wiringChecked;

	/** 第一次有人问时检查一次装配（那时启动早已完成，答案是对的）。 */
	private void checkWiringOnce() {
		if (wiringChecked) {
			return;
		}
		wiringChecked = true;
		boolean enabled = resolveLimited().isPresent();
		if (enabled) {
			org.slf4j.LoggerFactory.getLogger(AgentConcurrencyLimitsView.class)
					.info("模型侧并发闸门已启用：上限 {} 个在途请求", properties.maxConcurrentModelRequests());
		}
		else {
			// 只 WARN 不抛：测试与自定义装配下确实可能没有这一层。
			// 但**必须说出来** —— 沉默会让"没装上"和"没有流量"长得一样
			org.slf4j.LoggerFactory.getLogger(AgentConcurrencyLimitsView.class)
					.warn("模型侧并发闸门「未启用」（当前 ModelTurn 不是 LimitedModelTurn）——"
							+ "如果你以为它在生效，请检查 AgentConfig 里的装配。");
		}
	}

	/** 组装快照。字段名与界面一一对应，改一处要一起改。 */
	public Map<String, Object> snapshot() {
		// 顺带在第一次被问时检查装配（理由见 checkWiringOnce 的注释）
		checkWiringOnce();

		Map<String, Object> body = new LinkedHashMap<>();

		// ---- 配置（含判据阈值，页面要显示"实际值 vs 阈值"） ----
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("maxConcurrentModelRequests", properties.maxConcurrentModelRequests());
		config.put("maxConcurrentToolRequests", properties.maxConcurrentToolRequests());
		config.put("acquireTimeoutMs", properties.acquireTimeoutMs());
		config.put("circuitEnabled", properties.circuitEnabled());
		config.put("failureRateThreshold", properties.circuit().failureRateThreshold());
		config.put("minimumCalls", properties.circuit().minimumCalls());
		config.put("windowSize", properties.circuit().windowSize());
		config.put("openDurationMs", properties.circuit().openDurationMs());
		body.put("config", config);

		// ---- 模型侧闸门 ----
		body.put("modelGate", modelGateSnapshot());

		// ---- 工具侧：每把工具一行，闸门与熔断并排 ----
		// 【为什么要并排】"闸门满"和"熔断打开"是两种不同的病：
		// 前者是"太挤了，等等就好"，后者是"下游坏了，别等了"。
		// 分开放的话，页面上会出现"闸门：正常"和"熔断：打开"两个互不相干的区域，
		// 而读者需要的是"这把工具现在到底能不能用"
		Map<String, ConcurrencyGate.Snapshot> gates = new LinkedHashMap<>();
		for (ConcurrencyGate.Snapshot snapshot : perToolGate.gateSnapshots()) {
			gates.put(snapshot.name(), snapshot);
		}
		Map<String, CircuitBreaker.Snapshot> breakers = new LinkedHashMap<>();
		for (CircuitBreaker.Snapshot snapshot : perToolGate.breakerSnapshots()) {
			breakers.put(snapshot.name(), snapshot);
		}

		List<Map<String, Object>> tools = new ArrayList<>();
		for (String name : union(gates.keySet(), breakers.keySet())) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("toolName", name);
			ConcurrencyGate.Snapshot gate = gates.get(name);
			row.put("maxConcurrent", gate == null ? properties.maxConcurrentToolRequests() : gate.maxConcurrent());
			row.put("inFlight", gate == null ? 0 : gate.inFlight());
			row.put("peakInFlight", gate == null ? 0 : gate.peakInFlight());
			row.put("acquired", gate == null ? 0L : gate.acquired());
			row.put("rejected", gate == null ? 0L : gate.rejected());
			row.put("averageWaitMs", gate == null ? 0.0 : round1(gate.averageWaitMs()));
			row.put("maxWaitMs", gate == null ? 0L : gate.maxWaitMs());
			CircuitBreaker.Snapshot breaker = breakers.get(name);
			row.put("circuitState", breaker == null ? "CLOSED" : breaker.state().name());
			row.put("windowSize", breaker == null ? 0 : breaker.windowSize());
			row.put("failureRate", breaker == null ? 0.0 : round1(breaker.failureRate()));
			row.put("circuitOpenCount", breaker == null ? 0L : breaker.openCount());
			row.put("shortCircuited", breaker == null ? 0L : breaker.shortCircuited());
			tools.add(row);
		}
		body.put("tools", tools);

		// ---- 汇总（页面顶部的几个数字） ----
		Map<String, Object> totals = new LinkedHashMap<>();
		long rejected = tools.stream().mapToLong(t -> ((Number) t.get("rejected")).longValue()).sum();
		long shortCircuited = tools.stream().mapToLong(t -> ((Number) t.get("shortCircuited")).longValue()).sum();
		totals.put("toolsTracked", tools.size());
		totals.put("totalRejected", rejected);
		totals.put("totalShortCircuited", shortCircuited);
		long openCircuits = tools.stream().filter(t -> !"CLOSED".equals(t.get("circuitState"))).count();
		totals.put("openCircuits", openCircuits);
		body.put("totals", totals);

		body.put("note", "闸门与熔断都是「全局共享」的：同一把工具被所有任务共用，"
				+ "所以这里的数字不是某一次运行的属性。"
				+ "「拒绝」是限流生效（稍后重试即可）；「熔断」是下游已判为不可用（别再用它）。");
		return body;
	}

	/** 模型侧闸门。测试替换掉真实适配器时拿不到，此时明确返回"未启用"而不是编一个空快照。 */
	private Map<String, Object> modelGateSnapshot() {
		var limited = resolveLimited();
		if (limited.isPresent()) {
			ConcurrencyGate.Snapshot snapshot = limited.get().gateSnapshot();
			Map<String, Object> gate = new LinkedHashMap<>();
			gate.put("available", true);
			gate.put("maxConcurrent", snapshot.maxConcurrent());
			gate.put("inFlight", snapshot.inFlight());
			gate.put("peakInFlight", snapshot.peakInFlight());
			gate.put("acquired", snapshot.acquired());
			gate.put("rejected", snapshot.rejected());
			gate.put("averageWaitMs", round1(snapshot.averageWaitMs()));
			gate.put("maxWaitMs", snapshot.maxWaitMs());
			return gate;
		}
		// 【为什么明确报"未启用"而不是给一堆 0】
		// 一堆 0 看起来像"闸门在工作但没流量"，而真相是"这一层没装上"。
		// 两者对排查的含义完全不同 —— 前者正常，后者是缺陷。
		//
		// 【而这条判断本身在真后端上救过一次场】见 resolveLimited 的注释：
		// 第一版因为初始化顺序问题，真后端上一直显示 available=false ——
		// 也就是"限流层没装上"。如果没有这个字段，那个缺陷会完全不可见
		Map<String, Object> gate = new LinkedHashMap<>();
		gate.put("available", false);
		gate.put("maxConcurrent", properties.maxConcurrentModelRequests());
		gate.put("note", "当前 ModelTurn 不是 LimitedModelTurn —— 模型侧并发闸门未启用"
				+ "（测试或自定义装配时会这样）。这不代表数字是 0，而是这一层不存在。");
		return gate;
	}

	private static List<String> union(java.util.Set<String> first, java.util.Set<String> second) {
		java.util.TreeSet<String> all = new java.util.TreeSet<>(first);
		all.addAll(second);
		return List.copyOf(all);
	}

	/** 保留一位小数：失败率显示成 0.6000000000000001 会让页面很难看，而两位又过于精细。 */
	private static double round1(double value) {
		return Math.round(value * 10.0) / 10.0;
	}

}
