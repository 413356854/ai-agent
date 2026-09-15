package ai.agent.controller;

import ai.agent.agent.AgentFailureCause;
import ai.agent.agent.AgentLoop;
import ai.agent.agent.AgentRun;
import ai.agent.agent.AgentStopReason;
import ai.agent.agent.ModelFailureClassifier;
import ai.agent.agent.ModelTurn;
import ai.agent.agent.ScriptedModelTurn;
import ai.agent.agent.limit.LimitedModelTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 界面接口的测试（阶段三第 5 件）。
 *
 * <h2>为什么不测 HTTP 层，而测 controller 方法</h2>
 * 这一轮要验证的是**组装正确**：运行记录里哪些字段被带出来、
 * 每个 stage 的"算不算工具失败"判得对不对、四层保护的快照形状对不对。
 * 这些都在 controller 方法里，直接调它比发 HTTP 请求更快、更好定位
 * （失败了直接看到是哪个字段，而不是一个 500）。
 *
 * <p>HTTP 层的契约（路径、方法、JSON 形状）由**界面自检脚本**在真实后端上验证 ——
 * 那才是它该待的地方：形状对不对，要看真实的请求/响应，
 * 而不是在一个测试里把 controller 当普通对象调。
 *
 * <h2>本类里最值得看的一条</h2>
 * {@link #failureCauseSeparatesBusyFromBroken()}：它验证本轮实际修掉的那个问题 ——
 * "模型太挤"与"模型真的挂了"必须是**两个不同的原因**。
 * 在那之前它们都是 {@code MODEL_ERROR}，于是页面在压力最大、限流生效时
 * 恰恰给出错误归因（"上游有问题"），而这会把排查方向整个带偏。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.url=jdbc:h2:mem:agent-ui;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"ai.agent.store.node=ui-test", "ai.agent.loop.max-steps=6" })
class AgentControllerTest {

	@Autowired
	private AgentController controller;

	@Autowired
	private AgentLoop loop;

	private static final AtomicReference<ScriptedModelTurn> CURRENT = new AtomicReference<>();

	@TestConfiguration
	static class ScriptedModelConfig {

		@Bean
		@Primary
		ModelTurn scriptedModelTurn() {
			return new Delegating();
		}
	}

	static class Delegating implements ModelTurn {

		@Override
		public ModelReply chat(List<Message> messages) {
			ScriptedModelTurn turn = CURRENT.get();
			if (turn == null) {
				throw new IllegalStateException("测试没有设置脚本模型");
			}
			return turn.chat(messages);
		}

	}

	private static ScriptedModelTurn install(ModelTurn.ModelReply... replies) {
		ScriptedModelTurn turn = new ScriptedModelTurn(List.of(replies));
		CURRENT.set(turn);
		return turn;
	}

	@BeforeEach
	void clearScript() {
		CURRENT.set(null);
	}

	// ------------------------------------------------------------------
	//  /ask：一次完整的任务
	// ------------------------------------------------------------------

	@Test
	@DisplayName("/ask 返回过程与成本：每步、每次工具调用、token、耗时都要有")
	void askReturnsProcessAndCost() {
		install(
				ScriptedModelTurn.callTool("call_1", "list_documents", "{}"),
				ScriptedModelTurn.answer("知识库里有 4 份文档。"));

		Map<String, Object> body = controller.ask(new AgentController.AskRequest("知识库里有哪些文档？"));

		assertThat(body.get("ok")).isEqualTo(true);
		assertThat(body.get("runId")).as("运行 id 是后续查详情的凭据").isNotNull();
		assertThat(body.get("answered")).isEqualTo(true);
		assertThat(body.get("finalAnswer").toString()).contains("4 份文档");
		assertThat(body.get("stopReason")).isEqualTo("ANSWERED");
		assertThat((Integer) body.get("totalTokens")).as("成本必须返回 —— 否则用户不知道花了多少").isPositive();
		assertThat(body.get("toolCallSummary").toString()).as("工具调用的分类统计").contains("OK=");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> steps = (List<Map<String, Object>>) body.get("steps");
		assertThat(steps).hasSize(2);
		assertThat(steps.get(0).get("usedTools")).isEqualTo(true);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> calls = (List<Map<String, Object>>) steps.get(0).get("toolCalls");
		assertThat(calls).hasSize(1);
		assertThat(calls.get(0).get("toolName")).isEqualTo("list_documents");
		assertThat(calls.get(0).get("stage")).isEqualTo("OK");
		assertThat(calls.get(0).get("countsAsToolFailure")).as("OK 不算工具失败").isEqualTo(false);
	}

	@Test
	@DisplayName("/ask 拒绝空问题（不调模型、不花钱）")
	void askRejectsBlankQuestion() {
		Map<String, Object> body = controller.ask(new AgentController.AskRequest("   "));

		assertThat(body.get("ok")).isEqualTo(false);
		assertThat(body.get("error").toString()).contains("不能为空");
	}

	// ------------------------------------------------------------------
	//  失败细分：本轮实际修掉的那个问题
	// ------------------------------------------------------------------

