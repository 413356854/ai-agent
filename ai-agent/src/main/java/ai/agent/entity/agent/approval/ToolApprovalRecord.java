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
 * @param status 当前状态
 * @param requestedAt 什么时候开始等人
 * @param decidedAt 什么时候有的结论；待批时为 {@code null}
 * @param decidedBy 谁做的决定（人工填写；超时/作废时为 {@code null}）
 * @param decisionNote 决定的说明（拒绝理由 / 批准备注）
 */
public record ToolApprovalRecord(String id, String runId, Integer stepIndex, String toolName, String arguments,
		String hints, String summary, ApprovalStatus status, Instant requestedAt, Instant decidedAt, String decidedBy,
		String decisionNote) {

	/**
	 * 新建一条待批记录。
	 *
	 * <p>{@code requestedAt} 由调用方给（而不是在这里 {@code Instant.now()}）：
	 * 时间来源只有一处，测试才能构造出确定性的时间线 ——
	 * 一个在对象内部偷偷取当前时间的构造器，会让"超时"这件事没法被断言。
	 */
	public static ToolApprovalRecord pending(String id, String runId, Integer stepIndex, String toolName,
			String arguments, String hints, String summary, Instant requestedAt) {
		return new ToolApprovalRecord(id, runId, stepIndex, toolName, arguments, hints, summary, ApprovalStatus.PENDING,
				requestedAt, null, null, null);
	}

	/** 带结论的副本。 */
	public ToolApprovalRecord decided(ApprovalStatus newStatus, Instant at, String by, String note) {
		return new ToolApprovalRecord(id, runId, stepIndex, toolName, arguments, hints, summary, newStatus, requestedAt,
				at, by, note);
	}

	public boolean pending() {
		return status == ApprovalStatus.PENDING;
	}

	/** 等人等了多久（待批时算到 now，已决时算到 decidedAt）。 */
	public long waitedMs(Instant now) {
		Instant end = decidedAt == null ? now : decidedAt;
		return Math.max(0, end.toEpochMilli() - requestedAt.toEpochMilli());
	}

	/** 给日志/界面的一行摘要。 */
	public String describe() {
		return "#%s %s %s(%s)".formatted(id, status, toolName, hints);
	}

}
