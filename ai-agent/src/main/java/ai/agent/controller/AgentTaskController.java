package ai.agent.controller;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.service.task.AgentTaskService;
import ai.agent.service.task.TaskConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * <b>agent 任务的界面入口</b>（应用管理区 · agent任务）。
 *
 * <h2>端点与它回答的问题</h2>
 * <table border="1">
 *   <caption>九个端点</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/agent-tasks}</td><td>"现在有哪些任务、各自在哪一列"（看板一次取回；
 *       {@code ?userId=} 时只给"球在他手上"的那些）</td></tr>
 *   <tr><td>{@code GET /api/agent-tasks/{id}}</td><td>"这条任务走到哪了、每一步是谁怎么答的、现在能做什么"</td></tr>
 *   <tr><td>{@code POST /api/agent-tasks}</td><td>"发布一条任务"</td></tr>
 *   <tr><td>{@code POST …/{id}/advance}</td><td>"登记执行结果"（执行中 → 待验收）</td></tr>
 *   <tr><td>{@code POST …/{id}/accept}</td><td>"任务已完成"（待验收 → 已完成；顺带把这条任务的经验
 *       汇成规则补进技能，见 {@code TaskRuleDigest}）</td></tr>
 *   <tr><td>{@code POST …/{id}/reject}</td><td>"任务未完成"（待验收 → 未完成，可带一句选填的原因）</td></tr>
 *   <tr><td>{@code POST …/{id}/confirms/{confirmId}/answer}</td><td>"答复这条确认项"</td></tr>
 *   <tr><td>{@code POST …/{id}/revise}</td><td>"已修改任务"（权限不足 → 新发布，请求体里带着改过的任务描述）</td></tr>
 *   <tr><td>{@code POST …/{id}/cancel}</td><td>"取消任务"（必须给原因）</td></tr>
 * </table>
 *
 * <h2>为什么写动作是 POST 而不是 PUT</h2>
 * 它们不是"把资源替换成请求体里这一份"，而是"请执行这个事件"（推进、答复、重发、取消）——
 * 同一次请求重放两次的后果也不同（第二次会被状态机挡下并回 409，而不是得到一个相同结果）。
 *
 * <h2>为什么每个写动作返回的是"改完之后的那一份"</h2>
 * 与 {@code PermissionController} 同一条取舍：返回 {@code {"ok":true}} 之后界面只能重新拉一次，
 * 而那次拉取与这次写入之间可能隔着别人的一次修改。返回新状态就没有这个缝。
 *
 * <h2>状态冲突是 409，参数错是 400</h2>
 * 前者意味着"按钮本身点错了时机"（该刷新），后者意味着"请求里少填了东西"（该改内容）——
 * 界面上的下一步动作不同，混成一个码会让用户对着一个正确的按钮反复点。
 */
