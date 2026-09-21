package ai.agent.service.tool.workspace;

/**
 * <b>一把"只能在工作区里干活"的工具</b>（五把文件工具都是）。
 *
 * <h2>它为什么是一个<b>标记</b>接口，而不是 {@code AgentTool} 的子接口</h2>
 * 它一层方法都不加，作用是让那些<b>不执行工具、但要决定怎么对待它</b>的地方
 * 能一眼认出这一类工具：
 * <ul>
 *   <li>MCP 暴露侧：外部 MCP 客户端那条链路没有"一次运行"，
 *       于是 {@code ToolCallContext} 里<b>没有工作区</b> —— 这些工具在那条链路上
 *       没有可解析路径的根，暴露出去只会"每次都回一句没有工作区"（见
 *       {@code McpToolExposure#exposeReadOnlyTools}）；</li>
 *   <li>人工确认闸门：这些工具的"是读还是写、写在工作区内还是外"取决于<b>本次参数</b>，
 *       而不是工具自己的声明（见 {@link WorkspaceCallContext}）。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>继承 {@code AgentTool}：那会让实现类同时继承两条泛型链
 * （本接口的 {@code AgentTool} 与 {@code AgentTool<P>}），而 Java 不允许同一个类
 * 用不同的类型实参继承同一个泛型接口 —— 一个纯粹的标记不需要付出这个代价。
 */
public interface WorkspaceBoundTool {

}
