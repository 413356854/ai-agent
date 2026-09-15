package ai.agent.rag;

import ai.agent.rag.eval.ChunkingComparison;
import ai.agent.rag.eval.RetrievalMetrics;
import ai.agent.rag.pipeline.RagEvaluationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

/**
 * 证据生成器：把一次完整对照评测的结果<b>打印成可直接贴进台账的表格</b>。
 *
 * <h2>为什么要有这样一个"测试"</h2>
 * 台账里记的数字必须能重新量出来，而不是"当时跑过一次、截图存在某处"。
 * 这个类的作用就是<b>让数字随时可复现</b>：跑一次
 * {@code mvn test -Dtest=RagEvidenceReportTest}，就会重新量一遍，
 * 而且走的是与线上完全相同的代码路径（不是另写一份统计脚本 ——
 * 那样脚本一旦与实现漂移，结论就失去意义）。
 *
 * <p>它不写断言：数字本身会随参数变化，把具体数值钉成断言会让
 * "调参"变成"改测试"。真正需要防退化的不变式由
 * {@link RagPipelineIntegrationTest} 负责。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class RagEvidenceReportTest {

	@Autowired
	private RagEvaluationService evaluationService;

	@Test
	@DisplayName("打印分块策略对照的完整证据表")
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

		System.out.println("### 差值（百分点，相对 " + RagEvaluationService.BASELINE_STRATEGY + "）");
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
	}

}
