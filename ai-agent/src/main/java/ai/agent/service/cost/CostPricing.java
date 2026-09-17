package ai.agent.service.cost;

import ai.agent.config.CostProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>把 token 换算成钱的那唯一一处实现</b>（阶段四成本看板）。
 *
 * <h2>为什么它是一个纯类，而不是散在看板服务里的几行乘法</h2>
 * 因为这一类计算有一个致命特征：<b>算错了不会报错</b>。少了缓存折扣、把命中的
 * token 加了两遍、除以一百万时先除了再加 —— 每一种都会得到一个"看起来完全正常"的钱数。
 * 看板上唯一的反馈是"这个月比上个月贵"，而人会先怀疑业务量，不会怀疑那个乘法。
 *
 * <p>所以这里把全部算术收进一个**没有 IO、没有 Spring、可以逐条断言**的类：
 * 每个非平凡分支都有一个测试盯着（见 {@code CostPricingTest}），
 * 而看板服务只负责"取数 → 调它 → 放进响应"。
 *
 * <h2>钱用"微"（百万分之一货币单位）做整数运算</h2>
 * <ul>
 *   <li>不用 {@code double}：累加几千条记录之后，{@code double} 的表示误差会让总额
 *       出现"差一分钱"的现象，而那种差异会被当成"数据有问题"去查上一整天；</li>
 *   <li>不在每条记录上四舍五入到"分"：单条运行的成本常常**远小于一分钱**
 *       （一次 2000 token 的任务在 ¥1/百万 的价位上就是 ¥0.002），
 *       先舍到分再累加会让总额低估到零 —— 而这正是"成本看板显示 ¥0.00"的一种成因。</li>
 * </ul>
 * 所以内部一律 long 微，只在**渲染那一刻**才变成给人看的字符串。
 *
 * <h2>认不出模型时返回"算不出"，而不是 0</h2>
 * 与 {@code ModelFailureClassifier} 的兜底是同一条规矩：<b>没有价格就是没有价格</b>。
 * 拿别的模型的价去顶，会得出一个具体的假数字；而 {@code Optional.empty()} 会让看板
 * 明确显示"这个模型还没有价格"，那是一个**能被修**的状态。
 */
public class CostPricing {

