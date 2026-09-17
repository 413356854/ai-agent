package ai.agent.service.agent.limit;

import ai.agent.config.AgentConcurrencyProperties;
import ai.agent.service.tool.ToolCallStage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>每把工具各自一份</b>闸门与熔断器。
 *
 * <h2>为什么必须按工具隔离，而不是全局共用一个池</h2>
 * 全局共享会让"一把慢工具"占满所有名额，把其它健康工具一起饿死 ——
 * 那是把一个<b>局部故障放大成全局故障</b>。这个坑在真实系统里非常常见：
 * 一个查报表的接口变慢，整个 Agent 的所有工具都调不动了，
 * 而日志上看起来是"系统整体变慢"。
 *
 * <p>按工具隔离之后，"报表工具坏了"只影响报表工具，
 * 文档检索照常工作 —— 而模型收到的回填也会明确指向那把工具，
 * 于是它可以换工具继续把任务做完。
 *
 * <h2>为什么闸门与熔断器放在一起</h2>
 * 它们<b>一起被使用</b>，而且顺序是固定的：先问熔断（"还要不要发"），再抢闸门（"能不能发"）。
 * 顺序反了会在熔断打开期间仍然去抢许可，于是"被熔断的调用"也会排队等待 ——
 * 白等一场，而且会把闸门的排队统计污染成"这里很挤"。
 *
 * <h2>关于 Map 会不会无限增长</h2>
 * 工具是<b>注册期确定</b>的（{@code ToolRegistry} 在启动时自检并冻结），
 * 所以这个 Map 的键集合在上限上是固定的（等于工具数量），不存在泄漏。
 * 如果将来支持"动态注册工具"，这里要加清理 —— 注释写在这里而不是等它出问题。
 */
@Component
public class PerToolGate {

	private final Map<String, ConcurrencyGate> gates = new ConcurrentHashMap<>();

	private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

	private final AgentConcurrencyProperties properties;

	public PerToolGate(AgentConcurrencyProperties properties) {
		this.properties = properties;
	}

	/**
	 * 申请执行一次工具调用的许可。
	 *
	 * @return {@code null} 表示放行；否则是<b>拒绝结果</b>（带 stage 与给模型的说明）
	 */
	public Rejection tryAcquire(String toolName) {
		// ---- 先问熔断：已经确定坏了就别再排队 ----
		String circuitReason = breaker(toolName).tryAcquire();
		if (circuitReason != null) {
			return new Rejection(ToolCallStage.CIRCUIT_OPEN, circuitReason);
		}

		// ---- 再抢闸门 ----
		String gateReason = gate(toolName).tryAcquire();
		if (gateReason != null) {
			// 【必须撤销熔断那边的放行】熔断可能在这次放行中把 HALF_OPEN 的那**唯一一个**
			// 试探名额给了我们（OPEN → HALF_OPEN 的转换就发生在上一步）。
			// 如果不还回去，那个名额会因为"调用根本没发生"而永远减不掉，
			// 于是半开状态再也放不出试探请求 —— 熔断器永远恢复不了，
			// 而现象是"那把工具一直不可用"，排查方向会跑到"下游是不是真的坏了"上去
			breaker(toolName).undoAcquire();
			return new Rejection(ToolCallStage.REQUEST_REJECTED, gateReason);
		}
		return null;
	}

	/**
	 * 归还许可并记录结果。
	 *
	 * <h2>哪些 stage 不参与熔断记账</h2>
	 * 第一版的实现里，闸门放在"参数解析之后"，理由是"参数错是毫秒级的、为它排队没意义，
	 * 而且会污染排队统计"。那个理由听起来对，但<b>它带来一个更糟的后果</b>：
	 * 解析失败的调用会因为"压根没抢过闸门"而不进熔断窗口 —— 这本身没问题，
	 * 但改顺序把闸门提前之后（见 {@code ToolCallExecutor} 的注释：闸门保护的是"整个执行尝试"
	 * 而不是"执行那几毫秒"），解析失败的调用就会走到这里，
	 * 于是它们会以"成功"的身份进窗口，把失败率<b>稀释掉</b>。
	 *
	 * <p>判据本身<b>不在这里</b>：它由 {@link ToolCallStage#countsAsCircuitSample()} 回答
	 * （谁的经历、谁自己说算不算样本）。这里只做转发 ——
	 * 因为当初把判据写成这里的一个 {@code if} 时，新增 stage 的默认走向是
	 * "进窗口并记成成功"，也就是<b>忘了改它不会报错</b>。
	 * 移到枚举上之后，每一个取值都必须显式回答这个问题。
	 *
	 * <p>被拒绝的两类（{@code REQUEST_REJECTED} / {@code CIRCUIT_OPEN}）走不到这里
	 * —— 它们是在 {@link #tryAcquire} 里被拒的，压根没拿到许可，所以不会调 release。
	 */
	public void release(String toolName, ToolCallStage stage) {
		gate(toolName).release();
		if (!stage.countsAsCircuitSample()) {
			// 工具压根没被调用过（参数错 / 正等人批 / 人没批）：不算工具的成败，不进熔断窗口
			return;
		}
		// 用 stage 自己的判断，而不是"这次没成功" —— 这是本轮最关键的一个决定，
		// 理由见 CircuitBreaker 类注释：模型参数错、限流被拒都不算工具故障
		breaker(toolName).record(stage.countsAsToolFailure());
	}

	/**
	 * 归还许可，<b>但不记录结果</b>。
	 *
	 * <p>给一种具体情况用：闸门抢到了、但调用在真正执行前被别的原因提前结束
	 * （例如被上层取消）。这种情况既不是成功也不是工具故障，
	 * 记任何一边都会让统计失真。
	 */
	public void releaseWithoutRecording(String toolName) {
		gate(toolName).release();
	}

	private ConcurrencyGate gate(String toolName) {
		return gates.computeIfAbsent(toolName,
				name -> new ConcurrencyGate(name, properties.maxConcurrentToolRequests(), properties.acquireTimeoutMs()));
	}

	private CircuitBreaker breaker(String toolName) {
		return breakers.computeIfAbsent(toolName,
				name -> new CircuitBreaker(name, properties.circuit()));
	}

	/** 一次拒绝：stage + 给模型看的说明。 */
	public record Rejection(ToolCallStage stage, String reason) {
	}

	/** 诊断用：所有闸门的快照。 */
	public List<ConcurrencyGate.Snapshot> gateSnapshots() {
		return gates.values().stream().map(ConcurrencyGate::snapshot).sorted(
				java.util.Comparator.comparing(ConcurrencyGate.Snapshot::name)).toList();
	}

	/** 诊断用：所有熔断器的快照。 */
	public List<CircuitBreaker.Snapshot> breakerSnapshots() {
		return breakers.values().stream().map(CircuitBreaker::snapshot).sorted(
				java.util.Comparator.comparing(CircuitBreaker.Snapshot::name)).toList();
	}

	/** 供测试：确定性地把某把工具的熔断器设成打开。 */
	public void forceOpenForTesting(String toolName) {
		breaker(toolName).forceStateForTesting(CircuitBreaker.State.OPEN);
	}

	/** 供测试：当前某把工具的状态。 */
	public CircuitBreaker.State stateOf(String toolName) {
		return breaker(toolName).state();
	}

	/** 配置（诊断端点要显示"这套上限是多少"）。 */
	public AgentConcurrencyProperties properties() {
		return properties;
	}

}
