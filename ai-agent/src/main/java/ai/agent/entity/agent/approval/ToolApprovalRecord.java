package ai.agent.entity.agent.approval;

import ai.agent.service.agent.approval.ApprovalStatus;
import ai.agent.service.agent.approval.JdbcToolApprovalStore;
import java.time.Instant;

/**
 * 一条审批记录：<b>「谁、什么时候、对哪一次调用、做了什么决定」</b>。
 *
 * <h2>为什么这一步必须落库，而不是只放在内存里</h2>
 * 内存里的审批状态够用（一个 {@code CompletableFuture} 就能让阻塞的虚拟线程醒来），
 * 但"够用"和"能审计"是两件事，而 HITL 的全部价值在后一半：
 * <ul>
 *   <li>验收要能回答「<b>什么时候让人确认</b>」——
 *       没有记录时，这句话只能靠回忆；</li>
 *   <li>写操作出事之后的第一个问题是"<b>这是谁批的</b>"。内存里那个 future 早就不在了；</li>
 *   <li>审批记录是<b>唯一能证明"闸门真的拦下过东西"</b>的证据。
 *       这一点与「装上了」与「真在生效」是两件事直接相关：那次的教训是
 *       「『已完成』这个状态，只在被实际问过一次『装的是哪个实现』之后才算数」——
 *       审批同理，一张空表既可能表示"从没有过危险操作"，也可能表示"闸门没装"。</li>
 * </ul>
 *
 * <h2>参数原样保存（不脱敏、不截断成摘要）</h2>
 * 给人看的参数必须<b>就是会被执行的那一份</b>。这里刻意不做任何加工：
 * 一旦"展示的参数"与"执行的参数"是两次不同的序列化，
 * 就存在两者不一致的可能 —— 而人批的是他看到的那份。
 * 长度上限由存储层定（{@link JdbcToolApprovalStore} 里的列宽），
 * 且超过时会在记录里留下明确的截断标记，而不是静默截掉。
 *
 * @param id 审批 id（界面拿它调用批准/拒绝端点）
 * @param runId 运行 id；执行器被单独调用时为 {@code null}
 * @param stepIndex 第几步；未知时为 {@code null}
 * @param toolName 工具名
 * @param arguments 参数原始 JSON（<b>人要看到真正会被执行的那份</b>）
 * @param hints 风险声明的可读描述（存字符串而不是枚举：它是展示用的，
 *              而这个字段的演进不该逼着历史数据一起迁移）
 * @param summary 工具自己的一句话摘要（来自四段描述）
 * @param permissionNote <b>这次调用发生时，发起它的那次运行被授予的权限</b>
 *              （形如 {@code 仅可查看 · D:\work\demo}）。
 *              <p>为什么它必须与 {@code hints} 分开存：{@code hints} 回答"这把工具<b>本身</b>
 *              危不危险"（工具作者写的，对所有运行都一样），本字段回答"这一次为什么弹了确认"。
 *              同一把 {@code propose_review} 在"仅可查看"下会弹、在"完全权限"下不会 ——
 *              只看 {@code hints} 分不出这两种情况。
 *              <p>空串 = 没有权限上下文（外部 MCP 客户端那条链路没有"运行"这个对象，
 *              老数据也没有这一列）。**刻意不用 null**：与 {@code hints} 同一条规矩，
 *              空串表示"取不到"，与"某个叫空串的档位"不会混淆
 * @param status 当前状态
 * @param requestedAt 什么时候开始等人
 * @param decidedAt 什么时候有的结论；待批时为 {@code null}
 * @param decidedBy 谁做的决定（人工填写；超时/作废时为 {@code null}）
 * @param decisionNote 决定的说明（拒绝理由 / 批准备注）
 */
public record ToolApprovalRecord(String id, String runId, Integer stepIndex, String toolName, String arguments,
		String hints, String summary, String permissionNote, ApprovalStatus status, Instant requestedAt,
		Instant decidedAt, String decidedBy, String decisionNote) {

	/**
	 * 新建一条待批记录。
	 *
	 * <p>{@code requestedAt} 由调用方给（而不是在这里 {@code Instant.now()}）：
	 * 时间来源只有一处，测试才能构造出确定性的时间线 ——
	 * 一个在对象内部偷偷取当前时间的构造器，会让"超时"这件事没法被断言。
	 */
	public static ToolApprovalRecord pending(String id, String runId, Integer stepIndex, String toolName,
			String arguments, String hints, String summary, String permissionNote, Instant requestedAt) {
		return new ToolApprovalRecord(id, runId, stepIndex, toolName, arguments, hints, summary,
				permissionNote == null ? "" : permissionNote, ApprovalStatus.PENDING, requestedAt, null, null, null);
	}

	/** 不带权限上下文的待批记录（外部链路与老调用点）。 */
	public static ToolApprovalRecord pending(String id, String runId, Integer stepIndex, String toolName,
			String arguments, String hints, String summary, Instant requestedAt) {
		return pending(id, runId, stepIndex, toolName, arguments, hints, summary, "", requestedAt);
	}

	/** 带结论的副本。 */
	public ToolApprovalRecord decided(ApprovalStatus newStatus, Instant at, String by, String note) {
		return new ToolApprovalRecord(id, runId, stepIndex, toolName, arguments, hints, summary, permissionNote,
				newStatus, requestedAt, at, by, note);
	}

	public boolean pending() {
		return status == ApprovalStatus.PENDING;
	}

	/** 等人等了多久（待批时算到 now，已决时算到 decidedAt）。 */
	public long waitedMs(Instant now) {
		Instant end = decidedAt == null ? now : decidedAt;
		return Math.max(0, end.toEpochMilli() - requestedAt.toEpochMilli());
	}

	/** 给日志/界面的一行摘要。权限上下文非空时一并带上 —— 它是"这一次为什么弹了确认"的答案。 */
	public String describe() {
		String base = "#%s %s %s(%s)".formatted(id, status, toolName, hints);
		return hasPermissionNote() ? base + " 权限=" + permissionNote : base;
	}

	/**
	 * 有没有记下运行权限。
	 *
	 * <p>判据写成方法而不是让三处各写一遍 {@code permissionNote != null && !isBlank()}：
	 * 那一列在 DDL 里是 {@code NOT NULL DEFAULT ''}，而记录是通过构造器映射读回来的，
	 * 所以 <b>null 与空串都会出现</b>（老库补列、外部链路），而只判其中一半的写法
	 * 会让一句"（未记录）"在某一处渲染成空白格。
	 */
	public boolean hasPermissionNote() {
		return permissionNote != null && !permissionNote.isBlank();
	}

}
