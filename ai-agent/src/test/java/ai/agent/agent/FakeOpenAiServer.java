package ai.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>假的 OpenAI 兼容端点</b>（测试替身）—— 用来验证"协议层往返"这一段。
 *
 * <h2>它补的是哪一个缺口（这条比代码本身重要）</h2>
 * 之前 39 个测试全部用 {@link ScriptedModelTurn} 在 {@link ModelTurn} 接口那一层打桩，
 * 所以下面这一整条链路<b>从来没有被跑过</b>：
 * <pre>
 *   AgentLoop → OpenAiModelTurn → Spring AI 的 ChatModel → OpenAI Java SDK
 *             → HTTP 请求（tools[] 序列化）→ 上游响应（tool_calls 反序列化）→ 回到 Loop
 * </pre>
 * 那一段里有大量"只有真跑才知道"的东西：请求里的 {@code tools[]} 长什么样、
 * 响应的 {@code tool_calls} 能不能被 SDK 认出来、assistant 的 {@code tool_calls}
 * 与 tool 消息的 {@code tool_call_id} 在协议上对不对得上。
 * 这些**编译通过、类型契约核实过**，但没跑过 —— 所以当时能说的只有"Java 侧逻辑有测试"，
 * 不能说"端到端通"。
 *
 * <h2>为什么是"真起 HTTP 服务"而不是 MockRestServiceServer / 桩 RestClient</h2>
 * 那两种会跳过 JSON 序列化与反序列化 —— 而**恰好就是那一段最容易错**。
 * 一个真监听端口的服务能让 SDK 走完整的 HTTP + Jackson 路径，
 * 于是"请求里到底发了什么"和"响应里到底能解析出什么"都变成了可断言的事实。
 *
 * <h2>为什么要记录请求体</h2>
 * 这是本类存在的另一半理由：**只有抓到出站的请求体，才能证明"工具定义真的发给了模型"**。
 * 断言"我构造了 ToolDefinition"是不够的 —— 它可能压根没进请求
 * （本项目里"算了但没接线"这类 bug 出现过不止一次）。
 */
public class FakeOpenAiServer implements AutoCloseable {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpServer server;

	/** 每一轮收到的原始请求体（保序）。 */
	private final List<String> requestBodies = new CopyOnWriteArrayList<>();

	/**
	 * 当前脚本（按轮次取，用完了用最后一个兜底）。
	 *
	 * <p><b>为什么是可变字段而不是构造器参数</b>：Spring 的测试上下文是<b>缓存的</b>，
	 * 而 {@code @DynamicPropertySource} 里的 base-url 只解析<b>一次</b>。
	 * 所以端点必须活得比单个测试方法长 —— 每个测试只换脚本，不换端点。
	 * 第一版把端点做成"每个测试起一个新的"（端口随之变化），
	 * 结果第二个测试会打到第一个测试那个已经关掉的端口上：
	 * 症状是"连接被拒绝"，看着像网络问题，其实是替身的生命周期设计错了。
	 */
	private volatile List<String> scriptedResponses = List.of();

	/** 起一个常驻的假端点（端口由系统分配，供整个测试类共用）。 */
	public FakeOpenAiServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	/**
	 * 换一套脚本，并清空已记录的请求。
	 *
	 * <p>清空是必须的：不清的话，断言里的 {@code callCount()} 与 {@code requestJson(0)}
	 * 会指向上一个测试的请求 —— 于是测试之间互相污染，而表现为"断言偶尔失败"。
	 */
	public void script(List<String> responses) {
		this.scriptedResponses = List.copyOf(responses);
		this.requestBodies.clear();
	}

	/** 假端点根地址，直接喂给 {@code spring.ai.openai.base-url}。 */
	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/**
	 * 只清空已记录的请求，不动脚本。
	 *
	 * <h2>为什么需要它（而不是用 {@link #script} 顺手清）</h2>
	 * {@code script(...)} 会同时换脚本与清请求，于是"连跑两次、各取第 0 个请求"这种
	 * 形态就取不到东西：第二次 {@code requestJson(0)} 拿到的仍然是**合并后**列表里的
	 * 第一个（也就是第一次运行的那个）。实测踩到过 —— 表现是"两次运行的出站请求
	 * 一模一样"，看上去像"灰度没生效"，实际是测试读错了下标。
	 */
	public void clearRequests() {
		requestBodies.clear();
	}

	/** 全部收到的请求体（按轮次）。 */
	public List<String> requestBodies() {
		return List.copyOf(requestBodies);
	}

	/** 第 N 轮请求体的 JSON 树。 */
	public JsonNode requestJson(int index) {
		try {
			return MAPPER.readTree(requestBodies.get(index));
		}
		catch (Exception e) {
			throw new IllegalStateException("第 " + index + " 轮请求体不是合法 JSON", e);
		}
	}

