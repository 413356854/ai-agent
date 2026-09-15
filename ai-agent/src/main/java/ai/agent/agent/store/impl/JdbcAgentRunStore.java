package ai.agent.agent.store.impl;

import ai.agent.agent.AgentStopReason;
import ai.agent.agent.store.AgentRunRecord;
import ai.agent.agent.store.AgentRunStore;
import ai.agent.agent.store.RunStatus;
import ai.agent.config.AgentStoreProperties;
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
 * {@link AgentRunStore} 的 H2/MySQL 实现 —— <b>纯 JDBC，不依赖 Spring 的事务与异常翻译</b>。
 *
 * <h2>为什么是"手写 java.sql"而不是 {@code JdbcTemplate}</h2>
 * 不是因为喜欢手写，而是因为<b>没有可用的那个依赖</b>（实测）：
 * 本地仓库里没有 spring-boot-starter-jdbc 的 4.1.1，spring-jdbc 只到 6.2.8，
 * 而本项目跑在 Spring Framework 7.0.9 上。硬引 6.x 是"编译可能过、运行期才炸"的组合。
 *
 * <p><b>代价必须写清楚</b>（不写就是假装没有）：
 * <ul>
 *   <li>没有 {@code DataAccessException} 的异常翻译 → 这里自己包 {@link AgentStoreException}；</li>
 *   <li>没有 {@code @Transactional} → 事务手写（{@code setAutoCommit(false)} + 显式 commit/rollback）；</li>
 *   <li>没有 {@code DataSource} 自动配置 → 自己建 {@link JdbcConnectionPool}（H2 自带，不用再找依赖）。</li>
 * </ul>
 *
 * <h2>什么时候该换掉这个类</h2>
 * spring-boot-starter-jdbc 4.x 可获取时。换的时候替换面**只有这一个文件**
 * （接口 {@link AgentRunStore} 不变），这类"可替换面收在一个文件里"的取舍，
 * 与阶段二把 pgvector 换成自研 {@code VectorIndex} 是同一种做法。
 *
 * <h2>关于"检查点写失败"这件事的取舍</h2>
 * 写检查点失败时，本类<b>抛异常而不是吞掉</b>。理由是"静默失败"在这里的代价特别大：
 * 恢复能力是"进程被杀时才用到"的功能，如果写库一直在失败而没人知道，
 * 那么真正被杀的那一刻才会发现"没有检查点可恢复"——
 * 而那时已经丢掉了整次运行。宁可现在就把调用方打断。
 */
