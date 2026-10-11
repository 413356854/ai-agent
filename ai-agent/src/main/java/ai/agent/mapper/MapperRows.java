package ai.agent.mapper;

import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.AccountStatus;
import ai.agent.entity.agent.permission.PermissionAccess;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;
import ai.agent.entity.agent.skill.AgentSkill;
import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.AgentTaskStatus;
import ai.agent.entity.agent.task.ConfirmOptions;
import ai.agent.entity.agent.task.ConfirmType;
import ai.agent.entity.alert.AlertEvent;
import ai.agent.entity.chat.ConversationSummary;
import ai.agent.entity.prompt.PromptVersion;
import ai.agent.entity.tool.RetrievalTrace;
import ai.agent.service.agent.AgentFailureCause;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.store.RunStatus;
import ai.agent.service.alert.AlertRule;
import ai.agent.service.canary.CanaryRunBucket;
import ai.agent.service.canary.CanaryStore;
import ai.agent.service.cost.CostQuery;
import ai.agent.service.prompt.PromptKind;

import java.time.Instant;
import java.time.LocalDate;

/**
 * <b>mapper 的返回类型</b>：数据库里的一行，数值字段一律是<b>包装类型</b>。
 *
 * <h2>★ 为什么不能直接映射到 domain 的 record（实测结论，不是风格偏好）</h2>
 * 实测（Spring Boot 4.1.1 + MyBatis 3.5.19：两套最小工程 + 本项目的验证器，共 41 项断言）：
 * MyBatis 的<b>构造器映射</b>在实例化时用的是"各列读出来的值的运行时类型"，
 * 而数值列读出来是<b>包装类型</b>（{@code Long} / {@code Integer}）。
 * 于是只要目标 record 的构造器参数是<b>基本类型</b>，匹配必然失败：
 * <pre>
 * ReflectionException: Error instantiating class …GroupTotals with invalid types
 *   (String,Long,Long,…)  Cause: NoSuchMethodException: GroupTotals.&lt;init&gt;(String,Long,Long,…)
 * </pre>
 * 而 {@code javaType="long"} 与 {@code javaType="java.lang.Long"} <b>两种写法结果完全一样</b> ——
 * 那个属性管不到实例化时传进去的类型。字符串与时间类型没这个问题（它们本来就是引用类型）。
 *
 * <p>所以有两条路：
 * <ol>
 *   <li>把 domain 的 record 改成包装类型 —— <b>不选</b>：那是让持久层的框架约束渗透进领域类型，
 *       而这些类型（{@code CostQuery.GroupTotals}、{@code AgentRunRecord}…）被服务层、
 *       接口层与看板共用；</li>
 *   <li><b>在本包里放一层"数据库行"类型（就是下面这些），转换写在 Java 里</b> —— 选它。
 *       框架的约束被关在 mapper 层，转换点只有一处、看得见、能被断言。
 *       代价是每个类型多一个 DTO 与一句转换。</li>
 * </ol>
 *
 * <h2>两条转换纪律（下面每个 {@code toDomain} 都遵守）</h2>
 * <ol>
 *   <li><b>计数列 null → 0</b>：聚合列在 SQL 里都有 {@code COALESCE}，
 *       唯一的 null 来源是"老数据 + 加列"，按 0 记是保守方向；</li>
 *   <li><b>"可以没有"的事实保持 null</b>：{@code firstAt} / {@code decidedAt} / {@code resolvedAt}
 *       这些 null 是有含义的（"还没有"），**不编默认值** —— 编一个会让"从来没发生过"
 *       与"发生过但没记下来"变成同一件事。</li>
 * </ol>
 */
public final class MapperRows {

	private MapperRows() {
	}

	// ==================================================================
	//  agent_run / agent_step / agent_tool_invocation / retrieval_*
	// ==================================================================

	/** {@code agent_run} 的一行。 */
	public record AgentRunRow(String id, String question, RunStatus status, AgentStopReason stopReason,
			String finalAnswer, Integer totalTokens, Integer toolCallCount, Long elapsedMs, String node, String error,
			Instant createdAt, Instant updatedAt, AgentFailureCause failureCause, String intent,
			String conversationId, String tenantId, String workspaceDir,
			ai.agent.entity.agent.AgentPermissionLevel permissionLevel) {

		public AgentRunRecord toDomain() {
			return new AgentRunRecord(id, question, status, stopReason, finalAnswer, nz(totalTokens),
					nz(toolCallCount), nz(elapsedMs), node, error, createdAt, updatedAt, failureCause, intent,
					conversationId, tenantId, workspaceDir, permissionLevel);
		}
	}

