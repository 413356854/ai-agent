package ai.agent.service.task;

import ai.agent.config.AgentTaskProperties;
import ai.agent.config.TaskReviewLimits;
import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>定时任务：agent 任务执行</b> —— 什么时候跑、谁跑、跑几条。
 *
 * <h2>两个触发源，一个执行通道</h2>
 * <table border="1">
 *   <caption>触发与它的作用</caption>
 *   <tr><th>触发</th><th>什么时候</th><th>为什么需要它</th></tr>
 *   <tr><td>{@link #tick()}（{@code @Scheduled}）</td><td>启动时一次，之后每
 *       {@code ai.agent.task.fixed-delay}（默认 10 分钟）一次</td>
 *       <td><b>兜底</b>：上一次判定失败、或调度时进程正好在重启，任务会留在原地 ——
 *       没有这个周期它就永远停在那里</td></tr>
 *   <tr><td>{@link #onTaskUpdated}（记录更新）</td>
 *       <td>任务被发布 / 答复 / 推进之后</td>
 *       <td>下一步几乎总在 agent 手上（判需求、拆任务、开跑）。等下一个周期才动的话，
 *       一条本来能立刻往下走的任务会白等十分钟，而用户看到的是"刷新了它还在原地"</td></tr>
 * </table>
 * 两者都只做一件事：{@link #dispatch()} —— 提交一次"把待处理的任务跑掉"。
 *
 * <h2>串行：一个执行锁 + 一次一条</h2>
 * <ol>
 *   <li><b>执行锁</b>（{@link #lock}，{@code tryLock} 而不是 {@code lock}）：
 *       同一时刻只有一个执行通道在跑。抢不到锁就<b>直接返回</b>，不排队、不等待 ——
 *       抢不到意味着已经有人在跑了，而那个人跑完会再看一眼有没有新的活
 *       （{@link #dirty} 就是为这句话存在的）；</li>
 *   <li><b>一次一条</b>：{@code AgentTaskExecution#nextPending} 每次只取一条，
 *       跑完再取下一条（而不是一次取一批）。理由在 {@code AgentTaskMapper#findNextPending}
 *       的注释里：一次取一批会让后面几条按一份过期的状态快照去跑。</li>
 * </ol>
 *
 * <h2>为什么"没抢到锁就返回"还需要一个 dirty 标记</h2>
 * 因为"没抢到"的那次调用带来的信息不能丢：它说"库里有一条新的待处理任务"。
 * 只返回不记的话，那次更新要等到下一个周期（最多 10 分钟）才被看见 ——
 * 而那正是"每次记录更新都调度一下"要避免的事。
 * 所以抢不到锁时置上 {@link #dirty}，正在跑的那一轮结束前会再检查一次它。
 *
 * <h2>这个锁保护的是"本进程内"，跨进程不成立</h2>
 * 多实例部署时两个进程各有各的锁，同一条任务会被两边各跑一遍。
 * 真正的互斥要靠数据库（乐观锁 / 状态条件更新）。这里没有做，因为当前的形态是
 * 单实例本地部署 —— 但这条边界要写出来：它的失效形态是<b>同一条任务的写操作被执行两遍</b>，
 * 而那属于"重复副作用"，不是"多花一次钱"。
 */
@Component
public class AgentTaskScheduler implements AgentTaskDispatch {

	private static final Logger log = LoggerFactory.getLogger(AgentTaskScheduler.class);

	private final AgentTaskExecution execution;

	private final AgentTaskProperties properties;

	/**
	 * 判定的上线参数（只为启动日志那一行）。
	 *
	 * <p>判定现在也是一次真正的 Agent 运行（会调工具、会跑好几步），所以"它最多能跑多少"
	 * 必须能一眼看到 —— 否则"一条判定失败"与"一条判定被上限拦下"在日志里长得一样。
	 */
	private final TaskReviewLimits reviewLimits;

	/**
	 * 跑任务用的执行器（容器里那个虚拟线程执行器）。
	 *
	 * <p>用它而不是自己 new 一个线程池：执行里包含<b>阻塞</b>的模型调用与工具调用，
	 * 而虚拟线程正是为这种"长时间挂着等"的活准备的（见 {@code VirtualThreadConfig}）。
	 * 显式写 {@code @Qualifier}：容器里将来多一个 {@code ExecutorService} 时，
	 * 按类型注入会变成启动期的一个歧义错误。
	 */
	private final ExecutorService worker;

	/** 执行锁。{@code tryLock} + {@code finally unlock}，见类注释。 */
	private final ReentrantLock lock = new ReentrantLock();

	/** 上一次执行期间有没有新的调度请求进来（有就再来一轮）。 */
	private final AtomicBoolean dirty = new AtomicBoolean(false);

	/** 是否已经有一个执行通道在排队或正在跑（避免每次记录更新都提交一个空跑的任务）。 */
	private final AtomicBoolean scheduled = new AtomicBoolean(false);

	public AgentTaskScheduler(AgentTaskExecution execution, AgentTaskProperties properties,
			TaskReviewLimits reviewLimits, @Qualifier("virtualThreadExecutor") ExecutorService worker) {
		this.execution = execution;
		this.properties = properties;
		this.reviewLimits = reviewLimits;
		this.worker = worker;
	}

	@PostConstruct
	void reportConfiguration() {
		// 这一行是"它到底装没装、按什么参数在跑"的唯一出处：定时任务不响的时候，
		// 第一个要回答的问题就是"它是关着的，还是跑了但没活干"
		log.info("agent 任务执行已装配：总开关 {}，定时触发间隔 {}，一轮最多 {} 条，"
				+ "任务点 ≥ {} 个要人确认计划，执行权限档位 {}，本应用在 Agent 应用注册表里的 id {}",
				properties.enabled(), properties.fixedDelay(), properties.maxTasksPerRound(),
				properties.planConfirmThreshold(), properties.level().describe(), properties.agentId());
		// 判定那一半的参数单独一行：它与执行不是同一套（判定只查不改、上限与循环同源），
		// 而"这一格卡住了是模型不听话还是撞上限"只能靠它所带的数字回答
		reviewLimits.report();
	}

	/**
	 * 定时触发（启动时一次，之后每 {@code ai.agent.task.fixed-delay} 一次）。
	 *
	 * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：后者按"上一次<b>开始</b>的时刻"排下一次，
	 * 于是执行时间比周期长时会连着触发、把两轮的间隔压没。前者按"上一次<b>结束</b>之后"排，
	 * 周期始终是"两次执行之间的空闲时间"，这也是"执行是串行的"这句承诺在调度层的形态。
	 */
	@Scheduled(fixedDelayString = "${ai.agent.task.fixed-delay}")
	public void tick() {
		dispatch();
	}

	@Override
	public void onTaskUpdated(AgentTaskStatus status) {
		// 已完成 / 已取消：不会再有下一步，不必为它们调度（这条判据只有这一处，见接口注释）
		if (status == null || status.terminal()) {
			return;
		}
		dirty.set(true);
		dispatch();
	}

	/**
	 * 提交一次执行。
	 *
	 * <p><b>它不阻塞</b>：抢锁与跑任务都在 worker 线程上做。调用方（HTTP 请求线程）到这里就结束。
	 * 已经有一个执行通道在排队或正在跑时直接返回 —— 那一个跑完会把这一趟的 {@link #dirty} 一起处理掉。
	 */
	private void dispatch() {
		if (!properties.enabled()) {
			log.debug("agent 任务执行已关闭（ai.agent.task.enabled=false），这次调度被跳过");
			return;
		}
		if (!scheduled.compareAndSet(false, true)) {
			return;
		}
		try {
			worker.execute(this::drain);
		}
		catch (RuntimeException e) {
			// 提交失败必须把标记复位，否则之后每一次调度都会被这一行挡掉 ——
			// 表现是"它再也不动了"，且不报错
			scheduled.set(false);
			throw e;
		}
	}

	/** 一个执行通道：抢锁 → 一轮一轮地跑（每轮开始时清 dirty，跑完还有新的就再来一轮）。 */
	private void drain() {
		try {
			if (!lock.tryLock()) {
				// 没抢到锁就返回：已经有人在跑了，而它会看到我们置上的 dirty
				log.debug("agent 任务执行：没抢到执行锁，这次返回（正在跑的那一轮会带上这次的任务）");
				return;
			}
			try {
				while (true) {
					dirty.set(false);
					runRound();
					if (!dirty.get()) {
						break;
					}
					log.info("agent 任务执行：这一轮期间又有任务变更，再跑一轮");
				}
			}
			finally {
				lock.unlock();
			}
		}
		finally {
			scheduled.set(false);
			// 关掉 scheduled 与"最后一轮检查 dirty"之间有一个窗口：这一段里进来的变更
			// 会看到 scheduled=true 而直接返回。所以这里再确认一次 —— 漏掉它的代价是
			// 那条任务要等到下一个定时周期（默认 10 分钟）
			if (dirty.get()) {
				dispatch();
			}
		}
	}

	/**
	 * 一轮：反复取"下一条待处理"并处理，直到没有、或撞上这一轮的上限。
	 *
	 * <h2>失败的那一条为什么这一轮不再取它</h2>
	 * 失败时本类<b>没有改那条任务</b>，所以它的变更时间也没变 ——
	 * 下一个查询还会取到它。不排除的话，一条老是失败的任务会把这一轮占满，
	 * 而后面那些本来能跑的任务一条都跑不到（"卡在队首"）。
	 * 排除的是"这一轮失败的"，不是"这一轮处理过的"：处理成功后它可能还停在
	 * "等 agent"的下一格（新发布 → 已确认需求），那一格本来就该在同一轮里接着走完。
	 */
	private void runRound() {
		Set<Long> failed = new HashSet<>();
		int processed = 0;
		while (true) {
			Optional<AgentTask> next = execution.nextPending(failed);
			if (next.isEmpty()) {
				return;
			}
			AgentTask task = next.get();
			if (processed >= properties.maxTasksPerRound()) {
				// 撞上限说明"处理完之后又变成待处理"这件事发生了不止一次。
				// 不静默截断：这一行日志就是"这一轮被谁吃满了"的答案
				log.warn("agent 任务执行：这一轮处理到上限 {} 条就停了，剩下的任务下一个周期继续"
						+ "（要一次处理更多就调大 ai.agent.task.max-tasks-per-round）", properties.maxTasksPerRound());
				return;
			}
			processed++;
			try {
				AgentTaskStatus reached = execution.execute(task.id());
				log.info("agent 任务执行：任务 #{} {} → {}", task.id(), task.status().label(), reached.label());
			}
			catch (RuntimeException e) {
				// 一条任务失败不该拖住别的任务，也不该让整轮崩掉：
				// 它留在原状态（执行器在失败前不做任何写入），下一个周期重试
				failed.add(task.id());
				log.warn("agent 任务执行：任务 #{} 这一步失败，它留在「{}」不动，这一轮不再取它：{}",
						task.id(), task.status().label(), e.toString());
				log.debug("任务 #{} 失败的完整堆栈", task.id(), e);
			}
		}
	}

}
