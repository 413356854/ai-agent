package ai.agent.service.canary;

import ai.agent.entity.agent.AgentRun;
import ai.agent.entity.canary.CanaryDecision;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.FakeOpenAiServer;
import ai.agent.service.agent.ResumableAgentService;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.entity.alert.AlertEvent;
import ai.agent.service.alert.AlertRecorder;
import ai.agent.service.alert.AlertRule;
import ai.agent.service.alert.AlertSnapshotQuery;
import ai.agent.service.alert.AlertStore;
import ai.agent.entity.prompt.PromptChange;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.prompt.PromptValidator;
import ai.agent.entity.prompt.PromptVersion;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>灰度发布到底有没有作用于「真正发出去的请求」—— 这一件唯一算数的证据。</b>
 * （阶段四第 5 件）
 *
 * <h2>它补的是哪一个缺口（这一条比代码本身重要）</h2>
 * 灰度发布有一整套非常容易自欺的验证方式：断言配置写进去了、断言
 * {@code decide()} 返回了候选版、断言界面上那个 20% 显示出来了。
 * 这三条<b>全部可以在"分流压根没接上"的实现下通过</b> —— 因为 {@code decide()}
 * 是个纯函数，它算对了不等于有人真的按它的结论去取提示词。
 *
 * <p>所以这里的证据形态与 {@code PromptHotReloadTest} 那条一致：
 * <b>把出站请求体抓下来，从里面取出 system 消息，看它是不是候选版那一份</b>。
 * 只有这一条能同时证明三件事：判定发生了、读的是那一版、<b>发出去的确实是它</b>。
 *
 * <h2>为什么"哪一次走新版"是算出来的，不是猜出来的</h2>
 * 分流是运行 id 的稳定哈希（见 {@link DefaultCanaryService#bucketOf}），
 * 所以在测试里可以直接<b>先用 {@code decide()} 问一遍</b>，拿到一个命中该走候选版的
 * 运行 id —— 于是测试不依赖"跑够多次总会有一次命中"这种概率假设。
 * 一个靠概率的测试会偶尔红，而偶发的红很快就会被当成噪声。
 *
 * <h2>数据库为什么是文件库</h2>
 * 与热更新测试同一条理由：它必须证明<b>分组真的落盘了</b>。
 * 内存库在"同一个 Spring 上下文里测试之间共享"时也能跑，但证明不了这件事 ——
 * 而"分组有没有落盘"恰恰是事后回答"新版到底比旧版好还是差"的唯一依据。
 * 文件名带 {@code nanoTime}（不是 pid）：surefire 会复用同一个 fork JVM，
 * 按 pid 命名会让文件库跨 mvn 运行存活，于是绝对数字断言会莫名其妙地失败。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key", "spring.ai.openai.chat.options.model=fake-model",
		"ai.agent.store.auto-resume-on-startup=false" })
class CanaryRoutingTest {

	private static FakeOpenAiServer fakeServer;

	@DynamicPropertySource
	static void pointAtFakeServerAndFreshDb(DynamicPropertyRegistry registry) throws IOException {
		fakeServer = new FakeOpenAiServer();
		registry.add("spring.ai.openai.base-url", fakeServer::baseUrl);
		Path dbDir = Path.of("target", "canary-routing");
		Files.createDirectories(dbDir);
		String url = "jdbc:h2:file:" + dbDir.toAbsolutePath().toString().replace('\\', '/')
				+ "/canary-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
		registry.add("ai.agent.store.url", () -> url);
	}

	@Autowired
	private CanaryService canary;

	@Autowired
	private PromptService prompts;

	@Autowired
	private ResumableAgentService service;

	@Autowired
	private AgentRunStore store;

	@Autowired
	private AlertRecorder alertRecorder;

	@Autowired
	private AlertStore alertStore;

	@Autowired
	private AlertSnapshotQuery alertQuery;

