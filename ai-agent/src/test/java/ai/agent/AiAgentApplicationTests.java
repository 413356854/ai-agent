package ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载测试。
 *
 * <p>显式给一个假 api-key：Spring AI 的 OpenAI 自动配置在启动时就需要它，
 * 否则 {@code mvn test} 必须依赖本机环境变量 OPENAI_API_KEY 才能跑过（CI 上必踩）。
 * 这里只是占位，不会真的发起模型调用。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class AiAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
