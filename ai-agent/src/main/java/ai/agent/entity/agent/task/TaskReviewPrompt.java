package ai.agent.entity.agent.task;

import java.util.List;

/**
 * <b>一次判定的提示词</b> —— 「系统那一段」与「用户那一段」分开的两半。
 *
 * <h2>为什么是两半，而不是一段拼好的字符串</h2>
 * 系统那一段进的是 {@code AgentLoop} 的系统消息（受 Prompt 版本库管理、可以热更新、
 * 见 {@code PromptKind.TASK_REVIEW}），用户那一段是这一次判定的输入（描述、已答复的确认项、
 * 这一次要判的事）。合成一段之后，两支消息在协议里就变成同一条 ——
 * 于是"改提示词"与"这一次判什么"混在一处，版本库里那一版也就说不清它到底管什么。
 *
 * <h2>为什么 JSON Schema 只出现在系统那一段</h2>
 * Schema 来自 {@code BeanOutputConverter}（它从两个 record 生成），
 * 而"提示词里写的字段"与"代码解析的字段"必须是同一个东西。所以出厂正文里写的是
 * {@link #SCHEMA_PLACEHOLDER} 占位符、由 {@link #renderSystem} 在读取时替换成真的 schema，
 * 而 {@link #reviewInstruction} / {@link #breakdownInstruction} 只说
 * "按系统提示词里那份 schema 输出"：两段各写一份 schema 就是两处维护，
 * 分叉时的现象是"模型照一份输出、另一份解析不到值"（只在运行期出现，看起来像模型不听话）。
 *
 * @param system 系统消息的正文（出厂模板里的 schema 占位符已替换成真的 JSON Schema；
 *            它由 {@code TaskReviewRunner} 直接算出来传给循环，所以这个 record 通常只用 {@code instruction} 那一位）
 * @param instruction 用户消息的正文（这一次判定的输入 + 字段含义；不含 schema）
 */
public record TaskReviewPrompt(String system, String instruction) {

	/** 正文里的 schema 占位符。提示词里写它、代码替换它，两边靠这一个常量对齐。 */
	public static final String SCHEMA_PLACEHOLDER = "{{schema}}";

	/**
	 * 把出厂正文里的占位符换成真的 schema。
	 *
	 * <p>占位符不在正文里时<b>抛</b>：那说明有人把提示词里的那一行删了，
	 * 而"没有 schema 的判定提示词"照样能跑 —— 模型会按自己的理解输出字段，
	 * 解析失败率上升，而现象只是"判定老是不通过"。宁可在这里就炸。
	 */
	public static String renderSystem(String template, String jsonSchema) {
		if (template == null || !template.contains(SCHEMA_PLACEHOLDER)) {
			throw new IllegalStateException(
					"判定提示词里没有 " + SCHEMA_PLACEHOLDER + " 占位符 —— 模型看不到 JSON Schema 就会按自己的理解输出字段，"
							+ "而那种失败只表现为「判定老是不通过」。请把它加回去（Prompt 版本页或 "
							+ "prompts/task-review.st）");
		}
		return template.replace(SCHEMA_PLACEHOLDER, jsonSchema);
	}

	/**
	 * 需求歧义判定的指令。
	 *
	 * <p>判断标准三条（要交付什么 / 在什么范围里做 / 怎么算做完）与输出契约都在这里。
	 * <b>JSON Schema 不在这里</b>：它由 {@code BeanOutputConverter} 从
	 * {@code RequirementReview} 生成、替换进系统提示词的 {@link #SCHEMA_PLACEHOLDER} ——
	 * 两段都写一遍就是两份 schema，迟早分叉。
	 */
	public static String reviewInstruction(String description, List<AgentTaskConfirm> history) {
		return """
				判断下面这条任务描述**有没有歧义**。

				判断标准（逐条看，有一条读不出来就算有歧义）：
				1. 要交付什么 —— 做出来的东西是什么；
				2. 在什么范围里做 —— 哪些文件、哪些数据、哪个系统；
				3. 怎么算做完 —— 验收标准。

				任务描述：
				%s

				已经问过并答复过的内容（可能为空）：
				%s

				按系统提示词里那份 JSON Schema 输出一个 JSON 对象，字段含义：
				- ambiguous：true = 还有歧义；
				- question：有歧义时**只问一个**最要紧的问题（无歧义时给空字符串）。
				""".formatted(description, renderHistory(history));
	}

	/**
	 * 任务拆分的指令。
	 *
	 * <p><b>已答复的确认项必须原样带上</b>：模型看不到确认项表，而"人同意过什么"
	 * 是它拆任务点的唯一依据（见 {@code DefaultAgentTaskExecution#instruction} 里同一条理由）。
	 *
	 * <p>JSON Schema 同样不在这里（理由见 {@link #reviewInstruction}）。
	 */
	public static String breakdownInstruction(String description, List<AgentTaskConfirm> history) {
		return """
				把下面这条任务拆成**可以直接执行的小任务点**。

				任务描述：
				%s

				已经问过并答复过的内容（可能为空）：
				%s

				按系统提示词里那份 JSON Schema 输出一个 JSON 对象，四个字段的要求：
				- actions：每个任务点一句话、动词开头、做完能判断（不要写"分析一下"这种没有完成标志的点）；
				- plan：给用户看的执行计划（先做什么、后做什么）；
				- authorizationScope：这次执行要用户授权做什么（改哪些文件、要不要跑命令）；
				- workspaceDir：这次执行在哪个目录下干活（绝对路径）。**不需要目录就给空字符串** ——
				  例如只回答问题、或只跑一段不依赖文件的命令。
				""".formatted(description, renderHistory(history));
	}

	/** 已有的确认项渲染成"第几步 · 哪种确认 · 问的是什么 → 答复是什么"。 */
	public static String renderHistory(List<AgentTaskConfirm> history) {
		if (history == null || history.isEmpty()) {
			return "（没有）";
		}
		StringBuilder sb = new StringBuilder();
		for (AgentTaskConfirm confirm : history) {
			sb.append("- 第 ").append(confirm.stepIndex()).append(" 步 · ").append(confirm.typeLabel())
					.append("：").append(confirm.description() == null ? "（无正文）" : confirm.description())
					.append(" → 答复：").append(confirm.answerLabel()).append('\n');
		}
		return sb.toString().strip();
	}

}
