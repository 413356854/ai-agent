package ai.agent.cost;

import ai.agent.config.CostProperties;
import ai.agent.cost.CostQuery.DayTotals;
import ai.agent.cost.CostQuery.ModelDayRow;
import ai.agent.cost.CostQuery.ToolTotals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>看板组装逻辑的测试（用一份假的聚合结果，不碰数据库）。</b>
 *
 * <h2>这一层最容易错、也最危险的一件事</h2>
 * <b>把不能算的算成 0。</b>价格没配、模型名没上报 —— 这两种情况下若显示 ¥0.00，
 * 读者会得出「这个功能不花钱」，而真相是「我们不知道它花多少钱」。
 * 所以本测试的核心是一批"**不该出现的零**"断言：
 * {@code amount} 必须是 {@code null} 且带一句原因，而不是 {@code "0.0000"}。
 *
 * <h2>第二件事：金额必须按模型分别算完再相加</h2>
 * 一天里跑过两个模型时，{@code 当天总 token × 某一个单价} 是错的 ——
 * 而它给出的钱数看起来完全正常。这里用一个"两个模型单价差 10 倍"的构造把那件事钉住：
 * 正确答案是加权的结果，而任何"先加 token 再乘单价"的写法都会落在两端之一。
 */
class CostDashboardViewTest {

	private static final LocalDate TODAY = LocalDate.now();

	// ------------------------------------------------------------------
	//  造假的聚合结果
	// ------------------------------------------------------------------

	private static CostQuery.GroupTotals group(String key, long runs, long prompt, long cached, long completion) {
		return new CostQuery.GroupTotals(key, runs, runs, 0, 0, prompt, cached, completion, 0, 0, 0, 0,
				Instant.parse("2026-09-17T01:00:00Z"));
	}

	private static CostQuery.GroupTotals groupWithFailures(String key, long runs, long failed, long prompt,
			long cached, long completion) {
		return new CostQuery.GroupTotals(key, runs, runs - failed, 0, failed, prompt, cached, completion, 0, 0, 0, 0,
				Instant.parse("2026-09-17T01:00:00Z"));
	}

	/**
	 * 一次被上限拦下的运行：它<b>是</b>已结束（{@code finishedCount} 含它），
	 * 但既不是成功也不是失败。
	 *
	 * <p>参数刻意是"tokens"而不是 0：这个状态的整个意义就是"钱花了、事没办成"，
	 * 用一个 0 token 的样例去断言它，恰好绕开了它要证明的那件事。
	 */
	private static CostQuery.GroupTotals groupStopped(String key, long runs, long stopped, long prompt,
			long cached, long completion) {
		return new CostQuery.GroupTotals(key, runs, runs, stopped, 0, prompt, cached, completion, 0, 0, 0, 0,
				Instant.parse("2026-09-17T01:00:00Z"));
	}

	/** 一份可控的假 CostQuery。 */
	private static class FakeQuery implements CostQuery {

		List<DayTotals> days = new ArrayList<>();

		List<GroupTotals> intents = new ArrayList<>();

		List<GroupTotals> models = new ArrayList<>();

		List<ToolTotals> tools = new ArrayList<>();

		List<ModelDayRow> modelDays = new ArrayList<>();

		Reconciliation reconciliation = new Reconciliation(0, 0, 0, 0, 0, 0, 0);

		@Override
		public List<DayTotals> byDay(LocalDate from, LocalDate to) {
			return List.copyOf(days);
		}

		@Override
		public List<GroupTotals> byIntent(LocalDate from, LocalDate to) {
			return List.copyOf(intents);
		}

		@Override
		public List<GroupTotals> byModel(LocalDate from, LocalDate to) {
			return List.copyOf(models);
		}

		@Override
		public List<ToolTotals> byTool(LocalDate from, LocalDate to) {
			return List.copyOf(tools);
		}

		@Override
		public List<ModelDayRow> byModelAndDay(LocalDate from, LocalDate to) {
			return List.copyOf(modelDays);
		}

