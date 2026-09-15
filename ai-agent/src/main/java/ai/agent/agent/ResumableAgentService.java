package ai.agent.agent;

import ai.agent.agent.store.AgentRunRecord;
import ai.agent.agent.store.AgentRunStore;
import ai.agent.agent.store.RunStatus;
import ai.agent.agent.store.support.TranscriptCodec;
import ai.agent.config.AgentStoreProperties;
import ai.agent.tool.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 带状态持久化与断点恢复的 Agent 服务（阶段三：路线文档验收里的「进程被杀能从检查点恢复」）。
 *
 * <h2>它比 {@link AgentLoop} 多做了什么</h2>
 * 循环负责"把任务跑完"，本类负责"跑的过程不丢"：
 * <ol>
 *   <li>跑之前先落一条 {@code RUNNING} —— 否则"进程第一步就挂了"在库里不留任何痕迹；</li>
 *   <li>每一步结束写检查点（**含完整对话**）—— 恢复靠它，不靠重放；</li>
 *   <li>启动时认领上一次留下的未完成运行（{@code RUNNING} → {@code INTERRUPTED}）；</li>
 *   <li>从最新检查点**接着跑**，并把"那个工具的结果未知"这件事显式告诉模型。</li>
 * </ol>
 *
 * <h2>恢复为什么不是"从头重跑一遍"</h2>
 * 这是本类最重要的一个取舍。重跑看起来简单（反正有幂等性就行？），但：
 * <ul>
 *   <li><b>已经花掉的 token 会再花一遍</b> —— 一个跑了 4 步的任务，重跑就是 4 步的钱；</li>
 *   <li><b>工具会被重复执行</b>（只读的是浪费，写操作是事故）——
 *       这正是路线文档在面试追问里点名的「工具调用重试会不会产生重复副作用」；</li>
 *   <li><b>上下文会变</b>：工具返回的内容可能已经不一样了，重跑出来的 trace
 *       与你原来看到的那份不是同一个东西，于是"还原执行过程"也就无从谈起。</li>
 * </ul>
 * 所以这里"接着跑"：把检查点里的对话原样恢复，步数接着数。
 *
 * <h2>关于 stepIndex 的续接</h2>
 * 恢复后的步号从"已跑过的步数 + 1"开始。这不只是好看 ——
 * {@link AgentLoop} 的步数上限是按轮号判定的，如果恢复时从 1 重新数，
 * 一个被杀过 N 次的任务就能跑 N×maxSteps 步，上限形同虚设。
 */
@Service
public class ResumableAgentService {

	private static final Logger log = LoggerFactory.getLogger(ResumableAgentService.class);

	private final AgentLoop loop;

	private final AgentRunStore store;

	private final TranscriptCodec codec;

	private final AgentStoreProperties properties;

	public ResumableAgentService(AgentLoop loop, AgentRunStore store, TranscriptCodec codec,
			AgentStoreProperties properties) {
		this.loop = loop;
		this.store = store;
		this.codec = codec;
		this.properties = properties;
	}

	/** 新起一次任务（带持久化）。 */
	public AgentRun start(String question) {
		String runId = UUID.randomUUID().toString();
		return runWithId(runId, question);
	}

	/** 用指定 id 跑一次（测试要能控制 id，否则没法在断言里引用它）。 */
	public AgentRun runWithId(String runId, String question) {
		return runWithId(runId, question, null);
	}

	/**
	 * 用指定 id 与<b>指定模型</b>跑一次。
	 *
	 * <h2>为什么要暴露"换一个模型"这个能力（而不是只给测试用）</h2>
	 * 它不只是测试便利，它是<b>这条链路上唯一能造出"中途死掉"的手段</b>：
	 * 断点恢复要验证的是"进程死在两步之间"，而那要求"跑到某一步就停住"——
	 * 用一个会在指定轮次抛异常的模型就能确定性地造出来，
	 * 而不必真的去 kill 进程（那种测试既慢又不稳定）。
	 *
	 * <p>顺带它也让"同一个 loop 换不同模型跑"成为可能 —— 那是做模型对照实验的入口
	 * （属于阶段四"评测接入 CI"的范畴）。
	 *
	 * @param modelTurn 覆盖默认模型；传 {@code null} 表示用容器里那个
	 */
	public AgentRun runWithId(String runId, String question, ModelTurn modelTurn) {
		store.start(runId, question, properties.node());
		PersistingListener listener = new PersistingListener(runId);
		try {
			// 传了模型就临时换一个 loop：AgentLoop 是无状态的（所有可变状态都在方法局部变量里），
			// 所以这里 new 一个是安全的 —— 不是"共享可变状态"那种危险做法
			AgentLoop effectiveLoop = modelTurn == null ? loop : loop.withModelTurn(modelTurn);
			AgentRun run = effectiveLoop.run(runId, question, listener);
			store.persistCompletedRun(runId, run);
			return run;
		}
		catch (RuntimeException e) {
			// 循环正常路径不抛异常（失败会变成 MODEL_ERROR 的报告），所以走到这里
			// 说明是**存储或监听器**炸了。必须把状态落成 FAILED：
			// 留在 RUNNING 的话，下次启动会被当成"被中断的运行"去恢复一个已经确定失败的任务
			log.error("Agent 运行失败（非模型原因）runId={}", runId, e);
			safeFinishFailed(runId, e);
			throw e;
		}
	}

