package ai.agent.config;

import ai.agent.prompt.PromptContextResource;
import ai.agent.prompt.PromptKind;
import ai.agent.prompt.PromptService;
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
import org.springframework.context.annotation.Primary;

/**
 * ChatClient 装配。
 *
 * <p>要点（均来自官方 ChatClient 文档）：
 * <ul>
 *   <li>不要用 {@code ChatClient.create(chatModel)} / {@code ChatClient.builder(chatModel)} 自建，
 *       那样会绕过自动配置，导致 <b>observability 与 ChatClientBuilderCustomizer 失效</b>。
 *       应注入自动配置的 {@code ChatClient.Builder}（prototype 作用域）。</li>
 *   <li>Advisor 的<b>加入顺序决定执行顺序</b>，每个 advisor 改完的 prompt / 上下文会传给下一个。</li>
 *   <li>系统提示词外置到 classpath 资源，可被 {@code system(Resource)} 直接读取。
 *       <b>阶段四之后它改成了从版本库取</b>（见 {@code promptContextResource}），
 *       理由与"为什么这一句只是启动时快照"都写在那里。</li>
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

	/**
	 * 系统提示词的<b>来源</b>（阶段四第 3 件：Prompt 版本管理 + 配置热更新）。
	 *
	 * <h2>为什么把配置属性注入的 {@code Resource} 换成这个 bean</h2>
	 * 原先这里直接 {@code @Value("${ai.chat.system-prompt-location:...}") Resource}，
	 * 也就是"从 classpath 读一个文件"。换成版本库之后有两个变化：
	 * <ol>
	 *   <li>内容是<b>库里当前生效的那一版</b>，不再有"库里是 v3、而 ChatClient 用着出厂文件"
	 *       这种两处真相（那种不一致不报错，只表现为"改了提示词没反应"）；</li>
	 *   <li>取数改成走 {@code PromptService}，它每次调用都重新问库。</li>
	 * </ol>
	 * 属性 {@code ai.chat.system-prompt-location} 因此在阶段四之后<b>不再影响运行时</b>
	 * （它是"出厂默认值与回滚去处"的位置，见 {@code PromptKind}）。
	 * <b>刻意保留属性本身</b>：删掉它会让老配置报"未知属性"，
	 * 而"配置文件里有一条看起来还在生效、实际已经不生效的设置"正是最难查的一类问题 ——
	 * 所以留着它、并在 application.properties 里写清它现在的含义。
	 *
	 * <h2>⚠️ 这里注入的内容是"启动那一刻的快照"</h2>
	 * {@code ChatClientBuilder.defaultSystem(Resource)} 在<b>构建 Client 时</b>就调用了
	 * {@code Resource.getContentAsString()}（用 {@code javap} 核过
	 * {@code DefaultChatClientBuilder} 的字节码），所以换了版本之后<b>这个 bean 不会自动更新</b>。
	 * 真正的热更新在<b>调用点</b>：{@code ChatServiceImpl} / {@code StructuredChatServiceImpl}
	 * 每次请求都显式 {@code .system(...)}。这里仍然注入一次，是为了让"没有任何调用点覆盖时"
	 * 的那次请求也有正确的系统提示词（以及为了让 {@code ChatClient} 自身的默认值与库一致）。
	 */
	@Bean
	@Primary
	PromptContextResource promptContextResource(PromptService promptService) {
		return new PromptContextResource(promptService, PromptKind.CHAT_SYSTEM);
	}

	@Bean
	ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
			PromptContextResource systemPrompt) {
		return builder
				// 系统提示词：来自版本库（当前生效的那一版），与代码解耦。
				// ⚠️ 这一句是**启动时快照**，热更新靠调用点的 .system(...)，见上面那段注释
				.defaultSystem(systemPrompt)
				// 注意：官方建议 SimpleLoggerAdvisor 放在链的靠后位置
				// 光配 logging.level 不够，必须显式加入 advisor 链才会输出
				.defaultAdvisors(
						MessageChatMemoryAdvisor.builder(chatMemory).build(),
						new SimpleLoggerAdvisor(ORDER_LOGGING))
				.build();
	}

}
