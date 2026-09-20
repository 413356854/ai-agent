package ai.agent.entity.agent.permission;

import java.util.Locale;

/**
 * 一个角色被授予了多大的工具范围。
 *
 * <h2>为什么需要这个"二选一"，而不是"工具清单为空就代表没有工具"</h2>
 * 因为"这个角色能调全部工具"这句话<b>必须能写下来</b>。用"清单为空 = 全部"去表达它，
 * 会让"清单为空"同时意味着两件相反的事 —— 而更早失效的是安全的那一边：
 * 新建一个角色、还没来得及配工具时，它已经是"全部工具"了。
 *
 * <p>所以 {@link #LISTED} 是**默认值**，而且它的含义是"清单里有什么才能用什么"；
 * {@link #ALL} 必须被显式选中。两个方向里，代价不对称的是"多给了权限"，
 * 默认值取窄的那一边（与本项目 {@code AgentPermissionLevel.parse} 的取舍同源）。
 */
public enum PermissionAccess {

	/** 只允许清单里的工具（清单为空 = 一把都不能用）。 */
	LISTED("清单白名单"),

	/** 全部已注册工具。给管理员角色用，新增工具时不必回来补登记。 */
	ALL("全部工具");

	private final String label;

	PermissionAccess(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/**
	 * 宽松解析（库里的人工改动、手写配置、请求体里的大小写）。
	 *
	 * <p><b>认不出来一律落 {@link #LISTED}</b>：认不出就当"全部工具"
	 * 会让一次越权被静默放行，而 {@code LISTED} 顶多让某个角色少用几把工具 ——
	 * 后者会被人当场发现（模型说工具不存在），前者不会。
	 */
	public static PermissionAccess parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return LISTED;
		}
		String normalized = raw.strip();
		for (PermissionAccess access : values()) {
			if (access.name().equalsIgnoreCase(normalized) || access.label.equals(normalized)) {
				return access;
			}
		}
		return LISTED;
	}

	/** 库里的取值一律写枚举名。 */
	public String storedValue() {
		return name().toUpperCase(Locale.ROOT);
	}

}
