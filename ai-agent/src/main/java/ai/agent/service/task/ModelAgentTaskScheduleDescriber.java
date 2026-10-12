package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.TaskSchedulePrompt;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;

/**
 * {@link AgentTaskScheduleDescriber} 的模型实现 —— <b>一次裸模型调用</b>。
 *
 * <h2>为什么不是"跑成一次 Agent 运行"</h2>
 * 与 {@code TaskRuleSummary} 同一条判断：判定要走循环是因为它要<b>查</b>
 * （这条任务要动哪个目录、有没有技能覆盖），而这一件事什么都不查 ——
 * 材料全在手上（一句描述 + 一张算好的时间表），产物是一句话。
 * 为它套一层循环的代价是多一次工具与技能目录装配、多一条 {@code agent_run}，
 * 收益是零。所以它与 {@code TaskRuleSummary} / {@code StructuredChatServiceImpl} 同一形态。
 *
 * <h2>★ 用它自己的 {@code ChatClient}，不用容器里那个 bean</h2>
 * 容器里那个挂着 {@code MessageChatMemoryAdvisor}（按 conversationId 读写会话记忆）。
 * 这一件事要的是**无状态改写**（同一句描述 + 同一天，任何时候都该给同一个结果），
 * 而"写进某个会话历史"是连带的副作用 —— 谁会去看那个会话？没人。
 * 所以注入<b>自动配置的 {@code ChatClient.Builder}（prototype）</b>自己 build 一个局部实例。
 *
 * <h2>温度 0、带上限，失败一律抛</h2>
 * 温度 0：这是改写，不是创作 —— 同一句描述在同一天应当给同一个结果。
 * {@code maxTokens} 给一个明确的上界：产物是一句话，而默认输出上限（配置里的
 * {@code ai.chat.budget.max-output-tokens}）比它大一个数量级，白占额度。
 * 调用失败 / 输出为空 / 超长一律抛 {@link TaskScheduleResolveException}，
 * 由调用方决定"这次不派活"（见那个异常与 {@link AgentTaskScheduleDescriber} 的注释）。
 *
 * <h2>系统提示词受版本管理</h2>
 * 它读的是 {@link PromptKind#TASK_SCHEDULE} 当前生效的那一版（每次解析前读一次），
 * 出厂正文在 {@code prompts/task-schedule.st} —— 与 {@code AgentLoop} 的读法一样，
 * 所以换一版对**下一次触发**生效，不需要重启。
 */
@Component
public class ModelAgentTaskScheduleDescriber implements AgentTaskScheduleDescriber {

	private static final Logger log = LoggerFactory.getLogger(ModelAgentTaskScheduleDescriber.class);

	/** 改写类任务的温度：0 = 同一份输入尽量给同一个结果。 */
	private static final double TEMPERATURE = 0.0;

	/**
	 * 输出的 token 上界。
	 *
	 * <p>产物是一句任务描述（最长 {@link AgentTask#MAX_DESCRIPTION_CHARS} 字符 ≈ 1500 token），
	 * 给 1024 足够；它同时是一道闸：模型跑题写成一篇长文时会被截断，
	 * 而截断后的文本会被下面那条长度判据拦下（不至于把半篇解释写进任务描述）。
	 */
	private static final int MAX_TOKENS = 1024;

	/** 输出超过任务描述的上限就不采用 —— 判据与实体那一份是同一个常量。 */
	private static final int MAX_CHARS = AgentTask.MAX_DESCRIPTION_CHARS;

	private final ChatClient chatClient;

	private final PromptService prompts;

	public ModelAgentTaskScheduleDescriber(ChatClient.Builder builder, PromptService prompts) {
		// 刻意不挂 advisor、不设 defaultSystem：系统提示词在调用点按当前生效版本给（见类注释）
		this.chatClient = builder.build();
		this.prompts = prompts;
	}

