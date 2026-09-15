package ai.agent.agent;

import ai.agent.agent.limit.LimitedModelTurn;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 把"调模型时抛出来的那个异常"翻译成 {@link AgentFailureCause}。
 *
 * <h2>为什么要有这个翻译层（而不是在循环里写 instanceof 链）</h2>
 * 三个理由，每一个都很实际：
 * <ol>
 *   <li><b>它会被测试</b>：异常分类是纯函数，可以拿一堆构造出来的异常直接断言
 *       "这个应该被判成鉴权""那个应该被判成超时"。
 *       写在循环里的话，要验证它就得真的制造出那些异常穿过整个循环 ——
 *       而"制造一个 429"比"构造一个异常对象"贵得多，于是分类就不会被充分测试；</li>
 *   <li><b>它依赖框架的异常类型</b>：Spring AI / Reactor / JDK 的异常类名会随版本变
 *       （项目里已经踩过两次：{@code TransientAiException} 不存在、
 *       {@code ACCEPT_STRING_AS_NUMBER} 被删）。把这些耦合收在一个文件里，
 *       升级时改一处；</li>
 *   <li><b>分类的判据值得单独写清楚</b>：为什么先判"太挤"再判鉴权、为什么用消息文本兜底、
 *       为什么最后一定要有 {@code UNEXPECTED} —— 这些判断放在几十行的循环中间没人会读。</li>
 * </ol>
 *
 * <h2>判据的顺序是有讲究的</h2>
 * <ol>
 *   <li><b>先认自己的异常</b>（{@link LimitedModelTurn.ModelBusyException}）——
 *       它一定是我们自己抛的，不需要去看消息文本，也不会被上游的异常类型误判；</li>
 *   <li><b>再按异常类型判</b>（连接失败、超时）。类型比消息可靠：消息的措辞会变，
 *       类型不会；</li>
 *   <li><b>最后才按消息文本兜底</b>。这一步是<b>必要的妥协</b>：
 *       上游返回 401/429 时，很多 SDK 只把它包成一个泛化的异常，
 *       类型上看不出区别，只有消息里有 "401" / "rate limit" 这些字眼。
 *       所以这里对文本做匹配 —— 但**只作为兜底**，而不是主要判据。</li>
 * </ol>
 *
 * <p>⚠️ 一个必须说清的局限：<b>按文本匹配是语言/措辞相关的</b>。
 * 上游换个说法（或换成中文错误）就会落到 {@code UPSTREAM_ERROR}。
 * 这个失效方向是**可接受的** —— 它退化成"未细分的上游错误"，而不是一个错误的细分。
 * 这也是为什么兜底类别必须存在：宁可"没细分"，也不要"分错了"。
 */
public final class ModelFailureClassifier {

	private ModelFailureClassifier() {
	}

	/**
	 * 分类。
	 *
	 * @param error 模型调用抛出的异常（可能被 Reactor / SDK 层层包装）
	 * @return 失败原因；永远不返回 null
	 */
	public static AgentFailureCause classify(Throwable error) {
		if (error == null) {
			return AgentFailureCause.UNEXPECTED;
		}

		// ---- ① 先认自己的异常：本机并发闸门拒绝 ----
		// 这一步放在最前，因为它与上游无关，却最容易被后面的文本匹配误伤
		// （它的消息里也可能出现"限制"之类的字眼）
		if (contains(error, LimitedModelTurn.ModelBusyException.class)) {
			return AgentFailureCause.LOCAL_CONCURRENCY_LIMIT;
		}

		// ---- ② 按异常类型判（比消息可靠） ----
		if (contains(error, SocketTimeoutException.class)
				|| contains(error, java.util.concurrent.TimeoutException.class)
				|| contains(error, java.net.http.HttpTimeoutException.class)) {
			return AgentFailureCause.UPSTREAM_TIMEOUT;
		}
		if (contains(error, ConnectException.class) || contains(error, UnknownHostException.class)) {
			return AgentFailureCause.UPSTREAM_UNAVAILABLE;
		}

		// ---- ③ 按消息文本兜底 ----
		String message = allMessages(error).toLowerCase(java.util.Locale.ROOT);
		if (message.contains("401") || message.contains("authentication")
				|| message.contains("unauthorized") || message.contains("invalid api key")
				|| message.contains("api key")) {
			return AgentFailureCause.UPSTREAM_AUTH;
		}
		if (message.contains("429") || message.contains("rate limit") || message.contains("too many requests")
				|| message.contains("quota") || message.contains("insufficient_quota")) {
			return AgentFailureCause.UPSTREAM_RATE_LIMITED;
		}
		if (message.contains("timeout") || message.contains("timed out") || message.contains("read timed out")) {
			return AgentFailureCause.UPSTREAM_TIMEOUT;
		}
		if (message.contains("connection refused") || message.contains("connection reset")
				|| message.contains("unknown host") || message.contains("no route")) {
			return AgentFailureCause.UPSTREAM_UNAVAILABLE;
		}
		if (message.contains("500") || message.contains("502") || message.contains("503")
				|| message.contains("504") || message.contains("internal server error")
				|| message.contains("service unavailable")) {
			return AgentFailureCause.UPSTREAM_ERROR;
		}

		// ---- ④ 兜底：认不出来就诚实地说认不出来 ----
		// 【为什么不把最后这类硬塞进 UPSTREAM_ERROR】
		// 那会给一个"具体的假归因"。而"未分类"至少是诚实的，
		// 而且它本身就是一个信号：说明我们的分类需要补一种情况
		return AgentFailureCause.UNEXPECTED;
	}

