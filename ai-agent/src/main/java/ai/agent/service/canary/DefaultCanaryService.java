package ai.agent.service.canary;

import ai.agent.entity.canary.CanaryDecision;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.entity.prompt.PromptVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link CanaryService} 的默认实现（阶段四第 5 件）。
 *
 * <h2>分流函数的三个决定，每一个都可以被反驳，所以写在这里</h2>
 * <ol>
 *   <li><b>哈希算法用 SHA-256，不用 {@code String.hashCode()}</b>。
 *       后者在不同 JVM 实现之间<b>不保证一致</b>（它只是 Object.hashCode 的一个约定），
 *       而"分桶"这件事一旦在两次运行之间不稳定，实验就会被悄悄切成两半 ——
 *       表现出来是"新版那一组的数字莫名其妙地不稳定"，没人会想到是哈希；</li>
 *   <li><b>分桶的输入是运行 id，不是会话 id，也不是随机数</b>。
 *       运行 id 是"每一次运行一个"，所以两组之间的样本**互相独立**
 *       （用会话 id 会让同一段对话的所有运行落在同一组：样本量看似够，
 *       实际有效样本数是"对话数"而不是"运行数"）。用随机数就更糟 ——
 *       同一个运行 id 两次判定会得到两个结果，于是"这次走的是哪一组"
 *       在落库与读取之间就可能不一致；</li>
 *   <li><b>取模 100 而不是取 0..1 的浮点</b>。整数分桶不存在"边界上抖动"这种问题，
 *       而且桶值可以直接印在界面上（"bucket=17/20%"比"hash=0.17"更容易被人核对）。</li>
 * </ol>
 *
 * <h2>判定结果里为什么带着 reason</h2>
 * 见 {@link CanaryDecision} 的注释：没走候选版有四种原因，而它们在日志里长得一样。
 * 排查"为什么灰度没生效"时，那四种的处置完全不同（分别是：没开 / 停了 / 重量是 0 / 没分中）。
 */
@Service
public class DefaultCanaryService implements CanaryService {

	private static final Logger log = LoggerFactory.getLogger(DefaultCanaryService.class);

	/** 分桶空间。100 与"重量就是百分数"对齐 —— 少一次单位换算就少一次算错的机会。 */
	static final int BUCKETS = 100;

	private final CanaryStore store;

	private final PromptService prompts;

	public DefaultCanaryService(CanaryStore store, PromptService prompts) {
		this.store = store;
		this.prompts = prompts;
	}

	// ------------------------------------------------------------------
	//  读配置
	// ------------------------------------------------------------------

	@Override
	public Optional<Canary> current(PromptKind kind) {
		return store.active(kind).map(CanaryStore.Row::toCanary);
	}

	/** 上一轮实验（含已停止的）—— 界面上"上一次实验的结论"读它。 */
	@Override
	public Optional<Canary> previous(PromptKind kind) {
		return store.latest(kind).map(CanaryStore.Row::toCanary);
	}

	// ------------------------------------------------------------------
	//  开 / 停
	// ------------------------------------------------------------------

