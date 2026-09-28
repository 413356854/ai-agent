package ai.agent.service.task;

import ai.agent.config.AgentTaskProperties;
import ai.agent.entity.agent.AgentRun;
import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.AgentTaskStatus;
import ai.agent.entity.agent.task.ConfirmType;
import ai.agent.entity.agent.skill.SkillCatalog;
import ai.agent.entity.tool.RunPermission;
import ai.agent.entity.tool.ToolCallOutcome;
import ai.agent.mapper.AgentTaskConfirmMapper;
import ai.agent.mapper.AgentTaskMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.agent.ResumableAgentService;
import ai.agent.service.skill.SkillDirectory;
import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AgentTaskExecution} 的实现 —— <b>这条链上"agent 做的那些动作"的写入口</b>。
 *
 * <h2>一步一格，格子由状态决定</h2>
 * 每个状态对应一个私有方法，方法与状态的对应关系写在 {@link AgentTaskExecution} 的表格里。
 * 写成一个 switch 分派而不是一串 if：漏掉一个状态时编译器会报出来，
 * 而漏掉的表现本来是"那条任务永远停在那里、什么都不报"。
 *
 * <h2>判定用的是模型，判定失败就停住</h2>
 * "需求有没有歧义""拆成哪几个任务点"由 {@link AgentTaskAnalyst} 答（提示词与模型调用见
 * {@code ModelAgentTaskAnalyst}）。它抛异常时本类<b>不做任何写入</b>：
 * 任务留在原状态，调度器记一条 WARN，下一个周期重判。
 * 猜一个结论往下走会留下一条"看起来走完了"的错记录 —— 那比停住难发现得多。
 *
 * <h2>模型调用与写库的边界</h2>
 * 本类的方法<b>不是</b>一个事务：判定与执行会花掉几秒到几十秒，
 * 把它们圈进事务等于让一次长时间的外部调用一直占着数据库连接。
 * 事务只在写入的那几处（{@link AgentTaskTransitions} 的方法上），
 * 每次写入本身是原子的。
 *
 * <h2>每一步为什么这么走</h2>
 * <ol>
 *   <li><b>新发布</b>：需求有歧义就先问一轮（生成需求确认项，落回"待确认需求"）；
 *       没有歧义才进"已确认需求"—— 也就是说"确认需求"这件事<b>可以没有人工环节</b>，
 *       而它有没有发生，看的是"确认项里有没有一条需求确认"；</li>
 *   <li><b>已确认需求</b>：再判一次（用户的答复本身就是新输入），然后拆任务点。
 *       任务点达到阈值就把计划交给用户确认（那些点是他要读的东西）；
 *       没到阈值就直接进授权 —— 拆出来的东西少到一眼看得完，再让人点一次"同意计划"只是多一次点击；</li>
 *   <li><b>已确认计划</b>：计划批了，<b>判断授权</b>（{@link TaskFileAuthorization}：任务人角色的目录前缀
 *       覆盖不覆盖这次要动的目录）—— 通过就<b>直接进「执行中」</b>（下一步由执行器自己开跑），
 *       不通过直接进「权限不足」；</li>
 *   <li><b>执行中</b>：真的跑（{@link ResumableAgentService}，与「Agent 工具调用」那一页同一个入口），
 *       跑完把"改了什么、产出是什么"汇总进执行结果，转到待验收。</li>
 * </ol>
 */
@Component
public class DefaultAgentTaskExecution implements AgentTaskExecution {

	private static final Logger log = LoggerFactory.getLogger(DefaultAgentTaskExecution.class);

	/** 成本归因键：这一族的运行都记在它下面（成本看板按它分组）。 */
	private static final String INTENT = "agent-task";

	/**
	 * 会话 id 前缀 + 任务 id：同一条任务的多次运行因此在 trace 里串成一段。
	 *
	 * <p>长度受 {@code agent_run.conversation_id}（64 字符）限制：
	 * 前缀 5 字符 + 19 位 Long 上限，留着富余。
	 */
	private static final String CONVERSATION_PREFIX = "task-";

	private final AgentTaskMapper tasks;

	private final AgentTaskConfirmMapper confirms;

	private final AgentTaskTransitions transitions;

	private final AgentTaskAnalyst analyst;

