package ai.agent.service.impl.tool;

import java.util.Optional;
import java.util.Set;

/**
 * <b>执行前的那一道边界</b> —— 只做两件事：取首关键字、认出"一条 SQL 里塞了多条语句"。
 *
 * <h2>它<b>不是</b>危险 SQL 黑名单，这是刻意的</h2>
 * 需求是"操作数据库，不限定 SQL"：{@code DROP TABLE} / {@code TRUNCATE} / {@code DELETE}
 * 都是合法能力，它们归 {@code ToolHints.writeRequiringApproval()} 管 ——
 * 也就是"必须有人点过确认"。所以这里<b>不判断语句危不危险</b>，
 * 只回答一个问题：<b>这条语句是"读"还是"写"</b>。
 *
 * <p>这个问题必须有人回答，因为本项目的审批判据来自工具<b>自己声明的风险</b>
 * （{@code ToolHints#requiresApproval()}：只读 → 免审批；非幂等的写 → 必须审批），
 * 而"这次 SQL 是读还是写"只有运行时才知道。唯一能做到"SELECT 不用等人、写必须等人"的形态，
 * 就是把读与写拆成两把工具 —— 于是需要一道判据来决定"这次调用该不该被这把工具接受"。
 *
 * <h2>为什么只做"首关键字"这一层，而不是引一个 SQL 解析器</h2>
 * 完整解析要引依赖、要跟方言走，而它换来的只是把"绕过审批"的手段从"随便一种写法"
 * 缩小到"另一种写法" —— 判据越复杂，能被绕过的缝隙反而越不容易被看见。
 * 这里的目标是**明确的一道门**：非读语句走 {@code query_sql} 一律被拒，
 * 并直接告诉模型去调 {@code execute_sql}（那里一定有人审批）。
 *
 * <h2>为什么"多条语句"必须拒，而且必须在这里拒</h2>
 * 实测（H2 2.3.232）：{@code execute("INSERT …; INSERT …")} 返回
 * {@code getUpdateCount()=1}，而<b>两条都执行了</b> ——
 * 也就是"报出来的影响行数只覆盖其中一条"。它属于最该避免的那类失败：
 * <b>看起来正常、数字具体、而事实不是那个数字</b>。
 *
 * <p>它同时还破坏 HITL 的语义：人批的是**一条**语句、审计记录的也应该是**一条**变更。
 * 一次审批放行若干条变更之后，"这条记录是哪一次审批放行的"这个问题就再也答不出来了。
 */
final class SqlGuard {

