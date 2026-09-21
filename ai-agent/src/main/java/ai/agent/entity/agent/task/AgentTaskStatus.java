package ai.agent.entity.agent.task;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 任务状态 —— 一条链上的十一个取值（库里的值一律写枚举名，见 {@link #storedValue}）。
 *
 * <h2>状态链</h2>
 * <pre>
 *   新发布 NEW
 *     → 待确认需求 PENDING_REQUIREMENT   （等需求确认项的答复；确认项由任务执行器生成）
 *     → 已确认需求 CONFIRMED_REQUIREMENT （等任务执行器：再判一次歧义、拆任务点）
 *     → 待确认计划 PENDING_PLAN          （任务点 ≥ 5 时才有这一格，等计划确认项的答复）
 *     → 已确认计划 CONFIRMED_PLAN        （等任务执行器：判断授权）
 *     → 待授权     PENDING_AUTHORIZATION （等授权确认项的答复）
 *     → 已授权     AUTHORIZED            （等任务执行器：执行）
 *     → 执行中     EXECUTING             （任务执行器正在跑；跑完写执行结果）
 *     → 待验收     PENDING_ACCEPTANCE
 *     → 已完成     COMPLETED
 *   任一非终态 → 已取消 CANCELLED（取消原因与状态同一次写入）
 * </pre>
 *
 * <h2>「等确认项答复」与「等任务执行器动手」是两类状态</h2>
 * 前者的球在发布人手上（他在界面上答复确认项），后者的球在 agent 手上
 * （见 {@link #awaitingAgent()}：它把"哪几个状态该由任务执行器处理"写成状态的属性）。
 * 两类的区别决定界面上的按钮：等待答复的状态有「答复确认项」，
 * 等 agent 的状态没有可点的动作（只有「取消任务」）——
 * 一个"看起来能推进、点下去其实什么都没发生"的按钮比没有按钮更坏。
 *
 * <h2>为什么"已确认需求 / 已确认计划 / 已授权"是三个独立状态</h2>
 * 它们都是"人已经答完了、球在 agent 手上"，但<b>agent 要做的事完全不同</b>：
 * 已确认需求要拆任务点、已确认计划要判断授权、已授权要真的执行。
 * 合成一个之后，"这一步该干什么"只能靠"再看看它前面答过哪些确认项"推出来 ——
 * 那是把判据散进查询，而推错的形态是"重复拆一次任务点"或"跳过授权直接执行"。
 *
 * <h2>为什么三个"待确认"是三个状态，而不是一个</h2>
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

	PENDING_AUTHORIZATION("待授权"),

	AUTHORIZED("已授权"),

	EXECUTING("执行中"),

	PENDING_ACCEPTANCE("待验收"),

	COMPLETED("已完成"),

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

	/** 终态：不会再流转（{@link #COMPLETED} / {@link #CANCELLED}）。 */
	public boolean terminal() {
		return this == COMPLETED || this == CANCELLED;
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
			case PENDING_AUTHORIZATION -> Optional.of(ConfirmType.AUTHORIZATION);
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
		return this == NEW || this == CONFIRMED_REQUIREMENT || this == CONFIRMED_PLAN || this == AUTHORIZED;
	}

	/** 全部"等任务执行器动手"的状态（{@link #awaitingAgent()} 的集合形态，给查询用）。 */
	public static List<AgentTaskStatus> awaitingAgentStatuses() {
		return Arrays.stream(values()).filter(AgentTaskStatus::awaitingAgent).toList();
	}

	/**
	 * 宽松解析。认不出的取值落 {@link #NEW}。
	 *
	 * <p>与 {@code AccountStatus.parse} 的取向一致（认不出落最保守的一档），但"保守"在这里的含义
	 * 不同：账号那边落 DISABLED 是因为它决定"能不能用"；这里落 NEW 是因为它决定"看不看得见" ——
	 * 落成 {@link #COMPLETED} / {@link #CANCELLED} 会让那一行从看板的活跃列里**消失**，
	 * 而"卡片不见了"是这一页最难被发现的一种坏法。落在 NEW 至少看得见、还能重新推进。
	 */
	public static AgentTaskStatus parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return NEW;
		}
		String normalized = raw.strip();
		for (AgentTaskStatus status : values()) {
			if (status.name().equalsIgnoreCase(normalized) || status.label.equals(normalized)) {
				return status;
			}
		}
		return NEW;
	}

}
