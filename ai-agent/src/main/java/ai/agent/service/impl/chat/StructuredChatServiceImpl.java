package ai.agent.service.impl.chat;

import ai.agent.service.chat.structured.StructuredAnswer;
import ai.agent.service.chat.structured.StructuredResult;
import ai.agent.config.ChatCallProperties;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.chat.StructuredChatService;
import ai.agent.service.token.TokenBudgetGuard;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 结构化输出 + JSON 校验 + 回灌重试（Retry &amp; Reflection Loop）。
 *
 * <h2>为什么需要这一整套东西</h2>
 * 直觉上"让模型输出 JSON"就够了，但实测 DeepSeek **不支持** {@code json_schema}（strict 模式），
 * 只能用 {@code response_format=json_object}。而 json_object 的保证非常弱 ——
 * 它只保证"输出是合法 JSON"，<b>不保证</b>：
 * <ul>
 *   <li>字段齐全（可能漏掉 {@code confidence}）</li>
 *   <li>类型正确（{@code confidence} 可能是字符串 {@code "0.9"}）</li>
 *   <li>取值合理（{@code confidence} 可能给 {@code 95} 而不是 {@code 0.95}）</li>
 * </ul>
 * 这三种情况 <b>JSON 解析都会成功</b>。也就是说：<b>解析成功 ≠ 数据可用</b>，
 * 所以必须有一层真校验（JSR-380），而校验的价值只有在"失败了知道怎么办"时才成立 ——
 * 那就是把具体错误回灌给模型让它自己改。
 *
 * <h2>为什么用阻塞调用（.call()）而不是流式</h2>
 * 结构化输出是<b>全有或全无</b>：JSON 必须完整才能反序列化，边流边解析没有意义
 * （半截 JSON 没法用，也没法提前渲染给用户看）。所以这里用同步调用，
 * 也就必须配一道总超时（见 {@code ai.chat.timeout.idle}）—— 否则模型挂起时线程会被一直占着，
 * 这正是学习点 7「异步改造」要解决的问题，本条留作那个改造的对照样本。
 *
 * <h2>为什么每轮都重建 ChatClient 请求而不是复用</h2>
 * 回灌时提示词变了（要带上"上次错在哪"），所以每轮都是一次新的、独立的模型调用。
 * 记忆仍然共用（同一个 {@code conversationId}），所以用户看到的是"连续追问"而不是"失忆重来"。
 */
@Service
public class StructuredChatServiceImpl implements StructuredChatService {

	private static final Logger log = LoggerFactory.getLogger(StructuredChatServiceImpl.class);

	private final ChatClient chatClient;

	private final Validator validator;

	private final ChatCallProperties properties;

	private final TokenBudgetGuard budgetGuard;

	/**
	 * 系统提示词的来源（阶段四第 3 件）。
	 *
	 * <p>与 {@code ChatServiceImpl} 里那一个字段是同一条理由：{@code ChatClient} 的
	 * {@code defaultSystem} 是<b>启动时快照</b>，热更新只能在调用点做。
	 */
	private final PromptService prompts;

	/**
	 * 生成 JSON Schema 并与字符串互转。
	 *
	 * <p>用 Spring AI 自带的 {@link BeanOutputConverter} 而不是手写 schema：
	 * 它能从 record 的字段类型 + JSR-380 注解直接生成 schema，还会在转换失败时抛带上下文的异常。
	 * 注意它的 {@code getJsonSchema()} 是<b>要写进提示词</b>的 —— 这是让模型知道
	 * "该输出什么"的主要手段（strict 模式用不了，只能靠提示词 + 校验兜）。
	 */
	private final BeanOutputConverter<StructuredAnswer> converter = new BeanOutputConverter<>(StructuredAnswer.class);

	public StructuredChatServiceImpl(ChatClient chatClient, Validator validator, ChatCallProperties properties,
			TokenBudgetGuard budgetGuard, PromptService prompts) {
		this.chatClient = chatClient;
		this.validator = validator;
		this.properties = properties;
		this.budgetGuard = budgetGuard;
		this.prompts = prompts;
	}

