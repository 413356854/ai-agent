package ai.agent.service.impl.tool.file.support;

import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>把模型写的文件名模式变成一个匹配器</b> —— {@code glob} 与 {@code grep} 共用。
 *
 * <h2>为什么要包一层，而不是直接 {@code FileSystems.getDefault().getPathMatcher("glob:" + pattern)}</h2>
 * 三件事它默认做不了，而这三件事在实测里都会立刻遇到：
 * <ol>
 *   <li><b>带深度的模式匹配不到根目录下的文件</b>：写成"任意层 + 文件名"的模式
 *       （{@code **} 后面接分隔符再接 {@code *.java}）按 JDK 的 glob 语义要求<b>至少一层目录</b>
 *       —— 实测它之于根目录下的 {@code Foo.java} 为 false。而模型的意图显然是
 *       "这个目录树里的 java 文件"，于是它会得到一个空结果，然后开始编路径。
 *       这里把那种前缀展开成"零层或多层"；</li>
 *   <li><b>花括号展开在部分平台上不被支持</b>：花括号写法（形如 {@code *.{java,kt}} 那段模式）在 Windows 上
 *       由 JDK 自己展开，在别的实现里可能交给 shell 语义而落空。这里自己做展开，
 *       于是同一个 pattern 在所有平台上给出同一个结果（判据一致比"跟随平台"重要）；</li>
 *   <li><b>模式不合法时抛的是 {@link InvalidPathException}</b>（例如只写一个 {@code [}），
 *       而它继承自 {@code IllegalArgumentException} —— 一把工具的解析异常不该是 JVM 的路径异常。
 *       这里转成一个自己的 {@link InvalidGlobException}，让工具能把话说清楚。</li>
 * </ol>
 *
 * <h2>{@code /} 的宽容：不含斜杠就是"这个目录树里的"</h2>
 * 模型常写 {@code *.java} 想表达"所有 java 文件"。严格按 glob 语义它只匹配当前目录这一层，
 * 而模型极少真的这么想。这里的处理是：<b>模式里不含任何 {@code /} 时，按"任意深度"匹配</b> ——
 * 与 {@code **} 前缀同义。代价是"只想看顶层"这件事要写 {@code ./} 前缀来表达，
 * 而那个代价远小于"模型拿到空结果然后开始猜路径"。
 */
public final class GlobPattern {

	/** 一次编译出来的匹配器：正则 + 它匹配的是"相对路径"的哪种写法。 */
	private final List<Pattern> alternatives;

	private GlobPattern(List<Pattern> alternatives) {
		this.alternatives = List.copyOf(alternatives);
	}

	/** 把模型给的模式编译成匹配器。不合法时抛 {@link InvalidGlobException}。 */
	public static GlobPattern of(String rawPattern) {
		if (rawPattern == null || rawPattern.isBlank()) {
			throw new InvalidGlobException("pattern 不能为空。", null);
		}
		String pattern = rawPattern.strip().replace('\\', '/');
		List<Pattern> alternatives = new ArrayList<>();
		for (String expanded : braceExpand(pattern)) {
			String normalized = expanded.startsWith("./") ? expanded.substring(2) : expanded;
			// 不含分隔符 = "任意深度"（见类注释）
			String withDepth = normalized.indexOf('/') < 0 ? "**/" + normalized : normalized;
			alternatives.add(Pattern.compile(toRegex(withDepth)));
		}
		return new GlobPattern(alternatives);
	}

	/** 这个相对路径（用 {@code /} 分隔）算不算命中。 */
	public boolean matches(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return false;
		}
		String normalized = relativePath.replace('\\', '/');
		for (Pattern pattern : alternatives) {
			if (pattern.matcher(normalized).matches()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 用一个 glob 模式筛"文件名"（{@code grep} 的 {@code include} 用）。
	 *
	 * <p>它比 {@link #of} 多一件事：<b>把只写扩展名的形态也认下来</b>（{@code .java} →
	 * {@code *.java}）。模型写 {@code include: ".java"} 时想说的显然是"java 文件"，
	 * 而 glob 语义里 {@code .java} 只匹配一个字面名叫 {@code .java} 的文件 ——
	 * 那种"按规矩拒绝"换来的是模型改用正则、于是又一轮试错。
	 */
	public static GlobPattern forFileNames(String rawPattern) {
		if (rawPattern == null || rawPattern.isBlank()) {
			throw new InvalidGlobException("模式不能为空。", null);
		}
		String pattern = rawPattern.strip();
		if (pattern.startsWith(".") && pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
			pattern = "*" + pattern;
		}
		return of(pattern);
	}

	/**
	 * 花括号展开：把一组并列的写法（{@code *.java} 与 {@code *.kt} 那样的一对）
	 * 展开成多个独立模式。
	 *
	 * <p>手写而不是用 {@link FileSystems} 的那一套：这里只需要支持"一层花括号 + 顶层逗号"，
	 * 而"顶层逗号"必须自己判 —— 逗号出现在字符类 {@code [a,b]} 或嵌套花括号里时不能切
	 * （切了会把一个模式变成两个都不对的模式，而且不报错）。
	 */
	static List<String> braceExpand(String pattern) {
		int open = pattern.indexOf('{');
		if (open < 0) {
			return List.of(pattern);
		}
		int depth = 0;
		int close = -1;
		List<Integer> commas = new ArrayList<>();
		for (int i = open; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '\\') {
				i++;
				continue;
			}
			if (c == '[') {
				// 字符类里的逗号与花括号都不是语法（[a,{] 里的逗号只是一个字符）
				int end = pattern.indexOf(']', i + 1);
				i = end < 0 ? pattern.length() : end;
				continue;
			}
			if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					close = i;
					break;
				}
			}
			else if (c == ',' && depth == 1) {
				commas.add(i);
			}
		}
		if (close < 0) {
			// 花括号不配对：当成普通字符处理，让下面的转义把它变成字面量。
			// 抛异常的话"某个文件名里带 { "就永远搜不到 —— 那是把限制加到了正常输入上
			return List.of(pattern);
		}

		List<String> results = new ArrayList<>();
		String prefix = pattern.substring(0, open);
		String suffix = pattern.substring(close + 1);
		int from = open + 1;
		for (int comma : commas) {
			results.addAll(braceExpand(prefix + pattern.substring(from, comma) + suffix));
			from = comma + 1;
		}
		results.addAll(braceExpand(prefix + pattern.substring(from, close) + suffix));
		return results;
	}

	/**
	 * glob → 正则。
	 *
	 * <p>{@code **} 在转义循环里被单独处理：读到一个 {@code *} 时如果下一个也是 {@code *}，
	 * 就连着吃掉后面的分隔符，换成 {@code (?:} 开头的"零层或多层"那一段 —— 这正是它想要的效果。
	 */
	static String toRegex(String glob) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			switch (c) {
				case '*' -> {
					boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
					if (doubleStar) {
						i++;
						// **/ 或 **（末尾）：零层或多层
						if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
							i++;
							regex.append("(?:.*/)?");
						}
						else {
							regex.append(".*");
						}
					}
					else {
						regex.append("[^/]*");
					}
				}
				case '?' -> regex.append("[^/]");
				case '.' , '(', ')' , '+' , '|' , '^' , '$' , '@' , '%' -> regex.append('\\').append(c);
				case '[' -> {
					// 字符类原样搬过去（glob 与正则在这里的写法一致：a-z、! 取反由下面处理）
					int end = glob.indexOf(']', i + 1);
					if (end < 0) {
						regex.append("\\[");
					}
					else {
						String body = glob.substring(i + 1, end);
						if (body.startsWith("!")) {
							body = "^" + body.substring(1);
						}
						regex.append('[').append(body).append(']');
						i = end;
					}
				}
				case '\\' -> {
					// 反斜杠是转义：下一个字符按字面量处理（Windows 路径风格在 of() 里已经被换成 / 了）
					if (i + 1 < glob.length()) {
						regex.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
						i++;
					}
				}
				case '{' , '}' , ',' -> {
					// 走到这里说明花括号不配对（配对的已在 braceExpand 里展开）：当字面量
					regex.append('\\').append(c);
				}
				default -> regex.append(c);
			}
		}
		return regex.toString();
	}

	/** 模式不合法（空、或用不了）。它不是 JVM 的路径异常，而是"模型给的参数不对"。 */
	public static class InvalidGlobException extends RuntimeException {

		public InvalidGlobException(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
