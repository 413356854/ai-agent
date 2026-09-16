package ai.agent.prompt;

import java.time.Instant;

/**
 * 一份被版本化管理的提示词快照（阶段四第 3 件）。
 *
 * <h2>为什么里面的东西都是"取值"而不是"引用"</h2>
 * {@code content} 直接带在记录里，而不是"存个 id、要用时再查一次"。
 * 理由不是性能，而是<b>一致性</b>：版本管理的全部意义是"哪一版被用了"这件事可被追问，
 * 而一次运行用的提示词必须是<b>一份完整的、当时的那份文本</b>。
 * 如果外面拿到 id 之后再查一次，中间有人激活了新版本，就会出现
 * "报告说用的是第 3 版、实际发出去的是第 4 版"—— 而这种错<b>不报错</b>，
 * 只表现为"改了提示词之后效果没变（或者变了但说不清是哪一版导致的）"。
 *
 * @param kind    哪一份提示词
 * @param version 版本号，<b>每份提示词各自从 1 开始</b>（不是全局递增）
 * @param content 正文
 * @param hash    正文的 SHA-256 前 12 位十六进制（见 {@link #hashOf}）
 * @param charCount 正文长度（字符数）。单独存一列而不是每次现算：见下
 * @param tokenEstimate 估算 token 数
 * @param note    这一版为什么这么改
 * @param author  谁改的
 * @param forkedFrom 复制自哪一版；首次手工新建为 {@code null}
 * @param createdAt 落库时间
 */
public record PromptVersion(
		PromptKind kind,
		int version,
		String content,
		String hash,
		int charCount,
		int tokenEstimate,
		String note,
		String author,
		Integer forkedFrom,
		Instant createdAt) {

	/**
	 * 正文指纹（SHA-256 前 12 位）。
	 *
	 * <h2>为什么长度只要 12 位十六进制</h2>
	 * 它不是密码学用途，用途是<b>肉眼比对</b>与<b>断言相等</b>：
	 * "页面上显示的这一版"与"数据库里那一版"是不是同一份内容。
	 * 48 bit 的空间里，同一个项目几十个版本的碰撞概率可以忽略；
	 * 而短到能在界面上一行放下，才是它真正被用起来的前提 ——
	 * 一个 64 位的串没人会去比。
	 *
	 * <h2>为什么必须有一个指纹</h2>
	 * 「激活了第 5 版」与「发出去的确实是第 5 版的内容」是两件事，
	 * 而第二件才是这一件的验收口径。有了指纹，第二件才能被<b>断言</b>：
	 * {@code PromptHotReloadTest} 的做法是从出站请求体里把系统消息取出来、
	 * 算它的指纹、与库里那条 active 的指纹比对。
	 * 没有指纹的话，"热更新生效了"就只能靠"文本里包含我加的那句话"这种模糊判据。
	 */
	public static String hashOf(String content) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest
					.digest((content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(12);
			for (int i = 0; i < 6; i++) {
				sb.append(String.format("%02x", bytes[i]));
			}
			return sb.toString();
		}
		catch (java.security.NoSuchAlgorithmException e) {
			// SHA-256 是 JDK 必须提供的算法，走到这里说明 JRE 是坏的 —— 抛出去比返回一个假指纹好
			throw new IllegalStateException("JRE 不提供 SHA-256", e);
		}
	}

	/**
	 * token 数相对另一版的变化（正数表示这一版占得更多）。
	 *
	 * <p>换提示词最常见的副作用就是<b>预算变了</b>，而它在别处完全看不出来：
	 * 只表现为"历史比以前更早被裁掉"，那时没人会想到是提示词改的。
	 */
	public int tokenDeltaFrom(PromptVersion other) {
		return other == null ? tokenEstimate : tokenEstimate - other.tokenEstimate();
	}

}
