package ai.agent.agent.store;

import ai.agent.agent.AgentRun;
import ai.agent.agent.AgentStopReason;
import ai.agent.agent.ModelFailureClassifier;

import java.util.List;
import java.util.Optional;

/**
 * Agent 运行状态的存储契约（阶段三：状态持久化 + 断点恢复）。
 *
 * <h2>为什么先有接口，而不是直接用 JdbcTemplate 写</h2>
 * 三个具体的理由，都不是"为了抽象而抽象"：
 * <ol>
 *   <li><b>恢复逻辑要能被确定性地测试</b>：`"进程被杀"这个场景本身没法在单测里真造</b> ——
 *       但"库里留着一条 RUNNING 的行、当前进程里没有它"是可以造出来的。
 *       有了接口，测试就能直接构造那个状态（不用真的去 kill 一个进程）；</li>
 *   <li><b>实现方式已知会换</b>：现在是纯 JDBC（因为 spring-boot-starter-jdbc 4.x 拿不到，
 *       理由写在 pom 的注释里）。等能引 JdbcTemplate 时，替换面应该只有实现类一个文件；</li>
 *   <li><b>内存实现本身就是有用的</b>：跑单测、跑没有数据库的环境时不该被迫起 H2。</li>
 * </ol>
 *
 * <h2>为什么每个方法都显式带 runId</h2>
 * 不做"当前运行"这种隐式状态：这个类会被多线程（虚拟线程）并发调用，
 * 而隐式状态在并发下就是串味（这个坑在阶段一的钉住实验里踩过：
 * 一个"只在第一次算"的字段在真并发下会串）。
 */
public interface AgentRunStore {

	/**
	 * 开一次运行并记下它的<b>全部启动元信息</b>：节点、业务归因键、会话 id。
	 *
	 * <h2>为什么"开一次运行"的参数被包成了一个 {@link Start}，而不是继续加参数</h2>
	 * 这是这一轮唯一一处刻意的接口改动，理由很具体：这些字段<b>全是同一类东西</b> ——
	 * 调用方在运行开始之前就知道、运行自己推不出来的事实。它们已经有两个了
	 * （归因键、会话 id），而"按租户细分成本"那条已排在阶段四第 6 件，很快是三个。
	 *
	 * <p>继续加重载的代价在这一轮已经出现过一次预演：一个
	 * {@code start(id, question, node, "会话A")} 的调用在"第四个参数是归因键"的
	 * 重载下可以编译通过，于是那个会话 id 被<b>静默写进归因列</b> ——
	 * 现象是成本看板上多出一个叫「会话A」的业务，而"按会话串起来"一次都不生效。
	 * 两边都不报错，也没有任何断言会自然覆盖到。
	 * 包成对象之后，这种写错在<b>编译期</b>就不可能发生（字段有名字）。
	 *
	 * <h2>为什么这次写必须发生在"跑"之前</h2>
	 * 如果等结束才写，那么"进程在第一步就挂了"这件事在库里<b>不留任何痕迹</b> ——
	 * 用户看到的现象是"点了没反应，也没有记录"，而这正是断点恢复要解决的第一个问题。
	 * 同理，失败与中断的运行<b>同样花了钱</b>，看板上不能凭空少一块。
	 *
	 * @param start 启动元信息（见 {@link Start}）
	 */
	void start(Start start);

	/**
	 * 「一次运行开始时的全部已知事实」。
	 *
	 * <h2>为什么 node 也在这里面</h2>
	 * 因为它的性质与另外两个完全相同：都是调用方在开始时就确定、运行自己推不出来的。
	 * 让它单独留在参数列表里会破坏这个类存在的理由（"所有启动元信息在一处"）。
	 *
	 * @param runId 运行 id（恢复时的对账凭据）
	 * @param question 用户原始问题
	 * @param node 哪个实例在跑（多实例下回答"这是谁挂的"）
	 * @param intent 业务归因键（例如"客服问答"/"报表助手"）；{@code null} 或空白 = 未标注
	 * @param conversationId 会话 id；{@code null} 或空白 = **没有会话信息**
	 *            （curl 直接调、外部系统调）。**刻意不自动生成**：给每次孤立调用编一个
	 *            会话 id 会让"有会话"看起来处处成立，而真相是我们不知道它们属不属于同一段对话
	 */
	record Start(String runId, String question, String node, String intent, String conversationId) {

