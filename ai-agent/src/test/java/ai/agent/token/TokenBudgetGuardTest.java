package ai.agent.token;

import ai.agent.config.ChatCallProperties;
import ai.agent.token.impl.CharacterClassTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token 预算守卫的行为测试（学习点 2 的验收证据）。
 *
 * <h2>为什么要用"故意调小的窗口"来测</h2>
 * 生产配置是 128K 窗口，正常对话永远碰不到边界 —— 也就是说
 * <b>"超预算时会发生什么"这条路径在真实环境里几乎不可能被自然触发</b>。
 * 而它恰恰是最需要验证的部分（拒绝？裁剪？裁多少？报错文案说不说得清？）。
 *
 * <p>解法不是"等哪天真撞上了再看"，而是把窗口调成几百 token，
 * 让边界在几行代码里就能到达。这跟结构化输出那边"把 min-confidence 设成 0.99
 * 来确定性地触发校验失败"是同一个手法：<b>把不可复现的边界变成可复现的</b>。
 * 顺带这也验证了一件事 —— 窗口大小真的是配置驱动的，不是写死的。
 */
class TokenBudgetGuardTest {

	private ChatCallProperties properties;

	private ChatMemoryRepository repository;

	private TokenBudgetGuard guard;

	@BeforeEach
	void setUp() {
		properties = new ChatCallProperties();
		repository = new InMemoryChatMemoryRepository();
		guard = new TokenBudgetGuard(properties, new CharacterClassTokenEstimator(properties), repository,
				TokenBudgetGuard.DEFAULT_SYSTEM_PROMPT_LOCATION);
	}

	@Test
	@DisplayName("默认配置（128K 窗口）下正常对话直接放行，不做任何多余动作")
	void allowsNormalRequest() {
		TokenBudgetGuard.BudgetCheck check = guard.prepare("c1", "你好，介绍一下 SSE");

		assertThat(check.enabled()).isTrue();
		assertThat(check.truncated()).isFalse();
		assertThat(check.droppedMessages()).isZero();
		assertThat(check.remainingTokens()).isPositive();
		// 系统提示词必须被算进去（它是 advisor 注入的，业务代码里看不见，但确实占窗口）
		assertThat(check.systemPromptTokens()).isPositive();
		assertThat(check.neededTokens()).isGreaterThan(check.systemPromptTokens());
	}

	@Test
	@DisplayName("预算公式：needed = 系统提示词 + 提问 + 历史，且 allowance = 窗口 - 输出预留")
	void accountingAddsUp() {
		ChatCallProperties.Budget cfg = properties.getBudget();
		int allowance = guard.inputAllowance();

		// 手算一遍，和守卫报出来的对齐 —— 这条测试是"公式有没有写对"的钉子
		assertThat(allowance).isEqualTo(cfg.usableBudget() - cfg.reservedForOutput());
		assertThat(cfg.usableBudget()).isEqualTo((int) (cfg.getContextWindow() * (1 - cfg.getSafetyMargin())));

		// 安全边际必须是"真的扣掉了"，不能只是注释里写着
		assertThat(cfg.usableBudget()).isLessThan(cfg.getContextWindow());

		TokenBudgetGuard.BudgetCheck check = guard.prepare("c1", "你好");
		assertThat(check.allowedTokens()).isEqualTo(allowance);
		assertThat(check.neededTokens()).isLessThan(allowance);
	}

	@Test
	@DisplayName("历史越长账目越贵：把历史条数加进去，needed 随之增长")
	void historyIsCounted() {
		int withoutHistory = guard.prepare("c1", "继续").neededTokens();

		for (int i = 0; i < 5; i++) {
			repository.saveAll("c1", append(history("c1"),
					new UserMessage("第 " + i + " 轮的问题，内容稍微长一点用来占用预算"),
					new AssistantMessage("第 " + i + " 轮的回答，同样写长一点")));
		}

		int withHistory = guard.prepare("c1", "继续").neededTokens();
		assertThat(withHistory).isGreaterThan(withoutHistory);
	}

	@Test
	@DisplayName("超预算 → 裁掉最旧的历史后放行，且保留的是最近的消息")
	void truncatesOldestHistoryAndKeepsRecent() {
		// 把窗口调到几百 token，让边界能在一屏代码内到达
		properties.getBudget().setContextWindow(600);
		properties.getBudget().setMaxOutputTokens(100);
		properties.getBudget().setMinHistoryMessages(2);

		List<Message> longHistory = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			longHistory.add(new UserMessage("这是第 " + i + " 轮的问题，故意写得很长以占用预算空间" + "补充" + i));
			longHistory.add(new AssistantMessage("这是第 " + i + " 轮的回答，同样故意写长一点"));
		}
		repository.saveAll("c1", longHistory);

