package ai.agent.alert;

import ai.agent.alert.AlertEvaluator.Outcome;
import ai.agent.alert.AlertRule.CanarySplit;
import ai.agent.alert.AlertRule.Severity;
import ai.agent.alert.AlertRule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>判据本身的测试</b>（阶段四第 5 件：异常告警）。
 *
 * <h2>为什么这个类不需要起 Spring、不需要库</h2>
 * 因为它测的是 {@link AlertRule} 与 {@link AlertEvaluator} —— 两者都是纯的：
 * 判据读一份手造的 {@link RuntimeSnapshot}，评估器返回一串结论，都不碰数据库。
 *
 * <p>这不是为了快（虽然它确实只跑几十毫秒），而是为了<b>失败时能指向正确的地方</b>：
 * 如果判据只能通过"真跑出一批数据、再算一遍"来验证，那么判据错了的时候，
 * 你分不清是<b>判据错了</b>还是<b>数据不对</b> —— 而那两种情况要改的代码正好相反。
 * 阶段四第 4 件（回归门禁）把方向判据拆出去单独测，用的是同一个理由。
 *
 * <h2>这个类里的三类断言，各自盯着一类"不报错的错"</h2>
 * <ol>
 *   <li><b>目录的完整性</b>：代号唯一、标题非空、处置可照做、
 *       "只有构建阶段的判据能是 BLOCKER"。少任何一条都不会报错 ——
 *       只会在某天有人照着一条残缺的告警去处理时才发现；</li>
 *   <li><b>逐条判据的边界</b>：每一条都要有"刚好不响"与"刚好响"两侧。
 *       ⚠️ 只测"响"的那一侧是不够的 —— 一条<b>永远响</b>的判据会让整个告警列表失效，
 *       而那正是"误报会杀死告警系统"的具体形态；</li>
 *   <li><b>★ 抄写的阈值与公开常量一致</b>：判据 lambda 里写的是字面量
 *       （原因见 {@code AlertRule.CONSECUTIVE_EMPTY_RETRIEVALS} 的注释），
 *       而公开常量也写着同一个数。<b>抄错一位不会有任何症状</b> ——
 *       只会让某条判据在该响的时候不响。所以这里用"逼响它"的办法把两份对齐。</li>
 * </ol>
 */
class AlertRuleCatalogTest {

	// ------------------------------------------------------------------
	//  一、目录的完整性
	// ------------------------------------------------------------------

	@Test
	@DisplayName("判据目录：代号唯一、标题与处置都非空 —— 缺一项都不会报错，只会让照着它做的人猜")
	void catalogIsComplete() {
		Set<String> codes = java.util.Arrays.stream(AlertRule.values()).map(AlertRule::code)
				.collect(Collectors.toSet());
		assertThat(codes).as("代号是落库、去重、确认的键，重复会让两条判据互相覆盖")
				.hasSize(AlertRule.values().length);

		for (AlertRule rule : AlertRule.values()) {
			assertThat(rule.code()).as("%s 的代号不能为空", rule.name()).isNotBlank()
					.matches("[a-z0-9-]+");
			assertThat(rule.title()).as("%s 的标题不能为空（告警列表上显示的就是它）", rule.code()).isNotBlank();
			assertThat(rule.action()).as("%s 必须有处置说明 —— 一条只说「出问题了」的告警"
					+ "会让人回到代码里重新推一遍，而那时已经来不及了", rule.code()).isNotBlank();
			assertThat(rule.executedBy()).as("%s 必须写明谁在执行它（构建阶段那几条不在本类里跑）", rule.code())
					.isNotBlank();
		}
	}

