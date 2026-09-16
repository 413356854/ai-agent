package ai.agent.token;

import ai.agent.config.ChatCallProperties;
import ai.agent.prompt.PromptKind;
import ai.agent.prompt.PromptService;
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
 * 而对话历史的裁剪单元是<b>消息</b>（一问一答成对），语义完全不同 ——
 * 按内容块裁会把某一轮问答切成两半，留下"有回答没有提问"的畸形上下文。
 * 本项目在框架之外补一个守卫，裁的就是消息。
 *
 * <h2>三条不同的处理路径（这是本类的全部业务逻辑）</h2>
 * <ol>
 *   <li><b>放行</b> —— 估算值在预算内，什么都不做（绝大多数请求走这里，零成本）；</li>
 *   <li><b>裁剪历史后放行</b> —— 历史太长但本次提问本身不大。
 *       丢最旧的几轮对话，保住"这次能答"；这是对话变长时的<b>常态</b>，不该报错；</li>
 *   <li><b>拒绝</b> —— 连"本次提问 + 系统提示词"都放不下（比如用户直接粘了一本书进来）。
 *       这时候裁剪救不了（该裁的是提问本身，但提问是用户的意图，不能替他删），
 *       必须明确报错并告诉他差多少、该怎么办。</li>
 * </ol>
 *
 * <h2>为什么裁剪走仓储层（{@code saveAll}）而不是 {@code ChatMemory.add}</h2>
 * 同一个坑在重试清理孤儿消息时已经踩过一次：{@code ChatMemory.add()} 是<b>追加合并</b>语义
 * （读旧 → 合并 → 存回），拿裁剪后的列表去调它等于把裁掉的历史又加回来。
 * 真正"整段替换"的入口只有 {@link ChatMemoryRepository#saveAll}。
 */
@Component
public class TokenBudgetGuard {

	private static final Logger log = LoggerFactory.getLogger(TokenBudgetGuard.class);

	private final ChatCallProperties properties;

	private final TokenEstimator estimator;

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
			ChatMemoryRepository chatMemoryRepository, PromptService prompts) {
		this.properties = properties;
		this.estimator = estimator;
		this.chatMemoryRepository = chatMemoryRepository;
		this.prompts = prompts;
	}

	/**
	 * 流式对话入口用：检查预算，必要时<b>裁剪历史</b>，返回一份账目快照。
	 *
	 * @param conversationId 会话 ID
	 * @param userMessage    本次提问
	 * @return 账目快照（用于日志与可观测性）
	 * @throws TokenBudgetExceededException 连"系统提示词 + 本次提问"都放不下时
	 */
	public BudgetCheck prepare(String conversationId, String userMessage) {
		ChatCallProperties.Budget cfg = properties.getBudget();
		if (!cfg.isEnabled()) {
			return BudgetCheck.disabled();
		}

		List<Message> history = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));

		int questionTokens = estimator.estimateMessage(userMessage);
		// 【每次现算，不是读字段】提示词可以在运行中换版本，读一个构造期算好的常量
		// 会让这个数永久过时 —— 而失效形态只是"历史被裁得比预期早"，没有任何报错
		int systemPromptTokens = systemPromptTokens();
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
			return new BudgetCheck(true, needed, allowance, history.size(), 0, systemPromptTokens);
		}

		// 路径 2 的分岔：配置不允许裁剪 → 只能拒绝
		if (!cfg.isTruncateHistory()) {
			String reason = """
					上下文预算超限且未启用历史裁剪：本次需要约 %d tokens（历史 %d + 提问 %d + 系统提示词 %d），可用预算 %d。
					建议：开启 ai.chat.budget.truncate-history，或新开一个会话。"""
					.formatted(needed, historyTokens, questionTokens, systemPromptTokens, allowance);
			log.warn("预算拒绝（未启用裁剪）conversationId={} 需要={} 可用={} 历史条数={}",
					conversationId, needed, allowance, history.size());
			throw new TokenBudgetExceededException(reason, needed, allowance);
		}

		// 路径 2：裁剪历史后放行
		int keepFrom = trimIndex(history, allowance, base);
		int dropped = keepFrom;
		if (dropped > 0) {
			List<Message> kept = new ArrayList<>(history.subList(keepFrom, history.size()));
			// 整段替换语义（不是 add 的追加合并），见类注释
			chatMemoryRepository.saveAll(conversationId, kept);
			log.warn("上下文超预算 → 裁掉最旧的 {} 条历史消息 conversationId={} needed={} allowance={} kept={}",
					dropped, conversationId, needed, allowance, kept.size());
			history = kept;
			historyTokens = estimateHistory(kept);
			needed = base + historyTokens;
		}

		// 裁到最少条数仍然超限：说明"留下来的那几条"本身就很大，这时不能假装成功
		if (needed > allowance) {
			String reason = """
					上下文预算超限：已裁到最少 %d 条历史仍然放不下（需要约 %d tokens，可用 %d）。
					建议：新开一个会话，避免把长文本粘进对话。"""
					.formatted(cfg.getMinHistoryMessages(), needed, allowance);
			log.warn("预算拒绝（裁到下限仍超）conversationId={} 需要={} 可用={} 保留条数={}",
					conversationId, needed, allowance, history.size());
			throw new TokenBudgetExceededException(reason, needed, allowance);
		}

		return new BudgetCheck(true, needed, allowance, history.size(), dropped, systemPromptTokens);
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
		return new BudgetCheck(true, needed, allowance, historyMessages, 0, systemPromptTokens);
	}

	/** 当前配置下留给输入的 token 上限（便于诊断端点与前端展示）。 */
	public int inputAllowance() {
		return properties.getBudget().inputAllowance();
	}

	/**
	 * 从历史里裁到"不超预算"所需的起始下标。
	 *
	 * <p>裁剪方向<b>只能是从最旧的开始</b>：越近的对话越相关，而且最近的这一轮
	 * 往往就是用户正在追问的上下文。这跟"滑动窗口"是同一个直觉，
	 * 所以这里也是 {@code MessageWindowChatMemory} 早就做的事（那个窗口默认 20 条）。
	 *
	 * <p>有两条硬约束：
	 * <ol>
	 *   <li>至少要保留 {@code minHistoryMessages} 条 —— 全丢掉等于让模型失忆，
	 *       用户会觉得"聊着聊着它就不认识我了"，比报错更难排查；</li>
	 *   <li>裁剪必须<b>整条整条地丢</b>，不能从某条消息中间截断 ——
	 *       半条消息会让模型看到残缺的上下文。</li>
	 * </ol>
	 */
	private int trimIndex(List<Message> history, int allowance, int base) {
		int minKeep = Math.max(0, properties.getBudget().getMinHistoryMessages());
		int maxDroppable = Math.max(0, history.size() - minKeep);

		// 逐条从最旧的开始扣减，不每次重算总和（否则退化成 O(n²)，虽然 n 不大但没必要）
		int historyTokens = estimateHistory(history);
		int index = 0;
		while (index < maxDroppable && base + historyTokens > allowance) {
			historyTokens -= estimator.estimateMessage(textOf(history.get(index)));
			index++;
		}
		return index;
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
	 */
	public record BudgetCheck(boolean enabled, int neededTokens, int allowedTokens, int historyMessages,
			int droppedMessages, int systemPromptTokens) {

		static BudgetCheck disabled() {
			return new BudgetCheck(false, 0, 0, 0, 0, 0);
		}

		/** 剩余可用额度，负数表示已超限（正常情况下不会出现，因为超限会被拦下）。 */
		public int remainingTokens() {
			return allowedTokens - neededTokens;
		}

		public boolean truncated() {
			return droppedMessages > 0;
		}
	}

}
