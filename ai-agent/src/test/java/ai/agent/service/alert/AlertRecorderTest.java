package ai.agent.service.alert;

import ai.agent.entity.alert.AlertEvent;
import ai.agent.entity.alert.RuntimeSnapshot;
import ai.agent.service.alert.AlertRule.CanarySplit;
import ai.agent.service.alert.AlertRule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>告警的"状态机"测试</b>（阶段四第 5 件：异常告警）。
 *
 * <h2>它盯的是"什么时候通知、什么时候标恢复"</h2>
 * 一个告警系统的价值不在"能算出异常"（那部分已经由 {@code AlertRuleCatalogTest} 测过），
 * 而在<b>它的通知时机</b>。做错这一处不会有任何报错，只会留下两种安静的事故：
 * <ul>
 *   <li><b>每次都通报</b> → 值班的人一天收到几百条一样的话，然后他开始不看 ——
 *       而那之后真正的新问题也被淹没。所以"持续响"时<b>绝不能</b>再通报；</li>
 *   <li><b>把「没评估」当成「已经好了」</b> → 一次数据库抖动就把所有告警刷成"已恢复"，
 *       而"故障看起来自己好了"是所有观测系统里最坏的假象 ——
 *       它会把正在排查的人直接劝退。</li>
 * </ul>
 *
 * <h2>为什么用内存替身而不是真库</h2>
 * 因为这个类要验证的是<b>状态转换的规则</b>，不是 SQL。真库那条路径由
 * {@code AlertStoreTest} 单独验（那里验的是 UPSERT 语义与去重）。
 * 混在一起的话，"UPSERT 写错了"与"通知时机写错了"会给出很像的失败信息。
 */
class AlertRecorderTest {

	/** 记录每一次通知 —— 它是本类唯一要盯的产出。 */
	private static final class RecordingRecorder extends AlertRecorder {

		private final List<String> notifications = new ArrayList<>();

		RecordingRecorder(AlertSnapshotQuery query, AlertStore store) {
			super(query, store);
		}

		@Override
		protected void notify(AlertEvent event, boolean firing) {
			notifications.add((firing ? "响:" : "恢复:") + event.fingerprint());
		}

	}

	/** 一个"永远返回同一份快照"的取数替身。 */
	private record FixedQuery(RuntimeSnapshot snapshot) implements AlertSnapshotQuery {

		@Override
		public RuntimeSnapshot collect(int days) {
			return snapshot;
		}

		@Override
		public List<String> unpricedModels(int days) {
			return snapshot.unpricedModels();
		}

		@Override
		public AlertRule.CanarySplit canarySplit(int days) {
			return snapshot.canary();
		}

		@Override
		public List<String> catalogNotes() {
			return List.of();
		}

	}

	/** 一个会炸的取数替身（模拟数据库抖动）。 */
	private record FailingQuery(String message) implements AlertSnapshotQuery {

		@Override
		public RuntimeSnapshot collect(int days) {
			throw new IllegalStateException(message);
		}

		@Override
		public List<String> unpricedModels(int days) {
			throw new IllegalStateException(message);
		}

		@Override
		public AlertRule.CanarySplit canarySplit(int days) {
			throw new IllegalStateException(message);
		}

		@Override
		public List<String> catalogNotes() {
			return List.of();
		}

	}

	// ------------------------------------------------------------------
	//  1. 新响 / 持续响 / 恢复
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 从「没响」变成「响」通报一次；一直响着不再通报（重复通报会让人不再看）")
	void onlyTransitionsAreNotified() {
		InMemoryAlertStore store = new InMemoryAlertStore();
		RecordingRecorder recorder = new RecordingRecorder(queryOf(badSnapshot()), store);

		AlertRecorder.Sweep first = recorder.evaluate(7);
		assertThat(first.fired()).as("第一次评估：这些判据是第一次响").isNotEmpty();
		assertThat(first.ongoing()).isEmpty();
		int notificationsAfterFirst = recorder.notifications.size();
		assertThat(notificationsAfterFirst).as("每条新响的告警通报一次").isEqualTo(first.fired().size());

		// 同一个坏状态再评估两次 —— 它还在响，但**不该**再通报
		AlertRecorder.Sweep second = recorder.evaluate(7);
		AlertRecorder.Sweep third = recorder.evaluate(7);
		assertThat(second.fired()).as("第二次不该再有「新响」").isEmpty();
		assertThat(second.ongoing()).as("它在 ongoing 里（页面要能显示「它响多久了」）").isNotEmpty();
		assertThat(third.ongoing()).hasSize(second.ongoing().size());
		assertThat(recorder.notifications).as("★ 持续响着**不再通报** —— 这是整个告警系统里最容易做错的一处")
				.hasSize(notificationsAfterFirst);
	}

