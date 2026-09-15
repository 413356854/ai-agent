package ai.agent.agent.limit;

import ai.agent.config.AgentConcurrencyProperties;
import ai.agent.tool.AgentTool;
import ai.agent.tool.AgentToolResult;
import ai.agent.tool.ToolCallExecutor;
import ai.agent.tool.ToolCallOutcome;
import ai.agent.tool.ToolCallStage;
import ai.agent.tool.ToolInvocation;
import ai.agent.tool.ToolRegistry;
import ai.agent.tool.model.AgentToolDefinition;
import ai.agent.tool.support.ToolFeedbackFormatter;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>并发控制 + 限流 + 熔断的测试</b>（阶段三第 3 件）。
 *
 * <h2>这个类要证明的三件事，以及各自为什么必须"实测"</h2>
 * <ol>
 *   <li><b>上限真的按住了</b> —— 断言的不是"配置读到了 4"，而是
 *       <b>工具内部同时执行的最大线程数 ≤ 4</b>。这两者的差别是本质的：
 *       配置读到了但没用上（忘了接线、包在了错误的位置）是完全可能发生的，
 *       而那种情况下"读配置"的断言会一路绿灯；</li>
 *   <li><b>等不到就拒绝，而不是无限排队</b> —— 用一个"卡住不放手"的工具把闸门占满，
 *       再发一个请求，断言它<b>在超时后返回 REQUEST_REJECTED</b>（而不是一直等）；</li>
 *   <li><b>熔断只对"工具侧的失败"动作</b> —— 这是本轮最重要的一个判断。
 *       模型写错参数、以及被限流拒掉，都<b>不该</b>推着熔断器去打开一个健康工具的闸门。</li>
 * </ol>
 *
 * <h2>为什么这个类不起 Spring 上下文</h2>
 * 并发保护是纯内存逻辑（信号量 + 计数器 + 状态机），不需要模型、不需要数据库、不需要网络。
 * 不起上下文让它跑得快（毫秒级），于是可以放心地<b>真的开十几个线程去撞</b> ——
 * 而那正是验证"上限生效"的唯一办法。
 */
class AgentConcurrencyTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	private static final ToolFeedbackFormatter FORMATTER = new ToolFeedbackFormatter(2000);

	/** 可编程的替身工具：能卡住、能炸、能记录并发峰值。 */
	private static class ControllableTool implements AgentTool<ControllableTool.Params> {

		private final String name;

		/** 工具内部同时执行的线程数峰值 —— "上限真的按住了"的直接证据。 */
		private final AtomicInteger concurrentInside = new AtomicInteger();

		private final AtomicInteger peakInside = new AtomicInteger();

		private final AtomicInteger calls = new AtomicInteger();

		/** 非空时：先把许可占满（用来造"闸门满了"的场景）。 */
		private volatile CountDownLatch holdLatch;

		/** 打开它让工具抛异常（用来造"工具故障"）。 */
		private volatile boolean explode;

		/** 执行时睡多久 —— 用来模拟"下游很慢"，从而测出熔断到底省了多少等待。 */
		private volatile long delayMs;

		ControllableTool(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public AgentToolDefinition description() {
			return new AgentToolDefinition(name, "测试替身",
					"只在测试里使用，用来构造并发与故障场景", "不要在生产里用它", "返回固定文本", "可能抛异常");
		}

		@Override
		public Class<Params> parameterType() {
			return Params.class;
		}

		@Override
		public AgentToolResult invoke(Params parameters) {
			calls.incrementAndGet();
			int now = concurrentInside.incrementAndGet();
			updatePeak(now);
			try {
				long delay = delayMs;
				if (delay > 0) {
					// 模拟"下游很慢"：熔断省下的等待就是这一段
					Thread.sleep(delay);
				}
				CountDownLatch latch = holdLatch;
				if (latch != null) {
					// 卡住不放：用来把闸门名额一直占着
					latch.await(30, TimeUnit.SECONDS);
				}
				if (explode) {
					throw new IllegalStateException("模拟工具依赖炸了");
				}
				return AgentToolResult.ok("替身工具的返回");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return AgentToolResult.ok("被中断");
			}
			finally {
				concurrentInside.decrementAndGet();
			}
		}

		private void updatePeak(int current) {
			int peak;
			do {
				peak = peakInside.get();
				if (current <= peak) {
					return;
				}
			}
			while (!peakInside.compareAndSet(peak, current));
		}

		/**
		 * 参数。
		 *
		 * <p>{@code @NotBlank} 是刻意的：本轮有两类测试要"造出校验失败"和"造出成功"，
		 * 所以需要参数**真的**有约束 —— 否则 {@code {}} 是合法的，
		 * "校验失败"那个测试就会拿到 OK（第一版就是这么错的）。
		 * 这也是一个小教训：<b>替身的数据类型也要如实带约束</b>，
		 * 否则用它测出来的东西和真实工具的行为不是一回事。
		 */
		public record Params(@jakarta.validation.constraints.NotBlank(message = "note 不能为空") String note) {
		}
	}

	private static AgentConcurrencyProperties properties(int maxTool, long timeoutMs,
			AgentConcurrencyProperties.Circuit circuit) {
		return new AgentConcurrencyProperties(maxTool, maxTool, timeoutMs, circuit);
	}

	private static AgentConcurrencyProperties.Circuit circuit(boolean enabled, double threshold, int minimumCalls,
			int window, long openMs, int probes) {
		return new AgentConcurrencyProperties.Circuit(enabled, threshold, minimumCalls, window, openMs, probes);
	}

	private static ToolCallExecutor executor(AgentTool<?> tool, PerToolGate gate) {
		return new ToolCallExecutor(new ToolRegistry(List.of(tool)), VALIDATOR, FORMATTER, gate);
	}

	// ------------------------------------------------------------------
	//  1. 上限真的按住了（实测并发峰值）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("并发上限真的生效：12 个线程同时打，工具内部并发峰值不超过 4")
	void concurrencyLimitIsActuallyEnforced() throws Exception {
		ControllableTool tool = new ControllableTool("test_tool");
		// 上限 4、超时 10 秒（足够宽，让 12 个请求全部能排到 —— 这个测试要测的是"上限"，
		// 不是"超时拒绝"；两件事混在一个测试里就说不清失败原因）
		PerToolGate gate = new PerToolGate(properties(4, 10_000L, circuit(false, 0.5, 5, 20, 1000, 1)));
		ToolCallExecutor executor = executor(tool, gate);
		tool.holdLatch = new CountDownLatch(1);

		int threads = 12;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch allStarted = new CountDownLatch(threads);
		CountDownLatch release = new CountDownLatch(1);
		try {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					allStarted.countDown();
					try {
						allStarted.await();
						// 每个请求都会在工具里等 holdLatch —— 所以同时在工具内部的线程数
						// 就等于"闸门放行了几个人"
						executor.execute(ToolInvocation.from("c" + Thread.currentThread().threadId(), "test_tool",
								"{\"note\":\"x\"}"));
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					finally {
						release.countDown();
					}
				});
			}
			// 等到工具内部确实有 4 个线程（说明闸门放满了），再等一小会儿看有没有第 5 个混进来
			long deadline = System.currentTimeMillis() + 5000;
			while (tool.concurrentInside.get() < 4 && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}
			Thread.sleep(300);

			assertThat(tool.peakInside.get())
					.as("工具内部并发峰值必须 <= 上限（这是「上限真的按住了」的直接证据）")
					.isLessThanOrEqualTo(4);
			assertThat(tool.peakInside.get()).as("同时确实有 4 个在跑（否则是上限没放开，而不是生效）")
					.isEqualTo(4);
			assertThat(gate.gateSnapshots().get(0).peakInFlight()).as("闸门自己记的峰值也要对上")
					.isLessThanOrEqualTo(4);
		}
		finally {
			tool.holdLatch.countDown();
			release.await(5, TimeUnit.SECONDS);
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("许可全部归还：跑完之后在途数必须回到 0（漏还一个就是永久少一个名额）")
	void permitsAreAlwaysReleased() {
		ControllableTool tool = new ControllableTool("test_tool");
		PerToolGate gate = new PerToolGate(properties(2, 1000L, circuit(false, 0.5, 5, 20, 1000, 1)));
		ToolCallExecutor executor = executor(tool, gate);

		// 四种出口都要走一遍：成功 / 校验失败 / 解析失败 / 执行异常
		executor.execute(ToolInvocation.from("c1", "test_tool", "{\"note\":\"ok\"}"));
		executor.execute(ToolInvocation.from("c2", "test_tool", "{}"));
		executor.execute(ToolInvocation.from("c3", "test_tool", "{半截"));
		tool.explode = true;
		executor.execute(ToolInvocation.from("c4", "test_tool", "{\"note\":\"boom\"}"));

		assertThat(gate.gateSnapshots().get(0).inFlight())
				.as("四个出口都必须归还许可 —— 漏一个就会'越跑越慢最后卡住、重启又好了'")
				.isZero();
	}

	// ------------------------------------------------------------------
	//  2. 等不到就拒绝（不是无限排队）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("排队超时后返回 REQUEST_REJECTED，且回填里明确说「同样的参数重试是有意义的」")
	void queueTimeoutRejectsWithRetriableFeedback() throws Exception {
		ControllableTool tool = new ControllableTool("test_tool");
		// 上限 1、超时 200ms
		PerToolGate gate = new PerToolGate(properties(1, 200L, circuit(false, 0.5, 5, 20, 1000, 1)));
		ToolCallExecutor executor = executor(tool, gate);
		tool.holdLatch = new CountDownLatch(1);

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// 第一个请求占住唯一的名额（它会在工具里卡住）
			pool.submit(() -> executor.execute(ToolInvocation.from("c1", "test_tool", "{\"note\":\"hold\"}")));
			long deadline = System.currentTimeMillis() + 3000;
			while (tool.concurrentInside.get() < 1 && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}

			// 第二个请求必然等不到名额
			long startedAt = System.currentTimeMillis();
			ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c2", "test_tool", "{\"note\":\"me\"}"));
			long waited = System.currentTimeMillis() - startedAt;

			assertThat(outcome.stage()).isEqualTo(ToolCallStage.REQUEST_REJECTED);
			assertThat(waited).as("应该在超时附近返回，而不是一直等").isLessThan(3000L);
			assertThat(outcome.fixableByModel()).as("被限流是'值得再给一轮'的 —— 稍后重试真的会有用").isTrue();
			// 【这段措辞是本轮的重点之一】两种拒绝要让模型做相反的事，所以文本必须区分
			assertThat(outcome.feedback()).contains("暂时").contains("重试");
			assertThat(outcome.feedback()).as("必须明确说「不要换一个语义不同的参数」").contains("不要");
		}
		finally {
			tool.holdLatch.countDown();
			pool.shutdownNow();
		}
	}

	// ------------------------------------------------------------------
	//  3. 熔断：状态机与开合
	// ------------------------------------------------------------------

	@Test
	@DisplayName("熔断器：失败率到阈值就打开，打开期间不再放行，冷却后转半开")
	void circuitOpensAndHalfOpens() {
		// minimumCalls=2：意思是"窗口里要有 2 个结果才评估失败率"。
		// 所以第 1 次结果进来时窗口只有 1 条，不评估；第 2 条进来（此时 1 成功 1 失败 = 50%）才达标并打开。
		// 【第一版我把 minimum-calls=4 理解成"要 4 次调用才评估"，于是断言写在第 2 次结果之后 ——
		// 那条断言是错的，代码是对的。教训：配置项的名字有歧义时，去读它的判断式，别按名字猜】
		CircuitBreaker breaker = new CircuitBreaker("t", circuit(true, 0.5, 2, 10, 50L, 1));

		assertThat(breaker.tryAcquire()).isNull();
		breaker.record(false); // 成功
		assertThat(breaker.state()).as("窗口里只有 1 条，还不够评估").isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.tryAcquire()).isNull();
		breaker.record(true); // 失败 → 1 成功 1 失败 = 50%，达到阈值

		assertThat(breaker.state()).as("窗口达到 minimumCalls 且失败率达标 → 打开").isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(breaker.failureRate()).isEqualTo(0.5);
		assertThat(breaker.openCount()).isEqualTo(1);

		// 打开期间：拒绝，且不进窗口
		String rejection = breaker.tryAcquire();
		assertThat(rejection).as("打开时必须拒绝").isNotNull();
		assertThat(rejection).contains("熔断").contains("换用其它工具");
		assertThat(breaker.shortCircuitedCount()).isEqualTo(1);

		// 冷却期到了 → 允许一个试探
		sleep(80);
		assertThat(breaker.tryAcquire()).as("冷却期后应放行一个试探").isNull();
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

		// 试探成功 → 恢复
		breaker.record(false);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.windowSize()).as("试探成功后要清空窗口 —— 否则会立刻被熔断前的旧失败再次触发").isZero();
	}

	@Test
	@DisplayName("熔断器：试探失败要回到 OPEN 并重新计时（没恢复就别急着放行）")
	void halfOpenProbeFailureReopensCircuit() {
		CircuitBreaker breaker = new CircuitBreaker("t", circuit(true, 0.5, 2, 10, 50L, 1));
		breaker.tryAcquire();
		breaker.record(true);
		breaker.tryAcquire();
		breaker.record(true);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

		sleep(80);
		assertThat(breaker.tryAcquire()).isNull();
		breaker.record(true); // 试探还是失败

		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(breaker.openCount()).as("打开了两次（第二次是试探失败后重开）").isEqualTo(2);
		assertThat(breaker.tryAcquire()).as("刚回到 OPEN，冷却期重新算，应立刻拒绝").isNotNull();
	}

	@Test
	@DisplayName("minimumCalls 不能省：只有 1 次失败时不能熔断（否则首个失败就把健康工具关掉）")
	void minimumCallsPreventsImmediateTrip() {
		// 阈值 0.5、但最少要 5 次才评估
		CircuitBreaker breaker = new CircuitBreaker("t", circuit(true, 0.5, 5, 20, 1000L, 1));

		breaker.tryAcquire();
		breaker.record(true);
		assertThat(breaker.failureRate()).as("1 次全失败 = 100%，但样本不够").isEqualTo(1.0);
		assertThat(breaker.state()).as("样本不够时不许打开").isEqualTo(CircuitBreaker.State.CLOSED);

		breaker.tryAcquire();
		breaker.record(true);
		breaker.tryAcquire();
		breaker.record(true);
		assertThat(breaker.state()).as("3 次仍不够").isEqualTo(CircuitBreaker.State.CLOSED);

		breaker.tryAcquire();
		breaker.record(true);
		breaker.tryAcquire();
		breaker.record(true);
		assertThat(breaker.state()).as("第 5 次够了，才允许打开").isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	@DisplayName("关掉熔断时闸门照常工作（用来做对照实验：证明「开着确实少等了」）")
	void disabledCircuitNeverBlocks() {
		CircuitBreaker breaker = new CircuitBreaker("t", circuit(false, 0.5, 1, 1, 1L, 1));
		for (int i = 0; i < 50; i++) {
			assertThat(breaker.tryAcquire()).as("关掉之后永远放行").isNull();
			breaker.record(true);
		}
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	// ------------------------------------------------------------------
	//  4. 归因：熔断只对"工具侧的失败"动作（本轮最重要的判断）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("模型的参数错**不**计入熔断失败率：连写 10 次错参数也不该把健康工具熔断")
	void validationErrorsDoNotTripCircuit() {
		ControllableTool tool = new ControllableTool("test_tool");
		// 阈值 0.5、最少 2 次 —— 激进的参数，就是为了让"错分类"立刻暴露
		PerToolGate gate = new PerToolGate(properties(4, 1000L, circuit(true, 0.5, 2, 10, 1000L, 1)));
		ToolCallExecutor executor = executor(tool, gate);

		for (int i = 0; i < 10; i++) {
			ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c" + i, "test_tool", "{}"));
			assertThat(outcome.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		}
		// 参数解析错也一样
		for (int i = 0; i < 10; i++) {
			executor.execute(ToolInvocation.from("p" + i, "test_tool", "{半截"));
		}

		assertThat(gate.stateOf("test_tool"))
				.as("模型写错参数是模型的问题，不该由工具的可用性买单 —— 熔断必须保持关闭")
				.isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(gate.breakerSnapshots().get(0).windowSize())
				.as("这些调用压根不该进熔断窗口").isZero();
	}

	@Test
	@DisplayName("被限流拒绝**不**计入熔断失败率：否则保护措施自己会制造一次故障")
	void rejectionsDoNotTripCircuit() throws Exception {
		ControllableTool tool = new ControllableTool("test_tool");
		PerToolGate gate = new PerToolGate(properties(1, 100L, circuit(true, 0.5, 2, 10, 1000L, 1)));
		ToolCallExecutor executor = executor(tool, gate);
		tool.holdLatch = new CountDownLatch(1);

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			pool.submit(() -> executor.execute(ToolInvocation.from("hold", "test_tool", "{\"note\":\"h\"}")));
			long deadline = System.currentTimeMillis() + 3000;
			while (tool.concurrentInside.get() < 1 && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}
			// 连续被拒 8 次
			for (int i = 0; i < 8; i++) {
				ToolCallOutcome outcome = executor.execute(ToolInvocation.from("r" + i, "test_tool", "{\"note\":\"x\"}"));
				assertThat(outcome.stage()).isEqualTo(ToolCallStage.REQUEST_REJECTED);
			}
			assertThat(gate.stateOf("test_tool"))
					.as("限流本来就是'我们在保护下游'，把被拒算成失败 = 既限流又熔断 = 保护措施自己制造故障")
					.isEqualTo(CircuitBreaker.State.CLOSED);
		}
		finally {
			tool.holdLatch.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("工具真的反复炸 → 熔断打开，且后续调用**不经过闸门**（省下的是超时等待）")
	void executionErrorsTripCircuitAndSkipTheGate() {
		ControllableTool tool = new ControllableTool("test_tool");
		tool.explode = true;
		PerToolGate gate = new PerToolGate(properties(4, 1000L, circuit(true, 0.5, 4, 10, 5000L, 1)));
		ToolCallExecutor executor = executor(tool, gate);

		for (int i = 0; i < 4; i++) {
			ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c" + i, "test_tool", "{\"note\":\"x\"}"));
			assertThat(outcome.stage()).isEqualTo(ToolCallStage.EXECUTION_ERROR);
		}
		assertThat(gate.stateOf("test_tool")).as("4/4 全失败 → 打开").isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(tool.calls.get()).as("前 4 次真的执行了").isEqualTo(4);

		ToolCallOutcome blocked = executor.execute(ToolInvocation.from("c9", "test_tool", "{\"note\":\"x\"}"));
		assertThat(blocked.stage()).isEqualTo(ToolCallStage.CIRCUIT_OPEN);
		assertThat(tool.calls.get()).as("熔断打开后工具**一次都不该被执行**（省下的正是那些注定超时的等待）")
				.isEqualTo(4);
		assertThat(blocked.feedback()).as("熔断的回填要和限流区分：这个不该说「稍后重试」").contains("熔断");
		assertThat(blocked.feedback()).contains("不要再用同样的参数重试");
	}

	// ------------------------------------------------------------------
	//  5. 按工具隔离（局部故障不该放大成全局故障）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("工具之间隔离：一把工具熔断了，另一把照常工作")
	void toolsAreIsolatedFromEachOther() {
		ControllableTool broken = new ControllableTool("broken_tool");
		broken.explode = true;
		ControllableTool healthy = new ControllableTool("healthy_tool");

		PerToolGate gate = new PerToolGate(properties(4, 500L, circuit(true, 0.5, 2, 10, 5000L, 1)));
		ToolCallExecutor executor = new ToolCallExecutor(new ToolRegistry(List.of(broken, healthy)), VALIDATOR,
				FORMATTER, gate);

		// 把坏工具打到熔断
		for (int i = 0; i < 2; i++) {
			executor.execute(ToolInvocation.from("b" + i, "broken_tool", "{\"note\":\"x\"}"));
		}
		assertThat(gate.stateOf("broken_tool")).isEqualTo(CircuitBreaker.State.OPEN);

		// 健康工具完全不受影响 —— 这是"按工具隔离"的全部意义：
		// 不做隔离的话，这里会因为共用一个池/一个熔断器而一起挂掉
		ToolCallOutcome ok = executor.execute(ToolInvocation.from("h1", "healthy_tool", "{\"note\":\"x\"}"));
		assertThat(ok.stage()).isEqualTo(ToolCallStage.OK);
		assertThat(gate.stateOf("healthy_tool")).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(gate.gateSnapshots()).as("两把工具各有一份闸门").hasSize(2);
	}

	// ------------------------------------------------------------------
	//  6. 配置合法性（非法配置要在构造期就抛）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("非法并发配置在构造期就抛（尤其是「无限等待」必须被拒绝）")
	void invalidConcurrencyConfigFailsFast() {
		// 0 或负数表示无限等待 —— 虚拟线程让无限等待的成本极低，会把故障模式变成"静默挂起"
		assertThat(org.assertj.core.api.Assertions
				.catchThrowable(() -> new AgentConcurrencyProperties(8, 4, 0, circuit(true, 0.5, 5, 20, 1000, 1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("acquire-timeout-ms");
		assertThat(org.assertj.core.api.Assertions
				.catchThrowable(() -> new AgentConcurrencyProperties(0, 4, 100, circuit(true, 0.5, 5, 20, 1000, 1))))
				.isInstanceOf(IllegalArgumentException.class);
		// window < minimumCalls：窗口里永远凑不够最小样本数 → 熔断永不生效，而现象是"配了没用"
		assertThat(org.assertj.core.api.Assertions
				.catchThrowable(() -> circuit(true, 0.5, 10, 5, 1000, 1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("minimum-calls");
		// 阈值必须在 (0,1]
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> circuit(true, 1.5, 5, 20, 1000, 1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("默认值都自洽（能被构造出来，说明几条约束互相不冲突）")
	void defaultsAreCoherent() {
		AgentConcurrencyProperties defaults = AgentConcurrencyProperties.defaults();

		assertThat(defaults.maxConcurrentModelRequests()).isEqualTo(8);
		assertThat(defaults.maxConcurrentToolRequests()).isEqualTo(4);
		assertThat(defaults.circuitEnabled()).isTrue();
		// 这几条是最容易配出矛盾的：window >= minimumCalls、probes >= 1、冷却 > 0
		assertThat(defaults.circuit().windowSize()).isGreaterThanOrEqualTo(defaults.circuit().minimumCalls());
		assertThat(defaults.circuit().halfOpenProbes()).isGreaterThanOrEqualTo(1);
	}

	// ------------------------------------------------------------------
	//  7. 工具级闸门与熔断的协作顺序
	// ------------------------------------------------------------------

	@Test
	@DisplayName("先问熔断再抢闸门：熔断打开时不去占闸门名额（也不污染排队统计）")
	void circuitIsCheckedBeforeGate() {
		PerToolGate gate = new PerToolGate(properties(1, 100L, circuit(true, 0.5, 1, 1, 5000L, 1)));
		gate.forceOpenForTesting("t");

		PerToolGate.Rejection rejection = gate.tryAcquire("t");

		assertThat(rejection).isNotNull();
		assertThat(rejection.stage()).as("熔断打开时应该报 CIRCUIT_OPEN 而不是 REQUEST_REJECTED")
				.isEqualTo(ToolCallStage.CIRCUIT_OPEN);
		assertThat(gate.gateSnapshots())
				.as("没抢过闸门，所以不该产生任何排队记录（否则会污染「这里很挤」的判断）")
				.allSatisfy(snapshot -> assertThat(snapshot.acquired()).isZero());
	}

	@Test
	@DisplayName("熔断放行后闸门拒绝时，半开名额必须还回去（否则熔断器永远恢复不了）")
	void halfOpenProbeIsReturnedWhenGateRejects() throws Exception {
		ControllableTool tool = new ControllableTool("t");
		tool.holdLatch = new CountDownLatch(1);
		// 上限 1、超时 100ms、熔断冷却 1ms（这样下一个请求就会把它推进 HALF_OPEN）
		PerToolGate gate = new PerToolGate(properties(1, 100L, circuit(true, 0.5, 1, 1, 1L, 1)));
		ToolCallExecutor executor = executor(tool, gate);

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// 占住唯一名额
			pool.submit(() -> executor.execute(ToolInvocation.from("h", "t", "{\"note\":\"h\"}")));
			long deadline = System.currentTimeMillis() + 3000;
			while (tool.concurrentInside.get() < 1 && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}

			// 先把它打进 OPEN（1 次失败就够，因为 minimum-calls=1）
			gate.forceOpenForTesting("t");
			sleep(50); // 让冷却期(1ms)过去 → 下一次尝试会转 HALF_OPEN 并放行一个试探

			// 这次：熔断放行（占掉那个唯一的半开名额），但闸门满了 → 拒绝。
			// 如果不把名额还回去，半开状态就再也放不出试探了
			ToolCallOutcome first = executor.execute(ToolInvocation.from("p1", "t", "{\"note\":\"x\"}"));
			assertThat(first.stage()).isEqualTo(ToolCallStage.REQUEST_REJECTED);

			// 现在把闸门放开，再试一次：必须能被放行（拿到那个被还回去的名额）
			tool.holdLatch.countDown();
			sleep(200);
			ToolCallOutcome second = executor.execute(ToolInvocation.from("p2", "t", "{\"note\":\"x\"}"));
			assertThat(second.stage())
					.as("半开名额被正确还回来的话，这一次应该真的执行（而不是一直报'试探已用满'）")
					.isNotEqualTo(ToolCallStage.CIRCUIT_OPEN);
		}
		finally {
			tool.holdLatch.countDown();
			pool.shutdownNow();
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ------------------------------------------------------------------
	//  8. 实测数字：三种保护各自省下了什么
	// ------------------------------------------------------------------

	/**
	 * <b>熔断省下的到底是"时间"还是"错误"——把它量出来。</b>
	 *
	 * <p>这条测试的目的不是"断言功能正确"（那些上面已经覆盖了），而是<b>产出一个数字</b>：
	 * 台账里"熔断有用"这句话必须有个可引用的量化依据，否则它只是一句设计意图。
	 *
	 * <p>做法：让替身工具"很慢"（每次 200ms），先把它打到熔断，再测两次调用的耗时 ——
	 * 一次是熔断打开前（真发出去了，要等满 200ms），一次是打开后（压根没发出去）。
	 * 两者的差就是<b>熔断每次为任务省下的等待</b>。
	 *
	 * <p>这个数字的意义在于：一个 6 轮的 Agent 任务，如果第 2 轮就发现某把工具坏了，
	 * 熔断能让后面 4 轮都省下这 200ms（真实下游通常是秒级，省得更多）——
	 * 而更重要的不是时间，是<b>那 4 次等待本来没有任何成功可能</b>。
	 */
	@Test
	@DisplayName("实测：熔断把「每次等满下游超时」变成「立刻返回」——量出差多少")
	void measuresHowMuchTimeTheCircuitSaves() {
		ControllableTool slow = new ControllableTool("slow_tool");
		slow.explode = true;
		slow.delayMs = 200;
		// 最少 2 次、阈值 0.5：两次失败就打开
		PerToolGate gate = new PerToolGate(properties(4, 1000L, circuit(true, 0.5, 2, 10, 60_000L, 1)));
		ToolCallExecutor executor = executor(slow, gate);

		// ---- 打开前：真的发出去，每次都要等满 200ms ----
		long beforeStarted = System.currentTimeMillis();
		ToolCallOutcome first = executor.execute(ToolInvocation.from("c1", "slow_tool", "{\"note\":\"x\"}"));
		ToolCallOutcome second = executor.execute(ToolInvocation.from("c2", "slow_tool", "{\"note\":\"x\"}"));
		long beforeMs = System.currentTimeMillis() - beforeStarted;

		assertThat(first.stage()).isEqualTo(ToolCallStage.EXECUTION_ERROR);
		assertThat(second.stage()).isEqualTo(ToolCallStage.EXECUTION_ERROR);
		assertThat(gate.stateOf("slow_tool")).isEqualTo(CircuitBreaker.State.OPEN);

		// ---- 打开后：压根不发出，立刻返回 ----
		long afterStarted = System.currentTimeMillis();
		ToolCallOutcome blocked = executor.execute(ToolInvocation.from("c3", "slow_tool", "{\"note\":\"x\"}"));
		long afterMs = System.currentTimeMillis() - afterStarted;

		assertThat(blocked.stage()).isEqualTo(ToolCallStage.CIRCUIT_OPEN);
		assertThat(slow.calls.get()).as("熔断后工具一次都没被执行").isEqualTo(2);
		assertThat(afterMs).as("熔断后的调用应该几乎不耗时（<50ms，相对于下游的 200ms）").isLessThan(50);
		assertThat(beforeMs).as("熔断前两次真的等了下游（各 200ms，合计 >= 400ms）").isGreaterThanOrEqualTo(400);

		System.out.printf("MEASURE 熔断：打开前 %dms（2 次真执行，每次约 %dms）→ 打开后 %dms（未发出）｜单次省约 %dms%n",
				beforeMs, beforeMs / 2, afterMs, beforeMs / 2 - afterMs);
	}

	/**
	 * <b>排队会被如实记进闸门的统计里</b> —— 这是"限流造成的延迟"可观测的唯一手段。
	 *
	 * <p>为什么要专门测它：限流<b>不产生错误，它产生延迟</b>。
	 * 如果只统计"拒绝了几次"，那么"闸门一直没满、但每次都要等 150ms"这种情况
	 * 在所有监控上都显示正常 —— 而用户体感已经变差了。
	 */
	@Test
	@DisplayName("实测：排队时长被记进闸门统计（限流只产生延迟、不产生错误，所以要能看见延迟）")
	void measuresQueueWaitTime() throws Exception {
		ControllableTool tool = new ControllableTool("t");
		tool.delayMs = 200;
		// 上限 1：第二个请求必须排队等第一个做完
		PerToolGate gate = new PerToolGate(properties(1, 5000L, circuit(false, 0.5, 5, 20, 1000L, 1)));
		ToolCallExecutor executor = executor(tool, gate);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// 两个请求几乎同时发出：一个立刻拿到名额，另一个要么等要么被拒
			CountDownLatch start = new CountDownLatch(1);
			var f1 = pool.submit(() -> {
				start.await();
				return executor.execute(ToolInvocation.from("c1", "t", "{\"note\":\"1\"}"));
			});
			var f2 = pool.submit(() -> {
				start.await();
				return executor.execute(ToolInvocation.from("c2", "t", "{\"note\":\"2\"}"));
			});
			start.countDown();
			ToolCallOutcome o1 = f1.get(10, TimeUnit.SECONDS);
			ToolCallOutcome o2 = f2.get(10, TimeUnit.SECONDS);

			assertThat(List.of(o1.stage(), o2.stage())).as("两个都该成功（超时 5 秒足够等到 200ms）")
					.containsExactlyInAnyOrder(ToolCallStage.OK, ToolCallStage.OK);

			ConcurrencyGate.Snapshot snapshot = gate.gateSnapshots().get(0);
			assertThat(snapshot.peakInFlight()).as("峰值必须真的到过 1（证明闸门被用到过）").isEqualTo(1);
			assertThat(snapshot.acquired()).isEqualTo(2);
			// 关键：第二个请求确实等了 —— 而这件事只有在 maxWaitMs / averageWaitMs 里才看得见
			assertThat(snapshot.maxWaitMs()).as("应记录到真实的排队时长（第二个请求等第一个做完）")
					.isGreaterThanOrEqualTo(100);

			System.out.printf("MEASURE 排队：maxWait=%dms avgWait=%.1fms acquired=%d rejected=%d peakInFlight=%d%n",
					snapshot.maxWaitMs(), snapshot.averageWaitMs(), snapshot.acquired(), snapshot.rejected(),
					snapshot.peakInFlight());
		}
		finally {
			pool.shutdownNow();
		}
	}

}
