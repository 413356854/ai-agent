package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 对话调用层策略配置（超时 + 重试），前缀 {@code ai.chat}。
 *
 * <p>为什么要有这个类：超时和重试的数字是<b>排障时第一个要改的东西</b>
 * （"模型慢" 和 "模型挂了" 的处理方式完全不同）。把它们写在代码常量里，
 * 每次调整都要重新编译；绑到配置后，改 {@code application.properties} 或
 * 启动参数 {@code --ai.chat.timeout.first-token=60s} 就能生效。
 *
 * <p>为什么用 {@code Duration} 而不是 long 毫秒：配置里可以写 {@code 20s} / {@code 500ms}，
 * 比 {@code 20000} 更难看错一个 0 —— 这类"少写一个 0 变成 2 秒"的错误非常常见。
 *
 * <p><b>默认值之间的约束关系</b>（改任何一个都要跟着检查其余几个，注释里写了原因）：
 * <pre>
 *   前端首字节超时 30s
 *     &gt; 后端首字超时 20s + 第 1 次重试退避 ≈1.1s + ... （后端要先有机会把重试跑完）
 *     &gt; 前端整体超时 180s（给足重试 3 次 + 模型慢速生成的空间）
 * </pre>
 * 顺序反了会出现"后端还在重试，前端已经报超时"的假故障。
 */
@ConfigurationProperties(prefix = "ai.chat")
public class ChatCallProperties {

	@NestedConfigurationProperty
	private Retry retry = new Retry();

	@NestedConfigurationProperty
	private Timeout timeout = new Timeout();

	@NestedConfigurationProperty
	private Structured structured = new Structured();

	@NestedConfigurationProperty
	private Budget budget = new Budget();

	@NestedConfigurationProperty
	private Estimation estimation = new Estimation();

	@NestedConfigurationProperty
	private VirtualThreads virtualThreads = new VirtualThreads();

	@NestedConfigurationProperty
	private Sampling sampling = new Sampling();

	public Retry getRetry() {
		return retry;
	}

	public void setRetry(Retry retry) {
		this.retry = retry;
	}

	public Timeout getTimeout() {
		return timeout;
	}

	public void setTimeout(Timeout timeout) {
		this.timeout = timeout;
	}

	public Structured getStructured() {
		return structured;
	}

	public void setStructured(Structured structured) {
		this.structured = structured;
	}

	public Budget getBudget() {
		return budget;
	}

	public void setBudget(Budget budget) {
		this.budget = budget;
	}

	public Estimation getEstimation() {
		return estimation;
	}

	public void setEstimation(Estimation estimation) {
		this.estimation = estimation;
	}

	public VirtualThreads getVirtualThreads() {
		return virtualThreads;
	}

	public void setVirtualThreads(VirtualThreads virtualThreads) {
		this.virtualThreads = virtualThreads;
	}

	public Sampling getSampling() {
		return sampling;
	}

	public void setSampling(Sampling sampling) {
		this.sampling = sampling;
	}

	/**
	 * 结构化输出的回灌重试策略（Retry &amp; Reflection Loop）。
	 *
	 * <p><b>与 {@link Retry}（网络层重试）的区别必须拎清</b>，这是两件不同的事：
	 * <ul>
	 *   <li>{@link Retry} 重试的是<b>同一次请求</b>——网络抖了、限流了，重发一遍期望"这次能通"。
	 *       判定依据是异常类型（超时 / 429 / 5xx）。</li>
	 *   <li>这里的重试重试的是<b>同一个问题</b>，但<b>请求内容变了</b>——
	 *       把"你上次输出哪里不合格"告诉模型，期望"这次能改对"。
	 *       判定依据是校验结果（字段缺失 / 类型错 / 取值越界）。</li>
	 * </ul>
	 * 所以它们可以同时生效：一次对模型的调用可能先经历 2 次网络重试，再经历 2 轮回灌重试。
	 */
	public static class Structured {

		/**
		 * 回灌重试的最大轮数（含首次调用）。
		 *
		 * <p>默认 3 的含义是：初次 1 次 + 最多回灌 2 次。为什么不更大：
		 * 实测模型改不对的原因几乎都是"它压根没理解要什么"，这种理解性问题再灌 10 次也一样；
		 * 而每多一轮就多烧一次 token、多等几秒。3 轮之后走兜底比继续纠缠更划算。
		 */
		private int maxAttempts = 3;

