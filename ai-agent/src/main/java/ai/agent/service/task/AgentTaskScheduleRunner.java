package ai.agent.service.task;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.task.AgentTaskSchedule;
import ai.agent.mapper.AgentTaskScheduleMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>一条规则到点时执行的那个动作</b> —— 按这条规则生成一条 agent 任务记录，就这一件事。
 *
 * <h2>★ 它不跑模型、不碰文件、不走 ReAct 循环</h2>
 * 生成的是一条 {@code agent_task}，而那条任务接下来怎么走由<b>任务执行器</b>负责
 * （判需求 → 拆任务点 → 判授权 → 执行 → 验收，见 {@code AgentTaskScheduler}）。
 * 这条分界决定了三件事：
 * <ol>
 *   <li>"定时任务派出来的活"与"人在界面上发布的活"在系统里<b>没有任何行为差异</b>
 *       —— 同一个入口（{@link AgentTaskService#create(AgentTaskService.Draft)}）、
 *       同一条状态链、同一个执行器；</li>
 *   <li>这个动作是<b>毫秒级</b>的（一次插入 + 一次异步调度），所以它占调度线程池那一格的时间
 *       可以忽略 —— 池子的大小不是"能同时干几件活"（见 {@code AgentTaskScheduleScheduler}）；</li>
 *   <li>失败必须在这里被接住并打全日志：{@code scheduleAtFixedRate} 那一族在任务抛出时
 *       <b>会静默地取消后续执行</b>，而 Spring 的 {@code ThreadPoolTaskScheduler}
 *       只是记一条日志 —— 两种行为在界面上都表现为"这条规则不再触发了"。</li>
 * </ol>
 *
 * <h2>★ 每次触发都重新读一次这条规则，不缓存</h2>
 * 手上这一份（构造时传进来的）只用来知道"我是哪条规则"。
 * 真正派活之前必须按 id 从库里读<b>当前</b>那一行，两个理由：
 * <ol>
 *   <li>规则可能已经被<b>停用或删掉</b>了（改规则会删旧线程重加，但"取消"与"正在跑"
 *       之间有一段窗口，而且停机前的最后一次触发也可能排在这里）——
 *       停用之后照样派活是这一页最不能接受的一种错，因为用户以为它已经停了；</li>
 *   <li>规则可能已经被<b>改过</b>（描述、任务人）。用旧的那一份会派出一条按旧描述执行的任务，
 *       而界面上明明白白写着新描述。</li>
 * </ol>
 * 读回来那一行的 cron 是什么<b>不影响</b>这一次触发：调度时刻是线程池按旧表达式算好的，
 * 而"下一次"由池子里那条新线程负责（旧线程在这次跑完之后就会被取消）。
 *
 * <h2>发布人 / 任务人：库里存的是用户 id，这个入口要的是登录名</h2>
 * {@link AgentTaskService#create} 收的是<b>登录名</b>（它拿账号表核一遍再落 id）。
 * 所以这里把两个 id 换回登录名 —— 换不回来的那一种（账号被清掉了）直接失败并记日志：
 * 派一条发布人不存在、或者任务人不存在
 * 的任务，在库里看起来完全正常，而它永远没有对应的人（见 {@code DefaultAgentTaskService#realUser}）。
 */
final class AgentTaskScheduleRunner implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(AgentTaskScheduleRunner.class);

	/** 装进来时那一条规则（只用它的 id；其余字段以每次触发时读回来的那一行为准）。 */
	private final AgentTaskSchedule rule;

	/** 触发计数（与调度器共用同一个；只用于日志与诊断，不落库）。 */
	private final AtomicLong fired;

	private final AgentTaskScheduleMapper schedules;

	private final AgentTaskService tasks;

	private final PermissionService permissions;

	/**
	 * 把描述模板解析成"当下这条任务的描述"（见 {@link AgentTaskScheduleDescriber}）。
	 *
	 * <p>它<b>不在</b>"生成任务记录"那一步里：那一步是与人工发布共用的一条路
	 * （{@code AgentTaskService#create}），而"描述里那些周期说法是什么意思"只有定时这一侧才知道。
	 * 所以解析在这里做完、把结果当成描述传进去 —— 落到库里的那句就是最终那句。
	 */
	private final AgentTaskScheduleDescriber describer;

	/** 解析时用的时区（配置项 {@code ai.agent.schedule.timezone}）。 */
	private final ZoneId zone;

	AgentTaskScheduleRunner(AgentTaskSchedule rule, AtomicLong fired, AgentTaskScheduleMapper schedules,
			AgentTaskService tasks, PermissionService permissions, AgentTaskScheduleDescriber describer,
			ZoneId zone) {
		this.rule = rule;
		this.fired = fired;
		this.schedules = schedules;
		this.tasks = tasks;
		this.permissions = permissions;
		this.describer = describer;
		this.zone = zone;
	}

	@Override
	public void run() {
		long count = fired.incrementAndGet();
		Long id = rule.id();
		try {
			MapperRows.AgentTaskScheduleRow row = schedules.findById(id);
			if (row == null) {
				// 规则被删了，而池子里那条线程还在（它会在下一次改规则/重启时消失）。
				// 不派活，也不抛异常：抛出去只会让调度器记一条 WARN，而这件事的正常处置
				// 就是"这一条不再触发"
				log.info("定时规则 #{} 已经不在库里了，这一次触发跳过（它的线程会在下一次重启或改规则时被摘掉）", id);
				return;
			}
			AgentTaskSchedule current = row.toDomain();
			if (!current.enabled()) {
				// 停用了却还在触发 —— 说明池子里那条线程没被摘掉。这是**必须看得见**的一件事：
				// 用户以为已经停了，而它还在派活。所以按 WARN 记，并且不派活
				log.warn("定时规则 #{} 已经停用（enabled=0），但它的线程仍在触发 —— 这一次不派活；"
						+ "如果反复出现，说明摘线程那一步没走到（见 AgentTaskScheduleService#setEnabled）", id);
				return;
			}
			String publisher = loginOf(current.publisherId(), "发布人", id);
			String assignee = loginOf(current.assigneeId(), "任务人", id);
			// ★ 描述先进解析这一步：规则里那句是**周期性**的（"把昨天的失败运行汇总成晨报"），
			// 而派出去的是一条一次性的活 —— 执行它的 Agent 看不到"这是每月 1 号的规则"，
			// 它只看到那一句话。所以"昨天"必须在**这一刻**换成具体日期。
			// ⚠️ 解析失败时**不派活**（下一次周期重试）：拿模板原文派出去，
			// Agent 会照"昨天"去猜一个日子，而猜错是干错事（写错文件、汇总错日期），不是晚一天。
			AgentTaskScheduleDescriber.Resolved resolved = describer.resolve(id, current.description(),
					Instant.now(), zone);
			AgentTaskService.Detail detail = tasks.create(AgentTaskService.Draft.scheduled(id, publisher, assignee,
					resolved.description()));
			log.info("定时规则 #{} 第 {} 次触发：派出一条 agent 任务 #{}（{}）—— {}{}",
					id, count, detail.task().id(), detail.task().status().label(), current.describe(),
					resolved.changed() ? "；描述已按当下时间解析（模板里的周期说法换成了具体日期）"
							: "；描述与模板逐字相同（模板里没有时间说法，或模型没认出来）");
		}
		catch (TaskScheduleResolveException e) {
			// 与下面那条 catch 分开：这一种失败的处置是"这一次不派活、等下一个周期"，
			// 而其余失败（账号查不到、库里读不到）是"这一条规则本身有问题"——
			// 两者的下一句话不同，日志里必须分得开
			log.warn("定时规则 #{} 这一次触发没派活：描述模板没解析出来（它下一个周期会重试）：{}", id, e.getMessage());
			log.debug("定时规则 #{} 描述解析失败的完整堆栈", id, e);
		}
		catch (RuntimeException e) {
			// 一条规则失败不该让池子里别的规则停摆，也不该让这一次的异常冒到调度器上
			// （那样只会多一条看不懂的 WARN，而真正的原因在这里）
			log.warn("定时规则 #{} 这一次触发失败了，它下一次仍会照常触发：{}", id, e.toString());
			log.debug("定时规则 #{} 触发失败的完整堆栈", id, e);
		}
	}

	/**
	 * 用户 id → 登录名（{@link AgentTaskService#create} 收的那一份）。
	 *
	 * <p>账号查不到时<b>拒绝派活</b>并说明是谁：那条任务会挂在一个不存在的人名下，
	 * 而它在界面上与一条正常任务长得一模一样。这种情形来自"账号被清掉了"
	 * （agent_user 只停用不删除，所以正常路径上不会发生）。
	 */
	private String loginOf(Long userId, String what, Long scheduleId) {
		if (userId == null) {
			throw new IllegalStateException("定时规则 #%d 没有%s —— 这一列在写入路径上是必填的"
					.formatted(scheduleId, what));
		}
		return permissions.findAccountById(userId)
				.map(Account::name)
				.orElseThrow(() -> new IllegalStateException(
						("定时规则 #%d 的%s（用户 id %d）在账号表里已经不在了 —— 派出去的任务会挂在一个"
								+ "不存在的人名下，所以这一次不派。请把这条规则的任务人改成现有账号"
								+ "（发布人改不了，那条规则该停用后重建一条）。")
								.formatted(scheduleId, what, userId)));
	}

}
