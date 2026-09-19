package ai.agent.service.impl.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>两把 SQL 工具共用的执行器</b>：一个读出口、一个写出口，其余全部落在这一层。
 *
 * <h2>为什么读与写是两个方法，而不是一个 {@code execute}</h2>
 * 它们的返回形状与失败语义都不同：读要列名 + 行 + 截断标记，写要影响行数。
 * 更实际的理由是 {@code executeQuery} <b>打在写语句上会抛</b>
 * （实测 H2：{@code Method is only allowed for a query}），
 * 于是"哪把工具走哪个出口"这件事在代码结构上就是确定的 ——
 * 不会出现"某个分支顺手把写语句当查询跑了"。
 *
 * <h2>上限为什么是这里的常量，而不是 {@code application.properties} 里的配置项</h2>
 * 这是本次改动刻意做的一个取舍：不新增配置项、宿主应用的开机配置一个字符都不动。
 * 先例在本项目里已有（{@code SearchDocsTool.DEFAULT_TOP_K}、
 * {@code ReadSectionTool.MAX_CHARS=1800}、{@code StructuralChunker} 的重复率上限）。
 * <b>代价要一起说清</b>：这些值现在是编译期常量，要调只能改代码重新编译；
 * 真需要它随环境调时，把这个类改成一个注入的 record 即可，
 * 两把工具与渲染逻辑都不用动（它们只依赖本类的公开方法）。
 *
 * <h2>{@code setReadOnly(true)} 刻意<b>没有</b>出现在这里</h2>
 * 实测（H2 2.3.232）：设了 {@code setReadOnly(true)} 之后 {@code isReadOnly()} 回读 {@code false}，
 * 随后的 {@code INSERT} <b>照样成功</b>。也就是说它在这个版本上不是防线，
 * 而一个"看起来像防线、实测没生效"的调用比没有它更危险 ——
 * 后者只让人少拿一个保证，前者会让人以为已经有一道保证。
 * 真正的边界在 {@link SqlGuard}（首关键字）与 {@code ToolHints}（写操作必须人工确认）。
 */
@Component
public class JdbcSqlExecutor {

	private static final Logger log = LoggerFactory.getLogger(JdbcSqlExecutor.class);

	/**
	 * 单次查询最多返回多少行。
	 *
	 * <p>它<b>同时</b>进 JSON Schema（模型看得到的上限）与 {@link Statement#setMaxRows}（真正执行的上限）——
	 * 一处定义、两处生效，所以"模型以为能要 500 行"与"实际只给 20 行"不可能漂移。
	 *
	 * <p>必须在 {@code executeQuery} <b>之前</b>设：之后设的话结果集已经全量物化了，
	 * 限制就只剩"少显示几行"，而内存与查询代价已经花掉。
	 */
	static final int MAX_ROWS = 20;

	/** 单个单元格最多显示多少字符（长 TEXT 列不设它，一次查询就把上下文顶满）。 */
	static final int MAX_CELL_CHARS = 60;

	/** 最多显示多少列（超出的列不显示，但会在结果文本里说明被省略了几列）。 */
	static final int MAX_COLUMNS = 20;

	/**
	 * 单条语句的超时（秒）。
	 *
	 * <p>5 秒的依据：实测 {@code setQueryTimeout(1)} 真能把一个巨型交叉连接中止在 1016ms，
	 * 所以这个开关是有效的，取 5 秒是给真表上的聚合留余量；
	 * 同时它远小于 {@code ai.agent.loop.max-duration-ms=60000} ——
	 * 一条慢查询不该把整个 Agent 运行的预算吃掉。
	 */
	static final int QUERY_TIMEOUT_SECONDS = 5;

	/**
	 * 单条 SQL 的字符上限。
	 *
	 * <p>取 4000 是为了与 {@code JdbcToolApprovalStore.ARGUMENTS_MAX_CHARS=4000} 对齐：
	 * 更长的 SQL 会在审批记录里被截断，而"人批准的那份必须就是真正会被执行的那份" ——
	 * 让工具自己先拒掉，比让审批页面显示一份被截断的语句诚实。
	 */
	static final int MAX_SQL_CHARS = 4000;

