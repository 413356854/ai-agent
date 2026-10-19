package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code knowledge_base} 表的 SQL（知识库）。
 *
 * <h2>★ 自增主键要显式取，而且必须在同一个事务里</h2>
 * 与 {@link AgentTaskScheduleMapper} / {@link AgentTaskMapper} 逐条相同：
 * MyBatis 的三条"自动回填"路在本项目的驱动组合下都不通，失效时**不抛异常、只留一个 null**。
 * 服务层紧接着调 {@link #lastInsertId()}，并且建库这件事整体在一个事务里
 * （{@code LAST_INSERT_ID()} 是连接级的，换一条连接就变成 0）。
 *
 * <h2>取 id 是为了拼 Milvus 集合名</h2>
 * 集合名 = {@code ai.rag.kb.collection-prefix} + id（见 {@code KbVectorStoreFactory}）。
 * 用 id 而不是用户填的名字：名字可以改、可以重名（虽然有唯一键）、可以带 Milvus 不接受的字符；
 * id 是稳定的、且天然满足集合名的字符集要求。
 *
 * <h2>更新分成三条，而不是一条全量写</h2>
 * 判据与 {@link AgentTaskScheduleMapper} 那条一致 —— "这几列会不会被分开改"：
 * 改名/说明（界面上的"编辑"）、改检索参数（"检索配置"）、启停（开关）是三个入口，
 * 合成一条的话，改名字那一次会把没提交的检索参数按旧值写回去。
 * ⚠️ embedding 的三列（provider / model / dim）与 collection_name **没有任何 update**：
 * 它们是建库时的快照，集合维度改不了（见 {@code db/knowledge-schema.sql} 的头注）。
 */
@Mapper
public interface KnowledgeBaseMapper {

	/** 建库。集合名由服务层按 id 生成，所以这里先插一行、再取 id、再回写集合名。 */
	int insert(@Param("name") String name, @Param("description") String description,
			@Param("embeddingProvider") String embeddingProvider, @Param("embeddingModel") String embeddingModel,
			@Param("embeddingDim") int embeddingDim, @Param("chunkStrategy") String chunkStrategy,
			@Param("topK") int topK, @Param("similarityThreshold") double similarityThreshold,
			@Param("rerankEnabled") boolean rerankEnabled);

	/** 刚插入那一行的自增主键。**必须在同一个事务里紧接着插入调用**。 */
	Long lastInsertId();

	/** 回写集合名（插入时 id 还不知道，而集合名要用 id）。 */
	int updateCollectionName(@Param("id") Long id, @Param("collectionName") String collectionName);

	/** 全部知识库。列表不分页：这一页按人建库的规模长，而"哪些库现在能检索"要一眼看全。 */
	List<MapperRows.KnowledgeBaseRow> list();

	/** 按 id 取一行；不存在时返回 null（调用方据此回 400）。 */
	MapperRows.KnowledgeBaseRow findById(@Param("id") Long id);

	/** 按名字取一行：新建/改名时查重（唯一键 uk_knowledge_base_name 是最后一道，这是先给一句人话）。 */
	MapperRows.KnowledgeBaseRow findByName(@Param("name") String name);

	/** 按 id 批量取（检索时要按库取 embedding 快照与集合名）。空列表由调用方拦住，SQL 不处理。 */
	List<MapperRows.KnowledgeBaseRow> findByIds(@Param("ids") List<Long> ids);

	/** 改名字与说明。 */
	int updateNameDescription(@Param("id") Long id, @Param("name") String name,
			@Param("description") String description);

	/** 改检索参数（召回条数 / 阈值 / 是否重排）。 */
	int updateSearch(@Param("id") Long id, @Param("topK") int topK,
			@Param("similarityThreshold") double similarityThreshold, @Param("rerankEnabled") boolean rerankEnabled);

	/** 只改启停那一格。 */
	int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

	/**
	 * 删掉一行。
	 *
	 * <p>返回受影响行数：调用方据此判断"这一行还在不在"，而不是先查一次再删
	 * （两次之间别人可能已经删掉了）。
	 * ⚠️ 调用方必须先删 Milvus 集合、再删文档与切片，最后才删这一行 ——
	 * 顺序反了的话，中途崩掉会留下一个没人知道集合名的孤儿集合。
	 */
	int delete(@Param("id") Long id);

}
