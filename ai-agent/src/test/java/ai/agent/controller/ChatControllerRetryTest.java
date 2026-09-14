package ai.agent.controller;

import ai.agent.chat.dto.ChatRequest;
import ai.agent.chat.service.ChatService;
import ai.agent.chat.support.ChatRetryPolicy;
import ai.agent.config.ChatCallProperties;
import ai.agent.token.TokenBudgetGuard;
import ai.agent.token.impl.CharacterClassTokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.HttpServerErrorException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时与重试的行为测试（不连模型、不起 Spring 上下文）。
 *
 * <p>这一组测试覆盖的是<b>失败路径</b>——而失败路径恰恰是之前整个项目的空白：
 * 所有验证都跑在 happy path 上，"模型挂了会怎样"从来没被验证过。
 * 失败路径没法靠"跑一次看看"来确认（你没法让线上模型按需返回 503），
 * 所以必须用确定性的替身把每种失败精确复现出来。
 *
 * <p>测试直接调 {@code streamByPost}（Controller 的公开入口），而不是绕过它去测私有方法：
 * 这样断言的是<b>前端真正收到的 SSE 事件序列</b>，而不是内部实现细节。
 */
class ChatControllerRetryTest {

	private final ChatCallProperties properties = new ChatCallProperties();

	private StubChatService service;

	private ChatController controller;

	private ChatMemoryRepository repository;

	/** 新控制器 + 新记忆 + 固定策略，避免每个测试互相污染（JUnit 每个测试都会新建实例） */
	private StubChatService newController() {
		this.repository = new InMemoryChatMemoryRepository();
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(repository)
				.maxMessages(20)
				.build();
		this.service = new StubChatService(chatMemory);
		// 预算守卫用真实实现：这些超时/重试测试的上下文都很短，
		// 走的是"放行"路径，不会干扰重试行为；但换来的是"两条链路真的能共存"这个事实
		TokenBudgetGuard budgetGuard = new TokenBudgetGuard(properties, new CharacterClassTokenEstimator(properties),
				repository, TokenBudgetGuard.DEFAULT_SYSTEM_PROMPT_LOCATION);
		this.controller = new ChatController(service, new ChatRetryPolicy(properties), properties, repository,
				budgetGuard);
		return service;
	}

	private static ChatRequest request() {
		return new ChatRequest("conv-1", "你好", 0.3);
	}

