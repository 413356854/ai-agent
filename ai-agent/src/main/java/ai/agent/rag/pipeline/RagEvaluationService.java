package ai.agent.rag.pipeline;

import ai.agent.rag.chunk.Chunk;
import ai.agent.rag.chunk.DocumentChunker;
import ai.agent.rag.chunk.impl.FixedSizeChunker;
import ai.agent.rag.chunk.impl.StructuralChunker;
import ai.agent.rag.config.RagProperties;
import ai.agent.rag.eval.ChunkingComparison;
import ai.agent.rag.eval.EvalCase;
import ai.agent.rag.eval.EvalSetLoader;
import ai.agent.rag.eval.SectionLocator;
import ai.agent.rag.parse.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

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
 * <h2>已知的混杂因素（本轮起由 {@link RagAlignmentService} 处理）</h2>
 * 这个"原始对照"里两组的块大小并不相同（实测 234.8 vs 200.9 token），
 * 而判定标准是"块有没有覆盖答案章节"——<b>块越大越容易碰到答案</b>。
 * 所以原始对照<b>只能</b>得出"结构分块省 token"这一半结论；
 * 想说"策略本身更好或更差"，必须看对齐实验。
 * 这个边界写在报告里（{@link #buildNotes}），而不是只存在于文档中 ——
 * 数字摆在表里，读者会自动脑补方向性结论，不写出来就一定会被误读。
 */
@Service
public class RagEvaluationService {

	private static final Logger log = LoggerFactory.getLogger(RagEvaluationService.class);

	/** 对照组的名字。差值一律相对它计算，所以它必须始终排在第一个。 */
	public static final String BASELINE_STRATEGY = FixedSizeChunker.NAME;

	private final CorpusLoader corpusLoader;

	private final EvalSetLoader evalSetLoader;

	private final RagProperties properties;

	private final StrategyEvaluator evaluator;

	/**
	 * 只留一个公开构造器。
	 *
	 * <p>两个分块器都是<b>无状态策略对象</b>，构造代价为零，所以由
	 * {@link StrategyEvaluator} 按变体参数去造，不注入进容器 ——
	 * 否则 Spring 会因为"依赖两个 DocumentChunker"而无法确定装配哪个，
	 * 需要额外的 {@code @Qualifier}。（这也顺带避开了台账踩坑库 #27 那个坑：
	 * Spring 只在恰有一个构造器时才自动装配，多个构造器会退回"找无参构造器"
	 * 并报出极具误导性的错。）
	 */
	public RagEvaluationService(CorpusLoader corpusLoader, EvalSetLoader evalSetLoader, RagProperties properties,
			StrategyEvaluator evaluator) {
		this.corpusLoader = corpusLoader;
		this.evalSetLoader = evalSetLoader;
		this.properties = properties;
		this.evaluator = evaluator;
	}

	/**
	 * 跑一次原始对照。
	 *
	 * <p>注意它<b>不再</b>是"全部结论的来源"：块大小没对齐，所以它只能说
	 * "谁更省 token"。要回答"策略本身好不好"用 {@link RagAlignmentService}。
	 *
	 * @param strategies 参与对照的策略名（空/null 表示用默认的两个）
	 * @param topK       粗召回条数；小于等于 0 时用配置值
	 */
	public ChunkingComparison compare(List<String> strategies, int topK) {
		return compare(strategies, topK, List.of());
	}

	/**
	 * 原始对照（可选带预算对齐档位）。
	 *
	 * @param budgets token 预算档位；空表示不算"等成本召回"这一项。
	 *                它是<b>额外报告</b>的东西，不影响任何按名次算的指标
	 */
	public ChunkingComparison compare(List<String> strategies, int topK, List<Integer> budgets) {
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
		int rawTokens = evaluator.rawCorpusTokens(documents);

		// 【两种策略必须共用同一个定位器】判定"这个块算不算命中"的基准一旦不同，
		// 两次实验就不可比了 —— 而不可比的数字放在一张表里，看起来仍然像是可比的
		SectionLocator locator = SectionLocator.build(cases, documents);

		List<ChunkingComparison.StrategyReport> reports = new ArrayList<>();
		for (String strategyName : selected) {
			StrategyEvaluator.ChunkingVariant variant = variantByName(strategyName);
			reports.add(evaluator.evaluate(documents, cases, locator, variant, effectiveTopK, rawTokens, budgets)
					.asReport());
		}

		Map<String, Map<String, Double>> deltas = new LinkedHashMap<>();
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
		return evaluator.chunkCorpus(corpusLoader.load().documents(), variantByName(strategyName));
	}

	/** 按名字取分块器。<b>不认识的策略名直接抛</b>，不要静默退回默认值。 */
	public DocumentChunker chunkerByName(String name) {
		return evaluator.chunkerFor(variantByName(name));
	}

	/** 名字 → 变体（用配置里的参数）。 */
	private StrategyEvaluator.ChunkingVariant variantByName(String name) {
		if (name == null || name.isBlank() || FixedSizeChunker.NAME.equals(name)) {
			return StrategyEvaluator.ChunkingVariant.configuredFixed(properties);
		}
		if (StructuralChunker.NAME.equals(name)) {
			return StrategyEvaluator.ChunkingVariant.configuredStructural(properties);
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
		// 这一条是本轮新增的：原始对照的边界必须写在报告里，
		// 否则 0.7674 与 0.8140 摆在一起，任何人都会自行脑补一个方向性结论
		notes.add("⚠ **这份原始对照不能用来判断\"哪个分块策略更好\"**：两组的平均块大小不同"
				+ "（块越大越容易覆盖答案所在章节），所以 Recall 的差异里混着块大小的差异。"
				+ "要下这个结论请看**块大小对齐实验**（/api/diag/rag/align）");

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
		ChunkingComparison.StrategyReport baseline = reports.get(0);

		// 每个指标一行，便于前端直接渲染成表格
		for (int k : baseline.metrics().recallAt().keySet()) {
			deltas.put("recall@" + k,
					deltaRow(reports, r -> r.metrics().recallAt().getOrDefault(k, 0.0),
							baseline.metrics().recallAt().get(k)));
		}
		deltas.put("mrr", deltaRow(reports, r -> r.metrics().mrr(), baseline.metrics().mrr()));
		for (int k : baseline.metrics().contextPrecisionAt().keySet()) {
			deltas.put("contextPrecision@" + k, deltaRow(reports,
					r -> r.metrics().contextPrecisionAt().getOrDefault(k, 0.0),
					baseline.metrics().contextPrecisionAt().get(k)));
		}
		// 【下面是"量"不是"率"，差值必须是普通减法，不能乘 100】
		// 这里踩过一次，而且是报告类 bug 里最坏的一种 —— 数字看起来仍然"像那么回事"：
		// 第一版对**所有**指标统一乘 100（因为 Recall/MRR 这类率要换成百分点），
		// 于是 topKTokens 的差值印成了 -44320.93（真实差值是 -443.2 token），
		// avgChunkTokens 印成 -3392.18（真实是 -33.9），chunkCount 印成 7800（真实是 78）。
		// 一个把 token 成本夸大了 100 倍的对照表，比没有对照表更糟：
		// "结构分块省了很多钱"会被用来支撑一个根本不成立的结论。
		// 通用规矩：**差值单位跟着指标的量纲走，而量纲必须写进表头**。
		deltas.put("avgChunkTokens", plainDeltaRow(reports, ChunkingComparison.StrategyReport::avgChunkTokens,
				baseline.avgChunkTokens()));
		deltas.put("chunkCount", plainDeltaRow(reports, r -> r.chunkCount(), baseline.chunkCount()));
		deltas.put("firstThreeHitsTokens", plainDeltaRow(reports, r -> r.metrics().firstThreeHitsTokens(),
				baseline.metrics().firstThreeHitsTokens()));
		deltas.put("topKTokens", plainDeltaRow(reports, r -> r.metrics().topKTokens(),
				baseline.metrics().topKTokens()));
		deltas.put("duplicationRatio", plainDeltaRow(reports, r -> r.tokenCost().duplicationRatio(),
				baseline.tokenCost().duplicationRatio()));
	}

	/** 量纲是"率"的指标（0~1）：差值 × 100 换成百分点。 */
	private static Map<String, Double> deltaRow(List<ChunkingComparison.StrategyReport> reports,
			ToDoubleFunction<ChunkingComparison.StrategyReport> extractor, double baselineValue) {
		Map<String, Double> row = new LinkedHashMap<>();
		for (ChunkingComparison.StrategyReport report : reports) {
			double value = extractor.applyAsDouble(report);
			// 对照组自己的差值恒为 0（不做 0.0 的浮点比较，直接按是否是第一个策略判断）
			double delta = report == reports.get(0) ? 0.0 : (value - baselineValue) * 100.0;
			row.put(report.strategy(), round(delta));
		}
		return row;
	}

	/** 量纲是"个数 / token 数 / 比值"的指标：差值就是普通减法（不乘 100）。 */
	private static Map<String, Double> plainDeltaRow(List<ChunkingComparison.StrategyReport> reports,
			ToDoubleFunction<ChunkingComparison.StrategyReport> extractor, double baselineValue) {
		Map<String, Double> row = new LinkedHashMap<>();
		for (ChunkingComparison.StrategyReport report : reports) {
			double value = extractor.applyAsDouble(report);
			double delta = report == reports.get(0) ? 0.0 : (value - baselineValue);
			row.put(report.strategy(), round(delta));
		}
		return row;
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

}
