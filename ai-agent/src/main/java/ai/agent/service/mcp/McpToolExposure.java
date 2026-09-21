package ai.agent.service.mcp;

import ai.agent.service.tool.AgentTool;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.service.tool.ToolCallExecutor;
import ai.agent.service.tool.ToolCallOrigin;
import ai.agent.entity.tool.ToolCallOutcome;
import ai.agent.service.tool.ToolRegistry;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.service.tool.workspace.WorkspaceBoundTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>把内部工具包成"可以让外部 MCP 客户端调用"的样子 —— 但只包只读的那些。</b>
 *
 * <h2>为什么需要这一层包装，而不是直接把 {@code AgentTool} 交出去</h2>
 * 这是本轮最值得记下的一个坑，而且它是<b>真实存在、一碰就炸</b>的：
 * <ol>
 *   <li>本项目的 {@code AgentTool} <b>就是</b> Spring AI 的 {@link ToolCallback}（那是刻意的设计，
 *       见 {@code AgentTool} 的类注释：直接实现真 SPI，换回真模型零适配）；</li>
 *   <li>它们同时是 Spring 容器里的 bean（{@code @Component}）；</li>
 *   <li>而 Spring AI 的 MCP 自动配置 {@code ToolCallbackConverterAutoConfiguration}
 *       会<b>收集容器里全部的 {@code ToolCallback} bean</b> 并把它们注册成 MCP 工具；</li>
 *   <li>但 {@code AgentTool.call(String)} 被<b>故意实现成抛异常</b> ——
 *       因为它是第二条绕过校验的执行路径，必须堵死。</li>
 * </ol>
 * 四条合起来的后果是：<b>"已装配的 MCP Server 一旦被调用就抛 UnsupportedOperationException"</b>。
 * 最坏的地方在于它是<b>静默的</b> —— 启动正常、{@code tools/list} 正常（工具赫然在列）、
 * 只有真正 {@code tools/call} 的那一刻才炸，而错误信息看起来像"这个工具的代码有 bug"。
 *
 * <p>这正好是那一类失败：<b>"装上了"与"能用"是两件事</b>
 * （「『已完成』这个状态，只在被实际问过一次之后才算数」）。
 *
 * <h2>解法：把"暴露什么"变成一次显式的决定</h2>
 * 不再让自动配置替我们决定，而是由 {@link ai.agent.config.mcp.McpExposureConfig}
 * <b>排除那个自动配置</b>，并只用本类包装出来的回调注册工具。
 * 于是"哪些能力对外开放"这件事，在代码里有一处明确的答案。
 *
 * <h2>为什么只暴露只读工具（这是一条安全边界，不是偷懒）</h2>
 * 写工具（{@code propose_review}）<b>刻意不暴露</b>，理由是它的另一半是人工确认：
 * MCP 调用的对面是<b>另一个系统</b>，它既看不到本项目的待办界面、
 * 也没法替人点"批准"。把它暴露出去只有两种结局：
 * 要么外部调用永远卡在等人上（对调用方是"这个工具坏了"），
 * 要么我们为了让外部能用而给它开后门 —— 而那道门一开，审批就形同虚设。
 * <p>所以规矩是：<b>MCP 对外只提供"读"，"写"留在这个应用自己的模型链路里、并受人监管。</b>
 * 这条规矩在 {@link #wrap} 里是<b>硬失败</b>（抛异常）而不是过滤掉 ——
 * 哪天有人手滑把写工具加进暴露列表，启动就该失败，而不是安静地少一个工具。
 */
public class McpToolExposure {

	private final ToolRegistry registry;

	private final ToolCallExecutor executor;

	/**
	 * <b>这一次交给 MCP 的工具</b> —— 构造期算一次，之后三处读的都是它：
	 * 装 MCP Server 的那份 spec、启动日志、启动自检的预期清单。
	 */
	private final List<ExposedTool> exposedTools;

	public McpToolExposure(ToolRegistry registry, ToolCallExecutor executor) {
		this.registry = registry;
		this.executor = executor;
		// 暴露面在构造期定一次：注册表在整个进程生命周期里不再变（ToolRegistry 的 all() 是常量），
		// 而"这次暴露了哪些"要被三处读（装 MCP 的那份 spec、启动日志、启动自检），
		// 每次现算一遍会让"挡在门外的那些"日志重复打印，也会让三处有机会各算出一个结果
		this.exposedTools = decideExposure();
	}

	/**
	 * 可以被外部 MCP 客户端调用的工具（当前 = 全部只读工具）。
	 *
	 * <h2>⚠️ 它只在"注册表里全是只读工具"时才会返回结果</h2>
	 * 只要注册表里出现<b>任何一把非只读工具，它就抛异常</b> —— 这是刻意的，
	 * 而且这个取舍值得解释清楚，因为第一版写反了：
	 *
	 * <p><b>第一版的写法是"过滤掉非只读的"</b>（{@code if (readOnly) wrap(tool)}），
	 * 而它让下面 {@link #wrap} 里那道"拒绝暴露写工具"的硬失败变成了<b>死代码</b>——
	 * 过滤条件已经把写工具挡在前面，抛异常那一行永远不会被执行。
	 * 于是一个"看起来有两道防线"的实现实际上只有一道，而另一道是摆设。
	 * 这个失败很典型：<b>两道防线写在同一个方法里、且第一道已经把第二道的输入清空了。</b>
	 *
	 * <p>修法不是简单地把顺序换一下，而是<b>把"决定暴露哪些"这件事搬到装配层</b>
	 * （见 {@code ai.agent.config.mcp.McpExposureConfig}：那里对每把工具按它的风险声明显式路由 ——
	 * 只读的暴露、有审批要求的挡在门外并记一条日志），
	 * 而本方法只留一个<b>用途单一</b>的语义：<i>"我想要一个纯只读的暴露集合"</i>。
	 * 谁在混合集合上调它，就会当场失败 —— 而它现在的调用方只有测试，
	 * 这正是它该有的样子。
	 *
	 * @throws IllegalStateException 注册表里存在非只读工具（见 {@link #wrap}）
	 */
	public List<ToolCallback> exposedCallbacks() {
		List<ToolCallback> callbacks = new ArrayList<>();
		for (AgentTool<?> tool : registry.all()) {
			// 不过滤，直接交给 wrap —— 由它决定"能不能暴露"，不能就抛。
			// 过滤会让这里的失败变得沉默，而那正是第一版的问题
			callbacks.add(wrap(tool));
		}
		return List.copyOf(callbacks);
	}

	/**
	 * <b>装配层真正用的入口</b>：按每把工具自己的风险声明路由，返回"这次要暴露的"那些。
	 *
	 * <p>它把"决定"放在这里、而把"拒绝"留在 {@link #exposedCallbacks()}，
	 * 两者的分工是刻意的：
	 * <ul>
	 *   <li>本方法回答<b>"实际上暴露什么"</b> —— 对混合集合（生产就是混合的：
	 *       只读的检索工具、只读但需要工作区的文件工具、有审批要求的写工具）它正常工作，
	 *       挡在门外的那些各自记一条日志说明为什么；</li>
	 *   <li>{@link #exposedCallbacks()} 回答<b>"我要一个纯只读的集合"</b> ——
	 *       混合集合上它会抛。它现在是给测试与将来的"只读网关"用的。</li>
	 * </ul>
	 * 这么拆之后，"挡住写工具"这件事不再依赖"调用方恰好用了哪个入口"：
	 * 装配层记日志说明它挡了什么，而任何在混合集合上要求"全是只读"的调用都会当场失败。
	 * 两者都比"安静地少一个工具"好。
	 *
	 * <h2>为什么文件工具即使只读也不能暴露</h2>
	 * 外部 MCP 客户端的上下文是 {@code ToolCallContext.mcpClient()}，它<b>没有工作区</b>
	 * （那是"一次运行"的属性，而这条路没有运行）。于是这些工具在 MCP 这条链路上
	 * 没有可解析路径的根 —— 暴露出去的结果只会是"每次都回一句没有工作区"，
	 * 而那种现象在客户端看来像"这个工具坏了"。判据由工具自己声明
	 * （{@link WorkspaceBoundTool}，一个纯粹的标记接口）：将来再加文件工具时，
	 * 忘了实现的后果是"它被暴露出去"，而那种错不报错 —— 所以这里认的就是那个接口。
	 */
	public List<ExposedTool> exposeReadOnlyTools() {
		return exposedTools;
	}

	/**
	 * 按每把工具自己的声明路由一次：需要工作区的、写操作的都挡在门外，各记一条日志说明为什么。
	 *
	 * <p>这是"对外到底暴露什么"<b>唯一</b>一次计算 —— 见 {@link #exposedToolNames()} 里那条判据。
	 */
	private List<ExposedTool> decideExposure() {
		List<ExposedTool> exposed = new ArrayList<>();
		for (AgentTool<?> tool : registry.all()) {
			if (tool instanceof WorkspaceBoundTool) {
				log.info("工具「{}」不通过 MCP 暴露（它需要工作区）—— "
						+ "外部 MCP 链路没有「一次运行」这个对象，ToolCallContext 里没有工作区目录，"
						+ "这些工具在那条链路上没有可解析路径的根", tool.name());
				continue;
			}
			ToolHints hints = tool.description().hints();
			if (!hints.readOnlyHint()) {
				log.info("工具「{}」不通过 MCP 暴露（风险声明：{}）—— "
						+ "写操作的审批要在本应用的界面上完成，外部客户端看不到待办、也没法替人批准",
						tool.name(), hints.describe());
				continue;
			}
			exposed.add(new ExposedTool(wrap(tool), hints));
		}
		return List.copyOf(exposed);
	}

	/** 一把"已包装好、准备交给 MCP"的工具，连同它的风险声明（annotations 要照着它填）。 */
	public record ExposedTool(ToolCallback callback, ToolHints hints) {

		public String name() {
			return callback.getToolDefinition().name();
		}

	}

	/**
	 * 对外暴露的工具名 —— <b>它就是"交给 MCP 的那一份"</b>（由 {@link #decideExposure()} 派生）。
	 *
	 * <h2>为什么不在这里另写一遍过滤</h2>
	 * 那会变成第二份判据，而两份判据一旦分叉，启动自检的"预期"里就会出现
	 * <b>我们其实没有交出去的工具</b> —— 现象是默认配置下应用起不来
	 * （预期 7 把、实际注册 4 把，差的正是那三把需要工作区的文件工具），
	 * 而它真正要拦的那件事（别的自动配置又往 MCP 里塞了工具）反而被这堆噪声淹没。
	 * 判据只有一份时，这个断言拦的仍然是原本那两件事：
	 * 容器里多出来的原始 {@code AgentTool} 被注册成了 MCP 工具、以及这份清单压根没被注册。
	 */
	public List<String> exposedToolNames() {
		return exposedTools.stream().map(ExposedTool::name).toList();
	}

	/** 被<b>刻意挡在门外</b>的工具名（写操作）—— 同样要说出来，否则"少了什么"没人知道。 */
	public List<String> withheldToolNames() {
		return registry.all().stream().filter(t -> !t.description().hints().readOnlyHint()).map(AgentTool::name).toList();
	}

	/**
	 * 包装一把工具：<b>真正的执行仍然走 {@link ToolCallExecutor}</b>。
	 *
	 * <h2>为什么包装体不直接调用 {@code tool.invoke(...)}</h2>
	 * 那会绕过白名单、JSON 解析、JSR-380 校验、并发闸门、熔断、审批、计时与分类 ——
	 * 也就是把本项目花了一整个阶段立起来的边界，在 MCP 这一侧全部拆掉。
	 * <b>外部客户端的参数和模型的输出一样不可信</b>（都是"另一个程序给的字符串"），
	 * 所以它必须走同一道门。
	 *
	 * <p>顺带的好处：包出来的错误文本是 {@code ToolFeedbackFormatter} 那一套，
	 * 也就是说"参数不合法"返回给外部客户端的说明里同样会带上字段名与可用值。
	 * 一份措辞服务两种调用方，不需要维护第二套。
	 */
	private ToolCallback wrap(AgentTool<?> tool) {
		ToolHints hints = tool.description().hints();
		if (!hints.readOnlyHint()) {
			// 硬失败，理由见类注释：安静的少一个工具比启动失败危险得多
			throw new IllegalStateException(
					"拒绝把写工具「%s」暴露给外部 MCP 客户端（它的风险声明是「%s」）。"
							.formatted(tool.name(), hints.describe())
							+ "写操作必须留在本项目自己的模型链路里、并经过人工确认 —— "
							+ "外部客户端看不到待办界面，也就没法替人批准。"
							+ "如果你确实要暴露它，请先想清楚它的人工确认由谁来做。");
		}

		// 委托定义：名字 / 描述 / schema 全部来自工具自己（单一事实来源，
		// 与模型看到的那份逐字一致 —— 两处各写一遍必然漂移）
		ToolDefinition definition = tool.getToolDefinition();

		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String arguments) {
				// 调用 id 用工具名：MCP 协议里没有 tool_call_id 这个概念，
				// 而执行器需要一个 id 来拼回填文本。用工具名比编一个随机值更好读，
				// 而且它同时也是"这条记录来自 MCP"的第一眼线索
				ToolCallOutcome outcome = executor.execute(
						ai.agent.entity.tool.ToolInvocation.from("mcp:" + tool.name(), tool.name(), arguments),
						ToolCallContext.mcpClient());
				return outcome.feedback();
			}

			@Override
			public String toString() {
				return "McpExposedTool(" + tool.name() + ", readOnly, via ToolCallExecutor)";
			}
		};
	}

	@Override
	public String toString() {
		return "MCP 暴露：%s；刻意不暴露（写操作）：%s".formatted(exposedToolNames(),
				withheldToolNames().isEmpty() ? "无" : withheldToolNames());
	}

	/**
	 * <b>Spring AI 那个会"收集容器里全部 {@code ToolCallback} bean"的自动配置的类名。</b>
	 *
	 * <p>做成常量并放在这里（而不是散在某个注解字符串里），是因为它需要出现在三个地方：
	 * ① 排除它的配置；② 启动自检的断言；③ 注释与文档。
	 * 三处各写一遍字符串的话，哪天 Spring AI 改了包名，
	 * 最可能的结果是"排除了一个不存在的类"—— 而 Spring <b>不会因此报错</b>，
	 * 它只会安静地继续收集。那正好回到本类要防的那个坑里。
	 */
	public static final String TOOL_CALLBACK_CONVERTER_AUTOCONFIG =
			"org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration";

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpToolExposure.class);

}