		/**
		 * 是否要求模型走 JSON 模式（{@code response_format=json_object}）。
		 *
		 * <p>留成开关是因为不同供应商支持度不同：DeepSeek 支持 {@code json_object}
		 * 但<b>不支持</b> {@code json_schema}（实测返回 "This response_format type is unavailable now"）。
		 * 换成只认提示词、不认 response_format 的供应商时，把它关掉即可 ——
		 * 校验与回灌逻辑不受影响，因为真正的兜底本来就在应用侧。
		 */
		private boolean jsonMode = true;

		/**
		 * 可接受的最低自评置信度。
		 *
		 * <p>默认 0.0 = 不设门槛（任何 0~1 的值都算合格）。设成大于 0 的值时，
		 * 模型说"我不确定"（低 confidence）会被判为不合格并被要求重答 ——
		 * 这对"必须给出确定答案"的场景是必要的（例如给业务系统做字段抽取，
		 * 拿一个 0.2 置信度的结果还不如让人来处理）。
		 *
		 * <p>还有一个调试用途：把它设成 0.99 可以<b>确定性地触发校验失败</b>，
		 * 用来验证回灌重试链路是否真的在工作 —— 否则很难让线上模型恰好输出一个越界值。
		 */
		private double minConfidence = 0.0;

		public int getMaxAttempts() {
			return maxAttempts;
		}

		public void setMaxAttempts(int maxAttempts) {
			this.maxAttempts = maxAttempts;
		}

		public boolean isJsonMode() {
			return jsonMode;
		}

		public void setJsonMode(boolean jsonMode) {
			this.jsonMode = jsonMode;
		}

		public double getMinConfidence() {
			return minConfidence;
		}

		public void setMinConfidence(double minConfidence) {
			this.minConfidence = minConfidence;
		}
	}

	/**
	 * 重试策略。
	 *
	 * <p><b>核心约束（血泪教训）</b>：重试只允许发生在「模型还没吐出任何增量」之前。
	 * 一旦已经有增量发给前端了，再重发请求会让用户看到两段拼接的回答，
	 * 而模型侧还会因为记忆里留着上一段而行为错乱。
	 * 调用方（{@code ChatController}）负责用「是否已产出增量」这个开关把重试挡住，
	 * 这里只负责"怎么退避、退几次、哪些异常值得退"。
	 */
	public static class Retry {

		/**
		 * 最大<b>重试</b>次数（不含首次调用），所以最坏情况总调用次数 = maxAttempts + 1。
		 * 默认 2 → 最多 3 次调用。
		 *
		 * <p>为什么不设更大：重试次数的价值是"扛过瞬时抖动"，不是"掩盖持续故障"。
		 * 5xx 持续返回时，重试 10 次只是让用户多等 10 倍时间，还顺带把故障下游压得更死。
		 */
		private int maxAttempts = 2;

		/** 第一次重试前等待时长，后续按 multiplier 指数递增。 */
		private Duration initialBackoff = Duration.ofMillis(500);

		/** 退避倍数。0.5s → 1s → 2s …（再被 maxBackoff 截断） */
		private double multiplier = 2.0;

		/**
		 * 单次退避上限。
		 *
		 * <p>没有它的话，指数增长到第 5 次重试就是 8 秒起步，用户会以为页面卡死。
		 */
		private Duration maxBackoff = Duration.ofSeconds(5);

		/**
		 * 抖动系数（0~1）。默认 0.2 → 实际等待 = 计算值 × [0.8, 1.2]。
		 *
		 * <p>为什么必须有：同一时刻被打挂的上游，会让所有客户端在<b>同一个毫秒</b>同时重试，
		 * 形成一堵"重试墙"把刚恢复的下游再次打挂。加随机抖动把重试时刻摊开，
		 * 这是生产环境重试策略的标配（AWS 的 Exponential Backoff and Jitter 讲得很清楚）。
		 */
		private double jitter = 0.2;

		/**
		 * 值得重试的 HTTP 状态码。默认只认"等一会儿再来就可能成功"的那几个：
		 * <ul>
		 *   <li>429 —— 限流，重试前等一等确实有用</li>
		 *   <li>500 / 502 / 503 / 504 —— 服务端临时故障、网关抖动</li>
		 * </ul>
		 * <b>绝不包含 4xx</b>（401/403/404/422…）：这些是"请求本身有问题"，
		 * 重试 100 次结果一模一样，只会浪费配额并掩盖真正的配置错误
		 * （例如 API Key 没读到导致的 401 —— 重试只会让排查更困难）。
		 */
		private Set<Integer> retryableStatusCodes = new LinkedHashSet<>(Set.of(429, 500, 502, 503, 504));

