package ai.agent.service.tool;

import ai.agent.service.agent.limit.NoopPerToolGate;
import ai.agent.service.agent.limit.PerToolGate;
import ai.agent.service.support.tool.ToolFeedbackFormatter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * <b>本学习点的核心：模型与 Java 之间的那道边界。</b>
 *
 * <h2>它存在的唯一理由</h2>
 * 路线文档的原话是「明确<b>模型只输出调用意图，Java 端负责校验 + 执行 + 回填</b>的边界」。
 * 这句话读起来像常识，但它有一个非常具体的敌人：<b>把模型的输出当成已经合法的调用</b>。
 * 一旦那样做，下面每一件都会发生，而且都不报错：
 * <ul>
 *   <li>模型编一个不存在的工具名 → 反射找不到方法 → 抛 <i>NoSuchMethodException</i>，
 *       而它在日志里看起来像"系统故障"；</li>
 *   <li>模型少给一个必填参数 → 参数是 null → 工具内部 NPE，
 *       报错指向工具的第 40 行，而真正的原因在模型的输出里；</li>
 *   <li>模型给一个参数多塞一个没用的字段 → Jackson 默认静默忽略 →
 *       <b>调用"成功"了，但行为不是模型以为的那个</b>（踩坑库 #20 同族）；</li>
 *   <li>模型把数字写成字符串 {@code "3"} → Jackson 顺手强转 → 看起来没问题，
 *       但如果哪天模型给的是 {@code "三"}，报错位置又会跑到很深的地方。</li>
 * </ul>
 * 所以这个类的每一段代码都在回答同一个问题：<b>"模型可以随便乱来，然后会怎样？"</b>
 *
 * <h2>四道门禁，顺序不能换</h2>
 * <ol>
 *   <li><b>白名单</b>（{@link ToolCallStage#UNKNOWN_TOOL}）—— 必须先做。
 *       放到参数校验之后的话，一个不存在的工具会先报"参数不合法"，
 *       而真正的问题是"压根没这个工具"，回填内容会把模型带向错误的修法；</li>
 *   <li><b>JSON 解析</b>（{@link ToolCallStage#PARSE_ERROR}）—— 参数不是合法 JSON，
 *       或结构对不上（给对象类型传了数组）；</li>
 *   <li><b>JSR-380 校验</b>（{@link ToolCallStage#VALIDATION_ERROR}）—— JSON 合法但内容不合格。
 *       <b>这一步不能省</b>：模型看到 schema ≠ 模型会遵守 schema；</li>
 *   <li><b>执行</b>（{@link ToolCallStage#EXECUTION_ERROR}）—— 参数可信之后才干活，
 *       异常在这里被兜住，转成一条 observation 回到模型（而不是炸掉整个会话）。</li>
 * </ol>
 *
 * <h2>两个刻意的严格选择</h2>
 * <ul>
 *   <li><b>多余的字段一律拒绝</b>（不是忽略）。忽略会让"模型多给了一个字段"
 *       变成一次静默的行为偏差；拒绝则变成一条能被回填、能被模型自己删掉的错误。
 *       代价是多一轮，但这个代价换的是"不会悄悄算错"。</li>
 *   <li><b>数字/布尔写成了字符串会拒绝</b>（关掉 {@code MapperFeature.ALLOW_COERCION_OF_SCALARS}）。
 *       模型的典型形态是把 {@code 3} 写成 {@code "3"}；放行它看起来更"健壮"，
 *       但那是在<b>替模型猜意图</b> —— 同一套宽松规则遇到 {@code "三"} 就只能抛一个
 *       位置很深的解析异常。宁可现在给一条明确的回填。
 *       <p><b>这里有一个实测出来的坑值得记下来</b>：直觉上会去关
 *       {@code DeserializationFeature.ACCEPT_STRING_AS_NUMBER} —— 但那个常量
 *       <b>在 Jackson 2.21（本项目实际解析到的版本）里已经被删掉了</b>，
 *       它变成了一个"编译不过"的选项；而如果写的是某个"看起来对"的常量名，
 *       最坏的情况是它存在但控制的是另一件事。真正有效的开关只有一个：
 *       {@code MapperFeature.ALLOW_COERCION_OF_SCALARS}（做了 javap 核实）。
 *       教训：**"我以为我关掉了强转"与"强转真的被关掉了"是两件事，只能靠测试区分。**</li>
 * </ul>
 * 这两条都有一个共同的代价：白名单里那把工具本来能"侥幸跑通"。换成拒绝之后，
 * 模型必须再花一轮改参数 —— 而这一轮的值就是这个学习点要展示的东西：<b>错误回填能不能让模型自愈</b>。
 */
@Component
public class ToolCallExecutor {

	private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);

	/**
	 * 参数解析用的 mapper。
	 *
	 * <p>刻意<b>不复用</b> Spring 容器里那个（{@code ObjectMapper} bean）：
	 * 那个是为 Web 层配置的（可能开了各种宽松特性、可能带自定义模块），
	 * 而这里是<b>不可信输入的第一道解析</b>，strict 必须由本类自己保证 ——
	 * 依赖容器配置的话，"哪天有人为了兼容某个接口把容器 mapper 配宽了"就会
	 * 悄悄把这道门禁拆掉，而且不会有任何测试失败。
	 */
	private static final ObjectMapper STRICT = new ObjectMapper()
			// 多余的字段：拒绝（见类注释里的取舍）
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			// null 传给基本类型：拒绝。默认会被当成 0 —— 而 0 是一个看起来完全合法的值（踩坑库 #20）
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			// 类型强转：整体关闭（模型把 5 写成 "5"、把 true 写成 "true" 都会被拒）
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

	private final ToolRegistry registry;

	private final Validator validator;

	private final ToolFeedbackFormatter formatter;

	/**
	 * 并发闸门与熔断（阶段三第 3 件）。
	 *
	 * <p>有参构造器注入真的实现；无参构造器给 {@link ai.agent.service.agent.limit.NoopPerToolGate}
	 * —— 于是工具自己的单测不需要起 Spring 上下文，而且**限制生效与不生效走的是同一段代码**
	 * （空实现只是把上限调到极大），不存在"某条分支没被测到"。
	 */
	private final PerToolGate gate;

	/**
	 * <b>人工确认闸门</b>（阶段三第 9 件 HITL）。
	 *
	 * <p>与 {@link PerToolGate} 同一套装配手法：有参构造器注入真实现，
	 * 无参构造器给"全部放行"—— 于是工具自己的单测不需要起审批流程，
	 * 而且<b>审批生效与不生效走的是同一段代码</b>（空实现只是把判据换成永远放行），
	 * 不存在"某条分支从来没被测到"。
	 */
	private final ToolApprovalGate approvalGate;

	public ToolCallExecutor(ToolRegistry registry, Validator validator, ToolFeedbackFormatter formatter) {
		this(registry, validator, formatter, NoopPerToolGate.instance(), ToolApprovalGate.Allow.instance());
	}

	/**
	 * 带闸门、<b>不带人工确认</b>的构造器。
	 *
	 * <p>留给"只想测并发闸门与熔断"的测试（{@code AgentConcurrencyTest} 就是）。
	 * 有它，那些测试就不必为了一个用不到的依赖去装配审批流程 ——
	 * 而它们测的东西（限流、熔断、许可归还）与 HITL 完全正交。
	 * <b>保留这个重载是刻意的：审批默认是"全部放行"</b>，
	 * 所以调用它的人拿到的是"没有 HITL 的执行器"，那是他们明确表达的意思。
	 */
	public ToolCallExecutor(ToolRegistry registry, Validator validator, ToolFeedbackFormatter formatter,
			PerToolGate gate) {
		this(registry, validator, formatter, gate, ToolApprovalGate.Allow.instance());
	}

	@org.springframework.beans.factory.annotation.Autowired
	public ToolCallExecutor(ToolRegistry registry, Validator validator, ToolFeedbackFormatter formatter,
			PerToolGate gate, ToolApprovalGate approvalGate) {
		this.registry = registry;
		this.validator = validator;
		this.formatter = formatter;
		this.gate = gate;
		this.approvalGate = approvalGate;
	}

	/** 不带上下文的执行（单独调用执行器时用；来源按模型链路算）。 */
	public ToolCallOutcome execute(ToolInvocation invocation) {
		return execute(invocation, ToolCallContext.none());
	}

	/**
	 * 执行一次工具调用意图。<b>本方法保证不抛异常</b>（除了 JVM 级错误）。
	 *
	 * <p>"不抛"不是"吞掉"：每一种失败都变成一条带 stage 的回填文本，
	 * 回到模型那里成为它能用来修正自己的 observation。
	 * 这是 {@code AgentLoop} 能持续跑下去的前提 —— 工具失败不该中断整个任务。
	 *
	 * <h2>五道门禁的顺序（第 ⑤ 道是本轮新增的 HITL）</h2>
	 * <ol>
	 *   <li><b>白名单</b>（{@link ToolCallStage#UNKNOWN_TOOL}）—— 必须先做。
	 *       放到参数校验之后的话，一个不存在的工具会先报"参数不合法"，
	 *       而真正的问题是"压根没这个工具"，回填内容会把模型带向错误的修法；</li>
	 *   <li><b>并发闸门 + 熔断</b>（{@code REQUEST_REJECTED} / {@code CIRCUIT_OPEN}）
	 *       —— 位置理由见下面的长注释；</li>
	 *   <li><b>JSON 解析</b>（{@link ToolCallStage#PARSE_ERROR}）；</li>
	 *   <li><b>JSR-380 校验</b>（{@link ToolCallStage#VALIDATION_ERROR}）；</li>
	 *   <li><b>人工确认</b>（{@link ToolCallStage#AWAITING_APPROVAL} /
	 *       {@code APPROVAL_DENIED} / {@code APPROVAL_TIMEOUT}）——
	 *       <b>必须在校验之后、执行之前</b>，两条理由见 {@link ToolApprovalGate} 的类注释：
	 *       人要批准的是"真正会被执行的那份参数"，而闸门不能交给被监管方自己把守。</li>
	 * </ol>
	 *
	 * @param invocation 工具调用意图（不可信）
	 * @param context 执行上下文（哪次运行、第几步、哪条链路）—— 审计与 HITL 记账用
	 * @return 走到哪一步、结果、以及要回填给模型的文本
	 */
	public ToolCallOutcome execute(ToolInvocation invocation, ToolCallContext context) {
		long startedAt = System.nanoTime();

		// ---- 门禁 1：白名单 ----
		// 回填里必须带**可用工具列表**：只说"工具不存在"，模型的下一个反应往往是再编一个。
		// 这是"错误信息里带可用值"这条要求最直接的落点
		var found = registry.find(invocation.name());
		if (found.isEmpty()) {
			String feedback = formatter.unknownTool(invocation, registry.names());
			log.warn("模型提出了不存在的工具 name={}（可用：{}）", invocation.name(), registry.names());
			return new ToolCallOutcome(invocation, ToolCallStage.UNKNOWN_TOOL, null, feedback,
					elapsedMs(startedAt), true, ToolApprovalRef.none(), context.origin());
		}
		AgentTool<?> tool = found.get();

		// ---- 门禁 2：并发闸门 + 熔断（阶段三第 3 件） ----
		//
		// 【位置：白名单之后、解析之前 —— 但这个位置我来回改过一次，值得记下来】
		//
		// 第一版放在"解析之后"，理由是："参数错是毫秒级的、不消耗下游资源，
		// 为它排队没意义，而且会污染闸门的排队统计。"
		//
		// 那个理由**听起来对，但换来的后果更糟**：闸门在解析之后，
		// 意味着"参数解析失败"的调用没有拿到许可 —— 于是它进入不了熔断记账的配对关系里。
		// 而把闸门提前之后（现在这样），解析失败的调用会走到 release，
		// 就<b>必须以某种身份</b>进熔断窗口 —— 除非显式排除。第一版没排除，
		// 结果是它们以"成功"的身份进窗口，把失败率稀释掉（测试报
		// `windowSize expected: 0 but was: 10` 才暴露）。
		//
		// 现在的做法是两头都对：
		//   - 闸门保护的是**整个执行尝试**（而不是"执行那几毫秒"）——
		//     这与它的初衷一致：限制的是"同时在处理多少个工具请求"，
		//     而解析、校验同样占着一个处理中的请求；
		//   - 由 ToolCallStage.countsAsCircuitSample() 决定**哪些 stage 不参与熔断记账**
		//     （解析/校验失败、以及正等人批或人没批，都不是工具的成败）。
		//
		// 为什么白名单仍然在闸门之前：白名单失败（模型编了个名字）连"工具"都不存在，
		// 替它建一份闸门/熔断器是给不该有状态的输入建状态，而且 map 会被随机名字撑大。
		var rejection = gate.tryAcquire(tool.name());
		if (rejection != null) {
			// 被拒时**不调用 gate.release** —— 因为我们压根没拿到许可。
			// 这个"拿到才还"的配对关系是本类最容易写错的地方，
			// 写反的后果是闸门永久少一个名额（越跑越慢、重启就好），见 ConcurrencyGate.release 的注释
			log.warn("工具「{}」的调用被拒绝：{} {}", tool.name(), rejection.stage(), rejection.reason());
			return new ToolCallOutcome(invocation, rejection.stage(), null,
					formatter.rejected(invocation, tool, rejection.stage(), rejection.reason()), elapsedMs(startedAt),
					true, ToolApprovalRef.none(), context.origin());
		}

		// 本次调用的审批记账。它是"走到哪个出口"才知道的，所以先声明在这里，
		// 由各出口在调 record(...) 之前填 —— 而不是让它变成一个到处传的参数
		ToolApprovalRef approval = ToolApprovalRef.none();
		try {
			// ---- 门禁 3：解析 ----
			Object parameters;
			try {
				parameters = parse(tool, invocation);
			}
			catch (ToolArgumentsException e) {
				log.warn("工具「{}」的参数解析失败：{}", tool.name(), e.getMessage());
				return record(tool, invocation, ToolCallStage.PARSE_ERROR, null,
						formatter.parseError(invocation, tool, e.getMessage()), startedAt, approval, context);
			}

			// ---- 门禁 4：校验 ----
			// 这一步是"模型看到 schema ≠ 模型遵守 schema"的直接体现。
			// 回填里带字段名 + 具体原因 + 可用值，模型才有可能一次改对
			List<String> issues = validate(parameters);
			if (!issues.isEmpty()) {
				log.warn("工具「{}」的参数未通过校验：{}", tool.name(), issues);
				return record(tool, invocation, ToolCallStage.VALIDATION_ERROR, null,
						formatter.validationError(invocation, tool, issues), startedAt, approval, context);
			}

			// ---- 门禁 5：人工确认（HITL）----
			// 参数到这里已经"可信"了，于是人看到的就是真正会被执行的那份
			ToolApprovalGate.Authorization authorization = approvalGate.authorize(invocation, tool, context);
			switch (authorization) {
				case ToolApprovalGate.Authorization.Pending pending -> {
					// 【这里千万不能调 record(...)】它是"归还许可 + 记账"的统一出口，
					// 而此刻这次调用**既没成功也没失败**，只是停住了。
					// 走 record 会把它按 OK 记进熔断窗口（工具没执行却记成功），
					// 同时归还闸门许可 —— 而"等人"期间本来就不该占着下游的名额。
					// 所以这里直接返回，并把许可原样还掉、不记任何账
					gate.releaseWithoutRecording(tool.name());
					log.info("工具「{}」停住等人确认 #{}（run={} step={}）", tool.name(), pending.approvalId(),
							context.runId(), context.stepIndex());
					return new ToolCallOutcome(invocation, ToolCallStage.AWAITING_APPROVAL, null,
							formatter.awaitingApproval(invocation, tool, pending.request()), elapsedMs(startedAt),
							false, ToolApprovalRef.of(pending.approvalId()), context.origin());
				}
				case ToolApprovalGate.Authorization.Denied denied -> {
					approval = denied.approvalId() == null ? ToolApprovalRef.none()
							: ToolApprovalRef.of(denied.approvalId());
					log.warn("工具「{}」的人工确认未通过：{} {}", tool.name(), denied.stage(), denied.reason());
					return record(tool, invocation, denied.stage(), null,
							formatter.approvalRejected(invocation, tool, denied.stage(), denied.reason(), denied.approvalId()),
							startedAt, approval, context);
				}
				case ToolApprovalGate.Authorization.Allowed allowed -> approval = allowed.approved()
						? ToolApprovalRef.of(allowed.approvalId())
						: ToolApprovalRef.none();
				// 【这个 default 不是"兜底"而是"防漏"】
				// Authorization 是 sealed 的，三个分支已经穷尽 —— 但 Java 的 switch **表达式**
				// 要求赋值完备性，而这里的箭头分支没有 yield/赋值，所以编译器会要求 default。
				// 关键是不能让它"顺其自然地什么都不做"：那样新加一个 Authorization 取值时，
				// 这次调用会**带着上一个分支的状态继续执行** —— 一个静默的越权路径。
				// 抛出来，让它在第一次被走到时就暴露
				default -> throw new IllegalStateException(
						"未处理的工具授权结论：" + authorization.getClass().getSimpleName()
								+ "（请在 ToolCallExecutor 里显式处理它 —— 默认放行会是一条越权路径）");
			}

			// ---- 门禁 6：执行 ----
			return invoke(tool, parameters, invocation, startedAt, approval, context);
		}		finally {
			// 【这里刻意什么都不做，只留一句说明】
			//
			// 归还许可**不在**这个 finally 里，而是在下面 record(...) 里 —— 这不是风格问题：
			// 归还时必须同时把"这次的结果"记进熔断窗口，而 stage 只有走到具体出口才知道。
			// 如果在 finally 里归还、在 record 里记账，两者之间会有一条缝：
			// 许可一还，另一个线程可能立刻拿到并开始执行，而这次的结果还没进窗口 ——
			// 在"失败率刚好在阈值附近"时，熔断会因此晚一拍才打开。
			//
			// 代价是"每个出口都必须经过 record(...)"，而这是一条**可断言的不变式**：
			// 见 ToolCallBoundaryTest 里"每次执行后闸门的在途数必须回到 0"那条测试 ——
			// 哪天有人加了新出口却没走 record，它会立刻变红（而不是变成"越跑越慢最后卡住"）。
			//
			// 【HITL 那一个出口是唯一的例外，而且是刻意例外】
			// AWAITING_APPROVAL 走的是 releaseWithoutRecording（只还许可、不记账），
			// 因为它既不是成功也不是失败 —— 这一点在那段代码里有单独说明。
		}
	}

	/**
	 * 统一的"记录结论 + 归还许可"出口。
	 *
	 * <p>除 HITL 的"待批"之外，所有阶段（解析失败 / 校验失败 / 人工拒绝 / 执行成功 /
	 * 执行异常）都从这里出去，保证"stage 判定"与"释放 + 熔断记账"永远成对发生。
	 * {@code feedback} 由各阶段自己构造（措辞归 formatter 管），本方法只负责记账与归还。
	 */
	private ToolCallOutcome record(AgentTool<?> tool, ToolInvocation invocation, ToolCallStage stage,
			AgentToolResult result, String feedback, long startedAt, ToolApprovalRef approval,
			ToolCallContext context) {
		long elapsed = elapsedMs(startedAt);
		// 先记账再归还 —— 顺序理由见上面 finally 里的注释。
		// PerToolGate.release 内部就是"归还许可 + 按 stage 判断要不要算失败"
		gate.release(tool.name(), stage);
		return new ToolCallOutcome(invocation, stage, result, feedback, elapsed, fixableByModel(stage), approval,
				context.origin());
	}

	/**
	 * 该 stage 的失败是否值得<b>再给模型一轮</b>。
	 *
	 * <p>注意它和 {@link ToolCallStage#countsAsToolFailure()} 是<b>两个不同的问题</b>，
	 * 而这两个问题极容易混成一个：
	 * <ul>
	 *   <li>{@code countsAsToolFailure} 问的是"这次失败该不该算在<b>工具</b>头上"（熔断用）——
	 *       答案是"只有执行异常算"；</li>
	 *   <li>本方法问的是"这次失败该不该<b>让模型再试</b>"（循环用）——
	 *       答案是"除了执行异常与人工拒绝之外都值得，因为参数错、名字错、太挤了都能靠改一下解决"。</li>
	 * </ul>
	 * 两者对 {@code EXECUTION_ERROR} 的答案恰好都是"否"，但<b>理由完全不同</b>
	 * （一个是"不是工具的错"，一个是"再来一次也一样"）。
	 * 把两者合成一个布尔，就会在将来某次改判据时把另一边一起改错。
	 *
	 * <h2>人工拒绝为什么是"不值得再给一轮"</h2>
	 * 它不是"再试一次就能过"的问题：模型原样重提会被<b>再次拦住</b>（人要再点一次），
	 * 而"再点一次"不该由模型的重试来驱动。所以回填里要明确说
	 * "不要再提同样的调用，请换方案或直接回答用户"（见
	 * {@code ToolFeedbackFormatter.approvalRejected}）。
	 *
	 * <p>超时与拒绝在这里答案相同（都是 false），但理由不同：
	 * 超时是"没有人来处理"，重试只是再等一次同样的超时。
	 */
	private static boolean fixableByModel(ToolCallStage stage) {
		return switch (stage) {
			// 名字错、参数错、被限流、熔断打开 —— 都值得再给一轮：
			// 前两个改参数/改名字，后两个等一下或换工具
			case UNKNOWN_TOOL, PARSE_ERROR, VALIDATION_ERROR, REQUEST_REJECTED, CIRCUIT_OPEN, OK -> true;
			// 工具自己炸了：原样重试不会有不同结果（回填里会明确说这一点）
			case EXECUTION_ERROR -> false;
			// 【HITL】人工拒绝 / 超时：原样重提**会被再拦一次**，所以不值得再给一轮。
			// 注意"待批"给的是 false —— 但它连"失败"都不是，这个布尔对它没有意义
			// （调用方应当先看 awaitingApproval()，见那个方法的注释）
			case APPROVAL_DENIED, APPROVAL_TIMEOUT, AWAITING_APPROVAL -> false;
		};
	}

	/** 供测试断言"许可有没有被正确归还"（闸门泄漏是最难查的一类问题）。 */
	public PerToolGate gate() {
		return gate;
	}

	/**
	 * 解析成强类型参数。
	 *
	 * <p>用"先 {@code readTree} 再 {@code treeToValue}"而不是直接 {@code readValue(String, Class)}：
	 * 前者的失败信息能区分"整串不是 JSON"与"是 JSON 但形状不对"，
	 * 而这两种给模型的建议不同（一个说语法，一个说结构）。
	 */
	@SuppressWarnings("unchecked")
	private <P> P parse(AgentTool<?> tool, ToolInvocation invocation) {
		String raw = invocation.arguments();
		if (raw == null || raw.isBlank()) {
			raw = "{}";
		}

		JsonNode tree;
		try {
			tree = STRICT.readTree(raw);
		}
		catch (Exception e) {
			throw new ToolArgumentsException("参数不是合法 JSON：" + rootMessage(e));
		}
		if (tree == null || tree.isNull()) {
			// 无参工具允许模型传 "null" 或 ""；其它工具视为缺参数
			tree = STRICT.createObjectNode();
		}
		if (!tree.isObject()) {
			throw new ToolArgumentsException(
					"参数必须是一个 JSON 对象，实际收到的是 " + tree.getNodeType().name().toLowerCase());
		}

		Class<?> parameterType = tool.parameterType();
		try {
			return (P) STRICT.treeToValue(tree, parameterType);
		}
		catch (UnrecognizedPropertyException e) {
			// 单独接住：这是最常见的"模型多给字段"，而默认异常信息（带类名与包路径）
			// 对模型毫无帮助。这里把 可用字段 与 它多给的那个 都拿出来
			SortedSet<String> known = fieldNamesOf(parameterType);
			throw new ToolArgumentsException(
					"参数里出现了工具不认识的字段「%s」。这个工具只接受：%s".formatted(e.getPropertyName(), known));
		}
		catch (Exception e) {
			throw new ToolArgumentsException("参数与工具的声明对不上：" + rootMessage(e));
		}
	}

	/** 跑 JSR-380 校验，返回可读的问题清单。字段名必须出现在每条里（否则模型无从下手）。 */
	private List<String> validate(Object parameters) {
		Set<ConstraintViolation<Object>> violations = validator.validate(parameters);
		if (violations.isEmpty()) {
			return List.of();
		}
		List<String> issues = new ArrayList<>();
		for (ConstraintViolation<Object> v : violations) {
			// 与 StructuredChatServiceImpl 同一条规则：只说"校验失败"模型改不动，
			// 必须带 propertyPath（哪个字段）+ message（错在哪）
			issues.add("字段 %s：%s".formatted(v.getPropertyPath(), v.getMessage()));
		}
		return issues;
	}

	/** 真正调用工具。异常在这里被兜住 —— 目标是"工具炸了不等于会话炸了"。 */
	private ToolCallOutcome invoke(AgentTool<?> tool, Object parameters, ToolInvocation invocation, long startedAt,
			ToolApprovalRef approval, ToolCallContext context) {
		try {
			// 在调用之前的最后一刻把上下文交给工具（可选钩子，默认实现什么都不做）。
			// 位置：放在 try 里面 —— 钩子是工具自己的代码，它抛异常同样该被兜住并归类，
			// 而不是变成一个"没被任何分类接住"的异常
			tool.onContext(approval.present() ? context.withApproval(approval.approvalId()) : context);

			AgentToolResult result = invokeUnchecked(tool, parameters);

			// 注意：工具自己返回 Failure 时 stage 仍是 OK ——
			// 工具**正常完成了它的工作**，只是工作上给出了一个否定结论。
			// 把它记成 EXECUTION_ERROR 会让"没查到"看起来像"服务故障"，那是错误分类（踩坑库 #26/#37 同族）
			log.info("工具「{}」执行完成 elapsed={}ms result={} approval={}", tool.name(), elapsedMs(startedAt),
					result instanceof AgentToolResult.Success ? "success" : "failure",
					approval.present() ? approval.approvalId() : "-");
			return record(tool, invocation, ToolCallStage.OK, result,
					formatter.resultFeedback(invocation, tool, result), startedAt, approval, context);
		}
		catch (RuntimeException e) {
			// 打成 ERROR：走到这里说明工具实现或它的依赖真出问题了，这和"没查到"不是一回事
			log.error("工具「{}」执行时抛出异常 elapsed={}ms", tool.name(), elapsedMs(startedAt), e);
			// 走同一个 record 出口 —— 于是"执行异常"也会被算进熔断失败率
			// （它是唯一算失败的一类，见 ToolCallStage.countsAsToolFailure）
			return record(tool, invocation, ToolCallStage.EXECUTION_ERROR, null,
					formatter.executionError(invocation, tool, rootMessage(e)), startedAt, approval, context);
		}
	}

	@SuppressWarnings("unchecked")
	private <P> AgentToolResult invokeUnchecked(AgentTool<?> tool, Object parameters) {
		return ((AgentTool<P>) tool).invoke((P) parameters);
	}

	/** 参数类型里声明的字段名（排序后，便于可复现地回填给模型）。 */
	private static SortedSet<String> fieldNamesOf(Class<?> parameterType) {
		SortedSet<String> names = new TreeSet<>();
		if (parameterType.isRecord()) {
			for (var component : parameterType.getRecordComponents()) {
				names.add(component.getName());
			}
		}
		return names;
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	/** 剥到最内层消息，避免把整条包装链写进给模型看的回填里。 */
	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		if (message == null || message.isBlank()) {
			return cause.getClass().getSimpleName();
		}
		return message.length() > 300 ? message.substring(0, 300) + "…" : message;
	}

	/** 参数解析阶段的失败。私有类型：它只在 {@link #parse} 与它的调用者之间传递。 */
	private static class ToolArgumentsException extends RuntimeException {

		ToolArgumentsException(String message) {
			super(message);
		}
	}

	/** 供测试读取：执行器实际使用的严格解析配置（避免测试自己 new 一个 mapper 而与实现漂移）。 */
	public static ObjectMapper strictMapper() {
		return STRICT;
	}

	/** 已注册工具名（报告与自检用）。 */
	public List<String> knownTools() {
		return registry.names();
	}

	/** 遍历参数 JSON 的顶层字段名（调试用；空对象返回空列表）。 */
	static List<String> topLevelFields(JsonNode node) {
		if (node == null || !node.isObject()) {
			return List.of();
		}
		List<String> fields = new ArrayList<>();
		for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
			fields.add(it.next());
		}
		return fields;
	}

}