@Component
public class JdbcAgentRunStore implements AgentRunStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcAgentRunStore.class);

	private final JdbcConnectionPool pool;

	private final AgentStoreProperties properties;

	public JdbcAgentRunStore(AgentStoreProperties properties) {
		this.properties = properties;
		this.pool = JdbcConnectionPool.create(properties.url(), properties.username(), properties.password());
		initSchema();
		log.info("Agent 状态库就绪：url={} node={}", properties.url(), properties.node());
	}

	/**
	 * 建表。
	 *
	 * <p>用 {@code CREATE TABLE IF NOT EXISTS}（DDL 里写的）而不是"先查元数据再决定"：
	 * 幂等，重复启动不会报错，也不需要维护一个版本表 —— 现阶段表结构还很年轻，
	 * 真正的迁移机制（Flyway/Liquibase）等到有"线上已有数据要保"的需求时再引。
	 */
	private void initSchema() {
		String ddl = readSchema();
		try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
			// H2 允许一次执行多条语句吗？不允许 —— 必须按分号切开逐条执行。
			// 这里踩过：直接把整个 .sql 丢给 execute() 会报语法错误，
			// 而错误信息指向第二条语句的位置，看起来像"我的 SQL 写错了"
			for (String statement : ddl.split(";")) {
				String sql = stripComments(statement).strip();
				if (!sql.isEmpty()) {
					st.execute(sql);
				}
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("初始化 Agent 状态库失败（url=" + properties.url() + "）", e);
		}
	}

	/** 去掉一行里的 {@code --} 注释（DDL 里写了大量注释解释每一列，不能把它们当 SQL 执行）。 */
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
			return StreamUtils.copyToString(new ClassPathResource("db/agent-schema.sql").getInputStream(),
					StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			// 建表脚本读不到就别继续：一个"表不存在"的运行期错误会伪装成 SQL 写错
			throw new UncheckedIOException("读不到 db/agent-schema.sql", e);
		}
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@Override
	public void start(String runId, String question, String node) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"INSERT INTO agent_run (id, question, status, node) VALUES (?, ?, ?, ?)")) {
			ps.setString(1, runId);
			ps.setString(2, question);
			ps.setString(3, RunStatus.RUNNING.name());
			ps.setString(4, node);
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new AgentStoreException("开一次 Agent 运行失败 runId=" + runId, e);
		}
	}

	@Override
	public void checkpoint(AgentRunRecord.StepCheckpoint checkpoint) {
		String sql = """
				INSERT INTO agent_step
				  (run_id, step_index, thought_text, prompt_tokens, completion_tokens, elapsed_ms, transcript)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE
				  thought_text = VALUES(thought_text),
				  prompt_tokens = VALUES(prompt_tokens),
				  completion_tokens = VALUES(completion_tokens),
				  elapsed_ms = VALUES(elapsed_ms),
				  transcript = VALUES(transcript)
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, checkpoint.runId());
			ps.setInt(2, checkpoint.stepIndex());
			ps.setString(3, checkpoint.thoughtText());
			ps.setInt(4, checkpoint.promptTokens());
			ps.setInt(5, checkpoint.completionTokens());
			ps.setLong(6, checkpoint.elapsedMs());
			ps.setString(7, checkpoint.transcriptJson());
			ps.executeUpdate();
		}
		catch (SQLException e) {
			// 抛而不是吞 —— 理由见类注释：恢复是"被杀时才用到"的能力，
			// 写库一直失败而没人知道，代价是事发时整次运行都丢了
			throw new AgentStoreException("写检查点失败 runId=" + checkpoint.runId() + " step="
					+ checkpoint.stepIndex(), e);
		}
	}

	@Override
	public void recordToolCall(AgentRunRecord.ToolCallRecord record) {
		String sql = """
				INSERT INTO agent_tool_invocation
				  (run_id, step_index, call_index, call_id, tool_name, arguments, stage, feedback, elapsed_ms)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE
				  call_id = VALUES(call_id), tool_name = VALUES(tool_name), arguments = VALUES(arguments),
				  stage = VALUES(stage), feedback = VALUES(feedback), elapsed_ms = VALUES(elapsed_ms)
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, record.runId());
			ps.setInt(2, record.stepIndex());
			ps.setInt(3, record.callIndex());
			ps.setString(4, record.callId());
			ps.setString(5, record.toolName());
			ps.setString(6, record.arguments());
			ps.setString(7, record.stage());
			ps.setString(8, record.feedback());
			ps.setLong(9, record.elapsedMs());
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new AgentStoreException("记工具调用失败 runId=" + record.runId() + " step=" + record.stepIndex(), e);
		}
	}

	@Override
	public void updateProgress(String runId, int totalTokens, int toolCallCount, long elapsedMs) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"UPDATE agent_run SET total_tokens = ?, tool_calls = ?, elapsed_ms = ? WHERE id = ?")) {
			ps.setInt(1, totalTokens);
			ps.setInt(2, toolCallCount);
			ps.setLong(3, elapsedMs);
			ps.setString(4, runId);
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new AgentStoreException("更新进度失败 runId=" + runId, e);
		}
	}

	@Override
	public void finish(String runId, RunStatus status, AgentStopReason stopReason, String finalAnswer, int totalTokens,
			int toolCallCount, long elapsedMs, String error, ai.agent.agent.AgentFailureCause failureCause) {
		String sql = """
				UPDATE agent_run
				   SET status = ?, stop_reason = ?, final_answer = ?, total_tokens = ?,
				       tool_calls = ?, elapsed_ms = ?, error = ?, failure_cause = ?
				 WHERE id = ?
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, status.name());
			ps.setString(2, stopReason == null ? null : stopReason.name());
			ps.setString(3, finalAnswer);
			ps.setInt(4, totalTokens);
			ps.setInt(5, toolCallCount);
			ps.setLong(6, elapsedMs);
			ps.setString(7, error);
			ps.setString(8, failureCause == null ? null : failureCause.name());
			ps.setString(9, runId);
			int updated = ps.executeUpdate();
			if (updated == 0) {
				// 收尾一个不存在的运行 = 有人在别处删了它，或者 runId 拼错了。
				// 静默返回会让"状态一直是 RUNNING"这件事永远查不出来
				throw new AgentStoreException("收尾失败：找不到运行 runId=" + runId);
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("收尾失败 runId=" + runId, e);
		}
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public Optional<AgentRunRecord> find(String runId) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement("SELECT * FROM agent_run WHERE id = ?")) {
			ps.setString(1, runId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("读运行失败 runId=" + runId, e);
		}
	}

	@Override
	public List<AgentRunRecord.ToolCallRecord> toolCallsOf(String runId) {
		String sql = """
				SELECT run_id, step_index, call_index, call_id, tool_name, arguments, stage, feedback, elapsed_ms
				  FROM agent_tool_invocation
				 WHERE run_id = ?
				 ORDER BY step_index, call_index
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, runId);
			try (ResultSet rs = ps.executeQuery()) {
				List<AgentRunRecord.ToolCallRecord> records = new ArrayList<>();
				while (rs.next()) {
					records.add(new AgentRunRecord.ToolCallRecord(rs.getString("run_id"), rs.getInt("step_index"),
							rs.getInt("call_index"), rs.getString("call_id"), rs.getString("tool_name"),
							rs.getString("arguments"), rs.getString("stage"), rs.getString("feedback"),
							rs.getLong("elapsed_ms")));
				}
				return List.copyOf(records);
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("读工具调用失败 runId=" + runId, e);
		}
	}

	@Override
	public Optional<AgentRunRecord.StepCheckpoint> latestCheckpoint(String runId) {
		String sql = """
				SELECT run_id, step_index, thought_text, prompt_tokens, completion_tokens, elapsed_ms, transcript
				  FROM agent_step
				 WHERE run_id = ?
				 ORDER BY step_index DESC
				 LIMIT 1
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, runId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(mapStep(rs)) : Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("读检查点失败 runId=" + runId, e);
		}
	}

	@Override
	public List<AgentRunRecord.StepCheckpoint> stepsOf(String runId) {
		String sql = """
				SELECT run_id, step_index, thought_text, prompt_tokens, completion_tokens, elapsed_ms, transcript
				  FROM agent_step
				 WHERE run_id = ?
				 ORDER BY step_index
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, runId);
			try (ResultSet rs = ps.executeQuery()) {
				List<AgentRunRecord.StepCheckpoint> steps = new ArrayList<>();
				while (rs.next()) {
					steps.add(mapStep(rs));
				}
				return List.copyOf(steps);
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("读步骤失败 runId=" + runId, e);
		}
	}

	/**
	 * 认领本实例留下的未完成运行。
	 *
	 * <p>分两步：先查出来，再逐条改成 INTERRUPTED。
	 *
	 * <p><b>为什么查与改要在一个事务里</b>：如果两个实例同时启动，
	 * 查完之后各自去改，两边都会"认领"到同一批 —— 然后同一次任务被跑两遍，
	 * 工具被执行两遍。用"改的时候带上 {@code status='RUNNING'} 条件"来保证只有一个赢家
	 * （乐观锁），这是没有行锁时最省事也最可靠的做法。
	 */
	@Override
	public List<AgentRunRecord> markInterruptedRuns(String node) {
		String select = "SELECT * FROM agent_run WHERE status = ? AND node = ? ORDER BY updated_at";
		String claim = "UPDATE agent_run SET status = ? WHERE id = ? AND status = ?";
		List<AgentRunRecord> claimed = new ArrayList<>();

		try (Connection conn = pool.getConnection()) {
			conn.setAutoCommit(false);
			try {
				List<AgentRunRecord> candidates = new ArrayList<>();
				try (PreparedStatement ps = conn.prepareStatement(select)) {
					ps.setString(1, RunStatus.RUNNING.name());
					ps.setString(2, node);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							candidates.add(mapRun(rs));
						}
					}
				}
				for (AgentRunRecord candidate : candidates) {
					try (PreparedStatement ps = conn.prepareStatement(claim)) {
						ps.setString(1, RunStatus.INTERRUPTED.name());
						ps.setString(2, candidate.id());
						ps.setString(3, RunStatus.RUNNING.name());
						if (ps.executeUpdate() == 1) {
							claimed.add(new AgentRunRecord(candidate.id(), candidate.question(),
									RunStatus.INTERRUPTED, candidate.stopReason(), candidate.finalAnswer(),
									candidate.totalTokens(), candidate.toolCallCount(), candidate.elapsedMs(),
									candidate.node(), candidate.error(), candidate.createdAt(), Instant.now()));
						}
						else {
							// 没抢到：说明另一个实例（或另一次启动）先改了它。
							// 这不是错误，但必须留痕 —— 否则"为什么这次没恢复"永远查不出来
							log.info("未认领运行 {}（已被其它实例改为非 RUNNING）", candidate.id());
						}
					}
				}
				conn.commit();
			}
			catch (SQLException e) {
				conn.rollback();
				throw e;
			}
			finally {
				conn.setAutoCommit(true);
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("认领未完成运行失败 node=" + node, e);
		}

		if (!claimed.isEmpty()) {
			log.warn("发现 {} 次上次未跑完的 Agent 运行（已标记 INTERRUPTED）：{}", claimed.size(),
					claimed.stream().map(AgentRunRecord::id).toList());
		}
		return List.copyOf(claimed);
	}

	@Override
	public void markInterruptedForTesting(String runId) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"UPDATE agent_run SET status = ? WHERE id = ?")) {
			ps.setString(1, RunStatus.RUNNING.name());
			ps.setString(2, runId);
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new AgentStoreException("标记测试状态失败 runId=" + runId, e);
		}
	}

	@Override
	public void replaceStepTranscriptForTesting(String runId, int stepIndex, String transcriptJson) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"UPDATE agent_step SET transcript = ? WHERE run_id = ? AND step_index = ?")) {
			ps.setString(1, transcriptJson);
			ps.setString(2, runId);
			ps.setInt(3, stepIndex);
			int updated = ps.executeUpdate();
			if (updated == 0) {
				throw new AgentStoreException(
						"要替换的检查点不存在 runId=" + runId + " step=" + stepIndex + "（先让它跑到那一步）");
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("替换检查点失败 runId=" + runId, e);
		}
	}

	@Override
	public List<AgentRunRecord> recentRuns(int limit) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"SELECT * FROM agent_run ORDER BY created_at DESC, id DESC LIMIT ?")) {
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery()) {
				List<AgentRunRecord> runs = new ArrayList<>();
				while (rs.next()) {
					runs.add(mapRun(rs));
				}
				return List.copyOf(runs);
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("读最近运行失败", e);
		}
	}

	// ------------------------------------------------------------------
	//  映射
	// ------------------------------------------------------------------

	private static AgentRunRecord mapRun(ResultSet rs) throws SQLException {
		return new AgentRunRecord(rs.getString("id"), rs.getString("question"),
				RunStatus.valueOf(rs.getString("status")),
				rs.getString("stop_reason") == null ? null : AgentStopReason.valueOf(rs.getString("stop_reason")),
				rs.getString("final_answer"), rs.getInt("total_tokens"), rs.getInt("tool_calls"),
				rs.getLong("elapsed_ms"), rs.getString("node"), rs.getString("error"),
				toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("updated_at")),
				// 老数据这一列是 NULL → 读成 null，页面显示"未细分"。
				// **不给它编一个默认值**：编一个具体的默认值等于给一个假归因
				rs.getString("failure_cause") == null ? null
						: ai.agent.agent.AgentFailureCause.valueOf(rs.getString("failure_cause")));
	}

	private static AgentRunRecord.StepCheckpoint mapStep(ResultSet rs) throws SQLException {
		return new AgentRunRecord.StepCheckpoint(rs.getString("run_id"), rs.getInt("step_index"),
				rs.getString("transcript"), rs.getString("thought_text"), rs.getInt("prompt_tokens"),
				rs.getInt("completion_tokens"), rs.getLong("elapsed_ms"));
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	/**
	 * 关池子（应用关闭时）。
	 *
	 * <p>不关的话：内存库靠 JVM 退出兜底，但<b>文件库可能留下未刷盘的写</b> ——
	 * 而"关一次服务就丢最后几个检查点"这件事，正好会在"被杀之后要恢复"的场景里咬人。
	 */
	@jakarta.annotation.PreDestroy
	public void close() {
		pool.dispose();
		log.info("Agent 状态库连接池已关闭");
	}

	/** 存储层的失败。包一层而不是把 {@link SQLException} 透出去：调用方不该被迫处理受检异常。 */
	public static class AgentStoreException extends RuntimeException {

		public AgentStoreException(String message, Throwable cause) {
			super(message, cause);
		}

		public AgentStoreException(String message) {
			super(message);
		}
	}

}
