package ai.agent.agent;

import ai.agent.tool.ToolCallOutcome;
import ai.agent.tool.ToolInvocation;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 循环过程中的进度回调 —— <b>断点恢复的挂载点</b>。
 *
 * <h2>为什么做成回调，而不是让 {@code AgentLoop} 直接依赖存储</h2>
 * 三个理由，每个都很具体：
 * <ol>
 *   <li><b>循环本身的测试不该被迫起数据库</b>：现有 15 个循环测试证明的是
 *       "think → act → observe 与四个出口"，它们不需要一个 H2 就能成立。
 *       如果循环直接依赖 store，每个循环测试都要起库、都要清表；</li>
 *   <li><b>存储是可选的</b>：跑诊断、跑单机试玩时不该强制落库。
 *       回调可以传"什么都不做"的实现（{@link #NOOP}）；</li>
 *   <li><b>失败语义要能分别定</b>：检查点写失败该不该中断整个任务？
 *       那是<b>调用方的策略</b>，不是循环的策略 —— 回调让这个选择留在外面。</li>
 * </ol>
 *
 * <h2>为什么 {@link #afterStep} 要给"完整的消息列表"</h2>
 * 因为恢复需要的就是它。<b>只给"这一步发生了什么"是不够的</b> ——
 * 那需要恢复时把前面每一步重放一遍才能重建上下文，而重放会重新执行工具
 * （对写操作是灾难；对读操作也会拿到与当时不同的数据）。
 * 给完整消息列表，恢复才能"接着跑"而不是"重跑"。
 *
 * <h2>回调抛异常会怎样</h2>
 * {@code AgentLoop} <b>不</b>兜这些异常：它们会一路冒到调用方。
 * 这是刻意的 —— 检查点写不进去意味着"下次没得恢复"，
 * 而如果循环把它吞掉继续跑，用户会以为一切正常，直到真的被杀那一刻才发现。
 * 要不要因此中断任务，由调用方决定（见 {@code ResumableAgentService} 的取舍注释）。
 */
public interface AgentProgressListener {

	/** 任务开始（在第一次模型调用之前）。 */
	default void onStart(String runId, String question) {
	}

	/**
	 * 一步结束（模型往返 + 该步的全部工具调用都已处理完）。
	 *
	 * @param runId 运行 id
	 * @param stepIndex 第几步（从 1 开始）
	 * @param step 这一步的完整记录（含模型文字、意图、执行结论）
	 * @param messages <b>这一步之后的完整对话消息</b> —— 断点恢复就靠它
	 * @param totalTokensSoFar 到这一步为止累计的 token
	 * @param toolCallsSoFar 到这一步为止累计的工具调用次数（含被拒绝的）
	 * @param elapsedMsSoFar 到这一步为止的累计耗时
	 */
	default void afterStep(String runId, int stepIndex, AgentRun.Step step, List<Message> messages,
			int totalTokensSoFar, int toolCallsSoFar, long elapsedMsSoFar) {
	}

	/** 任务结束（任何出口都会调用一次）。 */
	default void onFinish(String runId, AgentRun run) {
	}

	/** 什么都不做的实现：不需要持久化时用它（循环的单元测试、单机试玩）。 */
	AgentProgressListener NOOP = new AgentProgressListener() {
	};

	/** 工具调用结论的便捷读取（回调实现里常要按 stage 分类统计）。 */
	static String stageOf(ToolCallOutcome outcome) {
		return outcome.stage().name();
	}

	/** 工具调用意图的便捷读取。 */
	static String nameOf(ToolInvocation invocation) {
		return invocation.name();
	}

}
