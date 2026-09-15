package ai.agent.agent;

import ai.agent.tool.ToolInvocation;
import ai.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModelTurn} 的生产实现：接真实的 OpenAI 兼容模型。
 *
 * <h2>本类里最重要的一个决定：用 {@code ChatModel} 而不是 {@code ChatClient}</h2>
 * 这不是风格偏好，而是<b>正确性</b>问题。2.0.1 实测：{@code ChatClient} 在
 * 请求里带上工具之后会<b>自动执行工具</b>（{@code DefaultToolCallingManager.executeToolCalls}），
 * 执行完再把结果塞回去自动重问 —— 也就是说，"循环"会由框架跑起来。
 *
 * <p>那对本项目是致命的，原因很具体：框架执行工具走的是
 * {@code ToolCallback.call(String)}，而本项目的 {@code AgentTool} 把那个方法实现成
 * <b>故意抛异常</b>（所有执行必须经过 {@code ToolCallExecutor} 才有白名单、校验、
 * 分类与计时）。于是框架一旦接管，结果是<b>每一次工具调用都抛异常</b> ——
 * 而且症状会表现为"模型调什么工具都失败"，排查方向很容易跑到模型或工具实现上去。
 *
 * <p>{@code ChatModel.call(Prompt)} <b>不做这件事</b>：它把模型返回的
 * {@code tool_calls} 原样交出来，执不执行由调用方决定。所以本类只做"翻译"
 * （框架类型 ↔ 本项目的类型），执行权留在 {@link AgentLoop} 手里。
 *
 * <h2>翻译里两个必须显式处理的点</h2>
 * <ol>
 *   <li><b>{@code finish_reason = length}</b>：模型因为输出额度被截断时，
 *       工具参数可能是半截 JSON，解析必然失败。不把这个信号带出来，
 *       报告里会写成"模型输出了非法 JSON"—— 而真正的原因是我们给的 max_tokens 太小。
 *       这是"同一现象两种归因、修法完全相反"的典型。</li>
 *   <li><b>token 用量可能缺失</b>：不是所有兼容端点都返回 usage。
 *       缺的时候记 0 而不是抛异常 —— 但要在日志里说一次，
 *       因为"用量恒为 0"会让阶段四的成本看板一直显示零成本，而那是<b>看起来很正常的错数据</b>。</li>
 * </ol>
 */
@Component
public class OpenAiModelTurn implements ModelTurn {

	private static final Logger log = LoggerFactory.getLogger(OpenAiModelTurn.class);

	private final ChatModel chatModel;

	private final ToolRegistry registry;

	/** usage 缺失只提醒一次，避免每次调用都刷日志。 */
	private volatile boolean usageMissingReported;

	public OpenAiModelTurn(ChatModel chatModel, ToolRegistry registry) {
		this.chatModel = chatModel;
		this.registry = registry;
	}

	@Override
	public ModelReply chat(List<Message> messages) {
		// 工具定义随请求发出：模型据此知道能调什么、参数长什么样。
		// 注意 toolCallbacks 里放的是 AgentTool 本身（它实现 ToolCallback）——
		// 但**不会**被框架执行，因为走的是 ChatModel.call 而不是 ChatClient（见类注释）
		//
		// 【必须用 OpenAiChatOptions，不能用通用的 ToolCallingChatOptions】—— 这一条是实测踩出来的：
		// 第一版写的是 ToolCallingChatOptions.builder().toolCallbacks(...)，
		// 它造出来的是 DefaultToolCallingChatOptions，而 OpenAiChatModel 内部会把它
		// **强转成 OpenAiChatOptions**，于是运行期直接：
		//   ClassCastException: DefaultToolCallingChatOptions cannot be cast to OpenAiChatOptions
		//
		// 最值得记的是它的**症状**：这个异常被 AgentLoop 兜住、变成了 MODEL_ERROR 的 run 报告
		// （那是刻意的设计：模型失败不该炸掉会话，已花的成本要带回来）。
		// 于是表面上"只是模型调用失败"，而真正的原因是我传错了 options 类型。
		// 抓出它的是协议层测试（AgentModelProtocolTest）—— 在 ModelTurn 接口打桩的 39 个测试
		// 一个都碰不到这一行，因为它们根本不经过这个适配器。
		OpenAiChatOptions options = OpenAiChatOptions.builder()
				.toolCallbacks(new ArrayList<>(registry.all()))
				.build();

		Prompt prompt = new Prompt(messages, options);
		ChatResponse response = chatModel.call(prompt);

		return translate(response);
	}

