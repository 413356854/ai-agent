package ai.agent.service.agent.memory;

import ai.agent.service.chat.compact.ChatCompactionService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * <b>把对话记忆分成两层：存储那一层是全量，交给模型那一层是压过的。</b>
 *
 * <h2>为什么需要这一层</h2>
 * 对话历史原来在 {@code ChatMemory.get()} 里被原样交给模型，于是"历史太长"只能靠删 ——
 * 而删掉的消息在下游没有任何留底（{@code InMemoryChatMemoryRepository} 是堆内 Map，
 * {@code saveAll} 是整段替换），"用户当时到底说了什么"就此变成一个不可回答的问题。
 *
 * <p>这一层把两件事拆开：
 * <ul>
 *   <li><b>存储</b>（{@code findByConversationId}）拿到的仍是全部消息 ——
 *       压缩只影响拼进 prompt 的那一份，<b>不回写存储</b>，所以原文查得到；</li>
 *   <li><b>交给模型</b>（{@code get}）拿到的是"摘要 + 未被覆盖的那些轮"。
 *       advisor 在 {@code before()} 阶段调的就是它。</li>
 * </ul>
 *
 * <h2>为什么 {@code get()} 里不能做模型调用</h2>
 * 它在 advisor 的 {@code before()} 阶段被<b>同步</b>调用：那里花一次模型调用等于
 * 每个请求都多等一次往返，而且流式链路上没有地方放这个失败。所以
 * {@code get()} 只读已经生效的摘要拼装；<b>生成摘要</b>发生在
 * {@code TokenBudgetGuard.prepare()}（发请求之前那一次），那里本来就在做预算判断。
 *
 * <h2>为什么定位靠"轮数"而不是下标或消息 id</h2>
 * 存储里的消息会被别处改动（重试前摘掉孤儿用户消息就是一个），下标与 id 都会漂。
 * 而"这份摘要覆盖了前 N 轮用户发言"这个事实不随那些改动变化 ——
 * 摘要是按轮生成、也按轮推进的。计数规则只有一处：
 * {@code ChatCompactionService.indexAfterCoveredTurns}。
 *
 * <h2>它不改变写入语义</h2>
 * {@code add()} 原样转给内层（{@code MessageWindowChatMemory}），所以条数窗口、
 * 系统消息去重、"窗口不切在用户消息中间"那几条既有规则全部不变。
 */
public class SummarizingChatMemory implements ChatMemory {

	private final ChatMemory archive;

	private final ChatCompactionService compaction;

	public SummarizingChatMemory(ChatMemory archive, ChatCompactionService compaction) {
		this.archive = archive;
		this.compaction = compaction;
	}

	/**
	 * 交给模型的那一份：摘要 + 未被覆盖的对话。
	 *
	 * <p>没有摘要（从没压过）时就是全部消息 —— 与改动之前的行为逐字一致，
	 * 所以"没触发压缩的会话"不受这一层影响。
	 */
	@Override
	public List<Message> get(String conversationId) {
		List<Message> full = archive.get(conversationId);
		return compaction.view(conversationId, full).messages();
	}

	@Override
	public void add(String conversationId, List<Message> messages) {
		archive.add(conversationId, messages);
	}

	/**
	 * 清空一条会话。
	 *
	 * <p>必须连摘要一起清：只清消息会让摘要留在库里指着一段已经不存在的对话，
	 * 而那种状态是静默的（下一次 {@code get()} 里那段摘要凭空出现，
	 * 模型会把它当成"用户之前说过的话"）。
	 */
	@Override
	public void clear(String conversationId) {
		archive.clear(conversationId);
		compaction.forget(conversationId);
	}
}
