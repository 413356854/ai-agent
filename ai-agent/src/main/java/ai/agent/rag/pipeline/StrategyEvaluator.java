package ai.agent.rag.pipeline;

import ai.agent.rag.chunk.Chunk;
import ai.agent.rag.chunk.DocumentChunker;
import ai.agent.rag.chunk.impl.FixedSizeChunker;
import ai.agent.rag.chunk.impl.StructuralChunker;
import ai.agent.rag.config.RagProperties;
import ai.agent.rag.eval.BudgetAlignment;
import ai.agent.rag.eval.ChunkingComparison;
import ai.agent.rag.eval.EvalCase;
import ai.agent.rag.eval.RetrievalMetrics;
import ai.agent.rag.eval.SectionLocator;
import ai.agent.rag.parse.ParsedDocument;
import ai.agent.rag.retrieve.HybridRetriever;
import ai.agent.rag.retrieve.LexicalRetriever;
import ai.agent.rag.retrieve.RetrievalHit;
import ai.agent.rag.retrieve.Retriever;
import ai.agent.rag.retrieve.VectorRetriever;
import ai.agent.rag.vector.VectorIndex;
import ai.agent.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把<b>一个分块变体</b>跑成一组数字：切块 → 建索引 → 逐条检索 → 算指标。
 *
 * <h2>为什么要从 {@code RagEvaluationService} 里把这段抽出来</h2>
 * 因为"块大小对齐实验"需要跑的变体远不止两个：
 * 对齐后的对照组、关掉面包屑的结构分块、参数扫描的每一档 ——
 * 实测一轮是 <b>9 个变体</b>。如果每个实验各抄一遍这段循环，
 * 就会出现"原始对照跑的是 A 逻辑、对齐实验跑的是 B 逻辑"这种漂移，
 * 而两次实验结果还要放在同一张表里比较 —— <b>探针与实现漂移，结论就作废</b>
 * （台账踩坑库反复出现的一个模式：量出来的差值是"两条代码路径的差异"，
 * 而不是"两个策略的差异"）。
 *
 * <p>所以这里的原则是：<b>变体是数据，流程只有一份</b>。
 * 任何实验都只是换一组 {@link ChunkingVariant} 参数，走路完全相同的那条链路。
 */
@Component
public class StrategyEvaluator {

	/** 预算对齐表的默认档位：覆盖 1000～8000，够看出"召回随预算的拐点"。 */
	public static final List<Integer> DEFAULT_BUDGET_STEPS = List.of(1000, 2000, 3000, 4000, 6000, 8000);

	private static final Logger log = LoggerFactory.getLogger(StrategyEvaluator.class);

	private final RagProperties properties;

	private final TokenEstimator estimator;

	private final VectorIndex vectorIndex;

	public StrategyEvaluator(RagProperties properties, TokenEstimator estimator, VectorIndex vectorIndex) {
		this.properties = properties;
		this.estimator = estimator;
		this.vectorIndex = vectorIndex;
	}

	/**
	 * 一个分块变体：<b>策略 + 参数</b>。
	 *
	 * <p>{@code reportName} 与 {@code fieldDescription} 刻意分开：
	 * 前者是报告里做键的名字（要唯一），后者是给人看的"这一组到底是什么配置"。
	 * 把说明塞进名字里（比如 {@code structural-400-nobreadcrumb}）会让报告列名长得没法读，
	 * 而把名字写短又会让读者不知道这组参数是多少 —— 两者都需要，
	 * 所以是两个字段。
	 *
	 * @param reportName       报告里的名字，必须唯一
	 * @param fieldDescription 这一组的字段说明，例如 {@code structural + max-tokens 400 + 无面包屑}
	 * @param structural       是否用结构分块（false = 固定字数硬切）
	 * @param fixedSize        固定切分的字符数（structural 为 true 时忽略）
	 * @param fixedOverlap     固定切分的重叠字符数（structural 为 true 时忽略）
	 * @param maxTokens        结构分块的 token 上限（structural 为 false 时忽略）
	 * @param breadcrumb       是否把标题面包屑拼进块正文（structural 为 false 时忽略）
	 */
	public record ChunkingVariant(String reportName, String fieldDescription, boolean structural, int fixedSize,
			int fixedOverlap, int maxTokens, boolean breadcrumb) {

		/** 按配置切（500 字硬切）的原始对照组。 */
		public static ChunkingVariant configuredFixed(RagProperties properties) {
			RagProperties.Chunking.Fixed cfg = properties.getChunking().getFixed();
			return new ChunkingVariant(FixedSizeChunker.NAME,
					"fixed-size " + cfg.getSize() + "字/" + cfg.getOverlap() + "重叠", false, cfg.getSize(),
					cfg.getOverlap(), 0, false);
		}

		/** 按配置切（400 token + 面包屑）的原始结构分块。 */
		public static ChunkingVariant configuredStructural(RagProperties properties) {
			RagProperties.Chunking.Structural cfg = properties.getChunking().getStructural();
			return new ChunkingVariant(StructuralChunker.NAME,
					"structural max-tokens=" + cfg.getMaxTokens() + (cfg.isIncludeHeadingBreadcrumb() ? " +面包屑" : " 无面包屑"),
					true, 0, 0, cfg.getMaxTokens(), cfg.isIncludeHeadingBreadcrumb());
		}

	}

