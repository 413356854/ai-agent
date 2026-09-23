package ai.agent.service.tool.workspace;

/**
 * <b>一把"必须有运行上下文才成立"的工具</b>（技能的正文读取是这一类）。
 *
 * <h2>与 {@link WorkspaceBoundTool} 的分工</h2>
 * 两者都回答"这条路有没有'一次运行'"这件事，但缺的东西不同：
 * <ul>
 *   <li>{@link WorkspaceBoundTool}：缺的是<b>工作区目录</b>（路径判定没有根）；</li>
 *   <li>本接口：缺的是<b>执行者</b>（技能目录是按"这次是谁在跑"筛的，没有他也就没有候选范围）。</li>
 * </ul>
 * 合成一个接口的写法在 MCP 那一侧看起来一样（都挡在门外），但两件事的理由完全不同 ——
 * 而"为什么挡住它"这句话是要给人看的（装配层会逐条记日志），
 * 写成同一个理由会让下一个加工具的人以为"挡住的都是因为工作区"。
 *
 * <h2>它为什么也是一个<b>标记</b>接口</h2>
 * 理由与 {@link WorkspaceBoundTool} 一字不差：一层方法都不加，
 * 作用只是让"不执行工具、但要决定怎么对待它"的地方能一眼认出这一类 ——
 * 现在唯一的消费者是 MCP 暴露侧（见 {@code McpToolExposure#decideExposure}）。
 */
public interface RunContextBoundTool {

}
