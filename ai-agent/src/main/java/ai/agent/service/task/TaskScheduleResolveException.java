package ai.agent.service.task;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * <b>描述模板这一次没解析出来</b> —— 定时任务到点派活时，把模板交给模型那一步失败了。
 *
 * <h2>它为什么单独一个类型，而不是复用 TaskInvalidException</h2>
 * 两者的处置完全不同，而调用点必须分得开：
 * <ul>
 *   <li>{@code TaskInvalidException}（400）是"请求里填的东西有问题"——
 *       用户的下一步是<b>去改</b>那条规则；</li>
 *   <li>这一条是"上游没跑成"—— 用户什么都没填错，下一步是<b>等一下再试</b>。
 *       把它报成 400 会让用户去改一句本来没问题的描述。</li>
 * </ul>
 *
 * <h2>为什么是 502，以及那句话是怎么送到客户端的</h2>
 * 上游（模型服务）没有给出可用的回答，属于网关那一类失败：请求本身没有错，错在它依赖的下游。
 * 502 与 500 的区别对排障有用 —— 500 会让人去看本应用的堆栈，
 * 而这一条的原因在 {@code ai.agent.web.ErrorResponseAdvice#handleScheduleResolve} 里被写进响应体。
 *
 * <p>⚠️ {@code @ResponseStatus} 在这里<b>只声明状态码</b>，不负责带话：
 * 它的 {@code reason} 属性会盖掉异常自己的 message，而实测（本项目 {@code ErrorResponseAdvice}
 * 的类注释里记着这一段）{@code ResponseStatusExceptionResolver} 只设状态码、
 * 不经过 Boot 的 {@code /error}，所以 {@code server.error.include-message} 对它无效 ——
 * 一个字都到不了客户端。因此这一族由 {@code ErrorResponseAdvice} 显式接住。
 *
 * <h2>两条触发路径各自的落点</h2>
 * <ul>
 *   <li><b>到点触发</b>：{@code AgentTaskScheduleRunner} 接住它、记一行 WARN、
 *       <b>这一次不派活</b>，规则留到下一个周期重试（这条链路上"描述就是任务本身"，
 *       拿模板原文派出去会让 Agent 去猜一个日子，而猜错是干错事）；</li>
 *   <li><b>手工触发</b>（界面上的「现在跑一次」）：它冒到 HTTP 层变成 502 ——
 *       用户当场就知道"这次没派活、原因是模板没解析出来"，而不是看到一个假成功。</li>
 * </ul>
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class TaskScheduleResolveException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public TaskScheduleResolveException(String message) {
		super(message);
	}

	public TaskScheduleResolveException(String message, Throwable cause) {
		super(message, cause);
	}

}
