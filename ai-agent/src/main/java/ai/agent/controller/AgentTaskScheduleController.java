package ai.agent.controller;

import ai.agent.entity.agent.task.AgentTaskSchedule;
import ai.agent.service.task.AgentTaskScheduleService;
import ai.agent.service.support.task.ScheduleCron;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * <b>定时任务的界面入口</b>（应用管理区 · 定时任务）。
 *
 * <h2>端点与它回答的问题</h2>
 * <table border="1">
 *   <caption>七个端点</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/agent-task-schedules}</td><td>"现在有哪些规则、各自下一次什么时候跑、
 *       调度线程池里装着几条"</td></tr>
 *   <tr><td>{@code GET /api/agent-task-schedules/{id}}</td><td>"这一条现在长什么样"</td></tr>
 *   <tr><td>{@code POST /api/agent-task-schedules}</td><td>"配一条规则"（落库 + 当场把线程装上）</td></tr>
 *   <tr><td>{@code PUT /api/agent-task-schedules/{id}}</td><td>"改这条规则"（<b>删旧线程重加</b>）</td></tr>
 *   <tr><td>{@code POST …/{id}/enabled}</td><td>"停用 / 启用"（停用会把线程从池子里摘掉）</td></tr>
 *   <tr><td>{@code POST …/{id}/trigger}</td><td>"现在就跑一次"（立刻派一条 agent 任务；
 *       不重排下一次触发）</td></tr>
 *   <tr><td>{@code DELETE /api/agent-task-schedules/{id}}</td><td>"删掉这条规则"
 *       （<b>派过任务的不能删</b> —— 那些任务还需要它作为由来）</td></tr>
 * </table>
 *
 * <h2>★ 第八个端点：{@code POST /api/agent-task-schedules/preview} —— 它不写库</h2>
 * 界面上"页面配置化定时规则"那一块靠它：用户按「每天 / 每周 / 每月」配完之后，
 * 界面把生成的那句 6 段正则发过来，拿回去的是"解析得过没有 + 中文怎么说 +
 * 接下来 5 次是什么时候"。三样里最后一样是唯一能让人当场确认"我配的对不对"的证据
 * —— 只看正则本身，{@code 0 0 9 * * MON-FRI} 与 {@code 0 0 9 * * *} 的区别要读的人自己推。
 *
 * <p>它是 <b>POST 而不是 GET</b>：请求体里是一句可能带 {@code *} {@code /} {@code ,} 的表达式，
 * 放 URL 上要过一遍百分号编码，而那种编码错了的表现是"预览说这句不合法"
 * （用户看到的错指向他自己写的表达式）。这与 {@code /api/chat/stream} 那条"提问不进 URL"
 * 是同一条判断。它<b>不写库、不装线程</b> —— 预览是"还没决定要不要保存"的动作，
 * 写进去会留下一条用户并没有确认过的规则（而且它默认是启用的，到点真的会派活）。
 *
 * <h2>为什么是 PUT 改规则、POST 做动作</h2>
 * {@code PUT /{id}} 提交的是"这条规则现在应该是什么样"（四个可改列一起给），
 * 重放两次的结果相同；而 {@code /enabled} 与 {@code /trigger} 是**动作**
 * ——"请把它停掉"、"请现在跑一次"。后者重放两次会真的派两条任务，所以它不是 PUT。
 * 与 {@code AgentTaskController} 那条"写动作是 POST"同源。
 *
 * <h2>为什么每个写动作返回的是"改完之后的那一份"</h2>
 * 与 {@code PermissionController} / {@code AgentTaskController} 同一条取舍：
 * 返回 {@code {"ok":true}} 之后界面只能重新拉一次，而那次拉取与这次写入之间
 * 可能隔着别人的一次修改 —— 页面上显示的是"别人改完之后的样子"，而用户以为是自己这次的结果。
 *
 * <h2>为什么错误码分开：400 / 404 一族都是 400</h2>
 * 这一族只有一种失败：请求里填的东西不对（正则不合法、账号不存在、规则不存在、
 * 派过任务的不能删）。它们都由 {@code TaskInvalidException} 报出，经
 * {@code ErrorResponseAdvice} 变成 <b>400 + 一句能照着改的话</b>。
 * 这里刻意<b>不</b>引入 409：规则没有状态机，两个人同时改同一条规则的后果是
 * "后写的覆盖先写的"，而那不是冲突，是最后一次写入生效（界面上有 {@code updatedAt}）。
 */
