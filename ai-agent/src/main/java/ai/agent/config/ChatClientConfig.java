package ai.agent.config;

import ai.agent.service.agent.memory.SummarizingChatMemory;
import ai.agent.service.chat.compact.ChatCompactionService;
import ai.agent.service.prompt.PromptContextResource;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
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
 *
 * <p><b>本类同时决定"对话记忆存在哪里"</b>：{@code chatMemoryRepository()} 现在返回内存实现，
 * 换成官方的 {@code JdbcChatMemoryRepository}（MySQL / H2 都支持）即可改成存库 ——
 * 完整可编译的写法与三个前置条件写在那个方法上。
 */
@Configuration
public class ChatClientConfig {

	/** Advisor 执行顺序：小的先执行。 */
	private static final int ORDER_LOGGING = 0;

	/**
	 * 对话记忆的存储层 —— <b>对话消息现在存在系统内存里，不落库</b>。
	 *
	 * <h2>1. 它现在存在哪里（一句话就够了）</h2>
	 * 它内部只有一个 JVM 堆内的 Map（字段名 {@code chatMemoryStore}，
	 * 类型是「会话 id → 消息列表」，用 {@code javap} 核过），key 是 {@code conversationId}。所以：
	 * <ul>
	 *   <li><b>进程一退（重启 / 重新部署）记忆就没了</b>。{@code conversationId} 传得再对也没用，
	 *       用户那边看到的是"它不认识我了" —— 而这<b>不会报任何错</b>，因为对后端来说那本来就是一段新会话。</li>
	 *   <li><b>多实例部署时记忆不共享</b>：同一个 {@code conversationId} 被负载均衡打到另一个实例，
	 *       那边是空的。而且这里的问题是<b>偶发</b>的（取决于打没打到同一台），最难查的就是这种。</li>
	 *   <li><b>没有淘汰与过期</b>：会话不会被清理，长时间运行是内存只增不减。这一点被上层的
	 *       {@code MessageWindowChatMemory}（默认每会话 20 条）与 Token 预算裁剪挡住了大半，
	 *       但那两处管的是"每个会话多少条"，不是"一共留了多少个会话"。</li>
	 * </ul>
	 *
	 * <p><b>另外两个方向不在"存储"这一层</b>，别找错地方：
	 * <b>模型平台不存</b>（{@code chat/completions} 是无状态的，每次把历史放进 {@code messages[]}
	 * 发过去）；<b>数据库里也没有对话正文</b>（{@code agent_run} / {@code agent_step} 有
	 * {@code conversation_id} 列，但那只用来把几次运行串成一条会话，一个消息字都没有）。
	 *
	 * <h2>2. 想存库怎么换：把这一行的实现换掉，其余代码一个字都不用改</h2>
	 * 官方对 MySQL / H2 / MariaDB / PostgreSQL / SQLite / Oracle / SQL Server 都提供了
	 * {@code JdbcChatMemoryRepository}（包名
	 * {@code org.springframework.ai.chat.memory.repository.jdbc}，<b>2.0.1 实测存在</b>）。
	 * 把下面这段的注释解开、并删掉现在那两行（{@code return new InMemoryChatMemoryRepository();}）即可 ——
	 * 这是这一段注释里<b>唯一不能凭印象写</b>的地方，所以给的是逐一核过的写法（2.0.1 的 jar 里 {@code javap} 过）：
	 * <pre>{@code
	 * // ① pom 加依赖（版本由已导入的 spring-ai-bom 管，不要自己写版本号）：
	 * //    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
	 * // ② 本方法签名要加上 dataSource 参数（builder 没有单参构造器，不给 DataSource 建不出来）
	 * // ③ 建表交给自动配置（见下面第 3 节），不必自己写 DDL
	 * return JdbcChatMemoryRepository.builder()
	 *         .dataSource(dataSource)                                    // 就是 ai.agent.store.url 那个库
	 *         .dialect(JdbcChatMemoryRepositoryDialect.from(dataSource)) // 按数据库产品名自动识别，MySQL / H2 都覆盖了
	 *         .build();
	 * }</pre>
	 * 换的<b>只是实现</b>：Advisor 装配、窗口（{@code MessageWindowChatMemory}）、
	 * Token 预算裁剪（{@code TokenBudgetGuard}）、重试前的孤儿消息清理
	 * （{@code ChatController#dropOrphanedUserMessage}）用的<b>全是这个接口</b>，它们一个字都不用改。
	 * 这也正是把仓储做成 bean、而不是在 {@code chatClient(...)} 里内联 new 一个的原因。
	 *
	 * <h2>3. 换之前必须知道的四件事（都是"不报错就不会被发现"的那种）</h2>
	 * <ol>
	 *   <li><b>"改了代码但库还是内存库"</b>：{@code ai.agent.store.url} 默认是
	 *       <code>jdbc:h2:mem:agent;DB_CLOSE_DELAY=-1</code> —— <b>它自己是内存库</b>，
	 *       于是会出现"看起来落库、重启照样清空"。要真正持久，照 application.properties 里那段注释
	 *       改成文件库或 MySQL（<code>jdbc:h2:file:./data/agent;MODE=MySQL;...</code> /
	 *       <code>jdbc:mysql://...</code>）。</li>
	 *   <li><b>自动配置会抢着提供这个 bean</b>：加了那个 starter 之后，
	 *       {@code JdbcChatMemoryRepositoryAutoConfiguration} 自己就会注册一个
	 *       {@code ChatMemoryRepository}。所以要么像上面那样<b>显式返回它</b>（本方法优先），
	 *       要么<b>把这个方法整个删掉</b>交给自动配置 —— <b>但不能一边留着内存实现、一边加 starter</b>：
	 *       两个同类型 bean 会让 {@code chatMemory(...)} 的注入出现歧义。</li>
	 *   <li><b>建表与 {@code conversation_id} 的长度</b>：自动配置会按
	 *       {@code spring.ai.chat.memory.repository.jdbc.initialize-schema}（默认 {@code embedded}，
	 *       即只对嵌入式库生效）建表 —— <b>默认值下 H2 会自动建，而 MySQL 不会</b>
	 *       （Boot 4.1.1 的 {@code EmbeddedDatabaseConnection} 只认 H2 / HSQLDB / Derby），
	 *       用 MySQL 时要显式设成 {@code always}。
	 *       而官方 DDL 里会话 id 列是 <b>{@code VARCHAR(36)}</b>（UUID 的长度），
	 *       本项目的会话 id 却最长允许 64 字符（{@code AgentController} 的
	 *       {@code MAX_CONVERSATION_CHARS}）—— 超长会在真 MySQL 上插入失败，
	 *       所以要么把列改宽，要么让调用方只用 UUID / 短 id。
	 *       <b>这是换存储时最容易漏的问题，而且它在 H2 上根本不报</b>
	 *       （H2 不校验 VARCHAR 长度，本机测一切正常，换到 MySQL 才炸）。</li>
	 *   <li><b>方言识别失败时会静默按 Postgres 走</b>：{@code dialect(...)} 是按数据库产品名
	 *       做 switch 的（PostgreSQL / MySQL / MariaDB / SQL Server / HSQLDB / SQLite / H2 / Oracle）；
	 *       读不到产品名时它的兜底是 <b>Postgres 方言</b>，那时生成的 SQL 在 MySQL 上不对，
	 *       而症状是运行期才报的 SQL 语法错误。</li>
	 * </ol>
	 * 官方另有 Redis / MongoDB / Cassandra / Neo4j 的同族实现，换哪一个都是这一行的事。
	 *
	 * <h2>4. 为什么把仓储也暴露成 bean（当初做这个决定的原因）</h2>
	 * {@code ChatMemory.add(...)} 是<b>追加合并</b>语义（读旧 + 合并新 + 保存），
	 * 要真正"删掉一条"必须走仓储的 {@link ChatMemoryRepository#saveAll} —— 它是<b>整段替换</b>语义。
	 * 重试前清理重复的用户消息只能用后者。
	 * <b>而"能换实现"是这个决定的副产品</b>，不是当初的理由：上面那几个调用点全都注入的是接口，
	 * 所以换存储不用动它们。
	 */
	@Bean
	ChatMemoryRepository chatMemoryRepository() {
		// ⚠️ 内存实现：进程退出即清空，多实例之间不共享。
		// 要落库就把上面 return 这行换成：
		//   return JdbcChatMemoryRepository.builder()
		//           .dataSource(dataSource)
		//           .dialect(JdbcChatMemoryRepositoryDialect.from(dataSource))
		//           .build();
		// 前置条件（加依赖、改方法签名、两个 bean 的歧义、conversation_id 只有 36 字符）
		// 见方法注释第 2、3 节
		return new InMemoryChatMemoryRepository();
	}

