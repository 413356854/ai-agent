package ai.agent.alert;

import ai.agent.alert.AlertRule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>告警状态的真实落库语义</b>（阶段四第 5 件：异常告警）。
 *
 * <h2>它验的是 {@code AlertRecorderTest} 验不了的那一半</h2>
 * {@code AlertRecorderTest} 用一个内存替身验证"状态机的规则"（什么时候通知、
 * 什么时候标恢复）。而本类验证<b>同一套规则在 SQL 里真的成立</b>：
 * UPSERT 的四条语义、去重键、并发保护、以及"确认带前置条件"。
 *
 * <p>两份实现（内存替身 + 真 SQL）就有分叉的风险，所以这边的断言刻意
 * 与那边<b>一一对应</b>：持续时间、重新触发、已确认保持已确认。
 * 分叉的表现会很难看：内存里测得好好的，一上真库就"确认按钮点了没用"。
 *
 * <h2>为什么要专门测"UPSERT 的四条语义"</h2>
 * 因为它们全部是<b>不报错的错</b>：
 * <ul>
 *   <li>{@code first_seen_at} 被顺手更新 → "这个问题存在了多久"**永远接近 0**
 *       （复盘时最常被问的那句话再也答不出来）；</li>
 *   <li>{@code occurrences} 没累加 → "这个异常是持续的还是偶尔的"分不出来；</li>
 *   <li>{@code resolved_at} 没清 → "修好过又坏了"停留在"已恢复"，没人会看到；</li>
 *   <li>已确认的被重置成 OPEN → 系统每评估一遍就把人的确认抹掉一次，
 *       而值班的人只会觉得"这个按钮时灵时不灵"。</li>
 * </ul>
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.url=jdbc:h2:mem:alert-store-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"ai.agent.store.node=alert-store-test" })
class AlertStoreTest {

	@Autowired
	private AlertStore store;

