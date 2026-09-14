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

}
