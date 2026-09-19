package ai.agent.service.tool;

import ai.agent.entity.model.tool.ToolHints;
import ai.agent.entity.tool.ApprovalGrant;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.entity.tool.ToolInvocation;

/**
 * <b>人工确认（HITL）闸门</b> —— 高危写操作在执行之前必须先有人点头。
 *
 * <h2>它在第几道门禁上，以及为什么是这个位置</h2>
 * {@code ToolCallExecutor} 的五道门禁顺序是：
 * <pre>
 *   ①白名单 → ②并发闸门/熔断 → ③JSON 解析 → ④JSR-380 校验 → <b>⑤人工确认</b> → 执行
 * </pre>
 * 审批放在<b>校验之后、执行之前的最后一个位置</b>，两条理由都不是风格问题：
 * <ul>
 *   <li><b>审批必须在参数可信之后</b>。让人去批准一份 {@code {"query": "分块", "topK": "5"}}
 *       是没法批的 —— 人看到的参数要是"这把工具真正会拿去执行的那份"，
 *       否则他批的是 A、执行的是 B（这是审批制度最经典也最严重的失效形态）；</li>
 *   <li><b>审批必须在执行之前</b>，这是它的全部意义。放在工具内部（让工具自己问）
 *       就等于把闸门交给了被监管方 —— 哪天有人新写一把高危工具却忘了问，没人会发现。</li>
 * </ul>
 *
 * <h2>为什么它是接口，而且有一个"全部放行"的默认实现</h2>
 * 与 {@code PerToolGate} / {@code NoopPerToolGate} 同一套做法，理由也一样：
 * 工具自己的单元测试不该被迫起一个人工审批流程，而
 * <b>"审批生效"与"审批不生效"必须走同一段代码</b>（空实现只是把判据换成"永远放行"），
 * 这样就不存在"某条分支从来没被测到"。
 *
 * <h2>被拒绝的那一次，<b>不是</b>工具失败</h2>
 * 这一点在设计上与熔断/限流那两条一致（见 {@code ToolCallStage.countsAsToolFailure}）：
 * 人工拒绝会进熔断窗口的话，<b>人点两次"拒绝"就能把一把健康的工具熔断掉</b>。
 * 归因要按"是谁造成的"来定，而不是按"没成功"来定。
 */
public interface ToolApprovalGate {

	/**
	 * 一次工具调用是否已经获批。
	 *
	 * <h2>为什么"已经批过"要单独一个状态，而不是让门自己记</h2>
	 * 批准发生在<b>上一次 HTTP 请求</b>里，而执行发生在<b>恢复之后的新请求</b>里 ——
	 * 两者之间隔着一个检查点。所以"已获批"这件事必须由调用方显式传进来
	 * （{@code ApprovalGrant}），不能靠门内部的记忆：
	 * 门内部的记忆会随进程重启一起消失，而"人已经批准过"是一个<b>不该随重启消失</b>的事实。
	 *
	 * @param invocation 模型提出的调用意图（已通过白名单/解析/校验）
	 * @param tool 对应的工具
	 * @param context 执行上下文（哪次运行、第几步）；审计用
	 * @return 放行 / 待批 / 拒绝
	 */
	Authorization authorize(ToolInvocation invocation, AgentTool<?> tool, ToolCallContext context);

	/** 门自己说清楚它现在是什么状态（诊断端点与启动日志用）。 */
	default String describe() {
		return getClass().getSimpleName();
	}

	/**
	 * 一次审批判定的结果。
	 *
	 * <p>三个分支对应三种完全不同的后续动作，所以做成 sealed 而不是"一个布尔 + 一个可空 id"：
	 * <ul>
	 *   <li>{@link Allowed} → 继续执行（可能带着审批记录 id，用于把结论挂到那次审批上）；</li>
	 *   <li>{@link Pending} → <b>整个循环暂停</b>，把待批记录交给界面；</li>
	 *   <li>{@link Denied} → 把拒绝原因回填给模型，让它换方案。</li>
	 * </ul>
	 */
	sealed interface Authorization {

		/**
		 * 放行。
		 *
		 * @param approvalId 关联的审批记录 id；<b>免审批时为 null</b>
		 *                   （"没走过审批"与"审批通过了"不是同一件事，
		 *                   都写成 null 会让审计表里查不出"哪些调用曾经被拦下过"）
		 * @param approved 这次放行是不是来自一次<b>真实的人工批准</b>（而不是"不需要审批"）
		 * @param reason <b>为什么不需要审批</b>（只有 {@code approved=false} 时有意义）。
		 *               它是"这次为什么没弹确认"的答案，而这个问题在实际排查里一定会被问到
		 *               （例：人以为"写操作都会弹"，而这一次没有）。
		 *               <p>判据是：{@code "工具本身免审批"} 与 {@code "本次运行的权限档位覆盖了它"}
		 *               是两件不同的事 —— 后者意味着"换一档就会弹"，而前者不会。
		 *               两者都不打断执行，但写进日志的是同一个字符串，所以必须能分开读
		 */
		record Allowed(String approvalId, boolean approved, String reason) implements Authorization {

			/** 不需要审批，且原因是"工具自己的声明说它免审批"（只读工具，或幂等的写）。 */
			public static Allowed notRequired() {
				return new Allowed(null, false, "工具自己的声明是免审批（只读，或幂等的写）");
			}

