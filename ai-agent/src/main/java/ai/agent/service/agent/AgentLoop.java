package ai.agent.service.agent;

import ai.agent.entity.agent.AgentRun;
import ai.agent.entity.canary.CanaryDecision;
import ai.agent.service.canary.CanaryService;
import ai.agent.config.AgentLoopProperties;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.entity.tool.ApprovalGrant;
import ai.agent.entity.tool.RunPermission;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.service.tool.ToolCallExecutor;
import ai.agent.entity.tool.ToolCallOutcome;
import ai.agent.entity.tool.ToolInvocation;
import ai.agent.service.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>手写 ReAct 循环</b>（阶段三学习点 1）：think → act → observe，直到给出答案或撞上上限。
 *
 * <h2>为什么"不用框架先写一遍"</h2>
 * Spring AI 自带工具执行：给 {@code ChatClient} 传 {@code toolCallbacks}，
 * 它就会<b>自动</b>解析工具调用、执行、把结果塞回上下文、再问一次模型，
 * 直到模型不再要工具 —— 整个过程对你的代码是透明的。
 *
 * <p>那样能跑，但它把下面这些<b>全部藏起来了</b>，而它们恰恰是要在面试里讲清楚的东西：
 * <ul>
 *   <li><b>循环了几轮、每轮花了多少 token</b> —— 不算出来就不知道成本花在哪；</li>
 *   <li><b>工具失败了会怎样</b> —— 谁来兜异常、失败结果怎么措辞、模型有没有机会改；</li>
 *   <li><b>什么时候该强行停下</b> —— 没有上限的话，一个打转的模型会一直烧钱；</li>
 *   <li><b>重复动作怎么发现</b> —— 框架不管这个，它会老老实实把同一个调用执行 6 遍。</li>
 * </ul>
 * 所以本类的立场是：<b>把 think/act/observe 这三步以及停止条件全部显式写出来</b>，
 * 每一步都可观测、每个上限都可配置、每条失败路径都有测试。
 * 框架的能力（工具定义、schema、协议序列化）照样用 —— 用不着自己重写 HTTP 协议；
 * <b>但"循环"这件事自己做</b>。
 *
 * <h2>它<b>不</b>自己执行工具，这是关键</h2>
 * 工具执行一律走 {@link ToolCallExecutor}（白名单 → 解析 → 校验 → 执行 → 分类 → 回填）。
 * 本类只负责"把意图交给它、把回填放回上下文"。
 * 这样做的直接好处是：{@code AgentTool.call(String)} 被实现成<b>故意抛异常</b>，
 * 于是"绕过边界"这条路在代码层面是走不通的 —— 它不会在某天被悄悄打开。
 *
 * <h2>停止条件：三个上限 + 一个检测，各拦一种死法</h2>
 * <table border="1">
 *   <caption>循环的出口</caption>
 *   <tr><th>出口</th><th>拦的是什么</th></tr>
 *   <tr><td>{@link AgentStopReason#ANSWERED}</td><td>模型不再要工具，给了文字（正常结束）</td></tr>
 *   <tr><td>{@link AgentStopReason#MAX_STEPS}</td><td>往返轮数用完</td></tr>
 *   <tr><td>{@link AgentStopReason#MAX_TOKENS}</td><td>累计 token 用完</td></tr>
 *   <tr><td>{@link AgentStopReason#MAX_DURATION}</td><td>墙上时间用完</td></tr>
 *   <tr><td>{@link AgentStopReason#DUPLICATE_ACTION}</td><td>同一个工具 + 同一份参数重复太多次（打转）</td></tr>
 * </table>
 *
 * <h2>为什么"检查上限"必须在调用模型<b>之前</b></h2>
 * 放在之后的话，第 N 轮已经花掉了钱才判定"超预算" —— 而那一轮的结果根本不会被用。
 * 同理，时间上限在调用前检查，可以避免"发起一次注定要超时的请求、白等 30 秒"。
 * 这是一个很便宜的规则，但它决定了超支是"最多超一轮"还是"每轮都超"。
 */
@Component
public class AgentLoop {

	private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

	private final ToolRegistry registry;

	private final ToolCallExecutor executor;

	private final ModelTurn modelTurn;

	private final AgentLoopProperties properties;

	/**
	 * 系统提示词（阶段四第 3 件之后：<b>来自版本库当前生效的那一版</b>）。
	 *
	 * <p>与阶段一的做法一致的部分：提示词不进 Java 字符串。
	 * 改掉的部分：它不再是"启动时解析一次的 {@code Resource}"，而是每次运行开始时
	 * 向 {@link PromptService} 问一次 —— 这正是"配置热更新"在本条链路上的落点。
	 *
	 * <p>热更新的粒度是<b>下一次运行</b>，不是"下一步"：一次运行中途换提示词，
	 * 会让同一份对话里前后两轮遵循不同的指令，于是报告里"它为什么这么选"变得无法解释。
	 */
	private final PromptService prompts;

	/**
	 * 灰度发布（阶段四第 5 件）：决定<b>这一次运行</b>用哪一版提示词。
	 *
	 * <h2>为什么它出现在循环里，而不是包在 {@link PromptService} 外面（装饰器）</h2>
	 * 因为分流的输入是<b>运行 id</b> —— 而 {@code PromptService} 的接口上没有任何
	 * "这一次运行是谁"的信息（它只有 {@code kind}）。做成装饰器就必须把运行 id
	 * 塞进一个 ThreadLocal 或参数里，而 ThreadLocal 在虚拟线程 + 异步链路上
	 * 是出了名的容易丢（本项目阶段一的载体线程实验已经量过它的代价）。
	 *
	 * <p>更根本的理由：<b>判定要发生在"读提示词"那一刻，而且只发生一次</b>。
	 * 循环里读提示词的地方就只有一处（{@code messages.isEmpty()} 那一个分支），
	 * 所以把判定放在那里，天然满足"一次运行只判定一次"这条与热更新粒度对齐的纪律。
	 */
	private final CanaryService canary;

	/**
	 * <b>这一次运行的权限</b>（工作区目录 + 三档之一）。
	 *
	 * <h2>为什么它是循环的字段，而不是 run 的参数</h2>
	 * 与 {@code modelTurn} 完全同一个理由：它是"这一次运行怎么跑"的一个前提，
	 * 由服务层在开跑之前定好，而循环只负责把它转成 {@link ToolCallContext} 交给执行器。
	 * 循环自始至终不需要知道"完全权限是什么意思" —— 那个判据只在
	 * {@code AgentPermissionPolicy} 一处。
	 *
	 * <p>{@code null} 表示没有权限上下文（老入口、测试直接 new 循环）：
	 * 那时闸门按最严的一档算，也就是"和加这个字段之前完全一样"的行为。
	 */
	private final RunPermission permission;

	/**
	 * 容器装配用的那一个（<b>标了 `@Autowired` 是必需的，不是风格</b>）。
	 *
	 * <p>类里有两个构造器，而 Spring 只在"只有一个候选"时才自动选它。有了第二个之后
	 * 两个都不标就会去找无参构造器（本类全是 final 字段，没有），启动直接报
	 * {@code No default constructor found}；而如果把 `@Autowired` 标在**那个带权限的**上，
	 * 失败信息会变成 {@code required a bean of type 'RunPermission' that could not be found} ——
	 * 它指向"少一个 bean"，真正的原因却是"注解标错了构造器"。
	 * 所以：标在这个六参的上面，权限那两个构造器保持 {@code private}。
	 */
	@org.springframework.beans.factory.annotation.Autowired
	public AgentLoop(ToolRegistry registry, ToolCallExecutor executor, ModelTurn modelTurn,
			AgentLoopProperties properties, PromptService prompts, CanaryService canary) {
		this(registry, executor, modelTurn, properties, prompts, canary, null);
	}

	/**
	 * 完整形态（多一个权限字段）。
	 *
	 * <p>⚠️ <b>它必须是 `private`，而且不能标 `@Autowired`</b> —— 两者都是为了同一件事：
	 * 让容器只看见上面那一个公开构造器。Spring 的规则是"类里只有一个构造器时才自动选它"，
	 * 所以多出一个公开构造器会让它去找无参构造器（本类全是 final 字段，没有），
	 * 启动直接报 `No default constructor found`；而把 `@Autowired` 标在**这一个**上更糟 ——
	 * 它会去容器里找一个 `RunPermission` bean，于是失败信息变成
	 * `required a bean of type 'RunPermission' that could not be found`，
	 * 指向的是"少一个 bean"，而真正的原因是"注解标错了构造器"。
	 *
	 * <p>权限由 {@code ResumableAgentService} 每次运行用 {@link #withPermission} 派生实例带进来
	 * （它本来就为换模型这么做过），所以容器里那个循环的权限字段永远是 {@code null} ——
	 * 语义是"没有权限上下文"，判定侧按最严的一档处理。
	 */
	private AgentLoop(ToolRegistry registry, ToolCallExecutor executor, ModelTurn modelTurn,
			AgentLoopProperties properties, PromptService prompts, CanaryService canary, RunPermission permission) {
		this.registry = registry;
		this.executor = executor;
		this.modelTurn = modelTurn;
		this.properties = properties;
		this.prompts = prompts;
		this.canary = canary;
		this.permission = permission;
	}

	/**
	 * 跑一次任务（不持久化）。
	 *
	 * <p>本方法<b>不抛异常</b>（模型调用失败也会变成 {@link AgentStopReason#MODEL_ERROR} 的
	 * 一份报告）：一次 Agent 运行是有成本的，把已经花掉的那几轮结果连同失败原因一起返回，
	 * 比抛出去丢掉更有用 —— 用户至少能看到"它查了两次文档然后模型挂了"。
	 */
	public AgentRun run(String question) {
		return run(UUID.randomUUID().toString(), question, List.of(), 0, AgentProgressListener.NOOP, null);
	}

	/**
	 * 跑一次任务，并<b>带上进度回调</b>（断点恢复的挂载点）。
	 *
	 * @param listener 每一步结束后的回调；不需要持久化时传 {@link AgentProgressListener#NOOP}
	 */
	public AgentRun run(String runId, String question, AgentProgressListener listener) {
		return run(runId, question, List.of(), 0, listener, null);
	}

	/**
	 * 从检查点<b>接着跑</b>（断点恢复的入口）。
	 *
	 * <h2>三个必须由调用方给出的东西，以及为什么</h2>
	 * <ol>
	 *   <li>{@code resumeMessages}：上一次跑到最后一步时的完整对话。
	 *       <b>不能靠"重放前面几步"来重建</b> —— 重放会重新执行工具，
	 *       而对写操作那是重复副作用，对读操作也会拿到与当时不同的数据；</li>
	 *   <li>{@code resumeStepIndex}：已经跑完几步。它决定步数上限从哪算起 ——
	 *       不传的话，一个被杀过 3 次的任务每次都能再跑 6 步，上限形同虚设；</li>
	 *   <li>{@code pendingInvocation}：被杀时**意图已提出但结果未知**的那次调用。
	 *       它必须显式传进来，因为这是整条链路里唯一一个"状态不明"的东西：</li>
	 * </ol>
	 *
	 * <h2>为什么 resumedFromStep &gt; 0 时要往上下文里补一条说明</h2>
	 * 模型不知道"上一次进程死了"。如果不告诉它，它看到的历史会停在
	 * "我要求调用某个工具"那一句，而**永远等不到那个工具的结果** ——
	 * 于是它下一轮很可能原样再要一次（表现就是"卡住"）。
	 * 补一条说明是<b>把中断这件事变成模型能看见的事实</b>，而不是让它去猜。
	 */
	public AgentRun run(String runId, String question, List<Message> resumeMessages, int resumeStepIndex,
			AgentProgressListener listener, ToolInvocation pendingInvocation) {
		return run(runId, question, resumeMessages, resumeStepIndex, listener, pendingInvocation, null);
	}

	/**
	 * 完整形态：多一个<b>人工批准的通行证</b>（HITL 恢复路径用）。
	 *
	 * <h2>为什么通行证是"进循环的输入"，而不是循环自己去查</h2>
	 * 循环的职责是"跑任务"，它不该知道审批记录存在哪张表、怎么查。
	 * 服务层在恢复时查好（见 {@code ResumableAgentService}），
	 * 把"这一次调用已经获批"这件事作为<b>一个事实</b>交给循环 ——
	 * 循环只负责在合适的时机把它转成 {@code ToolCallContext}，
	 * 让执行器看见、让闸门放行。
	 *
	 * @param grant 上一次被人工批准的调用；没有时为 {@code null}
	 */
	public AgentRun run(String runId, String question, List<Message> resumeMessages, int resumeStepIndex,
			AgentProgressListener listener, ToolInvocation pendingInvocation, ApprovalGrant grant) {
		return run(runId, question, resumeMessages, resumeStepIndex, listener, pendingInvocation, grant, null);
	}

	/**
	 * 完整形态，多一个<b>续跑时的提示词指纹</b>（{@code fallbackPromptFingerprint}）。
	 *
	 * <h2>为什么恢复路径必须显式把这个值传进来，而不是让它留空</h2>
	 * 恢复走的是"检查点里已经有系统消息"那条分支，所以本次运行<b>不会去读提示词</b>
	 * （{@link #loadSystemPrompt()} 只在 {@code messages.isEmpty()} 时调用）。
	 * 如果那时记空串，就会出现一个很坏的后果：<b>恢复会把原来那几步的指纹覆盖掉</b> ——
	 * 因为检查点按 {@code (run_id, step_index)} 覆盖写，而恢复后的步号是接着数的，
	 * 但"最新那一步"在恢复开始时就已经存在了。
	 *
	 * <p>更根本的是语义：那一步用的确实是<b>原来那一版</b>的提示词
	 * （它就写在检查点的系统消息里）。记成"本次读到的版本"是假话，
	 * 记成空串则是"不知道"—— 而这两者都不是事实。
	 * 所以服务层从检查点里把原指纹取出来传真，让恢复后的步骤
	 * 与它前面那几步<b>记的是同一版</b>，而那也是"当时到底用的哪一版"的正确答案。
	 *
	 * @param fallbackPromptFingerprint 本次没有读提示词时使用的指纹（恢复路径传原值）；
	 *            为 {@code null} 表示"确实没有"（新起的运行会自己去读）
	 */
	public AgentRun run(String runId, String question, List<Message> resumeMessages, int resumeStepIndex,
			AgentProgressListener listener, ToolInvocation pendingInvocation, ApprovalGrant grant,
			String fallbackPromptFingerprint) {
		long startedAt = System.nanoTime();

		List<Message> messages = new ArrayList<>(resumeMessages);
		// 【阶段四 trace：这一步用的是哪一版提示词】
		//
		// 在这里读**一次**，然后每一步都传同一个值。三个理由，按重要性排：
		//  ① 与热更新的粒度对齐：提示词只在"下一次运行"生效。如果每一步各读一次，
		//     那么运行中途换版本时，同一份对话的前后两轮会遵循不同的指令 ——
		//     报告里"它为什么这么选"就变得无法解释（这正是 PromptService 注释里
		//     写死的那个取舍）；
		//  ② 本方法下面有三个 afterStep 调用点（作答 / 暂停等人 / 正常一步）。
		//     各自读一次就要三处都记得读 —— 漏一处的表现是"某些运行没有指纹"，
		//     而那看起来像存储坏了；
		//  ③ 它是**事实**，不是配置：一次运行用的就是这一版，读到一次就定了。
		//
		// 新起的运行（messages 为空）：自己去读一次。
		// 续跑：不读（系统消息已经在检查点里），改用服务层从检查点取来的原指纹。
		String promptFingerprint;
		String canaryVariant = "";
		if (messages.isEmpty()) {
			// 【阶段四第 5 件：先判定这一次运行属于哪一组，再按那一组读提示词】
			//
			// 顺序不能反：如果先读 stable 那一版的正文、再决定"其实该走候选版"，
			// 那么正文与指纹就会来自两个不同的版本 —— 而指纹是要落库的，
			// 于是库里会出现一个"对不上任何版本"的指纹，且**只在读 trace 时才发现**。
			CanaryDecision decision = canary.decide(PromptKind.AGENT_LOOP, runId);
			canaryVariant = decision.variant();
			promptFingerprint = decision.fingerprint();
			messages.add(new SystemMessage(loadSystemPrompt(decision)));
			messages.add(new UserMessage(question));
			if (!canaryVariant.isEmpty()) {
				log.info("灰度分流 runId={} → {}（kind={}）", runId, decision.describe(),
						PromptKind.AGENT_LOOP.defaultValue());
			}
		}
		else {
			promptFingerprint = fallbackPromptFingerprint == null ? "" : fallbackPromptFingerprint;

			// 【下面的顺序很关键：先判 grant、再判 pendingInvocation】
			//
			// 两者在恢复时**会同时存在**（那个被批准的调用，在"结果未知"这个意义上
			// 也是一个"没有返回的调用"）。而它们的说明文本是完全相反的：
			//   - 中断说明说"它可能没执行，别假定成功"；
			//   - 批准说明说"人已经批准了，请**原样重新提出**这次调用"。
			// 如果先判 pending，模型会收到"结果未知、请重新发起"——
			// 那句话在语义上没说错，但它**没有告诉模型"这次已经批过了"**，
			// 于是模型很可能改一版参数（"既然没成功，我换个写法"）——
			// 而换参数意味着**重新审批**：人刚点完的那一下白费了。
			if (grant != null) {
				messages.add(new UserMessage(approvalNotice(grant)));
			}
			else if (pendingInvocation != null) {
				// 中断说明：只加文字，不加假的 tool 消息 ——
				// 伪造一条"工具返回了 X"是最危险的做法：模型会把它当成事实
				messages.add(new UserMessage(interruptionNotice(pendingInvocation)));
			}
		}

		List<AgentRun.Step> steps = new ArrayList<>();
		DuplicateActionDetector duplicates = new DuplicateActionDetector(properties.maxIdenticalActions());
		int totalTokens = 0;
		int toolCallCount = 0;

		// 恢复时步数从"已跑过的"接着数：不这么做的话，被杀 N 次的任务能跑 N×maxSteps 步，
		// 而"最大步数"这个上限就失去意义了
		int firstRound = resumeStepIndex + 1;

		for (int round = firstRound; round <= properties.maxSteps(); round++) {
			// ---- 出口 1/2/3：三个上限，全部在调模型之前判定（理由见类注释） ----
			AgentStopReason blocked = checkLimits(round, totalTokens, startedAt);
			if (blocked != null) {
				log.warn("Agent 循环在第 {} 轮前停止：{}（已用 {} tokens / {}ms）", round, blocked, totalTokens,
						elapsedMs(startedAt));
				AgentRun stopped = new AgentRun(question, steps, null, blocked, totalTokens, elapsedMs(startedAt),
						toolCallCount);
				listener.onFinish(runId, stopped);
				return stopped;
			}

			// ---- think：问模型一轮 ----
			long stepStarted = System.nanoTime();
			ModelTurn.ModelReply reply;
			try {
				reply = modelTurn.chat(messages);
			} catch (RuntimeException e) {
				// 模型调用失败：把已有步骤带回去，而不是抛掉已经花掉的成本
				//
				// 【这里做了一次"翻译"】把异常分成"太挤/鉴权/限流/超时/连不上/其它"。
				// 不做这一步的话，报告里只有 MODEL_ERROR 一句笼统的话 ——
				// 而其中最要命的一个细分是"太挤"：那是**我们自己的并发闸门**拒绝的，
				// 却会被读成"上游有问题"，于是排查方向整个跑偏
				AgentFailureCause cause = ModelFailureClassifier.classify(e);
				log.error("Agent 循环第 {} 轮调用模型失败（细分原因：{}）", round, cause, e);
				steps.add(new AgentRun.Step(round, "", List.of(), List.of(), 0, 0, elapsedMs(stepStarted)));
				AgentRun failed = new AgentRun(question, steps, null, AgentStopReason.MODEL_ERROR, totalTokens,
						elapsedMs(startedAt), toolCallCount, cause);
				listener.onFinish(runId, failed);
				return failed;
			}
			totalTokens += reply.tokens();

			// ---- 模型没要工具 → 这是最终回答 ----
			if (!reply.wantsTools()) {
				AgentRun.Step step = new AgentRun.Step(round, reply.text(), List.of(), List.of(),
						reply.promptTokens(), reply.completionTokens(), elapsedMs(stepStarted),
						reply.cachedPromptTokens());
				steps.add(step);
				if (reply.text() == null || reply.text().isBlank()) {
					// 既没文字也没工具调用：不是"模型的回答"，是上游异常。
					// 归到 ANSWERED 会让报告里出现一条"成功但答案是空的"记录 —— 那是最坏的观测
					log.warn("Agent 第 {} 轮收到空响应（无文字、无工具调用）", round);
					AgentRun empty = new AgentRun(question, steps, null, AgentStopReason.EMPTY_RESPONSE, totalTokens,
							elapsedMs(startedAt), toolCallCount);
					listener.onFinish(runId, empty);
					return empty;
				}
				// 【作答这一步也要写检查点】第一版只在"调了工具之后"写检查点，
				// 于是**最后一步（给出答案那步）永远不落库**。
				// 后果很具体：完成后的 trace 少了最后一步，"还原执行过程"时看不到模型是怎么收尾的；
				// 而且如果进程死在 `onFinish` 与 `store.persistCompletedRun` 之间，
				// 库里就只有过程没有结论 —— 恢复出来还得再问一次模型（多花一次钱）。
				//
				// 放这里还有第二个好处：它让"检查点"的定义变成**"每一步之后"**，
				// 而不是"每一次工具调用之后"—— 后者在纯文字回答的任务上一次都不写。
				messages.add(new AssistantMessage(reply.text()));
				listener.afterStep(runId, round, step, List.copyOf(messages), totalTokens, toolCallCount,
						elapsedMs(startedAt), promptFingerprint, canaryVariant);
				log.info("Agent 在 {} 轮后给出答案，共 {} tokens / {}ms", round, totalTokens, elapsedMs(startedAt));
				AgentRun answered = new AgentRun(question, steps, reply.text(), AgentStopReason.ANSWERED, totalTokens,
						elapsedMs(startedAt), toolCallCount);
				listener.onFinish(runId, answered);
				return answered;
			}

			// ---- act：把意图交给执行器（本类不自己执行工具） ----
			// 先记下 assistant 消息：它带着 tool_calls，是后续 tool 消息的"被回复对象"。
			// OpenAI 协议要求 tool 消息必须能对应到前面某条 assistant 的 tool_call_id，
			// 缺了这条消息，请求会被上游以 400 拒绝 —— 而错误信息通常不会说清是这个原因
			messages.add(assistantWithToolCalls(reply));

			List<ToolCallOutcome> outcomes = new ArrayList<>();
			boolean duplicateHit = false;
			String suspendedApprovalId = null;
			for (ToolInvocation invocation : reply.toolCalls()) {
				toolCallCount++;

				// 【通行证：这次调用是不是就是人刚批的那一次】
				// 判据是"工具名 + 参数"（见 ApprovalGrant），因为恢复之后模型会重新提出
				// 这个调用、而协议层会给它一个新的 tool_call_id。
				ApprovalGrant effectiveGrant = grant != null && grant.matches(invocation) ? grant : null;

				// ---- 出口 4：重复动作检测（在真正执行之前判，省掉一次工具执行） ----
				//
				// 【带通行证的那一次要先"忘掉"它的历史计数】
				// 否则刚被人批准、正准备执行的这一次，会被自己人判成"重复动作"并立刻停住 ——
				// 表现是"人批准了，但任务还是没往下走"。
				// 只放行这一个动作（而不是清空检测器）：人的批准让"重复"这个判断的前提
				// 对这一条不再成立，但它不能顺带把别的重复也洗白
				if (effectiveGrant != null) {
					duplicates.forget(invocation);
				}
				if (duplicates.record(invocation)) {
					duplicateHit = true;
					log.warn("检测到重复动作：{}（最多允许 {} 次）", invocation.describe(),
							properties.maxIdenticalActions());
					break;
				}

				ToolCallOutcome outcome = executor.execute(invocation,
						ToolCallContext.ofStep(runId, round, effectiveGrant).withRunPermission(permission));

				// ---- 出口 5：停在人工闸门上（HITL） ----
				//
				// 【为什么必须在 outcomes.add 与 messages.add 之前就退出】
				// 待批的那一次调用**没有结果**：它既没成功也没失败，只是停住了。
				// 如果照常把它放进这一步的 outcomes、并在对话里补一条 tool 消息，
				// 就等于伪造了一条"工具返回了 X"—— 而那正是本类在别处反复避免的事
				// （见 interruptionNotice 的注释：伪造工具返回是最危险的做法，模型会当成事实）。
				//
				// 于是这一步**不落进 steps 列表**：'这一步'还没有结束。
				// 循环在开始时补上的那条 assistant(tool_calls) 消息已经进了 messages，
				// 而它带着一个没有 tool 返回的 tool_call —— 这正是"停在半路"的真实形态，
				// 也是恢复时能把那次调用找出来的依据（见 ResumableAgentService.findPendingInvocation）。
				if (outcome.awaitingApproval()) {
					suspendedApprovalId = outcome.approval().approvalId();
					log.info("Agent 循环停在第 {} 轮：工具「{}」需要人工确认 #{}（本次运行暂停，等人）", round,
							invocation.name(), suspendedApprovalId);
					break;
				}

				outcomes.add(outcome);
				// ---- observe：回填进上下文 ----
				messages.add(toolResponse(invocation, outcome));
			}

			// ---- 暂停等人：写检查点、交回一份"等待中"的报告 ----
			if (suspendedApprovalId != null) {
				// 检查点必须写：恢复要"接着跑"，而"接着跑"靠的就是这份上下文。
				// 注意 checkpoint 的语义是"这一步之后"—— 而这里严格说是"这一步的中间"。
				// 仍然这样写是刻意的：它就是"进程现在能保存下来的最新状态"，
				// 而恢复逻辑不依赖"每步都完整"这个假设（它自己会从对话里找出悬空的 tool_call）
				AgentRun.Step partialStep = new AgentRun.Step(round, reply.text(), reply.toolCalls(), outcomes,
						reply.promptTokens(), reply.completionTokens(), elapsedMs(stepStarted),
						reply.cachedPromptTokens());
				listener.afterStep(runId, round, partialStep, List.copyOf(messages), totalTokens, toolCallCount,
						elapsedMs(startedAt), promptFingerprint, canaryVariant);

				AgentStopReason reason = AgentStopReason.WAITING_FOR_APPROVAL;
				AgentRun waiting = new AgentRun(question, steps, null, reason, totalTokens, elapsedMs(startedAt),
						toolCallCount);
				listener.onFinish(runId, waiting);
				return waiting;
			}

			AgentRun.Step step = new AgentRun.Step(round, reply.text(), reply.toolCalls(), outcomes,
					reply.promptTokens(), reply.completionTokens(), elapsedMs(stepStarted),
					reply.cachedPromptTokens());
			steps.add(step);

			// ---- 检查点：这一步已经完整结束，上下文可以落库了 ----
			// 位置很讲究：放在 `if (duplicateHit)` 之前。
			// 重复动作是一个"正常出口"，它这一步的上下文是完整可用的 ——
			// 放后面的话，恰恰在最需要看到"它在原地打转"的那次运行里会丢掉最后一步
			listener.afterStep(runId, round, step, List.copyOf(messages), totalTokens, toolCallCount,
					elapsedMs(startedAt), promptFingerprint, canaryVariant);

			if (duplicateHit) {
				// 不把"重复"当成正常一步继续：模型已经证明它没有新信息可用，
				// 再给它一轮只会得到第三次相同的调用
				AgentRun duplicated = new AgentRun(question, steps, null, AgentStopReason.DUPLICATE_ACTION,
						totalTokens, elapsedMs(startedAt), toolCallCount);
				listener.onFinish(runId, duplicated);
				return duplicated;
			}
		}

		// 循环正常跑满（每一轮都在调工具）—— 这是 MAX_STEPS
		log.warn("Agent 循环达到最大步数 {}，未给出最终答案", properties.maxSteps());
		AgentRun exhausted = new AgentRun(question, steps, null, AgentStopReason.MAX_STEPS, totalTokens,
				elapsedMs(startedAt), toolCallCount);
		listener.onFinish(runId, exhausted);
		return exhausted;
	}

	/**
	 * 中断说明。
	 *
	 * <p>措辞刻意有两部分：<b>发生了什么</b>（上一次执行被中断、那个工具的结果未知）
	 * 与<b>你该怎么做</b>（不要假定它成功、也不要假定失败）。
	 * 只说前一半，模型会自己去猜结果；而它对"未知"的默认处理往往是当成"没发生"然后重试 ——
	 * 于是同一个写操作可能被执行两次。
	 */
	private static String interruptionNotice(ToolInvocation pending) {
		return """
				【系统提示】上一次执行在调用工具「%s」的过程中被中断了，所以那个调用**没有结果**：
				它可能压根没开始，也可能已经执行完了但结果没来得及记下来。
				请不要假定它成功或失败。如果需要它的结果，请重新发起调用；
				如果这个操作看起来可能已经产生副作用，请在回答里明确指出这一点。""".formatted(pending.name());
	}

	/**
	 * <b>人工已批准的说明</b>（HITL 恢复路径）。
	 *
	 * <h2>为什么这段文本要原样带上工具名与参数</h2>
	 * 它的任务是让模型<b>重新提出完全相同的那个调用</b>。而"完全相同"是硬要求：
	 * 参数只要有一个字节不同，就会被判定为"另一件事"、需要<b>重新审批</b> ——
	 * 人刚点的那一下白费，界面上还会多出一条待批。
	 *
	 * <p>怎么让模型可靠地重提出一模一样的调用？<b>把那份参数写在它眼前</b>。
	 * 这比"请重复你上一次的调用"可靠得多：后者要求模型从自己的上文里精确复制，
	 * 而它很可能顺手"改进"一下措辞（尤其是它刚被拒过一次的时候）。
	 * 与 {@code interruptionNotice} 那条一样：<b>把关键事实写成模型能直接读到的东西，
	 * 而不是让它去推断。</b>
	 *
	 * <p>参数带上限（500 字符）：与回填文本同一个量级，避免一次超长参数把上下文顶满。
	 */
	private static String approvalNotice(ApprovalGrant grant) {
		return """
				【系统提示】你上一次提出的工具调用「%s」已经**经过人工确认通过**，可以执行了：
				参数：%s
				请**原样重新提出这次调用**（工具名与参数必须与上面完全一致，不要改写、不要补充字段）。
				只要参数有一个字不同，就会被当作另一次调用而需要重新审批，那会让人刚才的确认白费。
				如果这次调用成功，请继续完成原任务；如果它失败了，按失败原因决定下一步。"""
				.formatted(grant.toolName(), grant.normalizedArguments());
	}

	/** 三个上限的检查。返回 null 表示可以继续。顺序是按"最便宜的检查先做"排的。 */
	private AgentStopReason checkLimits(int round, int totalTokens, long startedAt) {
		// 步数：第 maxSteps+1 轮不该开始（循环条件已经保证，这里是显式的一道）
		if (round > properties.maxSteps()) {
			return AgentStopReason.MAX_STEPS;
		}
		// 时间：用 >= 而不是 >，"刚好到点"就算超
		if (elapsedMs(startedAt) >= properties.maxDurationMs()) {
			return AgentStopReason.MAX_DURATION;
		}
		// token：累计值达到上限就不再发起新的模型调用。
		// 注意判据是"已经花掉的"，不是"预测下一次会花多少"——
		// 预测需要估算器且必然有误差，而这里的目标只是"别无限烧"，不是"精确花满预算"
		if (totalTokens >= properties.maxTokens()) {
			return AgentStopReason.MAX_TOKENS;
		}
		return null;
	}

	/**
	 * 把模型的工具调用意图转成 assistant 消息。
	 *
	 * <p>必须把 {@code tool_calls} 原样带上：它是后续 tool 消息的<b>被回复对象</b>。
	 * 只放文字、丢掉 tool_calls 的话，请求在协议层就是不成立的
	 * （tool 消息找不到对应的 call id），而多数网关的报错信息不会指出这一点。
	 */
	private static AssistantMessage assistantWithToolCalls(ModelTurn.ModelReply reply) {
		List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
		for (ToolInvocation invocation : reply.toolCalls()) {
			toolCalls.add(new AssistantMessage.ToolCall(invocation.id(), "function", invocation.name(),
					invocation.arguments()));
		}
		return AssistantMessage.builder()
				.content(reply.text() == null ? "" : reply.text())
				.toolCalls(toolCalls)
				.build();
	}

	/**
	 * 把执行结论转成 tool 消息（observe 那一步）。
	 *
	 * <p>回填的内容用 {@code outcome.feedback()} —— 那是
	 * {@code ToolFeedbackFormatter} 精心写过的文本，包含"错在哪、可用值是什么、能不能重试"。
	 * <b>这里不要自己拼一句话</b>：一旦在这里另写一份措辞，
	 * 模型看到的东西就与测试断言的那份不一样了（而测试断言的是 feedback）。
	 */
	private static ToolResponseMessage toolResponse(ToolInvocation invocation, ToolCallOutcome outcome) {
		return ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse(invocation.id(), invocation.name(),
						outcome.feedback())))
				.build();
	}

	/**
	 * 读系统提示词。<b>读哪一版由 {@code decision} 决定</b>（灰度分流的结果），
	 * 与工具目录拼在一起 —— 见 {@code ToolRegistry.renderCatalog} 的注释。
	 *
	 * <h2>为什么正文与指纹必须来自同一次判定</h2>
	 * 判定结果同时给出了 version 与 fingerprint。如果这里重读一次"当前生效那一版"
	 * 的正文、而指纹用的是判定时算的那个，两者就可能来自<b>不同的版本</b> ——
	 * 当中间恰好有人切了版本时（这正是热更新会发生的事）。
	 * 那种情况下库里的指纹对不上真正发出去的正文，而它**不报错**：
	 * 事后查"那天的答案为什么变差"时，你会对着一个错的版本号去比正文。
	 */
	private String loadSystemPrompt(CanaryDecision decision) {
		// 兜底链（库 → classpath → 抛异常）在 PromptService 里，这里不重复实现 ——
		// 两处兜底就意味着两处会漂移
		String base = decision.candidate() ? candidateContent(decision) : prompts.currentContent(PromptKind.AGENT_LOOP);
		String catalog = registry.renderCatalog();
		if (catalog.isBlank()) {
			return base;
		}
		return base + "\n\n## 你当前可以使用的工具\n\n" + catalog;
	}

	/**
	 * 候选版本的正文。
	 *
	 * <p>判定时已经确认过那一版存在（见 {@code DefaultCanaryService.decide}），
	 * "两次读之间被删掉"在本项目里只剩"库被重建"这一种可能（库里没有删除接口）。
	 * 读不到时这里<b>兜底成当前生效那一版</b>，而不是抛出去 ——
	 * 理由与 {@code currentPromptFingerprint} 当初那条取舍正好相反，
	 * 所以放在一起讲清楚：
	 * <ul>
	 *   <li><b>主路径（读当前生效那一版）读不到 → 必须炸</b>：那时 Agent
	 *       没有系统提示词会用默认人格回答，看起来像"模型不听话"，实际是部署问题；</li>
	 *   <li><b>候选版读不到 → 不该炸</b>：实验配置的问题不该让命中的那批运行全部失败 ——
	 *       它们用稳定版正文照样能把任务跑完。代价是这一次的指纹记的是候选版、
	 *       而正文来自稳定版，这个不一致<b>必须留 WARN</b>（静默地不一致与
	 *       "本来就是这样"分不开，而那条日志就是两者的分界）。</li>
	 * </ul>
	 */
	private String candidateContent(CanaryDecision decision) {
		return canary.candidateContent(PromptKind.AGENT_LOOP, decision.version())
				.orElseGet(() -> {
					log.warn("灰度候选版 v{} 读不到正文 —— 这一次运行用稳定版正文兜底"
							+ "（trace 里的指纹仍是候选版：分流判定已经发生了，这一格如实记录那次的不一致）",
							decision.version());
					return prompts.currentContent(PromptKind.AGENT_LOOP);
				});
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	/** 当前配置（诊断端点与报告要能显示"这套上限是多少"）。 */
	public AgentLoopProperties properties() {
		return properties;
	}

	/**
	 * 换一个模型，其余装配照旧 —— 返回一个<b>新实例</b>，不改动自己。
	 *
	 * <p>{@code AgentLoop} 是<b>无状态</b>的：所有可变状态（消息列表、步数、累计 token、重复检测器）
	 * 都在 {@code run(...)} 的方法局部变量里。所以"换模型"可以安全地 new 一个新实例，
	 * 而不是在一个共享对象上改字段 —— 后者在虚拟线程下会串味
	 * （这个坑阶段一的钉住实验踩过：一个"只在第一次算"的字段在真并发下会互相干扰）。
	 */
	public AgentLoop withModelTurn(ModelTurn otherModel) {
		return new AgentLoop(registry, executor, otherModel, properties, prompts, canary, permission);
	}

	/**
	 * 换一次运行的<b>权限</b>，其余装配照旧 —— 返回一个<b>新实例</b>，不改动自己。
	 *
	 * <p>给恢复路径用：续跑必须用<b>原来那一档</b>（从库里读回来），
	 * 而不是"用当前默认档位跑完剩下的步骤" —— 后者会让一次"仅可查看"的运行
	 * 在恢复之后拿到完全权限，而那种放大在页面上完全看不出来。
	 */
	public AgentLoop withPermission(RunPermission other) {
		return new AgentLoop(registry, executor, modelTurn, properties, prompts, canary, other);
	}

}
