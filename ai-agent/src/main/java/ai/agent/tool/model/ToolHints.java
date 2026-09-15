package ai.agent.tool.model;

/**
 * 一把工具的<b>风险与副作用声明</b>。
 *
 * <h2>为什么四个字段都用 MCP 的语义命名，而不是自己起名字</h2>
 * MCP 规范里 {@code ToolAnnotations} 定义的四个提示是生态里<b>已经被广泛理解的词汇</b>：
 * <ul>
 *   <li>{@code readOnlyHint} —— 调用它不会修改任何东西；</li>
 *   <li>{@code destructiveHint} —— 它可能造成<b>破坏性</b>更新（只对非只读工具有意义）；</li>
 *   <li>{@code idempotentHint} —— 重复调用同样的参数不会产生额外副作用（只对非只读工具有意义）；</li>
 *   <li>{@code openWorldHint} —— 它会与外部世界的实体交互，影响范围不可枚举。</li>
 * </ul>
 * 沿用这套名字有一个很实际的好处：<b>同一份声明可以同时驱动三件事，而且是逐字一致的</b> ——
 * ① 通过 MCP 暴露给外部客户端时填进 {@code annotations}（让对方的模型也知道风险）；
 * ② 本项目的 HITL 审批判据（见 {@link #requiresApproval()}）；
 * ③ 启动期自检（见 {@code ToolRegistry}，把"自相矛盾的声明"在启动时拦下）。
 *
 * <p>自己另起一套 {@code isWriteTool / isDangerous} 的话，那两套词汇之间的翻译
 * 迟早会漂移，而漂移的表现是"某个写着危险的工具没有被审批拦住"——
 * 而那正是这一层要防的事。
 *
 * <h2>{@link #requiresApproval()} 不是第五个字段，是一条推导</h2>
 * 如果再加一个布尔 {@code requireApproval}，就出现了<b>两个都在表达"这把工具危险吗"</b>的字段：
 * 它们可以互相矛盾（声明只读、却要求审批），而矛盾时怎么判是一个没人会去读的规则。
 * 所以这里让"只读"与"免审批"是同一件事的两面 ——
 * 路线文档的原话就是「高危<b>写</b>操作必须审批」，只读工具不在其列。
 *
 * <p><b>要写又不想每次审批怎么办：{@code idempotentHint = true}</b>。
 * 这条规则不是特例，它是有理由的：同一个<b>幂等</b>的写操作
 * 重复执行不会产生额外副作用，所以"人确认一次"这件事对它的价值最低。
 * 反过来，一个"创建类"的操作既不幂等、又可能被模型重复调用，它就必须走审批 ——
 * 这正是它值得被拦住的原因。
 *
 * <h2>四个提示都是"工具作者的声明"，不是系统验证过的事实</h2>
 * 这一点必须说清楚：本项目做不到"证明某个工具真的只读"。
 * 所以它们是<b>契约</b>：写错了不会有运行时异常，只会让风险判断出错。
 * 能做的两件事是 —— 启动期拦下<b>自相矛盾</b>的声明（只读 + 破坏性、只读 + 不幂等），
 * 以及让声明出现在界面与 MCP 元数据里<b>被人看见</b>。
 * 这与台账里那条结论同族：「『已完成』这个状态，只在被实际问过一次之后才算数」。
 *
 * @param readOnlyHint 调用它不会修改任何东西（只读）
 * @param destructiveHint 可能做破坏性更新。<b>只对非只读工具有意义</b>
 * @param idempotentHint 重复同样参数不会产生额外副作用。<b>只对非只读工具有意义</b>
 * @param openWorldHint 会与外部世界交互（发消息、调外部系统、影响不可枚举）
 */
public record ToolHints(boolean readOnlyHint, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint) {

	/**
	 * 只读工具（默认）。
	 *
	 * <p>默认值是只读，这是一个<b>刻意的保守选择</b>：
	 * 忘了写声明的工具会被当成只读 —— 于是它<b>不会</b>触发审批。
	 * 那看起来是"漏放了危险操作"，但反过来的默认值（默认要求审批）
	 * 会让每一把只读工具都弹一次无意义的确认框，而<b>人很快就开始无条件点"通过"</b>——
	 * 那时审批就从一道闸门退化成一个仪式。
	 * 真正兜住这件事的是另外两处：启动期自检 + 新增写工具时的测试。
	 */
	public static ToolHints readOnly() {
		return new ToolHints(true, false, true, false);
	}

	/** 只读，但要读外部世界（例如查一个外部系统的接口，结果取决于对方的状态）。 */
	public static ToolHints readOnlyOpenWorld() {
		return new ToolHints(true, false, true, true);
	}

	/**
	 * 写操作：<b>非幂等、可能破坏性</b> → 必须人工审批。
	 *
	 * <p>典型形态是"创建一条记录"：模型重试一次就多一条，而且它可能把内容写错。
	 * 这是 HITL 真正要拦的那一类。
	 */
	public static ToolHints writeRequiringApproval() {
		return new ToolHints(false, true, false, true);
	}

	/**
	 * 写操作：<b>幂等</b> → 不需要审批。
	 *
	 * <p>典型形态是"提交/确认一个已经存在的东西"：重复提交会被下游去重，
	 * 语义上等价于"再查一次"。它仍然会改变状态（所以不是只读），
	 * 但"再执行一次"的代价是零，于是人工确认换不来什么。
	 */
	public static ToolHints idempotentWrite() {
		return new ToolHints(false, false, true, true);
	}

	/**
	 * 这次调用要不要先经过人工确认。
	 *
	 * <p>判据只有一条，而且它的<b>顺序</b>是有讲究的：
	 * <ol>
	 *   <li>只读 → 不用审批。这是路线文档「高危<b>写</b>操作必须审批」的直接落地；</li>
	 *   <li>幂等的写 → 不用审批（理由见 {@link #idempotentWrite()}）；</li>
	 *   <li>其余（非幂等的写）→ 必须审批。</li>
	 * </ol>
	 * 注意第 3 条是<b>兜底</b>而不是"看 {@code destructiveHint}"：
	 * 一个既不只读、又不声明幂等的工具，无论它把 {@code destructiveHint} 写成什么
	 * 都要审批。判据写成"看 destructiveHint"的话，
	 * 一个忘了声明破坏性的创建类工具就会静默地免掉审批 ——
	 * 而"忘了声明"是默认情况，不是例外情况。
	 */
	public boolean requiresApproval() {
		return !readOnlyHint && !idempotentHint;
	}

	/** 自相矛盾的声明：只读的东西不可能是破坏性的，也不可能不幂等。 */
	public boolean selfContradictory() {
		return readOnlyHint && (destructiveHint || !idempotentHint);
	}

	/** 给日志/界面的一行说明。 */
	public String describe() {
		if (readOnlyHint) {
			return openWorldHint ? "只读（读外部世界）" : "只读";
		}
		String kind = idempotentHint ? "写（幂等，免审批）" : "写（非幂等，需人工确认）";
		return destructiveHint ? kind + "，可能破坏性" : kind;
	}

}
