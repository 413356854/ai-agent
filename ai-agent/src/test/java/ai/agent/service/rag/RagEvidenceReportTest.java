package ai.agent.service.rag;

import ai.agent.entity.rag.eval.AlignmentExperiment;
import ai.agent.entity.rag.eval.BudgetAlignment;
import ai.agent.entity.rag.eval.ChunkingComparison;
import ai.agent.entity.rag.eval.RetrievalMetrics;
import ai.agent.service.rag.pipeline.RagAlignmentService;
import ai.agent.service.rag.pipeline.RagEvaluationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

/**
 * 证据生成器：把一次完整评测的结果<b>打印成可直接引用的表格</b>。
 *
 * <h2>为什么要有这样一个"测试"</h2>
 * 本项目的实测数字必须能重新量出来，而不是"当时跑过一次、截图存在某处"。
 * 这个类的作用就是<b>让数字随时可复现</b>：跑一次
 * {@code mvn test -Dtest=RagEvidenceReportTest}，就会重新量一遍，
 * 而且走的是与线上完全相同的代码路径（不是另写一份统计脚本 ——
 * 那样脚本一旦与实现漂移，结论就失去意义）。
 *
 * <p>它不写断言：数字本身会随参数变化，把具体数值钉成断言会让
 * "调参"变成"改测试"。真正需要防退化的不变式由
 * {@link RagPipelineIntegrationTest} 负责。
 *
 * <h2>它打印两份报告，两份缺一不可</h2>
 * <ol>
 *   <li><b>原始对照</b>（{@link RagEvaluationService}）：500 字硬切 vs 结构分块。
 *       它回答"谁更省 token"，<b>回答不了</b>"哪个策略更好"——
 *       因为两组块大小不同，而块越大越容易覆盖答案所在章节；</li>
 *   <li><b>块大小对齐实验</b>（{@link RagAlignmentService}）：把对照组调到与实验组同大小，
 *       再加上"等 token 预算"这条轴。它才是"切点选得好不好"的证据。</li>
 * </ol>
 * 只打印第一份，就是拿一个混着块大小差异的数字去下分块策略的结论 ——
 * 这正是本轮要修掉的那个问题。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class RagEvidenceReportTest {

	@Autowired
	private RagEvaluationService evaluationService;

	@Autowired
	private RagAlignmentService alignmentService;

	@Test
	@DisplayName("打印原始对照 + 块大小对齐实验的完整证据表")
	void printEvidenceReport() {
		ChunkingComparison comparison = evaluationService.compare(null, 0);

		System.out.println();
		System.out.println("################ RAG 分块策略对照 · 实测证据 ################");
		System.out.println("评测集：" + comparison.evalSetLocation() + "  用例数：" + comparison.caseCount()
				+ "  样本量是否达标：" + comparison.sampleSufficient());
		System.out.println("语料文档数：" + comparison.documents() + "  加载失败：" + comparison.failedDocuments());
		System.out.println();

		System.out.println("| 策略 | 块数 | 平均 token | 最大 token | 重复率 |");
		System.out.println("|---|---|---|---|---|");
		for (ChunkingComparison.StrategyReport r : comparison.strategies()) {
			System.out.printf("| %s | %d | %.1f | %d | %.2f%% |%n",
					r.strategy(), r.chunkCount(), r.avgChunkTokens(), r.maxChunkTokens(),
					r.tokenCost().duplicationRatio() * 100);
		}
		System.out.println();

		System.out.println("| 策略 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR | Precision@5 | 前3条token | 全部候选token |");
		System.out.println("|---|---|---|---|---|---|---|---|---|");
		for (ChunkingComparison.StrategyReport r : comparison.strategies()) {
			RetrievalMetrics m = r.metrics();
			System.out.printf("| %s | %.4f | %.4f | %.4f | %.4f | %.4f | %.4f | %.0f | %.0f |%n",
					r.strategy(),
					m.recallAt().getOrDefault(1, 0.0), m.recallAt().getOrDefault(3, 0.0),
					m.recallAt().getOrDefault(5, 0.0), m.recallAt().getOrDefault(10, 0.0),
					m.mrr(), m.contextPrecisionAt().getOrDefault(5, 0.0),
					m.firstThreeHitsTokens(), m.topKTokens());
		}
		System.out.println();

		System.out.println("### 差值（相对 " + RagEvaluationService.BASELINE_STRATEGY + "）");
		System.out.println("> **单位跟着量纲走**：率类指标（Recall / MRR / Precision）是**百分点**，"
				+ "量类指标（块数 / token 数 / 比值）是**原值差**。第一版对所有指标统一乘 100，"
				+ "把 topKTokens 的差值印成了 -44320.93（真实 -443.2）—— 详见 RagEvaluationService 的注释");
		System.out.println("| 指标 | " + String.join(" | ", comparison.strategies().stream()
				.map(ChunkingComparison.StrategyReport::strategy).toList()) + " |");
		System.out.println("|---|" + "---|".repeat(comparison.strategies().size()));
		for (Map.Entry<String, Map<String, Double>> entry : comparison.deltas().entrySet()) {
			StringBuilder row = new StringBuilder("| " + entry.getKey() + " |");
			for (ChunkingComparison.StrategyReport r : comparison.strategies()) {
				row.append(" ").append(entry.getValue().getOrDefault(r.strategy(), 0.0)).append(" |");
			}
			System.out.println(row);
		}
		System.out.println();

		System.out.println("### 分组表现（哪一类问题最差）");
		for (ChunkingComparison.StrategyReport r : comparison.strategies()) {
			System.out.println("策略 " + r.strategy() + "：");
			System.out.println("| tag | 用例数 | Recall@5 | Recall@10 | MRR |");
			System.out.println("|---|---|---|---|---|");
			r.metrics().perTag().forEach((tag, t) -> System.out.printf("| %s | %d | %.4f | %.4f | %.4f |%n",
					tag, t.caseCount(), t.recallAt5(), t.recallAt10(), t.mrr()));
		}
		System.out.println();

		System.out.println("### 完全未命中的用例（前 20 条）");
		for (ChunkingComparison.StrategyReport r : comparison.strategies()) {
			System.out.println("策略 " + r.strategy() + " 未命中 " + r.metrics().misses().size() + " 条：");
			r.metrics().misses().stream().limit(20).forEach(miss ->
					System.out.println("  " + miss.caseId() + " [" + miss.tag() + "] " + miss.question()));
		}
		System.out.println();

		System.out.println("### 报告备注");
		comparison.notes().forEach(n -> System.out.println("  - " + n));
		System.out.println("############################################################");

		printAlignmentReport(alignmentService.run(null, 0));
	}

	/** 块大小对齐实验的证据表。 */
	private void printAlignmentReport(AlignmentExperiment experiment) {
		System.out.println();
		System.out.println("########## RAG 块大小对齐实验 · 实测证据 ##########");
		System.out.println("对齐轴：" + experiment.dimension() + "  对齐目标：" + experiment.alignTargetTokens()
				+ " token  收敛尺寸：" + experiment.alignedFixedSize() + " 字  实测平均："
				+ experiment.alignedMeasuredAvg() + " token  与目标差：" + experiment.alignedGapTokens() + " token");
		System.out.println("二分细化探针次数：" + experiment.refinementProbes()
				+ "  扫描范围：" + experiment.sweepRange() + "  用例数：" + experiment.caseCount()
				+ "  样本量是否达标：" + experiment.sampleSufficient());
		System.out.println("检索方式：" + experiment.retrievalMethods() + "  Embedding："
				+ experiment.embeddingModel() + "（" + experiment.embeddingDimensions() + " 维）  索引指纹："
				+ experiment.vectorFingerprint());
		System.out.println();

		System.out.println("### 尺寸扫描（为什么不能靠线性外推一次算准）");
		System.out.println("| 硬切字符数 | 块数 | 平均 token | 总 token | 重复率 | 与目标差(token) |");
		System.out.println("|---|---|---|---|---|---|");
		for (AlignmentExperiment.SweepRow row : experiment.sweep()) {
			System.out.printf("| %d | %d | %.1f | %d | %.2f%% | %+.1f |%n", row.fixedSize(), row.chunkCount(),
					row.avgChunkTokens(), row.totalTokens(), row.duplicationRatio() * 100, row.gapToTarget());
		}
		System.out.println();

		for (AlignmentExperiment.PairComparison pair : experiment.pairs()) {
			System.out.println("### 对齐对照 ｜ " + pair.fieldDescription() + "（轴：" + pair.dimension() + "）");
			System.out.println("| 组 | 块数 | 平均 token | 总 token | 重复率 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR | Precision@5 | 前3条token | 全部候选token |");
			System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
			for (ChunkingComparison.StrategyReport r : pair.reports()) {
				RetrievalMetrics m = r.metrics();
				System.out.printf("| %s | %d | %.1f | %d | %.2f%% | %.4f | %.4f | %.4f | %.4f | %.4f | %.4f | %.0f | %.0f |%n",
						r.strategy(), r.chunkCount(), r.avgChunkTokens(), r.tokenCost().totalTokens(),
						r.tokenCost().duplicationRatio() * 100,
						m.recallAt().getOrDefault(1, 0.0), m.recallAt().getOrDefault(3, 0.0),
						m.recallAt().getOrDefault(5, 0.0), m.recallAt().getOrDefault(10, 0.0),
						m.mrr(), m.contextPrecisionAt().getOrDefault(5, 0.0),
						m.firstThreeHitsTokens(), m.topKTokens());
			}
			System.out.println();
			System.out.println("差值（右 − 左；率=百分点 / 量=原值差）：");
			pair.deltas().forEach((metric, values) -> System.out.println("  " + metric + " = " + values));
			System.out.println();
			System.out.println("读法：");
			pair.notes().forEach(n -> System.out.println("  - " + n));
			System.out.println();
		}

		System.out.println("### 等 token 预算对照（同一预算内谁把答案找回来）");
		System.out.println("组名：" + String.join(" | ", experiment.budgetLabel()));
		System.out.println("| 预算 | 组 | 预算内 Recall | 预算内 MRR | 实际用掉 token | 装进几条 |");
		System.out.println("|---|---|---|---|---|---|");
		experiment.budgetRows().forEach((budget, row) -> row.forEach((label, a) -> System.out.printf(
				"| %d | %s | %.4f | %.4f | %.0f | %.1f |%n", budget, label, a.recall(), a.mrr(),
				a.avgTokensUsed(), a.avgHitCount())));
		System.out.println();

		printCrossRetrievalTable(experiment);

		System.out.println("### 对齐实验备注");
		experiment.notes().forEach(n -> System.out.println("  - " + n));
		System.out.println("########################################################");
	}

	/**
	 * 跨检索方式对照：<b>同一组对齐对照，在词法 / 向量 / 混合下的指标并排</b>。
	 *
	 * <p>这张表回答的是"分块策略的结论会不会随检索方式变化"——
	 * 它是本轮把"检索方式"当成实验维度之后新增的主表。
	 * 只报一种检索下的结论，等于把一个交叉因素当成了常量。
	 */
	private void printCrossRetrievalTable(AlignmentExperiment experiment) {
		System.out.println("### 跨检索方式对照（同一分块对齐组 × 每一路检索）");
		System.out.println("| 对照 | 检索方式 | 左组 Recall@5 | 右组 Recall@5 | ΔRecall@5(pp) | 左 MRR | 右 MRR | ΔMRR(pp) |");
		System.out.println("|---|---|---|---|---|---|---|---|");
		for (AlignmentExperiment.PairComparison pair : experiment.pairs()) {
			var left = pair.reports().get(0).metrics();
			var right = pair.reports().get(1).metrics();
			String method = pair.deltas().getOrDefault("recall@5", java.util.Map.of()).keySet().stream()
					.findFirst().orElse("?");
			String methodLabel = pair.dimension();
			if (pair.fieldDescription().contains("检索=")) {
				methodLabel = pair.fieldDescription().substring(pair.fieldDescription().indexOf("检索=") + 3);
			}
			System.out.printf("| %s | %s | %.4f | %.4f | %+.2f | %.4f | %.4f | %+.2f |%n",
					abbreviate(pair.fieldDescription()), methodLabel,
					left.recallAt().getOrDefault(5, 0.0), right.recallAt().getOrDefault(5, 0.0),
					pair.deltas().getOrDefault("recall@5", java.util.Map.of()).getOrDefault(method, 0.0),
					left.mrr(), right.mrr(),
					pair.deltas().getOrDefault("mrr", java.util.Map.of()).getOrDefault(method, 0.0));
		}
		System.out.println();
	}

	private static String abbreviate(String description) {
		// 只保留"左=…｜右=…"里最要紧的两段，表格里放全文会宽到没法看
		String flat = description.replace(" ｜ ", " | ");
		return flat.length() <= 120 ? flat : flat.substring(0, 117) + "…";
	}

	/** 供肉眼核对：把预算表按组重新组织（同一组在各档预算下的走势）。 */
	@SuppressWarnings("unused")
	private static void printBudgetByStrategy(AlignmentExperiment experiment) {
		for (String label : experiment.budgetLabel()) {
			System.out.println("组 " + label + "：");
			experiment.budgetRows().forEach((budget, row) -> {
				BudgetAlignment a = row.get(label);
				if (a != null) {
					System.out.printf("  预算 %d → Recall %.4f MRR %.4f 用掉 %.0f token / %.1f 条%n",
							budget, a.recall(), a.mrr(), a.avgTokensUsed(), a.avgHitCount());
				}
			});
		}
	}

}
