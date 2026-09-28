package ai.agent.service.task;

import ai.agent.config.AgentLoopProperties;
import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.TaskReviewPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link AgentTaskAnalyst} 的模型实现 —— 两次判定，每次都<b>跑成一次 Agent 运行</b>。
 *
 * <h2>为什么判定不是"一次裸模型调用"</h2>
 * 判定要回答的问题依赖两样只有运行链路才知道的东西：
 * <b>这次能用的技能</b>（任务人 → 他的角色 → 角色目录 → 那些目录下的技能）与
 * <b>这次能用的工具</b>（注册表里的全部工具，含文件类）。所以判定与执行同源：
 * 同一个 {@link TaskReviewRunner}、同一条手写循环、同一份技能判据。
 * 于是"要交付的东西没有任何技能覆盖"这类判断才有据可依，而不是靠模型凭印象猜。
 *
 * <h2>本类现在只剩两件事</h2>
 * 拼出这一次判定的输入（描述 + 已答复的确认项 + 输出契约）与读结论。
 * 模型怎么调、技能与工具怎么装配、失败怎么翻译，都在 {@link TaskReviewRunner} 与
 * {@code AgentLoop} 里 —— 那些是两条判定共用的，写两遍必然漂移。
 *
 * <h2>提示词在哪</h2>
 * 系统那一段受版本库管理（{@code PromptKind.TASK_REVIEW}，出厂正文在
 * {@code prompts/task-review.st}），用户那一段由 {@link TaskReviewPrompt} 按这次输入拼出来。
 * 两者都不进这个类：提示词里的字段与 {@code RequirementReview} / {@code TaskBreakdown} 是
 * 同一个东西（schema 由 {@link BeanOutputConverter} 从它们生成），
 * 抄进 Java 字符串之后就会变成两处维护。
 *
 * <h2>JSON 的纪律</h2>
 * 响应用 {@code json_object} 这条协议开关<b>已经不在本类上了</b>：判定走的是
 * {@code AgentLoop} + {@code ModelTurn}，而那条接口上没有 response-format 的入口。
 * 格式因此靠提示词（「最后只输出一个 JSON 对象、不要围栏」）+ 应用侧的解析兜住 ——
 * 解析不出来就是"这次没有结论"，任务停在原状态等下一个周期（见 {@link AgentTaskAnalyst}）。
 * <b>刻意不加"校验失败就回灌"的循环</b>：判定的失败有更好的去处，
 * 而回灌会在这里连烧三轮 token 去救一次"这条描述本来就读不懂"的判定。
 */
@Component
public class ModelAgentTaskAnalyst implements AgentTaskAnalyst {

	private static final Logger log = LoggerFactory.getLogger(ModelAgentTaskAnalyst.class);

	/** 判定的名字（日志与异常里用）。写成常量是因为两条判定各有一处，日志与断言要认它。 */
	static final String WHAT_REQUIREMENT = "需求歧义";

	static final String WHAT_BREAKDOWN = "任务拆分";

	private final TaskReviewRunner runner;

	private final AgentLoopProperties loopProperties;

	private final BeanOutputConverter<RequirementReview> reviewConverter =
			new BeanOutputConverter<>(RequirementReview.class);

	private final BeanOutputConverter<TaskBreakdown> breakdownConverter =
			new BeanOutputConverter<>(TaskBreakdown.class);

	public ModelAgentTaskAnalyst(TaskReviewRunner runner, AgentLoopProperties loopProperties) {
		this.runner = runner;
		this.loopProperties = loopProperties;
	}

	@Override
	public RequirementReview reviewRequirement(AgentTask task, List<AgentTaskConfirm> history) {
		RequirementReview review = runner.judge(task,
				TaskReviewPrompt.reviewInstruction(task.description(), history),
				reviewConverter, WHAT_REQUIREMENT);
		log.info("需求歧义判定：ambiguous={} question={}", review.ambiguous(),
				review.question() == null || review.question().isBlank() ? "(无)" : review.question());
		return review;
	}

	@Override
	public TaskBreakdown breakdown(AgentTask task, List<AgentTaskConfirm> history) {
		TaskBreakdown breakdown = runner.judge(task,
				TaskReviewPrompt.breakdownInstruction(task.description(), history),
				breakdownConverter, WHAT_BREAKDOWN);
		log.info("任务拆分结果：{} 个任务点，工作区 {}", breakdown.actions() == null ? 0 : breakdown.actions().size(),
				breakdown.workspaceDir() == null || breakdown.workspaceDir().isBlank() ? "(不需要)"
						: breakdown.workspaceDir());
		return breakdown;
	}

	@Override
	public Limits limits() {
		return new Limits(loopProperties.maxSteps(), loopProperties.maxTokens(), loopProperties.maxDurationMs());
	}

}