	// ------------------------------------------------------------------
	//  1. ★ 端到端：分流真的改变了"发出去的提示词"
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 命中候选版的那次运行，出站系统消息里有候选版的标记；没命中的那次没有")
	void routingChangesWhatIsActuallySentToTheModel() {
		// ---- 准备：存一版新的（不激活），再开 50% 的灰度 ----
		String marker = "【灰度候选版标记-%d】".formatted(System.nanoTime());
		PromptVersion stable = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();
		PromptChange saved = prompts.save(PromptKind.AGENT_LOOP, new PromptValidator.Input(PromptKind.AGENT_LOOP,
				stable.content() + "\n\n" + marker + "这一行只属于灰度候选版。\n", "灰度路由测试：在末尾加一个标记",
				"canary-test", false));
		// 【存与生效是两次决定】保存之后它**不该**生效 —— 否则这次根本不是灰度，是发布
		assertThat(prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow().version())
				.as("保存不自动生效：灰度是「候选版还没生效」的前提下才成立的事")
				.isEqualTo(stable.version());

		CanaryService.Canary started = canary.start(PromptKind.AGENT_LOOP, saved.saved().version(), 50,
				"验证分流是否真的改变了出站提示词", "canary-test");
		assertThat(started.active()).isTrue();
		assertThat(started.candidateVersion()).isEqualTo(saved.saved().version());

		// ---- 挑两个运行 id：一个命中、一个不命中（都先问一遍 decide，不靠概率） ----
		String hitId = firstRunIdWith(entry -> entry.candidate());
		String missId = firstRunIdWith(entry -> !entry.candidate());
		assertThat(hitId).as("50% 的重量下必须能找到命中的 id").isNotNull();
		assertThat(missId).as("也必须有没命中的").isNotNull();

		// ---- 真跑两次，各抓一份出站请求 ----
		JsonNode hitRequest = runAndCapture(hitId, "第一个问题");
		JsonNode missRequest = runAndCapture(missId, "第二个问题");

		String hitSystem = systemMessageOf(hitRequest);
		String missSystem = systemMessageOf(missRequest);

		assertThat(hitSystem).as("★ 命中候选版的那次运行，发出去的提示词里必须有候选版的标记 —— "
				+ "「decide() 返回了候选版」证明不了这件事，只有抓出站请求能证明").contains(marker);
		assertThat(missSystem).as("★ 没命中的那次**绝不能**出现候选版的标记").doesNotContain(marker);
		assertThat(hitSystem).as("对照组的正文与库里那一版逐字节一致").startsWith(stable.content());
		assertThat(missSystem).startsWith(stable.content());

		// ---- 分组也落了库（事后唯一能区分两组的依据） ----
		assertThat(store.stepsOf(hitId)).as("命中组的步骤要记成 canary").isNotEmpty()
				.allSatisfy(step -> assertThat(step.canaryVariant()).isEqualTo(CanaryDecision.LABEL_CANARY));
		assertThat(store.stepsOf(missId)).as("对照组要记成 stable —— 空串与 stable 是两件事")
				.allSatisfy(step -> assertThat(step.canaryVariant()).isEqualTo(CanaryDecision.LABEL_STABLE));
		assertThat(store.stepsOf(hitId).get(0).promptFingerprint())
				.as("★ 指纹必须是候选那一版的 —— 正文与指纹不同源的话，库里会出现一个"
						+ "对不上任何版本的指纹")
				.isEqualTo(saved.saved().fingerprint());
		assertThat(store.stepsOf(missId).get(0).promptFingerprint()).isEqualTo(stable.fingerprint());

		// ---- 观测分布：配置 50%、实测这两份是 1/2 ----
		CanaryService.Split split = canary.observation(PromptKind.AGENT_LOOP, 7);
		assertThat(split.candidateVersion()).isEqualTo(saved.saved().version());
		assertThat(split.weightPercent()).isEqualTo(50);
		assertThat(split.participatingRuns()).as("两次运行都参与了这次实验").isEqualTo(2);
		assertThat(split.candidateRuns()).isEqualTo(1);
		assertThat(split.stableRuns()).isEqualTo(1);
		assertThat(split.observedRatio()).isEqualTo(0.5);
		assertThat(split.versions()).as("逐版本表里该有候选版与稳定版各一行")
				.anySatisfy(row -> assertThat(row.variant()).isEqualTo(CanaryDecision.LABEL_CANARY))
				.anySatisfy(row -> assertThat(row.variant()).isEqualTo(CanaryDecision.LABEL_STABLE));

		// ---- 停止：必须带原因，而且停了之后新运行全部回稳定版 ----
		CanaryService.Canary stopped = canary.stop(PromptKind.AGENT_LOOP, "实验结束，未推全", "canary-test");
		assertThat(stopped.active()).isFalse();
		assertThat(stopped.endReason()).contains("实验结束");
		assertThat(canary.current(PromptKind.AGENT_LOOP)).as("停掉之后没有进行中的灰度").isEmpty();
		assertThat(canary.previous(PromptKind.AGENT_LOOP)).as("但上一轮实验仍然读得出来 —— "
				+ "一次实验的结论不该随着「点了一下停止」而消失").isPresent()
				.get().satisfies(previous -> {
					assertThat(previous.candidateVersion()).isEqualTo(saved.saved().version());
					assertThat(previous.weightPercent()).isEqualTo(50);
				});

		String afterId = firstRunIdWith(entry -> entry.candidate());
		assertThat(afterId).as("停了之后不该再有任何运行走候选版（哪怕桶命中也一样）").isNull();

		// 已经跑过的那两次不受影响 —— 它们的 trace 里仍然记着当时那一组
		assertThat(store.stepsOf(hitId).get(0).canaryVariant())
				.as("★ 停止灰度不会改写历史 —— 否则那次实验的数据就废了")
				.isEqualTo(CanaryDecision.LABEL_CANARY);
	}

