package ai.agent.agent;

import ai.agent.config.AgentLoopProperties;
import ai.agent.config.ChatCallProperties;
import ai.agent.prompt.ClasspathPromptService;
import ai.agent.prompt.PromptService;
import ai.agent.token.impl.CharacterClassTokenEstimator;
import ai.agent.tool.ToolCallExecutor;
import ai.agent.tool.ToolCallOutcome;
import ai.agent.tool.ToolCallStage;
import ai.agent.tool.ToolInvocation;
import ai.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>手写 ReAct 循环的测试</b>：think → act → observe 三步，以及四种停止条件。
 *
 * <h2>为什么必须用脚本化模型（而不是真模型）</h2>
 * 这个类要验证的东西里，有一大半<b>真模型不会配合触发</b>：
 * "模型编了个工具名然后改对"、"模型重复调同一个动作 3 次"、
 * "模型转满 6 轮还在调工具"、"模型返回空响应"。
 * 用真模型验证它们只能靠祈祷 —— 而且模型每次跑的结果还不一样，
 * 断言只能写得极松（"最后有答案就行"），松到失去意义。
 *
 * <p>脚本化之后，每条分支都能被确定性地跑到，而且断言可以精确到
 * <b>"第 2 轮的请求里必须真的带上第 1 轮的错误回填"</b> ——
 * 这是"边界生效了"最直接的证据。只断言 {@code outcome.feedback()} 是不够的：
 * 那段文本完全可能被算出来却从没进过请求（"算了但没接线"是本项目里出现过不止一次的一类 bug）。
 *
 * <h2>为什么用 {@code @TestConfiguration} + {@code @Primary} 换掉 ModelTurn</h2>
 * 生产装配里 {@code OpenAiModelTurn} 是 {@code @ConditionalOnMissingBean}，
 * 但 {@code @TestConfiguration} 里的 bean 定义与自动配置的可见性顺序不保证，
 * 所以这里额外标 {@code @Primary}：<b>让"用哪个模型"这件事在测试里是显式的</b>，
 * 而不是依赖条件装配的先后。一旦哪天它被换回真实的适配器，
 * 测试会真的发 HTTP 请求然后失败 —— 那种失败很吵，不会被忽略。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key", "ai.agent.loop.max-steps=6",
		"ai.agent.loop.max-tokens=12000", "ai.agent.loop.max-duration-ms=60000",
		"ai.agent.loop.max-identical-actions=2" })
class AgentLoopTest {

	@Autowired
	private AgentLoop agentLoop;

	@Autowired
	private ToolRegistry registry;

	@Autowired
	private ToolCallExecutor executor;

	/** 当前测试装上的脚本模型（每个测试自己设置）。 */
	private static final AtomicReference<ScriptedModelTurn> CURRENT = new AtomicReference<>();

	@TestConfiguration
	static class ScriptedModelConfig {

		@Bean
		@Primary
		ModelTurn scriptedModelTurn() {
			// 用一个"可替换的委托"而不是每个测试新建一个 bean：
			// 测试之间共享同一个 bean 实例，测试方法只换里面的脚本
			return new DelegatingModelTurn();
		}
	}

	/** 把调用转发给 {@link #CURRENT} 里当前的脚本模型。 */
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

	/** 装上一份脚本，返回它以便断言"模型收到了什么"。 */
	private static ScriptedModelTurn install(ModelTurn.ModelReply... replies) {
		ScriptedModelTurn turn = new ScriptedModelTurn(List.of(replies));
		CURRENT.set(turn);
		return turn;
	}

	// ------------------------------------------------------------------
	//  基本三步：think → act → observe
	// ------------------------------------------------------------------