	/**
	 * 启动时恢复上一次留下的未完成运行。
	 *
	 * @return 恢复结果列表（每次恢复一条）；没有可恢复的就返回空
	 */
	public List<ResumeResult> resumeInterruptedRuns() {
		List<AgentRunRecord> interrupted = store.markInterruptedRuns(properties.node());
		if (interrupted.isEmpty()) {
			return List.of();
		}
		log.warn("发现 {} 次上次未跑完的运行，开始恢复", interrupted.size());
		List<ResumeResult> results = new ArrayList<>();
		for (AgentRunRecord record : interrupted) {
			results.add(resume(record));
		}
		return List.copyOf(results);
	}

	/** 恢复一次运行（也可以手工调用某一次）。 */
	public ResumeResult resume(AgentRunRecord record) {
		Optional<AgentRunRecord.Checkpoint> loaded = store.loadCheckpoint(record.id());
		if (loaded.isEmpty()) {
			return ResumeResult.notFound(record.id());
		}
		AgentRunRecord.Checkpoint checkpoint = loaded.get();
		if (!checkpoint.hasTranscript()) {
			// 一次都没跑完一步：没有可用的上下文。**不在这里自己从头跑** ——
			// 那是"重跑"而不是"恢复"，语义不同，该由调用方明确选择
			log.warn("运行 {} 没有可用检查点（可能在第 1 步就被杀了），不做自动恢复", record.id());
			store.finish(record.id(), RunStatus.FAILED, AgentStopReason.MODEL_ERROR, null, record.totalTokens(),
					record.toolCallCount(), record.elapsedMs(), "被中断且没有可用检查点（第 1 步未完成）");
			return ResumeResult.noCheckpoint(record.id());
		}

		// 被杀那一刻"意图已提出、结果未知"的那次调用
		ToolInvocation pending = findPendingInvocation(checkpoint.latestStep().transcriptJson());
		if (pending != null) {
			log.warn("运行 {} 死在一个未完成的工具调用上：{}（会明确告知模型这个结果未知）", record.id(),
					pending.describe());
		}

		List<Message> messages = codec.decode(checkpoint.latestStep().transcriptJson());
		PersistingListener listener = new PersistingListener(record.id());
		AgentRun run = loop.run(record.id(), record.question(), messages, checkpoint.resumeFromStep(), listener, pending);
		store.persistCompletedRun(record.id(), run);
		log.info("运行 {} 已从第 {} 步恢复并结束：{}", record.id(), checkpoint.resumeFromStep(), run.stopReason());
		return ResumeResult.resumed(record.id(), checkpoint.resumeFromStep(), run, pending);
	}

