package ai.agent.service.alert;

import ai.agent.entity.alert.RuntimeSnapshot;
import ai.agent.service.impl.agent.store.JdbcAgentRunStore;
import ai.agent.service.canary.CanaryService;
import ai.agent.service.cost.CostPricing;
import ai.agent.service.cost.CostQuery;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AlertSnapshotQuery} 的生产实现（阶段四第 5 件）。
 *
 * <h2>★ 这里只做一件事：把已经存在的三个口径读成一份快照</h2>
 * 本类<b>不产生任何新数据</b>，也不重新定义任何口径：
 * <table border="1">
 *   <caption>三个来源各管什么</caption>
 *   <tr><th>来源</th><th>贡献</th><th>为什么用它而不是自己查</th></tr>
 *   <tr><td>{@link CostQuery}</td><td>价格配置 / 未配价的模型 / 对账差值 / 缓存命中</td>
 *       <td>这些口径在成本看板那一轮已经定过并测过。重新写一条 SQL 意味着
 *           "同一件事有两份实现"，而两份迟早会对不上 —— 那时告警与看板会互相矛盾</td></tr>
 *   <tr><td>{@code retrieval_call}（第十三轮）</td><td>连续空命中次数 / 索引规模</td>
 *       <td>那两张表就是为这件事建的（台账点名要监测 {@code hit_count = 0}）</td></tr>
 *   <tr><td>{@link CanaryService}</td><td>灰度分流分布</td>
 *       <td>"配置了多少"与"实际分到多少"必须一起读，否则判据会拿两个不同时刻的事实比</td></tr>
 * </table>
 *
 * <h2>⚠️ 一个刻意的取舍：检索那一路是"最近 N 次"，不是"窗口内连续 N 次"</h2>
 * 判据是"连续空命中"（见 {@link AlertRule#EMPTY_RETRIEVAL} 里为什么不是累计），
 * 而实现是"按时间倒序取最近若干条，从最新往前数连续 0 命中的次数"。
 * 这与"窗口内连续"的差别在一种情形下会显形：窗口内只有 1 次检索、
 * 而它恰好是空命中 —— 那时计数是 1，不会到 3，不报。这是<b>对的</b>
 * （一次空命中不构成"状态"）。
 * 反过来的情形：最近 3 次检索都在窗口之外（比如窗口设成 1 天而最近检索在昨天），
 * 那时会因为"取不到 3 条"而不报 —— 也是对的，因为那个窗口内本来就没有检索。
 */
@Component
public class JdbcAlertMetricsQuery implements AlertSnapshotQuery {

	private static final Logger log = LoggerFactory.getLogger(JdbcAlertMetricsQuery.class);

	/** 「上游没上报模型名」的显示名。**只在这里定义一次** —— 成本看板那一侧也读它。 */
	public static final String UNKNOWN_MODEL_LABEL = "未上报模型名";

	/**
	 * 连续空命中最多往回数多少次。
	 *
	 * <p>它只影响"最多能数出多大的连续数"，不影响判据（判据的阈值是 3）。
	 * 取阈值的 3 倍：够长到能报出"连续 9 次都没命中"这种更严重的情形，
	 * 又不会让一次评估去读一整张表。
	 */
	private static final int EMPTY_STREAK_SCAN = AlertRule.CONSECUTIVE_EMPTY_RETRIEVALS * 3;

	private final CostQuery costQuery;

	private final CostPricing pricing;

	private final CanaryService canary;

	private final JdbcConnectionPool pool;

	public JdbcAlertMetricsQuery(CostQuery costQuery, CostPricing pricing, CanaryService canary,
			JdbcAgentRunStore runStore) {
		this.costQuery = costQuery;
		this.pricing = pricing;
		this.canary = canary;
		this.pool = runStore.pool();
	}

	@Override
	public RuntimeSnapshot collect(int days) {
		int effective = Math.max(1, Math.min(days, 90));
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		LocalDate from = today.minusDays(effective - 1L);

		CostQuery.Reconciliation reconciliation = costQuery.reconcile(from, today);
		Retrieval retrieval = retrievalSignals(from);
		// 【阶段四第 6 件】租户口径：复用**成本看板那一条**按租户分组的查询。
		// 不在这里另写一条 SQL —— 那正是本类类注释里那条纪律
		//（"同一件事有两份实现，而两份迟早会对不上"），
		// 而这里的后果更具体：告警说"某租户缓存 0 命中"、看板说"它命中了 12%"。
		List<CostQuery.GroupTotals> tenantRows = costQuery.byTenant(from, today);

		RuntimeSnapshot snapshot = new RuntimeSnapshot(Instant.now(), from.toString(), today.toString(),
				pricing.configured(), unpricedModels(effective), reconciliation.gap(),
				reconciliation.stepLevelTokens(), reconciliation.runLevelTokens(), reconciliation.runsWithoutSteps(),
				reconciliation.promptTokens(), reconciliation.cachedPromptTokens(),
				retrieval.consecutiveEmpty(), retrieval.lastIndexSize(), retrieval.calls, canarySplit(effective),
				tenantCaches(tenantRows), tenantShare(tenantRows));

		log.debug("告警快照：窗口 {}~{} 对账差={} 空命中连续={} 灰度={} 租户={}（未标注 {} 次运行）", from, today,
				snapshot.reconcileGap(), snapshot.consecutiveEmptyRetrievals(), snapshot.canary(),
				snapshot.tenantCaches().size(),
				tenantRows.stream().filter(r -> r.key() == null).mapToLong(CostQuery.GroupTotals::runCount).sum());
		return snapshot;
	}

	/**
	 * 逐租户的缓存口径（阶段四第 6 件）。
	 *
	 * <h2>为什么"未标注租户"那一行被<b>丢掉</b>，而不是当成一个租户</h2>
	 * 因为判据要报的是「<b>某一家</b>一次都没命中缓存」，而「未标注」不是一个主体 ——
	 * 它是一堆互不相干的运行的合计。把它当成一个租户报出来，会得到一条
	 * "未标注租户 的缓存命中率为 0"：那个数字是对的，但它**没有处置动作**
	 * （要去补的是调用方的 tenantId，而不是"优化未标注租户的提示词"）。
	 * 那种告警会消耗掉这条判据的信用。
	 *
	 * <p>丢掉它<b>不会漏报</b>：一个还没接归因的部署，
	 * {@code tenantCaches} 是空的，判据会退回窗口口径继续报（见
	 * {@code AlertEvaluator.perTenant}），所以"整个部署都没命中缓存"这件事仍然会被看见。
	 */
	private static List<RuntimeSnapshot.TenantPoint> tenantCaches(List<CostQuery.GroupTotals> tenantRows) {
		List<RuntimeSnapshot.TenantPoint> points = new ArrayList<>();
		for (CostQuery.GroupTotals row : tenantRows) {
			if (row.key() == null || row.promptTokens() <= 0) {
				// 没有输入 token 的租户不进这一维：命中率对它是"无从谈起"，
				// 让它进来只会让逐租户评估多跑一次必然不响的判据
				continue;
			}
			points.add(new RuntimeSnapshot.TenantPoint(row.key(), row.promptTokens(), row.cachedPromptTokens()));
		}
		return List.copyOf(points);
	}

	/**
	 * 花费最集中的那个租户（阶段四第 6 件）。
	 *
	 * <h2>分母刻意不含"未标注租户"</h2>
	 * 见 {@link AlertRule.TenantShare} 的注释：判据问的是"某一家占掉了绝大部分"，
	 * 而未标注不是一个"家"。把它算进分母还有更糟的一面：一个还没接归因的部署
	 * 会因为"未标注占了 100%"而……不响（它不在候选里）—— 那是对的，
	 * 因为那件事该由"去传 tenantId"解决，而不是由一条成本告警解决。
	 *
	 * @return 没有已标注租户、或它们的 token 全是 0 时返回 {@code null}
	 *         （**不是零值**：零值会被读成"没有租户在花钱"）
	 */
	private static AlertRule.TenantShare tenantShare(List<CostQuery.GroupTotals> tenantRows) {
		String top = null;
		long topTokens = 0;
		long total = 0;
		for (CostQuery.GroupTotals row : tenantRows) {
			if (row.key() == null) {
				continue;
			}
			long tokens = row.totalTokens();
			total += tokens;
			if (tokens > topTokens) {
				topTokens = tokens;
				top = row.key();
			}
		}
		if (top == null || total <= 0) {
			return null;
		}
		return new AlertRule.TenantShare(top, topTokens, total);
	}

	@Override
	public List<String> unpricedModels(int days) {
		int effective = Math.max(1, Math.min(days, 90));
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		LocalDate from = today.minusDays(effective - 1L);

		// 【整张价格表是空的时候，这一条判据主动不报】
		//
		// 不是"算不出来"，而是**刻意不报**：那种情况下每一个模型都没有价，
		// 于是这条判据会一次报出五条"某模型没有价格"，而真相只有一句
		// 「ai.cost.prices 是空的」—— 而那句话由 UNCONFIGURED_PRICES 负责说。
		// 两条判据同时响会让同一件事看起来像两个问题，
		// 而"同一件事在两个地方各说一遍"正是让告警列表变成噪声的最快方式。
		if (!pricing.configured()) {
			return List.of();
		}

		List<String> unpriced = new ArrayList<>();
		for (CostQuery.GroupTotals row : costQuery.byModel(from, today)) {
			if (row.totalTokens() <= 0) {
				continue;
			}
			// 【key 为 null = 上游没上报模型名】它不是模型名，所以绝不能用兜底价去算它 ——
			// 那会得到一个"看起来有金额"的数字，而它是按随便哪个模型的价算的。
			// 判据那一侧的 action 里专门讲了"这一种不能靠补价格解决"
			// （这条判据与 CostDashboardView.priceByModel 是同一条，刻意的）
			if (row.key() == null) {
				unpriced.add(UNKNOWN_MODEL_LABEL);
				continue;
			}
			if (pricing.price(row.key(), row.uncachedPromptTokens(), row.cachedPromptTokens(),
					row.completionTokens(), from.atStartOfDay(ZoneId.systemDefault()).toInstant()).isEmpty()) {
				unpriced.add(row.key());
			}
		}
		return List.copyOf(unpriced);
	}

	@Override
	public AlertRule.CanarySplit canarySplit(int days) {
		CanaryService.Split split = canary.observation(ai.agent.service.prompt.PromptKind.AGENT_LOOP, days);
		boolean active = canary.current(ai.agent.service.prompt.PromptKind.AGENT_LOOP).isPresent();
		return new AlertRule.CanarySplit(active, split.candidateVersion(), split.weightPercent(),
				split.participatingRuns(), split.candidateRuns());
	}

	@Override
	public List<String> catalogNotes() {
		// ⚠️ 这几句会**原样渲染到页面上**（纯文本 <ul>），所以不许出现 markdown 星号。
		// 这一条是本项目的常客：第十轮（成本看板）与第十一轮（全局搜了一遍）
		// 都是同一类坑 —— 而本轮它在"真后端路径"上又现了一次：
		// 假后端的 notes 里没有星号，所以那条断言只在真路径上红。
		// 强调一律改用「」。
		return List.of(
				"判据目录是「什么算异常」的唯一一份定义：门禁（构建阶段）与告警（运行阶段）读的是同一个枚举。",
				"门禁只在构建阶段评估（只对「构建环境足以判定」的事下结论），告警只在运行阶段评估 —— "
						+ "所以本页不显示构建阶段的门禁结论：那要去 CI 上看。",
				"阈值写死在判据旁边（枚举里），不从配置读：一个可以被调低的阈值会在"
						+ "「告警响了但没人管」时被顺手调低，而那时它就不再是判据了。");
	}

	// ------------------------------------------------------------------
	//  检索信号（第十三轮那两张表）
	// ------------------------------------------------------------------

	/**
	 * 检索侧的两个信号。
	 *
	 * @param consecutiveEmpty 从最新一次往前数，连续"一条都没命中"的次数
	 * @param lastIndexSize 最新一次检索时索引里的块数（0 = 索引根本没建起来）
	 * @param calls 窗口内一共发生了几次检索
	 */
	private record Retrieval(int consecutiveEmpty, int lastIndexSize, long calls) {
	}

	private Retrieval retrievalSignals(LocalDate from) {
		Instant since = from.atStartOfDay(ZoneId.systemDefault()).toInstant();
		List<Integer> hits = new ArrayList<>();
		int lastIndexSize = 0;
		boolean first = true;
		// 按时间倒序取最近 N 条：这样"从最新往前数连续空命中"只需要扫一遍
		String sql = "SELECT hit_count, index_size FROM retrieval_call WHERE created_at >= ? "
				+ "ORDER BY created_at DESC LIMIT " + EMPTY_STREAK_SCAN;
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setTimestamp(1, Timestamp.from(since));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int hitCount = rs.getInt("hit_count");
					if (first) {
						lastIndexSize = rs.getInt("index_size");
						first = false;
					}
					hits.add(hitCount);
				}
			}
		}
		catch (SQLException e) {
			throw new RuntimeException("读检索信号失败（retrieval_call 表存在吗？它是第十三轮加的）", e);
		}

		int consecutive = 0;
		for (int hitCount : hits) {
			if (hitCount > 0) {
				break;
			}
			consecutive++;
		}
		return new Retrieval(consecutive, lastIndexSize, countRetrievalCalls(since));
	}

	/** 窗口内的检索总次数（判据明细里要能看出"这 3 次是 3 次里的 3 次，还是 300 次里的 3 次"）。 */
	private long countRetrievalCalls(Instant since) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"SELECT COUNT(*) AS n FROM retrieval_call WHERE created_at >= ?")) {
			ps.setTimestamp(1, Timestamp.from(since));
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong("n") : 0;
			}
		}
		catch (SQLException e) {
			throw new RuntimeException("统计检索次数失败", e);
		}
	}

}
