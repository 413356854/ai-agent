package ai.agent.controller.prompt;

import ai.agent.entity.prompt.PromptChange;
import ai.agent.service.prompt.PromptConflictException;
import ai.agent.entity.prompt.PromptDiff;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptRejectedException;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.prompt.PromptValidator;
import ai.agent.entity.prompt.PromptVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * Prompt 版本管理的界面入口（阶段四第 3 件）。
 *
 * <h2>这一页要回答的问题（也是端点的划分依据）</h2>
 * 阶段四的验收口径是「Prompt 改坏<b>能回滚</b>」。拆开看是三件事：
 * <ol>
 *   <li>「现在生效的是哪一版、它长什么样」→ {@code GET /api/prompts}、{@code /{kind}}；</li>
 *   <li>「上一版是什么、这次改了什么」→ {@code GET /{kind}/versions}、{@code /diff}；</li>
 *   <li>「怎么回去」→ {@code POST /{kind}/activate}、{@code POST /{kind}/rollback}。</li>
 * </ol>
 *
 * <h2>为什么把"能不能写进去"的判断放在端点之外（在 {@code PromptValidator}）</h2>
 * 端点只做"读参数 → 调服务 → 组装 JSON"。与 Agent 控制器同一条理由：
 * 一旦这里出现业务判断，那份判断就会与服务层两处维护，然后慢慢漂移。
 * 唯一的例外是下面三个 {@code @ExceptionHandler} —— 它们翻译的是<b>协议层</b>的问题
 * （该回 400 还是 409），而不是业务规则。
 *
 * <h2>三个异常各自回什么码，以及为什么不能合并</h2>
 * <table border="1">
 *   <caption>失败信号与"重试有没有意义"</caption>
 *   <tr><th>异常</th><th>HTTP</th><th>重试有意义吗</th></tr>
 *   <tr><td>{@link PromptRejectedException}</td><td>400</td>
 *       <td>没有 —— 内容不合规，改内容才能过。回 500 会让人以为"系统坏了"然后反复重试同一个内容</td></tr>
 *   <tr><td>{@link PromptConflictException}</td><td>409</td>
 *       <td><b>有</b> —— 刷新看到新的当前版本之后再决定就行。这正是它要与 400 分开的原因</td></tr>
 *   <tr><td>名字不认识 / 版本不存在</td><td>404</td>
 *       <td>没有 —— 请求的目标不存在</td></tr>
 * </table>
 * 这与阶段一那条"分清 429/5xx 和 4xx"是同一种判断：
 * <b>重试有意义和没有意义的失败，必须给出不同的信号</b>。
 */
@RestController
@RequestMapping(path = "/api/prompts", produces = MediaType.APPLICATION_JSON_VALUE)
public class PromptController {

	private static final Logger log = LoggerFactory.getLogger(PromptController.class);

	private final PromptService service;

