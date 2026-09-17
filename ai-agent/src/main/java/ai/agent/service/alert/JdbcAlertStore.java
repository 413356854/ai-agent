package ai.agent.service.alert;

import ai.agent.entity.alert.AlertEvent;
import ai.agent.service.impl.agent.store.JdbcAgentRunStore;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link AlertStore} 的 H2 / MySQL 实现（纯 JDBC，与 {@code JdbcPromptStore} 同一种做法）。
 *
 * <h2>三个要点，每个都是"不这么写就会有一个安静的错"</h2>
 * <ol>
 *   <li><b>写用一条 {@code INSERT ... ON DUPLICATE KEY UPDATE}，而不是"先查再写"</b>。
 *       {@link AlertStore#recordFiring} 会被定时器调用，而"先 SELECT 再决定 INSERT 还是
 *       UPDATE"在两次评估重叠时（上一次还没跑完、下一次已经到点）会写出两行。
 *       这里让数据库按主键仲裁 —— 与 Prompt 版本激活那条
 *       "带前置条件的 UPDATE"是同一个做法；</li>
 *   <li><b>重新触发要把状态拉回 OPEN，并清掉 resolved_at</b>。
 *       不清的话，"修好过又坏了"会停留在 RESOLVED 里 —— 而它恰恰是最该被看见的一种；
 *       同时 {@code first_seen_at} <b>不动</b>：它记的是"这个问题最早是什么时候出现的"，
 *       与"这一轮是从什么时候开始的"是两件事（后者由 resolved_at 到现在的间隔隐含表达）；</li>
 *   <li><b>确认时必须带前置条件</b>（{@code WHERE fingerprint = ?} 且状态还在响）。
 *       已恢复的告警不该被"确认"成已处理 —— 那会把一条已经结束的历史
 *       标成"人处理过"，而复盘时这两句话的含义完全不同。</li>
 * </ol>
 */
@Component
public class JdbcAlertStore implements AlertStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcAlertStore.class);

	/** 所有查询共用的列清单（三处 SELECT 各写一份的话，加一列时很容易漏掉其中一处）。 */
	private static final String COLUMNS = "fingerprint, rule_code, subject, severity, phase, state, first_seen_at, "
			+ "last_seen_at, occurrences, resolved_at, acknowledged_at, acknowledged_by, observed, threshold, why, "
			+ "action, window_label";

	private final JdbcConnectionPool pool;

	public JdbcAlertStore(JdbcAgentRunStore runStore) {
		this.pool = runStore.pool();
		initSchema();
		log.info("告警库就绪（表 alert_event / prompt_canary）");
	}

	private void initSchema() {
		String ddl = readSchema();
		try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
			// H2 不接受一次执行多条语句，必须按分号切开逐条执行（与 JdbcAgentRunStore 同一条经验）
			for (String statement : ddl.split(";")) {
				String sql = stripComments(statement).strip();
				if (!sql.isEmpty()) {
					st.execute(sql);
				}
			}
		}
		catch (SQLException e) {
			throw new AlertStoreException("初始化告警库失败", e);
		}
	}

	/**
	 * 去掉 {@code --} 行注释。
	 *
	 * <p>⚠️ 与 {@code JdbcPromptStore} 里那个同名方法不同的一点：本文件的 DDL 里
	 * 有一条注释里就带着 {@code |} 与引号，所以**不能**顺手把注释保留再交给 H2 ——
	 * 分号切分时注释会被留在语句里，而注释里的分号会把一条语句切成两条
	 * （本件的注释里就写了「一条判据 × 一个主体 = 一行」，没有分号是运气，
	 * 所以这里明确按行剥掉，而不是依赖"注释里别写分号"这种约定）。
	 */
	private static String stripComments(String sql) {
		StringBuilder sb = new StringBuilder();
		for (String line : sql.split("\n")) {
			int idx = line.indexOf("--");
			sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
		}
		return sb.toString();
	}

	private static String readSchema() {
		try {
			return StreamUtils.copyToString(new ClassPathResource("db/alert-schema.sql").getInputStream(),
					StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("读不到 db/alert-schema.sql", e);
		}
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@Override
	public AlertEvent recordFiring(AlertRule rule, String subject, AlertRule.Verdict verdict, String windowLabel,
			Instant now) {
		String fingerprint = AlertEvent.fingerprintOf(rule, subject);
		Instant at = now == null ? Instant.now() : now;
		// 【这条 UPSERT 的语义有四条，每条都对应一个会安静的错】
		//
		//  ① 已存在就 **occurrences + 1**、last_seen_at 往后走 —— 这条是"持续响"的表达；
		//  ② resolved_at 回 NULL —— 已恢复的告警**又出现了**，那是新的一个 incident，
		//     必须重新出现在"还在响"里。留在 RESOLVED 里的话，"修好过又坏了"会被
		//     静默地当成"一直没修好"，而这两句话的处置完全不同；
		//  ③ **first_seen_at 与 created_at 都不动** —— 它们记的是"这个问题最早是什么时候出现的"，
		//     是复盘时唯一能回答"它存在了多久"的两个字段。顺手更新它们会让那条时长**永远接近 0**；
		//  ④ ★ **已确认的必须保持已确认**：`state` 只在"上一状态是 RESOLVED"或本来就 OPEN 时
		//     才回到 OPEN。写成直接 `state = 'OPEN'` 的话，系统每评估一遍就把人的确认抹掉一次 ——
		//     而值班的人只会觉得「这个按钮时灵时不灵」。
		//     ⚠️ 第一版就是直接写 OPEN，而内存替身里那一条是对的 ——
		//     于是"内存里跑得好好的、真库上一按就没了"。
		//     那次分叉正是"两份实现"的代价，也正是为什么真库与内存两条都要能说出同一套语义。
		//
		// ⚠️ 这里用 `CASE WHEN` 而不是 `IF(...)`：后者在 H2 与真 MySQL 上的
		// 函数名不完全一致，而本项目的存储层刻意只依赖两者的**交集**
		String sql = """
				INSERT INTO alert_event
				  (fingerprint, rule_code, subject, severity, phase, state, first_seen_at, last_seen_at,
				   occurrences, resolved_at, observed, threshold, why, action, window_label)
				VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, 1, NULL, ?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE
				  state = CASE WHEN state = 'ACKNOWLEDGED' THEN 'ACKNOWLEDGED' ELSE 'OPEN' END,
				  last_seen_at = VALUES(last_seen_at),
				  occurrences = occurrences + 1,
				  resolved_at = NULL,
				  severity = VALUES(severity),
				  observed = VALUES(observed),
				  threshold = VALUES(threshold),
				  why = VALUES(why),
				  action = VALUES(action),
				  window_label = VALUES(window_label)
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, fingerprint);
			ps.setString(2, rule.code());
			ps.setString(3, subject == null ? "" : subject);
			ps.setString(4, rule.severity().name());
			ps.setString(5, rule.phase().name());
			ps.setTimestamp(6, Timestamp.from(at));
			ps.setTimestamp(7, Timestamp.from(at));
			ps.setString(8, verdict.observed());
			ps.setString(9, verdict.threshold());
			ps.setString(10, verdict.why());
			ps.setString(11, rule.action());
			ps.setString(12, windowLabel);
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new AlertStoreException("写告警失败 fingerprint=" + fingerprint, e);
		}
		return find(fingerprint).orElseThrow(
				() -> new AlertStoreException("刚写入的告警读不回来 fingerprint=" + fingerprint));
	}

	@Override
	public Optional<AlertEvent> recordResolved(String fingerprint, Instant now) {
		Optional<AlertEvent> existing = find(fingerprint);
		// 找不到 / 已经恢复 → 什么都不做。写一行"已恢复"占位会让列表里出现
		// 一堆从来没见过的问题，而列表的可信度一旦下降就回不来了
		if (existing.isEmpty() || existing.get().state() == AlertEvent.State.RESOLVED) {
			return Optional.empty();
		}
		Instant at = now == null ? Instant.now() : now;
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"UPDATE alert_event SET state = 'RESOLVED', resolved_at = ? WHERE fingerprint = ?")) {
			ps.setTimestamp(1, Timestamp.from(at));
			ps.setString(2, fingerprint);
			if (ps.executeUpdate() == 0) {
				return Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new AlertStoreException("标记告警恢复失败 fingerprint=" + fingerprint, e);
		}
		log.info("告警已恢复：{}", fingerprint);
		return find(fingerprint);
	}

	@Override
	public Optional<AlertEvent> acknowledge(String fingerprint, String by, Instant now) {
		Instant at = now == null ? Instant.now() : now;
		// 前置条件里带上 state != 'RESOLVED'：已恢复的告警不该被"确认"，见类注释第 3 条
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement("UPDATE alert_event SET state = 'ACKNOWLEDGED', "
						+ "acknowledged_at = ?, acknowledged_by = ? WHERE fingerprint = ? AND state <> 'RESOLVED'")) {
			ps.setTimestamp(1, Timestamp.from(at));
			ps.setString(2, by == null || by.isBlank() ? "匿名" : by.strip());
			ps.setString(3, fingerprint);
			if (ps.executeUpdate() == 0) {
				// 两种情况都返回空，由调用方（端点）去查一次原因：
				// "这条不存在"（404）与"它已经恢复了"（409）该说两句不同的话
				return Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new AlertStoreException("确认告警失败 fingerprint=" + fingerprint, e);
		}
		log.info("告警已确认：{} by={}", fingerprint, by);
		return find(fingerprint);
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public List<AlertEvent> firing() {
		// 排序：严重度（BLOCKER → WARNING → INFO）、再按第一次发现的时间。
		// 为什么不是"按最后一次发现倒序"：持续响着的旧问题会因为一直被更新而永远排在最前，
		// 而真正的新问题排在它后面 —— 一个"越老越靠前"的列表很快就没人往下看了
		String sql = "SELECT " + COLUMNS + " FROM alert_event WHERE state <> 'RESOLVED' "
				+ "ORDER BY severity, first_seen_at, fingerprint";
		return query(sql, null);
	}

	@Override
	public List<AlertEvent> recent(int limit) {
		int effective = Math.max(1, Math.min(limit, 500));
		String sql = "SELECT " + COLUMNS + " FROM alert_event ORDER BY last_seen_at DESC, fingerprint LIMIT "
				+ effective;
		return query(sql, null);
	}

	@Override
	public Optional<AlertEvent> find(String fingerprint) {
		String sql = "SELECT " + COLUMNS + " FROM alert_event WHERE fingerprint = ?";
		List<AlertEvent> found = query(sql, fingerprint);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	@Override
	public long openCount() {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"SELECT COUNT(*) AS n FROM alert_event WHERE state = 'OPEN'");
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getLong("n") : 0;
		}
		catch (SQLException e) {
			throw new AlertStoreException("统计未处理告警数失败", e);
		}
	}

	private List<AlertEvent> query(String sql, String fingerprint) {
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			if (fingerprint != null) {
				ps.setString(1, fingerprint);
			}
			try (ResultSet rs = ps.executeQuery()) {
				List<AlertEvent> events = new ArrayList<>();
				while (rs.next()) {
					events.add(map(rs));
				}
				return List.copyOf(events);
			}
		}
		catch (SQLException e) {
			throw new AlertStoreException("读告警失败", e);
		}
	}

	private static AlertEvent map(ResultSet rs) throws SQLException {
		return new AlertEvent(rs.getString("fingerprint"), rs.getString("rule_code"), rs.getString("subject"),
				AlertRule.Severity.valueOf(rs.getString("severity")),
				AlertRule.AlertPhase.valueOf(rs.getString("phase")),
				AlertEvent.State.valueOf(rs.getString("state")),
				toInstant(rs.getTimestamp("first_seen_at")), toInstant(rs.getTimestamp("last_seen_at")),
				rs.getInt("occurrences"), toInstant(rs.getTimestamp("resolved_at")),
				toInstant(rs.getTimestamp("acknowledged_at")), rs.getString("acknowledged_by"),
				rs.getString("observed"), rs.getString("threshold"), rs.getString("why"),
				rs.getString("action"), rs.getString("window_label"));
	}

	private static Instant toInstant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}

	/** 告警存储自己的异常类型（与 {@code PromptStoreException} 同一种做法：便于端点分别处理）。 */
	public static class AlertStoreException extends RuntimeException {

		public AlertStoreException(String message, Throwable cause) {
			super(message, cause);
		}

		public AlertStoreException(String message) {
			super(message);
		}

	}

}
