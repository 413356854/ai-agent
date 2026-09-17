package ai.agent.service.agent;

import ai.agent.entity.agent.AgentRun;
import ai.agent.entity.prompt.PromptChange;
import ai.agent.service.prompt.PromptConflictException;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.prompt.PromptStore;
import ai.agent.service.prompt.PromptValidator;
import ai.agent.entity.prompt.PromptVersion;
import ai.agent.service.token.TokenBudgetGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>热更新到底有没有生效 —— 这一件东西的唯一硬证据。</b>
 *
 * <h2>它补的是哪个缺口（这一条比代码重要）</h2>
 * "改了提示词，下一次运行就用新的"这句话有一个非常容易自欺的验证方式：
 * 断言 {@code promptService.currentContent()} 返回了新内容。
 * 而那只证明<b>服务读到了新内容</b>，完全没有证明<b>发出去的请求里有它</b>。
 * 本项目在这一点上已经吃过两次亏（「装上了」与「真在生效」是两件事：bean 装着但实现是裸的；
 * 第九轮：MCP 工具注册了但路由没注册）—— 两次的失败都发生在
 * <b>"两段之间那条没人检查的缝里"</b>。
 *
 * <p>所以这里的断言方式是：<b>把出站请求体抓下来，从里面把系统消息取出来，
 * 算它的指纹，与库里那条 active 的指纹比对。</b>
 * 只有这一条能同时证明三件事：库里的版本变了、读的人读到了、发出去的确实是它。
 *
 * <h2>为什么用真 HTTP 假端点，而不是在 {@code ModelTurn} 上打桩</h2>
 * 在接口那一层打桩会跳过 {@code OpenAiModelTurn → Spring AI → SDK → JSON} 整条链路 ——
 * 而"系统消息到底有没有进 JSON 请求"恰恰是这条链路上最容易出问题、
 * 又最不会被编译期发现的地方。{@link FakeOpenAiServer} 会记录原始请求体。
 *
 * <h2>为什么数据库是<b>文件库</b>而且路径由环境变量给</h2>
 * 热更新测试必须证明"换一个进程它还认"这件事吗？不必 —— 它会太贵。
 * 但它必须证明<b>版本历史是真的落盘了</b>，所以这里用一个每次运行都全新的文件库：
 * 相对路径 {@code target/prompt-hot-reload-<pid>} 落在 Maven 的构建目录里，
 * 进程退出后不会污染仓库（{@code target/} 本来就被忽略），
 * 也不会与上一次运行的数据串味（文件名带 pid）。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key", "spring.ai.openai.chat.options.model=fake-model",
		// 空库启动：这样"出厂版本初始化到第 1 版"这件事是被真正验证的，
		// 而不是在一个已经有数据的库上碰巧成立
		"ai.agent.store.auto-resume-on-startup=false" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PromptHotReloadTest {

	private static FakeOpenAiServer fakeServer;

	/**
	 * 每次运行一个全新的文件库。
	 *
	 * <p><b>为什么不用内存库</b>：内存库在"测试之间共享同一个 Spring 上下文"时也能用，
	 * 但它证明不了"版本历史真的写下去了"—— 而"历史只追加"是这一整件的核心纪律。
	 * 用文件库时，任何"以为写了其实没写"的实现都会在下一个测试里现形。
	 *
	 * <p>⚠️ 后缀是 {@code nanoTime} 而不是 {@code pid}：实测 <b>surefire 会复用同一个 fork JVM</b>
	 * （连续几次都是 pid 12000），于是按 pid 命名的文件库会**跨 mvn 运行存活**，
	 * 第二次跑时库里已经有一堆版本 → 与"第 1 版是出厂版本""这里应该有 N 版"这类
	 * 绝对数字断言直接冲突，而失败信息看起来像功能坏了。
	 */
	@DynamicPropertySource
	static void pointAtFakeServerAndFreshDb(DynamicPropertyRegistry registry) throws IOException {
		fakeServer = new FakeOpenAiServer();
		registry.add("spring.ai.openai.base-url", fakeServer::baseUrl);
		Path dbDir = Path.of("target", "prompt-hot-reload");
		Files.createDirectories(dbDir);
		String url = "jdbc:h2:file:" + dbDir.toAbsolutePath().toString().replace('\\', '/')
				+ "/prompts-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		registry.add("ai.agent.store.url", () -> url);
	}

	@Autowired
	private AgentLoop agentLoop;

	@Autowired
	private PromptService prompts;

	@Autowired
	private PromptStore store;

	@Autowired
	private TokenBudgetGuard budgetGuard;

	/** 跑一次任务，返回假端点收到的<b>第一个</b>请求体。 */
	private JsonNode runAndCapture(String question) {
		fakeServer.script(List.of(FakeOpenAiServer.textResponse("好的，我直接回答。")));
		AgentRun run = agentLoop.run(question);
		assertThat(run).isNotNull();
		assertThat(fakeServer.callCount()).as("至少发生了一轮模型调用").isGreaterThanOrEqualTo(1);
		return fakeServer.requestJson(0);
	}

	/** 从出站请求里取出系统消息的正文（模型真正看到的那一份）。 */
	private static String systemMessageOf(JsonNode request) {
		JsonNode messages = request.get("messages");
		assertThat(messages).as("请求里必须有 messages 数组").isNotNull();
		for (JsonNode message : messages) {
			if ("system".equals(message.path("role").asText())) {
				return message.path("content").asText();
			}
		}
		throw new AssertionError("出站请求里没有 system 消息 —— 提示词压根没发出去");
	}

	// ------------------------------------------------------------------
	//  1. 出厂版本进了库
	// ------------------------------------------------------------------

	@Test
	@Order(1)
	@DisplayName("启动即把 classpath 出厂提示词写成第 1 版并生效 —— 回滚的最终去处永远存在")
	void seedsFactoryVersionOnStartup() {
		List<PromptVersion> versions = prompts.versions(PromptKind.AGENT_LOOP);
		assertThat(versions).as("出厂版本必须在库里（否则'回滚到最初那一版'无处可回）").isNotEmpty();

		PromptVersion first = versions.get(versions.size() - 1);
		assertThat(first.version()).as("第一版就是出厂版本，版本号从 1 开始").isEqualTo(1);
		assertThat(first.content()).as("内容来自 classpath 那一份").contains("think").contains("工作方式");
		assertThat(first.note()).as("出厂版本自己也要有说明，否则历史的第一行就是空的").contains("出厂版本");
		assertThat(first.author()).as("写入者是 system，不是某个不存在的『我』").isEqualTo("system");

		PromptVersion active = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();
		assertThat(active.version()).isEqualTo(1);
		assertThat(active.hash()).as("指纹必须是正文的 SHA-256 前 12 位").hasSize(12);
		assertThat(active.hash()).isEqualTo(PromptVersion.hashOf(active.content()));
	}

	@Test
	@Order(2)
	@DisplayName("出站请求里的系统提示词 == 库里 active 那一版（含工具目录）")
	void outboundSystemMessageMatchesActiveVersion() {
		JsonNode request = runAndCapture("你好");

		String system = systemMessageOf(request);
		PromptVersion active = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();

		// 【核心断言形态】不是"包含我加的那句话"，而是"前缀与库里那一版逐字节相同"。
		// 前者在"两处各写一份提示词"的实现下也会通过（而那是这个 bug 最可能的形态）
		assertThat(system)
				.as("出站系统消息的前缀必须与库里 active 那一版的正文完全相同（指纹 %s）", active.hash())
				.startsWith(active.content());
		// 工具目录是运行时拼上去的第二段，它不该进库（库里的那一版只是"人写的那部分"）
		assertThat(system).as("工具目录仍然被拼在末尾（它与 tools[] 同源）").contains("你当前可以使用的工具");
		assertThat(active.content()).as("库里那一版**不该**含工具目录：它是拼出来的，不是人写的")
				.doesNotContain("你当前可以使用的工具");
	}

	// ------------------------------------------------------------------
	//  2. 换一版 → 下一次运行真的用新的（这一条就是"热更新"）
	// ------------------------------------------------------------------

	@Test
	@Order(3)
	@DisplayName("★ 激活新版本之后：下一次运行的出站提示词就是新的（不需要重启、不需要重新打包）")
	void hotReloadTakesEffectOnNextRun() {
		PromptVersion before = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();
		String marker = "【热更新标记-%d】".formatted(System.nanoTime());

		// 在正文末尾追加一行标记 —— 保持其余部分不变，
		// 这样"结构锚点"（工作方式 / 工具 / 不编造）仍然齐全，校验不会拦
		String updated = before.content() + "\n\n" + marker + "这一行是为了验证热更新而临时加的。\n";
		PromptChange change = prompts.save(PromptKind.AGENT_LOOP, new PromptValidator.Input(
				PromptKind.AGENT_LOOP, updated, "热更新测试：在末尾追加一行标记", "hot-reload-test", false));

		// 【存与生效是两次决定】保存之后**还没生效** —— 这一步必须被断言，
		// 否则"保存即生效"这个更危险的实现会一路绿灯
		assertThat(prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow().version())
				.as("保存不自动生效：当前生效的仍是旧版")
				.isEqualTo(before.version());

		prompts.activate(PromptKind.AGENT_LOOP, change.saved().version(), before.version(), "hot-reload-test");

		JsonNode request = runAndCapture("热更新生效了吗");
		String system = systemMessageOf(request);

		assertThat(system).as("新一版的标记必须出现在**真正发出去的请求**里").contains(marker);
		PromptVersion active = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();
		assertThat(active.version()).isEqualTo(change.saved().version());
		assertThat(system).as("出站内容仍然与库里那一版逐字节一致").startsWith(active.content());
		assertThat(active.forkedFrom()).as("手工新建的版本不是复制品，forkedFrom 应为空").isNull();
	}

	@Test
	@Order(4)
	@DisplayName("★ 提示词换了之后，预算里那个『系统提示词 token』跟着重算（不是启动时算一次）")
	void budgetRecomputesAfterHotReload() {
		// 【这里改的是 CHAT_SYSTEM，不是 AGENT_LOOP】—— 这个选择本身就是一条知识：
		// 预算守卫管的是**对话**那条链路（它算的是 ChatClient 请求的窗口），
		// 而 Agent 循环有自己的 token 上限（ai.agent.loop.max-tokens），两者不是同一个预算。
		// 第一版这个用例改的是 AGENT_LOOP，于是它验证的其实是"一个与预算无关的提示词改了，
		// 预算没变" —— 那当然会红，而红的理由看起来像"热更新没生效"。
		PromptVersion before = prompts.activeVersion(PromptKind.CHAT_SYSTEM).orElseThrow();
		int tokensBefore = budgetGuard.systemPromptTokens();
		assertThat(tokensBefore).as("改之前预算里那个数就是当前生效那一版的估算值")
				.isEqualTo(before.tokenEstimate());

		// 造一版明显更长的提示词：如果预算那个数是构造期算出来的常量，
		// 下面这条断言会红，而**别的任何地方都不会红**（这正是它的危险之处）
		String longer = before.content() + "\n\n## 附加说明\n\n"
				+ "这一整段是为了让 prompt token 明显变大而写的。".repeat(30) + "\n";
		PromptChange change = prompts.save(PromptKind.CHAT_SYSTEM, new PromptValidator.Input(
				PromptKind.CHAT_SYSTEM, longer, "预算重算测试：追加一大段", "hot-reload-test", false));
		prompts.activate(PromptKind.CHAT_SYSTEM, change.saved().version(), before.version(), "hot-reload-test");

		int tokensAfter = budgetGuard.systemPromptTokens();
		assertThat(tokensAfter)
				.as("换了一版提示词之后，预算里那个数必须变 —— 它是每次现算的，不是构造期常量")
				.isGreaterThan(tokensBefore);
		assertThat(tokensAfter).as("而且必须等于当前生效那一版的估算值")
				.isEqualTo(prompts.activeVersion(PromptKind.CHAT_SYSTEM).orElseThrow().tokenEstimate());

		// 把这一版切回去：后面的用例还在同一个上下文与同一个库里跑，
		// 留着一份被人为撑大的对话提示词会让它们的失败信息变得难以理解
		prompts.activate(PromptKind.CHAT_SYSTEM, before.version(), change.saved().version(), "hot-reload-test");
		assertThat(budgetGuard.systemPromptTokens()).as("切回去之后预算也跟着回去了（双向都跟着变）")
				.isEqualTo(tokensBefore);
	}

	// ------------------------------------------------------------------
	//  3. 并发：前提过期就不许静默覆盖
	// ------------------------------------------------------------------

	@Test
	@Order(5)
	@DisplayName("★ 带着过期的 expectedVersion 切换 → 409（不是静默覆盖别人的决定）")
	void staleActivateIsRejected() {
		PromptVersion current = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();
		int stale = current.version() - 1;

		assertThatThrownBy(() -> prompts.activate(PromptKind.AGENT_LOOP, current.version() - 1, stale,
				"另一个标签页"))
				.as("两个人同时改：后一个的前提已经过期，必须被拒 —— 静默成功会把前一个人的决定悄悄抹掉")
				.isInstanceOf(PromptConflictException.class)
				.satisfies(e -> {
					PromptConflictException conflict = (PromptConflictException) e;
					// 错误信息里必须带**实际**版本号：只说"冲突了"会让人反复点同一个按钮
					assertThat(conflict.actualVersion()).isEqualTo(current.version());
					assertThat(conflict.expectedVersion()).isEqualTo(stale);
				});

		assertThat(prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow().version())
				.as("被拒之后生效版本一个都没动")
				.isEqualTo(current.version());
	}

	// ------------------------------------------------------------------
	//  4. 回滚 = 新增一版
	// ------------------------------------------------------------------

	@Test
	@Order(6)
	@DisplayName("★ 回滚不是把指针挪回去，而是把旧版内容复制成新的一版 —— 历史里留着这次回滚")
	void rollbackAppendsInsteadOfMovingPointer() {
		List<PromptVersion> versions = prompts.versions(PromptKind.AGENT_LOOP);
		PromptVersion target = versions.get(versions.size() - 1); // 出厂那一版
		int beforeCount = versions.size();
		int activeBefore = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow().version();
		assertThat(activeBefore).as("回滚之前生效的不是出厂版（否则这次回滚没有意义）").isNotEqualTo(target.version());

		PromptVersion rolled = prompts.rollback(PromptKind.AGENT_LOOP, target.version(), "oncall", "线上答案变差，回滚");

		assertThat(prompts.versions(PromptKind.AGENT_LOOP))
				.as("回滚**新增**了一版（不是把指针挪回去）")
				.hasSize(beforeCount + 1);
		assertThat(rolled.version()).as("新版本号比之前那个更大").isGreaterThan(activeBefore);
		assertThat(rolled.forkedFrom()).as("新版本记着它复制自哪一版 —— 这是『谁把哪一版拉回来的』的凭据")
				.isEqualTo(target.version());
		assertThat(rolled.content()).as("正文与目标版本逐字节相同").isEqualTo(target.content());
		assertThat(rolled.author()).isEqualTo("oncall");
		assertThat(prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow().version())
				.as("回滚之后生效的就是这个新版本").isEqualTo(rolled.version());

		// 端到端：下一次运行发出去的确实是出厂那一版的内容
		JsonNode request = runAndCapture("回滚之后还热更新吗");
		String system = systemMessageOf(request);
		assertThat(system).as("出站提示词已经回到出厂版本").startsWith(target.content());
	}

}