@RestController
@RequestMapping(path = "/api/agent-tasks", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentTaskController {

	private static final Logger log = LoggerFactory.getLogger(AgentTaskController.class);

	private final AgentTaskService tasks;

	public AgentTaskController(AgentTaskService tasks) {
		this.tasks = tasks;
	}

	/**
	 * 看板：任务按列分好（列的顺序与归属见 {@code AgentTaskService.Column}）。
	 *
	 * @param userId 只看"与这个人有关、而且该由他看见"的任务（{@code ?userId=<当前登录的人的用户 id>}）。
	 *        <b>不传就是不筛</b>（全量），传了但这个人不在账号表里是 <b>400</b>（不是"筛不出来"）。
	 *        判断按状态挑列（{@code AgentTaskStatus#party()}）：新发布 / 已完成 / 已取消是
	 *        发布人**或**任务人；待确认需求 / 待验收只有发布人；其余状态只有任务人。
	 */
	@GetMapping
	public Map<String, Object> board(
			@RequestParam(name = "userId", required = false) Long userId) {
		AgentTaskService.Board board = tasks.board(userId);
		List<Map<String, Object>> columns = new ArrayList<>();
		int total = 0;
		for (AgentTaskService.BoardColumn column : board.columns()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("key", column.key());
			row.put("label", column.label());
			row.put("statusLabels", column.statusLabels());
			row.put("count", column.tasks().size());
			row.put("tasks", column.tasks().stream().map(AgentTaskController::task).toList());
			columns.add(row);
			total += column.tasks().size();
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("columns", columns);
		body.put("total", total);
		// 回显这次到底筛没筛、按谁筛：界面上那句"只看与他有关的"，依据就是它（前端不自己推一份）。
		// ⚠️ 键是 userId 而不是 currentExecutorId —— 后者在任务卡片上是**另一个东西**
		// （那一格此刻球在谁手上）；两个同名的键放进同一个响应体里迟早被读错
		body.put("userId", userId);
		body.put("howToAdvance", "POST /api/agent-tasks/{id}/advance | /accept | /reject | "
				+ "/confirms/{confirmId}/answer | /cancel");
		return body;
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable Long id) {
		AgentTaskService.Detail detail = tasks.detail(id);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("task", task(detail.task()));
		body.put("confirms", detail.confirms().stream().map(AgentTaskController::confirm).toList());
		body.put("actions", detail.actions().stream()
				.map(action -> Map.of("key", action.key(), "label", action.label()))
				.toList());
		return body;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
		if (request == null || request.publisher() == null || request.publisher().isBlank()) {
			return badRequest("缺少 publisher（发布人）—— 发布人与任务人都要从用户列表里选一个真实用户");
		}
		if (request.assignee() == null || request.assignee().isBlank()) {
			return badRequest("缺少 assignee（任务人）—— 发布人与任务人都要从用户列表里选一个真实用户");
		}
		if (request.description() == null || request.description().isBlank()) {
			return badRequest("缺少 description（任务描述）");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("task", task(tasks.create(request.publisher(), request.assignee(), request.description()).task()));
		return ResponseEntity.ok(body);
	}

	/**
	 * 推进当前事件：{@code 执行中 → 待验收}（登记执行结果）。
	 *
	 * <p>验收<b>不在这里</b>：那一格的两个落点是两个端点（{@link #accept} / {@link #reject}），
	 * 理由与"它们是两个动作"同一条（见 {@code AgentTaskService#reject}）。
	 */
	@PostMapping(path = "/{id}/advance", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> advance(@PathVariable Long id, @RequestBody(required = false) AdvanceRequest request) {
		return write(tasks.advance(id, request == null ? null : request.result()));
	}

	/**
	 * 「任务已完成」：待验收 → 已完成。
	 *
	 * <p>没有要填的东西，请求体是一个空对象（客户端照其余写端点一样发 {@code {}}）——
	 * 保留 {@code consumes} 与那个请求体参数，是为了让这一族写端点在 HTTP 层长得完全一样：
	 * 少一个 {@code consumes} 之后，一个打错 Content-Type 的调用方会走到另一条解析路径上，
	 * 而那时报的是"缺请求体"，看起来像参数漏了而不是头写错了。
	 *
	 * <p>它顺带触发一次"把这条任务的经验汇成规则补进技能"（在事务提交之后的另一个线程上跑），
	 * 而那件事失败不影响这次验收的结果 —— 界面上不必为它显示任何东西。
	 */
	@PostMapping(path = "/{id}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> accept(@PathVariable Long id,
			@RequestBody(required = false) AcceptRequest request) {
		return write(tasks.accept(id));
	}

	/**
	 * 「任务未完成」：待验收 → 未完成。
	 *
	 * <p>请求体里的 {@code reason} 是<b>选填</b>的未完成原因：这一格要记的是"没做成"这个判断本身，
	 * 而原因可能一时说不清（逼着人写一句他写不出来的话，那一格就会被填成"没做完"）。
	 * 长度判据在服务层（≤ 512 字符，超了 400），这里只把请求体原样传下去。
	 */
	@PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest request) {
		return write(tasks.reject(id, request == null ? null : request.reason()));
	}

	@PostMapping(path = "/{id}/confirms/{confirmId}/answer", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> answer(@PathVariable Long id, @PathVariable Long confirmId,
			@RequestBody AnswerRequest request) {
		return write(tasks.answer(id, confirmId, request == null ? null : request.answer()));
	}

	/**
	 * 「已修改任务」：权限不足 → 新发布，<b>任务描述就在这一次请求里改</b>。
	 *
	 * <p>请求体带的是<b>改过之后的完整描述</b>，不是一段"改了什么"的说明：
	 * 重走的这一遍读的就是 {@code agent_task.description} 那一列（判需求歧义、拆任务点都以它为准），
	 * 所以描述与这次选择必须落在同一次写入里 —— 分成两次的话，
	 * "状态已经回到新发布、描述还是旧的"那一小段时间里，重走的判据是旧文本。
	 *
	 * <p><b>没有改动也要把当前那一份原样传回来</b>（空描述是 400）：界面上那一格是可编辑的输入框，
	 * "用户把它清空了"与"他没改"在请求体里长得一样，而后者该照旧走。
	 * 描述本身非空与长度上限的判据在 {@code AgentTask#revisedDescription}（实体那一份），
	 * 这里只拦"根本没传"。
	 */
	@PostMapping(path = "/{id}/revise", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> revise(@PathVariable Long id, @RequestBody(required = false) ReviseRequest request) {
		return write(tasks.revise(id, request == null ? null : request.description()));
	}

	@PostMapping(path = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> cancel(@PathVariable Long id, @RequestBody CancelRequest request) {
		return write(tasks.cancel(id, request == null ? null : request.reason()));
	}

	// ------------------------------------------------------------------
	//  错误处理
	// ------------------------------------------------------------------

	/**
	 * 状态冲突 → 409。
	 *
	 * <p>不能是 500：两个人同时看到同一条待办、都点了"答复"不是服务器故障，
	 * 而界面要能说出"这条已经被答复过了"—— 那需要它知道自己撞的是状态而不是参数。
	 */
	@ExceptionHandler(TaskConflictException.class)
	public ResponseEntity<Map<String, Object>> conflict(TaskConflictException e) {
		log.info("agent 任务的状态冲突：{}", e.getReason());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("ok", false, "error", String.valueOf(e.getReason())));
	}

	/** 业务层的 400 那一族（{@code TaskInvalidException}）由 {@code ErrorResponseAdvice} 处理。 */

	private ResponseEntity<Map<String, Object>> badRequest(String message) {
		return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
	}

	// ------------------------------------------------------------------
	//  装配
	// ------------------------------------------------------------------

	/** 写动作的回执：{@code ok} + 改完之后的那一份详情（理由见类注释）。 */
	private Map<String, Object> write(AgentTaskService.Detail detail) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("task", task(detail.task()));
		body.put("confirms", detail.confirms().stream().map(AgentTaskController::confirm).toList());
		body.put("actions", detail.actions().stream()
				.map(action -> Map.of("key", action.key(), "label", action.label()))
				.toList());
		return body;
	}

	/**
	 * 一条任务装配成响应体。
	 *
	 * <p>三个"人"各给两份：{@code publisherId} / {@code assigneeId} / {@code currentExecutorId}
	 * 是库里存的那一份（用户 id），{@code publisher} / {@code assignee} / {@code currentExecutor}
	 * 是读侧关联账号表查出来的姓名、也就是界面直接显示的那一份。两份都给是因为它们回答的
	 * 不是同一个问题："当时是谁"要能落到一个不会变的 id 上，而屏幕上要显示的是姓名
	 * （姓名可以改，见 {@code db/task-schema.sql}）。
	 *
	 * <p>⚠️ {@code currentExecutor} 是<b>文案</b>而不是库里的原值：球在 agent 手上时它给的是
	 * {@code agent#<agentId>}（那一列此刻是空的）。理由与 {@code statusLabel} 同一条 ——
	 * 用户看到的字由后端给，前端不自己拼一份，否则两处说法迟早不一致。
	 */
	private static Map<String, Object> task(AgentTask task) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", task.id());
		row.put("publisherId", task.publisherId());
		row.put("publisher", task.publisher() == null ? "" : task.publisher());
		row.put("assigneeId", task.assigneeId());
		row.put("assignee", task.assignee() == null ? "" : task.assignee());
		row.put("description", task.description());
		row.put("status", task.status().name());
		row.put("statusLabel", task.status().label());
		row.put("currentExecutorId", task.currentExecutorId());
		row.put("currentExecutor", task.executorLabel());
		row.put("currentStep", task.currentStep());
		row.put("agentId", task.agentId());
		// 结果与取消原因刻意保留 null：它们的"还没有"是有含义的，
		// 界面据此不画出那一行，而不是画一行空白（与 MapperRows 的两条转换纪律一致）
		row.put("result", task.result());
		row.put("cancelReason", task.cancelReason());
		// 未完成原因同理，而且它本身还是选填的：null 有两种来源（不是未完成 / 未完成但没写原因），
		// 两者的区别由 status 回答 —— 界面按状态决定要不要说"未填原因"这句话
		row.put("incompleteReason", task.incompleteReason());
		row.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
		row.put("updatedAt", task.updatedAt() == null ? null : task.updatedAt().toString());
		return row;
	}

	private static Map<String, Object> confirm(AgentTaskConfirm confirm) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", confirm.id());
		row.put("taskId", confirm.taskId());
		row.put("stepIndex", confirm.stepIndex());
		row.put("type", confirm.type() == null ? "" : confirm.type().name());
		row.put("typeLabel", confirm.typeLabel());
		row.put("description", confirm.description() == null ? "" : confirm.description());
		row.put("options", confirm.options());
		// answer 为 null = 还没答（那是**有含义的状态**，不编默认值）；
		// answerLabel 是画在那一格上的说法 —— 与 statusLabel / typeLabel 同一条规矩：
		// 用户看到的字由后端给，前端不再自己拼一份（两份文案迟早不一致）
		row.put("answer", confirm.answer());
		row.put("answerLabel", confirm.answerLabel());
		row.put("answered", confirm.answered());
		return row;
	}

	// ------------------------------------------------------------------
	//  请求体
	// ------------------------------------------------------------------

	/**
	 * 发布任务的请求体：三个字段<b>都必填</b>。
	 *
	 * <p>{@code publisher} / {@code assignee} 是人的<b>登录名</b>（不是用户 id）：
	 * 它们必须是账号表里真实存在的用户，落库的是查出来的那两个 id ——
	 * 判据在服务层（{@code DefaultAgentTaskService#realUser}），它读得到账号表；
	 * 这里只拦"根本没传"。{@code assignee} 不是可空的"先建任务、后指派"：
	 * 一条没有任务人的任务，第一步（需求确认）就不知道该问谁。
	 *
	 * <p><b>没有 agentId</b>：那一列由平台在第一次接手这条任务时写入
	 * （见 {@code AgentTaskProperties#agentId}）。请求体里给一个 agentId 不会被采纳 ——
	 * Jackson 忽略认不出的字段，于是"它由平台定"这件事在这一层就成立，不靠调用方自觉。
	 */
	public record CreateRequest(String publisher, String assignee, String description) {
	}

	/** 推进请求体。{@code result} 只在「执行中 → 待验收」那一步有意义（可空）。 */
	public record AdvanceRequest(String result) {
	}

	/** 答复请求体。{@code answer} 是用户选的选项或自由回复（必填，判据在服务层）。 */
	public record AnswerRequest(String answer) {
	}

	/**
	 * 「已修改任务」的请求体：{@code description} 是<b>改过之后的完整任务描述</b>
	 * （必填 —— 没改也要把当前那一份传回来，判据见 {@link #revise}）。
	 */
	public record ReviseRequest(String description) {
	}

	/**
	 * 「任务已完成」的请求体。
	 *
	 * <p>刻意<b>没有字段</b>：这一格要答的就是"做成了"这一件事，没有别的参数可填。
	 * 留着这个 record 是为了让上面那个端点在方法签名上与其余写端点一致。
	 */
	public record AcceptRequest() {
	}

	/**
	 * 「任务未完成」的请求体。
	 *
	 * @param reason 未完成原因，<b>选填</b>（没写就是没写，不编默认值 ——
	 *        卡片上据此不画那一行，而不是画一行"（未填）"）
	 */
	public record RejectRequest(String reason) {
	}

	/** 取消请求体。{@code reason} 必填（判据在服务层）。 */
	public record CancelRequest(String reason) {
	}

}
