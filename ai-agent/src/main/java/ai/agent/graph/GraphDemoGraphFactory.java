package ai.agent.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * Graph 演示的图工厂 —— 9 个节点的 RepoOps 生命周期图。
 *
 * <h2>节点与边</h2>
 * <pre>
 * START → triage（模型）→ route_issue（确定性）
 *   bug     → bug_diagnoser（模型）  ─┐
 *   feature → feature_planner（模型） ─┼→ implementer（模型）→ reviewer（模型）→ review_gate
 *   question → question_responder（模型；写跳过证据）→ auditor（模型）→ END
 * review_gate ── FAIL ──→ implementer（回环，最多 2 轮）
 * review_gate ── PASS ──→ auditor → END
 * </pre>
 *
 * <h2>状态写入的两条纪律</h2>
 * <ol>
 *   <li>每个节点只返回它自己产出的键；{@code current_node}、{@code node_trace} 与
 *       {@code evidence_bundle} 由 {@link #traced} 统一写，节点不各写一遍。</li>
 *   <li>业务键用 {@link ReplaceStrategy} 覆盖；轨迹/路由历史/证据用
 *       {@link AppendStrategy} 追加。把轨迹键写成 Replace 会让页面只剩最后一条。</li>
 * </ol>
 *
 * <h2>模型调用</h2>
 * 直接注入 {@link ChatModel}，不走应用里的 {@code ChatClient} bean：那个 bean 带对话
 * 系统提示词与 ChatMemory advisor；也不传 toolCallbacks，避免框架自动执行工具。
 */
public class GraphDemoGraphFactory {

	public static final String GRAPH_NAME = "graph-demo-repoops";

	public static final int MAX_REVIEW_ROUNDS = 2;

	public static final String RUN_ID = "run_id";

	public static final String THREAD_ID = "thread_id";

	public static final String STARTED_AT = "started_at";

	public static final String TITLE = "title";

	public static final String REQUESTED_TYPE = "requested_type";

	public static final String SUMMARY = "summary";

	public static final String FORCE_FIRST_REVIEW_FAIL = "force_first_review_fail";

	public static final String TRIAGE_RESULT = "triage_result";

	public static final String TRIAGE_ROUTE_CANDIDATE = "triage_route_candidate";

	public static final String ROUTE = "route";

	public static final String ROUTE_REASON = "route_reason";

	public static final String WORK_PLAN = "work_plan";

	public static final String IMPLEMENTATION = "implementation";

	public static final String IMPLEMENTATION_ROUND = "implementation_round";

	public static final String REVIEW_DECISION = "review_decision";

	public static final String REVIEW_VERDICT = "review_verdict";

	public static final String REVIEW_ROUND = "review_round";

	public static final String REVIEW_GATE_RESULT = "review_gate_result";

	public static final String REVIEW_GATE_REASON = "review_gate_reason";

	public static final String REVIEW_GATE_FORCED = "review_gate_forced";

	public static final String REVIEW_GATE_ROUND = "review_gate_round";

	public static final String REVIEW_GATE_SKIPPED = "review_gate_skipped";

	public static final String REVIEW_GATE_DEGRADED = "review_gate_degraded";

	public static final String AUDIT_REPORT = "audit_report";

	public static final String CURRENT_NODE = "current_node";

	public static final String NODE_TRACE = "node_trace";

	public static final String ROUTE_HISTORY = "route_history";

	public static final String EVIDENCE_BUNDLE = "evidence_bundle";

	public static final String TRIAGE = "triage";

	public static final String ROUTE_ISSUE = "route_issue";

	public static final String BUG_DIAGNOSER = "bug_diagnoser";

	public static final String FEATURE_PLANNER = "feature_planner";

	public static final String QUESTION_RESPONDER = "question_responder";

	public static final String IMPLEMENTER = "implementer";

	public static final String REVIEWER = "reviewer";

	public static final String REVIEW_GATE = "review_gate";

	public static final String AUDITOR = "auditor";

	public static final String BUG = "bug";

	public static final String FEATURE = "feature";

	public static final String QUESTION = "question";

	public static final String PASS = "pass";

	public static final String FAIL = "fail";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Set<String> ROUTES = Set.of(BUG, FEATURE, QUESTION);

	private static final List<String> REPLACE_KEYS = List.of(RUN_ID, THREAD_ID, STARTED_AT, TITLE, REQUESTED_TYPE,
			SUMMARY, FORCE_FIRST_REVIEW_FAIL, TRIAGE_RESULT, TRIAGE_ROUTE_CANDIDATE, ROUTE, ROUTE_REASON, WORK_PLAN,
			IMPLEMENTATION, IMPLEMENTATION_ROUND, REVIEW_DECISION, REVIEW_VERDICT, REVIEW_ROUND, REVIEW_GATE_RESULT,
			REVIEW_GATE_REASON, REVIEW_GATE_FORCED, REVIEW_GATE_ROUND, REVIEW_GATE_SKIPPED, REVIEW_GATE_DEGRADED,
			AUDIT_REPORT, CURRENT_NODE);

	private static final List<String> APPEND_KEYS = List.of(NODE_TRACE, ROUTE_HISTORY, EVIDENCE_BUNDLE);

	private final ChatModel chatModel;

	private final ObjectProvider<OpenAiChatProperties> chatProperties;

	public GraphDemoGraphFactory(ChatModel chatModel, ObjectProvider<OpenAiChatProperties> chatProperties) {
		this.chatModel = chatModel;
		this.chatProperties = chatProperties;
	}

	/**
	 * 构建并编译 9 节点图。
	 *
	 * <p>拓扑与分支见类注释；条件边只有两处：{@code route_issue} 按 {@code route} 分三路，
	 * {@code review_gate} 按 {@code review_gate_result} 分 pass / fail（fail 回到 {@code implementer}）。
	 * 检查点由传入的 {@link MemorySaver} 保存；{@code recursionLimit=100} 是回环的兜底上限。
	 */
	public CompiledGraph build(MemorySaver saver) throws GraphStateException {
		StateGraph graph = new StateGraph(GRAPH_NAME, GraphDemoGraphFactory::keyStrategies)
			.addNode(TRIAGE,
					traced(TRIAGE, "模型分诊", "model", "worker", true,
							(state, config) -> triage(state)))
			.addNode(ROUTE_ISSUE,
					traced(ROUTE_ISSUE, "确定性路由", "deterministic", "router", false,
							(state, config) -> routeIssue(state)))
			.addNode(BUG_DIAGNOSER,
					traced(BUG_DIAGNOSER, "缺陷诊断", "model", "worker", true,
							(state, config) -> bugDiagnoser(state)))
			.addNode(FEATURE_PLANNER,
					traced(FEATURE_PLANNER, "特性规划", "model", "worker", true,
							(state, config) -> featurePlanner(state)))
			.addNode(QUESTION_RESPONDER,
					traced(QUESTION_RESPONDER, "问题答复", "model", "worker", true,
							(state, config) -> questionResponder(state)))
			.addNode(IMPLEMENTER,
					traced(IMPLEMENTER, "实现方案", "model", "worker", true,
							(state, config) -> implementer(state)))
			.addNode(REVIEWER,
					traced(REVIEWER, "方案评审", "model", "reviewer", true,
							(state, config) -> reviewer(state)))
			.addNode(REVIEW_GATE,
					traced(REVIEW_GATE, "评审闸门", "deterministic", "gate", false,
							(state, config) -> reviewGate(state)))
			.addNode(AUDITOR,
					traced(AUDITOR, "审计汇总", "model", "auditor", true,
							(state, config) -> auditor(state)))
			.addEdge(START, TRIAGE)
			.addEdge(TRIAGE, ROUTE_ISSUE)
			.addConditionalEdges(ROUTE_ISSUE, edge_async(state -> text(state, ROUTE, QUESTION)),
					Map.of(BUG, BUG_DIAGNOSER, FEATURE, FEATURE_PLANNER, QUESTION, QUESTION_RESPONDER))
			.addEdge(BUG_DIAGNOSER, IMPLEMENTER)
			.addEdge(FEATURE_PLANNER, IMPLEMENTER)
			.addEdge(QUESTION_RESPONDER, AUDITOR)
			.addEdge(IMPLEMENTER, REVIEWER)
			.addEdge(REVIEWER, REVIEW_GATE)
			.addConditionalEdges(REVIEW_GATE, edge_async(state -> text(state, REVIEW_GATE_RESULT, FAIL)),
					Map.of(PASS, AUDITOR, FAIL, IMPLEMENTER))
			.addEdge(AUDITOR, END);

		CompileConfig compileConfig = CompileConfig.builder()
			.saverConfig(SaverConfig.builder().register(saver).build())
			.recursionLimit(100)
			.build();
		return graph.compile(compileConfig);
	}

	// ------------------------------------------------------------------
	//  节点动作
	// ------------------------------------------------------------------

	/**
	 * 模型分诊：调用一次模型，写 {@code triage_result} 与从文本里解析出的
	 * {@code triage_route_candidate}。路由决定不在这里做 —— 由 {@link #routeIssue} 统一裁决。
	 */
	private Map<String, Object> triage(OverAllState state) {
		String title = text(state, TITLE, "");
		String requestedType = text(state, REQUESTED_TYPE, QUESTION);
		String summary = text(state, SUMMARY, "");
		String result = callModel(GraphDemoPrompts.TRIAGE_SYSTEM,
				GraphDemoPrompts.triageUser(title, requestedType, summary));
		return single(TRIAGE_RESULT, result, TRIAGE_ROUTE_CANDIDATE, routeCandidate(result));
	}

	/**
	 * 确定性路由：模型给出的 {@code route_candidate} 合法时以它为准；否则回退到用户选择的
	 * {@code requested_type}，认不出的值落 {@code question}。写 {@code route} / {@code route_reason}
	 * 与一条 {@code route_history}。
	 */
	private Map<String, Object> routeIssue(OverAllState state) {
		String candidate = text(state, TRIAGE_ROUTE_CANDIDATE, "");
		String requested = sanitizeRoute(text(state, REQUESTED_TYPE, QUESTION));
		String route;
		String reason;
		if (isRoute(candidate)) {
			route = candidate;
			reason = "模型分诊 route_candidate=" + candidate;
		}
		else {
			route = requested;
			reason = "回退到用户选择的类型 " + requested;
		}
		Map<String, Object> update = new LinkedHashMap<>();
		update.put(ROUTE, route);
		update.put(ROUTE_REASON, reason);
		update.put(ROUTE_HISTORY, List.of(routeEntry(ROUTE_ISSUE, route, reason)));
		return update;
	}

	/**
	 * bug 分支：让模型给出复现步骤、故障边界、待查文件、回归测试建议与证据缺口，写 {@code work_plan}。
	 */
	private Map<String, Object> bugDiagnoser(OverAllState state) {
		String result = callModel(GraphDemoPrompts.BUG_SYSTEM,
				GraphDemoPrompts.bugUser(text(state, TITLE, ""), text(state, SUMMARY, ""),
						text(state, TRIAGE_RESULT, "")));
		return Map.of(WORK_PLAN, result);
	}

	/**
	 * feature 分支：让模型给出用户故事、非目标、设计草案、验收标准与评审/发布门，写 {@code work_plan}。
	 */
	private Map<String, Object> featurePlanner(OverAllState state) {
		String result = callModel(GraphDemoPrompts.FEATURE_SYSTEM,
				GraphDemoPrompts.featureUser(text(state, TITLE, ""), text(state, SUMMARY, ""),
						text(state, TRIAGE_RESULT, "")));
		return Map.of(WORK_PLAN, result);
	}

	/**
	 * question 分支：让模型直接回答并指出是否需要文档/runbook 更新，写 {@code work_plan}；
	 * 同时写入跳过实现与评审所需的结论（{@code implementation}、{@code review_*} 与
	 * {@code review_gate_skipped=true}），于是这条分支直接进入 {@code auditor}。
	 */
	private Map<String, Object> questionResponder(OverAllState state) {
		String result = callModel(GraphDemoPrompts.QUESTION_SYSTEM,
				GraphDemoPrompts.questionUser(text(state, TITLE, ""), text(state, SUMMARY, ""),
						text(state, TRIAGE_RESULT, "")));
		Map<String, Object> update = new LinkedHashMap<>();
		update.put(WORK_PLAN, result);
		update.put(IMPLEMENTATION, "question 分支不需要实现方案。");
		update.put(REVIEW_DECISION, "PASS: question 分支不进入实现与评审。");
		update.put(REVIEW_VERDICT, "PASS");
		update.put(REVIEW_GATE_RESULT, PASS);
		update.put(REVIEW_GATE_REASON, "question 分支跳过实现与评审");
		update.put(REVIEW_GATE_FORCED, false);
		update.put(REVIEW_GATE_ROUND, 0);
		update.put(REVIEW_GATE_SKIPPED, true);
		update.put(REVIEW_GATE_DEGRADED, false);
		return update;
	}

	/**
	 * 实现方案：让模型给出可评审的改动点、测试与风险，写 {@code implementation}；
	 * {@code implementation_round} 从 1 开始递增，第 2 轮把上一轮 {@code review_decision} 带进提示词。
	 */
	private Map<String, Object> implementer(OverAllState state) {
		int round = state.value(IMPLEMENTATION_ROUND, Integer.class).orElse(0) + 1;
		String result = callModel(GraphDemoPrompts.IMPLEMENT_SYSTEM,
				GraphDemoPrompts.implementUser(text(state, TITLE, ""), text(state, SUMMARY, ""),
						text(state, TRIAGE_RESULT, ""), text(state, WORK_PLAN, ""), round,
						text(state, REVIEW_DECISION, "")));
		return single(IMPLEMENTATION, result, IMPLEMENTATION_ROUND, round);
	}

	/**
	 * 独立评审：模型输出以 PASS / FAIL 开头的 JSON，写 {@code review_decision}、解析出的
	 * {@code review_verdict} 与递增的 {@code review_round}。解析失败按 FAIL 处理。
	 */
	private Map<String, Object> reviewer(OverAllState state) {
		String raw = callModel(GraphDemoPrompts.REVIEW_SYSTEM,
				GraphDemoPrompts.reviewUser(text(state, TITLE, ""), text(state, SUMMARY, ""),
						text(state, TRIAGE_RESULT, ""), text(state, WORK_PLAN, ""),
						text(state, IMPLEMENTATION, "")));
		int round = state.value(REVIEW_ROUND, Integer.class).orElse(0) + 1;
		return single(REVIEW_DECISION, raw, REVIEW_VERDICT, reviewVerdict(raw), REVIEW_ROUND, round);
	}

	/**
	 * 评审闸门：把 {@code review_verdict} 转成 {@code pass} / {@code fail}。
	 *
	 * <p>三条规则：{@code forceFirstReviewFail} 让第 1 轮强制 fail；第 2 轮仍 FAIL 时按 pass
	 * 结束并标记 {@code degraded}，保证图终止；{@code forced} 标记一旦为真就保留到最终状态。
	 * fail 时条件边回到 {@code implementer}。写 {@code review_gate_*} 与一条 {@code route_history}。
	 */
	private Map<String, Object> reviewGate(OverAllState state) {
		int round = state.value(REVIEW_ROUND, Integer.class).orElse(1);
		String verdict = text(state, REVIEW_VERDICT, "FAIL");
		boolean force = Boolean.TRUE.equals(state.value(FORCE_FIRST_REVIEW_FAIL, Boolean.class).orElse(false));
		boolean forced = force && round == 1;
		boolean forcedBefore = Boolean.TRUE.equals(state.value(REVIEW_GATE_FORCED, Boolean.class).orElse(false));
		boolean degraded = false;
		String result;
		String reason;
		if (forced) {
			result = FAIL;
			reason = "演示开关：第 1 轮评审强制 FAIL";
		}
		else if ("FAIL".equals(verdict) && round >= MAX_REVIEW_ROUNDS) {
			result = PASS;
			degraded = true;
			reason = "评审连续 " + MAX_REVIEW_ROUNDS + " 轮 FAIL，按演示上限放行（degraded）";
		}
		else if ("PASS".equals(verdict)) {
			result = PASS;
			reason = "评审 verdict=PASS";
		}
		else {
			result = FAIL;
			reason = "评审 verdict=FAIL，回到实现节点";
		}
		Map<String, Object> update = new LinkedHashMap<>();
		update.put(REVIEW_GATE_RESULT, result);
		update.put(REVIEW_GATE_REASON, reason);
		update.put(REVIEW_GATE_FORCED, forced || forcedBefore);
		update.put(REVIEW_GATE_ROUND, round);
		update.put(REVIEW_GATE_SKIPPED, false);
		update.put(REVIEW_GATE_DEGRADED, degraded);
		update.put(ROUTE_HISTORY, List.of(routeEntry(REVIEW_GATE, result, reason)));
		return update;
	}

	/**
	 * 审计汇总：让模型根据 route、计划、实现、评审与门禁结果生成最终报告，写 {@code audit_report}。
	 */
	private Map<String, Object> auditor(OverAllState state) {
		String result = callModel(GraphDemoPrompts.AUDIT_SYSTEM,
				GraphDemoPrompts.auditUser(text(state, TITLE, ""), text(state, REQUESTED_TYPE, ""),
						text(state, SUMMARY, ""), text(state, TRIAGE_RESULT, ""), text(state, ROUTE, ""),
						text(state, WORK_PLAN, ""), text(state, IMPLEMENTATION, ""),
						text(state, REVIEW_DECISION, ""), text(state, REVIEW_GATE_RESULT, ""),
						text(state, REVIEW_GATE_REASON, "")));
		return Map.of(AUDIT_REPORT, result);
	}

	// ------------------------------------------------------------------
	//  模型调用与解析
	// ------------------------------------------------------------------

	/**
	 * 调一次真实模型：{@code SystemMessage} + {@code UserMessage}，不传 tools。
	 *
	 * <p>模型名取自 {@code spring.ai.openai.chat.options.model}；没配时直接抛，不发一个
	 * 浏览器都没配好的请求。空响应也抛 —— 让页面看到 502，而不是把空串当结论写进状态。
	 */
	private String callModel(String system, String user) {
		String model = modelName();
		if (model == null) {
			throw new IllegalStateException(
					"spring.ai.openai.chat.options.model 没配（或为空）—— Graph 演示不发请求");
		}
		OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).build();
		Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options);
		ChatResponse response = chatModel.call(prompt);
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			throw new IllegalStateException("模型返回空响应");
		}
		String text = response.getResult().getOutput().getText();
		return text == null ? "" : text;
	}

	/** 当前配置的模型名；取不到时返回 {@code null}（页面显示「未上报」，运行直接报配置缺失）。 */
	private String modelName() {
		OpenAiChatProperties properties = chatProperties.getIfAvailable();
		String model = properties == null ? null : properties.getModel();
		return model == null || model.isBlank() ? null : model;
	}

	/**
	 * 从 triage 文本里取 {@code route_candidate}。认不出时返回空串，由路由节点回退到用户类型。
	 */
	private static String routeCandidate(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("(?im)^\\s*[-*]?\\s*(?:route_candidate|route|type)\\s*[:=]\\s*`?\\s*(bug|feature|question)\\b")
			.matcher(raw);
		if (matcher.find()) {
			return sanitizeRoute(matcher.group(1));
		}
		return "";
	}

	/**
	 * 从评审 JSON 里取 {@code verdict}；只认 {@code PASS}，其余（含解析失败、缺字段）都按
	 * {@code FAIL} 处理。评审的失败方向是保守的：不通过就回到实现节点。
	 */
	private static String reviewVerdict(String raw) {
		String json = jsonObject(raw);
		if (json == null) {
			return "FAIL";
		}
		try {
			JsonNode node = OBJECT_MAPPER.readTree(json);
			String verdict = node.path("verdict").asText("").strip().toUpperCase(Locale.ROOT);
			return "PASS".equals(verdict) ? "PASS" : "FAIL";
		}
		catch (Exception e) {
			return "FAIL";
		}
	}

	/**
	 * 取文本里的第一个 JSON 对象。评审提示词要求纯 JSON，这里只做一层“去掉 Markdown 围栏”的兜底。
	 * 取不到时返回 {@code null}，评审结果按 FAIL 处理。
	 */
	private static String jsonObject(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		return start < 0 || end <= start ? null : raw.substring(start, end + 1);
	}

	// ------------------------------------------------------------------
	//  轨迹包装器
	// ------------------------------------------------------------------

	/**
	 * 给节点动作套一层轨迹：执行前记输入 keys 与开始时间，执行后追加一条 {@code node_trace}
	 * 与一条 {@code evidence_bundle}，并统一写 {@code current_node}。
	 *
	 * <p>节点动作因此只返回自己的业务产出，不各写一遍这三样（各写一遍的后果是新增节点时
	 * 漏掉其中一样，而页面只会表现为「这一行没有耗时」或「轨迹少一条」）。
	 * {@code index} 取当前 {@code node_trace} 的长度 + 1，所以回环里第二次执行的节点会接着编号。
	 */
	private AsyncNodeActionWithConfig traced(String nodeId, String label, String nodeType, String role,
			boolean usesModel, NodeActionWithConfig action) {
		return AsyncNodeActionWithConfig.node_async((state, config) -> {
			Instant startedAt = Instant.now();
			long started = System.nanoTime();
			List<String> inputKeys = businessKeys(state.data());
			Map<String, Object> update = action.apply(state, config);
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;
			Map<String, Object> result = new LinkedHashMap<>(update);
			result.put(CURRENT_NODE, nodeId);

			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("index", traceOf(state).size() + 1);
			entry.put("nodeId", nodeId);
			entry.put("label", label);
			entry.put("nodeType", nodeType);
			entry.put("role", role);
			entry.put("usesModel", usesModel);
			entry.put("model", usesModel ? modelName() : null);
			entry.put("inputKeys", inputKeys);
			entry.put("outputKeys", businessKeys(update));
			entry.put("elapsedMs", elapsedMs);
			entry.put("startedAt", startedAt.toString());
			entry.put("summary", clip(primaryOutput(nodeId, update)));
			entry.put("outcome", outcome(nodeId, update));
			entry.put("reviewRound", reviewRound(nodeId, update));
			result.put(NODE_TRACE, List.of(entry));
			result.put(EVIDENCE_BUNDLE, List.of(evidence(nodeId, "produced " + primaryKey(nodeId))));
			return result;
		});
	}

	@SuppressWarnings("unchecked")
	private static List<?> traceOf(OverAllState state) {
		return state.value(NODE_TRACE, List.class).orElse(List.of());
	}

	private static String primaryKey(String nodeId) {
		return switch (nodeId) {
			case TRIAGE -> TRIAGE_RESULT;
			case ROUTE_ISSUE -> ROUTE;
			case BUG_DIAGNOSER, FEATURE_PLANNER, QUESTION_RESPONDER -> WORK_PLAN;
			case IMPLEMENTER -> IMPLEMENTATION;
			case REVIEWER -> REVIEW_DECISION;
			case REVIEW_GATE -> REVIEW_GATE_RESULT;
			case AUDITOR -> AUDIT_REPORT;
			default -> "state";
		};
	}

	private static String primaryOutput(String nodeId, Map<String, Object> update) {
		Object value = update.get(primaryKey(nodeId));
		return value == null ? "" : String.valueOf(value);
	}

	private static String outcome(String nodeId, Map<String, Object> update) {
		if (ROUTE_ISSUE.equals(nodeId)) {
			return String.valueOf(update.get(ROUTE));
		}
		if (REVIEW_GATE.equals(nodeId)) {
			return String.valueOf(update.get(REVIEW_GATE_RESULT));
		}
		return null;
	}

	private static Integer reviewRound(String nodeId, Map<String, Object> update) {
		Object value = switch (nodeId) {
			case IMPLEMENTER -> update.get(IMPLEMENTATION_ROUND);
			case REVIEWER -> update.get(REVIEW_ROUND);
			case REVIEW_GATE -> update.get(REVIEW_GATE_ROUND);
			default -> null;
		};
		return value instanceof Integer round ? round : null;
	}

	// ------------------------------------------------------------------
	//  小工具
	// ------------------------------------------------------------------

	private static String text(OverAllState state, String key, String fallback) {
		return state.value(key, String.class).orElse(fallback);
	}

	private static boolean isRoute(String value) {
		return value != null && ROUTES.contains(value);
	}

	private static String sanitizeRoute(String value) {
		String route = value == null ? QUESTION : value.strip().toLowerCase(Locale.ROOT);
		return ROUTES.contains(route) ? route : QUESTION;
	}

	/**
	 * 业务键清单：排除 {@code node_trace} / {@code route_history} / {@code evidence_bundle} /
	 * {@code current_node} 与框架内部键（以下划线开头），按字典序返回。页面用它显示每个节点
	 * 实际读写了哪些状态键。
	 */
	public static List<String> businessKeys(Map<String, Object> data) {
		return data.keySet()
			.stream()
			.filter(key -> !NODE_TRACE.equals(key) && !ROUTE_HISTORY.equals(key)
					&& !EVIDENCE_BUNDLE.equals(key) && !CURRENT_NODE.equals(key))
			// 框架会塞一个 _graph_execution_id_ 内部键；它不是业务状态，不展示给页面
			.filter(key -> !key.startsWith("_"))
			.sorted()
			.collect(Collectors.toList());
	}

	private static Map<String, Object> routeEntry(String nodeId, String route, String reason) {
		return Map.of("nodeId", nodeId, "route", route, "reason", reason, "timestamp", Instant.now().toString());
	}

	private static Map<String, Object> evidence(String nodeId, String summary) {
		return Map.of("nodeId", nodeId, "summary", summary, "timestamp", Instant.now().toString());
	}

	private static String clip(String value) {
		String oneLine = value == null ? "" : value.replaceAll("\\s+", " ").strip();
		return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240) + "…";
	}

	private static Map<String, Object> single(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
		}
		return map;
	}

	private static Map<String, KeyStrategy> keyStrategies() {
		Map<String, KeyStrategy> strategies = new HashMap<>();
		for (String key : REPLACE_KEYS) {
			strategies.put(key, new ReplaceStrategy());
		}
		for (String key : APPEND_KEYS) {
			strategies.put(key, new AppendStrategy(false));
		}
		return strategies;
	}

	// ------------------------------------------------------------------
	//  页面元信息
	// ------------------------------------------------------------------

	/** 页面拓扑的节点清单：id、label、type、role、usesModel、description。它是 `/meta` 的唯一来源。 */
	public static List<Map<String, Object>> nodes() {
		List<Map<String, Object>> nodes = new ArrayList<>();
		nodes.add(node(TRIAGE, "模型分诊", "model", "worker", true, "判断 issue 类型并给出 route_candidate"));
		nodes.add(node(ROUTE_ISSUE, "确定性路由", "deterministic", "router", false, "把分诊结果转成最终 route"));
		nodes.add(node(BUG_DIAGNOSER, "缺陷诊断", "model", "worker", true, "复现、故障边界、回归测试建议"));
		nodes.add(node(FEATURE_PLANNER, "特性规划", "model", "worker", true, "用户故事、设计草案、验收标准"));
		nodes.add(node(QUESTION_RESPONDER, "问题答复", "model", "worker", true, "回答问题并跳过实现/评审"));
		nodes.add(node(IMPLEMENTER, "实现方案", "model", "worker", true, "生成可评审的实现方案"));
		nodes.add(node(REVIEWER, "方案评审", "model", "reviewer", true, "输出 PASS/FAIL 评审 JSON"));
		nodes.add(node(REVIEW_GATE, "评审闸门", "deterministic", "gate", false, "转换评审结论并控制回环"));
		nodes.add(node(AUDITOR, "审计汇总", "model", "auditor", true, "汇总最终生命周期审计报告"));
		return nodes;
	}

	/** 页面拓扑的边清单：from、to、kind（fixed / conditional）、label（条件边的分支值）。 */
	public static List<Map<String, Object>> edges() {
		List<Map<String, Object>> edges = new ArrayList<>();
		edges.add(edge(START, TRIAGE, "fixed", null));
		edges.add(edge(TRIAGE, ROUTE_ISSUE, "fixed", null));
		edges.add(edge(ROUTE_ISSUE, BUG_DIAGNOSER, "conditional", BUG));
		edges.add(edge(ROUTE_ISSUE, FEATURE_PLANNER, "conditional", FEATURE));
		edges.add(edge(ROUTE_ISSUE, QUESTION_RESPONDER, "conditional", QUESTION));
		edges.add(edge(BUG_DIAGNOSER, IMPLEMENTER, "fixed", null));
		edges.add(edge(FEATURE_PLANNER, IMPLEMENTER, "fixed", null));
		edges.add(edge(QUESTION_RESPONDER, AUDITOR, "fixed", null));
		edges.add(edge(IMPLEMENTER, REVIEWER, "fixed", null));
		edges.add(edge(REVIEWER, REVIEW_GATE, "fixed", null));
		edges.add(edge(REVIEW_GATE, AUDITOR, "conditional", PASS));
		edges.add(edge(REVIEW_GATE, IMPLEMENTER, "conditional", FAIL));
		edges.add(edge(AUDITOR, END, "fixed", null));
		return edges;
	}

	/** 状态键与策略清单：业务键是 replace，轨迹/历史/证据是 append。 */
	public static List<Map<String, Object>> stateKeys() {
		List<Map<String, Object>> keys = new ArrayList<>();
		for (String key : REPLACE_KEYS) {
			keys.add(stateKey(key, "replace"));
		}
		for (String key : APPEND_KEYS) {
			keys.add(stateKey(key, "append"));
		}
		return keys;
	}

	private static Map<String, Object> node(String id, String label, String type, String role, boolean usesModel,
			String description) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", id);
		map.put("label", label);
		map.put("type", type);
		map.put("role", role);
		map.put("usesModel", usesModel);
		map.put("description", description);
		return map;
	}

	private static Map<String, Object> edge(String from, String to, String kind, String label) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("from", from);
		map.put("to", to);
		map.put("kind", kind);
		map.put("label", label);
		return map;
	}

	private static Map<String, Object> stateKey(String key, String strategy) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("key", key);
		map.put("strategy", strategy);
		return map;
	}

}
