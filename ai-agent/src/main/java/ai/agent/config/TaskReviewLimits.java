package ai.agent.config;

import ai.agent.service.prompt.PromptKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>agent 任务判定的上线参数</b> —— 判定现在是一次真正的 Agent 运行，所以它也有上限。
 *
 * <h2>为什么不新增配置项，而是与执行同源</h2>
 * 判定与执行是同一类东西（都是"问模型、可能要调工具、有停止条件"的运行），
 * 它们的上限该由同一组数字表达：{@code ai.agent.loop.max-steps} / {@code max-tokens} /
 * {@code max-duration-ms}。另开一组 {@code ai.agent.task.review-*} 会立刻产生一个说不清的问题 ——
 * "调大执行的上限时，判定要不要跟着调？" 而答案只能是"要，但没人会记得"。
 *
 * <h2>它的唯一产物是一行启动日志</h2>
 * 判定在库里、在 trace 里、在成本看板里都已经看得见，唯一看不见的是"它最多能跑多少" ——
 * 而这一格决定了"一条判定失败是模型不听话，还是被上限拦下了"。所以这一行与被拦下时
 * 那条异常消息里的数字必须来自同一处（{@link AgentLoopProperties}），而不是各抄一份。
 *
 * <p>它同时是"判定这一条链路装上了"的凭据：与本项目其它几条链路的启动日志同一个用途
 * （见 {@code AgentTaskScheduler#reportConfiguration}）。
 */
@Component
public class TaskReviewLimits {

	private static final Logger log = LoggerFactory.getLogger(TaskReviewLimits.class);

	private final AgentLoopProperties loop;

	public TaskReviewLimits(AgentLoopProperties loop) {
		this.loop = loop;
	}

	/** 判定的上限（与 Agent 循环同源）。 */
	public int maxSteps() {
		return loop.maxSteps();
	}

	/** 启动时打一行：判定按什么参数在跑。 */
	public void report() {
		log.info("agent 任务判定已装配：最多 {} 步 / {} tokens / {}ms（与 Agent 循环同源，取自 ai.agent.loop.*）；"
				+ "提示词用 {}，技能按任务人的角色目录筛，档位仅可查看（判定只查不改）",
				loop.maxSteps(), loop.maxTokens(), loop.maxDurationMs(),
				PromptKind.TASK_REVIEW.defaultValue());
	}

}
