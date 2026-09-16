package ai.agent.alert;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 异常告警的端点（阶段四第 5 件）。
 *
 * <h2>端点分工：读三个、写两个</h2>
 * <table border="1">
 *   <caption>端点与它回答的问题</caption>
 *   <tr><th>端点</th><th>回答什么</th><th>谁用</th></tr>
 *   <tr><td>{@code GET /api/alerts/rules}</td><td>"这个项目把什么算作异常"（含构建阶段的门禁）</td>
 *       <td>人 + 接入监控时的说明书。<b>一次库都不查</b></td></tr>
 *   <tr><td>{@code GET /api/alerts}</td><td>"现在有什么在响、响多久了、有人看过吗"</td><td>页面 / 值班</td></tr>
 *   <tr><td>{@code GET /api/alerts/sweep?days=7}</td><td>跑一轮评估并返回结果（`dryRun` 可只算不写）</td>
 *       <td>页面刷新 / cron / 监控探针</td></tr>
 *   <tr><td>{@code POST /api/alerts/{fingerprint}/ack}</td><td>"我看过了"</td><td>值班的人</td></tr>
 * </table>
 *
 * <h2>★ 为什么"跑一轮评估"是 GET，而它其实会写库</h2>
 * 这是个刻意的取舍，值得说清。严格按 HTTP 语义，它会改状态，应该是 POST。
 * 但这一轮评估的<b>主要用途</b>是"页面打开时刷新一下"与"监控系统定期探一下" ——
 * 而这两者都希望它能被 curl / 浏览器 / 探针直接打，不需要构造请求体。
 *
 * <p>代价是它违反了一条众所周知的约定，所以做了两件事来补偿：
 * <ol>
 *   <li><b>提供 {@code dryRun=true}</b>：只算不写。给"我只想看现在是什么状态"
 *       这种纯粹的读用 —— 一个真的只读的入口；</li>
 *   <li><b>写操作要点名</b>：它只会<b>新增或更新告警行</b>，不会动业务数据。
 *       换句话说，这个副作用的方向是"把观测结论记下来"，而不是"改变系统行为"。</li>
 * </ol>
 * <p>而真正<b>会改变系统行为</b>的那个动作（确认告警、开停灰度）一律是 POST。
 *
 * <h2>为什么要有一个可选的写令牌</h2>
 * 本项目的 MCP 端点目前没有鉴权，台账里把这一条单列为风险。
 * 告警这边<b>不能再重复那个取舍</b>：确认告警会改变"还有几条没人看"这个数字，
 * 而那是值班交接的依据。所以写了 {@code ai.alert.write-token}：
 * 配了它就要求请求头带上；<b>没配就放行（本地开发）</b>，但在响应里明确标注
 * "当前写入未受保护" —— 这个标注很重要，它让"忘了配"变成一件看得见的事。
 */
