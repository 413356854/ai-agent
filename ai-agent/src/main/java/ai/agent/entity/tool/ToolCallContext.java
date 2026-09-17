package ai.agent.entity.tool;

import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.ToolCallOrigin;
import java.time.Duration;

/**
 * 一次工具调用的执行上下文 —— <b>工具自己看不到，但审计需要知道</b>的那几个字段。
 *
 * <h2>为什么在执行器里单独加一个参数，而不是让工具自己去查</h2>
 * "这次调用属于哪一次运行的第几步"是一个<b>只有循环知道</b>的事实：
 * 工具实现是单例、无状态的（阶段一那条教训：共享可变状态在虚拟线程下会串味），
 * 让它自己去找当前运行，等于把"当前上下文"做成隐式状态 —— 并发下必错。
 *
 * <h2>为什么它不是可选信息的"装饰"</h2>
 * {@code stepIndex} 在 HITL 里是<b>必需的</b>：人工审批要落库，
 * 而"这个审批对应第几步的哪次调用"是事后回答
 * 「日志能不能还原执行过程」时的第一句话。没有它，审批记录只能靠时间戳去猜。
 *
 * <p>写成 record 而不是往 {@link ToolInvocation} 里加字段：
 * {@code ToolInvocation} 的语义是<b>不可信输入</b>（来自模型），
 * 而这个上下文是<b>我们自己的事实</b>。把两者混进一个类型，
 * 就会出现"模型输出里带着步号"这种说不通的形态。
 *
 * @param runId 运行 id；执行器被单独调用（不经循环）时为 {@code null}
 * @param stepIndex 第几步；为 {@code null} 表示"不属于某个具体步骤"
 * @param origin 从哪条链路进来的（模型 / 外部 MCP 客户端）。
 *               <b>它决定这条记录属于谁的账</b> —— 见 {@link ToolCallOrigin}
 * @param grant <b>这一次调用的"已批准"通行证</b>（可能为 {@code null}）。
 *              <p>它由<b>服务层</b>在恢复时从审批记录里重建并放进来（见
 *              {@code ResumableAgentService}）—— 而不是由执行器自己去查库：
 *              执行器是"边界"，它只该看到"有没有通行证"这个事实，
 *              不该知道审批记录存在哪、该怎么查。
 *              <p>为什么必须走这条路而不是让闸门自己记：批准发生在<b>上一次请求/上一个进程</b>里，
 *              恢复时闸门的内存记忆已经没了。把通行证做成本次调用的输入，
 *              "人批过"这件事才能跨过检查点与重启活下来。
 * @param approvalId <b>放行这次调用的审批 id</b>（可能为 {@code null}）。
 *              <p>它与 {@code grant} 不是一回事，两个都要有：
 *              {@code grant} 回答"要不要再问一次人"（只有"带着上一次批准"时才非空），
 *              而本字段回答"这次执行在审计上挂在哪条审批上"——
 *              一个刚刚被人批准、正在执行的调用，两者都非空；
 *              一个免审批的只读调用，两者都为空；一个"人批过但参数被改了"的调用，
 *              通行证不匹配（会重新审批），而<b>旧的那条审批 id 也不该被挂到新执行上</b>。
 *              <p>它的用途很具体：写工具要把"谁批的"写进它创建的业务记录里，
 *              于是"一条不该存在的记录"能直接倒查到"哪一次审批放行了它"。
 * @param decisionTimeout <b>这一次调用最多等多久人工确认</b>；{@code null} 表示用全局配置。
 *              <p>为什么需要"按调用覆盖"：不同链路的等待预算是不同的 ——
 *              一个交互式请求背后有用户在等页面（几秒到两分钟），
 *              而一个后台/批量任务可以等很久。把它们钉死在同一个全局值上，
 *              必然是一边嫌短、一边嫌长。
 *              <p>它同时让"超时"这条路径可以被测试：默认量表（分钟级）在测试里不可接受，
 *              而把逻辑删掉就等于不测它。有了覆盖，测试可以用同一个语义、只改时间刻度。
 */
public record ToolCallContext(String runId, Integer stepIndex, ToolCallOrigin origin, ApprovalGrant grant,
		String approvalId, Duration decisionTimeout) {

	public ToolCallContext {
		origin = origin == null ? ToolCallOrigin.AGENT_LOOP : origin;
	}

	/** Agent 循环里的一步（没有通行证 —— 首次提出调用时必定没有）。 */
	public static ToolCallContext ofStep(String runId, int stepIndex) {
		return new ToolCallContext(runId, stepIndex, ToolCallOrigin.AGENT_LOOP, null, null, null);
	}

	/** Agent 循环里的一步，且这一次调用带着人工批准的通行证（恢复路径用）。 */
	public static ToolCallContext ofStep(String runId, int stepIndex, ApprovalGrant grant) {
		return new ToolCallContext(runId, stepIndex, ToolCallOrigin.AGENT_LOOP, grant, null, null);
	}

	/**
	 * 与 {@link #ofStep(String, int)} 相同，但<b>这一次调用最多等这么久</b>。
	 *
	 * <p>存在的理由见 {@link #decisionTimeout}：不同链路该有不同的等待预算，
	 * 而测试需要在不删掉逻辑的前提下把时间刻度改小。
	 */
	public static ToolCallContext ofStep(String runId, int stepIndex, Duration decisionTimeout) {
		return new ToolCallContext(runId, stepIndex, ToolCallOrigin.AGENT_LOOP, null, null, decisionTimeout);
	}

	/**
	 * 把"这次调用挂在哪条审批上"补上 —— 执行器在门禁 ⑤ 之后调用它。
	 *
	 * <p>做成"派生一个新对象"而不是"设一个字段"：record 不可变正是这里需要的性质，
	 * 因为上下文会被传进工具（{@link AgentTool#onContext}）——
	 * 一个可变对象传进去，工具就能顺手改掉"我是谁批的"。<b>审计字段不该由被审计方改写。</b>
	 */
	public ToolCallContext withApproval(String approvalId) {
		return new ToolCallContext(runId, stepIndex, origin, grant, approvalId, decisionTimeout);
	}

	/**
	 * 外部 MCP 客户端的调用。
	 *
	 * <p>刻意<b>不带 runId</b>：这条路没有"运行"这个对象（没有模型、没有循环、没有检查点）。
	 * 硬编一个假的 runId 会让审批记录挂到一个不存在的运行上，
	 * 而那比"没有运行 id"更难排查 —— 后者一眼能看出是外部调用。
	 */
	public static ToolCallContext mcpClient() {
		return new ToolCallContext(null, null, ToolCallOrigin.MCP_CLIENT, null, null, null);
	}

	/** 没有上下文（执行器被单独调用时用）。 */
	public static ToolCallContext none() {
		return new ToolCallContext(null, null, ToolCallOrigin.AGENT_LOOP, null, null, null);
	}

	public boolean hasRun() {
		return runId != null && !runId.isBlank();
	}

	/** 这次调用有没有预先批准的通行证。 */
	public boolean hasGrant() {
		return grant != null;
	}

}
