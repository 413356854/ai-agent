package ai.agent.service.agent;

import ai.agent.service.tool.ToolCallOutcome;
import ai.agent.service.tool.ToolInvocation;

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
 * @param failureCause 失败细分原因；只有 {@code stopReason == MODEL_ERROR} 时非空。
 *                     <b>它是后加的字段，所以放在最后</b> —— 这样已有的构造调用
 *                     （15 个循环测试里的那些）不需要改动就能编译，
 *                     而新增信息由便利构造器补上（默认 null）。
 *                     <p>为什么必须加它：{@code MODEL_ERROR} 这一个出口下面藏着
 *                     鉴权失败、限流、超时、连不上、以及**我们自己的并发闸门拒绝**——
 *                     而"太挤"与"上游挂了"的排查方向完全相反。
 *                     只有 {@code stopReason} 的话，页面在最需要区分的时候
 *                     （压力最大、限流生效时）恰恰给出的是**错误的归因**。
 */
public record AgentRun(String question, List<Step> steps, String finalAnswer, AgentStopReason stopReason,
		int totalTokens, long elapsedMs, int toolCallCount, AgentFailureCause failureCause) {

	public AgentRun {
		steps = List.copyOf(steps);
	}

	/**
	 * 不带失败细分的构造器。
	 *
	 * <p>保留它有三个好处：① 已有调用点（十几个测试）不用改；
	 * ② 非失败路径本来就没有失败原因，写 null 是噪音；
	 * ③ <b>它让"这个字段是补充信息而不是核心状态"这件事在类型上就成立</b> ——
	 * 核心状态（停因）仍然只有一个必填字段。
	 */
	public AgentRun(String question, List<Step> steps, String finalAnswer, AgentStopReason stopReason,
			int totalTokens, long elapsedMs, int toolCallCount) {
		this(question, steps, finalAnswer, stopReason, totalTokens, elapsedMs, toolCallCount, null);
	}

	/** 带失败原因的副本（循环在把异常翻译成原因之后用它补全报告）。 */
	public AgentRun withFailureCause(AgentFailureCause cause) {
		return new AgentRun(question, steps, finalAnswer, stopReason, totalTokens, elapsedMs, toolCallCount, cause);
	}

	/** 失败时给页面/日志的下一步建议；没失败或没有细分原因时返回 null。 */
	public String failureAdvice() {
		return failureCause == null ? null : ModelFailureClassifier.advice(failureCause);
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
	 * @param cachedPromptTokens 这一轮输入里**命中上游提示词缓存**的部分（{@code promptTokens} 的子集）。
	 *            它是阶段四成本看板的输入口径：缓存命中与未命中的单价差一个数量级，
	 *            而只留 {@code promptTokens} 就只能按最贵那档算。
	 *            <p>同样放成"补充信息"（带便利构造器、默认 0）：旧调用点不用改，
	 *            而 0 的语义是明确的 ——"没有命中"，也就是按最贵那一档计费。
	 */
	public record Step(int index, String thoughtText, List<ToolInvocation> invocations, List<ToolCallOutcome> outcomes,
			int promptTokens, int completionTokens, long elapsedMs, int cachedPromptTokens) {

		public Step {
			invocations = List.copyOf(invocations);
			outcomes = List.copyOf(outcomes);
		}

		/** 不带缓存信息的构造器（脚本化模型 / 旧调用点用）。 */
		public Step(int index, String thoughtText, List<ToolInvocation> invocations, List<ToolCallOutcome> outcomes,
				int promptTokens, int completionTokens, long elapsedMs) {
			this(index, thoughtText, invocations, outcomes, promptTokens, completionTokens, elapsedMs, 0);
		}

		/** 这一步有没有真的调工具。 */
		public boolean usedTools() {
			return !invocations.isEmpty();
		}

		/** 一步的 token 小计。 */
		public int tokens() {
			return promptTokens + completionTokens;
		}

		/**
		 * 这一步里按**未命中缓存单价**计费的输入 token。
		 *
		 * <p>与 {@code ModelReply.uncachedPromptTokens()} 是同一个派生量，
		 * 之所以两处都要有：前者是适配器交出来的口径，后者是落库/报告用的口径，
		 * 而**减法只有一个正确写法** —— 与其让每个使用点自己减（总有一个会写错，
		 * 而写错的表现是成本偏高、不报错），不如各留一个名字说得清的入口。
		 */
		public int uncachedPromptTokens() {
			return Math.max(0, promptTokens - cachedPromptTokens);
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