		public int getMaxAttempts() {
			return maxAttempts;
		}

		public void setMaxAttempts(int maxAttempts) {
			this.maxAttempts = maxAttempts;
		}

		public Duration getInitialBackoff() {
			return initialBackoff;
		}

		public void setInitialBackoff(Duration initialBackoff) {
			this.initialBackoff = initialBackoff;
		}

		public double getMultiplier() {
			return multiplier;
		}

		public void setMultiplier(double multiplier) {
			this.multiplier = multiplier;
		}

		public Duration getMaxBackoff() {
			return maxBackoff;
		}

		public void setMaxBackoff(Duration maxBackoff) {
			this.maxBackoff = maxBackoff;
		}

		public double getJitter() {
			return jitter;
		}

		public void setJitter(double jitter) {
			this.jitter = jitter;
		}

		public Set<Integer> getRetryableStatusCodes() {
			return retryableStatusCodes;
		}

		public void setRetryableStatusCodes(Set<Integer> retryableStatusCodes) {
			this.retryableStatusCodes = retryableStatusCodes;
		}
	}

	/**
	 * 超时策略。
	 *
	 * <p><b>为什么是"首字超时 + 空闲超时"而不是一个总超时</b>：
	 * 流式对话的正常耗时本来就是几十秒（一个字一个字地吐），
	 * 拿"总耗时"当超时会<b>杀掉正常但话多的回答</b>；而完全不设超时，
	 * 模型侧挂起时页面会永远停在「正在思考…」——这是最糟的用户体验，因为用户不知道该等还是该刷新。
	 * 拆成两个正交的指标后：关心的是"多久没动静"，而不是"一共花了多久"。
	 *
	 * <p>空闲超时这一条之所以要单独存在：SSE 连接断开<b>不一定报错</b>。
	 * TCP 半开（NAT 超时、网线被拔、代理静默丢包）时，客户端只会一直读不到数据，
	 * 表现就是「卡住」而不是「报错」。只能靠超时把这个"静默失败"变成显式失败。
	 */
	public static class Timeout {

		/**
		 * 首个增量到达的最长等待（覆盖建连 + 模型排队 + 思考时间）。
		 *
		 * <p>这是重试的<b>触发条件</b>：只有首字前超时才有"整体重来一次"的意义。
		 */
		private Duration firstToken = Duration.ofSeconds(20);

		/**
		 * 相邻两个 chunk 之间的最大间隔。
		 *
		 * <p>取 30s 而不是 20s：模型在长回答中途"想一下"（例如准备输出一段代码）时，
		 * 出现十几秒静默是正常的，设太小会把正常回答拦腰截断，还会被误判成故障而触发重试。
		 */
		private Duration idle = Duration.ofSeconds(30);

		public Duration getFirstToken() {
			return firstToken;
		}

		public void setFirstToken(Duration firstToken) {
			this.firstToken = firstToken;
		}

		public Duration getIdle() {
			return idle;
		}

		public void setIdle(Duration idle) {
			this.idle = idle;
		}
	}

	/**
	 * Token 预算（学习点 2）。
	 *
	 * <p><b>要回答的问题只有一个</b>：这次请求发出去，会不会超窗口？
	 * <pre>
	 *   contextWindow × (1 - safetyMargin)  ≥  estimatedInputTokens + maxOutputTokens
	 * </pre>
	 * 左边是"这次真正能用的预算"，右边是"这次要花的额度"。
	 *
	 * <p><b>为什么必须在发请求之前算</b>：超窗口这件事的失败成本极不对称 ——
	 * 自己算出来只需一次本地计算（微秒级、0 成本）；等模型报错则要
	 * <b>先花一次往返的钱和几秒钟</b>，而且那时候提示词已经拼好、时间已经花掉。
	 * 更糟的是：有些供应商不报错，而是<b>静默截断</b>输入 ——
	 * 你会在"答案为什么变差了"上排查半天，根本想不到是上下文被悄悄砍掉了。
	 *
	 * <p><b>安全边际留多少</b>：默认 15%（资料给的经验区间是 10%–20%）。为什么不能是 0：
	 * <ul>
	 *   <li>Token 估算永远是<b>估算</b>——不同模型的分词器不同，没有一个通用算法能算准；</li>
	 *   <li>脚手架要额外占额度：system 提示词、消息角色包装、工具（function）定义、
	 *       多模态占位符，这些都不在你的正文里，但都要计费、都要占窗口；</li>
	 *   <li>供应商可能悄悄改默认值（例如给输出留的余量）。</li>
	 * </ul>
	 * 边际留小了会"偶发超窗"，留大了会"早早拒绝本来能过的请求"。
	 * 15% 是在本项目的实测数据上选的。
	 */
	public static class Budget {

