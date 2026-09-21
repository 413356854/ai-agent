package ai.agent;

import ai.agent.config.AgentApprovalProperties;
import ai.agent.config.ChatCallProperties;
import ai.agent.config.ReviewProperties;
import ai.agent.config.WorkspaceFileProperties;
import ai.agent.config.WorkspaceProperties;
import ai.agent.config.rag.RagProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 为什么要显式 {@code @EnableConfigurationProperties} 而不是给配置类加 {@code @Component}：
 * 前者是 Spring Boot 绑定的标准姿势，能拿到宽松绑定（kebab-case ↔ camelCase）、
 * 类型转换（{@code 20s} → {@code Duration}）与 {@code spring-boot-configuration-processor}
 * 生成的元数据（IDE 里写配置有提示、写错有告警）；后者只是把它当普通 bean，
 * 顺带在业务包里多一个语义不清的组件。
 *
 * <p>阶段二新增 {@link RagProperties}（前缀 {@code ai.rag}）：
 * 分块与评测参数需要反复试，必须能改配置就生效，不能每次改完重新编译。
 *
 * <p>阶段三第 9 件（HITL + MCP）新增两个：
 * <ul>
 *   <li>{@link AgentApprovalProperties}（{@code ai.agent.approval}）——
 *       等人确认的时限与总开关。⚠️ 它的 {@code timeout} 在<b>构造期</b>就拒绝 0/负数
 *       （那表示"永远等下去"），所以一个写错的配置会在启动时就暴露，
 *       而不是在第一次有人需要审批时表现为"页面一直转圈"；</li>
 *   <li>{@link ReviewProperties}（{@code ai.review}）——
 *       写工具（建工单）的落盘位置与幂等窗口。</li>
 * </ul>
 *
 * <p><b>MCP 的配置刻意不在这里</b>：它由 Spring AI 自己的
 * {@code McpServerProperties}（前缀 {@code spring.ai.mcp.server}）管，
 * 我们只在 {@code application.properties} 里写值。包一层自己的 properties
 * 会多出一处"这个字段到底谁说了算"的歧义。
 *
 * <p>文件类工具的边界单独一个 {@link WorkspaceFileProperties}（前缀
 * {@code ai.agent.workspace.file}）：它管的是"读进来多少、写出去多大"，
 * 与 {@link WorkspaceProperties}（工作区在哪、必填校验）的读者不是同一批人。
 *
 * <p>{@code @MapperScan} 指向 {@code ai.agent.mapper} —— <b>SQL 与 Java 的边界包</b>：
 * 每个接口对应一张表（或一组相关查询），语句在同名 XML 里。
 * 放在这里而不是各 mapper 上逐个标 {@code @Mapper}，是为了让"这一层有哪些 mapper"
 * 在一个地方看得全（新增一个 mapper 忘了标注，症状是启动时"找不到 bean"，
 * 而那种错看起来像包扫描没生效）。
 */
@SpringBootApplication
@MapperScan("ai.agent.mapper")
@EnableConfigurationProperties({ ChatCallProperties.class, RagProperties.class, AgentApprovalProperties.class,
		ReviewProperties.class, WorkspaceProperties.class, WorkspaceFileProperties.class })
public class AiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiAgentApplication.class, args);
	}

}
