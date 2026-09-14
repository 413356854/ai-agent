package ai.agent.config;

import ai.agent.token.TokenBudgetGuard;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

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

	/**
	 * 对话记忆的存储层。
	 *
	 * <p><b>为什么把仓储也暴露成 bean</b>：{@code ChatMemory.add(...)} 是<b>追加合并</b>语义
	 * （读旧 + 合并新 + 保存），要真正"删掉一条"必须走仓储的
	 * {@link ChatMemoryRepository#saveAll} —— 它是<b>整段替换</b>语义。重试前清理重复的
	 * 用户消息只能用后者。
	 */
	@Bean
	ChatMemoryRepository chatMemoryRepository() {
		return new InMemoryChatMemoryRepository();
	}

	/**
	 * 对话记忆。
	 *
	 * <p>窗口默认 20 条，超出后淘汰旧消息，但 system 消息会被保留。
	 *
	 * <p><b>为什么把窗口大小做成可配的</b>（实测发现的必要性）：
	 * 这个窗口是<b>按条数</b>限制的，而 Token 预算是<b>按长度</b>限制的 ——
	 * 两者量纲不同，所以谁先触发完全取决于消息有多长。
	 * 默认 20 条恰好让"消息条数"先到顶：实测第 11 轮时历史 20 条、需要 2058 tokens，
	 * 而预算是 2150，<b>被条数窗口挡住了，预算校验的裁剪逻辑一次都没执行过</b>。
	 *
	 * <p>这说明一件容易误判的事：<b>看到一个"保护"在生效，不代表你新加的那个保护在生效</b>。
	 * 想把 Token 预算这条链路真正验证到，必须把条数窗口放大到不再抢先 ——
	 * 于是它成了配置项（{@code ai.chat.memory.max-messages}），
	 * 压测/验证时调大即可，生产环境仍按需设置。
	 */
	@Bean
	ChatMemory chatMemory(ChatMemoryRepository repository,
			@Value("${ai.chat.memory.max-messages:20}") int maxMessages) {
		return MessageWindowChatMemory.builder()
				.chatMemoryRepository(repository)
				.maxMessages(maxMessages)
				.build();
	}

	@Bean
	ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
			@Value("${ai.chat.system-prompt-location:" + TokenBudgetGuard.DEFAULT_SYSTEM_PROMPT_LOCATION + "}") Resource systemPrompt) {
		return builder
				// 系统提示词：从 classpath 读取，与代码解耦（阶段一的 prompt 版本化基础）。
				// 位置来自配置属性，与 TokenBudgetGuard 算预算时读的是**同一个属性** ——
				// 否则会出现"校验算的是旧提示词、实际发的是新的"，预算永远算不准
				.defaultSystem(systemPrompt)
				// 注意：官方建议 SimpleLoggerAdvisor 放在链的靠后位置
				// 光配 logging.level 不够，必须显式加入 advisor 链才会输出
				.defaultAdvisors(
						MessageChatMemoryAdvisor.builder(chatMemory).build(),
						new SimpleLoggerAdvisor(ORDER_LOGGING))
				.build();
	}

}
