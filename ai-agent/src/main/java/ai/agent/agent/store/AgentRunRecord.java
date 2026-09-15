package ai.agent.agent.store;

import ai.agent.agent.AgentStopReason;

import java.time.Instant;
import java.util.List;

/**
 * 「一次 Agent 运行」的持久化镜像。
 *
 * <h2>为什么它不是 {@code AgentRun}（内存里那个记录）</h2>
 * 两者的生命周期完全不同：
 * <ul>
 *   <li>{@link ai.agent.agent.AgentRun} 是<b>结果</b>：任务结束后的完整报告，回答"它干了什么"；</li>
 *   <li>本记录是<b>过程</b>：任务还在跑的时候就要能读能写，回答"它现在到哪一步了、能不能接着跑"。</li>
 * </ul>
 * 把它们合成一个类，就会逼着"运行中的状态"去填一堆结果字段（答案、stopReason），
 * 而那些字段在运行期<b>本来就该是空的</b> —— 于是到处都是 null 判断，
 * 而"答案是 null 是因为还没跑完，还是因为跑完了但没答出来"就分不清了。
 *
 * @param id 运行 id（恢复时的对账凭据）
 * @param question 用户原始问题
 * @param status 当前状态
 * @param stopReason 走到哪个出口；未结束时为 {@code null}
 * @param finalAnswer 最终答案；未结束或被上限截断时为 {@code null}
 * @param totalTokens 累计 token
 * @param toolCallCount 一共提出过几次工具调用（**含被拒绝的**，理由见踩坑库 #56）
 * @param elapsedMs 累计耗时
 * @param node 哪个实例在跑。多实例部署时，靠它回答"这是谁挂的"
 * @param error 失败原因；{@code status=FAILED} 时非空
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 * @param failureCause 失败细分原因（第七轮新增）。老数据为 null —— 这正是"加列不破坏旧行"的好处
 */
public record AgentRunRecord(String id, String question, RunStatus status, AgentStopReason stopReason,
		String finalAnswer, int totalTokens, int toolCallCount, long elapsedMs, String node, String error,
		Instant createdAt, Instant updatedAt, ai.agent.agent.AgentFailureCause failureCause) {

	/** 不带失败细分的构造器（保持旧调用点可编译）。 */
	public AgentRunRecord(String id, String question, RunStatus status, AgentStopReason stopReason, String finalAnswer,
			int totalTokens, int toolCallCount, long elapsedMs, String node, String error, Instant createdAt,
			Instant updatedAt) {
		this(id, question, status, stopReason, finalAnswer, totalTokens, toolCallCount, elapsedMs, node, error,
				createdAt, updatedAt, null);
	}

	/** 失败时给页面看的下一步建议。 */
	public String failureAdvice() {
		return failureCause == null ? null : ai.agent.agent.ModelFailureClassifier.advice(failureCause);
	}

	/** 这次运行是不是已经结束（结束的才不该被恢复）。 */
	public boolean finished() {
		return status == RunStatus.ANSWERED || status == RunStatus.STOPPED || status == RunStatus.FAILED;
	}

	/** 这一步是不是"可以接着跑"（只有中断的才值得恢复）。 */
	public boolean resumable() {
		return status == RunStatus.INTERRUPTED;
	}

	/**
	 * 检查点的镜像：**这一步结束时**完整的对话消息（JSON）+ 已累计的用量。
	 *
	 * <p>恢复就靠它 —— 有它在，恢复时不需要重放前面任何一步，也就不需要知道
	 * "前面那些工具当时返回了什么"。这一点很重要：工具的输出（尤其是依赖外部系统的那些）
	 * 可能已经变了，重放会得到与原来不同的上下文。
	 *
	 * @param stepIndex 这是第几步（从 1 开始）
	 * @param transcriptJson 完整消息列表的 JSON（OpenAI 的 role/content/tool_calls 形态）
	 * @param thoughtText 这一步模型的文字输出
	 * @param promptTokens 这一步的输入 token
	 * @param completionTokens 这一步的输出 token
	 * @param elapsedMs 这一步耗时
	 */
	public record StepCheckpoint(String runId, int stepIndex, String transcriptJson, String thoughtText,
			int promptTokens, int completionTokens, long elapsedMs) {
	}

	/** 一次工具调用的落库记录（含被拒绝的）。 */
	public record ToolCallRecord(String runId, int stepIndex, int callIndex, String callId, String toolName,
			String arguments, String stage, String feedback, long elapsedMs) {
	}

	/** 恢复一个运行所需要的全部东西：记录本身 + 检查点 + 已发生的工具调用。 */
	public record Checkpoint(AgentRunRecord run, StepCheckpoint latestStep, List<ToolCallRecord> toolCalls) {

		public Checkpoint {
			toolCalls = List.copyOf(toolCalls);
		}

		/** 从第几步之后接着跑（0 表示一次都没跑过，从第 1 步开始）。 */
		public int resumeFromStep() {
			return latestStep == null ? 0 : latestStep.stepIndex();
		}

		/** 有没有可用的检查点（有才能恢复；没有就只能从头跑）。 */
		public boolean hasTranscript() {
			return latestStep != null && latestStep.transcriptJson() != null
					&& !latestStep.transcriptJson().isBlank();
		}
	}

}
