package ai.agent.service.impl.chat;

import ai.agent.entity.chat.structured.StructuredResult;
import ai.agent.service.support.chat.structured.MinConfidenceValidator;
import ai.agent.config.ChatCallProperties;
import ai.agent.service.token.TokenBudgetGuard;
import ai.agent.service.impl.token.CharacterClassTokenEstimator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结构化输出 + JSON 校验 + 回灌重试的行为测试（不连模型）。
 *
 * <p><b>这组测试才是"JSON 校验失败处理"的验收证据。</b>
 * 原因是模型不可能按需犯错 —— 想让线上模型稳定地"漏掉 confidence 字段"
 * 或"把 JSON 截断到一半"，只能靠替身精确注入。而这几类脏数据恰恰是
 * {@code json_object} 模式挡不住的：它们<b>都是合法 JSON</b>，解析全会成功，
 * 只有 JSR-380 校验能识破。
 *
 * <p>覆盖的注入类型（对应学习点 8 的三类异常注入）：
 * <ol>
 *   <li><b>JSON 截断</b> —— 输出到一半被 max_tokens 截断，未闭合的 JSON</li>
 *   <li><b>字段缺失</b> —— 少了 confidence，模型"交了半份作业"</li>
 *   <li><b>取值越界</b> —— confidence 给了 95（百分制），实测最常见的错误</li>
 *   <li><b>空集合</b> —— keywords 给空数组</li>
 * </ol>
 */
class StructuredChatServiceImplTest {

	private final ChatCallProperties properties = new ChatCallProperties();

	private Validator validator;

	private ChatClient chatClient;

	private StructuredChatServiceImpl service;

	private TokenBudgetGuard budgetGuard;

	@BeforeEach
	void setUp() {
		validator = buildValidator(properties);
		chatClient = mock(ChatClient.class);
		// 预算守卫用真实实现（配一份足够大的窗口）：这样"预算校验 + 回灌循环"的组合行为
		// 是被真实验证过的，而不是"测试里把它 mock 掉了所以永远通过"
		budgetGuard = new TokenBudgetGuard(properties, new CharacterClassTokenEstimator(properties),
				new InMemoryChatMemoryRepository(), new ai.agent.service.prompt.ClasspathPromptService(
						new CharacterClassTokenEstimator(properties)));
		service = new StructuredChatServiceImpl(chatClient, validator, properties, budgetGuard,
				new ai.agent.service.prompt.ClasspathPromptService(new CharacterClassTokenEstimator(properties)));
	}

	/**
	 * 手工装配一个能用依赖注入的 Validator。
	 *
	 * <p>为什么不能用 {@code Validation.buildDefaultValidatorFactory()}：
	 * {@code MinConfidenceValidator} 需要构造器注入 {@link ChatCallProperties}（阈值来自配置），
	 * 而默认工厂只会调无参构造器，会直接抛"找不到构造器"。
	 * Spring 环境下这不成问题（Boot 的 {@code SpringConstraintValidatorFactory} 负责注入），
	 * 但单元测试里必须自己提供等价的工厂 —— 这一条本身也是个值得记住的坑：
	 * <b>自定义校验器一旦带了依赖，就不能再用默认工厂了。</b>
	 */
	static Validator buildValidator(ChatCallProperties properties) {
		ConstraintValidatorFactory factory = new ConstraintValidatorFactory() {

			@Override
			public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
				if (key == MinConfidenceValidator.class) {
					return key.cast(new MinConfidenceValidator(properties));
				}
				try {
					return key.getDeclaredConstructor().newInstance();
				}
				catch (ReflectiveOperationException e) {
					throw new IllegalStateException("测试里无法实例化校验器：" + key, e);
				}
			}

			@Override
			public void releaseInstance(ConstraintValidator<?, ?> instance) {
				// 无状态校验器，不需要回收
			}
		};

