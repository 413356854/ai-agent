package ai.agent.agent.approval;

import ai.agent.agent.store.impl.JdbcAgentRunStore;
import ai.agent.config.AgentStoreProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ToolApprovalStore} 的 H2/MySQL 实现 —— 与 {@code JdbcAgentRunStore} 同一套做法。
 *
 * <h2>为什么建表语句不在这里，而在 {@code db/agent-schema.sql}</h2>
 * 那个文件由 {@code JdbcAgentRunStore} 在启动时执行一次（它已经有一套
 * "按分号切、去掉行注释"的 DDL 执行逻辑）。把审批表加进同一个文件，
 * 换来的是<b>一个 schema、一处建表、一次执行</b>；
 * 在这里再写一份自己的建表逻辑，就会出现两个都在管表结构的地方 ——
 * 它们迟早会漂移，而漂移的表现是"某个环境里表少了一列"。
 *
 * <p>代价也要说清楚：本类因此<b>依赖 {@code JdbcAgentRunStore} 先跑完建表</b>。
 * 两者都是 {@code @Component}，启动顺序由 Spring 决定。
 * 这是刻意接受的：审批功能整体依附于"运行记录"这个上下文 ——
 * 没有运行记录时，审批记录也没有可挂靠的对象。
 * （真要独立部署，正确做法是给这个表配自己的建表脚本，而不是靠运气。）
 */
