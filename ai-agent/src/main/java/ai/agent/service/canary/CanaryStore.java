package ai.agent.service.canary;

import ai.agent.service.prompt.PromptKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 灰度配置与观测的存储（阶段四第 5 件）。
 *
 * <h2>★ 为什么这张表是「一份提示词一行」，而不是一张放量历史表</h2>
 * 因为"同时开着两个灰度"这件事在语义上就是错的：两个候选版本同时放量时，
 * 一次运行走的是哪一版取决于两次判定谁先跑 —— 而三次实验的结果会互相污染，
 * 事后完全分不开。所以 {@code PRIMARY KEY (kind)}，
 * 开第二次之前必须先停掉第一次（{@link #start} 会抛 {@link CanaryConflictException}）。
 *
 * <h2>为什么停止是「写 ended_at」而不是删掉这一行</h2>
 * 一次灰度是一次实验，事后必须能回答"它什么时候开的、开了多久、当时配的重量是多少"。
 * 删掉的话，那些走了候选版本的运行在库里就成了"不知道为什么用了那一版"——
 * 而"当时那一版是什么"恰恰是复盘的第一句话（与 {@code prompt_version}
 * 只追加是同一条纪律）。
 *
 * <h2>为什么观测分布也在这里查</h2>
 * 因为它要读的是 {@code agent_step}（数据侧），而 {@link CanaryService.Split}
 * 需要把配置侧与数据侧凑齐才能回答"配置 20%、实测多少"。
 * 拆到两个类去凑只会多出一次"两边窗口不一致"的机会 ——
 * 而窗口不一致时，那个比例看起来完全正常。
 */
public interface CanaryStore {

	/** 当前进行中的那一条（{@code ended_at IS NULL}）；没有时为 {@code Optional.empty()}。 */
	Optional<Row> active(PromptKind kind);

	/** 最近一次（含已停止的）—— 停止之后界面上仍要能看到"上一轮实验的结论"。 */
	Optional<Row> latest(PromptKind kind);

	/**
	 * 开一次灰度。
	 *
	 * <p>并发保护落在 SQL 上而不是"先查一次再判断"：后者在两个人都点"开始"时都会通过检查，
	 * 而结果是后写的那个静默覆盖前一个 —— 一次实验被另一次实验无声地替换掉，
	 * 页面上看不出任何异常（与 Prompt 激活那条 409 是同一个坑）。
	 *
	 * @throws CanaryConflictException 已经有一条进行中的灰度
	 */
	Row start(PromptKind kind, int candidateVersion, int weightPercent, String note, String by, Instant now);

	/**
	 * 停止（带前置条件：只有还在进行中的那条能被停）。
	 *
	 * <p>已经停了又停一次 → 返回空并由服务层回 409：**"我停了"与"它本来就是停的"
	 * 是两句话**，把它们合成一句会让"两次点击都成功"看起来很正常，
	 * 而其中一次其实什么也没做。
	 */
	Optional<Row> stop(PromptKind kind, String endReason, String by, Instant now);

	/**
	 * 观测窗口内的分流分布（数据侧）。
	 *
	 * @param days 时间窗（天），与告警评估用的是同一个窗口
	 * @return 原始计数；凑成 {@link CanaryService.Split} 由服务层做（口径在代码里，不在 SQL 里）
	 */
	Observation observe(int days);

	/**
	 * 一次观测查询的结果。
	 *
	 * @param runs 窗口内**有新步骤**的运行数（分母：参与率的分母）
	 * @param participatingRuns 其中带了灰度变体标签的（即灰度期间新起的运行）
	 * @param candidateRuns 其中走了候选版本的
	 * @param stableRuns 其中走了稳定版的
	 * @param versions 逐（版本号, 变体）的运行数 —— 报告的主表
	 */
	record Observation(int runs, int participatingRuns, int candidateRuns, int stableRuns,
			List<RunVersion> versions) {

		public Observation {
			versions = versions == null ? List.of() : List.copyOf(versions);
		}

	}

	/**
	 * 逐（版本号, 变体）的运行数。
	 *
	 * @param version 版本号（从指纹里解析出来；解析不出来时为 0）
	 * @param variant {@code canary} / {@code stable} / 空串（没参与分流）
	 * @param runs 运行数（按 run_id 去重 —— 一次运行有多个步骤，数步骤会把"跑得久的运行"加权）
	 */
	record RunVersion(int version, String variant, int runs) {
	}

	/** 一次灰度的配置行。 */
	record Row(PromptKind kind, int candidateVersion, int weightPercent, String startedBy, Instant startedAt,
			String note, Instant endedAt, String endedBy, String endReason) {

		public boolean active() {
			return endedAt == null;
		}

		public CanaryService.Canary toCanary() {
			return new CanaryService.Canary(kind, candidateVersion, weightPercent, startedBy, startedAt, note,
					active(), endedAt, endedBy, endReason);
		}

	}

	/** 已经有一条进行中的灰度（由服务层转成 400/409，见 {@link CanaryService#start}）。 */
	class CanaryConflictException extends RuntimeException {

		public CanaryConflictException(String message) {
			super(message);
		}

	}

	/** 灰度存储的异常类型。 */
	class CanaryStoreException extends RuntimeException {

		public CanaryStoreException(String message) {
			super(message);
		}

		public CanaryStoreException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
