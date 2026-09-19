package ai.agent.mapper;

import ai.agent.service.agent.AgentFailureCause;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.store.RunStatus;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static ai.agent.mapper.MapperRows.AgentRunRow;
import static ai.agent.mapper.MapperRows.RetrievalCallRow;
import static ai.agent.mapper.MapperRows.RetrievalHitRow;
import static ai.agent.mapper.MapperRows.StepCheckpointRow;
import static ai.agent.mapper.MapperRows.ToolCallRow;

/**
 * Agent 状态库的全部 SQL：{@code agent_run} / {@code agent_step} /
 * {@code agent_tool_invocation} / {@code retrieval_call} / {@code retrieval_hit}
 * （原先手写在 {@code JdbcAgentRunStore} 里，那个类 758 行里有 400 多行是 JDBC 样板）。
 *
 * <h2>写侧的两个"必须看返回值"</h2>
 * <ul>
 *   <li>{@link #finishRun} 返回 0 表示<b>要收尾的那次运行不存在</b>（runId 拼错、或被人删了）。
 *       静默放过的话，那次运行的状态会<b>永远停在 RUNNING</b>，而"状态一直是 RUNNING"
 *       正是最容易被当成"还在跑"的一种假象；</li>
 *   <li>{@link #claimInterrupted} 返回 0 表示<b>没抢到</b>（另一个实例先改了它）。
 *       这不是错误，但必须留痕 —— 否则"为什么这次没恢复"永远查不出来。</li>
 * </ul>
 *
 * <h2>三处 upsert 是刻意的，不要"简化"成先查再写</h2>
 * 检查点、工具调用、检索明细的写入都可能被重复执行（恢复后接着跑、调用方重试），
 * 而"先 SELECT 再决定 INSERT 还是 UPDATE"在两次执行重叠时会写出两行。
 * 让数据库按主键仲裁才是幂等的。
 */
@Mapper
public interface AgentRunMapper {

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	/** 开一次运行。状态写死 RUNNING：调用方能决定的只有"开始"，结局由 finish 写。 */
	int startRun(@Param("id") String id, @Param("question") String question, @Param("node") String node,
			@Param("intent") String intent, @Param("conversationId") String conversationId,
			@Param("tenantId") String tenantId, @Param("workspaceDir") String workspaceDir,
			@Param("permissionLevel") ai.agent.entity.agent.AgentPermissionLevel permissionLevel);

	/**
	 * 写一步的检查点（幂等 upsert）。
	 *
	 * <p>它是断点恢复的关键：{@code transcript} 存的是<b>这一步结束时</b>完整的消息列表。
	 * 没有它就只能从头重放，而那会把已经执行过的工具再执行一遍 ——
	 * 对只读工具是浪费，对写操作是事故。
	 */
	int saveCheckpoint(@Param("runId") String runId, @Param("stepIndex") int stepIndex,
			@Param("thoughtText") String thoughtText, @Param("promptTokens") int promptTokens,
			@Param("completionTokens") int completionTokens, @Param("cacheReadTokens") int cacheReadTokens,
			@Param("modelName") String modelName, @Param("promptFingerprint") String promptFingerprint,
			@Param("canaryVariant") String canaryVariant, @Param("elapsedMs") long elapsedMs,
			@Param("transcript") String transcript);

	/** 记一次工具调用（含被拒绝的）——「模型一共想调几次、其中几次没让它调」靠它。 */
	int saveToolCall(@Param("runId") String runId, @Param("stepIndex") int stepIndex,
			@Param("callIndex") int callIndex, @Param("callId") String callId,
			@Param("toolName") String toolName, @Param("arguments") String arguments,
			@Param("stage") String stage, @Param("feedback") String feedback, @Param("elapsedMs") long elapsedMs);