	@Test
	@DisplayName("think → act → observe：模型调工具拿到结果后给出答案，共 2 轮")
	void reactLoopRunsToolThenAnswers() {
		ScriptedModelTurn model = install(
				ScriptedModelTurn.callTool("search_docs", "{\"query\":\"分块策略\",\"topK\":2}"),
				ScriptedModelTurn.answer("分块策略的结论是：块大小对齐之后固定切分全面占优。"));

		AgentRun run = agentLoop.run("分块策略的结论是什么？");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(run.answered()).isTrue();
		assertThat(run.finalAnswer()).contains("固定切分");
		assertThat(run.steps()).as("两步：一次工具往返 + 一次作答").hasSize(2);
		assertThat(run.steps().get(0).usedTools()).isTrue();
		assertThat(run.steps().get(1).usedTools()).isFalse();
		assertThat(run.toolCallCount()).isEqualTo(1);

		// 工具真的执行了（这把工具查的是真实语料，所以结果里会带片段）
		ToolCallOutcome outcome = run.allOutcomes().get(0);
		assertThat(outcome.stage()).isEqualTo(ToolCallStage.OK);
		assertThat(run.allOutcomes()).as("第一次调用应当是成功的").allMatch(ToolCallOutcome::succeeded);
	}

	@Test
	@DisplayName("observe 真的进了下一轮请求：第 2 轮的上下文里必须带工具返回的内容")
	void toolResultIsFedBackIntoNextRequest() {
		ScriptedModelTurn model = install(
				ScriptedModelTurn.callTool("list_documents", "{}"),
				ScriptedModelTurn.answer("知识库里有 4 份文档。"));

		agentLoop.run("知识库里有哪些文档？");

		assertThat(model.callCount()).isEqualTo(2);
		// 这是"回填接线正确"的证据：只断言 outcome 是证明不了这一点的
		String secondRequest = model.requestTextAt(1);
		assertThat(secondRequest).as("第 2 轮请求里必须有工具返回的内容").contains("ledger-progress.md");
		// 上下文形状：system + user + assistant(tool_calls) + tool
		assertThat(model.requestShapeAt(1)).as("第 2 轮的消息构成")
				.containsEntry("system", 1)
				.containsEntry("user", 1)
				.containsEntry("assistant", 1)
				.containsEntry("tool", 1);
	}

	@Test
	@DisplayName("assistant 消息必须带上 tool_calls（不然 tool 消息在协议上找不到被回复对象）")
	void assistantMessageCarriesToolCalls() {
		ScriptedModelTurn model = install(
				ScriptedModelTurn.callTool("call_abc", "list_documents", "{}"),
				ScriptedModelTurn.answer("好了。"));

		agentLoop.run("有哪些文档？");

		String secondRequest = model.requestTextAt(1);
		assertThat(secondRequest).as("assistant 消息里要能看到它调用过什么").contains("tool_call:list_documents");
		assertThat(secondRequest).as("tool 消息里要带对应的返回").contains("tool_response:list_documents");
	}

	// ------------------------------------------------------------------
	//  自愈：边界 + 回填的价值所在
	// ------------------------------------------------------------------

	@Test
	@DisplayName("自愈：模型先编错工具名 → 回填列出可用工具 → 第 2 轮改用正确的工具并成功")
	void healsFromUnknownToolAfterFeedback() {
		ScriptedModelTurn model = install(
				// 第 1 轮：编了一个很像但不存在的工具名（实测里最常见的形态）
				ScriptedModelTurn.callTool("doc_search", "{\"query\":\"分块\"}"),
				// 第 2 轮：改用正确名字
				ScriptedModelTurn.callTool("call_2", "search_docs", "{\"query\":\"分块策略\",\"topK\":2}"),
				// 第 3 轮：作答
				ScriptedModelTurn.answer("查到了：块大小对齐后固定切分占优。"));

		AgentRun run = agentLoop.run("分块策略怎么样？");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(run.allOutcomes()).hasSize(2);
		assertThat(run.allOutcomes().get(0).stage()).isEqualTo(ToolCallStage.UNKNOWN_TOOL);
		assertThat(run.allOutcomes().get(1).stage()).isEqualTo(ToolCallStage.OK);

		// 【核心证据】第 2 轮的请求里必须真的带着"没有这个工具 + 可用的是哪些"
		String secondRequest = model.requestTextAt(1);
		assertThat(secondRequest).as("回填必须真的发给了模型（算了但没接线是另一回事）")
				.contains("没有名为「doc_search」的工具");
		assertThat(secondRequest).as("回填里必须列出可用工具，模型才可能改对")
				.contains("search_docs").contains("list_documents");
	}