	/** {@code agent_step} 的一行（检查点）。 */
	public record StepCheckpointRow(String runId, Integer stepIndex, String transcript, String thoughtText,
			Integer promptTokens, Integer completionTokens, Long elapsedMs, Integer cacheReadTokens,
			String modelName, String promptFingerprint, String canaryVariant) {

		/**
		 * 转成 domain 记录。
		 *
		 * <p>四个计数列在 DDL 里都是 {@code NOT NULL DEFAULT 0}，走 {@code nz} 只是防线；
		 * 三个"结果列"为 null 时交给 {@code StepCheckpoint} 的紧凑构造器归一成空串 ——
		 * <b>"空串与 NULL 是两件事"这条规则只在那里定义一次</b>。
		 */
		public AgentRunRecord.StepCheckpoint toDomain() {
			return new AgentRunRecord.StepCheckpoint(runId, nz(stepIndex), transcript, thoughtText,
					nz(promptTokens), nz(completionTokens), nz(elapsedMs), nz(cacheReadTokens), modelName,
					promptFingerprint, canaryVariant);
		}
	}

	/** {@code agent_tool_invocation} 的一行。 */
	public record ToolCallRow(String runId, Integer stepIndex, Integer callIndex, String callId, String toolName,
			String arguments, String stage, String feedback, Long elapsedMs) {

		public AgentRunRecord.ToolCallRecord toDomain() {
			return new AgentRunRecord.ToolCallRecord(runId, nz(stepIndex), nz(callIndex), callId, toolName,
					arguments, stage, feedback, nz(elapsedMs));
		}
	}

	/**
	 * 一次检索命中（已经带上 JOIN 出来的工具名）。
	 *
	 * <p>它<b>不是</b> {@code RetrievalTrace.Hit}：那一层的 {@code rank / tokenCount / score} 是基本类型，
	 * 而这里这几列可空。转换放在 {@link #toHit()} 里，缺计数按 0 兜底 ——
	 * 0 个 token / 0 分在报告里是**看得出来**的（不会伪装成正常值），
	 * 而抛异常会让整条 trace 读不出来，代价更大。
	 */
	public record RetrievalHitRow(Integer stepIndex, Integer callIndex, String toolName, Integer rank, String chunkId,
			String documentId, String breadcrumb, Integer tokenCount, Double score, String excerpt) {

		public RetrievalTrace.Hit toHit() {
			return new RetrievalTrace.Hit(nz(rank), chunkId, documentId, breadcrumb, nz(tokenCount),
					score == null ? 0d : score, excerpt);
		}
	}

	/** 一次检索调用（{@code retrieval_call} 的一行）。 */
	public record RetrievalCallRow(Integer stepIndex, Integer callIndex, String toolName, String query, Integer topK,
			Integer indexSize, Long elapsedMs) {
	}

	/** 检索侧的两个告警信号（{@code retrieval_call} 上的两个读数）。 */
	public record RetrievalSignalRow(Integer hitCount, Integer indexSize) {
	}

	// ==================================================================
	//  成本看板
	// ==================================================================

	/** {@code CostQuery.GroupTotals} 的数据库行（按天/业务/租户/模型共用；列名与手写时代一致）。 */
	public record GroupTotalsRow(String key, Long runCount, Long finishedCount, Long stoppedCount, Long failedCount,
			Long promptTokens, Long cachedTokens, Long completionTokens, Long toolCalls, Long toolFailures,
			Long toolElapsed, Long runElapsed, Instant firstAt) {

		public CostQuery.GroupTotals toDomain() {
			return new CostQuery.GroupTotals(key, nz(runCount), nz(finishedCount), nz(stoppedCount), nz(failedCount),
					nz(promptTokens), nz(cachedTokens), nz(completionTokens), nz(toolCalls), nz(toolFailures),
					nz(toolElapsed), nz(runElapsed), firstAt);
		}
	}