	@Override
	public Canary start(PromptKind kind, int candidateVersion, int weightPercent, String note, String by) {
		// ---- 四个前置条件，每一个都对应一种"看起来开了其实没用"的失败 ----
		PromptVersion candidate = prompts.versions(kind).stream()
				.filter(v -> v.version() == candidateVersion)
				.findFirst()
				.orElseThrow(() -> new CanaryRejectedException(
						"没有第 %d 版 kind=%s —— 候选版本必须已经存在（先保存那一版，再开灰度）"
								.formatted(candidateVersion, kind.defaultValue())));

		Optional<PromptVersion> active = prompts.activeVersion(kind);
		if (active.isPresent() && active.get().version() == candidateVersion) {
			throw new CanaryRejectedException(
					"第 %d 版就是当前生效那一版 —— 那样两组跑的是同一份提示词，实验什么都证明不了，"
							.formatted(candidateVersion)
							+ "而报告上两侧数字会漂亮地几乎一样。想全量上线请走 /activate（那不是灰度，是发布）");
		}
		if (weightPercent < 1 || weightPercent > 99) {
			throw new CanaryRejectedException(
					"重量必须在 1..99 之间（现在 %d）：0 等于没开却看起来开着，100 等于全量发布。"
							.formatted(weightPercent)
							+ "两者都不该用灰度来表达 —— 前者是「先别开」，后者是「activate」");
		}
		if (note == null || note.isBlank()) {
			throw new CanaryRejectedException("必须写清这次实验想验证什么（note）—— "
					+ "不写的话，一周后没人记得，唯一的结局就是忘了关");
		}

		CanaryStore.Row row = store.start(kind, candidateVersion, weightPercent, note.strip(),
				by == null || by.isBlank() ? null : by.strip(), Instant.now());
		log.info("灰度已开启 kind={} candidate=v{} weight={}% by={} tokens(old={} new={})", kind.defaultValue(),
				candidateVersion, weightPercent, by,
				active.map(PromptVersion::tokenEstimate).orElse(0), candidate.tokenEstimate());
		return row.toCanary();
	}

	@Override
	public Canary stop(PromptKind kind, String endReason, String by) {
		if (endReason == null || endReason.isBlank()) {
			throw new CanaryRejectedException("必须写清为什么停（endReason）—— "
					+ "一次实验的结论常常就是那句话（推全 / 回滚 / 只是到期了）");
		}
		CanaryStore.Row row = store.stop(kind, endReason.strip(), by == null || by.isBlank() ? null : by.strip(),
				Instant.now())
				.orElseThrow(() -> new CanaryStoppedException(
						"这一份提示词现在没有进行中的灰度 —— 停止没有执行。"
								+ "⚠️「我停了」与「它本来就是停的」是两句话，"
								+ "合成一句会让两次点击都显示成功，而其中一次什么也没做"));
		log.info("灰度已停止 kind={} by={} reason={}", kind.defaultValue(), by, endReason);
		return row.toCanary();
	}

	// ------------------------------------------------------------------
	//  判定（每次运行开始时调用一次）
	// ------------------------------------------------------------------

	@Override
	public CanaryDecision decide(PromptKind kind, String runId) {
		Optional<CanaryStore.Row> currentRow = store.active(kind);
		if (currentRow.isEmpty()) {
			return CanaryDecision.stable(currentVersion(kind), currentFingerprint(kind), "没有进行中的灰度");
		}
		CanaryStore.Row canary = currentRow.get();

		// 【运行 id 为空时不参与分流】
		// 拿一个空串去分桶会让所有"没有运行 id"的运行挤进同一个桶 ——
		// 而那一组的数字会看起来"极其稳定"，因为它其实只有一份样本被数了很多次
		if (runId == null || runId.isBlank()) {
			log.warn("灰度进行中，但这次运行没有 id —— 不参与分流（走稳定版）。"
					+ "给空 id 分桶会让所有无 id 的运行挤进同一组，而那个组的数字看起来极其稳定");
			return CanaryDecision.stable(currentVersion(kind), currentFingerprint(kind),
					"没有运行 id，按「不参与分流」处理");
		}

		int bucket = bucketOf(runId);
		boolean inCanary = bucket < canary.weightPercent();
		if (!inCanary) {
			return new CanaryDecision(currentVersion(kind), currentFingerprint(kind), false, bucket,
					canary.weightPercent(), "分桶未命中（bucket=" + bucket + " ≥ " + canary.weightPercent() + "）");
		}

		// 走候选版：读那一版的正文与指纹。
		// 读不到时**不抛**，而是降级成稳定版并说清原因 ——
		// 一次"候选版本被删了"不该让所有命中该桶的运行全部失败（它们本来能正常跑）
		Optional<PromptVersion> candidate = prompts.versions(kind).stream()
				.filter(v -> v.version() == canary.candidateVersion())
				.findFirst();
		if (candidate.isEmpty()) {
			log.warn("灰度配置指向第 {} 版，但那一版读不到 —— 这次运行降级走稳定版（kind={}）",
					canary.candidateVersion(), kind.defaultValue());
			return CanaryDecision.stable(currentVersion(kind), currentFingerprint(kind),
					"候选版 v" + canary.candidateVersion() + " 读不到，降级走稳定版");
		}
		PromptVersion version = candidate.get();
		return new CanaryDecision(version.version(), version.fingerprint(), true, bucket, canary.weightPercent(),
				"分桶命中（bucket=" + bucket + " < " + canary.weightPercent() + "%）");
	}

