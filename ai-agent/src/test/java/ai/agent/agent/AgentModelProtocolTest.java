package ai.agent.agent;

import ai.agent.tool.ToolCallOutcome;
import ai.agent.tool.ToolCallStage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>协议层往返测试：从 HTTP 请求到 HTTP 响应的完整链路。</b>
 *
 * <h2>它补的是哪个缺口</h2>
 * 另外 39 个测试都在 {@link ModelTurn} 接口那一层打桩，所以下面这一整条链路从没被跑过：
 * <pre>
 *   AgentLoop → OpenAiModelTurn → Spring AI ChatModel → OpenAI Java SDK
 *             → HTTP（tools[] 序列化）→ 上游响应（tool_calls 反序列化）→ 回到 Loop
 * </pre>
 * 这一段的失败模式都很安静：工具定义没进请求、响应的 {@code tool_calls} 解析不出来、
 * assistant 的 tool_call 与 tool 消息的 id 对不上（协议层 400）……
 * 它们**编译通过、类型也对**，只有真跑一次才会暴露。
 *
 * <h2>为什么用真 HTTP 服务而不是 MockRestServiceServer</h2>
 * 后者会跳过 JSON 序列化/反序列化 —— 而那恰好是最容易错的一段。
 * 真端口 + 真 SDK 让"请求里到底发了什么"成为可断言的事实（{@link FakeOpenAiServer} 会记录请求体）。
 *
 * <h2>三件这个测试要证明的事</h2>
 * <ol>
 *   <li><b>工具定义真的发给了模型</b> —— 名字、描述（四段拼装后）、JSON Schema 参数都在请求里；</li>
 *   <li><b>多轮工具往返在协议上成立</b> —— 第 2 轮请求里有 assistant(tool_calls) 与 role=tool
 *       的消息，且 {@code tool_call_id} 与响应的 id **一致**（不一致会被上游 400，而报错信息通常很含糊）；</li>
 *   <li><b>回填内容真的在请求里</b> —— 也就是"错误信息确实送到了模型眼前"，而不只是被生成出来。</li>
 * </ol>
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key", "spring.ai.openai.chat.options.model=fake-model",
		"ai.agent.loop.max-steps=6", "ai.agent.loop.max-tokens=12000", "ai.agent.loop.max-duration-ms=60000" })
class AgentModelProtocolTest {

	/**
	 * 常驻的假端点。<b>必须是 static 且只起一次</b>：
	 * Spring 测试上下文是缓存的，{@code @DynamicPropertySource} 里的 base-url 只解析一次，
	 * 所以端点必须活得比单个测试方法长 —— 每个测试只换脚本（{@link FakeOpenAiServer#script}）。
	 */
	private static FakeOpenAiServer fakeServer;

	/**
	 * 把 base-url 指到假端点。
	 *
	 * <p>用 {@link DynamicPropertySource} 而不是写死一个端口：假端点用 0 端口启动（系统分配），
	 * 所以必须**在容器启动前**把真实端口注入配置。
	 *
	 * <p>顺带说明一件事：{@code spring.ai.openai.base-url} 是<b>运行时属性</b>（不是构建期占位符），
	 * 所以才能在测试里换掉上游 —— 这一点在动手之前先用 {@code javap} 核实过
	 * （{@code AbstractOpenAiProperties.getBaseUrl()}），而不是想当然。
	 */
	@DynamicPropertySource
	static void pointAtFakeServer(DynamicPropertyRegistry registry) throws IOException {
		fakeServer = new FakeOpenAiServer();
		registry.add("spring.ai.openai.base-url", fakeServer::baseUrl);
	}

	@Autowired
	private AgentLoop agentLoop;

	/** 换脚本并清空上一轮的请求记录。 */
	private FakeOpenAiServer script(List<String> responses) {
		fakeServer.script(responses);
		return fakeServer;
	}

