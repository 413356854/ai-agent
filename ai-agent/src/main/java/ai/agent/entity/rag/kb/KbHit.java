package ai.agent.entity.rag.kb;

/**
 * 一条知识库召回结果。
 *
 * <h2>为什么不复用阶段二的 {@code RetrievalHit}</h2>
 * 那个是 {@code (Chunk, score, rank)}，而 {@link Chunk} 的三个字段在知识库这边没有意义：
 * {@code startBlock}/{@code endBlock} 是**解析块的下标**（分块器切的时候才有），
 * 而知识库的切片可以来自库里的清单、可以被人改过正文 —— 它们不对应任何解析块。
 * 填 0 会让"这个块覆盖第 0 个解析块"与"这个块不来自解析块"变成同一个值，
 * 正是本项目禁止的那种空值/零值混淆。
 *
 * <p>另外这里必须能带 {@code kbName}/{@code documentName}（命中要显示"它来自哪个库的哪份文档"），
 * 以及两个分数（向量分与重排分）—— 它们是不同的量纲，合成一个数就没法回答
 * "重排到底改了什么"。
 *
 * @param kbId 命中的知识库
 * @param kbName 知识库名（展示用；跨库检索时"这条来自哪"必须能读出来）
 * @param documentId 命中的文档
 * @param documentName 文档名（展示用）
 * @param chunkUid 切片 uid（Milvus 里的主键，也是回表取正文的键）
 * @param seq 切片在文档里的序号
 * @param text 切片正文（不含标题路径，路径单独一列）
 * @param headingPath 标题路径，可为 null
 * @param tokenCount 估算 token 数
 * @param vectorScore 向量相似度（COSINE，越大越像）；**只在同一次检索内可比**
 * @param rerankScore 重排分；没重排时为 null（null 与 0 的含义不同：0 是一个很低的重排分）
 * @param rank 名次，从 1 开始
 */
public record KbHit(long kbId, String kbName, long documentId, String documentName, String chunkUid, int seq,
		String text, String headingPath, int tokenCount, double vectorScore, Double rerankScore, int rank) {

	/** 带上名次的副本（合并、过滤、重排之后统一编号）。 */
	public KbHit withRank(int newRank) {
		return new KbHit(kbId, kbName, documentId, documentName, chunkUid, seq, text, headingPath, tokenCount,
				vectorScore, rerankScore, newRank);
	}

	/** 带上重排分的副本。 */
	public KbHit withRerankScore(double score) {
		return new KbHit(kbId, kbName, documentId, documentName, chunkUid, seq, text, headingPath, tokenCount,
				vectorScore, score, rank);
	}

	/** 来源标签，形如 {@code 产品手册 / 安装指南.pdf}。 */
	public String source() {
		return kbName + " / " + documentName;
	}

}