	// ------------------------------------------------------------------
	//  2. ★ 告警真的会响（并留下可查询的事件）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 跑几轮评估之后，告警真的落了库、而且【未处理】的能查出来、能确认")
	void alertsActuallyFireAndPersist() {
		// 【为什么这条断言值得存在】告警这一件最容易的失败是"判据算得对、
		// 但没有任何东西会把它变成一条能看见的记录" —— 也就是"算出来了但没人知道"。
		// 那正是这一件要消灭的那半句话，所以这里必须有一条端到端的路径验证。
		AlertRecorder.Sweep first = alertRecorder.evaluate(7);

		assertThat(first.fired())
				.as("价格表是空的、缓存命中为 0 —— 这两条判据在测试库里必然成立")
				.extracting(AlertEvent::ruleCode)
				.contains(AlertRule.UNCONFIGURED_PRICES.code());
		assertThat(first.openCount()).as("未处理的条数要能直接读出来（界面顶部那个红点）").isPositive();

		String fingerprint = first.fired().stream()
				.filter(e -> e.ruleCode().equals(AlertRule.UNCONFIGURED_PRICES.code())).findFirst()
				.orElseThrow().fingerprint();
		AlertEvent stored = alertStore.find(fingerprint).orElseThrow();
		assertThat(stored.observed()).as("观测值要落库（不是只打印出来）").isNotBlank();
		assertThat(stored.action()).as("处置说明来自判据目录，随告警一起交付").isNotBlank();
		assertThat(stored.windowLabel()).as("窗口一起落库 —— 没有窗口的告警数字不可复现").contains("~");

		// 再评估一轮：它还在响，但**不算新响、也不再通报**
		AlertRecorder.Sweep second = alertRecorder.evaluate(7);
		assertThat(second.fired()).as("持续响着的东西不该被重复报成「新问题」").isEmpty();
		assertThat(second.ongoing()).isNotEmpty();
		assertThat(alertStore.find(fingerprint).orElseThrow().occurrences()).isGreaterThanOrEqualTo(2);

		// 确认之后它仍然是"在响"，但不再是"未处理"
		long openBefore = alertStore.openCount();
		assertThat(alertStore.acknowledge(fingerprint, "值班-张三", java.time.Instant.now())).isPresent();
		assertThat(alertStore.openCount()).isEqualTo(openBefore - 1);
		assertThat(alertStore.find(fingerprint).orElseThrow().state())
				.isEqualTo(AlertEvent.State.ACKNOWLEDGED);
	}

	@Test
	@DisplayName("干跑（只算不写）不会改变任何告警状态 —— 它是这一组端点里唯一的纯读入口")
	void dryRunSnapshotDoesNotTouchTheStore() {
		long before = alertStore.openCount();
		var snapshot = alertQuery.collect(7);
		var outcomes = ai.agent.service.alert.AlertEvaluator.evaluate(snapshot, AlertRule.AlertPhase.OBSERVE);

		assertThat(outcomes).as("干跑照样给出结论").isNotEmpty();
		assertThat(alertStore.openCount()).as("★ 只取数与判定，一行都不写").isEqualTo(before);
	}

	// ------------------------------------------------------------------
	//  辅助
	// ------------------------------------------------------------------

	/** 找一个"按给定条件命中/不命中"的运行 id（直接问 decide，不靠概率）。 */
	private String firstRunIdWith(java.util.function.Predicate<CanaryDecision> wanted) {
		return IntStream.range(0, 500).mapToObj(i -> "canary-probe-" + i)
				.filter(id -> wanted.test(canary.decide(PromptKind.AGENT_LOOP, id)))
				.findFirst().orElse(null);
	}

	/** 用一个指定的运行 id 真跑一次，返回假端点收到的**这一次**的请求体。 */
	private JsonNode runAndCapture(String runId, String question) {
		// 【先清请求再接脚本】两次运行各取自己的请求体 —— 不清的话
		// requestJson(0) 拿到的永远是列表里的第一个（也就是上一次运行的那个），
		// 而那种失败看起来像"灰度没生效"
		fakeServer.clearRequests();
		fakeServer.script(List.of(FakeOpenAiServer.textResponse("好的，我直接回答。")));
		AgentRun run = service.runWithId(runId, question);
		assertThat(run.stopReason()).as("这次运行该正常作答（假端点给了文字响应）")
				.isEqualTo(AgentStopReason.ANSWERED);
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

}