	public PromptController(PromptService service) {
		this.service = service;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	/**
	 * 所有受管理的提示词 + 当前生效的那一版（<b>界面进面板只打这一个请求</b>）。
	 *
	 * <p>为什么把"哪几份提示词"和"各自生效的是哪一版"合在一个端点里：
	 * 分两个端点的话，界面会先渲染一个空列表再填内容 —— 而
	 * 「列表是空的」与「这一份还没有版本」在屏幕上长得一样，读者会以为功能没生效。
	 */
	@GetMapping
	public Map<String, Object> list() {
		List<Map<String, Object>> prompts = new ArrayList<>();
		for (PromptKind kind : PromptKind.values()) {
			prompts.add(describe(kind));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("prompts", prompts);
		body.put("hotReload", hotReloadNote());
		body.put("notes", notes());
		return body;
	}

	@GetMapping("/{kind}/versions")
	public Map<String, Object> versions(@PathVariable String kind) {
		PromptKind resolved = requireKind(kind);
		List<PromptVersion> all = service.versions(resolved);
		Integer active = service.activeVersion(resolved).map(PromptVersion::version).orElse(null);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("prompt", describe(resolved));
		body.put("versions", all.stream().map(v -> summary(v, active)).toList());
		body.put("count", all.size());
		return body;
	}

	@GetMapping("/{kind}/versions/{version}")
	public Map<String, Object> version(@PathVariable String kind, @PathVariable int version) {
		PromptKind resolved = requireKind(kind);
		PromptVersion found = requireVersion(resolved, version);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("prompt", describe(resolved));
		body.put("version", full(found, isActive(resolved, version)));
		return body;
	}

	/**
	 * 版本对比（<b>差异在后端算</b>）。
	 *
	 * <p>{@code from} 省略时默认对比"上一版 → 这一版"：这是最常见的问法
	 * （"我刚改了什么"），而让界面每次自己算"上一版是几"会让这个规则两处维护。
	 */
	@GetMapping("/{kind}/diff")
	public Map<String, Object> diff(@PathVariable String kind,
			@RequestParam(name = "from", required = false) Integer from,
			@RequestParam(name = "to", required = false) Integer to) {
		PromptKind resolved = requireKind(kind);
		List<PromptVersion> all = service.versions(resolved);
		if (all.isEmpty()) {
			throw new PromptNotFoundException("「" + resolved.defaultValue() + "」还没有任何版本");
		}
		int toVersion = to != null ? to : all.get(0).version();
		int fromVersion = from != null ? from : previousOf(all, toVersion);
		PromptDiff diff = service.diff(resolved, fromVersion, toVersion);

		List<Map<String, Object>> lines = new ArrayList<>();
		for (PromptDiff.Line line : diff.lines()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("op", line.op().name());
			row.put("fromLine", line.from());
			row.put("toLine", line.to());
			row.put("text", line.text());
			lines.add(row);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", fromVersion);
		body.put("to", toVersion);
		body.put("identical", diff.identical());
		body.put("added", diff.added());
		body.put("removed", diff.removed());
		body.put("tokenDelta", diff.tokenDelta());
		body.put("fromTokenEstimate", tokenOf(resolved, fromVersion));
		body.put("toTokenEstimate", tokenOf(resolved, toVersion));
		body.put("lines", lines);
		body.put("note", "差异由后端按最长公共子序列计算：改了中间一行不会让它之后的所有行都显示成『已修改』");
		return body;
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	/**
	 * 存一个新版本（<b>不自动生效</b>）。
	 *
	 * <p>为什么用 POST 而不是 PUT：它不是幂等的 —— 同一个内容提交两次会得到两个版本
	 * （各自带各自的说明与时间）。这一点与"提示词的历史只追加"是一致的。
	 */
	@PostMapping("/{kind}/versions")
	public ResponseEntity<Map<String, Object>> create(@PathVariable String kind, @RequestBody(required = false) CreateRequest request) {
		PromptKind resolved = requireKind(kind);
		if (request == null || request.content() == null) {
			throw new PromptRejectedException("请求体里必须有 content 字段",
					PromptValidator.validate(null, null));
		}
		PromptValidator.Input input = new PromptValidator.Input(resolved, request.content(), request.note(),
				request.author(), request.forceOrDefault());
		PromptChange change = service.save(resolved, input);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("prompt", describe(resolved));
		body.put("version", full(change.saved(), isActive(resolved, change.saved().version())));
		body.put("validation", validationOf(change.result()));
		// ⚠️ 这段文本会渲染在页面的纯文本区域里，**不能写字面量星号**（那是「纯文本写 markdown 星号会原样显示」那一族）：
		// 成本看板那一轮就是这么在真后端上印出了 ** 的字面量的
		body.put("note", "已存为第 " + change.saved().version() + " 版，但尚未生效 —— "
				+ "存与生效是两次决定：先看一眼篇幅变化，再决定要不要让它生效");
		return ResponseEntity.status(HttpStatus.CREATED).body(body);
	}

	@PostMapping("/{kind}/activate")
	public Map<String, Object> activate(@PathVariable String kind, @RequestBody ActivateRequest request) {
		PromptKind resolved = requireKind(kind);
		PromptVersion switched = service.activate(resolved, request.version(), request.expectedVersion(),
				request.author());
		return result(resolved, switched, request.author(), "activate", "切换生效");
	}

	/**
	 * 回滚到某一版。
	 *
	 * <p>返回体的 {@code createdVersion} 是关键：回滚<b>会新增一版</b>。
	 * 如果哪一天有人把它改成"只挪指针"，这一格就会等于 {@code targetVersion}，
	 * 而"历史里留下了这次回滚"这件事也同时消失 —— 所以界面上与测试里都要盯着它。
	 */
	@PostMapping("/{kind}/rollback")
	public Map<String, Object> rollback(@PathVariable String kind, @RequestBody ActivateRequest request) {
		PromptKind resolved = requireKind(kind);
		PromptVersion target = requireVersion(resolved, request.version());
		PromptVersion current = service.activeVersion(resolved).orElse(null);
		boolean alreadySameContent = current != null && current.content().equals(target.content());
		if (alreadySameContent) {
			// 内容已经一模一样：再新增一版只会让历史里多一条"复制了第 N 版、而内容与当前完全相同"的记录。
			// 这个动作可以不产生副作用，这里就应该不产生 —— 但**要明说没产生**，
			// 否则读者会以为回滚失败了（现象一样，含义相反）
			Map<String, Object> body = result(resolved, current, request.author(), "rollback",
					"回滚（当前内容已与第 " + request.version() + " 版一致，未新增版本）");
			body.put("noOp", true);
			body.put("createdVersion", null);
			body.put("targetVersion", request.version());
			body.put("note", "当前生效版本的内容与第 " + request.version() + " 版完全相同，所以这次没有新增版本 —— "
					+ "无意义的复制会让历史里多出一条解释不了的记录");
			return body;
		}
		PromptVersion activated = service.rollback(resolved, request.version(), request.author(), request.note());
		// 这条文案里**两个版本号都要有**，而且实测发现缺一个会让人误读：
		//   - 「第 N 版」= 被回滚到的目标（人的意图）；
		//   - 「新版本（vM）」= 这次操作实际产生的版本。
		// 只报后者的话，读者看到"产生了一版新版本"却不知道它回到了哪一版；
		// 只报前者的话，读者会以为指针被挪回去了（而这次回滚其实在历史里留了一条记录）。
		// （这条断言最初就是我写的：先只报了后者，自检立刻指出"反馈没点名被回滚的那一版"）
		Map<String, Object> body = result(resolved, activated, request.author(), "rollback",
				"回滚：第 " + request.version() + " 版的内容被复制为新的一版（v" + activated.version() + "）并生效");
		body.put("noOp", false);
		body.put("createdVersion", activated.version());
		body.put("targetVersion", request.version());
		body.put("note", "回滚也是一次新增（不是把指针挪回去）：这样历史里留着"
				+ "「是谁、什么时候、把哪一版拉回来的」，而指针式回滚会让这段历史消失");
		return body;
	}

	// ------------------------------------------------------------------
	//  组装
	// ------------------------------------------------------------------

	private Map<String, Object> describe(PromptKind kind) {
		var active = service.activeVersion(kind);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("kind", kind.defaultValue());
		row.put("summary", kind.summary());
		row.put("classpathLocation", kind.classpathLocation());
		row.put("versionCount", service.versions(kind).size());
		row.put("activeVersion", active.map(PromptVersion::version).orElse(null));
		row.put("activeHash", active.map(PromptVersion::hash).orElse(null));
		row.put("activeChars", active.map(PromptVersion::charCount).orElse(0));
		row.put("activeTokens", active.map(PromptVersion::tokenEstimate).orElseGet(() -> service.estimateFallback(kind)));
		row.put("source", active.isPresent() ? "library" : "classpath");
		row.put("readBy", readBy(kind));
		return row;
	}

	/** 谁在读这一份 —— 让"改了哪一份会影响哪些功能"这件事在页面上一眼可见。 */
	private static String readBy(PromptKind kind) {
		return switch (kind) {
			case AGENT_LOOP -> "Agent 工具调用面板（每次运行开始时读一次）";
			case CHAT_SYSTEM -> "SSE 流式对话与结构化输出（每轮请求读一次）";
			case TASK_REVIEW -> "agent 任务判定（需求歧义判定与任务点拆分；每次判定开始时读一次）";
		};
	}

	private Map<String, Object> summary(PromptVersion v, Integer active) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("version", v.version());
		row.put("hash", v.hash());
		row.put("chars", v.charCount());
		row.put("tokens", v.tokenEstimate());
		row.put("note", v.note());
		row.put("author", v.author());
		row.put("createdAt", v.createdAt() == null ? null : v.createdAt().toString());
		row.put("forkedFrom", v.forkedFrom());
		row.put("active", active != null && active == v.version());
		return row;
	}

	private Map<String, Object> full(PromptVersion v, boolean active) {
		Map<String, Object> row = summary(v, active ? v.version() : null);
		row.put("content", v.content());
		row.put("lines", v.content() == null ? 0 : v.content().split("\n", -1).length);
		return row;
	}

	/**
	 * 把写入前那一次校验的结论转成 JSON。
	 *
	 * <p><b>刻意接收 {@code Result} 而不是重新校验一遍</b>：重算的话，"上一版"可能已经变了
	 * （有人在另一个标签页里激活了新版本），于是返回给页面的警告与实际写入时用的判据不一致 ——
	 * 而那种不一致没有任何地方会报错。
	 */
	private Map<String, Object> validationOf(PromptValidator.Result result) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("checks", result.checks().stream().map(c -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("rule", c.rule());
			row.put("level", c.level().name());
			row.put("detail", c.detail());
			return row;
		}).toList());
		body.put("warnings", result.warnings().size());
		return body;
	}

	private Map<String, Object> result(PromptKind kind, PromptVersion active, String author, String action,
			String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("prompt", describe(kind));
		body.put("activeVersion", active.version());
		body.put("activeHash", active.hash());
		body.put("activeTokens", active.tokenEstimate());
		body.put("author", author);
		body.put("action", action);
		body.put("message", message);
		return body;
	}

	/** 热更新的边界 —— 写在接口返回里，而不是只写在文档里。 */
	private static Map<String, Object> hotReloadNote() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("takesEffect", List.of(
				"下一次 Agent 运行（每次运行开始时读一次系统提示词）",
				"下一轮对话与结构化输出（每轮请求重新注入系统提示词）"));
		body.put("doesNotAffect", List.of(
				"已经在跑的那次运行 —— 它的系统消息在运行开始时就已经进了消息列表。"
						+ "这是刻意的：中途换提示词会让同一份对话里前后两轮遵循不同指令，"
						+ "于是『它为什么这么选』变得无法解释",
				"已经算出去的那次 token 预算 —— 预算随正文长度变，所以它是每次重新问的，不是启动时算的"));
		return body;
	}

