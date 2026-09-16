package ai.agent.cost;

import ai.agent.agent.store.impl.JdbcAgentRunStore;
import ai.agent.config.AgentStoreProperties;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link CostQuery} 的 H2/MySQL 实现（阶段四成本看板）。
 *
 * <h2>它为什么"依赖建表"而不是自己建</h2>
 * 与 {@code JdbcToolApprovalStore} 同一套做法：DDL 由 {@link JdbcAgentRunStore}
 * 在启动时执行一次（{@code db/agent-schema.sql}），本类通过构造器参数
 * {@code JdbcAgentRunStore} 声明这个顺序依赖 —— 于是"表还没建"这件事在类型上就不会发生，
 * 而不是靠"启动顺序大概是这样的"。
 *
 * <h2>连接池是共用同一个实例的</h2>
 * 两个存储类拿的是同一个 {@code url}，所以 H2 的内存库靠 {@code DB_CLOSE_DELAY=-1} 保活
 * （这一点写在配置的注释里）。本类**不 dispose 这个池** —— 池的生命周期归
 * {@code JdbcAgentRunStore}（它是那个建池的人）。
 *
 * <h2>SQL 里刻意不出现计价参数</h2>
 * 这些查询只回**token 与次数的合计**，一行钱都不算。换算由 {@link CostPricing} 做。
 * 两个理由：① 单价是 BigDecimal，塞进 SQL 会变成浮点参数，误差直接进钱数；
 * ② 把"聚合"与"计价"分开之后，计价那一半可以被逐条单测覆盖（而 SQL 里的算术不能）。
 */
@Component
public class JdbcCostQuery implements CostQuery {

	private static final Logger log = LoggerFactory.getLogger(JdbcCostQuery.class);

	private final JdbcConnectionPool pool;

	public JdbcCostQuery(AgentStoreProperties properties, JdbcAgentRunStore runStore) {
		// runStore 只为声明"建表必须先发生"这个顺序依赖：不引它的话，
		// 在依赖树里本类可能先被构造，于是第一次查询报"表不存在" ——
		// 而那个错误看起来像 SQL 写错了
		this.pool = runStore.pool();
		log.info("成本看板查询就绪：url={}（与运行库共用连接池，不单独持有）", properties.url());
	}

	// ------------------------------------------------------------------
	//  按天 / 按维度
	// ------------------------------------------------------------------

