package ai.agent.service.prompt;

import ai.agent.entity.prompt.PromptVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词写前校验的判据测试（阶段四第 3 件）。
 *
 * <h2>为什么这个类的测试值得单独写这么多条</h2>
 * 它拦的是一类<b>不报错的错</b>：提示词写坏了，接口照样 200、模型照样回答、
 * 字数甚至差不多，唯一的症状是答案质量下降 —— 而"质量"现阶段没有断言能钉住。
 * 也就是说：<b>这个校验器是这一整件功能里唯一能自动发现"改坏了"的地方</b>。
 * 它自己的判据如果不准（该拦的没拦、或不该拦的拦了），那整个保护就是空的。
 *
 * <p>另一半理由是"误拒"的代价：一旦校验开始拦正常的改动，
 * 人就会去删校验或者绕过它 —— <b>而一个被绕过的校验等于不存在</b>。
 * 所以这里每一条 REJECT 判据都要配一条"正当的改动必须放行"的用例。
 */
class PromptValidatorTest {

	private static final String GOOD_AGENT_PROMPT = """
			你是「AI Agent 项目」的技术助手。

			## 工作方式

			1. 先判断要不要查，需要查时用工具去查。
			2. 看到工具结果后要真的读它。

			## 回答要求

			- 不编造：查不到就说查不到。
			- 简洁：先给结论。
			""";

	private static PromptValidator.Input input(String content, String note) {
		return new PromptValidator.Input(PromptKind.AGENT_LOOP, content, note, "tester", false);
	}

	private static PromptValidator.Check checkOf(PromptValidator.Result result, String rule) {
		return result.checks().stream().filter(c -> c.rule().equals(rule)).findFirst()
				.orElseThrow(() -> new AssertionError("没有这条判据：" + rule));
	}

	// ------------------------------------------------------------------
	//  正常改动必须放行（先钉住这一半，否则校验会慢慢变成"什么都不让改"）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("一份完整的提示词：所有判据都通过，没有被拒的项")
	void acceptsAWellFormedPrompt() {
		PromptValidator.Result result = PromptValidator.validate(input(GOOD_AGENT_PROMPT, "首次写入"), null);

		assertThat(result.rejected()).as("这是一份结构完整的提示词，不该被拒").isFalse();
		assertThat(result.rejections()).isEmpty();
		// 【全部判据都要有结论】包括通过的项 —— 页面要把"检查了什么"列出来，
		// 只报错误的话，读者无从知道还有哪些东西被检查过
		assertThat(result.checks()).as("每条判据都要有结论，不能只报坏消息").hasSizeGreaterThanOrEqualTo(5);
		assertThat(result.checks()).allSatisfy(c -> assertThat(c.detail()).as("结论必须带具体内容").isNotBlank());
	}

	@Test
	@DisplayName("每一版都要求写改动说明 —— 没有说明的历史等于没有历史")
	void requiresANote() {
		PromptValidator.Result blank = PromptValidator.validate(input(GOOD_AGENT_PROMPT, "   "), null);
		assertThat(blank.rejected()).isTrue();
		assertThat(blank.rejections()).anySatisfy(c -> assertThat(c.rule()).isEqualTo("改动说明必填"));

		PromptValidator.Result tooShort = PromptValidator.validate(input(GOOD_AGENT_PROMPT, "改了"), null);
		assertThat(tooShort.rejected()).as("两个字说明不了改了什么").isTrue();

		assertThat(PromptValidator.validate(input(GOOD_AGENT_PROMPT, "把不编造那一条写得更硬"), null).rejected())
				.as("一句像样的说明必须放行，否则人会开始写『改』这种敷衍的说明")
				.isFalse();
	}

	@Test
	@DisplayName("改动说明的判据用 strip 之后的长度 —— 一串空格不算说明")
	void noteIsMeasuredAfterStrip() {
		PromptValidator.Result result = PromptValidator.validate(input(GOOD_AGENT_PROMPT, "  a  "), null);
		assertThat(result.rejected()).as("strip 之后只剩 1 个字，仍应被拒").isTrue();
		assertThat(checkOf(result, "改动说明必填").detail()).contains("1 个字");
	}

	// ------------------------------------------------------------------
	//  拒绝级：正文本身
	// ------------------------------------------------------------------

	@Test
	@DisplayName("正文为空 → 拒绝（空提示词会让模型失去角色设定，而回答看起来只是『变笨了』）")
	void rejectsBlankContent() {
		PromptValidator.Result result = PromptValidator.validate(input("   \n  \n", "想清空"), null);
		assertThat(result.rejected()).isTrue();
		assertThat(checkOf(result, "正文不能为空").level()).isEqualTo(PromptValidator.Level.REJECT);
	}

