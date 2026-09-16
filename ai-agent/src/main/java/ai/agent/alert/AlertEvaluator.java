package ai.agent.alert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>把一份快照喂给全部判据，得到"这一轮要报哪些、哪些已经好了"</b>（阶段四第 5 件）。
 *
 * <h2>为什么它是纯函数</h2>
 * 它<b>不写库、不发通知、不看时间</b>（时间由调用方作为参数传进来）。
 * 这样做的直接好处是：整个"什么算异常 + 告警怎么变化"的逻辑
 * 可以用手造的快照在毫秒级测完，而不需要起容器、造数据、等定时器。
 *
 * <p>这一条不是洁癖，它来自本项目已经吃过的一次亏：判据如果只能通过
 * "跑一次完整链路"来验证，那么判据错了的时候，你分不清是
 * <b>判据错了</b>还是<b>数据不对</b> —— 而那两种情况要改的地方正好相反。
 * 阶段四第 4 件（回归门禁）把方向判据拆出去单独测，也是同一个理由。
 *
 * <h2>★ 两种"没响"必须分开</h2>
 * 一次评估里，一条判据没响有两种完全不同的原因，而它们混在一起时
 * 会有很具体的后果（见 {@link Outcome#skipped()}）：
 * <ul>
 *   <li>{@link Outcome.Kind#FIRING} —— 违反了；</li>
 *   <li>{@link Outcome.Kind#QUIET} —— 评估了、没违反。**这一条要能把已经响着的告警标成恢复**；</li>
 *   <li>{@link Outcome.Kind#SKIPPED} —— <b>压根没评估</b>（判据属于构建阶段，
 *       或者取数失败）。绝不能把它当成 QUIET —— 那会在取数失败的一瞬间
 *       把所有正在响的告警<b>标成"已恢复"</b>，而"故障看起来自己好了"
 *       是所有观测系统里最坏的一种假象。</li>
 * </ul>
 */
public final class AlertEvaluator {

	private AlertEvaluator() {
	}

	/**
	 * 跑一轮评估。
	 *
	 * @param snapshot 本次快照
	 * @param phase 这一轮只评估哪个阶段（构建阶段的门禁不在运行时评估，见 {@link AlertRule}）
	 */
	public static List<Outcome> evaluate(RuntimeSnapshot snapshot, AlertRule.AlertPhase phase) {
		List<Outcome> outcomes = new ArrayList<>();
		for (AlertRule rule : AlertRule.values()) {
			if (rule.phase() != phase) {
				outcomes.add(Outcome.skipped(rule, "这条判据属于「" + rule.phase().label() + "」，本轮不评估 —— "
						+ "它的执行者是 " + rule.executedBy()));
				continue;
			}
			outcomes.addAll(judge(rule, snapshot));
		}
		return List.copyOf(outcomes);
	}

	/**
	 * 单独评估一条判据（多主体判据会被展开成多个结果）。
	 *
	 * <h2>为什么有的判据会产出多个结果</h2>
	 * "有模型没有价格"这句话的真正有用的形态是<b>"哪个模型没有价格"</b> ——
	 * 一个主体一条告警，值班的人才能一眼看出要补哪几条配置。
	 * 合成一条（"有 5 个模型没价"）在界面上占一行，看起来很整洁，
	 * 但那行字数不解决问题，而点开它还得再查一次。
	 *
	 * <p>反过来，"对账对不上"就只有一个主体（它说的是一次汇总的差），
	 * 所以它只产出一个结果。**主体的粒度由判据自己决定**，不强行统一。
	 */
	public static List<Outcome> judge(AlertRule rule, RuntimeSnapshot snapshot) {
		if (rule == AlertRule.UNPRICED_MODELS) {
			// 多主体判据：一条判据、每个模型一条告警
			List<Outcome> outcomes = new ArrayList<>();
			AlertRule.Verdict verdict = rule.judge(snapshot);
			if (!verdict.breached()) {
				return List.of(Outcome.quiet(rule));
			}
			for (String model : snapshot.unpricedModels()) {
				// 每个主体一条，但"为什么"沿用判据给出的那一句 ——
				// 不在这里另写一份说明：说明改了的时候，两处会分叉
				outcomes.add(Outcome.fired(rule, model, AlertRule.Verdict.of(model,
						"每个出现在窗口内的模型都要有匹配的价格规则", verdict.why())));
			}
			return List.copyOf(outcomes);
		}
		AlertRule.Verdict verdict = rule.judge(snapshot);
		if (verdict.breached()) {
			List<Outcome> single = new ArrayList<>(1);
			single.add(Outcome.fired(rule, subjectOf(rule, snapshot), verdict));
			return List.copyOf(single);
		}
		List<Outcome> quiet = new ArrayList<>(1);
		quiet.add(Outcome.quiet(rule));
		return List.copyOf(quiet);
	}

	/**
	 * 单主体判据的"说的是谁"。
	 *
	 * <p>主体的取值直接进 {@link AlertEvent#fingerprint()}，所以它必须
	 * <b>稳定、可读、且能唯一区分</b>：
	 * <ul>
	 *   <li>灰度那两条用"版本 vN"—— 两次实验（v4 与 v5）是不同的主体，
	 *       所以第一轮实验的结论不会被第二轮覆盖掉；</li>
	 *   <li>其余的用一个把窗口外的含义也说清的固定词（例如"窗口合计"），
	 *       而不是空串：空串在界面上会渲染成一对空括号，读的人不知道那是"没有主体"
	 *       还是"主体没取到"。</li>
	 * </ul>
	 */
	private static String subjectOf(AlertRule rule, RuntimeSnapshot snapshot) {
		return switch (rule) {
			case CANARY_NO_TRAFFIC, CANARY_SPLIT_BIASED -> "灰度 v" + snapshot.canary().candidateVersion();
			case ZERO_CACHE_HITS -> "提示词缓存";
			case EMPTY_RETRIEVAL -> "检索";
			case RECONCILE_GAP -> "窗口合计";
			case UNCONFIGURED_PRICES -> "价格表";
			default -> "";
		};
	}

	/**
	 * 一轮评估的结果：这条判据现在是什么状态。
	 *
	 * @param rule 哪一条判据
	 * @param subject 说的是谁（{@link Kind#QUIET} / {@link Kind#SKIPPED} 时为空串）
	 * @param kind 这一轮的形态
	 * @param verdict 判定结论（只有 FIRING 时有内容）
	 * @param note 没评估的原因（只有 SKIPPED 时有内容）
	 */
	public record Outcome(AlertRule rule, String subject, Kind kind, AlertRule.Verdict verdict, String note) {

		public enum Kind {

			/** 违反了 —— 要报出来（或让已经在响的那条继续响）。 */
			FIRING,

			/** 评估了、没违反 —— 已经在响的那条要被标成恢复。 */
			QUIET,

			/** **没评估** —— 绝不能当成 QUIET（理由见 {@link AlertEvaluator} 的类注释）。 */
			SKIPPED

		}

		static Outcome fired(AlertRule rule, String subject, AlertRule.Verdict verdict) {
			return new Outcome(rule, subject, Kind.FIRING, verdict, "");
		}

		static Outcome quiet(AlertRule rule) {
			return new Outcome(rule, "", Kind.QUIET, AlertRule.Verdict.silent(), "");
		}

		static Outcome skipped(AlertRule rule, String note) {
			return new Outcome(rule, "", Kind.SKIPPED, AlertRule.Verdict.silent(), note);
		}

		/** 这一轮的指纹（只有确定主体的那两种形态才有意义 —— 由调用方判 kind）。 */
		public Optional<String> fingerprint() {
			return kind == Kind.SKIPPED ? Optional.empty()
					: Optional.of(AlertEvent.fingerprintOf(rule, subject));
		}

		/** 日志与接口上的一句话（**不在两处各写一份**）。 */
		public String describe() {
			return switch (kind) {
				case FIRING -> "响：" + rule.code() + (subject.isBlank() ? "" : "(" + subject + ")") + " 观测="
						+ verdict.observed() + " 判据=" + verdict.threshold();
				case QUIET -> "静：" + rule.code();
				case SKIPPED -> "跳过：" + rule.code() + "（" + note + "）";
			};
		}

	}

	/**
	 * 把一轮评估的结果按指纹归组（同一个指纹只会出现一次 —— 多主体判据已按主体展开）。
	 *
	 * <p>为什么要归组而不是直接用列表：判据之间有<b>蕴含关系</b>
	 * （例如"整张价格表没配"蕴含"每个模型都没价"），
	 * 而归组是后面做优先级屏蔽的前提 —— 见 {@code AlertRecorder} 里那段
	 * "同一件事只报一次"的处理。
	 */
	public static Map<String, Outcome> byFingerprint(List<Outcome> outcomes) {
		Map<String, Outcome> map = new LinkedHashMap<>();
		for (Outcome outcome : outcomes) {
			outcome.fingerprint().ifPresent(fp -> map.put(fp, outcome));
		}
		return map;
	}

}