	/**
	 * 一个变体跑完的完整结果。
	 *
	 * <p>{@code hitsByCase} 必须带出来：预算对齐要用"同一次检索的完整名次列表"重新归纳，
	 * 而不是从头再检一遍 —— 再检一遍就可能出现"两次结果不完全一致"，
	 * 而两张表放在一起时，没人能看出这个不一致。
	 */
	public record StrategyEvaluation(String strategyName, String fieldDescription, List<Chunk> chunks,
			Map<String, List<RetrievalHit>> hitsByCase, RetrievalMetrics metrics,
			ChunkingComparison.StrategyReport report) {

		public StrategyEvaluation {
			chunks = List.copyOf(chunks);
			hitsByCase = Map.copyOf(hitsByCase);
		}

		/** 投影成报告行（不带召回明细）。 */
		public ChunkingComparison.StrategyReport asReport() {
			return report;
		}

	}

	/**
	 * 检索方式。<b>把"用哪一路检索"做成显式参数</b>，而不是靠注入哪个 bean 决定 ——
	 * 因为实验里必须在同一次运行中对同一种分块跑多路检索，
	 * 才能回答"换了检索方式，分块策略的结论变了吗"。
	 */
	public enum RetrieverProvider {

		/** Lucene BM25（词法）。 */
		LEXICAL("lexical-bm25"),

		/** 稠密向量（当前是离线哈希向量，见 HashingEmbeddingModel 的边界说明）。 */
		VECTOR(VectorRetriever.NAME),

		/** 向量 + BM25，按名次 RRF 融合。 */
		HYBRID(HybridRetriever.NAME);

		private final String reportName;

		RetrieverProvider(String reportName) {
			this.reportName = reportName;
		}

		/** 报告里的列名。**刻意返回常量而不是枚举名**：报告里要看到算法名，不是大写枚举。 */
		public String reportName() {
			return reportName;
		}

	}

	/**
	 * 造一个检索器。
	 *
	 * <p>每次都造新的，因为 {@link Retriever#index} 是<b>重建</b>语义：
	 * 复用同一个实例会让上一组实验的块留在索引里，两次实验就不是同一起点了。
	 * 这正是"索引重建"这个契约存在的意义。
	 */
	public Retriever retrieverFor(RetrieverProvider provider) {
		return switch (provider) {
			case LEXICAL -> new LexicalRetriever();
			case VECTOR -> new VectorRetriever(vectorIndex, properties.getVector());
			case HYBRID -> new HybridRetriever(
					List.of(new LexicalRetriever(), new VectorRetriever(vectorIndex, properties.getVector())));
		};
	}