	@Test
	@DisplayName("★ 只有构建阶段的判据能是 BLOCKER —— 「严重」与「挡不挡构建」是两件事")
	void onlyGatesCanBlockTheBuild() {
		for (AlertRule rule : AlertRule.values()) {
			if (rule.severity() == Severity.BLOCKER) {
				assertThat(rule.phase())
						.as("%s 是 BLOCKER 却不是构建阶段的门禁 —— "
								+ "一条在运行阶段「阻断」的告警拦不住任何东西，"
								+ "而它的名字会让人以为它拦得住", rule.code())
						.isEqualTo(AlertRule.AlertPhase.GATE);
			}
			else {
				assertThat(rule.phase())
						.as("%s 是构建阶段的判据却不是 BLOCKER —— 门禁的意义就是会红，"
								+ "一条不会红的门禁等于没有", rule.code())
						.isEqualTo(AlertRule.AlertPhase.OBSERVE);
			}
		}
	}

	@Test
	@DisplayName("构建阶段的判据在本类里一律「不自行判断」—— 真正那条比较在 RagRegressionTest 里")
	void gatesDoNotJudgeHere() {
		RuntimeSnapshot worstCase = snapshot().unpricedModels(List.of("m1")).reconcileGap(999)
				.cachedPromptTokens(0).promptTokens(1000).emptyStreak(99, 0)
				.canary(new CanarySplit(true, 4, 50, 100, 0)).build();
		for (AlertRule gate : AlertRule.gates()) {
			assertThat(gate.judge(worstCase).breached())
					.as("%s 在本类里不该给出结论 —— 那会让同一件事有两份实现，"
							+ "而两份实现迟早会对不上（这正是判据要集中在一处的原因）", gate.code())
					.isFalse();
			assertThat(gate.executedBy()).as("%s 必须写明真正的执行者", gate.code())
					.doesNotContain("AlertEvaluator");
		}
	}

	@Test
	@DisplayName("运行阶段的判据只评估自己那一半 —— 构建阶段的判据在运行时会被标成 SKIPPED，不是 QUIET")
	void runtimeEvaluationSkipsGates() {
		List<Outcome> outcomes = AlertEvaluator.evaluate(quietSnapshot(), AlertRule.AlertPhase.OBSERVE);

		assertThat(outcomes).as("每一条判据都要有一个结论（包括「没评估」）")
				.hasSize(AlertRule.values().length);
		for (Outcome outcome : outcomes) {
			if (outcome.rule().isGate()) {
				assertThat(outcome.kind()).as("%s 在运行阶段必须是 SKIPPED", outcome.rule().code())
						.isEqualTo(Outcome.Kind.SKIPPED);
				assertThat(outcome.fingerprint()).as("没评估的判据没有指纹 —— 它不能参与「恢复」的判断")
						.isEmpty();
			}
			else {
				assertThat(outcome.kind()).as("%s 在运行阶段必须被真的评估过", outcome.rule().code())
						.isNotEqualTo(Outcome.Kind.SKIPPED);
			}
		}
	}

	// ------------------------------------------------------------------
	//  二、逐条判据：两侧边界
	// ------------------------------------------------------------------

	@Test
	@DisplayName("价格没配：空表 → 响；配了 → 静（且它只是 INFO，不该把人叫醒）")
	void unconfiguredPrices() {
		Verdict fired = AlertRule.UNCONFIGURED_PRICES.judge(snapshot().pricingConfigured(false).build());
		assertThat(fired.breached()).isTrue();
		assertThat(AlertRule.UNCONFIGURED_PRICES.severity()).as("它是要有人去填，不是要有人被叫醒")
				.isEqualTo(Severity.INFO);
		assertThat(AlertRule.UNCONFIGURED_PRICES.judge(snapshot().pricingConfigured(true).build()).breached())
				.isFalse();
	}

	@Test
	@DisplayName("模型没配价：一个模型一条（主体粒度），报出的是「哪个模型」而不是「有几个」")
	void unpricedModelsAreOneAlertPerModel() {
		RuntimeSnapshot two = snapshot().pricingConfigured(true)
				.unpricedModels(List.of("模型甲", "模型乙")).build();
		List<Outcome> outcomes = AlertEvaluator.judge(AlertRule.UNPRICED_MODELS, two);

		assertThat(outcomes).as("两个模型必须是两条告警 —— 合成一条之后，"
				+ "值班的人还得再查一次才知道要补哪几条配置").hasSize(2);
		assertThat(outcomes).extracting(Outcome::subject).containsExactly("模型甲", "模型乙");
		assertThat(outcomes).extracting(o -> o.fingerprint().orElseThrow()).doesNotHaveDuplicates();

		assertThat(AlertEvaluator.judge(AlertRule.UNPRICED_MODELS, snapshot().pricingConfigured(true).build()))
				.as("没有未配价的模型时只产出一条 QUIET（它负责把在响的那几条标成恢复）")
				.singleElement().satisfies(o -> assertThat(o.kind()).isEqualTo(Outcome.Kind.QUIET));
	}

