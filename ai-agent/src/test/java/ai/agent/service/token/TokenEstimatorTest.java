package ai.agent.service.token;

import ai.agent.config.ChatCallProperties;
import ai.agent.service.impl.token.CharacterClassTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 估算器的行为测试。
 *
 * <h2>这个测试能证明什么、不能证明什么</h2>
 * <b>能证明</b>：不同字符类别的文本，估算值<b>按正确的方向</b>变化
 * （中文比同长度英文贵、Emoji 更贵、短消息有固定开销）。
 * 这些是"估算器有没有写错"的检查 —— 一旦有人把系数用反了、或把
 * {@code codePointAt} 改回 {@code charAt}，这里会立刻红。
 *
 * <p><b>不能证明</b>：估算值离真实值有多远。那需要真实模型的 usage 数字，
 * 属于"校准"，由 {@code TokenEstimatorLiveCalibrationTest} 负责
 * （它打真实接口、拿到真实 token 数，再把误差算出来）。
 * <b>把这两件事分开</b>很有必要：前者是每次提交都要跑的快速检查，
 * 后者是偶尔跑一次、结果已经实测记录的验证 —— 混在一起会让整个测试套件依赖外网和配额。
 */
class TokenEstimatorTest {

	private ChatCallProperties properties;

	private CharacterClassTokenEstimator estimator;

	@BeforeEach
	void setUp() {
		properties = new ChatCallProperties();
		estimator = new CharacterClassTokenEstimator(properties);
	}

	@Test
	@DisplayName("空输入返回 0，不抛异常（预算校验自己崩掉比不校验更糟）")
	void handlesEmptyInput() {
		assertThat(estimator.estimate(null)).isZero();
		assertThat(estimator.estimate("")).isZero();
	}

	@Test
	@DisplayName("同长度下中文远贵于英文 —— 这是「字符÷4」通式在中文上低估的根因")
	void cjkCostsFarMoreThanAscii() {
		// 两边都是 20 个字符，只有字符集不同
		String ascii = "abcdefghijklmnopqrst";
		String chinese = "一二三四五六七八九十一二三四五六七八九十";

		int asciiTokens = estimator.estimate(ascii);
		int chineseTokens = estimator.estimate(chinese);

		// 中文按 0.75 字/token ≈ 15；英文按 5.5 字符/token ≈ 4
		assertThat(chineseTokens).isGreaterThanOrEqualTo(15);
		assertThat(asciiTokens).isLessThanOrEqualTo(5);
		assertThat(chineseTokens).isGreaterThan(asciiTokens * 2);

		// 反过来说：拿英文系数去算中文会严重低估。
		// 这里用的是**实测**的英文密度（6.6 字符/token），比配置里的 5.5 还省 ——
		// 也就是说真实差距比这个断言更大
		int wrongIfUsingAsciiCoefficient = (int) Math.ceil(chinese.length() / 6.6);
		assertThat(wrongIfUsingAsciiCoefficient).isLessThan(chineseTokens / 2);
	}

	@Test
	@DisplayName("Emoji 与代理对按码点计数，不会被拆成两个 char")
	void handlesSurrogatePairs() {
		// 4 个补充平面 Emoji：每个占 2 个 char，共 8 个 char、4 个码点。
		// 若误用 charAt 遍历，会得到 8 个"字符"，估算值直接翻倍
		String emoji = "😀😀😀😀";
		assertThat(emoji.length()).isEqualTo(8);

		int tokens = estimator.estimate(emoji);
		// 4 个码点 × 1.5 token/char = 6
		assertThat(tokens).isEqualTo(6);
	}

	@Test
	@DisplayName("CJK 标点与全角标点按中文密度计（「，」「。」不该被当成英文）")
	void countsCjkPunctuation() {
		String punctuated = "你好，世界。";
		// 4 个汉字 + 2 个标点（U+FF0C 全角逗号、U+3002 句号）= 6 个字符。
		// 注意这两个标点<b>不</b>在 U+4E00 汉字区里，必须靠 0x3000–0x303F 与
		// 0xFF00–0xFFEF 两个区间单独覆盖 —— 少写一个区间，中文标点就会被当成"其他字符"
		// 按 1.5 算，长文档的估算会偏高（不危险，但没必要）
		assertThat(punctuated.length()).isEqualTo(6);
		// 6 个 CJK 字符 × 0.75 = 4.5 → 向上取整 5
		assertThat(estimator.estimate(punctuated)).isEqualTo(5);

		// 反向确认：标点走的是 CJK 分支，而不是"其他字符"分支（后者会算成 9）
		assertThat(estimator.estimate(punctuated)).isNotEqualTo(9);
	}

