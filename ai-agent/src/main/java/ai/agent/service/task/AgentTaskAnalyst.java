package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTaskConfirm;

import java.util.List;

/**
 * <b>任务判定</b> —— 两件"要读懂任务描述才能回答"的事，交给模型答，执行器只读结论。
 *
 * <h2>为什么判定与执行分成两个接口</h2>
 * 两者的产物完全不同：判定产出的是<b>要不要问人、问什么</b>（一句话 + 一个布尔），
 * 执行产出的是<b>一次运行</b>（改了什么、花了多少 token）。合成一个接口之后，
 * "换一个判定模型做对照"会连带把执行也换掉 —— 而那两件事的实验目的不一样。
 *
 * <h2>为什么判定失败要抛异常，而不是给一个"默认结论"</h2>
 * 两个方向的默认值都有人会选，而两个都是错的：
 * <ul>
 *   <li>默认"无歧义" → 直接拆任务点往下走，把一个没读懂的需求交给执行；</li>
 *   <li>默认"有歧义" → 拿一个编出来的问题去问用户，而他答的是一个不存在的问题。</li>
 * </ul>
 * 抛出去之后由执行器与调度器处理：<b>任务停在原状态不动</b>，
 * 下一次调度（默认 10 分钟后）再判一次。宁可停着等下一轮，也不按猜出来的结论往下走 ——
 * 后者会留下一条"看起来走完了"的错记录，而错记录比停住难发现得多。
 */
public interface AgentTaskAnalyst {

	/**
	 * 判断需求描述<b>还有没有歧义</b>。
	 *
	 * <p>调用点有两个：新任务（第一次判）与已确认需求（拿到用户答复之后再判一次 ——
	 * 答复本身可能带来新的信息，也可能仍然没把问题说清）。
	 *
	 * @param description 任务描述原文
	 * @param history 这条任务已有的确认项与答复（可能为空；执行器只传已答复的那些）
	 */
	RequirementReview reviewRequirement(String description, List<AgentTaskConfirm> history);

	/**
	 * 把需求拆成<b>可以直接执行的小任务点</b>，并说清"计划、要授权什么、在哪个目录干活"。
	 *
	 * <p>三个附加字段各自有唯一的去处，所以放在同一个返回值里：
	 * {@code plan} 进计划确认项的正文（人要批的就是它）、
	 * {@code authorizationScope} 进授权确认项的正文、
	 * {@code workspaceDir} 决定这次运行的工作区（见 {@code DefaultAgentTaskExecution#run}）。
	 * 分成三次模型调用的话，三份结论可能互相矛盾（计划里说改 A 目录、授权里说 B 目录）。
	 *
	 * @param description 任务描述原文
	 * @param history 这条任务已有的确认项与答复（可能为空）
	 */
	TaskBreakdown breakdown(String description, List<AgentTaskConfirm> history);

	/**
	 * 需求歧义的判定结果。
	 *
	 * @param ambiguous 有歧义（要问用户一轮）
	 * @param question 要问用户的那<b>一个</b>问题（无歧义时给空串）。
	 *            <p>一次只问一件最要紧的事：问题可以连着问（答完再判、再问），
	 *            而一次抛五个问题会让人答成一段没有结构的话，反而更难判。
	 */
	record RequirementReview(boolean ambiguous, String question) {
	}

	/**
	 * 任务点的拆分结果。
	 *
	 * @param actions 可以直接执行的小任务点（每点一句话、动词开头、做完能判断）
	 * @param plan 给用户看的执行计划（进计划确认项的正文）
	 * @param authorizationScope 这次执行要用户授权的范围（进授权确认项的正文）
	 * @param workspaceDir 这次执行在哪个目录下干活；<b>空串表示这条任务不需要目录</b>
	 *            （例如只回答问题、或执行一段不依赖文件的命令）
	 */
	record TaskBreakdown(List<String> actions, String plan, String authorizationScope, String workspaceDir) {
	}

}