		/**
		 * 把空白字符串一律归一成 {@code null}。
		 *
		 * <p>理由与归因键那一条相同，而且更硬：**"没标注"与"标注成了空"是两件事**，
		 * 而"只有空格的会话 id"比两者都糟 —— 它既不是"没有会话信息"，
		 * 也不是一个能用来分组的键（所有只打了空格的运行会挤在同一组里，
		 * 看起来像"这些人属于同一段对话"）。
		 * 归一化只做一次，做在构造期：让下游任何一处都不必再判 blank。
		 */
		public Start {
			intent = blankToNull(intent);
			conversationId = blankToNull(conversationId);
		}

		/** 同一个节点模板，换一次运行的 id 与问题（服务层为每次运行派生一个）。 */
		public Start forRun(String runId, String question) {
			return new Start(runId, question, node, intent, conversationId);
		}

		private static String blankToNull(String value) {
			return value == null || value.isBlank() ? null : value;
		}
	}

	/**
	 * 写一个检查点：这一步的完整对话 + 累计用量。
	 *
	 * <p>调用时机在 {@code AgentLoop} 每一步**成功之后**。粒度选"每步"而不是"每次工具调用"：
	 * 每步已经包含了那一步的全部工具调用，而更细的粒度换来的只是"少重放一个工具"，
	 * 代价是写库次数翻几倍。
	 */
	void checkpoint(AgentRunRecord.StepCheckpoint checkpoint);

	/** 记一次工具调用（**含被拒绝的**）。 */
	void recordToolCall(AgentRunRecord.ToolCallRecord record);

	/**
	 * 记一次<b>检索</b>的明细（阶段四 trace 的「检索」那一层）。
	 *
	 * <h2>为什么它挂在 {@link #recordToolCall} 旁边，而不是被它包着</h2>
	 * 因为粒度不同，而粒度正是这一层存在的理由：一次工具调用是<b>一条</b>记录，
	 * 一次检索会带 N 条命中（默认 5、上限 10）。前者是一行，后者是一组行。
	 *
	 * <h2>为什么调用方必须先判"这次是不是检索"</h2>
	 * 不是检索的调用<b>不该</b>产生任何行。写一个"空明细"占位会让
	 * "这次任务检索了几次"这个数字变大 —— 而它恰恰是这一层最常被问的数。
	 * 判据是 {@code outcome.retrievalTrace().isPresent()}，见 {@code PersistingListener}。
	 */
	void recordRetrieval(AgentRunRecord.RetrievalRecord record);

	/**
	 * 读某次运行的全部检索明细（按步、按调用、按名次）。
	 *
	 * <p>返回的是<b>已按调用归组</b>的形状，而不是一个摊平的命中列表：
	 * 页面上要显示的正是"第 2 步的第一次检索查了什么词、命中哪 5 条"，
	 * 而摊平之后调用与命中的关系要靠调用方自己按 (step, call) 重新分组 ——
	 * 那种分组写两遍就会出现两处不一致（一处把空命中的调用丢了，一处没丢）。
	 * 所以分组做在存储这一侧，只做一次。
	 */
	List<AgentRunRecord.RetrievalRecord> retrievalsOf(String runId);

	/** 把一次运行的累计用量更新到库里（不改变状态）。 */
	void updateProgress(String runId, int totalTokens, int toolCallCount, long elapsedMs);

