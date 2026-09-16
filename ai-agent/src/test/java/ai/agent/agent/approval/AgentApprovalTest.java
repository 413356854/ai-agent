package ai.agent.agent.approval;

import ai.agent.agent.AgentLoop;
import ai.agent.agent.AgentRun;
import ai.agent.agent.AgentProgressListener;
import ai.agent.agent.AgentStopReason;
import ai.agent.agent.ModelTurn;
import ai.agent.agent.ScriptedModelTurn;
import ai.agent.agent.store.AgentRunStore;
import ai.agent.agent.store.RunStatus;
import ai.agent.config.AgentApprovalProperties;
import ai.agent.review.ReviewTicket;
import ai.agent.review.TicketStore;
import ai.agent.tool.AgentTool;
import ai.agent.tool.ApprovalGrant;
import ai.agent.tool.ToolApprovalGate;
import ai.agent.tool.ToolCallContext;
import ai.agent.tool.ToolCallExecutor;
import ai.agent.tool.ToolCallOutcome;
import ai.agent.tool.ToolCallStage;
import ai.agent.tool.ToolInvocation;
import ai.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>人工确认（HITL）的行为契约测试</b> —— 阶段三第 9 件。
 *
 * <h2>本类要证明的最重要的一件事</h2>
 * 不是"审批代码能跑"，而是：<b>人拒绝之后，那个副作用真的没有发生。</b>
 * 这一条必须用"数一数工单表里有几条"来断言，而不是断言"回填文本里有'拒绝'两个字"——
 * 台账在 HITL 那一条里写得很清楚：
 * 「要做出可信的样子，必须先有至少一个『写』工具」，原因就在这里。
 * 一个只读工具走审批，无论断言写得多么漂亮，都回答不了这个问题。
 *
 * <h2>⚠️ 本类的测试写法有一个前提，值得单独说明</h2>
 * <b>闸门是阻塞的</b>：写操作走到门禁 ⑤ 时，当前线程会停下来等人
 * （见 {@code AgentApprovalService.await}）。这是刻意的设计 ——
 * 它让"人在页面上一按，那个还没结束的请求直接接着跑完"成为可能，
 * 而不是"先把任务存起来、再恢复一次"。
 *
 * <p>所以测试<b>不能</b>天真地写成"execute() 之后再 decide()"：
 * 那样 execute() 会一直阻塞到超时，decide() 永远轮不到执行。
 * 本类用两个小工具正确地表达这个时序：
 * <ul>
 *   <li>{@link #submit} —— 在另一个线程上发起调用（模拟"正在等的那个请求线程"）；</li>
 *   <li>{@link #awaitPending} —— 等到待批登记出来（<b>而不是 sleep 一个魔法数字</b>，
 *       那会让测试在慢机器上闪断，而闪断的测试最后总会被注释掉）。</li>
 * </ul>
 *
 * <h2>四组断言各自防的是什么</h2>
 * <ol>
 *   <li>{@link #readOnlyToolsNeverAskForApproval()} —— 防"审批变成仪式"：
 *       只读工具也要审批的话，人很快就开始无条件点通过，闸门也就没了；</li>
 *   <li>{@link #deniedWriteToolLeavesNoSideEffect()} —— 防"批了等于没批"，本类的核心；</li>
 *   <li>{@link #approvalCannotBeReversed()} —— 防"审计记录与真实执行对不上"；</li>
 *   <li>{@link #timeoutDeniesInsteadOfAllowing()} —— 防"默认通过"，
 *       那是审批制度最危险的默认值。</li>
 * </ol>
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.url=jdbc:h2:mem:approval-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"ai.agent.store.node=approval-test",
		// 【时限压到 2 秒】默认 120 秒对测试毫无意义，而"超时"这条路径必须被测到。
		// 用缩短时长（而不是删掉超时逻辑）来让测试变快，是本项目一贯的做法：
		// 被测的代码路径一根都不能少，只是把时间刻度改小
		"ai.agent.approval.timeout=2s",
		// 工单文件写到 target 下，别污染仓库根目录
		"ai.review.ticket-file=./target/test-review-tickets.jsonl",
		// 幂等窗口设成 0：本类测的是审批闸门，不是存储去重（那是另一条独立的边界）。
		// 不关掉的话，同一个标题在窗口内第二次创建会命中去重、
		// 于是"批准之后工单数 +1"那条断言会因为**另一个原因**变红 ——
		// 一个测试只该验证一件事
		"ai.review.deduplication-window=0s", "spring.ai.mcp.server.enabled=false" })
class AgentApprovalTest {

	private static final String WRITE_TOOL = "propose_review";

	/**
	 * 校验器与回填格式化器：{@code @SpringBootTest} 会给容器里的执行器注好，
	 * 但 {@link #loopSuspendsAndRecordsCheckpointWithNonBlockingGate()} 需要
	 * <b>手工 new 一个换掉闸门的执行器</b>（理由见那个用例），所以这里要自己造两个。
	 * 用真实现而不是替身：被测的是"挂起时的时序"，不该在那条路径上引入替身的行为差异。
	 */
	private static final jakarta.validation.Validator VALIDATOR = jakarta.validation.Validation
			.buildDefaultValidatorFactory().getValidator();

	private static final ai.agent.tool.support.ToolFeedbackFormatter FORMATTER = new ai.agent.tool.support.ToolFeedbackFormatter(
			2000);

	private static final AtomicReference<ScriptedModelTurn> CURRENT = new AtomicReference<>();

	@Autowired
	private ToolCallExecutor executor;

	@Autowired
	private AgentApprovalService approvals;

	@Autowired
	private TicketStore ticketStore;

	@Autowired
	private AgentLoop loop;

	@Autowired
	private AgentApprovalProperties approvalProperties;

	@Autowired
	private ToolRegistry registry;

	@TestConfiguration
	static class ScriptedModelConfig {

		@Bean
		@Primary
		ModelTurn scriptedModelTurn() {
			return messages -> {
				ScriptedModelTurn turn = CURRENT.get();
				if (turn == null) {
					throw new IllegalStateException("测试没有设置脚本模型");
				}
				return turn.chat(messages);
			};
		}

	}

	@BeforeEach
	void clearScript() {
		CURRENT.set(null);
	}

	/**
	 * 每个用例之后<b>把还没处理的待批全部拒掉</b>。
	 *
	 * <p>为什么必须做：闸门是阻塞的，而某个用例如果留下一个还停在 {@code await} 上的线程，
	 * 它会在<b>下一个用例</b>的时间窗里超时、往库（同一个内存库，全类共用一个 Spring 上下文）
	 * 里留下一条 TIMED_OUT —— 于是下一个用例拿到的"待批"根本不是它自己的那条。
	 * 这类失败的表现是"单独跑通过、一起跑就红"，是最费时间的一种。
	 */
	@AfterEach
	void drainPendingApprovals() {
		for (ToolApprovalRecord pending : approvals.pending()) {
			try {
				approvals.decide(pending.id(), false, "测试收尾", "清理未处理的待批");
			}
			catch (RuntimeException e) {
				// 已经被别的路径决定掉了 —— 那正是我们想要的结果
			}
		}
	}

	// ------------------------------------------------------------------
	//  两个时序小工具（本类所有用例的基础）
	// ------------------------------------------------------------------

	/** 在另一个线程上发起一次写调用 —— 模拟"正在等的那个请求线程"。 */
	private AsyncCall submit(String runId, int step, String arguments) {
		AsyncCall call = new AsyncCall(runId, step, arguments);
		call.start();
		return call;
	}

	/** 等某次运行的待批登记出来（最多 5 秒）。 */
	private ToolApprovalRecord awaitPending(String runId) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			var found = approvals.pendingOfRun(runId);
			if (found.isPresent()) {
				return found.get();
			}
			Thread.sleep(50);
		}
		throw new AssertionError("等了 5 秒也没看到运行 " + runId + " 的待批记录");
	}

	/** 一次后台调用：它会被闸门阻塞，直到有人决定或超时。 */
	private class AsyncCall {

		private final String runId;

		private final int step;

		private final String arguments;

		private final Thread thread;

		private final CountDownLatch done = new CountDownLatch(1);

		private volatile ToolCallOutcome outcome;

		private volatile Throwable failure;

		AsyncCall(String runId, int step, String arguments) {
			this.runId = runId;
			this.step = step;
			this.arguments = arguments;
			this.thread = new Thread(this::runCall, "hitl-async-" + runId);
		}

		void start() {
			thread.start();
		}

		private void runCall() {
			try {
				outcome = executor.execute(ToolInvocation.from("call-w", WRITE_TOOL, arguments),
						ToolCallContext.ofStep(runId, step));
			}
			catch (Throwable t) {
				failure = t;
			}
			finally {
				done.countDown();
			}
		}

		/** 等它结束（最多 5 秒）并返回结论。 */
		ToolCallOutcome await() throws InterruptedException {
			if (!done.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("后台调用没有在 5 秒内结束（run=" + runId + "）—— 闸门可能没被唤醒");
			}
			if (failure != null) {
				throw new AssertionError("后台调用抛了异常", failure);
			}
			return outcome;
		}

	}

	// ------------------------------------------------------------------
	//  ① 只读工具不该问人
	// ------------------------------------------------------------------

	@Test
	@DisplayName("只读工具**不**触发审批：否则人很快就开始无条件点通过，闸门退化成仪式")
	void readOnlyToolsNeverAskForApproval() {
		ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "list_documents", "{}"),
				ToolCallContext.ofStep("run-x", 1));

		assertThat(outcome.stage()).as("只读工具应当直接执行").isEqualTo(ToolCallStage.OK);
		assertThat(outcome.approval().present()).as("它不该有任何审批记录").isFalse();
		assertThat(approvals.pending()).as("待办列表必须仍然是空的").isEmpty();
	}

	// ------------------------------------------------------------------
	//  ② 核心：拒绝之后副作用真的没发生
	// ------------------------------------------------------------------

	@Test
	@DisplayName("人工**拒绝**之后：写操作没有执行、工单一条都没建、模型收到不可重试的拒绝说明")
	void deniedWriteToolLeavesNoSideEffect() throws Exception {
		int before = ticketStore.count();
		String title = "台账自相矛盾-" + System.nanoTime();

		// 1) 模型提出一次写调用，它在闸门处停下来等人（阻塞在另一个线程上）
		AsyncCall first = submit("run-deny", 1, "{\"title\":\"%s\",\"severity\":\"HIGH\"}".formatted(title));
		ToolApprovalRecord pending = awaitPending("run-deny");

		assertThat(pending.toolName()).isEqualTo(WRITE_TOOL);
		assertThat(pending.arguments())
				.as("留存的参数必须**就是会被执行的那一份** —— 否则人批的是 A、执行的是 B").contains(title);
		assertThat(pending.hints()).as("界面要能显示风险声明（来自 ToolHints，不是另写一份）").contains("非幂等");
		assertThat(ticketStore.count()).as("★ 还没批准，所以一条工单都不该有").isEqualTo(before);

		// 2) 人拒绝 —— 等待的线程应当当场被唤醒
		AgentApprovalService.Decision decision = approvals.decide(pending.id(), false, "张三", "标题与已有工单重复");
		assertThat(decision.accepted()).isTrue();
		assertThat(decision.awakened()).as("★ 有线程在等，决定必须当场唤醒它").isTrue();
		assertThat(decision.status()).isEqualTo(ApprovalStatus.DENIED);

		ToolCallOutcome denied = first.await();
		assertThat(denied.stage()).isEqualTo(ToolCallStage.APPROVAL_DENIED);
		assertThat(denied.toolResult()).as("它没有结果 —— 一次都没执行过").isNull();
		assertThat(denied.fixableByModel())
				.as("人工拒绝**不值得再给模型一轮**：原样重提会被再拦一次，而'再拒一次'不该由模型的重试驱动")
				.isFalse();
		assertThat(denied.feedback()).as("拒绝理由必须原样带上 —— 它是人给出的唯一一条新信息")
				.contains("标题与已有工单重复").contains("不要再重新发起");
		assertThat(ticketStore.count()).as("★★★ 本类最重要的一条断言：被拒绝的写操作**没有产生任何副作用**")
				.isEqualTo(before);

		// 3) 审计：这条记录里能读出"谁、什么时候、为什么"
		ToolApprovalRecord record = approvals.find(pending.id()).orElseThrow();
		assertThat(record.status()).isEqualTo(ApprovalStatus.DENIED);
		assertThat(record.decidedBy()).isEqualTo("张三");
		assertThat(record.decisionNote()).isEqualTo("标题与已有工单重复");
		assertThat(record.decidedAt()).as("决定时间必须落下来（'什么时候让人确认'的答案靠它）").isNotNull();
		assertThat(record.runId()).as("审批要挂在运行上 —— 否则运行详情里看不到'它当时在等什么'")
				.isEqualTo("run-deny");
	}

	// ------------------------------------------------------------------
	//  ③ 批准之后副作用真的发生（与 ② 成对 —— 单向断言接不住漂移）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("人工**批准**之后：写操作真的执行、工单建出来、并且工单里带着审批 id（审计闭环）")
	void approvedWriteToolActuallyWrites() throws Exception {
		int before = ticketStore.count();
		String title = "批准路径验证-" + System.nanoTime();
		String arguments = "{\"title\":\"%s\",\"severity\":\"LOW\"}".formatted(title);

		AsyncCall first = submit("run-approve", 1, arguments);
		ToolApprovalRecord pending = awaitPending("run-approve");
		approvals.decide(pending.id(), true, "李四", "确认要建");

		ToolCallOutcome approved = first.await();
		assertThat(approved.stage()).as("批准之后应当直接执行").isEqualTo(ToolCallStage.OK);
		assertThat(approved.approval().approvalId()).as("结论要挂回那条审批，审计才连得起来").isEqualTo(pending.id());
		assertThat(approved.approval().approvedByHuman()).as("这次是**真人批的**，不是'不需要审批'").isTrue();
		assertThat(ticketStore.count()).as("★ 批准之后工单必须真的建出来").isEqualTo(before + 1);

		ReviewTicket ticket = ticketStore.all().stream().filter(t -> title.equals(t.title())).findFirst()
				.orElseThrow();
		assertThat(ticket.approvalId())
				.as("工单里必须带上放行它的审批 id —— 这样'一条不该存在的工单'能直接倒查到'哪一次审批放行了它'")
				.isEqualTo(pending.id());
	}

	@Test
	@DisplayName("通行证只对**那一次调用**有效：参数改了就必须重新审批（否则审批变成一次性橡皮图章）")
	void grantIsBoundToExactArguments() throws Exception {
		String original = "{\"title\":\"原始标题-%d\",\"severity\":\"LOW\"}".formatted(System.nanoTime());
		AsyncCall first = submit("run-grant", 1, original);
		ToolApprovalRecord pending = awaitPending("run-grant");
		approvals.decide(pending.id(), true, "王五", null);
		assertThat(first.await().stage()).isEqualTo(ToolCallStage.OK);

		// 参数被改了 —— 人批准的是"原始标题"，模型现在要建的是另一个标题。
		// 带上那张旧通行证：它**不该**匹配（ApprovalGrant 按"工具名 + 参数"匹配）
		ApprovalGrant staleGrant = ApprovalGrant.of(pending.id(), WRITE_TOOL, original);
		int before = ticketStore.count();

		// 这次调用会停在闸门上等人 —— 所以它在后台线程上跑（由 @AfterEach 统一拒掉）
		AsyncCall changed = submit("run-grant", 2, "{\"title\":\"另一个标题\",\"severity\":\"LOW\"}");
		ToolApprovalRecord second = awaitPending("run-grant");

		assertThat(second.id()).as("这是一条**新的**待批，不是复用旧的那条").isNotEqualTo(pending.id());
		assertThat(staleGrant.matches(ToolInvocation.from("x", WRITE_TOOL, "{\"title\":\"另一个标题\",\"severity\":\"LOW\"}")))
				.as("★ 参数不同 = 另一件事 = 通行证不匹配。放行它的后果是"
						+ "「人批的是 A、执行的是 B」—— 审批制度最严重的失效形态")
				.isFalse();
		assertThat(ticketStore.count()).as("第二次调用还没被批准，所以工单数不变").isEqualTo(before);
		assertThat(changed).isNotNull();
	}

	// ------------------------------------------------------------------
	//  ④ 审计记录不可被反转
	// ------------------------------------------------------------------

	@Test
	@DisplayName("同一条审批不能被决定两次：人的决定写下之后，重复决定必须写不进去")
	void approvalCannotBeReversed() throws Exception {
		AsyncCall call = submit("run-once", 1,
				"{\"title\":\"不可反转-%d\",\"severity\":\"LOW\"}".formatted(System.nanoTime()));
		ToolApprovalRecord pending = awaitPending("run-once");

		approvals.decide(pending.id(), true, "赵六", "第一次决定");
		call.await();
		assertThat(approvals.find(pending.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);

		// 第二次决定：**必须报错**而不是静默覆盖。
		// 静默覆盖的后果是"审计记录与真实执行对不上"—— 而那是 HITL 最不该发生的事
		assertThatThrownBy(() -> approvals.decide(pending.id(), false, "钱七", "想反悔"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("不能重复决定");

		ToolApprovalRecord after = approvals.find(pending.id()).orElseThrow();
		assertThat(after.status()).isEqualTo(ApprovalStatus.APPROVED);
		assertThat(after.decidedBy()).as("第一个写下决定的人不能被后来者顶掉").isEqualTo("赵六");

		// 作废那条路径同样写不进去（decide 的 WHERE 带 status='PENDING'）。
		// 这是"谁先到谁生效"的落点：一条已被人工批准的记录，不该被随后到达的作废覆盖
		assertThat(approvals.expire(pending.id(), "迟到的作废")).as("已经决过的记录，迟到的决定必须写不进去").isFalse();
		assertThat(approvals.find(pending.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
	}

	// ------------------------------------------------------------------
	//  ⑤ 超时 = 拒绝（本模块最重要的取向决定）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("等人超时 → 按**拒绝**处理（不是默认通过），并且归到 APPROVAL_TIMEOUT 而不是 DENIED")
	void timeoutDeniesInsteadOfAllowing() throws Exception {
		int before = ticketStore.count();
		long startedAt = System.currentTimeMillis();

		// 没有任何人来决定：这一次会一直等到时限
		AsyncCall call = submit("run-timeout", 1,
				"{\"title\":\"没人处理-%d\",\"severity\":\"LOW\"}".formatted(System.nanoTime()));
		ToolCallOutcome outcome = call.await();
		long waitedMs = System.currentTimeMillis() - startedAt;

		assertThat(outcome.stage()).as("★ 超时必须**拒绝**：默认通过会让这道闸门在最需要它的时候自动消失")
				.isEqualTo(ToolCallStage.APPROVAL_TIMEOUT);
		assertThat(outcome.stage())
				.as("它与 APPROVAL_DENIED 必须分开：'人不批准'是业务判断，'没人搭理'是流程没接上 —— "
						+ "要去修的东西没有一寸重叠")
				.isNotEqualTo(ToolCallStage.APPROVAL_DENIED);
		assertThat(ticketStore.count()).as("★ 超时之后工单一条都不该建").isEqualTo(before);
		assertThat(waitedMs).as("应当等满配置的 2 秒（实际 %dms）", waitedMs).isGreaterThanOrEqualTo(1900);
		assertThat(outcome.feedback()).contains("超时").contains("没有执行");
		assertThat(approvals.find(outcome.approval().approvalId()).orElseThrow().status())
				.as("库里也要落成 TIMED_OUT，而不是留在 PENDING（留在 PENDING 会让界面一直显示'在等你'）")
				.isEqualTo(ApprovalStatus.TIMED_OUT);
	}

	// ------------------------------------------------------------------
	//  ⑥ 循环层：停在写操作上，且写操作真的没执行
	// ------------------------------------------------------------------

	@Test
	@DisplayName("AgentLoop 走到写调用上时不会执行它：结论落在审批相关的那两类，且工单数不变")
	void loopSuspendsOnWriteTool() {
		// 脚本模型只有一条"要调 propose_review" —— 审批时限 2 秒过后它被判超时，
		// 而模型没有下一轮可用，于是循环以 MAX_STEPS 收尾。
		// 关键断言是：**那个写工具从来没有真的执行过**
		//
		// ⚠️ 这一条要等满配置的 2 秒：闸门是阻塞的，而循环跑在**测试线程**上，
		// 所以这个等待不可避免（除非把闸门改成非阻塞 —— 那会丢掉"同一个请求里
		// 人一按就继续"这个能力）。2 秒是刻意为测试压小的量级，
		// 而"超时这条路径真的被走到了"正是本用例想覆盖的东西之一。
		//
		// 真正的"挂起（WAITING_FOR_APPROVAL）"路径另有用例覆盖 —— 见
		// loopSuspendsAndRecordsCheckpointWithNonBlockingGate()：
		// 阻塞闸门**永远不会**返回"待批"，所以那条路径必须用不阻塞的桩才测得出来
		CURRENT.set(new ScriptedModelTurn(List.of(ScriptedModelTurn.callTool("call_1", WRITE_TOOL,
				"{\"title\":\"循环挂起测试-%d\",\"severity\":\"HIGH\"}".formatted(System.nanoTime())))));

		int before = ticketStore.count();
		AgentRun run = loop.run("把这个问题记下来");

		assertThat(run.allOutcomes()).as("应当有一次工具调用的结论").isNotEmpty();
		assertThat(run.allOutcomes()).anySatisfy(outcome -> assertThat(outcome.stage())
				.isIn(ToolCallStage.APPROVAL_TIMEOUT, ToolCallStage.AWAITING_APPROVAL));
		assertThat(ticketStore.count()).as("★ 没有人工批准过，所以工单数不该变").isEqualTo(before);
		// 这一步的结论是"没执行"，所以循环还会再问模型一轮（那一轮它没有新信息可用）。
		// 断言"确实多问了一轮"而**不是**"它给出了答案"：后者取决于脚本的兜底回复写了什么，
		// 那是一个与本次要验证的行为无关的细节
		assertThat(run.steps()).as("第 1 步调工具、第 2 步模型收尾").hasSizeGreaterThanOrEqualTo(2);
	}

	/**
	 * <b>不阻塞</b>的桩闸门：登记一条待批之后立刻返回"待批"。
	 *
	 * <h2>为什么必须有它：真实闸门永远不会走到那条路径</h2>
	 * 真实的 {@link AgentApprovalService} 是<b>阻塞</b>的 —— 它在 {@code await} 里等人，
	 * 所以它要么等到"批准"、要么等到"拒绝"、要么等到"超时"，
	 * <b>永远不会把"待批"这个中间状态交回给循环</b>。
	 *
	 * <p>也就是说：只测真实闸门的话，{@code AgentLoop} 里那段
	 * "遇到待批就写检查点、并带着 {@link AgentStopReason#WAITING_FOR_APPROVAL} 返回"的代码
	 * <b>一次都不会被执行到</b> —— 而它是本次改动里最容易写错的一段
	 * （它要求在 outcomes 与 messages 都不落的情况下安全返回，
	 * 因为"待批"的那一步还没有结束）。
	 *
	 * <p>这段代码的存在理由是"给另一种闸门实现留出口"（例如将来某条链路希望
	 * 不阻塞、直接把待办返回给调用方），所以它必须被一个不阻塞的桩覆盖 ——
	 * 这正是台账那条结论的又一次应用：
	 * <b>一个分支如果没有任何测试能走到，它就等于没被实现过。</b>
	 */
	private static class NonBlockingGate implements ToolApprovalGate {

		@Override
		public Authorization authorize(ToolInvocation invocation, AgentTool<?> tool, ToolCallContext context) {
			if (!tool.description().requiresApproval()) {
				return Authorization.Allowed.notRequired();
			}
			String id = java.util.UUID.randomUUID().toString();
			return new Authorization.Pending(id,
					new ApprovalRequest(id, context.runId(), context.stepIndex(), tool.name(),
							invocation.arguments(), tool.description().hints(), tool.description().summary()));
		}

		@Override
		public String describe() {
			return "测试桩：不阻塞，直接返回待批";
		}

	}

	@Test
	@DisplayName("循环遇到**待批**（非阻塞闸门）时：写检查点、返回 WAITING_FOR_APPROVAL、"
			+ "并且**不伪造**一条工具返回（待批的那一步不落进 steps）")
	void loopSuspendsAndRecordsCheckpointWithNonBlockingGate() {
		// 换一个不阻塞的闸门：只有这样才能走到"挂起"那条路径（理由见 NonBlockingGate 的注释）
		ToolCallExecutor nonBlocking = new ToolCallExecutor(registry, VALIDATOR, FORMATTER,
				ai.agent.agent.limit.NoopPerToolGate.instance(), new NonBlockingGate());
		AgentLoop loopWithStub = new AgentLoop(registry, nonBlocking, messages -> {
			ScriptedModelTurn turn = CURRENT.get();
			return turn == null ? ModelTurn.ModelReply.text("无脚本", 0, 0) : turn.chat(messages);
		}, loop.properties(), new ai.agent.prompt.ClasspathPromptService(
				new ai.agent.token.impl.CharacterClassTokenEstimator(new ai.agent.config.ChatCallProperties())));

		String title = "挂起路径-%d".formatted(System.nanoTime());
		CURRENT.set(new ScriptedModelTurn(List.of(ScriptedModelTurn.callTool("call_1", WRITE_TOOL,
				"{\"title\":\"%s\",\"severity\":\"LOW\"}".formatted(title)), ScriptedModelTurn.answer("在等确认"))));

		int before = ticketStore.count();
		java.util.concurrent.atomic.AtomicInteger checkpoints = new java.util.concurrent.atomic.AtomicInteger();
		// 注意：这里必须写匿名类而不是 lambda —— AgentProgressListener 的三个方法
		// **都有默认实现**，所以它不是函数式接口（一个"全部可选"的接口没法用 lambda 表达）
		AgentProgressListener countingListener = new AgentProgressListener() {
			@Override
			public void afterStep(String runId, int stepIndex, AgentRun.Step step, java.util.List<org.springframework.ai.chat.messages.Message> messages,
					int totalTokensSoFar, int toolCallsSoFar, long elapsedMsSoFar, String promptFingerprint) {
				checkpoints.incrementAndGet();
			}
		};
		AgentRun run = loopWithStub.run("run-suspend", "把这个问题记下来", countingListener);

		assertThat(run.stopReason())
				.as("★ 停因必须是 WAITING_FOR_APPROVAL：它是独立的出口，既不是失败也不是被上限拦下 —— "
						+ "否则页面会说'步数用完'，而真相是'在等人点按钮'")
				.isEqualTo(AgentStopReason.WAITING_FOR_APPROVAL);
		assertThat(run.finalAnswer()).as("它没有答案 —— 任务停住了").isNull();
		assertThat(run.toolCallCount()).as("模型确实提出过一次调用").isEqualTo(1);
		assertThat(run.steps())
				.as("★ 待批的那一步**不落进 steps** —— 它还没有结束。"
						+ "落进去等于伪造一条'这一步已完成'的记录，而那会让报告说它执行过")
				.isEmpty();
		assertThat(checkpoints.get()).as("★ 检查点必须写：恢复要'接着跑'，而接着跑靠的就是这份上下文").isEqualTo(1);
		assertThat(ticketStore.count()).as("★ 没人批准，工单一条都不该建").isEqualTo(before);
	}

	@Test
	@DisplayName("等待时限可按调用覆盖：后台链路与交互式链路该有不同的等待预算，而不是钉死在同一个全局值上")
	void decisionTimeoutCanBeOverriddenPerCall() throws Exception {
		// 覆盖成 300 毫秒：同一个语义、更小的时间刻度。
		// 这条能力本身就是设计的一部分（见 ToolCallContext.decisionTimeout 的注释），
		// 而它顺带让"超时"这条路径能在不删逻辑的前提下被快速测到
		long startedAt = System.currentTimeMillis();
		ToolCallOutcome outcome = executor.execute(
				ToolInvocation.from("call-w", WRITE_TOOL,
						"{\"title\":\"覆盖时限-%d\",\"severity\":\"LOW\"}".formatted(System.nanoTime())),
				ToolCallContext.ofStep("run-override", 1, Duration.ofMillis(300)));
		long waitedMs = System.currentTimeMillis() - startedAt;

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.APPROVAL_TIMEOUT);
		assertThat(waitedMs)
				.as("应当按覆盖值（300ms）超时，而不是按全局的 2 秒 —— 否则这个覆盖根本没生效").isLessThan(1500);
		// 回填里报的必须是**实际生效**的那个时限，而且要写得对。
		// 这里刻意断言"300 毫秒"而不是"0 秒"—— 后者是 toSeconds() 会产生的误导性输出
		// （0.3 秒截断成 0，读起来像"压根没等"），见 AgentApprovalService.human 的注释
		assertThat(outcome.feedback()).contains("超过 300 毫秒");

		// 覆盖值同样不允许 0/负数（那等于永远等下去）——
		// 在这里挡一次是必要的："某条链路传了 0 进来"与"配置写错了"是两种来源
		assertThatThrownBy(() -> executor.execute(
				ToolInvocation.from("call-w2", WRITE_TOOL, "{\"title\":\"零时限\",\"severity\":\"LOW\"}"),
				ToolCallContext.ofStep("run-zero", 1, Duration.ZERO)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须是正的");
	}

	// ------------------------------------------------------------------
	//  ⑦ 库状态规则：待批的运行不该被当成"被中断"
	// ------------------------------------------------------------------

	@Test
	@DisplayName("停因 WAITING_FOR_APPROVAL 落成 WAITING_APPROVAL 状态，且**不可被恢复**"
			+ "（否则下次启动会重新提出写调用、产生第二条待批）")
	void waitingStatusIsNotResumable() {
		// 这条断言测的是"停因 → 库状态"这条映射规则，而不是跑一遍完整循环 ——
		// 要真跑出 WAITING_FOR_APPROVAL，审批时限必须长于整个测试的时间预算，
		// 而"把时限调大然后等"会让测试又慢又受机器影响。
		// AgentRunStore.persistCompletedRun 是接口的 default 方法，
		// 所以可以直接拿一份"内存里的运行"去问它 —— 被测的就是运行时那一份代码
		RunStatus status = AgentRunStore.statusOf(
				new AgentRun("q", List.of(), null, AgentStopReason.WAITING_FOR_APPROVAL, 0, 0, 0));

		assertThat(status).isEqualTo(RunStatus.WAITING_APPROVAL);
		// 这三条规则写在 AgentRunRecord 上（它才是页面与恢复逻辑实际读的东西）
		var record = new ai.agent.agent.store.AgentRunRecord("r", "q", status,
				AgentStopReason.WAITING_FOR_APPROVAL, null, 0, 0, 0, "test", null, java.time.Instant.now(),
				java.time.Instant.now());
		assertThat(record.finished())
				.as("待批算'这一次执行已经交出了结论'，所以界面上不该显示成'正在运行'").isTrue();
		assertThat(record.resumable())
				.as("★ 待批的运行**不该**被当成可恢复的：它已经在正确的等待里了，"
						+ "硬恢复它只会重新提出那个写调用、产生第二条待批")
				.isFalse();
	}

	// ------------------------------------------------------------------
	//  ⑧ 配置防线
	// ------------------------------------------------------------------

	@Test
	@DisplayName("审批时限**不允许** 0 或负数：那表示'永远等下去'，故障模式会从报错变成静默挂起")
	void zeroTimeoutIsRejectedAtConstruction() {
		assertThatThrownBy(() -> new AgentApprovalProperties(true, Duration.ZERO, "x"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeout");
		assertThatThrownBy(() -> new AgentApprovalProperties(true, Duration.ofSeconds(-1), "x"))
				.isInstanceOf(IllegalArgumentException.class);
		// 没填决定人时必须落成 unknown 而不是空（空值在审计时等于没有）
		assertThat(new AgentApprovalProperties(true, Duration.ofSeconds(1), "  ").defaultDecider())
				.isEqualTo("unknown");
	}

	@Test
	@DisplayName("总开关关掉时闸门直接放行，并且 describe() 必须**明确说出来**"
			+ "（否则'没拦住'看起来像'没危险'—— 这正是踩坑 #67 的形状）")
	void disabledApprovalAllowsEverythingButSaysSo() {
		assertThat(approvalProperties.enabled()).as("默认必须是打开 —— 它保护的是不可逆的副作用").isTrue();
		assertThat(approvals.describe()).contains("已启用").contains("待批");

		// 用同一个存储、换一份"关掉"的配置：这样测的是"配置真的在起作用"，
		// 而不是"测试自己造的那个组合能跑"
		AgentApprovalService disabled = new AgentApprovalService(approvals.store(),
				new AgentApprovalProperties(false, Duration.ofSeconds(5), "x"));
		assertThat(disabled.describe()).contains("被关闭");

		AgentTool<?> writeTool = registry.find(WRITE_TOOL).orElseThrow();
		ToolApprovalGate.Authorization authorization = disabled.authorize(
				ToolInvocation.from("c1", WRITE_TOOL, "{\"title\":\"关掉审批\",\"severity\":\"LOW\"}"), writeTool,
				ToolCallContext.ofStep("run-off", 1));

		assertThat(authorization).isInstanceOf(ToolApprovalGate.Authorization.Allowed.class);
		assertThat(((ToolApprovalGate.Authorization.Allowed) authorization).approved())
				.as("不是'人批准了'，而是'压根不需要审批'—— 两者必须分得开（approved 布尔就是那个区分）")
				.isFalse();
		assertThat(disabled.pending()).as("关掉时不该产生任何待批记录").isEmpty();
	}

	@Test
	@DisplayName("工具的风险声明是单一事实来源：写工具声明 requiresApproval、只读工具不声明")
	void riskDeclarationsDriveApproval() {
		assertThat(registry.find(WRITE_TOOL).orElseThrow().description().requiresApproval())
				.as("propose_review 是非幂等的写 —— 必须要求审批").isTrue();
		assertThat(registry.find(WRITE_TOOL).orElseThrow().description().hints().describe()).contains("非幂等");

		for (String readOnly : List.of("search_docs", "list_documents", "read_section")) {
			var hints = registry.find(readOnly).orElseThrow().description().hints();
			assertThat(hints.readOnlyHint()).as("%s 应当声明为只读", readOnly).isTrue();
			assertThat(hints.requiresApproval()).as("只读工具不该要求审批", readOnly).isFalse();
		}

		// 自相矛盾的声明必须在**构造期**就抛（不留到注册期自检）
		assertThat(new ai.agent.tool.model.ToolHints(true, true, false, false).selfContradictory())
				.as("只读 + 破坏性 是自相矛盾的").isTrue();
		assertThatThrownBy(() -> new ai.agent.tool.model.AgentToolDefinition("t", "摘要", "何时用", "", "", "",
				new ai.agent.tool.model.ToolHints(true, true, false, false)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("自相矛盾");
		assertThat(new ai.agent.tool.model.ToolHints(false, false, true, true).requiresApproval())
				.as("幂等的写**免审批** —— 这条规则不是特例，它由'重复执行有没有额外副作用'推出")
				.isFalse();
	}

}
