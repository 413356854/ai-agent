package ai.agent.entity.agent.task;

import java.time.Instant;
import java.util.List;

/**
 * <b>一条确认项</b> —— 任务在第几步问了什么、给了哪些选项、用户答了什么。
 *
 * <h2>它为什么是任务之外的一张表</h2>
 * 见 {@code db/task-schema.sql} 的头注：每步一条、各有选项与答复，
 * 挤进一列只能存 JSON，而"答没答"要靠解析。
 *
 * <h2>三种"空"的含义，必须分清</h2>
 * <ul>
 *   <li>{@code answer == null} —— <b>还没答</b>（待办）；</li>
 *   <li>{@code options} 为空列表 —— 这条确认只收自由回复，界面上不画选项按钮；</li>
 *   <li>{@code type == null} —— 库里的类型取值认不出（只可能来自手工改库），
 *       展示成「认不出的类型」而不是猜一个。</li>
 * </ul>
 *
 * @param id 主键；未落库时为 {@code null}
 * @param taskId 所属任务（非空）
 * @param stepIndex 这一条是在任务的第几步提出的（≥ 1）
 * @param type 类型；认不出时为 {@code null}
 * @param description 确认事项描述
 * @param options 选项集（空列表 = 只收自由回复）
 * @param answer 用户确认项：选的选项或自由回复；{@code null} = 还没答
 * @param createdAt 落库时间
 * @param updatedAt 最近一次变更时间（答复会推动它）
 */
public record AgentTaskConfirm(Long id, Long taskId, int stepIndex, ConfirmType type, String description,
		List<String> options, String answer, Instant createdAt, Instant updatedAt) {

	/** 确认事项描述上限（与任务描述同一量级与同一条"拒绝而不是截断"的规则）。 */
	public static final int MAX_DESCRIPTION_CHARS = 2000;

	/** 用户答复上限。它要装得下"选项之外的补充说明"，所以与描述同量级。 */
	public static final int MAX_ANSWER_CHARS = 2000;

	/** 选项个数上限：选项是给人点的一排按钮，超过这个数说明问题该拆开。 */
	public static final int MAX_OPTIONS = 8;

	/** 单个选项的字符上限（按钮上的一行字）。 */
	public static final int MAX_OPTION_CHARS = 64;

	/** 还没答时的答复文本（界面与模型都读它）。 */
	public static final String UNANSWERED = "（未答复）";

	public AgentTaskConfirm {
		if (taskId == null) {
			throw new IllegalArgumentException("确认项必须属于一个任务（taskId 不能为空）");
		}
		if (stepIndex < 1) {
			throw new IllegalArgumentException("确认项的步骤从 1 开始，实际是 " + stepIndex);
		}
		description = description == null || description.isBlank() ? null
				: fit(description.strip(), "确认事项描述", MAX_DESCRIPTION_CHARS);
		options = normalize(options);
		answer = answer == null || answer.isBlank() ? null : fit(answer.strip(), "用户确认项", MAX_ANSWER_CHARS);
	}

	/** 答过了没有。判据只在这里 —— "空串"在构造期已经被归一成 null。 */
	public boolean answered() {
		return answer != null;
	}

	/** 界面上显示的答复（没答时给一个可读的占位，而不是空白）。 */
	public String answerLabel() {
		return answer == null ? UNANSWERED : answer;
	}

	/** 界面上显示的类型。认不出的取值说"认不出"，不猜。 */
	public String typeLabel() {
		return type == null ? "（认不出的类型）" : type.label();
	}

	private static List<String> normalize(List<String> options) {
		if (options == null) {
			return List.of();
		}
		List<String> cleaned = options.stream()
				.filter(option -> option != null && !option.isBlank())
				.map(option -> fit(option.strip(), "选项", MAX_OPTION_CHARS))
				.distinct()
				.toList();
		if (cleaned.size() > MAX_OPTIONS) {
			throw new IllegalArgumentException("选项最多 %d 个，实际 %d 个 —— 超过这个数说明这一个问题该拆成几条确认项"
					.formatted(MAX_OPTIONS, cleaned.size()));
		}
		return cleaned;
	}

	private static String fit(String value, String what, int max) {
		if (value.length() > max) {
			throw new IllegalArgumentException("%s过长（%d 字符 > 上限 %d）".formatted(what, value.length(), max));
		}
		return value;
	}

}
