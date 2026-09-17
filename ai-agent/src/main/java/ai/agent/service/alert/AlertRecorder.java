package ai.agent.service.alert;

import ai.agent.entity.alert.AlertEvent;
import ai.agent.entity.alert.RuntimeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>跑一轮告警评估，并把结论落成状态变化 + 通知</b>（阶段四第 5 件）。
 *
 * <h2>这一件补的是验收口径里最容易被漏掉的半句话</h2>
 * 路线文档对阶段四验收写的是「成本异常<b>有人能发现</b>」。在这一件之前，
 * "能发现"只做到了一半：成本看板能<b>看见</b>异常（{@code notes} 里那几条 ⚠️），
 * 但"看见"的前提是<b>有人打开那一页</b>。它的失败方式恰好是最安静的一种 ——
 * <b>没有人被通知</b>。
 *
 * <p>所以这里做三件事，顺序很重要：
 * <ol>
 *   <li><b>采集</b>：拿一份快照（判据的全部输入，见 {@link RuntimeSnapshot}）；</li>
 *   <li><b>判定</b>：{@link AlertEvaluator}（纯函数，可单独测）；</li>
 *   <li><b>落地</b>：把结论变成 {@code alert_event} 的状态变化，并<b>在状态真的变化时</b>通知。</li>
 * </ol>
 *
 * <h2>★ 通知的时机：状态变化时，不是"每次评估都通报"</h2>
 * 这是整个告警系统里最容易做错的一处。一个持续存在的异常会被评估很多次，
 * 如果每次都通报，值班的人一天会收到几百条一样的话 —— 而后果不是"他看烦了"，
 * 是<b>他开始不看</b>，于是真正的新问题也被淹没。
 *
 * <p>所以判据是：
 * <ul>
 *   <li>从"没响"变成"响" → 通报一次（这是新问题）；</li>
 *   <li>一直响着 → <b>不通报</b>（只更新计数与时间，"它还在响多久了"在页面上能看）；</li>
 *   <li>从"响"变成"不响" → 通报一次（恢复也是一条消息，而且要能对上此前那条）；</li>
 *   <li><b>压根没评估</b>（取数失败 / 构建阶段的判据）→ <b>什么都不做</b>。
 *       见下面那段注释，它是本类里最要紧的一个判断。</li>
 * </ul>
 *
 * <h2>⚠️ 取数失败绝不能让"正在响的告警"变成已恢复</h2>
 * 如果实现成"这一轮没看到它响，就把它标成恢复"，那么一次数据库抖动、
 * 一次查询超时，都会把所有告警刷成"已恢复" —— 而<b>故障看起来自己好了</b>
 * 是所有观测系统里最坏的假象：它会把正在排查的人直接劝退。
 * 所以"没评估"与"评估了、没响"在这里是两条完全不同的路径
 * （{@link AlertEvaluator.Outcome.Kind#SKIPPED} 与 {@link AlertEvaluator.Outcome.Kind#QUIET}），
 * 而只有后者能触发恢复。
 *
 * <h2>为什么通知的默认实现只是"打一条 WARN 日志"</h2>
 * 因为一个真实的通知通道（webhook / 邮件 / 钉钉）需要凭据、需要重试策略、
 * 需要"发了但没收到怎么办" —— 那些是运维接入的事，不是这个项目的核心命题。
 * 但**把通知点显式留在一个方法里**很重要：它让"接到哪里去"变成一个明确的接线动作，
 * 而不是散落在各处的 {@code log.warn}。真要接 webhook 时，改 {@link #notify} 一个方法即可。
 */
@Service
public class AlertRecorder {

	private static final Logger log = LoggerFactory.getLogger(AlertRecorder.class);

	/** 默认窗口：与成本看板一致（7 天）。 */
	public static final int DEFAULT_DAYS = 7;

	private final AlertSnapshotQuery query;

	private final AlertStore store;

	public AlertRecorder(AlertSnapshotQuery query, AlertStore store) {
		this.query = query;
		this.store = store;
	}

	/**
	 * 跑一轮评估（会写库）。
	 *
	 * @param days 时间窗（天）
	 * @return 这一轮做了什么（新响的 / 恢复的 / 还在响的）—— 端点把它交给界面
	 */
	public Sweep evaluate(int days) {
		RuntimeSnapshot snapshot = query.collect(days);
		return apply(snapshot);
	}

	/**
	 * 用一份现成的快照跑一轮（会写库）。
	 *
	 * <p>拆出来是为了让测试可以喂一份<b>手造</b>的快照走完整条落库与通知路径 ——
	 * 造数据（真的跑出一次对账不一致）要难得多，而那条路径恰恰是最需要测的。
	 */
	public Sweep apply(RuntimeSnapshot snapshot) {
		List<AlertEvaluator.Outcome> outcomes = AlertEvaluator.evaluate(snapshot,
				AlertRule.AlertPhase.OBSERVE);
		Instant now = Instant.now();
		List<AlertEvent> fired = new ArrayList<>();
		List<AlertEvent> resolved = new ArrayList<>();
		List<AlertEvent> ongoing = new ArrayList<>();

		Map<String, AlertEvaluator.Outcome> byFingerprint = AlertEvaluator.byFingerprint(outcomes);
		String windowLabel = snapshot.windowLabel();

		// ---- 1. 这一轮判定为"响"的：记一次（已在响的会更新计数，不再通报） ----
		for (AlertEvaluator.Outcome outcome : outcomes) {
			if (outcome.kind() != AlertEvaluator.Outcome.Kind.FIRING) {
				continue;
			}
			String fingerprint = outcome.fingerprint().orElseThrow();
			boolean wasFiring = store.find(fingerprint)
					.map(event -> event.state().firing())
					.orElse(false);
			AlertEvent event = store.recordFiring(outcome.rule(), outcome.subject(), outcome.verdict(),
					windowLabel, now);
			if (wasFiring) {
				ongoing.add(event);
			}
			else {
				fired.add(event);
				// 【只在"从没响变成响"时通报】—— 见类注释
				notify(event, true);
			}
		}

		// ---- 2. 这一轮"评估了、没响"的：把已经在响的那些标成恢复 ----
		// 注意判据是"这一轮的指纹集合里没有它"，而不是"它不在库里的所有行里" ——
		// 后者会把构建阶段的门禁（本轮压根没评估）也算成"已经好了"
		for (AlertEvaluator.Outcome outcome : outcomes) {
			if (outcome.kind() != AlertEvaluator.Outcome.Kind.QUIET) {
				continue;
			}
			for (AlertEvent event : store.firing()) {
				if (!event.ruleCode().equals(outcome.rule().code())) {
					continue;
				}
				if (byFingerprint.containsKey(event.fingerprint())) {
					continue; // 这一轮它响着（多主体判据的另一个主体）
				}
				store.recordResolved(event.fingerprint(), now).ifPresent(back -> {
					resolved.add(back);
					notify(back, false);
				});
			}
		}

		Sweep sweep = new Sweep(snapshot, fired, resolved, ongoing, store.openCount(), Instant.now());
		log.info("告警评估完成：窗口 {} 新响 {} 条 / 恢复 {} 条 / 仍在响 {} 条 / 未处理合计 {}",
				windowLabel, fired.size(), resolved.size(), ongoing.size(), sweep.openCount());
		return sweep;
	}

	/**
	 * 通知（**唯一的通知点**）。
	 *
	 * <p>默认实现是打一条日志：<b>WARN 给"响"、INFO 给"恢复"</b>。
	 * 恢复刻意不是 WARN —— 一条"问题恢复了"的警告会让值班的人在半夜爬起来看，
	 * 而那正是他五分钟前刚处理完的那件事。
	 *
	 * @param event 事件
	 * @param firing true = 新响；false = 恢复
	 */
	protected void notify(AlertEvent event, boolean firing) {
		if (firing) {
			log.warn("★ 告警响起 [{}] {}｜{}｜处置：{}", event.severity().label(), event.ruleCode(),
					event.describe(), event.action().replace('\n', ' '));
		}
		else {
			log.info("✓ 告警恢复 {}｜它从 {} 响到 {}（共 {} 次评估）", event.fingerprint(), event.firstSeenAt(),
					event.resolvedAt(), event.occurrences());
		}
	}

	/**
	 * 一轮评估的产出。
	 *
	 * @param snapshot 这一轮用的快照（界面把"判据看到的数字"与"结论"并排显示 ——
	 *            没有它，告警里的数字就成了不可复现的孤立值）
	 * @param fired 这一轮**新**响的
	 * @param resolved 这一轮恢复的
	 * @param ongoing 这一轮仍然响着的（**没有重复通报**）
	 * @param openCount 未处理的条数（界面顶部那个红点）
	 * @param finishedAt 这一轮结束的时刻
	 */
	public record Sweep(RuntimeSnapshot snapshot, List<AlertEvent> fired, List<AlertEvent> resolved,
			List<AlertEvent> ongoing, long openCount, Instant finishedAt) {

		public Sweep {
			fired = fired == null ? List.of() : List.copyOf(fired);
			resolved = resolved == null ? List.of() : List.copyOf(resolved);
			ongoing = ongoing == null ? List.of() : List.copyOf(ongoing);
		}

		/** 这一轮一共判定出多少条"响着"的（含已经在响的）。 */
		public int firingTotal() {
			return fired.size() + ongoing.size();
		}

	}

}
