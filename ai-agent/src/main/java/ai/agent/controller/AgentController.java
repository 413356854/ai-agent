package ai.agent.controller;

import ai.agent.agent.AgentRun;
import ai.agent.agent.ResumableAgentService;
import ai.agent.agent.limit.AgentConcurrencyLimitsView;
import ai.agent.agent.store.AgentRunRecord;
import ai.agent.agent.store.AgentRunStore;
import ai.agent.agent.store.RunStatus;
import ai.agent.tool.ToolCallOutcome;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 的界面入口（阶段三第 5 件）。
 *
 * <h2>为什么之前 Agent 只有后端没有界面，而这一件不该再拖</h2>
 * 路线文档阶段三的验收写的是：「能讲清「它为什么选这个工具、失败重试几次、
 * 什么时候让人确认、**日志能不能还原执行过程**」」。
 * 前三件（ReAct 循环、持久化恢复、并发保护）做完之后，
 * 这些信息**全部躺在库里**（三张表 + 闸门/熔断快照）——
 * 但**界面上看不见**，等于"能还原"只对会查库的人成立。
 *
 * <p>所以本类的意义不是"加个页面"，而是把已经存在的事实**摆到能被看见的地方**。
 * 这也是为什么这些端点几乎不含业务逻辑：它们只做"读 + 组装"。
 * 一旦这里出现业务判断（比如"失败率算不算高"），那份判断就会与
 * {@code CircuitBreaker} 里的判据**两处维护**，然后慢慢漂移。
 *
 * <h2>端点划分：一条写路径 + 三条只读路径</h2>
 * <table border="1">
 *   <caption>端点与它回答的问题</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code POST /api/agent/ask}</td><td>"跑一个任务，然后告诉我它干了什么"</td></tr>
 *   <tr><td>{@code GET /api/agent/runs}</td><td>"历史上跑了哪些任务、各自什么结局"</td></tr>
 *   <tr><td>{@code GET /api/agent/runs/{id}}</td><td>"这一次到底是怎么跑的"（步骤 + 每次工具调用）</td></tr>
 *   <tr><td>{@code GET /api/agent/limits}</td><td>"四层保护现在什么状态"（并发闸门 / 熔断 / 上限配置）</td></tr>
 * </table>
 *
 * <h2>为什么 {@code /ask} 是 POST 而不是 GET</h2>
 * 与阶段一那个校准端点的 POST 版本同一条理由（踩坑库 #32）：GET 把参数放在 query string 里，
 * 参数一多就会被 URL 长度和转义规则坑到，而且**失败常常是静默的**。
 * 更直接的是：这个端点会**真的调模型花钱**，它不该是一个"能在浏览器地址栏里误触"的路径。
 */