	/**
	 * 语料切出来的全部块（评测与预览共用这一条路径）。
	 *
	 * <h2>这里有过一个把两个实验都变成废纸的 bug</h2>
	 * 第一版写成了 {@code chunker.chunk(document)} —— 也就是走
	 * {@link DocumentChunker} 接口那个<b>不带参数</b>的方法。
	 * 而变体里的尺寸/重叠是在 {@link ChunkingVariant} 里带着的，
	 * 接口方法只认配置值（500 字）。
	 *
	 * <p>后果不是报错，而是<b>所有变体都切出同一份块</b>：
	 * 尺寸扫描表的 9 行全是"157 块 / 234.8 token"，
	 * 对齐组号称"fixed 121 字"实际跑的却是 500 字 ——
	 * 二分收敛于是永远收敛不了（每条曲线的值都一样，收敛判据失效），
	 * 而且<b>每个数字看起来都像正常结果</b>：块数、平均 token、重复率都自洽。
	 * 唯一的破绽是"9 个不同的尺寸量出完全相同的数字"，
	 * 而那要靠人去盯表格才会发现 —— 这正是结构化断言该防、
	 * 而"功能是否成功"的断言防不住的那一类退化（台账踩坑库 #40 同族）。
	 *
	 * <p>所以这里必须按变体<b>显式分派</b>到带参数的重载；
	 * 并且配套加了一条不变式断言：扫描表里不同尺寸的数值不能全都相同。
	 */
	public List<Chunk> chunkCorpus(List<ParsedDocument> documents, ChunkingVariant variant) {
		DocumentChunker chunker = chunkerFor(variant);
		List<Chunk> chunks = new ArrayList<>();
		for (ParsedDocument document : documents) {
			chunks.addAll(chunkVariant(chunker, document, variant));
		}
		return chunks;
	}

	/** 按变体分派到"带参数"的切分重载（参数在变体里，不在配置里）。 */
	private static List<Chunk> chunkVariant(DocumentChunker chunker, ParsedDocument document,
			ChunkingVariant variant) {
		if (chunker instanceof FixedSizeChunker fixed && !variant.structural()) {
			return fixed.chunk(document, variant.fixedSize(), variant.fixedOverlap());
		}
		return chunker.chunk(document);
	}

	/** 只量规模（块数 / 平均 token / 总 token），不检索 —— 用于尺寸扫描与收敛。 */
	public SizeMeasurement measure(List<ParsedDocument> documents, ChunkingVariant variant, int rawTokens) {
		List<Chunk> chunks = chunkCorpus(documents, variant);
		int total = chunks.stream().mapToInt(Chunk::tokenCount).sum();
		double avg = chunks.isEmpty() ? 0 : total / (double) chunks.size();
		double duplication = rawTokens <= 0 ? 0 : (total / (double) rawTokens) - 1.0;
		return new SizeMeasurement(variant, chunks.size(), round(avg), total, round(duplication));
	}

	/** 规模测量结果（扫描表里的行）。 */
	public record SizeMeasurement(ChunkingVariant variant, int chunkCount, double avgChunkTokens, int totalTokens,
			double duplicationRatio) {
	}

	/**
	 * 跑一个变体的完整检索评测。
	 *
	 * @param documents 语料（外面加载一次、所有变体共用，保证语料逐字节相同）
	 * @param cases     评测集
	 * @param locator   章节定位器。<b>必须由调用方构建并共用同一个</b>：判定基准一变，
	 *                  变体之间就不可比了，而不可比的数字放在同一张表里看起来仍然可比
	 * @param variant   分块变体
	 * @param topK      粗召回条数。<b>小于等于 0 表示用配置值</b> ——
	 *                  这个约定必须在这里落实，不能指望调用方都传对：
	 *                  第一版把 topK 直接透传给了检索器，而
	 *                  {@code Retriever.retrieve(query, 0)} 的契约是<b>返回空列表</b>
	 *                  （"要 0 条"就是不要），于是对齐实验里每一组的召回都是空的、
	 *                  等预算表整张是 0 —— 而"0 条召回"看起来像"这个策略完全不行"，
	 *                  不像一个参数错误。教训：**"-1/0 表示默认值"这种约定
	 *                  必须在最靠近使用点的地方落实一次**，否则每个调用方都要记得，
	 *                  而总有一个会忘。
	 * @param rawTokens 原文总 token（重复率的分母）
	 * @param budgets   预算对齐的档位（空表示不算这一项）
	 */
	public StrategyEvaluation evaluate(List<ParsedDocument> documents, List<EvalCase> cases, SectionLocator locator,
			ChunkingVariant variant, int topK, int rawTokens, List<Integer> budgets) {
		return evaluate(documents, cases, locator, variant, topK, rawTokens, budgets, RetrieverProvider.LEXICAL);
	}

