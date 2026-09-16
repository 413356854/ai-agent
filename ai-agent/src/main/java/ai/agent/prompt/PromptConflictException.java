package ai.agent.prompt;

/**
 * 切换生效版本时"前提已过期"（阶段四第 3 件）。
 *
 * <p>场景：两个人同时打开了这一页，都看到当前生效的是第 3 版。
 * 甲先切成第 4 版；乙随后点了"让第 5 版生效"。
 * 如果服务端不做检查，乙会成功，而<b>甲那次切换被无声地覆盖</b> ——
 * 页面上没有任何异常，事后也没人知道甲的决定去哪了。
 *
 * <p>所以这一类冲突要变成 <b>409 Conflict</b>，而且错误信息里必须带
 * <b>当前实际的版本号</b>：只说"冲突了"会让人反复点同一个按钮，
 * 说"现在是第 4 版（你看到的是第 3 版）"才能让人知道刷新一下再决定。
 *
 * <p>这与阶段一那条"分清 429/5xx 和 4xx"是同一种判断：
 * <b>重试有意义和没有意义的失败，必须给出不同的信号</b>。
 */
public class PromptConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** 切换者以为的版本号。 */
	private final Integer expectedVersion;

	/** 服务端实际的版本号（可能为空：库里还没有生效版本）。 */
	private final Integer actualVersion;

	public PromptConflictException(String message, Integer expectedVersion, Integer actualVersion) {
		super(message);
		this.expectedVersion = expectedVersion;
		this.actualVersion = actualVersion;
	}

	public Integer expectedVersion() {
		return expectedVersion;
	}

	public Integer actualVersion() {
		return actualVersion;
	}

}