@RestController
@RequestMapping(path = "/api/agent", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentController {

	private static final Logger log = LoggerFactory.getLogger(AgentController.class);

	private final ResumableAgentService service;

	private final AgentRunStore store;

	private final AgentConcurrencyLimitsView limitsView;

	public AgentController(ResumableAgentService service, AgentRunStore store, AgentConcurrencyLimitsView limitsView) {
		this.service = service;
		this.store = store;
		this.limitsView = limitsView;
	}

	/**
	 * 跑一个任务（真实调模型、真实执行工具）。
	 *
	 * <p><b>它是有成本的操作</b>，所以返回体里必须带上成本（token）与过程（每步、每次工具调用），
	 * 否则用户只能看到一个答案，而不知道这个答案花了多少钱、绕了几圈。
	 */
	@PostMapping(path = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> ask(@RequestBody AskRequest request) {
		String question = request == null || request.question() == null ? "" : request.question().strip();
		if (question.isEmpty()) {
			return Map.of("ok", false, "error", "问题不能为空");
		}

		String runId = java.util.UUID.randomUUID().toString();
		AgentRun run = service.runWithId(runId, question);
		return assemble(runId, run);
	}

	/** 历史运行列表（最近的在前）。 */
	@GetMapping("/runs")
	public Map<String, Object> runs(@RequestParam(defaultValue = "20") int limit) {
		List<AgentRunRecord> records = service.recentRuns(Math.max(1, Math.min(limit, 100)));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (AgentRunRecord record : records) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", record.id());
			row.put("question", record.question());
			row.put("status", record.status().name());
			row.put("stopReason", record.stopReason() == null ? null : record.stopReason().name());
			row.put("failureCause", record.failureCause() == null ? null : record.failureCause().name());
			row.put("totalTokens", record.totalTokens());
			row.put("toolCalls", record.toolCallCount());
			row.put("elapsedMs", record.elapsedMs());
			row.put("createdAt", record.createdAt() == null ? null : record.createdAt().toString());
			row.put("node", record.node());
			rows.add(row);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runs", rows);
		body.put("count", rows.size());
		body.put("awaitingRecovery", records.stream().filter(r -> r.status() == RunStatus.INTERRUPTED).count());
		return body;
	}

	/**
	 * 一次运行的完整过程。
	 *
	 * <p>{@code steps} 与 {@code toolCalls} 必须分开给：
	 * 前者是"模型每一轮说了什么、要调什么"，后者是"每次调用走到了哪一步"。
	 * 合成一个嵌套结构会让界面很难回答"这一步调了几次、几次被拒"，
	 * 而那正是这一页最想展示的东西。
	 */
	@GetMapping("/runs/{runId}")
	public Map<String, Object> runDetail(@PathVariable String runId) {
		var record = store.find(runId);
		if (record.isEmpty()) {
			return Map.of("ok", false, "error", "没有这个运行：" + runId);
		}
		AgentRunRecord run = record.get();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("run", Map.ofEntries(
				Map.entry("id", run.id()),
				Map.entry("question", run.question()),
				Map.entry("status", run.status().name()),
				Map.entry("stopReason", run.stopReason() == null ? "" : run.stopReason().name()),
				Map.entry("failureCause", run.failureCause() == null ? "" : run.failureCause().name()),
				Map.entry("failureAdvice", run.failureAdvice() == null ? "" : run.failureAdvice()),
				Map.entry("finalAnswer", run.finalAnswer() == null ? "" : run.finalAnswer()),
				Map.entry("totalTokens", run.totalTokens()),
				Map.entry("toolCalls", run.toolCallCount()),
				Map.entry("elapsedMs", run.elapsedMs()),
				Map.entry("node", run.node() == null ? "" : run.node())));

		List<Map<String, Object>> steps = new ArrayList<>();
		for (AgentRunRecord.StepCheckpoint step : store.stepsOf(runId)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("stepIndex", step.stepIndex());
			row.put("thoughtText", step.thoughtText() == null ? "" : step.thoughtText());
			row.put("promptTokens", step.promptTokens());
			row.put("completionTokens", step.completionTokens());
			row.put("elapsedMs", step.elapsedMs());
			// 检查点里存的是完整对话，可能很大 —— 列表里只给长度，
			// 让页面知道"这一步有上下文"，但不把几万字符塞进列表响应
			row.put("transcriptChars", step.transcriptJson() == null ? 0 : step.transcriptJson().length());
			steps.add(row);
		}
		body.put("steps", steps);

		List<Map<String, Object>> toolCalls = new ArrayList<>();
		for (AgentRunRecord.ToolCallRecord call : store.toolCallsOf(runId)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("stepIndex", call.stepIndex());
			row.put("callIndex", call.callIndex());
			row.put("callId", call.callId() == null ? "" : call.callId());
			row.put("toolName", call.toolName());
			row.put("arguments", call.arguments() == null ? "" : call.arguments());
			row.put("stage", call.stage());
			row.put("feedback", call.feedback() == null ? "" : call.feedback());
			row.put("elapsedMs", call.elapsedMs());
			// 这个布尔是**页面着色的依据**：同一个 stage 在不同页面上的"好坏"不同，
			// 而判断逻辑只能有一份 —— 放在后端，页面不做第二份判断
			row.put("countsAsToolFailure", stageCountsAsFailure(call.stage()));
			toolCalls.add(row);
		}
		body.put("toolCalls", toolCalls);
		return body;
	}

	/**
	 * 四层保护的当前状态。
	 *
	 * <p>为什么把它和运行列表分开：这一页回答的是"系统现在健康吗"，
	 * 而不是"某个任务怎么样了"。混在一起会让人以为熔断状态是某次运行的属性 ——
	 * 而它是<b>全局共享</b>的（同一把工具被所有任务共用）。
	 */
	@GetMapping("/limits")
	public Map<String, Object> limits() {
		return limitsView.snapshot();
	}

	/** 把一次运行组装成界面要的形状。 */
	private Map<String, Object> assemble(String runId, AgentRun run) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("runId", runId);
		body.put("question", run.question());
		body.put("answered", run.answered());
		body.put("finalAnswer", run.finalAnswer() == null ? "" : run.finalAnswer());
		body.put("stopReason", run.stopReason().name());
		body.put("failureCause", run.failureCause() == null ? "" : run.failureCause().name());
		body.put("failureAdvice", run.failureAdvice() == null ? "" : run.failureAdvice());
		body.put("totalTokens", run.totalTokens());
		body.put("elapsedMs", run.elapsedMs());
		body.put("toolCallCount", run.toolCallCount());
		body.put("toolCallSummary", ToolCallOutcome.summarize(run.allOutcomes()));
		body.put("stepSummaries", run.stepSummaries());

		List<Map<String, Object>> steps = new ArrayList<>();
		for (AgentRun.Step step : run.steps()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("index", step.index());
			row.put("thoughtText", step.thoughtText() == null ? "" : step.thoughtText());
			row.put("promptTokens", step.promptTokens());
			row.put("completionTokens", step.completionTokens());
			row.put("elapsedMs", step.elapsedMs());
			row.put("usedTools", step.usedTools());
			List<Map<String, Object>> calls = new ArrayList<>();
			for (ToolCallOutcome outcome : step.outcomes()) {
				Map<String, Object> call = new LinkedHashMap<>();
				call.put("toolName", outcome.invocation().name());
				call.put("arguments", outcome.invocation().argumentsForFeedback(400));
				call.put("stage", outcome.stage().name());
				call.put("elapsedMs", outcome.elapsedMs());
				call.put("fixableByModel", outcome.fixableByModel());
				call.put("countsAsToolFailure", stageCountsAsFailure(outcome.stage().name()));
				call.put("feedback", outcome.feedback());
				calls.add(call);
			}
			row.put("toolCalls", calls);
			steps.add(row);
		}
		body.put("steps", steps);
		log.info("Agent 界面调用完成 runId={} stopReason={} tokens={} tools={}", runId, run.stopReason(),
				run.totalTokens(), run.toolCallCount());
		return body;
	}

	private static boolean stageCountsAsFailure(String stageName) {
		try {
			return ai.agent.tool.ToolCallStage.valueOf(stageName).countsAsToolFailure();
		}
		catch (IllegalArgumentException e) {
			// 库里存的是历史字符串，而枚举可能变过。**返回 false 而不是抛**：
			// 一个老记录不该让整个详情页打不开；而 false 的语义是"不算失败"，
			// 对未知 stage 是保守的（不会因为一条读不懂的记录就把工具标成坏）
			log.warn("库里有一条无法识别的工具调用 stage：{}", stageName);
			return false;
		}
	}

	/** 请求体：问题走 body（不进 URL），理由见类注释。 */
	public record AskRequest(String question) {
	}

}