		TokenBudgetGuard.BudgetCheck check = guard.prepare("c1", "最后一问");

		// 1) 被裁剪了，而且确实丢掉了最旧的那批
		assertThat(check.truncated()).isTrue();
		assertThat(check.droppedMessages()).isPositive();

		// 2) 剩下的一定装得下（不能出现"裁完了还是超"却假装成功）
		assertThat(check.neededTokens()).isLessThanOrEqualTo(check.allowedTokens());

		// 3) 仓储里真的只剩最近的消息 —— 这条最关键：
		//    ChatMemory.add 是追加合并语义，用错方法会把裁掉的历史又加回来（实测过"越清越多"）
		List<Message> remaining = repository.findByConversationId("c1");
		assertThat(remaining).hasSize(longHistory.size() - check.droppedMessages());
		assertThat(remaining).isNotEmpty();
		assertThat(remaining.get(remaining.size() - 1).getText()).isEqualTo("这是第 11 轮的回答，同样故意写长一点");

		// 4) 旧消息确实不在了
		assertThat(remaining).noneMatch(m -> String.valueOf(m.getText()).contains("第 0 轮"));
	}

	@Test
	@DisplayName("裁剪有下限：不会为了挤进预算把历史全丢光（那等于让模型失忆）")
	void neverTrimsBelowMinimum() {
		// 【这个用例的写法值得说明】最初把窗口写死成 700，靠"历史的绝对长度够大"来逼出裁剪。
		// 结果估算系数一校准（中文密度从 1.0 改成 0.75），历史变"便宜"了，
		// 同一份数据只裁到 5 条就装得下 —— 断言"必须剩 2 条"直接失败。
		// 那次失败不是 bug，是**测试不该依赖系数的具体取值**。
		// 现在改成从预算公式反推：先算出这次能用的额度，再按这个额度生成
		// "必然装不下"的历史。这样以后再校准系数，这个用例依然成立。
		TokenBudgetGuard probe = guard;
		properties.getBudget().setContextWindow(700);
		properties.getBudget().setMaxOutputTokens(100);
		properties.getBudget().setMinHistoryMessages(2);

		int allowance = probe.inputAllowance();
		int roomForHistory = Math.max(1, allowance - probe.systemPromptTokens() - 32);

		// 每条历史消息按"能塞满可用额度 1/3"来构造 —— 于是必然需要裁掉大部分
		int perMessageChars = Math.max(20, (roomForHistory / 3) * 2);
		String longText = "很长的内容".repeat(perMessageChars / 5 + 1);
		List<Message> history = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			history.add(new UserMessage("问题 " + i + "：" + longText));
			history.add(new AssistantMessage("回答 " + i + "：" + longText));
		}
		repository.saveAll("c1", history);

		TokenBudgetGuard.BudgetCheck check = guard.prepare("c1", "问");

		// 核心断言：不管裁多少，"至少保留 min-history-messages 条"这条红线不能破
		List<Message> remaining = repository.findByConversationId("c1");
		assertThat(remaining).hasSizeGreaterThanOrEqualTo(2);
		assertThat(check.droppedMessages()).isEqualTo(history.size() - remaining.size());
		assertThat(check.droppedMessages()).isPositive();

		// 而且留下的必须是<b>最近</b>的 —— 裁错方向（留下最旧的）比不裁更糟
		assertThat(remaining.get(remaining.size() - 1).getText()).contains("回答 19：");
	}

	@Test
	@DisplayName("关掉截断开关 → 超预算直接拒绝，且历史一条不动")
	void rejectsWhenTruncationDisabled() {
		properties.getBudget().setContextWindow(600);
		properties.getBudget().setMaxOutputTokens(100);
		properties.getBudget().setTruncateHistory(false);

		List<Message> history = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			history.add(new UserMessage("问题 " + i + "：" + "很长的内容".repeat(6)));
		}
		repository.saveAll("c1", history);

		assertThatExceptionOfType(TokenBudgetExceededException.class)
				.isThrownBy(() -> guard.prepare("c1", "问"))
				.satisfies(e -> {
					assertThat(e.getStatusCode().value()).isEqualTo(400);
					// 报错文案必须给出可行动的信息，而不是一句"超限"
					assertThat(e.getReason()).contains("预算").contains("建议");
				});

		// 拒绝的路径绝不能有副作用：历史必须原样保留
		assertThat(repository.findByConversationId("c1")).hasSize(history.size());
	}

	@Test
	@DisplayName("提问本身就超预算 → 直接拒绝（裁剪救不了，该裁的是提问但那是用户的意图）")
	void rejectsOversizedQuestion() {
		properties.getBudget().setContextWindow(500);
		properties.getBudget().setMaxOutputTokens(100);

		String huge = "这是一段特别长的粘贴内容。".repeat(60); // 约 780 字 ≈ 780 tokens

		assertThatThrownBy(() -> guard.prepare("c1", huge))
				.isInstanceOf(TokenBudgetExceededException.class)
				.satisfies(e -> {
					TokenBudgetExceededException exceeded = (TokenBudgetExceededException) e;
					// 差多少必须能看出来 —— 否则用户只知道"失败了"，不知道该缩到多小
					assertThat(exceeded.getNeeded()).isGreaterThan(exceeded.getAllowed());
					assertThat(exceeded.getReason()).contains("拆小").contains("缩短");
				});
	}

	@Test
	@DisplayName("关掉总开关 → 一律放行（排障用的逃生口）")
	void disabledBypassesEverything() {
		properties.getBudget().setEnabled(false);
		properties.getBudget().setContextWindow(10); // 小到任何请求都不可能通过

		TokenBudgetGuard.BudgetCheck check = guard.prepare("c1", "你好，这会超预算");

		assertThat(check.enabled()).isFalse();
		assertThat(check.allowedTokens()).isZero();
	}

	@Test
	@DisplayName("结构化输出的检查只校验、不裁剪：不能把回灌轮刚写进记忆的内容裁掉")
	void structuredCheckNeverTruncatesHistory() {
		properties.getBudget().setContextWindow(1000);
		properties.getBudget().setMaxOutputTokens(100);

		List<Message> history = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			history.add(new UserMessage("历史 " + i + "：" + "内容".repeat(10)));
		}
		repository.saveAll("c1", history);
		int sizeBefore = repository.findByConversationId("c1").size();

		// 提示词很小 → 通过；但无论通过与否，都不许动历史
		guard.check("测试轮", "请输出 JSON", sizeBefore);

		assertThat(repository.findByConversationId("c1")).hasSize(sizeBefore);
	}

	@Test
	@DisplayName("结构化输出超预算 → 报错文案要提到回灌轮次会让提示词逐轮增长")
	void structuredCheckExplainsGrowth() {
		properties.getBudget().setContextWindow(500);
		properties.getBudget().setMaxOutputTokens(100);

		String reflectionPrompt = "你上一次的输出没有通过校验，请修正后重新输出。" + "附上你上次的原文与错误清单。".repeat(30);

		assertThatThrownBy(() -> guard.check("结构化输出第 2 轮", reflectionPrompt, 0))
				.isInstanceOf(TokenBudgetExceededException.class)
				.satisfies(e -> assertThat(((TokenBudgetExceededException) e).getReason())
						.contains("回灌")
						.contains("max-attempts"));
	}

	/**
	 * 真实配置（128K 窗口）下的量级感：正常对话的账目应该是几百 token，
	 * 距离预算三个数量级远。
	 *
	 * <p><b>为什么要断言这个"显然成立"的事</b>：因为如果哪天有人误改了系数
	 * （比如把 {@code asciiCharsPerToken} 写成 0.35）或者把单位搞错，
	 * 估算值会突然放大 10 倍 —— 那种错误不会让任何功能报错，
	 * 只会让系统从"永远放行"变成"经常误拒"。这个断言就是那道防线。
	 */
	@Test
	@DisplayName("默认 128K 配置下，正常对话的账目在数百 token 量级（数量级护栏）")
	void realisticMagnitude() {
		TokenBudgetGuard.BudgetCheck check = guard.prepare("c1", "请用三句话解释一下什么是 SSE，以及它和 WebSocket 的区别");

		assertThat(check.neededTokens()).isBetween(200, 900);
		assertThat(check.allowedTokens()).isGreaterThan(100_000);
		assertThat(check.remainingTokens()).isGreaterThan(100_000);
	}

	@Test
	@DisplayName("窗配置自相矛盾（预留输出比窗口还大）时不崩，但 inputAllowance 为 0")
	void contradictoryConfigDoesNotBlowUp() {
		properties.getBudget().setContextWindow(100);
		properties.getBudget().setMaxOutputTokens(5000);

		assertThat(guard.inputAllowance()).isZero();
		// 任何请求都会被拒绝，但拒绝的方式是"明确的 400"，不是空指针
		assertThatThrownBy(() -> guard.prepare("c1", "你好")).isInstanceOf(TokenBudgetExceededException.class);
	}

	private List<Message> history(String conversationId) {
		return new ArrayList<>(repository.findByConversationId(conversationId));
	}

	private static List<Message> append(List<Message> base, Message... more) {
		List<Message> result = new ArrayList<>(base);
		result.addAll(List.of(more));
		return result;
	}

}