	private static List<String> notes() {
		return List.of(
				"历史只追加：改一版等于新增一版，回滚也等于新增一版 —— 所以任何一次改动都能回答「是谁、什么时候、为什么」",
				"存与生效是两次决定：保存后的新版本默认不生效（一次粘贴失误不会直接作用到线上）",
				"提示词改坏了不会报错，只会让答案质量下降 —— 所以校验放在写入之前，而不是等跑起来",
				"这一页的改动对已经跑过的历史没有影响：token 数字与金额都留在各自的表里，不会被追改");
	}

	private PromptKind requireKind(String name) {
		PromptKind kind = PromptKind.byName(name);
		if (kind == null) {
			throw new PromptNotFoundException("没有名为「" + name + "」的提示词；可用的是 "
					+ java.util.Arrays.stream(PromptKind.values()).map(PromptKind::defaultValue).toList());
		}
		return kind;
	}

	private PromptVersion requireVersion(PromptKind kind, int version) {
		return service.versions(kind).stream().filter(v -> v.version() == version).findFirst()
				.orElseThrow(() -> new PromptNotFoundException(
						"「" + kind.defaultValue() + "」没有第 " + version + " 版"));
	}

	private boolean isActive(PromptKind kind, int version) {
		return service.activeVersion(kind).map(v -> v.version() == version).orElse(false);
	}

