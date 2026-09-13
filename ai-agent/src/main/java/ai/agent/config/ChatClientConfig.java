package ai.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * ChatClient 装配。
 *
 * <p>要点（均来自官方 ChatClient 文档）：
 * <ul>
 *   <li>不要用 {@code ChatClient.create(chatModel)} / {@code ChatClient.builder(chatModel)} 自建，
 *       那样会绕过自动配置，导致 <b>observability 与 ChatClientBuilderCustomizer 失效</b>。
 *       应注入自动配置的 {@code ChatClient.Builder}（prototype 作用域）。</li>
 *   <li>Advisor 的<b>加入顺序决定执行顺序</b>，每个 advisor 改完的 prompt / 上下文会传给下一个。</li>
 *   <li>系统提示词外置到 classpath 资源，可被 {@code system(Resource)} 直接读取。</li>
 * </ul>
 */
@Configuration
public class ChatClientConfig {

	/** Advisor 执行顺序：小的先执行。 */
	private static final int ORDER_LOGGING = 0;

	@Bean
	ChatClient chatClient(ChatClient.Builder builder) {

		// 对话记忆：默认窗口 20 条，超出后淘汰旧消息，但 system 消息会被保留
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.maxMessages(20)
				.build();

		return builder
				// 系统提示词：从 classpath 读取，与代码解耦（阶段一的 prompt 版本化基础）
				.defaultSystem(new ClassPathResource("prompts/system-agent.st"))
				// 注意：官方建议 SimpleLoggerAdvisor 放在链的靠后位置
				// 光配 logging.level 不够，必须显式加入 advisor 链才会输出
				.defaultAdvisors(
						MessageChatMemoryAdvisor.builder(chatMemory).build(),
						new SimpleLoggerAdvisor(ORDER_LOGGING))
				.build();
	}

}
