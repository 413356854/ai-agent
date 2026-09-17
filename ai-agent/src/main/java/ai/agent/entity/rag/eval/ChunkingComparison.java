package ai.agent.entity.rag.eval;

import java.util.List;
import java.util.Map;

/**
 * 分块策略对照报告 —— <b>阶段二验收口径的载体</b>。
 *
 * <h2>验收口径为什么必须"并排比"，不能只报一个数</h2>
 * 验收口径是「能用数字说明<b>换分块策略提升了多少</b>」。
 * "提升了多少"是个<b>相对量</b>，所以报告里必须同时有实验组和对照组的数字，
 * 并且给出差值。只报"我的策略 Recall@5 = 0.88"是没法验收的 ——
 * 谁知道 0.88 算好还是算差？
 *
 * <h2>差值为什么按"百分点"而不是"倍数"</h2>
 * 评测指标都在 0~1 之间，说"提升了 1.4 倍"容易误导
 * （从 0.5 到 0.7 既可以说 +40% 也可以说 +20 个百分点，前者听着更唬人）。
 * 百分点（percentage point）在召回率语境下是标准说法，也更难被误读。
 *
 * @param evalSetLocation 评测集位置（报告要能自证用了哪份用例集）
 * @param caseCount      用例数
 * @param sampleSufficient 用例数是否达到"结论可用"的门槛
 * @param documents      语料文档数
 * @param failedDocuments 加载失败的文档（必须出现在报告里，否则"分数变了"无法解释）
 * @param strategies     各策略的指标
 * @param deltas         指标名 → 各策略相对第一个策略的差值（百分点）
 */
public record ChunkingComparison(String evalSetLocation, int caseCount, boolean sampleSufficient,
		int documents, List<String> failedDocuments, List<StrategyReport> strategies,
		Map<String, Map<String, Double>> deltas, List<String> notes) {

	public ChunkingComparison {
		failedDocuments = List.copyOf(failedDocuments);
		strategies = List.copyOf(strategies);
		deltas = Map.copyOf(deltas);
		notes = List.copyOf(notes);
	}

	/**
	 * 一个策略的完整报告。
	 *
	 * @param strategy   策略名
	 * @param chunkCount 块总数
	 * @param avgChunkTokens 平均块 token 数
	 * @param maxChunkTokens 最大块 token 数（能看到"有没有块超窗"）
	 * @param metrics    检索指标
	 * @param tokenCost  方案自身的规模代价（不检索也有意义的量）
	 */
	public record StrategyReport(String strategy, int chunkCount, double avgChunkTokens, int maxChunkTokens,
			RetrievalMetrics metrics, TokenCost tokenCost) {
	}

	/**
	 * 方案规模代价：<b>这一组数字连检索都不用跑就能说明问题</b>。
	 *
	 * @param totalTokens      全语料切成块后的 token 总量（含所有重复的重叠部分）
	 * @param duplicationRatio 重复率 = 总 token / 原文 token − 1。
	 *                         <b>这个数直接就是"重叠策略花了多少冤枉钱"</b> ——
	 *                         重叠是为了保住语义边界，但它对每一块都是重复计费：
	 *                         检索时会召回多个含重复内容的块，喂给模型时也重复占窗口。
	 * @param minTokens        最小块 token（看碎片化程度）
	 * @param undersizedChunks 低于配置下限的块数（碎块计数的直接证据）
	 */
	public record TokenCost(int totalTokens, double duplicationRatio, int minTokens, int undersizedChunks) {
	}

}