	// ------------------------------------------------------------------
	//  1. 工具定义有没有真的发给模型
	// ------------------------------------------------------------------

	@Test
	@DisplayName("工具定义真的进了请求：名字、四段描述、JSON Schema 参数都在 tools[] 里")
	void toolDefinitionsAreSentToModel() throws IOException {
		FakeOpenAiServer fake = script(
				List.of(FakeOpenAiServer.textResponse("我不需要查文档，直接回答。")));

		AgentRun run = agentLoop.run("你好");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(fake.callCount()).as("只问了一轮（模型直接作答）").isEqualTo(1);

		JsonNode request = fake.requestJson(0);
		JsonNode tools = request.get("tools");
		assertThat(tools).as("请求里必须有 tools 数组").isNotNull();
		assertThat(tools.isArray()).isTrue();
		assertThat(tools.size()).as("四把工具都要发出去").isEqualTo(4);

		// 把工具名收出来对照 —— 用集合断言而不是按下标取，
		// 因为 SDK 对 tools 数组的元素顺序不作保证，按下标取会让断言变得脆弱
		List<String> names = new java.util.ArrayList<>();
		tools.forEach(t -> names.add(t.path("function").path("name").asText()));
		assertThat(names).containsExactlyInAnyOrder("search_docs", "list_documents", "read_section", "propose_review");

		// 找到 search_docs，核对描述与参数
		JsonNode searchDocs = null;
		for (JsonNode t : tools) {
			if ("search_docs".equals(t.path("function").path("name").asText())) {
				searchDocs = t;
			}
		}
		assertThat(searchDocs).isNotNull();
		String description = searchDocs.path("function").path("description").asText();
		// 描述即 prompt：四段都要在（这段文字是模型选择工具的唯一依据）
		assertThat(description).contains("何时使用").contains("何时不要使用").contains("返回").contains("失败时");

		JsonNode parameters = searchDocs.path("function").path("parameters");
		assertThat(parameters.path("type").asText()).isEqualTo("object");
		assertThat(parameters.path("properties").has("query")).as("参数 schema 必须随请求发出").isTrue();
		assertThat(parameters.path("properties").path("query").path("type").asText()).isEqualTo("string");
		// 上一轮修的那个 bug 在这里变成了一条端到端断言：
		// schema 里必须真的带上注解生成的约束（否则模型看不到边界、却永远过不去）
		assertThat(parameters.path("properties").path("query").path("minLength").asInt())
				.as("@NotBlank → minLength:1 必须出现在发给模型的 schema 里").isEqualTo(1);
		assertThat(parameters.path("properties").path("topK").path("maximum").asInt())
				.as("@Max(10) → maximum:10 必须出现在发给模型的 schema 里").isEqualTo(10);
		assertThat(parameters.path("required")).isNotNull();
	}

	@Test
	@DisplayName("系统提示词里带着工具目录（同一份描述的第二处落点）")
	void systemPromptCarriesToolCatalog() throws IOException {
		FakeOpenAiServer fake = script(List.of(FakeOpenAiServer.textResponse("好的。")));

		agentLoop.run("你好");

		JsonNode messages = fake.requestJson(0).get("messages");
		assertThat(messages).isNotNull();
		String system = messages.get(0).path("content").asText();
		assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
		// 工具目录来自 ToolRegistry.renderCatalog()，与 tools[] 用的是同一个对象，
		// 所以不存在"两处描述不一致"的问题 —— 这里断言它确实被拼进去了
		assertThat(system).contains("你当前可以使用的工具").contains("search_docs").contains("read_section");
		assertThat(system).as("人设与工作方式也要在").contains("think");
	}

	// ------------------------------------------------------------------
	//  2. 多轮工具往返在协议上成立
	// ------------------------------------------------------------------