	/**
	 * 从检查点里找出"提出了但没拿到结果"的那次工具调用。
	 *
	 * <h2>为什么要专门找它，而不是忽略</h2>
	 * 它是整条链路里**唯一一个状态不明**的东西：那次调用可能压根没开始，
	 * 也可能已经在外部系统里生效了、只是结果没记下来。
	 * 对只读工具这只是浪费；对写操作，这正是"重复副作用"的来源 ——
	 * 所以恢复时必须把它显式交给模型（见 {@code AgentLoop#interruptionNotice}），
	 * 而不是让模型看到一个悬空的 tool_call 然后自己猜。
	 *
	 * <p>判据：assistant 消息里的每个 {@code tool_calls[i].id}，
	 * 在它之后的 tool 消息里找不到对应返回，即为待定。
	 */
	private ToolInvocation findPendingInvocation(String transcriptJson) {
		List<Message> messages = codec.decode(transcriptJson);
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		List<String> answeredIds = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant) {
				calls.addAll(assistant.getToolCalls());
			}
			if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage responses) {
				for (var response : responses.getResponses()) {
					answeredIds.add(response.id());
				}
			}
		}
		for (AssistantMessage.ToolCall call : calls) {
			if (!answeredIds.contains(call.id())) {
				return ToolInvocation.from(call.id(), call.name(), call.arguments());
			}
		}
		return null;
	}

	private void safeFinishFailed(String runId, RuntimeException e) {
		try {
			store.finish(runId, RunStatus.FAILED, AgentStopReason.MODEL_ERROR, null, 0, 0, 0,
					e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		catch (RuntimeException finishFailure) {
			// 连收尾都失败：说明库本身不可用。把两件事一起记下来 ——
			// 只报后者会掩盖前一个原因
			log.error("收尾失败（原始异常：{}）", e.toString(), finishFailure);
		}
	}

	/** 最近若干次运行（排查与界面用）。 */
	public List<AgentRunRecord> recentRuns(int limit) {
		return store.recentRuns(limit);
	}

	/** 一次运行的完整步骤（还原执行过程用）。 */
	public List<AgentRunRecord.StepCheckpoint> stepsOf(String runId) {
		return store.stepsOf(runId);
	}

	/**
	 * 把每一步的检查点写进库的监听器。
	 *
	 * <h2>写失败时为什么会一路抛出去（而不是记个日志继续跑）</h2>
	 * 因为"检查点写不进去"意味着**这次运行没有恢复点**。如果这里吞掉异常继续跑，
	 * 用户会以为一切正常 —— 直到真的被杀那一刻才发现没得恢复，而那时整次运行都丢了。
	 * 宁可现在就打断，让调用方去决定（重试 / 降级成不持久化 / 报错给用户）。
	 */
	private class PersistingListener implements AgentProgressListener {

		private final String runId;

		PersistingListener(String runId) {
			this.runId = runId;
		}

		@Override
		public void afterStep(String runId, int stepIndex, AgentRun.Step step, List<Message> messages,
				int totalTokensSoFar, int toolCallsSoFar, long elapsedMsSoFar) {			// 先写工具调用明细，再写检查点。
			// 顺序有讲究：检查点是"这一步完成了"的凭据，如果先写它、
			// 后写明细时失败，恢复时就会看到一个"完成"的步骤却缺了工具记录 ——
			// 而那是无法从检查点本身发现的（检查点里只有对话，没有 stage 分类）
			int callIndex = 0;
			for (var outcome : step.outcomes()) {
				store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, stepIndex, callIndex++,
						outcome.invocation().id(), outcome.invocation().name(),
						outcome.invocation().argumentsForFeedback(2000), outcome.stage().name(), outcome.feedback(),
						outcome.elapsedMs()));
			}
			store.checkpoint(new AgentRunRecord.StepCheckpoint(runId, stepIndex, codec.encode(messages),
					step.thoughtText(), step.promptTokens(), step.completionTokens(), step.elapsedMs()));
			store.updateProgress(runId, totalTokensSoFar, toolCallsSoFar, elapsedMsSoFar);
		}
	}

	/**
	 * 一次恢复的结果。
	 *
	 * <p>三种情况分开表达，而不是用一个可空的 run + 一个布尔：
	 * 「没找到」「找到了但没检查点」「恢复了」对使用者是三件不同的事 ——
	 * 前两种需要人去处理（看为什么没检查点），第三种是正常路径。
	 *
	 * @param runId 运行 id
	 * @param outcome 结果类型
	 * @param resumedFromStep 从第几步之后接着跑（0 = 没有检查点）
	 * @param run 恢复后的运行结果（只有 {@code RESUMED} 时非空）
	 * @param pendingInvocation 被杀时状态不明的那次工具调用（可能为 null）
	 */
	public record ResumeResult(String runId, Outcome outcome, int resumedFromStep, AgentRun run,
			ToolInvocation pendingInvocation) {

		public enum Outcome {
			/** 从检查点接着跑完。 */
			RESUMED,
			/** 库里没有这次运行。 */
			NOT_FOUND,
			/** 有运行但没有可用检查点（第 1 步都没跑完）。 */
			NO_CHECKPOINT
		}

		static ResumeResult resumed(String runId, int fromStep, AgentRun run, ToolInvocation pending) {
			return new ResumeResult(runId, Outcome.RESUMED, fromStep, run, pending);
		}

		static ResumeResult notFound(String runId) {
			return new ResumeResult(runId, Outcome.NOT_FOUND, 0, null, null);
		}

		static ResumeResult noCheckpoint(String runId) {
			return new ResumeResult(runId, Outcome.NO_CHECKPOINT, 0, null, null);
		}

		public boolean resumed() {
			return outcome == Outcome.RESUMED;
		}
	}

}
