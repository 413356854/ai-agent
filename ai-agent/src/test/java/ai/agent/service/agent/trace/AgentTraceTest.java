package ai.agent.service.agent.trace;

import ai.agent.entity.agent.AgentRun;
import ai.agent.service.agent.ModelTurn;
import ai.agent.service.agent.ResumableAgentService;
import ai.agent.service.agent.ScriptedModelTurn;
import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.entity.tool.RetrievalTrace;
import ai.agent.service.tool.ToolCallStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「调用日志 + Agent trace 五层」的验收测试</b>（阶段四第 2 件）。
 *
 * <h2>★ 为什么它走的是 {@link ResumableAgentService#runWithId} 而不是自己拼存储调用</h2>
 * 这是本测试最重要的一个决定，值得写在最前面。
 *
 * <p>写这个测试的第一版是"在内存里跑一次 loop、然后手工把结果写进库"。
 * 那个版本能验证存储层与装配层，却<b>恰好漏掉了这一件最容易出问题的一段</b>：
 * "生产路径真的会去写检索明细吗"。
 * 而这正是本项目已经吃过两次亏的形状（「装上了」与「真在生效」是两件事：bean 装着但实现是裸的；
 * 第九轮：MCP 工具注册了但路由没注册）—— <b>失败都发生在两段之间那条没人检查的缝里</b>。
 *
 * <p>所以这里让 {@link ResumableAgentService} 走完整条生产链路
 * （{@code PersistingListener} → {@code recordToolCall} / {@code recordRetrieval} /
 * {@code checkpoint}），只在 {@link ModelTurn} 那一层换成脚本化模型 ——
 * 于是"模型的动作"是确定的，而"落库的那段代码"是真的。
 *
 * <h2>为什么用真 H2 文件库，而不是内存库</h2>
 * 与 {@code PromptHotReloadTest} 同一条理由：内存库证明不了"真的写下去了"。
 * 后缀用 {@code nanoTime} 而不是 pid —— 实测 surefire <b>复用同一个 fork JVM</b>，
 * 按 pid 命名的文件库会跨 {@code mvn} 运行存活，第二次跑时库里带着上一次的数据。
 *
 * <h2>明确没有覆盖的东西（比上面的代码重要）</h2>
 * 脚本化模型只证明"检索明细在<u>结构化落库</u>这条链路上是通的"，
 * <b>不</b>证明真实模型会怎么选查询词、会不会重复检索、命中质量如何。
 * 那需要真 key 跑一批问题（与"自愈率没有真实数字"是同一个缺口）。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.auto-resume-on-startup=false",
		// 关闭审批闸门：本测试关心 trace 的完整性，而闸门会让写工具停下来等人 ——
		// 那会让"这次运行有没有跑完"变成一个与本件无关的变量
		"ai.agent.approval.enabled=false" })
class AgentTraceTest {

	@DynamicPropertySource
	static void pointAtFreshFileDb(DynamicPropertyRegistry registry) throws IOException {
		Path dbDir = Path.of("target", "agent-trace");
		Files.createDirectories(dbDir);
		String url = "jdbc:h2:file:" + dbDir.toAbsolutePath().toString().replace('\\', '/')
				+ "/trace-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		registry.add("ai.agent.store.url", () -> url);
	}

	@Autowired
	private ResumableAgentService service;

	@Autowired
	private AgentRunStore store;

	@Autowired
	private AgentTraceView traceView;

	@Autowired
	private PromptService prompts;

	/** 当前测试装上的脚本模型（每个测试自己设置）。 */
	private static final AtomicReference<ScriptedModelTurn> CURRENT = new AtomicReference<>();

