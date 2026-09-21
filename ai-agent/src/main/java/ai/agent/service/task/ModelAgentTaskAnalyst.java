package ai.agent.service.task;

import ai.agent.config.ChatCallProperties;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link AgentTaskAnalyst} 的模型实现 —— 用一次结构化输出答一个问题。
 *
 * <h2>为什么这里自己建一个 ChatClient（而不是注入那个共享的）</h2>
 * 共享的那个挂了对话记忆（{@code MessageChatMemoryAdvisor}），而记忆是<b>按会话累积</b>的：
 * 判定如果共用一个会话 id，第二条任务的提示词里会带上第一条任务的判定过程与结论 ——
 * 而"这一条需求有没有歧义"是一个只看本条描述就能回答的问题，带上别的任务的内容只会让它跑偏。
 * 给每次判定编一个新会话 id 则是另一个方向的坏法：记忆只增不减（见
 * {@code ChatClientConfig#chatMemoryRepository} 里那三条后果）。
 * 所以这里注入 {@code ChatClient.Builder}（prototype，每次注入拿到新实例）自己建一个<b>不挂记忆</b>的。
 *
 * <h2>JSON 的纪律沿用结构化输出那一套，但不做回灌重试</h2>
 * 响应格式用 {@code json_object}（DeepSeek 只支持这一档，见
 * {@code StructuredChatServiceImpl} 的类注释），schema 由 {@link BeanOutputConverter}
 * 从 record 生成后写进提示词 —— 与那边逐条相同。
 * <b>刻意不加"校验失败就回灌"的循环</b>：判定的失败有更好的去处 ——
 * 任务停在原状态、下一个调度周期（默认 10 分钟）重判一次，
 * 而回灌会在这里连烧三轮 token 去救一次"这条描述本来就读不懂"的判定。
 *
 * <h2>提示词写在类里，不是随版本库管理的那两份</h2>
 * 判定的提示词与它要求的那份 JSON 契约是<b>同一个东西</b>：
 * schema 由下面两个 record 生成，提示词里说的字段就是它们。
 * 把提示词搬到 classpath 文件之后，"提示词里写的字段"与"代码解析的字段"会变成两处维护，
 * 而它们不一致时的现象是"模型照提示词输出、解析却拿不到值"——
 * 一个只在运行期出现、且看起来像模型不听话的错。
 */
@Component
public class ModelAgentTaskAnalyst implements AgentTaskAnalyst {

	private static final Logger log = LoggerFactory.getLogger(ModelAgentTaskAnalyst.class);

	private static final String SYSTEM = """
			你是一个任务需求分析器：判断"派给 Agent 的任务"的需求描述够不够清楚，或把它拆成可以直接执行的小任务点。
			只输出一个 JSON 对象，不要道歉、不要解释、不要用 markdown 代码块包裹。""";

	/** 判定不是创作，温度取 0（与结构化输出同一条理由：要可重复）。 */
	private static final double TEMPERATURE = 0.0;

	private final ChatClient chatClient;

	private final ChatCallProperties properties;

	private final BeanOutputConverter<RequirementReview> reviewConverter =
			new BeanOutputConverter<>(RequirementReview.class);

	private final BeanOutputConverter<TaskBreakdown> breakdownConverter =
			new BeanOutputConverter<>(TaskBreakdown.class);

	public ModelAgentTaskAnalyst(ChatClient.Builder builder, ChatCallProperties properties) {
		// 不调用 defaultSystem / defaultAdvisors：这个客户端的每一次调用都自带完整的提示词，
		// 而且**不能**带对话记忆（理由见类注释）
		this.chatClient = builder.build();
		this.properties = properties;
	}

	@Override
	public RequirementReview reviewRequirement(String description, List<AgentTaskConfirm> history) {
		String raw = call("""
				判断下面这条任务描述**有没有歧义**。

				判断标准（逐条看，有一条读不出来就算有歧义）：
				1. 要交付什么 —— 做出来的东西是什么；
				2. 在什么范围里做 —— 哪些文件、哪些数据、哪个系统；
				3. 怎么算做完 —— 验收标准。

				任务描述：
				%s

				已经问过并答复过的内容（可能为空）：
				%s

				只输出 JSON，字段含义：
				- ambiguous：true = 还有歧义；
				- question：有歧义时**只问一个**最要紧的问题（无歧义时给空字符串）。

				JSON Schema：
				%s
				""".formatted(description, renderHistory(history), reviewConverter.getJsonSchema()));
		RequirementReview review = parse(reviewConverter, raw, "需求歧义");
		log.info("需求歧义判定：ambiguous={} question={}", review.ambiguous(),
				review.question() == null || review.question().isBlank() ? "(无)" : review.question());
		return review;
	}

	@Override
	public TaskBreakdown breakdown(String description, List<AgentTaskConfirm> history) {
		String raw = call("""
				把下面这条任务拆成**可以直接执行的小任务点**。

				任务描述：
				%s

				已经问过并答复过的内容（可能为空）：
				%s

				要求：
				- actions：每个任务点一句话、动词开头、做完能判断（不要写"分析一下"这种没有完成标志的点）；
				- plan：给用户看的执行计划（先做什么、后做什么）；
				- authorizationScope：这次执行要用户授权做什么（改哪些文件、要不要跑命令）；
				- workspaceDir：这次执行在哪个目录下干活（绝对路径）。**不需要目录就给空字符串** ——
				  例如只回答问题、或只跑一段不依赖文件的命令。

				JSON Schema：
				%s
				""".formatted(description, renderHistory(history), breakdownConverter.getJsonSchema()));
		TaskBreakdown breakdown = parse(breakdownConverter, raw, "任务拆分");
		log.info("任务拆分结果：{} 个任务点，工作区 {}", breakdown.actions() == null ? 0 : breakdown.actions().size(),
				breakdown.workspaceDir() == null || breakdown.workspaceDir().isBlank() ? "(不需要)" : breakdown.workspaceDir());
		return breakdown;
	}

	/** 模型调用。响应格式按 {@code ai.chat.structured.json-mode} 决定（与结构化输出共用一个开关）。 */
	private String call(String prompt) {
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().temperature(TEMPERATURE);
		if (properties.getStructured().isJsonMode()) {
			options.responseFormat(OpenAiChatModel.ResponseFormat.builder()
					.type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
					.build());
		}
		return chatClient.prompt().system(SYSTEM).user(prompt).options(options).call().content();
	}

	/**
	 * 解析成 record。
	 *
	 * <p>失败抛出去（不是返回一个空结论）：解析不出来意味着"这次判定没有结论"，
	 * 而调用方对此的处置是明确的 —— 任务留在原状态、下一个周期重判（见接口注释）。
	 * 异常消息里带上模型的原始输出（截断）：不然"解析失败"这四个字无法回答
	 * "它到底输出了什么"，而排查这类问题只能靠那一句原文。
	 */
	private static <T> T parse(BeanOutputConverter<T> converter, String raw, String what) {
		try {
			return converter.convert(raw);
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("判定「%s」失败：模型输出解析不出目标结构（%s）。模型原始输出：%s"
					.formatted(what, rootMessage(e), truncate(raw)));
		}
	}

	/** 已有的确认项渲染成"第几步 · 哪种确认 · 问的是什么 → 答复是什么"。 */
	private static String renderHistory(List<AgentTaskConfirm> history) {
		if (history == null || history.isEmpty()) {
			return "（没有）";
		}
		StringBuilder sb = new StringBuilder();
		for (AgentTaskConfirm confirm : history) {
			sb.append("- 第 ").append(confirm.stepIndex()).append(" 步 · ").append(confirm.typeLabel())
					.append("：").append(confirm.description() == null ? "（无正文）" : confirm.description())
					.append(" → 答复：").append(confirm.answerLabel()).append('\n');
		}
		return sb.toString().strip();
	}

	/** 剥到最内层的异常消息，避免把整条 Reactor 包装链写进异常里。 */
	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	private static String truncate(String raw) {
		if (raw == null) {
			return "(空)";
		}
		return raw.length() <= 500 ? raw : raw.substring(0, 500) + "…(已截断)";
	}

}
