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
 * 人做的动作在这里（答复确认项、验收、重发、取消），agent 做的动作在
 * {@code DefaultAgentTaskExecution}；两边共用的写入原语在 {@link AgentTaskTransitions}。
 * 界面的按钮与接口返回的 {@code actions} 都从 {@link #actionsOf} 算出来 ——
 * 界面上再写一遍判断的后果是两份判据迟早不一致，而它们不一致时的现象是
 * "点得下去、点完报错"（用户会以为是自己操作错了）。
 *
 * <h2>验收为什么是两个动作（{@link #accept} / {@link #reject}）</h2>
 * 那一格的两个落点后续要做的事不同：一个触发"把这次问过、答过的汇成规则"、
 * 一个要记下未完成原因。合成一个带布尔的动作之后，"这次点的是哪一个"
 * 只能靠请求体里有没有 reason 去猜，而猜错是静默的（一次"没做成"被记成"做成了"）。
 *
 * <h2>每个写动作是一个事务</h2>
 * 状态推进与确认项生成必须同生共死：只改了状态没生成确认项，
 * 那条任务就卡在"待确认需求"而没有任何东西可答（界面上永远是待办、永远点不动）。
 * 这条链上会生成确认项的现在是任务执行器（见 {@code AgentTaskExecution}），
 * 所以本类的 {@code @Transactional} 保的是"答复一次确认项"这件事的原子性。
 *
 * <h2>发布人 / 任务人必须是真实账号</h2>
 * 判据在 {@code realUser}（两个重载：按登录名的那一份与按用户 id 的那一份），
 * 它落在服务层而不是实体的构造器里：那条判据要读账号表，
 * 而实体的构造点里有一个是"从库里读回一行"。
 * 两个入口给的写法不同 —— "人在界面上发布"给的是两个<b>登录名</b>（那两格是搜索之后
 * 从下拉里选的，不是可以随便打字的输入框），"定时规则派活"给的是规则里存下来的两个
 * <b>用户 id</b>（见 {@link AgentTaskService.Draft#scheduled}）。两条路都核一遍账号表，
 * 于是落库的发布人 / 任务人一定是账号表里真实存在的那一行（库里存 id，姓名不落库）。
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

	/**
	 * 「验收通过之后把这次问过的、答过的汇成规则写进技能」那一件事（见 {@code TaskRuleDigest}）。
	 *
	 * <p>它由验收触发、但<b>不在验收的响应路径上</b>：那个类挂在事务提交之后、
	 * 在别的线程上调模型，失败只记日志。所以这里不接它的任何异常，也不读它的任何返回值。
	 */
	private final TaskRuleDigest digests;

	public DefaultAgentTaskService(AgentTaskMapper tasks, AgentTaskConfirmMapper confirms,
			AgentTaskTransitions transitions, AgentTaskDispatch dispatch, PermissionService permissions,
			TaskRuleDigest digests) {
		this.tasks = tasks;
		this.confirms = confirms;
		this.transitions = transitions;
		this.dispatch = dispatch;
		this.permissions = permissions;
		this.digests = digests;
		verifyColumnsCoverAllStatuses();
		verifyPartyCoverAllStatuses();
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

	/**
	 * 启动期自检：<b>每一个状态都必须答得出"这件事该由谁在自己的列表里看见"</b>。
	 *
	 * <p>{@link AgentTaskStatus#party()} 是界面上那张看板的收件人判据（见 {@code board}），
	 * 它是一个 switch 表达式，编译器保证"每个取值都有分支"；这里问的是另一半 ——
	 * <b>三组加起来是不是全部</b>。多算一格（同一个状态落在两组里）会让同一条任务在
	 * 两张列表里都出现，而"它到底归谁"变成一件说不清的事；少算一格则相反：
	 * 那条任务在**谁的列表里都不出现**，而"看不到"与"它本来就不该出现"长得一模一样。
	 */
	private static void verifyPartyCoverAllStatuses() {
		int counted = 0;
		for (AgentTaskStatus.Party party : AgentTaskStatus.Party.values()) {
			counted += AgentTaskStatus.withParty(party).size();
		}
		if (counted != AgentTaskStatus.values().length) {
			throw new IllegalStateException(("按人取任务的收件人自检没过：%d 个状态里只有 %d 个落在某一组上 —— "
					+ "多算会让同一条任务出现在两张列表里，少算会让它在谁的列表里都不出现。"
					+ "改状态链时要在 AgentTaskStatus#party 里给它一个归属。")
					.formatted(AgentTaskStatus.values().length, counted));
		}
		log.info("任务看板的收件人自检完成：{} 个状态各归一组（发布人或任务人 {} 个 / 只有发布人 {} 个 / 只有任务人 {} 个）",
				AgentTaskStatus.values().length,
				AgentTaskStatus.withParty(AgentTaskStatus.Party.BOTH).size(),
				AgentTaskStatus.withParty(AgentTaskStatus.Party.PUBLISHER).size(),
				AgentTaskStatus.withParty(AgentTaskStatus.Party.ASSIGNEE).size());
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public Board board(Long userId) {
		List<MapperRows.AgentTaskRow> rows = userId == null ? tasks.list() : tasks.listForUser(
				person(userId).id(),
				storedValues(AgentTaskStatus.withParty(AgentTaskStatus.Party.BOTH)),
				storedValues(AgentTaskStatus.withParty(AgentTaskStatus.Party.PUBLISHER)),
				storedValues(AgentTaskStatus.withParty(AgentTaskStatus.Party.ASSIGNEE)));
		List<AgentTask> all = rows.stream().map(MapperRows.AgentTaskRow::toDomain).toList();
		List<BoardColumn> columns = new ArrayList<>();
		for (Column column : Column.values()) {
			List<AgentTask> inColumn = all.stream().filter(task -> column.statuses().contains(task.status())).toList();
			columns.add(new BoardColumn(column.key(), column.label(),
					column.statuses().stream().map(AgentTaskStatus::label).toList(), inColumn));
		}
		return new Board(List.copyOf(columns));
	}

	/** 状态名（库里存的那一份）—— 按人取任务的那条查询用它拼三组状态。 */
	private static List<String> storedValues(List<AgentTaskStatus> statuses) {
		return statuses.stream().map(AgentTaskStatus::storedValue).toList();
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
		// 这一支收的是登录名（界面那两格给的是搜索候选里的名字），在这里换成账号表里的 id；
		// 换不到时当场 400。"定时规则派活"那一支手上只有 id，直接进下面那个写入口
		Account from = realUser(publisher, "发布人");
		Account to = realUser(assignee, "任务人");
		return create(from.id(), to.id(), description, null);
	}

	/**
	 * 建一条任务的<b>唯一写入口</b>（人在界面上发布、定时规则到点派活都走这里）。
	 *
	 * <p>两条路在这一层只差一件事：{@code draft.scheduled()} 时多写一次
	 * {@code agent_task.schedule_id}（{@code AgentTaskTransitions#setSchedule}）——
	 * 状态、步骤、当前执行人、后续整条链完全一致。这正是"定时派出来的活与人发的活
	 * 在系统里没有行为差异"这句话的落点：差异只在<b>由来</b>那一列。
	 *
	 * <p>两个"人"在这一层是<b>用户 id</b>（{@link AgentTaskService.Draft} 上就是它们），
	 * 核账号表那一步仍然做 —— 不在账号表里的 id 当场 400，而不是落一条
	 * 永远没有对应的人的任务。
	 *
	 * <p>写由来是<b>单独一步</b>（不是 insert 的一个参数）：那条 SQL 的每一个调用点
	 * 都是"人在界面上发布"，并进去就等于每个调用点都要显式传一个 null。
	 */
	@Override
	@Transactional
	public Detail create(AgentTaskService.Draft draft) {
		return create(draft.publisherId(), draft.assigneeId(), draft.description(), draft.scheduleId());
	}

	/**
	 * 建一条任务：两个"人"是用户 id，第四格是"这次是谁派来的"（人在界面上发布时是 {@code null}）。
	 *
	 * <p>两条入口（名字那一支 / 草稿那一支）都落到这里，于是"状态、步骤、当前执行人、
	 * 由来那一列怎么落"只有一份实现；而 {@code scheduleId == null} 与
	 * {@code draft.scheduled()} 是同一个判断的两种写法（见 {@link AgentTaskService.Draft}）。
	 */
	private Detail create(Long publisherId, Long assigneeId, String description, Long scheduleId) {
		AgentTask task = draft(realUser(publisherId, "发布人"), realUser(assigneeId, "任务人"), description);
		tasks.insert(task.publisherId(), task.assigneeId(), task.description(), task.status().storedValue(),
				task.currentExecutorId(), task.currentStep());
		Long id = AgentTaskTransitions.requiredKey(tasks.lastInsertId(), "任务");
		log.info("新建 agent 任务 #{}：{}{}", id, task.describe(),
				scheduleId == null ? "（人在界面上发布）" : "（由定时任务规则 #" + scheduleId + " 派出）");
		if (scheduleId != null) {
			// ★ 传的是**刚拿到的主键**，不是 `task.id()`：`task` 是一份"入库之前"的记录，
			// 它的 id 是 null（id 由库生成）—— 拿它去 update 就是 `where id = null`，
			// **影响 0 行**，于是报"任务不存在"、整个事务回滚，而任务其实已经插进去了。
			// 实测踩过（见 AgentTaskTransitions#setSchedule 的注释，那里为此改了签名）
			transitions.setSchedule(id, scheduleId);
		}
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
			default -> throw new TaskConflictException(("当前状态「%s」没有可推进的事件 —— 能推进的只有"
					+ "「执行中」（登记执行结果）；「待验收」有两个落点，走的是 accept（「任务已完成」）"
					+ "与 reject（「任务未完成」）那两个动作，不是这里；"
					+ "两个「待确认」在等确认项的答复、「权限不足」要答复的是 revise（「已修改任务」/「取消任务」），"
					+ "「新发布」与两个「已确认」由 agent 任务执行处理（页面上没有按钮就是还没轮到人），"
					+ "终态不再流转。详情里的 actions 就是现在能做的事。")
					.formatted(task.status().label()));
		};
		dispatchAfterCommit(reached);
		return detail(id);
	}

	/**
	 * 验收通过：待验收 → 已完成。完成之后另触发一次"把这次问过、答过的汇成规则"（见 {@code digests}）。
	 *
	 * <p>两件事的顺序是刻意的：**先把验收落定，再补充规则**。反过来做的话，
	 * 模型调不通会让用户点不动「任务已完成」—— 而"这条活做完了"是他自己看到的事实，
	 * 不该由一次辅助调用挡住（补充失败只表现为技能里没有新规则，日志里有原因）。
	 */
	@Override
	@Transactional
	public Detail accept(Long id) {
		AgentTask task = transitions.require(id);
		if (task.status() != AgentTaskStatus.PENDING_ACCEPTANCE) {
			throw new TaskConflictException(("当前状态「%s」不能验收 —— 能验收的只有「待验收」那一格；"
					+ "「执行中」要先「登记执行结果」把它推到待验收、终态不再流转、"
					+ "两个「待确认」在等确认项的答复。详情里的 actions 就是现在能做的事。")
					.formatted(task.status().label()));
		}
		AgentTaskStatus reached = transitions.move(task, AgentTaskStatus.COMPLETED, task.currentStep() + 1,
				AgentTaskTransitions.executorFor(AgentTaskStatus.COMPLETED, task));
		log.info("任务 #{} 验收通过：已完成（第 {} 步）", id, task.currentStep() + 1);
		// 确认项要在这里读出来交给补充那一件事：它跑在事务提交之后、另一个线程上，
		// 而那条线程手上没有这份详情（它只会按 id 再查一次任务，不再查确认项 —— 少一次读）
		digests.digestAfterCommit(task, confirms.listByTask(id).stream()
				.map(MapperRows.AgentTaskConfirmRow::toDomain)
				.toList());
		return detail(id);
	}

	/**
	 * 任务未完成：待验收 → 未完成。原因<b>选填</b>。
	 *
	 * <p>先落原因再挪状态（与 {@code revise} 里"先落描述再挪状态"同一条）：原因超长时当场 400、
	 * 状态一动不动；反过来先挪状态的话，一次参数错的答复会留下一条"已经是任务未完成、
	 * 原因却没写上"的任务，而它在卡片上看起来完全正常。
	 *
	 * <p>它是终态，所以不触发经验补充（{@code accept} 才触发）：没做成的任务能总结出的是
	 * "这次为什么没做成"，而那属于 {@link AgentTask#incompleteReason()} 那一格，
	 * 把它写进技能会让下一次不再问 —— 而那次可能只是这一次没条件做。
	 */
	@Override
	@Transactional
	public Detail reject(Long id, String reason) {
		AgentTask task = transitions.require(id);
		if (task.status() != AgentTaskStatus.PENDING_ACCEPTANCE) {
			throw new TaskConflictException(("当前状态「%s」不能记成未完成 —— 能选这一格的只有「待验收」；"
					+ "要中止一条还没到验收的任务请用「取消任务」。详情里的 actions 就是现在能做的事。")
					.formatted(task.status().label()));
		}
		String checked = incompleteReason(reason);
		if (checked != null && tasks.updateIncompleteReason(id, checked) != 1) {
			throw new TaskInvalidException("任务不存在：#" + id);
		}
		transitions.move(task, AgentTaskStatus.NOT_COMPLETED, task.currentStep() + 1,
				AgentTaskTransitions.executorFor(AgentTaskStatus.NOT_COMPLETED, task));
		log.info("任务 #{} 验收记为「任务未完成」（第 {} 步）{}", id, task.currentStep() + 1,
				checked == null ? "（没写原因）" : "：" + checked);
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
				("当前状态「%s」不在等确认项的答复 —— 能答复的只有「待确认需求」/「待确认计划」；"
						+ "「执行中」与「待验收」要推进请用 advance 那个动作；"
						+ "「新发布」与两个「已确认」由 agent 任务执行处理，人和 advance 都推不动它。")
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
		// （两个落点都是"等 agent 动手"的状态，生成下一条确认项的是任务执行器 —— 它要按答复内容判断）
		AgentTaskStatus nextStatus = switch (waiting) {
			case REQUIREMENT -> transitions.enterAgent(task, AgentTaskStatus.CONFIRMED_REQUIREMENT);
			case PLAN -> transitions.enterAgent(task, AgentTaskStatus.CONFIRMED_PLAN);
			// 授权不由人答（判断在平台那一边，见 AgentTaskStatus 的状态链），所以这里没有它的分支
			case AUTHORIZATION -> throw new TaskConflictException(
					"授权确认项已经不由人答复了 —— 授权由平台按任务人角色的目录前缀判（判过就开跑）；"
							+ "这条记录产生于旧版本，它的处置是取消这条任务后重新发布。");
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
	 * <p>用户改过的描述与这次答复<b>同一次写入</b>：先落描述、再挪状态（同一个事务）。
	 * 顺序上先落描述是刻意的 —— 判据不过（空 / 超长）时当场报 400，状态一动不动；
	 * 反过来先挪状态的话，一次参数错的答复会留下一条"已经回到新发布、描述还是旧的"的任务。
	 *
	 * <p>步骤 +1（这一格也是"经过了一格"），当前执行人回到任务人
	 * （{@code executorFor(NEW, task)}：球在他手上，由他决定要不要往下走）。
	 *
	 * <p>上一次「权限不足」的结论<b>留在执行结果那一格里</b>，前面加一句说明：
	 * 它是"当时为什么被打回"的唯一记录，而重走一遍之后这个理由不该只剩"已取消 / 已完成"。
	 */
	@Override
	@Transactional
	public Detail revise(Long id, String description) {
		AgentTask task = transitions.require(id);
		if (task.status() != AgentTaskStatus.INSUFFICIENT_PERMISSION) {
			throw new TaskConflictException(("当前状态「%s」不是「权限不足」，没有可重发的任务 —— "
					+ "「已修改任务」只在权限不足时出现（权限那一关过不了，改了之后再重走一遍）；"
					+ "详情里的 actions 就是现在能做的事。").formatted(task.status().label()));
		}
		transitions.setDescription(task, description);
		String previous = task.result() == null ? "" : task.result().strip();
		AgentTaskStatus reached = transitions.move(task, AgentTaskStatus.NEW, task.currentStep() + 1,
				AgentTaskTransitions.executorFor(AgentTaskStatus.NEW, task));
		transitions.setResult(task, "用户答复「已修改任务」：这条任务已回到「新发布」重新走一遍"
				+ "（按这一份任务描述重新判需求 → 拆任务点 → 判授权）。"
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

	/**
	 * 未完成原因的判据 —— <b>选填</b>，写了的才过上限。
	 *
	 * <p>空值归成 {@code null} 而不是空串：与 {@code AgentTask} 的紧凑构造器同一套归一，
	 * 库里"没写原因"因此只有一种形态（NULL），卡片上据此不画那一行。
	 *
	 * @return 过完判据的那一份；没写时返回 {@code null}
	 */
	private static String incompleteReason(String reason) {
		if (reason == null || reason.isBlank()) {
			return null;
		}
		String checked = reason.strip();
		if (checked.length() > AgentTask.MAX_INCOMPLETE_REASON_CHARS) {
			throw new TaskInvalidException("未完成原因过长（%d 字符 > 上限 %d）"
					.formatted(checked.length(), AgentTask.MAX_INCOMPLETE_REASON_CHARS));
		}
		return checked;
	}

	/**
	 * 现在能做的动作。它是"界面上该画哪些按钮"的唯一依据
	 * （与 {@link #advance} / {@link #accept} / {@link #reject} / {@link #answer} / {@link #cancel}
	 * 允许的状态一一对应）。
	 *
	 * <p><b>「新发布」与两个「已确认」没有推进按钮</b>：那三格的下一步由任务执行器做，
	 * 人点不动。给它们画一个"看起来能推进"的按钮比不画更坏 —— 用户会反复点，
	 * 而每次都回一句"当前状态不允许"。
	 *
	 * <p>「待验收」给两个动作、「执行中」给一个：验收那一格的<b>两个</b>落点（做成 / 没做成）
	 * 是发布人当场要做的判断，所以两个按钮一起摆出来；而"登记执行结果"只是一件事，
	 * 它没有第二种结局（跑失败停在执行中也是它）。
	 *
	 * <p>「权限不足」两个按钮：{@code revise}（「已修改任务」= 重新走一遍）与 {@code cancel}。
	 * 没有 {@code advance}：这一格要的不是"推进当前事件"，而是"这条任务已经改过了"。
	 */
	private static List<Action> actionsOf(AgentTaskStatus status) {
		return switch (status) {
			case NEW -> List.of(new Action("cancel", "取消任务"));
			case PENDING_REQUIREMENT, PENDING_PLAN ->
					List.of(new Action("answer", "答复确认项"), new Action("cancel", "取消任务"));
			case INSUFFICIENT_PERMISSION ->
					List.of(new Action("revise", "已修改任务"), new Action("cancel", "取消任务"));
			case CONFIRMED_REQUIREMENT, CONFIRMED_PLAN -> List.of(new Action("cancel", "取消任务"));
			case EXECUTING -> List.of(new Action("advance", "登记执行结果"), new Action("cancel", "取消任务"));
			case PENDING_ACCEPTANCE -> List.of(new Action("accept", "任务已完成"),
					new Action("reject", "任务未完成"), new Action("cancel", "取消任务"));
			case COMPLETED, NOT_COMPLETED, CANCELLED -> List.of();
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
	 * <p>它只在"人在界面上发布"那一条路上用（那两格是搜索选择器，值就是候选里的登录名）——
	 * 写入口自己收的是用户 id，见 {@link #realUser(Long, String)}。
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

	/**
	 * 按用户 id 取人 —— 与 {@link #realUser(String, String)} 同一条判据的另一半。
	 *
	 * <p>定时规则派活那条路给的就是用户 id（它从库里读出来的两列），所以这一层按 id 核账号表：
	 * 那一行不在时当场 400 / 记一条 WARN，而不是落一条永远没有对应的人的任务 ——
	 * 那种任务在界面上与正常任务长得一模一样。
	 *
	 * <p>与按名字那一支一样<b>不接受 null</b>：这一列在两个写入路径上都是必填的
	 * （{@code agent_task.publisher_id} 是 NOT NULL）。
	 */
	private Account realUser(Long userId, String what) {
		if (userId == null) {
			throw new TaskInvalidException("这条任务的" + what + "没有用户 id —— "
					+ "发布人与任务人在落库时都是必填的账号 id（不是姓名）");
		}
		return permissions.findAccountById(userId).orElseThrow(() -> new TaskInvalidException(
				("账号表里没有 id=%d 这个用户，不能当%s —— 发布人与任务人都是账号表里真实存在的人。")
						.formatted(userId, what)));
	}

	/**
	 * 按 id 取人（界面上传进来的"当前登录的人"）—— 与 {@link #realUser(Long, String)} 同一条判据，
	 * 只有报错文案指向的下一步不同。
	 *
	 * <p>走的同样是一次真实查询，于是"这个 id 根本不在账号表里"会当着用户的面报 400：
	 * 那种情况来自本地存着的一条过期的登录记录（账号后来被清掉了），而**两种猜测都是错的** ——
	 * 当成没登录会给他看全量任务（他以为看到的是自己的），当成筛不出来会给他一张空看板
	 * （看起来像"我没有任务"）。两句话都要由这里说出来。
	 */
	private Account person(Long userId) {
		return permissions.findAccountById(userId).orElseThrow(() -> new TaskInvalidException(
				("账号表里没有 id=%d 这个人 —— 界面上传的是「当前登录的人」，他不在账号表里就取不出"
						+ "「与他有关」的任务。本地存着的那条登录记录已经过期，重新登录一次。")
						.formatted(userId)));
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
