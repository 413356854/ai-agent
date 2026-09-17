package ai.agent.service.rag.eval;

import ai.agent.config.ChatCallProperties;
import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.entity.rag.eval.EvalCase;
import ai.agent.entity.rag.eval.RetrievalMetrics;
import ai.agent.service.rag.chunk.DocumentChunker;
import ai.agent.service.impl.rag.chunk.StructuralChunker;
import ai.agent.config.rag.RagProperties;
import ai.agent.service.rag.parse.BlockType;
import ai.agent.entity.rag.parse.ParsedBlock;
import ai.agent.entity.rag.parse.ParsedDocument;
import ai.agent.service.rag.retrieve.LexicalRetriever;
import ai.agent.entity.rag.retrieve.RetrievalHit;
import ai.agent.service.impl.token.CharacterClassTokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测链路测试：<b>用一个答案位置完全已知的语料，验证指标算得对</b>。
 *
 * <h2>为什么必须先用合成语料验算指标，再去测真实语料</h2>
 * 真实语料上跑出来的 Recall 是 0.7 还是 0.5，<b>数字本身无法判断指标实现对不对</b> ——
 * 也许分块真的差，也许是指标算错了。而一个算错的指标比没有指标更危险：
 * 它会稳定地指导你做错的决定。
 *
 * <p>所以这里构造一份"每条用例的正确答案在哪个块里、清清楚楚"的小语料，
 * 让期望值可以<b>手算出来</b>：两条用例、一条命中一条不命中，Recall 就必须精确等于 0.5。
 * 算得出来的期望值，才叫验算。
 *
 * <h2>为什么测试里要走"解析文档 → 分块"而不是直接造 Chunk</h2>
 * 因为相关性判定现在依赖<b>块覆盖的解析块区间</b>（见 {@link SectionLocator}），
 * 手工造的 Chunk 没有真实区间，测出来的是假通过。
 * 走真实链路（有标题、有段落、有区间）才能验证判定逻辑本身。
 */
class RetrievalEvaluationTest {

	private static final String DOC = "test-corpus.md";

	private final CharacterClassTokenEstimator estimator =
			new CharacterClassTokenEstimator(new ChatCallProperties());

	/**
	 * 造一份带章节结构的文档，并切块。
	 *
	 * <p>结构：每个 {@code ## 标题} 后面跟一个正文段落。这样每个标题的区间是
	 * {@code [标题块, 正文块]}，判定时有明确的正确答案范围。
	 */
	private List<Chunk> chunkedDocument(String... headingAndBody) {
		List<ParsedBlock> blocks = new ArrayList<>();
		for (int i = 0; i < headingAndBody.length; i += 2) {
			blocks.add(new ParsedBlock(BlockType.HEADING, headingAndBody[i], 2, blocks.size()));
			blocks.add(new ParsedBlock(BlockType.PARAGRAPH, headingAndBody[i + 1], 0, blocks.size()));
		}
		ParsedDocument document = new ParsedDocument(DOC, blocks);
		RagProperties properties = new RagProperties();
		DocumentChunker chunker = new StructuralChunker(properties, estimator);
		return chunker.chunk(document);
	}

	private Map<String, List<RetrievalHit>> retrieveAll(LexicalRetriever retriever, List<EvalCase> cases, int topK) {
		Map<String, List<RetrievalHit>> byCase = new LinkedHashMap<>();
		for (EvalCase c : cases) {
			byCase.put(c.id(), retriever.retrieve(c.question(), topK));
		}
		return byCase;
	}

	private RetrievalMetrics metrics(List<EvalCase> cases, Map<String, List<RetrievalHit>> hits,
			List<Chunk> chunks, List<ParsedDocument> documents) {
		SectionLocator locator = SectionLocator.build(cases, documents);
		return RetrievalMetrics.of("test", cases, hits, List.of(1, 3, 5), locator, chunks.size(), 0);
	}

	private ParsedDocument documentOf(String... headingAndBody) {
		List<ParsedBlock> blocks = new ArrayList<>();
		for (int i = 0; i < headingAndBody.length; i += 2) {
			blocks.add(new ParsedBlock(BlockType.HEADING, headingAndBody[i], 2, blocks.size()));
			blocks.add(new ParsedBlock(BlockType.PARAGRAPH, headingAndBody[i + 1], 0, blocks.size()));
		}
		return new ParsedDocument(DOC, blocks);
	}