		/**
		 * 是否启用预算校验。默认开。
		 *
		 * <p>做成开关有两个实际用途：一是排查"是不是预算校验把请求拦了"时可以一键排除；
		 * 二是老系统接入时先观察、后启用（先只记日志不拒绝）。
		 */
		private boolean enabled = true;

		/**
		 * 模型上下文窗口（输入 + 输出共享这一个窗口）。
		 *
		 * <p>DeepSeek 的窗口是 128K。这个值是<b>供应商能力</b>，不是我们能调的参数，
		 * 所以换模型必须跟着改 —— 也是把它放在配置里的原因。
		 */
		private int contextWindow = 128_000;

		/**
		 * 我们希望为「模型输出」预留的最大 token 数。
		 *
		 * <p><b>为什么必须显式留</b>：输入 + 输出共享同一个窗口。如果不给输出留位置，
		 * 一个刚好塞满窗口的输入会让模型没有空间回答 —— 表现为回答被从中间切断
		 * （本项目实测过"JSON 截断"那条：解析失败不是因为模型笨，是因为它没地方写了）。
		 *
		 * <p>默认 2048 比实测最长回答（494 tokens）大得多，是刻意留的余量：
		 * 结构化输出要吐完整 JSON、代码类回答会很长。
		 */
		private int maxOutputTokens = 2048;

		/**
		 * 安全边际比例，0~1。默认 0.15（即 15%）。
		 *
		 * <p>作用是从窗口里先"扣掉"一块，剩下的才是可用预算。理由见类注释：
		 * 估算必然有误差、脚手架要占额度、供应商行为可能变。资料给的经验区间就是 10%–20%。
		 */
		private double safetyMargin = 0.15;

		/**
		 * 是否允许在超预算时自动裁剪最旧的对话历史（而不是直接拒绝）。
		 *
		 * <p>默认允许：对话越聊越长是<b>常态</b>，一超限就报错会让用户体验很差
		 * （"聊到第 20 轮突然不能用"）。裁剪旧历史是"丢掉最不重要的部分、保住这次能答"，
		 * 比拒绝更合理。
		 *
		 * <p>但裁剪<b>不能</b>裁到"这一句提问本身都放不下"——那种情况必须拒绝，
		 * 因为再裁也没用（见 {@code TokenBudgetGuard}）。
		 */
		private boolean truncateHistory = true;

		/**
		 * 裁剪后至少要保留的历史消息条数。
		 *
		 * <p>为什么不裁到 0：对话记忆是"能用"的前提。把历史全丢掉虽然一定不超预算，
		 * 但模型会失忆 —— 用户会觉得"聊着聊着它就不认识我了"，
		 * 这比报一个明确的"上下文过长"错误更难排查。
		 */
		private int minHistoryMessages = 2;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public int getContextWindow() {
			return contextWindow;
		}

		public void setContextWindow(int contextWindow) {
			this.contextWindow = contextWindow;
		}

		public int getMaxOutputTokens() {
			return maxOutputTokens;
		}

		public void setMaxOutputTokens(int maxOutputTokens) {
			this.maxOutputTokens = maxOutputTokens;
		}

		public double getSafetyMargin() {
			return safetyMargin;
		}

		public void setSafetyMargin(double safetyMargin) {
			this.safetyMargin = safetyMargin;
		}

		public boolean isTruncateHistory() {
			return truncateHistory;
		}

		public void setTruncateHistory(boolean truncateHistory) {
			this.truncateHistory = truncateHistory;
		}

		public int getMinHistoryMessages() {
			return minHistoryMessages;
		}

		public void setMinHistoryMessages(int minHistoryMessages) {
			this.minHistoryMessages = minHistoryMessages;
		}

		/** 扣掉安全边际后真正可用的 token 预算。 */
		public int usableBudget() {
			double usable = contextWindow * (1.0 - clampMargin(safetyMargin));
			return Math.max(0, (int) usable);
		}

		/** 预留给输出的额度：既不能超过可用预算，也不能为负。 */
		public int reservedForOutput() {
			return Math.max(0, Math.min(maxOutputTokens, usableBudget()));
		}