	@Override
	public List<DayTotals> byDay(LocalDate from, LocalDate to) {
		// 【日期分桶的两个坑，都在这条 SQL 里处理掉了】
		//  ① `CAST(... AS DATE)` 而不是某个数据库的日期函数：H2 与 MySQL 都支持 CAST，
		//     而 DATE_FORMAT / FORMATDATETIME 各是一家之言 —— 那种写法会让"换真库"变成一次重写；
		//  ② 分组键用 `COALESCE(CAST(r.created_at AS DATE), r.updated_at ...)` 没有意义，
		//     所以这里靠 created_at 的 NOT NULL 约束（DDL 里写了）来保证它永远不为空。
		String sql = """
				SELECT CAST(r.created_at AS DATE) AS bucket_day,
				       COUNT(*) AS run_count,
				       SUM(CASE WHEN r.status <> ? THEN 1 ELSE 0 END) AS finished_count,
				       SUM(CASE WHEN r.status = ? THEN 1 ELSE 0 END) AS stopped_count,
				       SUM(CASE WHEN r.status = ? THEN 1 ELSE 0 END) AS failed_count,
				       COALESCE(SUM(s.prompt_tokens), 0) AS prompt_tokens,
				       COALESCE(SUM(s.cache_read_tokens), 0) AS cached_tokens,
				       COALESCE(SUM(s.completion_tokens), 0) AS completion_tokens,
				       COALESCE(SUM(t.call_count), 0) AS tool_calls,
				       COALESCE(SUM(t.failures), 0) AS tool_failures,
				       COALESCE(SUM(t.tool_elapsed_sum), 0) AS tool_elapsed,
				       COALESCE(SUM(r.elapsed_ms), 0) AS run_elapsed,
				       MIN(r.created_at) AS first_at
				  FROM agent_run r
				  LEFT JOIN (
				        SELECT run_id, SUM(prompt_tokens) AS prompt_tokens,
				               SUM(cache_read_tokens) AS cache_read_tokens,
				               SUM(completion_tokens) AS completion_tokens
				          FROM agent_step GROUP BY run_id
				      ) s ON s.run_id = r.id
				  LEFT JOIN (
				        SELECT run_id, COUNT(*) AS call_count,
				               SUM(CASE WHEN stage = ? THEN 1 ELSE 0 END) AS failures,
				               SUM(elapsed_ms) AS tool_elapsed_sum
				          FROM agent_tool_invocation GROUP BY run_id
				      ) t ON t.run_id = r.id
				 WHERE r.created_at >= ? AND r.created_at < ?
				 GROUP BY CAST(r.created_at AS DATE)
				 ORDER BY bucket_day
				""";
		List<DayTotals> rows = new ArrayList<>();
		// 【上界为什么是"次日 0 点"而不是"当天 23:59:59"】
		// 用 `< 次日` 而不是 `<= 当天末`：后者会漏掉 23:59:59.5 这种带毫秒的时间，
		// 而漏掉的那条会让"今天"的数字比运行列表少一条 —— 一种只会让人怀疑数据的差异
		LocalDate exclusiveEnd = to.plusDays(1);
		forEachRow(sql, statement -> {
			statement.setString(1, Names.RUNNING);
			statement.setString(2, Names.STOPPED);
			statement.setString(3, Names.FAILED);
			statement.setString(4, Names.EXECUTION_ERROR);
			statement.setDate(5, Date.valueOf(from));
			statement.setDate(6, Date.valueOf(exclusiveEnd));
		}, rs -> {
			LocalDate day = toLocalDate(rs.getObject("bucket_day"));
			rows.add(new DayTotals(day, readGroup(day == null ? null : day.toString(), rs)));
		}, "按天汇总成本");
		return List.copyOf(rows);
	}

	@Override
	public List<GroupTotals> byIntent(LocalDate from, LocalDate to) {
		String sql = """
				SELECT r.intent AS grp,
				       COUNT(*) AS run_count,
				       SUM(CASE WHEN r.status <> ? THEN 1 ELSE 0 END) AS finished_count,
				       SUM(CASE WHEN r.status = ? THEN 1 ELSE 0 END) AS stopped_count,
				       SUM(CASE WHEN r.status = ? THEN 1 ELSE 0 END) AS failed_count,
				       COALESCE(SUM(s.prompt_tokens), 0) AS prompt_tokens,
				       COALESCE(SUM(s.cache_read_tokens), 0) AS cached_tokens,
				       COALESCE(SUM(s.completion_tokens), 0) AS completion_tokens,
				       COALESCE(SUM(t.call_count), 0) AS tool_calls,
				       COALESCE(SUM(t.failures), 0) AS tool_failures,
				       COALESCE(SUM(t.tool_elapsed_sum), 0) AS tool_elapsed,
				       COALESCE(SUM(r.elapsed_ms), 0) AS run_elapsed,
				       MIN(r.created_at) AS first_at
				  FROM agent_run r
				  LEFT JOIN (
				        SELECT run_id, SUM(prompt_tokens) AS prompt_tokens,
				               SUM(cache_read_tokens) AS cache_read_tokens,
				               SUM(completion_tokens) AS completion_tokens
				          FROM agent_step GROUP BY run_id
				      ) s ON s.run_id = r.id
				  LEFT JOIN (
				        SELECT run_id, COUNT(*) AS call_count,
				               SUM(CASE WHEN stage = ? THEN 1 ELSE 0 END) AS failures,
				               SUM(elapsed_ms) AS tool_elapsed_sum
				          FROM agent_tool_invocation GROUP BY run_id
				      ) t ON t.run_id = r.id
				 WHERE r.created_at >= ? AND r.created_at < ?
				 GROUP BY r.intent
				 ORDER BY COALESCE(SUM(s.prompt_tokens), 0) + COALESCE(SUM(s.completion_tokens), 0) DESC
				""";
		return groupRows(sql, from, to, "按业务归因汇总成本");
	}