	@Test
	@DisplayName("多轮工具往返：第 2 轮请求里有 assistant(tool_calls) 与 role=tool，且 id 对得上")
	void toolRoundTripIsProtocolValid() throws IOException {
		FakeOpenAiServer fake = script(List.of(
				FakeOpenAiServer.toolCallResponse(FakeOpenAiServer.calls(
						"call_proto_1", "list_documents", "{}")),
				FakeOpenAiServer.textResponse("知识库里有 4 份文档。")));

		AgentRun run = agentLoop.run("知识库里有哪些文档？");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(run.toolCallCount()).isEqualTo(1);
		assertThat(run.allOutcomes().get(0).stage()).isEqualTo(ToolCallStage.OK);
		assertThat(fake.callCount()).as("两轮：一次工具往返 + 一次作答").isEqualTo(2);

		JsonNode secondRequest = fake.requestJson(1);
		JsonNode messages = secondRequest.get("messages");
		assertThat(messages).isNotNull();

		// 找 assistant 消息里的 tool_calls
		JsonNode assistantToolCall = null;
		JsonNode toolMessage = null;
		for (JsonNode message : messages) {
			String role = message.path("role").asText();
			if ("assistant".equals(role) && message.has("tool_calls") && !message.get("tool_calls").isEmpty()) {
				assistantToolCall = message.get("tool_calls").get(0);
			}
			if ("tool".equals(role)) {
				toolMessage = message;
			}
		}

		assertThat(assistantToolCall).as("第 2 轮请求里必须有带 tool_calls 的 assistant 消息").isNotNull();
		assertThat(toolMessage).as("第 2 轮请求里必须有 role=tool 的消息").isNotNull();

		// 【核心断言】两边的 id 必须一致。不一致时上游会以 400 拒绝，
		// 而那个报错通常只说"invalid request"，根本不会指出是 id 对不上
		String callId = assistantToolCall.path("id").asText();
		assertThat(callId).isEqualTo("call_proto_1");
		assertThat(toolMessage.path("tool_call_id").asText())
				.as("tool 消息的 tool_call_id 必须与 assistant 的 tool_call id 完全相同")
				.isEqualTo(callId);
		assertThat(assistantToolCall.path("function").path("name").asText()).isEqualTo("list_documents");
		assertThat(assistantToolCall.path("type").asText()).isEqualTo("function");

		// 回填内容必须在 tool 消息里（这是"错误信息送到了模型眼前"的直接证据）
		String toolContent = toolMessage.path("content").asText();
		assertThat(toolContent).as("工具结果必须作为 tool 消息内容回填").contains("ledger-progress.md");
		assertThat(toolContent).as("回填要带小标题，模型才能区分'工具返回的'与'用户说的'").contains("工具");
	}

	@Test
	@DisplayName("token 用量从 HTTP 响应里读出来（阶段四成本看板的数据源）")
	void usageIsParsedFromHttpResponse() throws IOException {
		FakeOpenAiServer fake = script(List.of(
				FakeOpenAiServer.toolCallResponse(FakeOpenAiServer.calls(
						"call_usage", "list_documents", "{}")),
				FakeOpenAiServer.textResponse("好了。")));

		AgentRun run = agentLoop.run("有哪些文档？");

		// 假端点固定返回 220/35 与 300/60
		assertThat(run.totalTokens()).as("两轮的 token 合计必须从响应里读出来").isEqualTo(220 + 35 + 300 + 60);
		assertThat(run.steps().get(0).promptTokens()).isEqualTo(220);
		assertThat(run.steps().get(0).completionTokens()).isEqualTo(35);
		assertThat(run.steps().get(1).promptTokens()).isEqualTo(300);
	}

	// ------------------------------------------------------------------
	//  3. 忽略：模型直接作答时不要发工具
	// ------------------------------------------------------------------

