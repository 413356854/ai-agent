package ai.agent.entity.agent.task;

import java.util.Locale;
import java.util.Optional;

/**
 * 任务状态 —— 一条链上的八个取值（库里的值一律写枚举名，见 {@link #storedValue}）。
 *
 * <h2>状态链</h2>
 * <pre>
 *   新发布 NEW
 *     → 待确认需求 PENDING_REQUIREMENT   （第 1 步，等需求确认项答复）
 *     → 待确认计划 PENDING_PLAN          （第 2 步，等计划确认项答复）
 *     → 待授权     PENDING_AUTHORIZATION （第 3 步，等授权确认项答复）
 *     → 执行中     EXECUTING             （第 4 步，执行人是 agent）
 *     → 待验收     PENDING_ACCEPTANCE    （第 5 步）
 *     → 已完成     COMPLETED             （第 6 步）
 *   任一非终态 → 已取消 CANCELLED（取消原因与状态同一次写入）
 * </pre>
 * 每次推进步骤 +1，唯一不递增的是「新发布 → 待确认需求」：两者都停在第 1 步，
 * 后者表示"第 1 步已经开始了（确认项已发出）"，而不是"进了第 2 步"。
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

	PENDING_PLAN("待确认计划"),

	PENDING_AUTHORIZATION("待授权"),

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
