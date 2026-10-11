package ai.agent.mapper;

import ai.agent.entity.chat.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code chat_conversation_summary} 表的 SQL —— 会话压缩摘要的读写口。
 *
 * <h2>为什么写是 {@code upsert} 而不是"先查再决定 insert 还是 update"</h2>
 * 同一个会话可能有两处同时压缩（两条流式请求打到同一实例上），
 * 而"先查再写"在那种情况下两条都能过检查，后写的会静默覆盖先写的 ——
 * 覆盖的后果是"覆盖轮数"比真实值小，于是摘要覆盖的那一段对话会**又原样进一次 prompt**。
 * 交给数据库的 {@code ON DUPLICATE KEY UPDATE} 只有一条能生效。
 *
 * <p>⚠️ {@code ON DUPLICATE KEY UPDATE} 是 <b>MySQL 方言</b>；H2 认它的 MySQL 兼容模式。
 * 本项目两种形态都用过（见 {@code db/agent-schema.sql} 头注），所以这里不换成
 * {@code MERGE INTO}（那是 H2 与部分数据库的方言，MySQL 不认）——
 * 换之前先确认换过去之后另一种库还认不认。
 */
@Mapper
public interface ChatSummaryMapper {

	/** 一条会话当前生效的摘要；没压缩过时返回 {@code null}（不是空对象）。 */
	MapperRows.ChatSummaryRow find(@Param("conversationId") String conversationId);

	/** 写入或覆盖（一行一个会话）。 */
	int upsert(@Param("conversationId") String conversationId, @Param("coveredTurns") int coveredTurns,
			@Param("summary") String summary, @Param("turnsCompacted") int turnsCompacted,
			@Param("provider") String provider);

	/** 清掉一条会话的摘要（会话被清空时用）。 */
	int delete(@Param("conversationId") String conversationId);
}
