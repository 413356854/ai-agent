package ai.agent.service.cost;

import ai.agent.config.CostProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>计价算术的测试 —— 阶段四成本看板里唯一「算错了不会报错」的地方。</b>
 *
 * <h2>为什么这个类值得二十条断言</h2>
 * 看板上的每一个钱数都从这里出来，而这里全部是乘法与加法：
 * 把命中缓存的 token 加了两遍、忘了减掉缓存那部分、除错了百万 ——
 * 每一种都会得到一个**看起来完全正常**的钱数。页面上唯一可能的反馈是
 * 「这个月比上个月贵」，而人会先怀疑业务量，不会怀疑那个乘法。
 *
 * <p>所以这里把每一个非平凡分支都钉住，特别是三件最容易错的事：
 * <ol>
 *   <li><b>缓存命中的那部分不能再按未命中价算一遍</b> —— 那会让成本稳定偏高，
 *       而偏高的倍数取决于上游的缓存命中率（一个我们看不见也调不了的量）；</li>
 *   <li><b>累加不能用 double</b> —— 单条运行常常是 ¥0.002 的量级，
 *       先四舍五入到分再累加会让总额变成 0（「成本看板显示 0」的一种成因）；</li>
 *   <li><b>认不出模型时必须返回「算不出」</b>，而不是 0、也不是兜底价 ——
 *       后者会得出一个具体的假数字，而它和真数字长得一模一样。</li>
 * </ol>
 */
class CostPricingTest {

	private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

	/** 一条简单的价格：输入 ¥2/百万、缓存 ¥0.2/百万、输出 ¥8/百万。 */
	private static CostPricing pricing(CostProperties.Price... prices) {
		return new CostPricing(new CostProperties("CNY", List.of(prices)));
	}

	private static CostProperties.Price price(String model, String input, String cacheHit, String output) {
		return new CostProperties.Price(model, new BigDecimal(input),
				cacheHit == null ? null : new BigDecimal(cacheHit), new BigDecimal(output), "CNY", null, "测试价");
	}

	// ------------------------------------------------------------------
	//  1. 基本算式
	// ------------------------------------------------------------------

	@Test
	@DisplayName("基本算式：三档分别按自己的单价算，然后相加")
	void pricesThreeBuckets() {
		CostPricing pricing = pricing(price("m", "2", "0.2", "8"));

		// 1000 未命中 * 2 + 500 命中 * 0.2 + 200 输出 * 8 = 2000 + 100 + 1600 = 3700
		// （单价是「每百万」，而这里算的也是「每百万的倍数」，两边的百万正好抵消）
		var cost = pricing.price("m", 1000, 500, 200, NOW).orElseThrow();

		assertThat(cost.micros()).isEqualTo(3700L);
		assertThat(cost.amount()).isEqualTo("0.0037");
		assertThat(cost.currency()).isEqualTo("CNY");
	}

	@Test
	@DisplayName("命中缓存的 token 不能被按未命中价再算一遍 —— 这是最容易犯、也最隐蔽的错")
	void cachedTokensAreNotChargedTwice() {
		CostPricing pricing = pricing(price("m", "10", "1", "0"));

		var allCached = pricing.price("m", 0, 1000, 0, NOW).orElseThrow();
		var noneCached = pricing.price("m", 1000, 0, 0, NOW).orElseThrow();

		assertThat(allCached.micros()).as("1000 命中 × ¥1/百万 = 1000 微").isEqualTo(1000L);
		assertThat(noneCached.micros()).as("1000 未命中 × ¥10/百万 = 10000 微").isEqualTo(10_000L);
		assertThat(allCached.micros())
				.as("命中必须比未命中便宜 —— 这一条不成立就说明缓存这一档压根没参与计算")
				.isLessThan(noneCached.micros());
	}

