package ai.agent.service.support.agent.store;

import ai.agent.entity.tool.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把对话消息（{@code List<Message>}）在<b>内存对象</b>与<b>数据库里的 JSON</b> 之间来回转。
 *
 * <h2>它为什么是断点恢复的核心</h2>
 * "恢复"这件事的全部难点在于：<b>进程没了，但对话上下文必须在</b>。
 * 上下文就是这一串消息（系统提示词 + 用户问题 + 每轮的 assistant 工具调用 + 每轮的工具返回）。
 * 把它存下来，恢复时就能接着往下跑；不存，就只能从头重放 ——
 * 而重放会把已经执行过的工具再执行一遍（对写操作是灾难，这正是那句
 * 「工具调用重试会不会产生重复副作用」）。
 *
 * <h2>为什么用 OpenAI 的消息形态（role / content / tool_calls / tool_call_id）</h2>
 * 三个理由：
 * <ol>
 *   <li>它**本来就长这样** —— 消息最终要序列化成这个协议发给模型，用它存等于省掉一次翻译；</li>
 *   <li>它是**可读的**：排查时直接看那一列就知道当时上下文里有什么，
 *       而用一个 Java 序列化的二进制 blob 则什么都看不出来（这是"能不能还原执行过程"的前提）；</li>
 *   <li>它**不绑定 Spring AI 的类**：将来升版本、换 SDK，库里的历史数据照样能读。</li>
 * </ol>
 *
 * <h2>一个刻意的取舍：不存 usage / metadata</h2>
 * 只存"重建上下文所必需的四类字段"。usage 之类的元数据属于<b>统计</b>，
 * 它已经单独落在 {@code agent_run.total_tokens} 与 {@code agent_step} 的 token 列上；
 * 在这里再存一份，就会出现"两份用量不一致时以谁为准"的问题 —— 而那是个没有正确答案的问题。
 */
@Component
public class TranscriptCodec {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 单条消息的正文上限。防止"模型吐了几百 KB"把整列撑爆。 */
	private static final int MAX_TEXT_CHARS = 20_000;

	/** 工具调用参数的上限（与 {@code ToolFeedbackFormatter} 的取舍一致：回填不需要完整参数）。 */
	private static final int MAX_ARGUMENT_CHARS = 2_000;

