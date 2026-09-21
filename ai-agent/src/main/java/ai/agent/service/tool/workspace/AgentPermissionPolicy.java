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
 *   <tr><td>文件路径（写操作才有）</td><td>这次要改的东西在不在<b>工作区</b>里</td>
 *       <td>工具自己的参数，由 {@link FileAccess} 判定</td></tr>
 * </table>
 *
 * <h2>本类只有一条规则</h2>
 * <pre>
 *   读操作              → 直接执行（任何档位下都不变）
 *   写、且在工作区内    → 工作区内修改 / 完全权限：直接执行；仅可查看：由工具拒绝
 *   写、且在工作区之外  → 完全权限：直接执行；其余两档：需要人工确认（仅可查看还会被工具拒绝）
 * </pre>
 * 注意它<b>永不放宽</b>工具自己的要求：{@code FULL_ACCESS} 只决定"要不要产生审核请求"，
 * 它不改变"这把工具是不是只读"。所以这条叠加规则在方向上只有两个可能 ——
 * 让本来免审批的写操作<b>多</b>要一次确认，或者让本来要确认的写操作<b>少</b>一次确认。
 * 一条"叠加规则"如果也能放宽工具自身的风险声明，那就等于给出了第二条判据，
 * 而两份判据迟早漂移（这个项目里已经有三处栽在这上面）。
 *
 * <h2>为什么读操作不看档位</h2>
 * 三档里没有一档叫"禁止查看"：{@code READ_ONLY} 是"<b>仅</b>可查看"，
 * 那个"仅"限的是改。把只读工具也拦下来要人点头，换来的不是安全而是
 * "模型连查一次文档都要等人"，于是人开始无条件点通过 —— 审批退化成仪式。
 *
 * <h2>"仅可查看"档位下的写是<b>拒绝</b>，不是"转人工确认"</h2>
 * 那一句话针对的是<b>档位允许、只是没覆盖到这次位置</b>的情形（工作区内修改档位写工作区之外）——
 * 那种写应该去问人。"仅可查看"的字面意思则是"不许改"，把它转成人工确认等于给人一个
 * "点通过就绕过档位"的后门，那时档位就不再是档位了。
 *
 * <p>这一条拒绝落在<b>写工具自己</b>那里（文件类是 {@code FileToolSupport#requireWritable}，
 * SQL 类是 {@code SqlGuard}），而不是闸门里：闸门的职责是"要不要人点头"（见
 * {@link #coversWrite} 的注释，那里记着实测踩过的原因）。
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

		/** 不改任何东西（三把检索工具与三把读文件工具都是这一类）。 */
		READ,

		/** 会改东西，而且改的是<b>工作区内</b>的一个文件。 */
		WRITE_IN_WORKSPACE,

		/** 会改东西，而且改的是<b>工作区之外</b>的路径。 */
		WRITE_OUTSIDE_WORKSPACE;

		public boolean isWrite() {
			return this != READ;
		}

	}

	/**
	 * 这一次运行该不该跳过人工确认（<b>不涉及路径</b>的那一版）。
	 *
	 * <p>它只用于<b>没有路径参数</b>的写工具（建工单、执行 SQL）—— 那些工具的写入位置不由参数决定，
	 * 于是按"最宽的那种写"算：只有完全权限才能跳过确认。
	 * 对文件工具<b>必须</b>走 {@link #coversWrite}，理由见那个方法。
	 *
	 * <p>它只回答"能不能跳过"，不回答"要不要拒绝" ——
	 * 唯一的拒绝在文件工具自己那里（仅可查看档位下的写），见类注释。
	 */
	public static boolean coversWithoutApproval(RunPermission permission, Operation operation) {
		if (!operation.isWrite()) {
			return true;
		}
		if (operation == Operation.WRITE_IN_WORKSPACE) {
			// 「工作区内修改」这一档的语义就在这里：工作区内的改不需要人点头
			return permission != null && permission.level() != null && permission.level().workspaceWrite();
		}
		return permission != null && permission.allowsWriteWithoutApproval();
	}

	/**
	 * 一个<b>具体路径</b>上的写操作能不能跳过人工确认。
	 *
	 * <h2>为什么不能只用 {@code operationOf(tool)}</h2>
	 * 一把文件工具的类别取决于它这一次的参数：同一个 {@code write_file}，
	 * 写 {@code <工作区>/notes.md} 与写 {@code D:\secrets.txt} 是两件不同的事 ——
	 * 而这正是 {@code AgentPermissionLevel.WORKSPACE_WRITE} 那个字段的含义
	 * （"允许在这个目录里改东西"），在文件工具出现之前它判不出来，因为工具没有路径参数。
	 *
	 * <p>判据只有一句：<b>工作区内的写看 {@code workspaceWrite}，工作区外的写看 {@code fullAccess}</b>。
	 * 两边都不看工具自己的风险声明 —— 那一层由内层闸门负责，本方法回答的只是"档位覆盖了吗"。
	 *
	 * <h2>它<b>不</b>回答"允不允许做"</h2>
	 * 「仅可查看」档位下两样都是 false，于是本方法对它返回 false，闸门照旧把这次调用交给内层
	 * （去等人）。真正拒绝它的是写工具自己：文件类的是 {@code FileToolSupport#requireWritable}，
	 * SQL 类的是 {@code SqlGuard}。这么分是因为两个问题的答案不同 ——
	 * "不覆盖"意味着**去问人**（工作区内修改档位写工作区之外就是这一种），
	 * 而"不允许"意味着**直接拒绝**；把后者也塞进闸门，会让前者拿到一句"这一档不允许改任何东西"，
	 * 而档位其实是允许的（实测踩过这一处）。
	 *
	 * @param workspaceBound 这个路径算不算在工作区内；{@code false} 表示越界或没有工作区
	 */
	public static boolean coversWrite(RunPermission permission, boolean workspaceBound) {
		return coversWithoutApproval(permission,
				workspaceBound ? Operation.WRITE_IN_WORKSPACE : Operation.WRITE_OUTSIDE_WORKSPACE);
	}

	/**
	 * 这次调用属于哪一类（<b>只有"没有路径参数的工具"用得到它</b>）。
	 *
	 * <p>取 {@code description().hints()}：这些工具的描述是<b>静态常量</b>
	 * （构造期拼好的 record，不随调用变化），所以在门禁里读它与在注册期读它是同一个值。
	 *
	 * <p>写操作归到 {@link Operation#WRITE_OUTSIDE_WORKSPACE} 是刻意的保守取值：
	 * 这些工具的写入位置不由参数决定（建工单落在配置的目录里、SQL 落在整个库上），
	 * 也就是"没有证据说明它只在工作区内"，于是按最严的那一侧算 —— 只有完全权限才能跳过确认。
	 * 有路径参数的工具<b>必须</b>走 {@link #coversWrite}。
	 */
	public static Operation operationOf(AgentTool<?> tool) {
		ToolHints hints = tool.description().hints();
		return hints != null && hints.readOnlyHint() ? Operation.READ : Operation.WRITE_OUTSIDE_WORKSPACE;
	}

	/** 一行说明（日志与诊断用）：这次运行的工作区与档位各是什么。 */
	public static String describe(RunPermission permission) {
		if (permission == null) {
			return "(没有权限上下文：按工具自己的声明判定)";
		}
		return permission.describe();
	}

}
