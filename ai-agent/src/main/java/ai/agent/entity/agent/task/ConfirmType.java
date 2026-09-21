package ai.agent.entity.agent.task;

import java.util.Locale;

/**
 * 确认项的类型 —— 与状态链上的三个"待确认"一一对应。
 *
 * <h2>为什么只有三种</h2>
 * 它们是任务从发布到执行的三个卡点：需求（做什么）、计划（怎么做）、授权（允不允许做）。
 * 验收**不在其中**：它需要一个"执行结果"，而那是推进事件（{@code EXECUTING → 待验收}）登记的东西，
 * 不是一次选择题。硬把它做成第四种确认项，会让确认项的选项集去承担"验收通过 / 打回"这种
 * 带后果的决定 —— 而那条后果（打回之后回到哪一步）是状态机的事，不是一条记录能表达的。
 */
public enum ConfirmType {

	REQUIREMENT("需求确认"),

	PLAN("计划确认"),

	AUTHORIZATION("授权确认");

	private final String label;

	ConfirmType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public String storedValue() {
		return name().toUpperCase(Locale.ROOT);
	}

	/**
	 * 宽松解析。认不出的取值返回 {@code null}。
	 *
	 * <p>与 {@link AgentTaskStatus#parse} 的落点不同，因为这里**没有安全的兜底值**：
	 * 三种类型各自对着一份不同的确认内容，挑任何一个当默认都会把一条记录显示成它不是的那种确认。
	 * 所以认不出就返空，由展示层原样说出"认不出的类型"（{@link AgentTaskConfirm#typeLabel()}）——
	 * 看得见、改得掉，而不是让整个看板因为一行手工改坏的数据读不出来。
	 */
	public static ConfirmType parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String normalized = raw.strip();
		for (ConfirmType type : values()) {
			if (type.name().equalsIgnoreCase(normalized) || type.label.equals(normalized)) {
				return type;
			}
		}
		return null;
	}

}
