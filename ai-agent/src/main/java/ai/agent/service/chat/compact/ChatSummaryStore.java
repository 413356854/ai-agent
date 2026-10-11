package ai.agent.service.chat.compact;

import ai.agent.entity.chat.ConversationSummary;

import java.util.Optional;

/**
 * 会话压缩摘要的读写口。
 *
 * <h2>为什么是一个接口，而不是直接依赖某个实现</h2>
 * 与 {@code AgentTaskMapper} / {@code AgentRunStore} 同一条理由：
 * 摘要是<b>跨进程生命周期才有意义</b>的东西。内存实现下进程一退它就没了，
 * 于是"上一次压缩把哪些内容压掉了"在新进程里无从回答 —— 而那正是
 * "用户说它记得的事它不记得了"这类问题唯一的线索。
 *
 * <p>所以这里刻意保留一个接口 + 两份实现（内存 / 库），默认用内存那份
 * （与 {@code ChatClientConfig#chatMemoryRepository()} 当前的存储层级一致，
 * 见那个方法的注释），需要跨重启时换 {@link JdbcChatSummaryStore}。
 *
 * <h2>为什么没有"删一份摘要"</h2>
 * 摘要只增不减：它代表"这些对话已经被压过了"。删掉它不会让原文回来
 * （原文在 {@code chat_message} 或模型那侧已经不在上下文里），
 * 只会让 prompt 里出现一段覆盖范围对不上的拼接。要重来一次就清会话。
 */
public interface ChatSummaryStore {

	/** 一条会话当前生效的摘要；从没压缩过时返回空。 */
	Optional<ConversationSummary> find(String conversationId);

	/**
	 * 覆盖写入（一行一个会话，不是追加）。
	 *
	 * <p>写成覆盖而不是追加：摘要是增量合并的产物，模型那一侧只需要一份。
	 * 保留历次摘要会让"现在生效的是哪一份"变成需要额外判据的问题。
	 */
	void save(ConversationSummary summary);

	/** 清掉一条会话的摘要（会话被清空时用；原文归档不在此列）。 */
	void delete(String conversationId);
}
