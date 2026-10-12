package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTaskSchedule;
import ai.agent.mapper.MapperRows;

import java.time.Instant;
import java.util.List;

/**
 * agent 定时任务的读写契约（应用管理区 · 定时任务）。
 *
 * <h2>这张表回答什么，不回答什么</h2>
 * 它记的是<b>一条规则</b>：什么时候派活（定时正则）、以谁的名义派（发布人）、派给谁（任务人）、
 * 派什么活（任务描述）。它<b>不</b>记"这次跑到哪一步了" —— 那是它派出去的那些
 * {@code agent_task} 的事。所以这一页上"跑得怎么样"要去 agent任务 那一页看。
 *
 * <h2>★ 到点做的事只有一件：生成一条 agent 任务记录</h2>
 * 规则到点调的是 {@link AgentTaskService#create(AgentTaskService.Draft)} ——
 * 与人在界面上点「发布任务」<b>同一个入口</b>，落下的任务只多一个由来
 * （{@code agent_task.schedule_id}）。它自己不调模型、不碰文件、不走 ReAct 循环：
 * 那些是任务执行器的事，由任务人自己的权限判（见 {@code TaskFileAuthorization}）。
 * 于是"定时派出来的活"在系统里与人发的活没有任何行为差异，也让"它到底干了什么"
 * 只需要去一个地方看。
 *
 * <h2>★ 改了就删线程重加，不停用也不复用</h2>
 * {@link #update} 的做法固定是"先 {@code unregister}、再 {@code register}"，
 * <b>即使新的正则与旧的逐字相同</b>。三条理由：
 * <ol>
 *   <li>复用要判断"新旧表达式是不是同一句"，而那个判断一旦有偏差，
 *       结果就是<b>改了没生效</b> —— 页面上新正则明明白白写着，跑的还是旧的；</li>
 *   <li>{@code ScheduledFuture.cancel(false)} 之后 {@code ScheduledThreadPoolExecutor} 的
 *       取消任务还留在队列里，直到它原定的触发时刻才被清掉。用得越久，
 *       "改过很多次的规则"会在池子里越积越多的僵尸节点（不影响正确性，只影响它什么时候被回收）；</li>
 *   <li>"删旧加新"这件事只有一条代码路径，于是它只有一种行为。带条件的复用有两条，
 *       而两条里错的那一条平时跑不到。</li>
 * </ol>
 * 代价说清：改一次表达式会丢掉旧线程上还没跑的那一次触发 ——
 * 而那正是"改了规则"该有的效果（改之前那一刻的意图已经被改掉了）。
 *
 * <h2>停用 = 那一格没有线程</h2>
 * {@link #setEnabled}(false) 会真的把线程从池子里摘掉，而不是"留着线程但到点不做事"。
 * 两者的差别在界面上看得见（线程数那一栏），而"留着不做事"的那种实现会让
 * "这条规则到底还占不占资源"变成一个查不清的问题。
 */
public interface AgentTaskScheduleService {

	/**
	 * 规则列表 + 调度线程池现在的样子。
	 *
	 * <p>线程池那一部分是刻意的：这一页的失败形态是"规则配好了、它就是不动"，
	 * 而那句话有两个完全不同的原因 —— <b>规则不在池子里</b>（停用 / 装的时候失败了）
	 * 与<b>在池子里但还没到点</b>。不把池子摆出来，这两件事在界面上长得一样。
	 */
	Snapshot snapshot();

	/**
	 * 一条规则现在长什么样（写动作的回执用它）。
	 *
	 * @throws {@code TaskInvalidException} 规则不存在时（400）
	 */
	RuleView rule(Long id);

	/** 取一条规则的实体；取不到抛 400。它给"要写的那一条"用（实体里有落库的那些列）。 */
	AgentTaskSchedule require(Long id);

	/**
	 * 一个账号的<b>登录名</b>（按用户 id）。
	 *
	 * <p>它是给界面回传用的：读侧给的那两个"人"的格子是<b>姓名</b>（显示名优先），
	 * 而写请求（{@link #update}）收的是**登录名** —— 两处不是同一个字符串，
	 * 拿姓名去提交的后果是"改规则永远失败"，且报的是"账号表里没有「李工」这个人"。
	 * <p>账号那一行不在（手工清过库）时返回空串：那一格空着，界面会挡住提交。
	 */
	String loginName(Long userId);

	/**
	 * 校验一句定时正则并算出它未来几次触发时刻 —— <b>只读，不写库、不装线程</b>。
	 *
	 * <p>界面上"页面配置化定时规则"那一块靠的就是它：用户每改一次配置，
	 * 界面把生成的那句 6 段表达式发过来，拿回去的是
	 * "这句正则的格式对不对 + 中文怎么说 + 接下来 5 次是什么时候"。
	 * 三条里最后一条是唯一能让人确认"我配的这句到底对不对"的证据
	 * —— 只看正则本身，`0 0 9 * * MON-FRI` 与 `0 0 9 * * *` 的区别要靠读的人自己推。
	 *
	 * <p>它<b>不落库</b>：预览是"还没决定要不要保存"的动作，写进去就会留下一条
	 * 用户并没有确认过的规则（而且它可能已经是可用状态，到点真的会派活）。
	 */
	Preview preview(String expression);

	/**
	 * 建一条规则，并把它装进调度线程池。
	 *
	 * <p>发布人 / 任务人都是<b>登录名</b>（与 {@link AgentTaskService#create} 同一套：
	 * 两人都必须是账号表里真实存在的用户，落库的是查出来的两个 id）。
	 *
	 * <p>{@code enabled} 为真时<b>当场把线程装上</b>，不等下一次启动 ——
	 * 否则"配好了却没反应"要等到有人重启进程才发现。
	 */
	RuleView create(String publisher, String assignee, String description, String cron, boolean enabled);

