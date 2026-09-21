package ai.agent.entity.agent.task;

import java.time.Instant;

/**
 * <b>一条 agent 任务</b> —— 谁派的、派给谁、现在到哪一步、这一步在等谁。
 *
 * <h2>发布人 / 任务人 / 当前执行人：三个字段的分工</h2>
 * <ul>
 *   <li>{@code publisher} 发布人：指派出任务的人 —— 这一次从开始到结束都是他；</li>
 *   <li>{@code assignee} 任务人：交付任务的人 —— 也是开始时的执行人；</li>
 *   <li>{@code currentExecutor} 当前执行人：**这一条现在流转到谁手里** ——
 *       三个"待确认"里是发布人（等他的答复），执行中时是 agent（形如 {@code agent#3}）。
 *       它与前两列是两类东西：前两列是"这件事的人"，它是"此刻的球在谁手上"。</li>
 * </ul>
 *
 * <h2>校验放在紧凑构造器里</h2>
 * 与 {@code Account} 同一条做法：非空、长度、步骤下界都在这里一次写完，
 * 于是所有构造点（HTTP 写入口、从库里读回、将来的导入）共用同一份判据。
 *
 * @param id 主键；未落库时为 {@code null}
 * @param publisher 发布人（非空；姓名快照，不是账号外键，理由见 DDL）
 * @param assignee 任务人，可为空
 * @param description 任务描述（非空）
 * @param status 状态（链见 {@link AgentTaskStatus}）
 * @param currentExecutor 当前执行人；没人接手时为空
 * @param currentStep 任务当前步骤，从 1 开始
 * @param agentId 交给哪个 Agent 应用，可为空
 * @param result 执行结果；还没执行完时为空
 * @param cancelReason 取消原因；未取消时为空（取消时必填，判据在服务层的唯一写入口）
 * @param createdAt 落库时间（未落库时为 {@code null}）
 * @param updatedAt 最近一次变更时间（未落库时为 {@code null}）
 */
public record AgentTask(Long id, String publisher, String assignee, String description, AgentTaskStatus status,
		String currentExecutor, int currentStep, Long agentId, String result, String cancelReason,
		Instant createdAt, Instant updatedAt) {

	/** 与 DDL 的 {@code publisher VARCHAR(128)} / {@code current_executor VARCHAR(128)} 对齐。 */
	public static final int MAX_NAME_CHARS = 128;

	/**
	 * 任务描述上限。DDL 里是 TEXT（不设库级上限），这里给一个应用级上限：
	 * 超限**拒绝而不是截断** —— 一段被砍掉尾巴的任务描述看起来完全正常，
	 * 而据此生成的需求确认问的不是原来那件事（与本项目其它所有截断同一条规则）。
	 */
	public static final int MAX_DESCRIPTION_CHARS = 2000;

	/** 与 DDL 的 {@code cancel_reason VARCHAR(512)} 对齐。 */
	public static final int MAX_REASON_CHARS = 512;

	public AgentTask {
		publisher = required(publisher, "发布人", MAX_NAME_CHARS);
		assignee = blankToNull(assignee, "任务人", MAX_NAME_CHARS);
		description = required(description, "任务描述", MAX_DESCRIPTION_CHARS);
		status = status == null ? AgentTaskStatus.NEW : status;
		currentExecutor = blankToNull(currentExecutor, "当前执行人", MAX_NAME_CHARS);
		if (currentStep < 1) {
			throw new IllegalArgumentException("任务步骤从 1 开始，实际是 " + currentStep
					+ "（新发布也记 1 —— 它表示「停在第 1 步还没开始」，界面上没有第 0 步这个东西）");
		}
		result = blankToNull(result, "执行结果", MAX_DESCRIPTION_CHARS);
		cancelReason = blankToNull(cancelReason, "取消原因", MAX_REASON_CHARS);
	}

	/**
	 * 一条刚发布、还没落库的任务。
	 *
	 * <p>它固定了三个字段，于是"新任务长什么样"只有这一处定义：
	 * 状态 {@link AgentTaskStatus#NEW}、步骤 1、当前执行人是任务人（球在他手上，
	 * 由他发起第一条需求确认）。调用方没有机会把它们拼错。
	 */
	public static AgentTask draft(String publisher, String assignee, String description, Long agentId) {
		String normalizedAssignee = assignee == null || assignee.isBlank() ? null : assignee.strip();
		return new AgentTask(null, publisher, normalizedAssignee, description, AgentTaskStatus.NEW,
				normalizedAssignee, 1, agentId, null, null, null, null);
	}

	/** 执行中时的当前执行人写法（形如 {@code agent#3}；没配 agent 时是 {@code agent}）。 */
	public static String agentRef(Long agentId) {
		return agentId == null ? "agent" : "agent#" + agentId;
	}

	/** 一行说明（日志用）。 */
	public String describe() {
		return "#" + (id == null ? "(未落库)" : id) + " " + status.label() + " · 步骤 " + currentStep
				+ " · " + publisher + " → " + (assignee == null ? "(未指定任务人)" : assignee)
				+ " · 当前执行人 " + (currentExecutor == null ? "(无)" : currentExecutor);
	}

	private static String required(String value, String what, int max) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(what + "不能为空");
		}
		return fit(value.strip(), what, max);
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
