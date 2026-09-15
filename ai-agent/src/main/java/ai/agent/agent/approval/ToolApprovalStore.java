package ai.agent.agent.approval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 人工审批记录的存储契约。
 *
 * <h2>为什么它必须是一个接口（而不是直接写 JDBC）</h2>
 * 与 {@link ai.agent.agent.store.AgentRunStore} 同一套理由，而且这里更强：
 * <ul>
 *   <li><b>"等一个人"这件事在测试里不能真的等</b>。测试要能确定性地造出
 *       "有一条待批"、"它刚被批准"、"它超时了"三种状态 ——
 *       而这三件事在真实实现里都要靠时间与人为动作；</li>
 *   <li><b>审批是审计数据，它的实现形态会变</b>：现在跟运行 trace 挤在同一个 H2 里，
 *       生产上它更可能进一张独立的审计表或一个 SIEM。
 *       替换面收在一个类里，是这个接口最实际的价值。</li>
 * </ul>
 *
 * @see JdbcToolApprovalStore
 */
public interface ToolApprovalStore {

	/** 登记一条待批记录。 */
	void save(ToolApprovalRecord record);

	/** 按 id 读一条。 */
	Optional<ToolApprovalRecord> find(String id);

	/**
	 * 写结论。
	 *
	 * <p>返回 false 表示<b>这次写没有生效</b>（记录已经是终态、或压根不存在）。
	 * 这个返回值不是装饰：并发的"人工决定"与"超时"会同时想写同一条记录，
	 * 而**只有第一个写入才该生效** —— 否则一条已经被人工拒绝的记录
	 * 会被随后到达的超时覆盖成 TIMED_OUT，审计时看起来像"没人处理"。
	 * 这与 {@code ConcurrencyGate.release} 那条"拿到才还"是同一类不变式：
	 * <b>状态的迁移只能发生一次，而它必须是可断言的。</b>
	 */
	boolean decide(String id, ApprovalStatus status, Instant at, String decidedBy, String note);

	/** 还没人处理的待批（按请求时间升序 —— 先来的先处理）。 */
	List<ToolApprovalRecord> pending();

	/** 某次运行的全部审批记录（按请求时间升序）—— 页面还原过程用。 */
	List<ToolApprovalRecord> ofRun(String runId);

	/** 最近的若干条（不限状态）—— 排查用。 */
	List<ToolApprovalRecord> recent(int limit);

}
