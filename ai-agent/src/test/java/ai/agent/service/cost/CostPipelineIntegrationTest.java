package ai.agent.service.cost;

import ai.agent.entity.agent.AgentRun;
import ai.agent.service.agent.AgentStopReason;
import ai.agent.service.agent.ModelTurn;
import ai.agent.service.agent.ResumableAgentService;
import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.entity.tool.ToolInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>成本口径的端到端落库测试（阶段四 · 成本看板）。</b>
 *
 * <h2>它盯的是"模型报的 usage 真的走到库里了吗"</h2>
 * 看板上的每一个钱数都是从 {@code agent_step} 的两列算出来的：
 * {@code prompt_tokens / cache_read_tokens}。而这两列的值来自
 * 上游响应里的 usage —— 中间隔着**四个环节**：
 * <pre>
 *   OpenAiModelTurn.translate（读 usage）
 *     → ModelReply（带出来）
 *       → AgentRun.Step（记在步骤上）
 *         → PersistingListener（写检查点）
 *           → JdbcAgentRunStore（落库）
 * </pre>
 * 任何一环漏掉这个字段，看板的成本就会**静默地**按"全部未命中"算 ——
 * 偏高，且偏高的倍数取决于上游缓存命中率（一个我们看不见也调不了的量）。
 *
 * <p>所以这一条断言不能只测其中一层（那样测的是"我以为接上了"），
 * 而要**从模型一路查到库**：给一个"报出缓存命中"的假模型，跑一次任务，
 * 然后直接读库里的行。这与阶段三那条"回填真的进了下一轮请求"是同一种做法 ——
 * <b>证据必须是端到端的，而不是"某一层算出来了"</b>。
 *
 * <h2>第二件事：业务归因键也必须真的落库</h2>
 * 成本看板的「按业务归因」那一栏，唯一的数据来源就是 {@code agent_run.intent}。
 * 它由调用方在发起任务时给出，一路传到 {@code store.start}。中间任何一环没传，
 * 那一栏就会永远只有「未标注」—— 而页面上完全看不出来是漏传了还是真没人标注。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.url=jdbc:h2:mem:agent-cost-pipeline;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"ai.agent.store.node=cost-pipeline" })
class CostPipelineIntegrationTest {

	@Autowired
	private ResumableAgentService service;

	@Autowired
	private AgentRunStore store;

	/** 一个"报出缓存命中"的假模型：两轮，第一轮报缓存命中，第二轮不报。 */
	private static ModelTurn cachingModel() {
		return new ModelTurn() {

			private int round;

			@Override
			public ModelReply chat(List<Message> messages) {
				round++;
				if (round == 1) {
					// 1000 输入里 600 命中缓存；输出 200。
					// 用 6 参构造器明确给出缓存量 —— 5 参那个默认是 0
					return new ModelReply("", List.of(ToolInvocation.from("call_1", "list_documents", "{}")),
							1000, 200, false, 600);
				}
				return new ModelReply("知识库里有 4 份文档。", List.of(), 1200, 50, false, 300);
			}

			@Override
			public String modelName() {
				return "测试模型-带缓存";
			}
		};
	}

	@Test
	@DisplayName("**命中的缓存 token 必须一路落到库里** —— 任何一环漏掉，成本都会静默偏高")
	void cachedTokensReachTheDatabase() {
		String runId = UUID.randomUUID().toString();

		AgentRun run = service.runWithId(runId, "知识库里有哪些文档？", cachingModel(), "端到端归因");

		assertThat(run.stopReason()).isEqualTo(AgentStopReason.ANSWERED);

		List<AgentRunRecord.StepCheckpoint> steps = store.stepsOf(runId);
		assertThat(steps).as("两步都要有检查点").hasSize(2);

		// 第 1 轮：输入 1000、其中 600 命中缓存
		assertThat(steps.get(0).promptTokens()).isEqualTo(1000);
		assertThat(steps.get(0).cacheReadTokens())
				.as("缓存命中量必须落库 —— 只留 prompt_tokens 的话，成本只能按最贵那一档算，"
						+ "而高估的倍数取决于上游缓存命中率（我们看不见也调不了）")
				.isEqualTo(600);
		// 第 2 轮：输入 1200、其中 300 命中
		assertThat(steps.get(1).cacheReadTokens()).isEqualTo(300);

		// 模型名也要落库：它是"换了模型之后钱花在哪"的唯一数据来源
		assertThat(steps.get(0).modelName()).as("模型名必须落库，否则看板上全是「未上报模型名」"
				+ "（而那是装饰器忘了转发 modelName 的典型症状）").isEqualTo("测试模型-带缓存");
	}