	/** 每百万 token 的价格要除以这个数，才能变成"每 token"。 */
	private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000L);

	/** 微：货币单位的百万分之一。{@code micros = 金额 × 1_000_000}。 */
	private static final BigDecimal MICROS_PER_UNIT = BigDecimal.valueOf(1_000_000L);

	private final CostProperties properties;

	public CostPricing(CostProperties properties) {
		this.properties = properties;
	}

	/** 有没有配置过任何价格（看板据此决定"钱"这一块能不能显示）。 */
	public boolean configured() {
		return !properties.prices().isEmpty();
	}

	public String defaultCurrency() {
		return properties.currency();
	}

	/**
	 * 算一条（或一组同模型记录）的成本。
	 *
	 * @param model 模型名（上游 usage 里那个）
	 * @param uncachedInputTokens 未命中缓存的输入 token
	 * @param cachedInputTokens 命中缓存的输入 token（{@code promptTokens} 的子集）
	 * @param outputTokens 输出 token
	 * @param at 这批记录发生的时间 —— <b>用于选"当时生效"的那条价格</b>。
	 *           传 null 表示不限制时间（用于"现在就报个价"这类展示场景）
	 * @return 成本；没有匹配的价格规则时为空
	 */
	public Optional<Cost> price(String model, long uncachedInputTokens, long cachedInputTokens, long outputTokens,
			Instant at) {
		Optional<CostProperties.Price> rule = match(model, at);
		if (rule.isEmpty()) {
			return Optional.empty();
		}
		CostProperties.Price price = rule.get();

		// 【三个加数分别是什么，不能弄混】
		//   命中缓存的输入 × 缓存价（便宜）
		// + 未命中缓存的输入 × 输入价（贵）
		// + 输出 × 输出价
		// 把前两项合成 "promptTokens × 输入价" 是最常见的一种写法，
		// 而它会让成本稳定偏高 —— 偏高多少取决于上游缓存命中率
		long micros = 0;
		micros += perTokenMicros(price.effectiveCacheHitPerMillion(), cachedInputTokens);
		micros += perTokenMicros(price.inputPerMillion(), uncachedInputTokens);
		micros += perTokenMicros(price.outputPerMillion(), outputTokens);

		return Optional.of(new Cost(price.effectiveCurrency(properties.currency()), micros, price.model(),
				price.effectiveFrom(), price.source()));
	}

	/**
	 * "N 个 token 按单价 P（每百万）算是多少微"。
	 *
	 * <p>把 {@code N × P ÷ 1_000_000 × 1_000_000} 里的两个百万约掉之后，
	 * 这里其实只需要 {@code N × P} —— 但**刻意不约**，而是老老实实按"先算钱、再换成微"
	 * 写出来。理由是：约掉之后那个表达式（{@code tokens * price}）与"每百万"这个单位
	 * 之间的关系就消失了，下一个人改价格单位（换成"每千 token"）时不会意识到要动它。
	 * 保留单位链路，代码本身就是那份文档。
	 */
	private static long perTokenMicros(BigDecimal pricePerMillion, long tokens) {
		if (tokens <= 0) {
			// token 为 0 就是 0 微。**不能因为"反正乘出来是 0"就跳过** ——
			// 真正要跳过的是"负数 token"，那说明上游报的数自相矛盾，
			// 让它算出负成本是最坏的结果（负成本会在汇总时抵消掉别的真实花费）
			return 0L;
		}
		BigDecimal units = pricePerMillion.multiply(BigDecimal.valueOf(tokens)).divide(PER_MILLION, 10,
				RoundingMode.HALF_UP);
		return units.multiply(MICROS_PER_UNIT).setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	/**
	 * 找出管这个模型的价格规则。
	 *
	 * <h2>两轮匹配，顺序是刻意的</h2>
	 * <ol>
	 *   <li>先找"这个模型 + 在那个时刻已生效"的规则，同等具体时取生效时间最晚的
	 *       （调价之后当然用新价）；</li>
	 *   <li>一条都没有时，退回"这个模型最早的一条规则"。</li>
	 * </ol>
	 * 第二轮不是宽容，而是防止一个很具体的错误：<b>如果所有规则都还没生效，
	 * 第一轮会一条都匹配不到，于是整段时间的成本凭空消失</b>
	 * （界面显示"没有价格规则"，而配置里明明写着那个模型）。
	 * 用最早的那条是唯一合理的近似 —— 它至少是同一个模型的价。
	 *
	 * <h2>没有模型名时直接返回空</h2>
	 * 兜底规则（{@code *}）是给"认识的模型名"用的，<b>不是</b>给"没有模型名"用的。
	 * 这一个判断必须放在最前面：少了它，上游漏报模型名（空串）的运行会被兜底价定价，
	 * 得出一个按别的模型算出来的假金额 —— 而它和真金额长得一模一样。
	 */
	public Optional<CostProperties.Price> match(String model, Instant at) {
		if (model == null || model.isBlank()) {
			return Optional.empty();
		}
		String wanted = model.trim();
		List<CostProperties.Price> byModel = new ArrayList<>();
		for (CostProperties.Price price : properties.prices()) {
			if (matchesModel(price, wanted)) {
				byModel.add(price);
			}
		}
		if (byModel.isEmpty()) {
			return Optional.empty();
		}

		List<CostProperties.Price> effective = new ArrayList<>();
		for (CostProperties.Price price : byModel) {
			if (effectiveAt(price, at)) {
				effective.add(price);
			}
		}
		List<CostProperties.Price> pool = effective.isEmpty() ? byModel : effective;

		pool.sort((left, right) -> {
			// 精确匹配排在兜底规则前面。
			// 踩过：这里第一版写的是 compare(isWildcard(right), isWildcard(left))，
			// 排序方向反了，于是 `*` 那条兜底规则反而排在前面 ——
			// 结果是一个配了专属价的模型被按兜底价算了 100 倍，
			// 而页面上完全看不出来（只有一个金额，没有"用了哪条规则"的对照）
			int byExact = Boolean.compare(isWildcard(left), isWildcard(right));
			if (byExact != 0) {
				return byExact;
			}
			// 生效时间晚的优先（新的价管现在）。这一条同样是"倒着排"：
			// compareEffectiveFrom(right, left) 才是"晚的在前"
			return compareEffectiveFrom(right.effectiveFrom(), left.effectiveFrom());
		});
		return Optional.of(pool.get(0));
	}

	/** 这个模型名能不能用这条规则。{@code *} 是兜底规则。 */
	private static boolean matchesModel(CostProperties.Price price, String model) {
		return isWildcard(price) || price.model().equalsIgnoreCase(model);
	}

	private static boolean isWildcard(CostProperties.Price price) {
		return "*".equals(price.model().trim());
	}

	/**
	 * 这条价格在 {@code at} 时刻生效了吗。
	 *
	 * <p>解析不出来的日期<b>按"不限制时间"处理</b>（也就是生效），并在
	 * {@link #configNotes()} 里报出来 —— 一条写错日期的价格规则如果被静默忽略，
	 * 现象是"成本全变成未配置"，而人会去查价格表本身而不是那个日期字符串。
	 */
	private static boolean effectiveAt(CostProperties.Price price, Instant at) {
		if (price.effectiveFrom() == null || price.effectiveFrom().isBlank() || at == null) {
			return true;
		}
		LocalDate from = parseDate(price.effectiveFrom());
		if (from == null) {
			return true;
		}
		LocalDate when = LocalDate.ofInstant(at, ZoneId.systemDefault());
		return !when.isBefore(from);
	}

	private static LocalDate parseDate(String raw) {
		try {
			return LocalDate.parse(raw.trim());
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static int compareEffectiveFrom(String left, String right) {
		LocalDate l = left == null || left.isBlank() ? LocalDate.MIN : parseDate(left);
		LocalDate r = right == null || right.isBlank() ? LocalDate.MIN : parseDate(right);
		if (l == null) {
			l = LocalDate.MIN;
		}
		if (r == null) {
			r = LocalDate.MIN;
		}
		return l.compareTo(r);
	}

	/**
	 * 价格表本身的问题（写错的日期、重复生效且同等具体的规则）。
	 *
	 * <p>为什么要专门给一个"配置体检"入口：价格表是一份**只读的、外部来的**数据，
	 * 而它出错时表现安静（成本按某一条算，看起来正常）。把这些问题主动报在
	 * 看板响应里，等于让配置错误**在有人看的地方现形**。
	 */
	public List<String> configNotes() {
		List<String> notes = new ArrayList<>();
		Map<String, Integer> seen = new LinkedHashMap<>();
		for (CostProperties.Price price : properties.prices()) {
			if (price.effectiveFrom() != null && !price.effectiveFrom().isBlank()
					&& parseDate(price.effectiveFrom()) == null) {
				notes.add(("价格规则「%s」的 effective-from 不是合法日期（%s）—— 已按"
						+ "『不限制时间』处理").formatted(price.model(), price.effectiveFrom()));
			}
			String key = price.model() + "@" + price.effectiveFrom();
			seen.merge(key, 1, Integer::sum);
		}
		for (Map.Entry<String, Integer> entry : seen.entrySet()) {
			if (entry.getValue() > 1) {
				notes.add("价格规则「%s」重复了 %d 次（同一个模型 + 同一个生效时间）—— 会按配置里靠前的那条算"
						.formatted(entry.getKey(), entry.getValue()));
			}
		}
		return List.copyOf(notes);
	}

	/** 价格表快照（给界面显示"现在是按什么价在算"）。 */
	public List<Map<String, Object>> priceTable() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (CostProperties.Price price : properties.prices()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("model", price.model());
			row.put("inputPerMillion", price.inputPerMillion().toPlainString());
			row.put("cacheHitPerMillion", price.effectiveCacheHitPerMillion().toPlainString());
			// 显式告诉界面"缓存价是配的还是沿用输入价"：两者在页面上的含义不同
			// （一个是"缓存确实便宜"，一个是"我们不知道缓存的价，按不便宜算"）
			row.put("cacheHitIsAssumed", price.cacheHitPerMillion() == null);
			row.put("outputPerMillion", price.outputPerMillion().toPlainString());
			row.put("currency", price.effectiveCurrency(properties.currency()));
			row.put("effectiveFrom", price.effectiveFrom() == null ? "" : price.effectiveFrom());
			row.put("source", price.source() == null ? "" : price.source());
			rows.add(row);
		}
		return List.copyOf(rows);
	}

	/**
	 * 一条记录的成本。
	 *
	 * @param currency 币种
	 * @param micros 金额（货币单位的百万分之一）
	 * @param ruleModel 命中的规则里的模型（{@code *} 表示兜底规则命中）
	 * @param effectiveFrom 命中的那条价格何时生效
	 * @param source 价格出处
	 */
	public record Cost(String currency, long micros, String ruleModel, String effectiveFrom, String source) {

		/** 金额的可读字符串：固定 4 位小数（微的精度不需要全展示，但也不能四舍五入到分）。 */
		public String amount() {
			return BigDecimal.valueOf(micros).divide(MICROS_PER_UNIT, 4, RoundingMode.HALF_UP).toPlainString();
		}

		public Cost plus(Cost other) {
			if (!currency.equals(other.currency)) {
				throw new IllegalArgumentException(
						"不能把两种币种的金额相加：" + currency + " + " + other.currency());
			}
			return new Cost(currency, micros + other.micros, ruleModel, effectiveFrom, source);
		}

		public static Cost zero(String currency) {
			return new Cost(currency, 0L, "", "", "");
		}
	}

	/** 金额渲染的公共入口（响应里所有钱都走它，避免两处小数位不一致）。 */
	public static String format(String currency, long micros) {
		return new Cost(currency, micros, "", "", "").amount();
	}

}
