package ai.agent.entity.agent.task;

import ai.agent.service.support.task.ScheduleCron;

import java.time.Instant;
import java.util.List;

/**
 * <b>一条 agent 定时任务规则</b> —— 什么时候派活、以谁的名义派、派给谁、派什么活。
 *
 * <h2>它是一条"模板"，不是一次运行</h2>
 * 这张记录里没有"这次跑到哪一步了" —— 那种状态属于它**生成的那些任务**
 * （{@code agent_task}）。一次触发产生一条任务记录，触发 N 次就有 N 条，
 * 而本记录始终只有这一条。所以界面上这一页画的是"有哪些规则、各自下一次什么时候跑"，
 * 而那些任务本身仍然在 agent任务 那一页里按它们自己的状态流转
 * （判需求 → 拆任务点 → 判授权 → 执行 → 验收）。
 *
 * <h2>★ 到点做的事只有一件：生成一条 agent 任务记录</h2>
 * 规则自己<b>不跑模型、不碰文件、不走 ReAct 循环</b>。它到点调的是
 * {@code AgentTaskService#create} —— 与人在界面上点「发布任务」走的是同一个入口。
 * 这条取舍决定了"定时任务' 与 手工任务' 在系统里没有任何行为差异"：
 * 它们的状态链、确认项、授权判断、执行器完全同一套。
 * <p>反过来说，规则里也**没有**"权限档位""工作区目录""用哪个模型"这些字段 ——
 * 那些是执行阶段的事，由任务执行器按任务人自己的权限判（见 {@code TaskFileAuthorization}），
 * 在这里配一份就等于给同一件事留了第二个说法。
 *
 * <h2>★ 定时正则是 6 段式（秒 分 时 日 月 周）</h2>
 * 判据、归一与"日 / 周不能同时写具体值"那条拒绝都在 {@link ScheduleCron} 里，只有那一处；
 * 本记录的紧凑构造器只负责调它一次 —— 于是所有构造点（HTTP 写入口、从库里读回）
 * 共用同一份判据。
 *
 * <h2>发布人 / 任务人：存 id，姓名是读出来的</h2>
 * 与 {@link AgentTask} 逐条相同：{@code publisherId} / {@code assigneeId} 是
 * {@code agent_user.id}，而 {@code publisher} / {@code assignee} 是读侧 LEFT JOIN
 * 账号表查出来的姓名、不落库。姓名允许为空（账号那一行不在时），
 * 而 id 才是"这条规则以谁的名义派活"的原始依据 —— 账号改名之后，
 * 规则指向的人不变、显示的名字跟着改。
 *
 * <h2>自动派活不绕过授权</h2>
 * 这条规则只决定"什么时候派"，派出去之后那次执行能不能动文件，
 * 判的仍然是<b>任务人自己的角色目录前缀</b>（{@code TaskFileAuthorization}）——
 * 过不了就落在「权限不足」等人处置。所以"没人看着"这件事不会放大成"没人管着"。
 *
 * @param id 主键；未落库时为 {@code null}
 * @param cron 定时正则的<b>归一形态</b>（6 段式「秒 分 时 日 月 周」）
 * @param publisherId 发布人的用户 id（非空）
 * @param publisher 发布人的姓名（读侧关联查询得到；账号那一行不在时为空）
 * @param assigneeId 任务人的用户 id（新规则必填；库里可能有早期留下的空行）
 * @param assignee 任务人的姓名（读侧关联查询得到；为空时界面显示「未指定任务人」）
 *        ⚠️ 它是**姓名**（显示名优先），不是登录名也不是 id ——
 *        写请求（改规则）收的是 {@code assigneeId}，界面不要拿这一格回传
 * @param description 任务描述（非空）
 * @param enabled 是否启用；停用的规则<b>不在调度线程池里</b>（那一格没有线程）
 * @param createdAt 落库时间（未落库时为 {@code null}）
 * @param updatedAt 最近一次变更时间（未落库时为 {@code null}）
 */