	/** 查询结果里 NULL 的显示形式（与空字符串、与字面量 {@code 'NULL'} 都要长得不一样）。 */
	static final String NULL_MARK = "(NULL)";

	private final DataSource dataSource;

	public JdbcSqlExecutor(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * 一次读查询的结果（列名 + 行 + 截断标记）。
	 *
	 * @param columns 列名（最多 {@link #MAX_COLUMNS} 个）
	 * @param omittedColumns 因为列数上限而没有显示的列数（0 = 全显示了）
	 * @param rows 数据行；单元格已按 {@link #MAX_CELL_CHARS} 截断、NULL 显示成 {@link #NULL_MARK}
	 * @param rowsTruncated 是否撞到了 {@link #MAX_ROWS}（说明"这不是全部"）
	 * @param cellsTruncated 是否至少有一个单元格被截断
	 * @param elapsedMs 耗时（毫秒）
	 */
	record ReadResult(List<String> columns, int omittedColumns, List<List<String>> rows, boolean rowsTruncated,
			boolean cellsTruncated, long elapsedMs) {

		ReadResult {
			columns = List.copyOf(columns);
			rows = List.copyOf(rows);
		}
	}

	/**
	 * 一次写语句的结果。
	 *
	 * @param updateCount 驱动报告的影响行数。<b>0 是一个正常结果</b>
	 *                    （例如 {@code UPDATE} 的条件没匹配到任何行），不是失败；
	 *                    DDL 语句通常报 0
	 * @param elapsedMs 耗时（毫秒）
	 */
	record WriteResult(int updateCount, long elapsedMs) {
	}

	/**
	 * 执行一条读语句。
	 *
	 * @throws SQLException 交给调用方按"工具没能完成这件事"回填给模型（见两把工具的 invoke）
	 */
	ReadResult query(String sql) throws SQLException {
		long startedAt = System.nanoTime();
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			// 【向驱动要 MAX_ROWS + 1 行，而不是 MAX_ROWS —— 这是判"还有更多行"的唯一办法】
			// 实测（H2 2.3.232）：setMaxRows(20) 之后游标**停在 20 行**，再 next() 恒为 false；
			// 也就是说"读满上限"与"结果就这么大"在 API 上长得一模一样。
			// 于是多要一行：第 MAX_ROWS+1 行只用来回答"还有没有"，展示前会被丢掉。
			// 不这么做的话，截断提示一次都不会出现 —— 而那种失败看起来完全正常（就是少给了行）
			statement.setMaxRows(MAX_ROWS + 1);
			statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
			try (ResultSet resultSet = statement.executeQuery(sql)) {
				ResultSetMetaData meta = resultSet.getMetaData();
				int totalColumns = meta.getColumnCount();
				int shownColumns = Math.min(totalColumns, MAX_COLUMNS);
				List<String> columns = new ArrayList<>(shownColumns);
				for (int i = 1; i <= shownColumns; i++) {
					columns.add(meta.getColumnLabel(i));
				}

				List<List<String>> rows = new ArrayList<>();
				boolean cellsTruncated = false;
				int cellCount = 0;
				while (resultSet.next()) {
					List<String> row = new ArrayList<>(shownColumns);
					for (int i = 1; i <= shownColumns; i++) {
						String text = cell(resultSet, i);
						if (text.length() > MAX_CELL_CHARS) {
							text = text.substring(0, MAX_CELL_CHARS) + "…";
							cellsTruncated = true;
						}
						row.add(text);
						cellCount++;
					}
					rows.add(row);
				}
				// 读到 MAX_ROWS+1 行 = 确实还有更多（把多读的那行丢掉）。
				// 判据是"超过上限"，不是"等于上限"：后者会把"刚好 20 行"也标成截断，
				// 而模型会因此白白去加 WHERE 再查一次
				boolean rowsTruncated = rows.size() > MAX_ROWS;
				if (rowsTruncated) {
					rows.remove(rows.size() - 1);
				}
				long elapsedMs = elapsedMs(startedAt);
				log.info("query_sql 完成 列数={}（显示 {}）行数={} 截断={} 单格截断={} 单元格={} 耗时={}ms sql={}",
						totalColumns, shownColumns, rows.size(), rowsTruncated, cellsTruncated, cellCount, elapsedMs,
						oneLine(sql));
				return new ReadResult(columns, totalColumns - shownColumns, rows, rowsTruncated, cellsTruncated,
						elapsedMs);
			}
		}
	}

	/**
	 * 执行一条写语句。
	 *
	 * <p>用 {@link Statement#executeUpdate} 而不是 {@code execute}：后者的返回值是
	 * "第一个结果是不是 ResultSet"，影响行数还得再调一次 {@code getUpdateCount()}，
	 * 而多语句时它只报第一条 —— 多语句由 {@link SqlGuard} 提前拒掉，这里走最直白的出口。
	 */
	WriteResult execute(String sql) throws SQLException {
		long startedAt = System.nanoTime();
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
			int updateCount = statement.executeUpdate(sql);
			long elapsedMs = elapsedMs(startedAt);
			log.info("execute_sql 完成 影响行数={} 耗时={}ms sql={}", updateCount, elapsedMs, oneLine(sql));
			return new WriteResult(updateCount, elapsedMs);
		}
	}

	/**
	 * 把一行渲染成写工具的回填文本。
	 *
	 * <p>写在执行器里而不是工具里：读与写的两个出口都要它，
	 * 而且"执行了几条语句"这件事只有这里最清楚（永远是 1 —— 多语句进不来）。
	 */
	static String renderWrite(WriteResult result) {
		return """
				语句已执行：1 条（这次调用只执行了这一条，且只执行了一次）。
				- 影响行数：%d —— 0 是正常结果（条件没匹配到任何行），DDL 语句通常报 0
				- 耗时：%d ms
				说明：这条语句是在真实数据库上直接执行的，没有事务回滚的余地；
				如果影响行数与你预期的不同，请用 query_sql 核实当前状态，不要在没看清之前重复执行。"""
				.formatted(result.updateCount(), result.elapsedMs());
	}

	/**
	 * 把一个单元格读成文本。
	 *
	 * <p>{@code getString} 对任何类型都可用（数字、时间、CLOB 都能拿到文本形式），
	 * 而 {@code wasNull()} 必须在读完之后<b>立刻</b>判 —— 它就是驱动为"上一次读的是不是 NULL"
	 * 准备的那一个标志。用 {@code "null".equals(text)} 之类的写法会把字面量 {@code 'null'} 判成 NULL，
	 * 而这两件事在数据里完全不同。
	 */
	private static String cell(ResultSet resultSet, int columnIndex) throws SQLException {
		String text = resultSet.getString(columnIndex);
		if (text == null || resultSet.wasNull()) {
			return NULL_MARK;
		}
		// 单元格里的换行会把"一行数据"在文本里撑成好几行，于是表头对不上数据 ——
		// 折叠成空格，让每一行恰好占一行
		return text.replace('\r', ' ').replace('\n', ' ').strip();
	}

	/** 日志里的 SQL：折叠空白并截断（日志不该被一条长 SQL 冲爆，但首关键字必须看得见）。 */
	private static String oneLine(String sql) {
		String flat = sql == null ? "" : sql.replaceAll("\\s+", " ").strip();
		return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	/** 供工具在回填文本里引用同一份上限（避免"说明里的数字"与"真正执行的上限"两处维护）。 */
	static String limitsNote() {
		return "单次最多返回 %d 行、最多显示 %d 列、每个单元格最多 %d 字符，查询超时 %d 秒。"
				.formatted(MAX_ROWS, MAX_COLUMNS, MAX_CELL_CHARS, QUERY_TIMEOUT_SECONDS);
	}

}
