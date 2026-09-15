package ai.agent.agent;

import ai.agent.tool.ToolCallOutcome;
import ai.agent.tool.ToolInvocation;

import java.util.List;

/**
 * 一次 Agent 运行（一个用户问题）的<b>完整可观测记录</b>。
 *
 * <h2>为什么把"步骤"留痕，而不是只返回最终答案</h2>
 * 路线文档的阶段三验收写的是：「能讲清<b>它为什么选这个工具、失败重试几次、什么时候让人确认、
 * 日志能不能还原执行过程</b>」。这四条里有三条的前提是"过程被完整留下来了"。
 * 只返回答案的话，"它选了 3 个工具"和"它瞎蒙对了"在界面上长得一模一样。
 *
 * <p>所以这里的 {@link Step} 记录每一步的：模型这一轮说了什么（{@code thoughtText}）、
 * 它要调什么（{@code invocations}）、每个调用走到了哪一步、花了多少 token。
 * 这是阶段四「Agent trace」的最小可用形态 —— 那一层要做的只是把这些字段
 * 加上会话 id 与时间戳，落到日志/存储里。
 *
 * @param question 用户原始问题
 * @param steps 按时间顺序的每一步（含"模型直接回答"那一步）
 * @param finalAnswer 最终答案；被上限截断时为 {@code null}（此时 {@code stopReason} 会说明原因）
 * @param stopReason 为什么停下
 * @param totalTokens 整个任务累计消耗的 token（所有模型调用之和）
 * @param elapsedMs 整个任务的墙上耗时
 * @param toolCallCount 一共发起了几次工具调用（含被拒绝的）
 */
public record AgentRun(String question, List<Step> steps, String finalAnswer, AgentStopReason stopReason,
		int totalTokens, long elapsedMs, int toolCallCount) {

	public AgentRun {
		steps = List.copyOf(steps);
	}

	/** 是否给出了最终答案（false 表示被某个上限拦下）。 */
	public boolean answered() {
		return stopReason == AgentStopReason.ANSWERED && finalAnswer != null && !finalAnswer.isBlank();
	}

	/**
	 * 一步 = 一次模型往返（可能附带若干工具调用）。
	 *
	 * @param index 第几步，从 1 开始（人读报告时用）
	 * @param thoughtText 模型这一轮的文字输出（ReAct 里的 think；可能为空 ——
	 *                    模型可以"只想不做"或"只做不说"）
	 * @param invocations 模型这一步提出的工具调用意图（可能为空 = 它选择直接回答）
	 * @param outcomes 每个意图的执行结论，与 {@code invocations} 一一对应、顺序相同
	 * @param promptTokens 这一轮请求的 token
	 * @param completionTokens 这一轮生成的 token
	 * @param elapsedMs 这一轮（模型 + 全部工具）的耗时
	 */
	public record Step(int index, String thoughtText, List<ToolInvocation> invocations, List<ToolCallOutcome> outcomes,
			int promptTokens, int completionTokens, long elapsedMs) {

		public Step {
			invocations = List.copyOf(invocations);
			outcomes = List.copyOf(outcomes);
		}

		/** 这一步有没有真的调工具。 */
		public boolean usedTools() {
			return !invocations.isEmpty();
		}

		/** 一步的 token 小计。 */
		public int tokens() {
			return promptTokens + completionTokens;
		}

		/** 给报告用的一行摘要。 */
		public String summary() {
			if (!usedTools()) {
				return "第 %d 步：直接作答（%d tokens, %dms）".formatted(index, tokens(), elapsedMs);
			}
			String names = invocations.stream().map(ToolInvocation::name).reduce((a, b) -> a + "、" + b).orElse("");
			return "第 %d 步：调用 [%s] ×%d（%d tokens, %dms）".formatted(index, names, invocations.size(), tokens(),
					elapsedMs);
		}
	}

	/** 所有步骤的摘要行（页面与日志共用同一份渲染，避免两处慢慢漂移）。 */
	public List<String> stepSummaries() {
		return steps.stream().map(Step::summary).toList();
	}

	/**
	 * 所有工具调用的结论（摊平成一层，便于统计与断言）。
	 *
	 * <p>"本会话里 7 次工具调用、其中 3 次死于模型编了工具名"这种话
	 * 必须能直接算出来，而不是靠翻日志数 —— 所以给一个摊平入口。
	 */
	public List<ToolCallOutcome> allOutcomes() {
		return steps.stream().flatMap(s -> s.outcomes().stream()).toList();
	}

}
