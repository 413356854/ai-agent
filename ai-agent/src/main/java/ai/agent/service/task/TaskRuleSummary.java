package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.RuleSummary;
import ai.agent.entity.agent.task.RuleSummaryPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <b>把"这次问过什么、答过什么"汇成几条规则</b> —— 验收通过之后那一步里唯一调模型的地方。
 *
 * <h2>为什么不跟判定一样跑成一次 Agent 运行</h2>
 * 判定要走 {@code AgentLoop}（技能目录、工具、步数上限）是因为它要<b>查</b>：
 * "这条任务要动哪个目录、有没有技能覆盖"都只能查出来。这一件事不查任何东西 ——
 * 材料全在手上（确认项的正文与答复就在参数里），产物是一段文字。
 * 为它套一层循环的代价是：多一次工具目录与技能目录的装配、多一份提示词、多一条 {@code agent_run}，
 * 而收益是零。所以它是<b>一次裸模型调用</b>，与 {@code StructuredChatServiceImpl} 同一形态。
 *
 * <h2>★ 用它自己的 {@code ChatClient}，不用容器里那个 bean</h2>
 * 容器里的那个 {@code ChatClient} 挂着 {@code MessageChatMemoryAdvisor}（按 conversationId
 * 读写会话记忆）与 {@code CHAT_SYSTEM} 的默认系统提示词。这一件事两样都不该有：
 * 它要的是无状态抽取（同一份材料任何时候都该给同一个结果），而"写进会话历史"是连带的副作用 ——
 * 谁会去看那个会话？没人。所以这里注入<b>自动配置的 {@code ChatClient.Builder}（prototype）</b>
 * 自己 {@code build()} 一个局部实例，并在调用点显式给系统提示词。
 *
 * <h2>温度与失败</h2>
 * 温度取 0：这是一次抽取，不是创作。调用失败 / 解析不出来一律抛
 * {@link IllegalStateException}，由 {@code TaskRuleDigest} 吞掉并记 WARN ——
 * <b>不做回灌重试</b>（与判定同一条：判定的失败有更好的去处，回灌会连烧几轮 token
 * 去救一次"这段材料本来就归不出规矩"的调用）。
 */
@Component
public class TaskRuleSummary {

	private static final Logger log = LoggerFactory.getLogger(TaskRuleSummary.class);

	/** 抽取类任务的温度：0 = 同一份材料尽量给同一个结果。 */
	private static final double TEMPERATURE = 0.0;

	private final ChatClient chatClient;

	private final BeanOutputConverter<RuleSummary> converter = new BeanOutputConverter<>(RuleSummary.class);

	public TaskRuleSummary(ChatClient.Builder builder) {
		// 刻意不挂 advisor、不设 defaultSystem：两者都在调用点显式给（见类注释）
		this.chatClient = builder.build();
	}

	/**
	 * 归纳一次。
	 *
	 * @param task 这条任务（要它的描述与 id）
	 * @param answered 已经答复过的确认项（调用方保证非空 —— 空的那一次不该走到这里）
	 * @return 归纳结果；模型说"没什么可归纳的"时 {@code usable} 为 false
	 * @throws IllegalStateException 模型没跑成，或输出解析不出 {@link RuleSummary}
	 */
	public RuleSummary summarize(AgentTask task, List<AgentTaskConfirm> answered) {
		String raw = chatClient.prompt()
				.system(RuleSummaryPrompt.renderSystem(converter.getJsonSchema()))
				.user(RuleSummaryPrompt.instruction(task, answered))
				.options(OpenAiChatOptions.builder().temperature(TEMPERATURE))
				.call()
				.content();
		RuleSummary summary = parse(converter, raw);
		log.info("任务 #{} 的经验归纳：usable={}，{} 条规则", task.id(), summary.usable(),
				summary.rules() == null ? 0 : summary.rules().size());
		return summary;
	}

	/**
	 * 解析成 record。
	 *
	 * <p>失败抛出去、<b>不返回默认结论</b>（与 {@code TaskReviewRunner#parse} 同一条）：
	 * 一个"没有结论"的默认值会被当成"模型说没什么可归纳"，于是这次补充静悄悄什么都没做，
	 * 而日志里看不出是模型没给还是解析失败。异常消息里带上模型原文的截断 ——
	 * 排查这类问题只能靠那一句原文。
	 */
	private static RuleSummary parse(BeanOutputConverter<RuleSummary> converter, String raw) {
		try {
			return converter.convert(raw);
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("经验归纳的输出解析不出目标结构（" + root(e) + "）。模型原始输出："
					+ (raw == null ? "(空)" : raw.length() <= 500 ? raw : raw.substring(0, 500) + "…(已截断)"), e);
		}
	}

	/** 剥到最内层的异常消息，避免把整条 SDK 包装链写进异常里。 */
	private static String root(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

}