	@Override
	public List<GroupTotals> byTenant(LocalDate from, LocalDate to) {
		// 【与 byIntent 逐字相同，只换了分组列】这不是复制粘贴偷懒，而是刻意的：
		// 两个维度**必须**用同一套口径（同样的 JOIN、同样的 CASE WHEN、同样的窗口边界），
		// 否则"两个视图加起来应该相等"这条断言就不再成立 —— 而它正是
		// dayAndIntentViewsAgree 那条测试在钉的东西。
		// 把它们合成一个"接受列名参数"的方法会让列名变成字符串拼接（SQL 注入面 +
		// 打错列名在编译期查不出来），代价大于省下的那几行。
		//
		// ⚠️ 改这一段时**两处都要改**（byIntent 与 byTenant）。漏改一处的症状是
		// "租户那一栏的某个数字与业务那一栏对不上"，而两栏看起来都正常。
		String sql = """
				SELECT r.tenant_id AS grp,
				       COUNT(*) AS run_count,
				       SUM(CASE WHEN r.status <> ? THEN 1 ELSE 0 END) AS finished_count,
				       SUM(CASE WHEN r.status = ? THEN 1 ELSE 0 END) AS stopped_count,
				       SUM(CASE WHEN r.status = ? THEN 1 ELSE 0 END) AS failed_count,
				       COALESCE(SUM(s.prompt_tokens), 0) AS prompt_tokens,
				       COALESCE(SUM(s.cache_read_tokens), 0) AS cached_tokens,
				       COALESCE(SUM(s.completion_tokens), 0) AS completion_tokens,
				       COALESCE(SUM(t.call_count), 0) AS tool_calls,
				       COALESCE(SUM(t.failures), 0) AS tool_failures,
				       COALESCE(SUM(t.tool_elapsed_sum), 0) AS tool_elapsed,
				       COALESCE(SUM(r.elapsed_ms), 0) AS run_elapsed,
				       MIN(r.created_at) AS first_at
				  FROM agent_run r
				  LEFT JOIN (
				        SELECT run_id, SUM(prompt_tokens) AS prompt_tokens,
				               SUM(cache_read_tokens) AS cache_read_tokens,
				               SUM(completion_tokens) AS completion_tokens
				          FROM agent_step GROUP BY run_id
				      ) s ON s.run_id = r.id
				  LEFT JOIN (
				        SELECT run_id, COUNT(*) AS call_count,
				               SUM(CASE WHEN stage = ? THEN 1 ELSE 0 END) AS failures,
				               SUM(elapsed_ms) AS tool_elapsed_sum
				          FROM agent_tool_invocation GROUP BY run_id
				      ) t ON t.run_id = r.id
				 WHERE r.created_at >= ? AND r.created_at < ?
				 GROUP BY r.tenant_id
				 ORDER BY COALESCE(SUM(s.prompt_tokens), 0) + COALESCE(SUM(s.completion_tokens), 0) DESC
				""";
		return groupRows(sql, from, to, "按租户归因汇总成本");
	}