	/**
	 * 同一个分块变体，换一路检索器再跑一遍。
	 *
	 * <h2>为什么"换检索器"要成为评测的一个维度</h2>
	 * 因为分块策略的结论<b>可能依赖于用什么检索</b>：
	 * 硬切把标题和正文切开，词法检索靠"块里有没有那个词"来判断相关性，
	 * 于是硬切受到的伤害里有一部分其实是"词没落在这个块里"；
	 * 换成向量（按整体语义比相似度）之后这个惩罚会变化。
	 * 所以"换分块策略好不好"这个问题，必须在<b>每一种检索方式下</b>各回答一次 ——
	 * 只报一种检索下的结论，等于把一个交叉因素当成了常量。
	 *
	 * @param provider 用哪一路检索（词法 / 向量 / 混合）
	 */
	public StrategyEvaluation evaluate(List<ParsedDocument> documents, List<EvalCase> cases, SectionLocator locator,
			ChunkingVariant variant, int topK, int rawTokens, List<Integer> budgets,
			RetrieverProvider provider) {

		List<Chunk> chunks = chunkCorpus(documents, variant);

		// 每次都用新实例：index() 是"重建"语义，复用会让上一组实验的块留在索引里
		Retriever retriever = retrieverFor(provider);
		retriever.index(chunks);

		int effectiveTopK = topK > 0 ? topK : properties.getRetrieval().getTopK();
		Map<String, List<RetrievalHit>> hitsByCase = new LinkedHashMap<>();
		for (EvalCase evalCase : cases) {
			hitsByCase.put(evalCase.id(), retriever.retrieve(evalCase.question(), effectiveTopK));
		}

		double avgChunkTokens = chunks.isEmpty() ? 0
				: chunks.stream().mapToInt(Chunk::tokenCount).average().orElse(0);
		RetrievalMetrics metrics = RetrievalMetrics.of(variant.reportName(), cases, hitsByCase,
				properties.getRetrieval().getReportAtK(), locator, chunks.size(), avgChunkTokens, budgets);

		ChunkingComparison.StrategyReport report = new ChunkingComparison.StrategyReport(variant.reportName(),
				chunks.size(), round(avgChunkTokens), chunks.stream().mapToInt(Chunk::tokenCount).max().orElse(0),
				metrics, tokenCost(chunks, rawTokens));

		log.info("策略评测完成 strategy={} 检索={} 配置={} chunks={} avgTokens={} recall@5={} mrr={}",
				variant.reportName(), provider.reportName(), variant.fieldDescription(), chunks.size(),
				round(avgChunkTokens), metrics.recallAt().get(5), metrics.mrr());

		return new StrategyEvaluation(variant.reportName(), variant.fieldDescription(), chunks, hitsByCase, metrics,
				report);
	}

	/** 当前向量索引的指纹（诊断端点用：校验 Embedding 漂移）。 */
	public String vectorFingerprint() {
		return vectorIndex.fingerprint();
	}

	/** 按变体参数取（或造）分块器。 */
	public DocumentChunker chunkerFor(ChunkingVariant variant) {
		if (!variant.structural()) {
			return new FixedSizeChunker(properties, estimator);
		}
		RagProperties structuralProperties = propertiesWithMaxTokens(variant.maxTokens(), variant.breadcrumb());
		return new StructuralChunker(structuralProperties, estimator);
	}

	/**
	 * 造一份"只改了这两个字段"的配置副本。
	 *
	 * <h2>为什么不直接改全局配置、跑完再改回来</h2>
	 * 那是探针脚本里用过的一次性写法，有两个问题：
	 * ① 并发下两个实验会互相串味（本项目容器层是虚拟线程，请求是真并发的）；
	 * ② 异常路径上恢复不了 —— 一旦中间抛异常，全局配置就被永久改掉了，
	 * 后面所有实验都跑在错误的参数上，而且<b>不报错</b>。
	 * 造副本还顺带让每个 {@code StructuralChunker} 实例自洽：
	 * 它拿到的配置就是它要用的配置，不存在"跑起来之后配置被谁改了"的窗口。
	 */
	public RagProperties propertiesWithMaxTokens(int maxTokens, boolean breadcrumb) {
		RagProperties copy = new RagProperties();
		RagProperties.Chunking.Structural source = properties.getChunking().getStructural();
		RagProperties.Chunking.Structural target = copy.getChunking().getStructural();
		target.setMaxTokens(maxTokens > 0 ? maxTokens : source.getMaxTokens());
		target.setMinTokens(source.getMinTokens());
		target.setOverlapTokens(source.getOverlapTokens());
		target.setHardSplitThresholdTokens(source.getHardSplitThresholdTokens());
		target.setMaxDuplicationRatio(source.getMaxDuplicationRatio());
		target.setHeadingDepth(source.getHeadingDepth());
		target.setIncludeHeadingBreadcrumb(breadcrumb);
		return copy;
	}