	@Test
	@DisplayName("★ 从「响」变成「不响」要标恢复并通报一次 —— 而且历史行留着（不删）")
	void recoveryIsRecordedAndNotified() {
		InMemoryAlertStore store = new InMemoryAlertStore();
		RuntimeSnapshot bad = badSnapshot();
		RecordingRecorder badRecorder = new RecordingRecorder(queryOf(bad), store);
		AlertRecorder.Sweep first = badRecorder.evaluate(7);
		String fingerprint = first.fired().get(0).fingerprint();

		RecordingRecorder goodRecorder = new RecordingRecorder(queryOf(goodSnapshot()), store);
		AlertRecorder.Sweep recovered = goodRecorder.evaluate(7);

		assertThat(recovered.resolved()).as("这一轮全好了，之前响着的都要被标成恢复").isNotEmpty();
		assertThat(recovered.fired()).isEmpty();
		assertThat(goodRecorder.notifications).as("恢复也要通报一次（而且要能对上此前那条）")
				.anyMatch(n -> n.startsWith("恢复:"));
		assertThat(store.firing()).as("恢复之后不该还在「响着」的列表里").isEmpty();
		assertThat(store.find(fingerprint)).as("★ 但那一行必须留着 —— 删掉的话"
				+ "「它到底响过没有」这个问题在复盘时无从回答").isPresent()
				.get().satisfies(event -> {
					assertThat(event.state()).isEqualTo(AlertEvent.State.RESOLVED);
					assertThat(event.resolvedAt()).isNotNull();
					assertThat(event.firstSeenAt()).as("第一次发现的时间不能被改写成「刚才」").isNotNull();
				});
	}

	@Test
	@DisplayName("★ 修好又坏了 → 重新出现在「未处理」里，而且是一条全新的（确认记录被清掉）")
	void firingAgainAfterRecoveryReopens() {
		InMemoryAlertStore store = new InMemoryAlertStore();
		RecordingRecorder recorder = new RecordingRecorder(queryOf(badSnapshot()), store);
		String fingerprint = recorder.evaluate(7).fired().get(0).fingerprint();
		store.acknowledge(fingerprint, "张三", Instant.now());

		new RecordingRecorder(queryOf(goodSnapshot()), store).evaluate(7);
		assertThat(store.find(fingerprint).orElseThrow().state()).isEqualTo(AlertEvent.State.RESOLVED);

		AlertRecorder.Sweep reopened = new RecordingRecorder(queryOf(badSnapshot()), store).evaluate(7);
		assertThat(reopened.fired()).extracting(AlertEvent::fingerprint).contains(fingerprint);
		AlertEvent event = store.find(fingerprint).orElseThrow();
		assertThat(event.state()).as("又坏了 → 必须重新出现在「未处理」里，"
				+ "否则「修好过又坏了」会被静默地当成「一直没修好」").isEqualTo(AlertEvent.State.OPEN);
		assertThat(event.resolvedAt()).as("reopened 之后 resolvedAt 必须清掉").isNull();
		assertThat(event.acknowledgedBy()).as("新的一个 incident，上一轮的确认不算数").isNull();
	}

	@Test
	@DisplayName("确认过的告警持续响着 → 保持「已确认」，不会被重复计成「新响」")
	void acknowledgedStaysAcknowledgedWhileStillFiring() {
		InMemoryAlertStore store = new InMemoryAlertStore();
		RecordingRecorder recorder = new RecordingRecorder(queryOf(badSnapshot()), store);
		AlertRecorder.Sweep first = recorder.evaluate(7);
		// 这一份快照会同时触发三条判据（未配价的模型 / 对账差值 / 缓存命中为 0），
		// 所以三条都要确认 —— 只确认一条的话，"未处理"那个数字里剩下的两条会让下面那条断言失败，
		// 而那个失败看着像"确认没生效"（实际是测试只确认了一半）
		for (AlertEvent event : first.fired()) {
			store.acknowledge(event.fingerprint(), "值班-李四", Instant.now());
		}
		String fingerprint = first.fired().get(0).fingerprint();

		AlertRecorder.Sweep again = recorder.evaluate(7);

		assertThat(again.fired()).as("确认不等于解决 —— 它还在响").isEmpty();
		AlertEvent event = store.find(fingerprint).orElseThrow();
		assertThat(event.state()).isEqualTo(AlertEvent.State.ACKNOWLEDGED);
		assertThat(event.acknowledgedBy()).isEqualTo("值班-李四");
		assertThat(event.occurrences()).as("持续响着时 occurrences 一直涨 —— "
				+ "它是「这个异常是持续的还是偶尔的」的唯一线索").isGreaterThan(1);
		assertThat(store.openCount()).as("已确认的不算「未处理」（那是「还没人看过」的数字）").isZero();
	}

