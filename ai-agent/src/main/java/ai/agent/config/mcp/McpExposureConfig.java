package ai.agent.config.mcp;

import ai.agent.service.mcp.McpToolExposure;
import ai.agent.service.tool.ToolCallExecutor;
import ai.agent.service.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>MCP Server 的装配</b> —— 把本项目的只读工具暴露给外部 MCP 客户端。
 *
 * <h2>它做的三件事</h2>
 * <ol>
 *   <li>建一个 {@link McpToolExposure}（把内部工具包成 MCP 能调的样子，只包只读的）；</li>
 *   <li>把它转成 MCP 的 {@code SyncToolSpecification} 列表 ——
 *       <b>这个 Bean 就是 MCP Server 实际注册的工具集</b>；</li>
 *   <li>启动时做一次自检：<b>确认 MCP 侧列出来的工具真的是"包过的"那些，
 *       而不是原始的 {@code AgentTool} bean</b>。</li>
 * </ol>
 *
 * <h2>第 3 件才是本类存在的真正理由</h2>
 * 前两件是接线，第 3 件是<b>防线</b>。原因见 {@link McpToolExposure} 的类注释：
 * Spring AI 的 {@code ToolCallbackConverterAutoConfiguration} 会收集容器里<b>全部</b>
 * {@code ToolCallback} bean，而本项目的 {@code AgentTool} 恰好既是 {@code ToolCallback}
 * 又是 bean，且它的 {@code call(String)} 被故意实现成抛异常。
 * 如果那个自动配置生效，MCP 的 {@code tools/list} 会<b>正常列出工具</b>、
 * 只有 {@code tools/call} 才炸 —— 一个只在第一次真正使用时才暴露的失败。
 *
 * <p>所以本类<b>同时</b>做两件事，而不是只做一件：
 * <ul>
 *   <li>在 {@code application.properties} 里排除那个自动配置（
 *       见 {@code spring.autoconfigure.exclude}，值就是
 *       {@link McpToolExposure#TOOL_CALLBACK_CONVERTER_AUTOCONFIG}）；</li>
 *   <li>在启动时<b>真的去问一次 MCP 服务器："你现在装了哪些工具"</b>，
 *       并断言其中没有原始的 {@code AgentTool}。</li>
 * </ul>
 * 只做前者是不够的：一个"排除了但排除失效"的配置<b>不会报错</b>
 * （Spring 对"排除一个不存在的自动配置类"是宽容的），
 * 于是哪天 Spring AI 改了那个类的包名，我们会安静地回到坑里。
 * <b>配置表达意图，断言证明意图真的生效</b> —— 这两件事必须都有，
 * 这也是台账里那条结论的又一次应用：
 * 「『已完成』这个状态，只在被实际问过一次『装的是哪个实现』之后才算数」。
 */
@Configuration
public class McpExposureConfig {

	private static final Logger log = LoggerFactory.getLogger(McpExposureConfig.class);

	/**
	 * 暴露给外部的工具集。
	 *
	 * <p>Bean 类型刻意是 {@code List<SyncToolSpecification>}：
	 * Spring AI 的 {@code McpServerAutoConfiguration#mcpSyncServer(...)} 正是靠
	 * {@code ObjectProvider<List<SyncToolSpecification>>} 拿到工具并注册的。
	 * 也就是说<b>这个 Bean 就是"本 MCP Server 提供哪些工具"的答案</b>。
	 *
	 * <p>转换用 {@link McpToolUtils#toSyncToolSpecifications}：
	 * 它把 {@code ToolCallback} 的 name / description / inputSchema 原样映射成 MCP 的
	 * {@code Tool}。于是<b>模型看到的 description 与外部客户端看到的是同一份字符串</b> ——
	 * 单一事实来源又一次生效（描述改了，两边的行为一起变，不存在"改了模型那侧忘了改 MCP 那侧"）。
	 */
	@Bean
	public McpToolExposure mcpToolExposure(ToolRegistry registry, ToolCallExecutor executor) {
		McpToolExposure exposure = new McpToolExposure(registry, executor);
		log.info("MCP 工具暴露已确定：{}", exposure);
		return exposure;
	}

	@Bean
	public List<McpServerFeatures.SyncToolSpecification> mcpSyncToolSpecifications(McpToolExposure exposure) {
		List<McpToolExposure.ExposedTool> exposed = exposure.exposeReadOnlyTools();
		ToolCallback[] callbackArray = exposed.stream().map(McpToolExposure.ExposedTool::callback)
				.toArray(ToolCallback[]::new);
		List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>(
				McpToolUtils.toSyncToolSpecifications(callbackArray));

		// 把风险声明也带进 MCP 的 annotations —— 这是 MCP 规范给"工具安全提示"的地方，
		// 而我们的 ToolHints 用的正是它的语义（见 ToolHints 的类注释：四个字段都用 MCP 的命名）。
		// 于是"同一个声明驱动多件事"这句话在这里兑现：内部审批判据、启动自检、
		// 以及**外部客户端读到的元数据**
		//
		// 【hints 由 exposeReadOnlyTools() 一起给出来，而不是在这里回头去查工具】
		// 第一版是查的：`if (callback instanceof AgentTool<?> t)` —— 而这里拿到的是
		// **包装过的**回调（包装正是为了不让原始 AgentTool 流出去），
		// 所以那个 instanceof **永远为假**，annotations 一次都没被填上。
		// 这与本轮那个 MCP 陷阱是同一类错：**一个永远不会成立的条件，看起来像一道防线。**
		// 现在 hints 随回调一起传递，类型上就不可能"查不到"
		List<McpServerFeatures.SyncToolSpecification> annotated = new ArrayList<>(specs.size());
		for (int i = 0; i < specs.size(); i++) {
			annotated.add(withAnnotations(specs.get(i), exposed.get(i)));
		}
		return List.copyOf(annotated);
	}

	/**
	 * 启动自检：<b>问一次 MCP 服务器它现在装了哪些工具</b>，并确认没装上不该装的。
	 *
	 * <p>用 {@link ApplicationRunner} 而不是 {@code @PostConstruct}：
	 * 前者在<b>整个上下文就绪之后</b>才跑，于是 {@code McpSyncServer} 一定已经建好、
	 * 工具已经注册完 —— 这个断言才有意义。
	 * 在 {@code @PostConstruct} 里问，很可能问到一个还没装完工具的服务器，
	 * 然后得到一个"看起来通过"的结论。
	 *
	 * <p>断言失败时<b>直接抛</b>（启动失败）。这不是过激：一个"工具列在 MCP 里但一调就炸"
     * 的服务器，比一个起不来的服务器更难排查 —— 后者当场告诉你哪里不对，
     * 前者要等到有人真的去调它。
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = false)
	public ApplicationRunner mcpExposureSelfCheck(ObjectProvider<McpSyncServer> serverProvider,
			McpToolExposure exposure) {
		return args -> {
			McpSyncServer server = serverProvider.getIfAvailable();
			if (server == null) {
				log.warn("MCP Server 没有被装配（spring.ai.mcp.server.enabled=false）—— "
						+ "工具不会对外暴露；这可能是刻意的，但也可能是漏了配置");
				return;
			}

			List<String> registered = server.listTools().stream().map(McpSchema.Tool::name).sorted().toList();
			List<String> expected = exposure.exposedToolNames().stream().sorted().toList();

			// ① 注册进去的必须是"包过的"那些 —— 一把不多、一把不少
			if (!registered.equals(expected)) {
				throw new IllegalStateException(("MCP Server 注册的工具与预期不符 —— 拒绝启动。"
						+ "预期（只读工具）：%s；实际：%s。"
						+ "多出来的那些多半来自 ToolCallbackConverterAutoConfiguration："
						+ "它会收集容器里全部 ToolCallback bean，而本项目的 AgentTool "
						+ "故意让 call(String) 抛异常 —— 那样的工具列得出来但一调就炸。"
						+ "请确认 spring.autoconfigure.exclude 里仍然排除了 %s")
						.formatted(expected, registered, McpToolExposure.TOOL_CALLBACK_CONVERTER_AUTOCONFIG));
			}

			log.info("MCP Server 自检通过：对外提供 {} 把只读工具 [{}]；"
					+ "刻意不暴露的写工具 [{}]（写操作只能由本应用自己的模型链路发起，并经过人工确认）",
					registered.size(), String.join("、", registered),
					exposure.withheldToolNames().isEmpty() ? "无" : String.join("、", exposure.withheldToolNames()));
		};
	}

	/** 把 {@code ToolHints} 映射成 MCP 的 {@code annotations}（两者用的是同一套语义）。 */
	private static McpServerFeatures.SyncToolSpecification withAnnotations(
			McpServerFeatures.SyncToolSpecification spec, McpToolExposure.ExposedTool exposed) {
		var hints = exposed.hints();
		McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
				// 注意这四个 setter 收的是装箱 Boolean（javap 核实过），传原始值会被自动装箱。
				// 刻意全部显式写出，而不是只写 readOnlyHint：
				// annotations 里缺字段与"显式声明为 false"在 MCP 客户端看来是两件事 ——
				// 前者是"没说"，后者是"说了不是"。风险提示这种地方，说清楚比省字节重要
				.readOnlyHint(hints.readOnlyHint())
				.destructiveHint(hints.destructiveHint())
				.idempotentHint(hints.idempotentHint())
				.openWorldHint(hints.openWorldHint())
				.title(exposed.name())
				.build();
		McpSchema.Tool annotated = McpSchema.Tool.builder()
				.name(spec.tool().name())
				.description(spec.tool().description())
				.inputSchema(spec.tool().inputSchema())
				.annotations(annotations)
				.build();
		return McpServerFeatures.SyncToolSpecification.builder().tool(annotated).callHandler(spec.callHandler())
				.build();
	}

}
