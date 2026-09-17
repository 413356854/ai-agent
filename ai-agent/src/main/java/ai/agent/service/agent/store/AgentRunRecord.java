package ai.agent.service.agent.store;

import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.tool.RetrievalTrace;

import java.time.Instant;
import java.util.List;

/**
 * 「一次 Agent 运行」的持久化镜像。
 *
 * <h2>为什么它不是 {@code AgentRun}（内存里那个记录）</h2>
 * 两者的生命周期完全不同：
 * <ul>
 *   <li>{@link ai.agent.service.agent.AgentRun} 是<b>结果</b>：任务结束后的完整报告，回答"它干了什么"；</li>
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
 * @param intent 业务归因键（第十轮新增，成本看板）：这次运行为哪个业务/功能花的钱。
 *            {@code null} 表示未标注 —— 刻意不用空串，因为"没标注"与"标注成了空"是两件事
 * @param conversationId <b>会话 id</b>（第十三轮新增，Agent trace 的第一层）。
 *            一次运行回答一个用户问题，而一段对话可能包含多次运行 ——
 *            trace 要能回答"这段对话里一共跑了几次、各自什么结局、一共花了多少"，
 *            靠的就是它。
 *            <p>{@code null} = **没有会话信息**（curl 直接调的、外部系统调的）。
 *            刻意不自动生成一个：给每次孤立调用编一个会话 id，会让"有会话"这件事
 *            看起来处处成立，而真相是"我们不知道这些运行属不属于同一段对话" ——
 *            那与成本看板"没标注就显示未标注"是同一条规矩。
 * @param tenantId <b>租户</b>（阶段四第 6 件新增，成本归因）：这次运行花的是<b>谁</b>的钱。
 *            {@code null} 表示未标注 —— 与 {@code intent} 同一条规矩，
 *            也<b>刻意不用空串</b>（"没标注"与"标注成了空"是两件事）。
 *            <p>它与 {@code intent} 是两个维度：本字段是"谁"，{@code intent} 是"做什么"。
 *            看板上它们各自一栏、各自一行 —— 不合成一个字段，理由见
 *            {@code agent-schema.sql} 里 tenant_id 那一段。
 */