	/** 实际被调用了几轮。 */
	public int callCount() {
		return requestBodies.size();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = readAll(exchange.getRequestBody());
		requestBodies.add(body);

		List<String> responses = scriptedResponses;
		if (responses.isEmpty()) {
			throw new IllegalStateException("假端点没有脚本：测试忘了调 script(...)");
		}

		int index = requestBodies.size() - 1;
		String response = index < responses.size() ? responses.get(index) : responses.get(responses.size() - 1);

		byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static String readAll(InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	@Override
	public void close() {
		server.stop(0);
	}

	// ------------------------------------------------------------------
	//  响应构造：按 OpenAI 的线上格式手写，**不借助 SDK 的模型类**
	// ------------------------------------------------------------------

	/**
	 * 造一个"模型要求调用工具"的响应。
	 *
	 * <p>刻意用 {@link ObjectNode} 手拼而不是用 SDK 的 Builder：
	 * 这个替身要验证的正是<b>SDK 能不能解析这个格式</b>。
	 * 如果拿 SDK 自己构造响应，就变成了"SDK 能读懂 SDK 写的东西"—— 那是同义反复，
	 * 证明不了"它读得懂真实上游发的格式"。
	 *
	 * @param toolCalls 每个元素是 {id, name, arguments}
	 */
	public static String toolCallResponse(List<String[]> toolCalls) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("id", "chatcmpl-fake-toolcall");
		root.put("object", "chat.completion");
		root.put("created", 1735689600L);
		root.put("model", "fake-model");

		ArrayNode choices = root.putArray("choices");
		ObjectNode choice = choices.addObject();
		choice.put("index", 0);
		ObjectNode message = choice.putObject("message");
		message.put("role", "assistant");
		// content 刻意给 null：OpenAI 在"只调工具不讲话"时就是这个形态，
		// 而它是很多实现的空指针来源（本项目 ChatServiceImpl.deltaOf 也专门处理过 null）
		message.putNull("content");
		ArrayNode toolCallsNode = message.putArray("tool_calls");
		for (String[] call : toolCalls) {
			ObjectNode node = toolCallsNode.addObject();
			node.put("id", call[0]);
			node.put("type", "function");
			ObjectNode function = node.putObject("function");
			function.put("name", call[1]);
			function.put("arguments", call[2]);
		}
		choice.put("finish_reason", "tool_calls");

		putUsage(root, 220, 35);
		return root.toString();
	}

	/** 造一个"模型给出文字回答"的响应。 */
	public static String textResponse(String text) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("id", "chatcmpl-fake-text");
		root.put("object", "chat.completion");
		root.put("created", 1735689600L);
		root.put("model", "fake-model");

		ArrayNode choices = root.putArray("choices");
		ObjectNode choice = choices.addObject();
		choice.put("index", 0);
		ObjectNode message = choice.putObject("message");
		message.put("role", "assistant");
		message.put("content", text);
		choice.put("finish_reason", "stop");

		putUsage(root, 300, 60);
		return root.toString();
	}

	/**
	 * 造一个"因为 max_tokens 被截断"的响应（{@code finish_reason=length}）。
	 *
	 * <p>这个场景特别值得有：被截断的工具参数是**半截 JSON**，解析必然失败，
	 * 那时报告会写成"模型输出了非法 JSON"—— 而真正的原因是我们给的输出额度太小。
	 * 两种归因的修法完全相反，所以"能不能把 length 这个信号带出来"必须被测到。
	 */
	public static String truncatedToolCallResponse(String id, String name, String partialArguments) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("id", "chatcmpl-fake-truncated");
		root.put("object", "chat.completion");
		root.put("created", 1735689600L);
		root.put("model", "fake-model");

		ArrayNode choices = root.putArray("choices");
		ObjectNode choice = choices.addObject();
		choice.put("index", 0);
		ObjectNode message = choice.putObject("message");
		message.put("role", "assistant");
		message.putNull("content");
		ArrayNode toolCallsNode = message.putArray("tool_calls");
		ObjectNode node = toolCallsNode.addObject();
		node.put("id", id);
		node.put("type", "function");
		ObjectNode function = node.putObject("function");
		function.put("name", name);
		function.put("arguments", partialArguments);
		choice.put("finish_reason", "length");

		putUsage(root, 220, 400);
		return root.toString();
	}

	private static void putUsage(ObjectNode root, int prompt, int completion) {
		ObjectNode usage = root.putObject("usage");
		usage.put("prompt_tokens", prompt);
		usage.put("completion_tokens", completion);
		usage.put("total_tokens", prompt + completion);
	}

	/** 便捷构造：一组 {id, name, arguments}。 */
	public static List<String[]> calls(String... triples) {
		if (triples.length % 3 != 0) {
			throw new IllegalArgumentException("必须是 3 的倍数：id, name, arguments");
		}
		List<String[]> result = new ArrayList<>();
		for (int i = 0; i < triples.length; i += 3) {
			result.add(new String[] { triples[i], triples[i + 1], triples[i + 2] });
		}
		return result;
	}

}
