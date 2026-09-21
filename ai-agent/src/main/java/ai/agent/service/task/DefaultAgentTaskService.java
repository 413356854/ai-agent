package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.AgentTaskStatus;
import ai.agent.entity.agent.task.ConfirmOptions;
import ai.agent.entity.agent.task.ConfirmType;
import ai.agent.mapper.AgentTaskConfirmMapper;
import ai.agent.mapper.AgentTaskMapper;
import ai.agent.mapper.MapperRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AgentTaskService} 的实现 —— <b>任务状态链唯一的写入口</b>。
 *
 * <h2>状态机只有这一份</h2>
 * "什么状态能做什么、做完到哪、生成哪条确认项、执行人换成谁"全部落在下面这几个方法里，
 * 界面的按钮与接口返回的 {@code actions} 都是从这里算出来的（{@link #actionsOf}）。
 * 界面上再写一遍判断的后果是两份判据迟早不一致，而它们不一致时的现象是
 * "点得下去、点完报错" —— 用户会以为是自己操作错了。
 *
 * <h2>每个写动作是一个事务</h2>
 * 状态推进与确认项生成必须同生共死：只改了状态没生成确认项，
 * 那条任务就卡在"待确认需求"而没有任何东西可答（界面上永远是待办、永远点不动）。
 *
 * <h2>脚手架缺省文案说清楚是缺省</h2>
 * 三条确认事项的正文由 {@link #defaultDescription} 生成，其中计划与授权两条是**占位**——
 * 它们将来应由 Agent 按任务生成。文案里写明这一点，是为了不让"这条确认项说的是什么"
 * 被当成系统真的想清楚了：占位文本读起来同样通顺，而这正是它危险的地方。
 */
@Service
public class DefaultAgentTaskService implements AgentTaskService {

	private static final Logger log = LoggerFactory.getLogger(DefaultAgentTaskService.class);

	private final AgentTaskMapper tasks;

	private final AgentTaskConfirmMapper confirms;

	public DefaultAgentTaskService(AgentTaskMapper tasks, AgentTaskConfirmMapper confirms) {
		this.tasks = tasks;
		this.confirms = confirms;
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
		AgentTask task = require(id);
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
	public Detail create(String publisher, String assignee, String description, Long agentId) {
		AgentTask task = draft(publisher, assignee, description, agentId);
		tasks.insert(task.publisher(), task.assignee(), task.description(), task.status().storedValue(),
				task.currentExecutor(), task.currentStep(), task.agentId());
		Long id = requiredKey(tasks.lastInsertId(), "任务");
		log.info("新建 agent 任务 #{}：{}", id, task.describe());
		return detail(id);
	}

	@Override
	@Transactional
	public Detail advance(Long id, String result) {
		AgentTask task = require(id);
		switch (task.status()) {
			case NEW -> openFirstConfirmation(task);
			case EXECUTING -> finishExecution(task, result);
			case PENDING_ACCEPTANCE -> accept(task);
			default -> throw new TaskConflictException(("当前状态「%s」没有可推进的事件 —— 能推进的只有"
					+ "「新发布」（发起需求确认）、「执行中」（登记执行结果）与「待验收」（验收通过）；"
					+ "三个「待确认」在等确认项的答复，终态不再流转。详情里的 actions 就是现在能做的事。")
					.formatted(task.status().label()));
		}
		return detail(id);
	}

	@Override
	@Transactional
	public Detail answer(Long id, Long confirmId, String text) {
		if (text == null || text.isBlank()) {
			throw new TaskInvalidException("答复不能为空 —— 空答复在库里与「还没答」（answer 为 NULL）"
					+ "长得一样，而两者的处置完全不同：前者要推进状态，后者要继续等");
		}
		AgentTask task = require(id);
		AgentTaskStatus status = task.status();
		ConfirmType waiting = status.waitingConfirmation().orElseThrow(() -> new TaskConflictException(
				("当前状态「%s」不在等确认项的答复 —— 能答复的只有「待确认需求」/「待确认计划」/「待授权」；"
						+ "要推进请用 advance 那个动作。").formatted(status.label())));
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

		// 一步一跳的落点写成 switch 表达式，于是"到哪个状态"与"生成哪条确认项"在同一个分支里 ——
		// 分开写的两份判据迟早会出现"等计划确认、却生成了授权确认"这种对不上的组合
		AgentTaskStatus nextStatus = switch (waiting) {
			case REQUIREMENT -> enterConfirmation(task, AgentTaskStatus.PENDING_PLAN, ConfirmType.PLAN);
			case PLAN -> enterConfirmation(task, AgentTaskStatus.PENDING_AUTHORIZATION, ConfirmType.AUTHORIZATION);
			case AUTHORIZATION -> move(task, AgentTaskStatus.EXECUTING, task.currentStep() + 1,
					AgentTask.agentRef(task.agentId()));
		};
		log.info("任务 #{} 的{}已答复（{}）：这次答复把它推到了「{}」", task.id(), waiting.label(), text.strip(),
				nextStatus.label());
		return detail(id);
	}

	@Override
	@Transactional
	public Detail cancel(Long id, String reason) {
		AgentTask task = require(id);
		if (reason == null || reason.isBlank()) {
			throw new TaskInvalidException("取消原因必填 —— 一条没有原因的取消记录，事后只能靠回忆"
					+ "（与「谁批的」同一条规矩：那一列空着等于没有这条记录）");
		}
		if (task.status().terminal()) {
			throw new TaskConflictException("任务已经是「%s」，不能再取消".formatted(task.status().label()));
		}
		tasks.updateCancel(id, reason.strip());
		// 步骤与执行人保持不动：它们回答"取消的那一刻球在谁手上"，而这是复盘时要看的东西
		move(task, AgentTaskStatus.CANCELLED, task.currentStep(), task.currentExecutor());
		log.info("任务 #{} 已取消：{}", id, reason.strip());
		return detail(id);
	}

	// ------------------------------------------------------------------
	//  状态链上的一段一段
	// ------------------------------------------------------------------

	/** 新发布 → 待确认需求：生成第 1 步的需求确认项，球交到发布人手上。 */
	private void openFirstConfirmation(AgentTask task) {
		// 步骤**不**递增：新发布与待确认需求都停在第 1 步，后者表示"第 1 步已经开始了"
		move(task, AgentTaskStatus.PENDING_REQUIREMENT, task.currentStep(), task.publisher());
		openConfirmation(task, ConfirmType.REQUIREMENT, task.currentStep());
		log.info("任务 #{} 发起需求确认（第 {} 步）：现在等 {}", task.id(), task.currentStep(), task.publisher());
	}

	/** 执行中 → 待验收：登记执行结果（可空），球交回发布人。 */
	private void finishExecution(AgentTask task, String result) {
		int nextStep = task.currentStep() + 1;
		move(task, AgentTaskStatus.PENDING_ACCEPTANCE, nextStep, task.publisher());
		if (result != null && !result.isBlank()) {
			tasks.updateResult(task.id(), result.strip());
		}
		// 没登记结果也能进验收：真实链路里"跑完了但没产出"是常态，
		// 而把它挡在这里会让那条任务永远卡在执行中 —— 界面上表现为"它还在跑"，不报错
		log.info("任务 #{} 执行结束（第 {} 步）：现在等 {} 验收{}", task.id(), nextStep, task.publisher(),
				result == null || result.isBlank() ? "（未登记执行结果）" : "");
	}

	/** 待验收 → 已完成：验收通过。完成后没有"当前执行人"—— 没有事件在谁手上了。 */
	private void accept(AgentTask task) {
		move(task, AgentTaskStatus.COMPLETED, task.currentStep() + 1, null);
		log.info("任务 #{} 验收通过：已完成", task.id());
	}

	/**
	 * 答复之后进入下一个"待确认"：步骤 +1，生成那一步的确认项。
	 *
	 * <p>返回值就是 {@code next} —— 让调用点能把它写成 switch 表达式（见 {@link #answer}），
	 * 从而"落点"只写一遍。
	 */
	private AgentTaskStatus enterConfirmation(AgentTask task, AgentTaskStatus next, ConfirmType type) {
		int nextStep = task.currentStep() + 1;
		move(task, next, nextStep, task.publisher());
		openConfirmation(task, type, nextStep);
		log.info("任务 #{} 进入「{}」（第 {} 步）：现在等 {}", task.id(), next.label(), nextStep, task.publisher());
		return next;
	}

	/** 换状态、当前执行人与步骤（同一条 SQL，理由见 {@code AgentTaskMapper#updateProgress}）。返回值同 {@link #enterConfirmation}。 */
	private AgentTaskStatus move(AgentTask task, AgentTaskStatus to, int step, String executor) {
		if (tasks.updateProgress(task.id(), to.storedValue(), executor, step) != 1) {
			throw new TaskInvalidException("任务不存在：#" + task.id());
		}
		return to;
	}

	private void openConfirmation(AgentTask task, ConfirmType type, int step) {
		try {
			confirms.insert(task.id(), step, type.storedValue(), defaultDescription(task, type),
					ConfirmOptions.encode(defaultOptions(type)));
			requiredKey(confirms.lastInsertId(), "确认项");
		}
		catch (DataAccessException e) {
			// 唯一键（任务 + 步骤 + 类型）撞了：两条推进请求同时到了。
			// 把它翻成 409 而不是 500 —— 这不是故障，是"别人刚推过同一条任务"
			if (e instanceof DataIntegrityViolationException || hasDuplicateSqlState(e)) {
				throw new TaskConflictException("这一步的确认项已经有人生成过了（同一条任务被同时推进了两次）——"
						+ "刷新一次，看到的就是最新的那一条");
			}
			throw e;
		}
	}

	/**
	 * 三条确认事项的**缺省文案**（脚手架）。
	 *
	 * <p>需求那一条把任务描述原文带进去（它就是"要确认的那件事"）；计划与授权两条是占位，
	 * 将来应由 Agent 按任务生成 —— 文案里写明占位，理由见类注释。
	 */
	private static String defaultDescription(AgentTask task, ConfirmType type) {
		return switch (type) {
			case REQUIREMENT -> "请确认任务需求：" + task.description();
			case PLAN -> "请确认执行计划（脚手架缺省：计划正文待接入 Agent 按任务生成）";
			case AUTHORIZATION -> "请确认授权范围（脚手架缺省：授权正文待接入 Agent 按任务生成）";
		};
	}

	/** 三种确认各配一组按钮 —— 它们同时是"选项集"这一列的真实内容（JSON 数组）。 */
	private static List<String> defaultOptions(ConfirmType type) {
		return switch (type) {
			case REQUIREMENT -> List.of("确认", "需要补充");
			case PLAN -> List.of("同意", "不同意");
			case AUTHORIZATION -> List.of("授权", "拒绝");
		};
	}

	/**
	 * 现在能做的动作。它是"界面上该画哪些按钮"的唯一依据
	 * （与 {@link #advance} / {@link #answer} / {@link #cancel} 允许的状态一一对应）。
	 */
	private static List<Action> actionsOf(AgentTaskStatus status) {
		return switch (status) {
			case NEW -> List.of(new Action("advance", "发起需求确认"), new Action("cancel", "取消任务"));
			case PENDING_REQUIREMENT, PENDING_PLAN, PENDING_AUTHORIZATION ->
					List.of(new Action("answer", "答复确认项"), new Action("cancel", "取消任务"));
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
	private static AgentTask draft(String publisher, String assignee, String description, Long agentId) {
		try {
			return AgentTask.draft(publisher, assignee, description, agentId);
		}
		catch (IllegalArgumentException e) {
			throw new TaskInvalidException(e.getMessage());
		}
	}

	private AgentTask require(Long id) {
		if (id == null) {
			throw new TaskInvalidException("缺少任务 id");
		}
		MapperRows.AgentTaskRow row = tasks.findById(id);
		if (row == null) {
			throw new TaskInvalidException("任务不存在：#" + id);
		}
		return row.toDomain();
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

	/**
	 * 插入之后必须拿到新主键 —— 与 {@code DefaultPermissionService#requiredKey} 同一条判断
	 * （拿不到的后果是"创建成功、返回的 id 是 null"，而不是这次失败）。
	 */
	private static Long requiredKey(Long id, String what) {
		if (id == null) {
			throw new IllegalStateException("插入" + what + "之后没有拿到自增主键 —— "
					+ "SELECT LAST_INSERT_ID() 没有取到值（写法与实测的弯路见 AgentTaskMapper 的注释）。"
					+ "这一行已经写进库里了，但调用方拿不到它的 id");
		}
		return id;
	}

	/**
	 * 撞唯一键的判据，与 {@code DefaultPermissionService#hasDuplicateSqlState} 逐条相同：
	 * Spring 的异常翻译接住标准 SQLState，底层 {@link SQLException} 接住 H2 的 23000 与 MySQL 的 1062
	 * （这两个码不在 Spring 的"重复键"表里）。
	 */
	private static boolean hasDuplicateSqlState(Throwable root) {
		for (Throwable t = root; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				String state = sql.getSQLState();
				if ("23505".equals(state) || "23000".equals(state) || sql.getErrorCode() == 23505
						|| sql.getErrorCode() == 1062) {
					return true;
				}
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

}
