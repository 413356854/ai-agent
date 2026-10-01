package ai.agent.entity.agent.task;

import java.time.Instant;

/**
 * <b>一条 agent 任务</b> —— 谁派的、派给谁、现在到哪一步、这一步在等谁。
 *
 * <h2>★ 三个"人"存的是用户 id，姓名是读出来的</h2>
 * {@code publisherId} / {@code assigneeId} / {@code currentExecutorId} 是 {@code agent_user.id}
 * （库里就是这三列），而 {@code publisher} / {@code assignee} / {@code currentExecutor}
 * 是读侧按 id 关联账号表查出来的<b>姓名</b>，不落库（见 {@code AgentTaskMapper.xml}）。
 * 两者一起带着走是刻意的：界面要显示的永远是姓名，而"当时是谁"只能由 id 回答 ——
 * 只带 id 会让每个调用点各自去查一次账号表，只带姓名则账号改名之后就找不到人。
 *
 * <p>⚠️ 姓名允许为空：账号那一行不在时（手工清过库）关联查询落空，
 * 而那一格显示成「未指定任务人」比"这一行读不出来、整张看板打不开"好得多。
 *
 * <h2>发布人 / 任务人 / 当前执行人：三个字段的分工</h2>
 * <ul>
 *   <li>{@code publisherId} 发布人：指派出任务的人 —— 这一次从开始到结束都是他；</li>
 *   <li>{@code assigneeId} 任务人：交付任务的人 —— 也是开始时的执行人；</li>
 *   <li>{@code currentExecutorId} 当前执行人：**这一条现在流转到谁手里** ——
 *       球在人手上时是他的 id（待确认计划 / 权限不足 / 执行中 / 待验收在任务人手上，
 *       需求确认与验收在发布人手上，见 {@code AgentTaskTransitions#executorFor}），
 *       球在 agent 手上（三个"已确认"）或事情已经结束时为空。
 *       它与前两列是两类东西：前两列是"这件事的人"，它是"此刻的球在谁手上" ——
 *       看板按人筛选用的就是这一列。</li>
 * </ul>
 *
 * <h2>校验放在紧凑构造器里</h2>
 * 与 {@code Account} 同一条做法：非空、长度、步骤下界都在这里一次写完，
 * 于是所有构造点（HTTP 写入口、从库里读回、将来的导入）共用同一份判据。
 *
 * <h2>发布人 / 任务人：写入路径要求两个都是真实账号，读侧只要求发布人 id 非空</h2>
 * "是不是账号表里真实存在的用户"这条判据不在这里 —— 它要读账号表，所以落在服务层的唯一写入口
 * （{@code DefaultAgentTaskService#realUser}）。读侧<b>不</b>要求任务人存在：
 * 库里允许任务人为空的行（列可为 NULL），而"读到一行就抛"的后果是整张看板打不开。
 *
 * @param id 主键；未落库时为 {@code null}
 * @param publisherId 发布人的用户 id（非空；指向 {@code agent_user.id}）
 * @param publisher 发布人的姓名（读侧关联查询得到；账号那一行不在时为空）
 * @param assigneeId 任务人的用户 id（新发布必填；库里可能有早期留下的空行）
 * @param assignee 任务人的姓名（读侧关联查询得到；为空时界面显示「未指定任务人」）
 * @param description 任务描述（非空）
 * @param status 状态（链见 {@link AgentTaskStatus}）
 * @param currentExecutorId 当前执行人的用户 id；球在 agent 手上或任务已结束时为空
 * @param currentExecutor 当前执行人的姓名（读侧关联查询得到）
 * @param currentStep 任务当前步骤，从 1 开始
 * @param agentId 交给哪个 Agent 应用（应用注册表里的 id，字符串）；<b>发布时为空</b>，
 *        由平台在第一次接手这条任务时写入（见 {@code AgentTaskProperties#agentId}）
 * @param result 执行结果；还没执行完时为空
 * @param cancelReason 取消原因；未取消时为空（取消时必填，判据在服务层的唯一写入口）
 * @param incompleteReason 未完成原因；状态不是 {@link AgentTaskStatus#NOT_COMPLETED} 时为空。
 *        与取消原因不同，它<b>选填</b>：验收那一刻"没做成"这件事本身就是要记的东西，
 *        而原因可能一时说不清 —— 逼着人写一句他写不出来的话，结果是那一格被填成"没做完"
 * @param createdAt 落库时间（未落库时为 {@code null}）
 * @param updatedAt 最近一次变更时间（未落库时为 {@code null}）
 */