			/**
			 * 不需要审批，且原因是"<b>本次运行</b>的权限档位覆盖了它"。
			 *
			 * <p>与 {@link #notRequired()} 分开是刻意的：这一条意味着
			 * <b>换一个档位就会弹确认</b>，而 {@code notRequired()} 不会。
			 * 合成一句"不需要审批"之后，"完全权限"这次放宽就只存在于配置里，
			 * 而事后查"这次为什么没弹"会查到一段读不出结论的日志。
			 */
			public static Allowed byRunPermission(String reason) {
				return new Allowed(null, false, reason);
			}

			/** 人工批准过再恢复执行。 */
			public static Allowed preApproved(String approvalId) {
				return new Allowed(approvalId, true, "人工批准过（审批 #" + approvalId + "）");
			}
		}

		/**
		 * <b>需要人工确认，而且现在还没等到</b>。
		 *
		 * <p>返回它意味着"这次调用到此为止，但整个任务<b>没有失败</b>"——
		 * 循环会带着 {@code AgentStopReason.WAITING_FOR_APPROVAL} 暂停并写检查点。
		 * 这与"失败"的区别不是措辞：失败要重试或放弃，而待批只需要有人点一下。
		 *
		 * @param approvalId 审批记录 id —— 界面拿它去调用批准/拒绝端点
		 * @param request 审批请求的可读描述（给界面直接渲染，不用它自己拼）
		 */
		record Pending(String approvalId, ApprovalRequest request) implements Authorization {
		}

		/**
		 * 被拒绝（人工拒绝，或等到超时）。
		 *
		 * @param stage 归到哪个 stage：{@code APPROVAL_DENIED} 与 {@code APPROVAL_TIMEOUT}
		 *              要分开 —— "人不批准"和"没人搭理"对运维意味着完全不同的事
		 *              （前者是业务判断，后者是流程没接上）
		 */
		record Denied(ToolCallStage stage, String reason, String approvalId) implements Authorization {
		}
	}

	/**
	 * 待批请求的可读描述。
	 *
	 * <p>它是<b>给人和给模型同一份</b>的内容：人靠它决定批不批，
	 * 模型靠它在被拒后知道"我提了什么、为什么被拒"。
	 * 两处用同一个对象，就不会出现"界面显示的和模型看到的不一致"。
	 *
	 * @param approvalId 审批 id
	 * @param runId 运行 id（可能为空：执行器被单独调用时）
	 * @param stepIndex 第几步（可能为空，同上传入的上下文）
	 * @param toolName 工具名
	 * @param arguments 参数的原始 JSON（<b>人必须看到真正会被执行的那份参数</b>）
	 * @param hints 风险声明（界面据此显示"破坏性/影响外部"这类提示）
	 * @param summary 一句话说明这次要做什么（来自工具的四段描述里的 summary）
	 * @param permissionNote <b>这次运行被授予的权限</b>（形如
	 *            {@code 仅可查看 · D:\work\demo}）。
	 *            <p>它存在的理由很具体：当"要不要确认"是<b>运行档位</b>决定的而不是工具本身决定的时，
	 *            那个档位就是人做判断的依据之一 —— 不显示的话，
	 *            "这次为什么弹了确认"（明明上次没弹）在页面上没有任何线索。
	 *            <p>空串表示"没有权限上下文"（外部链路 / 本次没有工作区）。
	 *            刻意用空串而不是 null：它进的是审批记录的参数列，
	 *            而那一列已经用空串表示"取不到"（与 {@code ToolApprovalRecord.hints} 同一套规矩）
	 */
	record ApprovalRequest(String approvalId, String runId, Integer stepIndex, String toolName, String arguments,
			ToolHints hints, String summary, String permissionNote) {

		/** 兼容构造器：不带权限说明的审批请求（工具自己的声明要求审批时）。 */
		public ApprovalRequest(String approvalId, String runId, Integer stepIndex, String toolName, String arguments,
				ToolHints hints, String summary) {
			this(approvalId, runId, stepIndex, toolName, arguments, hints, summary, "");
		}

		/** 给界面/日志的一行摘要。 */
		public String describe() {
			return "工具「%s」%s：%s".formatted(toolName, hints.describe(), arguments);
		}
	}

	/**
	 * 全部放行（默认装配）。
	 *
	 * <p>⚠️ <b>它存在这件事本身必须可见</b>：HITL 关掉时，
	 * "高危写操作需要审批"这条约束就只剩文档里的一句话。
	 * 所以这个实现刻意不叫 {@code NoopApprovalGate} 而是强调语义，
	 * 并且 {@link #describe()} 会明确说出"全部放行"，
	 * 让它在诊断端点和启动日志里能被一眼看到 ——
	 * 这与「装上了」与「真在生效」是两件事同族：<b>"我配了保护"与"保护真的装着"是两件事。</b>
	 */
	final class Allow implements ToolApprovalGate {

		private static final Allow INSTANCE = new Allow();

		public static Allow instance() {
			return INSTANCE;
		}

		@Override
		public Authorization authorize(ToolInvocation invocation, AgentTool<?> tool, ToolCallContext context) {
			return Authorization.Allowed.notRequired();
		}

		@Override
		public String describe() {
			return "未启用（全部放行）—— 写操作不会有任何人工确认";
		}
	}

}