	/**
	 * 记一次检索的<b>调用行</b>（query / topK 属于调用，不属于任何一条命中）。
	 *
	 * <p>它必须与下面那批命中在<b>同一个事务</b>里：只有调用行没有命中行 → 一次看起来
	 * "0 条命中"的检索（那是最坏的一种：它与"真的没查到"在数据上完全一样，
	 * 于是"语料里没有"与"写库写丢了一半"分不开）；只有命中行没有调用行 →
	 * 一批没有查询词的命中，而"它拿什么词查的"正是最该回答的那个问题。
	 * 事务由 store 侧的方法级 {@code @Transactional} 提供。
	 */
	int saveRetrievalCall(@Param("runId") String runId, @Param("stepIndex") int stepIndex,
			@Param("callIndex") int callIndex, @Param("toolName") String toolName, @Param("query") String query,
			@Param("topK") int topK, @Param("hitCount") int hitCount, @Param("indexSize") int indexSize,
			@Param("elapsedMs") long elapsedMs);

	/**
	 * 记一条检索<b>命中</b>。
	 *
	 * <p>它由调用方开一个 {@code ExecutorType.BATCH} 的会话逐条调用、最后 flush
	 * （一次检索默认 5 条、上限 10 条，逐条一次往返是纯浪费）。
	 * 批处理是**写入方式**，不是"另一条 SQL" —— 所以语句只有这一条。
	 */
	int saveRetrievalHit(@Param("runId") String runId, @Param("stepIndex") int stepIndex,
			@Param("callIndex") int callIndex, @Param("rank") int rank, @Param("chunkId") String chunkId,
			@Param("documentId") String documentId, @Param("breadcrumb") String breadcrumb,
			@Param("tokenCount") int tokenCount, @Param("score") double score, @Param("excerpt") String excerpt);

	/** 只更新用量计数，不动状态 —— 它是"跑到一半也能看到花了多少"的那条路径。 */
	int updateProgress(@Param("runId") String runId, @Param("totalTokens") int totalTokens,
			@Param("toolCallCount") int toolCallCount, @Param("elapsedMs") long elapsedMs);

	/** 收尾。返回受影响行数（0 = 找不到这次运行，调用方必须炸而不是静默）。 */
	int finishRun(@Param("runId") String runId, @Param("status") RunStatus status,
			@Param("stopReason") AgentStopReason stopReason, @Param("finalAnswer") String finalAnswer,
			@Param("totalTokens") int totalTokens, @Param("toolCallCount") int toolCallCount,
			@Param("elapsedMs") long elapsedMs, @Param("error") String error,
			@Param("failureCause") AgentFailureCause failureCause);

	/**
	 * 认领本实例留下的未完成运行（乐观锁）。
	 *
	 * <p>{@code AND status = #{expected}} 是整条认领链路的关键：
	 * 两个实例同时启动时，只有改到 1 行的那个算抢到 —— 否则同一次任务会被跑两遍，
	 * 工具被执行两遍。
	 */
	int claimInterrupted(@Param("runId") String runId, @Param("status") RunStatus status,
			@Param("expected") RunStatus expected);

	/** 把某次运行标回 RUNNING（测试用：造"进程被杀"的状态）。 */
	int setRunStatus(@Param("runId") String runId, @Param("status") RunStatus status);

	/** 覆盖某一步的 transcript（测试用：手工制造"悬空的工具调用"）。返回受影响行数。 */
	int replaceTranscript(@Param("runId") String runId, @Param("stepIndex") int stepIndex,
			@Param("transcript") String transcript);

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	AgentRunRow findRun(@Param("runId") String runId);

	/** 本实例上状态为 RUNNING 的运行（认领的候选集），按更新时间升序。 */
	List<AgentRunRow> runsByStatusAndNode(@Param("status") RunStatus status, @Param("node") String node);

	List<AgentRunRow> recentRuns(@Param("limit") int limit);

	List<AgentRunRow> runsOfConversation(@Param("conversationId") String conversationId);

	/** 最新一步的检查点（{@code ORDER BY step_index DESC LIMIT 1}）。 */
	StepCheckpointRow latestCheckpoint(@Param("runId") String runId);

	List<StepCheckpointRow> stepsOf(@Param("runId") String runId);

	List<ToolCallRow> toolCallsOf(@Param("runId") String runId);

	/** 一次运行的全部命中（带 tool_name，来自 retrieval_call 的 JOIN 那一版）。 */
	List<RetrievalHitRow> retrievalHitsOf(@Param("runId") String runId);

	/** 一次运行的全部检索调用行。 */
	List<RetrievalCallRow> retrievalCallsOf(@Param("runId") String runId);

}