	/** 允许出现在只读工具里的首关键字。 */
	private static final Set<String> READ_KEYWORDS = Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESC", "DESCRIBE",
			"TABLE", "VALUES");

	private SqlGuard() {
	}

	/**
	 * 剥掉注释与空白之后，SQL 的第一个词（大写）。空串表示"整串没有代码字符"。
	 *
	 * <p>注释必须在取首关键字<b>之前</b>剥掉：以行注释（两个连字符）开头、或先写一段块注释
	 * 再写 {@code SELECT} 的 SQL 都是合法的，按原串取首词会得到一个注释片段而不是关键字 ——
	 * 于是"用注释开头"就能让一条读语句被自己的工具拒掉，那是把限制加到了正常输入上。
	 */
	static String leadingKeyword(String sql) {
		int i = skipTrivia(sql, 0);
		int start = i;
		while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
			i++;
		}
		return sql.substring(start, i).toUpperCase();
	}

	/** 首关键字是不是"读"（只读工具据此放行）。 */
	static boolean isReadStatement(String sql) {
		return READ_KEYWORDS.contains(leadingKeyword(sql));
	}

	/**
	 * 首关键字不是读语句时，给出拒绝文本；是读语句时返回空。
	 *
	 * <p>文本里必须<b>带上可用的替代方案</b>（"请改用 execute_sql"）：
	 * 只说"这个工具不能写"的话，模型的下一步通常是换一种写法再试一次这个工具 ——
	 * 而这正是"错误信息里带可用值"那条纪律要防的事。
	 */
	static Optional<String> rejectNonRead(String sql) {
		if (isReadStatement(sql)) {
			return Optional.empty();
		}
		String keyword = leadingKeyword(sql);
		return Optional.of(("这条语句的首关键字是「%s」，它不是读语句，所以 query_sql 拒绝执行它。"
				+ "query_sql 只执行读语句，是为了让「SELECT 不用等人确认」这件事成立。"
				+ "要写数据请改用工具 execute_sql —— 它会要求人工确认之后才执行。"
				+ "（读语句的合法开头还有：%s）")
				.formatted(keyword.isEmpty() ? "(空)" : keyword, String.join("、", READ_KEYWORDS)));
	}

	/**
	 * 认出一条 SQL 里的多语句。
	 *
	 * <p>扫描是<b>逐字符</b>的，因为它要区分四种完全不同的分号：
	 * 字符串字面量里的 {@code 'a;b'}、行注释里的、块注释里的、以及真正的语句分隔符。
	 * 用 {@code contains(";")} 或 {@code split(";")} 的写法会把前三种也判成多语句 ——
	 * 于是"查询里带一个分号的内容"永远调不通，而工具说明看起来毫无问题。
	 *
	 * <p>括号深度也一起跟：{@code … (';') …} 这类写法在真实 SQL 里存在，
	 * 而"括号内的分号"与"字面量内的分号"是两个不同的问题，两个都判才够。
	 *
	 * <p>结尾那个分号（{@code SELECT 1;}）与"后面只剩注释"（{@code SELECT 1; -- 完}）都算单条语句：
	 * 它们是合法的收尾写法，把正常输入判成多语句是"限制加到正常输入上"的同一类错误。
	 *
	 * @return 多语句时返回拒绝文本（含条数），单条时返回空
	 */
	static Optional<String> rejectMultipleStatements(String sql) {
		if (sql == null || sql.isBlank()) {
			return Optional.empty();
		}
		Scan scan = scan(sql);
		if (scan.topLevelSeparators() == 0) {
			return Optional.empty();
		}
		// 第一个分号之后还剩多少"代码字符"（注释与空白不算）：
		//   0 → 只是一条语句的收尾分号，放行
		//   非 0 → 后面确实还有语句
		if (scan.topLevelSeparators() == 1 && skipTrivia(sql, scan.firstSeparator() + 1) >= sql.length()) {
			return Optional.empty();
		}
		return Optional.of(("一次只能执行一条语句：这次给的 SQL 里有 %d 个语句分隔符（分号）。"
				+ "请只保留你要执行的那一条，其余的分成多次调用。"
				+ "两个具体原因：① 数据库对多语句只会报出第一条的影响行数，于是「到底改了几行」这个数字会是错的；"
				+ "② 人工确认与审计记录的粒度就是一条语句。").formatted(scan.topLevelSeparators()));
	}

	/** 扫描结果：顶层分号个数与第一个顶层分号的位置。 */
	private record Scan(int topLevelSeparators, int firstSeparator) {
	}

	/**
	 * 走一遍 SQL，数出"真正的"（顶层、非字面量、非注释里的）语句分隔符。
	 *
	 * <p>四种状态各自有明确跳法，别用正则做这件事：正则分不清
	 * "引号里的分号"与"引号外的分号"，而那个区别正是这个方法存在的全部理由。
	 */
	private static Scan scan(String sql) {
		int separators = 0;
		int firstSeparator = -1;
		int depth = 0;
		int i = 0;
		int length = sql.length();
		while (i < length) {
			char c = sql.charAt(i);
			if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
				i = skipToEndOfLine(sql, i + 2);
				continue;
			}
			if (c == '#') {
				i = skipToEndOfLine(sql, i + 1);
				continue;
			}
			if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
				int end = sql.indexOf("*/", i + 2);
				i = end < 0 ? length : end + 2;
				continue;
			}
			if (c == '\'' || c == '"' || c == '`') {
				i = skipQuoted(sql, i, c);
				continue;
			}
			if (c == '(') {
				depth++;
				i++;
				continue;
			}
			if (c == ')') {
				depth = Math.max(0, depth - 1);
				i++;
				continue;
			}
			if (c == ';' && depth == 0) {
				separators++;
				if (firstSeparator < 0) {
					firstSeparator = i;
				}
			}
			i++;
		}
		return new Scan(separators, firstSeparator);
	}

	/**
	 * 跳过空白与注释，返回下一个"代码字符"的下标（{@code >= sql.length()} 表示后面没有代码了）。
	 *
	 * <p>返回 {@code sql.length()} 而不是抛异常：调用方两种用法（取首关键字、判"后面还有没有内容"）
	 * 都只需要一个"到头了"的信号。
	 */
	private static int skipTrivia(String sql, int from) {
		if (sql == null) {
			return 0;
		}
		int i = Math.max(0, from);
		int length = sql.length();
		while (i < length) {
			char c = sql.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
				i = skipToEndOfLine(sql, i + 2);
				continue;
			}
			if (c == '#') {
				i = skipToEndOfLine(sql, i + 1);
				continue;
			}
			if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
				int end = sql.indexOf("*/", i + 2);
				i = end < 0 ? length : end + 2;
				continue;
			}
			break;
		}
		return i;
	}

	private static int skipToEndOfLine(String sql, int from) {
		int newline = sql.indexOf('\n', from);
		return newline < 0 ? sql.length() : newline + 1;
	}

	/**
	 * 跳过一个引号包围的字面量／标识符。
	 *
	 * <p>单引号里的 {@code ''} 是转义（SQL 标准），双引号与反引号连写同样按转义处理
	 * （与 H2 的 {@code MODE=MySQL} 一致）。找不到收尾引号时返回末尾 ——
	 * 让数据库去报语法错，比在这里自己猜一个位置更诚实。
	 */
	private static int skipQuoted(String sql, int start, char quote) {
		int i = start + 1;
		int length = sql.length();
		while (i < length) {
			if (sql.charAt(i) == quote) {
				if (i + 1 < length && sql.charAt(i + 1) == quote) {
					i += 2;
					continue;
				}
				return i + 1;
			}
			i++;
		}
		return length;
	}

}