	@Test
	@DisplayName("对账差值：0 静、1 响 —— 阈值是「逐 token 相等」，不是「差不多」")
	void reconcileGap() {
		assertThat(AlertRule.RECONCILE_GAP.judge(snapshot().reconcileGap(0).build()).breached()).isFalse();
		Verdict fired = AlertRule.RECONCILE_GAP.judge(snapshot().reconcileGap(1).build());
		assertThat(fired.breached()).isTrue();
		assertThat(fired.observed()).as("观测值就是差值本身，人能直接读").isEqualTo("1");
		assertThat(fired.why()).contains("步骤口径").contains("运行口径");
	}

	@Test
	@DisplayName("缓存命中率 0：有 token 才算异常；窗口内没有 token 时不响（无从谈起）")
	void zeroCacheHits() {
		Verdict fired = AlertRule.ZERO_CACHE_HITS
				.judge(snapshot().promptTokens(1000).cachedPromptTokens(0).build());
		assertThat(fired.breached()).isTrue();
		assertThat(fired.why()).as("它必须说清「为 0 意味着什么」—— 成本一直按最贵那一档在算")
				.contains("一个数量级").contains("最贵");
		assertThat(AlertRule.ZERO_CACHE_HITS.action())
				.as("★ 而「怎么分辨两种成因」那半句在处置说明里（判据的 why 讲的是含义，action 讲的是动作）—— "
						+ "两句话必须都在，少一句就会把人引到「优化提示词」上去")
				.contains("真 key").contains("原始 usage");

		assertThat(AlertRule.ZERO_CACHE_HITS.judge(snapshot().promptTokens(0).cachedPromptTokens(0).build())
				.breached()).as("窗口内没有输入 token 时命中率无从谈起").isFalse();
		assertThat(AlertRule.ZERO_CACHE_HITS.judge(snapshot().promptTokens(1000).cachedPromptTokens(1).build())
				.breached()).as("命中过一次就不该报 —— 这条判据盯的是「整窗为 0」").isFalse();
	}

	@Test
	@DisplayName("★ 连续空命中：2 次静、3 次响 —— 阈值是「连续」而不是「累计」")
	void emptyRetrievalUsesAStreakNotASum() {
		assertThat(AlertRule.EMPTY_RETRIEVAL.judge(snapshot().emptyStreak(2, 0).build()).breached())
				.as("两次空命中可能只是两个恰好没有答案的问题").isFalse();
		Verdict fired = AlertRule.EMPTY_RETRIEVAL.judge(snapshot().emptyStreak(3, 0).build());
		assertThat(fired.breached()).isTrue();
		assertThat(fired.threshold()).as("阈值那一格必须写出「不是累计」—— "
				+ "否则读的人会以为「一共空过 3 次」也算").contains("连续").contains("不是累计");
		assertThat(fired.why()).as("索引规模要带上：它是「索引没建起来」（部署问题）"
				+ "与「提问跑题」两种成因的分界").contains("索引");
	}

