package ai.agent.entity.agent.task;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * <b>把"这次触发是哪一天"说成人话</b> —— 定时任务那一次描述解析的用户侧输入。
 *
 * <h2>★ 为什么时间事实要由应用算好、而不是让模型自己推</h2>
 * "昨天是哪一天"这个问题，模型有能力答，但**答对与否不可核对**：
 * 它可能按训练数据的年份算、可能把周末算进工作日、可能把"上周"理解成
 * 上一个自然周还是最近七天。而这一条链路的产物是**要写进任务描述去执行的日期**
 * （"汇总 2026-09-30 的失败运行"）—— 猜错的后果是去汇总了错的那一天，
 * 而它在任务记录里看起来完全正常。
 *
 * <p>所以本类把每一个参照点都**算好、用具体日期写出来**，交给模型照抄；
 * 提示词里那句"不要自己推"指的就是这件事。模型的活只剩"把描述里的
 * 周期说法换成这里给出的某一个日期"，而那件事是可核对的。
 *
 * <h2>为什么不把这些值做成"替换占位符"直接改模板</h2>
 * 因为用户不会照着占位符写规则。没有占位符时"哪个词该换成哪个日期"仍然要有人判断 ——
 * 那一层判断就是模型；本类负责的只是"它手上有一份准确、完整、不用它自己算的时间表"。
 */
public final class TaskSchedulePrompt {

	/** 「当下时间」那一行的写法（把时区名写出来，避免模型按自己的默认时区理解）。 */
	private static final DateTimeFormatter FULL =
			DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm:ss", Locale.ENGLISH);

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);

	private TaskSchedulePrompt() {
	}

	/**
	 * 拼出这一次解析的用户侧输入：<b>时间事实在前、要改写的描述在后</b>。
	 *
	 * <p>描述放在最后是刻意的：模型对"最后那一段"的注意力最强，
	 * 而它是唯一需要被改写的东西（前面那些只是它要照抄的参照）。
	 *
	 * @param template 规则里存的那句描述（可能带周期说法）
	 * @param at 这次触发对应的时刻
	 * @param zone 用它把 {@code at} 说成人话，也是"哪一天"的判据
	 */
	public static String instruction(String template, Instant at, ZoneId zone) {
		ZonedDateTime now = ZonedDateTime.ofInstant(at, zone);
		StringBuilder text = new StringBuilder(1024);

		text.append("## 当下时间\n\n")
				.append("- ").append(FULL.format(now)).append("（时区 ").append(zone.getId()).append("）\n\n");

		text.append("## 常用参照（照抄这里给出的日期，不要自己推）\n\n")
				.append("- 今天 = ").append(DATE.format(now)).append('\n')
				.append("- 昨天 = ").append(DATE.format(now.minusDays(1))).append('\n')
				.append("- 前天 = ").append(DATE.format(now.minusDays(2))).append('\n')
				.append("- 明天 = ").append(DATE.format(now.plusDays(1))).append('\n')
				// 本周按"周一到今天"：与"工作日"这套说法对齐，而周末不在其中
				.append("- 本周（周一到今天）= ")
				.append(DATE.format(now.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))))
				.append(" 到 ").append(DATE.format(now)).append('\n')
				.append("- 上周（完整的一个自然周，周一到周日）= ")
				.append(DATE.format(now.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(1)))
				.append(" 到 ")
				.append(DATE.format(now.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusDays(1)))
				.append('\n')
				.append("- 本月 = ").append(DATE.format(now.withDayOfMonth(1))).append(" 到 ").append(DATE.format(now))
				.append('\n')
				.append("- 上个月（完整的一个自然月）= ")
				.append(DATE.format(now.minusMonths(1).withDayOfMonth(1)))
				.append(" 到 ").append(DATE.format(now.withDayOfMonth(1).minusDays(1)))
				.append('\n')
				.append("- 最近 7 天 = ").append(DATE.format(now.minusDays(6))).append(" 到 ").append(DATE.format(now))
				.append("\n\n");

		text.append("## 最近 7 天分别是星期几（用得上时照抄）\n\n");
		for (int back = 6; back >= 0; back--) {
			ZonedDateTime day = now.minusDays(back);
			text.append("- ").append(DATE.format(day)).append(" 是 ").append(day.getDayOfWeek()).append('\n');
		}
		text.append('\n');

		text.append("## 要改写的描述\n\n")
				.append("<描述>\n").append(template).append("\n</描述>\n\n")
				.append("按系统提示词的规则改写它，只输出改写后的那一句描述本身。");

		return text.toString();
	}

}