	@Test
	@DisplayName("命中与未命中各一条时，Recall@k 精确等于 0.5")
	void computesRecallExactly() {
		ParsedDocument document = documentOf(
				"虚拟线程", "虚拟线程在阻塞时会卸载载体线程，等待不再占用操作系统线程。",
				"Token 预算", "上下文预算的公式是窗口乘以安全边际，再扣掉为输出预留的额度。",
				"无关章节", "本节讨论的内容与任何提问都无关，纯粹用来占位。");

		RagProperties properties = new RagProperties();
		List<Chunk> chunks = new StructuralChunker(properties, estimator).chunk(document);
		LexicalRetriever retriever = new LexicalRetriever();
		retriever.index(chunks);

		List<EvalCase> cases = List.of(
				// 这条应当命中：提问里的词都在"虚拟线程"那一节
				new EvalCase(DOC, "C1", "虚拟线程阻塞时会怎样", List.of("虚拟线程"), "t"),
				// 这条应当命中不到：标准答案标在一个语义上不存在的章节
				new EvalCase(DOC, "C2", "完全不相关的提问内容", List.of("安全与权限"), "t"));

		RetrievalMetrics metrics = metrics(cases, retrieveAll(retriever, cases, 5), chunks, List.of(document));

		assertThat(metrics.caseCount()).isEqualTo(2);
		// 2 条里命中 1 条 → 0.5。这是可以手算出来的期望值
		assertThat(metrics.recallAt().get(5)).isEqualTo(0.5);
		assertThat(metrics.recallAt().get(1)).isEqualTo(0.5);
		// MRR：命中的那条排第 1 → 1.0；未命中 → 0；平均 0.5
		assertThat(metrics.mrr()).isEqualTo(0.5);
		// 未命中的那条应当出现在 misses 里（报告最该让人看到的部分）
		assertThat(metrics.misses()).hasSize(1);
		assertThat(metrics.misses().get(0).caseId()).isEqualTo("C2");
	}

	@Test
	@DisplayName("全部命中且都排第一时，Recall 与 MRR 都是 1.0")
	void perfectRetrievalScoresOne() {
		ParsedDocument document = documentOf(
				"虚拟线程", "虚拟线程在阻塞时会卸载载体线程。",
				"Token 预算", "上下文预算公式：窗口乘以安全边际。");
		List<Chunk> chunks = new StructuralChunker(new RagProperties(), estimator).chunk(document);
		LexicalRetriever retriever = new LexicalRetriever();
		retriever.index(chunks);

		List<EvalCase> cases = List.of(
				new EvalCase(DOC, "C1", "虚拟线程阻塞", List.of("虚拟线程"), "t"),
				new EvalCase(DOC, "C2", "上下文预算公式", List.of("Token 预算"), "t"));

		RetrievalMetrics metrics = metrics(cases, retrieveAll(retriever, cases, 5), chunks, List.of(document));
		assertThat(metrics.recallAt().get(1)).isEqualTo(1.0);
		assertThat(metrics.mrr()).isEqualTo(1.0);
		assertThat(metrics.misses()).isEmpty();
	}

	@Test
	@DisplayName("标注章节不存在时，locator 必须报出来（而不是让这条用例悄悄算未命中）")
	void reportsUnresolvedLabels() {
		ParsedDocument document = documentOf("真实章节", "一段关于虚拟线程的正文。");
		List<EvalCase> cases = List.of(
				new EvalCase(DOC, "C1", "虚拟线程", List.of("根本不存在的章节"), "t"));

		SectionLocator locator = SectionLocator.build(cases, List.of(document));

		// 这条断言的价值：标注过期必须可见。若不可见，召回率下降会被误读成"分块变差了"
		assertThat(locator.unresolved()).hasSize(1);
		assertThat(locator.unresolved().get(0)).contains("C1").contains("根本不存在的章节");
	}

	@Test
	@DisplayName("标注有效时 locator 不该报未解析")
	void resolvesValidLabels() {
		ParsedDocument document = documentOf("真实章节", "一段关于虚拟线程的正文。");
		List<EvalCase> cases = List.of(
				new EvalCase(DOC, "C1", "虚拟线程", List.of("真实章节"), "t"));

		SectionLocator locator = SectionLocator.build(cases, List.of(document));
		assertThat(locator.unresolved()).isEmpty();
	}

	@Test
	@DisplayName("不同文档的同名章节不算命中（避免跨文档误判）")
	void sameHeadingInOtherDocumentDoesNotMatch() {
		ParsedDocument document = documentOf("注意事项", "本文档的注意事项内容。");
		Chunk chunk = new StructuralChunker(new RagProperties(), estimator).chunk(document).get(0);

		// 用例的语料是别的文档，但标注了同名章节
		EvalCase evalCase = new EvalCase("other.md", "C1", "注意事项", List.of("注意事项"), "t");
		SectionLocator locator = SectionLocator.build(List.of(evalCase), List.of(document));

		// 块属于 test-corpus.md，用例的语料是 other.md —— 不能算命中
		assertThat(locator.matches(evalCase, chunk.documentId(), chunk.startBlock(), chunk.endBlock())).isFalse();
	}

