package ai.agent.service.canary;

import ai.agent.entity.canary.CanaryDecision;
import ai.agent.service.prompt.PromptKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * <b>灰度发布</b>：让一个新版提示词只作用于一部分运行（阶段四第 5 件）。
 *
 * <h2>它补的是哪一句话</h2>
 * 第 3 件（Prompt 版本管理）让"改哪一版、能不能回滚、<b>当时用的是哪一版</b>"
 * 成为有据可查的事实，而它<b>答不了"哪一版更好"</b> —— 台账里把这一条
 * 明确列成"还不能讲"的缺口之一。灰度发布就是那条缺失的链路：
 * 让新版只作用于一部分运行，于是两组之间**有得比**。
 *
 * <h2>★ 分流是「算出来的」，不是「存下来的」</h2>
 * 这是本件唯一一处必须守住的设计。{@link #decide} 拿运行 id 取一个<b>稳定哈希</b>
 * （SHA-256 取模 100），再与配置的重量比 —— 于是：
 * <ul>
 *   <li>同一份运行 id <b>永远</b>落同一个桶（不是"随机一次"）：一次运行不会
 *       在两次读取之间换组，重启服务也不换；</li>
 *   <li>因此<b>不需要</b>一张"运行 → 分组"的映射表，也就不存在
 *       "映射表与配置不一致"这个状态。</li>
 * </ul>
 * 存映射表还有一个更坏的后果：它让"这次走的是新版"变成一个<b>可以被改写</b>的字段，
 * 而分组一旦能被改写，"两组对比"就失去了全部意义。
 *
 * <p>⚠️ 代价必须说清：分流函数改了（比如换成另一种哈希），
 * <b>已经跑过的运行属于哪一组不会跟着变</b>（那是历史），但新运行的分布会变。
 * 所以真实分组同时落进了 trace（{@code canary} / {@code stable}），
 * 报告读的是那个字段，而不是"现在重算一遍"。
 *
 * <h2>为什么停止灰度时必须写清「为什么停」</h2>
 * 一次灰度是一次实验，而实验的结论常常就是"为什么停"那句话
 * （推全 / 回滚 / 只是到期了）。不写它的话，事后只能看到"某天它停了"。
 */
public interface CanaryService {

	/** 当前配置（没有进行中的灰度时为空）。 */
	Optional<Canary> current(PromptKind kind);

	/**
	 * 上一轮实验（含已经停止的）。
	 *
	 * <p>它与 {@link #current} 是两个问题：{@code current} 回答"现在在放量吗"，
	 * 这个回答"<b>上一轮实验当时是怎么配的</b>"。停止一次灰度之后，
	 * 后者是唯一还能看到"当时配了多少重量、为什么停"的地方 ——
	 * 一次实验的结论不该随着"点了一下停止"而消失。
	 */
	Optional<Canary> previous(PromptKind kind);

	/**
	 * 开一次灰度。
	 *
	 * <h2>四个前置条件，每一个都对应一种"看起来开了其实没用"的失败</h2>
	 * <ol>
	 *   <li><b>候选版本必须存在</b> —— 否则每一次分流都会退化成"读不到就兜底"，
	 *       而表现为"灰度的结论是没有区别"；</li>
	 *   <li><b>候选版本不能就是当前生效那一版</b> —— 那样两组跑的是<b>同一份提示词</b>，
	 *       实验什么都证明不了，而报告上两侧数字会漂亮地"几乎一样"；</li>
	 *   <li><b>重量必须在 1..99</b> —— 0 等于没开（却看起来开着），100 等于全量发布
	 *       （那不是灰度，是发布，它该走 activate）；</li>
	 *   <li><b>必须写实验假设</b> —— 没写的话，一周后没人记得这次想看什么，
	 *       唯一的结局就是"忘了关"。</li>
	 * </ol>
	 *
	 * @throws CanaryRejectedException 上面任一条不满足
	 */
	Canary start(PromptKind kind, int candidateVersion, int weightPercent, String note, String by);

	/**
	 * 停一次灰度。
	 *
	 * <p>{@code endReason} 必填（见接口注释）。停了之后新的运行全部回到稳定版 ——
	 * <b>已经跑过的不受影响</b>，它们的 trace 里仍然记着当时那一组。
	 */
	Canary stop(PromptKind kind, String endReason, String by);

	/**
	 * 判定这一次运行用哪一版。<b>每次运行开始时调用一次</b>。
	 *
	 * <p>为什么是"每次运行"而不是"每一步"：与热更新的粒度对齐
	 * （同一次运行中途换版本，会让同一份对话前后两轮遵循不同指令，
	 * 于是"它为什么这么选"变得无法解释 —— 这是第 3 件就定下的取舍）。
	 *
	 * @param runId 运行 id（分桶的输入）。空/空白时<b>不参与分流</b>：
	 *              拿一个空串去分桶会让所有无 id 的运行挤进同一组，
	 *              而那个组的数字会看起来"极其稳定"
	 */
	CanaryDecision decide(PromptKind kind, String runId);

	/**
	 * 指定版本的正文（灰度判定命中候选版时，循环用它去读正文）。
	 *
	 * <h2>为什么这个方法必须存在，而不是让循环自己调 {@code PromptService}</h2>
	 * 因为 {@code PromptService} 只回答"<b>当前生效</b>那一版"的正文 ——
	 * 而灰度恰恰要的是<b>另一版</b>。让循环去 {@code versions(kind)} 里自己筛一遍
	 * 会把"哪一版是候选"这个知识复制到调用点上，而那个知识只有灰度这一层有。
	 *
	 * @return 那一版的正文；版本不存在时为空（调用方决定怎么处理 ——
	 *         循环的选择是抛出去，理由见 {@code AgentLoop.candidateContent}）
	 */
	java.util.Optional<String> candidateContent(PromptKind kind, int version);

	/**
	 * 观测到的分流分布（时间窗内）。
	 *
	 * <p>它回答的是"<b>实际分到多少</b>"，而不是"配置写了多少" ——
	 * 这正是那条告警判据要的输入（配置 20% 而实测 0%，说明这次实验等于没做）。
	 */
	Split observation(PromptKind kind, int days);

	/**
	 * 哪一种提示词可以被灰度？—— <b>只有 Agent 循环那一份</b>。
	 *
	 * <h2>为什么把它做成一个常量，而不是让调用方随便传</h2>
	 * 灰度成立的前提是"提示词在<b>每次运行开始时</b>读一次"。
	 * 满足这条的只有 {@code AGENT_LOOP}：
	 * {@code CHAT_SYSTEM} 那一份的注入点在<b>每次请求</b>上（见 {@code PromptKind} 的注释里
	 * 那段"为什么两份的读法不一样"），而流式对话与结构化输出都带着自己的会话上下文 ——
	 * 在那里"按运行 id 分流"没有意义（那两种链路里没有一个稳定的"一次运行"）。
	 *
	 * <p>所以界面上那个下拉框不是"可切换"的：做成可切换会让读者以为两份都能灰度，
	 * 而选了另一份的后果是"灰度开了但一份都分不到"（与 {@code canary-no-traffic}
	 * 那条判据描述的失败一致）—— 一个本该被报警的状态，不该由界面主动制造。
	 */
	PromptKind CANARY_KIND = PromptKind.AGENT_LOOP;

	/**
	 * 一次灰度的配置。
	 *
	 * @param kind 哪一份提示词
	 * @param candidateVersion 候选版本号
	 * @param weightPercent 放量比例（%）
	 * @param startedBy 谁开的
	 * @param startedAt 什么时候开的
	 * @param note 这次实验想验证什么（必填）
	 * @param active 现在还在放量吗（{@code endedAt == null}）
	 * @param endedAt 停止时间（还在放量时为 null）
	 * @param endedBy 谁停的
	 * @param endReason 为什么停
	 */
	record Canary(PromptKind kind, int candidateVersion, int weightPercent, String startedBy, Instant startedAt,
			String note, boolean active, Instant endedAt, String endedBy, String endReason) {

		/** 匹配用的一把"这一版就是这个候选版吗"。 */
		public boolean matches(int version) {
			return version == candidateVersion;
		}

	}

	/**
	 * 一次运行的归属。
	 *
	 * @param runId 运行 id
	 * @param version 这次运行实际用的版本号
	 * @param variant {@code canary} / {@code stable}；空串 = 没参与分流
	 */
	record RunVariant(String runId, int version, String variant) {

		public boolean canary() {
			return CanaryDecision.LABEL_CANARY.equals(variant);
		}

		public boolean stable() {
			return CanaryDecision.LABEL_STABLE.equals(variant);
		}

	}

	/**
	 * 观测到的分流分布。
	 *
	 * @param candidateVersion 配置里的候选版本号（没开灰度时为 0）
	 * @param weightPercent 配置里的重量
	 * @param participatingRuns 参与了分流的运行数（灰度期间新起的那些）
	 * @param candidateRuns 其中真的用了候选版本的
	 * @param stableRuns 其中走了稳定版的
	 * @param runs 窗口内全部有新步骤的运行数（含灰度之前起的）—— 它是分母，
	 *            报告上要能看出"参与率"（多久才攒够样本）
	 * @param versions 逐版本的运行数（按版本号降序）—— 报告的主表
	 */
	record Split(int candidateVersion, int weightPercent, int participatingRuns, int candidateRuns, int stableRuns,
			int runs, List<VersionRuns> versions) {

		public Split {
			versions = versions == null ? List.of() : List.copyOf(versions);
		}

		/** 实测的候选占比（没有参与运行时为 0，**不是** NaN —— 界面上不该出现 NaN）。 */
		public double observedRatio() {
			return participatingRuns == 0 ? 0.0 : (double) candidateRuns / participatingRuns;
		}

		/** 一份都还没分到（配置开着但实验等于没做）。 */
		public boolean noTraffic() {
			return participatingRuns == 0;
		}

	}

	/** 逐版本的运行数。 */
	record VersionRuns(int version, String variant, int runs) {
	}

	/** 校验不通过（端点据此回 400，并把判据说清）。 */
	class CanaryRejectedException extends RuntimeException {

		public CanaryRejectedException(String message) {
			super(message);
		}

	}

}
