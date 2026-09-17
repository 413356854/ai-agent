package ai.agent.service.tool;

/**
 * 人工审批的记账（一次待批 / 批准 / 拒绝 / 超时就是一条记录）。
 *
 * <h2>为什么放最后一位、而且默认为 {@code null}</h2>
 * 与 {@code AgentRun.failureCause} 同一个理由：这个字段是<b>后加的</b>，
 * 已有的构造调用（十来个测试里那些）不该为了它全部改一遍。
 * 而它<b>只对写操作非空</b> —— 只读工具的调用没有审批这回事，
 * 那里写一个空串或 0 反而是"这里好像有过审批"的误导。
 *
 * <p>它存在的用途很具体：把「人工决定了什么」与「工具实际做了什么」连起来。
 * 没有它，审计表里只有一堆各自独立的审批记录与工具调用记录，
 * 而"这次写操作是哪个人批准的那一次"只能靠时间去猜。
 *
 * @param approvalId 审批记录 id；不需要审批时为 {@code null}
 * @param approvedByHuman 这次执行是不是经过了一次<b>真实的人工批准</b>
 */
public record ToolApprovalRef(String approvalId, boolean approvedByHuman) {

	/** 不需要审批（只读或幂等写）。 */
	public static ToolApprovalRef none() {
		return new ToolApprovalRef(null, false);
	}

	public static ToolApprovalRef of(String approvalId) {
		return new ToolApprovalRef(approvalId, approvalId != null);
	}

	public boolean present() {
		return approvalId != null && !approvalId.isBlank();
	}

}