	private final ResumableAgentService agent;

	/** 汇总"改动"要用它：一把工具是不是写类，判据在工具自己的风险声明里，不在这里列名字。 */
	private final ToolRegistry tools;

	/** 判断授权：这次要动的目录在不在任务人角色的目录前缀里（判据与措辞都在它那边）。 */
	private final TaskFileAuthorization fileAuthorization;

	/** 技能目录：任务人 → 他的角色 → 角色目录 → 那些目录下的技能（判据与措辞都在它那边）。 */
	private final SkillDirectory skillDirectory;

	private final AgentTaskProperties properties;

	public DefaultAgentTaskExecution(AgentTaskMapper tasks, AgentTaskConfirmMapper confirms,
			AgentTaskTransitions transitions, AgentTaskAnalyst analyst, ResumableAgentService agent,
			ToolRegistry tools, TaskFileAuthorization fileAuthorization, SkillDirectory skillDirectory,
			AgentTaskProperties properties) {
		this.tasks = tasks;
		this.confirms = confirms;
		this.transitions = transitions;
		this.analyst = analyst;
		this.agent = agent;
		this.tools = tools;
		this.fileAuthorization = fileAuthorization;
		this.skillDirectory = skillDirectory;
		this.properties = properties;
	}

	@Override
	public Optional<AgentTask> nextPending(Set<Long> excluded) {
		List<String> statuses = AgentTaskStatus.awaitingAgentStatuses().stream()
				.map(AgentTaskStatus::storedValue)
				.toList();
		MapperRows.AgentTaskRow row = tasks.findNextPending(statuses, List.copyOf(excluded));
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public AgentTaskStatus execute(Long taskId) {
		AgentTask task = stampAgent(transitions.require(taskId));
		return switch (task.status()) {
			case NEW -> reviewFirst(task);
			case CONFIRMED_REQUIREMENT -> reviewConfirmed(task);
			// 判授权与开跑分别是两格：通过时进的是「执行中」，那是下一轮要处理的状态
			case CONFIRMED_PLAN -> askAuthorization(task);
			case EXECUTING -> run(task);
			// 不在"等 agent"那几格里：调度器取不到它，取到了也只说明状态刚被别人改过。
			// 什么都不做是刻意的 —— 这里的每一种"顺手推进一下"都是对别人动作的覆盖
			default -> {
				log.warn("任务 #{} 当前是「{}」，不是任务执行器该处理的格子，跳过", taskId, task.status().label());
				yield task.status();
			}
		};
	}

	// ------------------------------------------------------------------
	//  平台接手：写入"交给哪个 Agent 应用"
	// ------------------------------------------------------------------

	/**
	 * 平台第一次接手这条任务时，把"交给哪个 Agent 应用"写进 {@code agent_task.agent_id}。
	 *
	 * <p>发布人填不了这一列（发布请求体里没有它），值来自配置
	 * {@code ai.agent.task.agent-id} —— 它是本应用在 Agent 应用注册表里的 id
	 * （那张表在另一个业务域里，见 {@code db/task-schema.sql} 的注释）。
	 *
	 * <h2>为什么写在这一刻，而不是"跑到执行那一格"</h2>
	 * 时机由两件事夹出来：写早了（发布那一刻）就需要一个发布人知道的值，而那个 id 是部署方
	 * 才有的东西（它回答的是"这条任务交给哪个 Agent 应用"）；写晚了（{@code 执行中}）
	 * 会让「已确认需求 / 已确认计划」两格的当前执行人显示成不带 id 的 {@code agent}，
	 * 而那两格与执行中在库里是同一件事（球在 agent 手上，见 {@link AgentTaskTransitions#executorFor}）。
	 *
	 * <p>只在为空时写：判定失败后重跑、或恢复之后再接手，都不覆盖已经写下的那个值 ——
	 * 一条任务先后换过两个执行方的话，卡片上只会剩最后一个，而"当时是谁跑的"就查不到了。
	 *
	 * <p>写完<b>重新读一遍</b>：卡片上那个 {@code agent#…} 是读侧按 {@code agent_id} 拼出来的
	 * （{@code AgentTask#executorLabel()}），手里这份还是写之前的那一份（它的 agentId 是 null，
	 * 会拼出不带 id 的 {@code agent}）。
	 */
	private AgentTask stampAgent(AgentTask task) {
		if (task.agentId() != null) {
			return task;
		}
		transitions.setAgentId(task, properties.agentId());
		return transitions.require(task.id());
	}

	// ------------------------------------------------------------------
	//  新发布 → 待确认需求 / 已确认需求
	// ------------------------------------------------------------------

	/**
	 * 新发布：判断需求描述有没有歧义。
	 *
	 * <p>有歧义就在<b>第 1 步</b>生成需求确认项（步骤不递增，见
	 * {@link AgentTaskTransitions#openFirstConfirmation}），没有歧义就直接进"已确认需求"。
	 */
	private AgentTaskStatus reviewFirst(AgentTask task) {
		AgentTaskAnalyst.RequirementReview review = analyst.reviewRequirement(task, answered(task.id()));
		if (review.ambiguous()) {
			log.info("任务 #{} 的需求描述有歧义：先问用户一轮", task.id());
			return transitions.openFirstConfirmation(task, question(review, task));
		}
		log.info("任务 #{} 的需求描述没有歧义：直接进「已确认需求」", task.id());
		return transitions.enterAgent(task, AgentTaskStatus.CONFIRMED_REQUIREMENT);
	}

	/**
	 * 已确认需求：再判一次歧义（用户的答复是新输入），没有歧义就拆任务点并决定下一步。
	 *
	 * <p>为什么这里要再判一次、而不是"答复了就当需求清楚了"：
	 * 答复可能是"需要补充"后面跟着一段仍然含糊的话，而<b>把含糊的需求直接拆成任务点</b>
	 * 会让执行器按自己补全的理解去干活 —— 那一轮干出来的东西没人要。
	 */
	private AgentTaskStatus reviewConfirmed(AgentTask task) {
		List<AgentTaskConfirm> history = answered(task.id());
		AgentTaskAnalyst.RequirementReview review = analyst.reviewRequirement(task, history);
		if (review.ambiguous()) {
			log.info("任务 #{} 答复之后需求仍有歧义：再问一轮", task.id());
			return transitions.enterPending(task, AgentTaskStatus.PENDING_REQUIREMENT, ConfirmType.REQUIREMENT,
					question(review, task));
		}
		AgentTaskAnalyst.TaskBreakdown breakdown = analyst.breakdown(task, history);
		List<String> actions = normalizedActions(breakdown);
		if (actions.size() >= properties.planConfirmThreshold()) {
			log.info("任务 #{} 拆出 {} 个任务点（≥ 阈值 {}）：计划要人确认", task.id(), actions.size(),
					properties.planConfirmThreshold());
			return transitions.enterPending(task, AgentTaskStatus.PENDING_PLAN, ConfirmType.PLAN,
					planText(breakdown, actions));
		}
		log.info("任务 #{} 拆出 {} 个任务点（< 阈值 {}）：跳过计划确认，直接判授权", task.id(), actions.size(),
				properties.planConfirmThreshold());
		return judgeAuthorization(task, breakdown);
	}

	// ------------------------------------------------------------------
	//  已确认计划 → 执行中 / 权限不足
	// ------------------------------------------------------------------

	/**
	 * 已确认计划：判断授权，通过就开跑。
	 *
	 * <p>这里重新拆一次（模型调用 1 次），而不是把"已确认需求"那一步拆出来的工作区存下来：
	 * 存它需要 agent_task 上再加一列，而计划已经写在计划确认项里了 ——
	 * 判断授权要回答的只是"这次要动的目录在不在任务人的角色前缀里"，
	 * 用当下的描述重算一次比存一份旧的更准。
	 */
	private AgentTaskStatus askAuthorization(AgentTask task) {
		AgentTaskAnalyst.TaskBreakdown breakdown = analyst.breakdown(task, answered(task.id()));
		return judgeAuthorization(task, breakdown);
	}

	/**
	 * <b>判断授权</b>那一格 —— 两条路都从这里进（"跳过计划确认"的那条与"已确认计划"那条）。
	 *
	 * <h2>先判权限，通过就开跑</h2>
	 * 判据只有一条：任务人角色的目录前缀覆盖不覆盖这次要动的目录
	 * （{@link TaskFileAuthorization}，判据与措辞都在那边）。通过就把这次要动的目录交给
	 * {@link #run} 开跑（落「执行中」），不通过就进「权限不足」等人处置。
	 *
	 * <p>不通过时<b>不停在"等人批一下"上</b>：平台已经判过"任务人自己没有权限做这件事"，
	 * 而让人批一件任务人自己没有权限做的事，批完之后 agent 会在文件工具那一层撞墙
	 * （完全权限档位下则是直接写进去，连墙都没有）。
	 */
	private AgentTaskStatus judgeAuthorization(AgentTask task, AgentTaskAnalyst.TaskBreakdown breakdown) {
		TaskFileAuthorization.Verdict verdict = fileAuthorization.judge(task.assigneeId(), breakdown.workspaceDir());
		if (!verdict.allowed()) {
			log.info("任务 #{} 的授权判断不通过（任务人「{}」）：{}", task.id(), assigneeName(task), verdict.reason());
			return transitions.enterPermissionDenied(task, verdict.reason());
		}
		log.info("任务 #{} 的授权判断通过（任务人「{}」）：{} —— 直接开跑", task.id(), assigneeName(task),
				verdict.reason());
		return run(task, breakdown);
	}

	// ------------------------------------------------------------------
	//  执行中 → 待验收
	// ------------------------------------------------------------------

	/**
	 * 执行中：按当下来重新算一次要做什么，然后跑。
	 *
	 * <p>判定<b>不做回灌重试</b>（与 {@link #reviewConfirmed} 同一取舍）：判不出来就抛出去，
	 * 执行器在抛之前<b>什么都没改</b>，于是任务留在「执行中」，调度器记一条 WARN，
	 * 下一个周期再判 —— 宁可停着等下一轮，也不按猜出来的清单去动文件。
	 */
	private AgentTaskStatus run(AgentTask task) {
		AgentTaskAnalyst.TaskBreakdown breakdown = analyst.breakdown(task, answered(task.id()));
		return run(task, breakdown);
	}

	/**
	 * 判断授权通过之后真的跑一次，把改动与产出汇总进执行结果，转到待验收。
	 *
	 * <p>先落"执行中"再开跑：那一格是给人和给恢复看的 ——
	 * 进程死在这次运行中途时，任务停在"执行中"而不是停在"已确认计划"，
	 * 于是它<b>不会被下一轮调度重新跑一遍</b>（重复的写操作是事故，不是浪费）。
	 * 代价是它会一直停在执行中等人处置，处置方式见下面那条 WARN。
	 *
	 * <h2>没跑成为什么不重试、也不进待验收</h2>
	 * 两者都是刻意的：
	 * <ul>
	 *   <li><b>不自动重试</b>：一次跑到一半失败的任务，重跑会把已经发生的写操作再做一遍；</li>
	 *   <li><b>不进待验收</b>：那样"验收通过"会把它推成"已完成"，而它并没有产出可验收的东西 ——
	 *   一个假的完成状态比一条停住的任务坏得多。</li>
	 * </ul>
	 * 所以它停在"执行中"，并把失败原因写进执行结果那一格（卡片上看得见）：
	 * 人可以用「登记执行结果」把它推到待验收，或者直接取消再发一条。
	 *
	 * <h2>为什么用这一次拆出来的工作区</h2>
	 * 它与 {@link #judgeAuthorization} 判的是同一份（同一次模型判定的 {@code workspaceDir}）——
	 * 判定与开跑在同一步里完成，中间没有人工答复，所以不存在"两次判定的目录不是一个"这件事。
	 */
	private AgentTaskStatus run(AgentTask task, AgentTaskAnalyst.TaskBreakdown breakdown) {
		List<String> actions = normalizedActions(breakdown);
		String instruction = instruction(task, answered(task.id()), actions);
		RunPermission permission = permission(breakdown);

		// 步骤 +1：进"执行中"（执行人换成任务人，理由见 AgentTaskTransitions#executorFor）
		transitions.enterAgent(task, AgentTaskStatus.EXECUTING);

		String runId = UUID.randomUUID().toString();
		AgentRun result = agent.runWithSkills(runId, instruction, null, INTENT,
				CONVERSATION_PREFIX + task.id(), null, permission, skillsOf(task, breakdown));
		String summary = summarize(runId, result);

		if (!result.answered()) {
			log.warn("任务 #{} 的这次执行没有正常结束（{}）：任务留在「执行中」，等人处置（取消，或用「登记执行结果」"
					+ "推到待验收）。运行 runId={}，{}", task.id(), result.stopReason(), runId,
					result.failureAdvice() == null ? "没有更细的失败原因" : result.failureAdvice());
			transitions.setResult(task, "【本次执行没有正常结束】停因：" + result.stopReason() + "\n\n" + summary);
			return AgentTaskStatus.EXECUTING;
		}

		log.info("任务 #{} 执行完成：{} 步 / {} tokens / {}ms，转到待验收", task.id(), result.steps().size(),
				result.totalTokens(), result.elapsedMs());
		// 待验收是"球回到发布人手上"：执行人由 executorFor 算，不在这里拼
		AgentTaskStatus reached = transitions.move(task, AgentTaskStatus.PENDING_ACCEPTANCE,
				task.currentStep() + 2, AgentTaskTransitions.executorFor(AgentTaskStatus.PENDING_ACCEPTANCE, task));
		transitions.setResult(task, summary);
		return reached;
	}

	/**
	 * 这次运行的工作区与权限。
	 *
	 * <p>工作区<b>由任务描述决定</b>（{@link AgentTaskAnalyst.TaskBreakdown#workspaceDir()}）：
	 * 有的任务要在某个目录里改文件，有的只是回答一个问题、或者跑一段不依赖文件的命令 ——
	 * 后者的正确形态是<b>没有工作区</b>，而不是"随便给它一个默认目录"
	 * （给一个用不上的目录会让那次运行的审计记录写着一次它其实没进去过的授权）。
	 *
	 * <p>模型给的目录<b>不可信</b>，所以这里要过一遍 {@code validateExistingDirectory()}：
	 * 不合法就退成"没有工作区"并留一条 WARN，而不是让这次运行失败 ——
	 * 目录写错时该发生的是"这次运行没有工作区"（文件类工具会在调用时明确回一句没有工作区），
	 * 不是"整条任务卡住"。两个分支（模型说不需要目录 / 模型给的目录用不了）的档位是同一条规则：
	 * 取配置里那一档，理由与没有工作区那条一致 —— 执行档位是平台按配置定的，
	 * 它不该因为一个目录名写错而静默放大或缩小（目录那一层的授权另有一道判断，见
	 * {@link #judgeAuthorization}）。副作用因此是可见的：
	 * 退化的那一次在日志里有一行 WARN，运行记录里的 `workspace_dir` 也是空的。
	 */
	private RunPermission permission(AgentTaskAnalyst.TaskBreakdown breakdown) {
		String dir = breakdown.workspaceDir() == null ? "" : breakdown.workspaceDir().strip();
		if (dir.isEmpty()) {
			// 没有工作区就是"没有额外授权"；档位取自配置（见 AgentTaskProperties 的说明）。
			// 注意这里仍用配置的档位：一次不需要目录的执行照样可以改库、可以调外部系统
			return RunPermission.ofStored("", properties.level());
		}
		RunPermission permission = RunPermission.ofStored(dir, properties.level());
		try {
			permission.validateExistingDirectory();
			return permission;
		}
		catch (IllegalArgumentException | SecurityException e) {
			log.warn("任务执行时工作区不可用（{}），这次按【没有工作区】跑（档位仍是配置里那一档）："
					+ "文件类工具会因为解析不出路径而报「没有工作区」，其余工具照常。原始判定：{}",
					e.getMessage(), dir);
			return RunPermission.ofStored("", properties.level());
		}
	}

	/**
	 * <b>这条任务的执行者能用的技能</b>。
	 *
	 * <h2>执行者是「任务人」，不是发布人</h2>
	 * 这与授权判断（{@link #judgeAuthorization}：判的是任务人角色的目录前缀）
	 * 是同一个人 —— 技能是按"角色目录"筛的，用它来筛的那一刻已经隐含了
	 * "这些目录他有权限"这一层；换成发布人就会出现"发布人的技能在任务人的目录下被执行"，
	 * 而那些技能可能包含任务人自己都读不到的东西。
	 *
	 * <h2>工作区用本次拆出来的那一个，失败时退成"全部文件夹"</h2>
	 * 技能与工作区不是一回事：技能装在<b>角色目录</b>下（可能有很多个），
	 * 而这次任务只在一个目录里干活。所以筛选<b>不以本次工作区为限</b> ——
	 * 否则"任务只动 demo 目录"会让这个人其它目录下的技能全部消失，
	 * 而那正是"技能写了却没人用得上"的另一种形态。
	 * 于是这里用的是全套角色目录前缀（{@link SkillDirectory#forPermissions}），
	 * {@code breakdown.workspaceDir()} 只是顺带记进日志的一个上下文。
	 *
	 * <p>解析不出来（账号不在 / 已停用 / 没有角色）时退成"没有技能"，并留一条 INFO：
	 * 一条任务不该因为"任务人的权限此刻解析不出来"而失败 ——
	 * 授权判断那一步会给出真正的结论。
	 */
	private SkillCatalog skillsOf(AgentTask task, AgentTaskAnalyst.TaskBreakdown breakdown) {
		SkillCatalog catalog = task.assigneeId() == null ? SkillCatalog.none()
				: skillDirectory.forExecutor(task.assigneeId());
		log.info("任务 #{} 的技能目录：{}（任务人 {}，本次工作区 {}）", task.id(), catalog.describe(), assigneeName(task),
				breakdown.workspaceDir() == null || breakdown.workspaceDir().isBlank() ? "(没有工作区)"
						: breakdown.workspaceDir());
		return catalog;
	}

	// ------------------------------------------------------------------
	//  提示词与文案
	// ------------------------------------------------------------------

	/**
	 * 执行提示词：任务描述 + 用户已确认的全部内容 + 这次要完成的清单。
	 *
	 * <p><b>已确认的内容必须原样带上</b>（需求与计划那两条确认项的正文与答复）：
	 * 模型看不到确认项表，而这两次确认正是"人同意过什么"的全部依据。
	 * 只给它一句任务描述的话，它会按自己的理解补全需求 —— 那正是前两轮确认要防的事。
	 */
	private static String instruction(AgentTask task, List<AgentTaskConfirm> history, List<String> actions) {
		StringBuilder sb = new StringBuilder();
		sb.append("【任务】\n").append(task.description()).append('\n');
		if (!history.isEmpty()) {
			sb.append("\n【用户已经确认过的内容】\n");
			for (AgentTaskConfirm confirm : history) {
				sb.append("- 第 ").append(confirm.stepIndex()).append(" 步 · ").append(confirm.typeLabel()).append("：");
				if (confirm.description() != null && !confirm.description().isBlank()) {
					sb.append(confirm.description()).append(" → ");
				}
				sb.append("答复：").append(confirm.answerLabel()).append('\n');
			}
		}
		if (!actions.isEmpty()) {
			sb.append("\n【这次要完成的事（按顺序）】\n");
			for (int i = 0; i < actions.size(); i++) {
				sb.append(i + 1).append(". ").append(actions.get(i)).append('\n');
			}
		}
		sb.append("""

				【要求】
				- 只做上面列出的这些事，不要顺手扩大范围；
				- 用工具完成，不要只在回答里描述你会怎么做；
				- 做完之后用一段话说明：实际改动了什么、产出是什么。没有任何改动也要明说。""");
		return sb.toString();
	}

	/**
	 * 执行结果正文：<b>汇总改动与产出</b>。
	 *
	 * <p>三段各回答一个问题，缺哪一段那个问题就没人答：
	 * 产出（模型自己的总结）、改动（这次运行里成功的写操作，逐条列出工具名与参数）、
	 * 运行（步数 / token / 耗时 / 停因）—— 人来验收时要看的是前两段，
	 * 事后要解释"这次花了多少钱、绕了几圈"看的是第三段。
	 *
	 * <p>判"哪些算改动"用的是工具自己的风险声明（{@code ToolHints.readOnlyHint}），
	 * 不是一份写死的工具名清单：清单会在新增工具时漏掉一项，而漏掉的表现是
	 * "改了东西但改动那一栏写着没有" —— 一条静默的假话。
	 */
	private String summarize(String runId, AgentRun run) {
		StringBuilder sb = new StringBuilder();
		sb.append("【产出】");
		if (run.finalAnswer() == null || run.finalAnswer().isBlank()) {
			sb.append("（模型没有给出最终答复：").append(run.stopReason()).append("）");
		}
		else {
			sb.append(run.finalAnswer().strip());
		}

		List<String> changes = new ArrayList<>();
		for (ToolCallOutcome outcome : run.allOutcomes()) {
			if (!isWrite(outcome.invocation().name())) {
				continue;
			}
			changes.add("- %s %s%s".formatted(outcome.invocation().name(),
					outcome.invocation().argumentsForFeedback(200),
					outcome.succeeded() ? "" : "（没成功：" + outcome.stage() + "）"));
		}
		sb.append("\n\n【改动】");
		if (changes.isEmpty()) {
			sb.append("这次运行没有写操作（只调用了只读工具，或没有调用工具）");
		}
		else {
			sb.append("成功的才算数，没成功的也在下面标了出来：\n").append(String.join("\n", changes));
		}

		sb.append("\n\n【运行】runId=%s · %d 步 · %d 次工具调用 · %d tokens · %dms · 停因 %s".formatted(runId,
				run.steps().size(), run.toolCallCount(), run.totalTokens(), run.elapsedMs(), run.stopReason()));
		if (run.failureAdvice() != null) {
			sb.append("\n失败建议：").append(run.failureAdvice());
		}
		return sb.toString();
	}

	/** 这把工具是不是写类（认不出的工具名一律按只读算：模型编出来的名字什么都没改动）。 */
	private boolean isWrite(String toolName) {
		return tools.find(toolName).map(AgentTool::description)
				.map(definition -> !definition.hints().readOnlyHint())
				.orElse(false);
	}

	// ------------------------------------------------------------------
	//  小工具
	// ------------------------------------------------------------------

	/** 这条任务里<b>已答复</b>的确认项（按步骤排）。没答复的那些是待办，不该进判定与提示词。 */
	private List<AgentTaskConfirm> answered(Long taskId) {
		return confirms.listByTask(taskId).stream()
				.map(MapperRows.AgentTaskConfirmRow::toDomain)
				.filter(AgentTaskConfirm::answered)
				.toList();
	}

	/** 日志里任务人的写法：账号那一行不在（姓名落空）时退回 id，而不是留一格空白。 */
	private static String assigneeName(AgentTask task) {
		return task.assignee() == null ? "用户#" + task.assigneeId() : task.assignee();
	}

	/**
	 * 确认项正文：模型没给出可用正文时退回任务描述。
	 *
	 * <p>退回而不是拒绝：一条正文为空的需求确认在界面上是一片空白 —— 用户不知道该答什么，
	 * 而"答不了"会被读成系统坏了。带着任务描述至少让人知道问的是哪件事。
	 */
	private static String question(AgentTaskAnalyst.RequirementReview review, AgentTask task) {
		String question = review.question() == null ? "" : review.question().strip();
		return question.isEmpty() ? "请确认任务需求：" + task.description() : question;
	}

	/** 计划确认项的正文：计划 + 拆出来的任务点（人要批的就是这两样）。 */
	private static String planText(AgentTaskAnalyst.TaskBreakdown breakdown, List<String> actions) {
		StringBuilder sb = new StringBuilder();
		String plan = breakdown.plan() == null ? "" : breakdown.plan().strip();
		sb.append(plan.isEmpty() ? "请确认执行计划（模型没有给出计划正文）" : "请确认执行计划：\n" + plan);
		if (!actions.isEmpty()) {
			sb.append("\n\n拆出来的任务点：\n");
			for (int i = 0; i < actions.size(); i++) {
				sb.append(i + 1).append(". ").append(actions.get(i)).append('\n');
			}
		}
		return sb.toString().strip();
	}

	/** 任务点：去空、去重、保持顺序（模型给的清单里出现重复项会让"几个任务点"这个数偏大）。 */
	private static List<String> normalizedActions(AgentTaskAnalyst.TaskBreakdown breakdown) {
		if (breakdown.actions() == null) {
			return List.of();
		}
		return breakdown.actions().stream()
				.filter(action -> action != null && !action.isBlank())
				.map(String::strip)
				.distinct()
				.toList();
	}

}
