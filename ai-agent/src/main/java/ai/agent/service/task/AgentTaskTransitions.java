package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

/**
 * agent 任务状态链的<b>写入原语</b> —— 状态怎么换、确认项怎么生成，只有这一份。
 *
 * <h2>为什么它不是 {@link DefaultAgentTaskService} 的私有方法</h2>
 * 推进这条链的现在有<b>两方</b>：人（界面上答复确认项、验收、取消）与任务执行器
 * （判断歧义、拆任务点、执行）。两方各自写一份"换状态 + 生成确认项"的后果很具体：
 * 同一次流转在两条路径上落出不同的步骤号或不同的确认项类型，
 * 而那种错的形态是"确认项问的是需求、任务却已经走到授权"—— 没有任何报错。
 * 所以这一层抽出来给两方共用，{@link DefaultAgentTaskService} 与
 * {@code DefaultAgentTaskExecution} 都只调它。
 *
 * <h2>每个方法是一个事务</h2>
 * 状态推进与确认项生成必须同生共死：只改了状态没生成确认项，
 * 那条任务就卡在"待确认需求"而没有任何东西可答（界面上永远是待办、永远点不动）。
 * 外层已经有事务时（服务层的写动作）这些方法<b>并进去</b>，不另起一个。
 */
@Component
public class AgentTaskTransitions {

	private static final Logger log = LoggerFactory.getLogger(AgentTaskTransitions.class);

	private final AgentTaskMapper tasks;

	private final AgentTaskConfirmMapper confirms;

