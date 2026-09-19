package ai.agent.service.tool.workspace;

import ai.agent.entity.model.tool.ToolHints;
import ai.agent.entity.tool.RunPermission;
import ai.agent.service.tool.AgentTool;

/**
 * <b>「这一次给多大权限」与「这把工具本身危不危险」怎么叠加</b> —— 唯一的判据在这里。
 *
 * <h2>两条输入，一个结论</h2>
 * <table border="1">
 *   <caption>谁说了什么</caption>
 *   <tr><th>输入</th><th>回答的问题</th><th>谁给的</th></tr>
 *   <tr><td>{@link ToolHints}</td><td>这把工具<b>本身</b>危不危险（只读？重跑会多出一条记录？）</td>
 *       <td>工具作者，写在代码里</td></tr>
 *   <tr><td>{@link RunPermission}</td><td><b>这一次运行</b>被授予了多大权限</td>
 *       <td>调用方，发起任务时选</td></tr>
 * </table>
 *
 * <h2>本类只有一条规则</h2>
 * <pre>
 *   读操作  → 直接执行（任何档位下都不变）
 *   写操作  → 完全权限：直接执行；其余两档：需要人工确认
 * </pre>
 * 注意它<b>永不放宽</b>工具自己的要求：{@code FULL_ACCESS} 只决定"要不要产生审核请求"，
 * 它不改变"这把工具是不是只读"。所以这次改动在方向上只有两个可能 ——
 * 让本来免审批的写操作<b>多</b>要一次确认（仅可查看 / 工作区内修改），
 * 或者让本来要确认的写操作<b>少</b>一次确认（完全权限）。
 * 一条"叠加规则"如果也能放宽工具自身的风险声明，那就等于给出了第二条判据，
 * 而两份判据迟早漂移（这个项目里已经有三处栽在这上面）。
 *
 * <h2>为什么读操作不看档位</h2>
 * 三档里没有一档叫"禁止查看"：{@code READ_ONLY} 是"<b>仅</b>可查看"，
 * 那个"仅"限的是改。把只读工具也拦下来要人点头，换来的不是安全而是
 * "模型连查一次文档都要等人"，于是人开始无条件点通过 —— 审批退化成仪式。
 *
 * <h2>这与 {@code AgentApprovalService} 里那条判据是同一件事的两半</h2>
 * 那边的 {@code hints.requiresApproval()} 保留原样（它对所有调用方都成立，
 * 包括外部 MCP 客户端那条没有"运行"的链路），本类补的是
 * "这一次运行的档位允许它不弹确认吗"。先判哪个不影响结果 ——
 * 两个判据都只会说"需要确认"，合起来仍然是"需要确认"。
 */
public final class AgentPermissionPolicy {

	private AgentPermissionPolicy() {
	}

	/**
	 * 这次工具调用属于哪一类。
	 *
	 * <p>判据取自 {@link ToolHints#readOnlyHint()} —— <b>不另起一份名单</b>。
	 * 另起一份（"哪些工具算写"）的失效方式是静默的：新加一把写工具时没人会记得去登记，
	 * 于是它在"仅可查看"档位下被当成读操作直接执行。
	 */
	public enum Operation {

		/** 不改任何东西（三把检索工具都是这一类）。 */
		READ,

		/** 会改东西：建一条记录、改一行数据、将来可能还有写文件。 */
		WRITE;

		public boolean isWrite() {
			return this == WRITE;
		}

	}

	/**
	 * 这一次运行该不该跳过人工确认。
	 *
	 * <p>它是本类对外的唯一入口。<b>它只回答"能不能跳过"</b>，
	 * 不回答"要不要拒绝" —— 本项目里权限不足的后果是去问人，
	 * 而不是直接失败（需求原话：超出权限选项则触发审核请求）。
	 */
	public static boolean coversWithoutApproval(RunPermission permission, Operation operation) {
		if (!operation.isWrite()) {
			return true;
		}
		return permission != null && permission.allowsWriteWithoutApproval();
	}

	/**
	 * 这次调用属于哪一类。
	 *
	 * <p>取 {@code description().hints()}：这些工具的描述是<b>静态常量</b>
	 * （构造期拼好的 record，不随调用变化），所以在门禁里读它与在注册期读它是同一个值。
	 */
	public static Operation operationOf(AgentTool<?> tool) {
		ToolHints hints = tool.description().hints();
		return hints != null && hints.readOnlyHint() ? Operation.READ : Operation.WRITE;
	}

	/** 一行说明（日志与诊断用）：这次运行的工作区与档位各是什么。 */
	public static String describe(RunPermission permission) {
		if (permission == null) {
			return "(没有权限上下文：按工具自己的声明判定)";
		}
		return permission.describe();
	}

}