		@Override
		public Reconciliation reconcile(LocalDate from, LocalDate to) {
			return reconciliation;
		}

		@Override
		public List<String> modelsInWindow(LocalDate from, LocalDate to) {
			return models.stream().map(GroupTotals::key).filter(java.util.Objects::nonNull).toList();
		}
	}

	private static CostProperties.Price price(String model, String input, String output) {
		return new CostProperties.Price(model, new BigDecimal(input), null, new BigDecimal(output), "CNY", null,
				"测试价");
	}

	private static CostDashboardView view(FakeQuery query, CostProperties.Price... prices) {
		return new CostDashboardView(query, new CostPricing(new CostProperties("CNY", List.of(prices))));
	}

	// ------------------------------------------------------------------
	//  1. 不该出现的零
	// ------------------------------------------------------------------

	@Test
	@DisplayName("**价格没配时合计不能是 0.0000** —— 必须是 null + 一句原因，并列出待配的模型")
	void missingPriceNeverRendersAsZero() {
		FakeQuery query = new FakeQuery();
		query.days.add(new DayTotals(TODAY, group(null, 2, 10_000, 0, 5_000)));
		query.models.add(group("deepseek-flash", 2, 10_000, 0, 5_000));
		query.modelDays.add(new ModelDayRow(TODAY, "deepseek-flash", 10_000, 0, 5_000));
		CostDashboardView view = view(query); // 空价格表

		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) view.snapshot(7).get("totals");

