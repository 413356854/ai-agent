package ai.agent.tool;

import java.util.List;

/**
 * 一次工具调用尝试的<b>完整结论</b>：走到哪一步、结果是什么、回填给模型看什么。
 *
 * <h2>为什么要有"阶段"这个字段（本类最重要的设计）</h2>
 * 一个工具调用失败，可能死在四个完全不同的地方，而对模型来说它们要引导出<b>四种不同的动作</b>：
 * <table border="1">
 *   <caption>阶段 → 模型该做什么</caption>
 *   <tr><th>阶段</th><th>含义</th><th>模型应该</th></tr>
 *   <tr><td>{@code UNKNOWN_TOOL}</td><td>工具名不在白名单里（模型编了一个）</td>
 *       <td><b>改用现有工具</b>，而不是重试同名工具</td></tr>
 *   <tr><td>{@code PARSE_ERROR}</td><td>参数不是合法 JSON / 字段类型对不上</td>
 *       <td>重新生成参数，注意 JSON 语法</td></tr>
 *   <tr><td>{@code VALIDATION_ERROR}</td><td>JSON 合法但过不了约束（缺字段、越界）</td>
 *       <td>修正<b>具体那个字段</b>（回填里带了可用值）</td></tr>
 *   <tr><td>{@code EXECUTION_ERROR}</td><td>参数没问题，工具自己炸了</td>
 *       <td>换个参数或换个工具；重试同样参数毫无意义</td></tr>
 * </table>
 *
 * <p>如果只用一个布尔"成功/失败"来表达，这四种就被合并了。后果不是难看，
 * 而是<b>模型的自愈率会掉下来</b>：它只能看到"失败了"，于是最常见的反应是<b>原样重试</b> ——
 * 而原样重试在上面四种里只有 {@code EXECUTION_ERROR} 有一点点意义。
 * 这也是路线文档要求「结构化错误回灌（错误信息里带可用值，模型才能自愈）」的原因。
 *
 * <p>反过来，这个字段对<b>人</b>同样重要：把 stage 做成机器可读的短码之后，
 * "本会话里 7 次工具调用，其中 3 次死于模型编了工具名"就成了一句可以统计、可以断言的话，
 * 而不是靠翻日志猜。
 *
 * @param invocation 模型原始提出的意图（原样保留，报告与日志都要能看到"它到底调了什么"）
 * @param stage 走到了哪一步
 * @param toolResult 工具自己的结果；只有 {@code stage == OK} 时非空
 * @param feedback <b>回填给模型的那段文本</b>。这是本记录存在的实际用途 ——
 *                 无论成功失败都必须有值，因为"失败不回填"等于让模型凭空猜下一步
 * @param elapsedMs 执行耗时（成功与失败都记：失败也要能看到它是不是卡了很久）
 * @param fixableByModel 这个失败<u>靠改参数或换工具</u>能不能解决。
 *                       它是"值不值得再给模型一轮"的唯一判据，
 *                       也是 {@code AgentLoop} 停止条件的一部分
 */
public record ToolCallOutcome(ToolInvocation invocation, ToolCallStage stage, AgentToolResult toolResult,
		String feedback, long elapsedMs, boolean fixableByModel) {

	public ToolCallOutcome {
		if (feedback == null || feedback.isBlank()) {
			// 空回填是最阴的一种 bug：模型收到一条空 observation，
			// 它只能编一个解释，而这看起来像"模型幻觉"
			throw new IllegalArgumentException("工具调用结论必须带回填文本（哪怕失败也要说明原因）");
		}
	}

	/** 是否真的执行成功了（注意：工具返回"没查到"也算成功 —— 空结果是正常结果）。 */
	public boolean succeeded() {
		return stage == ToolCallStage.OK;
	}

	/** 给报告/日志的一行摘要（不展开长内容）。 */
	public String summary() {
		return "%s stage=%s elapsed=%dms fixable=%s".formatted(invocation.name(), stage, elapsedMs, fixableByModel);
	}

	/** 会话级统计：哪一类失败各出现几次（"模型到底在哪儿卡住"的答案）。 */
	public static String summarize(List<ToolCallOutcome> outcomes) {
		if (outcomes.isEmpty()) {
			return "本轮没有任何工具调用";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("工具调用 ").append(outcomes.size()).append(" 次：");
		for (ToolCallStage stage : ToolCallStage.values()) {
			long count = outcomes.stream().filter(o -> o.stage() == stage).count();
			if (count > 0) {
				sb.append(stage.name()).append('=').append(count).append(' ');
			}
		}
		long totalMs = outcomes.stream().mapToLong(ToolCallOutcome::elapsedMs).sum();
		sb.append("｜工具侧总耗时 ").append(totalMs).append("ms");
		return sb.toString().strip();
	}

}