	@Test
	@DisplayName("正文超长 → 拒绝，且报出具体数字（多半是粘了两遍）")
	void rejectsOverlongContent() {
		String huge = "x".repeat(PromptValidator.MAX_CHARS + 1);
		PromptValidator.Result result = PromptValidator.validate(input(huge, "不小心粘多了"), null);

		assertThat(result.rejected()).isTrue();
		assertThat(checkOf(result, "正文长度上限").detail())
				.as("报错必须带数字，否则使用者只能猜差多少")
				.contains(String.valueOf(PromptValidator.MAX_CHARS));
	}

	@Test
	@DisplayName("★ 代码围栏是奇数个 → 拒绝：这是『粘贴被截断』最常见也最难发现的形态")
	void rejectsUnterminatedCodeFence() {
		String truncated = GOOD_AGENT_PROMPT + "\n\n## 示例\n\n```json\n{\"query\": \"分块\"}\n";
		PromptValidator.Result result = PromptValidator.validate(input(truncated, "补一个示例"), null);

		assertThat(result.rejected()).as("只有一个围栏标记，说明这份正文被截断了").isTrue();
		assertThat(checkOf(result, "代码围栏配平").detail())
				.as("要说明是奇数个，并指出这个坏法不会报错")
				.contains("奇数");
	}

	@Test
	@DisplayName("配平的围栏必须放行（提示词里带示例代码是正当写法）")
	void acceptsBalancedFences() {
		String withExample = GOOD_AGENT_PROMPT + "\n\n## 示例\n\n```json\n{\"query\": \"分块\"}\n```\n";
		PromptValidator.Result result = PromptValidator.validate(input(withExample, "补一个示例代码块"), null);

		assertThat(result.rejected()).isFalse();
		assertThat(checkOf(result, "代码围栏配平").detail()).contains("2 个");
	}

	@Test
	@DisplayName("★ 结构锚点丢了 → 拒绝（它挡的是『只粘贴了其中一段』这种残缺）")
	void rejectsMissingStructuralAnchors() {
		// 这一份内容非空、围栏配平、篇幅也不算离谱，所以前面几条全绿 ——
		// 只有"关键段落"这一条能拦住它。这正是它存在的理由
		String partial = """
				你是「AI Agent 项目」的技术助手。

				## 回答要求

				- 简洁：先给结论，再给依据。
				- 有依据：说明它来自哪份文档。
				""";
		PromptValidator.Result result = PromptValidator.validate(input(partial, "精简一下"), null);

		assertThat(result.rejected()).as("把『工作方式』整段丢了，Agent 不再知道要先查再答").isTrue();
		PromptValidator.Check anchor = checkOf(result, "关键段落还在");
		assertThat(anchor.detail()).contains("工作方式");
		// 报错要指出**缺了什么**，而不是只说"结构不对"
		assertThat(anchor.detail()).as("缺哪几项都要列出来，否则补不齐").contains("不编造");
	}

	@Test
	@DisplayName("对话提示词缺角色设定 → 拒绝；而 Agent 提示词的锚点与它不同（各钉各的）")
	void anchorsDifferPerKind() {
		// 长度刻意做够：否则它会被"低于下限"这条警告命中，
		// 于是这个用例测的就变成了篇幅，而不是"锚点按种类区分"
		String chatLike = "你是「AI Agent 项目」的助手，回答要简洁、先给结论再给依据。" + "补".repeat(230);
		assertThat(chatLike.length()).as("用例自身要够长，免得被篇幅判据干扰").isGreaterThan(200);

		// 这一段有"你是"，但没有任何 Agent 结构 —— 对 AGENT_LOOP 应被拒。
		// 改动说明刻意写够 4 个字：不到 4 个字会被"改动说明必填"这条先拒掉，
		// 于是这个用例测的就变成了说明长度，而不是锚点（这一条是实测踩到的）
		PromptValidator.Result agent = PromptValidator
				.validate(new PromptValidator.Input(PromptKind.AGENT_LOOP, chatLike, "换一份新的提示词", "t", false),
						null);
		assertThat(agent.rejected()).as("Agent 的提示词缺了工作方式与不编造，必须被拒").isTrue();
		assertThat(checkOf(agent, "关键段落还在").detail()).contains("工作方式");

		// 同一段内容对 CHAT_SYSTEM 恰恰是合格的（它只要角色设定）
		PromptValidator.Result chat = PromptValidator
				.validate(new PromptValidator.Input(PromptKind.CHAT_SYSTEM, chatLike, "换一份新的提示词", "t", false),
						null);
		assertThat(chat.rejected())
				.as("同一个判据不能对两份提示词用同一把尺；被拒的项是 %s", chat.rejections())
				.isFalse();
	}

