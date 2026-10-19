package ai.agent.service.impl.chat;

import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.chat.ChatService;
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

	/**
	 * 系统提示词的来源（阶段四第 3 件：Prompt 版本管理 + 配置热更新）。
	 *
	 * <p><b>为什么必须在这里再注入一次，而不是只靠 {@code ChatClient} 的默认值</b>：
	 * {@code ChatClientBuilder.defaultSystem(Resource)} 在<b>构建 Client 的那一刻</b>
	 * 就把 Resource 读成字符串了（用 {@code javap} 核过字节码），
	 * 所以换了提示词版本之后，那个默认值<b>不会变</b>。
	 * 真正的热更新只能发生在调用点 —— 也就是下面这两处 {@code .system(...)}。
	 */
	private final PromptService prompts;

	public ChatServiceImpl(ChatClient chatClient, PromptService prompts) {
		this.chatClient = chatClient;
		this.prompts = prompts;
	}

	@Override
	public Flux<ChatClientResponse> stream(String conversationId, String userText, double temperature) {
		return stream(conversationId, userText, temperature, null);
	}

	@Override
	public Flux<ChatClientResponse> stream(String conversationId, String userText, double temperature,
			String knowledgeBaseBlock) {
		return chatClient.prompt()
				// 【每轮都重新取一次当前生效的提示词】这是"配置热更新"真正的落点：
				// 换了一版之后，下一轮对话就用新的（不需要重启、不需要重新打包）
				//
				// 知识库材料接在这一句后面：它是**这一次请求的数据**，不进版本库
				// （同 AgentLoop 里技能目录/知识库材料那两段的位置与理由）
				.system(withKnowledgeBase(prompts.currentContent(PromptKind.CHAT_SYSTEM), knowledgeBaseBlock))
				.user(userText)
				// 注意：options(...) 接收的是 ChatOptions.Builder，不是构建好的 ChatOptions
				.options(OpenAiChatOptions.builder().temperature(temperature))
				// 【读取历史会话】这一行的 param 就是"把本次请求接到该会话的历史上"的开关：
				// ChatClient 上挂了 MessageChatMemoryAdvisor（装配见 ChatClientConfig#chatClient），
				// 它在 before() 阶段用这个 conversationId 调 ChatMemory.get(id) 取出历史消息、
				// 合并进本次 Prompt（2.0.1 字节码核过：before() → getConversationId(ctx) →
				// ChatMemory.get(id) → Prompt.Builder.messages(...)；流式也走 before()，
				// adviseStream() 的第一步 map 调的就是它）。
				// 所以"第 2 轮还记得第 1 轮说过什么"发生在这里 —— 端点只把 conversationId 传下来，
				// 自己并不拼历史；反过来说，这一行去掉就等于每轮都是新会话（且不报错，只是"失忆"）。
				// 同一个 param 也是写入的键：before() 落本次用户消息、after() 落回答（流式只在正常完成时落），
				// 重试会留下孤儿用户消息，所以每轮重试前必须先摘掉它（ChatController#dropOrphanedUserMessage）。
				// 2.0 起这个 param 每次调用必传、没有默认值，不传直接抛 IllegalArgumentException。
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.stream()
				.chatClientResponse();
	}

	@Override
	public String call(String conversationId, String message) {
		return call(conversationId, message, null);
	}

	@Override
	public String call(String conversationId, String message, String knowledgeBaseBlock) {
		return chatClient.prompt()
				.system(withKnowledgeBase(prompts.currentContent(PromptKind.CHAT_SYSTEM), knowledgeBaseBlock))
				.user(message)
				// 【读取历史会话】与上面 stream() 同一件事：非流式通路用的是同一个记忆 advisor，
				// 同样按 conversationId 取历史并进本次 Prompt（判据与坑见 stream() 里的那段注释）
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

	/**
	 * 把知识库材料接到了系统提示词后面。
	 *
	 * <p>空块与没有块是同一件事（都不接）—— 但拼进来的那一块里**一定**有内容：
	 * "查了没命中"与"查失败了"各有自己的一段文字（见 {@code KnowledgeBaseContextInjector}），
	 * 所以不会出现"勾了知识库却什么都没注入"这种界面上看不出来的状态。
	 */
	private static String withKnowledgeBase(String systemPrompt, String knowledgeBaseBlock) {
		return knowledgeBaseBlock == null || knowledgeBaseBlock.isBlank() ? systemPrompt
				: systemPrompt + "\n\n" + knowledgeBaseBlock;
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
