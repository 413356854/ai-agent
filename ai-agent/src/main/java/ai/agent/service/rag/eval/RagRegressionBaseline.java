package ai.agent.service.rag.eval;

import ai.agent.service.rag.pipeline.RagEvaluationService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>离线评测的回归基线</b>（阶段四第 4 件：评测接入 CI）。
 *
 * <h2>它解决的是哪一句话</h2>
 * 路线文档给阶段四第 4 件的验收口径是「改一次 Prompt 跑一遍回归」，
 * 而它真正要回答的是那句一直只能靠人看的话：<b>「改了之后怎么知道没改坏」</b>。
 *
 * <p>在此之前，43 条评测集的结果只被<b>打印</b>出来（`RagEvidenceReportTest` 刻意不写断言，
 * 理由很正当：把具体数值钉成断言会让调参变成改测试）。
 * 但"打印出来"与"能被回归挡住"是两件事 —— 打印的数字**没有人会去逐行比对**，
 * 于是"召回率掉了 3 个百分点"这件事可以静静地存在很久。
 *
 * <h2>为什么这条基线敢用严格相等（而不是一个宽松的阈值）</h2>
 * 因为这条链路<b>是完全确定的</b>：语料是仓库里的固定 4 份文档、评测集是固定的 43 条、
 * 检索是本地 BM25（无模型、无网络、无时间依赖）。实测<b>连跑两次，4 位小数逐位相同</b>。
 *
 * <p>确定性的东西就<b>不该用阈值</b>：一个"允许掉 5%"的阈值会把 4.9% 的退化放过去，
 * 而放过去的那些退化会一次一次累积 —— 最后没人知道是哪一次开始的。
 * 所以这里的判据是"**和基线不一样就要说出来**"，而不是"差不多就行"。
 *
 * <h2>⚠️ 它会红的两种情形，以及为什么两种都有价值</h2>
 * <ol>
 *   <li><b>真退化</b>：解析器/分块器/检索器被改坏了 —— 这正是要拦的；</li>
 *   <li><b>有意的改动</b>：调了分块参数、换了语料、加了用例、
 *       或者**修正了一条过期的标注**。这一类会让基线过时，
 *       而它**必须由人重新采一次基线**（见 {@code RagRegressionTest} 的报错指引），
 *       不能自动更新 —— 一个会自己更新的基线等于没有基线。</li>
 * </ol>
 * 这两种在数字上长得一样，所以报错信息必须把"怎么判断自己是哪一种"写清楚：
 * 看差值的方向、看是不是整组一起动、以及"是不是我刚改了什么"。
 *
 * <h2>基线的元信息为什么必须一起存</h2>
 * 只存数字的话，半年后没人知道它是什么条件下采的。所以每条基线都带上
 * {@code strategies} / {@code evalSet} / {@code caseCount} / {@code documents}，
 * 对比之前先核对这三样 —— 换了语料或改了用例数时，
 * 数字变了是**当然的**，而"当然的变化"不该被报成退化（那会消耗对这条断言的信任）。
 *
 * @param schema 基线格式版本。格式一变这个值就要变，读到不认识的值时**拒绝比较**而不是勉强读
 * @param takenAt 什么时候采的（人读的部分：排查"这条基线是不是早就过时了"）
 * @param note 为什么采这一版（**必填**，理由见 {@code RagRegressionTest} 的断言）
 * @param strategies 两个策略名（固定切分打头，与差值口径一致）
 * @param evalSet 评测集位置（报告要能自证用了哪份用例集）
 * @param caseCount 用例数
 * @param documents 语料文档数
 * @param metrics 策略名 → 指标快照
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagRegressionBaseline(String schema, String takenAt, String note, List<String> strategies,
		String evalSet, int caseCount, int documents, Map<String, StrategyMetrics> metrics) {

	/**
	 * 当前基线格式的版本。
	 *
	 * <p>它存在的理由与"接口版本"一样：读到旧格式时**必须拒绝比较**。
	 * 勉强读的后果很隐蔽 —— 缺的字段会是默认值（0.0），
	 * 于是"基线说 recall@5 = 0"会表现成一堆看起来像"严重退化"的红。
	 */
	public static final String SCHEMA = "rag-baseline/v1";

	/** 仓库里那份基线（测试资源，随代码一起进版本控制）。 */
	public static final String RESOURCE = "/rag-baseline.json";

	/**
	 * 基线在**仓库里的路径**（给人照着操作用的）。
	 *
	 * <p>它与 {@link #RESOURCE} 是两个值而不是一个，是因为两者用途不同：
	 * 前者是**类路径**（读的时候用，必须带前导斜杠、且不依赖工作目录），
	 * 后者是**文件路径**（打印给人看、让人去编辑的）。
	 * 实测踩到：第一版的报错信息直接打印了 {@link #RESOURCE}，
	 * 于是输出成「合并进 /rag-baseline.json」—— 一个**在文件系统根部**的路径。
	 * 照着它做只会得到"文件不存在"，而人还得自己猜出真实位置。
	 */
	public static final String RESOURCE_PATH = "ai-agent/src/test/resources/rag-baseline.json";

	/** 运行时把实测值写到哪（CI 收作 artifact；人排查时直接 `cat` 它）。 */
	public static final String ACTUAL_FILE = "target/rag-actual.json";

	/**
	 * 人读的报告写到哪（**UTF-8 文本，与 console 无关**）。
	 *
	 * <h2>为什么报告要落成文件，而不是只 println</h2>
	 * 因为实测发现：Maven 的 stdout 跟随**平台编码**（本机 Windows 上是 GBK），
	 * 于是中文报告写进 CI 日志就是乱码 —— 而**失败原因恰好就在那几行里**。
	 * 结果是最需要被读懂的信息，成了唯一读不出来的信息。
	 *
	 * <p>已经做了两手：pom 里钉了 `project.build.sourceEncoding` 与 surefire 的
	 * `-Dstdout.encoding=UTF-8`（那是治根的做法），但**报告仍然落一份文件** ——
	 * 因为这一份不依赖任何人的控制台设置：CI 直接 `cat` 它、本机直接打开它，
	 * 两边看到的是同一串字节。一条排查路径不该依赖"你这台机器的 locale 恰好对了"。
	 */
	public static final String REPORT_FILE = "target/rag-regression-report.txt";

	public RagRegressionBaseline {
		strategies = List.copyOf(strategies);
		metrics = Map.copyOf(metrics);
	}

	/**
	 * 一个策略的指标快照。
	 *
	 * <p>字段分两类，而<b>这个区分就是判据本身</b>（见 {@link Metric#HIGHER_IS_BETTER}）：
	 * 召回类的越大越好，成本类的越小越好。把两类混在一起写"变了就红"，
	 * 会在召回<b>变好</b>时也报红 —— 而那种误报会让人开始忽略这条断言。
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StrategyMetrics(double recallAt1, double recallAt3, double recallAt5, double recallAt10,
			double mrr, double precisionAt5, int chunkCount, double avgChunkTokens, int maxChunkTokens,
			int totalTokens, double duplicationRatio, int undersizedChunks) {
	}

	/**
	 * 指标的"哪个方向算变好"。
	 *
	 * <p>写成枚举而不是两串常量名：往 {@link RagRegressionTest} 里加一个新指标时，
	 * 编译器会逼着这里也回答"它算哪一类"—— 而不是静默落进一个默认判断。
	 * 一个默认判断在这里的后果是具体的：它会把"召回变好"报成退化，
	 * 或者把"成本暴涨"报成改进，两种都会让人不再相信这条断言。
	 */
	public enum Metric {

		/** 越大越好（召回、排序质量）。变小 = 退化。 */
		HIGHER_IS_BETTER,

		/** 越小越好（块数、token 成本、重复率）。变大 = 退化。 */
		LOWER_IS_BETTER

	}

	/** 从测试资源里读基线；读不到时返回空（由调用方给出"没有基线该怎么办"的指引）。 */
	public static Optional<RagRegressionBaseline> load(ObjectMapper mapper) {
		try (InputStream in = RagRegressionBaseline.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return Optional.empty();
			}
			return Optional.of(mapper.readValue(in, RagRegressionBaseline.class));
		}
		catch (IOException e) {
			throw new UncheckedIOException("读不到离线评测基线 " + RESOURCE, e);
		}
	}

	/** 序列化（只给"重新采基线"用；正常路径只读不写）。 */
	public static String toJson(ObjectMapper mapper, RagRegressionBaseline baseline) {
		try {
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(baseline);
		}
		catch (IOException e) {
			throw new UncheckedIOException("序列化基线失败", e);
		}
	}

	/** 把一次真实评测的结果抽成基线形状（**这是唯一一处"把报告变成基线"的代码**）。 */
	public static RagRegressionBaseline from(ChunkingComparison comparison, String note) {
		Map<String, StrategyMetrics> metrics = new LinkedHashMap<>();
		for (ChunkingComparison.StrategyReport report : comparison.strategies()) {
			RetrievalMetrics m = report.metrics();
			metrics.put(report.strategy(), new StrategyMetrics(
					m.recallAt().getOrDefault(1, 0.0),
					m.recallAt().getOrDefault(3, 0.0),
					m.recallAt().getOrDefault(5, 0.0),
					m.recallAt().getOrDefault(10, 0.0),
					m.mrr(),
					m.contextPrecisionAt().getOrDefault(5, 0.0),
					report.chunkCount(),
					report.avgChunkTokens(),
					report.maxChunkTokens(),
					report.tokenCost().totalTokens(),
					report.tokenCost().duplicationRatio(),
					report.tokenCost().undersizedChunks()));
		}
		return new RagRegressionBaseline(SCHEMA, LocalDate.now().toString(), note,
				comparison.strategies().stream().map(ChunkingComparison.StrategyReport::strategy).toList(),
				comparison.evalSetLocation(), comparison.caseCount(), comparison.documents(), metrics);
	}

	// ------------------------------------------------------------------
	//  指标表：名字 → 取值 + 方向
	// ------------------------------------------------------------------

	/**
	 * 把两条策略快照摊平成「指标名 → 取值」，并给出每个指标的方向。
	 *
	 * <p>键形如 {@code "fixed-size.recall@5"} —— 带上策略名是因为
	 * 报错信息里必须能直接看出"是哪一个策略的哪一项动了"，
	 * 而"recall@5 变了"在半数指标都有这个后缀时报不清是哪一个。
	 */
	public static Map<String, Double> flatten(String strategy, StrategyMetrics m) {
		Map<String, Double> out = new LinkedHashMap<>();
		out.put(strategy + ".recall@1", m.recallAt1());
		out.put(strategy + ".recall@3", m.recallAt3());
		out.put(strategy + ".recall@5", m.recallAt5());
		out.put(strategy + ".recall@10", m.recallAt10());
		out.put(strategy + ".mrr", m.mrr());
		out.put(strategy + ".precision@5", m.precisionAt5());
		out.put(strategy + ".chunkCount", (double) m.chunkCount());
		out.put(strategy + ".avgChunkTokens", m.avgChunkTokens());
		out.put(strategy + ".maxChunkTokens", (double) m.maxChunkTokens());
		out.put(strategy + ".totalTokens", (double) m.totalTokens());
		out.put(strategy + ".duplicationRatio", m.duplicationRatio());
		out.put(strategy + ".undersizedChunks", (double) m.undersizedChunks());
		return out;
	}

	/**
	 * 每个指标的方向。
	 *
	 * <p>{@code avgChunkTokens} / {@code maxChunkTokens} 归到"越小越好"是**刻意的**：
	 * 块越大，相关性判定里的"块覆盖了答案章节"就越容易成立（这是一个已知的混杂因素，
	 * 见 {@code RagEvaluationService} 的类注释）。也就是说块变大时召回率会**虚假地**变好 ——
	 * 所以"块变大"本身就该被看见，而不是被当成中性事件。
	 */
	public static Map<String, Metric> directions() {
		Map<String, Metric> out = new LinkedHashMap<>();
		for (String metric : List.of("recall@1", "recall@3", "recall@5", "recall@10", "mrr", "precision@5")) {
			out.put(metric, Metric.HIGHER_IS_BETTER);
		}
		for (String metric : List.of("chunkCount", "avgChunkTokens", "maxChunkTokens", "totalTokens",
				"duplicationRatio", "undersizedChunks")) {
			out.put(metric, Metric.LOWER_IS_BETTER);
		}
		return out;
	}

	/** 取某个指标的裸名（去掉 {@code 策略.} 前缀）。 */
	public static String bareName(String flattenedKey) {
		int dot = flattenedKey.indexOf('.');
		return dot < 0 ? flattenedKey : flattenedKey.substring(dot + 1);
	}

	/** 取某个指标属于哪个策略。 */
	public static String strategyOf(String flattenedKey) {
		int dot = flattenedKey.indexOf('.');
		return dot < 0 ? "" : flattenedKey.substring(0, dot);
	}

	/**
	 * 基线与实测的**全部差异**（不做判定，只做算术）。
	 *
	 * <p>判定留给调用方 —— 因为"差异"与"该不该红"是两件事：
	 * 召回变大也是差异，而它不该让构建失败（见 {@link RagRegressionTest}）。
	 */
	public List<Drift> diff(RagRegressionBaseline actual, double tolerance) {
		List<Drift> drifts = new ArrayList<>();
		Map<String, Metric> directions = directions();

		for (String strategy : strategies) {
			StrategyMetrics expected = metrics.get(strategy);
			StrategyMetrics actualMetrics = actual.metrics().get(strategy);
			if (expected == null || actualMetrics == null) {
				// 策略集合变了（换了策略名 / 少跑了一组）—— 这本身就是必须报出来的事，
				// 而不是"没有差异"。静默跳过会让"某一组整个没跑"看起来像全绿
				drifts.add(new Drift(strategy + ".*", Double.NaN, Double.NaN, false, true));
				continue;
			}
			Map<String, Double> want = flatten(strategy, expected);
			Map<String, Double> got = flatten(strategy, actualMetrics);
			for (Map.Entry<String, Double> entry : want.entrySet()) {
				double before = entry.getValue();
				double after = got.getOrDefault(entry.getKey(), Double.NaN);
				Metric direction = directions.get(bareName(entry.getKey()));
				if (direction == null) {
					// 加了新指标却忘了登记方向 —— 这是**代码缺陷**，必须炸而不是放过
					throw new IllegalStateException("指标「" + entry.getKey()
							+ "」没有登记方向（见 RagRegressionBaseline.directions()）。"
							+ "不登记方向的后果是它会被用默认判断，而默认判断会把一边的好转报成退化");
				}
				double delta = after - before;
				if (Math.abs(delta) <= tolerance) {
					continue;
				}
				boolean worsened = direction == Metric.HIGHER_IS_BETTER ? delta < 0 : delta > 0;
				drifts.add(new Drift(entry.getKey(), before, after, worsened, false));
			}
		}
		return List.copyOf(drifts);
	}

	/**
	 * 一处差异。
	 *
	 * @param metric 指标名（形如 {@code fixed-size.recall@5}）
	 * @param before 基线值
	 * @param after 实测值
	 * @param worsened 按该指标的方向判断，这一处是**退化**吗
	 * @param missing 这一处不是"数值变了"，而是**整组不见了**（策略集合变了）
	 */
	public record Drift(String metric, double before, double after, boolean worsened, boolean missing) {

		/** 相对变化（用于人读；分母为 0 时给 NaN 而不是 Infinity）。 */
		public double relative() {
			return before == 0 ? Double.NaN : (after - before) / Math.abs(before);
		}

		/** 一行人类可读的描述。 */
		public String describe() {
			if (missing) {
				return "%s：**这一组不见了**（基线里有、这次评测里没有）".formatted(metric);
			}
			return "%s：%s → %s（Δ%+.4f，%+.2f%%）%s".formatted(metric, fmt(before), fmt(after), after - before,
					relative() * 100, worsened ? " ← **退化**" : "（朝好的方向）");
		}

		private static String fmt(double value) {
			// 块数/token 这类整数指标用整数显示：0.0000 那种写法会让人以为它们是比率
			return Math.abs(value - Math.rint(value)) < 1e-9 ? String.valueOf((long) value)
					: "%.4f".formatted(value);
		}
	}

	/** 缺基线时的指引（**必须是可照做的**，而不是一句"基线不存在"）。 */
	public static String missingBaselineHint() {
		return """
				没有找到 %s。离线评测的回归基线是**随代码一起进版本控制**的一份文件，缺少它时这一道门禁失效。

				采基线的方法（**只在有意变更之后做**）：
				  mvn -o test -Dtest=RagRegressionTest -DfailIfNoSpecifiedTests=false -Drag.baseline.write=true
				它会写出 %s；把它拷到 %s 并**填好 note**。

				⚠️ 一个会自己更新的基线等于没有基线 —— 所以这一步必须是人明确做的，
				   而且要能在提交信息里回答"为什么这次基线动了"。
				""".formatted(RESOURCE, ACTUAL_FILE, RESOURCE_PATH);
	}

	/** 便于断言与日志：这次采到的 strategies / 用例数 / 文档数。 */
	public String describeContext() {
		return "strategies=%s evalSet=%s cases=%d documents=%d".formatted(strategies, evalSet, caseCount, documents);
	}

	/** 与另一条基线比，"采集条件"是否相同（条件不同时数字变化是当然的）。 */
	public boolean sameContext(RagRegressionBaseline other) {
		return strategies.equals(other.strategies) && caseCount == other.caseCount && documents == other.documents
				&& (evalSet == null ? other.evalSet == null : evalSet.equals(other.evalSet));
	}

	/** 给报告用的一行摘要。 */
	public String summary() {
		return "%s（%s，%s）：%s".formatted(schema, takenAt, note, describeContext());
	}

	/** 差值为 0 的策略名（用于"哪些组没动"的日志，减少噪音）。 */
	public List<String> unchangedStrategies(RagRegressionBaseline actual, double tolerance) {
		List<String> out = new ArrayList<>();
		for (String strategy : strategies) {
			boolean unchanged = diff(actual, tolerance).stream()
					.noneMatch(d -> d.metric().startsWith(strategy + "."));
			if (unchanged) {
				out.add(strategy);
			}
		}
		return List.copyOf(out);
	}
}