	// ------------------------------------------------------------------
	//  2. ★ 取数失败绝不能让告警变成"已恢复"
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 取数失败时抛出去、且绝不把在响的告警标成恢复 —— 「故障看起来自己好了」是最坏的假象")
	void collectionFailureNeverResolvesAnything() {
		InMemoryAlertStore store = new InMemoryAlertStore();
		new RecordingRecorder(queryOf(badSnapshot()), store).evaluate(7);
		int firingBefore = store.firing().size();
		assertThat(firingBefore).isPositive();

		RecordingRecorder broken = new RecordingRecorder(new FailingQuery("数据库抖了一下"), store);
		try {
			broken.evaluate(7);
			throw new AssertionError("取数失败必须抛出去，不能安静地当成本轮「什么都没发生」");
		}
		catch (IllegalStateException expected) {
			assertThat(expected).hasMessageContaining("数据库抖了一下");
		}

		assertThat(store.firing()).as("★ 一次查询失败**不能**让正在响的告警变成已恢复 —— "
				+ "那会把正在排查的人直接劝退").hasSize(firingBefore);
		assertThat(broken.notifications).as("取数失败时不该发出任何通知").isEmpty();
	}

	@Test
	@DisplayName("构建阶段的判据（本轮没评估）绝不会被标成恢复 —— 「没评估」与「没事了」是两件事")
	void skippedRulesAreNeverResolved() {
		InMemoryAlertStore store = new InMemoryAlertStore();
		RecordingRecorder recorder = new RecordingRecorder(queryOf(badSnapshot()), store);

		// 先手工塞一条"构建阶段门禁"的告警（模拟它曾经响过）
		AlertRule gate = AlertRule.GATE_EVAL_BASELINE_DRIFT;
		AlertEvent gateEvent = store.recordFiring(gate, "", Verdict.of("漂移", "逐位相等", "它是门禁"),
				"2026-09-12 ~ 2026-09-18", Instant.now());
		assertThat(store.firing()).isNotEmpty();

		recorder.evaluate(7);

		assertThat(store.find(gateEvent.fingerprint())).as("运行阶段的评估**不该**替构建阶段的门禁下结论 —— "
				+ "这里没有它的输入（语料、评测集、基线），凭什么说它好了？")
				.isPresent()
				.get().satisfies(event -> assertThat(event.state()).isEqualTo(AlertEvent.State.OPEN));
	}

	// ------------------------------------------------------------------
	//  3. 一轮评估的产出形状
	// ------------------------------------------------------------------

	@Test
	@DisplayName("一轮评估的产出：fired / resolved / ongoing 三者分开，且总数说得清")
	void sweepSplitsTheThreeKinds() {
		InMemoryAlertStore store = new InMemoryAlertStore();
		RecordingRecorder recorder = new RecordingRecorder(queryOf(badSnapshot()), store);

		AlertRecorder.Sweep first = recorder.evaluate(7);
		assertThat(first.firingTotal()).as("firingTotal = 新响 + 仍在响").isEqualTo(first.fired().size());
		assertThat(first.resolved()).isEmpty();
		assertThat(first.openCount()).as("未处理的条数要能直接读出来（顶部那个红点）")
				.isEqualTo(first.fired().size());
		assertThat(first.snapshot().windowLabel()).as("快照要跟着一起交出去 —— "
				+ "没有窗口的告警数字是不可复现的").contains("~");
	}

	// ------------------------------------------------------------------
	//  快照与替身
	// ------------------------------------------------------------------

	private static AlertSnapshotQuery queryOf(RuntimeSnapshot snapshot) {
		return new FixedQuery(snapshot);
	}

	/** 一份"有几条判据在响"的快照。 */
	private static RuntimeSnapshot badSnapshot() {
		// 【第 6 件】末尾两个参数是租户口径。这里刻意**同时给出**逐租户缓存与集中度：
		// 逐租户那条判据的展开依赖 tenantCaches，而集中度那条依赖 tenantShare ——
		// 只给其中一个的话，"每一条判据都能被触发"那条测试会以"缺一条判据"的形式失败
		return new RuntimeSnapshot(Instant.now(), "2026-09-12", "2026-09-18", true, List.of("模型甲"), 42, 1000, 958,
				2, 5000, 0, 0, 12, 30, CanarySplit.none(),
				List.of(new RuntimeSnapshot.TenantPoint("甲", 5000, 0)),
				new AlertRule.TenantShare("甲", 950, 1000));
	}