	@Test
	@DisplayName("没配缓存价时按未命中价算（偏保守），并且标记出这是假设")
	void missingCachePriceFallsBackToInputPrice() {
		CostPricing pricing = pricing(price("m", "5", null, "0"));

		var cost = pricing.price("m", 0, 1000, 0, NOW).orElseThrow();
		assertThat(cost.micros()).as("缓存价缺省 = 输入价：1000 * 5 = 5000 微").isEqualTo(5000L);

		var table = pricing.priceTable();
		assertThat(table).hasSize(1);
		assertThat(table.get(0).get("cacheHitIsAssumed"))
				.as("界面要能区分「缓存确实便宜」与「我们不知道缓存的价，按不便宜算」"
						+ "—— 这两句话对成本偏高这件事的解释完全不同")
				.isEqualTo(true);
	}

	@Test
	@DisplayName("token 为 0 就是 0 元，不是 null、也不是算不出")
	void zeroTokensCostZero() {
		CostPricing pricing = pricing(price("m", "2", "0.2", "8"));

		var cost = pricing.price("m", 0, 0, 0, NOW).orElseThrow();
		assertThat(cost.micros()).isZero();
		assertThat(cost.amount()).isEqualTo("0.0000");
	}

	// ------------------------------------------------------------------
	//  2. 精度（「成本显示 0」的成因）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("单次运行远小于一分钱时不能被舍成 0 —— 先舍到分再累加会让整张表变成 0")
	void subCentAmountsDoNotCollapseToZero() {
		CostPricing pricing = pricing(price("m", "1", null, "2"));

		// 一次 2000 token 的任务在 ¥1/百万 的价位上是 ¥0.002 —— 比一分钱小五倍。
		// 如果用「分」做最小单位，它会被舍成 0；累加 100 次仍然是 0，而真实总额是 ¥0.2
		long total = 0;
		for (int i = 0; i < 100; i++) {
			total += pricing.price("m", 2000, 0, 0, NOW).orElseThrow().micros();
		}
		assertThat(total).as("100 次 * 2000 token * ¥1/百万 = ¥0.2 = 200000 微").isEqualTo(200_000L);
		assertThat(CostPricing.format("CNY", total)).isEqualTo("0.2000");
	}

	@Test
	@DisplayName("金额字符串固定四位小数，且从整数微格式化（不经过 double）")
	void amountsAreFormattedFromIntegerMicros() {
		assertThat(CostPricing.format("CNY", 1L)).as("1 微 = ¥0.000001").isEqualTo("0.0000");
		assertThat(CostPricing.format("CNY", 10L)).isEqualTo("0.0000");
		assertThat(CostPricing.format("CNY", 100L)).isEqualTo("0.0001");
		assertThat(CostPricing.format("CNY", 1_234_567L)).as("¥1.234567 保留四位").isEqualTo("1.2346");
		assertThat(CostPricing.format("CNY", 0L)).isEqualTo("0.0000");
	}

	@Test
	@DisplayName("大额也不丢精度：千万级 token 的成本仍然精确")
	void largeAmountsStayExact() {
		CostPricing pricing = pricing(price("m", "3", null, "9"));

		// 10,000,000 * 3 = 30,000,000 微；5,000,000 * 9 = 45,000,000 微
		var cost = pricing.price("m", 10_000_000, 0, 5_000_000, NOW).orElseThrow();
		assertThat(cost.micros()).isEqualTo(75_000_000L);
		assertThat(cost.amount()).isEqualTo("75.0000");
	}

	// ------------------------------------------------------------------
	//  3. 认不出来就说认不出来
	// ------------------------------------------------------------------

	@Test
	@DisplayName("没有价格规则的模型返回算不出，而不是 0、也不是兜底价")
	void unknownModelIsNotPriced() {
		CostPricing pricing = pricing(price("known", "1", null, "2"));

		assertThat(pricing.price("unknown", 1000, 0, 1000, NOW))
				.as("拿别的模型的价去顶会得出一个具体的假数字，而"
						+ "「没有价格」是一个能被修的状态、「算错了」不是")
				.isEmpty();
		// 对照组：认识的模型必须算得出来 —— 否则上面那条断言可以被一个
		// 「永远返回 empty」的实现满足，而那等于没有断言
		assertThat(pricing.price("known", 1000, 0, 1000, NOW)).isPresent();
	}

