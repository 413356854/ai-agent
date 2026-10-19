package ai.agent.service.token;

import ai.agent.config.ChatCallProperties;
import ai.agent.entity.chat.ConversationSummary;
import ai.agent.service.chat.compact.ChatCompactionService;
import ai.agent.service.chat.compact.CompactionRequest;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 预算守卫（学习点 2）：**在把请求发给模型之前**算出这次要花多少 token，
 * 超了就先处理掉，而不是等模型报错。
 *
 * <h2>它到底在防什么</h2>
 * <pre>
 *   contextWindow × (1 - safetyMargin)  ≥  estimatedInputTokens + maxOutputTokens
 *   └────────── 可用预算 ──────────┘      └────────── 这次要花的额度 ──────────┘
 * </pre>
 * 左边小于右边就说明"这次装不下"。装不下的后果分两种，都很糟：
 * <ol>
 *   <li><b>模型报错</b>（429 / 400 context_length_exceeded）——
 *       这次往返的钱和时间已经花掉了，用户才看到失败；</li>
 *   <li><b>模型不报错，静默截断输入</b> —— 这才是最坑的：请求成功了、回答也回来了，
 *       只是模型<b>根本没看到</b>前半段上下文。表现是"越聊越傻、答非所问"，
 *       而排查方向会跑到提示词、跑到模型能力上去，根本想不到是上下文被悄悄砍了。</li>
 * </ol>
 *
 * <h2>为什么这件事**不能**只靠框架的 advisor 做</h2>
 * Spring AI 自带了 {@code LastMaxTokenSizeContentPurger}，但它处理的是
 * {@code List<MediaContent>}（多模态内容块），粒度在"内容块"这一层；
 * 而对话历史的<b>压缩</b>单元是"轮"（一问一答成对），语义完全不同 ——
 * 按内容块切会把某一轮问答切成两半，留下"有回答没有提问"的畸形上下文。
 * 本项目在框架之外补一个守卫，压的就是轮。
 *
 * <h2>三条不同的处理路径（这是本类的全部业务逻辑）</h2>
 * <ol>
 *   <li><b>放行</b> —— 估算值在预算内，什么都不做（绝大多数请求走这里，零成本）；</li>
 *   <li><b>压缩历史后放行</b> —— 历史太长但本次提问本身不大。把最旧的若干<b>整轮</b>
 *       交给 {@link ChatCompactionService} 压成摘要（摘要进 prompt，原文留在存储里），
 *       保住"这次能答"；这是对话变长时的<b>常态</b>，不该报错；</li>
 *   <li><b>拒绝</b> —— 连"本次提问 + 系统提示词"都放不下（比如用户直接粘了一本书进来）。
 *       这时候压缩救不了（该压的是提问本身，但提问是用户的意图，不能替他删），
 *       必须明确报错并告诉他差多少、该怎么办。</li>
 * </ol>
 *
 * <h2>为什么压缩不回写存储</h2>
 * 这是本类与它上一版最重要的一处不同。原来路径 2 是"裁掉最旧的 N 条 + {@code saveAll}
 * 写回"，也就是<b>真删</b> —— 而删掉的消息在下游没有任何留底，
 * 于是"用户当时到底说了什么"变成一个不可回答的问题。
 * 现在存储一个字都不动：被压掉的那一段以摘要的形式进 prompt
 * （拼接在 {@code SummarizingChatMemory.get()} 那一侧），原文随时还查得到。
 *
 * <p>⚠️ 由此带出一个读日志时容易误解的点：{@code droppedMessages} 现在恒为 0，
 * 它记的"删了多少条"这件事已经不存在了。这次压缩了多少看
 * {@link BudgetCheck#compactedMessages()}。
 */
@Component
public class TokenBudgetGuard {

	private static final Logger log = LoggerFactory.getLogger(TokenBudgetGuard.class);

	private final ChatCallProperties properties;

	private final TokenEstimator estimator;

	/**
	 * 对话历史的读写口：本类用它<b>读旧消息算 token、再把裁剪结果写回去</b>。
	 *
	 * <p>存储位置由 {@code ChatClientConfig#chatMemoryRepository()} 决定（默认内存实现）。
	 * 这里只依赖接口，换存储不影响本类 —— 但注意：<b>换了存储之后，"裁剪"就会真的改变库里的内容</b>，
	 * 而内存实现下它随进程一起消失。
	 */
	private final ChatMemoryRepository chatMemoryRepository;

	/**
	 * 提示词的来源（阶段四第 3 件）。预算里算的那一份与实际发出去的那一份
	 * <b>必须同源</b> —— 否则会出现"校验算的是旧提示词、实际发的是新的"，预算永远算不准。
	 *
	 * <p>在阶段四之前，两者的一致性靠"读同一个配置属性"保证（见类注释）。
	 * 现在它们靠"问同一个 {@link PromptService}"保证，而且这个保证更强：
	 * 属性只能保证<b>位置</b>相同，而这个还能保证<b>版本</b>相同。
	 */
	private final PromptService prompts;

	/**
	 * 超预算时<b>压缩</b>历史（而不是删）。
	 *
	 * <p>这一处是整条链上唯一"发请求之前"的时机，而摘要要花一次模型调用 ——
	 * 放在 {@code ChatMemory.get()} 里不行（那里在 advisor 的 {@code before()} 阶段被同步调用，
	 * 见 {@code SummarizingChatMemory} 的类注释）。
	 *
	 * <p>压完<b>不回写存储</b>：原文留在 {@code chatMemoryRepository} 里，
	 * 压缩只决定"这次发给模型的那一份"长什么样。所以这里的 {@code droppedMessages}
	 * 是"这次没进 prompt 的条数"，不是"被删掉的条数"。
	 */
	private final ChatCompactionService compaction;

	/**
	 * 系统提示词的 token 数（<b>每次现算，不是启动时算一次</b>）。
	 *
	 * <p><b>为什么必须把它算进来</b>：系统提示词是 advisor 在调用阶段注入的，
	 * 业务代码里看不见，但它实实在在占窗口。漏算它的后果是"预算校验永远偏松"——
	 * 而且是固定偏松，永远不会被察觉。
	 *
	 * <p>实测：本项目的 {@code system-agent.st} 约 250 字，估算约 312 tokens。
	 * 看着不多，但在"窗口调小"的测试里它就是最大的一笔开销 —— 这也说明它不能不算。
	 *
	 * <h2>⚠️ 阶段四的改动：这个数从"构造期常量"变成了"每次向版本库问一次"</h2>
	 * 之前它是 {@code final int}，在构造器里算好后就不再变。
	 * 那在"提示词是 classpath 文件"的前提下是对的（文件在进程生命周期里不会变），
	 * 但<b>阶段四第 3 件做的是配置热更新</b> —— 提示词会在运行中换版本，
	 * 而换了之后这个常量就<b>永久过时</b>。
	 * 它的失效形态很难发现：预算校验照常工作，只是算出来的数多了一点或少了一点，
	 * 表现为"历史被裁得比预期早"或"该拦的没拦住"，而没有任何一处会报错。
	 * 所以这里每次都问一遍 {@link PromptService#tokenCount} ——
	 * 它的返回值来自"当前生效那一版"的估算，与真正发出去的内容同源。
	 */
	public int systemPromptTokens() {
		return prompts.tokenCount(PromptKind.CHAT_SYSTEM);
	}

	/**
	 * 唯一的构造器 —— Spring 直接用它建 bean。
	 *
	 * <p><b>这里踩了两个坑，都值得记一笔</b>（第二个尤其反直觉）：
	 * <ol>
	 *   <li><b>"多个构造器 ⇒ 找不到无参构造器"。</b>
	 *       原先写成"公开构造器 + 一个包私有重载（供测试注入提示词）"，容器启动直接失败：
	 *       <pre>Failed to instantiate [TokenBudgetGuard]: No default constructor found</pre>
	 *       原因是 Spring 只在<b>恰有一个</b>构造器（或有 {@code @Autowired} 标注的那个）时
	 *       才自动装配；看到两个都没标注时，它退回"找无参构造器"，
	 *       于是报出这句极具误导性的话 —— 明明有两个带参构造器，却说找不到无参的。
	 *       <b>修法：只留一个公开构造器。</b></li>
	 *   <li><b>{@code Resource} / {@code ClassPathResource} 都不能当构造器参数"凭空注入"。</b>
	 *       换成具体类型后，报错从 {@code No qualifying bean of type 'Resource'} 变成
	 *       {@code No qualifying bean of type 'ClassPathResource'} —— 一模一样的问题。
	 *       容器里没有这种 bean，Spring 也没有"看到 Resource 类型就自己 new 一个"的规则。
	 *       真正管用的写法只有两种：<b>加 {@code @Value} 让属性占位符解析器介入</b>，
	 *       或者注入 {@code ResourceLoader} 自己解析。</li>
	 * </ol>
	 * <b>阶段四的后续</b>：系统提示词改成从版本库取之后，这里不再注入 {@code @Value} 资源，
	 * 而是注入 {@link PromptService}（一个有真实实现的 bean，不涉及上面第 2 条那个边界）。
	 * 上面两条<b>刻意留着</b>：它们记的是"为什么当时不能那么写"，
	 * 而一个不知道这段历史的人很容易把 {@code @Value} 资源再走一遍弯路。
	 */
	public TokenBudgetGuard(ChatCallProperties properties, TokenEstimator estimator,
			ChatMemoryRepository chatMemoryRepository, PromptService prompts,
			ChatCompactionService compaction) {
		this.properties = properties;
		this.estimator = estimator;
		this.chatMemoryRepository = chatMemoryRepository;
		this.prompts = prompts;
		this.compaction = compaction;
	}

	/**
	 * 流式对话入口用：检查预算，超了<b>压缩历史</b>，返回一份账目快照。
	 *
	 * <h2>为什么是"压缩"而不是"裁剪"</h2>
	 * 裁剪是<b>丢</b>：丢掉的对话在下游没有任何留底，于是"用户当时到底说了什么"
	 * 变成一个不可回答的问题 —— 而且它不报错，只表现为"聊着聊着它不记得了"。
	 * 压缩是<b>换一种说法留着</b>：被挤出去的那一段由模型压成摘要进 prompt，
	 * 原文仍然完整留在对话记忆里（看得到、导出得到），只是不再进这次 prompt。
	 *
	 * <h2>为什么压缩失败也不能回退成"删"</h2>
	 * 那是这个类改这件事时最容易写错的一笔：{@code compact()} 返回"没压成"时，
	 * 顺手把最旧的几条删掉看起来很自然 —— 但它把"压不动"降级成了"丢掉"，
	 * 而压不动恰恰发生在<b>旧摘要已经很满</b>（内容最多）的时候。
	 * 所以压不动时只做两件事：给系统消息加一句兜底提示，以及如实报出"仍超预算"。
	 *
	 * @param conversationId 会话 ID
	 * @param userMessage    本次提问
	 * @return 账目快照（用于日志与可观测性）
	 * @throws TokenBudgetExceededException 连"系统提示词 + 本次提问"都放不下时
	 */
	public BudgetCheck prepare(String conversationId, String userMessage) {
		return prepare(conversationId, userMessage, 0);
	}

	/**
	 * 与上面同一份账，多算一段<b>这次请求才会拼进系统提示词</b>的额外内容（知识库材料）。
	 *
	 * <h2>为什么不给这段内容另立一套账</h2>
	 * 因为它是这个请求的一部分：不把它算进去的话，账目里"可用"的那部分会被它悄悄吃掉，
	 * 表现为"预算说还够、实际发出去超了窗口"，而那一类的失败发生在模型侧，
	 * 与本地的账对不上。所以它是<b>加在系统提示词那一项上</b>，不是第四项。
	 *
	 * @param extraSystemPromptTokens 这次请求额外拼进系统提示词的 token 数（没拼时为 0）
	 */
	public BudgetCheck prepare(String conversationId, String userMessage, int extraSystemPromptTokens) {
		ChatCallProperties.Budget cfg = properties.getBudget();
		if (!cfg.isEnabled()) {
			return BudgetCheck.disabled();
		}

		List<Message> history = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));

		int questionTokens = estimator.estimateMessage(userMessage);
		// 【每次现算，不是读字段】提示词可以在运行中换版本，读一个构造期算好的常量
		// 会让这个数永久过时 —— 而失效形态只是"历史被裁得比预期早"，没有任何报错
		int systemPromptTokens = systemPromptTokens() + Math.max(0, extraSystemPromptTokens);
		int base = systemPromptTokens + questionTokens + estimator.requestOverhead();
		int allowance = cfg.inputAllowance();

		// 路径 3：连提问本身都放不下 —— 裁剪救不了，必须拒绝（而且要说清差多少）
		if (base > allowance) {
			String reason = """
					上下文预算超限：本次提问本身就超出可用预算。
					系统提示词约 %d tokens + 本次提问约 %d tokens = %d，可用预算仅 %d tokens
					（窗口 %d × 安全边际 %d%% 后扣掉为输出预留的 %d tokens）。
					建议：把问题拆小，或缩短粘贴的内容后重试。"""
					.formatted(systemPromptTokens, questionTokens, base, allowance,
							cfg.getContextWindow(), marginPercent(cfg), cfg.reservedForOutput());
			// 拒绝必须留日志。原因：预算校验是"静默生效"的逻辑 ——
			// 请求被拦下时用户只看到 400，而系统里没有任何痕迹说明"是我们拦的、拦在哪个环节"。
			// 生产上排查"某个请求为什么失败"时，这行日志是第一现场。
			log.warn("预算拒绝（提问本身超限）conversationId={} 需要={}（系统 {} + 提问 {}）可用={}",
					conversationId, base, systemPromptTokens, questionTokens, allowance);
			throw new TokenBudgetExceededException(reason, base, allowance);
		}

		int historyTokens = estimateHistory(history);
		int needed = base + historyTokens;

		// 路径 1：放行。绝大多数请求在这里结束，不做任何多余动作
		if (needed <= allowance) {
			// 即使没超预算，也要把"当前生效的摘要覆盖了几轮"报出去 ——
			// 否则"压缩到底发生过没有"只在压缩的那一次日志里能看到，
			// 而这个判断恰恰是排查"它为什么不记得早期对话"时要问的第一个问题
			ConversationSummary active = compaction.view(conversationId, history).summary().orElse(null);
			return new BudgetCheck(true, needed, allowance, history.size(), 0, systemPromptTokens, 0,
					active == null ? 0 : active.coveredTurns(), history.size(), null);
		}

		// 路径 2 的分岔：配置不允许压缩 → 只能拒绝
		if (!cfg.isTruncateHistory()) {
			String reason = """
					上下文预算超限且未启用历史压缩：本次需要约 %d tokens（历史 %d + 提问 %d + 系统提示词 %d），可用预算 %d。
					建议：开启 ai.chat.budget.truncate-history，或新开一个会话。"""
					.formatted(needed, historyTokens, questionTokens, systemPromptTokens, allowance);
			log.warn("预算拒绝（未启用历史压缩）conversationId={} 需要={} 可用={} 历史条数={}",
					conversationId, needed, allowance, history.size());
			throw new TokenBudgetExceededException(reason, needed, allowance);
		}

		// 路径 2：压缩历史后放行。
		//
		// 【这一段是本类从"删"改成"压"的全部所在】原来这里是 trimIndex + saveAll：
		// 把最旧的若干条从存储里抹掉。现在只压不删 —— 存储一个字都不动，
		// 被压掉的那一段以摘要的形式进 prompt（拼接在 ChatMemory.get() 那一侧）。
		ChatCompactionService.Outcome outcome = compaction.compact(new CompactionRequest(conversationId, history, base,
				allowance, cfg.getMinHistoryMessages(), null));
		int compacted = outcome.compactedMessages();
		if (compacted > 0) {
			// 这一份估算交给压缩服务算过一次（它要用它判"压缩之后还超不超"），
			// 这里不重算：两处各算一遍迟早会算出两个数，而"哪个是 prompt 真正的长度"
			// 就会变成一个要读两段代码才能回答的问题
			needed = base + estimateHistory(outcome.view().messages());
			log.warn("上下文超预算 → 压缩掉最旧的 {} 条历史消息（累计 {} 轮已进摘要）conversationId={} needed={} "
					+ "allowance={} 保留={} 摘要={} 字符",
					compacted, outcome.summary().map(summary -> summary.coveredTurns()).orElse(0), conversationId,
					needed, allowance, outcome.view().messages().size(), 
					outcome.summary().map(summary -> summary.summary().length()).orElse(0));
		}

		// 压完仍然超限：这时**不能**回退成"删几条" —— 那是把"压不动"降级成"丢掉"，
		// 而压不动恰恰发生在摘要内容最多的时候。如实报出来，由调用方决定。
		if (needed > allowance) {
			String reason = """
					上下文预算超限：历史已经压缩过，仍然放不下（需要约 %d tokens，可用 %d，历史 %d 条）。
					建议：新开一个会话，或把这一轮的问题缩短 —— 避免把长文本粘进对话。"""
					.formatted(needed, allowance, history.size());
			log.warn("预算拒绝（压缩后仍超）conversationId={} 需要={} 可用={} 历史条数={} 压缩条数={}",
					conversationId, needed, allowance, history.size(), compacted);
			throw new TokenBudgetExceededException(reason, needed, allowance);
		}

		return new BudgetCheck(true, needed, allowance, history.size(), 0, systemPromptTokens, compacted,
				outcome.summary().map(summary -> summary.coveredTurns()).orElse(0),
				outcome.view().messages().size(), outcome.needsModelNudge());
	}

	/**
	 * 结构化输出入口用：只检查、<b>不裁剪</b>。
	 *
	 * <p><b>为什么这里刻意不裁历史</b>：结构化输出是"同一个问题最多问 3 轮"的回灌循环，
	 * 每一轮的提示词都比上一轮长（带上了上次的原始输出与错误清单）。
	 * 如果在这一层顺手裁历史，会出现一个很难查的现象：
	 * <b>第 3 轮被裁掉的历史，正是第 1、2 轮刚刚写进去的内容</b> ——
	 * 模型会看到自己"失忆"，回灌反馈直接失效。
	 * 所以历史裁剪只在会话入口（{@link #prepare}）做一次，回灌循环里只做预算校验：
	 * 超了就明确失败，而不是悄悄改上下文。
	 *
	 * @param prompt 本轮真正要发给模型的完整提示词（含 schema / 回灌反馈）
	 * @throws TokenBudgetExceededException 超预算时
	 */
	public BudgetCheck check(String label, String prompt, int historyMessages) {
		ChatCallProperties.Budget cfg = properties.getBudget();
		if (!cfg.isEnabled()) {
			return BudgetCheck.disabled();
		}

		int systemPromptTokens = systemPromptTokens();
		int needed = systemPromptTokens + estimator.estimateMessage(prompt) + estimator.requestOverhead();
		int allowance = cfg.inputAllowance();

		if (needed > allowance) {
			String reason = """
					%s 超出上下文预算：本轮提示词约 %d tokens（含系统提示词 %d），可用预算 %d。
					提示：结构化输出的回灌轮次会把上一轮的原始输出与错误清单一并带上，提示词是逐轮增长的。
					建议：缩短问题，或调小 ai.chat.structured.max-attempts。"""
					.formatted(label, needed, systemPromptTokens, allowance);
			throw new TokenBudgetExceededException(reason, needed, allowance);
		}
		return new BudgetCheck(true, needed, allowance, historyMessages, 0, systemPromptTokens, 0, 0,
				historyMessages, null);
	}

	/** 当前配置下留给输入的 token 上限（便于诊断端点与前端展示）。 */
	public int inputAllowance() {
		return properties.getBudget().inputAllowance();
	}

	private int estimateHistory(List<Message> history) {
		int total = 0;
		for (Message message : history) {
			total += estimator.estimateMessage(textOf(message));
		}
		return total;
	}

	/**
	 * 取消息正文。
	 *
	 * <p>用 {@code getText()} 而不是 {@code toString()}：后者会带上类名与元数据，
	 * 估算值会虚高。但 tool-call 之类的消息 {@code getText()} 可能是 {@code null}，
	 * 所以统一走 {@link String#valueOf} 兜住，绝不让估算环节抛异常 ——
	 * 预算校验是"保护性逻辑"，它自己崩掉比不校验更糟。
	 */
	private static String textOf(Message message) {
		if (message == null) {
			return "";
		}
		return String.valueOf(message.getText());
	}

	private static int marginPercent(ChatCallProperties.Budget cfg) {
		return (int) Math.round(cfg.getSafetyMargin() * 100);
	}

	/**
	 * 一次预算校验的账目快照。
	 *
	 * <p><b>为什么要把这些数字返回出来，而不是只返回 boolean</b>：
	 * "预算够不够"这个结论没法排查 —— 出问题时你需要知道的是
	 * <b>差多少、钱花在哪（历史占了多少、系统提示词占了多少）</b>。
	 * 把这些数字交给日志与诊断端点，"上下文为什么变贵了"就是一个可以直接看的问题。
	 *
	 * <h2>{@code droppedMessages} 的语义</h2>
	 * <b>它恒为 0，且不会被删掉。</b>它原来记的是"这次从存储里删掉了多少条历史"，
	 * 而历史现在只压不删 —— 留着这个字段是为了让调用方（{@code ChatController} 的
	 * {@code truncated()} 分支）还能编译与显示，同时把"已经不再有删除这件事"
	 * 表达在类型上。要看这次到底压缩了多少，看 {@code compactedMessages}。
	 *
	 * @param compactedMessages 这次被压进摘要、因而不进 prompt 的条数
	 * @param summaryTurns      当前摘要覆盖了几轮用户发言（0 = 还没压过）
	 * @param promptMessages    这次真正发出去的消息条数（含摘要那一条）
	 * @param needsModelNudge   压不动时的兜底提示；{@code null} = 压成了（或本来不用压）
	 */
	public record BudgetCheck(boolean enabled, int neededTokens, int allowedTokens, int historyMessages,
			int droppedMessages, int systemPromptTokens, int compactedMessages, int summaryTurns,
			int promptMessages, String needsModelNudge) {

		static BudgetCheck disabled() {
			return new BudgetCheck(false, 0, 0, 0, 0, 0, 0, 0, 0, null);
		}

		/** 剩余可用额度，负数表示已超限（正常情况下不会出现，因为超限会被拦下）。 */
		public int remainingTokens() {
			return allowedTokens - neededTokens;
		}

		/** 这次有没有从存储里删掉历史。<b>恒为 false</b> —— 见 record 头注。 */
		public boolean truncated() {
			return droppedMessages > 0;
		}

		/** 这次有没有把历史压进摘要。 */
		public boolean compacted() {
			return compactedMessages > 0;
		}

		/** 摘要当前覆盖的轮数。 */
		public int coveredTurns() {
			return summaryTurns;
		}

		/** 这次摘要是不是没压成（压不动）。 */
		public boolean fallbackNudge() {
			return needsModelNudge != null;
		}
	}

}
