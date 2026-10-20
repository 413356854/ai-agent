package ai.agent.service.graph;

import ai.agent.graph.GraphDemoGraphFactory;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static ai.agent.graph.GraphDemoGraphFactory.AUDIT_REPORT;
import static ai.agent.graph.GraphDemoGraphFactory.BUG;
import static ai.agent.graph.GraphDemoGraphFactory.FAIL;
import static ai.agent.graph.GraphDemoGraphFactory.FEATURE;
import static ai.agent.graph.GraphDemoGraphFactory.FORCE_FIRST_REVIEW_FAIL;
import static ai.agent.graph.GraphDemoGraphFactory.IMPLEMENTATION;
import static ai.agent.graph.GraphDemoGraphFactory.IMPLEMENTATION_ROUND;
import static ai.agent.graph.GraphDemoGraphFactory.MAX_REVIEW_ROUNDS;
import static ai.agent.graph.GraphDemoGraphFactory.NODE_TRACE;
import static ai.agent.graph.GraphDemoGraphFactory.QUESTION;
import static ai.agent.graph.GraphDemoGraphFactory.REQUESTED_TYPE;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_DECISION;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_GATE_DEGRADED;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_GATE_FORCED;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_GATE_REASON;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_GATE_RESULT;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_GATE_ROUND;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_GATE_SKIPPED;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_ROUND;
import static ai.agent.graph.GraphDemoGraphFactory.REVIEW_VERDICT;
import static ai.agent.graph.GraphDemoGraphFactory.ROUTE;
import static ai.agent.graph.GraphDemoGraphFactory.ROUTE_REASON;
import static ai.agent.graph.GraphDemoGraphFactory.RUN_ID;
import static ai.agent.graph.GraphDemoGraphFactory.STARTED_AT;
import static ai.agent.graph.GraphDemoGraphFactory.SUMMARY;
import static ai.agent.graph.GraphDemoGraphFactory.THREAD_ID;
import static ai.agent.graph.GraphDemoGraphFactory.TITLE;
import static ai.agent.graph.GraphDemoGraphFactory.TRIAGE_RESULT;
import static ai.agent.graph.GraphDemoGraphFactory.TRIAGE_ROUTE_CANDIDATE;
import static ai.agent.graph.GraphDemoGraphFactory.WORK_PLAN;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * Graph 演示的读写入口。
 *
 * <h2>三个动作</h2>
 * <ul>
 *   <li>{@link #meta()}：页面需要的拓扑、状态键、模型名与调用次数；</li>
 *   <li>{@link #run(RunRequest)}：同步跑一次图，返回节点轨迹、最终状态与检查点历史；</li>
 *   <li>{@link #replay(ReplayRequest)}：从指定检查点之后重新执行，返回新的轨迹与历史。</li>
 * </ul>
 *
 * <h2>为什么用 {@link Semaphore} 限制为一次一条</h2>
 * 每次 run 会串行调用 3–7 次真实模型。页面只有一个运行按钮，但 HTTP 请求仍可能并发到达；
 * 两个 run 同时写同一个 {@code MemorySaver} 会交错写检查点，也会让成本翻倍。
 * 抢不到时返回 409，由页面提示“已有一次运行在跑”。
 *
 * <h2>检查点只活在进程内</h2>
 * {@code MemorySaver} 不落库：后端重启后 {@link #replay} 找不到旧 thread，返回 400。
 * 这是本次演示的边界，不是任务记录的持久化方案。
 */
public class GraphDemoService {

	private final CompiledGraph graph;

	private final ObjectProvider<OpenAiChatProperties> chatProperties;

	private final Semaphore runLock = new Semaphore(1);

	public GraphDemoService(CompiledGraph graph, ObjectProvider<OpenAiChatProperties> chatProperties) {
		this.graph = graph;
		this.chatProperties = chatProperties;
	}

	// ------------------------------------------------------------------
	//  元信息
	// ------------------------------------------------------------------

	public Map<String, Object> meta() {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("enabled", true);
		meta.put("graphName", GraphDemoGraphFactory.GRAPH_NAME);
		meta.put("model", modelName());
		meta.put("modelConfigured", modelName() != null);
		meta.put("checkpointSaver", "MemorySaver（进程内；后端重启后清空）");
		meta.put("checkpointScope", "memory");
		meta.put("recursionLimit", 100);
		meta.put("maxReviewRounds", MAX_REVIEW_ROUNDS);
		meta.put("forceFirstReviewFailSupported", true);
		meta.put("modelCalls", Map.of("question", 3, "bug", 5, "feature", 5, "forcedFirstReviewFailExtra", 2));
		meta.put("nodes", GraphDemoGraphFactory.nodes());
		meta.put("edges", GraphDemoGraphFactory.edges());
		meta.put("stateKeys", GraphDemoGraphFactory.stateKeys());
		meta.put("warnings", warnings(false));
		return meta;
	}

	// ------------------------------------------------------------------
	//  运行
	// ------------------------------------------------------------------

	public Map<String, Object> run(RunRequest request) {
		String title = requireText(request == null ? null : request.title(), "标题", 200);
		String summary = requireText(request == null ? null : request.summary(), "描述", 4000);
		String type = requireType(request == null ? null : request.type());
		boolean force = request != null && Boolean.TRUE.equals(request.forceFirstReviewFail());
		String threadId = optionalThreadId(request == null ? null : request.threadId());

		if (!runLock.tryAcquire()) {
			throw busy();
		}
		try {
			String runId = UUID.randomUUID().toString();
			Map<String, Object> initial = new LinkedHashMap<>();
			initial.put(RUN_ID, runId);
			initial.put(THREAD_ID, threadId);
			initial.put(STARTED_AT, Instant.now().toString());
			initial.put(TITLE, title);
			initial.put(REQUESTED_TYPE, type);
			initial.put(SUMMARY, summary);
			initial.put(FORCE_FIRST_REVIEW_FAIL, force);
			initial.put(IMPLEMENTATION_ROUND, 0);
			initial.put(REVIEW_ROUND, 0);

			RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
			long started = System.currentTimeMillis();
			OverAllState state = invoke(initial, config);
			long elapsedMs = System.currentTimeMillis() - started;

			Map<String, Object> response = response("run", threadId, state, elapsedMs, false);
			response.put("request", requestInfo(title, type, summary, force));
			response.put("replayedFromTraceIndex", 0);
			response.put("replayedNodeIds", List.of());
			return response;
		}
		catch (ResponseStatusException e) {
			throw e;
		}
		catch (RuntimeException e) {
			throw executionFailed(e);
		}
		finally {
			runLock.release();
		}
	}

	public Map<String, Object> replay(ReplayRequest request) {
		String threadId = requireText(request == null ? null : request.threadId(), "threadId", 128);
		String checkpointId = requireText(request == null ? null : request.checkpointId(), "checkpointId", 128);

		if (!runLock.tryAcquire()) {
			throw busy();
		}
		try {
			RunnableConfig config = RunnableConfig.builder().threadId(threadId).checkPointId(checkpointId).build();
			StateSnapshot snapshot = graph.stateOf(config)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"检查点不存在：threadId=" + threadId + "，checkpointId=" + checkpointId
								+ "。检查点由 MemorySaver 保存在进程内，后端重启后清空。"));
			String next = snapshot.next();
			if (next == null || END.equals(next)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"该检查点之后已经没有可执行的节点（next=" + next + "），不能重放");
			}
			int traceBefore = trace(snapshot.state()).size();

			long started = System.currentTimeMillis();
			OverAllState state = invoke(Map.of(), config);
			long elapsedMs = System.currentTimeMillis() - started;

			Map<String, Object> response = response("replay", threadId, state, elapsedMs, true);
			List<Map<String, Object>> nodes = trace(state);
			List<String> replayedNodeIds = new ArrayList<>();
			for (int i = Math.min(traceBefore, nodes.size()); i < nodes.size(); i++) {
				replayedNodeIds.add(String.valueOf(nodes.get(i).get("nodeId")));
			}
			response.put("replayedFromTraceIndex", traceBefore + 1);
			response.put("replayedNodeIds", replayedNodeIds);
			response.put("request", requestInfo(text(state, TITLE, ""), text(state, REQUESTED_TYPE, QUESTION),
					text(state, SUMMARY, ""),
					Boolean.TRUE.equals(state.value(FORCE_FIRST_REVIEW_FAIL, Boolean.class).orElse(false))));
			return response;
		}
		catch (ResponseStatusException e) {
			throw e;
		}
		catch (RuntimeException e) {
			throw executionFailed(e);
		}
		finally {
			runLock.release();
		}
	}

	// ------------------------------------------------------------------
	//  响应组装
	// ------------------------------------------------------------------

	private Map<String, Object> response(String kind, String threadId, OverAllState state, long elapsedMs,
			boolean replay) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("ok", true);
		response.put("kind", kind);
		response.put("threadId", threadId);
		response.put("runId", text(state, RUN_ID, ""));
		response.put("model", modelName());
		response.put("graphName", GraphDemoGraphFactory.GRAPH_NAME);
		response.put("startedAt", text(state, STARTED_AT, ""));
		response.put("elapsedMs", elapsedMs);
		response.put("route", text(state, ROUTE, ""));
		response.put("routeReason", text(state, ROUTE_REASON, ""));
		response.put("triageSuggestedRoute", text(state, TRIAGE_ROUTE_CANDIDATE, ""));
		response.put("reviewGate", reviewGate(state));
		response.put("nodes", trace(state));
		response.put("finalState", finalState(state));
		response.put("history", historyOf(threadId));
		response.put("warnings", warnings(replay));
		return response;
	}

	private Map<String, Object> finalState(OverAllState state) {
		Map<String, Object> finalState = new LinkedHashMap<>();
		finalState.put("runId", text(state, RUN_ID, ""));
		finalState.put("threadId", text(state, THREAD_ID, ""));
		finalState.put("title", text(state, TITLE, ""));
		finalState.put("requestedType", text(state, REQUESTED_TYPE, ""));
		finalState.put("summary", text(state, SUMMARY, ""));
		finalState.put("summaryChars", text(state, SUMMARY, "").length());
		finalState.put("route", text(state, ROUTE, ""));
		finalState.put("routeReason", text(state, ROUTE_REASON, ""));
		finalState.put("triageSuggestedRoute", text(state, TRIAGE_ROUTE_CANDIDATE, ""));
		finalState.put("triageResult", text(state, TRIAGE_RESULT, ""));
		finalState.put("workPlan", text(state, WORK_PLAN, ""));
		finalState.put("implementation", text(state, IMPLEMENTATION, ""));
		finalState.put("reviewDecision", text(state, REVIEW_DECISION, ""));
		finalState.put("reviewVerdict", text(state, REVIEW_VERDICT, ""));
		finalState.put("reviewRound", integer(state, REVIEW_ROUND, 0));
		finalState.put("reviewGateResult", text(state, REVIEW_GATE_RESULT, ""));
		finalState.put("reviewGateReason", text(state, REVIEW_GATE_REASON, ""));
		finalState.put("reviewGateForced", bool(state, REVIEW_GATE_FORCED, false));
		finalState.put("reviewGateSkipped", bool(state, REVIEW_GATE_SKIPPED, false));
		finalState.put("reviewGateDegraded", bool(state, REVIEW_GATE_DEGRADED, false));
		finalState.put("auditReport", text(state, AUDIT_REPORT, ""));
		return finalState;
	}

	private Map<String, Object> reviewGate(OverAllState state) {
		Map<String, Object> gate = new LinkedHashMap<>();
		gate.put("result", text(state, REVIEW_GATE_RESULT, FAIL));
		gate.put("round", integer(state, REVIEW_GATE_ROUND, 0));
		gate.put("maxRounds", MAX_REVIEW_ROUNDS);
		gate.put("forced", bool(state, REVIEW_GATE_FORCED, false));
		gate.put("skipped", bool(state, REVIEW_GATE_SKIPPED, false));
		gate.put("degraded", bool(state, REVIEW_GATE_DEGRADED, false));
		gate.put("reason", text(state, REVIEW_GATE_REASON, ""));
		return gate;
	}

	private Map<String, Object> requestInfo(String title, String type, String summary, boolean force) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("title", title);
		request.put("type", type);
		request.put("summaryChars", summary.length());
		request.put("forceFirstReviewFail", force);
		return request;
	}

	private List<Map<String, Object>> historyOf(String threadId) {
		RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
		List<StateSnapshot> snapshots = new ArrayList<>(graph.getStateHistory(config));
		Collections.reverse(snapshots);
		List<Map<String, Object>> history = new ArrayList<>();
		int index = 1;
		for (StateSnapshot snapshot : snapshots) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("index", index++);
			entry.put("checkpointId", snapshot.config().checkPointId().orElse(""));
			entry.put("kind", kindOf(snapshot.node()));
			entry.put("node", snapshot.node());
			entry.put("next", snapshot.next() == null ? "" : snapshot.next());
			entry.put("keys", GraphDemoGraphFactory.businessKeys(snapshot.state().data()));
			entry.put("selected", selected(snapshot.state().data()));
			history.add(entry);
		}
		return history;
	}

	private static Map<String, Object> selected(Map<String, Object> data) {
		Map<String, Object> selected = new LinkedHashMap<>();
		putIfPresent(selected, "route", data.get(ROUTE));
		putIfPresent(selected, "review_gate_result", data.get(REVIEW_GATE_RESULT));
		putIfPresent(selected, "review_round", data.get(REVIEW_ROUND));
		putIfPresent(selected, "title", data.get(TITLE));
		putIfPresent(selected, "requested_type", data.get(REQUESTED_TYPE));
		putIfPresent(selected, "run_id", data.get(RUN_ID));
		return selected;
	}

	private static void putIfPresent(Map<String, Object> target, String key, Object value) {
		if (value != null) {
			target.put(key, value);
		}
	}

	private static String kindOf(String node) {
		if (START.equals(node)) {
			return "start";
		}
		if (END.equals(node)) {
			return "end";
		}
		return "node";
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> trace(OverAllState state) {
		return state.value(NODE_TRACE, List.class)
			.map(value -> (List<Map<String, Object>>) value)
			.orElse(List.of());
	}

	private List<String> warnings(boolean replay) {
		List<String> warnings = new ArrayList<>();
		warnings.add("这是真实模型演示：bug/feature 通常调用 5 次模型，强制首轮 FAIL 时调用 7 次；页面超时 300 秒。");
		warnings.add("检查点由 MemorySaver 保存在进程内，后端重启后清空。");
		if (replay) {
			warnings.add("回放会从检查点之后重新执行节点，可能再次调用模型并产生费用；模型输出可能与第一次不同。");
		}
		return warnings;
	}

	// ------------------------------------------------------------------
	//  校验与异常
	// ------------------------------------------------------------------

	private static String requireText(String value, String what, int max) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "不能为空");
		}
		String checked = value.strip();
		if (checked.length() > max) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"%s过长（%d 字符 > 上限 %d）".formatted(what, checked.length(), max));
		}
		return checked;
	}

	private static String requireType(String value) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type 不能为空（bug / feature / question）");
		}
		String type = value.strip().toLowerCase(Locale.ROOT);
		if (!List.of(BUG, FEATURE, QUESTION).contains(type)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"type 只允许 bug / feature / question，实际是「" + value + "」");
		}
		return type;
	}

	private static String optionalThreadId(String value) {
		if (value == null || value.isBlank()) {
			return "graph-" + UUID.randomUUID();
		}
		String threadId = value.strip();
		if (threadId.length() > 128) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"threadId 过长（%d 字符 > 上限 128）".formatted(threadId.length()));
		}
		return threadId;
	}

	private static ResponseStatusException busy() {
		return new ResponseStatusException(HttpStatus.CONFLICT,
				"已有一次 Graph 演示在运行（真实模型调用需要时间），请等它结束后再试。");
	}

	private static ResponseStatusException executionFailed(RuntimeException e) {
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
				"Graph 演示执行失败：" + rootMessage(e)
						+ "。检查 OPENAI_API_KEY、网络与 spring.ai.openai.chat.options.model。",
				e);
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		String message = null;
		while (current != null) {
			if (current.getMessage() != null && !current.getMessage().isBlank()) {
				message = current.getMessage();
			}
			if (current.getCause() == current) {
				break;
			}
			current = current.getCause();
		}
		return message == null ? error.getClass().getSimpleName() : message;
	}

	private OverAllState invoke(Map<String, Object> input, RunnableConfig config) {
		Optional<OverAllState> state = graph.invoke(input, config);
		return state.orElseThrow(() -> new IllegalStateException("Graph 没有返回最终状态"));
	}

	private String modelName() {
		OpenAiChatProperties properties = chatProperties.getIfAvailable();
		String model = properties == null ? null : properties.getModel();
		return model == null || model.isBlank() ? null : model;
	}

	private static String text(OverAllState state, String key, String fallback) {
		return state.value(key, String.class).orElse(fallback);
	}

	private static int integer(OverAllState state, String key, int fallback) {
		return state.value(key, Integer.class).orElse(fallback);
	}

	private static boolean bool(OverAllState state, String key, boolean fallback) {
		return state.value(key, Boolean.class).orElse(fallback);
	}

	/**
	 * 页面运行请求。
	 *
	 * @param title issue 标题（必填，≤ 200 字符）
	 * @param type 用户预选类型（bug / feature / question）；模型 route_candidate 有效时优先
	 * @param summary issue 描述（必填，≤ 4000 字符）
	 * @param forceFirstReviewFail 演示开关：第 1 轮评审强制 FAIL
	 * @param threadId 可选；省略时服务端生成 {@code graph-<uuid>}
	 */
	public record RunRequest(String title, String type, String summary, Boolean forceFirstReviewFail,
			String threadId) {
	}

	/**
	 * 从检查点重放的请求。
	 *
	 * @param threadId 运行时的 thread id
	 * @param checkpointId 检查点 id（来自 run 响应的 history）
	 */
	public record ReplayRequest(String threadId, String checkpointId) {
	}

}
