package ai.agent.service.rag.retrieve;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.config.rag.EmbeddingProperties;
import ai.agent.entity.rag.retrieve.RetrievalHit;
import ai.agent.service.rag.vector.VectorIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索实现：<b>路线文档要求的那一半检索侧</b>（向量兜语义）。
 *
 * <h2>它与 {@link LexicalRetriever} 的关系：同一个契约，两种世界观</h2>
 * <ul>
 *   <li><b>BM25</b>：只认词面。提问里必须出现文档里的词（且被同一个分析器切出来）。
 *       好处是精确（专有名词、错误码、配置项名一搜一个准），
 *       坏处是"换了说法就搜不到"。</li>
 *   <li><b>向量</b>：把文本压成一个点，比"点位像不像"。理论上能跨过词汇不匹配，
 *       代价是具体词面信息被平均掉了，专有名词反而不如 BM25 准。</li>
 * </ul>
 * 这正是"混合检索 + RRF"存在的理由 —— <b>不是谁替代谁，而是各补一半</b>。
 *
 * <h2>用的是哪个 Embedding（这一条决定了结论的边界）</h2>
 * 当前是项目内的离线哈希向量（{@link ai.agent.service.impl.rag.vector.HashingEmbeddingModel}），
 * <b>不是语义嵌入</b> —— 原因（DeepSeek 无 embedding 端点 + pgvector 装不上）
 * 写在那边的类注释里。所以本类能支撑的结论只有
 * <b>"换成稠密向量度量之后，分块策略的结论变了吗"</b>，
 * <b>不能</b>支撑"向量检索比 BM25 强"或"混合检索提升了多少"。
 * 后者要等接上真 embedding 模型才能量 —— 接口已经留好，换实现即可。
 *
 * <h2>为什么"索引"这一步要拟合 IDF</h2>
 * 因为 IDF 是语料级统计量（与 BM25 同一性质）。见
 * {@link VectorIndex#index} 里那段顺序说明：必须"先拟合、后算向量"，
 * 否则索引里会混着两种口径的向量。
 */
public class VectorRetriever implements Retriever {

	/** 评测报告里的列名。 */
	public static final String NAME = "vector-local";

	private final VectorIndex index;

	private final EmbeddingProperties properties;

	public VectorRetriever(VectorIndex index, EmbeddingProperties properties) {
		this.index = index;
		this.properties = properties;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void index(List<Chunk> chunks) {
		index.index(chunks);
	}

	@Override
	public List<RetrievalHit> retrieve(String query, int topK) {
		List<VectorIndex.Result> results = index.search(query, topK, properties.getSearch().getScanLimit(),
				properties.getSearch().getMinScore());
		List<RetrievalHit> hits = new ArrayList<>(results.size());
		for (VectorIndex.Result result : results) {
			// 名次直接来自向量索引（它已经排好序）；分数**只在同一次检索内可比**，
			// 与 BM25 的分数不可比 —— 这正是混合检索必须按名次融合、不能相加的原因
			hits.add(new RetrievalHit(result.chunk(), result.score(), result.rank()));
		}
		return hits;
	}

	/** 索引指纹，供诊断端点校验 Embedding 漂移。 */
	public String fingerprint() {
		return index.fingerprint();
	}

}
