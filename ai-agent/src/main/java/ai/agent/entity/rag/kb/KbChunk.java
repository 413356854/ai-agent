package ai.agent.entity.rag.kb;

import java.time.Instant;

/**
 * 一个切片（{@code kb_chunk} 表的一行）。
 *
 * <p>它有两个 id，各自只有一个用途：
 * {@code id} 是 SQL 主键（自增，用于排序与关联），
 * {@code uid} 是 <b>Milvus 集合里的主键</b>（Spring AI 的 {@code MilvusVectorStore}
 * 以 {@code autoId=false} 建集合，主键取 {@code Document} 的 id 字符串）。
 * 之所以不用自增 id 直接当 Milvus 主键：切片是批量插入的，而本项目驱动组合下
 * MyBatis 的自增主键回填不可用（见 {@code AgentTaskMapper} 的类注释），
 * 拿不到刚插入那些行的 id；自己生成 uid 之后，批量插入一条 SQL 就够，
 * 一致性核对也仍然是对同一组 uid 做集合比对。
 *
 * <h2>正文与标题路径是两列</h2>
 * 与阶段二的 {@code Chunk} 不同（那个的 {@code text} 是"含面包屑的正文"，
 * 因为它要直接送进检索与模型）。这里的 {@code text} 是**纯正文**，标题路径单独一列：
 * 切片编辑页要改的是正文，而面包屑是"它从哪来"的事实，两者混在一列里改一次就分不开了。
 * 送进 embedding 的文本由 {@link #embeddingText()} 拼（正文 + 面包屑）——
 * 拼法与阶段二的结构化切分保持一致，标题参与向量、正文不重复。
 *
 * @param id SQL 主键（自增）
 * @param uid Milvus 集合里的主键（Java 生成）
 * @param kbId 所属知识库
 * @param documentId 所属文档
 * @param seq 文档内序号（从 0 开始）
 * @param text 纯正文
 * @param headingPath 标题路径，例「四、踩坑知识库 &gt; 2.3」；无标题为 null
 * @param tokenCount 估算 token 数
 * @param charCount 字符数
 * @param enabled 是否参与检索（检索侧在召回后按它过滤，Milvus 里不存这一位）
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record KbChunk(Long id, String uid, long kbId, long documentId, int seq, String text, String headingPath,
		int tokenCount, int charCount, boolean enabled, Instant createdAt, Instant updatedAt) {

	/** 与 {@code kb_chunk.uid} 的列宽一致。 */
	public static final int MAX_UID_CHARS = 64;

	/** 与 {@code kb_chunk.heading_path} 的列宽一致。 */
	public static final int MAX_HEADING_CHARS = 512;

	/** 送进 embedding 的文本。 */
	public String embeddingText() {
		if (headingPath == null || headingPath.isBlank()) {
			return text;
		}
		return headingPath + "\n\n" + text;
	}

	/** 标题路径超长时截断并留痕（列宽 512，超了写入会直接失败）。 */
	public static String clampHeading(String raw) {
		if (raw == null) {
			return null;
		}
		String stripped = raw.strip();
		if (stripped.isEmpty()) {
			return null;
		}
		if (stripped.length() <= MAX_HEADING_CHARS) {
			return stripped;
		}
		return stripped.substring(0, MAX_HEADING_CHARS - 1) + "…";
	}

}