	@Test
	@DisplayName("★ 灰度没流量：开着且 0 份参与 → 响；开着但分到过 → 静；没开 → 静")
	void canaryNoTraffic() {
		Verdict fired = AlertRule.CANARY_NO_TRAFFIC
				.judge(snapshot().canary(new CanarySplit(true, 4, 20, 0, 0)).build());
		assertThat(fired.breached()).as("配置 20% 而一份都没分到 —— 这次实验等于没做，"
				+ "而所有功能都正常、日志干干净净").isTrue();
		assertThat(fired.why()).contains("20%").contains("v4");

		assertThat(AlertRule.CANARY_NO_TRAFFIC
				.judge(snapshot().canary(new CanarySplit(true, 4, 20, 1, 1)).build()).breached())
				.as("只要分到过一份就不是「没流量」—— 比例不对由另一条判据管").isFalse();
		assertThat(AlertRule.CANARY_NO_TRAFFIC.judge(snapshot().canary(CanarySplit.none()).build()).breached())
				.as("没开灰度时不该报").isFalse();
	}

	@Test
	@DisplayName("★ 灰度分流偏离：样本不足一律不响（小样本报警是告警系统最常见的死法）")
	void canarySplitBiasRespectsTheMinimumSample() {
		// 配置 50%，实测 0/9 —— 看起来偏得离谱，但样本只有 9 份（阈值 10）
		assertThat(AlertRule.CANARY_SPLIT_BIASED
				.judge(snapshot().canary(new CanarySplit(true, 4, 50, 9, 0)).build()).breached())
				.as("9 份样本不够下结论 —— 10% 的重量在 5 份里一份都没分到，概率高达六成")
				.isFalse();

		Verdict fired = AlertRule.CANARY_SPLIT_BIASED
				.judge(snapshot().canary(new CanarySplit(true, 4, 50, 10, 0)).build());
		assertThat(fired.breached()).as("10 份样本、一份都没分到：分流函数可疑").isTrue();
		assertThat(fired.observed()).contains("0/10");
		assertThat(fired.why()).contains("10 份");

		// 配置 50%、实测 5/10 = 50% → 完全不偏
		assertThat(AlertRule.CANARY_SPLIT_BIASED
				.judge(snapshot().canary(new CanarySplit(true, 4, 50, 10, 5)).build()).breached()).isFalse();
		// 配置 50%、实测 3/10 = 30%（相对偏离 40% > 30%）→ 响
		assertThat(AlertRule.CANARY_SPLIT_BIASED
				.judge(snapshot().canary(new CanarySplit(true, 4, 50, 10, 3)).build()).breached())
				.as("相对偏离 40% 超过容忍度 30%").isTrue();
	}

	// ------------------------------------------------------------------
	//  三、★ 抄写的阈值必须与公开常量一致（这是本类最值钱的三条断言）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 判据里的字面量 == 公开常量：连续空命中的阈值")
	void emptyRetrievalThresholdMatchesThePublishedConstant() {
		// 用「逼响它」的办法反推判据里那个数：snapshot 的连续数刚好等于常量时它必须响，
		// 少一次时必须不响。**这是唯一能验证「两份数字一致」的办法** ——
		// 光读常量是验不了 lambda 里那个字面量的
		int threshold = AlertRule.CONSECUTIVE_EMPTY_RETRIEVALS;
		assertThat(AlertRule.EMPTY_RETRIEVAL.judge(snapshot().emptyStreak(threshold, 0).build()).breached())
				.as("连续 %d 次时必须响（说明判据里的字面量与公开常量一致）", threshold).isTrue();
		assertThat(AlertRule.EMPTY_RETRIEVAL.judge(snapshot().emptyStreak(threshold - 1, 0).build()).breached())
				.as("连续 %d 次时不该响", threshold - 1).isFalse();
	}

	@Test
	@DisplayName("★ 判据里的字面量 == 公开常量：灰度的最小样本量")
	void canaryMinSampleMatchesThePublishedConstant() {
		int minSample = AlertRule.CANARY_MIN_SAMPLE;
		CanarySplit atThreshold = new CanarySplit(true, 4, 80, minSample, 0);
		assertThat(AlertRule.CANARY_SPLIT_BIASED.judge(snapshot().canary(atThreshold).build()).breached())
				.as("样本刚好到 %d 份、一份都没分到 → 必须响", minSample).isTrue();
		CanarySplit below = new CanarySplit(true, 4, 80, minSample - 1, 0);
		assertThat(AlertRule.CANARY_SPLIT_BIASED.judge(snapshot().canary(below).build()).breached())
				.as("样本比 %d 少一份 → 必须不响", minSample).isFalse();
	}

