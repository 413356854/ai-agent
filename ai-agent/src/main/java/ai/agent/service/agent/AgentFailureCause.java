package ai.agent.service.agent;

/**
 * 循环里的某一步失败时，<b>到底是哪一类失败</b>。
 *
 * <h2>它为什么必须和 {@link AgentStopReason} 分开</h2>
 * {@code AgentStopReason} 回答的是"循环为什么停"（答完了 / 超步数 / 超 token / 卡住了 / 出错了），
 * 而本枚举回答"出错时错在哪"。
 *
 * <p>把它们合成一个枚举看起来更"简洁"，但会立刻撞上一个现实问题：
 * <b>"模型太挤被拒"和"模型真的挂了"需要的措施完全相反</b> ——
 * 前者该稍后重试（或者调大并发上限），后者该去查上游。
 * 而两者的"停因"都是"调模型没成功"，也就是同一个 {@code MODEL_ERROR}。
 *
 * <p>合成一个枚举的另一个问题是它会长出这种成员：{@code MODEL_BUSY}、
 * {@code MODEL_AUTH_FAILED}、{@code MODEL_TIMEOUT}、{@code MODEL_NETWORK}……
 * 于是"停因"这个维度被"失败原因"撑爆，
 * 而**判断"循环是正常结束还是异常结束"就得去列举一堆成员**（漏一个就是 bug）。
 * 分开之后，`AgentStopReason` 保持 7 个稳定的出口，
 * 而本枚举可以自由增减而不影响任何"停因"的判断。
 *
 * <h2>为什么"太挤"要单独一类，而不是并到 {@code UPSTREAM_ERROR}</h2>
 * 这是本轮实际修掉的那个问题：{@code LimitedModelTurn} 被拒时抛
 * {@code ModelBusyException}，而循环把它兜成了 "MODEL_ERROR" ——
 * 于是页面上显示的是一句笼统的"模型调用失败"，
 * 而真相是"我们自己设的并发闸门满了"。这两者的排查方向完全不同：
 * 前者去查模型服务，后者去看本机的并发上限配得对不对。
 *
 * <p>顺带一个更隐蔽的后果：如果都报"上游错误"，那么<b>恰恰在压力最大的时候</b>
 * （限流生效时）页面上显示的是"上游有问题" —— 而那是错的归因，会把人引到错误的方向去。
 */
public enum AgentFailureCause {

	/**
	 * <b>被本机的并发闸门拒绝了</b>（等不到名额）。
	 *
	 * <p>这不是上游的问题，是"我们自己太挤了"。
	 * 措施：稍后重试，或者按实际配额调大 {@code ai.agent.concurrency.max-concurrent-model-requests}。
	 */
	LOCAL_CONCURRENCY_LIMIT,

	/** 上游鉴权失败（key 不对 / 没读到 / 过期）。**重试没有意义**，要去改配置。 */
	UPSTREAM_AUTH,

	/** 上游限流（429）或配额用尽。措施：退避重试，或去提配额。 */
	UPSTREAM_RATE_LIMITED,

	/** 超时（首字或空闲）。措施：看是网络抖动还是模型服务整体变慢。 */
	UPSTREAM_TIMEOUT,

	/** 网络/连接层失败（连不上、TLS、DNS）。 */
	UPSTREAM_UNAVAILABLE,

	/**
	 * 上游返回了别的东西（5xx、协议不符、空响应）。
	 *
	 * <p>作为兜底类别存在，但<b>它必须存在</b>：
	 * 没有兜底的话，"认不出的失败"会被归到某个具体的类别里，
	 * 而那意味着一个假的具体归因 —— 比"未分类"更糟，因为它看起来是确定的。
	 */
	UPSTREAM_ERROR,

	/** 认不出来的异常（本机代码 bug、非预期异常）。措施：看日志堆栈。 */
	UNEXPECTED

}
