package ai.agent.service.tool;

/**
 * 这次工具调用<b>是从哪条链路进来的</b>。
 *
 * <h2>为什么这个字段值得存在（它一眼看起来像"调试信息"）</h2>
 * 本项目现在有两条能执行工具的链路，而它们的<b>信任边界完全不同</b>：
 * <ul>
 *   <li>{@link #AGENT_LOOP} —— 模型提出意图，经过五道门禁之后执行。
 *       这是主链路，"不可信输入"这个前提在这里成立；</li>
 *   <li>{@link #MCP_CLIENT} —— <b>外部</b>MCP 客户端（别人的 IDE、别的 Agent、
 *       别人的 Claude Desktop）通过我们暴露的 MCP Server 调进来。
 *       参数同样不可信，但请求方是<b>另一个系统</b>而不是我们自己。</li>
 * </ul>
 * 不区分这两者时，出问题之后第一个问题就没法回答：
 * <b>"这次调用是谁发起的？"</b> —— 而"模型乱调"和"外部客户端调错"的处置方式
 * （改提示词 vs 改对方的接线）与责任方完全不同。
 *
 * <p>顺带它还是一条安全审计的起点：MCP 暴露面是"我们主动对外开放的能力"，
 * 所以"外部到底调了什么、调了多少次"必须是可统计的 ——
 * 这个字段就是那条统计的键。
 */
public enum ToolCallOrigin {

	/** Agent 循环（模型提出的意图）。 */
	AGENT_LOOP,

	/**
	 * 外部 MCP 客户端。
	 *
	 * <p>⚠️ 这一路上<b>没有模型</b>：参数是对方的程序直接给的。
	 * 所以"回填给模型的文本"在这里变成了"返回给客户端的错误说明"——
	 * 同一份文本两用，是 {@code ToolFeedbackFormatter} 那些措辞能同时服务两边的直接原因。
	 */
	MCP_CLIENT;

	/** 给报告/界面用的可读名。 */
	public String label() {
		return switch (this) {
			case AGENT_LOOP -> "模型";
			case MCP_CLIENT -> "外部 MCP 客户端";
		};
	}

}
