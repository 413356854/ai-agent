package ai.agent.service.support.task;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>定时正则是"6 段式"，判据与中文说法只有这一份</b>（应用管理区 · 定时任务）。
 *
 * <h2>六个字段：秒 分 时 日 月 周</h2>
 * 例：{@code 0 0 9 * * MON-FRI} = 周一到周五每天 09:00:00。
 * 多出来的第一段是<b>秒</b>，而它不是为了精确 —— 是因为 5 段式（Unix crontab 的写法）
 * 根本没有秒这一格，而界面上"每 N 秒"这一类规则在 5 段式里表达不了。
 *
 * <h2>★ 实测：Spring 7.0.9 的 {@link CronExpression#parse} 只认 6 段，5 段式直接拒绝</h2>
 * 实测（本机 JDK 21 + spring-context 7.0.9），下面每一行的输入输出都是抄下来的原文：
 * <pre>
 * parse(0 0 9 * * *)        → OK，next = 次日 09:00:00
 * parse(0 0 9 * * * *)      → IllegalArgumentException: must consist of 6 fields (found 7)
 * parse(0 0 9)              → IllegalArgumentException: must consist of 6 fields (found 3)
 * parse(0 0 9 1 * MON)      → **解析成功**，next 落在"下一个 1 号**或**下一个周一"
 * parse(0 0 99 * * *)       → IllegalArgumentException: Invalid value for HourOfDay
 * parse(0 0 9 31 2 *)       → 解析成功，但 next() 返回 **null**（那一天永远不存在）
 * </pre>
 * 三条结论都写进了代码，因为它们各自对应一种"不报错的错"：
 * <ol>
 *   <li><b>不做 5 段式补秒</b>：本类<b>不</b>替用户在前面补一个 0。
 *       表面上看"补一个 0 更友好"，但那会让 {@code 0 0 9 * * MON} 这种<b>6 段的误写</b>
 *       也被接受成 {@code 0 0 0 9 * * MON}（7 段，接着报"must consist of 6 fields"），
 *       报错指向一个用户没写过的表达式。拒绝 5 段式的代价只是一句明确的提示
 *       （"found 3 in …"），而它的收益是"用户敲进去的那一句就是跑着的那一句"。
 *       {@code web/src/cron.ts}` 里那个构造器发出的永远是这个 6 段形态。</li>
 *   <li><b>「日」与「周」不能同时写具体值</b>：{@code 0 0 9 1 * MON} 解析<b>成功</b>，
 *       语义是"每月 1 号**和**每个周一"（两者取或）—— 而写它的人多半只想其中一个。
 *       这是本类最值钱的一条判断：它不报错，只在日历上多跑几次，
 *       而"多跑的那几次"在界面上看起来与正常触发一模一样。见 {@link #validate}。</li>
 *   <li><b>算不出下一次的表达式要拒</b>：{@code 0 0 9 31 2 *}（2 月 31 号）解析成功、
 *       {@code next()} 返回 null。它在调度上表现为"线程装了但永远不响"，
 *       而"它到底会不会响"是配这条规则的人唯一想确认的事。</li>
 * </ol>
 *
 * <h2>「下一次什么时候跑」由时序算出来，不是猜出来的</h2>
 * {@link #nextRuns} 用真的时钟往后推（{@link CronExpression#next(ZonedDateTime)}），
 * 界面把未来 5 次摆出来 —— 这是"我配的这句到底对不对"唯一的证据。
 * ⚠️ 起点必须带时区：实测传 {@code Instant} 会抛
 * {@code UnsupportedTemporalTypeException: Unsupported field: DayOfWeek}
 * （{@code Instant} 没有"星期几"这个字段），而传 {@link ZonedDateTime} 正常。
 * 时区取 JVM 默认（与 {@code CronTrigger} 在 {@code ThreadPoolTaskScheduler} 里的取法一致）——
 * 两处用同一个时区，"预览说 09:00"与"真的 09:00 跑"才是同一件事。
 */
public final class ScheduleCron {

	/** 6 个字段：秒 分 时 日 月 周。 */
	private static final int FIELDS = 6;

	/** 「日」在表达式里的下标（0 起）。 */
	private static final int DAY_OF_MONTH = 3;

	/** 「周」在表达式里的下标（0 起）。 */
	private static final int DAY_OF_WEEK = 5;

	/** 界面上摆未来几次触发时刻。5 是"看得出规律"与"不占版面"之间的取值。 */
	public static final int PREVIEW_COUNT = 5;

	private static final DateTimeFormatter RUN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final String[] WEEKDAY_LABELS = { "周日", "周一", "周二", "周三", "周四", "周五", "周六" };

	private ScheduleCron() {
	}

	/**
	 * 解析并归一：去空白 + 折叠中间的空格。
	 *
	 * <p>归一是为了让"同一句表达式"在库里只有一种写法：{@code 0  0 9 * * *} 与
	 * {@code 0 0 9 * * *} 是同一件事，而存两种形态会让"这条规则改过没有"看起来像是改过。
	 */
	public static String normalize(String expression) {
		if (expression == null || expression.isBlank()) {
			throw new IllegalArgumentException("定时正则不能为空 —— 格式是 6 段「秒 分 时 日 月 周」，"
					+ "例：0 0 9 * * MON-FRI（周一到周五每天 09:00:00）");
		}
		return expression.strip().replaceAll("\\s+", " ");
	}

	/**
	 * 校验一句定时正则；不合规就抛 {@link IllegalArgumentException}（服务层翻成 400）。
	 *
	 * <p>三层判据，顺序是"先语法、再语义、最后可执行"：
	 * 语法交给 {@link CronExpression#parse}（它自己会说出哪个字段、什么值超了）；
	 * 语义拦"日 + 周同时写具体值"（解析成功但语义是两者取或）；
	 * 可执行拦"算不出下一次"（解析成功但永远不会触发）。
	 *
	 * @param normalized {@link #normalize} 之后的那一份
	 * @return 解析好的表达式（调用方接着用它算触发时刻，不重复解析）
	 */
	public static CronExpression validate(String normalized) {
		return validate(normalized, ZonedDateTime.now());
	}

	/**
	 * 同上，起点由调用方给。
	 *
	 * <p>起点参与判据（"算不算得出下一次"要从某个时刻往后看），所以它必须能传进来：
	 * 探针要一个确定的时点，而"现在"在两次调用之间是会变的。
	 */
	public static CronExpression validate(String normalized, ZonedDateTime from) {
		CronExpression parsed;
		try {
			parsed = CronExpression.parse(normalized);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(("%s —— 「%s」不是一个可用的定时正则。"
					+ "格式是 6 段「秒 分 时 日 月 周」，例：0 0 9 * * MON-FRI（周一到周五每天 09:00:00）、"
					+ "*/5 * * * * *（每 5 秒）、0 0/30 8-18 * * MON-FRI（工作日 8~18 点每半小时）。"
					+ "⚠️ Unix crontab 的 5 段式这里不接受：解析器只认 6 段，秒那一段必须写出来。")
					.formatted(e.getMessage(), normalized));
		}
		String[] fields = normalized.split(" ");
		if (concrete(fields[DAY_OF_MONTH]) && concrete(fields[DAY_OF_WEEK])) {
			throw new IllegalArgumentException(("「日」与「周」不能同时写具体值：「%s」里的「%s」与「%s」"
					+ "会被解析成「日 **或** 周」—— 也就是两个日子都会触发，而写它的人通常只想其中一个；"
					+ "**而且它不会报错**。要按星期几跑就把「日」写成 ? 或 *（例：0 0 9 ? * MON），"
					+ "要按几号跑就把「周」写成 ? 或 *（例：0 0 9 1 * *）。")
					.formatted(normalized, fields[DAY_OF_MONTH], fields[DAY_OF_WEEK]));
		}
		if (nextRuns(parsed, 1, from).isEmpty()) {
			throw new IllegalArgumentException(("「%s」算不出下一次触发时刻 —— 它语法合法，"
					+ "但按日历永远等不到那一天（例如 2 月 31 号）。这样一条规则装进调度线程池之后"
					+ "**不会再有任何动静**，而界面上它看起来与一条正常规则一模一样。"
					+ "请改成真实存在的日子。").formatted(normalized));
		}
		return parsed;
	}

	/**
	 * 六格合成一句中文。
	 *
	 * <h2>为什么分两半拼：「秒 分 时」说时刻，「日 月 周」说哪一天</h2>
	 * 前三格单看都没有意义（"每 5 秒"要三段一起才成立），所以它们要合成一个整体；
	 * 合成一句时顺序必须是 <b>日子在前、时刻在后</b> —— 中文里时间状语在前
	 * （"每周 周一 09:00:00 触发"，而不是"09:00:00 每周 周一 触发"）。
	 *
	 * <p>三格都是具体值时它就是一个<b>钟点</b>（每天某时某分某秒），这时直接拼 {@code HH:mm:ss}，
	 * 不再套"每天…点的…"那种说法 —— 实测第一版写成"每天 3 点的 00:00 触发"：
	 * 意思没错，但同一个时刻被说了两遍，读的人得自己合一下。
	 *
	 * <p>两半之间那一个空格是<b>有条件的</b>：日子那一半为空时（月 / 日 / 周三个通配，
	 * 也就是最常见的"每天"）不能再多出一个前导空格。
	 */
	public static String describe(String normalized) {
		String[] f = normalized.split(" ");
		if (f.length != FIELDS) {
			return normalized;
		}
		String day = describeMonth(f[4]) + describeDayOfMonth(f[DAY_OF_MONTH]) + describeDayOfWeek(f[DAY_OF_WEEK]);
		String time = fixed(f[0]) && fixed(f[1]) && fixed(f[2])
				? "%02d:%02d:%02d".formatted(firstOf(f[2]), firstOf(f[1]), firstOf(f[0]))
				: describeSchedule(f);
		return day + (day.isEmpty() ? "" : " ") + time + " 触发";
	}

	/**
	 * 未来 {@code count} 次触发时刻（含这次校验之后最近的那一次）。
	 *
	 * <p>起点是<b>现在</b>：{@code next(now)} 给的是严格晚于 now 的第一个时刻，
	 * 所以"刚刚跑过的那一次"不会被算进来（它与"下一次"是两件事）。
	 */
	public static List<Instant> nextRuns(CronExpression expression, int count) {
		return nextRuns(expression, count, ZonedDateTime.now());
	}

	/** 同上，起点由调用方给（探针与自检要一个确定的时点）。 */
	public static List<Instant> nextRuns(CronExpression expression, int count, ZonedDateTime from) {
		List<Instant> runs = new ArrayList<>(count);
		ZonedDateTime cursor = from;
		for (int i = 0; i < count; i++) {
			ZonedDateTime next = expression.next(cursor);
			if (next == null) {
				break;
			}
			runs.add(next.toInstant());
			cursor = next;
		}
		return List.copyOf(runs);
	}

	/** 触发时刻的显示写法（{@code yyyy-MM-dd HH:mm:ss}，与界面上那一列一致）。 */
	public static String formatRun(Instant instant) {
		return RUN_FORMAT.format(ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()));
	}

	/**
	 * 这一格写的是"具体日子"吗 —— 通配（{@code *} / {@code ?}）不算。
	 *
	 * <p>判据只看首字符：`*`（每…）、`?`（不指定）都以这两个符号开头；
	 * 而带步长的写法（{@code &#42;/2}）也以 `*` 开头 —— 那是"每隔 2 天"，
	 * 同样属于"这一格没有指名某个日子"，与"每月 1 号"这种指名道姓的值不是一回事。
	 */
	private static boolean concrete(String field) {
		return !field.startsWith("*") && !field.startsWith("?");
	}

	// ------------------------------------------------------------------
	//  中文说法
	// ------------------------------------------------------------------

	/**
	 * 「秒 分 时」三段合成一句话（三格都是具体值的那一种由 {@link #describe} 直接拼钟点，不走这里）。
	 *
	 * <p>覆盖的是界面上构造器真的会发出的那几种形态（每 N 秒 / 每 N 分 / 每 N 小时 /
	 * 每天某时某分）以及手写时常见的等价写法；认不出的就如实说"这句里是这么写的"，
	 * 而不是编一个看起来确定的中文（编错的话，界面上那句中文与真正跑的时刻各说一套）。
	 *
	 * <h2>★ 分支顺序有判据：先"每 N 秒 / 分"，再"每 N 小时"，最后才是"每天某时某分"</h2>
	 * 实测踩过的两种写法，都来自把顺序写反：
	 * <ul>
	 *   <li>{@code 0 *&#47;5 * * * *}（每 5 分钟）第一版落进了"每天某时某分"那一支 ——
	 *       它先算了一个 {@code HH:mm:ss}，而那时「时」是通配符，{@code firstOf("*")} 给的是 0，
	 *       于是屏幕上写着"每天 00:00:00"，而它实际是每 5 分钟跑一次。
	 *       <b>它不报错，只是答非所问</b>；</li>
	 *   <li>{@code 0 0 0&#47;4 * * *}（每 4 小时）说成了"每 4 小时（从 0 点起算）"——
	 *       这句话是对的但不完整：{@code 0/4} 的语义是"从第 0 小时起、每 4 小时"，
	 *       真正会跑的是 0/4/8/12/16/20 这六个整点。中文里点出来，用户不必自己推。</li>
	 * </ul>
	 */
	private static String describeSchedule(String[] f) {
		String second = f[0];
		String minute = f[1];
		String hour = f[2];

		if (isEvery(minute) && isEvery(hour)) {
			// 每 N 秒（分与时都通配）
			String every = everyLabel(second, "秒");
			return every == null ? unparsedTime(f) : "每 " + every;
		}
		if (fixed(second) && isEvery(hour)) {
			// 每 N 分（秒固定、时通配）。「分」是固定值时它是"每小时的某一分"，
			// 那与"每 N 分钟"不是一回事，所以分开说
			String every = everyLabel(minute, "分钟");
			return every == null ? unparsedTime(f) : "每 " + every;
		}
		if (fixed(second) && fixed(minute)) {
			// 每 N 小时（秒与分都固定，"时"是步长形态）
			String everyHours = everyLabel(hour, "小时");
			if (everyHours != null) {
				return "每 " + everyHours + "（从 0 点起算，即 " + stepHours(hour) + " 各跑一次）";
			}
			// 「时」是一个列表或区间（"工作日 8~18 点"那种）：把原文点出来，别猜
			return "每天 " + hour + " 点";
		}
		return unparsedTime(f);
	}

	/**
	 * {@code 0/4} 这种步长形态真正会跑的那几个小时（"0、4、8、12、16、20"）。
	 *
	 * <p>说不出来时返回原文 —— 那是"这一步没懂"，比编一串数字好。
	 */
	private static String stepHours(String hour) {
		if (!hour.startsWith("0/")) {
			return hour;
		}
		try {
			int step = Integer.parseInt(hour.substring(2));
			if (step < 1 || step > 23) {
				return hour;
			}
			List<String> hours = new ArrayList<>();
			for (int value = 0; value < 24; value += step) {
				hours.add(String.valueOf(value));
			}
			return String.join("、", hours);
		}
		catch (NumberFormatException e) {
			return hour;
		}
	}

	private static String describeDayOfMonth(String field) {
		if (isEvery(field)) {
			return "";
		}
		if (field.startsWith("*/")) {
			return "每隔 " + field.substring(2) + " 天";
		}
		if ("L".equalsIgnoreCase(field)) {
			return "每月最后一天";
		}
		if (field.contains("-")) {
			return "每月 " + field.replace("-", " 号到 ") + " 号";
		}
		if (field.contains(",")) {
			return "每月 " + field.replace(",", "、") + " 号";
		}
		return "每月 " + field + " 号";
	}

	private static String describeDayOfWeek(String field) {
		if (isEvery(field)) {
			return "";
		}
		if (field.contains("-")) {
			String[] range = field.split("-", 2);
			return "每周 " + weekday(range[0]) + "到" + weekday(range[1]);
		}
		if (field.contains(",")) {
			return "每周 " + java.util.Arrays.stream(field.split(",")).map(ScheduleCron::weekday)
					.collect(java.util.stream.Collectors.joining("、"));
		}
		if (field.startsWith("*/")) {
			return "每隔 " + field.substring(2) + " 周";
		}
		return "每周 " + weekday(field);
	}

	private static String describeMonth(String field) {
		return isEvery(field) ? "" : field.replace(",", "、").replace("-", "~") + " 月";
	}

	/**
	 * 星期几的中文：认数字也认名字，认不出的原样返回（不编一个确定的中文）。
	 *
	 * <p>数字按 {@code % 7} 取模：Spring 的「周」接受 0~7，而 0 与 7 都是周日
	 * （实测 {@code 0 0 9 * * 0} 与 {@code 0 0 9 * * 7} 的 next 都是同一个周日）。
	 */
	private static String weekday(String raw) {
		String token = raw.strip();
		for (int i = 0; i < WEEKDAY_LABELS.length; i++) {
			if (token.equalsIgnoreCase(WEEKDAY_LABELS[i].substring(1)) || token.equalsIgnoreCase(english(i))) {
				return WEEKDAY_LABELS[i];
			}
		}
		if (token.chars().allMatch(Character::isDigit)) {
			return WEEKDAY_LABELS[Integer.parseInt(token) % 7];
		}
		return token;
	}

	private static String english(int weekday) {
		return switch (weekday) {
			case 0 -> "SUN";
			case 1 -> "MON";
			case 2 -> "TUE";
			case 3 -> "WED";
			case 4 -> "THU";
			case 5 -> "FRI";
			default -> "SAT";
		};
	}

	/** 这一格是"每…"（含带步长的 {@code &#42;/n}）吗。 */
	private static boolean isEvery(String field) {
		return field.startsWith("*") || field.startsWith("?");
	}

	/** 这一格是一个固定值（不是通配、不是枚举、不是区间、不带步长）吗。 */
	private static boolean fixed(String field) {
		return !isEvery(field) && field.chars().noneMatch(c -> c == '-' || c == ',' || c == '/');
	}

	/** 「每 N 单位」的写法；不是这个形态时返回 {@code null}（调用方据此走"复述原文"那一支）。 */
	private static String everyLabel(String field, String unit) {
		if (isEvery(field)) {
			return "1 " + unit;
		}
		if (field.startsWith("*/")) {
			String step = field.substring(2);
			return ("1".equals(step) ? "" : step + " ") + unit;
		}
		if (field.startsWith("0/")) {
			String step = field.substring(2);
			return ("1".equals(step) ? "" : step + " ") + unit;
		}
		return null;
	}

	/**
	 * 认不出的形态：如实说"这三位是这么写的"，而不是编一个看起来确定的中文。
	 *
	 * <p>这一支存在的理由与整个类同源：界面上那句中文与真正跑的时刻必须是同一件事。
	 * 编错的中文比没有中文更坏 —— 用户会照着它确认"我配对了"。
	 */
	private static String unparsedTime(String[] f) {
		return "在这几个时刻（秒=%s、分=%s、时=%s）".formatted(f[0], f[1], f[2]);
	}

	/** 固定值字段的数值；认不出时给 0（那一格只用于拼 {@code HH:mm:ss} 的展示）。 */
	private static int firstOf(String field) {
		try {
			return Integer.parseInt(field.strip().toLowerCase(Locale.ROOT));
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

}