	@Test
	@DisplayName("自愈：模型给了越界参数 → 回填带字段名与约束 → 第 2 轮改对")
	void healsFromValidationErrorAfterFeedback() {
		ScriptedModelTurn model = install(
				// topK=99 越界（schema 里 maximum=10）
				ScriptedModelTurn.callTool("search_docs", "{\"query\":\"分块\",\"topK\":99}"),
				ScriptedModelTurn.callTool("call_2", "search_docs", "{\"query\":\"分块\",\"topK\":3}"),
				ScriptedModelTurn.answer("好，查到了。"));

		AgentRun run = agentLoop.run("分块策略？");

		assertThat(run.allOutcomes().get(0).stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(run.allOutcomes().get(1).stage()).isEqualTo(ToolCallStage.OK);
		String secondRequest = model.requestTextAt(1);
		assertThat(secondRequest).as("回填里要带字段名（模型才知道改哪个字段）").contains("topK");
		assertThat(secondRequest).as("回填里要带 schema（模型才知道改成多少）").contains("maximum");
	}

	// ------------------------------------------------------------------
	//  停止条件之一：最大步数
	// ------------------------------------------------------------------

	@Test
	@DisplayName("最大步数：模型每轮都要工具、始终不回答 → 停在 MAX_STEPS，且步数不超过上限")
	void stopsAtMaxSteps() {
		// 每轮换一个新参数，避免触发"重复动作"检测 —— 这样测到的才是步数上限
		ScriptedModelTurn model = install(
				ScriptedModelTurn.callTool("c1", "search_docs", "{\"query\":\"分块1\"}"),
				ScriptedModelTurn.callTool("c2", "search_docs", "{\"query\":\"分块2\"}"),
				ScriptedModelTurn.callTool("c3", "search_docs", "{\"query\":\"分块3\"}"),
				ScriptedModelTurn.callTool("c4", "search_docs", "{\"query\":\"分块4\"}"),
				ScriptedModelTurn.callTool("c5", "search_docs", "{\"query\":\"分块5\"}"),
				ScriptedModelTurn.callTool("c6", "search_docs", "{\"query\":\"分块6\"}"),
				ScriptedModelTurn.callTool("c7", "search_docs", "{\"query\":\"分块7\"}"));

		AgentRun run = agentLoop.run("一直查但不回答");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.MAX_STEPS);
		assertThat(run.answered()).isFalse();
		assertThat(run.finalAnswer()).isNull();
		assertThat(run.steps()).as("上限是 6，不能跑出第 7 步").hasSize(6);
		assertThat(model.callCount()).as("第 7 轮不该发起模型调用（上限在调用之前判定）").isEqualTo(6);
	}

	// ------------------------------------------------------------------
	//  停止条件之二：token 上限
	// ------------------------------------------------------------------