	/**
	 * {@code CostQuery.DayTotals} 的数据库行 —— <b>扁平的</b>：一天 + 那一天的全部聚合列。
	 *
	 * <h2>为什么不把 GroupTotalsRow 嵌进来（实测教训）</h2>
	 * 试过用 {@code <arg column="totals" resultMap="…groupTotals"/>} 做嵌套构造器映射，
	 * 结果 MyBatis 报
	 * {@code NoSuchMethodException: GroupTotalsRow.<init>(Long,Long,…,Instant)}
	 * ——<b>参数个数都不对</b>（少了那个 String 分组键）。原因是构造器映射只把
	 * "在结果集里出现过的列"喂给构造器，而嵌套映射里那些列并没有成为外层结果集的列。
	 * 这类失败的报错指向"类型不匹配"，真因却是"少了一列"，很难从消息里看出来。
	 *
	 * <p>所以这里改成扁平：一条 SELECT 取出所有列，组装在 {@link #toDomain()} 里做。
	 * 多几行代码，换掉一整类看不懂的报错。
	 */
	public record DayTotalsRow(LocalDate day, String key, Long runCount, Long finishedCount, Long stoppedCount,
			Long failedCount, Long promptTokens, Long cachedTokens, Long completionTokens, Long toolCalls,
			Long toolFailures, Long toolElapsed, Long runElapsed, Instant firstAt) {

		public CostQuery.DayTotals toDomain() {
			return new CostQuery.DayTotals(day,
					new CostQuery.GroupTotals(key, nz(runCount), nz(finishedCount), nz(stoppedCount), nz(failedCount),
							nz(promptTokens), nz(cachedTokens), nz(completionTokens), nz(toolCalls),
							nz(toolFailures), nz(toolElapsed), nz(runElapsed), firstAt));
		}
	}

	/** {@code CostQuery.ModelDayRow} 的数据库行。 */
	public record ModelDayRow(LocalDate day, String model, Long promptTokens, Long cachedTokens,
			Long completionTokens) {

		public CostQuery.ModelDayRow toDomain() {
			return new CostQuery.ModelDayRow(day, model, nz(promptTokens), nz(cachedTokens), nz(completionTokens));
		}
	}

	/** {@code CostQuery.ToolTotals} 的数据库行。 */
	public record ToolTotalsRow(String toolName, Long calls, Long failures, Long unknownTool, Long validationErrors,
			Long parseErrors, Long rejected, Long circuitOpen, Long elapsedMs, Long runs) {

		public CostQuery.ToolTotals toDomain() {
			return new CostQuery.ToolTotals(toolName, nz(calls), nz(failures), nz(unknownTool), nz(validationErrors),
					nz(parseErrors), nz(rejected), nz(circuitOpen), nz(elapsedMs), nz(runs));
		}
	}

	/** {@code CostQuery.Reconciliation} 的数据库行。 */
	public record ReconciliationRow(Long runTokens, Long stepTokens, Long runsWithoutSteps, Long runs,
			Long hitRuns, Long promptTokens, Long cachedTokens) {

		public CostQuery.Reconciliation toDomain() {
			return new CostQuery.Reconciliation(nz(runTokens), nz(stepTokens), nz(runsWithoutSteps), nz(runs),
					nz(hitRuns), nz(promptTokens), nz(cachedTokens));
		}
	}

	// ==================================================================
	//  告警 / 灰度 / 提示词
	// ==================================================================

	/** {@code alert_event} 的一行。 */
	public record AlertEventRow(String fingerprint, String ruleCode, String subject, AlertRule.Severity severity,
			AlertRule.AlertPhase phase, AlertEvent.State state, Instant firstSeenAt, Instant lastSeenAt,
			Integer occurrences, Instant resolvedAt, Instant acknowledgedAt, String acknowledgedBy, String observed,
			String threshold, String why, String action, String windowLabel) {

		public AlertEvent toDomain() {
			return new AlertEvent(fingerprint, ruleCode, subject, severity, phase, state, firstSeenAt, lastSeenAt,
					nz(occurrences), resolvedAt, acknowledgedAt, acknowledgedBy, observed, threshold, why, action,
					windowLabel);
		}
	}

