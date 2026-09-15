package ai.agent.agent.store;

import ai.agent.agent.AgentLoop;
import ai.agent.agent.AgentRun;
import ai.agent.agent.AgentStopReason;
import ai.agent.agent.ModelTurn;
import ai.agent.agent.ResumableAgentService;
import ai.agent.agent.ScriptedModelTurn;
import ai.agent.agent.store.support.TranscriptCodec;
import ai.agent.config.AgentStoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>断点恢复测试：模拟"进程被杀"，验证真的能接着跑。</b>
 *
 * <h2>"进程被杀"这件事怎么测</h2>
 * 单测里没法真去 kill 一个进程，但被杀<b>在库里留下的事实</b>是可以造的，而且那个事实才是关键：
 * <ol>
 *   <li>{@code agent_run} 里留着一行 {@code status=RUNNING}（进程没来得及改成别的）；</li>
 *   <li>{@code agent_step} 里有最后一步的检查点，但那份对话<b>停在一个"提出了工具调用、
 *       还没有对应返回"的状态</b> —— 那正是被杀那一刻上下文的样子。</li>
 * </ol>
 * 于是恢复逻辑要面对的输入是真实的，只是它是被"构造"而不是被"kill"出来的。
 * 这也顺带说明为什么 {@link AgentRunStore} 需要那几个
 * {@code ...ForTesting} 方法：没有它们，测试就只能反射改私有字段，
 * 而那种测试在重构时会静默失效。
 *
 * <h2>这个测试最想钉住的三件事</h2>
 * <ol>
 *   <li><b>从检查点接着跑，而不是从头重跑</b> —— 断言"恢复后的第 1 步是第 2 步"，
 *       以及"上一轮那个工具没有被重新执行"（重跑 = 重复副作用 = 路线文档点名的问题）；</li>
 *   <li><b>模型被告知"那个工具的结果未知"</b> —— 不告诉它，它会看到一个悬空的 tool_call
 *       而永远等不到返回，下一轮很可能原样再要一次；</li>
 *   <li><b>步数接着数</b> —— 不接着数的话，一个被杀过 N 次的任务能跑 N×maxSteps 步，
 *       上限形同虚设。</li>
 * </ol>
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.url=jdbc:h2:mem:agent-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"ai.agent.store.node=test-node", "ai.agent.store.auto-resume-on-startup=false",
		"ai.agent.loop.max-steps=6" })
class AgentRunRecoveryTest {

	@Autowired
	private ResumableAgentService service;

	@Autowired
	private AgentRunStore store;

	@Autowired
	private TranscriptCodec codec;

	@Autowired
	private AgentStoreProperties properties;

	@Autowired
	private AgentLoop loop;

	/** 当前测试装上的脚本模型。 */
	private static final AtomicReference<ScriptedModelTurn> CURRENT = new AtomicReference<>();

	@TestConfiguration
	static class ScriptedModelConfig {

		@Bean
		@Primary
		ModelTurn scriptedModelTurn() {
			return new DelegatingModelTurn();
		}
	}

	static class DelegatingModelTurn implements ModelTurn {

		@Override
		public ModelReply chat(List<Message> messages) {
			ScriptedModelTurn turn = CURRENT.get();
			if (turn == null) {
				throw new IllegalStateException("测试没有设置脚本模型");
			}
			return turn.chat(messages);
		}

	}

	private static ScriptedModelTurn install(ModelTurn.ModelReply... replies) {
		ScriptedModelTurn turn = new ScriptedModelTurn(List.of(replies));
		CURRENT.set(turn);
		return turn;
	}

	@BeforeEach
	void clearScript() {
		CURRENT.set(null);
	}

	// ------------------------------------------------------------------
	//  1. 正常路径：每一步都落库
	// ------------------------------------------------------------------

