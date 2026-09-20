package ai.agent.entity.agent.permission;

import ai.agent.service.tool.ToolRegistry;

/**
 * <b>一条"角色 → 工具"的授权</b>。
 *
 * <h2>工具名不在这里校验存在性</h2>
 * 名字必须对得上 {@link ToolRegistry} 里已注册的那些，否则这把工具永远调不到 ——
 * 但那条校验<b>不在这里</b>，它在写入路径上（{@code DefaultPermissionService} 拿注册表逐个对，
 * 认不出的名字配一句"可用的是 a、b、c"当场报错）。
 *
 * <p>理由是这个类型有两种来源：人配的（要严格查）与库里读出来的（<b>读的时候不必查</b>）。
 * 库里那一行可能是"工具被删掉之前配的"，那时报错的正确位置是"这次运行用不了它"，
 * 而不是"这一行读不出来" —— 后者会让整个角色的权限页打不开。
 * 所以把它做成 {@code of(...)}（写入路径，校验在服务层）与 {@code fromStore(...)} 的差别，
 * 而不是在类型里塞一个需要注册表的构造器。
 *
 * @param roleId 角色 id
 * @param toolName 与 {@code AgentTool.name()} 逐字相同的名字
 * @param note 为什么给这个角色开这把工具
 */
public record RoleTool(Long roleId, String toolName, String note) {

	/** 与 DDL 的 {@code tool_name VARCHAR(128)} 对齐。 */
	public static final int MAX_NAME_CHARS = 128;

	/** 与 DDL 的 {@code note VARCHAR(512)} 对齐。 */
	public static final int MAX_NOTE_CHARS = 512;

	public RoleTool {
		if (roleId == null) {
			throw new IllegalArgumentException("角色 id 不能为空");
		}
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("工具名不能为空");
		}
		toolName = fit(toolName.strip(), MAX_NAME_CHARS, "工具名");
		note = note == null || note.isBlank() ? null : fit(note.strip(), MAX_NOTE_CHARS, "说明");
	}

	/** 写入路径的构造（存在性校验由服务层做，理由见类注释）。 */
	public static RoleTool of(Long roleId, String toolName, String note) {
		return new RoleTool(roleId, toolName, note);
	}

	private static String fit(String value, int max, String what) {
		if (value.length() > max) {
			throw new IllegalArgumentException("%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