	/** 收尾：写最终状态 / 答案 / 停止原因 / 失败细分。 */
	void finish(String runId, RunStatus status, AgentStopReason stopReason, String finalAnswer, int totalTokens,
			int toolCallCount, long elapsedMs, String error, ai.agent.agent.AgentFailureCause failureCause);

	/** 收尾（不带失败细分）—— 非失败路径用它，省掉一处写 null 的噪音。 */
	default void finish(String runId, RunStatus status, AgentStopReason stopReason, String finalAnswer,
			int totalTokens, int toolCallCount, long elapsedMs, String error) {
		finish(runId, status, stopReason, finalAnswer, totalTokens, toolCallCount, elapsedMs, error, null);
	}

	/** 读一次运行。 */
	Optional<AgentRunRecord> find(String runId);

	/** 读某次运行的全部工具调用（按步、按序）。 */
	List<AgentRunRecord.ToolCallRecord> toolCallsOf(String runId);

	/**
	 * 读某次运行**最新的检查点**。
	 *
	 * <p>取最新而不是第一个：恢复要接着最后一步往下跑，
	 * 而"用最旧的检查点恢复"会让已经跑过的步骤再跑一遍（对写操作是灾难）。
	 */
	Optional<AgentRunRecord.StepCheckpoint> latestCheckpoint(String runId);

	/** 拼出恢复所需的完整上下文。 */
	default Optional<AgentRunRecord.Checkpoint> loadCheckpoint(String runId) {
		return find(runId).map(run -> new AgentRunRecord.Checkpoint(run,
				latestCheckpoint(runId).orElse(null), toolCallsOf(runId)));
	}

	/**
	 * 找出**上次进程留下的**未完成运行，并标记为 {@code INTERRUPTED}。
	 *
	 * <p>这是"进程被杀"这个场景的入口，也是本接口里最需要解释的一个方法：
	 * <b>它不是在查"有没有失败的任务"，而是在做一次"上次启动遗留物"的推断</b> ——
	 * 库里 {@code status=RUNNING} 的行一律被当成"上次没跑完"，
	 * 因为**当前进程刚启动，不可能有正在跑的运行**。
	 *
	 * <p>⚠️ 这个推断在多实例部署下会出错：A 实例正在跑的运行，B 实例启动时会把它
	 * 误判成中断。所以参数里有 {@code node}，只认领**本实例**留下的那些
	 * （这正是 {@code agent_run.node} 这一列存在的理由）。
	 *
	 * @param node 当前实例标识
	 * @return 被标记为中断的运行（调用方可以据此决定要不要自动恢复）
	 */
	List<AgentRunRecord> markInterruptedRuns(String node);

	/** 从查询结果里筛出可以直接恢复的（有检查点的）。 */
	default List<AgentRunRecord> findResumable(String node) {
		return markInterruptedRuns(node).stream().filter(AgentRunRecord::resumable).toList();
	}

	/**
	 * 把一次已经结束的运行重新标成"可恢复"—— <b>只给测试与运维手工用</b>。
	 *
	 * <p>存在的理由很具体：测试要模拟"跑到第 2 步时进程被杀"，
	 * 而那需要把库里的行改回 RUNNING/INTERRUPTED 再让恢复逻辑去认领。
	 * 没有这个方法，测试就只能去反射改私有字段 —— 那种测试在重构时会静默失效。
	 */
	void markInterruptedForTesting(String runId);

	/**
	 * 把某一步的**检查点本身**改掉（只给测试用）。
	 *
	 * <p>为什么需要它：要模拟"进程死在一个工具调用进行中"，
	 * 就必须造出"assistant 消息里已经提出了 tool_calls，但**没有**对应的 tool 返回"
	 * 这种上下文 —— 而那正是被杀那一刻库里的真实样子。
	 * 正常路径写不出这种记录（检查点总在工具都处理完之后才写），
	 * 所以只能由测试手工构造。
	 */
	void replaceStepTranscriptForTesting(String runId, int stepIndex, String transcriptJson);