	@Test
	@DisplayName("★ 判据里的字面量 == 公开常量：灰度的分流容忍度")
	void canaryToleranceMatchesThePublishedConstant() {
		double tolerance = AlertRule.CANARY_SPLIT_TOLERANCE;
		// 配置 100 份里 50 份（50%），构造一个偏离刚好略大于容忍度的观测：
		// 观测比例 = 50% × (1 - tolerance) - 一点
		int weight = 50;
		int participating = 100;
		int observedAtLimit = (int) Math.floor(participating * weight / 100.0 * (1 - tolerance));
		CanarySplit over = new CanarySplit(true, 4, weight, participating, observedAtLimit - 1);
		CanarySplit under = new CanarySplit(true, 4, weight, participating, observedAtLimit + 1);
		assertThat(AlertRule.CANARY_SPLIT_BIASED.judge(snapshot().canary(over).build()).breached())
				.as("偏离略大于公开的容忍度 %.2f → 必须响（否则坏掉的不是判据，是那个常量）", tolerance)
				.isTrue();
		assertThat(AlertRule.CANARY_SPLIT_BIASED.judge(snapshot().canary(under).build()).breached())
				.as("偏离略小于公开的容忍度 → 必须不响").isFalse();
	}

	@Test
	@DisplayName("★ 每一条运行阶段的判据都必须给出「三格都不空」的结论 —— 校验放在测试里，不放在运行路径上")
	void everyFiringVerdictCarriesItsNumbers() {
		// 【为什么用两份快照而不是一份】
		// 两条灰度判据**互斥**：一份都没分到时 CANARY_NO_TRAFFIC 响，
		// 而分到了才轮到 CANARY_SPLIT_BIASED 判比例。这在语义上是对的
		// （"一份都没有"与"有但比例不对"是两种情形），
		// 但用"一份万能的最差快照"去覆盖它们就做不到 ——
		// 而第一版正是那么写的，失败信息里缺的正是 CANARY_NO_TRAFFIC。
		// 记在这里：**互斥的判据需要各自的最差输入**。
		List<RuntimeSnapshot> worstCases = List.of(
				snapshot().pricingConfigured(false).unpricedModels(List.of("模型甲")).reconcileGap(42)
						.promptTokens(1000).cachedPromptTokens(0).emptyStreak(5, 0)
						.canary(new CanarySplit(true, 4, 80, 0, 0)).build(),
				snapshot().pricingConfigured(false).unpricedModels(List.of("模型甲")).reconcileGap(42)
						.promptTokens(1000).cachedPromptTokens(0).emptyStreak(5, 0)
						.canary(new CanarySplit(true, 4, 80, 100, 0)).build());

		List<AlertRule> fired = new ArrayList<>();
		for (RuntimeSnapshot allBad : worstCases) {
			for (AlertRule rule : AlertRule.observed()) {
				for (Outcome outcome : AlertEvaluator.judge(rule, allBad)) {
					if (outcome.kind() != Outcome.Kind.FIRING) {
						continue;
					}
					if (!fired.contains(rule)) {
						fired.add(rule);
					}
					Verdict verdict = outcome.verdict();
					assertThat(verdict.observed()).as("%s 的观测值不能为空", rule.code()).isNotBlank();
					assertThat(verdict.threshold()).as("%s 的判据阈值不能为空（读的人要知道「跟什么比」）",
							rule.code()).isNotBlank();
					assertThat(verdict.why())
							.as("%s 的「为什么是这个阈值」不能为空（这一格是给三个月后的人看的）", rule.code())
							.isNotBlank();
				}
			}
		}
		assertThat(fired).as("这两份「最差」的快照合起来必须把每一条运行阶段的判据都逼响 —— "
				+ "漏掉哪条说明它压根没法被触发（而那种判据等于不存在）")
				.containsExactlyInAnyOrderElementsOf(AlertRule.observed());
	}

