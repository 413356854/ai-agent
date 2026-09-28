package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;

import java.util.List;

/**
 * <b>任务判定</b> —— 两件"要读懂任务描述才能回答"的事，交给模型答，执行器只读结论。
 *
 * <h2>它与执行同源</h2>
 * 判定与执行走的是同一条链路（{@code ResumableAgentService} → 手写 ReAct 循环，
 * 见 {@link TaskReviewRunner}）：<b>同一套技能判据</b>
 * （执行者 → 他的角色 → 角色目录 → 那些目录下的技能）、<b>同一份工具目录</b>、
 * <b>同样留下运行痕迹</b>（{@code agent_run} / {@code agent_step} / {@code agent_tool_invocation}）。
 * 理由是判定要回答的问题本身就依赖这两样：要交付的东西没有任何技能与工具能覆盖，
 * 正是"需求没说清"的一种具体形态；而"这条任务要动哪个目录、里面有什么"也只能查出来。
 *
 * <p>两处刻意不同：判定<b>只查不改</b>（档位是仅可查看），
 * 而且判定<b>失败时不写任何业务状态</b>（见下）。
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
	 * @param task 判的是哪条任务：判定要用它的描述、它的 id（运行与会话 id）、
	 *            以及<b>它的任务人</b>（技能按那个人的角色目录筛 —— 与执行、
	 *            与授权判断是同一个人）
	 * @param history 这条任务已有的确认项与答复（可能为空；执行器只传已答复的那些）
	 */
	RequirementReview reviewRequirement(AgentTask task, List<AgentTaskConfirm> history);

	/**
	 * 把需求拆成<b>可以直接执行的小任务点</b>，并说清"计划、要授权什么、在哪个目录干活"。
	 *
	 * <p>三个附加字段各自有唯一的去处，所以放在同一个返回值里：
	 * {@code plan} 进计划确认项的正文（人要批的就是它）、
	 * {@code authorizationScope} 进授权确认项的正文、
	 * {@code workspaceDir} 决定这次运行的工作区，也决定判定自己那次运行的工作区
	 * （见 {@link TaskReviewRunner}，两者用的是同一个目录）。
	 * 分成三次模型调用的话，三份结论可能互相矛盾（计划里说改 A 目录、授权里说 B 目录）。
	 *
	 * @param task 判的是哪条任务（见 {@link #reviewRequirement}）
	 * @param history 这条任务已有的确认项与答复（可能为空）
	 */
	TaskBreakdown breakdown(AgentTask task, List<AgentTaskConfirm> history);

	/**
	 * 这一条链上判定的<b>有效上限</b>（与执行同源，取自 {@code ai.agent.loop.*}，不新增配置项）。
	 *
	 * <p>为什么要能被问一次：判定现在是一次真正的 Agent 运行（可能调工具、可能跑好几步），
	 * 于是"它最多能跑多少"必须有据可查 —— 启动日志里那一行与这里读的是同一个来源。
	 */
	Limits limits();

	/**
	 * 判定的上限（与 {@code AgentLoopProperties} 同源）。
	 *
	 * @param maxSteps 最多几轮"模型 → 工具"往返
	 * @param maxTokens 累计 token 上限
	 * @param maxDurationMs 墙上时间上限
	 */
	record Limits(int maxSteps, int maxTokens, long maxDurationMs) {
	}

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
