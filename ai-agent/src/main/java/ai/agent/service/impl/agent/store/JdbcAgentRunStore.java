package ai.agent.service.impl.agent.store;

import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.entity.tool.RetrievalTrace;
import ai.agent.mapper.AgentRunMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.agent.AgentFailureCause;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.service.agent.store.RunStatus;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AgentRunStore} 的存储实现 —— <b>SQL 在 {@link AgentRunMapper} 里，本类只管事务边界与语义</b>。
 *
 * <h2>迁移后这个类还剩什么（对照它以前的样子）</h2>
 * 它原先 758 行，其中大部分是 `Connection` / `PreparedStatement` / `setXxx(位置, 值)` /
 * `ResultSet` 映射的样板。现在：
 * <ul>
 *   <li><b>两处手写事务改成了 {@code @Transactional}</b>（{@link #recordRetrieval}、
 *       {@link #markInterruptedRuns}）。原先的写法是
 *       {@code setAutoCommit(false)} + 显式 commit/rollback + finally 里
 *       {@code setAutoCommit(true)} —— 最后那一步是**必须的**（它把连接还给池子时得是干净的），
 *       而漏掉它不会报错，只会让下一个借到这条连接的线程**莫名处在手动事务里**。
 *       交给 Spring 之后这一类"忘了复位"在结构上就不存在了；</li>
 *   <li><b>池与建表不再由本类负责</b>：连接来自全应用唯一的 {@code DataSource}
 *       （{@code AgentStoreConfig}），表由 {@code spring.sql.init} 建。
 *       原先的 {@code pool()} 与 {@code @PreDestroy close()} 已删除 ——
 *       删 {@code pool()} 的连带影响是"借池"那 5 个类改为注入各自的 mapper；</li>
 *   <li>映射不再是手写的 {@code mapRun} / {@code mapStep}，而是 XML 里的
 *       {@code <resultMap>}（仍在同一处声明列与字段的对应关系，只是可被工具检查）。</li>
 * </ul>
 *
 * <h2>关于"检查点写失败"这件事的取舍（未变）</h2>
 * 写检查点失败时，本类<b>抛异常而不是吞掉</b>。理由是"静默失败"在这里的代价特别大：
 * 恢复能力是"进程被杀时才用到"的功能，如果写库一直在失败而没人知道，
 * 那么真正被杀的那一刻才会发现"没有检查点可恢复"—— 而那时已经丢掉了整次运行。
 * 宁可现在就把调用方打断。
 */
@Component
public class JdbcAgentRunStore implements AgentRunStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcAgentRunStore.class);

	private final AgentRunMapper mapper;

	/**
	 * 只为一件小事存在：检索命中要<b>批量写</b>（一次检索 5~10 条）。
	 *
	 * <p>不把批处理藏进 mapper：MyBatis 的批处理是**会话的执行模式**，不是另一条 SQL。
	 * 开一个 {@code ExecutorType.BATCH} 的会话、逐条调 mapper、最后 flush ——
	 * 这条路径写在下面 {@link #recordRetrieval} 里，读代码时看得见。
	 */
	private final SqlSessionFactory sqlSessionFactory;

	public JdbcAgentRunStore(AgentRunMapper mapper, SqlSessionFactory sqlSessionFactory) {
		this.mapper = mapper;
		this.sqlSessionFactory = sqlSessionFactory;
		log.info("Agent 状态库就绪（表由 spring.sql.init 建好，连接来自全应用唯一的 DataSource）");
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@Override
	public void start(Start start) {
		try {
			mapper.startRun(start.runId(), start.question(), start.node(), start.intent(), start.conversationId(),
					start.tenantId(), start.workspaceDir(), start.permissionLevel());
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("开一次 Agent 运行失败 runId=" + start.runId(), e);
		}
	}

	@Override
	public void checkpoint(AgentRunRecord.StepCheckpoint checkpoint) {
		try {
			mapper.saveCheckpoint(checkpoint.runId(), checkpoint.stepIndex(), checkpoint.thoughtText(),
					checkpoint.promptTokens(), checkpoint.completionTokens(), checkpoint.cacheReadTokens(),
					// 这三个空串由 StepCheckpoint 的紧凑构造器归一过（DDL 里是 NOT NULL DEFAULT ''）：
					// 空串是"取不到"，与"某个叫空串的模型/版本/分组"不会混淆。存 NULL 则要多一处判断
					checkpoint.modelName(), checkpoint.promptFingerprint(), checkpoint.canaryVariant(),
					checkpoint.elapsedMs(), checkpoint.transcriptJson());
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("写检查点失败 runId=" + checkpoint.runId() + " step="
					+ checkpoint.stepIndex(), e);
		}
	}

	@Override
	public void recordToolCall(AgentRunRecord.ToolCallRecord record) {
		try {
			mapper.saveToolCall(record.runId(), record.stepIndex(), record.callIndex(), record.callId(),
					record.toolName(), record.arguments(), record.stage(), record.feedback(), record.elapsedMs());
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("记工具调用失败 runId=" + record.runId() + " step=" + record.stepIndex(), e);
		}
	}

	/**
	 * 写一次检索的明细：<b>一行调用 + N 行命中</b>，全在一个事务里。
	 *
	 * <h2>为什么必须在一个事务里</h2>
	 * 两半各自都写得进去，所以"分开写"在本地永远看不出问题 ——
	 * 但半截数据是有具体后果的：
	 * <ul>
	 *   <li>只有命中行、没有调用行 → 页面上出现一批<b>没有查询词的命中</b>，
	 *       而"它拿什么词查的"正是最该回答的那个问题；</li>
	 *   <li>只有调用行、没有命中行 → 一次<b>看起来"0 条命中"</b>的检索。
	 *       那是最坏的一种：它与"真的没查到"在数据上完全一样，
	 *       于是"语料里没有"与"写库写丢了一半"分不开。</li>
	 * </ul>
	 * 后者尤其危险，因为它是**静默**的：0 条命中本来就是一个正常结果。
	 *
	 * <h2>为什么命中走批处理会话</h2>
	 * 一次检索默认 5 条、上限 10 条。这个批会话会加入上面那个事务
	 * （MyBatis-Spring 的事务同步），所以 flush 出去的语句仍然在同一个事务里 ——
	 * 出异常时一起回滚，不会留下"调用行没了、命中行还在"的半截状态。
	 */
	@Override
	@Transactional
	public void recordRetrieval(AgentRunRecord.RetrievalRecord record) {
		RetrievalTrace trace = record.trace();
		// 工具名 NOT NULL DEFAULT ''：空串是"取不到名字"，与"某把叫空串的工具"不会混淆
		String toolName = record.toolName() == null ? "" : record.toolName();
		try {
			mapper.saveRetrievalCall(record.runId(), record.stepIndex(), record.callIndex(), toolName,
					trace.query(), trace.topK(), trace.hitCount(), trace.indexSize(),
					// 落的是**检索自己**的耗时，不是整个工具调用的耗时：后者含参数解析与闸门排队，
					// 而"检索花了多久"是另一个问题（工具调用那个数已经在 agent_tool_invocation 里了）
					trace.elapsedMs());
			if (trace.hitCount() > 0) {
				// 0 条命中时不执行批：`flushStatements` 在空批上是合法的（返回空列表），
				// 但显式跳过能让"这次检索真的没有命中"这件事在代码里也读得出来
				writeHitsInBatch(record, trace, toolName);
			}
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("记检索明细失败 runId=" + record.runId() + " step=" + record.stepIndex()
					+ " call=" + record.callIndex(), e);
		}
	}

	private void writeHitsInBatch(AgentRunRecord.RetrievalRecord record, RetrievalTrace trace, String toolName) {
		try (SqlSession batch = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
			AgentRunMapper batchMapper = batch.getMapper(AgentRunMapper.class);
			for (RetrievalTrace.Hit hit : trace.hits()) {
				batchMapper.saveRetrievalHit(record.runId(), record.stepIndex(), record.callIndex(), hit.rank(),
						hit.chunkId(), hit.documentId(), hit.breadcrumb(), hit.tokenCount(), hit.score(),
						hit.excerpt());
			}
			batch.flushStatements();
		}
	}

	@Override
	public void updateProgress(String runId, int totalTokens, int toolCallCount, long elapsedMs) {
		try {
			mapper.updateProgress(runId, totalTokens, toolCallCount, elapsedMs);
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("更新进度失败 runId=" + runId, e);
		}
	}

	@Override
	public void finish(String runId, RunStatus status, AgentStopReason stopReason, String finalAnswer, int totalTokens,
			int toolCallCount, long elapsedMs, String error, AgentFailureCause failureCause) {
		try {
			int updated = mapper.finishRun(runId, status, stopReason, finalAnswer, totalTokens, toolCallCount,
					elapsedMs, error, failureCause);
			if (updated == 0) {
				// 收尾一个不存在的运行 = 有人在别处删了它，或者 runId 拼错了。
				// 静默返回会让"状态一直是 RUNNING"这件事永远查不出来
				throw new AgentStoreException("收尾失败：找不到运行 runId=" + runId);
			}
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("收尾失败 runId=" + runId, e);
		}
	}

	/**
	 * 认领本实例留下的未完成运行。
	 *
	 * <p>分两步：先查出来，再逐条改成 INTERRUPTED。
	 *
	 * <p><b>为什么查与改要在一个事务里</b>：如果两个实例同时启动，
	 * 查完之后各自去改，两边都会"认领"到同一批 —— 然后同一次任务被跑两遍，
	 * 工具被执行两遍。用"改的时候带上 {@code status='RUNNING'} 条件"来保证只有一个赢家
	 * （乐观锁），这是没有行锁时最省事也最可靠的做法。
	 *
	 * <p>认领成功的那条记录是**从候选记录派生的**（只换状态与 updatedAt），
	 * 而不是重新拼一遍字段：拼字段的写法每加一列都要在这里补一次，
	 * 而漏掉一个同类型的 String（tenantId 与 intent 之间）**编译得过**，
	 * 后果是被认领的运行丢掉租户、成本从"某个租户"悄悄挪到"未标注租户"。
	 */
	@Override
	@Transactional
	public List<AgentRunRecord> markInterruptedRuns(String node) {
		List<MapperRows.AgentRunRow> candidates;
		try {
			// 候选集先按"数据库行"取回来（mapper 只认行类型），逐条转换到 domain 再判断
			candidates = mapper.runsByStatusAndNode(RunStatus.RUNNING, node);
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("认领未完成运行失败 node=" + node, e);
		}

		List<AgentRunRecord> claimed = new ArrayList<>();
		for (MapperRows.AgentRunRow candidateRow : candidates) {
			AgentRunRecord candidate = candidateRow.toDomain();
			int updated;
			try {
				updated = mapper.claimInterrupted(candidate.id(), RunStatus.INTERRUPTED, RunStatus.RUNNING);
			}
			catch (DataAccessException e) {
				throw new AgentStoreException("认领未完成运行失败 node=" + node + " runId=" + candidate.id(), e);
			}
			if (updated == 1) {
				// 【为什么要从候选记录派生、而不是重新拼一遍字段】
				// 拼字段的写法每加一列都要在这里补一次，而漏掉一个同类型的 String
				// （tenantId 与 intent 之间、workspaceDir 与 tenantId 之间）**编译得过**，
				// 后果是被认领的运行丢掉那一列 —— 比如丢掉工作区与权限档位，
				// 于是"恢复之后用哪一档接着跑"这个问题会得到一个错的答案。
				// 所以这里是"整条记录复制 + 只改状态与 updatedAt"：
				// 每一列都显式出现在构造调用里，漏一列时编译器会报"参数不匹配"
				claimed.add(new AgentRunRecord(candidate.id(), candidate.question(), RunStatus.INTERRUPTED,
						candidate.stopReason(), candidate.finalAnswer(), candidate.totalTokens(),
						candidate.toolCallCount(), candidate.elapsedMs(), candidate.node(), candidate.error(),
						candidate.createdAt(), Instant.now(), candidate.failureCause(), candidate.intent(),
						candidate.conversationId(), candidate.tenantId(), candidate.workspaceDir(),
						candidate.permissionLevel()));
			}
			else {
				// 没抢到：说明另一个实例（或另一次启动）先改了它。
				// 这不是错误，但必须留痕 —— 否则"为什么这次没恢复"永远查不出来
				log.info("未认领运行 {}（已被其它实例改为非 RUNNING）", candidate.id());
			}
		}

		if (!claimed.isEmpty()) {
			log.warn("发现 {} 次上次未跑完的 Agent 运行（已标记 INTERRUPTED）：{}", claimed.size(),
					claimed.stream().map(AgentRunRecord::id).toList());
		}
		return List.copyOf(claimed);
	}

	@Override
	public void markInterruptedForTesting(String runId) {
		try {
			mapper.setRunStatus(runId, RunStatus.RUNNING);
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("标记测试状态失败 runId=" + runId, e);
		}
	}

	@Override
	public void replaceStepTranscriptForTesting(String runId, int stepIndex, String transcriptJson) {
		try {
			int updated = mapper.replaceTranscript(runId, stepIndex, transcriptJson);
			if (updated == 0) {
				throw new AgentStoreException(
						"要替换的检查点不存在 runId=" + runId + " step=" + stepIndex + "（先让它跑到那一步）");
			}
		}
		catch (DataAccessException e) {
			throw new AgentStoreException("替换检查点失败 runId=" + runId, e);
		}
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public Optional<AgentRunRecord> find(String runId) {
		MapperRows.AgentRunRow row = read(() -> mapper.findRun(runId), "读运行失败 runId=" + runId);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public List<AgentRunRecord.ToolCallRecord> toolCallsOf(String runId) {
		return read(() -> mapper.toolCallsOf(runId).stream().map(MapperRows.ToolCallRow::toDomain).toList(),
				"读工具调用失败 runId=" + runId);
	}

	/**
	 * 读一次运行的全部检索明细，<b>按调用归组</b>。
	 *
	 * <h2>为什么用两条查询 + 内存里归组，而不是一条 JOIN</h2>
	 * 理由见 {@code AgentRunMapper.retrievalHitsOf} 的注释（调用行会被命中的条数放大）。
	 * 两条查询的总行数是 {@code 调用数 + 命中数}，量级很小（一次运行几十行），
	 * 所以这个选择在数据量上也没有代价。
	 */
	@Override
	public List<AgentRunRecord.RetrievalRecord> retrievalsOf(String runId) {
		List<MapperRows.RetrievalHitRow> hitRows = read(() -> mapper.retrievalHitsOf(runId),
				"读检索明细失败 runId=" + runId);
		List<MapperRows.RetrievalCallRow> callRows = read(() -> mapper.retrievalCallsOf(runId),
				"读检索明细失败 runId=" + runId);

		// (step, call) → 命中列表
		Map<String, List<RetrievalTrace.Hit>> hitsByCall = new LinkedHashMap<>();
		for (MapperRows.RetrievalHitRow row : hitRows) {
			hitsByCall.computeIfAbsent(row.stepIndex() + ":" + row.callIndex(), k -> new ArrayList<>())
					.add(row.toHit());
		}

		List<AgentRunRecord.RetrievalRecord> out = new ArrayList<>();
		for (MapperRows.RetrievalCallRow row : callRows) {
			List<RetrievalTrace.Hit> hits = hitsByCall.getOrDefault(row.stepIndex() + ":" + row.callIndex(),
					List.of());
			out.add(new AgentRunRecord.RetrievalRecord(runId, row.stepIndex(), row.callIndex(), row.toolName(),
					new RetrievalTrace(row.query(), row.topK(), row.indexSize(), hits, row.elapsedMs())));
		}
		return List.copyOf(out);
	}

	@Override
	public Optional<AgentRunRecord.StepCheckpoint> latestCheckpoint(String runId) {
		MapperRows.StepCheckpointRow row = read(() -> mapper.latestCheckpoint(runId),
				"读检查点失败 runId=" + runId);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public List<AgentRunRecord.StepCheckpoint> stepsOf(String runId) {
		return read(() -> mapper.stepsOf(runId).stream().map(MapperRows.StepCheckpointRow::toDomain).toList(),
				"读步骤失败 runId=" + runId);
	}

	@Override
	public List<AgentRunRecord> recentRuns(int limit) {
		return read(() -> mapper.recentRuns(limit).stream().map(MapperRows.AgentRunRow::toDomain).toList(),
				"读最近运行失败");
	}

	/**
	 * 同一个会话里的全部运行（按创建时间<b>正序</b>）。
	 *
	 * <p>空白键返回空列表，**不是**"返回全部"：一个空白键如果被当成通配，
	 * 那么这个方法的调用方会在某次传参写错时拿到一份看起来完全正常的完整列表 ——
	 * 而"这段对话只有一次运行"与"没按会话过滤"在页面上会长得一样。
	 */
	@Override
	public List<AgentRunRecord> runsOfConversation(String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			return List.of();
		}
		return read(() -> mapper.runsOfConversation(conversationId).stream()
				.map(MapperRows.AgentRunRow::toDomain).toList(), "读会话运行失败 conversationId=" + conversationId);
	}

	/** 读操作的异常包装（六个读方法共用）。 */
	private <T> T read(java.util.function.Supplier<T> call, String whatFailed) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new AgentStoreException(whatFailed, e);
		}
	}

	/** 存储层的失败。包一层而不是把 {@code SQLException} 透出去：调用方不该被迫处理受检异常。 */
	public static class AgentStoreException extends RuntimeException {

		public AgentStoreException(String message, Throwable cause) {
			super(message, cause);
		}

		public AgentStoreException(String message) {
			super(message);
		}

	}

}