		/** 留给输入的上限。允许为 0 —— 那说明配置本身矛盾（窗口比输出预留还小），由调用方报错。 */
		public int inputAllowance() {
			return usableBudget() - reservedForOutput();
		}

		private static double clampMargin(double margin) {
			if (Double.isNaN(margin) || margin < 0) {
				return 0;
			}
			return Math.min(margin, 0.9);
		}
	}

	/**
	 * Token 估算系数（学习点 2 的"怎么算"部分）。
	 *
	 * <p><b>为什么要有系数、而不是"字符数 ÷ 4"一个公式走天下</b>：
	 * 分词器（BPE）对字符的切分密度和字符集强相关。以本项目实测的 DeepSeek 为例：
	 * <ul>
	 *   <li><b>中文/日文等 CJK</b>：约 <b>0.61 token/字</b>（常用词被合并成整块，
	 *       所以一个字往往连一个 token 都不到）；</li>
	 *   <li><b>英文与代码</b>：约 <b>6.6 字符/token</b>（缩进、标点、常见词合并得很凶）。</li>
	 * </ul>
	 * 一个"字符 ÷ 4"的通式在英文上看着很准，但它有两个问题：混进中文会<b>低估近 2 倍</b>，
	 * 而在英文上又<b>高估 1.6 倍</b>。所以必须按字符类别分开算。
	 *
	 * <p><b>这里的系数是实测校准出来的，不是拍脑袋定的</b>。
	 * 校准方法（可复现）：对同一段文本做不同长度的扫描，用真实 usage 拟合出边际密度 ——
	 * 详见本项目的实测数据，代码见 {@code TokenCalibrationController}。
	 *
	 * <p><b>为什么最终取值略高于实测值</b>：估算宁可偏保守一侧。
	 * 低估的后果是超窗没被拦住（钱花了、可能被静默截断）；
	 * 高估的后果只是"历史被多裁掉一点"。两者代价不对称，所以留了约 15%~20% 的余量。
	 * 注意这只是<b>第二道</b>余量：{@link Budget#safetyMargin} 那里还有一道 15% 的窗口级边际。
	 */
	public static class Estimation {

		/**
		 * 每多少个 ASCII 字符算 1 个 token。
		 *
		 * <p>注意是"字符 / token"（越大越省），不是"token / 字符"。
		 *
		 * <p><b>实测值 6.6，这里取 5.5</b>（约留 20% 余量）。
		 * 换供应商必须重新校准 —— 这个数跟着分词器走，换个模型就可能完全不同。
		 */
		private double asciiCharsPerToken = 5.5;

		/**
		 * 每个 CJK 字符算几个 token。
		 *
		 * <p><b>实测值 0.609，这里取 0.75</b>（约留 23% 余量）。
		 */
		private double cjkTokensPerChar = 0.75;

		/**
		 * 其他字符（Emoji、俄文、阿拉伯文等）每个算几个 token。
		 *
		 * <p><b>这一项没有实测数据支撑</b>，保留 1.5 —— 因为校准样本里没有这类字符。
		 * 取 1.5 的理由是它们几乎必然被拆成多个 BPE 片段（一个 Emoji 常见 3 个 token 起步）。
		 * 换句话说：这是一个<b>明确标注为"未校准"</b>的系数，而不是假装算准了。
		 * 真要用到多语言场景，应当补一组样本再调。
		 */
		private double otherTokensPerChar = 1.5;

		/**
		 * 每条消息的固定开销（角色包装等）。
		 *
		 * <p>对话协议的每条消息都要包一层角色结构
		 * （形如 {@code <|im_start|>user ... <|im_end|>}），这部分不来自正文却占 token。
		 * 每条 4 是业内常用的经验值（OpenAI 的 token 计数指南就这么算）。
		 */
		private int tokensPerMessage = 4;

		/** 整个请求的固定开销（回复引导符等）。 */
		private int tokensPerRequest = 3;

		public double getAsciiCharsPerToken() {
			return asciiCharsPerToken;
		}

		public void setAsciiCharsPerToken(double asciiCharsPerToken) {
			this.asciiCharsPerToken = asciiCharsPerToken;
		}

		public double getCjkTokensPerChar() {
			return cjkTokensPerChar;
		}

		public void setCjkTokensPerChar(double cjkTokensPerChar) {
			this.cjkTokensPerChar = cjkTokensPerChar;
		}

		public double getOtherTokensPerChar() {
			return otherTokensPerChar;
		}