	@Test
	@DisplayName("首次调用 503、第二次成功 → 前端只收到一份完整内容，绝不重复")
	void retriesBeforeFirstTokenAndNeverDuplicatesContent() {
		properties.getRetry().setInitialBackoff(Duration.ofMillis(30));
		properties.getRetry().setJitter(0.0);

		newController()
				.attemptStream(() -> Flux.error(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
				.attemptDeltas(() -> delta("你"), () -> delta("好"));

		List<ServerSentEvent<Object>> events = collect();
		List<String> content = contents(events);
		List<String> errors = errors(events);

		assertThat(content).containsExactly("你", "好");
		assertThat(errors).isEmpty();

		// 累计文本唯一 —— 这才是"没有发生两段拼接"的真正证据
		assertThat(String.join("", content)).isEqualTo("你好");

		// 重试确实发生了：服务被调了 2 次
		assertThat(service.attempts()).isEqualTo(2);
	}

	@Test
	@DisplayName("重试耗尽后报真实原因（HTTP 503），而不是 Reactor 那句没信息量的 Retries exhausted")
	void surfacesRealCauseAfterRetriesExhausted() {
		properties.getRetry().setMaxAttempts(2);
		properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
		properties.getRetry().setJitter(0.0);

		newController().fixed(3, () -> Flux.error(
				new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)));

		List<ServerSentEvent<Object>> events = collect();
		List<String> errors = errors(events);

		// 关键点：到底试了几次（1 次原始 + 2 次重试 = 3 次）
		assertThat(service.attempts()).isEqualTo(3);

		// 只发一条 error，不能因为重试了 3 次就给前端发 3 条
		assertThat(errors).hasSize(1);
		assertThat(errors.get(0)).contains("503").doesNotContain("Retries exhausted");

		// 失败路径不该有 done 事件（否则前端会显示一个 empty usage 的用量行）
		assertThat(eventNames(events)).doesNotContain("done");
	}

	@Test
	@DisplayName("已经吐过内容之后再失败 → 不重试，避免出现两段拼接的回答")
	void doesNotRetryAfterContentAlreadyEmitted() {
		properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
		properties.getRetry().setJitter(0.0);

		newController()
				.attemptDeltas(() -> delta("第一段"))
				.attemptStream(() -> Flux.error(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
				.always(() -> Flux.just(delta("第二段（不该出现在这里）")));

		List<String> content = contents(collect());

		assertThat(service.attempts()).isEqualTo(1);
		assertThat(String.join("", content)).isEqualTo("第一段");
		assertThat(content).noneMatch(s -> s.contains("不该出现"));
	}

	@Test
	@DisplayName("首字超时（模型一直不响应）会触发重试，而不是让页面永远停在「正在思考…」")
	void retriesWhenFirstTokenTimesOut() {
		properties.getTimeout().setFirstToken(Duration.ofMillis(150));
		properties.getTimeout().setIdle(Duration.ofSeconds(5));
		properties.getRetry().setInitialBackoff(Duration.ofMillis(10));

		// 第一次订阅后一直不产出任何元素，也没有结束 —— 正是"模型挂起"的样子
		newController()
				.attemptStream(Flux::never)
				.attemptDeltas(() -> delta("终于来了"));

		long startedAt = System.nanoTime();
		List<String> content = contents(collect());
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

		assertThat(content).containsExactly("终于来了");
		assertThat(service.attempts()).isEqualTo(2);
		// 证明是超时把它从"永久挂起"里捞出来的（超时设的是 150ms，不得早于它失败）
		assertThat(elapsedMs).isGreaterThanOrEqualTo(150L);
	}

	@Test
	@DisplayName("流正常结束时会带上 token 用量（重试后取成功那一次的用量）")
	void doneEventCarriesUsageFromSuccessfulAttempt() {
		properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
		properties.getRetry().setJitter(0.0);

		newController()
				.attemptStream(() -> Flux.error(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
				.attemptDeltas(() -> delta("好"));

		List<ServerSentEvent<Object>> events = collect();
		ServerSentEvent<Object> done = events.stream()
				.filter(e -> "done".equals(e.event()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("没有 done 事件"));

		@SuppressWarnings("unchecked")
		Map<String, Object> usage = (Map<String, Object>) done.data();
		assertThat(usage).containsEntry("totalTokens", 42).containsEntry("model", "stub-model");
	}

	@Test
	@DisplayName("Reactor 陷阱：timeout 必须套在源上，套在 switchOnFirst 里对「一个信号都没有」无效")
	void timeoutMustWrapSourceNotSwitchOnFirst() {
		Duration budget = Duration.ofMillis(100);

		// 反例：把 timeout 放在 switchOnFirst 的"还没首字"分支里。
		// 源一直没有任何信号时，那个分支的流根本不会被订阅 → 超时不会触发 → 永久挂起。
		Flux<String> naive = Flux.<String>never()
				.switchOnFirst((signal, flux) -> signal.hasValue() ? flux : flux.timeout(budget));

		StepVerifier.create(naive)
				.expectTimeout(budget.multipliedBy(4))
				.verify();

		// 正例：timeout 直接套在源上，超时计时器立刻开始工作
		StepVerifier.create(Flux.<String>never().timeout(budget))
				.expectError(TimeoutException.class)
				.verify(Duration.ofSeconds(2));
	}

	@Test
	@DisplayName("重试前会摘掉失败尝试留下的孤儿用户消息：下一次尝试看到的上下文里没有重复")
	void dropsOrphanedUserMessageBeforeRetry() {
		properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
		properties.getRetry().setJitter(0.0);

		newController()
				// 前两次都失败 → 触发两次重试，才有机会观察到"第 2、3 次尝试开头"的上下文
				.fixed(2, () -> Flux.error(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
				.attemptDeltas(() -> delta("好了"));

		collect();

		// 每次尝试开始时都该是 0 条"你好"：清理把上次失败留下的那条摘掉了，
		// 之后由本次尝试的 advisor 重新写入（也就是每次尝试看到的上下文都干净）。
		// 若没有清理，这里会是 0、1、2 —— 上一次的孤儿消息被带进下一次上下文
		assertThat(service.userMessagesSeenAtAttemptStart()).containsExactly(0, 0, 0);
	}

	@Test
	@DisplayName("清理只摘本次提问的孤儿消息，不误删之前轮次的上下文")
	void keepsPreviousRoundsIntact() {
		properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
		properties.getRetry().setJitter(0.0);

		newController()
				.fixed(2, () -> Flux.error(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
				.attemptDeltas(() -> delta("好了"));

		// 记忆里先放好上一轮的内容：用户问 + 模型答
		repository.saveAll("conv-1", List.of(
				new UserMessage("上一轮的问题"),
				new AssistantMessage("上一轮的回答")));

		collect();

		// 最后一次尝试开始时：上一轮的两条必须原封不动，且本轮提问还没被写进来
		List<String> texts = service.historyAtAttempt(2).stream().map(Message::getText).toList();
		assertThat(texts).containsExactly("上一轮的问题", "上一轮的回答");
	}

	@Test
	@DisplayName("空闲超时与首字超时各管各的：idle 很短也不该掐掉「首字还没来」的等待")
	void idleTimeoutDoesNotShortenFirstTokenWait() {
		properties.getTimeout().setFirstToken(Duration.ofSeconds(5));
		properties.getTimeout().setIdle(Duration.ofMillis(60));

		// 第一个增量在 150ms 后才到：远超 idle(60ms)，但远小于 firstToken(5s)。
		// 如果实现里把两个阈值取了较小值（曾经就是这么写的），这里会在首字到达前就超时失败
		newController().attemptDeltasAfter(Duration.ofMillis(150), () -> delta("慢到但到了"));

		List<String> content = contents(collect());

		assertThat(content).containsExactly("慢到但到了");
		assertThat(service.attempts()).isEqualTo(1);
	}

	@Test
	@DisplayName("回答到一半卡住（空闲超时）→ 不重试，报「回答中断」而不是从头再问一遍")
	void reportsInterruptionWhenStreamStallsMidway() {
		properties.getTimeout().setFirstToken(Duration.ofSeconds(5));
		properties.getTimeout().setIdle(Duration.ofMillis(60));
		properties.getRetry().setMaxAttempts(2);

		// 先吐一个增量，然后永久静默 —— 正是"TCP 半开 / 代理静默丢包"的样子
		newController()
				.attemptStream(() -> Flux.concat(
						Mono.just(delta("第一段")),
						Flux.<ChatClientResponse>never()))
				// 声明一个不该被用到的第 2 次尝试：若实现错误地重试了，这里会产出它
				.always(() -> Flux.just(delta("第二段（不该出现）")));

		List<ServerSentEvent<Object>> events = collect();
		List<String> content = contents(events);
		List<String> errors = errors(events);

		assertThat(content).containsExactly("第一段");
		assertThat(errors).hasSize(1);
		assertThat(errors.get(0)).contains("回答中断").contains("空闲超时");
		// 内容已经发出去了，就不能再说"已重试 N 次"——重试是被明确禁止的
		assertThat(errors.get(0)).doesNotContain("已重试");

		// 关键：内容已经发给用户了，绝不能重发（否则页面上会出现两段拼接的回答）
		assertThat(service.attempts()).isEqualTo(1);
	}

	@Test
	@DisplayName("首字超时且重试也超时 → 报超时并提供重试次数，不泄露堆栈")
	void reportsTimeoutWithActionableMessage() {
		properties.getTimeout().setFirstToken(Duration.ofMillis(80));
		properties.getTimeout().setIdle(Duration.ofSeconds(5));
		properties.getRetry().setMaxAttempts(1);
		properties.getRetry().setInitialBackoff(Duration.ofMillis(10));

		newController().always(Flux::never);
		List<String> errors = errors(collect());

		assertThat(service.attempts()).isEqualTo(2);
		assertThat(errors).hasSize(1);
		assertThat(errors.get(0)).contains("没有返回任何内容").contains("已重试 1 次");
		// 一个字的正文都没推出去过，就不该说"内容不完整"
		assertThat(errors.get(0)).doesNotContain("回答中断");
	}

	@Test
	@DisplayName("Reactor 陷阱：switchOnFirst 的第二个参数包含首元素，用 signal.get() 重建会重复")
	void switchOnFirstSecondArgumentAlreadyIncludesFirstElement() {
		// 源产生 a b c
		// 写法一（会重复）：把 signal.get() 和 flux 拼起来 —— flux 已含 a
		StepVerifier.create(Flux.just("a", "b", "c")
						.switchOnFirst((signal, flux) -> signal.hasValue()
								? Flux.concat(Flux.just(signal.get()), flux)
								: flux))
				.expectNext("a", "a", "b", "c")
				.verifyComplete();

		// 写法二（正确）：直接用 flux。实测它包含首元素 a 本身
		StepVerifier.create(Flux.just("a", "b", "c")
						.switchOnFirst((signal, flux) -> signal.hasValue() ? flux : flux))
				.expectNext("a", "b", "c")
				.verifyComplete();
	}

	@Test
	@DisplayName("确认可用的超时组合：源上 firstToken + 分支内 idle，首字慢也不误杀、中途卡住能抓住")
	void workingTimeoutComposition() {
		// 场景一：首字 200ms 才到（远大于 idle=100ms），后续增量间隔 40ms。
		// 若 idle 把"等首字"也算进去，这条正常回答会被拦腰截断
		Flux<String> slowFirstToken = Flux.concat(
				Mono.delay(Duration.ofMillis(200)).thenReturn("首字"),
				Flux.just("后续1", "后续2").delayElements(Duration.ofMillis(40)));

		StepVerifier.create(slowFirstToken
						.timeout(Duration.ofSeconds(5))                    // firstToken 兜住首字等待
						.switchOnFirst((signal, flux) -> signal.hasValue()
								? flux.timeout(Duration.ofMillis(100))      // idle 只管后续间隔
								: flux))
				.expectNext("首字", "后续1", "后续2")
				.verifyComplete();

		// 场景二：首字很快，但之后永久静默 —— 必须被 idle 抓住
		Flux<String> stallsMidway = Flux.concat(
				Mono.delay(Duration.ofMillis(20)).thenReturn("首字"),
				Flux.<String>never());

		StepVerifier.create(stallsMidway
						.timeout(Duration.ofSeconds(5))
						.switchOnFirst((signal, flux) -> signal.hasValue()
								? flux.timeout(Duration.ofMillis(100))
								: flux))
				.expectNext("首字")
				.expectError(TimeoutException.class)
				.verify(Duration.ofSeconds(3));
	}

	// ---------------------------------------------------------------
	//  测试替身与断言工具
	// ---------------------------------------------------------------

	/**
	 * 可控的 {@link ChatService} 替身：按"第几次调用"返回不同的 Flux。
	 *
	 * <p>为什么不直接用 Mockito 的 {@code when(...).thenReturn(...)}：
	 * 我们要断言的是"第 1 次失败、第 2 次成功"这种<b>按次序</b>的行为，
	 * 还要统计真实调用次数，手写替身比一堆 {@code thenReturn} 更直观，
	 * 而且不依赖 mock 框架对 record / 泛型方法的处理细节。
	 *
	 * <p>方法分三类，别混用：
	 * <ul>
	 *   <li>{@link #attemptStream} / {@link #attemptDeltas} —— 只为<b>一次</b>调用声明行为，按顺序消费；</li>
	 *   <li>{@link #fixed} —— 把同一个行为重复声明 N 次（例如"三次都 503"）；</li>
	 *   <li>{@link #always} —— 兜底行为，声明之外的所有调用都用它。</li>
	 * </ul>
	 */
	private static final class StubChatService implements ChatService {

		private final ChatMemory chatMemory;

		private final List<Supplier<Flux<ChatClientResponse>>> behaviors = new ArrayList<>();

		/** 每次尝试开始时，记忆里"本次提问的用户消息"的条数 */
		private final List<Integer> userMessagesSeenAtAttemptStart = new ArrayList<>();

		/** 每次尝试开始时看到的完整历史快照（按尝试次序） */
		private final List<List<Message>> historyAtAttemptStart = new ArrayList<>();

		private int calls;

		private int loggedErrors;

		private Supplier<Flux<ChatClientResponse>> always;

		StubChatService(ChatMemory chatMemory) {
			this.chatMemory = chatMemory;
		}

		/** 只为一次调用声明行为：这次调用直接返回一个 Flux（用于模拟失败 / 挂起） */
		StubChatService attemptStream(Supplier<Flux<ChatClientResponse>> behavior) {
			behaviors.add(behavior);
			return this;
		}

		/**
		 * 只为一次调用声明行为：这次调用依次产出若干个增量。
		 *
		 * <p>为什么和 {@code attemptStream} 分成两个方法名而不是重载：这两个方法都以
		 * {@code Supplier} 为参数，写成重载时 {@code () -> Flux.error(...)} 这种 lambda
		 * 会同时"看起来像"两个重载，Java 的 varargs 推断会直接报
		 * "无法将 ... 应用到给定类型"。分开命名后调用点的意图也更清楚。
		 */
		@SafeVarargs
		final StubChatService attemptDeltas(Supplier<ChatClientResponse>... deltas) {
			List<Supplier<ChatClientResponse>> list = List.of(deltas);
			return attemptStream(() -> Flux.fromIterable(list).map(Supplier::get));
		}

		/** 一次调用：隔 {@code delay} 后才产出第一个增量（用于验证首字等待与空闲超时的边界） */
		StubChatService attemptDeltasAfter(Duration delay, Supplier<ChatClientResponse> delta) {
			return attemptStream(() -> Mono.delay(delay).map(ignored -> delta.get()).flux());
		}

		/** 同一个行为重复声明 {@code times} 次 */
		StubChatService fixed(int times, Supplier<Flux<ChatClientResponse>> behavior) {
			for (int i = 0; i < times; i++) {
				behaviors.add(behavior);
			}
			return this;
		}

		/**
		 * 兜底行为：声明之外的所有调用都用它。
		 *
		 * <p>刻意不做成"追加一次"——它叫 always 就该是 always，
		 * 否则"声明了却没用上"这种行为会让测试断言出现难以理解的偏差。
		 */
		StubChatService always(Supplier<Flux<ChatClientResponse>> behavior) {
			this.always = behavior;
			return this;
		}

		int attempts() {
			return calls;
		}

		@Override
		public Flux<ChatClientResponse> stream(String conversationId, String userText, double temperature) {
			int index = calls++;

			// 先记录"这次尝试看到的记忆"，再模拟 advisor 写用户消息。
			// 顺序很关键：真话是 MessageChatMemoryAdvisor 在发起调用**之前**写用户消息，
			// 所以"进入 stream() 时仓储里的状态"就是这次尝试实际会发出去的上下文 ——
			// 这也正是验证"重试前清理有没有生效"最可靠的观察点（不依赖调度时序，无竞态）
			List<Message> history = chatMemory.get(conversationId);
			historyAtAttemptStart.add(history);
			userMessagesSeenAtAttemptStart.add((int) history.stream()
					.filter(m -> m.getMessageType() == MessageType.USER)
					.filter(m -> userText.equals(m.getText()))
					.count());

			chatMemory.add(conversationId, new UserMessage(userText));

			if (index < behaviors.size()) {
				return behaviors.get(index).get();
			}
			if (always != null) {
				return always.get();
			}
			// 没声明的调用 → 立刻炸，并且让"多调了一次"这件事出现在断言输出里。
			// 刻意不用 Flux.never() 卡住：那样测试要等到超时才失败，看不出真实原因
			return Flux.error(new AssertionError(
					"第 " + (index + 1) + " 次调用没有预期（只声明了 " + behaviors.size() + " 次）"));
		}

		List<Integer> userMessagesSeenAtAttemptStart() {
			return userMessagesSeenAtAttemptStart;
		}

		/** 第 {@code attemptIndex} 次尝试（从 0 开始）开始时看到的完整历史 */
		List<Message> historyAtAttempt(int attemptIndex) {
			return historyAtAttemptStart.get(attemptIndex);
		}

		@Override
		public String call(String conversationId, String message) {
			throw new UnsupportedOperationException("本测试只覆盖流式通路");
		}

		@Override
		public String deltaOf(ChatClientResponse response) {
			if (response == null || response.chatResponse() == null) {
				return "";
			}
			List<Generation> results = response.chatResponse().getResults();
			if (results == null || results.isEmpty() || results.get(0).getOutput() == null) {
				return "";
			}
			String text = results.get(0).getOutput().getText();
			return text == null ? "" : text;
		}

		@Override
		public Map<String, Object> usageOf(ChatClientResponse response) {
			Map<String, Object> usage = new LinkedHashMap<>();
			usage.put("promptTokens", 10);
			usage.put("completionTokens", 32);
			usage.put("totalTokens", 42);
			usage.put("model", "stub-model");
			return usage;
		}

		@Override
		public boolean hasDelta(String delta) {
			return delta != null && !delta.isEmpty();
		}

		@Override
		public void logError(String conversationId, Throwable e, boolean expected) {
			loggedErrors++;
		}

		int loggedErrors() {
			return loggedErrors;
		}
	}

	private static ChatClientResponse delta(String text) {
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
				.model("stub-model")
				.usage(new DefaultUsage(10, 32, 42))
				.build();
		return ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder()
						.generations(List.of(new Generation(new AssistantMessage(text))))
						.metadata(metadata)
						.build())
				.build();
	}

	private List<ServerSentEvent<Object>> collect() {
		return controller.streamByPost(request())
				.collectList()
				// 给足余量：测试里的超时/退避都是几十毫秒级，2 秒足够区分"正常路径"与"卡住"
				.block(Duration.ofSeconds(5));
	}

	private static List<String> contents(List<ServerSentEvent<Object>> events) {
		List<String> out = new ArrayList<>();
		for (ServerSentEvent<Object> event : events) {
			if ("content".equals(event.event())) {
				@SuppressWarnings("unchecked")
				Map<String, Object> data = (Map<String, Object>) event.data();
				out.add(String.valueOf(data.get("delta")));
			}
		}
		return out;
	}

	private static List<String> errors(List<ServerSentEvent<Object>> events) {
		List<String> out = new ArrayList<>();
		for (ServerSentEvent<Object> event : events) {
			if ("error".equals(event.event())) {
				@SuppressWarnings("unchecked")
				Map<String, Object> data = (Map<String, Object>) event.data();
				out.add(String.valueOf(data.get("message")));
			}
		}
		return out;
	}

	private static List<String> eventNames(List<ServerSentEvent<Object>> events) {
		return events.stream().map(ServerSentEvent::event).toList();
	}

}
