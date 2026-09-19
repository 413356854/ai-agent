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
import java.util.Optional;

/**
 * 工具 ⑥：<b>对应用自己的数据库执行一条写语句</b>（INSERT / UPDATE / DELETE / 建表 / 改表，不限定语法）。
 *
 * <h2>它是本项目里第二把"写"工具，也是第一把"影响面由 SQL 决定"的写工具</h2>
 * {@code propose_review} 的副作用是固定的一件事（建一条工单），而本工具的副作用
 * <b>由模型生成的 SQL 决定</b>：可能只是插一行，也可能把一张表删掉。
 * 这个区别决定了风险声明的写法：{@link ToolHints#writeRequiringApproval()}
 * （非只读 + 非幂等 → 必然触发人工确认）在这里不是"顺手填的默认值"，而是
 * <b>这把工具唯一的闸门</b> —— 因为需求明确是"不限定 SQL"，所以不存在
 * "靠禁止某些语句来兜底"这条路。
 *
 * <h2>为什么执行前必须先拦掉"多语句"，这是它最要紧的一处</h2>
 * 实测（H2 2.3.232）：{@code execute("INSERT …; INSERT …")} 的返回里
 * {@code getUpdateCount()=1}，而<b>两条都执行了</b>。对一把写工具来说这意味着两件事同时发生：
 * ① 回填给模型的"影响行数"是错的，而它具体、像真的，模型会据此认为改了一行；
 * ② 人批的是**一条**语句，实际执行的是若干条 —— 审批与审计的粒度当场失效。
 * 所以多语句在 {@link SqlGuard} 里被拒，而且<b>必须在执行之前拒</b>
 * （事后发现就只能报告"批一条、跑了五条"）。
 *
 * <h2>为什么这里允许 DROP / TRUNCATE，而不做"危险语句黑名单"</h2>
 * 需求是"操作数据库，不限定 SQL"。真正的保护不是猜测哪些语句危险，而是
 * <b>让每一次执行都要有人点头</b>：审批页面上原样显示这份参数（SQL + purpose + expectedEffect），
 * 人看到的是"真正会执行的那份"。黑名单只会制造一种假安全感 ——
 * 它拦得住 {@code DROP}，拦不住 {@code DELETE FROM t}。
 *
 * <p>⚠️ 另一件必须写清的事：默认配置下这个库是<b>内存库</b>
 * （{@code ai.agent.store.url=jdbc:h2:mem:agent}），所以"删掉一张表"重启即恢复；
 * 但如果把它换成了文件库或真 MySQL，同样的批准就是**不可逆**的。
 * 这条差异写在类注释里而不是靠读者自己推 —— 它决定了人在点"批准"前该多想一秒还是一分钟。
 */
@Component
public class ExecuteSqlTool implements AgentTool<ExecuteSqlTool.Params> {

	private static final Logger log = LoggerFactory.getLogger(ExecuteSqlTool.class);

	private final JdbcSqlExecutor executor;

	public ExecuteSqlTool(JdbcSqlExecutor executor) {
		this.executor = executor;
	}

	@Override
	public String name() {
		return "execute_sql";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"对应用自己的数据库执行一条**写**语句（INSERT / UPDATE / DELETE / CREATE / DROP / ALTER 等）。"
						+ "⚠️ 这是写操作：会真的改动数据库，**必须经过人工确认之后才会执行**。",
				"当用户明确要求改数据或改结构时使用：建表、插入测试数据、修正某条记录、加索引、"
						+ "按业务规则批量更新等。用户往往只会说一句「帮我建张表存…」，"
						+ "所以你要自己把它翻译成完整的 SQL，并在 purpose 里写清这次改动**要达成什么**"
						+ "（人就是靠它和 SQL 决定批不批的）。",
				"⚠️ **读数据不要用它**（读用 query_sql，那条路不需要人确认，快得多）。"
						+ "也不要用它做「先建表再插数据」这类多步操作：**一次调用只执行一条语句**，"
						+ "请分成多次调用，并让每一次的目的都能被单独看懂。"
						+ "如果用户只是想知道数据长什么样，用 query_sql。",
				"语句执行成功时返回影响行数（0 是正常结果：条件没匹配到任何行；建表这类 DDL 通常报 0）与耗时。"
						+ "⚠️ 参数**先经过人工确认**：被批准后才会执行，被拒绝或超时则**完全没有执行**。",
				"如果这次调用**没有通过人工确认**，你会收到一条明确的拒绝说明 —— "
						+ "那种情况下**不要重新提交同样的调用**（它会被再次拦住，只是再骚扰一次审批人），"
						+ "请把情况用文字告诉用户。如果 SQL 执行报错（违反约束、语法错），"
						+ "回填里会带数据库的原始错误，改对再提 —— 但注意改参数意味着**要重新审批**。",
				ToolHints.writeRequiringApproval());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		String sql = parameters.sql().strip();

