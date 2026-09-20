package ai.agent.entity.agent.permission;

/**
 * <b>一个账号</b> —— 名字、口令散列、状态。
 *
 * <h2>★ 口令字段是"已编码的散列"，而且构造期就守着这件事</h2>
 * {@code passwordHash} 只接受 {@link PasswordHash#looksHashed} 认得的串。
 * 这条校验放在 record 的紧凑构造器里，于是<b>所有</b>写入口（HTTP 建号、改口令、
 * 将来的导入脚本）都拦得住，而不是每个调用点各写一遍 ——
 * 那种写法漏一处就会有一条明文落库，且不报错。
 *
 * <p>它同时管住读取方向：从库里读出来的这一列如果对不上格式（有人手工插了明文），
 * 装配账号时会当场炸。宁可让那一行读不出来，也不要让一个明文口令在系统里流通 ——
 * 前者有明确报错，后者只在"库被拖走"时才有反馈。
 *
 * @param id 主键；未落库时为 {@code null}
 * @param name 登录名（库里有唯一约束；此处只做非空与长度校验）
 * @param passwordHash 口令散列（**不是明文**，见上面）
 * @param displayName 显示名，可为空
 * @param status 启用 / 停用
 */
public record Account(Long id, String name, String passwordHash, String displayName, AccountStatus status) {

	/** 与 DDL 的 {@code name VARCHAR(64)} 对齐。超限拒绝而不是截断：截断会让两个账号同名。 */
	public static final int MAX_NAME_CHARS = 64;

	/** 与 DDL 的 {@code display_name VARCHAR(128)} 对齐。 */
	public static final int MAX_DISPLAY_CHARS = 128;

	public Account {
		name = normalizeName(name);
		if (!PasswordHash.looksHashed(passwordHash)) {
			throw new IllegalArgumentException(("账号「%s」的口令列不是本项目的散列格式（期望 pbkdf2$…）。"
					+ "这一列只接受 PasswordHash.hash(...) 的产物 —— 明文与其它格式一律拒收，"
					+ "理由见 PasswordHash.looksHashed 的注释。").formatted(name));
		}
		displayName = displayName == null || displayName.isBlank() ? null : fit(displayName.strip(),
				MAX_DISPLAY_CHARS, "显示名");
		status = status == null ? AccountStatus.DISABLED : status;
	}

	/**
	 * 新建一个还没落库的账号。
	 *
	 * <p>它把 {@link PasswordHash#hash} 收进来，是为了让调用方<b>没有机会</b>把手写的字符串
	 * 塞进 {@code passwordHash} 参数 —— 想建号就得给明文，散列由这里算。
	 */
	public static Account of(String name, String plaintextPassword, String displayName, AccountStatus status) {
		return new Account(null, name, PasswordHash.hash(plaintextPassword), displayName, status);
	}

	/** 这个账号现在能不能用（停用的账号一律拒绝，判据只在这里）。 */
	public boolean usable() {
		return status.enabled();
	}

	/** 界面上显示的那一行名字（显示名为空时退回登录名）。 */
	public String label() {
		return displayName == null ? name : displayName + "（" + name + "）";
	}

	/** 一行说明（日志与界面用）。**不含口令散列**。 */
	public String describe() {
		return "#" + (id == null ? "(未落库)" : id) + " " + label() + " · " + status.label();
	}

	static String normalizeName(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("账号名不能为空");
		}
		return fit(raw.strip(), MAX_NAME_CHARS, "账号名");
	}

	private static String fit(String value, int max, String what) {
		if (value.length() > max) {
			throw new IllegalArgumentException(
					"%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