		public void setOtherTokensPerChar(double otherTokensPerChar) {
			this.otherTokensPerChar = otherTokensPerChar;
		}

		public int getTokensPerMessage() {
			return tokensPerMessage;
		}

		public void setTokensPerMessage(int tokensPerMessage) {
			this.tokensPerMessage = tokensPerMessage;
		}

		public int getTokensPerRequest() {
			return tokensPerRequest;
		}

		public void setTokensPerRequest(int tokensPerRequest) {
			this.tokensPerRequest = tokensPerRequest;
		}
	}

	/**
	 * 虚拟线程与异步执行（学习点 7）。
	 *
	 * <p>这一节要回答的问题：<b>非流式端点为什么必须改造，改造到哪一层才算数</b>。
	 * 见 {@code ai.agent.config.VirtualThreadConfig} 的类注释（那里写了完整的推理链）。
	 */
	public static class VirtualThreads {

		/**
		 * 是否给 Tomcat 的连接器换上虚拟线程执行器。
		 *
		 * <p>关掉它就回到"一个请求一个平台线程"，可以用来做同步 vs 虚拟线程的对照压测 ——
		 * 这个开关的全部价值就是让两种模式能在<b>同一个二进制、同一台机器</b>上对比，
		 * 否则对比数字会被"不同版本/不同机器"污染。
		 */
		private boolean enabled = true;

		/**
		 * 非流式调用的执行模式：{@code virtual} / {@code platform} / {@code auto}。
		 *
		 * <ul>
		 *   <li>{@code virtual} —— 交给 {@code Executors.newVirtualThreadPerTaskExecutor()} 执行（默认）；</li>
		 *   <li>{@code platform} —— 交给固定大小的平台线程池执行，用于对照；</li>
		 *   <li>{@code auto} —— 不切换线程，直接在容器线程上跑（保持改造前的行为）。</li>
		 * </ul>
		 */
		private String asyncExecutor = "virtual";

		/**
		 * {@code platform} 模式下的线程池大小。
		 *
		 * <p>默认 8 是刻意取的<b>小值</b>：它把"线程池大小 = 上游并发上限"这个约束
		 * 从"要 500 个并发才撞得到"变成"8 个并发就撞得到"，
		 * 于是阻塞式设计的问题在压测里几秒钟就能复现，而不用打满 200 个线程。
		 */
		private int platformPoolSize = 8;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getAsyncExecutor() {
			return asyncExecutor;
		}

		public void setAsyncExecutor(String asyncExecutor) {
			this.asyncExecutor = asyncExecutor;
		}

		public int getPlatformPoolSize() {
			return platformPoolSize;
		}

		public void setPlatformPoolSize(int platformPoolSize) {
			this.platformPoolSize = platformPoolSize;
		}
	}

	/**
	 * 压测替身配置：让"模型调用"变成一段可控的固定延迟。
	 *
	 * <p><b>为什么压测必须用替身，不能直接打真实模型</b>：
	 * <ol>
	 *   <li>真实模型每次调用要几秒、要花钱，做 200 并发的对比实验既慢又贵；</li>
	 *   <li>更致命的是<b>不可重复</b>——上游抖动会污染对比数字，
	 *       而"同步 vs 虚拟线程"这种对比最需要的恰恰是"两次实验除了线程模型之外完全一样"。</li>
	 * </ol>
	 *
	 * <p>让替身纯粹睡同样长的时间，就把变量收敛到只剩一个：<b>等待期间那个线程还能不能干别的活</b>。
	 * 这正是虚拟线程要证明的东西 —— 而且它证明的不是"虚拟线程更快"，
	 * 是"虚拟线程让等待不再占用宝贵资源"（本项目的实测结论）。
	 */
	public static class Sampling {

		/**
		 * 替身延迟。大于 0 时启用替身（不调用真实模型）。
		 *
		 * <p>默认 0 = 关闭，所以正常启动、正常对话完全不受影响 ——
		 * 替身只在压测时通过启动参数打开。
		 */
		private Duration latency = Duration.ZERO;

		/** 替身返回的固定文本，避免压测时产生大响应体干扰网络测量。 */
		private String response = "采样响应";

		public Duration getLatency() {
			return latency;
		}

		public void setLatency(Duration latency) {
			this.latency = latency;
		}

		public String getResponse() {
			return response;
		}

		public void setResponse(String response) {
			this.response = response;
		}

		public boolean isStandInActive() {
			return latency != null && !latency.isZero() && !latency.isNegative();
		}
	}

}
