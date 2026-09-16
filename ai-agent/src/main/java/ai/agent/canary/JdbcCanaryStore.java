package ai.agent.canary;

import ai.agent.agent.store.impl.JdbcAgentRunStore;
import ai.agent.prompt.PromptKind;
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
import java.util.Optional;

/**
 * {@link CanaryStore} 的 H2 / MySQL 实现（阶段四第 5 件）。
 *
 * <h2>建表脚本复用 {@code db/alert-schema.sql}</h2>
 * 因为它与告警是同一件（第 5 件）的两半，而且它们共用同一张"事实"：
 * 告警的一条判据（{@code canary-no-traffic} / {@code canary-split-biased}）
 * 读的正是这里的观测分布。分成两个脚本文件的话，
 * "改这个会不会影响那个"每次都变成要重新判断的问题（{@code JdbcPromptStore}
 * 当初分开写脚本的理由是"两张表属于不同的件"，这里恰好相反）。
 * 建表语句由 {@link ai.agent.alert.JdbcAlertStore} 执行 —— 它先于本类装配
 * （见下面的构造参数），所以这里只读不建。
 *
 * <h2>★ 观测查询的两个口径，写错任何一个都会得到一个"看起来正常"的比例</h2>
 * <ol>
 *   <li><b>按运行去重，不按步骤</b>：{@code COUNT(DISTINCT run_id)}。
 *       数步骤的话，一次跑了 5 步的运行会被算 5 次 —— 于是"跑得久的那一组"
 *       在比例里被加权，而"新版是不是更啰嗦"恰恰是这次实验想看的东西之一
 *       （一个自证式的偏差）；</li>
 *   <li><b>参与分流的判据是"变体标签非空"，不是"版本号等于候选版"</b>。
 *       后者会把灰度<b>之前</b>就跑过那一版的运行也算进来 ——
 *       而那些运行压根没参与这次实验。</li>
 * </ol>
 *
 * <p>⚠️ 版本号是从 {@code prompt_fingerprint} 的 {@code v<数字>:} 前缀里解析的。
 * 解析不出来时记 0（那一行仍然出现在报告里，标签写"（无法归属版本）"）——
 * <b>而不是丢掉它</b>：丢掉会让"总运行数"与"逐版本之和"对不上，
 * 而两个数字对不上时，读的人只会信那个大的。
 */
