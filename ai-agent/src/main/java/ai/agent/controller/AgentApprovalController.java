package ai.agent.controller;

import ai.agent.service.agent.ResumableAgentService;
import ai.agent.service.agent.approval.AgentApprovalService;
import ai.agent.service.agent.approval.ApprovalStatus;
import ai.agent.service.agent.approval.ToolApprovalRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>人工确认（HITL）的界面入口</b> —— 阶段三第 9 件。
 *
 * <h2>它回答的那句验收原话</h2>
 * 路线文档阶段三的验收写的是：「能讲清「它为什么选这个工具、失败重试几次、
 * <b>什么时候让人确认</b>、日志能不能还原执行过程」」。
 * 前两件在第八轮的 Agent 界面里已经能看见，而"什么时候让人确认"这一条
 * <b>在写操作出现之前根本没东西可看</b>（当时三把工具全是只读的）——
 * 这也是台账里把 HITL 与"写工具"绑在一起、要求一起做的原因。
 *
 * <h2>三个端点，对应三件不同的事</h2>
 * <table border="1">
 *   <caption>端点与它回答的问题</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/agent/approvals}</td><td>"现在有什么在等我批"</td></tr>
 *   <tr><td>{@code POST /api/agent/approvals/{id}/decide}</td><td>"我批 / 我不批"</td></tr>
 *   <tr><td>{@code GET /api/agent/runs/{id}/approvals}</td><td>"这一次运行里，人一共被问过几次、结果如何"</td></tr>
 * </table>
 * 第三个端点刻意挂在"运行详情"下面而不是并进待办列表：前者是<b>历史</b>
 * （这次任务的过程里有过哪些人工干预），后者是<b>当下的待办</b>。
 * 混在一个列表里，人分不清"这条是要我处理的"还是"这条是已经处理过的"。
 *
 * <h2>为什么决定是一个 POST 带 body，而不是两个端点（approve / deny）</h2>
 * 因为"批"与"拒"共享的全部参数（谁做的决定、理由）是一样的，
 * 而拆成两个端点会让"理由"这个字段在其中一个上显得可有可无 ——
 * 实际上它对拒绝是<b>必需</b>的（那是模型换方案的唯一依据）。
 * 一个端点 + 一个显式的 {@code approve} 字段，让"这是一个二选一"这件事在接口上就成立。
 */
