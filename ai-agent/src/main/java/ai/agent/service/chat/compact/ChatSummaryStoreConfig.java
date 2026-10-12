package ai.agent.service.chat.compact;

import ai.agent.entity.chat.ConversationSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话压缩摘要的默认（堆内）存储装配 —— 与对话历史本身
 * （{@code InMemoryChatMemoryRepository}）同一层级：<b>进程一退就没了</b>。
 *
 * <h2>为什么是 {@code @ConditionalOnMissingBean} 的装配类，而不是一个 {@code @Component}</h2>
 * 两种实现在同一层级、只能有一个生效，所以"谁被选中"必须由<b>装配</b>回答，
 * 而不是靠两个 {@code @Component} 都在容器里、再用 {@code @Primary} 去压。
 * 换库实现只需配 {@code ai.chat.summary.store=jdbc}
 * （见 {@link JdbcChatSummaryStore}），本类的 bean 会自动让位。
 *
 * <p>⚠️ 两个装配约束：类名不能与下面那个 {@code @Bean} 方法名相同（两者推导出的 bean 名字都是
 * {@code inMemoryChatSummaryStore}，而 Boot 默认禁止覆盖 bean 定义 → 启动抛
 * {@code BeanDefinitionOverrideException}）；也不能加私有构造器
 * （{@code @Configuration} 默认要 CGLIB 生成子类 → {@code No visible constructors}）。
 *
 * <h2>为什么它不做淘汰</h2>
 * 一条会话一行、一份摘要几百字，它的规模由"有多少个会话"决定 ——
 * 与 {@code ChatMemory} 那张表同一个量级，不会因为对话变长而变大。
 * 给它加 LRU 只会制造一个"聊到一半摘要不见了"的新失效形态，
 * 而那个失效是静默的（prompt 里少一段背景，模型只是答得差一点）。
 */
@Configuration
public class ChatSummaryStoreConfig {

	@Bean
	@ConditionalOnMissingBean(ChatSummaryStore.class)
	ChatSummaryStore inMemoryChatSummaryStore() {
		return new Summaries();
	}

	/**
	 * {@link ChatSummaryStore} 的堆内实现。
	 *
	 * <p>为什么是 {@code ConcurrentHashMap} 而不加锁：与框架那份实现同一个理由
	 * （{@code InMemoryChatMemoryRepository.chatMemoryStore} 也是它）——
	 * 读写来自不同线程（流式对话拼 prompt 的线程与压缩发生的线程不同），
	 * 而这里每个操作都是单次 map 调用，不需要复合原子性。
	 */
	private static final class Summaries implements ChatSummaryStore {

		private final Map<String, ConversationSummary> byConversation = new ConcurrentHashMap<>();

		@Override
		public Optional<ConversationSummary> find(String conversationId) {
			return conversationId == null || conversationId.isBlank()
					? Optional.empty()
					: Optional.ofNullable(byConversation.get(conversationId));
		}

		@Override
		public void save(ConversationSummary summary) {
			if (summary == null) {
				throw new IllegalArgumentException("要写入的会话摘要不能为 null");
			}
			byConversation.put(summary.conversationId(), summary);
		}

		@Override
		public void delete(String conversationId) {
			if (conversationId != null && !conversationId.isBlank()) {
				byConversation.remove(conversationId);
			}
		}
	}
}
