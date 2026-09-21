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
 *   <caption>六个端点</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/agent-tasks}</td><td>"现在有哪些任务、各自在哪一列"（看板一次取回）</td></tr>
 *   <tr><td>{@code GET /api/agent-tasks/{id}}</td><td>"这条任务走到哪了、每一步是谁怎么答的、现在能做什么"</td></tr>
 *   <tr><td>{@code POST /api/agent-tasks}</td><td>"发布一条任务"</td></tr>
 *   <tr><td>{@code POST …/{id}/advance}</td><td>"推进当前事件"（发起需求确认 / 登记执行结果 / 验收通过）</td></tr>
 *   <tr><td>{@code POST …/{id}/confirms/{confirmId}/answer}</td><td>"答复这条确认项"</td></tr>
 *   <tr><td>{@code POST …/{id}/cancel}</td><td>"取消任务"（必须给原因）</td></tr>
 * </table>
 *
 * <h2>为什么写动作是 POST 而不是 PUT</h2>
 * 它们不是"把资源替换成请求体里这一份"，而是"请执行这个事件"（推进、答复、取消）——
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

	/** 看板：全部任务按列分好（列的顺序与归属见 {@code AgentTaskService.Column}）。 */
	@GetMapping
	public Map<String, Object> board() {
		AgentTaskService.Board board = tasks.board();
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
		body.put("howToAdvance", "POST /api/agent-tasks/{id}/advance | /confirms/{confirmId}/answer | /cancel");
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
			return badRequest("缺少 publisher（发布人）");
		}
		if (request.description() == null || request.description().isBlank()) {
			return badRequest("缺少 description（任务描述）");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("task", task(tasks.create(request.publisher(), request.assignee(), request.description(),
				request.agentId()).task()));
		return ResponseEntity.ok(body);
	}

	@PostMapping(path = "/{id}/advance", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> advance(@PathVariable Long id, @RequestBody(required = false) AdvanceRequest request) {
		return write(tasks.advance(id, request == null ? null : request.result()));
	}

	@PostMapping(path = "/{id}/confirms/{confirmId}/answer", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> answer(@PathVariable Long id, @PathVariable Long confirmId,
			@RequestBody AnswerRequest request) {
		return write(tasks.answer(id, confirmId, request == null ? null : request.answer()));
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

	private static Map<String, Object> task(AgentTask task) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", task.id());
		row.put("publisher", task.publisher());
		row.put("assignee", task.assignee() == null ? "" : task.assignee());
		row.put("description", task.description());
		row.put("status", task.status().name());
		row.put("statusLabel", task.status().label());
		row.put("currentExecutor", task.currentExecutor() == null ? "" : task.currentExecutor());
		row.put("currentStep", task.currentStep());
		row.put("agentId", task.agentId());
		// 结果与取消原因刻意保留 null：它们的"还没有"是有含义的，
		// 界面据此不画出那一行，而不是画一行空白（与 MapperRows 的两条转换纪律一致）
		row.put("result", task.result());
		row.put("cancelReason", task.cancelReason());
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

	/** 发布任务的请求体。{@code assignee} 与 {@code agentId} 可空（先建任务、后指派）。 */
	public record CreateRequest(String publisher, String assignee, String description, Long agentId) {
	}

	/** 推进请求体。{@code result} 只在「执行中 → 待验收」那一步有意义（可空）。 */
	public record AdvanceRequest(String result) {
	}

	/** 答复请求体。{@code answer} 是用户选的选项或自由回复（必填，判据在服务层）。 */
	public record AnswerRequest(String answer) {
	}

	/** 取消请求体。{@code reason} 必填（判据在服务层）。 */
	public record CancelRequest(String reason) {
	}

}
