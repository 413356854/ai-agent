package ai.agent.service.support.task;

/**
 * <b>把模型吐出来的坏 JSON 修回能解析的样子 —— 只修语法，不改内容</b>。
 *
 * <h2>它修的是什么、为什么只修这一种</h2>
 * 只有一件事：<b>字符串值里那些没有转义的引号</b>。模型写规则时经常把原文里的引号原样搬进 JSON，
 * 例如 {@code ["先问清"口径""]} —— 那一份输出就不是合法 JSON 了，实测报错是
 * {@code Unexpected character ('"' (code 34)): was expecting comma to separate Object entries}。
 * 那时材料是好的、只有包装坏了，所以值得救一次。
 *
 * <p>刻意<b>不</b>做别的（不抠 markdown 围栏、不补逗号、不猜字段名、不宽容地接受缺字段）：
 * 那种"修"就变成猜哪一段才是答案了，而猜错的方式是<b>得到一个看起来正确的结论</b>。
 * 这一条只做"给引号补一个反斜杠"这一个语法事实，规则正文的字符一个都不增删。
 *
 * <h2>判据（逐字符扫一遍，每个字符只认自己那一种角色）</h2>
 * <ol>
 *   <li>遇到反斜杠：连同它后面那一个字符原样抄过去 —— 已经合法的 {@code \"} 与 {@code \\}
 *       因此不会被二次转义；</li>
 *   <li>不在值里、且这个引号<b>是字段名</b>：它后面（跳过空白）紧跟 {@code :}，
 *       或者它前面（跳过空白）是 {@code {} / {@code [} / {@code ,} —— 例如
 *       {@code { "usable": … }} 与 {@code "rules"  :  []}。字段名的引号原样抄、不改状态
 *       （{@code "rules": …} 里引号两侧的空白因此不会把它认成值的开头）；</li>
 *   <li>不在值里、其余位置的引号 = <b>值的开头</b>：进入值，原样抄；</li>
 *   <li>在值里遇到的引号：看它后面（跳过空白）是不是 {@code ,} / {@code }} / {@code ]} /
 *       文本末尾 —— 是则它是<b>值的收尾</b>（退出值），不是就说明它是正文里一个没转义的引号，
 *       补成 {@code \"} 留在值里。</li>
 * </ol>
 * 第 4 条里的"这几种字符"是刻意的：值里的引号后面通常直接跟着字母或汉字
 * （{@code …先问清"口径"不要…}），而合法收尾的后面只可能是那四种之一。
 *
 * <h2>修不了就给 null</h2>
 * 输入里连 {@code usable} 都没有时直接返回 null（那可能整个回答都不是这一份契约，
 * 而"修"只会制造一段看起来合理的幻觉）；修完与原文一模一样时也返回 null（没有任何修复发生，
 * 不值得再解析一次）。调用方据此走"原文进日志 + 抛出去"那条路 ——
 * 宁可这次不补，也不要写进去一段被修坏的内容。
 */
public final class RuleSummaryJson {

	private RuleSummaryJson() {
	}

	/**
	 * 修一份坏 JSON。
	 *
	 * @param raw 模型原始输出
	 * @return 修过的文本；无需修 / 不敢修时返回 {@code null}
	 */
	public static String repair(String raw) {
		if (raw == null || raw.isBlank() || !raw.contains("usable")) {
			return null;
		}
		StringBuilder sb = new StringBuilder(raw.length() + 16);
		boolean inValue = false;
		for (int i = 0; i < raw.length(); i++) {
			char ch = raw.charAt(i);
			if (ch == '\\') {
				sb.append(ch);
				if (i + 1 < raw.length()) {
					sb.append(raw.charAt(++i));
				}
				continue;
			}
			if (ch != '"') {
				sb.append(ch);
				continue;
			}
			if (!inValue) {
				if (!startsString(raw, i)) {
					sb.append(ch);
					continue;
				}
				inValue = true;
				sb.append(ch);
				continue;
			}
			if (closesValue(raw, i)) {
				inValue = false;
				sb.append(ch);
			}
			else {
				sb.append("\\\"");
			}
		}
		String repaired = sb.toString();
		return repaired.equals(raw) ? null : repaired;
	}