	/** 把消息列表编成 JSON 字符串（存库用）。 */
	public String encode(List<Message> messages) {
		ArrayNode array = MAPPER.createArrayNode();
		for (Message message : messages) {
			ObjectNode node = array.addObject();
			switch (message) {
				case SystemMessage system -> {
					node.put("role", "system");
					node.put("content", truncate(system.getText()));
				}
				case UserMessage user -> {
					node.put("role", "user");
					node.put("content", truncate(user.getText()));
				}
				case AssistantMessage assistant -> {
					node.put("role", "assistant");
					node.put("content", truncate(assistant.getText()));
					if (assistant.hasToolCalls()) {
						ArrayNode calls = node.putArray("tool_calls");
						for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
							ObjectNode callNode = calls.addObject();
							callNode.put("id", call.id());
							callNode.put("type", "function");
							ObjectNode function = callNode.putObject("function");
							function.put("name", call.name());
							function.put("arguments", truncate(call.arguments(), MAX_ARGUMENT_CHARS));
						}
					}
				}
				case ToolResponseMessage toolResponses -> {
					node.put("role", "tool");
					// 一条 tool 消息可以带多个返回（并行工具调用），所以这里存成数组
					ArrayNode responses = node.putArray("tool_responses");
					for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
						ObjectNode responseNode = responses.addObject();
						responseNode.put("id", response.id());
						responseNode.put("name", response.name());
						responseNode.put("response_data", truncate(response.responseData()));
					}
					// 同时写一个合并后的 content：让"只想知道工具说了什么"的人不用解析数组
					StringBuilder merged = new StringBuilder();
					for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
						merged.append(response.responseData()).append('\n');
					}
					node.put("content", truncate(merged.toString().strip()));
				}
				default -> {
					// 认不出来的消息类型：**抛，不静默丢**。
					// 丢掉一条消息意味着恢复出来的上下文与原来不同，
					// 而模型据此产生的行为差异没有任何地方会报错
					throw new IllegalArgumentException(
							"TranscriptCodec 不认识的消息类型：" + message.getClass().getName()
									+ "。新增消息类型时必须同时更新这里 —— 静默丢弃会让恢复出来的上下文与原来不一致，"
									+ "而模型基于不同上下文的行为差异不会报任何错。");
				}
			}
		}
		return array.toString();
	}

	/** 把 JSON 还原成消息列表（恢复时用）。 */
	public List<Message> decode(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			JsonNode root = MAPPER.readTree(json);
			if (!root.isArray()) {
				throw new IllegalArgumentException("对话记录必须是一个 JSON 数组，实际是 " + root.getNodeType());
			}
			List<Message> messages = new ArrayList<>();
			for (JsonNode node : root) {
				messages.add(toMessage(node));
			}
			return List.copyOf(messages);
		}
		catch (IllegalArgumentException e) {
			throw e;
		}
		catch (Exception e) {
			// 解析失败要说清是"这一列的内容坏了"，而不是让上层看到一个泛化的 JSON 异常：
			// 排查恢复失败时，"检查点那一列存坏了"和"恢复逻辑写错了"是两个完全不同的方向
			throw new IllegalArgumentException(
					"检查点里的对话记录不是合法 JSON（长度 " + json.length() + "）：" + e.getMessage(), e);
		}
	}

	private static Message toMessage(JsonNode node) {
		String role = node.path("role").asText("");
		String content = node.path("content").asText("");
		return switch (role) {
			case "system" -> new SystemMessage(content);
			case "user" -> new UserMessage(content);
			case "assistant" -> toAssistant(node, content);
			case "tool" -> toToolResponses(node, content);
			default -> throw new IllegalArgumentException(
					"对话记录里有不认识的角色「" + role + "」。支持的：system / user / assistant / tool。"
							+ "不认识的角色必须抛 —— 静默当成普通消息会让恢复出来的上下文悄悄变样。");
		};
	}

	private static Message toAssistant(JsonNode node, String content) {
		if (!node.has("tool_calls") || node.get("tool_calls").isEmpty()) {
			return new AssistantMessage(content);
		}
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (JsonNode call : node.get("tool_calls")) {
			calls.add(new AssistantMessage.ToolCall(call.path("id").asText(), "function",
					call.path("function").path("name").asText(), call.path("function").path("arguments").asText("{}")));
		}
		return AssistantMessage.builder().content(content).toolCalls(calls).build();
	}

	private static Message toToolResponses(JsonNode node, String fallbackContent) {
		if (!node.has("tool_responses") || node.get("tool_responses").isEmpty()) {
			// 只有 content 的旧格式（或手工写的记录）：用一个"无名"返回兜底。
			// 这会让协议上少一个 id，所以明确记在注释里：**这条路径只用于兼容，不该是新写入的形态**
			return ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("", "", fallbackContent)))
					.build();
		}
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		for (JsonNode response : node.get("tool_responses")) {
			responses.add(new ToolResponseMessage.ToolResponse(response.path("id").asText(),
					response.path("name").asText(), response.path("response_data").asText()));
		}
		return ToolResponseMessage.builder().responses(responses).build();
	}

	/** 供报告使用：把工具调用意图从记录里读出来（不改上下文，只做展示）。 */
	public List<ToolInvocation> toolCallsOf(String json) {
		List<ToolInvocation> invocations = new ArrayList<>();
		for (Message message : decode(json)) {
			if (message instanceof AssistantMessage assistant) {
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					invocations.add(ToolInvocation.from(call.id(), call.name(), call.arguments()));
				}
			}
		}
		return List.copyOf(invocations);
	}

	private static String truncate(String text) {
		return truncate(text, MAX_TEXT_CHARS);
	}

	private static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		return text.length() <= max ? text : text.substring(0, max) + "\n…（超出 " + max + " 字符，记录时已截断）";
	}

}
