package ai.agent.service.rag.kb;

import ai.agent.entity.rag.kb.KnowledgeBase;

import java.util.List;

/**
 * 知识库的增删改查（不含文档与切片，那两个在 {@code KnowledgeDocumentService} / {@code KbChunkService} 里）。
 *
 * <h2>建库与删库都要同时动两个地方</h2>
 * 库里一行 + Milvus 里一个集合。两者的顺序是刻意的：
 * <ul>
 *   <li><b>建</b>：先插行（拿自增 id 拼集合名）→ 建集合 → 回写集合名。建集合失败就把那一行删掉 ——
 *       留下"有行没集合"的话，界面显示得好好的，第一次上传才会炸在很远的地方；</li>
 *   <li><b>删</b>：先删集合 → 再删切片与文档行 → 最后删这一行。中途崩掉留下的是
 *       "有行没集合"（下一次删除能接着走），而不是"没人知道集合名的孤儿集合"。</li>
 * </ul>
 */
public interface KnowledgeBaseService {

	/** 全部知识库，最近变更在前。 */
	List<KnowledgeBase> list();

	/** 按 id 取；不存在时抛 400（带上是哪个 id）。 */
	KnowledgeBase get(long id);

	/** 按 id 批量取（检索时用）。不存在的 id 会被忽略，由调用方决定要不要报错。 */
	List<KnowledgeBase> findByIds(List<Long> ids);

	/** 建库：校验 → 落行 → 建 Milvus 集合。任一步失败都不留半截状态。 */
	KnowledgeBase create(KnowledgeBase.Draft draft);

	/** 改名字/说明/检索参数。embedding 快照不可改（见 {@link KnowledgeBase} 的类注释）。 */
	KnowledgeBase update(long id, String name, String description, int topK, double similarityThreshold,
			boolean rerankEnabled);

	/** 启停。 */
	KnowledgeBase setEnabled(long id, boolean enabled);

	/** 删库：集合 + 切片 + 文档 + 这一行。 */
	void delete(long id);

	/** 这个库下有几份文档（列表页显示用；由 kb_document 现算，不存计数器）。 */
	long documentCount(long kbId);

	/** 这个库下有几个切片（检索响应里的 indexSize 来源）。 */
	long chunkCount(long kbId);

	/** 当前这套知识库能用哪些能力。界面上要能分辨"没配上"与"配上了但这次没查到东西"。 */
	Capabilities capabilities();

	/**
	 * 能力快照。
	 *
	 * @param embeddingAvailable 能不能算向量（false 时建库会被拒）
	 * @param embeddingReason 为什么不能；能的时候是 null
	 * @param embeddingProvider 当前配置的提供方
	 * @param embeddingModel 当前配置的默认模型
	 * @param embeddingDimensions 当前配置的默认维度
	 * @param milvusReachable Milvus 连得上吗（连不上时列表/检索都会失败）
	 * @param rerankAvailable 能不能重排（模型没开通/没配 key 时为 false）
	 * @param rerankReason 为什么不能重排；能的时候是 null
	 * @param rerankModel 当前的默认重排模型名
	 */
	record Capabilities(boolean embeddingAvailable, String embeddingReason, String embeddingProvider,
			String embeddingModel, int embeddingDimensions, boolean milvusReachable, boolean rerankAvailable,
			String rerankReason, String rerankModel) {
	}

}
