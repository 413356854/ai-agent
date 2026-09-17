package ai.agent.service.token;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 请求超出 Token 预算时抛出（HTTP 400）。
 *
 * <p><b>为什么继承 {@link ResponseStatusException}</b>：这样 Spring MVC 会直接把它翻译成
 * 对应的状态码，不需要在 Controller 里写 try/catch，也不必自己定义
 * {@code @ControllerAdvice} 处理器 —— 错误语义（"你的请求本身不合法，改小再来"）
 * 恰好对应 400，不该混进 500。
 *
 * <p><b>为什么把 token 账目写进 message</b>：这是这个异常最有价值的部分。
 * 用户看到的应该是这样一句话：
 * <pre>
 *   上下文预算超限：本次需要约 1500 tokens（输入 1500 + 为输出预留 2048），
 *   可用预算只有 108800（窗口 128000 × 安全边际 85%）。
 *   建议：精简提问，或新开一个会话。
 * </pre>
 * 而不是"Context length exceeded"。差别在于<b>可行动性</b>：
 * 前者告诉用户"差多少、该做什么"，后者只告诉用户"失败了"。
 * 这类"把内部计算过程翻译成用户能据以行动的一句话"的工夫，
 * 正是工程与玩具的分界线 —— 而且它也让排查变简单（日志和响应里是同一句话）。
 */
public class TokenBudgetExceededException extends ResponseStatusException {

	private final int needed;

	private final int allowed;

	public TokenBudgetExceededException(String reason, int needed, int allowed) {
		super(HttpStatus.BAD_REQUEST, reason);
		this.needed = needed;
		this.allowed = allowed;
	}

	/** 本次请求估算需要的输入 token 数。 */
	public int getNeeded() {
		return needed;
	}

	/** 扣掉安全边际与输出预留后，实际允许的输入 token 数。 */
	public int getAllowed() {
		return allowed;
	}

}
