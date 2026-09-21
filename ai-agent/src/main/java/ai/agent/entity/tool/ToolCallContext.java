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
 * @param runPermission <b>这一次运行被授予的权限</b>（工作区目录 + 三档之一），
 *              {@code null} 表示没有权限上下文（外部 MCP 链路、执行器被单独调用）。
 *              <p>它为什么必须在上下文里、而不是让闸门自己去查运行：闸门是<b>门禁</b>，
 *              它只该看到"这一次给了多大权"这个事实，不该知道运行记录存在哪张表；
 *              而"这次运行的档位是什么"只有循环（或恢复它的服务层）知道，
 *              与 {@code runId} / {@code stepIndex} 完全同一类事实。
 *              <p>⚠️ 缺失时一律按<b>最严的一档</b>处理（见 {@code WorkspaceApprovalGate}）——
 *              把"没有档位"当成完全权限，会让一条没人审计的链路成为绕过审批的入口。
 * @param arguments <b>这一次调用的原始参数 JSON</b>（模型给的那份，未经校验）。
 *              <p>它为什么必须出现在上下文里：文件工具要判"这次改的东西在不在工作区内"，
 *              而那个判断只能从参数里的路径算出来 —— 而闸门读到的必须是
 *              "真正会执行的那份参数"的结论（见 {@code WorkspaceApprovalGate}）。
 *              让工具自己去别处找参数，就会多出第二条路径判据，两条迟早漂移。
 *              <p>形状是"原始字符串"而不是解析后的对象：上下文是<b>门禁与审计</b>的输入，
 *              它不该依赖每把工具各不相同的参数类型。工具自己按需解析（骨架只读一个字段）。
 */
public record ToolCallContext(String runId, Integer stepIndex, ToolCallOrigin origin, ApprovalGrant grant,
		String approvalId, Duration decisionTimeout, RunPermission runPermission, String arguments) {

	public ToolCallContext {
		origin = origin == null ? ToolCallOrigin.AGENT_LOOP : origin;
	}

	/** Agent 循环里的一步（没有通行证 —— 首次提出调用时必定没有）。 */
	public static ToolCallContext ofStep(String runId, int stepIndex) {
		return new ToolCallContext(runId, stepIndex, ToolCallOrigin.AGENT_LOOP, null, null, null, null, null);
	}

	/** Agent 循环里的一步，且这一次调用带着人工批准的通行证（恢复路径用）。 */
	public static ToolCallContext ofStep(String runId, int stepIndex, ApprovalGrant grant) {
		return new ToolCallContext(runId, stepIndex, ToolCallOrigin.AGENT_LOOP, grant, null, null, null, null);
	}

	/**
	 * 与 {@link #ofStep(String, int)} 相同，但<b>这一次调用最多等这么久</b>。
	 *
	 * <p>存在的理由见 {@link #decisionTimeout}：不同链路该有不同的等待预算，
	 * 而测试需要在不删掉逻辑的前提下把时间刻度改小。
	 */
	public static ToolCallContext ofStep(String runId, int stepIndex, Duration decisionTimeout) {
		return new ToolCallContext(runId, stepIndex, ToolCallOrigin.AGENT_LOOP, null, null, decisionTimeout, null, null);
	}

	/**
	 * 把<b>这一次运行的权限</b>补上 —— 循环在调执行器之前调用它。
	 *
	 * <p>与 {@link #withApproval(String)} 同一条做法（派生一个新对象）。
	 * 权限是与 {@code runId} 同级的"运行级事实"，所以它进的是构造器的最后一位，
	 * 而不是让闸门从别处去取：闸门只该看到"这次给了多大权"。
	 *
	 * @param permission 这次运行的权限；{@code null} 表示"没有权限上下文"
	 *                   （执行器被单独调用、外部链路）—— 那时按最严的一档处理
	 */
	public ToolCallContext withRunPermission(RunPermission permission) {
		return new ToolCallContext(runId, stepIndex, origin, grant, approvalId, decisionTimeout, permission, arguments);
	}

	/**
	 * 把<b>这一次调用的原始参数</b>补上 —— 执行器在解析之前调用它。
	 *
	 * <p>与另外两个 {@code withXxx} 同一条做法（派生一个新对象）。它必须在这里，
	 * 而不是让工具自己去哪找参数：文件工具判边界用的路径来自本次参数，
	 * 而闸门读的必须是<b>同一份</b>参数的结论（见 {@link #arguments}）。
	 *
	 * @param rawArguments 模型给的原始参数 JSON；{@code null} 表示没有参数（无参工具）
	 */
	public ToolCallContext withArguments(String rawArguments) {
		return new ToolCallContext(runId, stepIndex, origin, grant, approvalId, decisionTimeout, runPermission,
				rawArguments);
	}

	/**
	 * 把"这次调用挂在哪条审批上"补上 —— 执行器在门禁 ⑤ 之后调用它。
	 *
	 * <p>做成"派生一个新对象"而不是"设一个字段"：record 不可变正是这里需要的性质，
	 * 因为上下文会被传进工具（{@link AgentTool#onContext}）——
	 * 一个可变对象传进去，工具就能顺手改掉"我是谁批的"。<b>审计字段不该由被审计方改写。</b>
	 */
	public ToolCallContext withApproval(String approvalId) {
		return new ToolCallContext(runId, stepIndex, origin, grant, approvalId, decisionTimeout, runPermission,
				arguments);
	}

	/**
	 * 外部 MCP 客户端的调用。
	 *
	 * <p>刻意<b>不带 runId</b>：这条路没有"运行"这个对象（没有模型、没有循环、没有检查点）。
	 * 硬编一个假的 runId 会让审批记录挂到一个不存在的运行上，
	 * 而那比"没有运行 id"更难排查 —— 后者一眼能看出是外部调用。
	 *
	 * <p>同样刻意<b>不带权限</b>：档位是"一次运行"的属性，这条路没有运行。
	 * 于是它按最严的一档算（写操作要人点头），见 {@code WorkspaceApprovalGate} ——
	 * 放宽必须有一个能被审计的主体，而这里没有。
	 */
	public static ToolCallContext mcpClient() {
		return new ToolCallContext(null, null, ToolCallOrigin.MCP_CLIENT, null, null, null, null, null);
	}

	/** 没有上下文（执行器被单独调用时用）。 */
	public static ToolCallContext none() {
		return new ToolCallContext(null, null, ToolCallOrigin.AGENT_LOOP, null, null, null, null, null);
	}

	public boolean hasRun() {
		return runId != null && !runId.isBlank();
	}

	/** 这次调用有没有预先批准的通行证。 */
	public boolean hasGrant() {
		return grant != null;
	}

}