	@Test
	@DisplayName("模型只给文字（finish_reason=stop）→ 一轮结束，不再多问一次")
	void plainAnswerEndsInOneRound() throws IOException {
		FakeOpenAiServer fake = script(List.of(FakeOpenAiServer.textResponse("我是这个项目的技术助手。")));

		AgentRun run = agentLoop.run("你是谁？");

		assertThat(run.finalAnswer()).isEqualTo("我是这个项目的技术助手。");
		assertThat(run.steps()).hasSize(1);
		assertThat(run.toolCallCount()).isZero();
		assertThat(fake.callCount()).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	//  4. 截断信号：finish_reason=length 必须被带出来
	// ------------------------------------------------------------------

	@Test
	@DisplayName("finish_reason=length 时，半截 JSON 参数被报成解析失败，但**不是**静默失败")
	void truncatedArgumentsAreHandledVisibly() throws IOException {
		// 参数是半截 JSON —— 这正是"输出额度太小"的真实后果
		FakeOpenAiServer fake = script(List.of(
				FakeOpenAiServer.truncatedToolCallResponse("call_trunc", "search_docs", "{\"query\":\"分块策"),
				FakeOpenAiServer.textResponse("我换个方式回答。")));

		AgentRun run = agentLoop.run("分块策略是什么？");

		// 关键：它必须走到"被拒绝 + 回填"，而不是炸掉或静默当成空参数
		ToolCallOutcome outcome = run.allOutcomes().get(0);
		assertThat(outcome.stage()).isEqualTo(ToolCallStage.PARSE_ERROR);
		assertThat(outcome.fixableByModel()).isTrue();
		// 回填里要带 schema，模型才知道正确的参数长什么样
		assertThat(outcome.feedback()).contains("properties");

		// 而第 2 轮的请求里必须真的带上这段回填（否则模型没有机会修正）
		JsonNode secondRequest = fake.requestJson(1);
		StringBuilder all = new StringBuilder();
		secondRequest.get("messages").forEach(m -> all.append(m.path("content").asText()).append('\n'));
		assertThat(all.toString()).as("解析失败的回填必须进请求").contains("工具");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
	}

	// ------------------------------------------------------------------
	//  5. 未知工具：白名单拒绝 + 可用列表回填，端到端走一遍
	// ------------------------------------------------------------------

	@Test
	@DisplayName("模型编了工具名 → 白名单拒绝，且第 2 轮请求里带着可用工具列表（端到端自愈）")
	void unknownToolFeedbackReachesModelEndToEnd() throws IOException {
		FakeOpenAiServer fake = script(List.of(
				// 第 1 轮：编一个很像但不存在的名字
				FakeOpenAiServer.toolCallResponse(FakeOpenAiServer.calls(
						"call_bad", "doc_search", "{\"query\":\"分块\"}")),
				// 第 2 轮：改用正确的名字
				FakeOpenAiServer.toolCallResponse(FakeOpenAiServer.calls(
						"call_good", "search_docs", "{\"query\":\"分块策略\",\"topK\":2}")),
				// 第 3 轮：作答
				FakeOpenAiServer.textResponse("查到了，块大小对齐后固定切分占优。")));

		AgentRun run = agentLoop.run("分块策略怎么样？");

		assertThat(run.allOutcomes()).hasSize(2);
		assertThat(run.allOutcomes().get(0).stage()).isEqualTo(ToolCallStage.UNKNOWN_TOOL);
		assertThat(run.allOutcomes().get(1).stage()).isEqualTo(ToolCallStage.OK);
		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);
		assertThat(fake.callCount()).isEqualTo(3);

		// 【这条断言是这一轮最想拿到的证据】
		// 前 39 个测试只能证明"回填被生成出来了"；这一条证明它**真的到达了模型**
		// （走完了工具执行 → 回填格式化 → tool 消息 → HTTP 请求体 的完整路径）
		String secondRequestRaw = fake.requestBodies().get(1);
		assertThat(secondRequestRaw).as("可用工具列表必须出现在出站请求里")
				.contains("search_docs").contains("list_documents").contains("read_section");
		assertThat(secondRequestRaw).as("拒绝原因必须出现在出站请求里").contains("doc_search");
	}

}