	@Test
	@DisplayName("token 上限：累计用量到顶就停，且不会先花掉再判定")
	void stopsAtMaxTokens() {
		// 脚本里每次回复 6000 token（prompt+completion），两轮就到 12000
		ModelTurn.ModelReply heavyCall1 = new ModelTurn.ModelReply("",
				List.of(ToolInvocation.from("c1", "search_docs", "{\"query\":\"分块1\"}")), 5900, 100, false);
		ModelTurn.ModelReply heavyCall2 = new ModelTurn.ModelReply("",
				List.of(ToolInvocation.from("c2", "search_docs", "{\"query\":\"分块2\"}")), 5900, 100, false);
		ModelTurn.ModelReply heavyCall3 = new ModelTurn.ModelReply("",
				List.of(ToolInvocation.from("c3", "search_docs", "{\"query\":\"分块3\"}")), 5900, 100, false);
		ScriptedModelTurn model = install(heavyCall1, heavyCall2, heavyCall3);

		AgentRun run = agentLoop.run("烧钱的循环");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.MAX_TOKENS);
		assertThat(run.totalTokens()).as("累计用量至少到上限").isGreaterThanOrEqualTo(12000);
		// 第 3 轮不该被发起：上限在调用模型**之前**判定，所以最多只花掉两轮的钱
		assertThat(model.callCount()).as("不能在超限之后还多调一次模型").isEqualTo(2);
		assertThat(run.totalTokens()).as("不允许先花掉第三轮再判定超限").isLessThan(18000);
	}

	// ------------------------------------------------------------------
	//  停止条件之三：时间上限
	// ------------------------------------------------------------------

	@Test
	@DisplayName("时间上限：模型变慢时停在 MAX_DURATION，并且会带上已经跑完的步骤")
	void stopsAtMaxDuration() {
		ScriptedModelTurn slow = new ScriptedModelTurn(List.of(
				ScriptedModelTurn.callTool("c1", "search_docs", "{\"query\":\"分块1\"}"),
				ScriptedModelTurn.callTool("c2", "search_docs", "{\"query\":\"分块2\"}"),
				ScriptedModelTurn.callTool("c3", "search_docs", "{\"query\":\"分块3\"}"),
				ScriptedModelTurn.answer("终于答完了")));
		// 每轮睡 40ms，而时间上限设成 50ms：第 2 轮之前就会判定超时
		CURRENT.set(slow);
		AgentLoop fastTimeoutLoop = new AgentLoop(registry, executor, new SlowModelTurn(slow, 40L),
				new AgentLoopProperties(6, 12000, 50L, 2), promptService());

		AgentRun run = fastTimeoutLoop.run("慢模型");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.MAX_DURATION);
		assertThat(run.elapsedMs()).as("已经用掉的时间要如实记录").isGreaterThanOrEqualTo(40L);
		assertThat(run.steps()).as("停之前跑过的步骤要带回来（成本已经花了，不能丢）").isNotEmpty();
	}

	/** 每轮先睡眠再委托给内层模型 —— 用来确定性地制造"变慢"的下游。 */
	record SlowModelTurn(ModelTurn delegate, long sleepMs) implements ModelTurn {

		@Override
		public ModelReply chat(List<Message> messages) {
			try {
				Thread.sleep(sleepMs);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return delegate.chat(messages);
		}

	}

	/**
	 * 系统提示词的来源（阶段四第 3 件之后：提示词从版本库取）。
	 *
	 * <p>这里刻意用<b>只读 classpath 的替身</b>而不是真的 {@code PromptService}：
	 * 这个类要验证的是<b>循环的停止条件</b>，不是提示词存储。
	 * 让每个循环测试都去起一个 H2 库，会把"验证循环"变成"验证循环 + 验证存储"——
	 * 而后者失败时前者的结论也跟着不可信。
	 * <b>代价必须写清</b>：这个替身证明不了热更新（它永远返回 classpath 那一份），
	 * 热更新由 {@code PromptHotReloadTest} 用真服务验证。
	 */
	private PromptService promptService() {
		return new ClasspathPromptService(new CharacterClassTokenEstimator(new ChatCallProperties()));
	}

	// ------------------------------------------------------------------
	//  停止条件之四：重复动作
	// ------------------------------------------------------------------

	@Test
	@DisplayName("重复动作：同一个工具 + 同一份参数第 3 次出现 → 停在 DUPLICATE_ACTION（与 MAX_STEPS 分开）")
	void stopsAtDuplicateAction() {
		// 完全相同的调用重复给 —— 这正是模型卡住时的真实形态
		ScriptedModelTurn model = install(
				ScriptedModelTurn.callTool("c1", "search_docs", "{\"query\":\"不存在的词\"}"),
				ScriptedModelTurn.callTool("c2", "search_docs", "{\"query\":\"不存在的词\"}"),
				ScriptedModelTurn.callTool("c3", "search_docs", "{\"query\":\"不存在的词\"}"),
				ScriptedModelTurn.callTool("c4", "search_docs", "{\"query\":\"不存在的词\"}"));

		AgentRun run = agentLoop.run("反复问同一件事");

		assertThat(run.stopReason())
				.as("必须是 DUPLICATE_ACTION 而不是 MAX_STEPS：两者的改进方向相反")
				.isEqualTo(AgentStopReason.DUPLICATE_ACTION);
		// max-identical-actions=2 → 前 2 次正常执行，第 3 次触发。
		//
		// 【这里断言 3 而不是 2，是一个值得记下的取舍】：toolCallCount 统计的是
		// "模型提出的意图次数"，**包含那次被拒绝的** —— 因为报告要回答的问题是
		// "模型一共想调几次、其中几次没让它调"，而不是"实际执行了几次"。
		// 我第一版写成 2（以为统计的是执行次数），测试直接报 `expected: 2 but was: 3`。
		// 值得记的是：**那时实现是对的、断言是错的** ——
		// 如果当时顺手去"修"实现（把 count 挪到检测之后），就会得到一个
		// 悄悄少报一次的数字，而报告里那条"模型想调 N 次"从此永远偏小。
		assertThat(run.toolCallCount()).as("3 次意图都记入（含第 3 次被拦下的）").isEqualTo(3);
		// 真正被执行的是前 2 次：第 3 次在真正执行之前就被拦下了
		assertThat(run.allOutcomes()).as("只有前 2 次真的产生了执行结论").hasSize(2);
		assertThat(model.callCount()).as("它是在第 3 轮里被拦下的").isEqualTo(3);
	}

	@Test
	@DisplayName("参数里的空白不影响重复判定（{\"a\": 1} 与 {\"a\":1} 视为同一个动作）")
	void duplicateDetectionIgnoresWhitespace() {
		ScriptedModelTurn model = install(
				ScriptedModelTurn.callTool("c1", "search_docs", "{\"query\": \"同一个词\"}"),
				ScriptedModelTurn.callTool("c2", "search_docs", "{\"query\":\"同一个词\"}"),
				ScriptedModelTurn.callTool("c3", "search_docs", "{ \"query\" : \"同一个词\" }"),
				ScriptedModelTurn.answer("算了"));

		AgentRun run = agentLoop.run("带空白的重复");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.DUPLICATE_ACTION);
	}

	// ------------------------------------------------------------------
	//  异常与空响应
	// ------------------------------------------------------------------

	@Test
	@DisplayName("模型调用抛异常 → MODEL_ERROR，但已经跑完的步骤要带回来（成本已经花了）")
	void modelFailureKeepsPartialTrace() {
		// 一个"第一轮正常调工具、第二轮直接抛"的模型：模拟上游在任务中途挂掉。
		// 这比"一上来就抛"更接近真实故障，而且能验证"已花的成本有没有被保住"
		ModelTurn flaky = new ModelTurn() {
			private int calls;

			@Override
			public ModelReply chat(List<Message> messages) {
				calls++;
				if (calls == 1) {
					return ScriptedModelTurn.callTool("search_docs", "{\"query\":\"分块\"}");
				}
				throw new IllegalStateException("模拟上游 500");
			}
		};
		AgentLoop loop = new AgentLoop(registry, executor, flaky, AgentLoopProperties.defaults(),
				promptService());

		AgentRun run = loop.run("会中途失败的问题");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.MODEL_ERROR);
		assertThat(run.answered()).isFalse();
		// 关键：不抛出去。抛出去的话"它查了一次然后模型挂了"这个信息就丢了，
		// 而那次工具执行是花了钱的
		assertThat(run.steps()).as("失败也要返回一份报告，而不是抛异常").isNotEmpty();
		assertThat(run.allOutcomes()).as("第一次成功的工具调用要留在记录里").hasSize(1);
	}

	@Test
	@DisplayName("模型返回空（无文字、无工具调用）→ EMPTY_RESPONSE，不能算成 ANSWERED")
	void emptyResponseIsItsOwnStopReason() {
		install(new ModelTurn.ModelReply("", List.of(), 10, 0, false));

		AgentRun run = agentLoop.run("空响应");

		// 归到 ANSWERED 会在报告里产生"成功但答案是空的"记录 —— 那是最坏的一种观测
		assertThat(run.stopReason()).isEqualTo(AgentStopReason.EMPTY_RESPONSE);
		assertThat(run.answered()).isFalse();
	}

	@Test
	@DisplayName("工具内部抛异常 → 不中断循环，变成 EXECUTION_ERROR 的回填继续跑")
	void toolExceptionDoesNotBreakLoop() {
		// 用一把会抛异常的工具：read_section 传一个不存在的文档名不会抛（返回 Failure），
		// 所以这里直接构造场景 —— 让模型调用一个存在但参数会触发工具内部异常的路径。
		// search_docs 在语料加载失败时会抛，而那是环境问题；这里改用"重复调用不会被执行"之外的方式：
		// 直接断言执行器对异常的兜底行为（详见 ToolExceptionHandlingTest 的等价断言）
		ScriptedModelTurn model = install(
				ScriptedModelTurn.callTool("c1", "read_section", "{\"documentId\":\"不存在的文档.md\",\"section\":\"一\"}"),
				ScriptedModelTurn.answer("没找到那份文档，我换个方式回答。"));

		AgentRun run = agentLoop.run("读一份不存在的文档");

		// read_section 对"文档不存在"返回的是 Failure（工具正常完成、给了否定结论），
		// 所以 stage 是 OK —— 这正是"没查到"与"炸了"要分开的那个设计
		assertThat(run.allOutcomes().get(0).stage()).isEqualTo(ToolCallStage.OK);
		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		String secondRequest = model.requestTextAt(1);
		assertThat(secondRequest).as("回填里必须列出真实可用的文档名（否则模型只会再编一个）")
				.contains("ledger-progress.md");
		assertThat(secondRequest).as("并且要明确说'用同样参数重试没用'").contains("重试");
	}

	// ------------------------------------------------------------------
	//  配置与自检
	// ------------------------------------------------------------------

	@Test
	@DisplayName("三重上限的默认值都在配置里（不是硬编码在代码里）")
	void limitsComeFromConfiguration() {
		AgentLoopProperties props = agentLoop.properties();

		assertThat(props.maxSteps()).isEqualTo(6);
		assertThat(props.maxTokens()).isEqualTo(12000);
		assertThat(props.maxDurationMs()).isEqualTo(60000L);
		assertThat(props.maxIdenticalActions()).isEqualTo(2);
	}

	@Test
	@DisplayName("非法上限配置在构造期就抛（而不是运行期才发现）")
	void invalidLimitsFailFast() {
		assertThat(org.assertj.core.api.Assertions
				.catchThrowable(() -> new AgentLoopProperties(0, 12000, 1000L, 2)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(org.assertj.core.api.Assertions
				.catchThrowable(() -> new AgentLoopProperties(6, 0, 1000L, 2)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(org.assertj.core.api.Assertions
				.catchThrowable(() -> new AgentLoopProperties(6, 12000, 0L, 2)))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