		// ---- 边界：一次只执行一条语句（必须在执行之前拦，见类注释） ----
		Optional<String> multiple = SqlGuard.rejectMultipleStatements(sql);
		if (multiple.isPresent()) {
			log.warn("execute_sql 拒绝了多语句（这次调用没有执行任何语句）：{}", oneLine(sql));
			return new AgentToolResult.Failure("multiple_statements", multiple.get(), false);
		}

		// ---- 执行：走到这里说明参数已通过校验、并且已经有人批准过这一份参数 ----
		try {
			JdbcSqlExecutor.WriteResult result = executor.execute(sql);
			log.info("execute_sql purpose=\"{}\" expectedEffect=\"{}\" 影响行数={}", parameters.purpose(),
					parameters.expectedEffect(), result.updateCount());
			return AgentToolResult.ok(JdbcSqlExecutor.renderWrite(result));
		}
		catch (SQLException e) {
			// 执行失败**不改变"审批流程走完了"这个事实**：人批过，只是语句本身没成。
			// 所以这里返回 Failure 而不是抛异常 —— 前者会变成一条可读的 observation，
			// 后者会被执行器记成 EXECUTION_ERROR 并算进熔断失败率（那是"工具坏了"的意思，
			// 而这里坏的是模型写出来的那条 SQL）。
			log.warn("execute_sql 执行失败 SQLState={} code={} sql={}", e.getSQLState(), e.getErrorCode(),
					oneLine(sql));
			return new AgentToolResult.Failure("sql_error",
					("这条语句没有执行成功：%s（SQLState %s，错误码 %d）。"
							+ "参数本身是合法的，所以**原样重试不会有不同结果**；"
							+ "而且改了 SQL 就意味着要**重新经过人工确认**。"
							+ "请先用 query_sql 查 INFORMATION_SCHEMA 核对表名、列名与约束，"
							+ "把语句改对之后重新提出 —— 或者把情况直接告诉用户。")
							.formatted(rootMessage(e), e.getSQLState(), e.getErrorCode()),
					false);
		}
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
	 * <h2>为什么 {@code purpose} 与 {@code expectedEffect} 都是"给人看的字段"</h2>
	 * 审批页面上原样显示的是这份参数的 JSON，而一条 SQL 在 JSON 里是转义过的单行文本 ——
	 * 人可以读它，但很难一眼看懂"这次到底想干什么"。这两个字段补的正是这一层：
	 * <ul>
	 *   <li>{@code purpose}（必填）：这次改动要达成什么。<b>它是模型意图的唯一落点</b> ——
	 *       运行记录里只有 SQL 的话，事后回答"它为什么改这个"就只能靠猜；</li>
	 *   <li>{@code expectedEffect}（可空）：预期影响多少行／什么变化。它让人在点"批准"前
	 *       能拿预期与语句对一眼 —— 而"预期 1 行、语句没有 WHERE"这种不匹配，
	 *       正是审批真正该拦下的东西。</li>
	 * </ul>
	 *
	 * <p>两者都有长度上限：它们进的是审批记录与回填文本，而"人批准的那份参数"
	 * 不该被一段超长说明顶满（{@code JdbcToolApprovalStore} 对参数整体也有 4000 字符上限）。
	 */
	public record Params(
			@NotBlank(message = "sql 不能为空")
			@Size(max = 4000, message = "SQL 不能超过 4000 字符；请拆成更小的语句，一次调用只执行一条")
			String sql,

			@NotBlank(message = "purpose 不能为空：用一句话说明这次改动要达成什么（人要靠它决定批不批）")
			@Size(max = 200, message = "purpose 是一句话，不要超过 200 字符")
			String purpose,

			@Size(max = 200, message = "expectedEffect 是一句话，不要超过 200 字符")
			String expectedEffect) {
	}

}