	@Override
	public StructuredResult ask(String conversationId, String question) {
		int maxAttempts = properties.getStructured().getMaxAttempts();
		List<StructuredResult.Attempt> attempts = new ArrayList<>();

		// 当前这一轮要发给模型的内容。第一轮就是原始问题；
		// 之后每一轮都会变成"问题 + 上次的错误输出 + 具体哪不合格"
		String prompt = firstPrompt(question);

		for (int index = 1; index <= maxAttempts; index++) {
			long startedAt = System.nanoTime();
			String raw = null;
			List<String> issues;
			StructuredAnswer parsed = null;

			// Token 预算校验（学习点 2）在这里的价值最大：结构化输出是"最多 3 轮"的循环，
			// 而<b>每一轮的提示词都比上一轮长</b>（带上了上次的原始输出 + 错误清单 + schema）。
			// 实测第 1 轮 prompt 474 tokens、第 2 轮 845 —— 接近翻倍。
			// 不在这里拦，就会出现"前几轮烧掉几千 token，最后一轮才因为超窗失败"，
			// 那是最亏的一种失败：钱花了、时间花了、什么也没拿到。
			//
			// 【位置很讲究】必须放在 try 之外。这个 try 的 catch 会把任何 RuntimeException
			// 都当成"模型输出有问题"→ 转成 issues 去回灌。超预算不是模型的问题，
			// 回灌也解决不了（下一轮的提示词只会更长），被 catch 住就会变成
			// "反复回灌一个注定失败的请求"——比直接失败糟得多。
			budgetGuard.check("结构化输出第 " + index + " 轮", prompt, 0);

			try {
				raw = callModel(conversationId, prompt);
				Conversion outcome = parseAndValidate(raw);
				parsed = outcome.value();
				issues = outcome.issues();
			}
			catch (RuntimeException e) {
				// 解析失败（不是合法 JSON / 字段类型对不上）也走同一条回灌路径：
				// 对模型来说"你输出的不是合法 JSON"和"你漏了字段"是同一种反馈
				issues = List.of("输出无法解析为目标的 JSON 结构：" + rootMessage(e));
			}

			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
			attempts.add(new StructuredResult.Attempt(index, raw, parsed, issues, elapsedMs));

			if (issues.isEmpty()) {
				if (index > 1) {
					log.info("结构化输出经 {} 轮回灌后通过校验 conversationId={} elapsedMs={}",
							index, conversationId, elapsedMs);
				}
				return new StructuredResult(parsed, attempts, false);
			}

			log.warn("结构化输出第 {}/{} 轮校验未通过 conversationId={} issues={}",
					index, maxAttempts, conversationId, issues);
			prompt = reflectionPrompt(question, raw, issues);
		}

		log.error("结构化输出 {} 轮仍未通过校验，走兜底 conversationId={} issues={}",
				maxAttempts, conversationId, attempts.get(attempts.size() - 1).issues());
		return new StructuredResult(
				StructuredAnswer.degraded("模型连续 " + maxAttempts + " 轮未能产出符合结构要求的答案，已降级返回。"),
				attempts,
				true);
	}

	/** 调用模型并取出文本。同步调用，靠配置的总超时兜住"模型挂起"。 */
	private String callModel(String conversationId, String prompt) {
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
				.temperature(0.0); // 结构化输出要稳定，不要创造力

		if (properties.getStructured().isJsonMode()) {
			// response_format=json_object 让模型至少输出合法 JSON（DeepSeek 支持这一档）
			options.responseFormat(OpenAiChatModel.ResponseFormat.builder()
					.type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
					.build());
		}

		return chatClient.prompt()
				// 与流式对话同样的处理：**每轮回灌都重新取一次**当前生效的系统提示词。
				// 如果只在外面取一次并在回灌轮里复用，改了提示词之后会出现
				// "第 1 轮用新版、第 2 轮用旧版"—— 而两轮之间的矛盾会伪装成"模型自愈能力差"
				.system(prompts.currentContent(PromptKind.CHAT_SYSTEM))
				.user(prompt)
				.options(options)
				// 与流式对话共用同一套对话记忆：回灌轮次对用户来说仍是"同一个会话在追问"
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.call()
				.content();
	}