	/**
	 * 对话记忆：<b>存储层是全量，交给模型的那一份是压过的</b>。
	 *
	 * <h2>两层各管什么</h2>
	 * <ul>
	 *   <li>{@code MessageWindowChatMemory} 管<b>存储</b>：它是那份全量历史，
	 *       也是"用户当时到底说了什么"的唯一可查处；</li>
	 *   <li>{@code SummarizingChatMemory} 管<b>交给模型的那一份</b>：
	 *       advisor 在 {@code before()} 里读的是它，它把已被摘要覆盖的那几轮换成一条摘要。
	 *       压缩只影响这一份，<b>不回写存储</b>。</li>
	 * </ul>
	 *
	 * <h2>窗口为什么从 20 放大到 200</h2>
	 * 20 条的窗口是一个<b>静默的删除点</b>：它按条数淘汰最旧的消息，而淘汰是永久的
	 * （{@code InMemoryChatMemoryRepository.saveAll} 是整段替换），
	 * 于是"聊到第 11 轮之后它就忘了前 3 轮"既没有日志也没有痕迹。
	 *
	 * <p>原来把窗口压到 20 有一条实测依据：第 11 轮时历史 20 条、需要 2058 tokens，
	 * 而预算是 2150 —— <b>条数窗口先到顶，Token 预算那条路一次都没跑到</b>。
	 * 那条依据现在不成立了：预算那条路已经改成"压缩 + 不删"，
	 * 它自己就会控制进 prompt 的量，不再需要靠条数窗口替它挡。
	 * 窗口放大之后，两件事同时成立：进 prompt 的量由预算压，
	 * 而存储里留着全量原文。
	 *
	 * <p>⚠️ 内存代价随窗口线性增长：{@code maxMessages} 条 × 会话数。
	 * 它默认 200（而不是无上限）就是为了给"会话数 × 条数"一个看得见的上界。
	 */
	@Bean
	ChatMemory chatMemory(ChatMemoryRepository repository, ChatCompactionService compaction,
			@Value("${ai.chat.memory.max-messages:200}") int maxMessages) {
		ChatMemory archive = MessageWindowChatMemory.builder()
				.chatMemoryRepository(repository)
				.maxMessages(maxMessages)
				.build();
		return new SummarizingChatMemory(archive, compaction);
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
