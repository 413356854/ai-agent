package ai.agent.rag.pipeline;

import ai.agent.rag.chunk.Chunk;
import ai.agent.rag.chunk.DocumentChunker;
import ai.agent.rag.chunk.impl.FixedSizeChunker;
import ai.agent.rag.chunk.impl.StructuralChunker;
import ai.agent.rag.config.RagProperties;
import ai.agent.rag.eval.ChunkingComparison;
import ai.agent.rag.eval.EvalCase;
import ai.agent.rag.eval.EvalSetLoader;
import ai.agent.rag.eval.RetrievalMetrics;
import ai.agent.rag.eval.SectionLocator;
import ai.agent.rag.parse.ParsedDocument;
import ai.agent.rag.retrieve.LexicalRetriever;
import ai.agent.rag.retrieve.RetrievalHit;
import ai.agent.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块策略对照评测：<b>把"语义分块更好"变成一句能被追问的话</b>。
 *
 * <h2>它做的一件事</h2>
 * 同一份语料、同一套评测集、同一个检索器，分别用两种分块策略各跑一遍，
 * 输出并排指标。除了"分块策略"之外，所有变量都被按住不动 ——
 * 这是整套设计里最重要的要求。如果两次实验还换了语言模型、换了检索器、
 * 或者换了评测集，那量出来的差值就归因不到任何东西上
 * （台账踩坑库 #35 就是这个错误的一个变体：以为是新做的校验没生效，
 * 其实是被另一个先触发的限制挡住了 —— <b>变量没按住的实验会稳定地给出错误结论</b>）。
 *
 * <h2>为什么连"不检索"的规模代价也要报</h2>
 * 因为分块策略的影响远不止召回率。块多、块碎会带来两个直接后果：
 * ① 索引里的块多了，检索时的候选池被稀释；
 * ② 每块都有重叠，重叠部分是<b>重复计费</b>的 —— 检索时多召回、喂模型时多占窗口。
 * {@link ChunkingComparison.TokenCost#duplicationRatio} 就是这一项。
 * 只看召回率容易得出"块切得越小越好"的结论，而加上 token 成本就未必了。
 *
 * <h2>关于"块数不同会不会让对比不公平"</h2>
 * 会，而且这是本实验最大的一个混杂因素，必须说清：
 * 结构分块的块通常<b>更多</b>（每节独立成块），块多意味着候选多、更容易捞到答案，
 * 所以 Recall 的上升里有一部分只是"块变多了"的功劳。
 * 两个处理方式：
 * <ul>
 *   <li>报告里同时给 <b>MRR</b> 和 <b>token 成本</b> —— 块碎而多时 MRR 与成本会变差，
 *       三个指标一起看才能判断得失；</li>
 *   <li>把 <b>maxTokens 调到接近</b>（本项目的默认参数就是这么选的），
 *       让两种策略的"块大小量级"可比，差异主要来自"切在哪"而不是"切多大"。</li>
 * </ul>
 * 这一点在报告里以 {@code notes} 的形式写给读者，而不是藏在实现里。
 */
@Service
public class RagEvaluationService {

	private static final Logger log = LoggerFactory.getLogger(RagEvaluationService.class);

	/** 对照组的名字。差值一律相对它计算，所以它必须始终排在第一个。 */
	public static final String BASELINE_STRATEGY = FixedSizeChunker.NAME;

	private final CorpusLoader corpusLoader;

	private final EvalSetLoader evalSetLoader;

	private final RagProperties properties;

	private final TokenEstimator estimator;

	private final FixedSizeChunker fixedSizeChunker;

	private final StructuralChunker structuralChunker;

	/**
	 * 唯一的构造器，且刻意<b>不注入那两个分块器</b>。
	 *
	 * <p>为什么：如果把它们当依赖注入，Spring 会因为"这个类依赖两个 DocumentChunker"
	 * 而无法确定装配哪个（两个实现都是 {@code @Component}），
	 * 需要额外用 {@code @Qualifier} 或 bean 名字去指定。
	 * 而这里本来就需要"同时拿到两个、按名字选"，直接 new 出来更直白 ——
	 * 它们都是无状态策略对象，构造代价为零。
	 *
	 * <p>（这也顺带避开了台账踩坑库 #27 那个坑：Spring 只在恰有一个构造器时
	 * 才自动装配，多个构造器会退回"找无参构造器"并报出极具误导性的错。
	 * 这里只留一个公开构造器。）
	 */
	public RagEvaluationService(CorpusLoader corpusLoader, EvalSetLoader evalSetLoader,
			RagProperties properties, TokenEstimator estimator) {
		this.corpusLoader = corpusLoader;
		this.evalSetLoader = evalSetLoader;
		this.properties = properties;
		this.estimator = estimator;
		this.fixedSizeChunker = new FixedSizeChunker(properties, estimator);
		this.structuralChunker = new StructuralChunker(properties, estimator);
	}

	/**
	 * 跑一次完整对照。
	 *
	 * @param strategies 参与对照的策略名（空/null 表示用默认的两个）
	 * @param topK       粗召回条数；小于等于 0 时用配置值
	 */
	public ChunkingComparison compare(List<String> strategies, int topK) {
		long started = System.currentTimeMillis();

		List<String> selected = (strategies == null || strategies.isEmpty())
				? List.of(BASELINE_STRATEGY, StructuralChunker.NAME)
				: strategies;
		// 保证对照组排在第一个：报告里的差值全部相对第一个策略计算
		if (selected.contains(BASELINE_STRATEGY) && !BASELINE_STRATEGY.equals(selected.get(0))) {
			List<String> reordered = new ArrayList<>(selected);
			reordered.remove(BASELINE_STRATEGY);
			reordered.add(0, BASELINE_STRATEGY);
			selected = reordered;
		}

		int effectiveTopK = topK > 0 ? topK : properties.getRetrieval().getTopK();

		CorpusLoader.CorpusLoadResult corpus = corpusLoader.load();
		List<EvalCase> cases = evalSetLoader.load();
		List<ParsedDocument> documents = corpus.documents();
		int rawTokens = rawCorpusTokens(documents);

		// 【两种策略必须共用同一个定位器】判定"这个块算不算命中"的基准一旦不同，
		// 两次实验就不可比了 —— 而不可比的数字放在一张表里，看起来仍然像是可比的
		SectionLocator locator = SectionLocator.build(cases, documents);

		List<ChunkingComparison.StrategyReport> reports = new ArrayList<>();
		// 差值表：指标名 → 策略名 → 相对对照组的百分点差值
		Map<String, Map<String, Double>> deltas = new LinkedHashMap<>();

		for (String strategyName : selected) {
			DocumentChunker chunker = chunkerByName(strategyName);
			List<Chunk> chunks = new ArrayList<>();
			for (ParsedDocument document : documents) {
				chunks.addAll(chunker.chunk(document));
			}

			LexicalRetriever retriever = new LexicalRetriever();
			retriever.index(chunks);

			Map<String, List<RetrievalHit>> hitsByCase = new LinkedHashMap<>();
			for (EvalCase evalCase : cases) {
				hitsByCase.put(evalCase.id(), retriever.retrieve(evalCase.question(), effectiveTopK));
			}

			double avgChunkTokens = chunks.isEmpty() ? 0
					: chunks.stream().mapToInt(Chunk::tokenCount).average().orElse(0);
			RetrievalMetrics metrics = RetrievalMetrics.of(strategyName, cases, hitsByCase,
					properties.getRetrieval().getReportAtK(), locator, chunks.size(), avgChunkTokens);

			reports.add(new ChunkingComparison.StrategyReport(strategyName, chunks.size(),
					round(avgChunkTokens), chunks.stream().mapToInt(Chunk::tokenCount).max().orElse(0),
					metrics, tokenCost(chunks, rawTokens)));

			log.info("策略评测完成 strategy={} chunks={} recall@5={} mrr={}",
					strategyName, chunks.size(), metrics.recallAt().get(5), metrics.mrr());
		}

		buildDeltas(reports, deltas);

		List<String> notes = buildNotes(cases, locator);

		long elapsed = System.currentTimeMillis() - started;
		log.info("对照评测完成 策略数={} 用例数={} 耗时={}ms", reports.size(), cases.size(), elapsed);

		return new ChunkingComparison(properties.getEvaluation().getEvalSetLocation(), cases.size(),
				cases.size() >= properties.getEvaluation().getMinUsableCases(), documents.size(),
				corpus.failed().stream().map(CorpusLoader.FailedDocument::documentId).toList(),
				reports, deltas, notes);
	}

	/** 把语料切成块（供诊断端点查看"到底切成了什么样"）。 */
	public List<Chunk> chunkCorpus(String strategyName) {
		DocumentChunker chunker = chunkerByName(strategyName);
		List<Chunk> chunks = new ArrayList<>();
		for (ParsedDocument document : corpusLoader.load().documents()) {
			chunks.addAll(chunker.chunk(document));
		}
		return chunks;
	}

	/** 按名字取分块器。<b>不认识的策略名直接抛</b>，不要静默退回默认值。 */
	public DocumentChunker chunkerByName(String name) {
		if (name == null || name.isBlank() || FixedSizeChunker.NAME.equals(name)) {
			return fixedSizeChunker;
		}
		if (StructuralChunker.NAME.equals(name)) {
			return structuralChunker;
		}
		throw new IllegalArgumentException("未知的分块策略：" + name
				+ "（可用：" + FixedSizeChunker.NAME + ", " + StructuralChunker.NAME + "）");
	}

	/** 可用的策略名，供诊断端点列出来。 */
	public List<String> availableStrategies() {
		return List.of(FixedSizeChunker.NAME, StructuralChunker.NAME);
	}

	// ------------------------------------------------------------------

	/**
	 * 报告里的注意事项。
	 *
	 * <p>这些不是客套话，每一条都对应一个"如果不写出来就会被误读"的点 ——
	 * 尤其是标注解析失败和样本量不足这两条：它们都会让分数变差，
	 * 而如果不说明，读者只会得出"这个分块策略不行"的错误结论。
	 */
	private List<String> buildNotes(List<EvalCase> cases, SectionLocator locator) {
		List<String> notes = new ArrayList<>();
		notes.add("差值单位为**百分点**（percentage point），且一律相对对照组「" + BASELINE_STRATEGY + "」计算");
		notes.add("相关性判定按「块覆盖了原文哪些块」与「标准答案章节覆盖哪些块」是否有交集来做，"
				+ "因此两种策略在同一个判据下比较 —— 硬切不带标题路径也不会被误判为 0 分");
		notes.add("块数不同会天然影响 Recall（块越多越容易捞到答案），所以必须同时看 MRR 与 token 成本，"
				+ "不能只拿 Recall 一个数下结论");
		notes.add("本阶段检索为**词法（BM25）**：它只认词面，对\"块里有没有正确的词\"极其敏感。"
				+ "这意味着词法下表现好的分块，换成向量检索只会更好 —— 本报告的结论是偏保守的");

		if (!locator.unresolved().isEmpty()) {
			notes.add("⚠ 有 %d 处标注在语料里找不到对应章节，这些用例必然算作未命中（分数偏低不是检索的问题）：%s"
					.formatted(locator.unresolved().size(), String.join("；", locator.unresolved())));
		}
		if (cases.size() < properties.getEvaluation().getMinUsableCases()) {
			notes.add("⚠ 样本量不足：只有 %d 条用例（门槛 %d 条）。路线文档明确要求「别只问两三个问题觉得还行」——"
					.formatted(cases.size(), properties.getEvaluation().getMinUsableCases())
					+ "这份结果可以用来调参，但**不能作为结论引用**");
		}
		return notes;
	}

	private static void buildDeltas(List<ChunkingComparison.StrategyReport> reports,
			Map<String, Map<String, Double>> deltas) {
		if (reports.isEmpty()) {
			return;
		}
		RetrievalMetrics baseline = reports.get(0).metrics();

		// 每个指标一行，便于前端直接渲染成表格
		for (int k : baseline.recallAt().keySet()) {
			deltas.put("recall@" + k, deltaRow(reports, r -> r.metrics().recallAt().getOrDefault(k, 0.0), baseline.recallAt().get(k)));
		}
		deltas.put("mrr", deltaRow(reports, r -> r.metrics().mrr(), baseline.mrr()));
		for (int k : baseline.contextPrecisionAt().keySet()) {
			deltas.put("contextPrecision@" + k, deltaRow(reports,
					r -> r.metrics().contextPrecisionAt().getOrDefault(k, 0.0),
					baseline.contextPrecisionAt().get(k)));
		}
		deltas.put("avgChunkTokens", deltaRow(reports, r -> r.avgChunkTokens(), reports.get(0).avgChunkTokens()));
		deltas.put("chunkCount", deltaRow(reports, r -> r.chunkCount(), reports.get(0).chunkCount()));
		deltas.put("firstThreeHitsTokens", deltaRow(reports,
				r -> r.metrics().firstThreeHitsTokens(), baseline.firstThreeHitsTokens()));
		deltas.put("duplicationRatio", deltaRow(reports, r -> r.tokenCost().duplicationRatio(),
				reports.get(0).tokenCost().duplicationRatio()));
	}

	private static Map<String, Double> deltaRow(List<ChunkingComparison.StrategyReport> reports,
			java.util.function.ToDoubleFunction<ChunkingComparison.StrategyReport> extractor, double baselineValue) {
		Map<String, Double> row = new LinkedHashMap<>();
		for (ChunkingComparison.StrategyReport report : reports) {
			double value = extractor.applyAsDouble(report);
			// 对照组自己的差值恒为 0（不做 0.0 的浮点比较，直接按是否是第一个策略判断）
			double delta = report == reports.get(0) ? 0.0 : (value - baselineValue) * 100.0;
			row.put(report.strategy(), round(delta));
		}
		return row;
	}

	/**
	 * 方案自身的规模代价。
	 *
	 * <p>{@code rawTokens} 是"整篇原文当一个块"时的 token 数，
	 * 作为重复率的基准 —— 用 {@code estimator.estimateMessage} 而不是
	 * {@code estimate}，因为块在检索时也是按消息包装算的，两侧量纲必须一致
	 * （量纲不一致的比较是没有意义的，这也是为什么长度类的指标必须写清"算的是什么"）。
	 */
	private ChunkingComparison.TokenCost tokenCost(List<Chunk> chunks, int rawTokens) {
		int total = chunks.stream().mapToInt(Chunk::tokenCount).sum();
		int min = chunks.stream().mapToInt(Chunk::tokenCount).min().orElse(0);
		int undersized = (int) chunks.stream()
				.filter(c -> c.tokenCount() < properties.getChunking().getStructural().getMinTokens())
				.count();
		double duplication = rawTokens <= 0 ? 0 : (total / (double) rawTokens) - 1.0;
		return new ChunkingComparison.TokenCost(total, round(duplication), min, undersized);
	}

	private int rawCorpusTokens(List<ParsedDocument> documents) {
		int total = 0;
		for (ParsedDocument document : documents) {
			total += estimator.estimateMessage(document.plainText());
		}
		return total;
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

}
