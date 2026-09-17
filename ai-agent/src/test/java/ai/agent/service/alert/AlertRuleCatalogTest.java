package ai.agent.service.alert;

import ai.agent.entity.alert.RuntimeSnapshot;
import ai.agent.service.alert.AlertEvaluator.Outcome;
import ai.agent.service.alert.AlertRule.CanarySplit;
import ai.agent.service.alert.AlertRule.Severity;
import ai.agent.service.alert.AlertRule.Verdict;
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
	@DisplayName("★ 缓存命中率 0 按**租户**判（第 6 件）：整窗不为 0 时，某个租户为 0 仍然要报")
	void zeroCacheHitsIsJudgedPerTenant() {
		// 【这一条钉的正是第 6 件改动本身】整窗命中率是 12%（不为 0），
		// 而「甲」这个租户一次都没命中 —— 在改粒度之前，这条告警**永远不会响**
		RuntimeSnapshot mixed = snapshot().promptTokens(10_000).cachedPromptTokens(1_200)
				.tenantCaches(List.of(
						new RuntimeSnapshot.TenantPoint("甲", 6_000, 0),
						new RuntimeSnapshot.TenantPoint("乙", 4_000, 1_200)))
				.build();

		assertThat(AlertRule.ZERO_CACHE_HITS.judge(mixed).breached())
				.as("判据本身读的是「传进来的那份快照」—— 逐租户展开由 AlertEvaluator 负责，"
						+ "所以这里对窗口快照判就是整窗口径（不为 0 → 不响）").isFalse();

		List<Outcome> outcomes = AlertEvaluator.judge(AlertRule.ZERO_CACHE_HITS, mixed);
		assertThat(outcomes).as("只有「甲」响 —— 乙命中了 1200").hasSize(1);
		Outcome fired = outcomes.get(0);
		assertThat(fired.kind()).isEqualTo(Outcome.Kind.FIRING);
		assertThat(fired.subject()).as("主体必须是租户名 —— 泛泛的「窗口合计」会让这条告警"
				+ "与别的成本告警长得一样，而它要人做的动作恰恰与那个租户有关").isEqualTo("甲");
		assertThat(fired.verdict().observed()).as("观测值必须是**这个租户**的，不是整窗的")
				.isEqualTo("0 / 6000");

		// 【没有租户信息时要退回窗口口径】否则一个还没接归因的部署**永远收不到**这条告警，
		// 而那时它的成本正按最贵的一档算
		List<Outcome> noTenants = AlertEvaluator.judge(AlertRule.ZERO_CACHE_HITS,
				snapshot().promptTokens(1_000).cachedPromptTokens(0).build());
		assertThat(noTenants).singleElement().satisfies(o -> {
			assertThat(o.kind()).isEqualTo(Outcome.Kind.FIRING);
			assertThat(o.subject()).as("退回窗口口径时主体要说明它没按租户拆 —— "
					+ "否则读的人会以为这是某一个租户的结论").contains("未按租户拆分");
		});

		// 【一个租户都没响时必须产出一条 QUIET】它是"把已经在响的那几条标成恢复"的入口；
		// 漏了它，某租户缓存开始命中之后那条告警会一直响着
		List<Outcome> allGood = AlertEvaluator.judge(AlertRule.ZERO_CACHE_HITS,
				snapshot().promptTokens(10_000).cachedPromptTokens(1_200)
						.tenantCaches(List.of(new RuntimeSnapshot.TenantPoint("甲", 6_000, 600)))
						.build());
		assertThat(allGood).singleElement()
				.satisfies(o -> assertThat(o.kind()).isEqualTo(Outcome.Kind.QUIET));
	}

	@Test
	@DisplayName("★ 成本集中度：只有租户粒度才问得出的一个问题（<90% 静、≥90% 响、没有归因静）")
	void tenantCostConcentration() {
		// 两个租户各一半 —— 完全正常，不该响
		assertThat(AlertRule.TENANT_COST_CONCENTRATION
				.judge(snapshot().tenantShare(new AlertRule.TenantShare("甲", 5_000, 10_000)).build()).breached())
				.as("两个租户各占一半是正常分布；阈值取 0.5 会让任何两家客户的部署一上线就顶着一条告警")
				.isFalse();

		Verdict fired = AlertRule.TENANT_COST_CONCENTRATION
				.judge(snapshot().tenantShare(new AlertRule.TenantShare("甲", 9_500, 10_000)).build());
		assertThat(fired.breached()).isTrue();
		assertThat(fired.observed()).as("观测值必须带上是哪一个租户 —— 只说「95%」等于没说").contains("甲");
		assertThat(fired.why()).as("分母与分子都要给出（可复算）").contains("9500").contains("10000");
		assertThat(AlertRule.TENANT_COST_CONCENTRATION.action())
				.as("★ 它的处置说明第一句必须是「先确认这是不是已知的」——"
						+ "一个正当的大客户会让它一直响，而一条「去优化它」的说明会把人引错方向")
				.contains("已知");

		assertThat(AlertRule.TENANT_COST_CONCENTRATION.judge(snapshot().build()).breached())
				.as("没有任何租户信息时不响 —— 也**不给一个 0**：那不是「集中度低」，"
						+ "而是「这个问题无从谈起」（该做的是去看调用方有没有传 tenantId）")
				.isFalse();
	}

	@Test
	@DisplayName("★ 判据里的字面量 == 公开常量：租户集中度的阈值")
	void tenantConcentrationThresholdMatchesThePublishedConstant() {
		double threshold = AlertRule.TENANT_CONCENTRATION_THRESHOLD;
		long total = 100_000;
		// 占比刚好略高于阈值 → 必须响；略低于 → 必须不响。
		// 这是唯一能验证"两份数字一致"的办法（光读常量验不了 lambda 里那个字面量）
		long above = (long) Math.ceil(total * threshold) + 1;
		long below = (long) Math.floor(total * threshold) - 1;

		assertThat(AlertRule.TENANT_COST_CONCENTRATION
				.judge(snapshot().tenantShare(new AlertRule.TenantShare("甲", above, total)).build()).breached())
				.as("占比略高于公开阈值 %.2f → 必须响（否则坏掉的不是判据，是那个常量）", threshold).isTrue();
		assertThat(AlertRule.TENANT_COST_CONCENTRATION
				.judge(snapshot().tenantShare(new AlertRule.TenantShare("甲", below, total)).build()).breached())
				.as("占比略低于公开阈值 → 必须不响").isFalse();
	}

	@Test
	@DisplayName("★ 主体粒度是一处显式的 switch：成本那一族的粒度必须逐条被钉住")
	void costRulesDeclareTheirSubjectGranularity() {
		// 【为什么值得一条专门的测试】本项目点名写过：「换粒度就去 AlertEvaluator.judge 里改」——
		// 而"改了粒度"这件事本身没有症状：一条被判成"全局"的判据只是**少报几条**，
		// 页面上看不出任何异常。所以这里把当前的粒度写成断言：
		//   · 配置/取数类 → 全局（价格表是一个部署一份的配置，与有几个租户无关）
		//   · 花钱形态类 → 每租户（缓存 0 命中、花费集中）
		RuntimeSnapshot two = snapshot().promptTokens(10_000).cachedPromptTokens(0)
				.tenantCaches(List.of(
						new RuntimeSnapshot.TenantPoint("甲", 6_000, 0),
						new RuntimeSnapshot.TenantPoint("乙", 4_000, 0)))
				.build();

		assertThat(AlertEvaluator.judge(AlertRule.UNCONFIGURED_PRICES, two))
				.as("价格没配是**全局**的：按租户拆开问会得到 N 条一模一样的话")
				.hasSize(1);
		assertThat(AlertEvaluator.judge(AlertRule.RECONCILE_GAP, two))
				.as("对账对不上也是全局的：它说的是一次汇总的差，拆开之后每一份都不完整")
				.hasSize(1);
		assertThat(AlertEvaluator.judge(AlertRule.ZERO_CACHE_HITS, two))
				.as("缓存命中率是**每租户**的：两个租户都没命中 → 两条独立告警")
				.hasSize(2);
		assertThat(AlertEvaluator.judge(AlertRule.ZERO_CACHE_HITS, two))
				.extracting(Outcome::subject).as("两条的主体必须是两个租户名，而不是重复的窗口合计")
				.containsExactly("甲", "乙");
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
		//
		// 【第 6 件又加了一处】tenantShare 也要给 —— 否则
		// TENANT_COST_CONCENTRATION 永远不响，而"漏掉哪条说明它压根没法被触发"
		// 那条断言会以"缺一条判据"的形式失败（它确实抓到了）。
		List<RuntimeSnapshot> worstCases = List.of(
				snapshot().pricingConfigured(false).unpricedModels(List.of("模型甲")).reconcileGap(42)
						.promptTokens(1000).cachedPromptTokens(0).emptyStreak(5, 0)
						.tenantShare(new AlertRule.TenantShare("甲", 950, 1000))
						.canary(new CanarySplit(true, 4, 80, 0, 0)).build(),
				snapshot().pricingConfigured(false).unpricedModels(List.of("模型甲")).reconcileGap(42)
						.promptTokens(1000).cachedPromptTokens(0).emptyStreak(5, 0)
						.tenantShare(new AlertRule.TenantShare("甲", 950, 1000))
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

		// 【第 6 件：逐租户展开的判据，指纹必须按租户分开】
		// 不分开的后果很具体：N 个租户的告警会互相覆盖，只剩最后一条 ——
		// 而"只剩一条"在页面上完全看不出来，它看起来就是"只有一个租户有问题"
		RuntimeSnapshot perTenant = snapshot().promptTokens(10_000).cachedPromptTokens(0)
				.tenantCaches(List.of(
						new RuntimeSnapshot.TenantPoint("甲", 6_000, 0),
						new RuntimeSnapshot.TenantPoint("乙", 4_000, 0)))
				.build();
		List<Outcome> cacheOutcomes = AlertEvaluator.judge(AlertRule.ZERO_CACHE_HITS, perTenant);
		assertThat(cacheOutcomes).extracting(o -> o.fingerprint().orElseThrow())
				.as("两个租户必须是两个不同的指纹 —— 否则其中一条会把另一条覆盖掉")
				.doesNotHaveDuplicates();
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

		/** 逐租户的缓存口径（第 6 件）。默认空 = "这个部署还没接归因"。 */
		private List<RuntimeSnapshot.TenantPoint> tenantCaches = List.of();

		/** 花费最集中的租户（第 6 件）。默认 null = "无从谈起"。 */
		private AlertRule.TenantShare tenantShare;

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

		/** 逐租户口径：每项是 (租户, 输入 token, 其中命中缓存)。 */
		Builder tenantCaches(List<RuntimeSnapshot.TenantPoint> value) {
			this.tenantCaches = value;
			return this;
		}

		Builder tenantShare(AlertRule.TenantShare value) {
			this.tenantShare = value;
			return this;
		}

		RuntimeSnapshot build() {
			return new RuntimeSnapshot(Instant.parse("2026-09-18T00:00:00Z"), "2026-09-12", "2026-09-18",
					pricingConfigured, unpricedModels, reconcileGap, stepLevelTokens, runLevelTokens,
					runsWithoutSteps, promptTokens, cachedPromptTokens, emptyStreak, lastIndexSize,
					retrievalCalls, canary, tenantCaches, tenantShare);
		}

	}

}