	/**
	 * 这个引号是不是<b>一个字符串的开始</b>：它落在 JSON 的"取值位置"上。
	 *
	 * <p>取值位置 = 前面（跳过空白与引号）是 {@code {} / {@code [} / {@code ,} / {@code :}，
	 * 或者这是文本里第一个非空白字符。
	 *
	 * <h2>它不区分字段名与值，而这是刻意的</h2>
	 * 字段名的字符串也同样从这里"开始"，但两者没有可区分的形态：
	 * {@code "rules"} 与 {@code "a"} 在字符层面一模一样。区分它们需要看"后面还有没有一对引号 + 冒号"，
	 * 而那属于猜结构；这一条只需要知道"引号从这里开始、到哪里可能结束"就够修语法了 ——
	 * 收尾那一半（{@link #closesValue}）只认四种分隔符，于是字段名的引号几乎立刻就"收尾"了，
	 * 不会被误伤（实测：合法 JSON 上 {@code repair} 返回 null，一个字都没改）。
	 *
	 * <h2>为什么要跳过空白<b>与引号</b></h2>
	 * 紧挨着写的 JSON 里，值的开头前面正好是字段名的收尾引号：
	 * {@code {"rules":["a"]}} 里 {@code [} 之后那个引号，"前一个非空白"就是它旁边那个引号 ——
	 * 只看前一个字符的话它既不是 {@code {} 也不是 {@code ,}，会被判成"不是取值位置"，
	 * 于是整段被当成没有开始，实际发生的错是"值的开头没被认出来"（实测踩过）。
	 */
	private static boolean startsString(String text, int at) {
		int prev = previousValuePosition(text, at - 1);
		if (prev < 0) {
			return true;
		}
		char before = text.charAt(prev);
		return before == '{' || before == '[' || before == ',' || before == ':';
	}

	/** 往前找"取值位置"那一个字符：跳过空白与引号。 */
	private static int previousValuePosition(String text, int from) {
		for (int i = from; i >= 0; i--) {
			char ch = text.charAt(i);
			if (Character.isWhitespace(ch) || ch == '"') {
				continue;
			}
			return i;
		}
		return -1;
	}

	/**
	 * 这个引号是不是值的<b>收尾</b>：后面是空白、{@code ,} / {@code :} / {@code }} / {@code ]}，
	 * 或已经到末尾。
	 *
	 * <p><b>为什么"后面是空白"就算收尾</b>（真实踩过）：合法 JSON 里值后面什么都有可能是空白，
	 * 而值<b>内部</b>的空格后面紧跟的一定是别的正文字符 —— 所以"引号后面是空白"只可能是收尾。
	 * 不认这一条的后果很具体：{@code "keep the naming stable." ]} 与
	 * {@code "…two ways." , "next"} 的收尾引号会被当成正文引号补上反斜杠，
	 * 修出来的文本仍然不是合法 JSON（实测两次都踩在这上面）。
	 */
	private static boolean closesValue(String text, int at) {
		int next = at + 1;
		if (next >= text.length()) {
			return true;
		}
		char ch = text.charAt(next);
		return Character.isWhitespace(ch) || ch == ',' || ch == ':' || ch == '}' || ch == ']';
	}

	/** 下一个非空白字符的下标；到末尾时返回 -1。 */
	private static int nextMeaningful(String text, int from) {
		for (int i = from; i < text.length(); i++) {
			if (!Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	/** 上一个非空白字符的下标；到开头之前返回 -1。 */
	private static int previousMeaningful(String text, int from) {
		for (int i = from; i >= 0; i--) {
			if (!Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 逐字符打印这一次修复判断了什么（排查用：每一处引号一行，判据与结论都在）。
	 *
	 * <p>它是刻意的<b>成品代码</b>（不是临时脚本）：这条链路唯一难查的地方就是"哪一处引号被当成了什么"，
	 * 而外部复现它要把 Spring AI 的整条依赖链凑齐（实测二十来个 jar 还差三个）。
	 * 有它之后，一次 {@code RuleSummaryJson.trace(modelOutput)} 就能定位。
	 */
	public static void trace(String raw) {
		boolean inValue = false;
		System.out.println("trace: len=" + (raw == null ? -1 : raw.length()));
		for (int i = 0; raw != null && i < raw.length(); i++) {
			char ch = raw.charAt(i);
			if (ch == '\\') {
				System.out.println("  i=" + i + " backslash, skip '"
						+ (i + 1 < raw.length() ? raw.charAt(i + 1) : ' ') + "'");
				i++;
				continue;
			}
			if (ch != '"') {
				continue;
			}
			int next = nextMeaningful(raw, i + 1);
			String nextChar = next < 0 ? "(end)" : String.valueOf(raw.charAt(next));
			if (!inValue) {
				boolean opens = startsString(raw, i);
				System.out.println("  i=" + i + " outside: startsString=" + opens + " -> "
						+ (opens ? "OPEN" : "leave alone"));
				if (opens) {
					inValue = true;
				}
			}
			else {
				boolean closes = closesValue(raw, i);
				System.out.println("  i=" + i + " inside: next=" + nextChar + " closes=" + closes + " -> "
						+ (closes ? "CLOSE" : "ESCAPE IT"));
				if (closes) {
					inValue = false;
				}
			}
		}
	}

}
