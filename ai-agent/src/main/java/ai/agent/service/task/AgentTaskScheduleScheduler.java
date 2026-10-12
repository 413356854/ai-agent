package ai.agent.service.task;

import ai.agent.config.AgentTaskScheduleProperties;
import ai.agent.entity.agent.task.AgentTaskSchedule;
import ai.agent.mapper.AgentTaskScheduleMapper;
import ai.agent.service.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>定时任务的线程池</b>：一条启用的规则占一格线程，改了就删旧线程重加。
 *
 * <h2>为什么是 {@link ThreadPoolTaskScheduler} 而不是 {@code @Scheduled}</h2>
 * {@code @Scheduled} 的 cron 写在注解里 —— <b>它是编译期常量</b>，
 * 而这一页的功能恰恰是"用户随时改规则"。要让它跟着库走，只能自己拿一个调度器按
 * {@link CronTrigger} 一条条注册。这也是本项目里唯一一处动态调度：
 * {@code AgentTaskScheduler} 那个 {@code @Scheduled} 的周期是部署参数（写配置里就够），
 * 这条的周期是**数据**（用户敲进去的）。
 *
 * <h2>池子里的"一格"意味着什么</h2>
 * 一条规则占的那一格只在<b>它触发的那一瞬间</b>被真正占用：
 * {@link CronTrigger} 到点之后把 {@link AgentTaskScheduleRunner} 提交给池子，
 * 那个动作是"建一条任务记录"（一次数据库插入 + 一次异步调度），毫秒级。
 * 真正的活（判需求 → 拆任务点 → 执行）在 {@code AgentTaskScheduler} 那条串行通道上，
 * 不在这个池子里 —— 所以 {@code pool-size} 不是"能同时跑几条任务的活"，
 * 而是"同一瞬间最多几条规则能真的触发"（见 {@link AgentTaskScheduleProperties}）。
 *
 * <h2>★ 每一条规则的线程由 {@link #futures} 记住，才能"删了重加"</h2>
 * {@link ThreadPoolTaskScheduler#schedule(Runnable, org.springframework.scheduling.Trigger)}
 * 返回的 {@link ScheduledFuture} 是唯一能取消它的把手。
 * 不记住它，"改一条规则"就只剩"再加一条线程"这一条路 ——
 * 结果是同一条规则在池子里有两条线程，而它们<b>都会触发</b>：
 * 界面上规则只有一条，库里一条任务变成两条，且没有任何报错。
 *
 * <p>⚠️ {@code cancel(false)} 之后，{@code ScheduledThreadPoolExecutor} 的取消任务
 * 还留在队列里，直到<原定触发时刻>才被清掉（JDK 的行为，{@code setRemoveOnCancelPolicy(true)}
 * 只影响"取消时立刻摘掉"这一条，而 Spring 6 起已经默认打开它）。
 * 这些残留节点由 {@link #poolStatus()} 的 {@code cancelledPending} 报出来 ——
 * 它不是错误状态，而是"改过很多次的规则"在池子里的痕迹。
 *
 * <h2>触发动作失败不拖垮池子</h2>
 * {@link ThreadPoolTaskScheduler} 对定时任务抛出的异常只记一条日志（它自己接住），
 * 下一次触发照常。这是我们要的：一条规则的正则写错了（或者它的发布人被删了）不该让
 * 池子里别的规则一起停摆 —— 而"整池停摆"这种故障在界面上表现为"所有规则都不动了"，
 * 排查方向会跑到调度器上去。失败原因由 {@link AgentTaskScheduleRunner} 自己打全。
 *
 * <h2>这个池子保护的是"本进程内"，跨进程不成立</h2>
 * 与 {@code AgentTaskScheduler} 同一条边界：多实例部署时两个进程会各装一遍线程，
 * 同一条规则会被两边各触发一次（同一个时刻派出两条任务）。
 * 真正的互斥要靠数据库（例如按规则 id + 触发时刻做一次唯一插入）。
 * 当前形态是单实例本地部署，所以没做 —— 但这条边界要写出来：
 * 它的失效形态是<b>同一时刻多出一条重复的任务</b>，而那会走完整条链（包括写操作）。
 */
@Component
public class AgentTaskScheduleScheduler {

	private static final Logger log = LoggerFactory.getLogger(AgentTaskScheduleScheduler.class);

	/**
	 * 池子。{@code poolSize} 与"是否在取消时立刻摘掉队列节点"都取配置。
	 *
	 * <p>线程名前缀写成 {@code task-schedule-}：{@code /api/diag/threads} 那一页会列出
	 * 本进程的线程，而这个前缀让"定时任务的线程"在一堆线程里一眼认得出
	 * （与容器虚拟线程的 {@code tomcat-vt-} 同一条做法）。
	 */
	private final ThreadPoolTaskScheduler pool;

	/**
	 * 池子配置的线程数上限。
	 *
	 * <p>⚠️ <b>它必须自己存一份</b>：实测 {@code ThreadPoolTaskScheduler#getPoolSize()}
	 * 返回的是 {@code ThreadPoolExecutor#getPoolSize()}，也就是**当前已经建出来的线程数**
	 * （池子按需建线程，刚启动时是 0，与配置的 {@code setPoolSize} 无关）。
	 * 拿它当"上限"报给界面，得到的是"池子线程数上限 0"—— 一句话就把人引到
	 * "配置没读到"上去，而真相是那个方法问的不是同一件事。
	 */
	private final int configuredPoolSize;

	/**
	 * 触发动作要用的几样东西（建任务 / 读账号 / 每次重新读规则 / 解析描述模板）。
	 *
	 * <p>它们<b>不建成 bean</b>：触发动作是这一层的内部细节，而"谁可以建任务"
	 * 这件事不该多出一个公开入口。构造本类时装配，之后每个触发都用同一套。
	 */
	private final AgentTaskScheduleMapper schedules;

	private final AgentTaskService tasks;

	private final PermissionService permissions;

	/** 把描述模板解析成"当下这条任务的描述"（见 {@link AgentTaskScheduleDescriber}）。 */
	private final AgentTaskScheduleDescriber describer;

	/** 解析时用的时区（配置项 {@code ai.agent.schedule.timezone}）。 */
	private final ZoneId zone;

	/** 规则 id → 它那条线程。**这个表就是"哪几条规则在池子里"的唯一判据**。 */
	private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

	/** 触发过多少次（含手工触发）。只用于启动日志与诊断，不落库。 */
	private final AtomicLong fired = new AtomicLong();

	/** 安装/摘除是否已经发生过（用来区分"池子空"与"还没开始装"）。 */
	private final AtomicBoolean touched = new AtomicBoolean(false);

	public AgentTaskScheduleScheduler(AgentTaskScheduleProperties properties, AgentTaskScheduleMapper schedules,
			AgentTaskService tasks, PermissionService permissions, AgentTaskScheduleDescriber describer) {
		this.schedules = schedules;
		this.tasks = tasks;
		this.permissions = permissions;
		this.describer = describer;
		this.zone = properties.zone();
		this.configuredPoolSize = properties.poolSize();
		this.pool = new ThreadPoolTaskScheduler();
		this.pool.setPoolSize(configuredPoolSize);
		this.pool.setThreadNamePrefix("task-schedule-");
		// 取消时立刻把队列里的节点摘掉。不打开的话，改过很多次的规则会在池子里留下
		// 一串已取消但还占着位置的节点，而它们的存在只表现为"池子好像有点满"。
		// 直接设在执行器上：这一条是 JDK 的策略（Spring 6 起默认也是 true，写出来是为了它不随版本变）
		this.pool.setRemoveOnCancelPolicy(true);
		this.pool.setAwaitTerminationSeconds(1);
		this.pool.setWaitForTasksToCompleteOnShutdown(false);
		this.pool.setErrorHandler(error -> log.warn("定时任务的一次触发动作失败了（池子继续跑，下一条规则不受影响）：{}",
				error.toString(), error));
		this.pool.initialize();
	}

	/**
	 * 装一条规则的线程（覆盖式：同一个 id 先删旧的再加新的）。
	 *
	 * <h2>为什么"删旧加新"是无条件的</h2>
	 * 见 {@code AgentTaskScheduleService} 类注释里那三条理由；这里只补一条实现层的：
	 * 判断"新旧表达式是否相同"需要把旧表达式也存进这个表，而<b>那张表存的本来只是把手</b>
	 * （{@link ScheduledFuture}）—— 为了一个可以省掉的 {@code schedule()} 调用
	 * 引入第二份状态，代价与收益不成比例。
	 */
	public void add(AgentTaskSchedule rule) {
		if (rule.id() == null) {
			throw new IllegalStateException("定时规则的 id 还没有（它没落库）—— "
					+ "线程池按 id 记住每一条规则的线程，没有 id 就没有「改了就删旧线程」的把手");
		}
		remove(rule.id());
		// 解析必须在这里再做一次（而不是信实体里那一份）：实体只保证"它被构造时是合法的"，
		// 而从库里读回来的那一份可能是手工改库改出来的。抛在这里的后果是"这一条没装线程"，
		// 而调用方（安装器）会把它记成一条 WARN，别的规则照装
		CronTrigger trigger = new CronTrigger(rule.cron());
		ScheduledFuture<?> future = pool.schedule(
				new AgentTaskScheduleRunner(rule, fired, schedules, tasks, permissions, describer, zone), trigger);
		if (future == null) {
			// schedule 的返回值为 null 只有一种情形：池子已经关掉了。
			// 静默接受会变成"这条规则不在池子里，而没有任何痕迹"
			throw new IllegalStateException("定时规则 #" + rule.id() + " 没能装进调度线程池"
					+ "（池子已经停止？）—— 这一条规则不会有任何触发");
		}
		futures.put(rule.id(), future);
		touched.set(true);
		log.info("定时规则 #{} 已装进调度线程池：{}（下一次触发 {}）", rule.id(), rule.describe(),
				SafeNext.describe(future));
	}

	/** 摘掉一条规则的线程（不在池子里时什么也不做）。 */
	public void remove(Long id) {
		ScheduledFuture<?> previous = futures.remove(id);
		if (previous == null) {
			return;
		}
		// cancel(false)：不打断正在跑的那一次。触发动作是"插一条任务记录"，
		// 打断它可能留下半条写；而"这一次会跑完"是配规则的人不会反对的事
		previous.cancel(false);
		touched.set(true);
		log.info("定时规则 #{} 的线程已从调度线程池摘除（改规则 / 停用 / 删除都会走到这里）", id);
	}

	/** 这一条现在在池子里吗。 */
	public boolean isScheduled(Long id) {
		ScheduledFuture<?> future = futures.get(id);
		return future != null && !future.isCancelled() && !future.isDone();
	}

	/** 现在池子里挂着几条规则的线程。 */
	public int scheduledCount() {
		return (int) futures.values().stream()
				.filter(future -> !future.isCancelled() && !future.isDone())
				.count();
	}

	/**
	 * 池子当前的线程数与忙碌线程数（{@link java.util.concurrent.ThreadPoolExecutor} 的口径）。
	 *
	 * <p>第一个数是"已经建出来的线程数"（按需建，可能小于配置的上限），
	 * 不是配置值 —— 配置值走 {@link #poolSize()}。
	 */
	public int[] threadCounts() {
		var executor = pool.getScheduledThreadPoolExecutor();
		return new int[] { executor.getPoolSize(), executor.getActiveCount() };
	}

	/** 池子配置的线程数上限（见 {@link #configuredPoolSize}：它不是 {@code pool.getPoolSize()}）。 */
	public int poolSize() {
		return configuredPoolSize;
	}

	/**
	 * 已取消、但还没被池子清掉的队列节点数。
	 *
	 * <p>它不是错误：{@code cancel} 之后节点会留到原定触发时刻（见类注释），
	 * 而"改过很多次"的规则会把这个数推高。报出来是为了让那一栏能解释
	 * "池子里明明只有 3 条规则，队列却排着 12 个节点"。
	 */
	public int cancelledPending() {
		return Math.max(0, pool.getScheduledThreadPoolExecutor().getQueue().size() - scheduledCount());
	}

	/** 有没有装过 / 摘过线程。用来区分"池子是空的"与"还没开始装"。 */
	public boolean touched() {
		return touched.get();
	}

	/** 触发总次数（含手工触发），只给日志与诊断用。 */
	public long firedCount() {
		return fired.get();
	}

	/** 池子现有的规则 id（诊断用）。 */
	public List<Long> scheduledIds() {
		return futures.entrySet().stream()
				.filter(entry -> !entry.getValue().isCancelled() && !entry.getValue().isDone())
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
	}

	/**
	 * 下一次触发时刻的日志写法 —— 它算不出来时不能把日志本身弄挂。
	 *
	 * <p>{@code getDelay} 对周期任务给的是<b>下一次</b>触发还有多久，所以"现在 + 这个延迟"
	 * 就是那一刻。已经在跑的或已取消的会抛，那种情况下日志里退化成一句话，不抛出去。
	 */
	private static final class SafeNext {

		private SafeNext() {
		}

		static String describe(ScheduledFuture<?> future) {
			try {
				return Instant.now()
						.plusMillis(Math.max(0, future.getDelay(TimeUnit.MILLISECONDS)))
						.toString();
			}
			catch (RuntimeException e) {
				return "（算不出下一次：" + e.getClass().getSimpleName() + "）";
			}
		}
	}

}
