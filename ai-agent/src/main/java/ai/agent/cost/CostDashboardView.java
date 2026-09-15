package ai.agent.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把成本聚合结果组装成界面能直接渲染的形状（阶段四 · 成本看板）。
 *
 * <h2>这一页要回答的三个问题（其余都是它们的证据）</h2>
 * <ol>
 *   <li><b>花了多少</b> —— 按天的走势与窗口合计；</li>
 *   <li><b>谁花的</b> —— 按业务归因键、按模型的拆分（路线文档的「成本归因」）；</li>
 *   <li><b>花得值不值</b> —— 失败但同样花钱的那部分、缓存命中率、工具调用与延迟。</li>
 * </ol>
 * 第三问是这个看板与"账单截图"的区别：账单只知道花了多少，
 * 而"花了钱又没办成事"（{@code status=FAILED} 的运行）只在我们自己的数据里才有。
 *
 * <h2>金额是"按模型分别算完再相加"，不是"先加 token 再乘单价"</h2>
 * 这是本类唯一一处容易写错的地方，所以单独说清。按天/按业务分组的行里
 * **混着多个模型**，而每个模型的单价不同。把它们的 token 加总之后乘任意一个单价都是错的 ——
 * 而错出来的钱数看起来完全正常（只是偏大或偏小），不会报错。
 *
 * <p>所以实现是：钱只在"单一模型"的粒度上算（那是唯一有确切单价的粒度），
 * 更大粒度的金额由**逐模型算完的金额相加**得到。而与单价无关的量
 * （token、次数、缓存命中率）是线性可加的，直接求和就是对的 ——
 * 两类量分开处理，而不是笼统地"加起来"。
 *
 * <h2>界面侧一条不能破的纪律：不显示我们算不出来的东西</h2>
 * 价格没配 → 显示「未配置价格」+ 待配置的模型清单，<b>不显示 ¥0.00</b>；
 * 模型名没上报 → 单独一行「未上报模型名」并且**不给它计价**。
 * 理由是本项目反复记过的一条：一个"看起来正常的错数字"比一个明确的空洞危险得多，
 * 而成本表恰恰是最容易被直接引用的一张表。
 */
@Component
public class CostDashboardView {

	private static final Logger log = LoggerFactory.getLogger(CostDashboardView.class);

	/** 默认时间窗（天）。取 7 是因为它同时满足"看得见走势"与"一次查询只回 7 行"。 */
	public static final int DEFAULT_DAYS = 7;

	/** 允许的最大窗口。防止有人传 days=100000 把一次页面刷新变成一次全表扫描。 */
	public static final int MAX_DAYS = 90;

	/** 单一模型分组里 {@code key} 为空时的显示名（**不是一个模型名**，故不给它计价）。 */
	private static final String UNKNOWN_MODEL_LABEL = "未上报模型名";

	/** 归因键为空时的显示名。 */
	private static final String UNLABELED_INTENT = "未标注";

	private final CostQuery query;

	private final CostPricing pricing;

	public CostDashboardView(CostQuery query, CostPricing pricing) {
		this.query = query;
		this.pricing = pricing;
	}

