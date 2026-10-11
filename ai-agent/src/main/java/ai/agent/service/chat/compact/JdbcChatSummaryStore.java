package ai.agent.service.chat.compact;

import ai.agent.entity.chat.ConversationSummary;
import ai.agent.mapper.ChatSummaryMapper;
import ai.agent.mapper.MapperRows;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link ChatSummaryStore} 的库实现 —— 与 {@code ai.agent.store.*} 那一个库同一份 DataSource、同一处建表。
 *
 * <h2>什么时候用它</h2>
 * 配 {@code ai.chat.summary.store=jdbc} 时启用（默认是内存实现）。
 *
 * <p>它与内存实现的差别只有一条，但那一条决定了它值不值得开：<b>进程重启之后，
 * "上一次压缩把哪些内容压掉了"还查得到。</b>内存实现下重启即失效，
 * 表现是"同一段对话在新进程里被又压了一遍"（多花一次压缩的钱），
 * 或者更糟 —— 摘要没了、而那段对话也不再进 prompt。
 *
 * <p>⚠️ 与 {@code ai.chat.memory} 那把记忆是两回事：那把默认仍是内存实现，
 * 所以这个开关开着、对话原文本身仍是重启即失。要两者都持久，
 * 得把 {@code ChatClientConfig#chatMemoryRepository()} 也换成 JDBC 那份
 * （那边留着注释与代码）—— 只开这一边不会出错，但会得到一个
 * "摘要的覆盖轮数指向一段已经不存在的对话"的状态，而那是可以自愈的
 * （见 {@code ChatCompactionService} 的定位规则），不是必须一起换。
 */
@Component
@ConditionalOnProperty(name = "ai.chat.summary.store", havingValue = "jdbc")
public class JdbcChatSummaryStore implements ChatSummaryStore {

	private final ChatSummaryMapper mapper;

	public JdbcChatSummaryStore(ChatSummaryMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public Optional<ConversationSummary> find(String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			return Optional.empty();
		}
		MapperRows.ChatSummaryRow row = mapper.find(conversationId);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public void save(ConversationSummary summary) {
		if (summary == null) {
			throw new IllegalArgumentException("要写入的会话摘要不能为 null");
		}
		mapper.upsert(summary.conversationId(), summary.coveredTurns(), summary.summary(),
				summary.turnsCompacted(), summary.provider());
	}

	@Override
	public void delete(String conversationId) {
		if (conversationId != null && !conversationId.isBlank()) {
			mapper.delete(conversationId);
		}
	}
}