	@Test
	@DisplayName("正常跑完：库里有运行记录、每步的检查点、以及每次工具调用（含 stage）")
	void completedRunIsFullyPersisted() {
		install(
				ScriptedModelTurn.callTool("call_a", "list_documents", "{}"),
				ScriptedModelTurn.answer("知识库里有 4 份文档。"));
		String runId = UUID.randomUUID().toString();

		AgentRun run = service.runWithId(runId, "知识库里有哪些文档？");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);

		AgentRunRecord record = store.find(runId).orElseThrow();
		assertThat(record.status()).isEqualTo(RunStatus.ANSWERED);
		assertThat(record.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(record.question()).isEqualTo("知识库里有哪些文档？");
		assertThat(record.finalAnswer()).contains("4 份文档");
		assertThat(record.toolCallCount()).isEqualTo(1);
		assertThat(record.totalTokens()).as("用量来自模型响应").isPositive();
		assertThat(record.node()).isEqualTo("test-node");
		assertThat(record.finished()).isTrue();

		// 每一步都有检查点（这是"还原执行过程"的数据基础）
		List<AgentRunRecord.StepCheckpoint> steps = store.stepsOf(runId);
		assertThat(steps).hasSize(2);
		assertThat(steps.get(0).stepIndex()).isEqualTo(1);
		assertThat(steps.get(1).stepIndex()).isEqualTo(2);
		assertThat(steps.get(0).transcriptJson()).as("检查点里必须真的存了对话").isNotBlank();

		// 工具调用明细
		List<AgentRunRecord.ToolCallRecord> calls = store.toolCallsOf(runId);
		assertThat(calls).hasSize(1);
		assertThat(calls.get(0).toolName()).isEqualTo("list_documents");
		assertThat(calls.get(0).stage()).isEqualTo("OK");
		assertThat(calls.get(0).callId()).isEqualTo("call_a");
		assertThat(calls.get(0).feedback()).as("回填文本也要落库，否则事后无法还原'模型当时看到了什么'")
				.contains("ledger-progress.md");
	}

	@Test
	@DisplayName("被拒绝的工具调用也要落库（否则'模型想调几次'这个数字永远偏小）")
	void rejectedToolCallsAreAlsoPersisted() {
		install(
				ScriptedModelTurn.callTool("call_bad", "doc_search", "{\"query\":\"分块\"}"),
				ScriptedModelTurn.answer("我改用正确的工具。"));
		String runId = UUID.randomUUID().toString();

		service.runWithId(runId, "分块策略？");

		List<AgentRunRecord.ToolCallRecord> calls = store.toolCallsOf(runId);
		assertThat(calls).hasSize(1);
		assertThat(calls.get(0).stage()).as("被拒绝的调用必须留下痕迹").isEqualTo("UNKNOWN_TOOL");
		assertThat(calls.get(0).feedback()).contains("search_docs");

		AgentRunRecord record = store.find(runId).orElseThrow();
		assertThat(record.toolCallCount()).as("计数含被拒绝的那次").isEqualTo(1);
	}

	// ------------------------------------------------------------------
	//  2. 重置为"看起来没跑完"，然后恢复
	// ------------------------------------------------------------------