	@Test
	@DisplayName("「模型太挤」与「模型挂了」必须是两个不同的原因（否则页面在限流时给出错误归因）")
	void failureCauseSeparatesBusyFromBroken() {
		// 太挤：本机并发闸门拒绝
		AgentRun busy = new AgentRun("q", List.of(), null, AgentStopReason.MODEL_ERROR, 0, 0, 0,
				ModelFailureClassifier.classify(new LimitedModelTurn.ModelBusyException("名额等不到")));
		// 挂了：上游鉴权失败
		AgentRun broken = new AgentRun("q", List.of(), null, AgentStopReason.MODEL_ERROR, 0, 0, 0,
				ModelFailureClassifier.classify(new RuntimeException("401 Authentication Fails")));

		assertThat(busy.stopReason()).as("两者的停因相同（都是调模型没成功）").isEqualTo(broken.stopReason());
		assertThat(busy.failureCause()).as("但细分原因必须不同").isEqualTo(AgentFailureCause.LOCAL_CONCURRENCY_LIMIT);
		assertThat(broken.failureCause()).isEqualTo(AgentFailureCause.UPSTREAM_AUTH);

		// 建议也要不同 —— 这才是有用的部分
		assertThat(busy.failureAdvice()).contains("不是上游的问题");
		assertThat(broken.failureAdvice()).as("鉴权失败要明确说重试没意义").contains("重试没有意义");
	}

	@Test
	@DisplayName("异常分类：六类原因各自能认出来，认不出的落到 UNEXPECTED（而不是被塞进某一类）")
	void failureClassificationCoversSixCauses() {
		assertThat(ModelFailureClassifier.classify(new LimitedModelTurn.ModelBusyException("x")))
				.isEqualTo(AgentFailureCause.LOCAL_CONCURRENCY_LIMIT);
		assertThat(ModelFailureClassifier.classify(new RuntimeException("401 Unauthorized")))
				.isEqualTo(AgentFailureCause.UPSTREAM_AUTH);
		assertThat(ModelFailureClassifier.classify(new RuntimeException("429 Too Many Requests")))
				.isEqualTo(AgentFailureCause.UPSTREAM_RATE_LIMITED);
		assertThat(ModelFailureClassifier.classify(new java.net.SocketTimeoutException("read timed out")))
				.isEqualTo(AgentFailureCause.UPSTREAM_TIMEOUT);
		assertThat(ModelFailureClassifier.classify(new java.net.ConnectException("Connection refused")))
				.isEqualTo(AgentFailureCause.UPSTREAM_UNAVAILABLE);
		assertThat(ModelFailureClassifier.classify(new RuntimeException("503 Service Unavailable")))
				.isEqualTo(AgentFailureCause.UPSTREAM_ERROR);

		// 【最关键的一条】认不出来必须落到 UNEXPECTED，不能塞进一个具体的类别。
		// 塞进去 = 给一个"具体的假归因"，而那比"未分类"更糟 —— 它看起来是确定的
		assertThat(ModelFailureClassifier.classify(new IllegalStateException("完全认不出的错误")))
				.isEqualTo(AgentFailureCause.UNEXPECTED);
		assertThat(ModelFailureClassifier.advice(AgentFailureCause.UNEXPECTED))
				.as("未分类的建议要诚实说明这是分类缺口").contains("信号");
	}

	@Test
	@DisplayName("分类要在被包装的异常里找到原因（Reactor/SDK 会层层包装）")
	void classificationUnwrapsNestedExceptions() {
		Throwable wrapped = new RuntimeException("outer",
				new IllegalStateException("middle", new java.net.SocketTimeoutException("read timed out")));

		assertThat(ModelFailureClassifier.classify(wrapped))
				.as("原始异常在被包装的那一层里 —— 只看最外层会判不出超时").isEqualTo(AgentFailureCause.UPSTREAM_TIMEOUT);
	}

	// ------------------------------------------------------------------
	//  /runs 与 /runs/{id}
	// ------------------------------------------------------------------

	@Test
	@DisplayName("/runs 列出历史（含状态与失败细分），并按问句能认出是哪一次")
	void runsListsHistory() {
		install(
				ScriptedModelTurn.callTool("call_1", "list_documents", "{}"),
				ScriptedModelTurn.answer("有 4 份文档。"));
		Map<String, Object> asked = controller.ask(new AgentController.AskRequest("界面试跑：有哪些文档？"));
		String runId = asked.get("runId").toString();

		Map<String, Object> list = controller.runs(50);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> runs = (List<Map<String, Object>>) list.get("runs");
		assertThat(runs).isNotEmpty();
		Map<String, Object> mine = runs.stream().filter(r -> runId.equals(r.get("id"))).findFirst().orElseThrow();
		assertThat(mine.get("question")).isEqualTo("界面试跑：有哪些文档？");
		assertThat(mine.get("status")).isEqualTo("ANSWERED");
		assertThat(mine.get("totalTokens")).isNotNull();
		assertThat(mine.get("toolCalls")).isEqualTo(1);
		assertThat(list.get("awaitingRecovery")).isNotNull();
	}

