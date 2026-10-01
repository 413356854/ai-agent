package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.AgentTaskStatus;

import java.util.List;

/**
 * agent 任务的读写契约（应用管理区 · agent任务）。
 *
 * <h2>六个写动作，各对应链上的一段</h2>
 * <table border="1">
 *   <caption>动作与它推着链走的那一格</caption>
 *   <tr><th>动作</th><th>允许的状态</th><th>之后到哪</th></tr>
 *   <tr><td>{@link #create}</td><td>—（产生一条新任务）</td><td>新发布</td></tr>
 *   <tr><td>{@link #advance}</td><td>执行中</td><td>待验收（登记执行结果）</td></tr>
 *   <tr><td>{@link #accept}</td><td>待验收</td><td>已完成（验收通过，另触发一次"经验规则"补充）</td></tr>
 *   <tr><td>{@link #reject}</td><td>待验收</td><td>任务未完成（写未完成原因，选填）</td></tr>
 *   <tr><td>{@link #answer}</td><td>两个"待确认"</td><td>对应的"已确认"那一格（已确认需求 / 已确认计划）</td></tr>
 *   <tr><td>{@link #revise}</td><td>权限不足</td><td>新发布（用户答复「已修改任务」+ 改过的任务描述，整条链重走一遍）</td></tr>
 *   <tr><td>{@link #cancel}</td><td>任一非终态</td><td>已取消（写取消原因）</td></tr>
 * </table>
 *
 * <p><b>新发布、已确认需求、已确认计划这三格没有人工动作</b>：
 * 它们的球在 agent 手上，由 {@code AgentTaskExecution} 按状态推进（见
 * {@link AgentTaskStatus#awaitingAgent()}）。所以 {@link #advance} 不接受新发布 ——
 * "新发布 → 待确认需求"这一步现在由执行器先判断需求有没有歧义再决定，
 * 人工那条路会让同一条任务有两个不同的第一条确认项。
 *
 * <p><b>授权不在这张表里</b>：它由平台判（{@code TaskFileAuthorization}），判过就开跑 ——
 * 链上没有"等人答复授权"那一格（见 {@link AgentTaskStatus} 的状态链）。
 *
 * <h2>为什么"推进"、"答复"与"重发"是三个动作而不是一个</h2>
 * 回答一个问题（{@code answer}）与报告一件事发生了（{@code advance}）在链上填的格子不同：
 * 前者必须对上当步的那条确认项，后者没有对象可对。合成一个之后，
 * "这一条请求到底是答复还是推进"要靠请求体里有没有 confirmId 去猜 ——
 * 而猜错的形态是"答复被当成推进执行了"（一次静默的状态跳格）。
 * {@code revise} 与两者都不同：它是这条链上<b>唯一往回走</b>的一步（回到新发布重新走一遍），
 * 拿 {@code advance} 兼职的后果是"登记执行结果"与"重新发布"共用一个入口，
 * 而两者对同一条任务的影响完全不同（一个往前走、一个从头来）。
 *
 * <h2>为什么每个写动作返回 {@link Detail} 而不是一个 ok</h2>
 * 与 {@code PermissionController} 同一条取舍：返回 {@code {"ok":true}} 之后界面只能重新拉一次，
 * 而那次拉取与这次写入之间可能隔着别人的一次修改 —— 页面上显示的是"别人改完之后的样子"，
 * 而用户以为是自己这次的结果。返回新状态就没有这个缝。
 */
public interface AgentTaskService {

	/**
	 * 看板：按列分好（列的定义与顺序见 {@link Column}）。
	 *
	 * @param userId 只看"与这个人有关、而且该由他看见"的任务（界面上传的是当前登录的人）；
	 *        <b>传 {@code null} 就是不筛</b>（全量）。
	 *        <p>判断<b>不是某一列上的等值比较</b>，而是按状态挑列（{@link AgentTaskStatus#party()}）：
	 *        新发布 / 已完成 / 已取消是发布人**或**任务人（两个人里谁都找得到它）；
	 *        待确认需求 / 待验收只有发布人（那两件只有他能做）；其余状态只有任务人。
	 *        球在 agent 手上的两格（两个"已确认"）与谁都不相关，两张列表里都不出现。
	 *        <p>不在账号表里的 id 会被拒（400），理由见 {@code DefaultAgentTaskService#person}。
	 */
	Board board(Long userId);