	/** 全部运行（按创建时间倒序）——给界面与排查用。 */
	List<AgentRunRecord> recentRuns(int limit);

	/**
	 * 同一个<b>会话</b>里的全部运行（按创建时间正序 —— 对话的时间顺序）。
	 *
	 * <h2>为什么按正序，而 {@link #recentRuns} 按倒序</h2>
	 * 两者要回答的问题不同：列表页问"最近发生了什么"（倒序），
	 * 而会话里问"这段对话是怎么一步步走到这里的"（正序）。
	 * 混用会让排查时把因果读反 —— 而那种错不会报错，只是结论反过来。
	 *
	 * @param conversationId 会话 id；空白时返回空列表（**不是**"返回全部运行"）——
	 *            一个空白键如果被当成"匹配所有"，那这个方法的调用方会在
	 *            某次传参写错时得到一份看起来正常的完整列表
	 */
	List<AgentRunRecord> runsOfConversation(String conversationId);

	/** 一次运行的全部步骤检查点（按步序）——用于还原执行过程（阶段四 Agent trace 的基础）。 */
	List<AgentRunRecord.StepCheckpoint> stepsOf(String runId);

	/**
	 * 从一份内存里的 {@link AgentRun} 补写完整的 trace。
	 *
	 * <p>给"正常跑完"的路径用：正常结束时 trace 已经在内存里了，
	 * 不必在每一步都写一遍 transcript（那是恢复才需要的）。
	 */
	default void persistCompletedRun(String runId, AgentRun run) {
		RunStatus status = statusOf(run);
		// 失败原因要落库：页面靠它回答"是上游挂了还是我们自己太挤"，
		// 而那正是 MODEL_ERROR 这一个出口分不开的两件事
		String error = run.failureCause() == null ? null : ModelFailureClassifier.advice(run.failureCause());
		finish(runId, status, run.stopReason(), run.finalAnswer(), run.totalTokens(), run.toolCallCount(),
				run.elapsedMs(), error, run.failureCause());
	}

	/**
	 * 停因 → 库状态。<b>抽成静态方法，是为了让这条映射规则能被单独断言。</b>
	 *
	 * <p>理由很实际：要真的跑出 {@code WAITING_FOR_APPROVAL}，审批时限必须长于
	 * 整个测试的时间预算（那意味着用分钟级的等待去验证一条 if-else）。
	 * 把规则抽出来之后，测试可以对它逐条断言，而<b>运行时走的还是同一段代码</b>
	 * （{@link #persistCompletedRun} 就是调它）—— 这与本项目
	 * "把判据放在能被单独验证的地方"的一贯做法一致。
	 *
	 * <p>另一条同样重要：这个 switch <b>没有 default</b>。
	 * 将来给 {@code AgentStopReason} 加一个新出口时，编译器会逼着这里回答
	 * "它算哪种状态"—— 而不是静默落进一个默认分支（那种漏法表现为
	 * "新出口的运行在界面上显示成正在跑"）。
	 */
	static RunStatus statusOf(AgentRun run) {
		return switch (run.stopReason()) {
			case ANSWERED -> RunStatus.ANSWERED;
			case MAX_STEPS, MAX_TOKENS, MAX_DURATION, DUPLICATE_ACTION, EMPTY_RESPONSE -> RunStatus.STOPPED;
			// 【HITL】停在人工闸门上：既不是结束也不是故障，而是一个**活着的等待状态**。
			// 写成 RUNNING 的话，下次启动的认领逻辑会把它当"上次没跑完"去自动恢复，
			// 于是同一个写调用会被重新提出、产生第二条待批（理由见 RunStatus 的注释）
			case WAITING_FOR_APPROVAL -> RunStatus.WAITING_APPROVAL;
			case MODEL_ERROR -> RunStatus.FAILED;
		};
	}

}