	/**
	 * 给页面/日志用的一句话说明。
	 *
	 * <p>每种原因都要有<b>下一步动作</b>，而不只是"这是什么" ——
	 * 这与工具回填是同一条原则：光说"失败了"没人知道该做什么。
	 *
	 * <h2>⚠️ 这里的字符串里不许出现 markdown 星号（踩过两次，改之前先读完）</h2>
	 * 它在界面上有<b>一个纯文本消费方</b>：{@code AgentPanel} 的失败详情里那句
	 * {@code <Alert description={data.run.failureAdvice}>} —— 它的直接子内容是**纯文本**，
	 * 于是 {@code **重试没有意义**} 会在页面上原样印出两个星号。
	 *
	 * <p>第一版 {@code UPSTREAM_AUTH} 那条写的正是 {@code **重试没有意义**}。
	 * 同一个坑在成本看板的 {@code notes} 里已经咬过一次（那次是
	 * {@code CostDashboardView}，见那里的注释），修那一处时**没有顺手全局搜一遍**，
	 * 于是它以另一种形状又留在了这里 —— 这是"同一类错误会换一个地方复发"的典型。
	 *
	 * <p>处理办法与那次一样：<b>源头就不写星号</b>，改用中文书名号与破折号表达强调。
	 * <b>不要</b>在界面上过滤星号 —— 那样 JSON 接口的读者会拿到被改过的文本。
	 */
	public static String advice(AgentFailureCause cause) {
		return switch (cause) {
			case LOCAL_CONCURRENCY_LIMIT -> "本机并发闸门满了（这是我们自己的限制，不是上游的问题）。"
					+ "稍后重试即可；如果经常出现，按实际配额调大 ai.agent.concurrency.max-concurrent-model-requests。";
			// 【强调只能用书名号/破折号，不能用星号】理由见方法注释：
			// 这句话直接渲染在页面的纯文本里，星号会原样显示出来
			case UPSTREAM_AUTH -> "上游鉴权失败 —— 「重试没有意义」，请检查 API Key 是否被当前进程读到"
					+ "（注意环境变量是进程启动时的快照，改完要重启）。";
			case UPSTREAM_RATE_LIMITED -> "上游限流或配额用尽。稍后重试，或检查账号配额。";
			case UPSTREAM_TIMEOUT -> "上游超时。先看是这一次的网络抖动，还是模型服务整体变慢。";
			case UPSTREAM_UNAVAILABLE -> "连不上上游（网络/域名/端口）。检查 base-url 与出网是否正常。";
			case UPSTREAM_ERROR -> "上游返回了错误。看日志里的状态码与响应体。";
			case UNEXPECTED -> "没能归类的异常 —— 这是一个信号：分类逻辑需要补一种情况。请直接看日志堆栈。";
		};
	}

	/** 在异常链里找目标类型（Reactor/SDK 会把原始异常包好几层）。 */
	private static boolean contains(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) {
				return true;
			}
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
		}
		return false;
	}

	/** 把整条异常链的消息拼起来 —— 关键的判据常常在被包装的那一层里。 */
	private static String allMessages(Throwable error) {
		StringBuilder sb = new StringBuilder();
		Throwable current = error;
		while (current != null) {
			if (current.getMessage() != null) {
				sb.append(current.getMessage()).append(' ');
			}
			sb.append(current.getClass().getSimpleName()).append(' ');
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
		}
		return sb.toString();
	}

}