public record AgentTaskSchedule(Long id, String cron, Long publisherId, String publisher, Long assigneeId,
		String assignee, String description, boolean enabled, Instant createdAt, Instant updatedAt) {

	/** 与 DDL 的 {@code cron VARCHAR(128)} 对齐。 */
	public static final int MAX_CRON_CHARS = 128;

	/**
	 * 任务描述上限。判据与 {@link AgentTask#MAX_DESCRIPTION_CHARS} 是同一个数，
	 * 但**不复用那个常量**：那边是"这一条任务要做什么"，这边是"每一次触发生成的任务要做什么"，
	 * 两者将来要不要一样长是各自的事，共用一个常量会让改一边时静默地改掉另一边。
	 * <p>超限拒绝而不截断：一段被砍掉尾巴的描述看起来完全正常，而据此派出去的那条任务
	 * 判需求、拆任务点读的都是它。
	 */
	public static final int MAX_DESCRIPTION_CHARS = 2000;

	public AgentTaskSchedule {
		cron = ScheduleCron.normalize(cron);
		if (id != null) {
			// 库里读回来的那一份：解析规则只在这里出现一次，所以"库里存着一句语法错的表达式"
			// （手工改过库）会在这条规则上抛出 400，而不是让整个列表读不出来
			ScheduleCron.validate(cron);
		}
		publisherId = requiredId(publisherId, "发布人");
		publisher = blankToNull(publisher);
		assignee = blankToNull(assignee);
		description = required(description, "任务描述", MAX_DESCRIPTION_CHARS);
	}

	/** 用户 id → 可显示的写法：账号那一行不在（姓名为空）时退回 id，而不是留一格空白。 */
	public static String nameOf(Long id, String name) {
		if (id == null) {
			return "";
		}
		return name == null ? "用户#" + id : name;
	}

	/** 一条刚配好、还没落库的规则。 */
	public static AgentTaskSchedule draft(String cron, Long publisherId, String publisher, Long assigneeId,
			String assignee, String description, boolean enabled) {
		return new AgentTaskSchedule(null, cron, publisherId, publisher,
				requiredId(assigneeId, "任务人"), required(assignee, "任务人姓名", AgentTask.MAX_NAME_CHARS),
				description, enabled, null, null);
	}

	/** 把一件事重新配一遍（改规则或启停都走它）：{@code id} 与创建时间保持不动。 */
	public AgentTaskSchedule reconfigured(String newCron, Long newAssigneeId, String newAssignee,
			String newDescription, boolean newEnabled) {
		return new AgentTaskSchedule(id, newCron, publisherId, publisher, newAssigneeId, newAssignee,
				newDescription, newEnabled, createdAt, updatedAt);
	}

	/** 停用 / 启用（只动那一格，其余原样）。 */
	public AgentTaskSchedule withEnabled(boolean newEnabled) {
		return reconfigured(cron, assigneeId, assignee, description, newEnabled);
	}

	/**
	 * 发布人那一格该显示的字（与 {@link AgentTask#executorLabel()} 同一条规矩：
	 * 用户看到的字由后端给，前端不自己拼一份）。
	 */
	public String publisherLabel() {
		return nameOf(publisherId, publisher);
	}

	/**
	 * 任务人那一格该显示的字；没指派时是那句「未指定任务人」。
	 *
	 * <p>⚠️ 它是**姓名**（显示名优先），既不是登录名也不是 id ——
	 * 改规则时回传的是 {@code assigneeId}（见 {@code AgentTaskScheduleController#rule}）。
	 */
	public String assigneeLabel() {
		return assigneeId == null ? "未指定任务人" : nameOf(assigneeId, assignee);
	}

	/**
	 * 回执/日志里那句中文说法（正则 + 中文 + 下一次什么时候跑）。
	 *
	 * @param cronDescription 这句正则的中文说法（由 {@code ScheduleCron#describe} 算）。
	 *        它是<b>参数</b>而不是本记录的一格：这一格里没有这一列 —— 中文说法是从 {@code cron}
	 *        算出来的展示文案，落库会多出一份"与 cron 不同步"的第二真相
	 * @param nextRuns 接下来几次触发时刻；停用或算不出下一次时是空列表
	 */
	public String describe(String cronDescription, List<Instant> nextRuns) {
		String next = nextRuns == null || nextRuns.isEmpty() ? "（算不出下一次）" : nextRuns.get(0).toString();
		return "定时任务「%s」（%s）→ 发布人 %s、任务人 %s，下一次触发 %s"
				.formatted(cron, cronDescription == null || cronDescription.isBlank() ? "（无说明）" : cronDescription,
						publisherLabel(), assigneeLabel(), next);
	}

	/** 一行说明（日志用，不含中文说法 —— 那一份不在这条记录里）。 */
	public String describe() {
		return "#" + (id == null ? "(未落库)" : id) + " " + cron
				+ (enabled ? " 启用" : " 停用") + " · " + publisherLabel() + " → " + assigneeLabel();
	}

	private static Long requiredId(Long value, String what) {
		if (value == null) {
			throw new IllegalArgumentException(what + "不能为空（这一列存的是用户 id）");
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

	private static String fit(String value, String what, int max) {
		if (value.length() > max) {
			throw new IllegalArgumentException("%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
