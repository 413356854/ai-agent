package ai.agent.entity.alert;

import ai.agent.service.alert.AlertRule;
import java.time.Instant;
import java.util.List;

/**
 * <b>一条告警当前的状态</b>（阶段四第 5 件）。
 *
 * <h2>为什么状态是"当前值"而不是"一串历史事件"</h2>
 * 这一条决定了整个告警存储的形状，值得先讲清。
 * 直觉会做成"每次评估都追加一条事件"（append-only），像日志那样 ——
 * 而这在实践中会立刻撞上一个问题：<b>一个持续存在的异常会淹掉所有别的异常</b>。
 * 「价格没配」在每次评估时都成立，一天评估 288 次（每 5 分钟一次）就是 288 条同样的记录，
 * 而真正的新问题（今天下午开始有检索查不到了）会被埋在中间 ——
 * 于是这个列表变得没人看，而"没人看的告警"与"没有告警"是等价的。
 *
 * <p>所以这里的模型是：<b>一条判据的一个主体一条记录</b>（{@link #fingerprint} 唯一），
 * 持续成立时只更新 {@link #lastSeenAt} 与 {@link #occurrences}。
 * 于是列表长度由"有几种异常"决定，而不由"异常存在了多久"决定。
 *
 * <h2>★ 但"不追加"带来一个必须补回来的东西</h2>
 * 那就是<b>它什么时候开始的、什么时候结束的</b>。只留当前状态的话，
 * 事故复盘时那句"这个问题存在了多久"就答不出来。
 * 所以这里两个时间都留：
 * <ul>
 *   <li>{@link #firstSeenAt} —— 第一次发现它；</li>
 *   <li>{@link #lastSeenAt} —— 最后一次发现它（持续成立时会一直往后走）；</li>
 *   <li>{@link #resolved} —— 已经恢复。⚠️ <b>恢复是改成 resolved，而不是删掉</b>：
 *       删掉的话"昨天响过的那条告警"在今天完全查不到，
 *       而"它到底响过没有"恰恰是复盘时要问的第一句话。
 *       这与 Prompt 版本管理那条"回滚也是新增一版"是同一条纪律：
 *       <b>会改写历史的操作，事后无法解释</b>。</li>
 * </ul>
 *
 * @param fingerprint 去重键：{@code ruleCode + "|" + subject}。同一判据的不同主体
 *           各占一条（"哪个模型没配价"比"有模型没配价"有用得多）
 * @param ruleCode 判据代号（{@link AlertRule#code()}）
 * @param subject 这条告警说的是谁（模型名 / 检索 / 灰度版本 / 空串表示"没有主体"）
 * @param severity 严重度
 * @param phase 判据属于哪个阶段
 * @param state 当前状态
 * @param firstSeenAt 第一次发现
 * @param lastSeenAt 最后一次发现（状态是 FIRING 时它≈现在）
 * @param occurrences 一共被发现了多少次（**持续成立会一直涨** ——
 *           它是"这个异常是持续的还是偶尔的"的唯一线索）
 * @param resolvedAt 恢复时间（没恢复时为 null）
 * @param acknowledgedAt 人确认过的时间（null = 还没人看过）
 * @param acknowledgedBy 谁确认的
 * @param observed 观测值（人能读，例如 {@code "3/60"}）
 * @param threshold 判据阈值与形态（例如 {@code "≥ 3 次连续"}）
 * @param why 为什么这个阈值是这个数
 * @param action 该做什么（来自判据目录，**不在告警里重写一遍**）
 * @param windowLabel 判定时的窗口（没有它，告警里的数字不可复现）
 */
public record AlertEvent(String fingerprint, String ruleCode, String subject, AlertRule.Severity severity,
		AlertRule.AlertPhase phase, State state, Instant firstSeenAt, Instant lastSeenAt, int occurrences,
		Instant resolvedAt, Instant acknowledgedAt, String acknowledgedBy, String observed, String threshold,
		String why, String action, String windowLabel) {

	/**
	 * 一条告警的状态。
	 *
	 * <h2>为什么 ACKNOWLEDGED 与 OPEN 是两个状态，而不是一个布尔字段</h2>
	 * 因为"<b>没人看过</b>"与"<b>看过、知道、暂时不管</b>"需要被分开：
	 * 混成一个的话，值班的人确认完一轮之后，列表看起来与"完全没人管"一模一样 ——
	 * 于是下一个人会从头再查一遍，而第三个人就会开始忽略这个列表。
	 * 分开了之后，"还有几条没人看过"是一个能直接读出来的数字。
	 */
	public enum State {

		/** 没人看过。 */
		OPEN("未处理"),

		/** 有人看过了（看过≠已解决：也许它本来就是个待办）。 */
		ACKNOWLEDGED("已确认"),

		/** 已经恢复。 */
		RESOLVED("已恢复");

		private final String label;

		State(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		/** 还在响的（前两种）。 */
		public boolean firing() {
			return this != RESOLVED;
		}

	}

	/** 去重键。判据代号与主体一起构成 —— 两者各自都会重复，合起来才是唯一的。 */
	public static String fingerprintOf(AlertRule rule, String subject) {
		return rule.code() + "|" + (subject == null ? "" : subject);
	}

	/** 这条告警是不是"还需要人去动它"。 */
	public boolean needsAttention() {
		return state == State.OPEN;
	}

	/**
	 * 第一次发现到现在的时长（秒）。用于界面上的"这条响了多久了" ——
	 * 事故复盘里第一句被问的常常就是它。
	 */
	public long firingSeconds(Instant now) {
		if (firstSeenAt == null) {
			return 0;
		}
		Instant end = resolvedAt == null ? (now == null ? Instant.now() : now) : resolvedAt;
		return Math.max(0, end.getEpochSecond() - firstSeenAt.getEpochSecond());
	}

	/** 明细行的可读描述（日志与界面上都用它，两处不各写一份）。 */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append('[').append(severity.label()).append("] ").append(ruleCode);
		if (subject != null && !subject.isBlank()) {
			sb.append('(').append(subject).append(')');
		}
		sb.append(" 观测=").append(observed)
				.append(" 判据=").append(threshold)
				.append(" 窗口=").append(windowLabel);
		return sb.toString();
	}

	/** 不变的元信息（构造事件时用不着每次都抄一遍）。 */
	public record RuleRef(String ruleCode, String title, AlertRule.Severity severity, AlertRule.AlertPhase phase,
			String action) {

		public static RuleRef of(AlertRule rule) {
			return new RuleRef(rule.code(), rule.title(), rule.severity(), rule.phase(), rule.action());
		}

	}

	/** 只是为了让 {@link #action} 那一段注释能被引用（避免 IDE 里"未使用"的误报）。 */
	static List<String> documentedFields() {
		return List.of("action", "why", "threshold", "observed");
	}

}