	private int tokenOf(PromptKind kind, int version) {
		return requireVersion(kind, version).tokenEstimate();
	}

	/** 上一版是哪一版：列表是降序的，所以"比目标小的第一个"就是它。 */
	private static int previousOf(List<PromptVersion> descending, int version) {
		int fallback = descending.get(descending.size() - 1).version();
		for (PromptVersion v : descending) {
			if (v.version() < version) {
				return v.version();
			}
		}
		// 目标就是最早那一版：拿最新的一版当起始，差异会显示成"全部删掉/全部新增"，
		// 而那是事实（没有更早的版本可比）
		return fallback == version ? version : fallback;
	}

	// ------------------------------------------------------------------
	//  请求体与错误翻译
	// ------------------------------------------------------------------

	/**
	 * 新建版本的请求体。
	 *
	 * @param force 显式确认放行"警告级"的判据（例如确实要把提示词压得很短）。
	 *              用 {@code Boolean} 而不是 {@code boolean}：字段缺失要能区分成"没表态"，
	 *              而不是被反序列化成 {@code false}（与结构化输出那条"double 会把字段缺失
	 *              变成 0.0"是同一类坑）
	 */
	public record CreateRequest(String content, String note, String author, Boolean force) {

		boolean forceOrDefault() {
			return Boolean.TRUE.equals(force);
		}
	}