	@Test
	@DisplayName("缓存命中的 token 会被真的计入成本：同一批 token，命中越多越便宜")
	void cachedTokensChangeTheComputedCost() {
		String runId = UUID.randomUUID().toString();
		service.runWithId(runId, "再跑一次", cachingModel(), "端到端归因");

		var steps = store.stepsOf(runId);
		long cached = steps.stream().mapToLong(AgentRunRecord.StepCheckpoint::cacheReadTokens).sum();
		assertThat(cached).as("两轮合计命中 600 + 300 = 900").isEqualTo(900L);

		// 用一份明确的价格算一遍：命中价是未命中价的 1/10
		var pricing = new CostPricing(new ai.agent.config.CostProperties("CNY", List.of(
				new ai.agent.config.CostProperties.Price("测试模型-带缓存",
						new java.math.BigDecimal("1"), new java.math.BigDecimal("0.1"),
						new java.math.BigDecimal("2"), "CNY", null, "测试价"))));
		long prompt = steps.stream().mapToLong(AgentRunRecord.StepCheckpoint::promptTokens).sum();
		long completion = steps.stream().mapToLong(AgentRunRecord.StepCheckpoint::completionTokens).sum();

		var withCache = pricing.price("测试模型-带缓存", prompt - cached, cached, completion, null).orElseThrow();
		var withoutCache = pricing.price("测试模型-带缓存", prompt, 0, completion, null).orElseThrow();

		assertThat(withCache.micros()).as("900 个 token 按 1/10 的价算，成本必须明显更低")
				.isLessThan(withoutCache.micros());
	}

	@Test
	@DisplayName("**业务归因键必须真的落库** —— 它是「按业务归因」那一栏唯一的数据来源")
	void intentReachesTheDatabase() {
		String runId = UUID.randomUUID().toString();
		String intent = "客服问答-" + runId.substring(0, 8);

		service.runWithId(runId, "问题", cachingModel(), intent);

		var record = store.find(runId).orElseThrow();
		assertThat(record.question()).isEqualTo("问题");
		// ⚠️ AgentRunRecord 上目前没有 intent 字段（它只在库里）——
		// 所以这里**直接查库**来证明它落进去了。这一条断言的价值在于：
		// 它证明"调用方传的归因键真的走到了 INSERT 语句里"，而不是只走到了某一层
		assertThat(intentOf(runId))
				.as("归因键没落库的话，成本看板的「按业务归因」永远只有一行「未标注」，"
						+ "而页面上看不出是漏传了还是真没人标注")
				.isEqualTo(intent);
	}

	@Test
	@DisplayName("没有归因键时存 NULL，而不是空串 —— 「没标注」与「标注成了空」是两件事")
	void missingIntentIsStoredAsNull() {
		String runId = UUID.randomUUID().toString();
		service.runWithId(runId, "问题", cachingModel(), null);

		assertThat(intentOf(runId)).as("空白归因键统一成 NULL：看板把 NULL 和空串都显示成「未标注」，"
				+ "但库里至少要能看出原本是哪种").isNull();

		String blankId = UUID.randomUUID().toString();
		service.runWithId(blankId, "问题", cachingModel(), "   ");
		assertThat(intentOf(blankId)).as("只有空白的归因键同样按 NULL 处理").isNull();
	}

	@Test
	@DisplayName("ModelReply 的自相矛盾检查：命中量不能超过输入量、不能为负")
	void modelReplyRejectsContradictoryCache() {
		assertThatThrownBy(() -> new ModelTurn.ModelReply("", List.of(), 100, 10, false, 200))
				.as("命中量是输入量的子集，超过它说明上游报的数自相矛盾。"
						+ "在这里抛而不是截断：截断会把一个数据问题变成静默的错数字，"
						+ "而成本表恰恰最不该有『看起来正常的错数字』")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("不可能超过");

		assertThatThrownBy(() -> new ModelTurn.ModelReply("", List.of(), 100, 10, false, -1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ------------------------------------------------------------------

	/** 直接查库读 intent（`AgentRunRecord` 上没有这个字段，它只属于成本口径）。 */
	private String intentOf(String runId) {
		try (var conn = poolOf().getConnection();
				var ps = conn.prepareStatement("SELECT intent FROM agent_run WHERE id = ?")) {
			ps.setString(1, runId);
			try (var rs = ps.executeQuery()) {
				assertThat(rs.next()).as("这次运行应该在库里").isTrue();
				return rs.getString("intent");
			}
		}
		catch (java.sql.SQLException e) {
			throw new IllegalStateException("查归因键失败 runId=" + runId, e);
		}
	}

	/** 借生产那个连接池（与 {@code JdbcCostQuery} 同一种做法：不另起一个池）。 */
	@Autowired
	private ai.agent.service.impl.agent.store.JdbcAgentRunStore jdbcStore;

	private ai.agent.config.AgentStoreProperties storeProperties;

	@Autowired
	private void setProperties(ai.agent.config.AgentStoreProperties properties) {
		this.storeProperties = properties;
	}

	private org.h2.jdbcx.JdbcConnectionPool poolOf() {
		// 生产那个池子就够用：这个测试只加一条 SELECT，不需要自己的池
		return jdbcStore.pool();
	}

}