public record AgentRunRecord(String id, String question, RunStatus status, AgentStopReason stopReason,
		String finalAnswer, int totalTokens, int toolCallCount, long elapsedMs, String node, String error,
		Instant createdAt, Instant updatedAt, ai.agent.service.agent.AgentFailureCause failureCause, String intent,
		String conversationId, String tenantId) {

	/** 不带失败细分的构造器（保持旧调用点可编译）。 */
	public AgentRunRecord(String id, String question, RunStatus status, AgentStopReason stopReason, String finalAnswer,
			int totalTokens, int toolCallCount, long elapsedMs, String node, String error, Instant createdAt,
			Instant updatedAt) {
		this(id, question, status, stopReason, finalAnswer, totalTokens, toolCallCount, elapsedMs, node, error,
				createdAt, updatedAt, null, null, null, null);
	}

	/**
	 * 带失败细分、不带归因与会话的构造器。
	 *
	 * <p>保留它是因为<b>归因与会话都是"调用方才知道的事实"</b>，而有一批调用方
	 * （测试、外部链路）确实不知道它们。让它们显式传两个 null 只会增加噪音，
	 * 且不会有任何好处 —— 这两个字段的语义本身就是"可以没有"。
	 *
	 * <p>⚠️ 租户（第 6 件）同样落进这个构造器的 {@code null}：它的性质与归因键
	 * 一字不差（"调用方在运行开始前就知道"），所以<b>不加第四个构造器</b> ——
	 * 四个重载里有两个的差别只在一个尾部字段，那正是麻烦的开始。
	 */
	public AgentRunRecord(String id, String question, RunStatus status, AgentStopReason stopReason, String finalAnswer,
			int totalTokens, int toolCallCount, long elapsedMs, String node, String error, Instant createdAt,
			Instant updatedAt, ai.agent.service.agent.AgentFailureCause failureCause) {
		this(id, question, status, stopReason, finalAnswer, totalTokens, toolCallCount, elapsedMs, node, error,
				createdAt, updatedAt, failureCause, null, null, null);
	}

	/** 失败时给页面看的下一步建议。 */
	public String failureAdvice() {
		return failureCause == null ? null : ai.agent.service.agent.ModelFailureClassifier.advice(failureCause);
	}

	/**
	 * 这次运行有没有会话信息。
	 *
	 * <p>给它一个方法而不是让三处各写一遍 {@code conversationId != null && !isBlank()}：
	 * trace 那一层、界面、断言都要这个判断，而写三遍的后果是其中一遍迟早
	 * 漏掉空白串那一半（于是"只有空格"的会话 id 会被当成一个合法会话）。
	 */
	public boolean hasConversation() {
		return conversationId != null && !conversationId.isBlank();
	}

	/**
	 * 这次运行有没有租户信息（阶段四第 6 件）。
	 *
	 * <p>与 {@link #hasConversation()} 同一个理由：这个判据同时被
	 * "成本看板要不要把它归到未标注那一行"和"页面要不要显示租户"用到，
	 * 写两遍的后果是其中一遍迟早漏掉空白串那一半
	 * —— 于是"只有空格的租户"变成一个合法的分组键，看板上出现一个叫「 」的租户。
	 */
	public boolean hasTenant() {
		return tenantId != null && !tenantId.isBlank();
	}

	/**
	 * 这次运行是不是已经结束（结束的才不该被恢复）。
	 *
	 * <p>{@code WAITING_APPROVAL} 算"已结束"—— 这里的"结束"是
	 * 「<b>这一次执行已经交出了结论</b>」，而不是"这个任务再也不会动了"。
	 * 待批的运行确实交出了结论：它说"我停在一次需要人确认的写操作上"。
	 *
	 * <p>把它算进来有一个很实际的作用：页面与恢复逻辑都用本方法筛
	 * "哪些运行还在进行中"，而待批的运行<b>并没有在进行中</b>（没有线程在跑它）。
	 * 不算进来的话，一次待批的运行会在界面上显示成"正在运行"，
	 * 而人会一直等一个根本不会自己动的东西。
	 */
	public boolean finished() {
		return status == RunStatus.ANSWERED || status == RunStatus.STOPPED || status == RunStatus.FAILED
				|| status == RunStatus.WAITING_APPROVAL;
	}

	/**
	 * 这一步是不是"可以接着跑"（只有中断的才值得恢复）。
	 *
	 * <p>待批的<b>不算</b>：它已经在正确的等待里了，不需要任何人去"救"它 ——
	 * 硬恢复它只会重新提出那个写调用、产生第二条待批。见 {@link RunStatus#WAITING_APPROVAL}。
	 */
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
	 * @param cacheReadTokens 这一步的输入里命中上游提示词缓存的部分（阶段四成本看板）。
	 *            <b>它是 {@code promptTokens} 的子集</b>；两者分别按不同单价计费。
	 *            上游不报时为 0，读的时候按"全部未命中"算 —— 方向是保守的（偏高）
	 * @param modelName 这一步用的模型名。它让**成本可以按模型归因**，
	 *            也是"换了模型之后钱花在哪"这个问题的唯一数据来源
	 * @param promptFingerprint 这一步用的**系统提示词指纹**（形如 {@code v3:9ab12cd34ef5}）。
	 *            <p>它回答的是"某天的答案变差了，是不是那天换了提示词"—— 在它之前，
	 *            提示词可以在两次运行之间换版本而**报告里看不出来**，只能靠回忆。
	 *            <p>为什么落指纹而不是只落版本号：版本号是<b>指针</b>、指纹是<b>内容</b>。
	 *            "激活了第 5 版"与"发出去的确实是第 5 版的内容"是两件事，
	 *            而后者才是阶段四第 3 件（PromptHotReloadTest）证明热更新时用的判据 ——
	 *            这里把那次的判据<b>落成数据</b>，让它在事后也能回答。
	 *            <p>空串 = 库里读不到那一版（老数据 / 提示词服务不可用）。
	 *            刻意不用 null：列有 NOT NULL DEFAULT ''，而且"空指纹"与"某个叫空串的版本"
	 *            不会混淆（后者不是一个合法指纹）
	 * @param canaryVariant 这一步所在的那次运行属于灰度实验的**哪一组**（阶段四第 5 件）。
	 *            取值为 {@code "canary"} / {@code "stable"} / 空串。
	 *            <p>为什么必须与 {@code promptFingerprint} 分开存：指纹只说明"用的是哪一版的内容"，
	 *            而实验里的<b>对照组用的也是某一版内容</b> —— 更糟的是，实验结束把候选版推全之后，
	 *            "候选版"这个身份就消失了，那次实验的两组再也分不开。
	 *            所以分组是**判定当时的一个事实**，它必须独立落库。
	 *            <p>⚠️ 空串与 {@code "stable"} 是两件事：前者是"这次实验不包含它"，
	 *            后者是"它是这次实验的对照组"。混起来会让参与率虚高 ——
	 *            而参与率虚高的直接后果是"样本够了"这个判断过早成立
	 */
	public record StepCheckpoint(String runId, int stepIndex, String transcriptJson, String thoughtText,
			int promptTokens, int completionTokens, long elapsedMs, int cacheReadTokens, String modelName,
			String promptFingerprint, String canaryVariant) {

		/** 不带成本口径的构造器（旧调用点与测试用）。 */
		public StepCheckpoint(String runId, int stepIndex, String transcriptJson, String thoughtText,
				int promptTokens, int completionTokens, long elapsedMs) {
			this(runId, stepIndex, transcriptJson, thoughtText, promptTokens, completionTokens, elapsedMs, 0, null,
					"", "");
		}

		/** 带成本口径、但不带灰度分组的构造器（第十三轮及之前的调用点与测试用）。 */
		public StepCheckpoint(String runId, int stepIndex, String transcriptJson, String thoughtText,
				int promptTokens, int completionTokens, long elapsedMs, int cacheReadTokens, String modelName,
				String promptFingerprint) {
			this(runId, stepIndex, transcriptJson, thoughtText, promptTokens, completionTokens, elapsedMs,
					cacheReadTokens, modelName, promptFingerprint, "");
		}

		public StepCheckpoint {
			// 后加字段的默认值放在这里，与 ToolCallOutcome 同一条做法：
			// 让"没传指纹"落成一个明确的空串，而不是一个会在渲染到那一行时才炸的 null
			modelName = modelName == null ? "" : modelName;
			promptFingerprint = promptFingerprint == null ? "" : promptFingerprint;
			canaryVariant = canaryVariant == null ? "" : canaryVariant;
		}
	}

	/** 一次工具调用的落库记录（含被拒绝的）。 */
	public record ToolCallRecord(String runId, int stepIndex, int callIndex, String callId, String toolName,
			String arguments, String stage, String feedback, long elapsedMs) {
	}

	/**
	 * <b>一次检索调用</b>的落库记录（阶段四 trace 的「检索」那一层）。
	 *
	 * <h2>为什么它与 {@link ToolCallRecord} 分开，而不是给它加几列</h2>
	 * 因为粒度不同，而这一层的全部价值就在粒度上：
	 * {@code ToolCallRecord} 是<b>一次调用</b>（主键 {@code (run, step, call)}），
	 * 而一次检索调用会带 N 条命中（默认 5、上限 10）。
	 * 挤进同一条记录只有两种下场：把命中拼成 JSON 存一列（等于把结构化数据退化成字符串，
	 * 正是这一层要消灭的东西），或者把回填文本顶爆（它有 {@code max-chars} 上限）。
	 *
	 * <h2>为什么"调用"与"命中"是两张表</h2>
	 * 因为 {@code query} 与 {@code topK} 属于<b>调用</b>、不属于任何一条命中。
	 * 把它们冗余到每一行命中上，会在<b>0 条命中时整个丢失</b> ——
	 * 而"它拿什么词去查的、一条都没查到吗"恰恰是最需要查的那一次。
	 *
	 * @param runId 运行 id
	 * @param stepIndex 第几步发起的检索
	 * @param callIndex 这一步里的第几次工具调用（与 {@link ToolCallRecord#callIndex()} 同一套编号）
	 * @param toolName 哪把工具发起的（目前只有 {@code search_docs}，
	 *            但记下来才能回答"将来的第二把检索工具用得怎么样"）
	 * @param trace 检索明细本身
	 */
	public record RetrievalRecord(String runId, int stepIndex, int callIndex, String toolName, RetrievalTrace trace) {
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
