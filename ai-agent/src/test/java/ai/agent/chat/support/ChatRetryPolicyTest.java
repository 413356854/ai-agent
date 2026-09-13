package ai.agent.chat.support;

import ai.agent.config.ChatCallProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重试策略单测。
 *
 * <p>为什么要专门测这个"纯函数"：重试策略错了<b>很难在联调里发现</b> ——
 * 多试了不该试的（例如 401）只是慢一点、看不出异常；该试的没试（例如 429）
 * 则表现为"偶发失败"，会被归因成"模型不稳定"而永远查不到根因。
 * 所以判定逻辑必须用确定性断言钉死，尤其是"4xx 一律不重试"这条红线。
 */
class ChatRetryPolicyTest {

	private final ChatCallProperties properties = new ChatCallProperties();

	private final ChatRetryPolicy policy = new ChatRetryPolicy(properties);

	@Test
	@DisplayName("退避按指数增长：500ms → 1s，且抖动不越出 ±20% 包络")
	void backoffGrowsExponentiallyWithinJitterEnvelope() {
		// 多次采样：抖动是随机的，单次断言只能验证"落在包络内"，
		// 采样能顺带发现"抖动实现写成了固定值"这类退化成确定性的 bug
		for (int i = 0; i < 200; i++) {
			assertThat(policy.delayForAttempt(1).toMillis()).isBetween(400L, 600L);
			assertThat(policy.delayForAttempt(2).toMillis()).isBetween(800L, 1200L);
			// 未到上限：4 × 500ms = 2000ms，包络 [1600, 2400]
			assertThat(policy.delayForAttempt(3).toMillis()).isBetween(1600L, 2400L);
		}
	}

	@Test
	@DisplayName("退避被 maxBackoff 截断：指数涨到第 5 次也不会超过上限")
	void backoffIsCappedByMaxBackoff() {
		properties.getRetry().setMaxBackoff(Duration.ofMillis(1000));

		// 第 5 次计算值 8s，不加截断的话用户要干等 8 秒
		for (int i = 0; i < 200; i++) {
			assertThat(policy.delayForAttempt(5).toMillis()).isBetween(800L, 1200L);
		}
	}

	@Test
	@DisplayName("抖动关掉后退避是确定值（排障时可以把抖动设成 0 复现时序）")
	void jitterCanBeDisabled() {
		properties.getRetry().setJitter(0.0);

		assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofMillis(500));
		assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofMillis(1000));
	}

	@Test
	@DisplayName("429 限流 与 5xx 可重试；401/403/404 这类请求本身的问题绝不重试")
	void retriesRateLimitAndServerErrorsOnly() {
		assertThat(policy.isRetryable(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();
		assertThat(policy.isRetryable(new HttpServerErrorException(HttpStatus.BAD_GATEWAY))).isTrue();
		assertThat(policy.isRetryable(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))).isTrue();

		// 这三条是红线：重试它们等于用 3 倍时间掩盖一个必须修的配置错误
		assertThat(policy.isRetryable(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))).isFalse();
		assertThat(policy.isRetryable(new HttpClientErrorException(HttpStatus.FORBIDDEN))).isFalse();
		assertThat(policy.isRetryable(new HttpClientErrorException(HttpStatus.BAD_REQUEST))).isFalse();

		assertThat(policy.isRetryable(new IllegalArgumentException("conversationId 必填"))).isFalse();
		assertThat(policy.isRetryable(null)).isFalse();
	}

	@Test
	@DisplayName("超时可重试，且能穿透 Reactor 的异常包装层看到真因")
	void retriesTimeoutEvenWhenWrapped() {
		assertThat(policy.isRetryable(new TimeoutException())).isTrue();

		// Reactor 会把受检的 TimeoutException 包一层，最外层看不出是超时
		RuntimeException wrapped = new RuntimeException("outer", new IllegalStateException("inner", new TimeoutException()));
		assertThat(policy.isRetryable(wrapped)).isTrue();
	}

	@Test
	@DisplayName("异常链自引用不会把 rootCause 转成死循环")
	void rootCauseToleratesSelfReference() {
		RuntimeException self = new RuntimeException("self") {

			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};
		assertThat(ChatRetryPolicy.rootCause(self)).isSameAs(self);
	}

	@Test
	@DisplayName("网络层失败不重试：连不上/域名解析不了时，重试只是让用户多等三倍时间")
	void doesNotRetryWhenHostUnreachable() {
		assertThat(policy.isRetryable(new ResourceAccessException("Connection refused"))).isFalse();
	}

}
