package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.AgentTaskStatus;

import java.util.List;

/**
 * agent 任务的读写契约（应用管理区 · agent任务）。
 *
 * <h2>四个写动作，各对应链上的一段</h2>
 * <table border="1">
 *   <caption>动作与它推着链走的那一格</caption>
 *   <tr><th>动作</th><th>允许的状态</th><th>之后到哪</th></tr>
 *   <tr><td>{@link #create}</td><td>—（产生一条新任务）</td><td>新发布</td></tr>
 *   <tr><td>{@link #advance}</td>
 *       <td>执行中 / 待验收</td>
 *       <td>待验收（登记执行结果，人工兜底）/ 已完成</td></tr>
 *   <tr><td>{@link #answer}</td><td>三个"待确认"</td><td>对应的"已确认"那一格（已确认需求 / 已确认计划 / 已授权）</td></tr>
 *   <tr><td>{@link #cancel}</td><td>任一非终态</td><td>已取消（写取消原因）</td></tr>
 * </table>
 *
 * <p><b>新发布、已确认需求、已确认计划、已授权这四格没有人工动作</b>：
 * 它们的球在 agent 手上，由 {@code AgentTaskExecution} 按状态推进（见
 * {@link AgentTaskStatus#awaitingAgent()}）。所以 {@link #advance} 不接受新发布 ——
 * "新发布 → 待确认需求"这一步现在由执行器先判断需求有没有歧义再决定，
 * 人工那条路会让同一条任务有两个不同的第一条确认项。
 *
 * <h2>为什么"推进"与"答复"是两个动作而不是一个</h2>
 * 回答一个问题（{@code answer}）与报告一件事发生了（{@code advance}）在链上填的格子不同：
 * 前者必须对上当步的那条确认项，后者没有对象可对。合成一个之后，
 * "这一条请求到底是答复还是推进"要靠请求体里有没有 confirmId 去猜 ——
 * 而猜错的形态是"答复被当成推进执行了"（一次静默的状态跳格）。
 *
 * <h2>为什么每个写动作返回 {@link Detail} 而不是一个 ok</h2>
 * 与 {@code PermissionController} 同一条取舍：返回 {@code {"ok":true}} 之后界面只能重新拉一次，
 * 而那次拉取与这次写入之间可能隔着别人的一次修改 —— 页面上显示的是"别人改完之后的样子"，
 * 而用户以为是自己这次的结果。返回新状态就没有这个缝。
 */
public interface AgentTaskService {

	/** 看板：全部任务按列分好（列的定义与顺序见 {@link Column}）。 */
	Board board();

	/** 一条任务的详情：任务 + 它的全部确认项 + 当前可做的动作。 */
	Detail detail(Long id);

	/**
	 * 发布一条任务。落库时状态是 {@link AgentTaskStatus#NEW}、步骤 1、当前执行人是任务人。
	 *
	 * <p>发布人与任务人<b>都是必填的真实用户</b>：两个名字都要在账号表里存在
	 * （界面上的做法是搜索后从下拉里选，见 {@code PermissionController#searchUsers}）——
	 * 任务记录存的是姓名快照，错字不会被任何数据库约束拦下。
	 *
	 * <p><b>没有 agentId 这个入参</b>：它由平台在第一次接手这条任务时写入
	 * （见 {@code AgentTaskProperties#agentId}）。
	 */
	Detail create(String publisher, String assignee, String description);

	/** 推进当前事件（允许的状态与落点见类注释的表格）。 */
	Detail advance(Long id, String result);

	/** 答复一条确认项；答完按状态链推进并生成下一条确认项。 */
	Detail answer(Long id, Long confirmId, String answer);

	/** 取消任务。取消原因必填 —— 一条没有原因的取消记录，事后只能靠回忆。 */
	Detail cancel(Long id, String reason);

