package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * <b>agent 定时任务</b>的配置（前缀 {@code ai.agent.schedule}）。
 *
 * <h2>为什么只有两个键</h2>
 * 这一页的绝大部分东西是<b>数据</b>（用户敲进去的规则：正则、发布人、任务人、描述），
 * 不是部署参数。刻意做成配置的只剩这两件"跑起来才知道合不合适"的事：
 * 关掉整件事的开关，与线程池的大小。
 * <p>⚠️ 刻意<b>没有</b>"默认时区""扫描间隔"这类键：时区由 JVM 定（{@code CronTrigger}
 * 用的就是 JVM 默认时区，与界面上"下一次触发时刻"的算法同一处 —— 两处用两个时区的话，
 * 预览说 09:00 而实际 10:00 跑，且不报错）；扫描间隔不存在，因为规则是**装进线程池**的
 * （不是每次轮询全表），改一条只影响那一条。
 *
 * @param enabled 总开关。<b>关掉时不装任何线程</b>（已经在池子里的也会被摘掉），
 *        但规则本身照常留在库里、界面上照常看得见、也能手工触发 ——
 *        关的是"自动到点派活"这一件事，不是这一页。
 *        做成能关是因为它是"无人值守地往系统里灌任务"的唯一入口：
 *        出问题时需要一个能立刻停下来的把手，而"去把每条规则逐个停用"在紧急时太慢
 * @param poolSize 调度线程池的线程数。
 *        <b>它不是"能同时干几件活"</b>：一条规则占的那一格只在它触发的那一瞬间被占用，
 *        而那个动作是"插一条任务记录"（毫秒级）；真正的活（判需求 → 执行）在
 *        {@code AgentTaskScheduler} 那条串行通道上，不在这个池子里。
 *        所以这个数是"同一瞬间最多几条规则能真的触发"，4 对"按人配规则"的规模绰绰有余。
 *        ⚠️ 撞上它的后果<b>不是报错</b>：多出来的那几次触发会排队，晚一点执行
 *        （{@code ThreadPoolTaskScheduler} 的队列无界）—— 表现是"这一秒该跑的规则慢了几毫秒"。
 *        构造期拒绝 0 与负数：那会让池子起不来，而报错会以 {@code IllegalArgumentException}
 *        的形式出现在 {@code ThreadPoolTaskScheduler#initialize} 里，离这个键很远
 * @param timezone 解析任务描述模板时用的时区（IANA 名字，例 {@code Asia/Shanghai}）。
 *        <b>它决定「今天 / 昨天 / 上个月」各指哪一天</b>，而那些日期会被写进任务描述去执行
 *        （"汇总 2026-09-30 的失败运行"）—— 所以它<b>不能</b>留给 JVM 默认值：
 *        换一台机器或换一个部署环境，同一条规则就会解析出不同的日期，
 *        而那种偏差在任务记录里看起来完全正常。取值认不出来时拒绝启动
 *        （与 {@code ai.agent.task.permission-level} 同一条规矩）
 */
@ConfigurationProperties(prefix = "ai.agent.schedule")
public record AgentTaskScheduleProperties(boolean enabled, int poolSize, String timezone) {

	/** 池子线程数上限。见类注释：它管的是"同一瞬间最多几条规则能触发"。 */
	public static final int MAX_POOL_SIZE = 64;

	public AgentTaskScheduleProperties {
		if (poolSize < 1) {
			throw new IllegalArgumentException("ai.agent.schedule.pool-size 至少是 1，实际是 " + poolSize
					+ "（它是一瞬间能触发几条规则的上限，不是开关；要关掉整件事请用 ai.agent.schedule.enabled）");
		}
		if (poolSize > MAX_POOL_SIZE) {
			throw new IllegalArgumentException(("ai.agent.schedule.pool-size 最大 %d，实际是 %d —— "
					+ "这个池子里的动作是「插一条任务记录」（毫秒级），开这么大只能说明它被当成了"
					+ "「任务执行的并发数」，而那个上限在 ai.agent.task 那一边"
					+ "（执行是串行的，见 AgentTaskScheduler）").formatted(MAX_POOL_SIZE, poolSize));
		}
		// 取值认不出来**拒绝启动**（与 permission-level 同一条规矩）：一个拼错的时区如果被宽容成
		// "用 JVM 默认"，现象是"描述里的日期差了一天"—— 而那个现象会被归因到模型算错了
		if (timezone == null || timezone.isBlank()) {
			throw new IllegalArgumentException("ai.agent.schedule.timezone 必填（IANA 名字，例 Asia/Shanghai）—— "
					+ "它决定「今天 / 昨天 / 上个月」各指哪一天，而那些日期会被写进任务描述去执行");
		}
		try {
			ZoneId.of(timezone.strip());
		}
		catch (DateTimeException e) {
			throw new IllegalArgumentException(("ai.agent.schedule.timezone 的取值不认识：「%s」（%s）。"
					+ "要的是一个 IANA 时区名，例 Asia/Shanghai、UTC —— 它不能留给 JVM 默认值："
					+ "换一台机器（或换一个部署环境）就会让同一条规则解析出不同的日期。")
					.formatted(timezone, e.getMessage()));
		}
	}

	/** 解析描述时用的时区（构造期已经校验过取值，这里不会拿到降级结果）。 */
	public ZoneId zone() {
		return ZoneId.of(timezone.strip());
	}

	/** 装配用的缺省值（手工构造与探针用；配置里这三个键都要写）。 */
	public static AgentTaskScheduleProperties defaults() {
		return new AgentTaskScheduleProperties(true, 4, "Asia/Shanghai");
	}

}
