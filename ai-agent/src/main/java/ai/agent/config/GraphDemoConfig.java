package ai.agent.config;

import ai.agent.graph.GraphDemoGraphFactory;
import ai.agent.service.graph.GraphDemoService;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Graph 演示的装配。
 *
 * <p>{@code ai.graph-demo.enabled=false} 时整组 bean 不装配，控制器也一起不装配，
 * 于是 {@code /api/graph-demo/**} 返回 404 —— 与项目里其它功能开关同一种“关掉即不存在”的形态。
 *
 * <p>检查点用 {@link MemorySaver}：它只活在进程内，不新增表，也不参与业务事务。
 */
@Configuration
@ConditionalOnProperty(name = "ai.graph-demo.enabled", havingValue = "true", matchIfMissing = true)
public class GraphDemoConfig {

	@Bean
	MemorySaver graphDemoSaver() {
		return MemorySaver.builder().build();
	}

	@Bean
	CompiledGraph graphDemoGraph(ChatModel chatModel,
			ObjectProvider<OpenAiChatProperties> chatProperties, MemorySaver graphDemoSaver)
			throws GraphStateException {
		return new GraphDemoGraphFactory(chatModel, chatProperties).build(graphDemoSaver);
	}

	@Bean
	GraphDemoService graphDemoService(CompiledGraph graphDemoGraph,
			ObjectProvider<OpenAiChatProperties> chatProperties) {
		return new GraphDemoService(graphDemoGraph, chatProperties);
	}

}
