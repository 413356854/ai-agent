package ai.agent.mcp;

import ai.agent.tool.AgentTool;
import ai.agent.tool.ToolCallExecutor;
import ai.agent.tool.ToolInvocation;
import ai.agent.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>MCP Server 的接线与安全边界测试</b> —— 阶段三第 10 件（路线文档标注的「本项目最大亮点」）。
 *
 * <h2>本类存在的理由：一个"装得上、但一调就炸"的坑</h2>
 * 这是本轮最值得记下来的一个真实风险，它不是假想出来的：
 * <ol>
 *   <li>本项目的 {@code AgentTool} <b>就是</b> Spring AI 的 {@code ToolCallback}
 *       （刻意的设计：直接实现真 SPI，换回真模型零适配）；</li>
 *   <li>它们同时是 Spring 容器里的 bean；</li>
 *   <li>Spring AI 的 {@code ToolCallbackConverterAutoConfiguration}
 *       会<b>收集容器里全部的 {@code ToolCallback} bean</b> 注册成 MCP 工具；</li>
 *   <li>而 {@code AgentTool.call(String)} 被<b>故意实现成抛异常</b> ——
 *       它是绕过校验的第二条执行路径，必须堵死。</li>
 * </ol>
 * 四条相撞的结果是：<b>MCP 的 {@code tools/list} 正常列出工具，只有 {@code tools/call} 才炸。</b>
 * 一个只在第一次真正被使用时才暴露的失败。
 *
 * <h2>本类的三条断言各自堵哪一段</h2>
 * <ol>
 *   <li>{@link #onlyReadOnlyToolsAreExposed()} —— 注册进 MCP 的**恰好**是只读工具，
 *       写工具<b>不在里面</b>（"一把不多、一把不少"）；</li>
 *   <li>{@link #rawAgentToolsAreNeverHandedToMcp()} —— 交出去的不是原始
 *       {@code AgentTool}（那个一调就抛），而它的 {@code call} 真的能跑通；</li>
 *   <li>{@link #writeToolsAreRefusedAtExposureTime()} ——
 *       万一有人想把写工具加进暴露列表，它必须在**构造期**就抛，
 *       而不是安静地少一个工具或安静地开一个后门。</li>
 * </ol>
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.url=jdbc:h2:mem:mcp-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"ai.agent.store.node=mcp-test", "ai.rag.corpus.location=classpath:rag/corpus" })
class McpExposureTest {

	@Autowired
	private McpToolExposure exposure;

	@Autowired
	private ToolRegistry registry;

	@Autowired
	private ToolCallExecutor executor;

	/**
	 * MCP Server 真的被装配了吗（可空注入：关掉 MCP 是合法配置）。
	 *
	 * <p>本类用 {@code ObjectProvider} 而不是强制注入，是为了让"关掉 MCP"这个配置
	 * 也能跑同一份测试代码（那种情况下这个字段是 null，下面的用例会显式跳过并说明）。
	 * 但本类的 properties 里没有关它 —— 所以下面每条断言都要求它非空。
	 */
	@Autowired
	private org.springframework.beans.factory.ObjectProvider<McpSyncServer> serverProvider;

	// ------------------------------------------------------------------
	//  ① 注册进 MCP 的恰好是只读工具
	// ------------------------------------------------------------------

	@Test
	@DisplayName("MCP Server 里注册的是**恰好**三把只读工具：一把不多、一把不少")
	void onlyReadOnlyToolsAreExposed() {
		McpSyncServer server = serverProvider.getIfAvailable();
		assertThat(server).as("本测试的配置是打开的，所以 MCP Server 必须存在").isNotNull();

		List<String> registered = server.listTools().stream().map(McpSchema.Tool::name).sorted().toList();

		assertThat(registered)
				.as("★ 注册进去的必须恰好是只读工具。多一把就意味着有工具绕过了"
						+ "「写操作不对外」这条规矩；少一把意味着某把工具对外不可见了")
				.containsExactly("list_documents", "read_section", "search_docs");
		assertThat(registered)
				.as("★ 写工具**绝不能**出现在 MCP 的工具列表里 —— 它的另一半是人工确认，"
						+ "而外部客户端看不到待办界面、也没法替人点批准")
				.doesNotContain("propose_review");

		assertThat(exposure.exposedToolNames()).containsExactlyInAnyOrder("list_documents", "read_section",
				"search_docs");
		assertThat(exposure.withheldToolNames())
				.as("被挡在门外的工具也要能报出来 —— 否则'少了什么'没人知道").containsExactly("propose_review");
	}

	@Test
	@DisplayName("工具的风险声明被带进了 MCP 的 annotations（同一份声明驱动三件事：审批判据 / 自检 / 对外元数据）")
	void hintsAreCarriedIntoMcpAnnotations() {
		McpSyncServer server = serverProvider.getIfAvailable();
		assertThat(server).isNotNull();

		McpSchema.Tool search = server.listTools().stream().filter(t -> "search_docs".equals(t.name())).findFirst()
				.orElseThrow();

		assertThat(search.annotations())
				.as("annotations 必须存在 —— 它是 MCP 规范给'工具安全提示'的地方，"
						+ "而对方的模型会读它来决定要不要调")
				.isNotNull();
		assertThat(search.annotations().readOnlyHint()).as("search_docs 只读").isTrue();
		assertThat(search.annotations().destructiveHint()).as("只读工具不可能破坏性").isFalse();
		assertThat(search.annotations().idempotentHint()).isTrue();
		assertThat(search.annotations().openWorldHint()).as("它只在本地语料里检索，不碰外部世界").isFalse();

		// 描述必须与模型看到的那份**逐字一致**（单一事实来源）。
		// 两处各写一遍 description 必然漂移，而漂移的表现是"外部客户端与我们的模型
		// 对同一把工具的理解不一样"，那会让对方选错工具、而我们这边看起来很无辜
		String modelFacing = registry.find("search_docs").orElseThrow().description().render();
		assertThat(search.description()).isEqualTo(modelFacing);
	}

	// ------------------------------------------------------------------
	//  ② 交给 MCP 的不是原始 AgentTool（那个一调就抛）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 交给 MCP 的不是原始 AgentTool：原始那个的 call() 故意抛异常，直接暴露会让 MCP 一调就炸")
	void rawAgentToolsAreNeverHandedToMcp() {
		// 先把这个坑本身钉住：原始工具的直接调用**必须**抛
		// （这条断言与 ToolCallBoundaryTest 里那条是同一个事实的两面：
		//  那边的意思是"绕过边界的路走不通"，这里的意思是"所以它不能交给 MCP"）
		AgentTool<?> raw = registry.find("list_documents").orElseThrow();
		assertThatThrownBy(() -> raw.call("{}"))
				.as("★★ 这是本类存在的前提：如果它不抛，后面的隔离就都不必要了")
				.isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("ToolCallExecutor");

		// 再看真正交出去的东西
		// 用装配层真正用的那个入口（它允许混合集合，并挡掉写工具）。
		// 【不要用 exposedCallbacks()】那个入口的语义是"我要一个纯只读的集合"，
		// 而本项目的注册表是混合的（三把只读 + 一把有审批要求的写）——
		// 在它上面调会当场抛，那正是它设计出来的行为
		List<ToolCallback> exposed = readOnlyCallbacks();
		assertThat(exposed).isNotEmpty();
		for (ToolCallback callback : exposed) {
			assertThat(callback)
					.as("★ 交给 MCP 的必须是**包过的**回调，不能是原始 AgentTool —— "
							+ "否则 tools/list 正常、tools/call 必炸，而那个失败只在第一次真正被使用时才暴露")
					.isNotInstanceOf(AgentTool.class);
		}
	}

	@Test
	@DisplayName("包过的回调真的能跑通，而且返回的是执行器那一套回填文本（两种调用方共用一份措辞）")
	void exposedCallbackActuallyWorks() {
		ToolCallback listDocuments = readOnlyCallbacks().stream()
				.filter(c -> "list_documents".equals(c.getToolDefinition().name())).findFirst().orElseThrow();

		String result = listDocuments.call("{}");

		assertThat(result)
				.as("★ 它能跑通才算'装上了'。这一步正是上面的坑会炸掉的地方").isNotBlank();
		assertThat(result).as("回填文本来自 ToolFeedbackFormatter（含 call_id），所以外部客户端拿到的说明"
				+ "与模型看到的同一套措辞 —— 不需要维护第二份").contains("call_id").contains("知识库");
	}

	@Test
	@DisplayName("外部客户端的参数同样要过全部门禁：错参数返回的是带可用值的说明，而不是异常")
	void externalArgumentsGoThroughTheSameGates() {
		ToolCallback search = readOnlyCallbacks().stream()
				.filter(c -> "search_docs".equals(c.getToolDefinition().name())).findFirst().orElseThrow();

		// 缺必填字段
		String missingField = search.call("{}");
		assertThat(missingField)
				.as("★ 外部客户端的参数与模型的输出一样不可信（都是'另一个程序给的字符串'），"
						+ "所以必须走同一道门禁 —— 这里返回的是校验失败说明")
				.contains("query").contains("不合法");

		// 不存在的工具名（直接在执行器上验证白名单那一层）
		assertThat(executor.execute(ToolInvocation.from("mcp:x", "no_such_tool", "{}"),
				ai.agent.tool.ToolCallContext.mcpClient()).stage())
				.as("白名单对外部调用同样生效").isEqualTo(ai.agent.tool.ToolCallStage.UNKNOWN_TOOL);
	}

	@Test
	@DisplayName("来源被如实标成 MCP_CLIENT：'模型乱调'与'外部客户端调错'的处置方式与责任方完全不同")
	void originIsRecorded() {
		var outcome = executor.execute(ToolInvocation.from("mcp:list_documents", "list_documents", "{}"),
				ai.agent.tool.ToolCallContext.mcpClient());

		assertThat(outcome.origin()).isEqualTo(ai.agent.tool.ToolCallOrigin.MCP_CLIENT);
		assertThat(outcome.origin().label()).contains("外部");

		// 对照：默认（模型链路）不能被这条改动带偏
		var fromLoop = executor.execute(ToolInvocation.from("c1", "list_documents", "{}"));
		assertThat(fromLoop.origin()).as("旧签名必须仍然默认成模型链路 —— 十几处已有调用点依赖这个默认值")
				.isEqualTo(ai.agent.tool.ToolCallOrigin.AGENT_LOOP);
	}

	// ------------------------------------------------------------------
	//  ③ 写工具必须被硬拒（不是被安静过滤掉）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 想暴露写工具时**构造期就抛**：安静的少一个工具比启动失败危险得多")
	void writeToolsAreRefusedAtExposureTime() {
		// 造一个"只含写工具"的注册表，然后请求暴露它 —— 必须抛，而且要说清为什么
		AgentTool<?> writeTool = registry.find("propose_review").orElseThrow();
		ToolRegistry writeOnly = new ToolRegistry(List.of(writeTool));
		McpToolExposure exposureWithWrite = new McpToolExposure(writeOnly, executor);

		assertThatThrownBy(exposureWithWrite::exposedCallbacks)
				.as("★ 写工具不能'安静地不暴露'：那样有人手滑加进来时没人会发现。"
						+ "必须在构造期就抛，并说清'人工确认由谁来做'这个前提")
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("propose_review")
				.hasMessageContaining("人工确认");
	}

	// ------------------------------------------------------------------
	//  ④ 配置防线：排除自动配置这件事必须真的生效
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 会误收 AgentTool 的那个自动配置**真的被排除了**（配置写错了 Spring 不报错，所以必须断言）")
	void dangerousAutoconfigurationIsExcluded() {
		// 断言的是"那个类确实存在于 classpath 上"。它很重要：
		// 如果哪天 Spring AI 改了包名，我们排除的字符串就指向一个不存在的类，
		// 而 **Spring 对'排除一个不存在的自动配置类'是宽容的** —— 它不会报错，
		// 于是我们会安静地回到坑里，而且没有任何迹象。
		// 有这条断言，改名会在这里变红，而不是在某个 MCP 客户端第一次调用时炸
		assertThat(classExists(McpToolExposure.TOOL_CALLBACK_CONVERTER_AUTOCONFIG))
				.as("排除的类名必须仍然存在（它写在 application.properties 的 spring.autoconfigure.exclude 里）"
						+ "—— 否则那次排除是空操作，而 Spring 不会告诉你")
				.isTrue();

		// 而且它必须真的是一个自动配置类（否则我们排除的是别的东西）
		assertThat(annotationOn(McpToolExposure.TOOL_CALLBACK_CONVERTER_AUTOCONFIG, AutoConfiguration.class))
				.as("它应当带 @AutoConfiguration（这正是它能被 spring.autoconfigure.exclude 排掉的前提）")
				.isTrue();

		// 最后：实际装配的结果里不含原始工具 —— 这才是"排除真的生效了"的证据
		assertThat(readOnlyCallbacks()).noneMatch(c -> c instanceof AgentTool);
	}

	/** 装配层实际暴露出去的那些回调（按"每把工具自己的风险声明"路由之后）。 */
	private List<ToolCallback> readOnlyCallbacks() {
		return exposure.exposeReadOnlyTools().stream().map(McpToolExposure.ExposedTool::callback).toList();
	}

	private static boolean classExists(String className) {
		try {
			Class.forName(className, false, McpExposureTest.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException e) {
			return false;
		}
	}

	private static boolean annotationOn(String className, Class<? extends java.lang.annotation.Annotation> annotation) {
		try {
			Class<?> type = Class.forName(className, false, McpExposureTest.class.getClassLoader());
			return type.isAnnotationPresent(annotation);
		}
		catch (ClassNotFoundException e) {
			return false;
		}
	}

}
