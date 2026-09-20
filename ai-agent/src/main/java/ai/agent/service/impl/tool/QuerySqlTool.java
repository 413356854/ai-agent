package ai.agent.service.impl.tool;

import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.AgentToolResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 工具 ⑤：<b>对应用自己的数据库执行一条读语句</b>（写语句一律拒绝，并指向 {@link ExecuteSqlTool}）。
 *
 * <h2>它为什么必须与写工具分开，而不能是一把"带 type 参数的 sql 工具"</h2>
 * 本项目的审批判据来自工具<b>自己声明的风险</b>（{@code ToolHints#requiresApproval()}：
 * 只读 → 免审批；非幂等的写 → 必须审批），而"这次 SQL 是读还是写"只有运行时才知道。
 * 一把工具只能声明一种风险，于是它必然落进两个坏结局之一：
 * <ul>
 *   <li>声明只读 → 写语句会**绕过人工确认**直接就执行了。这是审批制度最不能有的漏洞，
 *       而且它不报错、界面上看起来一切正常；</li>
 *   <li>声明非幂等的写 → <b>每一次 SELECT 都要人点一次确认</b>。这也不是"更安全"，
 *       而是另一类失效：人很快就开始无条件点"通过"，审批退化成仪式。</li>
 * </ul>
 * 拆成两把之后，两个语义各自成立，而且不需要给执行器加任何特判 ——
 * "谁需要审批"这件事仍然只由 {@code ToolHints} 一个地方说了算。
 *
 * <h2>它复用的就是应用自己那一个库，没有第二套连接</h2>
 * 数据源是 {@code AgentStoreConfig#dataSource}（全应用唯一的 {@code DataSource}）。
 * 于是这把工具能看到的是**本应用自己的运行数据**：{@code agent_run} / {@code agent_step} /
 * {@code agent_tool_invocation} / {@code retrieval_hit} / {@code prompt_version} 等。
 * 库里有什么，模型自己用 {@code INFORMATION_SCHEMA} 查得出来 —— 所以四段描述里
 * <b>刻意不写任何表名</b>：写进去的那一刻它就开始随 DDL 漂移，
 * 而漂移之后的表现是"模型拿着一份过时的目录去查一个不存在的表"。
 * 同理，{@link #SCHEMA_QUERY} 里也不写死库名（MySQL 上是 {@code db_ai_agent}，
 * H2 内存库上是 {@code PUBLIC}）。
 *
 * <h2>为什么读也要有上限，而且上限写在 schema 里</h2>
 * 这把工具的返回值就是<b>进模型的上下文</b>，而上下文是花钱的。
 * 所以行数、列数、单元格长度三个上限都放在 {@link JdbcSqlExecutor} 一个地方，
 * 并且行数上限会出现在描述里（模型看得到边界，才不会"要 500 行然后拿到 20 行"）。
 */
@Component
public class QuerySqlTool implements AgentTool<QuerySqlTool.Params> {

	private static final Logger log = LoggerFactory.getLogger(QuerySqlTool.class);

	/**
	 * 给模型的"先查目录"那条语句。
	 *
	 * <p>⚠️ 这一条**不能写死 schema 名**：本应用的库在 MySQL 上是 {@code db_ai_agent}、
	 * 在 H2 内存库上是 {@code PUBLIC}，把其中一个名字写进提示词，换库之后模型就会拿着
	 * 一个不存在的 schema 去查、然后报告"库里没有表"（而库里有）。
	 * {@code DATABASE()} 在两种库上都返回当前库名，所以它写一次、两边都对。
	 */
	private static final String SCHEMA_QUERY =
			"SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
					+ "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";

	private final JdbcSqlExecutor executor;

	public QuerySqlTool(JdbcSqlExecutor executor) {
		this.executor = executor;
	}

	@Override
	public String name() {
		return "query_sql";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"对应用自己的数据库执行一条**读**语句（SELECT 一族），返回结果表格。",
				"当用户的问题需要看数据库里的真实数据时使用：运行记录（哪些任务跑过、花了多少 token、"
						+ "失败原因）、提示词版本、审批待办、检索命中明细等。"
						+ "表名不确定时先查目录：" + SCHEMA_QUERY
						+ "；表结构用 SELECT * FROM INFORMATION_SCHEMA.COLUMNS。"
						+ "典型用法：先查目录或结构，再写一条聚合查询（COUNT / GROUP BY）回答用户。",
				"⚠️ **不要**用它改任何数据（INSERT / UPDATE / DELETE / CREATE / DROP / ALTER 都会被拒绝）——"
						+ "写语句要用 execute_sql，那条路会要求人工确认。"
						+ "也不要用它查项目的技术文档：文档在语料库里，用 search_docs / read_section。",
				"返回一张文本表格：表头、数据行、行数与耗时；NULL 显示成 " + JdbcSqlExecutor.NULL_MARK
						+ "（与空字符串、与字面量 'NULL' 是不同的东西）。"
						+ "上限：" + JdbcSqlExecutor.limitsNote() + " 撞到上限时会**在文本里明确说明**，"
						+ "所以看到「只显示了前 N 行」就要知道那不是全部。",
				"SQL 语法错、表名/列名不存在、查询超时会返回明确的失败说明（含数据库报的原始错误）。"
						+ "这类失败**原样重试不会有不同结果** —— 请先查 INFORMATION_SCHEMA 核对表名与列名，"
						+ "或把查询改简单（例如先去掉 WHERE 里的复杂条件）。",
				ToolHints.readOnly());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		String sql = parameters.sql().strip();

		// ---- 边界 ①：一次只执行一条语句 ----
		Optional<String> multiple = SqlGuard.rejectMultipleStatements(sql);
		if (multiple.isPresent()) {
			log.warn("query_sql 拒绝了多语句：{}", oneLine(sql));
			return new AgentToolResult.Failure("multiple_statements", multiple.get(), false);
		}

		// ---- 边界 ②：只接受读语句（写语句在这里被拒，并指向 execute_sql） ----
		Optional<String> notRead = SqlGuard.rejectNonRead(sql);
		if (notRead.isPresent()) {
			log.warn("query_sql 拒绝了非读语句（首关键字 {}）：{}", SqlGuard.leadingKeyword(sql), oneLine(sql));
			return new AgentToolResult.Failure("write_not_allowed", notRead.get(), false);
		}

		// ---- 执行 ----
		try {
			JdbcSqlExecutor.ReadResult result = executor.query(sql);
			log.debug("query_sql purpose=\"{}\" 行数={}", parameters.purpose(), result.rows().size());
			return new AgentToolResult.Success(render(sql, result), result.rowsTruncated() || result.cellsTruncated(),
					List.of());
		}
		catch (SQLException e) {
			return new AgentToolResult.Failure("sql_error",
					("这条查询没有执行成功：%s（SQLState %s，错误码 %d）。"
							+ "**原样重试不会有不同结果**：请先确认表名与列名 —— 用 "
							+ SCHEMA_QUERY + " 看真实表名，用 "
							+ "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='…' 看列名。"
							+ "如果查询本身没问题，把它改简单一些再试。")
							.formatted(rootMessage(e), e.getSQLState(), e.getErrorCode()),
					false);
		}
	}

	/**
	 * 渲染成给模型看的文本。
	 *
	 * <p>文本里额外写清"撞的是哪一种上限" —— 因为 {@code ToolFeedbackFormatter} 那句通用提示
	 * （"结果因为过长被截断了"）分不出是"少了几行"还是"某几格被砍了尾巴"。
	 * 模型的下一步动作取决于这个区别：前者该加 {@code WHERE} 再查一次，后者该只查需要的那几列。
	 *
	 * <p>耗时在**每个出口**都要给：模型判断"这条要不要换个写法"时会用到它
	 * （毫秒级说明查询本身没问题，几秒说明它太重了）。
	 */
	private static String render(String sql, JdbcSqlExecutor.ReadResult result) {
		StringBuilder sb = new StringBuilder();
		sb.append("查询已完成（读了 %d 行）。\n".formatted(result.rows().size()));
		if (result.columns().isEmpty()) {
			// 极少数驱动/语句会给出 0 列（例如某些 SHOW 变体）。说清是"没有列"，
			// 而不是渲染一个空表让人以为查询没跑
			sb.append("这条语句没有返回任何列。");
			return sb.append(timing(result)).toString();
		}

		sb.append('\n').append(String.join(" | ", result.columns())).append('\n');
		sb.append("-".repeat(Math.min(80, result.columns().size() * 3))).append('\n');
		for (List<String> row : result.rows()) {
			sb.append(String.join(" | ", row)).append('\n');
		}
		if (result.rows().isEmpty()) {
			// "0 行"是一个正常结果（条件没匹配到），不是失败 —— 必须与"查询没跑成"在文本上区分开
			sb.append("（0 行 —— 这是正常的空结果：条件没有匹配到任何数据，不是查询失败。）\n");
		}

		// 【注意 formatted() 的位置】它调在**整个字符串**的末尾，而不是某一个字符串字面量上。
		// 写成 `"… %d …" + "…".formatted(x)` 时，左边那半的 %d 会**原样出现在给模型的文本里** ——
		// 而这个错误不报错、不看输出发现不了（实测就漏过一次，被"文本里必须写清上限"的断言抓住）
		StringBuilder notes = new StringBuilder();
		if (result.rowsTruncated()) {
			notes.append(("- ⚠️ 上面**只显示了前 %d 行**，结果集还有更多：要看完请加 WHERE 收窄条件，"
					+ "或用 COUNT / 聚合先看清有多少。\n").formatted(JdbcSqlExecutor.MAX_ROWS));
		}
		if (result.cellsTruncated()) {
			notes.append(("- ⚠️ 至少有一个单元格超过了 %d 字符，**尾部已被砍掉**："
					+ "需要完整内容请只查那一列（长正文通常没必要整篇取回来）。\n")
					.formatted(JdbcSqlExecutor.MAX_CELL_CHARS));
		}
		if (result.omittedColumns() > 0) {
			notes.append(("- ⚠️ 表列数超过 %d，**有 %d 列没有显示**：请在 SELECT 里明确列出你要的列。\n")
					.formatted(JdbcSqlExecutor.MAX_COLUMNS, result.omittedColumns()));
		}
		if (notes.isEmpty()) {
			sb.append("\n（结果未截断，共 %d 行）".formatted(result.rows().size()));
		}
		else {
			sb.append("\n截断说明：\n").append(notes);
		}
		sb.append(timing(result));
		sb.append("\n执行的 SQL：").append(oneLine(sql));
		return sb.toString();
	}

	/** 统一的耗时脚注（每个出口都带上，别让某个分支少了它）。 */
	private static String timing(JdbcSqlExecutor.ReadResult result) {
		return "\n（耗时 %d ms）".formatted(result.elapsedMs());
	}

	private static String rootMessage(SQLException e) {
		String message = e.getMessage();
		if (message == null || message.isBlank()) {
			return e.getClass().getSimpleName();
		}
		String flat = message.replaceAll("\\s+", " ").strip();
		return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
	}

	private static String oneLine(String sql) {
		String flat = sql == null ? "" : sql.replaceAll("\\s+", " ").strip();
		return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
	}

	/**
	 * 参数。
	 *
	 * <p>{@code purpose} 是必填的，理由与工具描述里那条一样：
	 * 它是**模型的意图**在这里唯一的落点。运行记录里只有 SQL 的话，
	 * 事后看"它为什么查这个"就只能靠猜（而 trace 那一层要回答的正是"它为什么这么做"）。
	 * 它同时也让人在 trace 里一眼看懂这条 SQL 是干什么的。
	 *
	 * <p>长度上限 4000 与 {@code JdbcSqlExecutor.MAX_SQL_CHARS} 是同一个数：
	 * 更长的 SQL 会在审批记录里被截断，而"人批准的那份必须就是真正会执行的那份"。
	 */
	public record Params(
			@NotBlank(message = "sql 不能为空")
			@Size(max = 4000, message = "SQL 不能超过 4000 字符；请拆成更小的查询，或先用 INFORMATION_SCHEMA 看清结构")
			String sql,

			@NotBlank(message = "purpose 不能为空：用一句话说明这次查询要回答什么问题")
			@Size(max = 200, message = "purpose 是一句话，不要超过 200 字符")
			String purpose) {
	}

}