	@Test
	@DisplayName("每条消息多算固定开销：很多条短消息的场景不能系统性低估")
	void addsPerMessageOverhead() {
		ChatCallProperties.Estimation cfg = properties.getEstimation();
		assertThat(estimator.estimateMessage("你好")).isEqualTo(estimator.estimate("你好") + cfg.getTokensPerMessage());

		// 20 条短消息（就是对话记忆默认窗口的大小）的包装开销是 80 tokens ——
		// 这个量级已经不能被忽略，所以必须算进去
		int twentyShortMessages = 20 * estimator.estimateMessage("好的");
		assertThat(twentyShortMessages).isGreaterThanOrEqualTo(20 * cfg.getTokensPerMessage());
	}

	@Test
	@DisplayName("估算值与系数配置联动：调大 CJK 系数，估算值同步变大")
	void respectsConfiguredCoefficients() {
		properties.getEstimation().setCjkTokensPerChar(2.0);
		// 注意：估算器在构造时捕获了 Estimation 配置对象（引用），
		// 所以这里改的是同一个对象，新系数立刻生效 —— 这也说明系数是"可调的",
		// 校准后改配置即可，不需要改代码
		assertThat(estimator.estimate("一二三四五")).isEqualTo(10);
	}

	@Test
	@DisplayName("向上取整：宁可多留一点余量，也不要因为截断误差放过去一个刚好超限的请求")
	void roundsUp() {
		// 1 个 ASCII 字符 = 1/5.5 = 0.18 token → 向上取整为 1
		assertThat(estimator.estimate("a")).isEqualTo(1);
	}

	/**
	 * 校准回归：把<b>真实模型实测出来的 token 密度</b>钉成测试，
	 * 防止系数被无意改回"看起来很合理"的值。
	 *
	 * <h2>这组数字是怎么来的</h2>
	 * 用 {@code /api/diag/token/calibrate} 对同一段文本做长度扫描
	 * （重复 1/5/20/50/200 次），再用真实 usage 拟合出边际密度：
	 * <pre>
	 *   英文：0.152 token/字符  →  6.6 字符/token
	 *   中文：0.609 token/字符  →  0.61 token/字
	 *   固定开销（系统提示词 + 消息包装）：约 236 tokens
	 * </pre>
	 *
	 * <h2>为什么值得写成测试</h2>
	 * 因为"更保守"很容易被误当成"更安全"。本项目第一版系数就是
	 * <b>英文 3.5 字符/token、中文 1.0 token/字</b> —— 教科书式的取值，看着很合理，
	 * 实测却分别高估了 <b>约 1.9 倍和 1.6 倍</b>。这类偏差不会让任何功能报错，
	 * 只会让系统一直做多余的裁剪。把这个量级钉住，
	 * 下次有人想"调大一点更保险"时就会先看到这组真实数字。
	 */
	@Test
	@DisplayName("校准回归：长文本的估算落在实测密度附近（不许退回教科书式的保守值）")
	void estimateMatchesMeasuredDensity() {
		String english = "Virtual threads release their carrier thread whenever they block, which makes waiting inexpensive. ";
		String longEnglish = english.repeat(200);
		double englishDensity = estimator.estimate(longEnglish) / (double) longEnglish.length();
		// 实测密度 0.152，配置取 1/5.5 = 0.182（约 20% 余量）。
		// 上限 0.25 意味着"绝不允许再回到 1/3.5 = 0.286 那种高估"
		assertThat(englishDensity).isBetween(0.15, 0.25);

		String chinese = "虚拟线程在阻塞时会让出载体线程，等待不再昂贵。";
		String longChinese = chinese.repeat(200);
		double chineseDensity = estimator.estimate(longChinese) / (double) longChinese.length();
		// 实测密度 0.609，配置取 0.75（约 23% 余量）。
		// 上限 0.9 意味着"绝不允许再回到 1.0 那种高估"
		assertThat(chineseDensity).isBetween(0.6, 0.9);

		// 两条密度的比值也要对：中文比英文贵约 4 倍（实测 0.609/0.152 ≈ 4.0）
		assertThat(chineseDensity / englishDensity).isBetween(3.0, 5.5);
	}

	@Test
	@DisplayName("长文本的估算随长度线性增长（不会因为某段特别长就失真）")
	void scalesLinearly() {
		String unit = "这是一段用于测试的中文文本，包含标点。";
		int one = estimator.estimate(unit);
		int ten = estimator.estimate(unit.repeat(10));
		assertThat(ten).isBetween(one * 9, one * 11);
	}

}
