package ai.agent.service.task;

import ai.agent.entity.agent.task.AgentTask;
import ai.agent.entity.agent.task.AgentTaskConfirm;
import ai.agent.entity.agent.task.RuleSummary;
import ai.agent.entity.agent.task.RuleSummaryPrompt;
import ai.agent.service.support.task.RuleSummaryJson;
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
	 * 解析成 record；<b>先原样解析，失败了把引号补成转义再试一次</b>。
	 *
	 * <h2>为什么要有第二次机会（真实踩过）</h2>
	 * 模型把规则正文里的引号原样写进 JSON 字符串时，整份输出就不是合法 JSON 了 ——
	 * 实测那条报错是 `Unexpected character ('"' (code 34)): was expecting comma to separate
	 * Object entries`（规则里写了「"0.8取整/按原月分摊"」这种带引号的原文）。
	 * 那时<b>材料本身是好的</b>，只是包装坏了；不救的话这一次归纳白花，而现象是
	 * "验收成功、技能里少了一条规则"。这与 {@code TaskReviewRunner} 那条"不抠围栏"的纪律不冲突：
	 * 那条说的是**不要去猜哪一段才是答案**（猜错的形态是得到一个看起来正确的结论，
	 * 而判定结果直接决定动什么文件）；这里不猜内容，只是把"引号没转义"这一个语法事实修掉，
	 * 内容一字不改 —— 而且**只在原样解析失败之后才走**，并留一条 WARN 说它被用到了。
	 *
	 * <h2>仍然解析不出来时：把原文整段写进日志</h2>
	 * 异常里那一句截断（前 500 字符）只够让人知道"它坏了"，不够看它坏在哪 ——
	 * 而这一条链路的失败**不影响任何别的东西**，所以原文（它只有几百字符）留全更值。
	 */
	private static RuleSummary parse(BeanOutputConverter<RuleSummary> converter, String raw) {
		try {
			return converter.convert(raw);
		}
		catch (RuntimeException first) {
			String repaired = RuleSummaryJson.repair(raw);
			if (repaired != null) {
				try {
					RuleSummary summary = converter.convert(repaired);
					log.warn("经验归纳的输出不是合法 JSON（{}），已按「字符串里的引号补转义」修好并用它：{}",
							root(first), oneLine(repaired));
					return summary;
				}
				catch (RuntimeException still) {
					first = still;
				}
			}
			log.warn("经验归纳的原始输出（解析不出来，{}）：{}", root(first), raw);
			throw new IllegalStateException("经验归纳的输出解析不出目标结构（" + root(first) + "）。模型原始输出："
					+ (raw == null ? "(空)" : raw.length() <= 500 ? raw : raw.substring(0, 500) + "…(已截断)"), first);
		}
	}

	/** 日志里的一行（换行会把它拆成多行，反而不好搜）。 */
	private static String oneLine(String text) {
		String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
		return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
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
