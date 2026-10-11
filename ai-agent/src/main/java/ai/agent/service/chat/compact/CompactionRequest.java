package ai.agent.service.chat.compact;

import ai.agent.entity.chat.ConversationSummary;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * 一次压缩请求的全部输入，<b>打成一份具名参数</b>。
 *
 * <h2>为什么不写成六个位置参数</h2>
 * 里面有<b>三个 int</b>（{@code baseTokens} / {@code allowance} / {@code minMessages}），
 * 而它们量纲不同、还都能取到合法的错值：把 {@code allowance} 与 {@code baseTokens} 传反了
 * 编译得过，现象是"预算越小压得越少"—— 排查方向会跑到估算器上去。
 * 打成 record 之后位置颠倒与漏传都是编译错误。
 *
 * @param conversationId 会话 id（摘要按它存）
 * @param history        存储里的完整历史（<b>不会被修改</b>）
 * @param baseTokens     除历史之外这次请求已占的估算 token（系统提示词 + 本轮提问 + 请求开销）
 * @param allowance      留给输入的 token 上限
 * @param minMessages    至少保留多少条历史（{@code ai.chat.budget.min-history-messages}）
 * @param provider       这次跑对话的模型名；{@code null} = 不知道（不编一个出来）
 */
public record CompactionRequest(String conversationId, List<Message> history, int baseTokens, int allowance,
		int minMessages, String provider) {

	public CompactionRequest {
		if (conversationId == null || conversationId.isBlank()) {
			throw new IllegalArgumentException("压缩请求的 conversationId 不能为空");
		}
		if (history == null) {
			throw new IllegalArgumentException("压缩请求的 history 不能为 null（没有历史时应传空列表）");
		}
		if (allowance <= 0) {
			// 允许为 0 的那条路在 TokenBudgetGuard 里已经被拒绝掉了（连提问都放不下）。
			// 到这里还是非正数说明配置矛盾，压也压不出结果
			throw new IllegalArgumentException("留给输入的预算必须是正数（收到 " + allowance + "）");
		}
		if (baseTokens < 0) {
			throw new IllegalArgumentException("已经占掉的 token 不能为负（收到 " + baseTokens + "）");
		}
		history = List.copyOf(history);
	}
}