	public AgentTaskTransitions(AgentTaskMapper tasks, AgentTaskConfirmMapper confirms) {
		this.tasks = tasks;
		this.confirms = confirms;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	/** 按 id 取任务，取不到就报 400（"任务不存在"是调用方的输入问题，不是服务器故障）。 */
	public AgentTask require(Long id) {
		if (id == null) {
			throw new TaskInvalidException("缺少任务 id");
		}
		MapperRows.AgentTaskRow row = tasks.findById(id);
		if (row == null) {
			throw new TaskInvalidException("任务不存在：#" + id);
		}
		return row.toDomain();
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	/** 换状态、当前执行人与步骤（同一条 SQL，理由见 {@code AgentTaskMapper#updateProgress}）。 */
	@Transactional
	public AgentTaskStatus move(AgentTask task, AgentTaskStatus to, int step, String executor) {
		if (tasks.updateProgress(task.id(), to.storedValue(), executor, step) != 1) {
			throw new TaskInvalidException("任务不存在：#" + task.id());
		}
		log.info("任务 #{}：{} → {}（第 {} 步，当前执行人 {}）", task.id(), task.status().label(), to.label(), step,
				executor == null ? "(无)" : executor);
		return to;
	}

	/**
	 * 进入一个"等 agent 动手"的状态：步骤 +1，当前执行人换成 agent。
	 *
	 * <p>执行人由 {@link #executorFor} 算，不在调用点拼 —— 两处各拼一次的后果是
	 * 某一条链上写着任务人的名字，而那一格明明在等 agent。
	 */
	@Transactional
	public AgentTaskStatus enterAgent(AgentTask task, AgentTaskStatus to) {
		return move(task, to, task.currentStep() + 1, AgentTask.agentRef(task.agentId()));
	}

	/**
	 * 进入一个"等确认项答复"的状态：步骤 +1，生成那一步的确认项。
	 *
	 * @param description 确认事项正文（由调用方给：人手上是缺省文案，执行器手上是模型问的那件事）
	 */
	@Transactional
	public AgentTaskStatus enterPending(AgentTask task, AgentTaskStatus pending, ConfirmType type, String description) {
		int nextStep = task.currentStep() + 1;
		AgentTaskStatus moved = move(task, pending, nextStep, executorFor(pending, task));
		openConfirmation(task, type, nextStep, description);
		return moved;
	}

	/**
	 * 「新发布 → 待确认需求」：<b>步骤不递增</b>，生成第 1 条需求确认项。
	 *
	 * <p>它是唯一不递增的一格：新发布与待确认需求都停在第 1 步，后者表示"第 1 步已经开始了
	 * （确认项已发出）"，而不是"进了第 2 步"。为此单开一个方法，而不是给
	 * {@link #enterPending} 加一个布尔参数 —— 那个布尔在调用点上读起来毫无信息量。
	 */
	@Transactional
	public AgentTaskStatus openFirstConfirmation(AgentTask task, String description) {
		int step = task.currentStep();
		AgentTaskStatus moved = move(task, AgentTaskStatus.PENDING_REQUIREMENT, step,
				executorFor(AgentTaskStatus.PENDING_REQUIREMENT, task));
		openConfirmation(task, ConfirmType.REQUIREMENT, step, description);
		return moved;
	}

	/** 生成一条确认项。选项集取这一种确认的缺省按钮（{@link #defaultOptions}）。 */
	private void openConfirmation(AgentTask task, ConfirmType type, int step, String description) {
		try {
			confirms.insert(task.id(), step, type.storedValue(), description,
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

	/** 登记执行结果（{@code 执行中 → 待验收} 那一步，以及任务执行器跑完之后）。 */
	@Transactional
	public void setResult(AgentTask task, String result) {
		if (result == null || result.isBlank()) {
			return;
		}
		tasks.updateResult(task.id(), result.strip());
	}

	// ------------------------------------------------------------------
	//  共用判据
	// ------------------------------------------------------------------

	/**
	 * <b>「这一格球在谁手上」的唯一判据</b>。
	 *
	 * <p>由状态决定，不由调用方传：让调用点自己拼的话，同一条链上会出现
	 * "状态已经变了、执行人还没变"的中间态 —— 一张卡片的两个字段各说一套，
	 * 没人能从界面上看出哪一个是旧的。
	 *
	 * <p>五种落点，各自的含义：
	 * <ul>
	 *   <li>{@link AgentTaskStatus#NEW} —— 任务人（没指派时是发布人），球在他手上，由他决定要不要往下走；</li>
	 *   <li>三个"待确认"与"待验收" —— 发布人（等他答复 / 等他验收）；</li>
	 *   <li>三个"已确认"与"执行中" —— agent（形如 {@code agent#3}，没配 agent 时是 {@code agent}）；</li>
	 *   <li>{@link AgentTaskStatus#COMPLETED} —— 没有执行人（没有事件在谁手上了），
	 *       留着名字会让那张卡片读起来像还卡着；</li>
	 *   <li>{@link AgentTaskStatus#CANCELLED} —— 由取消那条路单独处理（保持不动，见 {@code cancel}）。</li>
	 * </ul>
	 */
	public static String executorFor(AgentTaskStatus status, AgentTask task) {
		return switch (status) {
			case NEW -> task.assignee() == null ? task.publisher() : task.assignee();
			case PENDING_REQUIREMENT, PENDING_PLAN, PENDING_AUTHORIZATION, PENDING_ACCEPTANCE -> task.publisher();
			case CONFIRMED_REQUIREMENT, CONFIRMED_PLAN, AUTHORIZED, EXECUTING -> AgentTask.agentRef(task.agentId());
			case COMPLETED -> null;
			case CANCELLED -> task.currentExecutor();
		};
	}

	/**
	 * 三种确认各配一组按钮 —— 它们同时是"选项集"这一列的真实内容（JSON 数组）。
	 *
	 * <p>需求那一条的按钮与另外两条不同：它的"否"是<b>补充说明</b>而不是拒绝 ——
	 * 需求有歧义时该发生的是"再问一轮"，不是把任务毙掉。
	 */
	public static List<String> defaultOptions(ConfirmType type) {
		return switch (type) {
			case REQUIREMENT -> List.of("确认", "需要补充");
			case PLAN -> List.of("同意", "不同意");
			case AUTHORIZATION -> List.of("授权", "拒绝");
		};
	}

	/**
	 * 插入之后必须拿到新主键 —— 与 {@code DefaultPermissionService#requiredKey} 同一条判断
	 * （拿不到的后果是"创建成功、返回的 id 是 null"，而不是这次失败）。
	 */
	static Long requiredKey(Long id, String what) {
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
	static boolean hasDuplicateSqlState(Throwable root) {
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
