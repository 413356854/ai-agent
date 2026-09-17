package ai.agent.controller.canary;

import ai.agent.service.canary.CanaryDecision;
import ai.agent.service.canary.CanaryService;
import ai.agent.service.canary.CanaryStore;
import ai.agent.service.canary.DefaultCanaryService;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.prompt.PromptVersion;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 灰度发布的端点（阶段四第 5 件）。
 *
 * <h2>四个端点，两读两写</h2>
 * <table border="1">
 *   <caption>端点与它回答的问题</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/canary}</td><td>"现在有没有在放量、配置是多少、<b>实际分到了多少</b>"</td></tr>
 *   <tr><td>{@code POST /api/canary/start}</td><td>开一次实验</td></tr>
 *   <tr><td>{@code POST /api/canary/stop}</td><td>停一次实验（<b>必须写清为什么停</b>）</td></tr>
 * </table>
 *
 * <h2>★ 为什么"实际分到了多少"与"配置了多少"必须在同一个响应里</h2>
 * 这是本端点的核心取舍，也是这一整件最容易做错的地方。
 * 一个只显示配置的接口会让人以为实验在进行中 ——
 * 而"配置 20%、实际 0 份"是一件完全可能发生、并且<b>不报任何错</b>的事
 * （分桶函数没接上、运行 id 恒为同一个值、窗口太短）。
 * 那次实验等于没做，而使用者以为做过了 —— 并据此得出"新版没有区别"这个结论。
 *
 * <p>所以响应里同时给出：配置的重量、参与分流的运行数、候选/对照各多少、
 * 逐版本的运行数、以及那句<b>样本够不够</b>的判断。
 * 这与成本看板那条"算不出来要显示「算不出」而不是 ¥0.00"是同一条纪律：
 * <b>一个数字必须带着它的口径与它的证据一起交付</b>。
 */
@RestController
@RequestMapping(path = "/api/canary", produces = MediaType.APPLICATION_JSON_VALUE)
public class CanaryController {

	/** 观测窗口默认 7 天（与成本看板、告警评估同一个默认值 —— 三处不一样会让人对不上数）。 */
	private static final int DEFAULT_DAYS = 7;

	/**
	 * 默认的提示词种类。
	 *
	 * <h2>⚠️ 它必须**算出来**，不能手写一个字符串（这一条是实测踩出来的）</h2>
	 * 第一版这里写的是字面量 {@code "agent-loop"} —— 那是 <b>Java 枚举常量的名字</b>，
	 * 而接口上用的名字是 {@link PromptKind#defaultValue()}（{@code "system-agent-tools"}）。
	 * 于是真后端上 {@code GET /api/canary} 直接 <b>400</b>：
	 * 「没有这一份提示词：「agent-loop」」。
	 *
	 * <p>而假后端照着手写的那个字符串写了一遍，所以它**绿着** ——
	 * 这正是"假数据替真代码圆了场"的又一例：
	 * 一个只在前端自检里跑过的默认值，等于从来没有被验证过。
	 * 从枚举里取之后，两边不可能再对不上。
	 */
	private static final String DEFAULT_KIND = PromptKind.AGENT_LOOP.defaultValue();

	private final CanaryService canary;

	private final PromptService prompts;

	public CanaryController(CanaryService canary, PromptService prompts) {
		this.canary = canary;
		this.prompts = prompts;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	/**
	 * 当前灰度与它的实测分布。
	 *
	 * @param kind 哪一份提示词（按名字；不认识的名字回 400 并列出可用值）
	 * @param days 观测窗口（天）
	 */
	@GetMapping
	public Map<String, Object> status(@RequestParam(defaultValue = "") String kind,
			@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {
		// 空串 = 没传 → 用从枚举算出来的默认值（**不写字面量**，见 DEFAULT_KIND 的注释）
		PromptKind promptKind = resolve(kind == null || kind.isBlank() ? DEFAULT_KIND : kind);
		Optional<CanaryService.Canary> current = canary.current(promptKind);
		CanaryService.Split split = canary.observation(promptKind, days);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("kind", promptKind.defaultValue());
		body.put("kindSummary", promptKind.summary());
		body.put("kinds", java.util.Arrays.stream(PromptKind.values()).map(PromptKind::defaultValue).toList());
		body.put("windowDays", days);
		body.put("active", current.isPresent());
		body.put("canary", current.map(this::describe).orElse(null));
		// 上一轮实验：停止之后界面上仍要能看到"当时配了多少、实测多少"——
		// 一次实验的结论不该随着"点了一下停止"而消失
		body.put("previous", current.isPresent() ? null : latest(promptKind).map(this::describe).orElse(null));

		// ---- 版本清单（开灰度要选候选版本，所以这一栏是必须的） ----
		List<Map<String, Object>> versions = prompts.versions(promptKind).stream().map(this::version).toList();
		body.put("versions", versions);
		body.put("activeVersion", prompts.activeVersion(promptKind).map(PromptVersion::version).orElse(0));
		body.put("activeFingerprint", activeFingerprint(promptKind));

		// ---- 实测分布（★ 这一栏才是这个端点的重点） ----
		body.put("split", split(split, days));
		return body;
	}

	private Optional<CanaryService.Canary> latest(PromptKind kind) {
		return canary.previous(kind);
	}

	private Map<String, Object> describe(CanaryService.Canary canary) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", canary.kind().defaultValue());
		body.put("candidateVersion", canary.candidateVersion());
		body.put("weightPercent", canary.weightPercent());
		body.put("note", canary.note());
		body.put("startedBy", canary.startedBy());
		body.put("startedAt", text(canary.startedAt()));
		body.put("active", canary.active());
		body.put("endedAt", text(canary.endedAt()));
		body.put("endedBy", canary.endedBy());
		body.put("endReason", canary.endReason());
		return body;
	}

	private Map<String, Object> version(PromptVersion version) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("version", version.version());
		body.put("hash", version.hash());
		body.put("fingerprint", version.fingerprint());
		body.put("charCount", version.charCount());
		body.put("tokenEstimate", version.tokenEstimate());
		body.put("note", version.note());
		body.put("author", version.author());
		body.put("forkedFrom", version.forkedFrom());
		body.put("createdAt", text(version.createdAt()));
		return body;
	}

