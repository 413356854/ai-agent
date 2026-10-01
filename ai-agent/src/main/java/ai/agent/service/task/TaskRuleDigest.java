package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.RuleSummary;
import ai.agent.service.skill.SkillRuleSupplement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * <b>验收通过之后那一步：把这条任务的经验汇成规则、补进技能</b>。
 *
 * <h2>它挂在什么时刻上：验收事务提交之后</h2>
 * {@link #digestAfterCommit} 走 {@code TransactionSynchronization#afterCommit} ——
 * 与 {@code DefaultAgentTaskService#dispatchAfterCommit} 是同一条理由：
 * 这次补充<b>读的是刚落库的那一份状态与确认项</b>，而它就发生在同一个请求里。
 * 就地在事务里做的话，那一刻这一行还没提交，另一个线程按 id 查它读到的还是旧状态。
 *
 * <h2>★ 它不在 HTTP 线程上跑</h2>
 * 那个 {@code afterCommit} 回调仍然在请求线程上，而这里要调一次模型（秒级）。
 * 所以整个动作被丢进虚拟线程执行器：用户点一下「任务已完成」不该等一场模型往返，
 * 而这个动作的产物（技能正文）本来就不在这次响应里 —— 它在 skill 管理那一页看。
 *
 * <h2>★ 失败一律吞掉，绝不向调用方抛</h2>
 * 走到这里时验收<b>已经完成并提交</b>了。抛出去只会让用户以为验收失败了（而他刷新一下
 * 会看到任务确实是已完成），或者让一次辅助动作把已经成功的那件事说成出错。
 * 所以这里捕获一切并记日志，日志要能回答"这次为什么没补充"：
 * 没有确认项 / 模型说没有可归纳的 / 模型调用或解析失败 / 任务人没有可用技能 /
 * 正文刚被别处改过 —— 各一句不同的话。
 *
 * <h2>为什么它不落 {@code agent_run}</h2>
 * 那条链路记的是"一次 Agent 运行"（步数、工具调用、token、trace）。这一件事没有工具、
 * 没有循环、没有几步可数，套一层 {@code AgentLoop} 只为留下一条运行记录并不划算。
 * 代价是成本看板里看不到它那一次模型的费用 —— 这是刻意的取舍，写在这里以免后来的人
 * 以为"漏记了"：真要看这笔钱，把 {@code logging.level.org.springframework.ai.chat.client.advisor}
 * 调成 DEBUG 看请求与用量，或去模型服务商的用量页对。
 */
@Component
public class TaskRuleDigest {

	private static final Logger log = LoggerFactory.getLogger(TaskRuleDigest.class);

	private final TaskRuleSummary summaries;

	private final SkillRuleSupplement supplements;

	private final ExecutorService worker;

	public TaskRuleDigest(TaskRuleSummary summaries, SkillRuleSupplement supplements,
			@Qualifier("virtualThreadExecutor") ExecutorService worker) {
		this.summaries = summaries;
		this.supplements = supplements;
		this.worker = worker;
	}

	/**
	 * 这条任务验收通过了：把它的确认项汇成规则补进技能。
	 *
	 * @param task 刚被验收的那条任务（<b>提交之前</b>读出来的那一份：它的状态还是待验收，
	 *        但这不影响这一件事 —— 用的只有 id、任务人与描述）
	 * @param confirms 这条任务的全部确认项（调用方在同一个事务里读出来交进来，
	 *        省掉另一个线程再查一次）
	 */
	public void digestAfterCommit(AgentTask task, List<AgentTaskConfirm> confirms) {
		List<AgentTaskConfirm> answered = confirms == null ? List.of()
				: confirms.stream().filter(AgentTaskConfirm::answered).toList();
		if (answered.isEmpty()) {
			// 最常见的一种：需求一开始就没有歧义、任务点也少于阈值，于是链上一条确认项都没生成。
			// 那时"下次别再问"这件事本来就没有材料，连模型都不必调
			log.info("任务 #{} 验收通过，但它一条已答复的确认项都没有 —— 没有可归纳的内容，不补充技能", task.id());
			return;
		}
		Runnable work = () -> run(task, answered);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			// 事务之外调（测试、将来别的入口）：没有"未提交"这回事，直接交给执行器
			submit(task, work);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				submit(task, work);
			}
		});
	}

	/** 提交失败（执行器关掉了）只记一条 WARN：验收已经成功，不该因为这件事出错。 */
	private void submit(AgentTask task, Runnable work) {
		try {
			worker.execute(work);
		}
		catch (RuntimeException e) {
			log.warn("任务 #{} 的经验补充没能提交到执行器（验收已完成，不受影响）：{}", task.id(), e.toString());
		}
	}

	/**
	 * 真正的那一步：一次模型调用 + 一次技能写入。
	 *
	 * <p>两处失败分开记：模型那一半失败（调不通 / 解析不出来）与写入那一半失败
	 * （没有可用技能 / 正文被别处改过）在排查时是两件事，
	 * 而合成一句"补充失败"之后，看日志的人得先去猜是哪一半。
	 */
	private void run(AgentTask task, List<AgentTaskConfirm> answered) {
		try {
			RuleSummary summary = summaries.summarize(task, answered);
			if (!summary.usable() || summary.rules() == null || summary.rules().isEmpty()) {
				log.info("任务 #{} 的经验归纳：模型认为没有可归纳的规矩，不写技能", task.id());
				return;
			}
			supplements.append(task, summary.rules());
		}
		catch (RuntimeException e) {
			log.warn("任务 #{} 的经验补充失败（验收已完成，不受影响）：{}", task.id(), e.toString());
			log.debug("任务 #{} 经验补充失败的完整堆栈", task.id(), e);
		}
	}

}
