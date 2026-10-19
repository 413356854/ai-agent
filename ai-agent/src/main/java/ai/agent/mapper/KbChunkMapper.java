package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code kb_chunk} 表的 SQL（知识库切片）。
 *
 * <h2>逐条插入，成批由调用方开会话</h2>
 * {@link #insertChunk} 是一条一条插的，整篇文档的切片由服务层放进
 * {@code ExecutorType.BATCH} 会话里写（与 {@code JdbcAgentRunStore#writeHitsInBatch} 同一种做法）——
 * 本项目没有 {@code <foreach>} 批量插入的先例，而一条一条插要靠自增主键回填拿到 id，
 * 那条路在本项目的驱动组合下不通（见 {@link AgentTaskMapper} 的类注释）。
 * 所以 {@code uid} 由调用方生成一并带进来，见 {@code db/knowledge-schema.sql} 的列注释。
 *
 * <h2>分页：本项目第一个 {@code OFFSET} 查询</h2>
 * 其余 mapper 一律只有 {@code LIMIT #{limit}}，理由写在各自的注释里（那些列表"要一眼看全"）。
 * 切片不是：一份大文档能切出几千块，而切片编辑页是**翻着改**的 ——
 * 只给前 N 条等于"后面的块永远看不到、改不了"。所以 {@link #listByDocument} 收 offset。
 */
@Mapper
public interface KbChunkMapper {

	/** 写一条切片（{@code uid} 由调用方生成，理由见类注释）。 */
	int insertChunk(@Param("uid") String uid, @Param("kbId") Long kbId, @Param("documentId") Long documentId,
			@Param("seq") int seq, @Param("text") String text, @Param("headingPath") String headingPath,
			@Param("tokenCount") int tokenCount, @Param("charCount") int charCount);

	/** 一份文档的切片，按序号分页。 */
	List<MapperRows.KbChunkRow> listByDocument(@Param("documentId") Long documentId, @Param("offset") int offset,
			@Param("limit") int limit);

	/** 一份文档的切片总数（分页的"共 N 条"，与上面的查询在同一个 where 上）。 */
	Long countByDocument(@Param("documentId") Long documentId);

	/** 一个知识库的切片总数（检索响应里的 indexSize）。 */
	Long countByKb(@Param("kbId") Long kbId);

	/** 一个知识库下参与检索的切片数（{@code enabled=1}）。 */
	Long countEnabledByKb(@Param("kbId") Long kbId);

	/** 按 uid 取（Milvus 召回回来的是 uid，回表取正文用它）。 */
	List<MapperRows.KbChunkRow> findByUids(@Param("uids") List<String> uids);

	MapperRows.KbChunkRow findByUid(@Param("uid") String uid);

	/**
	 * 改正文。
	 *
	 * <p>字数与 token 数是正文的派生量，跟正文同一次写入 —— 分两次写会出现
	 * "正文改了、字数还是旧的"，而它在界面上只是一个偏小的数字，看不出是错的。
	 */
	int updateText(@Param("uid") String uid, @Param("text") String text, @Param("tokenCount") int tokenCount,
			@Param("charCount") int charCount);

	/** 只改启停那一格（Milvus 侧不存这一位，检索时按这里过滤）。 */
	int updateEnabled(@Param("uid") String uid, @Param("enabled") boolean enabled);

	/** 删一条（Milvus 侧由调用方先删）。 */
	int deleteByUid(@Param("uid") String uid);

	/** 删一份文档的全部切片（重新索引、删文档时用）。 */
	int deleteByDocument(@Param("documentId") Long documentId);

	/** 删一个知识库的全部切片（删库时用）。 */
	int deleteByKb(@Param("kbId") Long kbId);

}
