package ai.agent.service.agent;

import ai.agent.entity.agent.AgentRun;
import ai.agent.entity.tool.ToolCallOutcome;
import ai.agent.entity.tool.ToolInvocation;
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
	 * @param promptFingerprint <b>这一步用的系统提示词指纹</b>（阶段四第 2 件）。
	 *        <p>它由 {@code AgentLoop} 在<b>运行开始时读一次</b>，然后每一步都传同一个值 ——
	 *        这不是偷懒，而是与热更新的粒度对齐：提示词的更换只在<b>下一次运行</b>生效
	 *        （同一次运行中途换会让前后两轮遵循不同指令，报告就没法解释"它为什么这么选"）。
	 *        如果每一步各自去读一次，那么"某一步用的是哪一版"就会在运行中途换版本时
	 *        出现同一份对话里两版混用 —— 而那正是热更新刻意避免的形态。
	 *        <p>恢复路径上它是<b>空串</b>：那一步用的是检查点里已经注入过的系统消息，
	 *        而不是本次读到的这一版。空串表示"本次运行没有读提示词"，
	 *        与"某个叫空串的版本"不会混淆
	 * @param canaryVariant 这一步所在的那次运行属于灰度实验的**哪一组**
	 *        （{@code "canary"} / {@code "stable"} / 空串 = 没参与这次实验，阶段四第 5 件）。
	 */
	default void afterStep(String runId, int stepIndex, AgentRun.Step step, List<Message> messages,
			int totalTokensSoFar, int toolCallsSoFar, long elapsedMsSoFar, String promptFingerprint,
			String canaryVariant) {
		// 【为什么新形态自带默认实现，而不是做成抽象方法】
		// 关心分流的实现只有一个（{@code PersistingListener}，它要落库），
		// 而别的实现（测试里手写的监听器、诊断用的监听器）与灰度毫无关系。
		// 默认实现丢弃它即可 —— 这比"改签名让所有实现都跟着改一遍"少了很多改错的机会，
		// 也比"在旧方法上再加一个参数"更清楚地表达了"这一项是可选的观测"。
		afterStep(runId, stepIndex, step, messages, totalTokensSoFar, toolCallsSoFar, elapsedMsSoFar,
				promptFingerprint);
	}

	/**
	 * 旧形态的一步结束回调（不带灰度分组）。
	 *
	 * <p>保留它是因为有一部分实现与灰度无关：诊断用的监听器、以及
	 * {@code AgentApprovalTest} 里只关心"闸门停在第几步"的那个手写监听器。
	 * 让它们被迫实现一个用不上的参数，只会多出改错的机会。
	 *
	 * <p>{@link AgentLoop} <b>只调用新形态</b>（带 {@code canaryVariant} 那个），
	 * 新形态默认转调本方法 —— 所以旧的实现照样工作。
	 */
	default void afterStep(String runId, int stepIndex, AgentRun.Step step, List<Message> messages,
			int totalTokensSoFar, int toolCallsSoFar, long elapsedMsSoFar, String promptFingerprint) {
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