	/** 一份"全好"的快照。 */
	private static RuntimeSnapshot goodSnapshot() {
		return new RuntimeSnapshot(Instant.now(), "2026-09-12", "2026-09-18", true, List.of(), 0, 1000, 1000, 0,
				5000, 2000, 0, 12, 30, CanarySplit.none(), List.of(), null);
	}

	/**
	 * 内存版 {@link AlertStore}（测试替身）—— <b>语义必须与 {@code JdbcAlertStore} 一致</b>。
	 *
	 * <p>⚠️ 这里刻意把 UPSERT 的四条语义（occurrences+1、resolvedAt 清空、firstSeenAt 不动、
	 * acknowledged 不在这里清）也实现了一遍，因为 {@code AlertRecorderTest} 里
	 * 好几条断言依赖它们。<b>两份实现就有了分叉的风险</b> ——
	 * 所以 {@code AlertStoreTest} 用真库把同样这几条再验一遍：
	 * 内存替身保证"状态机的规则对"，真库保证"落地的语义也对"。
	 */
	static final class InMemoryAlertStore implements AlertStore {

		private final Map<String, AlertEvent> events = new LinkedHashMap<>();

		@Override
		public AlertEvent recordFiring(AlertRule rule, String subject, AlertRule.Verdict verdict, String windowLabel,
				Instant now) {
			String fingerprint = AlertEvent.fingerprintOf(rule, subject);
			AlertEvent existing = events.get(fingerprint);
			// 与 JdbcAlertStore 的四条 UPSERT 语义对齐：occurrences+1、firstSeenAt 不动、
			// resolvedAt 清空（见下面那个 null）、而 state 只在"新 incident"时回到 OPEN ——
			// **已确认的必须保持已确认**，否则系统每评估一遍就把人的确认抹掉一次
			boolean newIncident = existing == null || existing.state() == AlertEvent.State.RESOLVED;
			AlertEvent.State state = newIncident ? AlertEvent.State.OPEN : existing.state();
			AlertEvent event = new AlertEvent(fingerprint, rule.code(), subject == null ? "" : subject,
					rule.severity(), rule.phase(), state,
					existing == null ? now : existing.firstSeenAt(), now,
					existing == null ? 1 : existing.occurrences() + 1, null,
					newIncident ? null : existing.acknowledgedAt(),
					newIncident ? null : existing.acknowledgedBy(), verdict.observed(), verdict.threshold(),
					verdict.why(), rule.action(), windowLabel);
			events.put(fingerprint, event);
			return event;
		}

		@Override
		public Optional<AlertEvent> recordResolved(String fingerprint, Instant now) {
			AlertEvent existing = events.get(fingerprint);
			if (existing == null || existing.state() == AlertEvent.State.RESOLVED) {
				return Optional.empty();
			}
			AlertEvent resolved = new AlertEvent(existing.fingerprint(), existing.ruleCode(), existing.subject(),
					existing.severity(), existing.phase(), AlertEvent.State.RESOLVED, existing.firstSeenAt(),
					existing.lastSeenAt(), existing.occurrences(), now, existing.acknowledgedAt(),
					existing.acknowledgedBy(), existing.observed(), existing.threshold(), existing.why(),
					existing.action(), existing.windowLabel());
			events.put(fingerprint, resolved);
			return Optional.of(resolved);
		}

		@Override
		public List<AlertEvent> firing() {
			return events.values().stream().filter(e -> e.state().firing()).toList();
		}

		@Override
		public List<AlertEvent> recent(int limit) {
			List<AlertEvent> all = new ArrayList<>(events.values());
			return all.size() <= limit ? List.copyOf(all) : List.copyOf(all.subList(0, limit));
		}

		@Override
		public Optional<AlertEvent> find(String fingerprint) {
			return Optional.ofNullable(events.get(fingerprint));
		}

		@Override
		public Optional<AlertEvent> acknowledge(String fingerprint, String by, Instant now) {
			AlertEvent existing = events.get(fingerprint);
			if (existing == null || existing.state() == AlertEvent.State.RESOLVED) {
				return Optional.empty();
			}
			AlertEvent acked = new AlertEvent(existing.fingerprint(), existing.ruleCode(), existing.subject(),
					existing.severity(), existing.phase(), AlertEvent.State.ACKNOWLEDGED, existing.firstSeenAt(),
					existing.lastSeenAt(), existing.occurrences(), existing.resolvedAt(), now,
					by == null || by.isBlank() ? "匿名" : by.strip(), existing.observed(), existing.threshold(),
					existing.why(), existing.action(), existing.windowLabel());
			events.put(fingerprint, acked);
			return Optional.of(acked);
		}

		@Override
		public long openCount() {
			return events.values().stream().filter(AlertEvent::needsAttention).count();
		}

	}

}
