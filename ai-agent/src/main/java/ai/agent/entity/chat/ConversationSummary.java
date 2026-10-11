package ai.agent.entity.chat;

/**
 * 一条会话当前生效的<b>压缩摘要</b>（对话历史滚动摘要的产物）。
 *
 * <h2>为什么它有一个"覆盖了几轮"的字段</h2>
 * 摘要本身只是一段文本，而"这段文本替代了哪一段对话"必须能算出来 —— 否则
 * 拼接 prompt 时会出现两种都很难查的错：
 * <ul>
 *   <li>摘要与它覆盖的原文<b>同时</b>进了 prompt：同一件事说两遍，白花 token，
 *       而且模型会看到自己"重复强调"了某件事；</li>
 *   <li>摘要覆盖的那一段<b>又原样进去了一遍</b>的相反情况：摘要说的内容在上下文里
 *       找不到出处，模型会把它当成一个孤立断言。</li>
 * </ul>
 *
 * <p>定位用的是"<b>用户发言的轮数</b>"而不是下标或消息 id：见
 * {@code SummarizingChatMemory} 的类注释 —— 会话被重建、消息被摘掉之后，
 * 下标与 id 都会漂，而"这份摘要覆盖了前 N 轮用户发言"这个事实不变。
 *
 * @param conversationId  会话 id（与 {@code ai.chat.memory} / {@code agent_run.conversation_id} 同一个键）
 * @param coveredTurns    这份摘要覆盖了几轮<b>用户发言</b>（user 消息的条数）
 * @param summary         摘要正文
 * @param turnsCompacted  一共压缩过几次（增量合并的合并次数）
 * @param provider        写这份摘要的模型名；{@code null} = 没取到（不编一个模型名出来）
 */
public record ConversationSummary(String conversationId, int coveredTurns, String summary, int turnsCompacted,
		String provider) {

	public ConversationSummary {
		if (conversationId == null || conversationId.isBlank()) {
			throw new IllegalArgumentException("会话摘要的 conversationId 不能为空");
		}
		if (coveredTurns <= 0) {
			// 覆盖 0 轮的"摘要"没有意义：它什么都没替代。放过去只会让拼接逻辑多一个空分支
			throw new IllegalArgumentException("会话摘要至少要覆盖一轮用户发言（收到 " + coveredTurns + "）");
		}
		if (summary == null || summary.isBlank()) {
			throw new IllegalArgumentException("会话摘要正文不能为空 —— 一份空摘要会把已覆盖的对话永久丢掉");
		}
	}

	/**
	 * 这次压缩之后的下一份摘要（增量合并的结果）。
	 *
	 * <p>覆盖轮数与压缩次数都是<b>累加</b>的：新摘要不只是这一段对话的摘要，
	 * 它是"旧摘要 + 这一段"的摘要 —— 这一点写错的话，
	 * 定位会退回到"只覆盖最后一段"，于是更早的历史在 prompt 里凭空消失。
	 */
	public ConversationSummary mergedInto(int additionalTurns, String newSummary, String newProvider) {
		return new ConversationSummary(conversationId, coveredTurns + additionalTurns, newSummary,
				turnsCompacted + 1, newProvider == null || newProvider.isBlank() ? provider : newProvider);
	}
}