	private int currentVersion(PromptKind kind) {
		return prompts.activeVersion(kind).map(PromptVersion::version).orElse(0);
	}

	@Override
	public Optional<String> candidateContent(PromptKind kind, int version) {
		return prompts.versions(kind).stream()
				.filter(v -> v.version() == version)
				.map(PromptVersion::content)
				.findFirst();
	}

	private String currentFingerprint(PromptKind kind) {
		return prompts.currentFingerprint(kind);
	}

	/**
	 * 稳定分桶：运行 id → 0..99。
	 *
	 * <p>取 SHA-256 的前 4 个字节拼成整数再取模。用前 4 字节而不是第 1 个字节：
	 * 一个字节只有 256 个取值，取模 100 之后分布不再均匀
	 * （256 = 2×100 + 56，余数区间被多算一次）—— 那会让某些桶的概率高出一倍，
	 * 而表现出来的只是"比例有点偏"，看起来像巧合。
	 */
	static int bucketOf(String runId) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(runId.getBytes(StandardCharsets.UTF_8));
			int value = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8)
					| (hash[3] & 0xFF);
			return Math.floorMod(value, BUCKETS);
		}
		catch (NoSuchAlgorithmException e) {
			// SHA-256 是 JDK 必须提供的算法，走到这里说明 JVM 坏了
			throw new IllegalStateException("JVM 不支持 SHA-256 —— 无法做灰度分流", e);
		}
	}

	// ------------------------------------------------------------------
	//  观测（实验到底有没有按比例发生）
	// ------------------------------------------------------------------

	@Override
	public Split observation(PromptKind kind, int days) {
		CanaryStore.Observation raw = store.observe(days);
		Optional<CanaryStore.Row> latest = store.latest(kind);
		int candidateVersion = latest.map(CanaryStore.Row::candidateVersion).orElse(0);
		int weight = latest.map(CanaryStore.Row::weightPercent).orElse(0);

		// 逐版本表按（变体, 版本号）排：对照组在前 —— 读报告的人是先看对照再看实验组的
		List<VersionRuns> rows = new ArrayList<>();
		for (CanaryStore.RunVersion row : raw.versions()) {
			rows.add(new VersionRuns(row.version(), row.variant(), row.runs()));
		}
		rows.sort(Comparator.comparingInt((VersionRuns r) -> variantRank(r.variant()))
				.thenComparing(Comparator.comparingInt(VersionRuns::version).reversed()));

		return new Split(candidateVersion, weight, raw.participatingRuns(), raw.candidateRuns(), raw.stableRuns(),
				raw.runs(), rows);
	}

	private static int variantRank(String variant) {
		if (CanaryDecision.LABEL_STABLE.equals(variant)) {
			return 0;
		}
		if (CanaryDecision.LABEL_CANARY.equals(variant)) {
			return 1;
		}
		return 2;
	}

	/** 停一次"其实没在跑"的灰度（端点据此回 409）。 */
	public static class CanaryStoppedException extends RuntimeException {

		public CanaryStoppedException(String message) {
			super(message);
		}

	}

}
