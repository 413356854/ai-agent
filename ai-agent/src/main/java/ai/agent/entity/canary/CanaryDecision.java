package ai.agent.entity.canary;

/**
 * <b>灰度期间"这一次运行用哪一版提示词"的判定结果</b>（阶段四第 5 件：灰度发布）。
 *
 * <h2>为什么判定结果是「一个带理由的对象」，而不是一个版本号</h2>
 * 灰度发布最需要防的失败不是"分错了比例"，而是「<b>配置写着在放量，实际一份都没分到</b>」——
 * 那种情况下所有功能都正常、日志干干净净、页面上的配置也还在，唯一的问题是
 * 那次实验<b>等于没做</b>，而人以为做过了。
 *
 * <p>所以这里刻意把"为什么走了这一版"落成一个显式的对象：
 * <ul>
 *   <li>{@link #candidate()} —— 到底走了候选版没有（这是"分流有没有真的发生"的唯一凭据）；</li>
 *   <li>{@link #bucket()} / {@link #weightPercent()} —— 分桶值与重量。
 *       ★ 这两个数一起交付，是因为"这次为什么走新版"必须是<b>可复算的</b>：
 *       拿同一个运行 id 重算，得到的桶值必须一模一样；</li>
 *   <li>{@link #reason()} —— 没走候选版时是哪一档原因（没开灰度 / 关了 / 重量 0 / 分桶没中）。
 *       没有它的话，"没走新版"有五种可能而日志上完全一样，
 *       排查就得从配置一路查到哈希函数。</li>
 * </ul>
 *
 * @param version 本次生效的版本号
 * @param fingerprint 本次生效版本的指纹（形如 {@code v4:9ab12cd34ef5}）
 * @param candidate 是否走了候选（灰度）版本
 * @param bucket 稳定哈希分桶值 0..99（没参与分流时为 -1）
 * @param weightPercent 本次分流的重量（%，没开灰度时为 0）
 * @param reason 判定理由（一句话，直接进日志与 trace）
 */
public record CanaryDecision(int version, String fingerprint, boolean candidate, int bucket, int weightPercent,
		String reason) {

	/**
	 * 要落到 trace 里的<b>变体标签</b>。
	 *
	 * <h2>★ 为什么这里给的是 "canary" / "stable" 而不是"走了候选版没有"</h2>
	 * 因为 {@code agent_step.prompt_fingerprint} 是<b>事后回答"那一版表现怎么样"的唯一入口</b>。
	 * 如果只记指纹不记分组，那么事后要判断某次运行属于哪一组，就得反过来查
	 * "那一版的正文……"—— 而版本号会变、正文会改，那个推断迟早会错。
	 * 分组是<b>判定当时的一个事实</b>，它必须与指纹一起被记下来。
	 *
	 * <p>空串表示"没有参与灰度"（灰度没开），而不是"走了稳定版"：
	 * 前者是"这次实验不包含它"，后者是"它是对照组"。两者混起来会让
	 * 灰度报告的参与率虚高。
	 */
	public String variant() {
		if (weightPercent <= 0) {
			return "";
		}
		return candidate ? LABEL_CANARY : LABEL_STABLE;
	}

	/** 灰度组的标签（对照组与实验组的取值只在这里定义一次）。 */
	public static final String LABEL_CANARY = "canary";

	/** 对照组的标签。 */
	public static final String LABEL_STABLE = "stable";

	/** 没参与分流（灰度没开）时的判定：直接读当前生效版本。 */
	public static CanaryDecision stable(int version, String fingerprint, String reason) {
		return new CanaryDecision(version, fingerprint, false, -1, 0, reason);
	}

	/** 一句话摘要（日志与界面上都用它，两处不各写一份）。 */
	public String describe() {
		if (weightPercent <= 0) {
			return "v" + version + "（" + reason + "）";
		}
		return "v" + version + "（" + (candidate ? LABEL_CANARY : LABEL_STABLE) + " bucket=" + bucket + "/"
				+ weightPercent + "%，" + reason + "）";
	}

}
