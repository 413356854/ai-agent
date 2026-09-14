package ai.agent.service.impl;

import ai.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ChatService} 的默认实现：按官方文档实现流式输出，并顺带产出 token / 成本统计。
 *
 * <p>为什么用 {@code stream().chatClientResponse()} 而不是 {@code stream().content()}：
 * 官方文档明确两者都可用，但前者能在<b>同一条流</b>上同时拿到增量和完整的
 * {@code ChatResponse} 元数据（含 token 用量），因此不需要额外发第二次请求做统计。
 * 注意第一个 chunk 的 token 计数通常为 0，需取<b>最后一个非空值</b>。
 */
@Service
public class ChatServiceImpl implements ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

	private final ChatClient chatClient;

	public ChatServiceImpl(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	@Override
	public Flux<ChatClientResponse> stream(String conversationId, String userText, double temperature) {
		return chatClient.prompt()
				.user(userText)
				// 注意：options(...) 接收的是 ChatOptions.Builder，不是构建好的 ChatOptions
				.options(OpenAiChatOptions.builder().temperature(temperature))
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.stream()
				.chatClientResponse();
	}

	@Override
	public String call(String conversationId, String message) {
		return chatClient.prompt()
				.user(message)
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.call()
				.content();
	}

	@Override
	public String deltaOf(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null) {
			return "";
		}
		var results = response.chatResponse().getResults();
		if (results == null || results.isEmpty()) {
			return "";
		}
		var output = results.get(0).getOutput();
		if (output == null) {
			return "";
		}
		String text = output.getText();
		return text == null ? "" : text;
	}

	@Override
	public Map<String, Object> usageOf(ChatClientResponse response) {
		Map<String, Object> usage = new LinkedHashMap<>();
		usage.put("promptTokens", null);
		usage.put("completionTokens", null);
		usage.put("totalTokens", null);
		usage.put("model", null);

		if (response == null || response.chatResponse() == null) {
			return usage;
		}
		ChatResponseMetadata meta = response.chatResponse().getMetadata();
		if (meta == null) {
			return usage;
		}
		usage.put("model", meta.getModel());

		Usage u = meta.getUsage();
		if (u != null) {
			usage.put("promptTokens", u.getPromptTokens());
			usage.put("completionTokens", u.getCompletionTokens());
			usage.put("totalTokens", u.getTotalTokens());
		}
		return usage;
	}

	@Override
	public boolean hasDelta(String delta) {
		return delta != null && !delta.isEmpty();
	}

	@Override
	public void logError(String conversationId, Throwable e, boolean expected) {
		// 可预期的失败（超时 / 上游 4xx-5xx）用 WARN：它们会经常发生，
		// 打成 ERROR 会让日志监控里堆满"看着像系统炸了"的噪声
		if (expected) {
			log.warn("流式对话失败（可预期）conversationId={} cause={}", conversationId, e.toString());
		} else {
			log.error("流式对话失败 conversationId={}", conversationId, e);
		}
	}

}
