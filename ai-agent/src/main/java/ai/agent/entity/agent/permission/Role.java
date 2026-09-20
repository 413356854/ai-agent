package ai.agent.entity.agent.permission;

/**
 * <b>一个角色</b> —— 名字、说明、以及它被授予的工具范围。
 *
 * <h2>为什么 {@code access} 挂在角色上，而不是"工具清单非空即白名单"</h2>
 * 见 {@link PermissionAccess}：那种写法让"清单为空"同时意味着"没有工具"与"全部工具"。
 *
 * @param id 主键；未落库时为 {@code null}
 * @param name 角色名称（库里有唯一约束）
 * @param description 这个角色是干什么的
 * @param access {@link PermissionAccess#LISTED}（默认）或 {@link PermissionAccess#ALL}
 */
public record Role(Long id, String name, String description, PermissionAccess access) {

	/** 与 DDL 的 {@code name VARCHAR(64)} 对齐。 */
	public static final int MAX_NAME_CHARS = 64;

	/** 与 DDL 的 {@code description VARCHAR(512)} 对齐。 */
	public static final int MAX_DESCRIPTION_CHARS = 512;

	public Role {
		name = normalizeName(name);
		description = description == null || description.isBlank() ? null
				: fit(description.strip(), MAX_DESCRIPTION_CHARS, "角色说明");
		access = access == null ? PermissionAccess.LISTED : access;
	}

	/** 新建一个还没落库的角色。 */
	public static Role of(String name, String description, PermissionAccess access) {
		return new Role(null, name, description, access);
	}

	/** 这个角色是不是"全部工具"（判据只在这里）。 */
	public boolean allowsEveryTool() {
		return access == PermissionAccess.ALL;
	}

	/** 一行说明（日志与界面用）。 */
	public String describe() {
		return "#" + (id == null ? "(未落库)" : id) + " " + name + " · " + access.label();
	}

	static String normalizeName(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("角色名不能为空");
		}
		return fit(raw.strip(), MAX_NAME_CHARS, "角色名");
	}

	private static String fit(String value, int max, String what) {
		if (value.length() > max) {
			throw new IllegalArgumentException(
					"%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