@Component
public class JdbcCanaryStore implements CanaryStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcCanaryStore.class);

	private static final String COLUMNS =
			"kind, candidate_version, weight_percent, started_by, started_at, note, ended_at, ended_by, end_reason";

	private final JdbcConnectionPool pool;

	public JdbcCanaryStore(JdbcAgentRunStore runStore) {
		this.pool = runStore.pool();
		log.info("灰度配置就绪（表 prompt_canary；建表由 JdbcAlertStore 负责）");
	}

	// ------------------------------------------------------------------
	//  配置侧
	// ------------------------------------------------------------------

	@Override
	public Optional<Row> active(PromptKind kind) {
		return one("SELECT " + COLUMNS + " FROM prompt_canary WHERE kind = ? AND ended_at IS NULL", kind);
	}

	@Override
	public Optional<Row> latest(PromptKind kind) {
		// 一份提示词只有一行（主键就是 kind），所以 latest 与"那一行"是同一个东西 ——
		// 名字保留是因为调用方问的是"上一轮实验是什么"，而它读的是同一行
		return one("SELECT " + COLUMNS + " FROM prompt_canary WHERE kind = ?", kind);
	}

	@Override
	public Row start(PromptKind kind, int candidateVersion, int weightPercent, String note, String by, Instant now) {
		// 【并发保护：让主键来仲裁】
		// 先查 active 再 INSERT 是典型的 check-then-act，两个人都点"开始"时会同时通过检查。
		// 这里直接 INSERT，撞主键就是"已经有一条"—— 数据库不会漏判。
		//
		// ⚠️ 这里刻意**不**写成 ON DUPLICATE KEY UPDATE：
		// 那会让"开第二次"静默覆盖第一次，而覆盖掉的是一次正在进行的实验。
		// 想换实验必须先停（stop），那一步会留下 ended_at 与原因 —— 这才是可复盘的做法
		String sql = "INSERT INTO prompt_canary "
				+ "(kind, candidate_version, weight_percent, started_by, started_at, note) VALUES (?, ?, ?, ?, ?, ?)";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, kind.defaultValue());
			ps.setInt(2, candidateVersion);
			ps.setInt(3, weightPercent);
			ps.setString(4, by);
			ps.setTimestamp(5, Timestamp.from(now));
			ps.setString(6, note);
			ps.executeUpdate();
		}
		catch (SQLException e) {
			if (isDuplicate(e)) {
				throw new CanaryConflictException("这一份提示词已经有一次进行中的灰度了 —— "
						+ "先停掉它（停止时要写清为什么停），再开新的。"
						+ "⚠️ 不允许直接覆盖：被覆盖掉的那次是一次正在进行的实验，"
						+ "而覆盖之后它从历史上就消失了");
			}
			throw new CanaryStoreException("开灰度失败 kind=" + kind, e);
		}
		return latest(kind).orElseThrow(
				() -> new CanaryStoreException("刚写入的灰度配置读不回来 kind=" + kind));
	}

	/**
	 * 判"是不是主键冲突"。
	 *
	 * <p>为什么按 SQLState / 错误码判，而不是按异常消息里有没有 "Duplicate"：
	 * 消息文本是 H2 与 MySQL 各自的实现细节，而**它一旦变了，这里的判断会静默失效** ——
	 * 表现是"两个人同时开灰度时抛出的是 500 而不是 409"，而这种失败只在并发下出现，
	 * 平时测不到。所以判据用标准的东西：
	 * <ul>
	 *   <li>{@code 23505} —— SQL 标准的唯一约束冲突（H2 用的就是它）；</li>
	 *   <li>{@code 23000} —— 完整性约束冲突的父类（真 MySQL 在这个码下面）；</li>
	 *   <li>{@code 1062} —— MySQL 的 "Duplicate entry" 错误码（留作兜底）。</li>
	 * </ul>
	 * ⚠️ ⚠️ 前两条与第三条**都已经在 H2 上实测过**（{@code CanaryTest} 里那条
	 * "同时开两次只有一次成功"的断言走的就是这条路）；
	 * 但**真 MySQL 那一档没有实测过**（本项目没接真 MySQL）——
	 * 所以这里只声称"两种码都认"，不声称"在 MySQL 上也验过"。
	 */
	private static boolean isDuplicate(SQLException e) {
		String state = e.getSQLState();
		return "23505".equals(state) || "23000".equals(state) || e.getErrorCode() == 23505
				|| e.getErrorCode() == 1062;
	}

	@Override
	public Optional<Row> stop(PromptKind kind, String endReason, String by, Instant now) {
		String sql = "UPDATE prompt_canary SET ended_at = ?, ended_by = ?, end_reason = ? "
				+ "WHERE kind = ? AND ended_at IS NULL";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setTimestamp(1, Timestamp.from(now));
			ps.setString(2, by);
			ps.setString(3, endReason);
			ps.setString(4, kind.defaultValue());
			if (ps.executeUpdate() == 0) {
				// 没有进行中的那条 —— 由服务层回 409，见接口注释
				return Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new CanaryStoreException("停灰度失败 kind=" + kind, e);
		}
		return latest(kind);
	}

	private Optional<Row> one(String sql, PromptKind kind) {
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, kind.defaultValue());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(map(rs)) : Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new CanaryStoreException("读灰度配置失败 kind=" + kind, e);
		}
	}

	private static Row map(ResultSet rs) throws SQLException {
		return new Row(PromptKind.byName(rs.getString("kind")), rs.getInt("candidate_version"),
				rs.getInt("weight_percent"), rs.getString("started_by"), toInstant(rs.getTimestamp("started_at")),
				rs.getString("note"), toInstant(rs.getTimestamp("ended_at")), rs.getString("ended_by"),
				rs.getString("end_reason"));
	}

	// ------------------------------------------------------------------
	//  数据侧（观测）
	// ------------------------------------------------------------------

	@Override
	public Observation observe(int days) {
		int effective = Math.max(1, Math.min(days, 90));
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		LocalDate from = today.minusDays(effective - 1L);
		Instant since = from.atStartOfDay(ZoneId.systemDefault()).toInstant();

		// 【三个计数来自两条 GROUP BY，而不是一条"大而全"的查询】
		// 一条查询里同时 GROUP BY 指纹与变体，再想把"按变体的运行数"加起来，
		// 就依赖一个假设：同一次运行不会同时出现在两个变体里。
		// 那个假设**今天成立**（一次运行只读一次提示词），但它是一条隐含前提 ——
		// 而隐含前提在下一次改动时就会静默失效（这里失效的表现是参与数翻倍）。
		// 所以这里让数据库分别在两个粒度上各算一次：多一条 SQL，少一个假设
		List<RunVersion> versions = new ArrayList<>();
		String versionSql = """
				SELECT prompt_fingerprint, canary_variant, COUNT(DISTINCT run_id) AS runs
				FROM agent_step
				WHERE created_at >= ?
				GROUP BY prompt_fingerprint, canary_variant
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(versionSql)) {
			ps.setTimestamp(1, Timestamp.from(since));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String fingerprint = rs.getString("prompt_fingerprint");
					String variant = rs.getString("canary_variant");
					versions.add(new RunVersion(versionOf(fingerprint), variant == null ? "" : variant,
							rs.getInt("runs")));
				}
			}
		}
		catch (SQLException e) {
			// canary_variant 这一列是随本件一起加的，老库里可能没有 —— 那是"部署没完成"，
			// 必须炸而不是降级：降级会让观测报告安静地少一半数据
			throw new CanaryStoreException("观测灰度分流失败（canary_variant 列存在吗？见 db/agent-schema.sql）", e);
		}

		int candidate = 0;
		int stable = 0;
		int participating = 0;
		int runs = 0;
		String variantSql = """
				SELECT canary_variant, COUNT(DISTINCT run_id) AS runs
				FROM agent_step
				WHERE created_at >= ?
				GROUP BY canary_variant
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(variantSql)) {
			ps.setTimestamp(1, Timestamp.from(since));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String variant = rs.getString("canary_variant");
					int count = rs.getInt("runs");
					runs += count;
					if (CanaryDecision.LABEL_CANARY.equals(variant)) {
						candidate += count;
						participating += count;
					}
					else if (CanaryDecision.LABEL_STABLE.equals(variant)) {
						stable += count;
						participating += count;
					}
				}
			}
		}
		catch (SQLException e) {
			throw new CanaryStoreException("观测灰度分流失败", e);
		}
		return new Observation(runs, participating, candidate, stable, versions);
	}

	/**
	 * 从指纹里解析版本号（形如 {@code v4:9ab12cd34ef5}）。
	 *
	 * <p>解析不出来返回 0 —— 而不是丢行。丢掉会让"总运行数"与"逐版本之和"对不上，
	 * 而两个数字对不上时，读的人只会信那个大的（页面上更显眼的那个）。
	 */
	static int versionOf(String fingerprint) {
		if (fingerprint == null || fingerprint.length() < 2 || fingerprint.charAt(0) != 'v') {
			return 0;
		}
		int colon = fingerprint.indexOf(':');
		if (colon <= 1) {
			return 0;
		}
		try {
			return Integer.parseInt(fingerprint.substring(1, colon));
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

	private static Instant toInstant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}

}
