package ai.agent.agent.approval;

import ai.agent.config.AgentApprovalProperties;
import ai.agent.tool.AgentTool;
import ai.agent.tool.ApprovalGrant;
import ai.agent.tool.ToolApprovalGate;
import ai.agent.tool.ToolCallContext;
import ai.agent.tool.ToolCallStage;
import ai.agent.tool.ToolInvocation;
import ai.agent.tool.model.ToolHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <b>人工确认（HITL）的服务端</b> —— 同时扮演三个角色。
 *
 * <h2>角色一：执行器看到的闸门（{@link ToolApprovalGate}）</h2>
 * 高危及写操作走到这里时，本类<b>把当前线程停住</b>去等人。
 *
 * <p><b>为什么"停住一个线程"在这里是正确做法，而不是设计缺陷</b>：
 * 本项目跑在虚拟线程上（阶段一学习点 7 的改造）。虚拟线程被阻塞时，
 * 它占用的载体线程（平台线程）会<b>被释放去干别的活</b> ——
 * "等一个人两分钟"这种场景正是虚拟线程被设计出来要解决的问题：
 * 固定线程池时代，等 8 个人就会把池子占满；虚拟线程时代，等 800 个也只是一个数字。
 * 所以这里不需要"回调 + 状态机"那套复杂写法，一个 {@link CompletableFuture#get} 就够，
 * 而<b>简单的那版更容易被证明是对的</b>。
 * （这一条正好是阶段一那次改造的兑现：当时留下的风险提醒是
 * "并发上限从 200 个线程松开之后，真正会被打挂的变成了下游"，
 * 而 HITL 是少见的"虚拟线程让能力变简单"的例子，不是"让风险变大"的例子。）
 *
 * <h2>角色二：人看到的待办（{@link #pending()} / {@link #decide}）</h2>
 * 界面靠这两个方法回答"现在有什么在等我批"。<b>决定端点与等待线程是两件事</b>：
 * 决定总是先落库（审计凭据优先），再去唤醒线程 ——
 * 顺序反过来的话，一次"批准了但进程恰好在这一刻崩了"会丢掉那条审计记录，
 * 而人已经以为自己批过了。
 *
 * <h2>角色三：恢复时重建"已批准"这件事</h2>
 * {@link #approvedBy} 给断点恢复用。原因见 {@code ToolApprovalGate.Authorization.Allowed} 的注释：
 * "人已经批准过"是一个<b>不该随进程重启消失</b>的事实，所以它必须能从库里读回来，
 * 而不是留在某个对象的内存里。
 *
 * <h2>超时为什么判"拒绝"，而不是"默认通过"</h2>
 * 这是本类最重要的一个取向决定。两种做法都有人用，但它们的失效方向完全不同：
 * <ul>
 *   <li><b>超时=通过</b>：没人管事时系统照常写数据。而"没人管事"恰恰是审批流没接上
 *       最常见的形态 —— 于是这道闸门在最需要它的时候自动失效，且没有任何人注意到；</li>
 *   <li><b>超时=拒绝</b>（本实现）：没人管事时任务停下来。代价是有人抱怨"怎么又卡住了"，
 *       而<b>这个抱怨正是应该发生的事</b> —— 它把"值班没接上"变成一个必须解决的问题。</li>
 * </ul>
 * 用一句话概括：<b>审批的默认值必须是"不做"，因为"做了"是不可逆的。</b>
 */
@Service
public class AgentApprovalService implements ToolApprovalGate {

	private static final Logger log = LoggerFactory.getLogger(AgentApprovalService.class);

	/**
	 * 正在等人的那些线程的唤醒把手（approvalId → future）。
	 *
	 * <p>⚠️ 这是<b>内存态</b>，且刻意如此：它表示"当前这个进程里有一个线程正停在这里"。
	 * 进程一重启，这个 map 自然空掉 —— 而那时"库里有一条 PENDING"是<b>正确的历史事实</b>，
	 * 不该被清掉（人仍然需要看到它、并知道这次运行已经被中断了）。
	 * 两个状态各自表达一件事，不要把它们合并。
	 */
	private final Map<String, CompletableFuture<ApprovalStatus>> waiters = new ConcurrentHashMap<>();

	private final ToolApprovalStore store;

	private final AgentApprovalProperties properties;

	public AgentApprovalService(ToolApprovalStore store, AgentApprovalProperties properties) {
		this.store = store;
		this.properties = properties;
		log.info("人工确认（HITL）已装配：enabled={} timeout={}", properties.enabled(), properties.timeout());
	}

	// ------------------------------------------------------------------
	//  角色一：执行器看到的闸门
	// ------------------------------------------------------------------

	@Override
	public Authorization authorize(ToolInvocation invocation, AgentTool<?> tool, ToolCallContext context) {
		ToolHints hints = tool.description().hints();

		// ---- 不需要审批：只读，或幂等的写。见 ToolHints.requiresApproval() 的三条判据 ----
		if (!properties.enabled() || !hints.requiresApproval()) {
			return Authorization.Allowed.notRequired();
		}

		// ---- 已经在这次恢复里被批准过？ ----
		// 位置很关键：它在"新建一条待批"之前，而且走的是**显式的通行证**，
		// 不是"再查一次库"。见 ToolCallContext.grant 的注释：批准发生在上一段请求里，
		// 恢复时闸门的内存记忆已经没了，所以"批过"必须作为本次调用的输入传进来
		if (context.hasGrant() && context.grant().matches(invocation)) {
			ApprovalGrant grant = context.grant();
			Optional<ToolApprovalRecord> record = store.find(grant.approvalId());
			if (record.isPresent() && record.get().status() == ApprovalStatus.APPROVED) {
				log.info("工具「{}」的调用命中已批准记录 #{}，直接执行（不再重复询问）", tool.name(), grant.approvalId());
				return Authorization.Allowed.preApproved(grant.approvalId());
			}
			// 通行证指向的审批记录不是 APPROVED（被拒了、超时了、或压根不存在）——
			// **不能放行**。这是审批制度最不能让步的一处：一个失效的通行证必须失败，
			// 而不是"查不到就当批过"
			log.warn("工具「{}」带着通行证 #{}，但那条审批记录当前不是 APPROVED（{}），按未批准处理",
					tool.name(), grant.approvalId(), record.map(ToolApprovalRecord::status).orElse(null));
		}

		// ---- 新建一条待批 + 把当前线程停住 ----
		ToolApprovalRecord record = register(invocation, tool, context, hints);
		ApprovalStatus decided = await(record.id(), effectiveTimeout(context));

		return switch (decided) {
			case APPROVED -> Authorization.Allowed.preApproved(record.id());
			case TIMED_OUT, EXPIRED -> new Authorization.Denied(ToolCallStage.APPROVAL_TIMEOUT,
					"等待人工确认超过 %s，没有人处理。这次调用没有执行（超时按拒绝处理）"
							.formatted(human(effectiveTimeout(context))),
					record.id());
			default -> {
				ToolApprovalRecord latest = store.find(record.id()).orElse(record);
				yield new Authorization.Denied(ToolCallStage.APPROVAL_DENIED,
						"人工确认未通过：%s".formatted(
								latest.decisionNote() == null || latest.decisionNote().isBlank() ? "（未填写理由）"
										: latest.decisionNote()),
						record.id());
			}
		};
	}

	/**
	 * 这一次调用实际用哪个等待时限。
	 *
	 * <p>优先级：<b>调用方给的覆盖值 &gt; 全局配置</b>。
	 * 为什么允许覆盖，见 {@link ToolCallContext#decisionTimeout()}：
	 * 不同链路的等待预算本来就不一样（交互式请求背后有人在看页面，后台任务可以等很久），
	 * 而把它钉死在同一个全局值上必然一边嫌短一边嫌长。
	 *
	 * <p>覆盖值同样受"不允许 0/负数"的约束 —— 在这里再挡一次，
	 * 因为"某条链路传了一个 0 进来"与"配置写错了"是两种不同的来源，
	 * 而它们的后果一样（永远等下去）。
	 */
	private Duration effectiveTimeout(ToolCallContext context) {
		Duration override = context == null ? null : context.decisionTimeout();
		if (override == null) {
			return properties.timeout();
		}
		if (override.isZero() || override.isNegative()) {
			throw new IllegalArgumentException(
					"调用方给的 ai.agent.approval 等待时限必须是正的（收到 " + override + "）—— 0/负数等于永远等下去");
		}
		return override;
	}

	/**
	 * 登记一条待批记录，并注册唤醒把手。
	 *
	 * <p>顺序是<b>先落库、再注册 future</b>：反过来的话，在两者之间到达的人工决定
	 * 会因为"future 还没注册"而被丢掉，于是线程永远等下去（一个只在竞态下出现的死等）。
	 * 先落库则最坏情况是"记录已存在、future 尚未注册，决定先到" ——
	 * 那种情况由 {@link #decide} 里的兜底分支处理（它会把状态直接写进库里，
	 * 而 {@link #await} 在拿到 future 前会先查一次库）。
	 */
	private ToolApprovalRecord register(ToolInvocation invocation, AgentTool<?> tool, ToolCallContext context,
			ToolHints hints) {
		String id = UUID.randomUUID().toString();
		ToolApprovalRecord record = ToolApprovalRecord.pending(id, context.runId(), context.stepIndex(), tool.name(),
				invocation.argumentsForFeedback(ai.agent.agent.approval.JdbcToolApprovalStore.ARGUMENTS_MAX_CHARS),
				hints.describe(), tool.description().summary(), Instant.now());
		store.save(record);
		waiters.put(id, new CompletableFuture<>());
		log.warn("工具「{}」需要人工确认，已登记待批 #{}（等最多 {}s）：{}", tool.name(), id, properties.timeout().toSeconds(),
				record.arguments());
		return record;
	}

	/**
	 * 停住当前线程等人（最长 {@link AgentApprovalProperties#timeout()}）。
	 *
	 * <p>超时的实现刻意<b>不用调度线程</b>，而是让 {@code future.get(timeout)} 自己超时：
	 * 少一个线程池、少一处需要被关闭的资源，而且"等了多久"这件事由 JDK 保证
	 * （自己起定时器的话，多一条"定时器没跑"的失败路径）。
	 * 超时之后再把状态写进库 —— 那一次写是幂等的（
	 * {@link ToolApprovalStore#decide} 只在 PENDING 时生效），
	 * 所以它与人在这期间恰好点了"批准"这件事不会互相覆盖出错误结果：
	 * <b>谁先到谁生效</b>，另一个被明确地忽略。
	 */
	private ApprovalStatus await(String approvalId, Duration timeout) {
		CompletableFuture<ApprovalStatus> future = waiters.computeIfAbsent(approvalId,
				id -> new CompletableFuture<>());

		// ---- 决定可能比这次等待先到（见 register 的注释：先落库再注册把手）----
		// 先查一次库，把这个竞态窗口关掉。不查的话，那个窗口里的决定会**丢掉一个 future**，
		// 于是线程白等满整个超时 —— 结果是对的（超时也是拒绝），
		// 但"批准了却等到超时"这件事会让审批制度看起来像坏了
		Optional<ToolApprovalRecord> already = store.find(approvalId).filter(r -> !r.pending());
		if (already.isPresent()) {
			waiters.remove(approvalId);
			return already.get().status();
		}

		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			boolean written = store.decide(approvalId, ApprovalStatus.TIMED_OUT, Instant.now(), null,
					"超过 %d 秒没有人处理".formatted(timeout.toSeconds()));
			log.warn("待批 #{} 超时（{}s）{}", approvalId, timeout.toSeconds(),
					written ? "" : "—— 但记录已被其它决定抢先写入，忽略本次超时");
			if (written) {
				// 通知可能与我们擦肩而过（decide 里读 future 读到的还是 null）。
				// 补一次 complete，免得它留在 map 里成为一次泄漏。
				// 用 Optional.empty() 作哨兵：它在这里只会被 get() 到一次，之后就被移除了
				future.complete(null);
			}
			return written ? ApprovalStatus.TIMED_OUT : statusOf(approvalId);
		}
		catch (InterruptedException e) {
			// 恢复中断标志：吞掉它会让"取消"这个信号在这一层消失，
			// 而调用方（虚拟线程的取消 / 关闭流程）正是靠它在等的
			Thread.currentThread().interrupt();
			store.decide(approvalId, ApprovalStatus.EXPIRED, Instant.now(), null, "等待被中断");
			log.warn("待批 #{} 的等待被中断", approvalId);
			return ApprovalStatus.EXPIRED;
		}
		catch (ExecutionException e) {
			// decide() 只 complete 不 completeExceptionally，所以走到这里说明是别处出的问题。
			// 保守地按"没批"处理 —— 与超时同一条取向：默认不做
			log.error("待批 #{} 的等待异常", approvalId, e);
			return ApprovalStatus.EXPIRED;
		}
		finally {
			// 把手撤掉：否则每处理一次写调用就往这个 map 里留一条，
			// 长时间跑下来是一次典型的内存泄漏（工具数量有上限，审批次数没有）
			waiters.remove(approvalId);
		}
	}

	// ------------------------------------------------------------------
	//  角色二：人看到的待办
	// ------------------------------------------------------------------

	/** 还没人处理的待批（先来的在前）。 */
	public List<ToolApprovalRecord> pending() {
		return store.pending();
	}

	/** 某次运行的全部审批记录 —— 页面还原过程用。 */
	public List<ToolApprovalRecord> ofRun(String runId) {
		return store.ofRun(runId);
	}

	/** 最近若干条（不限状态）—— 排查用。 */
	public List<ToolApprovalRecord> recent(int limit) {
		return store.recent(limit);
	}

	/**
	 * 人工做出决定。
	 *
	 * <h2>返回的 {@code awakened} 是什么意思</h2>
	 * 它回答"这次决定有没有唤醒一个正在等的任务"：
	 * <ul>
	 *   <li>{@code true} —— 有一个线程停在 {@link #await} 上，它会被唤醒并<b>继续把任务跑完</b>。
	 *       这是正常路径（人审批时，发起请求的那个 HTTP 连接还开着）；</li>
	 *   <li>{@code false} —— 没有线程在等（进程重启过、或发起请求的客户端早已断开）。
	 *       决定<b>照样生效并落库</b>，但任务是停在库里的，要由恢复流程接着跑。</li>
	 * </ul>
	 * 把这个布尔返回给调用方，而不是自己偷偷去触发恢复：
	 * <b>"决定"与"恢复"是两件事，混在一起会让"谁在跑这个任务"变得说不清</b>
	 * （恢复要走 {@code ResumableAgentService}，那里有 node 认领与"接着跑不重跑"的整套规则）。
	 * 界面拿到 {@code awakened=false} 时应当明确提示"这次决定已记录，任务需要恢复后才能继续"。
	 *
	 * @param approvalId 审批 id
	 * @param approve true=批准，false=拒绝
	 * @param decidedBy 谁做的决定（人/系统标识）
	 * @param note 理由（拒绝时强烈建议填 —— 它是模型下一步改方案的唯一依据）
	 */
    public Decision decide(String approvalId, boolean approve, String decidedBy, String note) {
		ToolApprovalRecord record = store.find(approvalId)
				.orElseThrow(() -> new IllegalArgumentException("没有这条人工审批记录：" + approvalId));
		if (!record.pending()) {
			throw new IllegalStateException(
					"审批 #%s 已经是 %s，不能重复决定（重复决定会让审计记录与真实执行对不上）"
							.formatted(approvalId, record.status()));
		}

		String by = decidedBy == null || decidedBy.isBlank() ? properties.defaultDecider() : decidedBy.strip();
		ApprovalStatus target = approve ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
		boolean written = store.decide(approvalId, target, Instant.now(), by, note);
		if (!written) {
			// 库里的那一次写没生效 —— 说明在我们读与写之间，超时（或另一个人的决定）已经抢先写入了。
			// **不当作错误抛**：这是"谁先到谁生效"的正常结局，只是这次决定没被采纳。
			// 但要明确告诉调用方结果是什么，否则界面会显示"已批准"而库里是 TIMED_OUT
			ApprovalStatus actual = statusOf(approvalId);
			log.warn("审批 #{} 的决定未生效（实际状态 {}）—— 已被更早的决定抢先写入", approvalId, actual);
			return new Decision(approvalId, actual, false, "这次决定没有被采纳：记录已经是 " + actual);
		}

		CompletableFuture<ApprovalStatus> future = waiters.get(approvalId);
		boolean awakened = future != null && future.complete(target);
		log.info("人工审批 #{} → {}（by={}）awakened={}", approvalId, target, by, awakened);
		return new Decision(approvalId, target, awakened, null);
	}

	/** 决定的结果。{@code note} 非空表示这次决定<b>没有被采纳</b>，内容说明原因。 */
	public record Decision(String approvalId, ApprovalStatus status, boolean awakened, String note) {

		public boolean accepted() {
			return note == null;
		}

	}

	// ------------------------------------------------------------------
	//  角色三：恢复时重建"已批准"
	// ------------------------------------------------------------------

	/**
	 * 这次调用是不是已经被人批准过（按<b>工具名 + 参数</b>匹配）。
	 *
	 * <h2>为什么按"工具名 + 参数"匹配，而不是按运行 id</h2>
	 * 恢复之后，模型会<b>重新提出</b>那个调用 —— 它会拿到一个新的 {@code tool_call_id}
	 * （那是协议层的东西，不由我们决定）。所以按 id 匹配必然匹配不上。
	 * 而"同一个工具 + 逐字节相同的参数"才是"人批的那一次"的真实身份：
	 * 模型重复时给出的是从自己上文里复制出来的同一份 JSON
	 * （这条观察与本项目 {@code DuplicateActionDetector} 用的是同一个事实）。
	 *
	 * <p><b>参数不同就必须重新审批</b>，这不是苛刻而是审批制度的底线：
	 * 人批准的是"创建一条标题为 X 的工单"，模型回头提出"标题为 Y"，那是另一件事。
	 * 放行它的后果是<b>审批变成一次性的橡皮图章</b> ——
	 * 而它同时还会让"我批的那次到底执行了没有"变得无法回答。
	 *
	 * <p>这个方法给<b>服务层</b>用（它据此构造 {@link ApprovalGrant}），
	 * 闸门自己不调它 —— 闸门看的是传进来的通行证，理由见那一段的注释。
	 */
	public Optional<ToolApprovalRecord> approvedBy(String runId, ToolInvocation invocation, AgentTool<?> tool) {
		if (runId == null || runId.isBlank()) {
			return Optional.empty();
		}
		String wanted = normalizedArguments(invocation);
		return store.ofRun(runId).stream()
				.filter(r -> r.status() == ApprovalStatus.APPROVED)
				.filter(r -> r.toolName().equals(tool.name()))
				.filter(r -> normalizedArguments(r.arguments()).equals(wanted))
				.findFirst();
	}

	/** 某次运行里<b>仍然没人处理</b>的审批（正常情况下最多一条 —— 循环一遇到待批就暂停了）。 */
	public Optional<ToolApprovalRecord> pendingOfRun(String runId) {
		if (runId == null || runId.isBlank()) {
			return Optional.empty();
		}
		return store.ofRun(runId).stream().filter(ToolApprovalRecord::pending).findFirst();
	}

	/** 按 id 读一条审批记录。 */
	public Optional<ToolApprovalRecord> find(String approvalId) {
		return store.find(approvalId);
	}

	/**
	 * 把一条待批<b>作废</b>（不是拒绝、也不是超时）。
	 *
	 * <p>用途见 {@code ResumableAgentService.expireOrphanApprovals}：
	 * 进程重启之后，那些等人的线程都没了，而库里还留着 PENDING ——
	 * 它们指向的执行永远不会发生。留在界面上只会让人困惑"这到底要不要批"。
	 *
	 * @return 这次作废有没有生效（并发下可能已经被别人抢先决定，那时返回 false）
	 */
	public boolean expire(String approvalId, String reason) {
		boolean written = store.decide(approvalId, ApprovalStatus.EXPIRED, Instant.now(), null, reason);
		if (written) {
			// 万一还有线程在等（理论上不该有：调用方只在"运行已不在等待"时才作废），
			// 也把它唤醒 —— 否则那条线程会白等满整个超时
			CompletableFuture<ApprovalStatus> future = waiters.remove(approvalId);
			if (future != null) {
				future.complete(ApprovalStatus.EXPIRED);
			}
		}
		return written;
	}

	/** 参数规范化：去掉空白，这样 {@code {"a": 1}} 与 {@code {"a":1}} 视为同一份参数。 */
	private static String normalizedArguments(ToolInvocation invocation) {
		return normalizedArguments(invocation.arguments() == null ? "{}" : invocation.arguments());
	}

	private static String normalizedArguments(String arguments) {
		return arguments == null ? "{}" : arguments.replaceAll("\\s+", "");
	}

	// ------------------------------------------------------------------
	//  诊断
	// ------------------------------------------------------------------

	@Override
	public String describe() {
		if (!properties.enabled()) {
			return "已装配但被关闭（ai.agent.approval.enabled=false）—— 写操作不会有任何人工确认";
		}
		return "已启用：写操作等人确认，最长 %ds；当前待批 %d 条".formatted(properties.timeout().toSeconds(),
				pending().size());
	}

	public AgentApprovalProperties properties() {
		return properties;
	}

	/**
	 * 存储层（供测试构造"另一个配置下的闸门"用）。
	 *
	 * <p>为什么暴露它而不是让测试自己 new 一个存储：那样测试验证的就是<b>它自己造的那个组合</b>，
	 * 而不是真实装配。同一个存储、换一份配置，测的才是"配置真的在起作用"。
	 */
	public ToolApprovalStore store() {
		return store;
	}

	private ApprovalStatus statusOf(String approvalId) {
		return store.find(approvalId).map(ToolApprovalRecord::status).orElse(ApprovalStatus.EXPIRED);
	}

	/**
	 * 把时长写成给人看的形式。
	 *
	 * <p><b>为什么需要这个而不是直接用 {@code toSeconds()}</b>：那是被测试当场抓出来的一个
	 * 有误导性的输出 —— 覆盖成 300 毫秒时，{@code toSeconds()} 得到 <b>0</b>，
	 * 于是给模型的回填变成"等待人工确认超过 0 秒"，读起来像"压根没等"。
	 * 而回填文本的价值全在"它准不准"上：一句自相矛盾的话会让模型（和人）
	 * 对刚才发生了什么产生错误印象。
	 *
	 * <p>这与台账二·八节那个"正文比总数还大"的备注是同一类问题：
	 * <b>数字算对了、单位选错了，输出就变成假话。</b>
	 */
	static String human(Duration duration) {
		long millis = duration.toMillis();
		if (millis % 1000 == 0) {
			return (millis / 1000) + " 秒";
		}
		return millis + " 毫秒";
	}

}
