package ai.agent;

import ai.agent.config.ChatCallProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 为什么要显式 {@code @EnableConfigurationProperties} 而不是给配置类加 {@code @Component}：
 * 前者是 Spring Boot 绑定的标准姿势，能拿到宽松绑定（kebab-case ↔ camelCase）、
 * 类型转换（{@code 20s} → {@code Duration}）与 {@code spring-boot-configuration-processor}
 * 生成的元数据（IDE 里写配置有提示、写错有告警）；后者只是把它当普通 bean，
 * 顺带在业务包里多一个语义不清的组件。
 */
@SpringBootApplication
@EnableConfigurationProperties(ChatCallProperties.class)
public class AiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiAgentApplication.class, args);
	}

}
