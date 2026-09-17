package ai.agent.service.tool;

import ai.agent.service.tool.ToolInvocation;

import java.util.regex.Pattern;

/**
 * <b>"这次调用已经被人批准过"</b> —— 从审批记录里重建出来、交给循环的一张通行证。
 *
 * <h2>为什么需要它，而不是让闸门自己记住"批过了"</h2>
 * 批准发生在<b>上一次 HTTP 请求</b>里，而执行发生在<b>恢复之后的新请求</b>里，
 * 两者中间隔着一个检查点、甚至可能隔着一个进程的重启。
 * 所以"已批准"必须是<b>从库里读回来、显式传进去</b>的事实，
 * 而不是某个对象里的一格内存 —— 内存里的那一格会随重启消失，
 * 于是人批过的写操作会再问一次（或更糟：如果实现改成"重启后当没批过就直接执行"，
 * 那就成了绕过审批）。
 *
 * <h2>为什么按"工具名 + 参数"匹配，而不是按 tool_call_id</h2>
 * 恢复之后模型会<b>重新提出</b>那个调用，而协议层会给它一个<b>新的</b> {@code tool_call_id}
 * （那个 id 由上游生成，不由我们决定）。按 id 匹配必然匹配不上。
 * "同一个工具 + 逐字节相同的参数"才是"人批的那一次"的真实身份 ——
 * 这个观察与 {@code DuplicateActionDetector} 用的是同一个事实：
 * 模型重复一个调用时，给出的是从自己上文里复制出来的同一份 JSON。
 *
 * <p>参数不同就必须<b>重新审批</b>。这不是苛刻，是审批制度的底线：
 * 人批准的是"创建一条标题为 X 的工单"，模型回头提出标题为 Y，那是另一件事。
 * 放行它会让审批退化成一次性的橡皮图章。
 *
 * @param approvalId 审批记录 id（回填与落库都要带上它，审计才连得起来）
 * @param toolName 被批准的工具
 * @param normalizedArguments 被批准的那份参数（已去掉空白，见 {@link #normalize}）
 */
public record ApprovalGrant(String approvalId, String toolName, String normalizedArguments) {

	/** 空白差异不算差异（{@code {"a": 1}} 与 {@code {"a":1}} 是同一个调用）。 */
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	public static ApprovalGrant of(String approvalId, String toolName, String arguments) {
		return new ApprovalGrant(approvalId, toolName, normalize(arguments));
	}

	/** 这次调用是不是就是被批准的那一次。 */
	public boolean matches(ToolInvocation invocation) {
		return invocation != null && toolName.equals(invocation.name())
				&& normalizedArguments.equals(normalize(invocation.arguments()));
	}

	public static String normalize(String arguments) {
		return arguments == null ? "{}" : WHITESPACE.matcher(arguments).replaceAll("");
	}

}