	@Override
	public Resolved resolve(Long scheduleId, String template, Instant at, ZoneId zone) {
		String system = prompts.currentContent(PromptKind.TASK_SCHEDULE);
		String raw;
		try {
			raw = chatClient.prompt()
					.system(system)
					.user(TaskSchedulePrompt.instruction(template, at, zone))
					.options(OpenAiChatOptions.builder().temperature(TEMPERATURE).maxTokens(MAX_TOKENS))
					.call()
					.content();
		}
		catch (RuntimeException e) {
			// 包装成"这一次没解析出来"：上游的原始异常仍然带着（cause），
			// 而这一条链路的失败语义是"不派活、下个周期重试"，不是"服务器出错了"
			throw new TaskScheduleResolveException(("定时规则 #%d 的描述模板没解析出来（模型调用失败）：%s —— "
					+ "这一次不派活，它下一个周期会重试。要立刻确认可以把这条规则停用后手工派一次。")
					.formatted(scheduleId, root(e)), e);
		}
		String description = cleanup(raw);
		if (description == null || description.isBlank()) {
			throw new TaskScheduleResolveException(("定时规则 #%d 的描述模板解析不出可用的内容（模型返回空）："
					+ "原始输出 %s —— 这一次不派活，它下一个周期会重试。")
					.formatted(scheduleId, raw == null ? "(null)" : "「" + oneLine(raw) + "」"));
		}
		if (description.length() > MAX_CHARS) {
			throw new TaskScheduleResolveException(("定时规则 #%d 的描述模板解析之后过长（%d 字符 > 上限 %d）—— "
					+ "这一次不派活。多半是模型没按「只输出那句描述」来写；"
					+ "把模板写短一些，或者去 Prompt 版本那一页看一眼 task-schedule 那一份的正文。")
					.formatted(scheduleId, description.length(), MAX_CHARS));
		}
		boolean changed = !description.equals(template.strip());
		log.info("定时规则 #{} 的描述已解析：{}（{} 字符）{}", scheduleId, changed ? "已改写" : "原样返回",
				description.length(), changed ? "" : " —— 模板里没有可解析的时间说法，或模型没认出来");
		if (!changed) {
			// 「原样返回」是排查"为什么派出来的任务还写着昨天"的第一个读数：
			// 它说明解析**跑了**、只是没改动 —— 与"解析压根没跑"是两件事
			log.debug("定时规则 #{} 的模板与解析结果逐字相同：{}", scheduleId, oneLine(template));
		}
		return new Resolved(description, changed);
	}

	/**
	 * 把模型的原始输出收拾成"可以直接落库的那一句"。
	 *
	 * <p>两件事，都是**纯格式**的，不猜内容：
	 * <ol>
	 *   <li>去掉整段外围的空白；</li>
	 *   <li>剥掉整体包裹的 markdown 围栏（``` 或 ```text）—— 提示词里明确说了不要，
	 *       但它出现的概率很高，而围栏留在描述里会让那条任务读起来像坏掉了。</li>
	 * </ol>
	 * ⚠️ 与 {@code TaskRuleSummary} 那条"不抠围栏"的纪律不冲突：那条说的是判定结果
	 * <b>不要去猜哪一段才是答案</b>（猜错的形态是得到一个看起来正确的结论，而它决定动什么文件）；
	 * 这里不猜内容，只把"整段被围栏包着"这一个语法事实修掉，里面的字一个不改。
	 *
	 * <p>剥掉之后如果还带着围栏的行（模型把解释也写了进去），**不修**、原样返回 ——
	 * 那种输出该被长度判据或人工发现拦下，而不是在这一层被剪成一句看着正常的话。
	 */
	private static String cleanup(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.strip();
		if (text.startsWith("```") && text.endsWith("```") && text.length() > 6) {
			int firstBreak = text.indexOf('\n');
			if (firstBreak > 0) {
				String body = text.substring(firstBreak + 1, text.length() - 3).strip();
				log.warn("描述解析的输出被 markdown 围栏整体包着，已剥掉围栏（提示词里明确要求不要围栏）");
				return body;
			}
		}
		return text;
	}

	/** 日志里的一行（换行会把它拆成多行，反而不好搜）。 */
	private static String oneLine(String text) {
		String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
		return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
	}

	/** 剥到最内层的异常消息，避免把整条 SDK 包装链写进给用户看的那句话里。 */
	private static String root(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

}
