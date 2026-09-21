package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskStatus;

import java.util.Optional;
import java.util.Set;

/**
 * <b>agent 任务执行</b> —— 按状态把一条任务往前推一格（球在 agent 手上的那几格）。
 *
 * <h2>它负责的四格，以及每格做什么</h2>
 * <table border="1">
 *   <caption>状态 → 这一步做什么 → 推到哪</caption>
 *   <tr><th>状态</th><th>做什么</th><th>之后到哪</th></tr>
 *   <tr><td>新发布</td><td>判断需求描述有没有歧义</td>
 *       <td>有 → 待确认需求（生成需求确认项）；没有 → 已确认需求</td></tr>
 *   <tr><td>已确认需求</td><td>再判一次歧义；没有歧义就拆任务点</td>
 *       <td>仍有歧义 → 待确认需求（再生成一条需求确认项）；
 *           任务点 ≥ 阈值 → 待确认计划；否则 → 待授权</td></tr>
 *   <tr><td>已确认计划</td><td>判断授权范围</td><td>待授权（生成授权确认项）</td></tr>
 *   <tr><td>已授权</td><td>跑一次 Agent 运行，汇总改动与产出</td>
 *       <td>成功 → 待验收（写执行结果）；没跑成 → 留在执行中，等人处置</td></tr>
 * </table>
 *
 * <h2>它<b>不</b>决定"什么时候跑"</h2>
 * 调度（锁、周期、串行、每次记录更新后触发）在 {@code AgentTaskScheduler}，
 * 本接口只回答"这一条现在该做什么"。分开的理由是两者的失败形态完全不同：
 * 调度的失败是"没跑"（要重试、要防重入），执行的失败是"跑错了"（要停住、不要瞎猜）。
 * 揉在一起之后，"这一轮为什么没动"会变成一句说不清的话。
 *
 * <h2>为什么这里的方法不抛异常给调用方处理状态</h2>
 * {@link #execute} 允许抛（判定失败、存储失败、运行没跑成），
 * 而调度器对它的处置是统一的：<b>记一条日志、任务留在原状态、这一轮跳过它</b>。
 * 所以"停住"这件事只在调度器里写一遍 —— 每个分支各自决定"失败时退到哪"的话，
 * 迟早会出现一条"失败之后还被推到了下一步"的路。
 */
public interface AgentTaskExecution {

	/**
	 * 下一条该由 agent 处理的任务（按变更时间从早到晚，一次一条）。
	 *
	 * <p>取哪几条状态由 {@link AgentTaskStatus#awaitingAgentStatuses()} 给出 ——
	 * 不在这里列清单，理由见那个方法。
	 *
	 * @param excluded 这一轮<b>已经失败过</b>、不要再取的任务 id。
	 *            失败的判定不会改任务的变更时间，所以下一个查询还会取到同一条 ——
	 *            不排除的话，一条老是失败的任务会把这一轮占满，后面的任务一条都跑不到。
	 *            传空集合表示不排除任何任务（这是常态）
	 */
	Optional<AgentTask> nextPending(Set<Long> excluded);

	/**
	 * 处理一条任务的一步。
	 *
	 * @param taskId 任务 id；调用方通常直接用 {@link #nextPending()} 取到的那一条
	 * @return 这一步之后它落在哪个状态（用来记日志与断言；没动时返回原状态）
	 */
	AgentTaskStatus execute(Long taskId);

}