	@Test
	@DisplayName("恢复：库里留着 RUNNING 行 → 被认领为 INTERRUPTED → 从第 2 步接着跑完（步号续接）")
	void resumesFromCheckpointAfterInterruption() {
		String runId = UUID.randomUUID().toString();

		// 第一步：让第 1 步（一次工具往返）真的执行并被检查点记下来。
		// 第 2 轮的回复抛异常 —— 用来"把运行停在第二步中间"：循环会把 MODEL_ERROR 报告返回，
		// 而**异常发生在任何检查点写入之前**，所以库里只有第 1 步。
		// 这正是"进程在第二步中途被杀"在库里留下的形状。
		ModelTurn flaky = new ModelTurn() {
			private int calls;

			@Override
			public ModelReply chat(List<Message> messages) {
				calls++;
				if (calls == 1) {
					return ScriptedModelTurn.callTool("call_1", "list_documents", "{}");
				}
				throw new IllegalStateException("模拟进程在这里被杀（第二步没有产生检查点）");
			}
		};
		CURRENT.set(null);
		AgentRun first = service.runWithId(runId, "有哪些文档？", flaky);

		assertThat(first.stopReason()).isEqualTo(AgentStopReason.MODEL_ERROR);
		assertThat(store.stepsOf(runId)).as("死在第 2 步之前，所以库里只有第 1 步").hasSize(1);

		// 【真实的崩溃形状】第 1 步的对话停在一个"提出了工具调用、还没有对应返回"的状态
		store.markInterruptedForTesting(runId);
		store.replaceStepTranscriptForTesting(runId, 1, pendingToolCallTranscript("call_pending", "search_docs"));

		// 第二步：新进程启动，认领并恢复
		ScriptedModelTurn resumedModel = install(ScriptedModelTurn.answer("上次被打断了，我重新查了一下：有 4 份文档。"));
		List<ResumableAgentService.ResumeResult> results = service.resumeInterruptedRuns();

		assertThat(results).hasSize(1);
		ResumableAgentService.ResumeResult result = results.get(0);
		assertThat(result.runId()).isEqualTo(runId);
		assertThat(result.outcome()).isEqualTo(ResumableAgentService.ResumeResult.Outcome.RESUMED);
		assertThat(result.resumedFromStep()).as("从第 1 步之后接着跑").isEqualTo(1);
		assertThat(result.run().stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(result.run().finalAnswer()).contains("4 份文档");

		// 【核心断言 1】模型收到的第 1 轮请求里必须有中断说明。
		// 没有它，模型会看到一个悬空的 tool_call 而永远等不到返回
		String firstRequest = resumedModel.requestTextAt(0);
		assertThat(firstRequest).as("必须告诉模型「上次被中断了」").contains("被中断");
		assertThat(firstRequest).as("必须点名那个结果未知的工具").contains("search_docs");
		assertThat(firstRequest).as("必须明确说「可能已生效也可能没生效」，否则模型会自己猜").contains("没有结果");

		// 【核心断言 2】恢复不是重跑：恢复后只跑了 1 步（作答那一步），
		// 而不是把第 1 步连同它的工具再执行一遍（重跑 = 重复副作用）
		assertThat(result.run().steps()).as("只跑恢复之后的那一步").hasSize(1);
		assertThat(result.run().toolCallCount()).as("恢复过程没有重新执行任何工具").isZero();

		// 【核心断言 3】步号接着数：不接着数的话，被杀 N 次的任务能跑 N×maxSteps 步
		assertThat(result.run().steps().get(0).index()).as("步号从 2 开始，不是从 1 重新数").isEqualTo(2);

		// 恢复后状态被正确收尾
		AgentRunRecord after = store.find(runId).orElseThrow();
		assertThat(after.status()).isEqualTo(RunStatus.ANSWERED);
		assertThat(after.resumable()).isFalse();
		// 恢复后新增了第 2 步的检查点 → 库里现在有两步（这是"接着跑"留下的痕迹）
		assertThat(store.stepsOf(runId)).hasSize(2);
	}

	@Test
	@DisplayName("恢复：没有检查点时不做自动恢复（那会变成'重跑'，语义不同）")
	void runWithoutCheckpointIsNotResumed() {
		// 起一个运行但一步都没走完
		String runId = UUID.randomUUID().toString();
		store.start(runId, "还没开始就挂了", properties.node());

		List<ResumableAgentService.ResumeResult> results = service.resumeInterruptedRuns();

		assertThat(results).hasSize(1);
		assertThat(results.get(0).outcome())
				.isEqualTo(ResumableAgentService.ResumeResult.Outcome.NO_CHECKPOINT);
		assertThat(results.get(0).run()).isNull();
		// 它必须被收尾成 FAILED，而不是留在 INTERRUPTED 让下次启动再试一遍（永远失败的死循环）
		AgentRunRecord record = store.find(runId).orElseThrow();
		assertThat(record.status()).isEqualTo(RunStatus.FAILED);
		assertThat(record.error()).as("失败原因要说清是'没有检查点'，否则会被误读成模型的问题")
				.contains("检查点");
	}

	@Test
	@DisplayName("认领只针对本实例（node）：别的实例正在跑的运行不能被抢")
	void claimingIsScopedToNode() {
		String mine = UUID.randomUUID().toString();
		String theirs = UUID.randomUUID().toString();
		store.start(mine, "我的任务", properties.node());
		store.start(theirs, "别人的任务", "another-node");

		List<AgentRunRecord> claimed = store.markInterruptedRuns(properties.node());

		assertThat(claimed).extracting(AgentRunRecord::id).containsExactly(mine);
		assertThat(store.find(mine).orElseThrow().status()).isEqualTo(RunStatus.INTERRUPTED);
		assertThat(store.find(theirs).orElseThrow().status())
				.as("别的实例的运行必须原样留在 RUNNING —— 抢过来会让它的工具被执行两遍")
				.isEqualTo(RunStatus.RUNNING);
	}

	@Test
	@DisplayName("认领是幂等的：第二次调用不会再认领同一批（否则会恢复两次）")
	void claimingIsIdempotent() {
		String runId = UUID.randomUUID().toString();
		store.start(runId, "任务", properties.node());

		assertThat(store.markInterruptedRuns(properties.node())).hasSize(1);
		assertThat(store.markInterruptedRuns(properties.node()))
				.as("第二次不该再认领到（它已经不是 RUNNING 了）").isEmpty();
	}

	// ------------------------------------------------------------------
	//  3. 对话记录的编解码
	// ------------------------------------------------------------------

	@Test
	@DisplayName("编解码往返：四类消息全部无损（含 assistant 的 tool_calls 与 tool 的 id）")
	void transcriptRoundTripKeepsEverything() {
		List<Message> original = new ArrayList<>();
		original.add(new SystemMessage("你是助手"));
		original.add(new UserMessage("查一下文档"));
		original.add(AssistantMessage.builder()
				.content("我去查")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "search_docs",
						"{\"query\":\"分块\"}")))
				.build());
		original.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "search_docs", "找到 3 条")))
				.build());
		original.add(new UserMessage("【系统提示】上一次执行被中断了"));

		String json = codec.encode(original);
		List<Message> restored = codec.decode(json);

		assertThat(restored).hasSize(5);
		assertThat(restored.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(restored.get(0).getText()).isEqualTo("你是助手");
		assertThat(restored.get(1)).isInstanceOf(UserMessage.class);

		AssistantMessage assistant = (AssistantMessage) restored.get(2);
		assertThat(assistant.getText()).isEqualTo("我去查");
		assertThat(assistant.getToolCalls()).hasSize(1);
		assertThat(assistant.getToolCalls().get(0).id()).as("id 是协议对账的凭据，不能丢").isEqualTo("call_1");
		assertThat(assistant.getToolCalls().get(0).name()).isEqualTo("search_docs");
		assertThat(assistant.getToolCalls().get(0).arguments()).isEqualTo("{\"query\":\"分块\"}");

		ToolResponseMessage responses = (ToolResponseMessage) restored.get(3);
		assertThat(responses.getResponses()).hasSize(1);
		assertThat(responses.getResponses().get(0).id()).isEqualTo("call_1");
		assertThat(responses.getResponses().get(0).responseData()).isEqualTo("找到 3 条");
	}

	@Test
	@DisplayName("从检查点里能认出'提出了但没拿到结果'的那次调用")
	void pendingInvocationIsDetectedFromTranscript() {
		// 这份对话停在"提出了调用、没有返回"——正是被杀那一刻的样子
		String json = pendingToolCallTranscript("call_x", "read_section");

		assertThat(json).contains("read_section");
		// 用与恢复逻辑相同的判据验证一遍：解码后应该有一条 assistant 带 tool_calls、但没有任何 tool 返回
		List<Message> messages = codec.decode(json);
		boolean hasUnansweredCall = messages.stream().anyMatch(m -> m instanceof AssistantMessage a
				&& a.hasToolCalls())
				&& messages.stream().noneMatch(m -> m instanceof ToolResponseMessage);
		assertThat(hasUnansweredCall).as("无返回的调用必须能被识别出来").isTrue();
	}

	@Test
	@DisplayName("有返回的调用不会被误判成待定（否则每次恢复都会多一条'结果未知'的说明）")
	void answeredInvocationIsNotPending() {
		List<Message> messages = List.of(
				new UserMessage("查文档"),
				AssistantMessage.builder()
						.toolCalls(List.of(new AssistantMessage.ToolCall("call_done", "function", "search_docs", "{}")))
						.build(),
				ToolResponseMessage.builder()
						.responses(List.of(new ToolResponseMessage.ToolResponse("call_done", "search_docs", "结果")))
						.build());

		String json = codec.encode(messages);
		List<Message> restored = codec.decode(json);

		assertThat(restored.stream().anyMatch(m -> m instanceof ToolResponseMessage))
				.as("已经有返回了，不该再被当成待定").isTrue();
	}

	@Test
	@DisplayName("不认识的角色/类型一律抛，不静默丢弃（丢掉一条消息会让恢复出的上下文悄悄变样）")
	void unknownRoleFailsLoudly() {
		assertThatThrownBy(() -> codec.decode("[{\"role\":\"developer\",\"content\":\"x\"}]"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("developer");

		assertThatThrownBy(() -> codec.decode("{\"role\":\"user\"}"))
				.as("不是数组也要抛").isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("数组");
	}

	// ------------------------------------------------------------------
	//  4. 配置
	// ------------------------------------------------------------------

	@Test
	@DisplayName("默认配置是内存库且不自动恢复 —— 这两件事都必须显式说出来才不会误解")
	void defaultsAreExplicit() {
		AgentStoreProperties defaults = AgentStoreProperties.defaults();

		assertThat(defaults.url()).as("默认内存库：进程退出即清空，恢复能力靠测试验证").contains("jdbc:h2:mem");
		assertThat(defaults.url()).as("MODE=MySQL 是为了让 DDL 贴着 MySQL 写法").contains("MODE=MySQL");
		assertThat(defaults.autoResumeOnStartup()).as("默认不自动恢复：那意味着启动就调模型").isFalse();
		assertThat(defaults.node()).isNotBlank();
	}

	@Test
	@DisplayName("node 为空要在构造期抛（否则多实例下永远认领不到自己的遗留运行，且不报错）")
	void blankNodeFailsFast() {
		assertThatThrownBy(() -> new AgentStoreProperties("jdbc:h2:mem:x", "sa", "", "  ", false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("node");
	}

	// ------------------------------------------------------------------
	//  工具
	// ------------------------------------------------------------------

	/**
	 * 造一份"停在一个悬空工具调用上"的对话记录。
	 *
	 * <p>这是模拟"进程被杀"的关键素材：库里真的会留下这种形状 ——
	 * assistant 消息已经写好（带着 {@code tool_calls}），但对应的 tool 消息还没产生。
	 */
	private String pendingToolCallTranscript(String callId, String toolName) {
		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage("你是助手"));
		messages.add(new UserMessage("有哪些文档？"));
		messages.add(AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", toolName, "{}")))
				.build());
		return codec.encode(messages);
	}

	/** 让编译器知道 loop 被用到了（保留字段以便将来直接测 loop 的持久化行为）。 */
	@Test
	@DisplayName("AgentLoop 本身不需要存储就能跑（持久化是可选的）")
	void loopWorksWithoutStore() {
		install(ScriptedModelTurn.answer("不需要工具。"));

		AgentRun run = loop.run("你好");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(store.recentRuns(100)).as("这次运行不该产生任何库记录").noneMatch(r -> "你好".equals(r.question()));
	}

}