	/** 一条任务的详情：任务 + 它的全部确认项 + 当前可做的动作。 */
	Detail detail(Long id);

	/**
	 * 发布一条任务。落库时状态是 {@link AgentTaskStatus#NEW}、步骤 1、当前执行人是任务人。
	 *
	 * <p>发布人与任务人<b>都是必填的真实用户</b>：两个名字都要在账号表里存在
	 * （界面上的做法是搜索后从下拉里选，见 {@code PermissionController#searchUsers}）——
	 * 落库的是查出来的两个用户 id（见 {@code agent_task.publisher_id}），
	 * 而账号表里查不到的名字不会有任何约束拦下这条任务。
	 *
	 * <p><b>没有 agentId 这个入参</b>：它由平台在第一次接手这条任务时写入
	 * （见 {@code AgentTaskProperties#agentId}）。
	 */
	Detail create(String publisher, String assignee, String description);

	/**
	 * 推进当前事件：{@link AgentTaskStatus#EXECUTING} → 待验收（登记执行结果，可空）。
	 *
	 * <p><b>验收不在这里</b>：那一格的两个落点是两个动作（{@link #accept} / {@link #reject}），
	 * 见下面那条说明。
	 */
	Detail advance(Long id, String result);

	/**
	 * 验收通过：待验收 → {@link AgentTaskStatus#COMPLETED}。
	 *
	 * <p>验收之后这条任务<b>已经交付</b>了，所以顺带做一件事：把这条任务上"问过什么、答过什么"
	 * 交给模型汇成规则，追加到任务人的技能里（见 {@code TaskRuleDigest}）——
	 * 目的是下一次相似的任务不再问同样的问题。它<b>做成异步且失败不阻断</b>：
	 * 验收这条事务提交之后在另一个线程上跑，模型调不通只记一条日志，
	 * 用户看到的仍然是"已验收"。
	 */
	Detail accept(Long id);

	/**
	 * 任务未完成：待验收 → {@link AgentTaskStatus#NOT_COMPLETED}。
	 *
	 * <p>与 {@link #accept} 是**两个动作**而不是一个带布尔的动作：它们后续该做的事不同 ——
	 * 一个触发经验补充，一个要记原因；合成一个之后，"这次点的是哪一个"只能靠请求体里
	 * 有没有 reason 去猜，而猜错是静默的（一次"没做成"被记成"做成了"）。
	 *
	 * @param reason 未完成原因，<b>选填</b>：这一格要记的是"没做成"这个判断本身，
	 *        而原因可能一时说不清；非空时与状态同一次写入（≤ 512 字符，超限 400）
	 */
	Detail reject(Long id, String reason);

	/** 答复一条确认项；答完按状态链推进并生成下一条确认项。 */
	Detail answer(Long id, Long confirmId, String answer);

	/**
	 * 答复「已修改任务」：权限不足 → 新发布，整条链重走一遍。
	 *
	 * <p>这一格等的是"用户改了任务或改了权限"，而这件事没法从这个接口的入参里判断 ——
	 * 它由用户在界面上答复（按钮就是「已修改任务」）。判据只有一条：当前状态必须是
	 * {@link AgentTaskStatus#INSUFFICIENT_PERMISSION}。
	 *
	 * <p>{@code description} 是<b>改过之后的完整任务描述</b>（不是一段"改了什么"的说明），
	 * 它与这次答复同一次写入：先落 {@code agent_task.description}，再把状态挪回「新发布」。
	 * 两者必须一起落 —— 这条链上判需求歧义、拆任务点读的都是描述那一列，
	 * 只挪状态的话，重走的这一遍读到的还是旧描述（而界面上看起来一切正常）。
	 * <b>没有改动也要把它原样传回来</b>：空描述是 400（判据见 {@link AgentTask#revisedDescription}），
	 * 因为"用户把它清空了"与"他没改"在请求体里长得一样，而两者该做的事完全不同。
	 */
	Detail revise(Long id, String description);