	/** {@code prompt_canary} 的一行。 */
	public record CanaryRow(PromptKind kind, Integer candidateVersion, Integer weightPercent, String startedBy,
			Instant startedAt, String note, Instant endedAt, String endedBy, String endReason) {

		public CanaryStore.Row toDomain() {
			return new CanaryStore.Row(kind, nz(candidateVersion), nz(weightPercent), startedBy, startedAt, note,
					endedAt, endedBy, endReason);
		}
	}

	/** {@code prompt_version} 的一行。 */
	public record PromptVersionRow(PromptKind kind, Integer version, String content, String hash, Integer charCount,
			Integer tokenEstimate, String note, String author, Integer forkedFrom, Instant createdAt) {

		public PromptVersion toDomain() {
			return new PromptVersion(kind, nz(version), content, hash, nz(charCount), nz(tokenEstimate), note, author,
					forkedFrom, createdAt);
		}
	}

	/**
	 * 一条"按（提示词指纹, 灰度变体）聚合出来的运行数"。
	 *
	 * <p>带指纹原文而不是解析好的版本号：解析规则（{@code v<数字>:} 前缀、解析不出来记 0 而不是丢行）
	 * 属于代码、不属于 SQL，放在 SQL 里会让那条规矩散进字符串。
	 */
	public record CanaryRunBucketRow(String fingerprint, String variant, Integer runs) {

		public CanaryRunBucket toDomain() {
			return new CanaryRunBucket(fingerprint, variant, nz(runs));
		}
	}

	// ==================================================================
	//  权限管理（agent_user / agent_role / agent_user_role / agent_role_tool / agent_role_dir）
	// ==================================================================

	/** {@code agent_user} 的一行。 */
	public record AccountRow(Long id, String name, String passwordHash, String displayName, String status,
			Instant createdAt, Instant updatedAt) {

		/**
		 * {@code status} 在这里才解析成枚举（而不是让 MyBatis 直接映射枚举）：
		 * 宽松解析的规则（认不出落 DISABLED）与 {@code AgentPermissionLevel} 的处理保持一致 ——
		 * 它是一条业务判断，放在 {@link ai.agent.entity.agent.permission.AccountStatus#parse} 里只有一处。
		 */
		public Account toDomain() {
			return new Account(id, name, passwordHash, displayName, AccountStatus.parse(status));
		}
	}

	/** {@code agent_role} 的一行。 */
	public record RoleRow(Long id, String name, String description, String access, Instant createdAt,
			Instant updatedAt) {

		public Role toDomain() {
			return new Role(id, name, description, PermissionAccess.parse(access));
		}
	}

	/** {@code agent_role_tool} 的一行。 */
	public record RoleToolRow(Long roleId, String toolName, String note) {

		public RoleTool toDomain() {
			return new RoleTool(roleId, toolName, note);
		}
	}

	/** {@code agent_role_dir} 的一行。 */
	public record RoleDirectoryRow(Long roleId, String dirPrefix, String note) {

		public RoleDirectory toDomain() {
			return new RoleDirectory(roleId, dirPrefix, note);
		}
	}

	/**
	 * 账号 ↔ 角色（JOIN 出来的名字）。
	 *
	 * <p>为什么不只返回 {@code role_id} 再去查一次角色：权限页要显示"他有哪些角色"，
	 * 而那些名字是唯一需要的东西 —— 按 id 逐个回查是典型的 N+1。
	 */
	public record AccountRoleRow(Long userId, Long roleId, String roleName) {
	}

	// ==================================================================
	//  agent 任务（agent_task / agent_task_confirm）
	// ==================================================================

