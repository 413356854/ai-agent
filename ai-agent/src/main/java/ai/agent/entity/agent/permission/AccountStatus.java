package ai.agent.entity.agent.permission;

import java.util.Locale;

/**
 * 账号状态。
 *
 * <h2>为什么"停用"而不是"删除"</h2>
 * 删掉一个账号，它名下的授权行会跟着走（外键 CASCADE），
 * 于是"这个人当时是什么权限"这个复盘问题就永远没有答案了。
 * 停用保留历史、并且立刻生效 —— 代价只是那一行还在列表里。
 *
 * <h2>为什么两档，没有"锁定 / 待激活"</h2>
 * 多出来的档位必须有<b>一处代码</b>读它才有意义，否则那一档就是"看起来更细，
 * 实际与停用完全一样"。本项目现在没有登录链路，所以只留"能不能用"这一档。
 */
public enum AccountStatus {

	ENABLED("启用"),

	DISABLED("停用");

	private final String label;

	AccountStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public boolean enabled() {
		return this == ENABLED;
	}

	/**
	 * 宽松解析。认不出来的取值落 {@link #DISABLED}。
	 *
	 * <p>理由是代价不对称：认不出就当启用，等于人工改库写错一个字就把一个已停用的账号放回来，
	 * 而那种错误不报错、只在"这个人怎么还能用"时才被发现。
	 */
	public static AccountStatus parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return DISABLED;
		}
		String normalized = raw.strip();
		for (AccountStatus status : values()) {
			if (status.name().equalsIgnoreCase(normalized) || status.label.equals(normalized)) {
				return status;
			}
		}
		return DISABLED;
	}

	/** 库里的取值一律写枚举名。 */
	public String storedValue() {
		return name().toUpperCase(Locale.ROOT);
	}

}