	/**
	 * 方案自身的规模代价。
	 *
	 * <p>{@code rawTokens} 是"整篇原文当一个块"时的 token 数，作为重复率的基准 ——
	 * 用 {@code estimator.estimateMessage} 而不是 {@code estimate}，
	 * 因为块在检索时也是按消息包装算的，两侧量纲必须一致
	 * （量纲不一致的比较没有意义，这也是为什么长度类指标必须写清"算的是什么"）。
	 */
	public ChunkingComparison.TokenCost tokenCost(List<Chunk> chunks, int rawTokens) {
		int total = chunks.stream().mapToInt(Chunk::tokenCount).sum();
		int min = chunks.stream().mapToInt(Chunk::tokenCount).min().orElse(0);
		int undersized = (int) chunks.stream()
				.filter(c -> c.tokenCount() < properties.getChunking().getStructural().getMinTokens())
				.count();
		double duplication = rawTokens <= 0 ? 0 : (total / (double) rawTokens) - 1.0;
		return new ChunkingComparison.TokenCost(total, round(duplication), min, undersized);
	}

	/**
	 * 两个变体的指标差值（百分点，后者减前者）。
	 *
	 * <p>沿用"百分点"而不是倍数：指标本来就落在 0~1 之间，
	 * 从 0.5 到 0.7 说"+40%"比说"+20 个百分点"听着唬人得多，容易被误读。
	 *
	 * <p>键名与 {@code ChunkingComparison.deltas} 保持一致，
	 * 这样界面上的差值表<b>只有一种渲染方式</b> —— 两套渲染迟早会漂移成两种颜色规则。
	 */
	public static Map<String, Double> deltasBetween(ChunkingComparison.StrategyReport reference,
			ChunkingComparison.StrategyReport other) {
		Map<String, Double> rows = new LinkedHashMap<>();
		RetrievalMetrics a = reference.metrics();
		RetrievalMetrics b = other.metrics();

		for (int k : a.recallAt().keySet()) {
			rows.put("recall@" + k, delta(b.recallAt().getOrDefault(k, 0.0), a.recallAt().getOrDefault(k, 0.0)));
		}
		rows.put("mrr", delta(b.mrr(), a.mrr()));
		for (int k : a.contextPrecisionAt().keySet()) {
			rows.put("contextPrecision@" + k,
					delta(b.contextPrecisionAt().getOrDefault(k, 0.0), a.contextPrecisionAt().getOrDefault(k, 0.0)));
		}
		// "量"用普通减法，"率"才乘 100 —— 详见 RagEvaluationService 里那段踩坑注释：
		// 第一版对所有指标统一乘 100，把 topKTokens 的差值印成了 -44320.93（真实 -443.2），
		// 一个把 token 成本夸大 100 倍的对照表会被用来支撑根本不成立的结论
		rows.put("avgChunkTokens", plain(other.avgChunkTokens(), reference.avgChunkTokens()));
		rows.put("chunkCount", plain(other.chunkCount(), reference.chunkCount()));
		rows.put("firstThreeHitsTokens", plain(b.firstThreeHitsTokens(), a.firstThreeHitsTokens()));
		rows.put("topKTokens", plain(b.topKTokens(), a.topKTokens()));
		rows.put("duplicationRatio", plain(other.tokenCost().duplicationRatio(), reference.tokenCost().duplicationRatio()));
		return rows;
	}

	/** 率（0~1）的差值：换成百分点。 */
	private static double delta(double value, double reference) {
		return round((value - reference) * 100.0);
	}

	/** 量（个数 / token 数 / 比值）的差值：普通减法，不乘 100。 */
	private static double plain(double value, double reference) {
		return round(value - reference);
	}

	/** 原文总 token（重复率的分母）。 */
	public int rawCorpusTokens(List<ParsedDocument> documents) {
		int total = 0;
		for (ParsedDocument document : documents) {
			total += estimator.estimateMessage(document.plainText());
		}
		return total;
	}

	public static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

}