	/** 组装看板快照。{@code days} 会被夹到 [1, {@link #MAX_DAYS}]。 */
	public Map<String, Object> snapshot(int days) {
		int effectiveDays = Math.max(1, Math.min(days, MAX_DAYS));
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		LocalDate from = today.minusDays(effectiveDays - 1L);

		List<CostQuery.DayTotals> dayRows = query.byDay(from, today);
		List<CostQuery.GroupTotals> intentRows = query.byIntent(from, today);
		List<CostQuery.GroupTotals> modelRows = query.byModel(from, today);
		List<CostQuery.ToolTotals> toolRows = query.byTool(from, today);
		List<CostQuery.ModelDayRow> modelDayRows = query.byModelAndDay(from, today);
		CostQuery.Reconciliation reconciliation = query.reconcile(from, today);

		// 【计价的中枢】每个模型的 token 与金额先算好，后面所有粒度的金额都从这里派生。
		// 这样"按天 / 按业务 / 合计"三处的钱**必然**等于"按模型那一栏的和" ——
		// 它们是同一份数字的不同视图，而不是三次独立的计算（三次独立计算迟早会对不上）
		PricedModels priced = priceByModel(modelRows, today);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("window", window(from, today, effectiveDays));
		body.put("currency", pricing.defaultCurrency());
		body.put("pricingConfigured", pricing.configured());
		body.put("priceTable", pricing.priceTable());
		body.put("configNotes", pricing.configNotes());
		body.put("needsPriceFor", priced.unpricedModels());

		body.put("totals", totals(dayRows, priced));		body.put("days", days(dayRows, modelDayRows, priced, today));
		body.put("byIntent", groups(intentRows, Costs.NOT_SINGLE_MODEL, today, true));
		body.put("byModel", groups(modelRows, Costs.PER_MODEL, today, false));
		body.put("byTool", toolRows.stream().map(this::tool).toList());
		body.put("reconciliation", reconciliation(reconciliation));
		body.put("cache", cache(reconciliation, modelRows));
		body.put("notes", notes(dayRows, reconciliation, priced));

		log.info("成本看板：窗口 {}~{} 运行 {} 次 / {} tokens / 计价={} / 未配价的模型={}", from, today,
				priced.totals().runCount(), priced.totals().totalTokens(), pricing.configured(),
				priced.unpricedModels());
		return body;
	}