@RestController
@RequestMapping(path = "/api/agent-task-schedules", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentTaskScheduleController {

	private static final Logger log = LoggerFactory.getLogger(AgentTaskScheduleController.class);

	private final AgentTaskScheduleService schedules;

	public AgentTaskScheduleController(AgentTaskScheduleService schedules) {
		this.schedules = schedules;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	/**
	 * 规则列表 + 调度线程池。
	 *
	 * <p>{@code userId} <b>不在这里筛</b>：这一页要回答的是"现在哪些规则会跑"，
	 * 而那个答案与"我是谁"无关 —— 一条别人配的规则照样会在半夜派活。
	 * 参数收下来只为了回显（界面用它标一句"当前登录的人是谁"），
	 * 于是界面不会误以为"这一页只看得到我配的"。
	 */
	@GetMapping
	public Map<String, Object> list(@RequestParam(name = "userId", required = false) Long userId) {
		AgentTaskScheduleService.Snapshot snapshot = schedules.snapshot();
		Map<String, Object> body = new LinkedHashMap<>();
		List<Map<String, Object>> rules = new ArrayList<>();
		for (AgentTaskScheduleService.RuleView view : snapshot.rules()) {
			rules.add(rule(view));
		}
		body.put("rules", rules);
		body.put("total", rules.size());
		body.put("userId", userId);
		body.put("pool", pool(snapshot.pool()));
		body.put("howToWrite", "POST /api/agent-task-schedules（配一条）；改 PUT /{id}；"
				+ "停用启停 POST /{id}/enabled；手动跑一次 POST /{id}/trigger；删 DELETE /{id}；"
				+ "预览正则 POST /preview");
		return body;
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable Long id) {
		return rule(schedules.rule(id));
	}

	// ------------------------------------------------------------------
	//  预览（不写库）
	// ------------------------------------------------------------------

	/**
	 * 校验一句定时正则并算出未来几次触发时刻 —— <b>只读</b>。
	 *
	 * <p>它把界面上那个构造器的产物（一句 6 段正则）当输入，而不是让界面自己解释
	 * "每天 9 点"是什么意思：解析与"下一次什么时候"只有一处实现
	 * （{@code ScheduleCron}，线程池装线程时用的是同一份判断），
	 * 界面自己算一份的话，两边一旦不同就是"预览说 9 点、实际 10 点跑"。
	 *
	 * <p>⚠️ 它<b>不落库</b>，理由见类注释。用户点了保存才走 {@code POST} 那一条。
	 */
	@PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> preview(@RequestBody PreviewRequest request) {
		if (request == null || request.cron() == null || request.cron().isBlank()) {
			return badRequest("缺少 cron（定时正则）—— 格式是 6 段「秒 分 时 日 月 周」，"
					+ "例：0 0 9 * * MON-FRI（周一到周五每天 09:00:00）");
		}
		AgentTaskScheduleService.Preview preview = schedules.preview(request.cron());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("cron", preview.cron());
		body.put("description", preview.description());
		body.put("nextRuns", runs(preview.nextRuns()));
		return ResponseEntity.ok(body);
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
		if (request == null || request.publisherId() == null) {
			return badRequest("缺少 publisherId（发布人的用户 id）—— 发布人与任务人都要填"
					+ "账号表里的用户 id（GET /api/permissions/users/search 给的就是它）");
		}
		if (request.assigneeId() == null) {
			return badRequest("缺少 assigneeId（任务人的用户 id）—— 每次触发生成的那条任务派给他");
		}
		if (request.description() == null || request.description().isBlank()) {
			return badRequest("缺少 description（任务描述）—— 它同时是每次触发生成的那条任务的描述");
		}
		if (request.cron() == null || request.cron().isBlank()) {
			return badRequest("缺少 cron（定时正则）—— 格式是 6 段「秒 分 时 日 月 周」，"
					+ "例：0 0 9 * * MON-FRI；界面上可以按「每天 / 每周 / 每月」直接配出来");
		}
		// 没给 enabled 时按**启用**算：界面上配一次规则就是为了让它跑起来，
		// 默认停用会让"保存了却没反应"成为第一次使用的固定体验（理由见 db/schedule-schema.sql）
		boolean enabled = request.enabled() == null || request.enabled();
		AgentTaskScheduleService.RuleView view = schedules.create(request.publisherId(), request.assigneeId(),
				request.description(), request.cron(), enabled);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("rule", rule(view));
		body.put("message", view.rule().describe(view.cronDescription(), view.nextRuns()));
		return ResponseEntity.ok(body);
	}

	@PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
		if (request == null || request.cron() == null || request.cron().isBlank()) {
			return badRequest("缺少 cron（定时正则）—— 改规则时它也要给全（没改就原样传回来）");
		}
		if (request.assigneeId() == null) {
			return badRequest("缺少 assigneeId（任务人的用户 id）—— 改规则时它也要给全（没改就原样传回来）");
		}
		if (request.description() == null || request.description().isBlank()) {
			return badRequest("缺少 description（任务描述）—— 改规则时它也要给全（没改就原样传回来）");
		}
		boolean enabled = request.enabled() == null || request.enabled();
		AgentTaskScheduleService.RuleView view = schedules.update(id, request.assigneeId(), request.description(),
				request.cron(), enabled);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("rule", rule(view));
		body.put("message", "规则 #" + id + " 已改，旧线程已删除、按新正则重新装进调度线程池："
				+ view.rule().describe(view.cronDescription(), view.nextRuns()));
		return ResponseEntity.ok(body);
	}

	/** 停用 / 启用。停用会把线程从池子里摘掉（不是"留着线程但不做事"，见服务接口注释）。 */
	@PostMapping(path = "/{id}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable Long id,
			@RequestBody EnabledRequest request) {
		if (request == null || request.enabled() == null) {
			return badRequest("缺少 enabled（true 启用 / false 停用）—— 这一格要的是明确的一个布尔值，"
					+ "不给就分不清「要停」与「没提这件事」");
		}
		AgentTaskScheduleService.RuleView view = schedules.setEnabled(id, request.enabled());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("rule", rule(view));
		body.put("message", "规则 #" + id
				+ (request.enabled() ? " 已启用，线程已装回调度线程池" : " 已停用，线程已从调度线程池摘除")
				+ (request.enabled() && !view.nextRuns().isEmpty()
						? "；下一次触发 " + ScheduleCron.formatRun(view.nextRuns().get(0)) : ""));
		return ResponseEntity.ok(body);
	}

	/**
	 * 现在就跑一次：立刻按这条规则派一条 agent 任务。
	 *
	 * <p><b>不重排下一次触发</b>：它是"按人点的一次"，而不是"这条规则的周期变了"。
	 * 停用的规则也能手工触发 —— 那正是"先配好、确认一次再启用"的用法。
	 */
	@PostMapping(path = "/{id}/trigger", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> trigger(@PathVariable Long id, @RequestBody(required = false) TriggerRequest request) {
		AgentTaskScheduleService.TriggerResult result = schedules.trigger(id);
		log.info("定时规则 #{} 手工触发成功，派出 agent 任务 #{}（{}）", id, result.taskId(), result.statusLabel());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("scheduleId", result.scheduleId());
		body.put("taskId", result.taskId());
		body.put("statusLabel", result.statusLabel());
		body.put("message", "已按规则 #" + id + " 派出 agent 任务 #" + result.taskId() + "（" + result.statusLabel()
				+ "）—— 这次是手工点的，规则的触发周期没有变；它接下来怎么走，在 agent任务 那一页看");
		return body;
	}

	@DeleteMapping("/{id}")
	public Map<String, Object> delete(@PathVariable Long id) {
		schedules.delete(id);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("message", "规则 #" + id + " 已删除，它的线程已从调度线程池摘除"
				+ "（它派出去的那些任务不受影响 —— 它们本来就不依赖这条规则存在）");
		return body;
	}

	// ------------------------------------------------------------------
	//  装配
	// ------------------------------------------------------------------

	private ResponseEntity<Map<String, Object>> badRequest(String message) {
		return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
	}

	/**
	 * 一条规则装配成响应体。
	 *
	 * <p>发布人 / 任务人各给两份（与 {@code AgentTaskController} 逐条相同）：
	 * {@code publisherId} / {@code assigneeId} 是库里存的那一份（用户 id），
	 * {@code publisher} / {@code assignee} 是读侧关联账号表查出来的姓名、
	 * 也就是界面直接显示的那一份。两份都给是因为它们回答的不是同一个问题：
	 * "当时是谁"要能落到一个不会变的 id 上，而屏幕上要显示的是姓名（姓名可以改）。
	 *
	 * <p>四个时间字段（{@code createdAt} / {@code updatedAt} / {@code lastRunAt} /
	 * {@code nextRuns}）在这里统一成字符串：{@link Instant#toString()} 是 ISO-8601，
	 * 前端拿到就能排；而 {@code nextRuns} 走 {@code ScheduleCron#formatRun}
	 * —— 那一列给人的是"什么时候跑"，本地时刻的写法比 UTC 的 {@code Z} 好读得多。
	 *
	 * <p>⚠️ 写请求收的是上面那两格 {@code publisherId} / {@code assigneeId}（用户 id），
	 * 而不是 {@code publisher} / {@code assignee}（姓名）—— 姓名是显示名优先的那一份，
	 * 与账号表里那一行的登录名不是同一个字符串，拿它回传会查不到人。
	 */
	private Map<String, Object> rule(AgentTaskScheduleService.RuleView view) {
		AgentTaskSchedule rule = view.rule();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", rule.id());
		row.put("cron", rule.cron());
		row.put("cronDescription", view.cronDescription());
		row.put("publisherId", rule.publisherId());
		row.put("publisher", rule.publisherLabel());
		row.put("assigneeId", rule.assigneeId());
		row.put("assignee", rule.assigneeLabel());
		row.put("description", rule.description());
		row.put("enabled", rule.enabled());
		// 派过几条 + 最后一次：界面靠这两格回答"这条规则到底动过没有"。
		// ⚠️ runs=0 与 lastRunAt 为 null 是同一件事的两种写法，都如实给 ——
		// 界面据此说"一次都没派过"，而不是显示一个"0 次 / 时间空白"的组合让人猜
		row.put("runs", view.runs());
		row.put("lastRunAt", view.lastRunAt() == null ? null : view.lastRunAt().toString());
		row.put("nextRuns", runs(view.nextRuns()));
		// 这一刻它到底在不在调度线程池里。它与 enabled 是**两件事**：
		// 启用但装不上（正则被手工改坏了）时 enabled 是 true 而这里也是 false ——
		// 那正是"配好了它就是不动"的答案，所以两格都要给。
		// ⚠️ 判据是 `nextRuns` 非空（停用与"算不出下一次"都落在这一支），
		// 服务层给 nextRuns 时用的就是同一条判据 —— 见 DefaultAgentTaskScheduleService#nextRunsOf
		row.put("scheduled", !view.nextRuns().isEmpty());
		row.put("createdAt", rule.createdAt() == null ? null : rule.createdAt().toString());
		row.put("updatedAt", rule.updatedAt() == null ? null : rule.updatedAt().toString());
		return row;
	}

	private static List<String> runs(List<Instant> instants) {
		List<String> formatted = new ArrayList<>(instants.size());
		for (Instant instant : instants) {
			formatted.add(ScheduleCron.formatRun(instant));
		}
		return formatted;
	}

	private static Map<String, Object> pool(AgentTaskScheduleService.PoolStatus pool) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("poolSize", pool.poolSize());
		row.put("activeThreads", pool.activeThreads());
		row.put("poolSizeNow", pool.poolSizeNow());
		row.put("scheduled", pool.scheduled());
		row.put("cancelledPending", pool.cancelledPending());
		return row;
	}

	// ------------------------------------------------------------------
	//  请求体
	// ------------------------------------------------------------------

	/**
	 * 配一条规则的请求体。
	 *
	 * <p>{@code publisherId} / {@code assigneeId} 是人的<b>用户 id</b>（{@code agent_user.id}，
	 * 也就是 {@code GET /api/permissions/users/search} 候选项里的 {@code id}）。
	 * 落库的正是这两个值，所以这里给一个不存在的 id 是当场 400 ——
	 * 判据在服务层（{@code DefaultAgentTaskScheduleService#realUser}），它读得到账号表；
	 * 这里只拦"根本没传"。
	 *
	 * <p>它<b>不收姓名</b>（既不是登录名也不是显示名）：显示名可以重名、可以改，
	 * 而登录名与显示名在界面上是两个不同的字符串 —— 界面手上本来就有用户 id，
	 * 让它回传姓名等于把"这个名字指的是谁"又猜一次。
	 *
	 * <p>{@code cron} 是界面上那个构造器拼出来的 6 段表达式（也可以直接手写）——
	 * 界面在保存之前会先打 {@code /preview} 拿一次确认，但**判据不依赖那次预览**：
	 * 保存这一条会再校验一遍（预览与保存之间用户可能又改过输入框）。
	 *
	 * @param enabled 可空：<b>不传按启用算</b>（理由见 {@code db/schedule-schema.sql} 的头注）
	 */
	public record CreateRequest(Long publisherId, Long assigneeId, String description, String cron, Boolean enabled) {
	}

	/**
	 * 改一条规则：四个可改列<b>都要给全</b>（没改就原样传回来）。
	 *
	 * <p>刻意不做"只传要改的那一列"的部分更新：那样一来"用户把某格清空了"与
	 * "他没动这一格"在请求体里长得一样，而两者的处置完全相反
	 * （与 {@code AgentTaskController#revise} 那条同一条判断）。
	 *
	 * <p><b>发布人不在可改项里</b>：改它等于换一个人来背这条规则，
	 * 而那件事该由"停用旧的、建一条新的"表达（两条规则的由来与历史任务因此各自独立）。
	 */
	public record UpdateRequest(Long assigneeId, String description, String cron, Boolean enabled) {
	}

	/** 启停请求体。{@code enabled} 必填 —— 不给就分不清「要停」与「没提这件事」。 */
	public record EnabledRequest(Boolean enabled) {
	}

	/**
	 * 手工触发的请求体。
	 *
	 * <p>刻意<b>没有字段</b>：这一格要做的就是"按这条规则派一条任务"，没有别的参数可填。
	 * 留着这个 record 是为了让这个端点在方法签名上与其余写端点一致
	 * （客户端统一发一个空的 JSON 对象）。
	 */
	public record TriggerRequest() {
	}

	/** 预览请求体。{@code cron} 必填（判据在方法里 —— 空值先于解析被拦下）。 */
	public record PreviewRequest(String cron) {
	}

}