	/**
	 * 解析 + 校验。
	 *
	 * <p>两步刻意分开：解析失败说明"这不是我能认的 JSON"，校验失败说明"JSON 认了但内容不合格"。
	 * 两者给模型的反馈措辞不同（一个说格式、一个说字段），分开处理才能给出准确提示 ——
	 * 反馈越具体，模型一次改对的概率越高。
	 */
	private Conversion parseAndValidate(String raw) {
		StructuredAnswer value = converter.convert(raw); // 失败会抛，由调用方转成 issues
		Set<ConstraintViolation<StructuredAnswer>> violations = validator.validate(value);

		if (violations.isEmpty()) {
			return new Conversion(value, List.of());
		}

		List<String> issues = new ArrayList<>();
		for (ConstraintViolation<StructuredAnswer> v : violations) {
			// 把"哪个字段 + 错在哪"拼成一句人能读、模型也能照做的话。
			// 关键是必须带上字段名：只说"校验失败"模型无从下手
			issues.add("字段 " + v.getPropertyPath() + "：" + v.getMessage());
		}
		return new Conversion(null, issues);
	}

	/** 第一轮的提示词：问题 + 结构要求（JSON Schema）。 */
	private String firstPrompt(String question) {
		return """
				请只输出一个 JSON 对象，不要有任何解释性文字、不要用 markdown 代码块包裹。

				需要满足的结构（JSON Schema）：
				%s

				用户问题：
				%s
				""".formatted(converter.getJsonSchema(), question);
	}

	/**
	 * 回灌提示词：把上次的输出和具体错误一起还给模型，让它自己修。
	 *
	 * <p>这里的设计要点（都不是随手写的）：
	 * <ul>
	 *   <li><b>带上原始输出</b>：只描述错误（"confidence 超出范围"）模型不知道该改哪一处；
	 *       把原文给它，它才能对照着改，而且能省掉重新生成整段的成本。</li>
	 *   <li><b>逐条列出错误</b>：一次把所有问题都告诉它，而不是一次修一个 ——
	 *       否则 3 轮可能只够修 3 个字段。</li>
	 *   <li><b>明确"只输出 JSON"</b>：实测模型被指出错误后倾向于先道歉再解释
	 *       （"抱歉，我确实写错了……"），那会直接破坏 JSON 解析。</li>
	 * </ul>
	 */
	private String reflectionPrompt(String question, String previousRaw, List<String> issues) {
		StringBuilder errorList = new StringBuilder();
		for (String issue : issues) {
			errorList.append("- ").append(issue).append('\n');
		}

		return """
				你上一次的输出没有通过校验，请修正后重新输出。

				原始问题：
				%s

				你上一次的输出：
				%s

				存在的问题（逐条修正）：
				%s
				要求：
				1. 只输出一个 JSON 对象，不要道歉、不要解释、不要用 markdown 代码块；
				2. 必须满足以下 JSON Schema：
				%s
				""".formatted(question, previousRaw, errorList, converter.getJsonSchema());
	}

	/** 剥到最内层的异常消息，避免把整条 Reactor 包装链写进给模型看的提示词里。 */
	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		if (message == null || message.isBlank()) {
			return cause.getClass().getSimpleName();
		}
		return message.length() > 200 ? message.substring(0, 200) + "..." : message;
	}

	/** 解析+校验的结果：要么是合格的值，要么是一串可读的问题 */
	private record Conversion(StructuredAnswer value, List<String> issues) {
	}

}