	/** 价格表与"哪些模型还没有价"（界面据此显示一个能照着做的待办）。 */
	public Map<String, Object> priceSnapshot() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("currency", pricing.defaultCurrency());
		body.put("configured", pricing.configured());
		body.put("prices", pricing.priceTable());
		body.put("notes", pricing.configNotes());
		body.put("howToConfigure", """
				价格来自上游的商业条款，代码里刻意不写死默认值 —— 一个 ¥0.00 的成本表 \
				会让人得出『这个功能不花钱』的结论，而真相是『我们不知道它花多少钱』。
				在 application.properties 里加（可以多条，按模型区分）：
				  ai.cost.prices[0].model=你的模型名
				  ai.cost.prices[0].input-per-million=未命中缓存的输入单价
				  ai.cost.prices[0].cache-hit-per-million=命中缓存的输入单价（少了它按未命中价算，偏保守）
				  ai.cost.prices[0].output-per-million=输出单价
				  ai.cost.prices[0].currency=CNY
				  ai.cost.prices[0].effective-from=2026-01-01
				  ai.cost.prices[0].source=价格出处（调价之后要能回答『这个价哪来的』）
				模型名用 * 表示兜底规则。调价要新增一条带新 effective-from 的规则，
				不要改旧的那条 —— 否则历史成本会被新价改写，而那种改写不留任何痕迹。
				""");
		return body;
	}

	// ------------------------------------------------------------------
	//  计价中枢
	// ------------------------------------------------------------------

	/**
	 * 每个模型一行：token、金额、以及"这个模型有没有价"。
	 *
	 * @param byModel 模型名 → 计价结果（null 值不出现；没有价的模型记在 unpriced 里）
	 * @param totals 窗口合计的行数级数字
	 */
	private record PricedModels(Map<String, PricedRow> byModel, List<String> unpricedModels,
			CostQuery.GroupTotals totals) {
	}

	/** 一个模型的 token 与金额。 */
	private record PricedRow(String model, long promptTokens, long cachedPromptTokens, long completionTokens,
			Long micros, String currency, String ruleModel, String ruleSource) {

		long uncached() {
			return Math.max(0, promptTokens - cachedPromptTokens);
		}

		/** 按各档 token 的比例，把这个模型的钱分摊给它的一个子集（用于按天/按业务细分）。 */
		long shareFor(long uncachedShare, long cachedShare, long completionShare) {
			if (micros == null) {
				return 0L;
			}
			long total = uncached() + cachedPromptTokens + completionTokens;
			if (total == 0) {
				return 0L;
			}
			long share = uncachedShare + cachedShare + completionShare;
			// 按 token 比例分摊：**这与逐项重算是等价的**（费用对 token 是线性的），
			// 而它避免了对每一行再走一遍价格匹配 —— 价格匹配要选规则、要判生效时间，
			// 在热路径上重复做它只会多出出错的机会
			return Math.round((double) micros * share / total);
		}
	}

	private PricedModels priceByModel(List<CostQuery.GroupTotals> modelRows, LocalDate today) {
		Map<String, PricedRow> byModel = new LinkedHashMap<>();
		List<String> unpriced = new ArrayList<>();
		for (CostQuery.GroupTotals row : modelRows) {
			// 【key 为 null = 上游没上报模型名】它不是模型名，所以**绝不能用兜底价格去算它**。
			// 拿 * 规则给它定价，会得到一个"看起来有金额"的数字，而那个数字是按
			// 随便哪个模型的价算的 —— 那正是这一页最不能有的东西
			boolean named = row.key() != null;
			Optional<CostPricing.Cost> cost = named
					? pricing.price(row.key(), row.uncachedPromptTokens(), row.cachedPromptTokens(),
							row.completionTokens(), endOf(today))
					: Optional.empty();
			if (cost.isEmpty() && row.totalTokens() > 0) {
				unpriced.add(named ? row.key() : UNKNOWN_MODEL_LABEL);
			}
			CostPricing.Cost value = cost.orElse(null);
			byModel.put(row.key() == null ? "" : row.key(),
					new PricedRow(row.key(), row.promptTokens(), row.cachedPromptTokens(), row.completionTokens(),
							value == null ? null : value.micros(), value == null ? null : value.currency(),
							value == null ? null : value.ruleModel(), value == null ? null : value.source()));
		}
		return new PricedModels(byModel, List.copyOf(unpriced), sum(modelRows));
	}

	/** 计价用的时间点：窗口末日。用"现在"会在调价当天把之前那些天也按新价算。 */
	private static Instant endOf(LocalDate day) {
		return day.atStartOfDay(ZoneId.systemDefault()).toInstant();
	}

	// ------------------------------------------------------------------
	//  组装：合计 / 按天 / 按维度
	// ------------------------------------------------------------------

	private Map<String, Object> window(LocalDate from, LocalDate to, int days) {
		Map<String, Object> window = new LinkedHashMap<>();
		window.put("from", from.toString());
		window.put("to", to.toString());
		window.put("days", days);
		window.put("maxDays", MAX_DAYS);
		return window;
	}

	private Map<String, Object> totals(List<CostQuery.DayTotals> dayRows, PricedModels priced) {
		Map<String, Object> body = base(priced.totals());
		// 【★ 运行级计数必须取"按天"的和，不能取"按模型"的和 —— 这一条是一个真 bug 修出来的】
		//
		// 合计里的两样东西来源不同，混用会得到一个"看起来正常"的错数字：
		//   ① token 与金额：只能来自逐模型的计算（单价按模型不同）→ priced.totals()；
		//   ② runCount / finishedCount / stoppedCount / failedCount：是**一次运行**的属性，
		//      必须来自"按运行分组"的那条查询（byDay）。
		//
		// 原来这里整体用的是 priced.totals()，而它等于 **byModel** 那一组的和 ——
		// byModel 的 SQL 里 finished_count / failed_count 是**写死的 0**
		// （那一维按"模型"分组，没有运行级状态可言）。
		// 于是真后端上"已结束"恒为 0，而假后端那份手写数据里写的是 12 / 9 / 5 之类**正确**的和 ——
		// 两条路径长得一样，所以这个差异只会在真路径上现形：又一次"假数据替真代码圆了场"。
		// （byModel 的 run_count 是 COUNT(DISTINCT run_id)，所以运行次数那一格碰巧是对的，
		//   错的只有"已结束 / 失败"这类**按状态**数的格子。）
		//
		// byDay 按天分组且覆盖窗口内全部运行，所以它的和就是这批运行的运行级计数。
		CostQuery.GroupTotals runs = sum(dayRows.stream().map(CostQuery.DayTotals::totals).toList());
		body.put("runs", runs.runCount());
		body.put("finishedRuns", runs.finishedCount());
		body.put("stoppedRuns", runs.stoppedCount());
		body.put("failedRuns", runs.failedCount());

		Long micros = totalMicros(priced);
		body.put("amount", micros == null ? null : CostPricing.format(pricing.defaultCurrency(), micros));
		body.put("amountMicros", micros);
		body.put("amountReason", micros == null ? reasonNotPriced(priced.unpricedModels()) : null);
		body.put("allModelsPriced", priced.unpricedModels().isEmpty());
		body.put("daysWithData", dayRows.size());
		return body;
	}

	private List<Map<String, Object>> days(List<CostQuery.DayTotals> dayRows, List<CostQuery.ModelDayRow> modelDayRows,
			PricedModels priced, LocalDate today) {
		// 按天把金额算出来：把同一天的**每个模型**的钱分别算出再相加。
		// 直接拿"当天的总 token × 某个单价"是错的（单价按模型不同），
		// 而这个错法给出的数字看起来完全正常
		Map<String, Long> microsByDay = new LinkedHashMap<>();
		Map<String, List<String>> unpricedByDay = new LinkedHashMap<>();
		for (CostQuery.ModelDayRow row : modelDayRows) {
			String day = row.day() == null ? "" : row.day().toString();
			PricedRow model = priced.byModel().get(row.model() == null ? "" : row.model());
			if (model == null || model.micros() == null) {
				if (row.promptTokens() + row.completionTokens() > 0) {
					unpricedByDay.computeIfAbsent(day, key -> new ArrayList<>())
							.add(row.model() == null || row.model().isBlank() ? UNKNOWN_MODEL_LABEL : row.model());
				}
				continue;
			}
			long share = model.shareFor(row.uncachedPromptTokens(), row.cachedPromptTokens(),
					row.completionTokens());
			microsByDay.merge(day, share, Long::sum);
		}

		List<Map<String, Object>> rows = new ArrayList<>();
		for (CostQuery.DayTotals row : dayRows) {
			String day = row.day() == null ? "" : row.day().toString();
			Map<String, Object> body = base(row.totals());
			Long micros = microsByDay.get(day);
			body.put("day", day);
			body.put("amount", micros == null ? null : CostPricing.format(pricing.defaultCurrency(), micros));
			body.put("amountMicros", micros);
			body.put("amountReason", micros == null
					? reasonNotPriced(unpricedByDay.getOrDefault(day, List.of()))
					: null);
			rows.add(body);
		}
		return List.copyOf(rows);
	}

	/** 按业务 / 按模型的分组行。 */
	private List<Map<String, Object>> groups(List<CostQuery.GroupTotals> rows, Costs mode, LocalDate today,
			boolean withTools) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (CostQuery.GroupTotals row : rows) {
			Map<String, Object> body = base(row);
			body.put("key", row.key());
			body.put("keyLabel", label(row.key(), mode));
			body.put("keyMissing", row.key() == null);
			if (!withTools) {
				// 工具调用不属于"模型"这个维度：它无法归到某一个模型上
				// （一次工具调用的结果会进下一步的上下文，成本落在**下一步**的模型上）。
				// 留着这几个 0 会让人以为"这个模型没调工具"
				body.remove("toolCalls");
				body.remove("toolFailures");
				body.remove("toolElapsedMs");
			}
			Optional<CostPricing.Cost> single = mode == Costs.PER_MODEL && row.key() != null
					? pricing.price(row.key(), row.uncachedPromptTokens(), row.cachedPromptTokens(),
							row.completionTokens(), endOf(today))
					: Optional.empty();
			if (single.isPresent()) {
				CostPricing.Cost cost = single.get();
				body.put("amount", cost.amount());
				body.put("amountMicros", cost.micros());
				body.put("currency", cost.currency());
				body.put("priceRule", cost.ruleModel());
				body.put("priceSource", cost.source() == null ? "" : cost.source());
				body.put("amountReason", null);
			}
			else {
				body.put("amount", null);
				body.put("amountMicros", null);
				body.put("amountReason", mode == Costs.PER_MODEL
						? (row.key() == null
								? "上游没有上报模型名 —— 没有模型名就没有单价可用。"
										+ "刻意不用兜底价格去猜：那会算出一个按别的模型定价的假数字"
								: "没有匹配的价格规则（model=" + row.key()
										+ "）—— 在 ai.cost.prices 里补一条，或访问 /api/cost/prices 看写法")
						: "这一组混着多个模型，单一单价不适用 —— 金额按模型分别计算后相加，"
								+ "见「按模型」那一栏；各模型的合计等于这里的总额（已在上方总计核对）");
			}
			out.add(body);
		}
		return List.copyOf(out);
	}

	/** 分组模式：决定"金额"那一格是"按这个键的唯一模型算"还是"说明为什么算不了"。 */
	private enum Costs {

		/** 按模型分组：key 就是模型名，单价唯一。 */
		PER_MODEL,

		/** 其它维度：一格里可能混着多个模型。 */
		NOT_SINGLE_MODEL

	}

	private String label(String key, Costs mode) {
		if (key != null) {
			return key;
		}
		return mode == Costs.PER_MODEL ? UNKNOWN_MODEL_LABEL : UNLABELED_INTENT;
	}

	/** 一组数字里与单价无关的那部分（它们在所有粒度上都可直接相加）。 */
	private Map<String, Object> base(CostQuery.GroupTotals totals) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runs", totals.runCount());
		body.put("finishedRuns", totals.finishedCount());
		// 【被上限拦下的那些：既不是成功也不是失败，但它同样花了钱】
		// 它是 finishedRuns 的一部分（AgentRunRecord.isFinished 把它算作已结束），
		// 单独给一格是为了回答一个 finishedRuns 与 failedRuns 都回答不了的问题：
		// "有多少钱花在没办成事上"。并进任何一边都会让那边的数字说谎 ——
		// 并进成功会让"办成了多少"虚高，并进失败会把"被自己的上限拦下"报成上游故障
		body.put("stoppedRuns", totals.stoppedCount());
		body.put("failedRuns", totals.failedCount());
		body.put("promptTokens", totals.promptTokens());
		body.put("cachedPromptTokens", totals.cachedPromptTokens());
		body.put("completionTokens", totals.completionTokens());
		body.put("totalTokens", totals.totalTokens());
		body.put("cacheHitRate", rate(totals.cachedPromptTokens(), totals.promptTokens()));
		body.put("elapsedMs", totals.elapsedMs());
		body.put("toolCalls", totals.toolCalls());
		body.put("toolFailures", totals.toolFailures());
		body.put("toolElapsedMs", totals.toolElapsedMs());
		body.put("firstAt", totals.firstAt() == null ? "" : totals.firstAt().toString());
		return body;
	}

	private Map<String, Object> tool(CostQuery.ToolTotals row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("toolName", row.toolName());
		body.put("calls", row.calls());
		body.put("failures", row.failures());
		body.put("successRate", round4(row.successRate()));
		body.put("unknownTool", row.unknownTool());
		body.put("validationErrors", row.validationErrors());
		body.put("parseErrors", row.parseErrors());
		body.put("rejected", row.rejected());
		body.put("circuitOpen", row.circuitOpen());
		body.put("elapsedMs", row.elapsedMs());
		body.put("runs", row.runs());
		// 明确的"没有钱"：这一维只有次数与耗时，见 CostQuery.byTool 的注释
		body.put("amount", null);
		body.put("amountReason", "工具不直接消耗 token —— 它消耗的是延迟与下游容量。"
				+ "『这把工具花了多少钱』只能靠『它让模型多跑了几轮』间接归因，那是推测而不是数据");
		return body;
	}

	private Map<String, Object> reconciliation(CostQuery.Reconciliation row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runLevelTokens", row.runLevelTokens());
		body.put("stepLevelTokens", row.stepLevelTokens());
		body.put("gap", row.gap());
		body.put("runs", row.runs());
		body.put("runsWithoutSteps", row.runsWithoutSteps());
		// 【这条是看板的"仪表自检"】两个口径本该一致：看板的钱按步骤表算，
		// 而 AgentRun.totalTokens 是循环自己累加的（它决定 token 上限、也显示在运行列表上）。
		// 不一致说明有一处漏记，而看板上完全看不出来 —— 只是钱多一点或少一点
		boolean matched = row.gap() == 0;
		body.put("matched", matched);
		body.put("note", matched
				? "两个口径一致：按步骤汇总的 token 与运行行上的累计值相同。"
				: "⚠️ 两个口径对不上（差 " + row.gap() + "）—— 看板的钱按步骤表算，"
						+ "而运行列表显示的是运行行上的累计值。差值说明有一处漏记，"
						+ "最可能是某次运行没写检查点（见 runsWithoutSteps）");
		return body;
	}

	private Map<String, Object> cache(CostQuery.Reconciliation row, List<CostQuery.GroupTotals> modelRows) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("promptTokens", row.promptTokens());
		body.put("cachedPromptTokens", row.cachedPromptTokens());
		body.put("hitRate", rate(row.cachedPromptTokens(), row.promptTokens()));
		body.put("runsWithCacheHit", row.promptCacheHitRuns());
		// 【"命中率 0%"有两种完全不同的成因，必须分开说】
		// 一是"确实一次都没命中"，二是"上游根本没返回缓存字段"。
		// 后者会让整个缓存折扣口径失效（成本按最贵那一档算），而页面上看起来
		// 只是"缓存没起作用" —— 一个会让人跑去优化提示词的错误归因。
		//
		// 【为什么这里只给"可疑"而不是判定】
		// 从数据本身**分不出**这两种情况（两种情况在库里长得一模一样：都是 0）。
		// 想分辨只能去看一次真实响应的原始 usage —— 那需要真 key，本机没有。
		// 所以这里明确说"无法分辨"并给出下一步动作，而不是挑一个猜。
		boolean noCacheAtAll = row.promptTokens() > 0 && row.cachedPromptTokens() == 0;
		body.put("suspicious", noCacheAtAll);
		body.put("note", row.promptTokens() == 0
				? "窗口内没有 token 记录，命中率无从谈起。"
				: noCacheAtAll
						? "⚠️ 整个窗口的缓存命中都是 0。这有两种可能，数据本身分不出来："
								+ "（a）上游确实一次都没命中；（b）上游不支持/不返回缓存字段 —— "
								+ "如果是 (b)，成本被按最贵那一档算了。区分方法只有一个："
								+ "拿真 key 跑一次、直接看响应里的 usage 有没有缓存字段。"
						: "命中率越高，同样内容的重发越便宜 —— 缓存价与未命中价的差距是数量级的。");
		return body;
	}

	// ------------------------------------------------------------------
	//  边界说明（与数字一起交付）
	// ------------------------------------------------------------------

	/**
	 * 边界说明。
	 *
	 * <h2>⚠️ 这些字符串里**不许出现 markdown 星号**（实测踩到，改之前先读完）</h2>
	 * 第一版这里写的是 {@code **token 数不是估算的**} —— 而这段文本有**两个消费方**：
	 * <ol>
	 *   <li>JSON 接口（curl、别的系统读）—— 那里星号是无害的，甚至还算一种约定；</li>
	 *   <li><b>界面上那个 {@code <ul>}，它是纯文本渲染</b> —— 于是页面上真的出现了两个星号。</li>
	 * </ol>
	 * 真后端路径上"页面显示 **xxx**"这件事是被自检抓住的（假后端那份假数据里没有星号，
	 * 所以它只在真路径上红）。这与踩坑库 #24（纯文本里的 markdown 星号原样显示）
	 * 是同一个坑 —— 而且它第二次咬人的方式更隐蔽：<b>同一个字符串在两个消费方眼里含义不同</b>。
	 *
	 * <p>处理办法不是"在界面上过滤星号"（那会让接口的读者拿到被改过的文本），
	 * 而是**源头就不写星号**，改用中文书名号与换行来表达强调 ——
	 * 这一条对所有"同时给人和给程序看"的文案都成立。
	 */
	private List<String> notes(List<CostQuery.DayTotals> dayRows, CostQuery.Reconciliation reconciliation,
			PricedModels priced) {
		List<String> notes = new ArrayList<>();
		notes.add("token 数不是估算的：它来自上游响应里的 usage，应用侧只做聚合 —— "
				+ "所以这一页的 token 口径与运行列表、与上游账单是同一份事实。");
		if (!pricing.configured()) {
			String models = priced.unpricedModels().isEmpty() ? "（窗口内还没有运行记录）"
					: String.join("、", priced.unpricedModels());
			notes.add("⚠️ 价格未配置，所以这一页没有金额。窗口内出现过的模型：" + models
					+ " —— 配好 ai.cost.prices 之后金额会自动出现（写法见 /api/cost/prices）。");
		}
		else if (!priced.unpricedModels().isEmpty()) {
			notes.add("⚠️ 有模型还没有价格：" + String.join("、", priced.unpricedModels())
					+ " —— 它们的 token 计入了总量，但不在金额里，所以上面的合计是「下界」。");
		}
		else {
			notes.add("金额 = 命中缓存的输入 × 缓存价 + 未命中缓存的输入 × 输入价 + 输出 × 输出价，"
					+ "单价来自 ai.cost.prices（不是从代码里猜的）。");
		}
		if (reconciliation.runsWithoutSteps() > 0) {
			notes.add("⚠️ 窗口内有 " + reconciliation.runsWithoutSteps()
					+ " 次运行一个步骤都没有（大多死在第一步之前，例如上游鉴权失败）。"
					+ "它们记的是 0 token —— 那不是『没花钱』，而是『没有可记录的花费』。");
		}
		if (pricing.configured()) {
			notes.add("金额精度到 4 位小数：单次运行的成本常常远小于一分钱"
					+ "（2000 token 在 ¥1/百万 的价位上是 ¥0.002），四舍五入到分会让总额变成 0。");
		}
		notes.add("默认库是内存库（ai.agent.store.url 默认 jdbc:h2:mem:...）：进程退出即清空，"
				+ "所以『这一页只有本次启动之后的运行』是预期行为 —— "
				+ "要看多天走势必须把 url 指到文件库或真 MySQL。");
		notes.add("工具那一维没有金额，这是刻意的：token 花在模型往返上，"
				+ "工具消耗的是延迟与下游容量。");
		if (dayRows.isEmpty()) {
			notes.add("窗口内一次运行都没有 —— 先去 Agent 面板跑一个任务，这一页才有东西可算。");
		}
		return List.copyOf(notes);
	}

	// ------------------------------------------------------------------
	//  小工具
	// ------------------------------------------------------------------

	private Long totalMicros(PricedModels priced) {
		boolean any = false;
		long sum = 0;
		for (PricedRow row : priced.byModel().values()) {
			if (row.micros() != null) {
				any = true;
				sum += row.micros();
			}
		}
		if (any) {
			return sum;
		}
		// 【两种"一分钱都没有"必须区分开】
		//  ① 配了价格、但窗口里一次运行都没有 → 这是**确定的 0**（0 次运行必然花 0 元）；
		//  ② 价格压根没配 → 这是**未知**。
		// 混在一起（都返回 0，或都返回 null）会各错一半：前者让人以为功能没在算钱，
		// 后者让人以为配了价也没有数据。判据是"价格配上没有"，而不是"有没有数字"
		if (pricing.configured()) {
			return 0L;
		}
		// 【一个模型都没价时返回 null，而不是 0】
		// 0 会被读成"不花钱"；null 会让界面显示"未配置价格"。这两句话对使用者的含义完全不同
		return null;
	}

	private String reasonNotPriced(List<String> models) {
		if (!pricing.configured()) {
			return "价格未配置（ai.cost.prices 是空的）—— 这里刻意不显示 0："
					+ "0 会被读成『不花钱』，而真相是『我们不知道花多少钱』";
		}
		return "这些模型还没有价格：" + String.join("、", models) + " —— 补上之后金额才会出现";
	}

	/** 把多组聚合加起来（合计那一栏用它）。 */
	private static CostQuery.GroupTotals sum(List<CostQuery.GroupTotals> rows) {
		long runs = 0;
		long finished = 0;
		long stopped = 0;
		long failed = 0;
		long prompt = 0;
		long cached = 0;
		long completion = 0;
		long toolCalls = 0;
		long toolFailures = 0;
		long toolElapsed = 0;
		long elapsed = 0;
		Instant first = null;
		for (CostQuery.GroupTotals row : rows) {
			runs += row.runCount();
			finished += row.finishedCount();
			stopped += row.stoppedCount();
			failed += row.failedCount();
			prompt += row.promptTokens();
			cached += row.cachedPromptTokens();
			completion += row.completionTokens();
			toolCalls += row.toolCalls();
			toolFailures += row.toolFailures();
			toolElapsed += row.toolElapsedMs();
			elapsed += row.elapsedMs();
			if (row.firstAt() != null && (first == null || row.firstAt().isBefore(first))) {
				first = row.firstAt();
			}
		}
		return new CostQuery.GroupTotals(null, runs, finished, stopped, failed, prompt, cached, completion, toolCalls,
				toolFailures, toolElapsed, elapsed, first);
	}

	private static double rate(long part, long whole) {
		return whole == 0 ? 0.0 : round4((double) part / whole);
	}

	private static double round4(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

}
