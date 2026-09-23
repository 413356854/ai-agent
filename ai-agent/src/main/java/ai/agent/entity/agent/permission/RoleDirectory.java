package ai.agent.entity.agent.permission;

import ai.agent.entity.tool.RunPermission;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * <b>一条"角色 → 工作区目录前缀"的授权</b>，以及"某个目录算不算在前缀里"的唯一判据。
 *
 * <h2>★ 判定按路径分段做，不做字符串前缀比较</h2>
 * 字符串比较在这里有一个具体的坏法：前缀 {@code D:\work\demo} 会命中
 * {@code D:\work\demo-other} —— 因为它们的前 13 个字符确实相同。
 * 那是两个不同的目录，而字符串比较<b>不报错</b>，只表现为"授权范围比配的大"。
 * 所以判定是：两边都归一成绝对路径之后，逐段比较，且**允许完全相等**
 * （前缀就是那个目录本身时，那个目录是允许的）。
 *
 * <h2>"归一"只做词法归一，不解析符号链接</h2>
 * 这里用 {@link Path#toAbsolutePath()} + {@link Path#normalize()}：
 * {@code D:\work\demo\..\secret} 会被折成 {@code D:\work\secret}，于是它落不进
 * {@code D:\work\demo} 这个前缀 —— 这类"用 {@code ..} 走出去"的写法在这里拦住了。
 * <p>但 {@code D:\work\demo\link} 这种**符号链接**不解析（{@code toRealPath} 才解析）：
 * 判据是纯粹的字符串/路径计算，不查文件系统。已知的这一档差别写在
 * {@code AGENTS.md} 之外的项目文档里，因为它是一个真实的边界而不是一个 bug：
 * 授权前缀在语义上是"名字空间"这一段，符号链接指向哪里由文件系统决定。
 *
 * <h2>前缀为空、或不是合法路径时</h2>
 * 构造期就拒绝。一条"空前缀"的授权等于把根目录给了这个角色，
 * 而它在界面上看起来只是一行没填完的配置。
 *
 * @param roleId 角色 id
 * @param prefix 目录前缀（<b>用户输入的原始写法</b>，判定时才归一）
 * @param note 为什么给这个角色开这个目录
 */
public record RoleDirectory(Long roleId, String prefix, String note) {

	/** 与 DDL 的 {@code dir_prefix VARCHAR(192)} 对齐；长度依据见 permissions-schema.sql 的头注。 */
	public static final int MAX_PREFIX_CHARS = 192;

	/** 与 DDL 的 {@code note VARCHAR(512)} 对齐。 */
	public static final int MAX_NOTE_CHARS = 512;

	public RoleDirectory {
		if (roleId == null) {
			throw new IllegalArgumentException("角色 id 不能为空");
		}
		if (prefix == null || prefix.isBlank()) {
			throw new IllegalArgumentException("目录前缀不能为空（空前缀等于把这个角色的范围放到根目录）");
		}
		prefix = fit(prefix.strip(), MAX_PREFIX_CHARS, "目录前缀");
		// 当场解析一次：不合法的路径在这里就报出来，而不是等到判定时才炸
		// （那时调用点在看一次运行能不能跑，错误位置离填错的地方很远）
		try {
			Path.of(prefix);
		}
		catch (InvalidPathException e) {
			throw new IllegalArgumentException("目录前缀不是一个合法的路径：" + prefix);
		}
		note = note == null || note.isBlank() ? null : fit(note.strip(), MAX_NOTE_CHARS, "说明");
	}

	/** 写入路径的构造。 */
	public static RoleDirectory of(Long roleId, String prefix, String note) {
		return new RoleDirectory(roleId, prefix, note);
	}

	/**
	 * 这个工作区目录算不算落在本前缀里。
	 *
	 * <p><b>它是本类对外的核心方法</b>，{@link UserPermissions#coversDirectory(String)}
	 * 只是把它对全部前缀跑一遍。
	 *
	 * @param workspaceDir 一次运行的工作区目录（原始写法，可以是相对路径 ——
	 *            相对路径按 Java 进程的工作目录解析，与 {@link RunPermission} 同一条规矩）
	 * @return 工作区目录等于本前缀，或者是本前缀下面的子目录
	 */
	public boolean covers(String workspaceDir) {
		return coversPath(prefix, workspaceDir);
	}

	/**
	 * "目录算不算落在前缀里"的<b>唯一计算</b> —— {@link #covers(String)} 与
	 * {@link #coversPath(String, String)} 都走它。
	 *
	 * <p>抽出来是为了让"只有一份判据"这件事在类型上也成立：有些调用方手里只有两个字符串
	 * （技能记录上的 {@code folder} 与角色的目录前缀），它们不该为了比较而造一条假的授权对象 ——
	 * 造出来的那种对象会带一个编出来的 roleId，而下一处就会有人拿它去查库。
	 *
	 * @param prefix 目录前缀（用户输入的原始写法）
	 * @param workspaceDir 被检查的目录（原始写法，可以是相对路径）
	 */
	public static boolean coversPath(String prefix, String workspaceDir) {
		if (prefix == null || prefix.isBlank() || workspaceDir == null || workspaceDir.isBlank()) {
			return false;
		}
		Path target;
		try {
			target = Path.of(workspaceDir).toAbsolutePath().normalize();
		}
		catch (InvalidPathException e) {
			// 一个连路径都算不上的工作区目录不可能落在任何前缀里。
			// 不抛异常：判定本身要能对任意输入给出 true/false
			return false;
		}
		Path base;
		try {
			base = Path.of(prefix).toAbsolutePath().normalize();
		}
		catch (InvalidPathException e) {
			return false;
		}
		// 分段比较：段的个数与每一段都必须相同。`D:\work\demo` 有 3 段，
		// 而 `D:\work\demo-other` 的第 3 段是 "demo-other" —— 判否
		int baseCount = base.getNameCount();
		if (target.getNameCount() < baseCount) {
			return false;
		}
		if (!target.getRoot().equals(base.getRoot())) {
			return false;
		}
		for (int i = 0; i < baseCount; i++) {
			if (!target.getName(i).equals(base.getName(i))) {
				return false;
			}
		}
		return true;
	}

	private static String fit(String value, int max, String what) {
		if (value.length() > max) {
			throw new IllegalArgumentException("%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
