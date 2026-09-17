package ai.agent.service.rag.pipeline;

import ai.agent.service.impl.rag.chunk.FixedSizeChunker;
import ai.agent.service.impl.rag.chunk.StructuralChunker;
import ai.agent.config.rag.RagProperties;
import ai.agent.entity.rag.eval.AlignmentExperiment;
import ai.agent.entity.rag.eval.BudgetAlignment;
import ai.agent.entity.rag.eval.ChunkingComparison;
import ai.agent.entity.rag.eval.EvalCase;
import ai.agent.service.rag.eval.EvalSetLoader;
import ai.agent.service.rag.eval.SectionLocator;
import ai.agent.entity.rag.parse.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 块大小对齐实验：<b>消掉"块越大越容易覆盖答案"这个混杂因素</b>。
 *
 * <h2>为什么这件事必须做，而且必须在接向量检索之前做</h2>
 * 原始对照结论是"换分块策略没有提升召回率，省的是 token"——
 * 但这句话只有后半截成立。因为两组的<b>平均块大小不同</b>
 * （实测 234.8 vs 200.9 token），而判定标准是"检索到的块有没有覆盖答案所在章节"，
 * <b>块越大越容易覆盖到答案</b>。所以 Recall 那 4.66 个百分点的差距里，
 * 混着"块更大"的功劳，无法归因到"切点选在哪"。
 *
 * <p>顺序上的理由同样重要：如果先接向量检索，那个结论会变成
 * "向量检索下结构分块 Recall 还是略低"——同一个混杂因素还在，
 * 等于把同一个坑踩两遍，而且第二次更难发现（多了个变量在旁边）。
 *
 * <h2>它跑的 4 个组</h2>
 * <table border="1">
 * <caption>对齐实验的组</caption>
 * <tr><th>组</th><th>配置</th><th>它在证什么</th></tr>
 * <tr><td>{@code fixed-aligned-163}</td><td>硬切，字符数调到与"无面包屑结构分块"同平均大小</td>
 *     <td>轴一的参照方</td></tr>
 * <tr><td>{@code structural-400-nobc}</td><td>结构分块，关掉面包屑</td>
 *     <td><b>最干净的一组</b>：块大小对齐 + 正文对齐 = 唯一的差异只剩"切在哪"</td></tr>
 * <tr><td>{@code fixed-aligned-201}</td><td>硬切，字符数调到与默认结构分块同平均大小</td>
 *     <td>默认配置下的对齐参照方</td></tr>
 * <tr><td>{@code structural-400}</td><td>结构分块，默认（带面包屑）</td>
 *     <td>与上一组配对，回答"默认配置到底赢没赢"</td></tr>
 * </table>
 *
 * <h2>"对齐"是近似的，所以要报差多少</h2>
 * 字符数与 token 数不是线性关系（中英混排、面包屑、块边界都会影响），
 * 而且硬切的块大小有天然抖动（实测最大 328 / 最小 121）。
 * 所以这里用<b>二分收敛</b>找一个字符数，让实测平均块 token 落在目标 ±{@value #ALIGN_TOLERANCE_TOKENS}
 * 之内，并把<b>实际差了多少</b>{@code alignedGapTokens} 一起报出来 ——
 * 不报的话，"已对齐"就成了一句无法检验的声明。
 */
@Service
public class RagAlignmentService {

	private static final Logger log = LoggerFactory.getLogger(RagAlignmentService.class);

	/** 对齐维度名（报告与界面共用一套字符串，避免两处各写一份慢慢漂移）。 */
	public static final String DIM_AVG_CHUNK_TOKENS = "平均块大小";

	public static final String DIM_TOKEN_BUDGET = "等 token 预算";

	/** 对齐容差（token）：平均块 token 与目标的距离小于它就认为收敛。 */
	public static final double ALIGN_TOLERANCE_TOKENS = 0.5;

	/** 二分细化最多探几次。每探一次就减半区间，5 次足够把区间压到 3% 以内。 */
	private static final int MAX_REFINEMENT_PROBES = 6;

	/** 扫描区间相对"外推估计值"的下界比例。 */
	private static final double LOW_RATIO = 0.75;

	/** 扫描区间相对"外推估计值"的上界比例。 */
	private static final double HIGH_RATIO = 1.25;

	/** 对齐后的对照组名字。 */
	private static final String ALIGNED_FIXED = "fixed-aligned";

	private static final String STRUCTURAL_NO_BREADCRUMB = "structural-nobc";

	/** 预算档位默认值：覆盖"进上下文 3–6 条"（约 1000–1500）到"粗召回 30–100 条"（约 6000–8000）。 */
	public static final List<Integer> DEFAULT_BUDGETS = List.of(1000, 2000, 3000, 4000, 6000, 8000);

	/**
	 * 对照里要跑的检索方式。
	 *
	 * <p>把"检索方式"当成实验的一个维度而不是常量：分块策略的结论<b>可能依赖用什么检索</b>，
	 * 只报一种检索下的结论就是把这个交叉因素当成了常量。
	 * 三路的顺序固定为 词法 → 向量 → 混合，报告里的列序才稳定。
	 */
	public static final List<StrategyEvaluator.RetrieverProvider> RETRIEVAL_METHODS = List.of(
			StrategyEvaluator.RetrieverProvider.LEXICAL, StrategyEvaluator.RetrieverProvider.VECTOR,
			StrategyEvaluator.RetrieverProvider.HYBRID);

	private final CorpusLoader corpusLoader;

	private final EvalSetLoader evalSetLoader;

	private final RagProperties properties;

	private final StrategyEvaluator evaluator;

	public RagAlignmentService(CorpusLoader corpusLoader, EvalSetLoader evalSetLoader, RagProperties properties,
			StrategyEvaluator evaluator) {
		this.corpusLoader = corpusLoader;
		this.evalSetLoader = evalSetLoader;
		this.properties = properties;
		this.evaluator = evaluator;
	}

	/** 跑一次完整对齐实验（用默认预算档位）。 */
	public AlignmentExperiment run(List<Integer> budgets, int sweepPoints) {
		long started = System.currentTimeMillis();

		List<Integer> budgetSteps = (budgets == null || budgets.isEmpty()) ? DEFAULT_BUDGETS : budgets.stream()
				.distinct().sorted().toList();
		int points = sweepPoints > 0 ? sweepPoints : 9;

		CorpusLoader.CorpusLoadResult corpus = corpusLoader.load();
		List<EvalCase> cases = evalSetLoader.load();
		List<ParsedDocument> documents = corpus.documents();
		int rawTokens = evaluator.rawCorpusTokens(documents);

		// 两条轴、四组对照，共用同一个判定基准（这一步不能省：判定基准一变就不可比了）
		SectionLocator locator = SectionLocator.build(cases, documents);

		// ---- 第 1 步：量出两组的"靶子" -------------------------------------
		StrategyEvaluator.ChunkingVariant plainStructural = StrategyEvaluator.ChunkingVariant
				.configuredStructural(properties);
		StrategyEvaluator.ChunkingVariant noBreadcrumbStructural = new StrategyEvaluator.ChunkingVariant(
				STRUCTURAL_NO_BREADCRUMB, "structural max-tokens=" + plainStructural.maxTokens() + " 无面包屑", true, 0, 0,
				plainStructural.maxTokens(), false);

		StrategyEvaluator.SizeMeasurement plainSize = evaluator.measure(documents, plainStructural, rawTokens);
		StrategyEvaluator.SizeMeasurement noBreadcrumbSize = evaluator.measure(documents, noBreadcrumbStructural,
				rawTokens);

		log.info("对齐靶子：structural 平均块={} token（块数 {}），关掉面包屑后={} token（块数 {}）",
				plainSize.avgChunkTokens(), plainSize.chunkCount(), noBreadcrumbSize.avgChunkTokens(),
				noBreadcrumbSize.chunkCount());

		// ---- 第 2 步：把对照组调到与实验组一样大 ---------------------------
		// 锚只量一次：它是"这批语料上 字符 ↔ token"的实测比值，两次对齐共用
		StrategyEvaluator.ChunkingVariant anchored = StrategyEvaluator.ChunkingVariant.configuredFixed(properties);
		StrategyEvaluator.SizeMeasurement anchor = evaluator.measure(documents, anchored, rawTokens);
		int anchorSize = Math.max(1, anchored.fixedSize());
		double anchorAvgTokens = Math.max(1.0, anchor.avgChunkTokens());
		log.info("对齐锚点：{}字 → 实测平均 {} token（每字符 {}/token，后续区间由它外推）", anchorSize,
				anchorAvgTokens, round(anchorAvgTokens / anchorSize));

		AlignResult noBreadcrumbAlign = alignFixed(documents, rawTokens, noBreadcrumbSize.avgChunkTokens(), points,
				anchorSize, anchorAvgTokens);
		AlignResult plainAlign = alignFixed(documents, rawTokens, plainSize.avgChunkTokens(), points, anchorSize,
				anchorAvgTokens);

		// ---- 第 3 步：把各组跑成数字（每一路检索各跑一遍）------------------
		// 注意 fixed-500 也一并跑：它是原始对照的参照方，而且它同时充当
		// "对齐之前 / 对齐之后"这个对照的锚点 —— 只报表里的两组会让人看不懂差值从哪来
		//
		// 【为什么每路检索都要重跑分组】VectorRetriever 内部的索引是有状态的
		// （index() 是重建语义），所以同一个变体在词法/向量/混合下必须各建一次索引。
		// 想"先切块再复用块"来省时间是错的：ArrayIndexOutOfBounds 不会发生，
		// 但共享的索引会被后一次 index() 覆盖，前一路的结果就被悄悄换掉了。
		StrategyEvaluator.ChunkingVariant configuredFixed = StrategyEvaluator.ChunkingVariant
				.configuredFixed(properties);

		// 变体名单（键 = 报告名），与检索方式无关 —— 保证三路检索比的是**同一批**变体
		Map<String, StrategyEvaluator.ChunkingVariant> variants = new LinkedHashMap<>();
		variants.put(alignedKey(noBreadcrumbSize.avgChunkTokens()),
				variant(alignedKey(noBreadcrumbSize.avgChunkTokens()), alignedFixedDescription(noBreadcrumbAlign),
						noBreadcrumbAlign.size()));
		variants.put(STRUCTURAL_NO_BREADCRUMB, noBreadcrumbStructural);
		variants.put(alignedKey(plainSize.avgChunkTokens()),
				variant(alignedKey(plainSize.avgChunkTokens()), alignedFixedDescription(plainAlign), plainAlign.size()));
		variants.put(StructuralChunker.NAME, plainStructural);
		variants.put(FixedSizeChunker.NAME, configuredFixed);

		// 检索方式 → 变体名 → 该次评测结果
		Map<StrategyEvaluator.RetrieverProvider, Map<String, StrategyEvaluator.StrategyEvaluation>> byProvider =
				new LinkedHashMap<>();
		for (StrategyEvaluator.RetrieverProvider provider : RETRIEVAL_METHODS) {
			Map<String, StrategyEvaluator.StrategyEvaluation> perVariant = new LinkedHashMap<>();
			for (Map.Entry<String, StrategyEvaluator.ChunkingVariant> entry : variants.entrySet()) {
				// 预算对齐只在词法那一路算：它是"检索后的成本归纳"，
				// 三路都算会让报告里出现三张几乎一样的预算表，反而看不出差异
				List<Integer> budgetsForRun = provider == StrategyEvaluator.RetrieverProvider.LEXICAL ? budgetSteps
						: List.of();
				perVariant.put(entry.getKey(), evaluator.evaluate(documents, cases, locator, entry.getValue(),
						TOP_K_FROM_CONFIG, rawTokens, budgetsForRun, provider));
			}
			byProvider.put(provider, perVariant);
		}

		Map<String, StrategyEvaluator.StrategyEvaluation> evaluations = byProvider
				.get(StrategyEvaluator.RetrieverProvider.LEXICAL);

		// ---- 第 4 步：并排对照（每个"对齐字段" × 每一路检索）--------------		// 这是本轮把"检索方式"变成实验维度的地方：同一个对齐组，
		// 在词法 / 向量 / 混合下各比一次。分块策略的结论会不会随检索方式变化，
		// 只能这样看出来 —— 只报一种检索就是把这个交叉因素当成了常量
		List<AlignmentExperiment.PairComparison> pairs = new ArrayList<>();
		for (StrategyEvaluator.RetrieverProvider provider : RETRIEVAL_METHODS) {
			Map<String, StrategyEvaluator.StrategyEvaluation> perVariant = byProvider.get(provider);
			pairs.add(pair("平均块大小 == %.1f token（结构分块关掉面包屑，正文也对齐）"
					.formatted(noBreadcrumbSize.avgChunkTokens()) + " ｜ 检索=" + provider.reportName(),
					DIM_AVG_CHUNK_TOKENS, perVariant.get(alignedKey(noBreadcrumbSize.avgChunkTokens())),
					perVariant.get(STRUCTURAL_NO_BREADCRUMB),
					List.of("这是「最干净的一组」：块大小对齐、正文也对齐（关掉面包屑），"
							+ "于是唯一的差异只剩「切点选在哪」",
							("对照组的块大小是按实测收敛出来的，与目标差 %.2f token —— 不是完全相等")
									.formatted(noBreadcrumbAlign.measuredAvg()
											- noBreadcrumbSize.avgChunkTokens()))));
		}
		for (StrategyEvaluator.RetrieverProvider provider : RETRIEVAL_METHODS) {
			Map<String, StrategyEvaluator.StrategyEvaluation> perVariant = byProvider.get(provider);
			pairs.add(pair("平均块大小 == %.1f token（结构分块默认带面包屑）".formatted(plainSize.avgChunkTokens())
					+ " ｜ 检索=" + provider.reportName(), DIM_AVG_CHUNK_TOKENS,
					perVariant.get(alignedKey(plainSize.avgChunkTokens())), perVariant.get(StructuralChunker.NAME),
					List.of("这一组对齐的是「渲染后」的块大小：结构分块的正文仍然比对照组小"
							+ "（差的就是每块拼一遍的标题路径），所以它不能单独用来判断「切点」的优劣 ——"
							+ "判断切点请用关掉面包屑的那一组")));
		}
		// 块大小效应本身：只在词法下跑一组当锚点（换检索方式比的是"同一个效应换度量后还在不在"，
		// 三个都报会稀释主线）
		pairs.add(pair("块更大的效应有多大（结构分块无面包屑 vs 与默认组同大小的硬切） ｜ 检索="
				+ StrategyEvaluator.RetrieverProvider.LEXICAL.reportName(), DIM_TOKEN_BUDGET,
				evaluations.get(STRUCTURAL_NO_BREADCRUMB), evaluations.get(alignedKey(plainSize.avgChunkTokens())),
				List.of(("这一组刻意「不」对齐块大小（差着约 %.0f token），用来看"
						+ "「块更大 → 更容易覆盖答案」这个效应本身有多大")
								.formatted(plainSize.avgChunkTokens() - noBreadcrumbSize.avgChunkTokens()))));

		// ---- 第 5 步：等预算对照 ------------------------------------------
		List<String> budgetLabels = List.of(alignedKey(noBreadcrumbSize.avgChunkTokens()), STRUCTURAL_NO_BREADCRUMB,
				alignedKey(plainSize.avgChunkTokens()), StructuralChunker.NAME, FixedSizeChunker.NAME);
		// 预算 → 该档位下每个组的指标。做成"按档位切"而不是"按组切"，
		// 是为了前端能一行一个档位地渲染成表格（一行之内横向对比各组的召回与花费）
		// 预算 → 该档位下每个组的指标。做成"按档位索引"而不是"按组索引"，
		// 是为了前端能一行一个档位地渲染成表格（一行之内横向对比各组的召回与花费）
		Map<Integer, Map<String, BudgetAlignment>> budgetRows = new LinkedHashMap<>();
		for (int budget : budgetSteps) {
			Map<String, BudgetAlignment> row = new LinkedHashMap<>();
			for (String label : budgetLabels) {
				BudgetAlignment alignment = evaluations.get(label).metrics().budgetAlignment().get(budget);
				if (alignment != null) {
					row.put(label, alignment);
				}
			}
			budgetRows.put(budget, row);
		}

		List<String> notes = buildNotes(cases, locator, noBreadcrumbAlign, noBreadcrumbSize, plainSize, budgetSteps);

		long elapsed = System.currentTimeMillis() - started;
		log.info("对齐实验完成 用例数={} 变体数={} 检索路数={} 预算档={} 耗时={}ms", cases.size(), variants.size(),
				RETRIEVAL_METHODS.size(), budgetSteps.size(), elapsed);

		return new AlignmentExperiment(properties.getEvaluation().getEvalSetLocation(), cases.size(),
				cases.size() >= properties.getEvaluation().getMinUsableCases(), documents.size(),
				corpus.failed().stream().map(CorpusLoader.FailedDocument::documentId).toList(), DIM_AVG_CHUNK_TOKENS,
				noBreadcrumbSize.avgChunkTokens(), noBreadcrumbAlign.sweepRange(), noBreadcrumbAlign.sweep(),
				noBreadcrumbAlign.size(), noBreadcrumbAlign.measuredAvg(),
				round(noBreadcrumbAlign.measuredAvg() - noBreadcrumbSize.avgChunkTokens()),
				// 只报**被考察那一组**的探针数。第一版写成两组相加（noBreadcrumb + plain），
				// 那是个"把两次独立搜索的代价加在一起"的错误 —— 数字本身没错，
				// 但它回答的不是报告里那个问题（"这一组的对齐是怎么收敛的"），
				// 而且放大后会让读者以为收敛很贵。可复现性要求每个数字都对得上它的标题
				noBreadcrumbAlign.probes(), pairs, budgetSteps, budgetLabels, budgetRows,
				RETRIEVAL_METHODS.stream().map(StrategyEvaluator.RetrieverProvider::reportName).toList(),
				embeddingModelName(), embeddingDimensions(), evaluator.vectorFingerprint(), notes);
	}

	/** 向量那一路用的模型名（用于在报告里标明"这个向量是哪来的"）。 */
	private String embeddingModelName() {
		return ai.agent.service.impl.rag.vector.HashingEmbeddingModel.NAME;
	}

	private int embeddingDimensions() {
		return properties.getVector().getHash().getDimensions();
	}

	// ------------------------------------------------------------------
	//  对齐：二分收敛
	// ------------------------------------------------------------------

	/**
	 * 扫描 + 二分细化，找一个字符数让实测平均块 token 最接近目标。
	 *
	 * <h2>为什么不能一次算出来（以及第一版算错了什么）</h2>
	 * "字符数 → token 数"不是线性关系：中英混排比例、块边界的标点、
	 * 每块固定的消息包装开销，都会让换算系数随大小变化。
	 *
	 * <p><b>第一版把扫描区间写成了 {@code target × [0.6, 1.4]}</b>（想的是
	 * "目标 203 token，那 121～284 字总够了吧"）。那是把<b>token 数当成了字符数</b>——
	 * 这批语料实测约 <b>0.53 token/字符</b>（每块还有约 40 token 的固定包装开销），
	 * 所以想拿 203 token 需要约 **380 个字符**，落在这个区间外面。
	 * 后果：整个搜索区间都在目标值以下，二分一路往右边界走，
	 * 最后选了个 304 字、实测 144.6 token 的点 —— <b>与目标差 50 token（25%）</b>，
	 * 而"块大小已对齐"这句话就成了假的。
	 *
	 * <p>修法不是"把区间调大一点"（那是碰运气），而是<b>拿一个实测点当锚</b>：
	 * 先量一次"配置的 500 字切成多少 token"，用它的比值外推目标尺寸。
	 * 这一步只花一次测量，却把区间从"猜"变成了"算"。
	 * 外推仍然不是精确的（比值本身随大小变化），所以后面还有二分；
	 * 但二分只在<b>正确的区间</b>里收敛，这才是它的价值。
	 */
	private AlignResult alignFixed(List<ParsedDocument> documents, int rawTokens, double target, int sweepPoints,
			int anchorSize, double anchorAvgTokens) {
		// 锚：由调用方传入（run() 里量一次、两次对齐共用）。
		// 做成实例字段会引入可变共享状态 —— 本应用的容器层是虚拟线程，
		// 请求是真并发的，一个"只在第一次算"的字段在并发下会串味
		int estimate = (int) Math.round(anchorSize * (target / anchorAvgTokens));
		int lo = Math.max(1, (int) Math.round(estimate * LOW_RATIO));
		int hi = Math.max(lo + 1, (int) Math.round(estimate * HIGH_RATIO));
		int step = Math.max(1, (hi - lo) / Math.max(1, sweepPoints - 1));

		List<AlignmentExperiment.SweepRow> sweep = new ArrayList<>();
		int probes = 0;
		int bestSize = lo;
		double bestGap = Double.MAX_VALUE;

		for (int i = 0; i < sweepPoints; i++) {
			int size = lo + step * i;
			StrategyEvaluator.SizeMeasurement measurement = evaluator.measure(documents,
					variant(ALIGNED_FIXED, "fixed " + size + "字", size), rawTokens);
			probes++;
			sweep.add(new AlignmentExperiment.SweepRow(size, measurement.chunkCount(), measurement.avgChunkTokens(),
					measurement.totalTokens(), measurement.duplicationRatio(),
					round(measurement.avgChunkTokens() - target)));
			double gap = Math.abs(measurement.avgChunkTokens() - target);
			if (gap < bestGap) {
				bestGap = gap;
				bestSize = size;
			}
		}

		// 【边界扩张】如果最优点落在扫描区间的两端，说明真最优在区间外面 ——
		// 外推的比值本身有误差，这一步就是给它兜底。
		// 不做这一步的话，区间偏了就会稳定地选中一个偏离目标的点，
		// 而报告里"已对齐"照样写着（第一版就是这样，差 50 token）
		if (bestSize <= lo || bestSize >= lo + step * (sweepPoints - 1)) {
			int direction = bestSize <= lo ? -1 : 1;
			for (int attempt = 0; attempt < 6 && bestGap > ALIGN_TOLERANCE_TOKENS; attempt++) {
				int candidate = bestSize + direction * step * (attempt + 1);
				if (candidate < 1) {
					break;
				}
				StrategyEvaluator.SizeMeasurement measurement = evaluator.measure(documents,
						variant(ALIGNED_FIXED, "fixed " + candidate + "字", candidate), rawTokens);
				probes++;
				double gap = Math.abs(measurement.avgChunkTokens() - target);
				if (gap < bestGap) {
					bestGap = gap;
					bestSize = candidate;
				}
				else {
					break;
				}
			}
		}

		// 二分：在扫描发现的最优点附近继续压区间。
		// 收敛判据用**实测平均 token**（而不是字符数），因为要控的就是 token。
		//
		// 【踩过一次：判据不能只看"当前探针落在容差内"】
		// 第一版这么写了：
		//     measured = 本次探针的值; if (|measured - target| <= 容差) break;
		// 问题在于最终选中的是**全程 gap 最小的那个点**，而它可能来自粗扫描 ——
		// 粗扫描的点是离散的，未必落在容差内。于是"本次探针恰好命中"就提前 break，
		// 把区间留得很宽，最后仍选了个差 55.5 token 的扫描点回去。
		// 正确判据是"**已找到的最优点**是否落在容差内"（bestGap），
		// 而不是"这次探的那一点"。这两个条件看着只差一个变量，结果差一个数量级。
		int searchLo = Math.max(1, bestSize - step);
		int searchHi = bestSize + step;
		for (int attempt = 0; attempt < MAX_REFINEMENT_PROBES; attempt++) {
			if (bestGap <= ALIGN_TOLERANCE_TOKENS) {
				break;
			}
			int probeSize = (searchLo + searchHi) / 2;
			if (probeSize == searchLo || probeSize == searchHi) {
				break;
			}
			StrategyEvaluator.SizeMeasurement measurement = evaluator.measure(documents,
					variant(ALIGNED_FIXED, "fixed " + probeSize + "字", probeSize), rawTokens);
			probes++;
			double measured = measurement.avgChunkTokens();
			double gap = Math.abs(measured - target);
			if (gap < bestGap) {
				bestGap = gap;
				bestSize = probeSize;
			}
			if (measured < target) {
				searchLo = probeSize;
			}
			else {
				searchHi = probeSize;
			}
		}

		// 最后按选中的尺寸正式量一次：二分过程中可能没落到 bestSize 上，
		// 而报告里的数字必须是**这个尺寸自己的**实测值，不能是搜索过程中的某个中间值
		StrategyEvaluator.SizeMeasurement finalMeasurement = evaluator.measure(documents,
				variant(ALIGNED_FIXED, "fixed " + bestSize + "字", bestSize), rawTokens);
		log.info("对齐收敛 target={} token → size={}字 实测平均={} token 差={} token 扫描点={} 探针={}",
				round(target), bestSize, finalMeasurement.avgChunkTokens(),
				round(finalMeasurement.avgChunkTokens() - target), sweep.size(), probes);
		return new AlignResult(bestSize, finalMeasurement.avgChunkTokens(), probes, List.of(lo, hi), sweep);
	}

	/** 对齐结果（内部用）。 */
	private record AlignResult(int size, double measuredAvg, int probes, List<Integer> sweepRange,
			List<AlignmentExperiment.SweepRow> sweep) {
	}

	/** 取"与目标最接近"的那个对齐组：按实测平均块 token 与目标的距离选。 */
	private static String alignedKey(double target) {
		return ALIGNED_FIXED + "-" + Math.round(target);
	}

	/**
	 * 造一个"调好尺寸的硬切"变体，重叠固定为块大小的 10%。
	 *
	 * <p>不沿用配置里的固定 50 字：块变小之后 50 字重叠的占比会翻倍
	 * （500 字时 10%，340 字时 15%），于是"对齐块大小"这个动作顺带把
	 * "重叠占比"也改了 —— 又一个没被按住的变量。
	 * 固定成 10% 才是与原始对照同一条设计原则（原始对照的 50/500 正好是 10%）。
	 */
	private static StrategyEvaluator.ChunkingVariant variant(String reportName, String description, int fixedSize) {
		return new StrategyEvaluator.ChunkingVariant(reportName, description, false, fixedSize,
				Math.max(1, fixedSize / 10), 0, false);
	}

	/**
	 * 粗召回条数：<b>0 = 用配置里的 top-k</b>。
	 *
	 * <p>做成常量而不是到处写 0：这个约定在 {@link StrategyEvaluator#evaluate} 里落实
	 * （它会把 0 换成配置值），而<b>写死在调用点上会让"0 是什么意思"无处可查</b>。
	 * 这里踩过一次：早期版本直接把 0 透传给检索器，而 `retrieve(query, 0)`
	 * 的契约是"返回空列表"，于是整张等预算表全是 0。
	 */
	private static final int TOP_K_FROM_CONFIG = 0;

	private static String alignedFixedDescription(AlignResult align) {
		int overlap = Math.max(1, align.size() / 10);
		return "fixed " + align.size() + "字/" + overlap + "重叠 → 实测平均 "
				+ "%.1f".formatted(align.measuredAvg()) + " token";
	}

	/**
	 * 造一组对照。
	 *
	 * <p>{@code left} 是参照方、{@code right} 是被考察方，差值一律
	 * "右侧 − 左侧"（百分点）。方向定死，不让读者去猜。
	 */
	private static AlignmentExperiment.PairComparison pair(String fieldDescription, String dimension,
			StrategyEvaluator.StrategyEvaluation left, StrategyEvaluator.StrategyEvaluation right,
			List<String> notes) {
		List<ChunkingComparison.StrategyReport> reports = List.of(left.asReport(), right.asReport());
		Map<String, Map<String, Double>> deltas = new LinkedHashMap<>();
		// 复用"两个策略之间算差值"的唯一实现：再写一份的结果必然是两处阈值/口径慢慢漂移
		StrategyEvaluator.deltasBetween(reports.get(0), reports.get(1))
				.forEach((metric, value) -> deltas.put(metric, Map.of(right.strategyName(), value)));
		return new AlignmentExperiment.PairComparison("%s ｜ 左=%s ｜ 右=%s".formatted(fieldDescription,
				left.fieldDescription(), right.fieldDescription()), dimension, reports, deltas, notes);
	}

	// ------------------------------------------------------------------

	private List<String> buildNotes(List<EvalCase> cases, SectionLocator locator, AlignResult align,
			StrategyEvaluator.SizeMeasurement noBreadcrumb, StrategyEvaluator.SizeMeasurement plain,
			List<Integer> budgetSteps) {
		List<String> notes = new ArrayList<>();
		notes.add("「两个轴必须一起看」：轴一（块大小对齐）回答「切点选得好不好」，"
				+ "轴二（等 token 预算）回答「同样的钱谁找得回答案」。只报一个都会得出片面结论");
		notes.add("「检索方式也是一个实验维度」：同一组对齐对照在 " + RETRIEVAL_METHODS.stream()
				.map(StrategyEvaluator.RetrieverProvider::reportName).toList()
				+ " 下各比一次。分块策略的结论可能依赖用什么检索 —— "
				+ "只报一种检索下的结论，等于把这个交叉因素当成了常量");
		notes.add("⚠ 「向量那一路不是语义嵌入，它是本机的离线哈希向量」（" + embeddingModelName() + "，"
				+ embeddingDimensions() + " 维）：本机实测 DeepSeek 没有 embedding 端点"
				+ "（/v1/embeddings 返回 404），且 pgvector 装不上（Docker 引擎 OsNotSupported、无本机 PG、"
				+ "无 MSVC 工具链）。它是「哈希技巧 + TF-IDF」，仍建立在「词面重合」上，"
				+ "所以「不能」用它得出「向量检索比 BM25 强」或「混合检索提升了多少」——"
				+ "换一个真 embedding 模型，那两路数字会完全不一样。它能支撑的只有"
				+ "「换了度量方式之后，分块策略的结论变了吗」这一个问题");
		notes.add("差值与颜色的方向一律是「右侧相对左侧」（百分点）。"
				+ "在一张有多个数字的对照表里，方向靠猜一定会猜错");
		notes.add(("对齐后的实测平均块大小与目标差 %.2f token —— 「对齐」是近似的，"
				+ "这个差距是否足够小要由读者判断，不能由实现方默认成立")
						.formatted(align.measuredAvg() - noBreadcrumb.avgChunkTokens()));
		notes.add(("「关掉面包屑的那一组才是在比「切点」」：结构分块开/关面包屑的实测规模是 %.1f token × %d 块 "
				+ "vs %.1f token × %d 块（同一份语料、同一个 max-tokens=400）。两组「只有面包屑这一个开关不同」，"
				+ "所以它们的全部差异都来自「结构元数据有没有进块」这件事。"
				+ "⚠ 注意带面包屑那一组的平均块反而更小、块数更多（%d vs %d）——"
				+ "这是实测结果，不是预期方向；本报告只对齐块大小，「不对这个现象作解释」，"
				+ "要弄清它得单独查 StructuralChunker 的装填逻辑")
						.formatted(plain.avgChunkTokens(), plain.chunkCount(), noBreadcrumb.avgChunkTokens(),
								noBreadcrumb.chunkCount(), plain.chunkCount(), noBreadcrumb.chunkCount()));
		notes.add("面包屑的取舍本身是一个「独立实验」（它是特征，不是切点）："
				+ "真实系统里通常值得开（用户提问常直接用标题词，而正文可能一次都没写），"
				+ "这里关掉只是为了把「切点」这一个变量隔离出来");
		notes.add("等预算表的档位是 " + budgetSteps + "。预算口径是「装到下一个块会超预算为止」，"
				+ "第一条无条件放行（现实中不会因为单个块太大就一条都不给）——"
				+ "所以要同时看 avgTokensUsed 与 avgHitCount：预算没装满就是「块太大装不下」的直接证据");
		notes.add("本阶段检索的「词法那一路」为 BM25：它只认词面。所以对齐之后若结构分块仍然略低，"
				+ "结论是「在词法检索下，按结构切没有占到便宜」——"
				+ "「不能」推出「语义分块更差」（换真语义嵌入后词法这个约束就没了，结论可能反过来）");

		if (!locator.unresolved().isEmpty()) {
			notes.add("⚠ 有 %d 处标注在语料里找不到对应章节，这些用例必然算作未命中（分数偏低不是检索的问题）：%s"
					.formatted(locator.unresolved().size(), String.join("；", locator.unresolved())));
		}
		if (cases.size() < properties.getEvaluation().getMinUsableCases()) {
			notes.add("⚠ 样本量不足：只有 %d 条用例（门槛 %d 条）——这份结果能用来调参，"
					+ "但「不能作为结论引用」".formatted(cases.size(), properties.getEvaluation().getMinUsableCases()));
		}
		return notes;
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

}
