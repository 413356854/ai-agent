package ai.agent.review;

import java.time.Instant;

/**
 * 一条待人工处理的审查工单。
 *
 * <p>它是本项目选定的"<b>写操作</b>"：一次调用会在外部留下一条不可逆的记录
 * （多一条工单），所以它正是人工确认（HITL）该拦的那一类。
 * 路线文档对 HITL 的要求里有一句关键的前提 ——
 * 「⚠️ 现在三把工具全是只读的，这条没有真实场景可验证：要做出可信的样子，
 * 必须先有至少一个『写』工具」。这个记录类型就是那个"写"的落点。
 *
 * <h2>为什么字段里有 {@code createdBy} 与 {@code approvalId}</h2>
 * 写操作出事之后的第一个问题永远是"<b>这是谁批的</b>"。
 * 把审批 id 一起写进工单本身（而不是只留在审批表里），
 * 意味着可以从"一条不该存在的工单"直接倒查到"哪一次审批放行了它"——
 * 不需要去比对时间戳、也不需要假设两边时钟一致。
 *
 * @param id 工单 id
 * @param title 标题
 * @param detail 详情
 * @param severity 严重级别（LOW / MEDIUM / HIGH）
 * @param createdAt 创建时间
 * @param createdBy 发起方标识（模型 / 外部 MCP 客户端）
 * @param approvalId 放行这次写操作的审批 id；未走审批时为 {@code null}
 * @param deduplicated 这次创建是不是被去重了（同标题同级别在窗口内已存在）
 */
public record ReviewTicket(String id, String title, String detail, ReviewSeverity severity, Instant createdAt,
		String createdBy, String approvalId, boolean deduplicated) {

	/** 幂等窗口内命中已有工单时返回的副本。 */
	public ReviewTicket asDuplicate() {
		return new ReviewTicket(id, title, detail, severity, createdAt, createdBy, approvalId, true);
	}

}
