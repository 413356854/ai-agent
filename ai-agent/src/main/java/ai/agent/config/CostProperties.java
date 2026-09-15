package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成本看板的配置：**价格表**（阶段四）。
 *
 * <h2>这个类里最重要的一个设计决定：价格是"数据"，而且是"可以缺失的数据"</h2>
 * 整条链路上有两类量，它们的性质完全不同：
 * <ul>
 *   <li><b>token 数是事实</b>：它由上游在响应里报给我们，我们只做聚合，不做估算。
 *       所以它**永远可用**，不需要任何配置；</li>
 *   <li><b>钱是推导量</b>：它 = token × 单价，而单价是**外部世界的商业条款** ——
 *       会变、按模型不同、还可能因为谈了个折扣而不一样。我们没法从代码里知道它。</li>
 * </ul>
 *
 * <p>所以这里刻意<b>不写死任何默认价格</b>：价格缺失时看板显示 token 与"待配置"，
 * 而不是显示 ¥0.00。理由是"看起来正常的错数字"是本项目反复记为最坏的一类观测——
 * 一个 ¥0.00 的成本看板会让人得出"这个功能不花钱"的结论，而真相是"我们不知道它花多少钱"。
 *
 * <h2>为什么价格表是"列表"而不是"每个模型一个属性"</h2>
 * 因为要支持两个现实需求：① 一个模型可能有<b>多条不同生效时间</b>的价格
 * （调价之后看历史成本不能被新价格改写）；② 模型名是上游给的字符串，
 * 写成一个 Map 的键会把"模型名里有非法字符"变成一类莫名其妙的绑定错误。
 * 做成列表之后，"哪条规则管这个模型"这件事由 {@code CostPricing} 显式决定并可被断言。
 *
 * @param currency 默认币种（价格条目没写币种时用它）
 * @param prices 价格规则
 */
@ConfigurationProperties(prefix = "ai.cost")
public record CostProperties(String currency, List<Price> prices) {

	/** 默认币种。<b>显式写在每个金额上</b>：一个不带币种的钱数在跨团队沟通时是半个信息。 */
	public static final String DEFAULT_CURRENCY = "CNY";

	/**
	 * 一条价格规则。
	 *
	 * <h2>为什么单位是"每百万 token"而不是"每 token"</h2>
	 * 因为前者是上游报价的方式（也是所有人谈论价格的方式）。
	 * 换算成"每 token"来做配置的话，配置里会出现 {@code 0.0000012} 这种数字 ——
	 * 抄错一位就是十倍，而十倍的成本误差不会让任何东西报错。
	 *
	 * <h2>为什么缓存命中要单独一档</h2>
	 * 因为它与未命中的价差是数量级级的（DeepSeek 官方口径是 50 倍）。
	 * 把它并进"输入"一档的话，成本表会稳定地高估，而高估的倍数随上游缓存命中率浮动 ——
	 * 一个我们既看不见也调不了的量。有了它，看板上"缓存命中率"才是一个**能解释钱**的数字。
	 *
	 * @param model 模型名（与上游 usage 里那个名字一致）；{@code *} 表示兜底规则
	 * @param inputPerMillion 未命中缓存的输入单价（每百万 token）
	 * @param cacheHitPerMillion 命中缓存的输入单价（每百万 token）；为 null 表示"按未命中同价"
	 * @param outputPerMillion 输出单价（每百万 token）
	 * @param currency 币种；为空时用 {@link CostProperties#currency()}
	 * @param effectiveFrom 这条价格从什么时候开始生效（ISO-8601，例如 {@code 2026-09-10}）——
	 *            用于调价之后"历史运行仍按当时的价格算"。为空表示不限时间
	 * @param source 价格的出处（文档链接或"某次商务邮件"）。
	 *            <b>它不是注释而是数据</b>：成本数字被质疑时，第一个问题永远是"这个价哪来的"
	 */
	public record Price(String model, BigDecimal inputPerMillion, BigDecimal cacheHitPerMillion,
			BigDecimal outputPerMillion, String currency, String effectiveFrom, String source) {

		public Price {
			if (model == null || model.isBlank()) {
				throw new IllegalArgumentException("价格规则必须有 model（用 * 表示兜底规则）");
			}
			if (inputPerMillion == null || outputPerMillion == null) {
				// 输入或输出缺一个就不是一条完整的价格：漏了输入会让成本只算输出那部分，
				// 而那个数字比真实值小、且完全看不出来
				throw new IllegalArgumentException("价格规则必须同时给出输入与输出单价（model=" + model + "）");
			}
			if (inputPerMillion.signum() < 0 || outputPerMillion.signum() < 0
					|| (cacheHitPerMillion != null && cacheHitPerMillion.signum() < 0)) {
				// 负价格不是"折扣"，是配置写错了。放行它会让成本表出现负数，
				// 而负数在汇总时会悄悄抵消掉别的模型的真实花费
				throw new IllegalArgumentException("单价不能为负（model=" + model + "）");
			}
		}

		/** 命中缓存的单价；没配时等于未命中单价（也就是"不假设任何缓存折扣"，偏保守）。 */
		public BigDecimal effectiveCacheHitPerMillion() {
			return cacheHitPerMillion == null ? inputPerMillion : cacheHitPerMillion;
		}

		public String effectiveCurrency(String fallback) {
			return currency == null || currency.isBlank() ? fallback : currency;
		}
	}

	/**
	 * 默认值：**空价格表**，理由见类注释。
	 *
	 * <p>顺带说明为什么"默认没有价格"不会让这个功能变成摆设：看板的主要产出是
	 * token 口径的归因（谁在花钱、哪个业务在烧钱、缓存有没有起作用），
	 * 那些数字<b>不需要价格</b>就能回答；价格只是把它们换算成金额的那一步。
	 */
	public static CostProperties defaults() {
		return new CostProperties(DEFAULT_CURRENCY, List.of());
	}

	public CostProperties {
		if (currency == null || currency.isBlank()) {
			currency = DEFAULT_CURRENCY;
		}
		// 不做 List.copyOf 的"防御性拷贝就不写了"式省略：Spring 绑定进来的是可变列表，
		// 而这张表在运行期会被并发读（看板是多线程访问的）。
		// 不做拷贝的话，一次"顺手改一下配置对象"就能让并发的读看到半张表
		prices = prices == null ? List.of() : List.copyOf(prices);
	}

}
