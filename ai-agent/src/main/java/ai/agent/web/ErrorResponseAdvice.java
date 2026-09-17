package ai.agent.web;

import ai.agent.service.token.TokenBudgetExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把"带原因的 {@link ResponseStatusException}"翻译成一个<b>客户端真能读到</b>的响应体。
 *
 * <h2>这个类为什么必须存在（实测踩出来的）</h2>
 * 预算校验拒绝请求时会抛 {@link TokenBudgetExceededException}（继承自
 * {@code ResponseStatusException}），它携带了一段人话：
 * <pre>
 *   上下文预算超限：本次提问本身就超出可用预算。
 *   系统提示词约 232 tokens + 本次提问约 2592 tokens = 2824，可用预算仅 1075 tokens…
 *   建议：把问题拆小，或缩短粘贴的内容后重试。
 * </pre>
 * 而客户端实际收到的响应体是：
 * <pre>
 *   {"timestamp":"…","status":400,"error":"Bad Request","path":"/api/chat/structured"}
 * </pre>
 * <b>一个字都没过去</b>。用户只知道"400"，不知道该把问题缩到多小，也不知道该找谁。
 *
 * <h2>根因（这里有两层，第一层是错的答案）</h2>
 * <ol>
 *   <li>第一反应会去查 {@code server.error.include-message} ——
 *       Spring Boot 默认把它设成 {@code never}，理由是"内部异常信息可能泄露实现细节"。
 *       改成 {@code always} 之后……<b>还是没用</b>（实测）。</li>
 *   <li>真正的原因是：{@code ResponseStatusException} 由
 *       {@code ResponseStatusExceptionResolver} 处理，它<b>只设置响应状态码</b>，
 *       根本不经过 Boot 的 {@code BasicErrorController}（也就是 {@code /error}）。
 *       而 {@code include-message} 管的是 {@code /error} 那条路径的输出 ——
 *       两者压根不在一条链上。<b>于是那个开关对这条路径无效。</b></li>
 * </ol>
 * 所以要自己接住它。这也解释了一个更普遍的坑：
 * <b>"异常对象里有 message"和"客户端能看到 message"是两件事</b>，
 * 中间隔着序列化配置；单元测试断言前者会一路绿灯，而线上用户看到的是后者。
 *
 * <h2>为什么用 {@code @RestControllerAdvice} 而不是给每个 Controller 加 try/catch</h2>
 * 三个端点（流式 / 非流式异步 / 结构化）都要同样的行为，写在一个地方才能保证一致 ——
 * 否则最容易出现的结局是"三条链路里有一条忘了带文案"。
 */
@RestControllerAdvice
public class ErrorResponseAdvice {

	private static final Logger log = LoggerFactory.getLogger(ErrorResponseAdvice.class);

	/**
	 * 预算超限：除了 reason，额外把<b>账目数字</b>结构化返回。
	 *
	 * <p>为什么要多给一份结构化字段，而不是让前端去正则解析那句话：
	 * 界面要显示"红色提示 + 差多少"，靠解析中文句子既脆弱又难看；
	 * 有了 {@code neededTokens / allowedTokens}，前端可以直接画进度条或算差值。
	 * 这也符合本项目一贯的做法 —— <b>机器要用的数据和给人看的话分成两份</b>
	 * （参考结构化输出里"结果 + attempts 过程"的拆分）。
	 */
	@ExceptionHandler(TokenBudgetExceededException.class)
	public ResponseEntity<Map<String, Object>> handleBudgetExceeded(TokenBudgetExceededException e) {
		log.warn("预算超限已拒绝请求：需要={} 可用={}", e.getNeeded(), e.getAllowed());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", HttpStatus.BAD_REQUEST.value());
		body.put("error", "Token 预算超限");
		body.put("message", e.getReason());
		body.put("neededTokens", e.getNeeded());
		body.put("allowedTokens", e.getAllowed());
		body.put("overBy", Math.max(0, e.getNeeded() - e.getAllowed()));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body);
	}

	/**
	 * 其他带原因的 400/404 等（例如"参数必填"）：同样把 reason 送到客户端。
	 *
	 * <p>没有这一条的话，{@code requireText(...)} 抛出的"conversationId 必填"
	 * 同样只会变成一个光秃秃的 400 —— 同一类问题，不该修一半。
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> handleStatusException(ResponseStatusException e) {
		HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
		String reason = e.getReason();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", e.getStatusCode().value());
		body.put("error", status != null ? status.getReasonPhrase() : "Error");
		body.put("message", reason != null ? reason : "请求无法处理");
		return ResponseEntity.status(e.getStatusCode())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body);
	}

}