	/**
	 * 激活 / 回滚的请求体。
	 *
	 * @param expectedVersion 你以为的当前生效版本；null 表示不做并发检查。
	 *                        界面**应当**总是带上它 —— 不带就等于默认"我在看页面的时候没人动过"，
	 *                        而那正是并发覆盖发生的前提
	 */
	public record ActivateRequest(int version, Integer expectedVersion, String author, String note) {
	}

	/** 目标不存在（提示词种类或版本号）。 */
	public static class PromptNotFoundException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public PromptNotFoundException(String message) {
			super(message);
		}

	}

	@ExceptionHandler(PromptNotFoundException.class)
	public ResponseEntity<Map<String, Object>> onNotFound(PromptNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("not-found", e.getMessage(), null));
	}

	@ExceptionHandler(PromptRejectedException.class)
	public ResponseEntity<Map<String, Object>> onRejected(PromptRejectedException e) {
		Map<String, Object> checks = new LinkedHashMap<>();
		checks.put("checks", e.result().checks().stream().map(c -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("rule", c.rule());
			row.put("level", c.level().name());
			row.put("detail", c.detail());
			return row;
		}).toList());
		// 400 而不是 500：内容是调用方能改的东西。回 500 会让人以为"系统坏了"然后反复重试同一个内容
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("rejected", e.getMessage(), checks));
	}

	@ExceptionHandler(PromptConflictException.class)
	public ResponseEntity<Map<String, Object>> onConflict(PromptConflictException e) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("expectedVersion", e.expectedVersion());
		extra.put("actualVersion", e.actualVersion());
		// 409 而不是 400：**刷新之后重试是有意义的**。这正是它必须与"内容不合规"分开的原因
		return ResponseEntity.status(HttpStatus.CONFLICT).body(error("conflict", e.getMessage(), extra));
	}

	private static Map<String, Object> error(String code, String message, Map<String, Object> extra) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", code);
		body.put("message", message);
		if (extra != null) {
			body.putAll(extra);
		}
		log.debug("提示词端点返回 {}：{}", code, message);
		return body;
	}

}