@Component
public class JdbcToolApprovalStore implements ToolApprovalStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcToolApprovalStore.class);

	/** 参数列的字符上限。超过时<b>截断并留下标记</b>，而不是静默截掉（见下面的 {@link #fit}）。 */
	static final int ARGUMENTS_MAX_CHARS = 4000;

	private final JdbcConnectionPool pool;

	/**
	 * @param runStore <b>它没有被用到，但它必须出现在这里</b> —— 这是一个刻意的依赖声明。
	 *                 建表语句在 {@code db/agent-schema.sql} 里，而执行那份 DDL 的是
	 *                 {@code JdbcAgentRunStore} 的构造器。把本类声明成"依赖它"，
	 *                 Spring 就<b>保证先建表、再建这里的连接池</b>。
	 *                 <p>不这么写的话，两个 {@code @Component} 的初始化顺序由扫描顺序决定 ——
	 *                 本类所在包（{@code ai.agent.agent.approval}）排在
	 *                 {@code ai.agent.agent.store.impl} 之前，于是很可能先建本类、
	 *                 此时表还不存在。而那个失败只在<b>第一次真正用到审批时</b>才暴露
	 *                 （构造器里不查表），表现为"表不存在"——
	 *                 一个看起来像"忘了建表"、实际是"顺序不对"的错误。
	 *                 <p>这个参数<b>刻意不加 {@code @Autowired(required=false)} 之类的退让</b>：
	 *                 审批记录依附于运行记录，这个依赖是真实存在的，就该让它在装配图上可见。
	 */
	public JdbcToolApprovalStore(AgentStoreProperties properties, JdbcAgentRunStore runStore) {
		// 与 JdbcAgentRunStore 共用同一个 url —— 于是它们在同一个库里（内存库靠 DB_CLOSE_DELAY=-1 保活）。
		// 自建池而不是复用对方的：H2 的 JdbcConnectionPool 很轻，而共享一个池会引入
		// "谁的连接被谁占着"这种跨组件的耦合，代价大于收益
		this.pool = JdbcConnectionPool.create(properties.url(), properties.username(), properties.password());
		log.info("人工审批记录库就绪：url={}（表由 {} 在启动时建好）", properties.url(),
				runStore.getClass().getSimpleName());
	}

	@Override
	public void save(ToolApprovalRecord record) {
		String sql = """
				INSERT INTO agent_tool_approval
				  (id, run_id, step_index, tool_name, arguments, hints, summary, status, requested_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, record.id());
			ps.setString(2, record.runId());
			if (record.stepIndex() == null) {
				ps.setNull(3, java.sql.Types.INTEGER);
			}
			else {
				ps.setInt(3, record.stepIndex());
			}
			ps.setString(4, record.toolName());
			ps.setString(5, fit(record.arguments()));
			ps.setString(6, record.hints());
			ps.setString(7, record.summary());
			ps.setString(8, record.status().name());
			ps.setTimestamp(9, Timestamp.from(record.requestedAt()));
			ps.executeUpdate();
		}
		catch (SQLException e) {
			// 抛而不是吞：审批记录写不进去意味着"这次写操作没有可审计的凭据"。
			// 继续跑下去，人批准的动作在库里不留痕迹 —— 而那是 HITL 最不该发生的事
			throw new ApprovalStoreException("写入人工审批记录失败 id=" + record.id(), e);
		}
	}

	@Override
	public boolean decide(String id, ApprovalStatus status, Instant at, String decidedBy, String note) {
		// 关键：WHERE 里带 status = 'PENDING'。
		// 人工决定与超时定时器会同时想写这一行，而**只有第一个写入才该生效** ——
		// 否则一条已被人工拒绝的记录会被随后到达的超时覆盖成 TIMED_OUT，
		// 审计时看起来像"压根没人处理过"
		String sql = """
				UPDATE agent_tool_approval
				   SET status = ?, decided_at = ?, decided_by = ?, decision_note = ?
				 WHERE id = ? AND status = 'PENDING'""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, status.name());
			ps.setTimestamp(2, Timestamp.from(at));
			ps.setString(3, decidedBy);
			ps.setString(4, note);
			ps.setString(5, id);
			return ps.executeUpdate() == 1;
		}
		catch (SQLException e) {
			throw new ApprovalStoreException("更新人工审批记录失败 id=" + id, e);
		}
	}

	@Override
	public Optional<ToolApprovalRecord> find(String id) {
		return query("SELECT * FROM agent_tool_approval WHERE id = ?", ps -> ps.setString(1, id)).stream().findFirst();
	}

	@Override
	public List<ToolApprovalRecord> pending() {
		return query("SELECT * FROM agent_tool_approval WHERE status = 'PENDING' ORDER BY requested_at", ps -> {
		});
	}

	@Override
	public List<ToolApprovalRecord> ofRun(String runId) {
		return query("SELECT * FROM agent_tool_approval WHERE run_id = ? ORDER BY requested_at",
				ps -> ps.setString(1, runId));
	}

	@Override
	public List<ToolApprovalRecord> recent(int limit) {
		return query("SELECT * FROM agent_tool_approval ORDER BY requested_at DESC LIMIT ?",
				ps -> ps.setInt(1, Math.max(1, limit)));
	}

	/** 查询样板：绑定参数 → 逐行映射。三个读方法共用，避免三份几乎相同的 try/catch。 */
	private List<ToolApprovalRecord> query(String sql, StatementBinder binder) {
		List<ToolApprovalRecord> rows = new ArrayList<>();
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			binder.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					rows.add(map(rs));
				}
			}
		}
		catch (SQLException e) {
			throw new ApprovalStoreException("查询人工审批记录失败：" + sql, e);
		}
		return List.copyOf(rows);
	}

	private static ToolApprovalRecord map(ResultSet rs) throws SQLException {
		Timestamp decidedAt = rs.getTimestamp("decided_at");
		Integer stepIndex = rs.getObject("step_index") == null ? null : rs.getInt("step_index");
		return new ToolApprovalRecord(rs.getString("id"), rs.getString("run_id"), stepIndex, rs.getString("tool_name"),
				rs.getString("arguments"), rs.getString("hints"), rs.getString("summary"),
				ApprovalStatus.valueOf(rs.getString("status")), rs.getTimestamp("requested_at").toInstant(),
				decidedAt == null ? null : decidedAt.toInstant(), rs.getString("decided_by"),
				rs.getString("decision_note"));
	}

	/**
	 * 超长参数的处理：<b>截断并留下标记</b>。
	 *
	 * <p>不静默截断的理由与本项目其他所有截断一致（见 {@code ToolFeedbackFormatter} 规则 3）：
	 * 一段被砍掉尾巴的参数看起来完全正常，而人据此做出的批准决定
	 * 针对的是<b>他看到的那个调用</b>、不是真正会执行的那个。
	 * 标记写出来，人才知道"我看到的不是全部"。
	 */
	private static String fit(String arguments) {
		if (arguments == null) {
			return null;
		}
		if (arguments.length() <= ARGUMENTS_MAX_CHARS) {
			return arguments;
		}
		return arguments.substring(0, ARGUMENTS_MAX_CHARS)
				+ "\n…（参数超过 " + ARGUMENTS_MAX_CHARS + " 字符，此处截断；完整参数见运行日志）";
	}

	/** 参数绑定回调（三个读方法的差异只在这里）。 */
	@FunctionalInterface
	private interface StatementBinder {

		void bind(PreparedStatement ps) throws SQLException;

	}

	/** 存储层异常。与 {@code AgentStoreException} 分开，便于断言"是哪一侧坏了"。 */
	public static class ApprovalStoreException extends RuntimeException {

		public ApprovalStoreException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
