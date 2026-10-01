package ai.agent.entity.agent.task;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 任务状态 —— 一条链上的十个取值（库里的值一律写枚举名，见 {@link #storedValue}）。
 *
 * <h2>状态链</h2>
 * <pre>
 *   新发布 NEW
 *     → 待确认需求 PENDING_REQUIREMENT   （等需求确认项的答复；确认项由任务执行器生成）
 *     → 已确认需求 CONFIRMED_REQUIREMENT （等任务执行器：再判一次歧义、拆任务点）
 *     → 待确认计划 PENDING_PLAN          （任务点 ≥ 5 时才有这一格，等计划确认项的答复）
 *     → 已确认计划 CONFIRMED_PLAN        （等任务执行器：判断授权 → 开跑）
 *     → 执行中     EXECUTING             （任务执行器正在跑；跑完写执行结果）
 *     → 待验收     PENDING_ACCEPTANCE
 *     → 已完成     COMPLETED
 *   待验收 ──人答复「任务未完成」──▶ 任务未完成 NOT_COMPLETED（写未完成原因，选填）
 *   已确认计划 ──判断授权不通过──▶ 权限不足 INSUFFICIENT_PERMISSION（结论写进 result）
 *   权限不足 ──人答复「已修改任务」──▶ 新发布（重新走一遍）
 *   任一非终态 → 已取消 CANCELLED（取消原因与状态同一次写入）
 * </pre>
 *
 * <h2>判断授权通过之后没有人工环节</h2>
 * 授权由平台判（{@code TaskFileAuthorization}：任务人角色的目录前缀覆盖不覆盖这次要动的目录），
 * 判过就开跑 —— 链上没有"等答复授权"与"已授权"这两格。所以人在链上只需答复需求与计划，
 * 而"这次要动什么"与判据一起出现在计划确认项里。
 *
 * <h2>「等确认项答复」与「等任务执行器动手」是两类状态</h2>
 * 前者的球在人手上（他在界面上答复确认项：需求确认与验收在发布人手上、计划确认在任务人手上，
 * 见 {@code AgentTaskTransitions#executorFor}），后者的球在 agent 手上
 * （见 {@link #awaitingAgent()}：它把"哪几个状态该由任务执行器处理"写成状态的属性）。
 * 两类的区别决定界面上的按钮：等待答复的状态有「答复确认项」，
 * 等 agent 的状态没有可点的动作（只有「取消任务」）——
 * 一个"看起来能推进、点下去其实什么都没发生"的按钮比没有按钮更坏。
 *
 * <h2>「权限不足」为什么是一个状态，而不是"取消 + 一条说明"</h2>
 * 它等的是一个<b>只有人能做</b>的选择：取消这条任务，还是去把人/权限/要动的目录改好再重走一遍
 * （见 {@code AgentTaskService.Action} 的 {@code revise}）。做成"直接取消"的话，
 * 唯一能表达"我这就去改权限"的入口就没有了，而那条任务会以"已取消"结束 ——
 * 事后看只剩一条取消记录，看不出它其实是在等一次权限修正。
 *
 * <h2>「已确认需求 / 已确认计划」为什么是两个独立状态</h2>
 * 它们都是"人已经答完了、球在 agent 手上"，但<b>agent 要做的事完全不同</b>：
 * 已确认需求要拆任务点、已确认计划要判断授权并开跑。
 * 合成一个之后，"这一步该干什么"只能靠"再看看它前面答过哪些确认项"推出来 ——
 * 那是把判据散进查询，而推错的形态是"重复拆一次任务点"或"跳过授权直接执行"。
 *
 * <h2>「任务未完成」为什么是终态，而不是"待重做"</h2>
 * 它记的是发布人的一次判断：这条活没做成。把它做成"待重做"之后，链上就会出现
 * "重新执行一遍"这一步 —— 而重跑会把上一次已经发生的写操作再做一遍，那是事故不是浪费。
 * 要重做由人在界面上重新发布一条：那时任务描述、目录授权、确认项都是新的，
 * 而这一条留着"当时为什么没做成"（{@code agent_task.incomplete_reason}，选填）。
 *
 * <h2>两个"待确认"是两个状态，而不是一个</h2>
 * 见 {@code db/task-schema.sql} 的头注：一个状态装不下"现在等的是谁、等的是什么"。
 *
 * <h2>为什么"等哪一条确认项"由这个枚举回答，而不是散在服务层的 switch 里</h2>
 * 因为它是状态的属性：{@link #waitingConfirmation()} 与状态的定义天然同步 ——
 * 写在服务层的话，加一个状态时很容易只改了链上的箭头、忘了改那张对照表，
 * 而那种错的形态是"答复一条对不上的确认项被接受"（一条静默的错路）。
 */
public enum AgentTaskStatus {

	NEW("新发布"),

	PENDING_REQUIREMENT("待确认需求"),

	CONFIRMED_REQUIREMENT("已确认需求"),

	PENDING_PLAN("待确认计划"),

	CONFIRMED_PLAN("已确认计划"),

	INSUFFICIENT_PERMISSION("权限不足"),

	EXECUTING("执行中"),

	PENDING_ACCEPTANCE("待验收"),

	COMPLETED("已完成"),

	NOT_COMPLETED("任务未完成"),

	CANCELLED("已取消");

	private final String label;

	AgentTaskStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** 库里的取值。 */
	public String storedValue() {
		return name().toUpperCase(Locale.ROOT);
	}

	/** 终态：不会再流转（{@link #COMPLETED} / {@link #NOT_COMPLETED} / {@link #CANCELLED}）。 */
	public boolean terminal() {
		return this == COMPLETED || this == NOT_COMPLETED || this == CANCELLED;
	}

	/**
	 * 这个状态在等哪种确认项的答复；不是在等的状态返回空。
	 *
	 * <p>它同时是两个判断的依据：答复时"这条确认项的类型对不对"，以及进入这个状态时
	 * "该生成哪一种确认项" —— 两者必须是同一份映射，否则会出现"等需求确认、却生成了计划确认"。
	 */
	public Optional<ConfirmType> waitingConfirmation() {
		return switch (this) {
			case PENDING_REQUIREMENT -> Optional.of(ConfirmType.REQUIREMENT);
			case PENDING_PLAN -> Optional.of(ConfirmType.PLAN);
			default -> Optional.empty();
		};
	}

	/**
	 * 这个状态<b>等任务执行器动手</b>（球在 agent 手上），而不是等人答复确认项。
	 *
	 * <p>它有两个调用点，而且必须是同一份判据：任务执行器的分派 switch（谁该被处理），
	 * 以及"按 update_time 取一条待处理任务"那条查询的状态清单。
	 * 分开写的话，新增一个"等 agent"的状态时会出现"执行器认识它、查询取不到它"——
	 * 现象是那条任务永远停在那里，既不报错也不动。
	 */
	public boolean awaitingAgent() {
		return this == NEW || this == CONFIRMED_REQUIREMENT || this == CONFIRMED_PLAN;
	}

	/** 全部"等任务执行器动手"的状态（{@link #awaitingAgent()} 的集合形态，给查询用）。 */
	public static List<AgentTaskStatus> awaitingAgentStatuses() {
		return Arrays.stream(values()).filter(AgentTaskStatus::awaitingAgent).toList();
	}

	/**
	 * <b>"我的任务"按哪一列认人</b> —— 界面上那张看板收哪些任务，只看这一个属性。
	 *
	 * <p>它不是"这一格球在谁手上"（那是 {@code AgentTaskTransitions#executorFor}，
	 * 回答的是卡片上那一格显示谁）：两者在几处刻意不同，各自只有一个调用点，
	 * 混用的后果都是静默的 —— 用这一条去填 {@code current_executor_id}，
	 * 那条任务在链上的"当前执行人"就写错了；反过来用 {@code executorFor} 去筛，
	 * 新发布与终态会从两个人的列表里一起消失。
	 *
	 * <p>三组的判据：
	 * <ul>
	 *   <li>{@link #BOTH} —— 发布人与任务人都算：刚发出来（还没落到谁手上）、
	 *       已经结束、已被取消。这几格上"这件事是谁的"不再有区别，两个人里谁都该找得到它；</li>
	 *   <li>{@link #PUBLISHER} —— 只有发布人：「待确认需求」与「待验收」那两件只有他能做；</li>
	 *   <li>{@link #ASSIGNEE} —— 只有任务人：其余七个状态。球在 agent 手上的三格
	 *       （三个"已确认"）<b>也在这一组里</b> —— 那一格确实是 agent 在拆在做，
	 *       而它是任务人的活，他正是那个要看着它跑完、跑砸了要处置的人。</li>
	 * </ul>
	 */
	public enum Party {

		/** 发布人<b>或</b>任务人都算（新发布 / 已完成 / 任务未完成 / 已取消）。 */
		BOTH("发布人或任务人"),

		/** 只有发布人算（待确认需求 / 待验收）。 */
		PUBLISHER("只有发布人"),

		/** 只有任务人算（其余状态）。 */
		ASSIGNEE("只有任务人");

		private final String label;

		Party(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/**
	 * 这个状态的任务该由谁在自己的列表里看见（三组，见 {@link Party}）。
	 *
	 * <p>它必须覆盖全部取值 —— 漏掉一个状态的后果是"那条任务在谁的列表里都不出现"，
	 * 而那与"它本来就不该出现"在界面上长得一样（也由启动自检逐个问一遍）。
	 */
	public Party party() {
		return switch (this) {
			case NEW, COMPLETED, NOT_COMPLETED, CANCELLED -> Party.BOTH;
			case PENDING_REQUIREMENT, PENDING_ACCEPTANCE -> Party.PUBLISHER;
			case PENDING_PLAN, INSUFFICIENT_PERMISSION,
					CONFIRMED_REQUIREMENT, CONFIRMED_PLAN, EXECUTING -> Party.ASSIGNEE;
		};
	}
	/** 某一组里的全部状态（界面按人取任务时用它拼查询）。 */
	public static List<AgentTaskStatus> withParty(Party party) {
		return Arrays.stream(values()).filter(status -> status.party() == party).toList();
	}

	/**
	 * 宽松解析。认不出的取值落 {@link #NEW}。
	 *
	 * <p>与 {@code AccountStatus.parse} 的取向一致（认不出落最保守的一档），但"保守"在这里的含义
	 * 不同：账号那边落 DISABLED 是因为它决定"能不能用"；这里落 NEW 是因为它决定"看不看得见" ——
	 * 落成 {@link #COMPLETED} / {@link #NOT_COMPLETED} / {@link #CANCELLED} 会让那一行从看板的活跃列里**消失**，
	 * 而"卡片不见了"是这一页最难被发现的一种坏法。落在 NEW 至少看得见、还能重新推进。
	 *
	 * <h2>两个历史取值折回等价状态，而不是落 NEW</h2>
	 * 链上曾有"等答复授权"与"已授权"两格，库里可能还留着那两行。落 NEW 会让它们
	 * <b>从头重走一遍</b>（包括再跑一次可能已经发生过的写操作），而它们各自有一个等价的落点：
	 * <pre>
	 *   PENDING_AUTHORIZATION → CONFIRMED_PLAN （回到"判断授权"那一格，判的是同一次要动的目录）
	 *   AUTHORIZED            → EXECUTING      （已经批过，等执行 —— 与它原本的下一步是同一个格子）
	 * </pre>
	 * 折回 AUTHORIZED 那一条刻意<b>不</b>折成"重新跑"：那条任务当时已经过了授权这一关，
	 * 重跑等于把已经发生的写操作再做一遍。
	 */
	public static AgentTaskStatus parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return NEW;
		}
		String normalized = raw.strip();
		// 历史取值（见上面那段）：它们不在枚举里，所以先按名字认一遍
		if ("PENDING_AUTHORIZATION".equalsIgnoreCase(normalized) || "待授权".equals(normalized)) {
			return CONFIRMED_PLAN;
		}
		if ("AUTHORIZED".equalsIgnoreCase(normalized) || "已授权".equals(normalized)) {
			return EXECUTING;
		}
		for (AgentTaskStatus status : values()) {
			if (status.name().equalsIgnoreCase(normalized) || status.label.equals(normalized)) {
				return status;
			}
		}
		return NEW;
	}

}