	@Test
	@DisplayName("全部正常的快照下，一条都不该响 —— 一条「永远响」的判据会让整个列表失效")
	void quietSnapshotFiresNothing() {
		List<Outcome> outcomes = AlertEvaluator.evaluate(quietSnapshot(), AlertRule.AlertPhase.OBSERVE);
		assertThat(outcomes).filteredOn(o -> o.kind() == Outcome.Kind.FIRING)
				.as("误报会消耗掉告警的信用，而信用花完就回不来了").isEmpty();
	}

	@Test
	@DisplayName("按指纹归组：同一个指纹只留一条（多主体判据已按主体展开过）")
	void outcomesGroupByFingerprint() {
		RuntimeSnapshot bad = snapshot().pricingConfigured(true).unpricedModels(List.of("甲", "乙", "丙")).build();
		List<Outcome> outcomes = AlertEvaluator.evaluate(bad, AlertRule.AlertPhase.OBSERVE);
		Map<String, Outcome> byFingerprint = AlertEvaluator.byFingerprint(outcomes);

		assertThat(byFingerprint).as("三个模型 + 其它判据各自的指纹").hasSize(3 + AlertRule.observed().size() - 1);
		assertThat(byFingerprint.values()).filteredOn(o -> o.kind() == Outcome.Kind.FIRING)
				.extracting(o -> o.rule().code()).containsOnly("unpriced-models");
	}

	// ------------------------------------------------------------------
	//  构造快照
	// ------------------------------------------------------------------

	/** 一份"全部正常"的快照：任何一条判据都不该在它上面响。 */
	private static RuntimeSnapshot quietSnapshot() {
		return snapshot().pricingConfigured(true).promptTokens(1000).cachedPromptTokens(400)
				.reconcileGap(0).emptyStreak(0, 12).canary(CanarySplit.none()).build();
	}

	private static Builder snapshot() {
		return new Builder();
	}

	/**
	 * 快照的构造器。
	 *
	 * <p>为什么用一个小 builder 而不是直接 {@code new RuntimeSnapshot(14 个参数)}：
	 * 那 14 个参数里有 6 个是 long / int，写错顺序<b>编译器不会发现</b>
	 * （都是数字），而测试会以一个看不懂的方式失败。名字化的 setter 让
	 * "我这次到底改的是哪一个"在调用点上就看得见。
	 */
	private static final class Builder {

		private boolean pricingConfigured = true;

		private List<String> unpricedModels = List.of();

		private long reconcileGap;

		private long stepLevelTokens;

		private long runLevelTokens;

		private long runsWithoutSteps;

		private long promptTokens;

		private long cachedPromptTokens;

		private int emptyStreak;

		private int lastIndexSize = 12;

		private long retrievalCalls = 20;

		private CanarySplit canary = CanarySplit.none();

		Builder pricingConfigured(boolean value) {
			this.pricingConfigured = value;
			return this;
		}

		Builder unpricedModels(List<String> value) {
			this.unpricedModels = value;
			return this;
		}

		Builder reconcileGap(long value) {
			this.reconcileGap = value;
			return this;
		}

		Builder promptTokens(long value) {
			this.promptTokens = value;
			return this;
		}

		Builder cachedPromptTokens(long value) {
			this.cachedPromptTokens = value;
			return this;
		}

		Builder emptyStreak(int streak, int indexSize) {
			this.emptyStreak = streak;
			this.lastIndexSize = indexSize;
			return this;
		}

		Builder canary(CanarySplit value) {
			this.canary = value;
			return this;
		}

		RuntimeSnapshot build() {
			return new RuntimeSnapshot(Instant.parse("2026-09-18T00:00:00Z"), "2026-09-12", "2026-09-18",
					pricingConfigured, unpricedModels, reconcileGap, stepLevelTokens, runLevelTokens,
					runsWithoutSteps, promptTokens, cachedPromptTokens, emptyStreak, lastIndexSize,
					retrievalCalls, canary);
		}

	}

}