	/**
	 * {@code agent_task} 的一行。
	 *
	 * <p>{@code status} 保持字符串：宽松解析（认不出落 NEW）在
	 * {@link AgentTaskStatus#parse} 里，理由见那个方法。
	 * {@code cancel_reason} / {@code incomplete_reason} 与 {@code result} 为 null 是**正常状态**
	 * （未取消 / 未完成原因没写 / 未执行完），不编默认值 ——
	 * 编一个会让"还没有结果"与"结果就是那个默认值"变成同一件事。
	 *
	 * <p><b>⚠️ 这是构造器映射</b>：这里的参数顺序必须与 {@code AgentTaskMapper.xml} 里
	 * resultMap 的 {@code <arg>} 顺序、以及 {@link AgentTask} 的构造参数顺序逐字对齐 ——
	 * 错位不报错，只表现为某几列静默串到别人身上（两个 {@code String} 之间尤其看不出来）。
	 *
	 * <p>三个姓名（{@code publisher_name} / {@code assignee_name} / {@code current_executor_name}）
	 * 是 SQL 关联 {@code agent_user} 查出来的，表里没有它们 —— 所以它们可能为 null
	 * （账号那一行不在时），而 id 才是库里真正存的那一份（见 {@link AgentTask} 的类注释）。
	 */
	public record AgentTaskRow(Long id, Long publisherId, String publisherName, Long assigneeId, String assigneeName,
			String description, String status, Long currentExecutorId, String currentExecutorName, Integer currentStep,
			String agentId, String result, String cancelReason, String incompleteReason, Instant createdAt,
			Instant updatedAt) {

		public AgentTask toDomain() {
			return new AgentTask(id, publisherId, publisherName, assigneeId, assigneeName, description,
					AgentTaskStatus.parse(status), currentExecutorId, currentExecutorName, nz(currentStep), agentId,
					result, cancelReason, incompleteReason, createdAt, updatedAt);
		}
	}

	/**
	 * {@code agent_task_confirm} 的一行。
	 *
	 * <p>{@code option_set} 是 JSON 数组的原文、{@code confirm_type} 是库里的类型名 ——
	 * 两者的解析都放在 {@link #toDomain()} 里，解码规则各只有一处
	 * （{@link ConfirmOptions#decode} / {@link ConfirmType#parse}）。
	 * {@code answer} 为 null 是**待答复**这个状态本身（不是"没记下来"），见 {@link AgentTaskConfirm}。
	 */
	public record AgentTaskConfirmRow(Long id, Long taskId, Integer stepIndex, String confirmType,
			String description, String optionSet, String answer, Instant createdAt, Instant updatedAt) {

		public AgentTaskConfirm toDomain() {
			return new AgentTaskConfirm(id, taskId, nz(stepIndex), ConfirmType.parse(confirmType), description,
					ConfirmOptions.decode(optionSet), answer, createdAt, updatedAt);
		}
	}

	// ==================================================================
	//  Agent 技能（agent_skill）
	// ==================================================================

	/**
	 * {@code agent_skill} 的一行。
	 *
	 * <p>两个姓名（{@code owner_name} / {@code last_modified_name}）是 SQL 关联
	 * {@code agent_user} 查出来的，表里没有它们 —— 所以它们可能为 null
	 * （账号那一行不在时），而两个 id 才是库里真正存的那一份（见 {@link AgentSkill}）。
	 *
	 * <p>{@code description} / {@code content} / {@code last_modified_by} 为 null 是**正常状态**
	 * （说明或正文留空、更早的数据没记下修改人），不编默认值 —— 编一个会让"没有写"
	 * 与"写的就是那个默认值"变成同一件事。
	 */
	public record AgentSkillRow(Long id, String folder, String name, String description, String content, Long ownerId,
			String ownerName, Long lastModifiedBy, String lastModifiedName, Instant createdAt, Instant updatedAt) {

		public AgentSkill toDomain() {
			return new AgentSkill(id, folder, name, description, content, ownerId, ownerName, lastModifiedBy,
					lastModifiedName, createdAt, updatedAt);
		}
	}

	// ==================================================================
	//  对话历史压缩（chat_conversation_summary）
	// ==================================================================

	/**
	 * {@code chat_conversation_summary} 的一行。
	 *
	 * <p>{@code provider} 为 null 是**正常状态**（当时取不到模型名），不编默认值 ——
	 * 与成本归因同一条规矩：编一个模型名会让"不知道是哪个模型写的"变成
	 * "就是那个模型写的"，而两者在事后排查时方向完全相反。
	 */
	public record ChatSummaryRow(String conversationId, Integer coveredTurns, String summary, Integer turnsCompacted,
			String provider) {

		public ConversationSummary toDomain() {
			return new ConversationSummary(conversationId, nz(coveredTurns), summary, nz(turnsCompacted), provider);
		}
	}

	// ==================================================================

	private static long nz(Long v) {
		return v == null ? 0L : v;
	}

	private static int nz(Integer v) {
		return v == null ? 0 : v;
	}

}