	/** 取消任务。取消原因必填 —— 一条没有原因的取消记录，事后只能靠回忆。 */
	Detail cancel(Long id, String reason);

	/**
	 * 看板上的列 —— <b>五列</b>。
	 *
	 * <p><b>列的归并是一条业务定义，不是排版</b>：哪几个状态算"待处理"决定了
	 * 界面上一眼看到的是"有几件事在等人"还是"分成三列各几条"。所以它写在服务层，
	 * 与状态链一起被复用（界面只按 key 画，不自己算归属）。
	 *
	 * <p>{@link #COMPLETED} 一列收**三个**状态（已完成 / 任务未完成 / 已取消）：看板只画"这件事结束了没有"，
	 * 而三者都是结束 —— 给已取消单开一列会让一条流程里最边缘的结局占掉一整列宽度，
	 * 且那一列大多数时候是空的。"没做成"与"没做"（取消）的区别靠卡片上的状态标签
	 * 与那两行说明（未完成原因 / 取消原因）表达（列头的 {@code statusLabels} 也会把"这一列收哪几种"写出来）。
	 *
	 * <p>{@link #EXECUTING} 一列收**三个**状态（已确认需求 / 已确认计划 / 执行中）。
	 * 它们对看板是同一件事：球在 agent 手上，人不该去点它 ——
	 * 分成三列的话，一眼看去像是"有三件事在等人"，而实际上没有一件在等人。
	 * 三个状态的进度差别靠卡片上的状态标签表达。
	 *
	 * <p>{@link #PENDING} 一列收**三个**状态（两个"待确认"与"权限不足"）：它们等的东西不同
	 * （答复确认项 / 处置一次权限不足），但对看板的答案是同一个 —— <b>有三件事在等人，球都在人手上</b>。
	 * 「权限不足」单开一列的话，那一列在多数时候是空的，而"现在有几件事在等我"这个唯一的问题
	 * 反而要被两个列名拼起来才答得出。两者的区别靠卡片上的状态标签与动作（答复确认项 / 已修改任务）表达。
	 */
	enum Column {

		NEW_TASK("new", "新任务", List.of(AgentTaskStatus.NEW)),

		PENDING("pending", "待处理", List.of(AgentTaskStatus.PENDING_REQUIREMENT, AgentTaskStatus.PENDING_PLAN,
				AgentTaskStatus.INSUFFICIENT_PERMISSION)),

		EXECUTING("executing", "执行中", List.of(AgentTaskStatus.CONFIRMED_REQUIREMENT,
				AgentTaskStatus.CONFIRMED_PLAN, AgentTaskStatus.EXECUTING)),

		ACCEPTANCE("acceptance", "待验收", List.of(AgentTaskStatus.PENDING_ACCEPTANCE)),

		COMPLETED("completed", "已完成", List.of(AgentTaskStatus.COMPLETED, AgentTaskStatus.NOT_COMPLETED,
				AgentTaskStatus.CANCELLED));

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
	 * @param statusLabels 这一列收的状态名（「待处理」靠它说清自己收的是哪三种、「已完成」收的是哪三种）
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
	 * @param key 动作标识：{@code advance} / {@code accept} / {@code reject} / {@code answer} /
	 *        {@code revise} / {@code cancel}
	 * @param label 按钮上那句话（同一动作在不同状态下说法不同：执行中是「登记执行结果」，
	 *        待验收的两个落点是「任务已完成」与「任务未完成」，权限不足是「已修改任务」）
	 */
	record Action(String key, String label) {
	}

}
