package ai.agent.service.alert;

import java.time.Instant;
import java.util.List;

/**
 * <b>一次评估的全部输入</b>（阶段四第 5 件：异常告警）。
 *
 * <h2>为什么判据的输入是一个不可变快照，而不是"让每条判据自己去查库"</h2>
 * 三个理由，每个都在别处吃过亏：
 * <ol>
 *   <li><b>一致性</b>：一次评估里的九条判据必须看到<b>同一时刻</b>的数据。
 *       各自去查库的话，两条判据可能读到不同的窗口（中间恰好有新运行落库），
 *       于是"对账差值为 0"与"有运行没有步骤"可以同时成立 —— 一个自相矛盾的报告，
 *       而读的人只会信其中一条；</li>
 *   <li><b>可测</b>：判据是这一件里最需要单独测的东西，而"用手造的输入断言判据"
 *       不需要起数据库、不需要造数据 —— {@code AlertRuleCatalogTest} 就是这样测的；</li>
 *   <li><b>判据与取数分开</b>：取数（SQL）会随存储变化，判据（什么算异常）不该跟着变。
 *       这与成本看板"聚合放 SQL、口径放代码"是同一条分界。</li>
 * </ol>
 *
 * <h2>⚠️ 计数器是"累积的"而不是"窗口内的" —— 这一点必须说清</h2>
 * {@link #consecutiveEmptyRetrievals()} 的来源是一条 {@code ORDER BY ... DESC LIMIT n}
 * 的查询，所以它描述的是"<b>最近若干次检索</b>里连续空命中的次数"，
 * 而不是"这个时间窗内连续空命中的次数"。
 * 对"连续"这个形态来说这是对的（它问的本来就是最近的状态），
 * 但如果哪一天有人把它读成"窗口内的统计"，结论就会偏 —— 所以写在这里，
 * 而不是留给读者从 SQL 里推。
 */
