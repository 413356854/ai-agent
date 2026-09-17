package ai.agent.service.review;

/**
 * 工单的严重级别。
 *
 * <h2>为什么是枚举，而不是"用 {@code String} + {@code @Pattern} 校验"</h2>
 * 第一版写的是 {@code @Pattern(regexp = "LOW|MEDIUM|HIGH")} 的 {@code String}，
 * 而它有<b>两个</b>问题，第一个是当场被测试抓出来的：
 * <ol>
 *   <li><b>缺字段时校验压根不执行。</b> JSR-380 里 {@code @Pattern} 对 {@code null} 是通过的
 *       （那是规范定的：{@code null} 视为"没提供"，要禁止得再加 {@code @NotNull}）。
 *       于是模型不给 {@code severity} 时，参数变成 {@code null}、校验放行，
 *       而代码里那个"为 null 就用 MEDIUM"的兜底<**永远不会被走到**——
 *       因为它出现在反序列化之后、而在 Jackson 的视角里这个字段从来就不存在。
 *       结果是一段"看起来在处理缺失值、实际上处理的是空字符串"的代码；</li>
 *   <li><b>它表达不了"只有这三个值"这件事。</b> 字符串参数在类型上允许任何值，
 *       所以必须靠校验器去守；而枚举在<b>反序列化</b>阶段就拒绝了别的值 ——
 *       校验器那一层因此少一个必须记得写的东西。</li>
 * </ol>
 * 改成枚举之后，{@code severity} 变成了<b>必填</b>（枚举字段没有默认值可用），
 * 那才是它该有的语义：一条不知道紧急程度的工单，接到的人无从排优先级。
 */
public enum ReviewSeverity {

	LOW,

	MEDIUM,

	HIGH;

	/**
	 * 宽容地解析模型可能给出的写法。
	 *
	 * <p>为什么要宽容：模型常给出 {@code "high"} 或 {@code "High"}。
	 * 这在**协议层**应该被拒吗？不 —— 大小写不是语义问题，把它变成一轮
	 * "参数不合法，请改成大写"的回填是纯浪费（模型要么改对，要么在同一件事上卡两轮）。
	 * 真正有语义的是"这三个值之外的东西"，而那由枚举本身拦下。
	 *
	 * <p>这与 {@code ToolCallExecutor} 里那条"数字写成字符串要拒绝"<b>不矛盾</b>：
	 * 那一条拒绝的是<b>类型</b>上的猜测（{@code "5"} 是不是 5 需要猜，而 {@code "五"} 就没法猜），
	 * 这里处理的只是同一个值的书写形式。两者的分界线是：<b>猜测会不会改变含义。</b>
	 *
	 * @return 解析结果；{@code null} 表示"不是这三个值之一"
	 */
	public static ReviewSeverity parse(String raw) {
		if (raw == null) {
			return null;
		}
		String normalized = raw.strip().toUpperCase(java.util.Locale.ROOT);
		for (ReviewSeverity severity : values()) {
			if (severity.name().equals(normalized)) {
				return severity;
			}
		}
		return null;
	}

	/** 允许的取值清单（回填给模型用 —— "错误信息里带可用值"）。 */
	public static String allowed() {
		StringBuilder sb = new StringBuilder();
		for (ReviewSeverity severity : values()) {
			if (!sb.isEmpty()) {
				sb.append('、');
			}
			sb.append(severity.name());
		}
		return sb.toString();
	}

}