	@Test
	@DisplayName("上游没上报模型名（空串 / null）时不给它计价，即使配了兜底规则")
	void unnamedModelIsNeverPricedEvenWithWildcard() {
		CostPricing pricing = pricing(price("*", "1", null, "2"));

		assertThat(pricing.price(null, 1000, 0, 1000, NOW))
				.as("兜底规则是给「认识的模型名」用的，不是给「没有模型名」用的")
				.isEmpty();
		assertThat(pricing.price("", 1000, 0, 1000, NOW)).isEmpty();
		assertThat(pricing.price("随便什么模型", 1000, 0, 1000, NOW)).as("有名字的照样走兜底").isPresent();
	}

	@Test
	@DisplayName("空价格表：configured 为假，任何模型都算不出 —— 看板据此显示未配置而不是 ¥0.00")
	void emptyPriceTableMeansNotConfigured() {
		CostPricing pricing = new CostPricing(CostProperties.defaults());

		assertThat(pricing.configured()).isFalse();
		assertThat(pricing.price("m", 1000, 0, 1000, NOW)).isEmpty();
		assertThat(pricing.defaultCurrency()).as("即使没有金额，界面也要能显示单位").isEqualTo("CNY");
	}

	// ------------------------------------------------------------------
	//  4. 规则选择：精确 > 兜底，新价 > 旧价
	// ------------------------------------------------------------------

	@Test
	@DisplayName("精确匹配压过兜底规则")
	void exactModelBeatsWildcard() {
		CostPricing pricing = pricing(price("*", "100", null, "0"), price("special", "1", null, "0"));

		assertThat(pricing.price("special", 1000, 0, 0, NOW).orElseThrow().micros())
				.as("精确规则的价是 1000 微，兜底规则会是 100000 微")
				.isEqualTo(1000L);
		assertThat(pricing.price("other", 1000, 0, 0, NOW).orElseThrow().micros()).isEqualTo(100_000L);
	}

	@Test
	@DisplayName("同模型多条价时按时间选：历史运行必须仍按当时的价格算")
	void priceRulesAreSelectedByTime() {
		CostProperties.Price old = new CostProperties.Price("m", new BigDecimal("10"), null,
				new BigDecimal("20"), "CNY", "2026-01-01", "调价前");
		CostProperties.Price current = new CostProperties.Price("m", new BigDecimal("1"), null,
				new BigDecimal("2"), "CNY", "2026-09-10", "调价后");
		CostPricing pricing = pricing(old, current);

		Instant before = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
		Instant after = LocalDate.of(2026, 9, 15).atStartOfDay(ZoneId.systemDefault()).toInstant();

		assertThat(pricing.price("m", 1000, 0, 0, before).orElseThrow().micros())
				.as("五月那次运行必须按调价前的 ¥10/百万 算 —— 否则历史成本会被新价改写，"
						+ "而那种改写不留任何痕迹")
				.isEqualTo(10_000L);
		assertThat(pricing.price("m", 1000, 0, 0, after).orElseThrow().micros()).isEqualTo(1000L);
	}