		assertThat(totals.get("amount"))
				.as("0.0000 会被读成「不花钱」，而真相是「我们不知道花多少钱」—— "
						+ "这两种结论对使用者的含义完全相反")
				.isNull();
		assertThat((String) totals.get("amountReason")).contains("价格未配置");
		assertThat(view.snapshot(7).get("needsPriceFor")).as("未配价的模型要能被列出来（那是一份待办）")
				.asList().contains("deepseek-flash");
		assertThat(totals.get("totalTokens")).as("token 数照常显示 —— 它不需要价格就能算").isEqualTo(15_000L);
	}

	@Test
	@DisplayName("模型名没上报时，那一行**绝不能用兜底价格去算**")
	void unnamedModelIsNeverPricedEvenWithWildcardRule() {
		FakeQuery query = new FakeQuery();
		query.models.add(group(null, 1, 1_000, 0, 500));
		query.modelDays.add(new ModelDayRow(TODAY, "", 1_000, 0, 500));
		query.days.add(new DayTotals(TODAY, group(null, 1, 1_000, 0, 500)));
		// 配了兜底规则，但**没有**任何具名模型
		CostDashboardView view = view(query, price("*", "1", "2"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) view.snapshot(7).get("byModel");
		Map<String, Object> row = models.get(0);

		assertThat(row.get("keyLabel")).isEqualTo("未上报模型名");
		assertThat(row.get("amount"))
				.as("用兜底价给它算，会得出一个按别的模型定价的假数字 —— "
						+ "而有模型名时那个数字长得一模一样，没人分得出来")
				.isNull();
		assertThat((String) row.get("amountReason")).contains("没有上报模型名");
	}

	@Test
	@DisplayName("有模型没配价时合计是**下界**，必须在 notes 里说明（而不是悄悄给一个偏小的数）")
	void partialPricingIsDeclaredAsLowerBound() {
		FakeQuery query = new FakeQuery();
		query.models.add(group("priced", 1, 1_000, 0, 500));
		query.models.add(group("unpriced", 1, 1_000, 0, 500));
		query.modelDays.add(new ModelDayRow(TODAY, "priced", 1_000, 0, 500));
		query.modelDays.add(new ModelDayRow(TODAY, "unpriced", 1_000, 0, 500));
		query.days.add(new DayTotals(TODAY, group(null, 2, 2_000, 0, 1_000)));
		CostDashboardView view = view(query, price("priced", "1", "2"));

		Map<String, Object> body = view.snapshot(7);
		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) body.get("totals");

		assertThat(totals.get("allModelsPriced")).as("只要有一个模型没价，合计就不是真值").isEqualTo(false);
		assertThat(view.snapshot(7).get("needsPriceFor")).asList().contains("unpriced");
		@SuppressWarnings("unchecked")
		List<String> notes = (List<String>) body.get("notes");
		assertThat(notes).as("一个标着「合计」的下界是最容易误导的数字 —— 必须写明")
				.anyMatch(note -> note.contains("下界"));
	}

	@Test
	@DisplayName("**合计的运行级计数必须来自「按天」，不能来自「按模型」** —— 后者按设计恒为 0")
	void totalsTakeRunCountsFromDayRowsNotModelRows() {
		FakeQuery query = new FakeQuery();
		// 【这一条钉的是一个真 bug】按模型那一组的 SQL 里 finished/failed/stopped 都是写死的 0
		// （那一维按模型分组，没有运行级状态可言）。所以如果合计用 byModel 的和，
		// 真后端上"已结束"恒为 0，而假后端的手写数据给的是正确的和 —— 两条路径长得一样。
		query.models.add(groupWithFailures("m", 3, 1, 3_000, 0, 1_500)); // 模型行：failed=1，finished=2
		query.modelDays.add(new ModelDayRow(TODAY, "m", 3_000, 0, 1_500));
		// 按天行才是运行级计数的正确来源：3 次运行，全结束，其中 1 次失败、1 次被上限拦下
		query.days.add(new DayTotals(TODAY, new CostQuery.GroupTotals(null, 3, 3, 1, 1, 3_000, 0, 1_500,
				0, 0, 0, 0, Instant.parse("2026-09-17T01:00:00Z"))));

		CostDashboardView view = view(query, price("m", "1", "2"));
		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) view.snapshot(7).get("totals");

		assertThat(totals.get("finishedRuns"))
				.as("取按模型那一组的话这里会是 2（而且真后端上是 0）—— 而真相是 3 次运行全部结束")
				.isEqualTo(3L);
		assertThat(totals.get("stoppedRuns")).as("被上限拦下的那个也要能数出来").isEqualTo(1L);
		assertThat(totals.get("failedRuns")).isEqualTo(1L);
		assertThat(totals.get("totalTokens"))
				.as("token 与金额仍然来自逐模型的计算（那才是计价的来源），两边口径一致")
				.isEqualTo(4_500L);
	}

	// ------------------------------------------------------------------
	//  2. 金额必须按模型分别算完再相加
	// ------------------------------------------------------------------

	@Test
	@DisplayName("**被上限拦下的运行要单独一格透传到界面** —— 它既不是成功也不是失败，但它同样花了钱")
	void stoppedRunsReachTheDashboardWithTheirTokens() {
		FakeQuery query = new FakeQuery();
		// 1 次运行、其中 1 次被上限拦下，而它跑了 3000 token
		query.days.add(new DayTotals(TODAY, groupStopped(null, 1, 1, 2_000, 0, 1_000)));
		query.models.add(group("deepseek-flash", 1, 2_000, 0, 1_000));
		query.modelDays.add(new ModelDayRow(TODAY, "deepseek-flash", 2_000, 0, 1_000));
		CostDashboardView view = view(query, price("deepseek-flash", "1", "2"));

		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) view.snapshot(7).get("totals");

		assertThat(totals.get("stoppedRuns"))
				.as("界面要能把它单列出来 —— 只看『失败的运行』会得出『这次没出问题』，"
						+ "而真相是钱花掉了、答案一个字都没有")
				.isEqualTo(1L);
		assertThat(totals.get("failedRuns")).as("它不是失败").isEqualTo(0L);
		assertThat(totals.get("finishedRuns")).as("它算已结束（子集关系，不是互斥）").isEqualTo(1L);
		assertThat(totals.get("totalTokens"))
				.as("★ 被拦下的那次运行的 token 照样进账 —— 这一条比那个计数更重要："
						+ "计数错了只是少一格，而 token 漏了会让金额凭空变少")
				.isEqualTo(3_000L);
		// 2000 输入 × ¥1/百万 = 2000 微；1000 输出 × ¥2/百万 = 2000 微
		assertThat(totals.get("amount")).as("它花的钱照样要算出来").isEqualTo("0.0040");
	}

	@Test
	@DisplayName("**同一天两个模型：金额必须分别算完再加**，任何「先加 token 再乘单价」都会落在两端之一")
	void dayAmountSumsPerModelNotPerTokenTotal() {
		FakeQuery query = new FakeQuery();
		// 便宜模型：1000 输入 × ¥1/百万 = 1000 微；贵模型：1000 输入 × ¥10/百万 = 10000 微
		query.models.add(group("cheap", 1, 1_000, 0, 0));
		query.models.add(group("pricey", 1, 1_000, 0, 0));
		query.modelDays.add(new ModelDayRow(TODAY, "cheap", 1_000, 0, 0));
		query.modelDays.add(new ModelDayRow(TODAY, "pricey", 1_000, 0, 0));
		query.days.add(new DayTotals(TODAY, group(null, 2, 2_000, 0, 0)));
		CostDashboardView view = view(query, price("cheap", "1", "0"), price("pricey", "10", "0"));

		Map<String, Object> body = view.snapshot(7);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> days = (List<Map<String, Object>>) body.get("days");
		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) body.get("totals");

		assertThat(days).hasSize(1);
		assertThat(days.get(0).get("amountMicros"))
				.as("正确答案是 1000 + 10000 = 11000 微。"
						+ "若拿当天的 2000 token 乘便宜价会得到 2000，乘贵价会得到 20000 —— "
						+ "两个错数看起来都完全正常")
				.isEqualTo(11_000L);
		assertThat(totals.get("amountMicros")).as("合计与按天那一栏必须是同一份数字").isEqualTo(11_000L);
	}

	@Test
	@DisplayName("按模型那一栏的金额之和必须等于合计（否则三个视图各说一套）")
	void modelRowsSumToTotal() {
		FakeQuery query = new FakeQuery();
		query.models.add(group("a", 1, 1_000, 0, 1_000));
		query.models.add(group("b", 1, 2_000, 0, 2_000));
		query.modelDays.add(new ModelDayRow(TODAY, "a", 1_000, 0, 1_000));
		query.modelDays.add(new ModelDayRow(TODAY, "b", 2_000, 0, 2_000));
		query.days.add(new DayTotals(TODAY, group(null, 2, 3_000, 0, 3_000)));
		CostDashboardView view = view(query, price("a", "1", "1"), price("b", "3", "3"));

		Map<String, Object> body = view.snapshot(7);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) body.get("byModel");
		long sum = models.stream().mapToLong(row -> ((Number) row.get("amountMicros")).longValue()).sum();
		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) body.get("totals");

		assertThat(sum).as("按模型的钱加起来必须等于合计 —— 它们本就该是同一份数字的不同视图")
				.isEqualTo(((Number) totals.get("amountMicros")).longValue());
	}

	@Test
	@DisplayName("按业务那一栏（混着多个模型）不给单一金额，而是说明去哪里看")
	void mixedIntentRowExplainsInsteadOfGuessing() {
		FakeQuery query = new FakeQuery();
		query.intents.add(group("客服问答", 1, 1_000, 0, 500));
		query.models.add(group("m", 1, 1_000, 0, 500));
		query.modelDays.add(new ModelDayRow(TODAY, "m", 1_000, 0, 500));
		query.days.add(new DayTotals(TODAY, group(null, 1, 1_000, 0, 500)));
		CostDashboardView view = view(query, price("m", "1", "2"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> intents = (List<Map<String, Object>>) view.snapshot(7).get("byIntent");

		assertThat(intents.get(0).get("key")).isEqualTo("客服问答");
		assertThat(intents.get(0).get("amount"))
				.as("这一格里可能混着多个模型，单一单价不适用 —— 给一个数字必然是按某一个模型算的")
				.isNull();
		assertThat((String) intents.get(0).get("amountReason")).contains("多个模型");
	}

	// ------------------------------------------------------------------
	//  3. 契约与边界
	// ------------------------------------------------------------------

	@Test
	@DisplayName("天数被夹到上限而不是报错；窗口用闭区间")
	void daysAreClamped() {
		FakeQuery query = new FakeQuery();
		Map<String, Object> body = view(query).snapshot(100_000);

		@SuppressWarnings("unchecked")
		Map<String, Object> window = (Map<String, Object>) body.get("window");
		assertThat(window.get("days")).as("在地址栏多打一个 0 不该得到错误页，"
				+ "但也不该让一次刷新变成一次全表扫描").isEqualTo(CostDashboardView.MAX_DAYS);
		assertThat(window.get("to")).isEqualTo(TODAY.toString());
		assertThat(window.get("from")).isEqualTo(TODAY.minusDays(CostDashboardView.MAX_DAYS - 1L).toString());

		Map<String, Object> oneDay = view(query).snapshot(1);
		@SuppressWarnings("unchecked")
		Map<String, Object> narrow = (Map<String, Object>) oneDay.get("window");
		assertThat(narrow.get("from")).as("days=1 就是只有今天（闭区间）").isEqualTo(TODAY.toString());
	}

	@Test
	@DisplayName("空库（价格已配）：金额是确定的 0，不是 null")
	void emptyWindowWithPricesShowsZero() {
		Map<String, Object> body = view(new FakeQuery(), price("m", "1", "1")).snapshot(7);

		assertThat(body.get("ok")).isEqualTo(true);
		assertThat(body.get("days")).as("没有数据就是空列表，界面据此显示空状态").isEqualTo(List.of());
		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) body.get("totals");
		assertThat(totals.get("runs")).isEqualTo(0L);
		assertThat(totals.get("amount"))
				.as("价格配了、只是没有运行 —— 这是确定的 ¥0（0 次运行必然花 0 元），"
						+ "与「价格没配所以不知道花多少」是两件事")
				.isEqualTo("0.0000");
		@SuppressWarnings("unchecked")
		List<String> notes = (List<String>) body.get("notes");
		assertThat(notes).anyMatch(note -> note.contains("一次运行都没有"));
	}

	@Test
	@DisplayName("空库 + 价格没配：还是 null，不能因为「没有数据」就改口说 0")
	void emptyWindowWithoutPricesStaysUnknown() {
		Map<String, Object> body = view(new FakeQuery()).snapshot(7);

		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) body.get("totals");
		assertThat(totals.get("amount"))
				.as("「没有运行」不等于「不花钱」—— 价格没配时我们仍然不知道单价，"
						+ "给 0 会让第一次打开看板的人以为这个功能不花钱")
				.isNull();
		assertThat((String) totals.get("amountReason")).contains("价格未配置");
	}

	@Test
	@DisplayName("工具那一栏明确没有金额，并说明为什么（避免读者以为漏了）")
	void toolRowsDeclareWhyThereIsNoMoney() {
		FakeQuery query = new FakeQuery();
		query.tools.add(new ToolTotals("search_docs", 10, 1, 1, 2, 1, 1, 0, 500, 3));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tools = (List<Map<String, Object>>) view(query).snapshot(7).get("byTool");
		Map<String, Object> row = tools.get(0);

		assertThat(row.get("amount")).isNull();
		assertThat((String) row.get("amountReason")).contains("不直接消耗 token");
		assertThat(row.get("successRate")).as("成功率的口径是「模型提出的调用次数」含被拒的").isEqualTo(0.9);
	}

	@Test
	@DisplayName("对账不一致时必须在响应里明确报出来（这是看板的仪表自检）")
	void reconciliationGapIsReported() {
		FakeQuery query = new FakeQuery();
		query.reconciliation = new CostQuery.Reconciliation(1000, 900, 2, 5, 1, 1000, 100);

		@SuppressWarnings("unchecked")
		Map<String, Object> recon = (Map<String, Object>) view(query).snapshot(7).get("reconciliation");

		assertThat(recon.get("matched")).isEqualTo(false);
		assertThat(recon.get("gap")).isEqualTo(-100L);
		assertThat((String) recon.get("note")).contains("对不上");
	}

	@Test
	@DisplayName("缓存命中率全为 0 时说明「有两种可能、数据本身分不出来」，而不是下结论")
	void zeroCacheRateIsExplainedNotConcluded() {
		FakeQuery query = new FakeQuery();
		query.reconciliation = new CostQuery.Reconciliation(1000, 1000, 0, 1, 0, 1000, 0);

		@SuppressWarnings("unchecked")
		Map<String, Object> cache = (Map<String, Object>) view(query).snapshot(7).get("cache");

		assertThat(cache.get("hitRate")).isEqualTo(0.0);
		assertThat(cache.get("suspicious")).isEqualTo(true);
		assertThat((String) cache.get("note"))
				.as("「确实没命中」与「上游根本不报这个字段」在库里长得一模一样 —— "
						+ "挑一个说会把人引向错误的优化方向（去改提示词）")
				.contains("分不出来");
	}

	@Test
	@DisplayName("失败但花了钱的运行要能被单独看见（这是看板与账单截图的区别）")
	void failedRunsAreVisible() {
		FakeQuery query = new FakeQuery();
		query.days.add(new DayTotals(TODAY, groupWithFailures(null, 4, 3, 4_000, 0, 2_000)));
		query.models.add(groupWithFailures("m", 4, 3, 4_000, 0, 2_000));
		query.modelDays.add(new ModelDayRow(TODAY, "m", 4_000, 0, 2_000));
		CostDashboardView view = view(query, price("m", "1", "1"));

		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) view.snapshot(7).get("totals");

		assertThat(totals.get("failedRuns")).as("账单只知道花了多少，而「花了钱又没办成事」"
				+ "只有我们自己的数据里才有").isEqualTo(3L);
		assertThat(totals.get("finishedRuns")).isEqualTo(1L);
	}

	@Test
	@DisplayName("价格端点的响应必须自带配置写法（那是一个能照着做的待办）")
	void priceSnapshotIsActionable() {
		Map<String, Object> body = view(new FakeQuery()).priceSnapshot();

		assertThat(body.get("configured")).isEqualTo(false);
		assertThat((String) body.get("howToConfigure")).contains("ai.cost.prices[0].model");
		assertThat((String) body.get("howToConfigure")).as("调价要用新规则而不是改旧规则 —— "
				+ "改旧规则会把历史成本一起改写，且不留痕迹").contains("effective-from");
	}

	@Test
	@DisplayName("币种随价格规则走，缺省时用全局币种")
	void currencyFollowsRules() {
		FakeQuery query = new FakeQuery();
		query.models.add(group("m", 1, 1_000, 0, 0));
		query.modelDays.add(new ModelDayRow(TODAY, "m", 1_000, 0, 0));
		query.days.add(new DayTotals(TODAY, group(null, 1, 1_000, 0, 0)));

		CostProperties.Price usd = new CostProperties.Price("m", new BigDecimal("1"), null, new BigDecimal("1"),
				"USD", null, "海外价");
		CostDashboardView view = new CostDashboardView(query, new CostPricing(new CostProperties("CNY", List.of(usd))));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) view.snapshot(7).get("byModel");
		assertThat(models.get(0).get("currency")).as("金额必须带币种 —— 一个不带币种的钱数是半个信息")
				.isEqualTo("USD");
	}

	/** 让"没用到的引用"这类编译警告不至于掩盖真问题（测试里保留一个显式的契约检查）。 */
	@Test
	@DisplayName("默认窗口是 7 天（端点的默认值必须与这个常量一致）")
	void defaultWindowIsSevenDays() {
		assertThat(CostDashboardView.DEFAULT_DAYS).isEqualTo(7);
		assertThat(new LinkedHashMap<String, Object>()).isEmpty();
	}

}