	/**
	 * 用 {@code @Primary} 显式换掉模型（与 {@code AgentLoopTest} 同一套做法）。
	 *
	 * <p>理由不是"方便"，而是<b>让"用哪个模型"在测试里是显式的</b>：
	 * 一旦哪天它被换回真实适配器，测试会真的发 HTTP 请求然后失败 ——
	 * 那种失败很吵，不会被当成"偶然波动"忽略掉。
	 */
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
				throw new IllegalStateException("测试没有设置脚本模型（忘了 install(...)）");
			}
			return turn.chat(messages);
		}

	}

	private static ScriptedModelTurn install(ModelTurn.ModelReply... replies) {
		ScriptedModelTurn turn = new ScriptedModelTurn(List.of(replies));
		CURRENT.set(turn);
		return turn;
	}

	/** 跑一次<b>真的检索了</b>的任务，走完整条生产链路（含落库）。 */
	private AgentRun runWithSearch(String runId, String question, String query, int topK, String conversationId) {
		install(ScriptedModelTurn.callTool("call_1", "search_docs",
				"{\"query\":\"%s\",\"topK\":%d}".formatted(query, topK)),
				ScriptedModelTurn.answer("根据检索到的内容，答案是这样。"));
		return service.runWithId(runId, question, (String) null, conversationId);
	}

	// ==================================================================
	//  1. 第 5 层：检索明细真的落成了结构
	// ==================================================================

	@Test
	@DisplayName("★ 检索那一层落库：调用 1 次 + 命中 N 条结构行，名次连续、token 可加总")
	void retrievalLayerIsPersistedAsStructure() {
		String runId = "trace-retrieval";
		AgentRun run = runWithSearch(runId, "分块策略的结论是什么", "分块 策略", 5, "conv-retrieval");
		assertThat(run.stopReason().name()).as("脚本化模型会先检索再作答").isEqualTo("ANSWERED");

		List<AgentRunRecord.RetrievalRecord> retrievals = store.retrievalsOf(runId);

		// 【为什么断言"至少一次"而不是"恰好一次"】
		// 本测试的脚本只要求模型调一次，但"模型会不会一轮里提出多个工具调用"
		// 是循环的行为而不是本件的契约。绑死条数会让这条断言在无关改动上变红
		assertThat(retrievals).as("这次运行留下了检索明细 —— 而且它是**生产路径**写的").isNotEmpty();

		AgentRunRecord.RetrievalRecord first = retrievals.get(0);
		RetrievalTrace trace = first.trace();
		assertThat(first.toolName()).as("记下是哪把工具发起的（为将来的第二把检索工具）").isEqualTo("search_docs");
		assertThat(first.stepIndex()).as("检索挂在它实际发生的那一步上").isEqualTo(1);
		assertThat(first.callIndex()).as("第一步里的第一次调用").isZero();

		assertThat(trace.query()).as("查询词是**模型给的原词** —— 归一化会把『它为什么查不到』的线索擦掉")
				.isEqualTo("分块 策略");
		assertThat(trace.topK()).as("模型要了几条要留痕：命中小等于 topK 说明可能召回不足").isEqualTo(5);
		assertThat(trace.indexSize()).as("索引规模是『0 条命中』的分母 —— 没有它分不清空库与没匹配上")
				.isGreaterThan(0);

		// 【核心断言一：命中条数与引擎返回的条数一致】
		// 这是"结构真的落下来了"与"只落了调用没落命中"的分界
		assertThat(trace.hitCount()).as("命中了内容（语料里确实有分块策略的文档）").isGreaterThan(0);
		assertThat(trace.hits()).as("查出来的命中条数必须等于记录里的 hitCount").hasSize(trace.hitCount());

		// 【核心断言二：名次连续且从 1 开始】
		// 断续说明"名次"与"列表顺序"是两份数据，而页面显示的名次会错
		assertThat(trace.ranksAreSequential()).as("名次必须连续（1..n）—— 它就是模型看到的名次").isTrue();
		assertThat(trace.hits()).extracting(RetrievalTrace.Hit::rank)
				.containsExactly(1, 2, 3, 4, 5);

		// 【核心断言三（本件最值钱的一个数）：每块 token 落库了，可加总】
		// 在此之前这个数只存在于回填文本里 —— 而那段文本里**根本没有**每块的 token 总数，
		// 所以"检索塞进 prompt 的 token 有多少"一直答不了
		for (RetrievalTrace.Hit hit : trace.hits()) {
			assertThat(hit.tokenCount()).as("每块都要有估算 token").isGreaterThan(0);
			assertThat(hit.chunkId()).as("块 id 让页面能直接回到分块结果").isNotBlank();
			assertThat(hit.documentId()).as("来源文档是排查召回错误的第一条线索").isNotBlank();
			assertThat(hit.excerpt()).as("片段摘录要能让人认出召回对不对").isNotBlank();
		}
		assertThat(trace.totalHitTokens())
				.as("汇总 token 必须等于各行之和 —— 两处各算一遍迟早对不上")
				.isEqualTo(trace.hits().stream().mapToInt(RetrievalTrace.Hit::tokenCount).sum())
				.isGreaterThan(0);
	}

	@Test
	@DisplayName("★ 一次都没查到的检索**同样留痕**（0 条是正常结果，但『为什么没查到』必须可查）")
	void emptyRetrievalIsAlsoTraced() {
		String runId = "trace-retrieval-empty";
		// 【为什么查询词是一串无意义拉丁字母，而不是"不存在的词"这种中文】
		// 第一版用的是「zzz不存在的词zzz」，结果是**命中了 3 条**。
		// 原因很值得记：分析器把中文切成二元组（CJKBigramFilter），
		// 于是「存在」这个二元组生效 —— 而"存在"出现在**几乎每一页**的状态表里
		// （"该 key 曾以明文出现"之类的句子到处都是）。也就是说，
		// 一段"读起来像胡说"的中文，在二元组检索里的匹配率远高于直觉。
		//
		// 纯拉丁字母串没有中文二元组可切，整串作为一个词项去匹配 ——
		// 而这一串在 4 份技术文档里不可能出现。这才是"确定查不到"的正确构造方式。
		String neverMatches = "qqzzxxvvjwkp";
		runWithSearch(runId, "问一个语料里没有的东西", neverMatches, 3, "conv-empty");

		List<AgentRunRecord.RetrievalRecord> retrievals = store.retrievalsOf(runId);
		assertThat(retrievals).as("0 条命中也**必须**留下调用记录").isNotEmpty();

		RetrievalTrace trace = retrievals.get(0).trace();
		assertThat(trace.hitCount()).as("这次确实一条都没查到（查询词 %s）", neverMatches).isZero();
		assertThat(trace.hits()).isEmpty();
		// 【这两条是"只留一段文本"时完全丢失的东西】
		assertThat(trace.query()).as("必须能回答『它拿什么词去查的』").isEqualTo(neverMatches);
		assertThat(trace.indexSize()).as("必须能回答『是库空了还是没匹配上』—— 这两种的改法完全相反")
				.isGreaterThan(0);
	}

	@Test
	@DisplayName("不是检索的调用**不写**检索行（写空占位会把『检索了几次』这个数搞大）")
	void nonRetrievalCallsLeaveNoRetrievalRows() {
		String runId = "trace-no-retrieval";
		install(ScriptedModelTurn.callTool("call_1", "list_documents", "{}"),
				ScriptedModelTurn.answer("文档列表如上。"));
		service.runWithId(runId, "有哪些文档", (String) null, "conv-no-retrieval");

		assertThat(store.toolCallsOf(runId)).as("工具调用那一层有记录").isNotEmpty();
		assertThat(store.retrievalsOf(runId))
				.as("list_documents 不是检索类工具 —— 检索那一层必须是**空的**，而不是一行空明细")
				.isEmpty();
	}

	// ==================================================================
	//  2. 五层装配：对得齐，而不只是"五张表都非空"
	// ==================================================================

	@Test
	@DisplayName("★ 五层装配：会话 / 运行 / LLM 调用 / 工具调用 / 检索 一次读出来，且按步对齐")
	void fiveLayersAssembleAligned() {
		String runId = "trace-five-layers";
		runWithSearch(runId, "分块策略的结论是什么", "分块", 5, "conv-five");

		Map<String, Object> trace = traceView.of(runId).orElseThrow();

		// 第 2 层：运行
		@SuppressWarnings("unchecked")
		Map<String, Object> runRow = (Map<String, Object>) trace.get("run");
		assertThat(runRow.get("id")).isEqualTo(runId);
		assertThat(runRow.get("stopReason")).isEqualTo("ANSWERED");

		// 第 3 层：LLM 调用
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
		assertThat(steps).as("每一次模型往返是一步").isNotEmpty();

		// 【核心断言：检索挂在**正确的那一步**上，而不是"总共有检索"】
		// 只断言"trace 里有检索"能通过一个把检索挂到第 1 步的错实现 ——
		// 而那种错在页面上表现为"它在第 1 步就查到了答案"，会让排查方向整个错
		Map<String, Object> searchStep = steps.stream()
				.filter(s -> !((List<?>) s.get("retrievalCalls")).isEmpty())
				.findFirst()
				.orElseThrow(() -> new AssertionError("没有任何一步挂着检索明细 —— 第 5 层没接上"));
		assertThat(((Number) searchStep.get("stepIndex")).intValue())
				.as("脚本第 1 轮就检索，所以检索挂在第 1 步").isEqualTo(1);
		assertThat(((Number) searchStep.get("retrievalCount")).intValue()).isEqualTo(1);
		assertThat(((Number) searchStep.get("retrievalHitTokens")).intValue())
				.as("这一步的检索 token 必须是正数（它就是『检索占了 prompt 多少』）").isGreaterThan(0);

		// 第 4 层：工具调用，挂在**同一步**上，且带上它自己的检索明细（同一份事实的两个入口）
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> calls = (List<Map<String, Object>>) searchStep.get("toolCalls");
		assertThat(calls).as("检索是通过一次工具调用发生的 —— 两层必须挂在同一步").isNotEmpty();
		Map<String, Object> searchCall = calls.stream()
				.filter(c -> "search_docs".equals(c.get("toolName"))).findFirst().orElseThrow();
		assertThat(searchCall.get("stage")).isEqualTo(ToolCallStage.OK.name());
		assertThat((List<?>) searchCall.get("retrievals"))
				.as("这次调用的检索明细也要能从它自己身上取到").isNotEmpty();

		// 检索汇总
		@SuppressWarnings("unchecked")
		Map<String, Object> retrieval = (Map<String, Object>) trace.get("retrieval");
		assertThat(((Number) retrieval.get("calls")).intValue()).isEqualTo(1);
		assertThat(((Number) retrieval.get("emptyCalls")).intValue()).as("这次不是空命中").isZero();
		assertThat(retrieval.get("hitTokensEstimated"))
				.as("估算口径必须显式标出来 —— 它不能与上游 usage 的账目口径混用").isEqualTo(true);

		// 五层的到场清单
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> layers = (List<Map<String, Object>>) trace.get("layers");
		assertThat(layers).extracting(l -> l.get("name"))
				.containsExactly("会话", "轮次 / 运行", "LLM 调用", "工具调用", "检索");
		assertThat(layers.get(4).get("expected"))
				.as("这次真的检索过，所以第 5 层**该有**内容 —— 它为空就是缺陷").isEqualTo(true);
	}

	@Test
	@DisplayName("五层到场清单必须能区分『这一层本来就不该有』与『这一层漏记了』")
	void layersTellExpectedFromMissing() {
		// 一次只列文档、不检索的运行：第 5 层为空是**正常的**
		String runId = "trace-layers-no-search";
		install(ScriptedModelTurn.callTool("call_1", "list_documents", "{}"),
				ScriptedModelTurn.answer("列表如上。"));
		service.runWithId(runId, "有哪些文档", (String) null, "conv-layers");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> layers = (List<Map<String, Object>>) traceView.of(runId).orElseThrow()
				.get("layers");
		assertThat(layers.get(4).get("expected"))
				.as("没调用过检索类工具 → 第 5 层为空是正常的，expected 必须是 false。"
						+ "不区分的话，『空』与『漏记』在页面上长得完全一样")
				.isEqualTo(false);
		assertThat(layers.get(3).get("expected")).as("工具调用那一层确实有内容").isEqualTo(true);
	}

	// ==================================================================
	//  3. 会话：把多次运行串起来
	// ==================================================================

	@Test
	@DisplayName("★ 会话 id 把同一段对话里的多次运行串起来，且不串进别的会话")
	void conversationThreadsRunsTogether() {
		String conversation = "conv-" + System.nanoTime();
		install(ScriptedModelTurn.answer("直接回答。"));

		String first = "conv-run-1";
		String second = "conv-run-2";
		String other = "conv-run-other";

		service.runWithId(first, "第一个问题", (String) null, conversation);
		service.runWithId(second, "第二个问题", (String) null, conversation);
		// 另起一段会话：它绝不能出现在上面那段会话里
		service.runWithId(other, "别人的问题", (String) null, "conv-" + System.nanoTime());

		assertThat(store.find(first).orElseThrow().conversationId()).isEqualTo(conversation);
		assertThat(store.runsOfConversation(conversation)).extracting(AgentRunRecord::id)
				.as("同一段会话里的运行要能一次读出来").containsExactlyInAnyOrder(first, second);
		assertThat(store.runsOfConversation(conversation)).extracting(AgentRunRecord::id)
				.as("别的会话的运行不能被串进来 —— 串进来的后果是『这段对话怎么有个我没问过的问题』")
				.doesNotContain(other);

		// trace 的会话那一层
		@SuppressWarnings("unchecked")
		Map<String, Object> conversationRow = (Map<String, Object>) traceView.of(first).orElseThrow()
				.get("conversation");
		assertThat(conversationRow.get("present")).isEqualTo(true);
		assertThat(conversationRow.get("id")).isEqualTo(conversation);
		assertThat(((Number) conversationRow.get("runCount")).intValue()).isEqualTo(2);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> runs = (List<Map<String, Object>>) conversationRow.get("runs");
		assertThat(runs).extracting(r -> r.get("id")).containsExactly(first, second);
		assertThat(runs).filteredOn(r -> Boolean.TRUE.equals(r.get("current")))
				.as("必须标出『我正在看的是哪一次』—— 一段对话里所有运行的形状都一样")
				.extracting(r -> r.get("id")).containsExactly(first);
	}

	@Test
	@DisplayName("没有会话信息时要说『没有』，而不是显示成『这段对话只有一次运行』")
	void absentConversationIsReportedAsAbsent() {
		// 不传会话 id（等价于 curl 直接调）
		String runId = "no-conversation";
		install(ScriptedModelTurn.answer("直接回答。"));
		service.runWithId(runId, "孤立的问题", (String) null, (String) null);

		assertThat(store.find(runId).orElseThrow().hasConversation())
				.as("空白会话 id 必须落成 NULL，而不是空串").isFalse();

		@SuppressWarnings("unchecked")
		Map<String, Object> conversationRow = (Map<String, Object>) traceView.of(runId).orElseThrow()
				.get("conversation");
		assertThat(conversationRow.get("present"))
				.as("★ 这两种情况对使用者是两件事：前者正常，后者说明调用方没传会话 id。"
						+ "都给一个空列表的话，它们就分不开了")
				.isEqualTo(false);
		assertThat(conversationRow.get("note")).as("要说清是哪种情况、以及为什么").isNotNull();
	}

	// ==================================================================
	//  4. 提示词指纹：一次运行里只能有一版
	// ==================================================================

	@Test
	@DisplayName("★ 每一步都落提示词指纹，且一次运行里**只有一个**指纹（出现两个就是自相矛盾）")
	void promptFingerprintIsRecordedOncePerRun() {
		String runId = "trace-fingerprint";
		runWithSearch(runId, "分块策略的结论是什么", "分块", 3, "conv-fingerprint");

		List<AgentRunRecord.StepCheckpoint> steps = store.stepsOf(runId);
		assertThat(steps).isNotEmpty();

		String expected = prompts.currentFingerprint(PromptKind.AGENT_LOOP);
		assertThat(expected).as("指纹形如 vN:hash").contains(":");

		for (AgentRunRecord.StepCheckpoint step : steps) {
			assertThat(step.promptFingerprint())
					.as("第 %d 步必须记下它用的是哪一版提示词 —— 没有它，"
							+ "『某天的答案变差了是不是因为换了提示词』永远追不到", step.stepIndex())
					.isEqualTo(expected);
		}
		assertThat(steps.stream().map(AgentRunRecord.StepCheckpoint::promptFingerprint).distinct())
				.as("★ 一次运行里只能有一个指纹。两个说明同一次对话的前后两轮遵循了不同的指令 —— "
						+ "那正是热更新刻意避免的形态，而它会让『这次用的哪一版』没有唯一答案")
				.hasSize(1);

		// trace 视图里也要带上（排查的人看的是页面，不是库）
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> stepRows = (List<Map<String, Object>>) traceView.of(runId).orElseThrow()
				.get("steps");
		assertThat(stepRows).extracting(s -> s.get("promptFingerprint")).containsOnly(expected);
	}

	// ==================================================================
	//  5. 算不出来就说算不出来（与成本看板同一条纪律）
	// ==================================================================

	@Test
	@DisplayName("金额算不出来时给 null + 原因，**不给 ¥0.00**")
	void costSaysUnknownInsteadOfZero() {
		String runId = "trace-cost";
		runWithSearch(runId, "分块策略的结论是什么", "分块", 3, "conv-cost");

		@SuppressWarnings("unchecked")
		Map<String, Object> cost = (Map<String, Object>) traceView.of(runId).orElseThrow().get("cost");

		// 本项目的价格表默认是空的（刻意的，理由见 CostPricing 的注释）
		assertThat(cost.get("pricingConfigured")).isEqualTo(false);
		assertThat(cost.get("amount"))
				.as("★ 价格没配时必须给 null。给 ¥0.00 会被读成『这次不花钱』，"
						+ "而真相是『我们不知道它花多少』")
				.isNull();
		assertThat(cost.get("amountReason")).as("必须给一个能照着做的原因，而不是只说算不出来").isNotNull();
		assertThat(((Number) cost.get("totalTokens")).intValue())
				.as("token 来自上游 usage，与有没有价格无关").isGreaterThan(0);
	}

	// ==================================================================
	//  6. 边界
	// ==================================================================

	@Test
	@DisplayName("不存在的运行 → 空（调用方给 404，而不是一个『跑了但没留痕』的空 trace）")
	void unknownRunGivesEmpty() {
		assertThat(traceView.of("根本没有这个运行")).isEmpty();
	}

}
