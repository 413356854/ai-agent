package ai.agent.service.impl.token;

import ai.agent.config.ChatCallProperties;
import ai.agent.service.token.TokenEstimator;
import org.springframework.stereotype.Component;

/**
 * 按<b>字符类别</b>估算 token 数的实现（默认实现）。
 *
 * <h2>为什么按字符类别分开算</h2>
 * BPE 分词器对字符的切分密度和字符集强相关，实测差距极大：
 * <table border="1">
 *   <caption>同一段文本的实测密度</caption>
 *   <tr><th>文本类型</th><th>大体密度</th><th>按"字符 ÷ 4"估会怎样</th></tr>
 *   <tr><td>英文 / 代码</td><td>约 3–4 字符 1 token</td><td>大致准（那正是 ÷4 的来源）</td></tr>
 *   <tr><td>中文 / 日文（CJK）</td><td>约 1 字 1 token，生僻字与标点可到 1.5</td>
 *       <td><b>低估 4 倍</b> —— 危险方向</td></tr>
 *   <tr><td>Emoji / 其他文种</td><td>1 个字符常见 3+ token</td><td><b>低估 12 倍</b></td></tr>
 * </table>
 * 一个"字符 ÷ 4"的通式在纯英文语料上看着很准，但只要混进中文就会系统性低估。
 * 而<b>低估是这次要防的方向</b>：低估的后果是超窗没被拦住，
 * 请求照样发出去、照样花钱、可能被静默截断；高估的后果只是"本来能过的请求被提前裁了历史"，
 * 明显温和得多。所以这里的系数统一取偏保守的一侧。
 *
 * <h2>为什么不用 Spring AI 自带的 JTokkitTokenCountEstimator</h2>
 * 它在 classpath 上是现成的（{@code spring-ai-client-chat} 传递依赖了 jtokkit），
 * 用法一行就够，但它的编码表是 <b>OpenAI 的 cl100k_base</b>：
 * <ul>
 *   <li>对本项目实际使用的 DeepSeek 模型，分词表本来就不是 cl100k，
 *       中文上的偏差方向不确定 —— 可能低估，那就等于没有校验；</li>
 *   <li>更要紧的是它<b>默认只给字符串算</b>，不含消息包装开销，
 *       单条短消息的估算会系统性偏低。</li>
 * </ul>
 * 本项目同时跑过两种算法做对照，
 * 结论是：字符类别法配合真实 usage 校准，误差更可控也更容易解释。
 * 这里不是"重复造轮子"，是<b>轮子的齿距对不上这台机器</b>。
 *
 * <h2>这个类为什么不做缓存</h2>
 * 纯函数（无状态、无 IO、不分配大对象），单次计算 O(n) 且 n 是几千字符量级，
 * 在微秒级 —— 相比一次模型调用（秒级）完全可以忽略。
 * 加缓存反而要引入 key 管理与失效逻辑，得不偿失。
 */
@Component
public class CharacterClassTokenEstimator implements TokenEstimator {

	private final ChatCallProperties.Estimation config;

	public CharacterClassTokenEstimator(ChatCallProperties properties) {
		this.config = properties.getEstimation();
	}

	@Override
	public int estimate(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}

		// 分类累加：一轮扫描即可，避免为三类字符各扫一遍
		int asciiCount = 0;
		int cjkCount = 0;
		int otherCount = 0;

		for (int i = 0; i < text.length(); ) {
			int codePoint = text.codePointAt(i);
			int width = Character.charCount(codePoint); // 补充平面字符占 2 个 char，必须按码点走
			i += width;

			if (codePoint < 128) {
				asciiCount++;
			}
			else if (isCjk(codePoint)) {
				cjkCount++;
			}
			else {
				otherCount++;
			}
		}

		double tokens = asciiCount / config.getAsciiCharsPerToken()
				+ cjkCount * config.getCjkTokensPerChar()
				+ otherCount * config.getOtherTokensPerChar();

		// 向上取整：预算校验宁可多留一点余量，不要因为截断误差放过去一个刚好超限的请求
		return (int) Math.ceil(tokens);
	}

	@Override
	public int estimateMessage(String text) {
		return estimate(text) + config.getTokensPerMessage();
	}

	@Override
	public int requestOverhead() {
		return config.getTokensPerRequest();
	}

	/**
	 * 是否属于 CJK（中日韩）文字区。
	 *
	 * <p>覆盖的是<b>真实用得到的几个区段</b>，不是"所有非 ASCII"：
	 * 拉丁扩展、西里尔、阿拉伯、Emoji 都被归到"其他"，因为它们的密度完全不同
	 * （这正是 {@code otherTokensPerChar} 存在的原因）。
	 *
	 * <p>区间按 Unicode 码点判断，所以必须传 {@code codePoint} 而不是 {@code char}——
	 * 用 {@code char} 比较时，补充平面字符会被拆成两个代理项，判断全错。
	 */
	private static boolean isCjk(int codePoint) {
		return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)     // CJK 统一表意文字（常用汉字）
				|| (codePoint >= 0x3400 && codePoint <= 0x4DBF)  // 扩展 A（生僻汉字）
				|| (codePoint >= 0xF900 && codePoint <= 0xFAFF)  // 兼容表意文字
				|| (codePoint >= 0x3000 && codePoint <= 0x303F)  // CJK 标点（、。「」等）
				|| (codePoint >= 0xFF00 && codePoint <= 0xFFEF)  // 全角字符（，：；！等）
				|| (codePoint >= 0x3040 && codePoint <= 0x30FF)  // 日文假名
				|| (codePoint >= 0xAC00 && codePoint <= 0xD7AF); // 韩文谚文音节
	}

}
