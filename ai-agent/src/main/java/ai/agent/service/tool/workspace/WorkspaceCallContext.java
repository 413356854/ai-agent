package ai.agent.service.tool.workspace;

import ai.agent.entity.tool.ToolCallContext;

/**
 * <b>把"这一次调用动的是哪儿"交给要看它的那两层</b>（人工确认闸门与 MCP 暴露侧）。
 *
 * <h2>为什么它必须挂在 {@code AgentTool#onContext} 上</h2>
 * 判据是"这次要改的东西在不在工作区内"，而它只能从<b>本次参数里的路径</b>算出来。
 * {@code ToolCallExecutor} 的执行顺序是：
 * <pre>
 *   白名单 → 并发闸门 → 解析 → JSR-380 校验 → <b>人工确认（读这个结论）</b> → 工具执行
 * </pre>
 * 唯一的天然写入点就是 {@code onContext} —— 它在"人工确认"之前被调，
 * 而且它拿得到上下文（里面有本次的原始参数与工作区目录）。
 *
 * <h2>为什么状态存在工具里，而不是各自再解析一遍</h2>
 * 闸门与工具<b>必须看到同一个结论</b>。各解析一遍的失效方式是静默的：
 * 两边对"越界"的处理稍有出入（一边归一化了、一边没有），
 * 结果就是<b>闸门以为在工作区内放行了，工具却写到了工作区之外</b>。
 * 所以解析只有一次（工具自己做），闸门读的是同一个对象的 {@link #access()}。
 */
public interface WorkspaceCallContext {

	/**
	 * 设定这一次调用的工作区访问 —— <b>它就是 {@code AgentTool#onContext} 的实现</b>。
	 *
	 * @param context 本次调用的上下文（工作区在 {@code runPermission()} 里、参数在 {@code arguments()} 里）
	 */
	void setCallContext(ToolCallContext context);

	/**
	 * 这一次要动的东西的工作区访问；<b>从来不会是 {@code null}</b>。
	 *
	 * <p>没有工作区时返回的是 {@link FileAccess#none()}（一个"没有根"的访问对象），
	 * 而不是 null —— 判据与"缺失一律按最严的一侧算"一致：
	 * 没有工作区 = 不在工作区内 = 写操作要人点头。
	 */
	FileAccess access();

	/** 本次参数里那条路径的原始写法（空串 = 这把工具这次没有路径）。闸门用它复核边界。 */
	String targetPath();

	/** 给日志与审批记录的一行说明：这次要动哪个路径、它算不算在区内。 */
	String workspaceNote();
}