	@Test
	@DisplayName("/runs/{id} 给完整过程：步骤 + 每次工具调用（含 stage 与「算不算工具失败」）")
	void runDetailGivesFullTrace() {
		install(
				ScriptedModelTurn.callTool("call_bad", "doc_search", "{\"query\":\"分块\"}"),
				ScriptedModelTurn.callTool("call_ok", "search_docs", "{\"query\":\"分块\",\"topK\":2}"),
				ScriptedModelTurn.answer("查到了。"));
		String runId = controller.ask(new AgentController.AskRequest("界面试跑：分块策略？")).get("runId").toString();

		Map<String, Object> detail = controller.runDetail(runId);

		assertThat(detail.get("ok")).isEqualTo(true);
		@SuppressWarnings("unchecked")
		Map<String, Object> run = (Map<String, Object>) detail.get("run");
		assertThat(run.get("question")).isEqualTo("界面试跑：分块策略？");
		assertThat(run.get("finalAnswer").toString()).contains("查到");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> steps = (List<Map<String, Object>>) detail.get("steps");
		assertThat(steps).as("三轮：工具 / 工具 / 作答").hasSize(3);
		assertThat(steps.get(0).get("stepIndex")).isEqualTo(1);
		assertThat((Integer) steps.get(0).get("transcriptChars"))
				.as("每步都存了完整对话检查点 —— 这是「能还原执行过程」的数据基础")
				.isGreaterThan(100);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) detail.get("toolCalls");
		assertThat(toolCalls).hasSize(2);
		assertThat(toolCalls.get(0).get("stage")).as("第一次是编了工具名").isEqualTo("UNKNOWN_TOOL");
		assertThat(toolCalls.get(0).get("countsAsToolFailure"))
				.as("模型编错名字**不算**工具失败 —— 页面靠这个布尔上色，判断只能有一份").isEqualTo(false);
		assertThat(toolCalls.get(0).get("feedback").toString()).as("回填文本要能还原「模型当时看到了什么」")
				.contains("search_docs");
		assertThat(toolCalls.get(1).get("stage")).isEqualTo("OK");
	}

	@Test
	@DisplayName("/runs/{id} 对不存在的运行给出明确错误（而不是空对象）")
	void runDetailReportsMissingRun() {
		Map<String, Object> detail = controller.runDetail("does-not-exist");

		assertThat(detail.get("ok")).isEqualTo(false);
		assertThat(detail.get("error").toString()).contains("没有这个运行");
	}

	// ------------------------------------------------------------------
	//  /limits：四层保护的状态
	// ------------------------------------------------------------------

	@Test
	@DisplayName("/limits 给出配置 + 阈值 + 每把工具的闸门与熔断（并排，因为它们是两种不同的病）")
	void limitsGivesConfigAndPerToolState() {
		Map<String, Object> body = controller.limits();

		@SuppressWarnings("unchecked")
		Map<String, Object> config = (Map<String, Object>) body.get("config");
		assertThat(config.get("maxConcurrentModelRequests")).isEqualTo(8);
		assertThat(config.get("maxConcurrentToolRequests")).isEqualTo(4);
		// 注意是 5000L 而不是 5000：这一列在后端是 long，JSON 里也就是长整型。
		// 断言写成 int 会报 "expected: 5000 but was: 5000L" —— 一个纯粹的类型细节，
		// 而它的报错信息看起来像"值是错的"。**值对了、类型不对**这类失败要先看清 expected/but was 的差别
		assertThat(config.get("acquireTimeoutMs")).isEqualTo(5000L);
		assertThat(config.get("failureRateThreshold")).as("阈值要一起给 —— 页面显示「实际值 / 阈值」而不是替读者下结论")
				.isEqualTo(0.5);

		@SuppressWarnings("unchecked")
		Map<String, Object> modelGate = (Map<String, Object>) body.get("modelGate");
		// 本测试用脚本模型替换了真实适配器 → 模型侧闸门不存在。
		// 它必须**明确报 available=false**，而不是给一堆 0（一堆 0 看起来像"闸门在工作但没流量"）
		assertThat(modelGate.get("available")).isEqualTo(false);
		assertThat(modelGate.get("note").toString()).contains("未启用");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
		assertThat(tools).as("跑过工具之后应该至少有一把被记录").isNotEmpty();
		Map<String, Object> row = tools.get(0);
		assertThat(row).containsKeys("toolName", "inFlight", "peakInFlight", "rejected", "maxWaitMs",
				"circuitState", "failureRate", "shortCircuited");

		@SuppressWarnings("unchecked")
		Map<String, Object> totals = (Map<String, Object>) body.get("totals");
		assertThat(totals).containsKeys("toolsTracked", "totalRejected", "openCircuits");

		assertThat(body.get("note").toString()).as("必须说明这些数字是全局共享的，不是某次运行的属性")
				.contains("全局共享");
	}

}
