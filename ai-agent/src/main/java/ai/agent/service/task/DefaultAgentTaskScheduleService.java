package ai.agent.service.task;

import ai.agent.config.AgentTaskScheduleProperties;
import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.task.AgentTaskSchedule;
import ai.agent.mapper.AgentTaskMapper;
import ai.agent.mapper.AgentTaskScheduleMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.permission.PermissionService;
import ai.agent.service.support.task.ScheduleCron;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AgentTaskScheduleService} 的实现 —— <b>定时任务的写入口</b>。
 *
 * <h2>★ 写规则 = 写库 + 动线程池，两件事一起做</h2>
 * 每一个写动作都同时改两个地方：库里那一行，与调度线程池里那一条线程。
 * 两者的偏斜各有各的坏法，而且<b>都不会报错</b>：
 * <ul>
 *   <li>写了库没装线程 → 界面上规则明明在、状态是启用，但它<b>永远不触发</b>；</li>
 *   <li>装了线程没改库 → 到点派活用的还是旧描述 / 旧任务人，而界面上写着新的。</li>
 * </ul>
 * 所以顺序是固定的：<b>先落库、再动线程池</b>。反过来（先装线程再落库）的话，
 * 装线程那一刻库还没提交，而线程可能立刻触发一次 —— 那一次读到的规则是空的
 * （{@code findById} 返回 null），表现为"第一次触发什么都没发生"。
 *
 * <h2>为什么写动作都带 {@code @Transactional}，而线程池的操作放在方法体里</h2>
 * 线程池不是事务资源（回滚不回滚线程）。这里的做法是<b>让落库先成立</b>：
 * 落库失败会抛出，后面那一步根本不会执行；落库成功之后装线程失败（例如正则解析不过）
 * 会让整个请求以 400 结束，而库里那一行已经写进去了 —— 这一点是刻意的：
 * 那条规则是**用户填的那一份**（连同错的正则），界面上要能看到它、改它；
 * 静默丢弃用户的输入会让"我明明保存过"变成一句无法核实的话。
 * 而它的线程没装上，由 {@link #snapshot()} 的 {@code scheduled} 计数看得出来。
 *
 * <h2>发布人 / 任务人必须是真实账号</h2>
 * 判据与 {@code DefaultAgentTaskService#realUser} 逐字相同，而且<b>故意不抽公共方法</b>：
 * 这一层的报错要把用户引到"定时任务那一页的选人框"，而那一层要引到"发布任务那个弹窗"——
 * 两处指向的下一步操作不同，合成一个方法就只能写一句含糊的公共说明。
 * 定时规则这边多一层要求：<b>两个人都必须在</b>，因为这条规则要长期存在，
 * 而它每次派活都要求这两个 id 能换回登录名（见 {@code AgentTaskScheduleRunner#loginOf}）。
 */
@Service
public class DefaultAgentTaskScheduleService implements AgentTaskScheduleService {

	private static final Logger log = LoggerFactory.getLogger(DefaultAgentTaskScheduleService.class);

	private final AgentTaskScheduleMapper schedules;

	/**
	 * {@code agent_task} 表的<b>只读</b>入口 —— 这一页只用它做一件事：
	 * 数"这条规则派过几条任务"（{@code scheduleRunCounts}，见 {@link #runCounts}）。
	 *
	 * <p>字段名刻意不叫 {@code tasks}：本类同时依赖 {@link AgentTaskService}
	 * （派活要用它），两个都叫 tasks 时下面那行 {@code tasks.create(...)} 会编译失败 ——
	 * 而那种失败读起来像"服务上没这个方法"。
	 */
	private final AgentTaskMapper taskRows;

	/**
	 * <b>派活用的入口</b> —— 手工触发与到点触发都走它（{@link AgentTaskService#create}）。
	 *
	 * <p>用它而不是直接写 {@code agent_task} 表：定时派出来的活必须与人发的活
	 * <b>走同一条路</b>（同一套校验、同一次"新发布也要调度"），
	 * 直接插表的写法会绕开那些判断，而那两个地方的差别不会立刻显出来
	 * （它表现为"定时派的任务没人推它走第一步"，而人发的正常）。
	 *
	 * <p>字段名是 {@code taskService} 而不是 {@code tasks}：本类同时依赖
	 * {@link AgentTaskMapper}（数派过几条），两个都叫 tasks 时下面那行
	 * {@code taskService.create(...)} 会编译失败 —— 而那种失败读起来像"服务上没这个方法"。
	 */
	private final AgentTaskService taskService;

	private final AgentTaskScheduleScheduler scheduler;

	private final AgentTaskScheduleProperties properties;

	/**
	 * 账号表（发布人 / 任务人必须是真实用户）。
	 *
	 * <p>读的是权限域的服务而不是 {@code AccountMapper}：账号的存取口径（名字怎么比、
	 * 读失败怎么报）在那一层只有一份（与 {@code DefaultAgentTaskService} 同一条理由）。
	 */
	private final PermissionService permissions;

	/**
	 * 把描述模板解析成"当下这条任务的描述"（见 {@link AgentTaskScheduleDescriber}）。
	 *
	 * <p>手工触发那一条路也要它，而且用的是<b>同一个实现</b>：界面上的「现在跑一次」
	 * 正是"先配好、确认一次再启用"的用法 —— 如果那一次不做解析，
	 * 用户看到的描述与真正到点派出去的那一句就不是同一句，那这个按钮的用途就落空了。
	 */
	private final AgentTaskScheduleDescriber describer;

	public DefaultAgentTaskScheduleService(AgentTaskScheduleMapper schedules, AgentTaskMapper taskRows,
			AgentTaskService taskService, AgentTaskScheduleScheduler scheduler,
			AgentTaskScheduleProperties properties, PermissionService permissions,
			AgentTaskScheduleDescriber describer) {
		this.schedules = schedules;
		this.taskRows = taskRows;
		this.taskService = taskService;
		this.scheduler = scheduler;
		this.properties = properties;
		this.permissions = permissions;
		this.describer = describer;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public Snapshot snapshot() {
		List<AgentTaskSchedule> all = schedules.list().stream().map(row -> row.toDomain()).toList();
		return new Snapshot(views(all), null, poolStatus());
	}

	@Override
	public RuleView rule(Long id) {
		AgentTaskSchedule rule = require(id);
		return view(rule, runCounts(List.of(id)), ZonedDateTime.now());
	}

	@Override
	public AgentTaskSchedule require(Long id) {
		if (id == null) {
			throw new TaskInvalidException("缺少规则 id");
		}
		MapperRows.AgentTaskScheduleRow row = schedules.findById(id);
		if (row == null) {
			throw new TaskInvalidException("定时规则不存在：#" + id);
		}
		return row.toDomain();
	}

	@Override
	public String loginName(Long userId) {
		if (userId == null) {
			return "";
		}
		String name = schedules.findLoginName(userId);
		return name == null ? "" : name;
	}

	/**
	 * 把若干条规则装配成界面要的那一份：补上中文说法、接下来几次触发、派过几条。
	 *
	 * <p>三件事都是<b>一次算一批</b>，不是每条规则算一次：
	 * 触发时刻是本地计算（便宜，但每条规则要解析一次正则）、派活条数是一条
	 * {@code GROUP BY} 查询（见 {@link AgentTaskMapper#scheduleRunCounts} ——
	 * 每条规则查一次的写法是典型的 N+1，而这一页的规则条数会随着用的人变多而变多）。
	 *
	 * <p>起点只取一次（{@link ZonedDateTime#now()}）：同一次响应里每条规则都按<b>同一个</b>
	 * "现在"往后算，否则列表里上下两行的"下一次"会落在不同的基准上 ——
	 * 差几毫秒看不出来，但"我配的是同一时刻，为什么两行的下一次差一分钟"是会被问的问题。
	 */
	private List<RuleView> views(List<AgentTaskSchedule> rules) {
		List<Long> ids = rules.stream().map(AgentTaskSchedule::id).filter(Objects::nonNull).toList();
		Map<Long, MapperRows.ScheduleRun> runs = runCounts(ids);
		ZonedDateTime now = ZonedDateTime.now();
		List<RuleView> views = new ArrayList<>(rules.size());
		for (AgentTaskSchedule rule : rules) {
			views.add(view(rule, runs, now));
		}
		return List.copyOf(views);
	}

	private RuleView view(AgentTaskSchedule rule, Map<Long, MapperRows.ScheduleRun> runs, ZonedDateTime now) {
		MapperRows.ScheduleRun run = runs.get(rule.id());
		return new RuleView(rule, describeOf(rule.cron()), run == null ? 0L : run.runs(),
				run == null ? null : run.lastAt(), nextRunsOf(rule, now));
	}

	/**
	 * 这句正则的中文说法（界面显示、回执文案都用它）。
	 *
	 * <p>它是<b>算出来的展示文案</b>，不属于那条记录 —— 所以它不进实体、不落库
	 * （理由见 {@code AgentTaskScheduleService.RuleView} 的注释：存一份就会多出第二真相）。
	 * 算不出来（手工改库改坏了）时给空串，界面按"这一条算不出下一次"那一支显示。
	 */
	private static String describeOf(String cron) {
		try {
			return ScheduleCron.describe(cron);
		}
		catch (RuntimeException e) {
			return "";
		}
	}

	/**
	 * 这条规则接下来几次触发时刻 —— <b>停用的规则给空列表</b>。
	 *
	 * <p>⚠️ 这一格与 {@code scheduled} 是**同一个判据的两面**，不能各算各的：
	 * 停用的规则不在调度线程池里（线程被摘掉了），所以它没有"下一次"。
	 * 让它照样算出 5 个时刻的后果很具体 —— 界面上会写着"下一次触发 09:00"，
	 * 而它其实一次都不会跑；并且控制器那句 `scheduled = enabled && !nextRuns.isEmpty()`
	 * 会跟着判成 true，"启用却没挂上线程"那条提示也因此永远不会出现
	 * （实测就是这样：停用之后 `nextRuns` 仍然有 5 个，`scheduled` 也是 true）。
	 *
	 * <p>⚠️ 算不出来时也返回<b>空列表</b>而不是抛：一条手工改库改坏了的规则会让整页打不开，
	 * 而"这一条算不出下一次"恰恰是那一页最该显示出来的事（见 {@code ScheduleCron#validate}：
	 * 写入路径上这种表达式已经被拒了，能走到这里的只有改库那一种）。
	 */
	private static List<Instant> nextRunsOf(AgentTaskSchedule rule, ZonedDateTime now) {
		if (!rule.enabled()) {
			return List.of();
		}
		try {
			return ScheduleCron.nextRuns(CronExpression.parse(rule.cron()), ScheduleCron.PREVIEW_COUNT, now);
		}
		catch (RuntimeException e) {
			log.warn("定时规则 #{} 的正则「{}」算不出触发时刻，这一行在界面上会显示成「算不出下一次」：{}",
					rule.id(), rule.cron(), e.toString());
			return List.of();
		}
	}

	/** 一批规则的派活条数（{@code GROUP BY schedule_id}，空集合时不查 —— `IN ()` 是语法错）。 */
	private Map<Long, MapperRows.ScheduleRun> runCounts(List<Long> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, MapperRows.ScheduleRun> runs = new HashMap<>();
		for (MapperRows.ScheduleRunRow row : taskRows.scheduleRunCounts(ids)) {
			MapperRows.ScheduleRun run = row.toDomain();
			runs.put(run.scheduleId(), run);
		}
		return runs;
	}

	@Override
	public Preview preview(String expression) {
		String normalized = normalize(expression);
		checked(normalized);
		return new Preview(normalized, ScheduleCron.describe(normalized),
				ScheduleCron.nextRuns(CronExpression.parse(normalized), ScheduleCron.PREVIEW_COUNT));
	}

	/**
	 * 正则的判据走一遍，并把 {@link IllegalArgumentException} 翻成 <b>400</b>。
	 *
	 * <p>⚠️ 这一层翻译非有不可：{@code ScheduleCron} 抛的是 {@code IllegalArgumentException}
	 * （它属于"这段文本不是一个合法的正则"，而不是服务器故障），而
	 * {@code ErrorResponseAdvice} 只认 {@code ResponseStatusException} 那一族 ——
	 * 漏了这一层，用户在界面上看到的是一句"服务器出错了"（HTTP 500），
	 * 而真正的原因（哪个字段、什么值超了）一个字都没到。
	 * 实测就在预览上踩过：5 段式、日+周同时具体、2 月 31 号三种输入全是 500。
	 *
	 * @param normalized {@link #normalize} 之后的那一份
	 * <p>它是<b>保险</b>而不是唯一的一道：{@link AgentTaskSchedule} 的紧凑构造器里也调了同一个判据，
	 * 那里抛出的 {@link IllegalArgumentException} 由 {@link #draft} 翻。
	 * 两处都要有 —— 落库前的那一次（这里）给出的是"是哪个字段、什么值超了"的原文，
	 * 而实体那一次保证"任何构造点都过同一份判据"。
	 */
	private static void checked(String normalized) {
		try {
			ScheduleCron.validate(normalized);
		}
		catch (IllegalArgumentException e) {
			throw new TaskInvalidException(e.getMessage());
		}
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@Override
	@Transactional
	public RuleView create(String publisher, String assignee, String description, String cron, boolean enabled) {
		String normalized = normalize(cron);
		// 正则先过一遍：它是这条规则里唯一"写错了会安静地不触发"的一格，所以要在落库之前拦住
		checked(normalized);
		AgentTaskSchedule rule = draft(realUser(publisher, "发布人"), realUser(assignee, "任务人"), description,
				normalized, enabled);
		schedules.insert(rule.cron(), rule.publisherId(), rule.assigneeId(), rule.description(), rule.enabled());
		Long id = AgentTaskTransitions.requiredKey(schedules.lastInsertId(), "定时规则");
		AgentTaskSchedule saved = require(id);
		log.info("新建定时规则 #{}：{}", id, saved.describe(describeOf(saved.cron()), nextRunsOf(saved, java.time.ZonedDateTime.now())));
		// 启用才装线程。这一步在落库之后（理由见类注释）
		if (saved.enabled()) {
			scheduler.add(saved);
		}
		else {
			log.info("定时规则 #{} 建的时候就是停用的，没有装线程", id);
		}
		return rule(id);
	}

	@Override
	@Transactional
	public RuleView update(Long id, String assignee, String description, String cron, boolean enabled) {
		AgentTaskSchedule existing = require(id);
		String normalized = normalize(cron);
		checked(normalized);
		Account person = realUser(assignee, "任务人");
		AgentTaskSchedule updated = existing.reconfigured(normalized, person.id(), person.name(), description,
				enabled);
		if (schedules.update(id, updated.cron(), updated.assigneeId(), updated.description(), updated.enabled()) != 1) {
			throw new TaskInvalidException("定时规则不存在：#" + id);
		}
		log.info("定时规则 #{} 已改：{}（正则 {} → {}）", id, updated.describe(), existing.cron(), updated.cron());
		// ★ 删旧线程、重加新的 —— **即使表达式逐字相同也走这一条**（理由见 AgentTaskScheduleService 的类注释）
		scheduler.remove(id);
		if (enabled) {
			scheduler.add(require(id));
		}
		return rule(id);
	}

	@Override
	@Transactional
	public RuleView setEnabled(Long id, boolean enabled) {
		AgentTaskSchedule existing = require(id);
		if (existing.enabled() == enabled) {
			// 幂等：重复点同一个开关不该报错，也不该白跑一次"摘线程 + 装线程"
			log.info("定时规则 #{} 已经是「{}」，这次启停没有变化", id, enabled ? "启用" : "停用");
			return rule(id);
		}
		if (schedules.updateEnabled(id, enabled) != 1) {
			throw new TaskInvalidException("定时规则不存在：#" + id);
		}
		log.info("定时规则 #{} → {}（{}）", id, enabled ? "启用" : "停用",
				enabled ? "线程已装回调度线程池" : "线程已从调度线程池摘除");
		if (enabled) {
			scheduler.add(require(id));
		}
		else {
			scheduler.remove(id);
		}
		return rule(id);
	}

	@Override
	public TriggerResult trigger(Long id) {
		// 不 @Transactional：这一步只写一条 agent_task（它自己那个事务），
		// 而这边的规则行一个字节都不改 —— 手工触发**不重排下一次触发**
		AgentTaskSchedule rule = require(id);
		AgentTaskService.Detail detail = dispatch(rule);
		log.info("定时规则 #{} 被手工触发：派出 agent 任务 #{}（{}）",
				id, detail.task().id(), detail.task().status().label());
		return new TriggerResult(id, detail.task().id(), detail.task().status().label());
	}

	/**
	 * 按一条规则派一条任务（手工触发与到点触发<b>共用这一条</b>）。
	 *
	 * <h2>★ 描述先进解析这一步，再落进任务</h2>
	 * 规则里存的那句是**周期性**的（"把昨天的失败运行汇总成晨报"），
	 * 而派出去的任务是一次性的活 —— 执行它的 Agent 看不到"这是每天的规则"，
	 * 它只看到那一句话。所以这里先把"昨天"换成具体日期，再建任务。
	 * 两条触发路径走的是**同一份解析**（见 {@link AgentTaskScheduleDescriber}），
	 * 否则"手工试跑看到的描述"与"真跑出来的描述"会是两句不同的话。
	 *
	 * <p>解析失败时它抛 {@link TaskScheduleResolveException}（HTTP 502，由
	 * {@code ErrorResponseAdvice} 带回那句原因）—— 手工触发是**人在等结果**的动作，
	 * 所以"这一次没派活"必须当场说出来；而到点触发那一条路是自己接住、留到下一个周期重试。
	 *
	 * <p>另外两个参数（发布人 / 任务人）在这一层是登录名 —— {@code AgentTaskService#create}
	 * 收的就是登录名，而规则里存的是用户 id。换名字这一步与到点那条路一样会失败
	 * （账号被清掉了），失败时给 400，而不是派一条挂在不存在的人名下的任务。
	 */
	private AgentTaskService.Detail dispatch(AgentTaskSchedule rule) {
		AgentTaskScheduleDescriber.Resolved resolved = describer.resolve(rule.id(), rule.description(),
				Instant.now(), properties.zone());
		return taskService.create(AgentTaskService.Draft.scheduled(rule.id(), loginOf(rule.publisherId(), "发布人"),
				loginOf(rule.assigneeId(), "任务人"), resolved.description()));
	}

	@Override
	@Transactional
	public void delete(Long id) {
		AgentTaskSchedule rule = require(id);
		MapperRows.ScheduleRun run = runCounts(List.of(id)).get(id);
		long produced = run == null ? 0L : run.runs();
		if (produced > 0) {
			throw new TaskInvalidException(("定时规则 #%d 已经派过 %d 条任务，不能删 —— 那些任务还留在 "
					+ "agent任务 那一页，而这条规则是它们唯一的由来（任务上没有外键指向它，删了之后"
					+ "「这些活是谁派的」就再也答不出来）。要停就**停用**：停用同时会把它的线程从"
					+ "调度线程池摘掉，效果与删除一样，而由来还在。")
					.formatted(id, produced));
		}
		if (schedules.delete(id) != 1) {
			throw new TaskInvalidException("定时规则不存在：#" + id);
		}
		scheduler.remove(id);
		log.info("定时规则 #{} 已删除（它一条任务都没派过）：{}", id, rule.describe());
	}

	// ------------------------------------------------------------------
	//  调度线程池
	// ------------------------------------------------------------------

	@Override
	public void register(AgentTaskSchedule rule) {
		scheduler.add(rule);
	}

	@Override
	public void unregister(Long id) {
		scheduler.remove(id);
	}

	@Override
	public PoolStatus poolStatus() {
		int[] counts = scheduler.threadCounts();
		return new PoolStatus(scheduler.poolSize(), counts[1], counts[0], scheduler.scheduledCount(),
				scheduler.cancelledPending());
	}

	// ------------------------------------------------------------------
	//  内部
	// ------------------------------------------------------------------

	/** 正则的归一与出错翻译：判据在 {@code ScheduleCron}，这里只把它翻成 400。 */
	private static String normalize(String cron) {
		if (cron == null || cron.isBlank()) {
			throw new TaskInvalidException("定时正则必填 —— 格式是 6 段「秒 分 时 日 月 周」，"
					+ "例：0 0 9 * * MON-FRI（周一到周五每天 09:00:00）。"
					+ "界面上可以按「每天 / 每周 / 每月」直接配出来，不必手写");
		}
		try {
			return ScheduleCron.normalize(cron);
		}
		catch (IllegalArgumentException e) {
			throw new TaskInvalidException(e.getMessage());
		}
	}

	/**
	 * 建规则的实体构造，报错翻译成 400。
	 *
	 * <p>与 {@code DefaultAgentTaskService#draft} 同一条：非空与长度上限的判据只有
	 * {@link AgentTaskSchedule} 那一份（控制器只拦"根本没传"），
	 * 而它抛的 {@link IllegalArgumentException} 到不了 {@code ErrorResponseAdvice} 的 400 分支。
	 */
	private static AgentTaskSchedule draft(Account publisher, Account assignee, String description, String cron,
			boolean enabled) {
		try {
			return AgentTaskSchedule.draft(cron, publisher.id(), publisher.name(), assignee.id(), assignee.name(),
					description, enabled);
		}
		catch (IllegalArgumentException e) {
			throw new TaskInvalidException(e.getMessage());
		}
	}

	/**
	 * 发布人 / 任务人必须是从账号表里选出来的真实用户 —— 判据与
	 * {@code DefaultAgentTaskService#realUser} 相同，只有报错文案指向的下一步不同
	 * （这里要用户去"定时任务那一页的选人框"，那边是"发布任务那个弹窗"）。
	 *
	 * <p>它拦的是"随手打一个名字"：规则里存的是用户 id，而打错的字在账号表里一个都查不到 ——
	 * 那样一条规则在界面上与正常的完全一样，只是它每次触发的<b>那一刻</b>才会失败
	 * （见 {@code AgentTaskScheduleRunner#loginOf}），而那时用户在别的一页上。
	 */
	private Account realUser(String name, String what) {
		if (name == null || name.isBlank()) {
			throw new TaskInvalidException(what + "必填 —— 发布人与任务人都要从用户列表里选一个真实用户"
					+ "（搜索：GET /api/permissions/users/search?keyword=…）");
		}
		String wanted = name.strip();
		return permissions.findAccount(wanted)
				.orElseThrow(() -> new TaskInvalidException(("账号表里没有「%s」这个人，不能当%s —— 定时任务的"
						+ "发布人与任务人都必须是真实用户，因为每次触发都要用这两个 id 换回登录名去建任务；"
						+ "打错一个字不会有任何东西拦下这条规则，它只会在**触发的那一刻**失败，"
						+ "而那时你不在这一页上。").formatted(wanted, what)));
	}

	/** 用户 id → 登录名（手工派活那一条路要的那一份）。 */
	private String loginOf(Long userId, String what) {
		if (userId == null) {
			throw new TaskInvalidException("这条规则没有" + what + "（用户 id 为空），不能派活");
		}
		return permissions.findAccountById(userId)
				.map(Account::name)
				.orElseThrow(() -> new TaskInvalidException(
						("这条规则的%s（用户 id %d）在账号表里已经不在了 —— 不能按一个不存在的人派活。"
								+ "请把任务人改成现有账号；发布人改不了，需要停用这条规则后重建一条。")
								.formatted(what, userId)));
	}

}