	private String activeFingerprint(PromptKind kind) {
		try {
			return prompts.currentFingerprint(kind);
		}
		catch (RuntimeException e) {
			// 读不到指纹不该让整个状态页失败（那一页还有别的有用信息）——
			// 但要把"读不到"如实写出来，而不是给一个空串
			return "（读不到：" + e.getMessage() + "）";
		}
	}

	/**
	 * 分流分布的组装。
	 *
	 * <h2>为什么这里要专门给一个"样本够不够"的判断</h2>
	 * 因为比例是个<b>小样本下毫无意义</b>的数：3 份里分到 1 份既可能是 33%，
	 * 也可能只是 10% 的重量碰巧。把 33% 与配置的 10% 并排摆出来，
	 * 读的人一定会得出"分流偏了"这个结论 —— 而那是错的。
	 * 所以这里显式给出"还差几份样本"，让那句结论到不了读者眼前。
	 */
	private Map<String, Object> split(CanaryService.Split split, int days) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("candidateVersion", split.candidateVersion());
		body.put("weightPercent", split.weightPercent());
		body.put("runs", split.runs());
		body.put("participatingRuns", split.participatingRuns());
		body.put("candidateRuns", split.candidateRuns());
		body.put("stableRuns", split.stableRuns());
		body.put("observedRatio", round4(split.observedRatio()));
		body.put("minSampleForJudgement", ai.agent.service.alert.AlertRule.CANARY_MIN_SAMPLE);
		body.put("sampleEnough", split.participatingRuns() >= ai.agent.service.alert.AlertRule.CANARY_MIN_SAMPLE);
		body.put("runsStillNeeded", Math.max(0, ai.agent.service.alert.AlertRule.CANARY_MIN_SAMPLE
				- split.participatingRuns()));
		body.put("noTraffic", split.noTraffic());
		body.put("versions", split.versions().stream().map(row -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("version", row.version());
			item.put("variant", row.variant());
			item.put("variantLabel", variantLabel(row.variant()));
			item.put("runs", row.runs());
			return item;
		}).toList());
		body.put("note", splitNote(split, days));
		return body;
	}
	private String splitNote(CanaryService.Split split, int days) {
		if (split.weightPercent() == 0) {
			return "还没有开过灰度（或配置已被清掉）：这里只有 " + split.runs() + " 份运行，"
					+ "按版本分布如上。想开始实验请走 POST /api/canary/start。";
		}
		if (split.noTraffic()) {
			return "⚠️ 配置写着 " + split.weightPercent() + "% 走 v" + split.candidateVersion()
					+ "，但窗口内一份运行都没参与分流 —— 这次实验等于没做。"
					+ "两个可能：① 还没有新运行（灰度只作用于开启之后新起的运行）；"
					+ "② 分流没接上。"
					+ "★ 注意：这里数的是「实际分到几份」，不是「配置写了几成」";
		}
		if (split.participatingRuns() < ai.agent.service.alert.AlertRule.CANARY_MIN_SAMPLE) {
			return "已有 " + split.participatingRuns() + " 份参与分流，但还不到 "
					+ ai.agent.service.alert.AlertRule.CANARY_MIN_SAMPLE + " 份 —— "
					+ "样本不足时比例没有意义（10% 的重量在 5 份里一份都没分到，概率高达六成）。"
					+ "还要 " + (ai.agent.service.alert.AlertRule.CANARY_MIN_SAMPLE - split.participatingRuns())
					+ " 份才开始判断分流是否按比例发生。";
		}
		return "窗口 " + days + " 天内 " + split.participatingRuns() + " 份运行参与分流，其中 "
				+ split.candidateRuns() + " 份走了 v" + split.candidateVersion() + "（实测 "
				+ round4(split.observedRatio() * 100) + "%，配置 " + split.weightPercent() + "%）。"
				+ "⚠️ 这个数字回答的是「分流有没有按比例发生」，不是「新版更好还是更差」—— "
				+ "后者要一批问题 + 一套评判口径，本项目还没有。";
	}

	private static String variantLabel(String variant) {
		if (CanaryDecision.LABEL_CANARY.equals(variant)) {
			return "实验组（候选版）";
		}
		if (CanaryDecision.LABEL_STABLE.equals(variant)) {
			return "对照组（稳定版）";
		}
		return "未参与这次实验";
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	/**
	 * 开一次灰度。
	 *
	 * <p>请求体：{@code {"kind":"system-agent-tools","candidateVersion":4,"weightPercent":20,
	 * "note":"想验证新版对工具选择的影响","by":"zhangsan"}}。
	 *
	 * <p>校验不通过一律 400 并把判据说清（见 {@code CanaryService.start} 的四个前置条件）；
	 * "已经有一次在跑"是 409 —— 那两句话的处置不同（改参数 vs 先停掉）。
	 */
	@PostMapping("/start")
	public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
		PromptKind kind = resolve(str(body, "kind", DEFAULT_KIND));
		int candidateVersion = intOf(body.get("candidateVersion"));
		int weightPercent = intOf(body.get("weightPercent"));
		CanaryService.Canary started = canary.start(kind, candidateVersion, weightPercent, str(body, "note", null),
				str(body, "by", null));
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("ok", true);
		response.put("canary", describe(started));
		response.put("next", "新起的运行会按运行 id 的稳定哈希分流；已经在跑的不受影响。"
				+ "看实测分布走 GET /api/canary；停止走 POST /api/canary/stop（要写清为什么停）");
		return ResponseEntity.ok(response);
	}

	/** 停一次灰度（{@code endReason} 必填）。 */
	@PostMapping("/stop")
	public ResponseEntity<Map<String, Object>> stop(@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> payload = body == null ? Map.of() : body;
		PromptKind kind = resolve(str(payload, "kind", DEFAULT_KIND));
		CanaryService.Canary stopped = canary.stop(kind, str(payload, "endReason", null), str(payload, "by", null));
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("ok", true);
		response.put("canary", describe(stopped));
		response.put("next", "新的运行全部走稳定版。已经跑过的不受影响 —— "
				+ "它们的 trace 里仍然记着当时那一组，所以那次实验的数据读得出来。");
		return ResponseEntity.ok(response);
	}

	// ------------------------------------------------------------------
	//  小工具与错误
	// ------------------------------------------------------------------

	private PromptKind resolve(String name) {
		PromptKind kind = PromptKind.byName(name);
		if (kind == null) {
			// 这个名字不认识**不是**500：它是调用方写错了。列出可用值，省掉一次翻代码
			throw new CanaryService.CanaryRejectedException("没有这一份提示词：「" + name
					+ "」—— 可用的是 " + java.util.Arrays.stream(PromptKind.values())
							.map(PromptKind::defaultValue).toList());
		}
		return kind;
	}

	private static String str(Map<String, Object> body, String key, String fallback) {
		Object value = body.get(key);
		return value == null ? fallback : String.valueOf(value);
	}

	private static int intOf(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value == null) {
			// 【不默认成 0】缺字段与"显式写 0"必须区分：重量 0 是一个会被告知
			// "那等于没开"的非法值，而"你没填"是另一句话
			throw new CanaryService.CanaryRejectedException("缺少字段（weightPercent 或 candidateVersion）");
		}
		try {
			return Integer.parseInt(String.valueOf(value).trim());
		}
		catch (NumberFormatException e) {
			throw new CanaryService.CanaryRejectedException("这个值不是整数：" + value);
		}
	}

	private static String text(Instant instant) {
		return instant == null ? "" : instant.toString();
	}

	private static double round4(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	// ------------------------------------------------------------------
	//  错误
	// ------------------------------------------------------------------

	@ExceptionHandler(CanaryService.CanaryRejectedException.class)
	public ResponseEntity<Map<String, Object>> handleRejected(CanaryService.CanaryRejectedException e) {
		return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
	}

	@ExceptionHandler(CanaryStore.CanaryConflictException.class)
	public ResponseEntity<Map<String, Object>> handleConflict(CanaryStore.CanaryConflictException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false, "message", e.getMessage()));
	}

	@ExceptionHandler(DefaultCanaryService.CanaryStoppedException.class)
	public ResponseEntity<Map<String, Object>> handleStopped(DefaultCanaryService.CanaryStoppedException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false, "message", e.getMessage()));
	}

}
