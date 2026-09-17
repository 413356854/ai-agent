package ai.agent.entity.rag.eval;

import java.util.List;

/**
 * 块大小对齐实验报告 —— <b>把原始对照里那个混杂因素消掉</b>。
 *
 * <h2>它要回答的问题，以及为什么原始对照答不了</h2>
 * 原始对照（500 字硬切 vs 结构分块）量出：结构分块 Recall@5 更低、但更省 token。
 * 问题是<b>两组的块不一样大</b>（实测 232.5 vs 200.6 token），
 * 而本项目的相关性判定是"检索到的块有没有覆盖答案所在章节"——
 * <b>块越大越容易覆盖到答案</b>。于是那 4.66 个百分点的差距里，
 * 有多少来自"切点选在哪"、有多少来自"块更大"，完全分不开。
 *
 * <p>更要命的是这类结论<b>朝有利于自己的方向偏还不报错</b>：
 * 如果做了个"新分块策略"却把块也调大了，报告会显示召回大涨，
 * 而真正起作用的是块大小（同族还有一个例子，「结论朝有利于自己的方向偏，而且不报错」：
 * 判定用了对照组没有的元数据，实验组虚高、对照组虚低，结论漂亮且不报错）。
 *
 * <h2>两个必须分开的对照轴</h2>
 * <ol>
 *   <li><b>轴一 · 平均块大小对齐</b>：把对照组的块调到与实验组一样大，
 *       于是"块越大越容易碰到答案"两边同时成立 → 差异只能来自切点。
 *       光对齐还不算完：结构分块报出的 200.6 token 是<b>渲染后</b>的大小，
 *       每块都会再拼一遍标题路径，所以正文本身与对照组仍不同尺 ——
 *       因此还要再跑一组"关掉面包屑"的，
 *       否则对齐的只是渲染后的大小，正文仍然不同尺，混杂因素只是换了个地方待着。</li>
 *   <li><b>轴二 · 等 token 预算</b>：按"名次取前 10 条"比，大块那组天然花更多钱 ——
 *       "花更多钱换来更高召回"不构成"策略更好"。所以再按固定预算归纳一遍召回：
 *       同一个预算里谁把答案找回来，谁才真的划算。</li>
 * </ol>
 *
 * <p><b>两条轴必须都报</b>：只报轴一，会漏掉"小块在多轮检索里更省钱"这个真实收益；
 * 只报轴二，会让"块太小导致语义被切断"的代价看不见。
 *
 * @param evalSetLocation   评测集位置（报告要能自证用了哪份用例集）
 * @param caseCount         用例数
 * @param sampleSufficient  用例数是否达到"结论可用"的门槛
 * @param documents         语料文档数
 * @param failedDocuments   加载失败的文档（必须出现，否则"分数变了"无法解释）
 * @param dimension         对齐轴
 * @param alignTargetTokens 对齐目标：实验组的平均块 token（对齐的"靶子"）
 * @param sweepRange        尺寸扫描的字符数范围（含两端）
 * @param sweep             尺寸扫描表：字符数 → 量到的块大小
 * @param alignedFixedSize  收敛得到的对照组分块字符数
 * @param alignedMeasuredAvg 用该尺寸实测到的平均块 token
 * @param alignedGapTokens  与对齐目标的差（token）。<b>必须报出来</b>：
 *                          "对齐"永远是近似的，差多少要让人自己判断够不够
 * @param refinementProbes  二分细化实际跑了几次（可复现性的一部分：收敛过程也要能重放）
 * @param pairs             各对齐组的并排对照
 * @param budgetSteps       预算对齐的档位
 * @param budgetLabel       各对齐组的可读名，与 {@code budgetRows} 的下标一一对应
 * @param budgetRows        预算对齐表：档位 → 组 → 该组的预算内指标
 * @param retrievalMethods  对照里跑过的检索方式（词法 / 向量 / 混合）。
 *                          <b>它必须出现在报告里</b>：同一个分块策略在不同检索方式下
 *                          结论可能不同，不写明"这个数字来自哪一路检索"就无从解释差异
 * @param embeddingModel    向量那一路用的 Embedding 实现名。
 *                          带 {@code local-} 前缀说明它是离线的近似品，不是真语义嵌入
 * @param embeddingDimensions 向量维度
 * @param vectorFingerprint 索引指纹（模型/维度），用于校验 Embedding 漂移
 * @param notes             注意事项（读法、边界、不能推出的结论）
 */
public record AlignmentExperiment(String evalSetLocation, int caseCount, boolean sampleSufficient, int documents,
		List<String> failedDocuments, String dimension, double alignTargetTokens, List<Integer> sweepRange,
		List<SweepRow> sweep, int alignedFixedSize, double alignedMeasuredAvg, double alignedGapTokens,
		int refinementProbes, List<PairComparison> pairs, List<Integer> budgetSteps, List<String> budgetLabel,
		java.util.Map<Integer, java.util.Map<String, BudgetAlignment>> budgetRows, List<String> retrievalMethods,
		String embeddingModel, int embeddingDimensions, String vectorFingerprint, List<String> notes) {

	public AlignmentExperiment {
		failedDocuments = List.copyOf(failedDocuments);
		sweepRange = List.copyOf(sweepRange);
		sweep = List.copyOf(sweep);
		pairs = List.copyOf(pairs);
		budgetSteps = List.copyOf(budgetSteps);
		budgetLabel = List.copyOf(budgetLabel);
		budgetRows = java.util.Map.copyOf(budgetRows);
		retrievalMethods = List.copyOf(retrievalMethods);
		notes = List.copyOf(notes);
	}

	/** 扫描表的一行：某个字符数下的规模。 */
	public record SweepRow(int fixedSize, int chunkCount, double avgChunkTokens, int totalTokens,
			double duplicationRatio, double gapToTarget) {
	}

	/**
	 * 一组对齐对照。
	 *
	 * <p>{@code reports} 恒为两项，且<b>第 0 项是参照方、第 1 项是被考察方</b>：
	 * 差值与颜色一律按"第 1 项相对第 0 项"计算。方向定死是为了避免
	 * "这一组的差值是谁减谁"要靠猜 —— 在一张有 6 个数字的对照表里，
	 * 搞错方向会让读者得出完全相反的结论。
	 *
	 * @param fieldDescription 这一组对齐的是哪个字段（例如"平均块大小 == 202.4 token（结构分块关掉面包屑，正文也对齐）"）
	 * @param dimension        对齐轴
	 * @param reports          参照方 + 被考察方
	 * @param deltas           指标名 → 策略名 → 差值（百分点，被考察方 − 参照方）
	 * @param notes            这一组自己的注意事项（哪一组能说什么、不能说什么）
	 */
	public record PairComparison(String fieldDescription, String dimension, List<ChunkingComparison.StrategyReport> reports,
			java.util.Map<String, java.util.Map<String, Double>> deltas, List<String> notes) {

		public PairComparison {
			reports = List.copyOf(reports);
			deltas = java.util.Map.copyOf(deltas);
			notes = List.copyOf(notes);
		}
	}

}