	/**
	 * 重新配一条规则：正则 / 任务人 / 描述 / 启停四列一起写，<b>并把线程删掉重加</b>。
	 *
	 * <p>发布人<b>不在可改项里</b>：它是"这条规则以谁的名义派活"，
	 * 改它等于换一个人来背这条规则 —— 而那件事该由"停用旧的、建一条新的"表达
	 * （两条规则的由来、历史任务、执行次数因此各自独立，不会混在一起）。
	 */
	RuleView update(Long id, String assignee, String description, String cron, boolean enabled);

	/** 停用 / 启用：停用会把线程从池子里摘掉，启用会装回去。 */
	RuleView setEnabled(Long id, boolean enabled);

	/**
	 * 手工触发一次：<b>立刻按这条规则派一条任务</b>（不改任何状态、不重排下一次触发）。
	 *
	 * <p>它存在是因为"这条规则到底会不会派活"在界面上没有别的确定性答案：
	 * 等下一次触发可能要等到明天，而"等到明天再看"意味着配错一个字要一天才发现。
	 * 停用的规则<b>也能手工触发</b>（那正是"先配好、确认一次再启用"的用法），
	 * 但界面上会写明这一步是按人点的、不是定时触发的。
	 */
	TriggerResult trigger(Long id);

	/**
	 * 删掉一条规则。
	 *
	 * <p>它<b>已经派过任务时拒绝</b>（400）：那些任务还留在 agent任务 那一页，
	 * 而这条规则是它们唯一的由来 —— 删掉之后"这些活是谁派的"就再也答不出来了，
	 * 而页面上看不出区别（任务还在）。要停就停用：停用同时把线程摘掉。
	 * 一条从没派过任务的规则可以删（它是配错的草稿）。
	 */
	void delete(Long id);

	/** 调度线程池现在的样子（块大小 / 在跑的线程 / 已取消但还没清掉的）。 */
	PoolStatus poolStatus();

	// ------------------------------------------------------------------
	//  调度线程池（安装 / 摘除）
	// ------------------------------------------------------------------

	/**
	 * 把一条规则装进调度线程池（覆盖式：同一个 id 先删旧的再加新的）。
	 *
	 * <p>它是"规则 <-> 线程"这件事的唯一入口：{@code create} / {@code update} /
	 * {@code setEnabled} 与启动时的安装都调它，于是"什么时候该有线程"只有一处判断。
	 * 实现必须<b>幂等</b>：同一个 id 连装两次的后果只能是"池子里还是那一条"。
	 */
	void register(AgentTaskSchedule rule);

	/** 把一条规则的线程从池子里摘掉（不在池子里时什么也不做）。 */
	void unregister(Long id);

	/**
	 * 规则列表 + 池子。
	 *
	 * @param rules 规则（最近变更在前）
	 * @param userId 这次按谁筛（当前登录的人）；不筛时为 {@code null}
	 * @param pool 调度线程池现在的样子
	 */
	record Snapshot(List<RuleView> rules, Long userId, PoolStatus pool) {
	}

	/**
	 * 一条规则 + 它的运行情况。
	 *
	 * @param rule 规则本身
	 * @param cronDescription 这句正则的<b>中文说法</b>（"每周 周一、周三 09:00:00 触发"）。
	 *        它<b>不落库</b>（是从 {@code cron} 算出来的展示文案，存一份就会多出第二真相），
	 *        也不是 {@link AgentTaskSchedule} 的一格 —— 由服务层算好放在这里给界面用。
	 *        界面不自己拼一份：与 {@code statusLabel} 同一条规矩
	 * @param runs 它派过几条任务（0 = 一条都没派过）
	 * @param lastRunAt 最后一次派任务的时间；{@code null} = 一次都没派过
	 * @param nextRuns 接下来几次触发时刻；停用的规则是空的
	 *        （空列表不是"算不出来"，而是"它现在不在池子里，没有下一次" ——
	 *        界面按 {@link AgentTaskSchedule#enabled()} 分开说这两件事）
	 */
	record RuleView(AgentTaskSchedule rule, String cronDescription, long runs, Instant lastRunAt,
			List<Instant> nextRuns) {
	}

	/**
	 * 一次正则预览。
	 *
	 * @param cron 归一之后的定时正则（6 段式）
	 * @param description 它的中文说法（界面直接显示这一份，不自己拼）
	 * @param nextRuns 未来 5 次触发时刻
	 */
	record Preview(String cron, String description, List<Instant> nextRuns) {
	}

	/**
	 * 一次手工触发的结果。
	 *
	 * @param scheduleId 哪条规则
	 * @param taskId 派出来的那条任务 id（真的落库了，不是"预演"）
	 * @param statusLabel 那条任务落在哪个状态（写动作的回执里那句"它现在在哪个格子里"）
	 */
	record TriggerResult(Long scheduleId, Long taskId, String statusLabel) {
	}

	/**
	 * 调度线程池的样子。
	 *
	 * @param poolSize 池子的线程数上限（配置项 {@code ai.agent.schedule.pool-size}）——
	 *        它同时是"同一瞬间最多几条规则能真的触发"这个上限
	 * @param activeThreads 正在跑触发动作的线程数
	 * @param poolSizeNow 池子当前的线程数（用了才建，所以它只涨不落到 0 以下）
	 * @param scheduled 现在池子里挂着几条规则的线程
	 * @param cancelledPending 已取消但还没被池子清掉的节点数
	 *        （{@code cancel} 之后它们会留到原定触发时刻才消失，见接口注释里那条代价）
	 */
	record PoolStatus(int poolSize, int activeThreads, int poolSizeNow, int scheduled, int cancelledPending) {
	}

}