public record RuntimeSnapshot(

		/** 快照的时间窗（含两端）。 */
		Instant observedAt,

		/** 窗口的起点（判据明细里会带上它 —— 没有窗口的告警数字是不可复现的）。 */
		String windowFrom,

		/** 窗口的终点。 */
		String windowTo,

		// ---- 成本口径 ----

		/** {@code ai.cost.prices} 配上没有。 */
		boolean pricingConfigured,

		/**
		 * 窗口内出现过、却匹配不到价格规则的模型名（显示名，可能含「未上报模型名」）。
		 *
		 * <p>它<b>包含</b>两种完全不同的东西，而判据那条 action 里专门讲了为什么
		 * 不能靠补价格解决其中一种。
		 */
		List<String> unpricedModels,

		/** 两个 token 口径的差（步骤口径 − 运行口径）；0 才算对上。 */
		long reconcileGap,

		/** 步骤口径的 token 合计（看板的钱按它算）。 */
		long stepLevelTokens,

		/** 运行口径的 token 合计（运行列表与 token 上限按它算）。 */
		long runLevelTokens,

		/** 窗口内"有运行行、一个步骤都没有"的次数。 */
		long runsWithoutSteps,

		/** 窗口内的输入 token 合计（含命中缓存的部分）。 */
		long promptTokens,

		/** 其中命中提示词缓存的部分。 */
		long cachedPromptTokens,

		// ---- 检索口径（第十三轮 trace 加的 retrieval_call 表） ----

		/** 最近若干次检索里，从最后一次往前数、连续空命中的次数。 */
		int consecutiveEmptyRetrievals,

		/** 最后一次检索时索引里有多少块（回答"是不是索引根本没建起来"）。 */
		int lastIndexSize,

		/** 窗口内一共发生了几次检索（为 0 时上面的"连续"无从谈起）。 */
		long retrievalCalls,

		// ---- 灰度口径（第 5 件新增） ----

		/** 灰度分流的观测结果。 */
		AlertRule.CanarySplit canary,

		// ---- 租户口径（阶段四第 6 件：成本归因新增） ----

		/**
		 * <b>每个租户</b>在窗口内的输入 token 与其中命中缓存的部分。
		 *
		 * <h2>为什么成本判据需要"按租户"的输入，而不是一个窗口合计</h2>
		 * 在租户维度出现之前，"整窗缓存命中率为 0"是一个有意义的信号。
		 * 出现之后它变成了一句**可能掩盖问题的话**：窗口里只要有一个租户命中过缓存，
		 * 合计就不再是 0，而"某个租户的缓存一次都没命中"这个真正可处置的事实
		 * （它的提示词前缀可能每次都在变、或者它走的是另一条链路）就永远看不见了。
		 *
		 * <p>所以这一族判据的主体粒度改成了<b>每个租户一条</b>（见
		 * {@code AlertEvaluator.judge} 里那处显式的 switch）。
		 *
		 * <p>⚠️ <b>未标注租户的运行不在这个列表里</b>：它们不是一个主体，
		 * 而是一堆互不相干的运行的合计 —— 把那个合计当成"一个租户"去报警，
		 * 会让告警指向一个不存在的东西。它们由窗口合计那一条（{@code windowPromptTokens}）覆盖。
		 */
		List<TenantPoint> tenantCaches,
		/**
		 * 窗口内**单个租户占掉全部 token 的比例**最多是多少，那又是哪个租户。
		 *
		 * <p>为 {@code null} 表示"无从谈起"（没有租户信息、或窗口内没有 token）——
		 * 与"比例是 0"是两件事，后者会读成"没有租户在花钱"。
		 */
		AlertRule.TenantShare tenantShare) {

	/**
	 * 紧凑构造器：把可能为 null 的几处归一化。
	 *
	 * <p>判据里少一处判空就少一个坑 —— 而归一化只能有一处
	 * （与 {@code AgentRunStore.Start} 那条"空白一律在构造期归一"同一条纪律）。
	 */
	public RuntimeSnapshot {
		unpricedModels = unpricedModels == null ? List.of() : List.copyOf(unpricedModels);
		tenantCaches = tenantCaches == null ? List.of() : List.copyOf(tenantCaches);
		canary = canary == null ? AlertRule.CanarySplit.none() : canary;
		windowFrom = windowFrom == null ? "" : windowFrom;
		windowTo = windowTo == null ? "" : windowTo;
		observedAt = observedAt == null ? Instant.now() : observedAt;
	}

	/**
	 * 窗口内某一段时间的输入 token 与其中的缓存命中量（成本判据的输入）。
	 *
	 * <p>租户口径那一族判据读的就是它 —— 见 {@link #tenantCaches}。
	 * 它<b>刻意不复用 {@code CostQuery.GroupTotals}</b>：那个类型带着十来个
	 * 与判据无关的字段（工具耗时、运行状态计数…），把它塞进快照会让"判据依赖什么"
	 * 这件事在一堆字段里消失，而"判据只读它真正需要的东西"是这一层能被单独测的前提。
	 *
	 * @param tenant 租户名（**不会是"未标注"** —— 那种运行是几段互不相干的花费，
	 *            不是一个主体，见 {@link #tenantCaches}）
	 * @param promptTokens 这个租户的输入 token 合计（含命中缓存的部分）
	 * @param cachedPromptTokens 其中命中提示词缓存的部分
	 */
	public record TenantPoint(String tenant, long promptTokens, long cachedPromptTokens) {
	}

	/** 窗口的一句话描述（告警明细里带上它，数字才可复现）。 */
	public String windowLabel() {
		return windowFrom + " ~ " + windowTo;
	}

	/**
	 * <b>切一份"只看这一个租户"的快照</b>（阶段四第 6 件：逐租户判据的输入）。
	 *
	 * <h2>为什么是"切一份"而不是"把租户当参数传进判据"</h2>
	 * 判据的签名保持 {@code RuntimeSnapshot -> Verdict} 不变，才能继续满足
	 * "判据是纯函数、只读快照、能在毫秒级被单独测"这条纪律。
	 * 换签名会让每条判据都多一个可能被忽略的参数，而"某条判据忘了看那一个参数"
	 * 是没有任何症状的错。
	 *
	 * <h2>⚠️ 只换两个数，其余字段一律原样保留</h2>
	 * 顺手把别的字段也按租户重算（比如 unpricedModels）是个诱人的想法，但它会让
	 * <b>这条判据的结论随别的维度变化</b> —— 那种耦合没有症状，只是结论偶尔不同，
	 * 而"偶尔不同"的判据比错的判据更难查。
	 * 所以这里只动 {@code promptTokens} / {@code cachedPromptTokens} 两个字段，
	 * 并写明为什么，而不是"看起来更彻底"地全部重算。
	 */
	public RuntimeSnapshot forTenant(TenantPoint tenant) {
		return new RuntimeSnapshot(observedAt, windowFrom, windowTo, pricingConfigured, unpricedModels,
				reconcileGap, stepLevelTokens, runLevelTokens, runsWithoutSteps,
				tenant.promptTokens(), tenant.cachedPromptTokens(),
				consecutiveEmptyRetrievals, lastIndexSize, retrievalCalls,
				canary, tenantCaches, tenantShare);
	}

}