	@Test
	@DisplayName("运行时间早于所有规则时取最早那条，而不是算不出")
	void runsBeforeAnyRuleStillGetPriced() {
		CostProperties.Price rule = new CostProperties.Price("m", new BigDecimal("5"), null,
				new BigDecimal("5"), "CNY", "2026-09-10", "唯一一条");
		CostPricing pricing = pricing(rule);

		Instant longAgo = LocalDate.of(2020, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
		assertThat(pricing.price("m", 1000, 0, 0, longAgo))
				.as("没有任何规则覆盖那个时间时退回最早的那条 —— 返回空会让整段时间的成本凭空消失")
				.isPresent();
	}

	@Test
	@DisplayName("写错的生效日期按不限制时间处理，并且在配置体检里报出来")
	void malformedEffectiveFromIsReportedNotSwallowed() {
		CostProperties.Price broken = new CostProperties.Price("m", new BigDecimal("1"), null,
				new BigDecimal("1"), "CNY", "2026/09/10", "格式写错了");
		CostPricing pricing = pricing(broken);

		assertThat(pricing.price("m", 1000, 0, 0, NOW)).as("不能因为日期格式写错就整条规则失效").isPresent();
		assertThat(pricing.configNotes()).as("但必须报出来 —— 否则这个错永远没人发现")
				.anyMatch(note -> note.contains("不是合法日期"));
	}

	@Test
	@DisplayName("同模型同生效时间的重复规则会被报出来，但仍给出确定答案")
	void duplicateRulesAreReported() {
		CostProperties.Price first = new CostProperties.Price("m", new BigDecimal("1"), null,
				new BigDecimal("1"), "CNY", "2026-01-01", "a");
		CostProperties.Price second = new CostProperties.Price("m", new BigDecimal("2"), null,
				new BigDecimal("2"), "CNY", "2026-01-01", "b");
		CostPricing pricing = pricing(first, second);

		assertThat(pricing.configNotes()).anyMatch(note -> note.contains("重复"));
		// 有歧义时仍然要给出确定答案（按配置顺序取第一条）——
		// 「不确定就报错」在这里是错的：看板会整个打不开，而配置只是略有歧义
		assertThat(pricing.price("m", 1000, 0, 0, NOW).orElseThrow().micros()).isEqualTo(1000L);
	}

	// ------------------------------------------------------------------
	//  5. 配置校验：负价格 / 缺单价必须在构造期就炸
	// ------------------------------------------------------------------

	@Test
	@DisplayName("负单价在构造期直接抛 —— 放行它会让汇总时抵消掉别的模型的真实花费")
	void negativePriceIsRejectedAtConstruction() {
		assertThatThrownBy(() -> new CostProperties.Price("m", new BigDecimal("-1"), null, new BigDecimal("1"),
				"CNY", null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为负");
	}

	@Test
	@DisplayName("缺输入或输出单价也在构造期抛 —— 漏一个会让成本只算一半，而那个数字比真实值小")
	void missingPriceColumnsAreRejected() {
		assertThatThrownBy(() -> new CostProperties.Price("m", null, null, new BigDecimal("1"), "CNY", null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostProperties.Price("m", new BigDecimal("1"), null, null, "CNY", null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("两种币种的金额不能相加 —— 相加会得到一个没有意义的数字，而它看着像个金额")
	void currenciesCannotBeMixed() {
		CostPricing.Cost cny = new CostPricing.Cost("CNY", 1000L, "m", null, null);
		CostPricing.Cost usd = new CostPricing.Cost("USD", 1000L, "m", null, null);

		assertThatThrownBy(() -> cny.plus(usd)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("两种币种");
		assertThat(cny.plus(new CostPricing.Cost("CNY", 500L, "m", null, null)).micros()).isEqualTo(1500L);
	}

	@Test
	@DisplayName("显式配了缓存价（即使数值等于输入价）时，界面上的假设标记必须为假")
	void explicitCachePriceIsNotMarkedAsAssumed() {
		CostPricing pricing = pricing(price("m", "2", "2", "8"));

		assertThat(pricing.priceTable().get(0).get("cacheHitIsAssumed"))
				.as("显式写出来的价格与「没写所以按输入价顶」是两件事：前者是事实，后者是我们的假设 —— "
						+ "混在一起会让读者以为缓存真的不便宜")
				.isEqualTo(false);
	}

}