	@Override
	public List<GroupTotals> byModel(LocalDate from, LocalDate to) {
		// 口径来自**步骤表**（不是运行表）：一个运行里换过模型时，两步会落在两个模型上，
		// 而那正是"灰度期间钱花在哪个模型上"的唯一答案
		String sql = """
				SELECT s.model_name AS grp,
				       COUNT(DISTINCT s.run_id) AS run_count,
				       0 AS finished_count,
				       0 AS stopped_count,
				       0 AS failed_count,
				       COALESCE(SUM(s.prompt_tokens), 0) AS prompt_tokens,
				       COALESCE(SUM(s.cache_read_tokens), 0) AS cached_tokens,
				       COALESCE(SUM(s.completion_tokens), 0) AS completion_tokens,
				       0 AS tool_calls,
				       0 AS tool_failures,
				       0 AS tool_elapsed,
				       COALESCE(SUM(s.elapsed_ms), 0) AS run_elapsed,
				       MIN(s.created_at) AS first_at
				  FROM agent_step s
				  JOIN agent_run r ON r.id = s.run_id
				 WHERE r.created_at >= ? AND r.created_at < ?
				 GROUP BY s.model_name
				 ORDER BY COALESCE(SUM(s.prompt_tokens), 0) + COALESCE(SUM(s.completion_tokens), 0) DESC
				""";
		LocalDate exclusiveEnd = to.plusDays(1);
		List<GroupTotals> rows = new ArrayList<>();
		forEachRow(sql, statement -> {
			statement.setDate(1, Date.valueOf(from));
			statement.setDate(2, Date.valueOf(exclusiveEnd));
		}, rs -> rows.add(readGroup(rs.getString("grp"), rs)), "按模型汇总成本");
		return List.copyOf(rows);
	}

	@Override
	public List<ModelDayRow> byModelAndDay(LocalDate from, LocalDate to) {
		String sql = """
				SELECT CAST(r.created_at AS DATE) AS bucket_day,
				       s.model_name AS model_name,
				       COALESCE(SUM(s.prompt_tokens), 0) AS prompt_tokens,
				       COALESCE(SUM(s.cache_read_tokens), 0) AS cached_tokens,
				       COALESCE(SUM(s.completion_tokens), 0) AS completion_tokens
				  FROM agent_step s
				  JOIN agent_run r ON r.id = s.run_id
				 WHERE r.created_at >= ? AND r.created_at < ?
				 GROUP BY CAST(r.created_at AS DATE), s.model_name
				 ORDER BY bucket_day, s.model_name
				""";
		LocalDate exclusiveEnd = to.plusDays(1);
		List<ModelDayRow> rows = new ArrayList<>();
		forEachRow(sql, statement -> {
			statement.setDate(1, Date.valueOf(from));
			statement.setDate(2, Date.valueOf(exclusiveEnd));
		}, rs -> rows.add(new ModelDayRow(toLocalDate(rs.getObject("bucket_day")), rs.getString("model_name"),
				rs.getLong("prompt_tokens"), rs.getLong("cached_tokens"), rs.getLong("completion_tokens"))),
				"按天+模型汇总成本");
		return List.copyOf(rows);
	}

	@Override
	public List<ToolTotals> byTool(LocalDate from, LocalDate to) {
		String sql = """
				SELECT i.tool_name AS tool_name,
				       COUNT(*) AS call_count,
				       SUM(CASE WHEN i.stage = ? THEN 1 ELSE 0 END) AS failures,
				       SUM(CASE WHEN i.stage = ? THEN 1 ELSE 0 END) AS unknown_tool,
				       SUM(CASE WHEN i.stage = ? THEN 1 ELSE 0 END) AS validation_errors,
				       SUM(CASE WHEN i.stage = ? THEN 1 ELSE 0 END) AS parse_errors,
				       SUM(CASE WHEN i.stage = ? THEN 1 ELSE 0 END) AS rejected,
				       SUM(CASE WHEN i.stage = ? THEN 1 ELSE 0 END) AS circuit_open,
				       COALESCE(SUM(i.elapsed_ms), 0) AS tool_elapsed_sum,
				       COUNT(DISTINCT i.run_id) AS runs
				  FROM agent_tool_invocation i
				  JOIN agent_run r ON r.id = i.run_id
				 WHERE r.created_at >= ? AND r.created_at < ?
				 GROUP BY i.tool_name
				 ORDER BY COUNT(*) DESC, i.tool_name
				""";
		LocalDate exclusiveEnd = to.plusDays(1);
		List<ToolTotals> rows = new ArrayList<>();
		forEachRow(sql, statement -> {
			statement.setString(1, Names.EXECUTION_ERROR);
			statement.setString(2, Names.UNKNOWN_TOOL);
			statement.setString(3, Names.VALIDATION_ERROR);
			statement.setString(4, Names.PARSE_ERROR);
			statement.setString(5, Names.REQUEST_REJECTED);
			statement.setString(6, Names.CIRCUIT_OPEN);
			statement.setDate(7, Date.valueOf(from));
			statement.setDate(8, Date.valueOf(exclusiveEnd));
		}, rs -> rows.add(new ToolTotals(rs.getString("tool_name"), rs.getLong("call_count"),
				rs.getLong("failures"), rs.getLong("unknown_tool"), rs.getLong("validation_errors"),
				rs.getLong("parse_errors"), rs.getLong("rejected"), rs.getLong("circuit_open"),
				rs.getLong("tool_elapsed_sum"), rs.getLong("runs"))), "按工具汇总调用");
		return List.copyOf(rows);
	}

