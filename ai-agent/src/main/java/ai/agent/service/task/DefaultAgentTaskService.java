package ai.agent.service.task;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.AgentTaskStatus;
import ai.agent.entity.agent.task.ConfirmType;
import ai.agent.mapper.AgentTaskConfirmMapper;
import ai.agent.mapper.AgentTaskMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AgentTaskService} 的实现 —— <b>这条链上"人做的那些动作"的写入口</b>。
 *
 * <h2>状态机只有这一份</h2>
 * "什么状态能做什么、做完到哪、执行人换成谁"全部落在两处，而且只有两处：
 * 人做的动作在这里（答复确认项、验收、取消），agent 做的动作在
 * {@code DefaultAgentTaskExecution}；两边共用的写入原语在 {@link AgentTaskTransitions}。
 * 界面的按钮与接口返回的 {@code actions} 都从 {@link #actionsOf} 算出来 ——
 * 界面上再写一遍判断的后果是两份判据迟早不一致，而它们不一致时的现象是
 * "点得下去、点完报错"（用户会以为是自己操作错了）。
 *
 * <h2>每个写动作是一个事务</h2>
 * 状态推进与确认项生成必须同生共死：只改了状态没生成确认项，
 * 那条任务就卡在"待确认需求"而没有任何东西可答（界面上永远是待办、永远点不动）。
 * 这条链上会生成确认项的现在是任务执行器（见 {@code AgentTaskExecution}），
 * 所以本类的 {@code @Transactional} 保的是"答复一次确认项"这件事的原子性。
 *
 * <h2>发布人 / 任务人必须是真实账号</h2>
 * 判据在 {@link #realUser}，它落在服务层而不是实体的构造器里：那条判据要读账号表，
 * 而实体的构造点里有一个是"从库里读回一行"。它对应界面上的一处做法 ——
 * 那两格是<b>搜索之后从下拉里选</b>的，不是可以随便打字的输入框
 * （落库的是账号 id，而账号表里查不到的名字不会有任何约束拦下这条任务）。
 *
 * <h2>写完就调度一次任务执行</h2>
 * 每一次任务记录更新之后都调一次 {@link AgentTaskDispatch#onTaskUpdated}（已完成 / 已取消除外，
 * 那条判据在调度器里）：新发布要判断需求有没有歧义、答复完需求要拆任务点、答复完授权要开跑 ——
 * 这些"下一步"全在 agent 手上。等下一次定时触发（默认 10 分钟）才动的话，
 * 一条本来能立刻往下走的任务会白等一个周期，而用户看到的是"页面刷新了它还在原地"。
 * 调度是<b>异步且不阻塞</b>的：抢不到执行锁就直接返回（见 {@code AgentTaskScheduler}）。
 */
@Service
public class DefaultAgentTaskService implements AgentTaskService {

	private static final Logger log = LoggerFactory.getLogger(DefaultAgentTaskService.class);

	private final AgentTaskMapper tasks;

	private final AgentTaskConfirmMapper confirms;

	/** 状态链的写入原语（与任务执行器共用同一份）。 */
	private final AgentTaskTransitions transitions;

	/** 任务记录更新后的调度入口（定时任务那一侧实现）。 */
	private final AgentTaskDispatch dispatch;

	/**
	 * 账号表（发布人 / 任务人必须是真实用户）。
	 *
	 * <p>读的是权限域的服务而不是 {@code AccountMapper}：账号的存取口径（名字怎么比、
	 * 读失败怎么报）在那一层只有一份，这里再注入一个 mapper 就等于把那份口径复制一份。
	 */
	private final PermissionService permissions;

	public DefaultAgentTaskService(AgentTaskMapper tasks, AgentTaskConfirmMapper confirms,
			AgentTaskTransitions transitions, AgentTaskDispatch dispatch, PermissionService permissions) {
		this.tasks = tasks;
		this.confirms = confirms;
		this.transitions = transitions;
		this.dispatch = dispatch;
		this.permissions = permissions;
		verifyColumnsCoverAllStatuses();
	}

	/**
	 * 启动期自检：<b>每一个状态都必须落在某一列上</b>。
	 *
	 * <p>看板是按列筛的（{@link #board} 逐个 {@link Column} 取它名下的状态），
	 * 于是"加了一个状态、忘了给它一个列"的后果是那张卡片**从看板上消失** ——
	 * 一条不报错的错误，而且"卡片不见了"是这一页最难被发现的坏法（没有谁会去数卡片）。
	 * 所以这里在装配时就把每个状态问一遍，缺了当场起不来。
	 *
	 * <p>它同时是 {@link Column#of} 的调用点 —— 那个方法的存在意义就是这个判断。
	 */
	private static void verifyColumnsCoverAllStatuses() {
		for (AgentTaskStatus status : AgentTaskStatus.values()) {
			Column.of(status);
		}
		log.info("任务看板的分列自检完成：{} 个状态落在 {} 列上", AgentTaskStatus.values().length,
				Column.values().length);
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public Board board() {
		List<AgentTask> all = tasks.list().stream().map(MapperRows.AgentTaskRow::toDomain).toList();
		List<BoardColumn> columns = new ArrayList<>();
		for (Column column : Column.values()) {
			List<AgentTask> inColumn = all.stream().filter(task -> column.statuses().contains(task.status())).toList();
			columns.add(new BoardColumn(column.key(), column.label(),
					column.statuses().stream().map(AgentTaskStatus::label).toList(), inColumn));
		}
		return new Board(List.copyOf(columns));
	}

	@Override
	public Detail detail(Long id) {
		AgentTask task = transitions.require(id);
		List<AgentTaskConfirm> list = confirms.listByTask(id).stream()
				.map(MapperRows.AgentTaskConfirmRow::toDomain)
				.toList();
		return new Detail(task, list, actionsOf(task.status()));
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@Override
	@Transactional
	public Detail create(String publisher, String assignee, String description) {
		// 两个名字都换成账号表里那一行自己的写法：调用方写进来的 "Admin" 落库与回执都是 "admin"
		// （大小写与前后空格都以账号表为准），而落库的是这两个账号的 id（姓名不落库）
		AgentTask task = draft(realUser(publisher, "发布人"), realUser(assignee, "任务人"), description);
		tasks.insert(task.publisherId(), task.assigneeId(), task.description(), task.status().storedValue(),
				task.currentExecutorId(), task.currentStep());
		Long id = AgentTaskTransitions.requiredKey(tasks.lastInsertId(), "任务");
		log.info("新建 agent 任务 #{}：{}", id, task.describe());
		// 新发布也要调度：判断需求有没有歧义这一步在 agent 手上，不等下一个定时周期
		dispatchAfterCommit(AgentTaskStatus.NEW);
		return detail(id);
	}

	@Override
	@Transactional
	public Detail advance(Long id, String result) {
		AgentTask task = transitions.require(id);
		AgentTaskStatus reached = switch (task.status()) {
			case EXECUTING -> finishExecution(task, result);
			case PENDING_ACCEPTANCE -> accept(task);
			default -> throw new TaskConflictException(("当前状态「%s」没有可推进的事件 —— 能推进的只有"
					+ "「执行中」（登记执行结果）与「待验收」（验收通过）；三个「待确认」在等确认项的答复、"
					+ "「权限不足」要答复的是 revise（「已修改任务」/「取消任务」），"
					+ "「新发布」与三个「已确认」由 agent 任务执行处理（页面上没有按钮就是还没轮到人），"
					+ "终态不再流转。详情里的 actions 就是现在能做的事。")
					.formatted(task.status().label()));
		};
		dispatchAfterCommit(reached);
		return detail(id);
	}

	@Override
	@Transactional
	public Detail answer(Long id, Long confirmId, String text) {
		if (text == null || text.isBlank()) {
			throw new TaskInvalidException("答复不能为空 —— 空答复在库里与「还没答」（answer 为 NULL）"
					+ "长得一样，而两者的处置完全不同：前者要推进状态，后者要继续等");
		}
		AgentTask task = transitions.require(id);
		AgentTaskStatus status = task.status();
		ConfirmType waiting = status.waitingConfirmation().orElseThrow(() -> new TaskConflictException(
				("当前状态「%s」不在等确认项的答复 —— 能答复的只有「待确认需求」/「待确认计划」/「待授权」；"
						+ "「执行中」与「待验收」要推进请用 advance 那个动作；"
						+ "「新发布」与三个「已确认」由 agent 任务执行处理，人和 advance 都推不动它。")
						.formatted(status.label())));
		AgentTaskConfirm confirm = requireConfirm(id, confirmId);
		if (confirm.type() != waiting || confirm.stepIndex() != task.currentStep()) {
			throw new TaskConflictException(("这条确认项不属于任务当前这一步 —— 它问的是「%s」（第 %d 步），"
					+ "而任务现在在第 %d 步、等的是「%s」。多半是另一处已经推进过这条任务，刷新一次就能看到新的待办。")
					.formatted(confirm.typeLabel(), confirm.stepIndex(), task.currentStep(), waiting.label()));
		}
		// 条件更新（answer IS NULL）：两条请求同时答复时只有一条能改到行，另一条在这里被挡下
		if (confirms.answer(confirmId, text.strip()) != 1) {
			throw new TaskConflictException("这条确认项已经答复过了 —— 重复答复会覆盖掉第一次的选择，"
					+ "而覆盖之后界面上只剩一个答案，没有任何痕迹说明它曾经是另一个。刷新后看到的就是当时的答复");
		}

		// 一步一跳的落点写成 switch 表达式，于是"到哪个状态"与"谁接着做"在同一个分支里
		// （三个落点都是"等 agent 动手"的状态，生成下一条确认项的是任务执行器 —— 它要按答复内容判断）
		AgentTaskStatus nextStatus = switch (waiting) {
			case REQUIREMENT -> transitions.enterAgent(task, AgentTaskStatus.CONFIRMED_REQUIREMENT);
			case PLAN -> transitions.enterAgent(task, AgentTaskStatus.CONFIRMED_PLAN);
			case AUTHORIZATION -> transitions.enterAgent(task, AgentTaskStatus.AUTHORIZED);
		};
		log.info("任务 #{} 的{}已答复（{}）：这次答复把它推到了「{}」", task.id(), waiting.label(), text.strip(),
				nextStatus.label());
		dispatchAfterCommit(nextStatus);
		return detail(id);
	}

	@Override
	@Transactional
	public Detail cancel(Long id, String reason) {
		AgentTask task = transitions.require(id);
		if (reason == null || reason.isBlank()) {
			throw new TaskInvalidException("取消原因必填 —— 一条没有原因的取消记录，事后只能靠回忆"
					+ "（与「谁批的」同一条规矩：那一列空着等于没有这条记录）");
		}
		if (task.status().terminal()) {
			throw new TaskConflictException("任务已经是「%s」，不能再取消".formatted(task.status().label()));
		}
		tasks.updateCancel(id, reason.strip());
		// 步骤与执行人保持不动：它们回答"取消的那一刻球在谁手上"，而这是复盘时要看的东西
		transitions.move(task, AgentTaskStatus.CANCELLED, task.currentStep(), task.currentExecutorId());
		log.info("任务 #{} 已取消：{}", id, reason.strip());
		dispatchAfterCommit(AgentTaskStatus.CANCELLED);
		return detail(id);
	}

	/**
	 * 权限不足 → 新发布：用户答复「已修改任务」。
	 *
	 * <p>回到「新发布」而不是就地重判：这条链上的每一步都以上一步的结论为输入
	 * （需求歧义 → 任务点 → 计划 → 授权范围），改了任务描述或改了权限之后，
	 * 那些结论都可能已经过时 —— 只重判授权会让后面几步继续用旧结论。
	 *
	 * <p>步骤 +1（这一格也是"经过了一格"），当前执行人回到任务人
	 * （{@code executorFor(NEW, task)}：球在他手上，由他决定要不要往下走）。
	 *
	 * <p>上一次「权限不足」的结论<b>留在执行结果那一格里</b>，前面加一句说明：
	 * 它是"当时为什么被打回"的唯一记录，而重走一遍之后这个理由不该只剩"已取消 / 已完成"。
	 */
	@Override
	@Transactional
	public Detail revise(Long id) {
		AgentTask task = transitions.require(id);
		if (task.status() != AgentTaskStatus.INSUFFICIENT_PERMISSION) {
			throw new TaskConflictException(("当前状态「%s」不是「权限不足」，没有可重发的任务 —— "
					+ "「已修改任务」只在权限不足时出现（权限那一关过不了，改了之后再重走一遍）；"
					+ "详情里的 actions 就是现在能做的事。").formatted(task.status().label()));
		}
		String previous = task.result() == null ? "" : task.result().strip();
		AgentTaskStatus reached = transitions.move(task, AgentTaskStatus.NEW, task.currentStep() + 1,
				AgentTaskTransitions.executorFor(AgentTaskStatus.NEW, task));
		transitions.setResult(task, "用户答复「已修改任务」：这条任务已回到「新发布」重新走一遍"
				+ "（重新判需求 → 拆任务点 → 判授权）。"
				+ (previous.isEmpty() ? "" : "\n\n上一次「权限不足」的结论（留档）：\n" + previous));
		log.info("任务 #{} 由用户答复「已修改任务」，回到「新发布」（第 {} 步）", id, task.currentStep() + 1);
		dispatchAfterCommit(reached);
		return detail(id);
	}

	/**
	 * 调度一次任务执行 —— <b>必须等这次写事务提交之后</b>。
	 *
	 * <h2>为什么不能就地调</h2>
	 * 五个写动作都在事务里，而调度是<b>另一个线程</b>去查库的（见 {@code AgentTaskScheduler}）。
	 * 就地调的话，那一刻这一行还没提交，另一个连接读不到它 ——
	 * 实测（真后端、一次发布）：创建事务里的那次调度查到 0 条待处理任务，
	 * 于是这次调度白跑一趟，任务要等到下一个定时周期（默认 10 分钟）才动。
	 * <b>它的现象与"调度根本没接上"完全一样</b>，而两种原因的排查方向不同。
	 *
	 * <p>挂在 {@code afterCommit} 上之后，"记录更新即调度"这句话才真的成立。
	 * 事务之外的调用方（没有活跃事务时）直接调 —— 那时没有"未提交"这回事。
	 */
	private void dispatchAfterCommit(AgentTaskStatus status) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			dispatch.onTaskUpdated(status);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				dispatch.onTaskUpdated(status);
			}
		});
	}

	// ------------------------------------------------------------------
	//  状态链上"人做的那一段"
	// ------------------------------------------------------------------

	/** 执行中 → 待验收：登记执行结果（可空），球交回发布人。 */
	private AgentTaskStatus finishExecution(AgentTask task, String result) {
		AgentTaskStatus moved = transitions.move(task, AgentTaskStatus.PENDING_ACCEPTANCE,
				task.currentStep() + 1, AgentTaskTransitions.executorFor(AgentTaskStatus.PENDING_ACCEPTANCE, task));
		transitions.setResult(task, result);
		// 没登记结果也能进验收：真实链路里"跑完了但没产出"是常态，
		// 而把它挡在这里会让那条任务永远卡在执行中 —— 界面上表现为"它还在跑"，不报错
		log.info("任务 #{} 执行结束（第 {} 步）：现在等 {} 验收{}", task.id(), task.currentStep() + 1,
				named(task.publisher(), task.publisherId()), result == null || result.isBlank() ? "（未登记执行结果）" : "");
		return moved;
	}

	/** 待验收 → 已完成：验收通过。完成后没有"当前执行人"—— 没有事件在谁手上了。 */
	private AgentTaskStatus accept(AgentTask task) {
		AgentTaskStatus moved = transitions.move(task, AgentTaskStatus.COMPLETED, task.currentStep() + 1,
				AgentTaskTransitions.executorFor(AgentTaskStatus.COMPLETED, task));
		log.info("任务 #{} 验收通过：已完成", task.id());
		return moved;
	}

	/**
	 * 现在能做的动作。它是"界面上该画哪些按钮"的唯一依据
	 * （与 {@link #advance} / {@link #answer} / {@link #cancel} 允许的状态一一对应）。
	 *
	 * <p><b>「新发布」与三个「已确认」没有推进按钮</b>：那四格的下一步由任务执行器做，
	 * 人点不动。给它们画一个"看起来能推进"的按钮比不画更坏 —— 用户会反复点，
	 * 而每次都回一句"当前状态不允许"。
	 *
	 * <p>「权限不足」两个按钮：{@code revise}（「已修改任务」= 重新走一遍）与 {@code cancel}。
	 * 没有 {@code advance}：这一格要的不是"推进当前事件"，而是"这条任务已经改过了"。
	 */
	private static List<Action> actionsOf(AgentTaskStatus status) {
		return switch (status) {
			case NEW -> List.of(new Action("cancel", "取消任务"));
			case PENDING_REQUIREMENT, PENDING_PLAN, PENDING_AUTHORIZATION ->
					List.of(new Action("answer", "答复确认项"), new Action("cancel", "取消任务"));
			case INSUFFICIENT_PERMISSION ->
					List.of(new Action("revise", "已修改任务"), new Action("cancel", "取消任务"));
			case CONFIRMED_REQUIREMENT, CONFIRMED_PLAN, AUTHORIZED ->
					List.of(new Action("cancel", "取消任务"));
			case EXECUTING -> List.of(new Action("advance", "登记执行结果"), new Action("cancel", "取消任务"));
			case PENDING_ACCEPTANCE -> List.of(new Action("advance", "验收通过"), new Action("cancel", "取消任务"));
			case COMPLETED, CANCELLED -> List.of();
		};
	}

	// ------------------------------------------------------------------
	//  内部
	// ------------------------------------------------------------------

	/**
	 * 建任务的实体构造，报错翻译成 400。
	 *
	 * <p>非空与长度上限的判据只有 {@link AgentTask} 那一份（控制器只拦"根本没传"），
	 * 而它抛的是 {@link IllegalArgumentException} —— 那个类型到不了
	 * {@code ErrorResponseAdvice} 的 400 分支，于是"任务描述超长"会以 500 的形式出现，
	 * 用户看到的是一句"服务器出错了"。这里只做翻译，判据不在这里重写。
	 */
	private static AgentTask draft(Account publisher, Account assignee, String description) {
		try {
			return AgentTask.draft(publisher.id(), publisher.name(), assignee.id(), assignee.name(), description);
		}
		catch (IllegalArgumentException e) {
			throw new TaskInvalidException(e.getMessage());
		}
	}

	/**
	 * 发布人 / 任务人必须是从账号表里选出来的真实用户 —— <b>这条判据只在这里</b>。
	 *
	 * <p>它拦的是"随手打一个名字"：任务表存的是用户 id（不是姓名），而打错的字在账号表里
	 * 一个都查不到 —— 那样一条任务在界面上与正常任务长得一模一样，只是永远没有对应的人。
	 * 判据落在服务层而不是实体里，是因为它要读账号表。
	 *
	 * <p>返回值取账号表里那一行本身（id + 姓名）：落库的是它的 id，写进来的 "Admin" 与
	 * 回执里的姓名都按它那一行的写法（于是"这个名字到底指谁"不需要读的人再猜一次大小写）。
	 *
	 * <p>停用的账号<b>不算错</b>：这一列回答的是"当时是谁派的、派给谁"，
	 * 与"他现在能不能登录"是两件事（与 {@code agent_user_role} 的成员关系同一条规矩）。
	 */
	private Account realUser(String name, String what) {
		if (name == null || name.isBlank()) {
			throw new TaskInvalidException(what + "必填 —— 发布人与任务人都要从用户列表里选一个真实用户"
					+ "（搜索：GET /api/permissions/users/search?keyword=…）");
		}
		String wanted = name.strip();
		return permissions.findAccount(wanted)
				.orElseThrow(() -> new TaskInvalidException(("账号表里没有「%s」这个人，不能当%s —— 发布人与任务人"
						+ "都是真实用户；名字要从用户列表里搜出来选（GET /api/permissions/users/search?keyword=…），"
						+ "打错一个字不会有任何约束拦下这条任务，它只会挂在一个不存在的人名下。")
						.formatted(wanted, what)));
	}

	/** 日志里的姓名：账号那一行不在（姓名落空）时退回 id，而不是留一格空白。 */
	private static String named(String name, Long id) {
		return name == null ? "用户#" + id : name;
	}

	/** 按 id 取确认项，并核对它属于这条任务（拿别的任务的 confirmId 来答会被这里挡下）。 */
	private AgentTaskConfirm requireConfirm(Long taskId, Long confirmId) {
		if (confirmId == null) {
			throw new TaskInvalidException("缺少确认项 id");
		}
		MapperRows.AgentTaskConfirmRow row = confirms.findById(confirmId);
		if (row == null || !taskId.equals(row.taskId())) {
			throw new TaskInvalidException("确认项不存在或不属于这条任务：确认项 #" + confirmId + "、任务 #" + taskId);
		}
		return row.toDomain();
	}

}