		try (ValidatorFactory validatorFactory =
				Validation.byDefaultProvider().configure().constraintValidatorFactory(factory).buildValidatorFactory()) {
			return validatorFactory.getValidator();
		}
	}

	@Test
	@DisplayName("★ 系统提示词真的进了请求，而且**每一轮回灌都重新注入一次**（阶段四·热更新的接线证据）")
	void systemPromptIsInjectedOnEveryAttempt() {
		// 第 1 轮不合格（关键词为空）→ 第 2 轮回灌改对
		scriptModelReplies("{\"answer\":\"好的\",\"confidence\":0.9,\"keywords\":[]}", validJson(0.9));

		service.ask("c1", "SSE 是什么？");

		List<String> systems = capturedSystemPrompts();
		assertThat(systems).as("两轮模型调用 → 两次系统提示词注入").hasSize(2);
		assertThat(systems.get(0))
				.as("内容是当前生效的提示词（这里来自 classpath 替身），不是空串")
				.contains("你是");
		// 【为什么"每轮都注入"值得单独钉】如果实现改成"进来时取一次、回灌轮复用"，
		// 那么在两轮之间换掉提示词版本时，会出现第 1 轮用新版、第 2 轮用旧版 ——
		// 而两轮之间的矛盾会伪装成"模型自愈能力差"，排查方向完全错
		assertThat(systems.get(1)).as("回灌轮重新注入的是同一份（两次调用之间没有换版本）")
				.isEqualTo(systems.get(0));
		// 顺带钉住"取不到内容时不许静默发空提示词"：空系统提示词会让模型用默认人格回答，
		// 而那看起来像"模型不听话"，实际是部署问题
		assertThat(systems).allSatisfy(s -> assertThat(s).isNotBlank());
	}

	@Test
	@DisplayName("正常路径：第一次就合格 → 只调一次模型，不触发回灌")
	void returnsOnFirstValidOutput() {
		scriptModelReplies(validJson(0.9));

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		assertThat(result.degraded()).isFalse();
		assertThat(result.totalAttempts()).isEqualTo(1);
		assertThat(result.neededReflection()).isFalse();
		assertThat(result.answer().confidence()).isEqualTo(0.9);
		assertThat(result.answer().keywords()).containsExactly("SSE", "流式");
	}

	@Test
	@DisplayName("JSON 截断（未闭合）→ 识破 → 回灌一次改对")
	void recoversFromTruncatedJson() {
		// 模型输出到一半被 max_tokens 截断，这是真实会发生的情况
		scriptModelReplies(
				"{\"answer\":\"SSE 是服务器发送事件，它",
				validJson(0.8));

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		assertThat(result.degraded()).isFalse();
		assertThat(result.totalAttempts()).isEqualTo(2);
		// 第一次必须被记为失败，而且要给出"无法解析"这类可读原因
		StructuredResult.Attempt first = result.attempts().get(0);
		assertThat(first.success()).isFalse();
		assertThat(first.issues()).hasSize(1);
		assertThat(first.issues().get(0)).contains("无法解析");
	}

	@Test
	@DisplayName("字段缺失 → 校验识破（JSON 本身是合法的，解析不会报错）")
	void detectsMissingField() {
		scriptModelReplies(
				"{\"answer\":\"SSE 是服务器发送事件\",\"keywords\":[\"SSE\"]}", // 没有 confidence
				validJson(0.7));

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		assertThat(result.degraded()).isFalse();
		assertThat(result.attempts().get(0).issues())
				.anyMatch(issue -> issue.contains("confidence") && issue.contains("不能缺失"));
	}

	@Test
	@DisplayName("取值越界：confidence 给 95（百分制）→ 回灌提示必须说清要用 0~1 小数")
	void detectsOutOfRangeConfidenceAndTellsTheModelHowToFix() {
		scriptModelReplies(
				"{\"answer\":\"SSE 是服务器发送事件\",\"confidence\":95,\"keywords\":[\"SSE\"]}",
				validJson(0.95));

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		assertThat(result.degraded()).isFalse();
		assertThat(result.totalAttempts()).isEqualTo(2);

		// 关键断言：给模型的反馈必须带字段名和修正方向，否则它无从下手
		List<String> prompts = capturedPrompts();
		assertThat(prompts).hasSize(2);
		assertThat(prompts.get(1))
				.contains("confidence")
				.contains("0~1")
				.contains("95"); // 原始错误输出要带上，模型才能对照着改
	}

	@Test
	@DisplayName("空数组 → 校验识破")
	void detectsEmptyKeywords() {
		scriptModelReplies(
				"{\"answer\":\"SSE 是服务器发送事件\",\"confidence\":0.8,\"keywords\":[]}",
				validJson(0.8));

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		assertThat(result.attempts().get(0).issues())
				.anyMatch(issue -> issue.contains("keywords") && issue.contains("不能为空数组"));
	}

	@Test
	@DisplayName("三轮都改不对 → 走兜底，不抛异常，且 attempts 完整留痕")
	void fallsBackAfterExhaustingAttempts() {
		properties.getStructured().setMaxAttempts(3);
		scriptModelReplies(
				"{\"answer\":\"\",\"confidence\":95,\"keywords\":[]}",  // 三个字段全错
				"这不是 JSON",
				"{\"answer\":\"还是不行\"}");                            // 一直不合格

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		assertThat(result.degraded()).isTrue();
		assertThat(result.totalAttempts()).isEqualTo(3);
		assertThat(result.answer().confidence()).isEqualTo(0.0);
		assertThat(result.answer().answer()).contains("降级");

		// 每一轮的问题都要留痕：排查"模型到底错在哪"只能靠这个
		assertThat(result.attempts()).allMatch(attempt -> !attempt.success());
		assertThat(result.attempts()).allMatch(attempt -> !attempt.issues().isEmpty());
	}

	@Test
	@DisplayName("回灌轮数受配置控制：maxAttempts=1 时一次不合格就立刻兜底")
	void respectsConfiguredMaxAttempts() {
		properties.getStructured().setMaxAttempts(1);
		scriptModelReplies("{\"answer\":\"好的\",\"confidence\":95,\"keywords\":[\"x\"]}");

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		assertThat(result.totalAttempts()).isEqualTo(1);
		assertThat(result.degraded()).isTrue();
	}

	@Test
	@DisplayName("模型输出带 markdown 代码块围栏也能解析（不因为多了 ```json 就失败）")
	void toleratesMarkdownFence() {
		scriptModelReplies("```json\n" + validJson(0.85) + "\n```");

		StructuredResult result = service.ask("c1", "SSE 是什么？");

		// 不断言一定成功：这取决于 BeanOutputConverter 的清洗能力。
		// 断言的是"不会抛异常炸掉"，失败也要走正常的回灌/兜底路径
		assertThat(result.totalAttempts()).isGreaterThanOrEqualTo(1);
		assertThat(result.answer()).isNotNull();
	}

	@Test
	@DisplayName("置信度低于配置下界 → 校验识破（阈值可配，用于「必须给确定答案」的场景）")
	void detectsConfidenceBelowConfiguredFloor() {
		properties.getStructured().setMinConfidence(0.6);
		// 模型说"我不确定"（0.2），在要求确定答案的场景里这就是不合格
		scriptModelReplies(
				"{\"answer\":\"我不确定\",\"confidence\":0.2,\"keywords\":[\"不确定\"]}",
				validJson(0.9));

		StructuredResult result = service.ask("c1", "订单状态有哪些？");

		assertThat(result.degraded()).isFalse();
		assertThat(result.totalAttempts()).isEqualTo(2);
		assertThat(result.attempts().get(0).issues())
				.anyMatch(issue -> issue.contains("confidence") && issue.contains("下界"));
	}

	// -----------------------------------------------------------------
	//  替身：按顺序脚本化模型的返回
	// -----------------------------------------------------------------

	/**
	 * 让模型按顺序返回给定的若干段文本；调用次数超出脚本时，一直返回最后一段。
	 *
	 * <p>为什么要做成"脚本"：本测试的核心是<b>第 N 轮模型返回什么</b>，
	 * 而 Mockito 的 {@code thenReturn(...)} 链恰好表达这个。这里只是把它包一层，
	 * 避免在每个用例里重复十几行链式桩代码。
	 */
	private void scriptModelReplies(String... replies) {
		ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

		when(chatClient.prompt()).thenReturn(spec);
		// 【阶段四新增的这一行不是"补个桩"那么轻】
		// 生产代码从这一轮起每轮都调 .system(当前生效的提示词)，而 mock 对未打桩的方法
		// 返回 null —— 于是链在 .user(...) 处直接 NPE，报错文本变成
		// "Cannot invoke ... user(String) because the return value of ... system(...) is null"。
		// 这条报错指向的是**调用链**，看起来像生产的链写错了，实际是测试替身缺一个桩。
		// 一条经验：给 ChatClient 这种流式 API 打桩时，**链上每多一环就要多一个桩**，
		// 而漏掉的那一环只会在运行期以 NPE 的形式暴露。
		when(spec.system(anyString())).thenReturn(spec);
		when(spec.user(anyString())).thenReturn(spec);
		when(spec.options(any())).thenReturn(spec);
		// advisors 有三个重载（Consumer / Advisor... / List），`any()` 推不出该选哪个，
		// 必须显式指定成 Consumer 这一档 —— 生产代码用的正是 a -> a.param(...) 这种写法
		when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
		when(spec.call()).thenReturn(callSpec);

		OngoingStubbing<String> stub = when(callSpec.content()).thenReturn(replies[0]);
		for (int i = 1; i < replies.length; i++) {
			stub = stub.thenReturn(replies[i]);
		}
		stub.thenReturn(replies[replies.length - 1]);
	}

	/**
	 * 取出每一轮真正发给模型的<b>系统提示词</b>。
	 *
	 * <p>它证明的是"系统提示词确实进了请求"，而不是"我以为它进了"——
	 * 阶段四的热更新就是靠这条链把新版本送出去的，而"算了但没接线"这类 bug
	 * 在本项目已经出现过不止一次。
	 */
	private List<String> capturedSystemPrompts() {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		Mockito.verify(chatClient, Mockito.atLeastOnce()).prompt();
		Mockito.verify(chatClient.prompt(), Mockito.atLeastOnce()).system(captor.capture());
		return captor.getAllValues();
	}

	/** 取出每一轮真正发给模型的提示词，用于断言"回灌反馈里到底写了什么" */
	private List<String> capturedPrompts() {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		Mockito.verify(chatClient, Mockito.atLeastOnce()).prompt();
		Mockito.verify(chatClient.prompt(), Mockito.atLeastOnce()).user(captor.capture());
		return captor.getAllValues();
	}

	private static String validJson(double confidence) {
		return """
				{"answer":"SSE 是服务器发送事件","confidence":%s,"keywords":["SSE","流式"]}
				""".formatted(confidence);
	}

}