	@Override
	public Reconciliation reconcile(LocalDate from, LocalDate to) {
		String sql = """
				SELECT COALESCE(SUM(r.total_tokens), 0) AS run_tokens,
				       COALESCE(SUM(s.prompt_tokens), 0) + COALESCE(SUM(s.completion_tokens), 0) AS step_tokens,
				       COALESCE(SUM(s.prompt_tokens), 0) AS prompt_tokens,
				       COALESCE(SUM(s.cache_read_tokens), 0) AS cached_tokens,
				       COUNT(*) AS runs,
				       SUM(CASE WHEN s.run_id IS NULL THEN 1 ELSE 0 END) AS runs_without_steps,
				       SUM(CASE WHEN s.cache_read_tokens > 0 THEN 1 ELSE 0 END) AS cache_hit_runs
				  FROM agent_run r
				  LEFT JOIN (
				        SELECT run_id, SUM(prompt_tokens) AS prompt_tokens,
				               SUM(cache_read_tokens) AS cache_read_tokens,
				               SUM(completion_tokens) AS completion_tokens
				          FROM agent_step GROUP BY run_id
				      ) s ON s.run_id = r.id
				 WHERE r.created_at >= ? AND r.created_at < ?
				""";
		LocalDate exclusiveEnd = to.plusDays(1);
		Reconciliation[] holder = new Reconciliation[1];
		forEachRow(sql, statement -> {
			statement.setDate(1, Date.valueOf(from));
			statement.setDate(2, Date.valueOf(exclusiveEnd));
		}, rs -> holder[0] = new Reconciliation(rs.getLong("run_tokens"), rs.getLong("step_tokens"),
				rs.getLong("runs_without_steps"), rs.getLong("runs"), rs.getLong("cache_hit_runs"),
				rs.getLong("prompt_tokens"), rs.getLong("cached_tokens")), "成本对账");
		return holder[0] == null ? new Reconciliation(0, 0, 0, 0, 0, 0, 0) : holder[0];
	}

	@Override
	public List<String> modelsInWindow(LocalDate from, LocalDate to) {
		String sql = """
				SELECT DISTINCT s.model_name AS model_name
				  FROM agent_step s
				  JOIN agent_run r ON r.id = s.run_id
				 WHERE r.created_at >= ? AND r.created_at < ?
				 ORDER BY s.model_name
				""";
		LocalDate exclusiveEnd = to.plusDays(1);
		List<String> models = new ArrayList<>();
		forEachRow(sql, statement -> {
			statement.setDate(1, Date.valueOf(from));
			statement.setDate(2, Date.valueOf(exclusiveEnd));
		}, rs -> models.add(rs.getString("model_name")), "窗口内出现过的模型");
		return List.copyOf(models);
	}

	// ------------------------------------------------------------------
	//  公共部分
	// ------------------------------------------------------------------