public record AgentTask(Long id, Long publisherId, String publisher, Long assigneeId, String assignee,
		String description, AgentTaskStatus status, Long currentExecutorId, String currentExecutor, int currentStep,
		String agentId, String result, String cancelReason, String incompleteReason, Instant createdAt,
		Instant updatedAt) {

	/** 与 DDL 的 {@code agent_id VARCHAR(128)} 对齐。 */
	public static final int MAX_AGENT_ID_CHARS = 128;

	/**
	 * 姓名的应用级上限。真正的宽度在账号表那边（{@code agent_user.name VARCHAR(64)}），
	 * 这里给一倍余量只作兜底 —— 姓名是读出来的一格，不该因为对方列宽变了就把整张看板读挂。
	 */
	public static final int MAX_NAME_CHARS = 128;

	/**
	 * 任务描述上限。DDL 里是 TEXT（不设库级上限），这里给一个应用级上限：
	 * 超限**拒绝而不是截断** —— 一段被砍掉尾巴的任务描述看起来完全正常，
	 * 而据此生成的需求确认问的不是原来那件事（与本项目其它所有截断同一条规则）。
	 */
	public static final int MAX_DESCRIPTION_CHARS = 2000;

	/** 与 DDL 的 {@code cancel_reason VARCHAR(512)} 对齐。 */
	public static final int MAX_REASON_CHARS = 512;

	/**
	 * 未完成原因的上限（与 {@link #MAX_REASON_CHARS} 同源，DDL 里也是 {@code VARCHAR(512)}）。
	 *
	 * <p>超限<b>拒绝而不是截断</b>：一段被砍掉尾巴的说明读起来完全正常，
	 * 而它说的可能正是"差在哪"那一句。
	 */
	public static final int MAX_INCOMPLETE_REASON_CHARS = 512;

	public AgentTask {
		publisherId = requiredId(publisherId, "发布人");
		publisher = blankToNull(publisher);
		assignee = blankToNull(assignee);
		description = required(description, "任务描述", MAX_DESCRIPTION_CHARS);
		status = status == null ? AgentTaskStatus.NEW : status;
		currentExecutor = blankToNull(currentExecutor);
		if (currentStep < 1) {
			throw new IllegalArgumentException("任务步骤从 1 开始，实际是 " + currentStep
					+ "（新发布也记 1 —— 它表示「停在第 1 步还没开始」，界面上没有第 0 步这个东西）");
		}
		agentId = blankToNull(agentId, "Agent 应用 id", MAX_AGENT_ID_CHARS);
		result = blankToNull(result, "执行结果", MAX_DESCRIPTION_CHARS);
		cancelReason = blankToNull(cancelReason, "取消原因", MAX_REASON_CHARS);
		// 空 = 没写原因（选填），不报错；写了的就按上限拦（与取消原因同一条"拒绝而不截断"）
		incompleteReason = blankToNull(incompleteReason, "未完成原因", MAX_INCOMPLETE_REASON_CHARS);
	}

	/**
	 * 一条刚发布、还没落库的任务。
	 *
	 * <p>它固定了三个字段，于是"新任务长什么样"只有这一处定义：
	 * 状态 {@link AgentTaskStatus#NEW}、步骤 1、当前执行人是任务人（球在他手上，
	 * 由他发起第一条需求确认）；{@code agentId} 也在这里定成 {@code null} ——
	 * 发布人不填它，值由平台在第一次接手这条任务时写入。
	 * 调用方没有机会把它们拼错，也没有机会把 agentId 塞进来。
	 *
	 * <p>发布人与任务人<b>都要给全</b>（id 与姓名，空字符串在这里就被拒）：
	 * "这两个用户在账号表里存不存在"是服务层的事（它才拿得到账号表），
	 * 而"有没有填"是这一层就能拦下的。
	 */
	public static AgentTask draft(Long publisherId, String publisher, Long assigneeId, String assignee,
			String description) {
		return new AgentTask(null, publisherId, publisher, requiredId(assigneeId, "任务人"),
				required(assignee, "任务人姓名", MAX_NAME_CHARS), description, AgentTaskStatus.NEW, assigneeId,
				assignee, 1, null, null, null, null, null, null);
	}

	/**
	 * 一次改写之后的描述：判据与 {@link #draft} 里那一条<b>是同一份</b>
	 * （非空、去前后空格、长度上限 {@link #MAX_DESCRIPTION_CHARS}）。
	 *
	 * <p>「权限不足」那一格允许用户改了描述再重走一遍（见 {@code AgentTaskService#revise}）：
	 * 这一份文本要先过这里，而不是直接写进库 —— 一条空描述或超长描述在库里看起来完全正常，
	 * 而下一步会拿它去判需求、拆任务点。
	 */
	public static String revisedDescription(String description) {
		return required(description, "任务描述", MAX_DESCRIPTION_CHARS);
	}

	/** agent 执行时"当前执行人"的写法（形如 {@code agent#my-app}；没配 agent id 时是 {@code agent}）。 */
	public static String agentRef(String agentId) {
		return agentId == null ? "agent" : "agent#" + agentId;
	}

	/**
	 * 「当前执行人」那一格该显示的字。它是<b>后端给的文案</b>，与 {@code statusLabel} / {@code answerLabel}
	 * 同一条规矩：前端不自己拼一份，两处各拼一次迟早不一致。
	 *
	 * <p>三种落点：
	 * <ul>
	 *   <li>那一列有值（球在某个账号手上）→ 关联查询出来的姓名（账号那一行不在时退回 id）；</li>
	 *   <li>那一列为空而任务没结束 → 球在 agent 手上，给 {@link #agentRef()}；</li>
	 *   <li>任务已结束、或这列空而 agent 也还没接手过 → 空串（界面显示「（无）」）。</li>
	 * </ul>
	 *
	 * <p>第二条成立的前提是：非终态里"这一列为空"只可能是球在 agent 手上那三格 ——
	 * 有人接手的那几格都写着一个人的 id（{@code AgentTaskTransitions#executorFor}），
	 * 而发布人 id 非空（列 NOT NULL）、任务人在发布时必填。
	 */
	public String executorLabel() {
		if (currentExecutorId != null) {
			return currentExecutor == null ? "用户#" + currentExecutorId : currentExecutor;
		}
		if (status.terminal() || agentId == null) {
			return "";
		}
		return agentRef(agentId);
	}

	/** 一行说明（日志用）。 */
	public String describe() {
		return "#" + (id == null ? "(未落库)" : id) + " " + status.label() + " · 步骤 " + currentStep
				+ " · " + who(publisherId, publisher, "发布人") + " → " + who(assigneeId, assignee, "未指定任务人")
				+ " · 当前执行人 " + (executorLabel().isEmpty() ? "(无)" : executorLabel());
	}

	/** 日志里一个人的写法：有姓名就用姓名，账号查不到（姓名落空）时退回 id。 */
	private static String who(Long id, String name, String whenMissing) {
		if (id == null) {
			return "（" + whenMissing + "）";
		}
		return name == null ? "用户#" + id : name;
	}

	private static Long requiredId(Long value, String what) {
		if (value == null) {
			throw new IllegalArgumentException(what + "不能为空（任务记录里存的是用户 id）");
		}
		return value;
	}

	private static String required(String value, String what, int max) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(what + "不能为空");
		}
		return fit(value.strip(), what, max);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static String blankToNull(String value, String what, int max) {
		return value == null || value.isBlank() ? null : fit(value.strip(), what, max);
	}

	private static String fit(String value, String what, int max) {
		if (value.length() > max) {
			throw new IllegalArgumentException("%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
