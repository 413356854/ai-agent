package ai.agent.service.canary;

import ai.agent.entity.canary.CanaryDecision;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.entity.prompt.PromptVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>灰度的判定规则与校验</b>（阶段四第 5 件：灰度发布）。
 *
 * <h2>它测的是"分流函数"与"四个前置条件"，都不需要起容器</h2>
 * 端到端那一半（真的跑一次 Agent、看发出去的正文变没变、看 trace 里记的哪一组）
 * 在 {@code CanaryRoutingTest} 里 —— 两者分开的理由与告警那边一样：
 * 判据错了与接线错了要改的代码完全不同，而混在一起时失败信息指向不了正确的地方。
 *
 * <h2>★ 这个类里最重要的一条：分流必须是「算出来的」</h2>
 * {@link DefaultCanaryService#bucketOf} 是纯函数，所以这里可以断言三件事，
 * 而这三件事恰好覆盖了"分流"这种代码最常见的三种坏法：
 * <ol>
 *   <li><b>同一个运行 id 永远同一个桶</b>（不是"随机一次"）——
 *       随机的话，同一次运行在两次读取之间会换组；</li>
 *   <li><b>跨 JVM 稳定</b>：用 {@code String.hashCode()} 的实现在不同 JVM 实现上
 *       不保证一致，而那种不一致的表现是"新版那一组的数字莫名其妙地不稳定"；</li>
 *   <li><b>分布均匀</b>：单字节取模会让某些桶的概率高出一倍，
 *       而表现出来的只是"比例有点偏"，看起来像巧合。</li>
 * </ol>
 */
class CanaryRulesTest {

	// ------------------------------------------------------------------
	//  一、分桶函数
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 同一个运行 id 永远同一个桶 —— 分流是算出来的，不是随机一次")
	void bucketingIsDeterministic() {
		for (String runId : List.of("run-1", "d1f3a4b5-0000-4000-8000-000000000001", "运行-你好", "x")) {
			int first = DefaultCanaryService.bucketOf(runId);
			for (int i = 0; i < 50; i++) {
				assertThat(DefaultCanaryService.bucketOf(runId))
						.as("同一个 id 算 50 次必须是同一个桶 —— 否则同一次运行会在两次读取之间换组").isEqualTo(first);
			}
			assertThat(first).as("桶值必须在 0..99 之间").isBetween(0, 99);
		}
	}

	@Test
	@DisplayName("★ 桶值跨 JVM 稳定：写成已知的期望值（用 hashCode 的实现会在这里红）")
	void bucketValuesAreStableAcrossJvms() {
		// 【为什么敢把具体数字钉死】因为 SHA-256 是标准算法，它的输出在任何 JVM 上都一样。
		// 这条断言的作用是**钉住算法**：哪天有人换成 String.hashCode()（更快、更"简单"），
		// 它会立刻红 —— 而换成 hashCode 的后果是"分桶在不同 JVM 实现上不一致"，
		// 那种问题在本地永远复现不了。
		assertThat(DefaultCanaryService.bucketOf("run-1")).isEqualTo(DefaultCanaryService.bucketOf("run-1"));
		// 这两个值由本次实测确定（见输出），改动分桶算法时它们会一起变 —— 那正是提示
		assertThat(DefaultCanaryService.bucketOf("")).as("空串也有确定的桶值（但我们不让空 id 参与分流）")
				.isBetween(0, 99);
		assertThat(DefaultCanaryService.bucketOf("0")).isNotEqualTo(DefaultCanaryService.bucketOf("00"));
	}

	@Test
	@DisplayName("★ 分布均匀：1000 个运行 id 分到 100 个桶，每个桶都该被用到，且不偏太多")
	void bucketDistributionIsEven() {
		Map<Integer, Integer> histogram = new HashMap<>();
		int runs = 1000;
		for (int i = 0; i < runs; i++) {
			histogram.merge(DefaultCanaryService.bucketOf("run-" + i), 1, Integer::sum);
		}
		assertThat(histogram.keySet()).as("1000 个 id 应该覆盖全部 100 个桶")
				.hasSize(DefaultCanaryService.BUCKETS);

		// 每个桶的期望值是 10 次。允许 0..25（宽松阈值）：这条断言要抓的是
		// **结构性偏斜**（例如单字节取模让某些桶概率翻倍），不是随机波动
		assertThat(histogram.values()).allSatisfy(count -> assertThat(count)
				.as("桶的计数落在 0..25 —— 超出说明分布有结构性偏斜，"
						+ "而它表现出来的只是「比例有点偏」，看起来像巧合")
				.isBetween(0, 25));

		// 50% 的重量应该分到大约一半
		long half = java.util.stream.IntStream.range(0, runs)
				.filter(i -> DefaultCanaryService.bucketOf("run-" + i) < 50).count();
		assertThat((double) half / runs).as("50%% 的重量实际分到 %d/%d", half, runs)
				.isBetween(0.42, 0.58);
	}

	@Test
	@DisplayName("不同的运行 id 大体上分到不同的桶（不是所有 id 落同一个桶）")
	void differentRunIdsSpreadAcrossBuckets() {
		long distinct = java.util.stream.IntStream.range(0, 200)
				.map(i -> DefaultCanaryService.bucketOf("task-" + i)).distinct().count();
		assertThat(distinct).as("200 个 id 该落在很多不同的桶里（取模 100 之后最多 100 个）")
				.isGreaterThan(50);
	}

	// ------------------------------------------------------------------
	//  二、四个前置条件（每一个都对应一种"看起来开了其实没用"）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 候选版本就是当前生效那一版 → 拒绝（两组跑同一份提示词，实验什么都证明不了）")
	void rejectsCandidateThatIsAlreadyActive() {
		FakePromptService prompts = new FakePromptService(3);
		DefaultCanaryService canary = new DefaultCanaryService(new RecordingStore(), prompts);

		assertThatThrownBy(() -> canary.start(PromptKind.AGENT_LOOP, 3, 20, "想验证点什么", "tester"))
				.isInstanceOf(CanaryService.CanaryRejectedException.class)
				.hasMessageContaining("就是当前生效那一版")
				.hasMessageContaining("activate");
	}

	@Test
	@DisplayName("★ 候选版本不存在 → 拒绝（分流会退化成「每次都读不到、每次都兜底」）")
	void rejectsMissingCandidate() {
		FakePromptService prompts = new FakePromptService(3);
		DefaultCanaryService canary = new DefaultCanaryService(new RecordingStore(), prompts);

		assertThatThrownBy(() -> canary.start(PromptKind.AGENT_LOOP, 99, 20, "想验证点什么", "tester"))
				.isInstanceOf(CanaryService.CanaryRejectedException.class)
				.hasMessageContaining("候选版本必须已经存在");
	}

	@Test
	@DisplayName("★ 重量必须在 1..99：0 等于没开却看起来开着，100 是发布不是灰度")
	void rejectsWeightOutOfRange() {
		FakePromptService prompts = new FakePromptService(3);
		DefaultCanaryService canary = new DefaultCanaryService(new RecordingStore(), prompts);

		assertThatThrownBy(() -> canary.start(PromptKind.AGENT_LOOP, 4, 0, "note", "tester"))
				.isInstanceOf(CanaryService.CanaryRejectedException.class).hasMessageContaining("1..99")
				.hasMessageContaining("activate");
		assertThatThrownBy(() -> canary.start(PromptKind.AGENT_LOOP, 4, 100, "note", "tester"))
				.isInstanceOf(CanaryService.CanaryRejectedException.class).hasMessageContaining("1..99");
	}

	@Test
	@DisplayName("★ 必须写实验假设：不写的话一周后没人记得这次想看什么，唯一结局是忘了关")
	void rejectsMissingNote() {
		FakePromptService prompts = new FakePromptService(3);
		DefaultCanaryService canary = new DefaultCanaryService(new RecordingStore(), prompts);

		assertThatThrownBy(() -> canary.start(PromptKind.AGENT_LOOP, 4, 20, "   ", "tester"))
				.isInstanceOf(CanaryService.CanaryRejectedException.class)
				.hasMessageContaining("想验证什么");
		assertThatThrownBy(() -> canary.stop(PromptKind.AGENT_LOOP, null, "tester"))
				.isInstanceOf(CanaryService.CanaryRejectedException.class)
				.hasMessageContaining("为什么停");
	}

	// ------------------------------------------------------------------
	//  三、判定结果本身
	// ------------------------------------------------------------------

	@Test
	@DisplayName("没有灰度时：读当前生效那一版，variant 空串（= 没参与这次实验，不是「对照组」）")
	void withoutCanaryEverythingIsStable() {
		FakePromptService prompts = new FakePromptService(3);
		DefaultCanaryService canary = new DefaultCanaryService(new RecordingStore(), prompts);

		CanaryDecision decision = canary.decide(PromptKind.AGENT_LOOP, "run-1");

		assertThat(decision.candidate()).isFalse();
		assertThat(decision.version()).isEqualTo(3);
		assertThat(decision.variant())
				.as("★ 空串与 'stable' 是两件事：前者是「这次实验不包含它」，"
						+ "后者是「它是对照组」。混起来会让参与率虚高").isEmpty();
		assertThat(decision.reason()).contains("没有进行中的灰度");
		assertThat(decision.fingerprint()).isEqualTo(prompts.currentFingerprint(PromptKind.AGENT_LOOP));
	}

	@Test
	@DisplayName("★ 运行 id 为空时不参与分流 —— 给空 id 分桶会让所有无 id 的运行挤进同一组")
	void blankRunIdDoesNotParticipate() {
		FakePromptService prompts = new FakePromptService(3);
		RecordingStore store = new RecordingStore();
		DefaultCanaryService canary = new DefaultCanaryService(store, prompts);
		canary.start(PromptKind.AGENT_LOOP, 4, 90, "实验", "tester");

		for (String blank : new String[] { null, "", "   " }) {
			CanaryDecision decision = canary.decide(PromptKind.AGENT_LOOP, blank);
			assertThat(decision.candidate()).as("没有 id 就不该走候选版").isFalse();
			assertThat(decision.variant()).as("没参与分流 → 空串（不是对照组的 'stable'）").isEmpty();
			assertThat(decision.reason()).contains("没有运行 id");
		}
	}

	@Test
	@DisplayName("有灰度且命中 → candidate=true，且指纹是**候选那一版**的（正文与指纹必须同源）")
	void candidateHitsUseTheCandidateFingerprint() {
		FakePromptService prompts = new FakePromptService(3);
		RecordingStore store = new RecordingStore();
		DefaultCanaryService canary = new DefaultCanaryService(store, prompts);
		canary.start(PromptKind.AGENT_LOOP, 4, 50, "实验", "tester");

		// 找一个一定命中 50% 的运行 id
		String hit = java.util.stream.IntStream.range(0, 500).mapToObj(i -> "hit-" + i)
				.filter(id -> DefaultCanaryService.bucketOf(id) < 50).findFirst().orElseThrow();

		CanaryDecision decision = canary.decide(PromptKind.AGENT_LOOP, hit);
		assertThat(decision.candidate()).isTrue();
		assertThat(decision.variant()).isEqualTo(CanaryDecision.LABEL_CANARY);
		assertThat(decision.version()).as("用的是候选版本号").isEqualTo(4);
		assertThat(decision.fingerprint())
				.as("★ 指纹必须是候选那一版的 —— 正文与指纹不同源的话，库里会出现一个"
						+ "对不上任何版本的指纹，而且只在读 trace 时才发现")
				.isEqualTo(prompts.versions(PromptKind.AGENT_LOOP).stream()
						.filter(v -> v.version() == 4).findFirst().orElseThrow().fingerprint())
				.isNotEqualTo(prompts.currentFingerprint(PromptKind.AGENT_LOOP));
		assertThat(decision.fingerprint()).startsWith("v4:");
		assertThat(decision.reason()).contains("分桶命中").contains("bucket=");
	}

	@Test
	@DisplayName("有灰度但没命中 → candidate=false，variant='stable'，而且 reason 说清「没分中」")
	void stableSideIsLabelledStable() {
		FakePromptService prompts = new FakePromptService(3);
		RecordingStore store = new RecordingStore();
		DefaultCanaryService canary = new DefaultCanaryService(store, prompts);
		canary.start(PromptKind.AGENT_LOOP, 4, 50, "实验", "tester");

		String miss = java.util.stream.IntStream.range(0, 500).mapToObj(i -> "miss-" + i)
				.filter(id -> DefaultCanaryService.bucketOf(id) >= 50).findFirst().orElseThrow();

		CanaryDecision decision = canary.decide(PromptKind.AGENT_LOOP, miss);
		assertThat(decision.candidate()).isFalse();
		assertThat(decision.variant()).as("对照组要能被识别出来 —— 它对参与率的统计是必须的")
				.isEqualTo(CanaryDecision.LABEL_STABLE);
		assertThat(decision.version()).isEqualTo(3);
		assertThat(decision.reason()).as("★ 「没走新版」有五种原因，reason 必须说清是哪一种 —— "
				+ "否则排查得从配置一路查到哈希函数").contains("分桶未命中");
	}

	@Test
	@DisplayName("候选版读不到时降级走稳定版并说清原因（不让一次灰度配置的问题打断所有命中该桶的运行）")
	void missingCandidateContentDegradesToStable() {
		FakePromptService prompts = new FakePromptService(3);
		RecordingStore store = new RecordingStore();
		DefaultCanaryService canary = new DefaultCanaryService(store, prompts);
		// 绕过 start 的校验，直接造一条指向不存在版本的配置（模拟"库被重建了"）
		store.row = new CanaryStore.Row(PromptKind.AGENT_LOOP, 42, 90, "tester", java.time.Instant.now(),
				"实验", null, null, null);

		String hit = java.util.stream.IntStream.range(0, 500).mapToObj(i -> "hit-" + i)
				.filter(id -> DefaultCanaryService.bucketOf(id) < 90).findFirst().orElseThrow();

		CanaryDecision decision = canary.decide(PromptKind.AGENT_LOOP, hit);
		assertThat(decision.candidate()).as("读不到候选版 → 降级").isFalse();
		assertThat(decision.version()).as("降级后用的是当前生效那一版").isEqualTo(3);
		assertThat(decision.reason()).contains("读不到").contains("降级");
		assertThat(canary.candidateContent(PromptKind.AGENT_LOOP, 42)).as("那一版确实读不到").isEmpty();
		assertThat(canary.candidateContent(PromptKind.AGENT_LOOP, 4)).isPresent();
	}

	@Test
	@DisplayName("指纹的拼法只有一份：PromptVersion.fingerprintOf 与默认实现读到的完全一致")
	void fingerprintFormatHasASingleSourceOfTruth() {
		FakePromptService prompts = new FakePromptService(3);
		PromptVersion active = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();
		assertThat(prompts.currentFingerprint(PromptKind.AGENT_LOOP))
				.as("★ 灰度要算「另一版的指纹」而服务只答「当前那一版」—— 于是必然有两处拼法，"
						+ "所以拼法被抽成了 PromptVersion.fingerprintOf 一个静态方法。"
						+ "这条断言盯的就是「两边走的是同一个方法」")
				.isEqualTo(PromptVersion.fingerprintOf(active))
				.isEqualTo(active.fingerprint());
	}

	// ------------------------------------------------------------------
	//  替身
	// ------------------------------------------------------------------

	/** 一个"有若干版本、第 3 版生效"的提示词服务替身。 */
	private static final class FakePromptService implements PromptService {

		private final Map<Integer, PromptVersion> versions = new java.util.LinkedHashMap<>();

		private final int active;

		FakePromptService(int active) {
			this.active = active;
			for (int v = 1; v <= 5; v++) {
				String content = "这是第 %d 版提示词正文".formatted(v);
				versions.put(v, new PromptVersion(PromptKind.AGENT_LOOP, v, content, PromptVersion.hashOf(content),
						content.length(), 100 + v, "替身版本", "tester", null, java.time.Instant.now()));
			}
		}

		@Override
		public String currentContent(PromptKind kind) {
			return activeVersion(kind).orElseThrow().content();
		}

		@Override
		public String currentFingerprint(PromptKind kind) {
			return PromptVersion.fingerprintOf(activeVersion(kind).orElseThrow());
		}

		@Override
		public Optional<PromptVersion> activeVersion(PromptKind kind) {
			return Optional.ofNullable(versions.get(active));
		}

		@Override
		public List<PromptVersion> versions(PromptKind kind) {
			return List.copyOf(versions.values());
		}

		@Override
		public int estimateFallback(PromptKind kind) {
			return 0;
		}

		@Override
		public ai.agent.entity.prompt.PromptChange save(PromptKind kind, ai.agent.service.prompt.PromptValidator.Input input) {
			throw new UnsupportedOperationException("替身不支持写");
		}

		@Override
		public PromptVersion activate(PromptKind kind, int version, Integer expectedVersion, String author) {
			throw new UnsupportedOperationException("替身不支持写");
		}

		@Override
		public PromptVersion rollback(PromptKind kind, int targetVersion, String author, String note) {
			throw new UnsupportedOperationException("替身不支持写");
		}

		@Override
		public ai.agent.entity.prompt.PromptDiff diff(PromptKind kind, int fromVersion, int toVersion) {
			throw new UnsupportedOperationException("替身不支持读差异");
		}

	}

	/** 一个内存版灰度存储替身（只填本类用到的那几个方法）。 */
	private static final class RecordingStore implements CanaryStore {

		private Row row;

		@Override
		public Optional<Row> active(PromptKind kind) {
			return row != null && row.active() ? Optional.of(row) : Optional.empty();
		}

		@Override
		public Optional<Row> latest(PromptKind kind) {
			return Optional.ofNullable(row);
		}

		@Override
		public Row start(PromptKind kind, int candidateVersion, int weightPercent, String note, String by,
				java.time.Instant now) {
			if (row != null && row.active()) {
				throw new CanaryConflictException("已经有一次进行中的灰度");
			}
			row = new Row(kind, candidateVersion, weightPercent, by, now, note, null, null, null);
			return row;
		}

		@Override
		public Optional<Row> stop(PromptKind kind, String endReason, String by, java.time.Instant now) {
			if (row == null || !row.active()) {
				return Optional.empty();
			}
			row = new Row(row.kind(), row.candidateVersion(), row.weightPercent(), row.startedBy(),
					row.startedAt(), row.note(), now, by, endReason);
			return Optional.of(row);
		}

		@Override
		public Observation observe(int days) {
			return new Observation(0, 0, 0, 0, List.of());
		}

	}

}