	@Test
	@DisplayName("对照组（无标题路径）也能被公平判定：判定不看元数据，只看覆盖区间")
	void baselineIsJudgedFairly() {
		ParsedDocument document = documentOf(
				"虚拟线程", "虚拟线程在阻塞时会卸载载体线程，等待不再占用操作系统线程。".repeat(3));

		// 对照组：固定字数硬切，它的块**没有**标题路径
		List<Chunk> chunks = new ai.agent.service.impl.rag.chunk.FixedSizeChunker(new RagProperties(), estimator)
				.chunk(document);
		assertThat(chunks).isNotEmpty();
		assertThat(chunks).allSatisfy(c -> assertThat(c.headingPath()).isEmpty());

		// 但它必须记录覆盖区间，否则判定无从下手
		assertThat(chunks).allSatisfy(c -> assertThat(c.startBlock()).isGreaterThanOrEqualTo(0));

		EvalCase evalCase = new EvalCase(DOC, "C1", "虚拟线程阻塞", List.of("虚拟线程"), "t");
		SectionLocator locator = SectionLocator.build(List.of(evalCase), List.of(document));

		// 硬切的块覆盖了含答案的那一节 → 必须判为命中
		boolean anyMatch = chunks.stream().anyMatch(c ->
				locator.matches(evalCase, c.documentId(), c.startBlock(), c.endBlock()));
		assertThat(anyMatch)
				.as("硬切的块覆盖了答案所在章节，应当判为命中 —— 否则对照组会被无理由压成 0 分")
				.isTrue();
	}

	@Test
	@DisplayName("token 成本被统计出来：前 3 条与全部候选各算一遍")
	void tracksTokenCost() {
		String[] pairs = new String[16];
		for (int i = 0; i < 8; i++) {
			pairs[i * 2] = "章节" + i;
			pairs[i * 2 + 1] = ("第" + i + "段内容。").repeat(20);
		}
		ParsedDocument document = documentOf(pairs);
		List<Chunk> chunks = new StructuralChunker(new RagProperties(), estimator).chunk(document);
		LexicalRetriever retriever = new LexicalRetriever();
		retriever.index(chunks);

		List<EvalCase> cases = List.of(new EvalCase(DOC, "C1", "第3段内容", List.of("章节3"), "t"));
		RetrievalMetrics metrics = metrics(cases, retrieveAll(retriever, cases, 5), chunks, List.of(document));

		assertThat(metrics.firstThreeHitsTokens()).isPositive();
		// 前 3 条的 token 不该超过全部召回（5 条）的 token
		assertThat(metrics.firstThreeHitsTokens()).isLessThanOrEqualTo(metrics.topKTokens());
	}

	@Test
	@DisplayName("按 tag 分组统计：某类问题整体更差时，总平均会把它藏起来")
	void aggregatesPerTag() {
		ParsedDocument document = documentOf(
				"能命中的章节", "一段关于虚拟线程的正文内容。",
				"另一章节", "完全无关的占位内容。");
		List<Chunk> chunks = new StructuralChunker(new RagProperties(), estimator).chunk(document);
		LexicalRetriever retriever = new LexicalRetriever();
		retriever.index(chunks);

		List<EvalCase> cases = List.of(
				new EvalCase(DOC, "A1", "虚拟线程", List.of("能命中的章节"), "good"),
				new EvalCase(DOC, "B1", "毫无关联的问法", List.of("找不到的章节"), "bad"));

		RetrievalMetrics metrics = metrics(cases, retrieveAll(retriever, cases, 5), chunks, List.of(document));

		assertThat(metrics.perTag()).containsKeys("good", "bad");
		assertThat(metrics.perTag().get("good").recallAt5()).isEqualTo(1.0);
		assertThat(metrics.perTag().get("bad").recallAt5()).isZero();
	}

	@Test
	@DisplayName("RagProperties 的默认分块参数互相自洽（下限 < 上限，重叠 < 上限）")
	void defaultChunkingConfigIsSane() {
		RagProperties properties = new RagProperties();
		RagProperties.Chunking.Structural s = properties.getChunking().getStructural();
		assertThat(s.getMinTokens()).isLessThan(s.getMaxTokens());
		assertThat(s.getOverlapTokens()).isLessThan(s.getMaxTokens());
		// 对照组与结构组的块大小应当在同一量级，否则对比结论会混进"块大小也变了"这个变量
		int fixedCharsAsTokens = (int) (properties.getChunking().getFixed().getSize() * 0.75);
		assertThat(fixedCharsAsTokens).isBetween(s.getMaxTokens() / 2, s.getMaxTokens() * 2);
	}

}