	/** 每条判据用它自己的 subject，避免测试之间互相污染（库是整个上下文共享的）。 */
	private static String freshSubject(String tag) {
		return tag + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static Verdict verdict(String observed) {
		return Verdict.of(observed, "判据阈值", "为什么是这个阈值");
	}

	// ------------------------------------------------------------------
	//  1. 首次 / 持续 / 恢复 / 重新触发
	// ------------------------------------------------------------------

	@Test
	@DisplayName("首次触发：状态 OPEN，occurrences=1，firstSeenAt 与 lastSeenAt 都是刚才")
	void firstFiring() {
		String subject = freshSubject("first");
		Instant now = Instant.now();
		AlertEvent event = store.recordFiring(AlertRule.RECONCILE_GAP, subject, verdict("42"), "窗口A", now);

		assertThat(event.state()).isEqualTo(AlertEvent.State.OPEN);
		assertThat(event.occurrences()).isEqualTo(1);
		assertThat(event.firstSeenAt()).isNotNull();
		assertThat(event.resolvedAt()).isNull();
		assertThat(event.needsAttention()).as("没人看过的要进「未处理」").isTrue();
		assertThat(event.windowLabel()).as("窗口要留痕 —— 没有窗口的告警数字不可复现").isEqualTo("窗口A");
		assertThat(event.action()).as("处置说明来自判据目录，不在告警里重写一份").isNotBlank();
	}

	@Test
	@DisplayName("★ 持续触发：occurrences 累加、lastSeenAt 往后走，而 firstSeenAt **不动**")
	void repeatedFiringKeepsTheFirstSeenTime() {
		String subject = freshSubject("repeat");
		Instant first = Instant.parse("2026-09-01T00:00:00Z");
		Instant later = Instant.parse("2026-09-05T00:00:00Z");

		AlertEvent created = store.recordFiring(AlertRule.RECONCILE_GAP, subject, verdict("1"), "窗口A", first);
		AlertEvent again = store.recordFiring(AlertRule.RECONCILE_GAP, subject, verdict("2"), "窗口B", later);

		assertThat(again.fingerprint()).as("同一个指纹就是同一行（去重键生效）").isEqualTo(created.fingerprint());
		assertThat(again.occurrences()).isEqualTo(2);
		assertThat(again.firstSeenAt())
				.as("★ firstSeenAt 必须还是第一次那个值 —— 顺手更新它会让"
						+ "「这个问题存在了多久」永远接近 0，而那是复盘的第一句话")
				.isEqualTo(created.firstSeenAt());
		assertThat(again.lastSeenAt()).isAfterOrEqualTo(created.lastSeenAt());
		assertThat(again.observed()).as("观测值更新成最新那次").isEqualTo("2");
		assertThat(again.windowLabel()).isEqualTo("窗口B");
	}

	@Test
	@DisplayName("恢复：状态 RESOLVED + resolvedAt；再触发时 resolvedAt 清空、状态回 OPEN（行不删）")
	void resolvedThenReopened() {
		String subject = freshSubject("reopen");
		AlertEvent created = store.recordFiring(AlertRule.ZERO_CACHE_HITS, subject, verdict("0 / 100"), "窗口A",
				Instant.now());

		assertThat(store.recordResolved(created.fingerprint(), Instant.now())).isPresent()
				.get().satisfies(e -> {
					assertThat(e.state()).isEqualTo(AlertEvent.State.RESOLVED);
					assertThat(e.resolvedAt()).isNotNull();
				});
		assertThat(store.firing()).as("恢复之后不该还在响着的列表里")
				.noneMatch(e -> e.fingerprint().equals(created.fingerprint()));

		AlertEvent reopened = store.recordFiring(AlertRule.ZERO_CACHE_HITS, subject, verdict("0 / 200"), "窗口B",
				Instant.now());
		assertThat(reopened.state()).as("修好过又坏了 → 必须重新出现在未处理里").isEqualTo(AlertEvent.State.OPEN);
		assertThat(reopened.resolvedAt()).isNull();
		assertThat(store.find(created.fingerprint())).as("行一直都在（没有 delete）").isPresent();
	}

	@Test
	@DisplayName("对一条没见过 / 已经恢复的告警调「标恢复」→ 什么都不做（不写占位行）")
	void resolvingSomethingUnknownDoesNothing() {
		assertThat(store.recordResolved("reconcile-gap|从来不存在的", Instant.now()))
				.as("找不到就不写 —— 写一行「已恢复」占位会让列表里出现一堆从来没见过的问题")
				.isEmpty();

		String subject = freshSubject("double-resolve");
		AlertEvent created = store.recordFiring(AlertRule.ZERO_CACHE_HITS, subject, verdict("0 / 100"), "窗口A",
				Instant.now());
		store.recordResolved(created.fingerprint(), Instant.now());
		assertThat(store.recordResolved(created.fingerprint(), Instant.now()))
				.as("已经恢复的再标一次 → 空（不能把 resolvedAt 一直往后推）").isEmpty();
	}

	// ------------------------------------------------------------------
	//  2. 确认
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 确认：状态变 ACKNOWLEDGED、记下是谁；而且持续评估不会把它抹掉")
	void acknowledgeKeepsThroughRepeatedEvaluation() {
		String subject = freshSubject("ack");
		AlertEvent created = store.recordFiring(AlertRule.RECONCILE_GAP, subject, verdict("7"), "窗口A",
				Instant.now());

		AlertEvent acked = store.acknowledge(created.fingerprint(), "值班-张三", Instant.now()).orElseThrow();
		assertThat(acked.state()).isEqualTo(AlertEvent.State.ACKNOWLEDGED);
		assertThat(acked.acknowledgedBy()).isEqualTo("值班-张三");
		assertThat(acked.acknowledgedAt()).isNotNull();
		assertThat(acked.needsAttention()).as("看过了就不算「未处理」").isFalse();

		// 系统再评估两遍 —— 它还在响，但人的确认不该被系统抹掉
		store.recordFiring(AlertRule.RECONCILE_GAP, subject, verdict("7"), "窗口B", Instant.now());
		AlertEvent again = store.recordFiring(AlertRule.RECONCILE_GAP, subject, verdict("7"), "窗口C",
				Instant.now());
		assertThat(again.state()).as("★ 系统每评估一遍就把人的确认抹掉一次的话，"
				+ "值班的人只会觉得「这个按钮时灵时不灵」").isEqualTo(AlertEvent.State.ACKNOWLEDGED);
		assertThat(again.acknowledgedBy()).isEqualTo("值班-张三");
	}

	@Test
	@DisplayName("没有署名的确认按「匿名」记 —— 但绝不写成 NULL（那是「系统标的」的样子）")
	void acknowledgeWithoutNameIsAnonymousNotNull() {
		String subject = freshSubject("anon");
		AlertEvent created = store.recordFiring(AlertRule.ZERO_CACHE_HITS, subject, verdict("0 / 1"), "窗口A",
				Instant.now());
		AlertEvent acked = store.acknowledge(created.fingerprint(), "   ", Instant.now()).orElseThrow();
		assertThat(acked.acknowledgedBy()).isEqualTo("匿名");
	}

	@Test
	@DisplayName("★ 已恢复的告警不能被「确认」—— 那会把一条已经结束的历史说成是被人处理的")
	void resolvedCannotBeAcknowledged() {
		String subject = freshSubject("ack-resolved");
		AlertEvent created = store.recordFiring(AlertRule.ZERO_CACHE_HITS, subject, verdict("0 / 1"), "窗口A",
				Instant.now());
		store.recordResolved(created.fingerprint(), Instant.now());

		assertThat(store.acknowledge(created.fingerprint(), "张三", Instant.now())).isEmpty();
		assertThat(store.find(created.fingerprint()).orElseThrow().acknowledgedBy()).isNull();
	}

	@Test
	@DisplayName("确认一条不存在的指纹 → 空（端点据此回 404，而不是 500）")
	void acknowledgeUnknownIsEmpty() {
		assertThat(store.acknowledge("reconcile-gap|也没有这条", "张三", Instant.now())).isEmpty();
	}

	// ------------------------------------------------------------------
	//  3. 查询与计数
	// ------------------------------------------------------------------

	@Test
	@DisplayName("列表：按「严重度 → 第一次发现的时间」排，而且后者不随重复评估往后跑（否则老问题永远排最前）")
	void listOrderingIsBySeverityThenFirstSeen() {
		String ackSubject = freshSubject("order-ack");
		AlertEvent acked = store.recordFiring(AlertRule.UNPRICED_MODELS, ackSubject, verdict(ackSubject),
				"窗口A", Instant.now());
		store.acknowledge(acked.fingerprint(), "李四", Instant.now());
		String openSubject = freshSubject("order-open");
		store.recordFiring(AlertRule.UNPRICED_MODELS, openSubject, verdict(openSubject), "窗口A", Instant.now());

		var ours = store.firing().stream()
				.filter(e -> e.subject().equals(openSubject) || e.subject().equals(ackSubject)).toList();
		assertThat(ours).as("两条都在响着的列表里（已确认的不算「未处理」，但仍然是「在响」）").hasSize(2);
		assertThat(ours).extracting(AlertEvent::severity)
				.as("同一严重度 → 顺序由 first_seen_at 决定（库里的第一行先创建）")
				.containsOnly(AlertRule.Severity.WARNING);

		// 【这一条才是排序设计里真正要守住的东西】
		// 反复评估会让 last_seen_at 一直往后走。如果排序用的是它，那么一个
		// 持续了一周的老问题会永远排在新问题前面 —— 而"越老越靠前"的列表
		// 很快就没有人往下看了。所以排序里用的是 first_seen_at
		AlertEvent before = store.find(acked.fingerprint()).orElseThrow();
		for (int i = 0; i < 5; i++) {
			store.recordFiring(AlertRule.UNPRICED_MODELS, ackSubject, verdict(ackSubject), "窗口" + i,
					Instant.now());
		}
		AlertEvent after = store.find(acked.fingerprint()).orElseThrow();
		assertThat(after.lastSeenAt()).as("last_seen_at 一直往后走").isAfterOrEqualTo(before.lastSeenAt());
		assertThat(after.firstSeenAt()).as("★ first_seen_at 纹丝不动 —— 它是排序键，"
				+ "也是「这个问题存在了多久」的唯一来源").isEqualTo(before.firstSeenAt());
		// 它仍然排在另一条后面（另一条先创建）—— 重复评估没有把它挤到前面去。
		// ⚠️ 这里断言的是**相对位置**，而不是"下标等于某个数"：库里还有别的测试留下的行，
		// 写死下标等于把这条断言绑在别的测试的行为上（而那正是"测试之间互相污染"的形态）
		List<AlertEvent> afterOrder = store.firing().stream()
				.filter(e -> e.subject().equals(openSubject) || e.subject().equals(ackSubject)).toList();
		assertThat(afterOrder).extracting(AlertEvent::subject).containsExactly(ackSubject, openSubject);
	}

	@Test
	@DisplayName("openCount 只数「没人看过」的 —— 它是界面顶部那个红点的唯一来源")
	void openCountCountsOnlyUnseenOnes() {
		long before = store.openCount();
		String subject = freshSubject("count");
		AlertEvent created = store.recordFiring(AlertRule.UNPRICED_MODELS, subject, verdict(subject), "窗口A",
				Instant.now());
		assertThat(store.openCount()).isEqualTo(before + 1);

		store.acknowledge(created.fingerprint(), "李四", Instant.now());
		assertThat(store.openCount()).as("已确认的不算未处理").isEqualTo(before);

		store.recordResolved(created.fingerprint(), Instant.now());
		assertThat(store.openCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("指纹是「判据代号 + 主体」：同一个判据的不同主体各占一行")
	void fingerprintSeparatesSubjects() {
		String subjectA = freshSubject("fp-a");
		String subjectB = freshSubject("fp-b");
		AlertEvent a = store.recordFiring(AlertRule.UNPRICED_MODELS, subjectA, verdict(subjectA), "窗口A",
				Instant.now());
		AlertEvent b = store.recordFiring(AlertRule.UNPRICED_MODELS, subjectB, verdict(subjectB), "窗口A",
				Instant.now());

		assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
		assertThat(a.fingerprint()).isEqualTo(AlertRule.UNPRICED_MODELS.code() + "|" + subjectA);
	}

	@Test
	@DisplayName("存储层不接受 null 参数时的错误要能被端点区分（不是 500 掩盖一切）")
	void storeRejectsBrokenInputWithItsOwnExceptionType() {
		// 这里故意触发一次 SQL 层的错误（字段超长）：它必须被包成 AlertStoreException ——
		// 端点按类型处理它，而一个裸的 SQLException 会让端点上冒出 500 + 堆栈
		String tooLong = "x".repeat(200);
		assertThatThrownBy(() -> store.recordFiring(AlertRule.RECONCILE_GAP, tooLong, verdict("1"), "窗口A",
				Instant.now()))
				.as("subject 超过 128 列宽 → 应该是一个我们自己包装过的存储异常")
				.isInstanceOf(JdbcAlertStore.AlertStoreException.class);
	}

}
