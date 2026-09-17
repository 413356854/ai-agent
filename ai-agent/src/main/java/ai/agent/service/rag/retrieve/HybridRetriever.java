package ai.agent.service.rag.retrieve;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.entity.rag.retrieve.RetrievalHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：<b>向量兜语义 + BM25 兜精确，按名次融合（RRF）</b>。
 *
 * <h2>为什么必须按名次融合，而不是把两种分数加权相加</h2>
 * 因为两种分数的<b>量纲根本不同</b>：BM25 是"无上界的相关性得分"
 * （本语料实测动辄 5～20 分），向量是余弦相似度（<b>恒在 −1～1 之间</b>）。
 * 直接 {@code 0.5 * bm25 + 0.5 * cosine} 的结果是：向量那一路的贡献被 BM25 的数量级
 * 完全淹没，融合出来的排序和"纯 BM25"几乎一样 ——
 * 而报告上会写着"我们做了混合检索"。这类错误不报错、也不崩，
 * 只是<b>你做的那个改进从未生效</b>（台账踩坑库 #35 同族）。
 *
 * <p>RRF（Reciprocal Rank Fusion）绕开了量纲问题：它只看<b>名次</b>。
 * 第 {@code r} 名的贡献是 {@code 1/(k + r)}，多路得分相加。
 * 一个块如果两路都排得靠前，它就赢；只在某一路靠前，也能拿到分。
 *
 * <h2>{@code k} 取 60 是什么意思</h2>
 * RRF 原论文（Cormack et al. 2009）的经验值就是 60，作用是<b>压平名次之间的差距</b>：
 * k 很大时 {@code 1/(60+1)} 与 {@code 1/(60+10)} 相差不到 15%，
 * 于是"第 1 名"不会一枝独秀，多路一致的中等名次能超过单路的第一名。
 * 取小（比如 1）会让融合结果退化成"取并集后按单路最优名次排"，
 * 那基本等于没有融合。
 */
public class HybridRetriever implements Retriever {

	/** 评测报告里的列名。 */
	public static final String NAME = "hybrid-rrf";

	/** RRF 的平滑常数，取原论文经验值 60。 */
	private static final int RRF_K = 60;

	/** 参与融合的两路检索器。<b>顺序无关</b>（RRF 是对称的），但要都非空。 */
	private final List<Retriever> delegates;

	public HybridRetriever(List<Retriever> delegates) {
		if (delegates == null || delegates.isEmpty()) {
			throw new IllegalArgumentException("混合检索至少需要一路检索器");
		}
		this.delegates = List.copyOf(delegates);
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void index(List<Chunk> chunks) {
		for (Retriever delegate : delegates) {
			delegate.index(chunks);
		}
	}

	@Override
	public List<RetrievalHit> retrieve(String query, int topK) {
		if (topK <= 0) {
			return List.of();
		}
		Map<String, Double> fusedScore = new LinkedHashMap<>();
		Map<String, Chunk> chunkById = new LinkedHashMap<>();

		for (Retriever delegate : delegates) {
			// 每一路都按 topK 取：不要"各路取 2×topK 再截断"这种拍脑袋的放大 ——
			// 融合后的名次由 RRF 决定，多取只影响候选池大小，而候选池大小本身
			// 应当是一个显式参数（台账踩坑库 #46：兜底/放大顺手改掉了主逻辑的活）
			for (RetrievalHit hit : delegate.retrieve(query, topK)) {
				chunkById.put(hit.chunk().id(), hit.chunk());
				fusedScore.merge(hit.chunk().id(), 1.0 / (RRF_K + hit.rank()), Double::sum);
			}
		}

		List<Map.Entry<String, Double>> ordered = new ArrayList<>(fusedScore.entrySet());
		// 分数相同时按块 id 排：**不能让顺序取决于 HashMap 的遍历顺序**，
		// 否则同一份数据两次运行可能给出不同的名次，而指标会跟着抖（不可复现的实验没有价值）
		ordered.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
				.thenComparing(Map.Entry::getKey));

		List<RetrievalHit> hits = new ArrayList<>();
		int rank = 1;
		for (Map.Entry<String, Double> entry : ordered) {
			if (rank > topK) {
				break;
			}
			hits.add(new RetrievalHit(chunkById.get(entry.getKey()), entry.getValue(), rank));
			rank++;
		}
		return hits;
	}

}
