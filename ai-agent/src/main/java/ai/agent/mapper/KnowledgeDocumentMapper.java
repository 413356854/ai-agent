package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code kb_document} 表的 SQL（知识库文档）。
 *
 * <h2>状态迁移是一列一次更新，不是一条全量写</h2>
 * 与 {@link KnowledgeBaseMapper} 的分法同一条判据：索引线程只推状态，
 * 界面只改名字与启停，两者各有各的触发时机 —— 合成一条的话，
 * 索引线程收尾那一次会把用户刚改的名字按旧值写回去。
 *
 * <h2>{@code chunk_count} / {@code token_count} / {@code indexed_at} 只在成功那一次写</h2>
 * 三个字段一起写进 {@link #markProcessed}：它们描述的是**同一次索引的结果**。
 * 分开写会出现"块数更新了但时间没更新"这种自相矛盾的行，而它看起来完全正常。
 * ⚠️ {@link #markFailed} **不动这三列**：失败之后它们仍然描述上一次成功的结果，
 * 这正是需要的语义（"上次切出 42 块，这次挂了"）。
 */
@Mapper
public interface KnowledgeDocumentMapper {

	/** 落一行"已上传"（状态与启停都用列默认值，不来自调用方）。 */
	int insert(@Param("kbId") Long kbId, @Param("name") String name, @Param("format") String format,
			@Param("sizeBytes") long sizeBytes, @Param("path") String path);

	/** 刚插入那一行的自增主键（理由见 {@link KnowledgeBaseMapper}）。 */
	Long lastInsertId();

	/**
	 * 回写落盘路径。
	 *
	 * <p>插入时还不知道它：落盘名里含自增 id（见 {@code KbFileStorage}），
	 * 而那正是为了让磁盘上不留用户给的文件名。两次写入都在上传这一个事务里。
	 */
	int updateSourcePath(@Param("id") Long id, @Param("path") String path);

	MapperRows.KbDocumentRow findById(@Param("id") Long id);

	/** 某个知识库的文档列表（最近上传在前）。 */
	List<MapperRows.KbDocumentRow> listByKb(@Param("kbId") Long kbId);

	/** 一批 id 的文档（检索侧按 doc_id 过滤"停用的文档"时用，一次查完而不是逐个查）。 */
	List<MapperRows.KbDocumentRow> findByIds(@Param("ids") List<Long> ids);

	/** 每个状态各几份（界面上的分档计数；一次 GROUP BY，不是每种状态查一次）。 */
	List<MapperRows.KbDocumentCountRow> countByStatus(@Param("kbId") Long kbId);

	/** 用户看到的那份文档数 = 这个库下全部文档行数。 */
	Long countByKb(@Param("kbId") Long kbId);

	/** 参与检索的文档数（`enabled=1`），检索响应里的"分母"之一。 */
	Long countEnabledByKb(@Param("kbId") Long kbId);

	/**
	 * 启动时捞"上次没跑完的"。
	 *
	 * <p>进程内队列重启即丢，不扫的话这些行会永远停在 {@code UPLOADED}/{@code PROCESSING}，
	 * 而"停在那里"与"正在跑"在界面上长得一样（都在转圈）。
	 */
	List<MapperRows.KbDocumentRow> listUnfinished();

	/** 开始处理。 */
	int markProcessing(@Param("id") Long id);

	/**
	 * 处理成功。
	 *
	 * <p>{@code error} 置回 null：一份上次失败、这次成功的文档，若留着上次的原因，
	 * 界面上会同时显示"已索引"和一条错误 —— 那是最难查的一类不一致。
	 */
	int markProcessed(@Param("id") Long id, @Param("chunkCount") int chunkCount,
			@Param("tokenCount") int tokenCount);

	/** 处理失败：只写状态与原因，不动上一次成功的块数与时间（见类注释）。 */
	int markFailed(@Param("id") Long id, @Param("error") String error);

	/**
	 * 块数减一（人为删掉一片切片时）。
	 *
	 * <p>取值下限是 0：块数是"上次索引切出几块"的记录，删到 0 之后不再往下走 ——
	 * 负数在界面上无法解释，而它出现的唯一原因就是这里没兜住。
	 */
	int decrementChunkCount(@Param("id") Long id);

	/** 重新索引前把状态推回"已上传"。 */
	int markUploaded(@Param("id") Long id);

	int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

	/** 改展示名（只改这一列，不动落盘路径）。 */
	int updateName(@Param("id") Long id, @Param("name") String name);

	/**
	 * 删一行。
	 *
	 * <p>⚠️ 调用方要先删 Milvus 里的向量、再删 {@code kb_chunk}、最后删这一行：
	 * 反过来会先丢掉"这个文档有哪些切片"的线索，剩下的向量就再也找不到主人了。
	 */
	int delete(@Param("id") Long id);

	/** 某个知识库下的全部文档 id（删库时清理向量用）。 */
	List<Long> idsOfKb(@Param("kbId") Long kbId);

}