@RestController
@RequestMapping(path = "/api/agent", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentApprovalController {

	private static final Logger log = LoggerFactory.getLogger(AgentApprovalController.class);

	private final AgentApprovalService approvals;

	private final ResumableAgentService agentService;

	public AgentApprovalController(AgentApprovalService approvals, ResumableAgentService agentService) {
		this.approvals = approvals;
		this.agentService = agentService;
	}

	/**
	 * 现在有哪些写操作在等人确认。
	 *
	 * <p>返回体里带上 {@code enabled} 与 {@code note}：<b>一个空的待办列表有两种含义</b> ——
	 * "确实没有危险操作"与"审批功能压根没开"。不说明的话，
	 * 界面上的空列表看起来像一切正常，而那正是踩坑 #67 的形状
	 * （「『已完成』这个状态，只在被实际问过一次『装的是哪个实现』之后才算数」）。
	 */
	@GetMapping("/approvals")
	public Map<String, Object> pending() {
		List<ToolApprovalRecord> records = approvals.pending();
		Instant now = Instant.now();

		List<Map<String, Object>> rows = new ArrayList<>();
		for (ToolApprovalRecord record : records) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", record.id());
			row.put("runId", record.runId() == null ? "" : record.runId());
			row.put("stepIndex", record.stepIndex() == null ? -1 : record.stepIndex());
			row.put("toolName", record.toolName());
			// 参数原样给出：人要根据它决定批不批。**不做任何加工**，
			// 否则他批的就不是真正会被执行的那一份（见 ToolApprovalRecord 的注释）
			row.put("arguments", record.arguments() == null ? "" : record.arguments());
			row.put("hints", record.hints() == null ? "" : record.hints());
			row.put("summary", record.summary() == null ? "" : record.summary());
			row.put("requestedAt", record.requestedAt().toString());
			row.put("waitedMs", record.waitedMs(now));
			rows.add(row);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("pending", rows);
		body.put("count", rows.size());
		body.put("enabled", approvals.properties().enabled());
		body.put("timeoutSeconds", approvals.properties().timeout().toSeconds());
		body.put("note", approvals.describe());
		body.put("howToDecide", "POST /api/agent/approvals/{id}/decide  body: "
				+ "{\"approve\":true|false,\"decidedBy\":\"你的名字\",\"note\":\"理由（拒绝时必填）\"}");
		return body;
	}

	/**
	 * 批准或拒绝。
	 *
	 * <h2>返回体里最重要的那个字段是 {@code resumed}</h2>
	 * 它回答"批完之后任务继续跑了吗"，而它有三种取值，对应三种完全不同的处境：
	 * <ul>
	 *   <li>{@code true} —— 有线程正在等它，任务<b>当场继续跑完</b>（正常路径，
	 *       发起请求的那个 HTTP 连接还开着）；</li>
	 *   <li>{@code false} + {@code runId} 非空 —— 决定已记录，但没人等（进程重启过、
	 *       或客户端早就断开）。任务停在库里，<b>需要显式恢复</b>；
	 *       本方法<b>顺手就恢复了</b>（见下面的实现），所以这个取值只在恢复失败时出现；</li>
	 *   <li>{@code false} + {@code runId} 为空 —— 这次审批来自外部 MCP 客户端那条链路，
	 *       它没有"运行"这个对象，所以不存在"继续跑"这件事。</li>
	 * </ul>
	 * 把这三种压成一个布尔或一句"操作成功"，界面就只能显示"已处理"——
	 * 而"已处理但任务还停着"是最容易让人以为一切正常的状态。
	 */
	@PostMapping(path = "/approvals/{approvalId}/decide", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> decide(@PathVariable String approvalId, @RequestBody DecideRequest request) {
		if (request == null) {
			return Map.of("ok", false, "error", "缺少请求体");
		}

		AgentApprovalService.Decision decision;
		try {
			decision = approvals.decide(approvalId, request.approve(), request.decidedBy(), request.note());
		}
		catch (IllegalArgumentException | IllegalStateException e) {
			// 这两种是"调用方的问题"（id 不存在 / 已经决定过了），把它们原样告诉界面。
			// 不写成 500：一个"你点晚了"的竞争不是服务器故障
			return Map.of("ok", false, "error", e.getMessage());
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", decision.accepted());
		body.put("approvalId", approvalId);
		body.put("status", decision.status().name());
		body.put("awakened", decision.awakened());
		body.put("resumed", false);
		if (!decision.accepted()) {
			body.put("error", decision.note());
			return body;
		}

		// ---- 没人等在等：这一步由服务层接着把任务跑完 ----
		//
		// 【为什么这里要主动恢复，而不是让前端再调一次恢复端点】
		// 因为"人点了批准"与"任务继续"是同一件事的两半。中间插一次多余的调用，
		// 就会出现"点了批准、界面显示成功、任务却一直停着"的状态 ——
		// 而那与"批准根本没生效"在使用者眼里完全一样。
		//
		// 只对 APPROVED 恢复：拒绝/超时之后**不该**自动继续 ——
		// 那需要模型重新规划，而"重新规划"该由人来决定要不要再花一次钱
		// （与本项目 `ai.agent.store.auto-resume-on-startup=false` 的取向一致：
		// 花钱的动作要显式触发）
		if (decision.status() == ApprovalStatus.APPROVED && !decision.awakened()) {
			String runId = approvals.find(approvalId).map(ToolApprovalRecord::runId).orElse(null);
			if (runId != null && !runId.isBlank()) {
				try {
					ResumableAgentService.ResumeResult result = agentService.resumeAfterApproval(runId, approvalId);
					body.put("resumed", result.resumed());
					if (result.run() != null) {
						body.put("stopReason", result.run().stopReason().name());
						body.put("finalAnswer", result.run().finalAnswer() == null ? "" : result.run().finalAnswer());
						body.put("totalTokens", result.run().totalTokens());
					}
					log.info("审批 #{} 生效后接着跑运行 {}：{}", approvalId, runId, result.outcome());
				}
				catch (RuntimeException e) {
					// 恢复失败**不改写"批准成功"这个事实**：决定已经落库、审计是完整的。
					// 把失败原因单独返回，让人知道"批了，但任务没能继续"
					log.error("审批 #{} 生效后恢复运行 {} 失败", approvalId, runId, e);
					body.put("resumeError", e.getMessage());
					body.put("note", "审批已记录，但运行没能继续（见 resumeError）—— 它仍是待恢复状态");
				}
			}
			else {
				body.put("note", "这次审批没有关联的运行（来自外部 MCP 客户端那条链路），所以没有需要继续的任务");
			}
		}
		return body;
	}

	/** 某次运行里经历过的全部人工确认（历史，不是待办）。 */
	@GetMapping("/runs/{runId}/approvals")
	public Map<String, Object> ofRun(@PathVariable String runId) {
		List<ToolApprovalRecord> records = approvals.ofRun(runId);
		Instant now = Instant.now();

		List<Map<String, Object>> rows = new ArrayList<>();
		for (ToolApprovalRecord record : records) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", record.id());
			row.put("stepIndex", record.stepIndex() == null ? -1 : record.stepIndex());
			row.put("toolName", record.toolName());
			row.put("arguments", record.arguments() == null ? "" : record.arguments());
			row.put("status", record.status().name());
			row.put("pending", record.pending());
			row.put("requestedAt", record.requestedAt().toString());
			row.put("decidedAt", record.decidedAt() == null ? "" : record.decidedAt().toString());
			row.put("decidedBy", record.decidedBy() == null ? "" : record.decidedBy());
			row.put("decisionNote", record.decisionNote() == null ? "" : record.decisionNote());
			// 等了多久：它是"审批有没有拖慢任务"的唯一量化依据。
			// 待批的那条算到此刻，已决的算到决定那一刻
			row.put("waitedMs", record.waitedMs(now));
			rows.add(row);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("approvals", rows);
		body.put("count", rows.size());
		body.put("pending", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("pending"))).count());
		return body;
	}

	/**
	 * 决定请求体。
	 *
	 * <p>{@code approve} 是原始 {@code boolean}：不给默认值。
	 * 一个"缺字段就当批准"的默认值，会让一次漏传字段的调用
	 * <b>静默地放行一个写操作</b> —— 而这是这一层最不能有的默认值。
	 * 缺字段时 Jackson 会按 {@code false} 处理（拒绝），那个方向是安全的。
	 */
	public record DecideRequest(boolean approve, String decidedBy, String note) {
	}

	/** 供测试与界面自检：审批功能的当前描述。 */
	@GetMapping("/approvals/status")
	public Map<String, Object> status(@RequestParam(defaultValue = "false") boolean includeRecent) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("enabled", approvals.properties().enabled());
		body.put("timeoutSeconds", approvals.properties().timeout().toSeconds());
		body.put("defaultDecider", approvals.properties().defaultDecider());
		body.put("pendingCount", approvals.pending().size());
		body.put("describe", approvals.describe());
		if (includeRecent) {
			body.put("recent", approvals.recent(20).stream().map(ToolApprovalRecord::describe).toList());
		}
		return body;
	}

}
