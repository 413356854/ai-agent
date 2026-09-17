package ai.agent.service.support.chat;

import ai.agent.config.ChatCallProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * 重试策略：<b>判定"该不该重试" + 算"等多久再重试"</b>。
 *
 * <p>为什么把这些逻辑单独拎出来、而不是直接塞在 {@code Flux.retryWhen(...)} 的 lambda 里：
 * <ul>
 *   <li>这两件事是<b>纯函数</b>（给异常返回是/否，给次数返回时长），可以脱离 Spring 上下文、
 *       脱离模型、脱离网络单测 —— 重试策略出错是最难在联调里发现的（要么重试了不该重试的，
 *       要么该重试时一声不吭），必须有确定性的测试兜住；</li>
 *   <li>它们是最需要被复用的部分：阶段三的 Agent 工具调用同样要"瞬时故障退避重试、
 *       参数错误立即失败"，届时直接注入这个类即可，不必重写一份。</li>
 * </ul>
 *
 * <p>一句话原则：<b>只重试"等一会儿再来就可能成功"的失败</b>。
 * 瞬时故障（限流、5xx、超时）值得等；请求本身有问题（4xx、参数错、schema 不合法）
 * 重试多少次结果都一样，只会浪费配额并把真正的配置错误掩盖掉。
 */
@Component
public class ChatRetryPolicy {

	private final ChatCallProperties properties;

	public ChatRetryPolicy(ChatCallProperties properties) {
		this.properties = properties;
	}

	/**
	 * 这个失败值不值得重试。
	 *
	 * <p>判定来源有两类，覆盖模型调用链上实际会出现的失败：
	 * <ol>
	 *   <li><b>HTTP 状态码</b> —— Spring AI 的 HTTP 客户端（RestClient / WebClient）在
	 *       非 2xx 时会抛 {@link RestClientResponseException} 及其子类
	 *       （{@code HttpClientErrorException} 4xx / {@code HttpServerErrorException} 5xx），
	 *       状态码可直接读出。这是最可靠的判据，优先用它。</li>
	 *   <li><b>超时异常</b> —— Reactor 的 {@code Flux.timeout(...)} 抛
	 *       {@link TimeoutException}，网络层的读超时也常是它。
	 *       超时是"没有结论"而不是"结论是失败"，重试一次往往就好了。</li>
	 * </ol>
	 *
	 * <p>刻意<b>不</b>把 {@code IllegalArgumentException}、JSON 解析失败、
	 * 401/403 这类"确定性失败"算进来：它们重试的期望收益是零。
	 */
	public boolean isRetryable(Throwable error) {
		if (error == null) {
			return false;
		}
		Throwable cause = rootCause(error);

		if (cause instanceof TimeoutException) {
			return true;
		}

		HttpStatusCode status = statusOf(cause);
		if (status != null) {
			return properties.getRetry().getRetryableStatusCodes().contains(status.value());
		}

		return false;
	}

	/**
	 * 第 {@code retryIndex} 次重试（从 1 开始）之前应该等多久。
	 *
	 * <p>算法：{@code min(initialBackoff × multiplier^(retryIndex-1), maxBackoff)}，再乘一个
	 * 随机抖动。指数增长是为了给下游留出恢复时间（第 1 次等 0.5s 就够，第 3 次还等 0.5s
	 * 等于在打一个刚倒下的服务）；maxBackoff 截断是为了别让用户等到失去耐心；
	 * 抖动是为了避免"所有客户端在同一毫秒一起重试"的重试墙。
	 *
	 * <p>抖动用 {@link ThreadLocalRandom}：策略是每次请求独立使用的，
	 * 且只在"决定退避时长"这一刻调用，没有可观测的竞争问题。
	 * 返回值保证 {@code >= 0}（抖动系数配成负数时也兜住，不让它抛异常）。
	 */
	public Duration delayForAttempt(int retryIndex) {
		if (retryIndex < 1) {
			retryIndex = 1;
		}
		ChatCallProperties.Retry retry = properties.getRetry();

		double calculatedMs = retry.getInitialBackoff().toMillis()
				* Math.pow(retry.getMultiplier(), retryIndex - 1);

		// 先按上限截断，再加抖动：反过来的话抖动会让结果越过上限
		double cappedMs = Math.min(calculatedMs, retry.getMaxBackoff().toMillis());

		// 抖动系数夹到 [0, 1)：超过 1 会让包络变成 [负数, 2 倍]，下限会退化成 0
		double jitter = Math.clamp(retry.getJitter(), 0.0, 1.0);
		if (jitter > 0) {
			// nextDouble(1 - j, 1 + j) → 期望 1.0，包络 ±j
			cappedMs = cappedMs * ThreadLocalRandom.current().nextDouble(1 - jitter, 1 + jitter);
		}

		return Duration.ofMillis(Math.max(0, Math.round(cappedMs)));
	}

	/**
	 * 剥到最内层的 cause。
	 *
	 * <p>为什么必须剥：Reactor 会用 {@code Exceptions.propagate()} 把受检异常包进
	 * {@code ReactiveException}，Spring AI 也会加自己的包装层，最外层往往看不出真实原因。
	 * 上限 10 层是为了防异常链自引用（真的出现过 `cause == self` 导致死循环的案例）。
	 */
	public static Throwable rootCause(Throwable error) {
		Throwable cause = error;
		for (int depth = 0; depth < 10; depth++) {
			if (cause.getCause() == null || cause.getCause() == cause) {
				return cause;
			}
			cause = cause.getCause();
		}
		return cause;
	}

	/** 从异常链里提取 HTTP 状态码；不是 HTTP 错误则返回 null。 */
	private static HttpStatusCode statusOf(Throwable cause) {
		if (cause instanceof RestClientResponseException httpError) {
			return httpError.getStatusCode();
		}
		return null;
	}

}
