package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 人工确认（HITL）的配置。
 *
 * <h2>为什么 {@code enabled} 的默认值是 {@code true}</h2>
 * 这是本项目里少数几个"默认打开保护"的地方，理由与
 * {@code ai.agent.concurrency.circuit.enabled=true} 一致：
 * <b>HITL 保护的是"不可逆的副作用"，而它失效时不会有任何报错</b>。
 * 默认关闭的话，一个部署会把高危写操作直接放行，且日志上一切正常 ——
 * 那正是踩坑 #67 的形状（"我配了保护"与"保护真的装着"是两件事）。
 *
 * <p>反过来，默认打开会不会有代价？会，而且很具体：
 * 一个没接审批界面的部署，写工具会一直等到超时。
 * 但那个失败是<b>响亮</b>的（有超时、有拒绝原因、有回填、界面上有记录），
 * 而不是安静的。两害相权，宁可响亮。
 *
 * <h2>{@code timeout} 为什么必须有，且为什么不能是 0</h2>
 * 等人是<b>没有上界</b>的操作：没有人点，请求就永远挂着。
 * 所以这里必须是有限的 —— 构造期就拒绝 0 与负数，
 * 与 {@code ai.agent.concurrency.acquire-timeout-ms} 同一条规则
 * （那里写的理由在这里同样成立：虚拟线程让"无限等待"的代价变得极低，
 * 于是它会把故障模式从"报错"变成"静默挂起"）。
 *
 * <p>120 秒这个取值的依据是：它比任何一次模型/工具调用都长得多（那些是秒级），
 * 又短于"人离开工位"的量级（那是分钟级）——
 * 于是"人还在看页面"时它等得到，"人已经走了"时它明确失败而不是永远挂着。
 * 生产上它应该与你的值班响应时间对齐，这也是它做成配置而不是常量的原因。
 *
 * @param enabled 总开关。关掉时写操作<b>全部放行</b>，且日志/诊断端点会明确说出来
 * @param timeout 等人确认的最长时长；超时后这次调用被拒绝（{@code APPROVAL_TIMEOUT}），
 *                而不是"默认通过"—— 见 {@code AgentApprovalService} 里那条关于默认值的注释
 * @param defaultDecider 没填决定人时记谁。默认 {@code "unknown"} 而非空：
 *                一条"谁批的"是空的记录，在审计时等于没有
 */
@ConfigurationProperties(prefix = "ai.agent.approval")
public record AgentApprovalProperties(boolean enabled, Duration timeout, String defaultDecider) {

	public AgentApprovalProperties {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			// 允许 null 会让调用方在 await 那里拿到一个 null 超时 —— 而 NPE 的位置离这里很远
			throw new IllegalArgumentException(
					"ai.agent.approval.timeout 必须是正的时长（例如 120s）。"
							+ "设为 0/负数等于「永远等下去」，而虚拟线程让无限等待的代价极低，"
							+ "故障模式会从「报错」变成「静默挂起」");
		}
		if (defaultDecider == null || defaultDecider.isBlank()) {
			defaultDecider = "unknown";
		}
	}

	public static AgentApprovalProperties defaults() {
		return new AgentApprovalProperties(true, Duration.ofSeconds(120), "unknown");
	}

}