	private ModelReply translate(ChatResponse response) {
		if (response == null || response.getResult() == null) {
			// 空响应：交给 AgentLoop 判成 EMPTY_RESPONSE，而不是在这里抛 ——
			// "上游给了空响应"是一种可观测的失败，应该出现在报告里
			log.warn("模型返回了空响应（response 或 result 为 null）");
			return new ModelReply("", List.of(), 0, 0, false);
		}

		Generation generation = response.getResult();
		AssistantMessage output = generation.getOutput();
		String text = output == null || output.getText() == null ? "" : output.getText();

		List<ToolInvocation> invocations = new ArrayList<>();
		if (output != null) {
			for (AssistantMessage.ToolCall call : output.getToolCalls()) {
				invocations.add(ToolInvocation.from(call.id(), call.name(), call.arguments()));
			}
		}

		boolean truncated = isLengthTruncated(generation, output);
		if (truncated) {
			log.warn("模型输出因 max_tokens 被截断（finish_reason=length）—— 工具参数可能是半截 JSON，"
					+ "这会被报成「参数解析失败」，但真正原因是输出额度太小");
		}

		int promptTokens = 0;
		int completionTokens = 0;
		var metadata = response.getMetadata();
		if (metadata != null) {
			Usage usage = metadata.getUsage();
			if (usage != null) {
				promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
				completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
			}
		}
		if (promptTokens == 0 && completionTokens == 0 && !usageMissingReported) {
			usageMissingReported = true;
			log.warn("本次模型响应没有返回 token 用量，Agent 的 token 上限与成本统计会一直记 0。"
					+ "如果这不是预期，请检查上游端点是否支持 usage 字段（成本看板会因它显示零成本而看起来正常）");
		}

		return new ModelReply(text, invocations, promptTokens, completionTokens, truncated);
	}

	/**
	 * 判断是不是"因为输出额度被截断"。
	 *
	 * <p>两个来源都看：Spring AI 的 {@code finishReason} 字段，
	 * 以及 OpenAI 协议里的 {@code finish_reason=length}。
	 * 只看一个的话，某些兼容端点的响应会漏判 —— 而漏判的后果是
	 * 一条"模型输出了非法 JSON"的错误结论。
	 */
	private static boolean isLengthTruncated(Generation generation, AssistantMessage output) {
		if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
			String reason = generation.getMetadata().getFinishReason();
			if ("length".equalsIgnoreCase(reason) || "max_tokens".equalsIgnoreCase(reason)) {
				return true;
			}
		}
		// 有些实现把 finish_reason 放在消息上；工具调用被截断时最典型的形态是
		// "有 toolCall 但 arguments 是半截/空的 JSON"
		if (output != null && output.hasToolCalls()) {
			for (AssistantMessage.ToolCall call : output.getToolCalls()) {
				if (call.arguments() != null && !call.arguments().isBlank() && !isLikelyCompleteJson(call.arguments())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 粗判一段字符串是不是"看起来完整"的 JSON 对象/数组。
	 *
	 * <p>刻意<b>不</b>用真正的 JSON 解析：这里的用途只是"猜测上游是不是截断了"，
	 * 而解析失败本身会在 {@code ToolCallExecutor} 里被精确处理。
	 * 在这里做一次严格解析反而会制造第二个错误来源 ——
	 * 而且是"同一个问题在两处各报一次、措辞不同"的那种。
	 */
	private static boolean isLikelyCompleteJson(String raw) {
		String trimmed = raw.strip();
		char last = trimmed.charAt(trimmed.length() - 1);
		return last == '}' || last == ']' || last == '"';
	}

}