	/**
	 * 跑一条分组查询（把 NULL / 空串的键统一成 {@code null}）。
	 *
	 * <p>为什么这个归一化是必须的："没标注"这一个语义在库里可能有两种物理形态 ——
	 * 新建表之后写的是 NULL，而某次用空串写入（或者从别的系统导入）会留下空串。
	 * 不归一的话，看板上会出现**两行都叫「未标注」**的记录：数字被拆成两半，
	 * 而两半加起来才是真相，这种事没人会去加。
	 */
	private List<GroupTotals> groupRows(String sql, LocalDate from, LocalDate to, String what) {
		LocalDate exclusiveEnd = to.plusDays(1);
		List<GroupTotals> rows = new ArrayList<>();
		forEachRow(sql, statement -> {
			// 【绑定的顺序就是 SQL 里 `?` 出现的顺序】改动 SELECT 那几个 CASE WHEN 时
			// 必须一起改这里 —— 绑错一个位置不会报错，只会让"被上限拦下"与"失败"
			// 两个数字悄悄互换，而看板上两个数字都长得正常
			statement.setString(1, Names.RUNNING);
			statement.setString(2, Names.STOPPED);
			statement.setString(3, Names.FAILED);
			statement.setString(4, Names.EXECUTION_ERROR);
			statement.setDate(5, Date.valueOf(from));
			statement.setDate(6, Date.valueOf(exclusiveEnd));
		}, rs -> {
			String raw = rs.getString("grp");
			String key = raw == null || raw.isBlank() ? null : raw;
			rows.add(readGroup(key, rs));
		}, what);
		return List.copyOf(rows);
	}

	/** 从一行结果里读出一组聚合值（各维度共用，列名统一）。 */
	private static GroupTotals readGroup(String key, ResultSet rs) throws SQLException {
		return new GroupTotals(key, rs.getLong("run_count"), rs.getLong("finished_count"),
				rs.getLong("stopped_count"), rs.getLong("failed_count"), rs.getLong("prompt_tokens"),
				rs.getLong("cached_tokens"),
				rs.getLong("completion_tokens"), rs.getLong("tool_calls"), rs.getLong("tool_failures"),
				rs.getLong("tool_elapsed"), rs.getLong("run_elapsed"), toInstant(rs.getTimestamp("first_at")));
	}

	/** 绑定参数的小接口（避免每个查询都写一遍 setXxx 的样板）。 */
	private interface Binder {

		void bind(PreparedStatement statement) throws SQLException;

	}

	/** 读一行的小接口。 */
	private interface RowReader {

		void read(ResultSet rs) throws SQLException;

	}

	/**
	 * 跑一条聚合查询并逐行交给 {@code reader}。
	 *
	 * <p>三个查询除了 SQL 与参数之外完全一样，所以抽成这一个方法 ——
	 * 但**没有**抽成"通用 DAO"：这里只有"绑定参数 → 遍历结果"这一步是共用的，
	 * 而把连接获取、异常包装也抽出去会让"这次查询失败了"的信息被摊薄
	 * （本项目一贯的做法：可复用面收窄到"确实一样的部分"）。
	 */
	private void forEachRow(String sql, Binder binder, RowReader reader, String what) {
		try (Connection conn = pool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			binder.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					reader.read(rs);
				}
			}
		}
		catch (SQLException e) {
			// 不吞：看板报错比看板显示一个偏小的数字好得多。
			// 一个"部分数据"的成本表会被当成真的用
			throw new IllegalStateException("成本看板查询失败（" + what + "）：" + e.getMessage(), e);
		}
	}

	private static LocalDate toLocalDate(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof LocalDate localDate) {
			return localDate;
		}
		if (raw instanceof Date date) {
			return date.toLocalDate();
		}
		if (raw instanceof java.sql.Timestamp timestamp) {
			return timestamp.toLocalDateTime().toLocalDate();
		}
		return LocalDate.parse(raw.toString());
	}

	private static Instant toInstant(java.sql.Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
