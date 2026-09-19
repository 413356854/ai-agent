package ai.agent.entity.agent;

import java.util.Locale;

/**
 * <b>一次运行被授予的权限档位</b> —— 界面上是那个三选一的单选组。
 *
 * <h2>它与 {@code ToolHints} 的分工：一个是「这一次给多大权」，一个是「这把工具本身危不危险」</h2>
 * {@code ToolHints} 回答的是<b>工具固有</b>的风险（这把工具是不是只读、重跑一次会不会多出一条记录），
 * 它是工具作者写的、不随调用方变化。本枚举回答的是<b>这一次运行</b>被授予了多大权限，
 * 它是调用方在发起任务时选的。
 *
 * <p>两者叠加之后只有一条规则，写在 {@link ai.agent.service.tool.workspace.AgentPermissionPolicy} 里：
 * <b>档位只决定写操作要不要人点头，读操作在任何档位下都直接执行</b>。
 *
 * <h2>为什么读操作不看档位</h2>
 * 因为三档的名字里没有一档叫"禁止查看"：{@link #READ_ONLY} 是"仅可查看"，
 * 而"仅"限的是<b>改</b>。把 {@code read_section} 这种只读工具也拦下来要人批准，
 * 得到的是"模型连查一次文档都要等人"—— 那不是更安全，而是让人开始无条件点通过，
 * 于是审批从一道闸门退化成一个仪式（同一条取舍见 {@code ToolHints.readOnly()} 的注释）。
 *
 * <h2>为什么"只在 Agent 循环这条链路上生效"</h2>
 * 档位是<b>一次运行</b>的属性，而外部 MCP 客户端那条链路没有"运行"这个对象
 * （见 {@code ToolCallOrigin}）。所以那里的调用仍按工具自己的声明判定 ——
 * 拿一个不存在的运行的档位去放宽写操作，等于给一条没有审计主体的链路开后门。
 *
 * <h2>为什么"完全权限"是直接执行而不是"放行但记账"</h2>
 * 这是需求原话（「完全权限则直接执行不需要审核请求」），也是本项目里
 * <b>唯一一处刻意放宽人工确认</b>的地方。它成立的前提是"授权发生在运行开始之前、
 * 由人显式选择"，所以它必须能被看见：档位会落库（{@code agent_run.permission_level}）、
 * 并在本次结果、运行列表、运行详情、五层 trace 四处显示出来 ——
 * 一个"这次为什么没弹审批"的问题，事后只能靠这几个地方回答。
 *
 * @param workspaceWrite 允许在工作区内改东西（不改的话是"仅可查看"）
 * @param fullAccess 不受本档位限制（{@code true} 时工具自己的审批要求也被跳过）
 */
public enum AgentPermissionLevel {

	/** 仅可查看：只读工具直接执行，任何写操作都要人点头。 */
	READ_ONLY("仅可查看", false, false),

	/** 工作区内修改：仍然是写操作都要人点头 —— 因为本项目的工具没有路径参数，判不出"这次写在工作区内还是外"。 */
	WORKSPACE_WRITE("工作区内修改", true, false),

	/** 完全权限：写操作直接执行，不产生审核请求。 */
	FULL_ACCESS("完全权限", true, true);

	private final String label;

	private final boolean workspaceWrite;

	private final boolean fullAccess;

	AgentPermissionLevel(String label, boolean workspaceWrite, boolean fullAccess) {
		this.label = label;
		this.workspaceWrite = workspaceWrite;
		this.fullAccess = fullAccess;
	}

	/** 界面上显示的那三个字（与 {@code AgentPanel} 里的单选组逐字一致）。 */
	public String label() {
		return label;
	}

	public boolean workspaceWrite() {
		return workspaceWrite;
	}

	/** 这一档是不是"写操作直接执行、不产生审核请求"。 */
	public boolean fullAccess() {
		return fullAccess;
	}

	/**
	 * 一次<b>写</b>操作在这一档下能不能不经过人工确认直接执行。
	 *
	 * <p>读操作不走这里 —— 那个判断在 {@code AgentPermissionPolicy} 里，只有一处。
	 */
	public boolean allowsWriteWithoutApproval() {
		return fullAccess;
	}

	/**
	 * 宽松解析（用于库里的历史值、以及手写配置）。
	 *
	 * <p><b>认不出来一律降成 {@link #READ_ONLY}</b>，而且原因不是"随便挑一个"：
	 * 库里的 {@code permission_level} 只有两种来源 —— 正常写入的枚举名，
	 * 或者人工改库 / 将来版本新增的取值。前者不会认不出来；后者如果是<b>比 READ_ONLY 更宽</b>
	 * 的一档，那么"认不出就当完全权限"会让一次写操作被静默放行。
	 * 两个方向的代价不对称，所以取窄的那一边。
	 *
	 * <p>空值（老数据、没传档位的调用方）同样落 {@link #READ_ONLY}，
	 * 与"没写明就等于没有额外授权"是同一条规矩。
	 */
	public static AgentPermissionLevel parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return READ_ONLY;
		}
		String normalized = raw.strip();
		for (AgentPermissionLevel level : values()) {
			if (level.name().equalsIgnoreCase(normalized)) {
				return level;
			}
		}
		// 也认界面上的中文标签：自检与手工排查时会在配置/报错里看到那三个字
		for (AgentPermissionLevel level : values()) {
			if (level.label.equals(normalized)) {
				return level;
			}
		}
		return READ_ONLY;
	}

	/** 只给日志与报错用的一句话（形如 {@code FULL_ACCESS（完全权限）}）。 */
	public String describe() {
		return name() + "（" + label + "）";
	}

	/** 库里的取值一律写枚举名（小写别名都不写，避免两套写法并存）。 */
	public String storedValue() {
		return name().toUpperCase(Locale.ROOT);
	}

}