@RestController
@RequestMapping(path = "/api/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AlertController {

	private final AlertRecorder recorder;

	private final AlertStore store;

	private final AlertSnapshotQuery query;

	/** 写操作令牌。空 = 未保护（本地开发），非空 = 请求头必须带上。 */
	@Value("${ai.alert.write-token:}")
	private String writeToken;

	public AlertController(AlertRecorder recorder, AlertStore store, AlertSnapshotQuery query) {
		this.recorder = recorder;
		this.store = store;
		this.query = query;
	}

	// ------------------------------------------------------------------
	//  判据目录
	// ------------------------------------------------------------------

	/**
	 * <b>「什么算异常」的完整清单</b> —— 包括本接口不评估的那些。
	 *
	 * <p>为什么构建阶段的门禁也要在这里列出来：因为它们与告警是<b>同一份判据的两个时间点</b>
	 * （见 {@link AlertRule} 的类注释）。只列运行阶段的话，读这个接口的人会以为
	 * "这个项目只检查这几件事"，而实际上还有四道门禁在构建阶段挡着。
	 * 这正是台账里那条"判据只能有一份"的落地形态：<b>一处定义，两处使用，一处展示</b>。
	 */
	@GetMapping("/rules")
	public Map<String, Object> rules() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("writeProtected", !writeToken.isBlank());
		body.put("thresholds", Map.of(
				"consecutiveEmptyRetrievals", AlertRule.CONSECUTIVE_EMPTY_RETRIEVALS,
				"canarySplitTolerance", AlertRule.CANARY_SPLIT_TOLERANCE,
				"canaryMinSample", AlertRule.CANARY_MIN_SAMPLE));
		body.put("phases", List.of(phase(AlertRule.AlertPhase.GATE), phase(AlertRule.AlertPhase.OBSERVE)));
		body.put("rules", java.util.Arrays.stream(AlertRule.values()).map(this::describe).toList());
		body.put("notes", query.catalogNotes());
		return body;
	}

	private Map<String, Object> phase(AlertRule.AlertPhase phase) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("phase", phase.name());
		body.put("label", phase.label());
		body.put("meaning", phase.meaning());
		body.put("evaluatedHere", phase == AlertRule.AlertPhase.OBSERVE);
		body.put("count", phase == AlertRule.AlertPhase.GATE ? AlertRule.gates().size() : AlertRule.observed().size());
		return body;
	}

	private Map<String, Object> describe(AlertRule rule) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", rule.code());
		body.put("title", rule.title());
		body.put("severity", rule.severity().name());
		body.put("severityLabel", rule.severity().label());
		body.put("severityMeaning", rule.severity().meaning());
		body.put("phase", rule.phase().name());
		body.put("phaseLabel", rule.phase().label());
		body.put("action", rule.action());
		body.put("executedBy", rule.executedBy());
		body.put("evaluatedHere", rule.phase() == AlertRule.AlertPhase.OBSERVE);
		return body;
	}

	// ------------------------------------------------------------------
	//  当前状态
	// ------------------------------------------------------------------

	/**
	 * 现在有什么在响。
	 *
	 * @param includeResolved 是否带上已经恢复的（默认带 —— "它曾经响过没有"是复盘的第一句话）
	 */
	@GetMapping
	public Map<String, Object> list(@RequestParam(defaultValue = "true") boolean includeResolved,
			@RequestParam(defaultValue = "50") int limit) {
		List<AlertEvent> firing = store.firing();
		List<AlertEvent> events = new ArrayList<>(firing);
		if (includeResolved) {
			for (AlertEvent event : store.recent(Math.max(1, Math.min(limit, 500)))) {
				if (event.state() == AlertEvent.State.RESOLVED) {
					events.add(event);
				}
			}
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("openCount", store.openCount());
		body.put("firingCount", firing.size());
		body.put("acknowledgedCount", firing.stream()
				.filter(e -> e.state() == AlertEvent.State.ACKNOWLEDGED).count());
		body.put("worstSeverity", firing.isEmpty() ? null : firing.get(0).severity().name());
		body.put("events", events.stream().map(this::event).toList());
		body.put("writeProtected", !writeToken.isBlank());
		return body;
	}

	// ------------------------------------------------------------------
	//  跑一轮评估
	// ------------------------------------------------------------------

	/**
	 * 跑一轮评估。
	 *
	 * @param days 时间窗（天）
	 * @param dryRun 只算不写（给"我只想看现在什么状态"这种纯粹的读用）
	 */
	@GetMapping("/sweep")
	public Map<String, Object> sweep(@RequestParam(defaultValue = "7") int days,
			@RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
		if (dryRun) {
			RuntimeSnapshot snapshot = query.collect(days);
			List<AlertEvaluator.Outcome> outcomes = AlertEvaluator.evaluate(snapshot,
					AlertRule.AlertPhase.OBSERVE);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("ok", true);
			body.put("dryRun", true);
			body.put("window", snapshot.windowLabel());
			body.put("wouldFire", outcomes.stream()
					.filter(o -> o.kind() == AlertEvaluator.Outcome.Kind.FIRING)
					.map(this::outcome).toList());
			body.put("quiet", outcomes.stream()
					.filter(o -> o.kind() == AlertEvaluator.Outcome.Kind.QUIET)
					.map(o -> o.rule().code()).toList());
			body.put("skipped", outcomes.stream()
					.filter(o -> o.kind() == AlertEvaluator.Outcome.Kind.SKIPPED)
					.map(this::outcome).toList());
			body.put("note", "dryRun：这一轮**没有写库**，所以「新响 / 恢复」分不出来 —— "
					+ "那两种要跟上一轮比才知道。要看真实的状态变化，去掉 dryRun。");
			return body;
		}
		AlertRecorder.Sweep sweep = recorder.evaluate(days);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("dryRun", false);
		body.put("window", sweep.snapshot().windowLabel());
		body.put("openCount", sweep.openCount());
		body.put("fired", sweep.fired().stream().map(this::event).toList());
		body.put("resolved", sweep.resolved().stream().map(this::event).toList());
		// 【"仍在响"必须单独一格，而且要给出条数】
		// 混进 fired 里的话，"这一轮新响了 12 条"会把一个持续了一周的老问题
		// 报成今天才出现的 —— 而那正好是值班的人判断"要不要叫醒别人"的依据
		body.put("ongoingCount", sweep.ongoing().size());
		body.put("ongoing", sweep.ongoing().stream().map(this::event).toList());
		return body;
	}

	// ------------------------------------------------------------------
	//  确认
	// ------------------------------------------------------------------

	/**
	 * 确认一条告警（"我看过了"）。
	 *
	 * <p>两种情况要说两句不同的话：指纹不存在 → 404（你点的那条没了）；
	 * 它已经恢复了 → 409（恢复的告警不该被"确认"成已处理，
	 * 那会把一条已经结束的历史标成"人处理过"）。
	 */
	@PostMapping("/{fingerprint}/ack")
	public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable String fingerprint,
			@RequestBody(required = false) Map<String, String> body,
			@RequestHeader(name = "X-Alert-Token", required = false) String token) {
		requireWriteToken(token);
		String by = body == null ? null : body.get("by");
		Optional<AlertEvent> existing = store.find(fingerprint);
		if (existing.isEmpty()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false,
					"message", "没有这条告警：" + fingerprint + " —— 它可能已经被恢复了（恢复也是改状态，不是删行），"
							+ "也可能你手上的页面是旧的"));
		}
		if (existing.get().state() == AlertEvent.State.RESOLVED) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false,
					"message", "这条告警已经恢复了，不需要确认 —— 已恢复的不该被标成「人处理过」，"
							+ "那会把一条已经结束的历史说成是被人处理的"));
		}
		Optional<AlertEvent> acked = store.acknowledge(fingerprint, by, Instant.now());
		return acked.<ResponseEntity<Map<String, Object>>>map(event -> ResponseEntity.ok(Map.of("ok", true,
				"event", event(event)))).orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
						.body(Map.of("ok", false, "message", "确认没有执行（状态在两次读之间变了）—— 刷新后重试")));
	}

	// ------------------------------------------------------------------
	//  组装
	// ------------------------------------------------------------------

	private Map<String, Object> event(AlertEvent event) {
		return eventWithRule(event).orElseGet(() -> rawEvent(event));
	}

	/** 带上判据目录里的标题与做法（**不在告警里重写一遍**：判据改了，历史告警也跟着显示新说明）。 */
	private Optional<Map<String, Object>> eventWithRule(AlertEvent event) {
		return AlertRule.byCode(event.ruleCode()).map(rule -> {
			Map<String, Object> body = rawEvent(event);
			body.put("title", rule.title());
			body.put("action", rule.action());
			body.put("executedBy", rule.executedBy());
			body.put("severityMeaning", rule.severity().meaning());
			return body;
		});
	}

	private Map<String, Object> rawEvent(AlertEvent event) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("fingerprint", event.fingerprint());
		body.put("ruleCode", event.ruleCode());
		body.put("subject", event.subject());
		body.put("severity", event.severity().name());
		body.put("severityLabel", event.severity().label());
		body.put("phase", event.phase().name());
		body.put("state", event.state().name());
		body.put("stateLabel", event.state().label());
		body.put("firstSeenAt", text(event.firstSeenAt()));
		body.put("lastSeenAt", text(event.lastSeenAt()));
		body.put("resolvedAt", text(event.resolvedAt()));
		body.put("acknowledgedAt", text(event.acknowledgedAt()));
		body.put("acknowledgedBy", event.acknowledgedBy());
		body.put("occurrences", event.occurrences());
		body.put("firingSeconds", event.firingSeconds(Instant.now()));
		body.put("observed", event.observed());
		body.put("threshold", event.threshold());
		body.put("why", event.why());
		body.put("window", event.windowLabel());
		body.put("needsAttention", event.needsAttention());
		return body;
	}

	private Map<String, Object> outcome(AlertEvaluator.Outcome outcome) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ruleCode", outcome.rule().code());
		body.put("title", outcome.rule().title());
		body.put("subject", outcome.subject());
		body.put("severity", outcome.rule().severity().name());
		body.put("observed", outcome.verdict().observed());
		body.put("threshold", outcome.verdict().threshold());
		body.put("why", outcome.verdict().why());
		body.put("note", outcome.note());
		return body;
	}

	private static String text(Instant instant) {
		return instant == null ? "" : instant.toString();
	}

	/**
	 * 写操作的令牌检查。
	 *
	 * <p>没有配置令牌时放行 —— 但**这不是"忘了配也没关系"**：
	 * {@code /rules} 与 {@code /alerts} 的响应里都带着 {@code writeProtected: false}，
	 * 于是"当前写入未受保护"在界面上是一行看得见的字，而不是一个需要读配置才知道的事实。
	 */
	private void requireWriteToken(String token) {
		if (writeToken.isBlank()) {
			return;
		}
		if (!writeToken.equals(token)) {
			throw new AlertWriteForbiddenException(
					"这个部署配置了 ai.alert.write-token —— 写操作必须带上请求头 X-Alert-Token");
		}
	}

	/** 令牌不对 → 403。 */
	public static class AlertWriteForbiddenException extends RuntimeException {

		public AlertWriteForbiddenException(String message) {
			super(message);
		}

	}

	@ExceptionHandler(AlertWriteForbiddenException.class)
	public ResponseEntity<Map<String, Object>> handleForbidden(AlertWriteForbiddenException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false, "message", e.getMessage()));
	}

	@ExceptionHandler(JdbcAlertStore.AlertStoreException.class)
	public ResponseEntity<Map<String, Object>> handleStore(JdbcAlertStore.AlertStoreException e) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("ok", false, "message", e.getMessage()));
	}

}
