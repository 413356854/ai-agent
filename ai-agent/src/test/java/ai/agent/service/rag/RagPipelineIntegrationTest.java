package ai.agent.service.rag;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.entity.rag.eval.ChunkingComparison;
import ai.agent.service.rag.pipeline.CorpusLoader;
import ai.agent.service.rag.pipeline.RagEvaluationService;
import ai.agent.service.rag.retrieve.LexicalRetriever;
import ai.agent.entity.rag.retrieve.RetrievalHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 离线链路的<b>端到端</b>测试：语料 → 解析 → 分块 → 建索引 → 检索 → 指标。
 *
 * <h2>为什么单元测试之外还必须有这一个</h2>
 * 前面几个测试都用合成语料，它们能证明"每一环自己是对的"，
 * 但证明不了<b>串起来能跑</b>：配置有没有绑上、bean 有没有装配、
 * 真实文档解析出来是不是空的、中文检索能不能真的命中 ——
 * 这些只有拿真实语料跑一遍才知道。
 *
 * <p>它同时是一道<b>防退化闸门</b>：真实语料上如果哪天召回率掉到很低
 * （比如解析器被改动后标题识别不出来了），这里会直接失败，
 * 而不是等到有人翻报告时才发现。
 *
 * <p>注意本测试<b>不调用任何模型</b>：解析、分块、BM25 检索全是本地的，
 * 所以它跑得快、不要钱、可以在 CI 里每次都跑。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class RagPipelineIntegrationTest {

	@Autowired
	private CorpusLoader corpusLoader;

	@Autowired
	private RagEvaluationService evaluationService;

	@Test
	@DisplayName("真实语料能加载并被解析出结构（标题、表格、代码块都要有）")
	void loadsRealCorpusWithStructure() {
		CorpusLoader.CorpusLoadResult result = corpusLoader.load();

		assertThat(result.documents()).as("语料库里的文档").isNotEmpty();
		assertThat(result.failed()).as("不应有解析失败的文档").isEmpty();

		long headings = result.documents().stream()
				.flatMap(d -> d.blocks().stream())
				.filter(b -> b.type() == ai.agent.service.rag.parse.BlockType.HEADING)
				.count();
		long tables = result.documents().stream()
				.flatMap(d -> d.blocks().stream())
				.filter(b -> b.type() == ai.agent.service.rag.parse.BlockType.TABLE)
				.count();

		// 【最有价值的一条断言】标题数必须 > 0。
		// 标题识别是整个结构分块的地基：一旦解析器退化（例如 Tika 升级后
		// 不再吐 XHTML 结构），标题会变成普通段落，而"按标题层级分块"
		// 会静默退化成"按段落堆叠" —— 分数下降但没有任何报错。
		// 钉住这个数字，退化会立刻暴露
		assertThat(headings).as("识别出的标题块数（为 0 说明结构分块已失效）").isGreaterThan(20);
		assertThat(tables).as("识别出的表格块数（本项目大量结论是表格形态）").isGreaterThan(10);
	}

	@Test
	@DisplayName("两种策略都能切出块，且结构分块能给出标题路径")
	void bothStrategiesProduceChunks() {
		List<Chunk> fixed = evaluationService.chunkCorpus("fixed-size");
		List<Chunk> structural = evaluationService.chunkCorpus("structural");

		assertThat(fixed).isNotEmpty();
		assertThat(structural).isNotEmpty();

		// 对照组必须没有标题路径，实验组必须有 —— 这是两组的本质差异
		assertThat(fixed).allSatisfy(c -> assertThat(c.headingPath()).isEmpty());
		assertThat(structural.stream().filter(c -> !c.headingPath().isEmpty()).count())
				.as("带标题路径的块（结构分块的产出）").isGreaterThan(0);
	}

	@Test
	@DisplayName("中文提问能检索到正确的章节（词法检索 + 中文二元组确实可用）")
	void retrievesRelevantChunkForChineseQuery() {
		List<Chunk> chunks = evaluationService.chunkCorpus("structural");
		LexicalRetriever retriever = new LexicalRetriever();
		retriever.index(chunks);

		// 一个真实的问题：答案是"虚拟线程异步改造"那一节的实测数字
		List<RetrievalHit> hits = retriever.retrieve("虚拟线程在高并发下的吞吐量提升了多少", 5);

		assertThat(hits).isNotEmpty();
		// 前 5 条里应当出现虚拟线程相关的章节
		assertThat(hits.stream().map(h -> h.chunk().breadcrumb()).toList())
				.anySatisfy(breadcrumb -> assertThat(breadcrumb).contains("虚拟线程"));
	}

	@Test
	@DisplayName("完整对照评测能跑通，并给出两条策略的指标与差值")
	void runsFullComparison() {
		ChunkingComparison comparison = evaluationService.compare(null, 0);

		assertThat(comparison.strategies()).hasSize(2);
		assertThat(comparison.caseCount()).isGreaterThanOrEqualTo(30);
		assertThat(comparison.sampleSufficient()).as("用例数达到可用门槛").isTrue();
		assertThat(comparison.failedDocuments()).isEmpty();

		for (ChunkingComparison.StrategyReport report : comparison.strategies()) {
			assertThat(report.chunkCount()).isPositive();
			assertThat(report.maxChunkTokens()).isPositive();
			// 召回率必须有意义地大于 0，否则说明检索或标注整条链路是坏的
			assertThat(report.metrics().recallAt().get(10))
					.as("策略 %s 的 Recall@10", report.strategy()).isGreaterThan(0.3);
		}

		// 差值表以对照组为基准，且对照组自己的差值恒为 0
		assertThat(comparison.deltas()).containsKey("recall@5");
		assertThat(comparison.deltas().get("recall@5").get(RagEvaluationService.BASELINE_STRATEGY)).isZero();
	}

}