	/**
	 * 看板上的列 —— <b>五列</b>。
	 *
	 * <p><b>列的归并是一条业务定义，不是排版</b>：哪几个状态算"待处理"决定了
	 * 界面上一眼看到的是"有几件事在等人"还是"分成三列各几条"。所以它写在服务层，
	 * 与状态链一起被复用（界面只按 key 画，不自己算归属）。
	 *
	 * <p>{@link #COMPLETED} 一列收**两个**状态（已完成与已取消）：看板只画"这件事结束了没有"，
	 * 而两者都是结束 —— 给已取消单开一列会让一条流程里最边缘的结局占掉一整列宽度，
	 * 且那一列大多数时候是空的。两者的区别靠卡片上的状态标签与取消原因表达
	 * （列头的 {@code statusLabels} 也会把"这一列收哪两种"写出来）。
	 *
	 * <p>{@link #EXECUTING} 一列收**四个**状态（已确认需求 / 已确认计划 / 已授权 / 执行中）。
	 * 它们对看板是同一件事：球在 agent 手上，人不该去点它 ——
	 * 分成四列的话，一眼看去像是"有四件事在等人"，而实际上没有一件在等人。
	 * 四个状态的进度差别靠卡片上的状态标签表达。
	 */
	enum Column {

		NEW_TASK("new", "新任务", List.of(AgentTaskStatus.NEW)),

		PENDING("pending", "待处理", List.of(AgentTaskStatus.PENDING_REQUIREMENT, AgentTaskStatus.PENDING_PLAN,
				AgentTaskStatus.PENDING_AUTHORIZATION)),

		EXECUTING("executing", "执行中", List.of(AgentTaskStatus.CONFIRMED_REQUIREMENT,
				AgentTaskStatus.CONFIRMED_PLAN, AgentTaskStatus.AUTHORIZED, AgentTaskStatus.EXECUTING)),

		ACCEPTANCE("acceptance", "待验收", List.of(AgentTaskStatus.PENDING_ACCEPTANCE)),

		COMPLETED("completed", "已完成", List.of(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED));

		private final String key;

		private final String label;

		private final List<AgentTaskStatus> statuses;

		Column(String key, String label, List<AgentTaskStatus> statuses) {
			this.key = key;
			this.label = label;
			this.statuses = statuses;
		}

		public String key() {
			return key;
		}

		public String label() {
			return label;
		}

		public List<AgentTaskStatus> statuses() {
			return statuses;
		}

		/** 一个状态落在哪一列。每一个状态都必须有归属（列覆盖全部取值）。 */
		public static Column of(AgentTaskStatus status) {
			for (Column column : values()) {
				if (column.statuses.contains(status)) {
					return column;
				}
			}
			throw new IllegalStateException("状态「" + status + "」没有归属的列 —— "
					+ "新增状态时必须同时在 Column 里给它一个位置，否则那张卡片会从看板上消失");
		}

	}

	/** 看板数据：列按 {@link Column} 的顺序排。 */
	record Board(List<BoardColumn> columns) {
	}

	/**
	 * 一列。
	 *
	 * @param key 列的 key（对应 {@link Column#key}）
	 * @param label 列名
	 * @param statusLabels 这一列收的状态名（「待处理」靠它说清自己收的是哪三种、「已完成」收的是哪两种）
	 * @param tasks 这一列的任务（最近变更在前）
	 */
	record BoardColumn(String key, String label, List<String> statusLabels, List<AgentTask> tasks) {
	}

	/**
	 * 一条任务的详情。
	 *
	 * @param task 任务本身
	 * @param confirms 它的确认项（按步骤排）
	 * @param actions 现在能做的动作（由状态算出，见 {@code DefaultAgentTaskService#actionsOf}）——
	 *        界面据此画按钮，而不是自己再对一遍状态
	 */
	record Detail(AgentTask task, List<AgentTaskConfirm> confirms, List<Action> actions) {
	}

	/**
	 * 一个可做的动作。
	 *
	 * @param key 动作标识：{@code advance} / {@code answer} / {@code cancel}
	 * @param label 按钮上那句话（同一动作在不同状态下说法不同：新发布是「发起需求确认」，
	 *        执行中是「登记执行结果」）
	 */
	record Action(String key, String label) {
	}

}
