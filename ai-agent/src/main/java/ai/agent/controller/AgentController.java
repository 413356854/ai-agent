package ai.agent.controller;

import ai.agent.entity.agent.AgentRun;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.ResumableAgentService;
import ai.agent.service.agent.approval.AgentApprovalService;
import ai.agent.service.agent.limit.AgentConcurrencyLimitsView;
import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.service.agent.store.RunStatus;
import ai.agent.service.agent.trace.AgentTraceView;
import ai.agent.config.AgentApprovalProperties;
import ai.agent.entity.tool.ToolCallOutcome;
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
 * 阶段三的验收写的是：「能讲清「它为什么选这个工具、失败重试几次、
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
 * 与阶段一那个校准端点的 POST 版本同一条理由（静默失败的样本会被当成数据）：GET 把参数放在 query string 里，
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

	/**
	 * 人工审批（HITL）。
	 *
	 * <p>界面这一层读它<b>只读不写</b>：待办与决定都在 {@link AgentApprovalController}。
	 * 之所以这里也要读，是因为"这次运行现在在等什么"属于<b>运行详情</b>的一部分 ——
	 * 让人在运行页面上直接看到"它在等第 3 步那次写操作被批准"，
	 * 而不是跳去另一个列表里对着 id 找。
	 */
	private final AgentApprovalService approvals;

	/** 审批时限（秒），给界面显示"还剩多久就超时"。 */
	private final long approvalTimeoutSeconds;

	/**
	 * 五层 trace 的装配（阶段四第 2 件）。
	 *
	 * <p>本类只把它转出去，不做任何装配：那一层要读五张表、要算金额、要对齐会话，
	 * 放在控制器里会让这个"只做读+组装"的类变成一个有业务判断的类 ——
	 * 而类注释里写的第一条纪律正是"一旦这里出现业务判断，它就会与别处两处维护"。
	 */
	private final AgentTraceView traceView;

	public AgentController(ResumableAgentService service, AgentRunStore store, AgentConcurrencyLimitsView limitsView,
			AgentApprovalService approvals, AgentApprovalProperties approvalProperties, AgentTraceView traceView) {
		this.service = service;
		this.store = store;
		this.limitsView = limitsView;
		this.approvals = approvals;
		this.approvalTimeoutSeconds = approvalProperties.timeout().toSeconds();
		this.traceView = traceView;
	}

	/**
	 * 跑一个任务（真实调模型、真实执行工具）。
	 *
	 * <p><b>它是有成本的操作</b>，所以返回体里必须带上成本（token）与过程（每步、每次工具调用），
	 * 否则用户只能看到一个答案，而不知道这个答案花了多少钱、绕了几圈。
	 *
	 * <h2>返回值里多了一种结局：{@code waitingApproval}</h2>
	 * 任务可能<b>停在一次需要人工确认的写操作上</b>（HITL）。
	 * 它不是失败，也不该被渲染成"完成了" —— 所以这里显式给出
	 * {@code waitingApproval} 与待批的 id，让界面能直接弹出"批准 / 拒绝"两个按钮。
	 *
	 * <p>为什么不在这里阻塞等待人的决定（那也是一个能跑的方案）：
	 * 因为"跑一个任务"与"处理一次待办"是<b>两个不同的交互</b>，
	 * 而把它们叠在一个长连接里会让"用户关掉页面"直接等于"这次写操作被放弃"——
	 * 那是把审批的生命周期绑在了浏览器标签页上。
	 * 现在的做法是：任务明确地停在库里，人从待办列表去处理（见
	 * {@code /api/agent/approvals}），两边各自独立。
	 */
	@PostMapping(path = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> ask(@RequestBody AskRequest request) {
		String question = request == null || request.question() == null ? "" : request.question().strip();
		if (question.isEmpty()) {
			return Map.of("ok", false, "error", "问题不能为空");
		}

		String runId = java.util.UUID.randomUUID().toString();
		// 【阶段四成本看板】把业务归因键一起落库。
		//
		// 为什么在**这一刻**就写它（而不是跑完再补）：见 AgentRunStore.start 的注释 ——
		// 失败与中断的运行同样花了钱，而它们恰恰最需要按业务去看。
		// 补写式的设计会把它们全落进"未标注"。
		//
		// 这里做长度裁剪（64 字符，与库里的列宽一致）：**不裁的话**，
		// 一个超长的归因键会在写库那一刻抛 SQL 异常 —— 于是"用户多打了几个字"
		// 变成"这次任务直接失败"，而那个失败看起来像存储出了问题。
		// 裁剪是这里唯一合理的处理：归因键是给人看的标签，不是数据本体
		String intent = clamp(request == null ? null : request.intent(), MAX_INTENT_CHARS);
		// 【阶段四第 2 件：Agent trace 的第一层】会话 id。
		//
		// 与归因键同一条理由，而且更硬：它是**调用方的输入**，在这次运行存在之前就定了；
		// 等结束再补的写法在"中途失败/被中断"的运行上补不上 ——
		// 而"这一段对话里哪几次跑挂了"恰恰是靠它才问得出来的问题。
		//
		// 长度裁剪同样是必需的，理由与归因键一字不差（超长 → 写库抛 SQL 异常 →
		// 用户的几个字变成"这次任务失败了"）
		String conversationId = clamp(request == null ? null : request.conversationId(), MAX_CONVERSATION_CHARS);
		// 【阶段四第 6 件：成本归因的第二个维度】租户。
		//
		// 与归因键是**两列、两个维度**，不是"一个字段存三样东西"：
		// tenant 回答"谁在花钱"，intent 回答"花在哪个功能上"。
		// 拼成一个字符串（`京东-客服问答`）会有两个具体后果：
		//   ① "按租户看一共多少"要靠 LIKE 前缀匹配 —— 一次拼写不一致就让同一件事裂成两行；
		//   ② 租户改名或功能换归属都要改历史数据，而历史是只追加的。
		//
		// 长度裁剪同一条理由（超长 → 写库抛 SQL 异常 → 用户的几个字变成"这次任务失败了"）
		String tenantId = clamp(request == null ? null : request.tenantId(), MAX_TENANT_CHARS);
		AgentRun run = service.runWithId(runId, question, intent, conversationId, tenantId);
		return assemble(runId, run);
	}

	/** 归因键的最大长度（与 {@code agent_run.intent} 的列宽一致，改一处要一起改）。 */
	private static final int MAX_INTENT_CHARS = 64;

	/**
	 * 租户的最大长度（与 {@code agent_run.tenant_id} 的列宽一致）。
	 *
	 * <p>与归因键同一个数、同一条理由 —— 而<b>不共用一个常量</b>：
	 * 两个列宽将来真的可能不同（租户名往往是全称，归因键是短标签），
	 * 共用一个常量会让"改了一列"变成"悄悄改了另一列的约束"。
	 */
	private static final int MAX_TENANT_CHARS = 64;

	/**
	 * 会话 id 的最大长度（与 {@code agent_run.conversation_id} 的列宽一致）。
	 *
	 * <p>界面上生成的是一个 UUID（36 字符），所以 64 对正常调用方绰绰有余 ——
	 * 裁剪只对"外部系统自己拼了一个超长 id"生效。
	 */
	private static final int MAX_CONVERSATION_CHARS = 64;

	/**
	 * 裁剪到列宽，空白归一成 {@code null}。
	 *
	 * <p>抽成一个方法而不是两处各写一遍判空：这两处的判据必须完全一致，
	 * 而"归因键判了 blank、会话 id 忘了判"会让一个只打了空格的会话 id
	 * 变成一个合法的分组键（所有这样的运行会挤在一段**不存在的对话**里）。
	 */
	private static String clamp(String value, int maxChars) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
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
			// 【阶段四第 6 件】列表里带上两个归因维度：看板按它们分组，
			// 而"这次运行算在谁头上"要在它自己的列表里也能对得上 ——
			// 否则看完板的数字只能信，不能核（trace 那一层也带上，理由相同）
			row.put("intent", record.intent() == null ? "" : record.intent());
			row.put("tenantId", record.tenantId() == null ? "" : record.tenantId());
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
				Map.entry("node", run.node() == null ? "" : run.node()),
				// 【阶段四第 6 件】两个归因维度都带上：详情页是"核对看板数字"的落点，
				// 而"这次运行被算到谁头上"在这个页面上没有别的来源
				Map.entry("intent", run.intent() == null ? "" : run.intent()),
				Map.entry("tenantId", run.tenantId() == null ? "" : run.tenantId())));

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

		// ---- HITL：这次运行里人一共被问过几次、结果如何 ----
		//
		// 放在运行详情里（而不是只给一个全局待办列表）：要能讲清
		// 「什么时候让人确认」—— 而"什么时候"只有挂在那次运行的时间线上才有意义。
		// 全局列表回答的是另一个问题："现在有什么在等我"。
		List<Map<String, Object>> approvals = new ArrayList<>();
		for (var approval : this.approvals.ofRun(runId)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", approval.id());
			row.put("stepIndex", approval.stepIndex() == null ? -1 : approval.stepIndex());
			row.put("toolName", approval.toolName());
			row.put("arguments", approval.arguments() == null ? "" : approval.arguments());
			row.put("status", approval.status().name());
			row.put("pending", approval.pending());
			row.put("requestedAt", approval.requestedAt().toString());
			row.put("decidedAt", approval.decidedAt() == null ? "" : approval.decidedAt().toString());
			row.put("decidedBy", approval.decidedBy() == null ? "" : approval.decidedBy());
			row.put("decisionNote", approval.decisionNote() == null ? "" : approval.decisionNote());
			// 等了多久：审批对任务耗时的影响，唯一可量化的口径。
			// 本项目实测的"熔断每次省约 203ms"就是这种口径 —— 审批同样要给一个数字，
			// 否则"它有没有拖慢任务"只能靠感觉
			row.put("waitedMs", approval.waitedMs(java.time.Instant.now()));
			approvals.add(row);
		}
		body.put("approvals", approvals);
		body.put("approvalCount", approvals.size());
		body.put("pendingApprovals", approvals.stream().filter(a -> Boolean.TRUE.equals(a.get("pending"))).count());
		return body;
	}

	/**
	 * <b>五层 trace</b>：把一次运行的过程按层级完整还原（阶段四第 2 件）。
	 *
	 * <h2>它为什么与 {@code /runs/{runId}} 并存，而不是把那个端点改大</h2>
	 * 两个端点回答的是<b>两个不同的问题</b>，而混在一起会让两边都变模糊：
	 * <ul>
	 *   <li>{@code /runs/{id}} = 「这次运行怎么样」：状态、答案、每步的 token 与工具调用。
	 *       它是**读一次运行**，两条查询就够；</li>
	 *   <li>{@code /runs/{id}/trace} = 「这次运行<b>到底发生了什么</b>」：五层对齐 +
	 *       会话上下文 + 检索明细 + 金额 + 提示词指纹。
	 *       它要读五张表，而且响应明显更大。</li>
	 * </ul>
	 * 后者是排查入口（"用户说这次答得不对"），前者是列表点进来的概览。
	 * 把排查需要的东西全塞进概览端点，会让每次点开历史运行都付一遍五层装配的代价 ——
	 * 而其中大部分字段（例如检索明细）在概览里根本不会被看。
	 */
	@GetMapping("/runs/{runId}/trace")
	public Map<String, Object> trace(@PathVariable String runId) {
		return traceView.of(runId)
				.orElseGet(() -> Map.of("ok", false, "error", "没有这个运行：" + runId));
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

		// ---- HITL：这次任务是不是停在一次人工确认上 ----
		//
		// 三个字段一起给，因为界面要能直接渲染出"在等谁批什么"：
		// 只说 waitingApproval=true，用户还得再去待办列表里找是哪一条
		boolean waiting = run.stopReason() == AgentStopReason.WAITING_FOR_APPROVAL;
		body.put("waitingApproval", waiting);
		if (waiting) {
			approvals.pendingOfRun(runId).ifPresentOrElse(pending -> {
				body.put("approvalId", pending.id());
				body.put("approvalToolName", pending.toolName());
				body.put("approvalArguments", pending.arguments() == null ? "" : pending.arguments());
				body.put("approvalHints", pending.hints() == null ? "" : pending.hints());
				body.put("approvalTimeoutSeconds", approvalTimeoutSeconds);
			}, () -> {
				// 停因说是等审批，但待办里没有对应记录 —— 这是**必须报出来的不一致**。
				// 它可能意味着"记录被清理掉了"或"审批被别人抢先处理了"；
				// 无论哪种，界面都不能显示一个空的待批框让人以为还有救
				log.warn("运行 {} 的停因是 WAITING_FOR_APPROVAL，但待办里没有对应的审批记录", runId);
				body.put("approvalMissing", true);
			});
		}

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
			return ai.agent.service.tool.ToolCallStage.valueOf(stageName).countsAsToolFailure();
		}
		catch (IllegalArgumentException e) {
			// 库里存的是历史字符串，而枚举可能变过。**返回 false 而不是抛**：
			// 一个老记录不该让整个详情页打不开；而 false 的语义是"不算失败"，
			// 对未知 stage 是保守的（不会因为一条读不懂的记录就把工具标成坏）
			log.warn("库里有一条无法识别的工具调用 stage：{}", stageName);
			return false;
		}
	}

	/**
	 * 请求体：问题走 body（不进 URL），理由见类注释。
	 *
	 * @param question 用户问题
	 * @param intent 业务归因键（阶段四成本看板）：这次运行为哪个业务/功能花的钱。
	 *            可空 —— 空表示"未标注"，看板会把它单列一行而不是并进某个业务里。
	 *            <p>它进请求体而不是路径/查询串，与 question 同一条理由；
	 *            而且它是**成本表的分组键**，一旦被 URL 转义规则改写（比如
	 *            加号变成空格），就会出现两个只差一个空格的业务分组 ——
	 *            那种错误在看板上表现为"这个业务怎么有两行"，而没人会想到是转义
	 * @param conversationId <b>会话 id</b>（阶段四第 2 件）：这段对话的标识。
	 *            同一段对话里的多次 {@code /ask} 传同一个值，trace 才能把它们串起来。
	 *            <p>可空 —— 空表示"没有会话信息"（curl 直接调、外部系统调）。
	 *            <b>后端刻意不自动生成一个</b>：给每次孤立调用编一个会话 id，
	 *            会让"有会话"这件事看起来处处成立，而真相是我们不知道这些运行
	 *            属不属于同一段对话。这与归因键"没标注就显示未标注"是同一条规矩。
	 *            <p>它同样进请求体（理由与 intent 一字不差：它是分组键，
	 *            被 URL 转义改写会让同一段对话裂成两段 —— 而那种错在页面上
	 *            表现为"这段对话怎么只有一次运行"，没人会想到是转义）
	 * @param tenantId <b>租户</b>（阶段四第 6 件）：这次运行花的是谁的钱。
	 *            可空 —— 空表示"未标注租户"，看板会把它单列一行而不是并进某个租户里。
	 *            <p>它与 {@code intent} <b>是两个维度、两列</b>：
	 *            {@code intent} = 花在哪个功能上，本字段 = 谁在花钱。
	 *            拼接（{@code 京东-客服问答}）会让两个维度互相污染，
	 *            而"按租户看一共多少"会退化成前缀匹配。
	 *            <p>同样进请求体：它是分组键，被 URL 转义改写会让同一个租户裂成两个 ——
	 *            而那种错在看板上表现为"这个租户怎么有两行"
	 */
	public record AskRequest(String question, String intent, String conversationId, String tenantId) {

		/** 不带任何归因的构造器（最早的调用点与测试用）。 */
		public AskRequest(String question) {
			this(question, null, null, null);
		}

		/** 带归因键、不带会话与租户的构造器（成本看板那批调用点用）。 */
		public AskRequest(String question, String intent) {
			this(question, intent, null, null);
		}

		/** 带归因键与会话、不带租户的构造器（第 2 件那批调用点用）。 */
		public AskRequest(String question, String intent, String conversationId) {
			this(question, intent, conversationId, null);
		}
	}

}