	// ------------------------------------------------------------------
	//  警告级：可疑但放行（误拒会逼人绕过校验）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("篇幅骤减 → 警告但放行（它可能是刻意的删减，误拒会逼人绕过校验）")
	void warnsButAllowsOnBigShrink() {
		// 上一版固定 400 字符（用"上一版"这个长度是为了让下面那个比值可以算得准：
		// 拿 GOOD_AGENT_PROMPT 的实际长度当基准的话，比值是否越线取决于正文里几个字，
		// 于是这个用例会在别人改测试常量时莫名其妙地变红）
		PromptVersion previous = version(400);
		String shorter = "你是助手。\n## 工作方式\n用工具查，别凭印象回答，看到结果要真的读它。\n"
				+ "## 不编造\n查不到就说查不到，并说明查了什么。\n" + "补".repeat(90);
		assertThat(shorter.length()).as("用例自身的长度假设必须成立，否则测的不是相对变化")
				.isBetween(120, 200); // ≥120 躲开"低于下限"，<200 确保低于上一版的一半

		PromptValidator.Result result = PromptValidator.validate(
				input(shorter, "大幅精简，去掉重复表述"), previous);

		assertThat(result.rejected()).as("警告级判据不能变成拒绝：它只是一个提醒").isFalse();
		assertThat(result.warnings()).as("但必须报出来，而且页面上要看得见").isNotEmpty();
		PromptValidator.Check relative = checkOf(result, "篇幅相对上一版没有骤变");
		assertThat(relative.level()).isEqualTo(PromptValidator.Level.WARN);
		assertThat(relative.detail())
				.as("★ 必须同时给出『上一版多大』与『现在多大』："
						+ "只说『只有 165 字符』看不出这是删减还是粘贴残缺")
				.contains("400");
	}

	@Test
	@DisplayName("★ 「短于下限」与「相对骤变」是两条独立判据（骤减到很短时两条都要报）")
	void shortAndShrunkAreTwoSeparateChecks() {
		PromptVersion previous = version(1000);
		String tiny = "你是助手。\n## 工作方式\n用工具查。\n## 不编造\n查不到就说查不到。\n";

		PromptValidator.Result result = PromptValidator.validate(input(tiny, "压到最短"), previous);

		// 第一版把这两条写成了 if/else，于是最危险的形态（骤减到很短）
		// **只报出『低于下限』那一条**，"它掉了多少"这个关键信息完全没被说出来
		assertThat(result.checks()).anySatisfy(c -> assertThat(c.rule()).isEqualTo("篇幅没有低于下限"));
		assertThat(result.checks()).anySatisfy(c -> assertThat(c.rule()).isEqualTo("篇幅相对上一版没有骤变"));
		assertThat(result.warnings()).as("两条都该报").hasSize(2);
		assertThat(result.rejected()).as("但仍然只是警告，不是拒绝").isFalse();
	}

	@Test
	@DisplayName("篇幅暴涨 → 警告（它的副作用是 prompt token 涨、历史被更早裁掉）")
	void warnsOnBigGrowth() {
		PromptVersion previous = version(200);
		String big = GOOD_AGENT_PROMPT + "\n## 附录\n\n" + "补充说明。".repeat(200) + "\n";

		PromptValidator.Result result = PromptValidator.validate(input(big, "补充大量说明"), previous);
		assertThat(result.rejected()).isFalse();
		assertThat(checkOf(result, "篇幅相对上一版没有骤变").level()).isEqualTo(PromptValidator.Level.WARN);
	}

	@Test
	@DisplayName("正文过短 → 警告；带 force 时放行，但**别的判据照样拦**")
	void shortContentWarnsUnlessForced() {
		String short_ = "你是助手。\n## 工作方式\n用工具查。\n## 不编造\n查不到就说查不到。\n";

		PromptValidator.Result warned = PromptValidator.validate(input(short_, "压到最短"), null);
		assertThat(warned.rejected()).as("过短只是警告 —— 确实有人要压预算").isFalse();
		assertThat(warned.warnings()).hasSize(1);

		PromptValidator.Result forced = PromptValidator.validate(
				new PromptValidator.Input(PromptKind.AGENT_LOOP, short_, "压到最短", "t", true), null);
		assertThat(forced.warnings()).as("带 force 时那条警告不再出现").isEmpty();

		// 【force 只能压住警告，压不住拒绝】这一条很重要：
		// 如果 force 能把"正文为空"也放过去，它就成了一个"关掉校验"的开关
		PromptValidator.Result forcedBlank = PromptValidator.validate(
				new PromptValidator.Input(PromptKind.AGENT_LOOP, "   ", "想清空", "t", true), null);
		assertThat(forcedBlank.rejected()).as("force 不许让空正文通过").isTrue();
	}

	@Test
	@DisplayName("首次写入（没有上一版）时不比较篇幅，而且说明写明『无可比较』")
	void firstWriteSkipsComparison() {
		PromptValidator.Result result = PromptValidator.validate(input(GOOD_AGENT_PROMPT, "首次写入"), null);
		assertThat(checkOf(result, "篇幅相对上一版没有骤变").detail()).contains("首次写入");
		assertThat(checkOf(result, "篇幅相对上一版没有骤变").level()).isEqualTo(PromptValidator.Level.OK);
	}

	/** 造一个只用于比较篇幅的"上一版"（内容本身无关紧要）。 */
	private static PromptVersion version(int chars) {
		return new PromptVersion(PromptKind.AGENT_LOOP, 1, "x".repeat(chars), "h", chars, 10, "上一版", "t", null,
				null);
	}

}
