package ai.agent.service.impl.agent.store;

import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.store.AgentRunRecord;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.service.agent.store.RunStatus;
import ai.agent.config.AgentStoreProperties;
import ai.agent.service.tool.RetrievalTrace;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	public void start(Start start) {
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"INSERT INTO agent_run (id, question, status, node, intent, conversation_id, tenant_id) "
								+ "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
			ps.setString(1, start.runId());
			ps.setString(2, start.question());
			ps.setString(3, RunStatus.RUNNING.name());
			ps.setString(4, start.node());
			// 归因键、会话 id、租户的空白值已在 Start 的紧凑构造器里归一成 NULL，
			// 所以这里直接写 —— 不在两处判 blank：**归一化只能有一个地方**，
			// 两处判的话，"哪一处是权威"会在下一次改动时变成一个问题
			ps.setString(5, start.intent());
			ps.setString(6, start.conversationId());
			// 【阶段四第 6 件】租户与归因键**分别写进两列**，绝不拼成一个字符串：
			// 拼起来之后"按租户看一共多少"要靠 LIKE 前缀匹配，而一次拼写不一致
			// 就会让同一件事裂成两行 —— 且两行加起来才是真相，这种事没人会去加
			ps.setString(7, start.tenantId());
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new AgentStoreException("开一次 Agent 运行失败 runId=" + start.runId(), e);
		}
	}

	@Override
	public void checkpoint(AgentRunRecord.StepCheckpoint checkpoint) {
		String sql = """
				INSERT INTO agent_step
				  (run_id, step_index, thought_text, prompt_tokens, completion_tokens, cache_read_tokens,
				   model_name, prompt_fingerprint, canary_variant, elapsed_ms, transcript)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE
				  thought_text = VALUES(thought_text),
				  prompt_tokens = VALUES(prompt_tokens),
				  completion_tokens = VALUES(completion_tokens),
				  cache_read_tokens = VALUES(cache_read_tokens),
				  model_name = VALUES(model_name),
				  prompt_fingerprint = VALUES(prompt_fingerprint),
				  canary_variant = VALUES(canary_variant),
				  elapsed_ms = VALUES(elapsed_ms),
				  transcript = VALUES(transcript)
				""";
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, checkpoint.runId());
			ps.setInt(2, checkpoint.stepIndex());
			ps.setString(3, checkpoint.thoughtText());
			ps.setInt(4, checkpoint.promptTokens());
			ps.setInt(5, checkpoint.completionTokens());
			ps.setInt(6, checkpoint.cacheReadTokens());
			// 模型名 NOT NULL DEFAULT ''：空串是"取不到名字"，与"某个叫空串的模型"不会混淆
			// （后者不是一个合法的模型名）。存 NULL 则要多一处 null 判断，收益为零
			ps.setString(7, checkpoint.modelName());
			// 提示词指纹同理：空串 = 库里读不到那一版（老数据 / 提示词服务不可用），
			// 与"某个叫空串的版本"不会混淆（后者不是一个合法指纹）。
			// 归一化已经在 StepCheckpoint 的紧凑构造器里做过，这里不再判 null
			ps.setString(8, checkpoint.promptFingerprint());
			// 灰度分组：空串 = 没参与这次实验（与 'stable' 严格区分，见 AgentRunRecord 的注释）
			ps.setString(9, checkpoint.canaryVariant());
			ps.setLong(10, checkpoint.elapsedMs());
			ps.setString(11, checkpoint.transcriptJson());
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

	/**
	 * 写一次检索的明细：<b>一行调用 + N 行命中</b>。
	 *
	 * <h2>为什么必须在一个事务里</h2>
	 * 两半各自都写得进去，所以"分开写"在本地永远看不出问题 ——
	 * 但半截数据是有具体后果的：
	 * <ul>
	 *   <li>只有命中行、没有调用行 → 页面上出现一批<b>没有查询词的命中</b>，
	 *       而"它拿什么词查的"正是最该回答的那个问题；</li>
	 *   <li>只有调用行、没有命中行 → 一次<b>看起来"0 条命中"</b>的检索。
	 *       那是最坏的一种：它与"真的没查到"在数据上完全一样，
	 *       于是"语料里没有"与"写库写丢了一半"分不开。</li>
	 * </ul>
	 * 后者尤其危险，因为它是**静默**的：0 条命中本来就是一个正常结果。
	 */
	@Override
	public void recordRetrieval(AgentRunRecord.RetrievalRecord record) {
		String callSql = """
				INSERT INTO retrieval_call
				  (run_id, step_index, call_index, tool_name, query, top_k, hit_count, index_size, elapsed_ms)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE
				  tool_name = VALUES(tool_name), query = VALUES(query), top_k = VALUES(top_k),
				  hit_count = VALUES(hit_count), index_size = VALUES(index_size), elapsed_ms = VALUES(elapsed_ms)
				""";
		String hitSql = """
				INSERT INTO retrieval_hit
				  (run_id, step_index, call_index, hit_rank, chunk_id, document_id, breadcrumb,
				   token_count, score, excerpt)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE
				  chunk_id = VALUES(chunk_id), document_id = VALUES(document_id),
				  breadcrumb = VALUES(breadcrumb), token_count = VALUES(token_count),
				  score = VALUES(score), excerpt = VALUES(excerpt)
				""";
		RetrievalTrace trace = record.trace();
		try (Connection conn = pool.getConnection()) {
			conn.setAutoCommit(false);
			try {
				try (PreparedStatement ps = conn.prepareStatement(callSql)) {
					ps.setString(1, record.runId());
					ps.setInt(2, record.stepIndex());
					ps.setInt(3, record.callIndex());
					// 工具名 NOT NULL DEFAULT ''：空串是"取不到名字"，与"某把叫空串的工具"不会混淆
					// （后者不是一个合法工具名）。存 NULL 则要多一处 null 判断，收益为零
					ps.setString(4, record.toolName() == null ? "" : record.toolName());
					ps.setString(5, trace.query());
					ps.setInt(6, trace.topK());
					ps.setInt(7, trace.hitCount());
					ps.setInt(8, trace.indexSize());
					// 落的是**检索自己**的耗时，不是整个工具调用的耗时。
					// 后者含参数解析与闸门排队，而"检索花了多久"是另一个问题
					// （工具调用的那个数已经在 agent_tool_invocation 里了，两处给同一个数没用）
					ps.setLong(9, trace.elapsedMs());
					ps.executeUpdate();
				}
				try (PreparedStatement ps = conn.prepareStatement(hitSql)) {
					for (RetrievalTrace.Hit hit : trace.hits()) {
						ps.setString(1, record.runId());
						ps.setInt(2, record.stepIndex());
						ps.setInt(3, record.callIndex());
						ps.setInt(4, hit.rank());
						ps.setString(5, hit.chunkId());
						ps.setString(6, hit.documentId());
						ps.setString(7, hit.breadcrumb());
						ps.setInt(8, hit.tokenCount());
						ps.setDouble(9, hit.score());
						ps.setString(10, hit.excerpt());
						ps.addBatch();
					}
					// 0 条命中时不执行 batch：`executeBatch` 在空 batch 上是合法的（返回空数组），
					// 但显式跳过能让"这次检索真的没有命中"这件事在代码里也读得出来
					if (trace.hitCount() > 0) {
						ps.executeBatch();
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
			throw new AgentStoreException("记检索明细失败 runId=" + record.runId() + " step=" + record.stepIndex()
					+ " call=" + record.callIndex(), e);
		}
	}

	@Override
	public void updateProgress(String runId, int totalTokens, int toolCallCount, long elapsedMs) {		try (Connection conn = pool.getConnection();
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
			int toolCallCount, long elapsedMs, String error, ai.agent.service.agent.AgentFailureCause failureCause) {
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

	/**
	 * 读一次运行的全部检索明细，<b>按调用归组</b>。
	 *
	 * <h2>为什么用两条查询 + 内存里归组，而不是一条 JOIN</h2>
	 * 一条 {@code LEFT JOIN} 看起来更"省一次往返"，但它有一个安静的陷阱：
	 * <b>调用行会被命中的条数放大</b>（一次 5 条命中的检索会变成 5 行），
	 * 于是"这次运行检索了几次"要写成 {@code COUNT(DISTINCT ...)} ——
	 * 而任何一处漏了 DISTINCT，统计就把"检索 3 次"报成"检索 11 次"，
	 * 且数字看起来完全合理。成本看板那条"JOIN 不能把 token 放大"是同一个坑。
	 *
	 * <p>两条查询的总行数是 {@code 调用数 + 命中数}，量级很小（一次运行几十行），
	 * 所以这个选择在数据量上也没有代价。
	 */
	@Override
	public List<AgentRunRecord.RetrievalRecord> retrievalsOf(String runId) {
		String callSql = """
				SELECT step_index, call_index, tool_name, query, top_k, index_size, elapsed_ms
				  FROM retrieval_call
				 WHERE run_id = ?
				 ORDER BY step_index, call_index
				""";
		// ⚠️ 命中那一侧**查不出"哪把工具发起的"**：retrieval_hit 的主键里没有 tool_name。
		// 所以工具名从 retrieval_call 那一侧取，join 的键是 (step_index, call_index)。
		// 这里用 JOIN 把工具名一次带出来，而不是再查一遍 agent_tool_invocation ——
		// retrieval_call 本来就是"哪把工具在检索"的权威来源（写入时由调用方给出）
		String hitSql = """
				SELECT h.step_index, h.call_index, h.hit_rank, h.chunk_id, h.document_id, h.breadcrumb,
				       h.token_count, h.score, h.excerpt, c.tool_name
				  FROM retrieval_hit h
				  JOIN retrieval_call c
				    ON c.run_id = h.run_id AND c.step_index = h.step_index AND c.call_index = h.call_index
				 WHERE h.run_id = ?
				 ORDER BY h.step_index, h.call_index, h.hit_rank
				""";
		try (Connection conn = pool.getConnection()) {
			// (step, call) → 命中列表
			Map<String, List<RetrievalTrace.Hit>> hitsByCall = new LinkedHashMap<>();
			try (PreparedStatement ps = conn.prepareStatement(hitSql)) {
				ps.setString(1, runId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						String key = rs.getInt("step_index") + ":" + rs.getInt("call_index");
						hitsByCall.computeIfAbsent(key, k -> new ArrayList<>())
								.add(new RetrievalTrace.Hit(rs.getInt("hit_rank"), rs.getString("chunk_id"),
										rs.getString("document_id"), rs.getString("breadcrumb"),
										rs.getInt("token_count"), rs.getDouble("score"), rs.getString("excerpt")));
					}
				}
			}

			List<AgentRunRecord.RetrievalRecord> out = new ArrayList<>();
			try (PreparedStatement ps = conn.prepareStatement(callSql)) {
				ps.setString(1, runId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						int stepIndex = rs.getInt("step_index");
						int callIndex = rs.getInt("call_index");
						List<RetrievalTrace.Hit> hits = hitsByCall.getOrDefault(stepIndex + ":" + callIndex,
								List.of());
						out.add(new AgentRunRecord.RetrievalRecord(runId, stepIndex, callIndex,
								rs.getString("tool_name"), new RetrievalTrace(rs.getString("query"),
										rs.getInt("top_k"), rs.getInt("index_size"), hits, rs.getLong("elapsed_ms"))));
					}
				}
			}
			return List.copyOf(out);
		}
		catch (SQLException e) {
			throw new AgentStoreException("读检索明细失败 runId=" + runId, e);
		}
	}

	@Override
	public Optional<AgentRunRecord.StepCheckpoint> latestCheckpoint(String runId) {
		String sql = """
				SELECT run_id, step_index, thought_text, prompt_tokens, completion_tokens, cache_read_tokens,
				       model_name, prompt_fingerprint, canary_variant, elapsed_ms, transcript
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
				SELECT run_id, step_index, thought_text, prompt_tokens, completion_tokens, cache_read_tokens,
				       model_name, prompt_fingerprint, canary_variant, elapsed_ms, transcript
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
							// 【这里手工重建一条记录，所以每加一个字段都要在这里补一次】
							// transcript 那一批（新增字段落在末尾）用的就是这套做法。
							// ★ 它的失败方式是**编译不过**，而不是静默丢字段 —— 这正是
							// "位置参数多的构造器"在这里反而安全的原因：漏了编译器会拦。
							// 而 tenantId 漏了的话，被认领的运行会**丢掉租户**，
							// 于是它的成本从"某个租户"悄悄挪到"未标注租户"那一行
							claimed.add(new AgentRunRecord(candidate.id(), candidate.question(),
									RunStatus.INTERRUPTED, candidate.stopReason(), candidate.finalAnswer(),
									candidate.totalTokens(), candidate.toolCallCount(), candidate.elapsedMs(),
									candidate.node(), candidate.error(), candidate.createdAt(), Instant.now(),
									candidate.failureCause(), candidate.intent(), candidate.conversationId(),
									candidate.tenantId()));
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

	/**
	 * 同一个会话里的全部运行（按创建时间<b>正序</b>）。
	 *
	 * <p>空白键返回空列表，**不是**"返回全部"：一个空白键如果被当成通配，
	 * 那么这个方法的调用方会在某次传参写错时拿到一份看起来完全正常的完整列表 ——
	 * 而"这段对话只有一次运行"与"没按会话过滤"在页面上会长得一样。
	 */
	@Override
	public List<AgentRunRecord> runsOfConversation(String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			return List.of();
		}
		try (Connection conn = pool.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"SELECT * FROM agent_run WHERE conversation_id = ? ORDER BY created_at, id")) {
			ps.setString(1, conversationId);
			try (ResultSet rs = ps.executeQuery()) {
				List<AgentRunRecord> runs = new ArrayList<>();
				while (rs.next()) {
					runs.add(mapRun(rs));
				}
				return List.copyOf(runs);
			}
		}
		catch (SQLException e) {
			throw new AgentStoreException("读会话运行失败 conversationId=" + conversationId, e);
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
						: ai.agent.service.agent.AgentFailureCause.valueOf(rs.getString("failure_cause")),
				// 归因键、会话 id、租户都是"可以没有"的事实，NULL 就原样读成 null。
				// 同样不编默认值：编一个（例如"默认会话"）会让所有没有会话信息的运行
				// 在页面上聚成一段**不存在的对话**
				rs.getString("intent"), rs.getString("conversation_id"),
				// 【阶段四第 6 件】租户。老数据（加列之前写的行）这一列是 NULL →
				// 读成 null → 看板显示成「未标注租户」并独立成一行。
				// **不给它编一个"默认租户"**：那会把一批互不相干的运行并成一个租户，
				// 而那个租户的成本会虚高，且虚高的部分无从发现
				rs.getString("tenant_id"));
	}

	private static AgentRunRecord.StepCheckpoint mapStep(ResultSet rs) throws SQLException {
		return new AgentRunRecord.StepCheckpoint(rs.getString("run_id"), rs.getInt("step_index"),
				rs.getString("transcript"), rs.getString("thought_text"), rs.getInt("prompt_tokens"),
				rs.getInt("completion_tokens"), rs.getLong("elapsed_ms"), rs.getInt("cache_read_tokens"),
				rs.getString("model_name"), rs.getString("prompt_fingerprint"), rs.getString("canary_variant"));
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	/**
	 * 把连接池借给**同一个库上的其它读侧**（成本看板的聚合查询）。
	 *
	 * <h2>为什么是"借池子"而不是让它们各自建一个</h2>
	 * H2 的内存库靠 {@code DB_CLOSE_DELAY=-1} 保活，所以**再多一个池子也确实连得上** ——
	 * 也就是说，各自建池在本地完全能跑通，问题要到生产才出现：
	 * 每个池子都有自己的连接上限与超时，于是一个"多加了几个只读查询"的改动
	 * 会悄悄把进程的连接上限乘以几，而现象是"偶发的连接不够"，
	 * 排查方向会跑到"是不是有连接泄漏"上去。
	 *
	 * <p>同时，池子的生命周期只能有一个负责人：{@link #close()} 在应用关闭时 dispose 它。
	 * 两个类都持池、都 dispose，会在关闭时比赛谁先关掉 —— 而那个错误
	 * 只出现在停机过程里，最难复现。
	 */
	public JdbcConnectionPool pool() {
		return pool;
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
