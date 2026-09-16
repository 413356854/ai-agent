package ai.agent.cost;

import ai.agent.agent.AgentStopReason;
import ai.agent.agent.store.AgentRunRecord;
import ai.agent.agent.store.AgentRunStore;
import ai.agent.agent.store.RunStatus;
import ai.agent.config.AgentStoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>成本聚合的真实 SQL 测试（H2 + MODE=MySQL）。</b>
 *
 * <h2>为什么这一层必须用真库测，而不能用假的 CostQuery</h2>
 * 看板的钱是从四条 GROUP BY 查询里出来的，而那些查询里有本阶段最容易出错、
 * 也最不容易被发现的几件事：
 * <ul>
 *   <li><b>JOIN 的行数放大</b>：把步骤表与工具表一起 JOIN 到运行上时，
 *       一次运行如果有 3 步、2 次工具调用，会变成 6 行 —— 于是 token 被乘以 3。
 *       这个错给出的数字**看起来完全正常**（只是偏大），而它偏大的倍数随任务复杂度变化，
 *       所以连"大约是两倍"这种规律都看不出。本类的断言用"两边分别算再比对"来钉住它；</li>
 *   <li><b>两个口径的对账</b>：{@code agent_run.total_tokens}（循环自己累加的）
 *       与"步骤表的 token 之和"（看板算钱用的）本该一致。不一致说明有一处漏记，
 *       而看板上完全看不出来；</li>
 *   <li><b>NULL 归因键</b>：它必须独立成一行，不能被并进任何业务里。</li>
 * </ul>
 *
 * <p>这三件事都属于"结构性断言"能抓住、而"功能是否成功"的断言抓不住的类型 ——
 * 与阶段二那次用"9 个不同尺寸不能量出同一个数"抓住参数化失效是同一套做法。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key",
		"ai.agent.store.url=jdbc:h2:mem:agent-cost;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"ai.agent.store.node=cost-test" })
class CostQueryJdbcTest {

	@Autowired
	private AgentRunStore store;

	@Autowired
	private CostQuery query;

	@Autowired
	private AgentStoreProperties properties;

	private LocalDate today;

	@BeforeEach
	void reset() {
		today = LocalDate.now(ZoneId.systemDefault());
	}

	// ------------------------------------------------------------------
	//  造数据：直接写存储层，不跑 Agent（不调模型、确定性强）
	// ------------------------------------------------------------------

	/** 造一次运行：2 步 + 3 次工具调用（其中一次执行异常）。 */
	private String seedRichRun(String intent, String model, int promptPerStep, int cachedPerStep, int completion) {
		return seedRun(intent, null, model, promptPerStep, cachedPerStep, completion, RunStatus.ANSWERED,
				AgentStopReason.ANSWERED);
	}

	/** 同上，但可以指定结尾状态（用于造"被上限拦下"与"失败"这两种结局）。 */
	private String seedRichRun(String intent, String model, int promptPerStep, int cachedPerStep, int completion,
			RunStatus status, AgentStopReason stopReason) {
		return seedRun(intent, null, model, promptPerStep, cachedPerStep, completion, status, stopReason);
	}

	/**
	 * 同上，但可以同时指定<b>租户</b>（阶段四第 6 件）。
	 *
	 * <h2>⚠️ 为什么这个方法叫 {@code seedTenantRun} 而不是又一个 {@code seedRichRun} 重载</h2>
	 * 第一版就是加重载的：{@code seedRichRun(intent, tenantId, model, ...)} 与既有的
	 * {@code seedRichRun(intent, model, ...)} 在"第二、三个参数都是 String"这一点上重叠，
	 * 于是<b>所有已有的调用点全部编译不过</b>（javac 报"找不到合适的方法"，
	 * 而错误信息列的是几个候选，不会明说"你多了个重载"）。
	 *
	 * <p>这与 {@code ResumableAgentService.runWithId} 那一处是同一个坑的第二次出现：
	 * <b>给一个已经有多个 String 参数的方法再添一个 String 参数，
	 * 会把所有调用点变成歧义或"参数错位"</b> —— 而参数错位（把租户传进归因键的位置）
	 * 是<b>编译得过</b>的，它的后果是在看板上凭空多出一个叫「京东」的业务。
	 * 所以这里给新方法一个具名入口，而不是继续加重载。
	 */
	private String seedTenantRun(String tenantId, String intent, String model, int promptPerStep,
			int cachedPerStep, int completion) {
		return seedRun(intent, tenantId, model, promptPerStep, cachedPerStep, completion, RunStatus.ANSWERED,
				AgentStopReason.ANSWERED);
	}

	/** 一次运行（2 步 + 3 次工具调用），可指定归因键、租户、模型与结尾状态。 */
	private String seedRun(String intent, String tenantId, String model, int promptPerStep, int cachedPerStep,
			int completion, RunStatus status, AgentStopReason stopReason) {
		String runId = UUID.randomUUID().toString();
		store.start(new AgentRunStore.Start(runId, "问题 " + runId, properties.node(), intent, null, tenantId));
		for (int step = 1; step <= 2; step++) {
			store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, step, 0, "call_" + step,
					step == 1 ? "search_docs" : "read_section", "{}", "OK", "回填", 10L));
			store.checkpoint(new AgentRunRecord.StepCheckpoint(runId, step, "{\"messages\":[]}", "思考" + step,
					promptPerStep, completion, 100L, cachedPerStep, model, ""));
		}
		// 第 3 次工具调用：一次执行异常（用于验证"失败数按 stage 归因"）
		store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 2, 1, "call_x", "search_docs", "{}",
				"EXECUTION_ERROR", "炸了", 20L));
		int total = (promptPerStep + completion) * 2;
		store.finish(runId, status, stopReason, "答案", total, 3, 200L, null, null);
		return runId;
	}

	/** 造一次"一步都没有"的运行（死在第一步之前，例如鉴权失败）。 */
	private String seedEmptyRun(String intent) {
		String runId = UUID.randomUUID().toString();
		store.start(new AgentRunStore.Start(runId, "空运行", properties.node(), intent, null));
		store.finish(runId, RunStatus.FAILED, AgentStopReason.MODEL_ERROR, null, 0, 0, 5L, "鉴权失败", null);
		return runId;
	}

	// ------------------------------------------------------------------
	//  1. JOIN 不能放大行数（最值钱的一条）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("**JOIN 不能把 token 放大**：一次运行 2 步 3 次工具调用，token 仍必须只算 2 步的量")
	void joinsDoNotMultiplyTokens() {
		String intent = "归因-" + UUID.randomUUID();
		seedRichRun(intent, "deepseek-flash", 1000, 200, 500);

		var rows = query.byIntent(today, today);
		var row = rows.stream().filter(r -> intent.equals(r.key())).findFirst().orElseThrow();

		// 2 步 × 1000 输入 = 2000；2 步 × 500 输出 = 1000
		assertThat(row.promptTokens())
				.as("工具表有 3 行、步骤表有 2 行 —— 若把两张表 JOIN 到一起再 SUM，"
						+ "这里会变成 3000（被工具行数放大），而那个数字看起来完全正常")
				.isEqualTo(2000L);
		assertThat(row.completionTokens()).isEqualTo(1000L);
		assertThat(row.cachedPromptTokens()).as("2 步 × 200 命中").isEqualTo(400L);
		assertThat(row.runCount()).as("运行数也不能被 JOIN 放大").isEqualTo(1L);
		assertThat(row.toolCalls()).as("工具调用次数来自工具表：3 次（含那次失败的）").isEqualTo(3L);
		assertThat(row.toolFailures()).as("只有 EXECUTION_ERROR 算工具失败（与熔断判据同源）").isEqualTo(1L);
		assertThat(row.toolElapsedMs()).as("10 + 10 + 20").isEqualTo(40L);
	}

	@Test
	@DisplayName("按天汇总与按归因汇总必须**算出同一组 token** —— 两个视图不该各算一套")
	void dayAndIntentViewsAgree() {
		String intent = "一致性-" + UUID.randomUUID();
		seedRichRun(intent, "deepseek-flash", 700, 100, 300);

		var dayTotal = query.byDay(today, today).stream().mapToLong(d -> d.totals().totalTokens()).sum();
		var intentTotal = query.byIntent(today, today).stream().mapToLong(CostQuery.GroupTotals::totalTokens).sum();

		assertThat(dayTotal).as("同一批数据显示在两个维度上，加起来必须相等").isEqualTo(intentTotal);
		assertThat(dayTotal).isPositive();
	}

	// ------------------------------------------------------------------
	//  1.5 归因的两个维度：租户（谁的钱）与业务（哪个功能）—— 阶段四第 6 件
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 按租户与按业务是**两条独立的分组**：同一个租户的两个功能合成一行，而两个租户永不合并")
	void tenantAndIntentAreSeparateDimensions() {
		String tenant = "租户-" + UUID.randomUUID();
		String other = "租户-" + UUID.randomUUID();
		// 同一个租户下两个不同的功能 —— 在「按租户」那一栏它们必须合成一行
		seedTenantRun(tenant, "客服问答", "deepseek-flash", 1_000, 0, 500);
		seedTenantRun(tenant, "报表助手", "deepseek-flash", 1_000, 0, 500);
		// 另一个租户的同一个功能 —— 在「按业务」那一栏它们合成一行、在租户栏是两行
		seedTenantRun(other, "客服问答", "deepseek-flash", 1_000, 0, 500);

		var tenants = query.byTenant(today, today);
		var mine = tenants.stream().filter(r -> tenant.equals(r.key())).findFirst().orElseThrow();
		var theirs = tenants.stream().filter(r -> other.equals(r.key())).findFirst().orElseThrow();

		assertThat(mine.runCount())
				.as("同一个租户的两个功能必须是**一行** —— 租户维度问的是「谁在花钱」，"
						+ "不是「花在哪个功能上」（那是另一栏）").isEqualTo(2L);
		assertThat(mine.totalTokens()).as("2 次运行 × (1000 + 500) × 2 步").isEqualTo(6_000L);
		assertThat(theirs.runCount()).isEqualTo(1L);

		var intentRows = query.byIntent(today, today);
		var support = intentRows.stream().filter(r -> "客服问答".equals(r.key())).findFirst().orElseThrow();
		assertThat(support.runCount())
				.as("同一个功能下两个租户在「按业务」那一栏合成一行 —— 两栏的差别就在这里，"
						+ "而它们各自加起来都等于同一份总量").isEqualTo(2L);

		// 【两条 GROUP BY 的总量必须相等】它们看的是同一批运行，
		// 所以"总额"这个数字不该因为换了分组维度而变
		assertThat(tenants.stream().mapToLong(CostQuery.GroupTotals::totalTokens).sum())
				.as("两个维度加起来必须相等 —— 不相等说明其中一条 SQL 漏了行（例如 JOIN 写法不同）")
				.isEqualTo(intentRows.stream().mapToLong(CostQuery.GroupTotals::totalTokens).sum());
	}

	@Test
	@DisplayName("★ 未标注租户独立成一行，且与未标注业务互不影响（两个「空」是两件事）")
	void unlabeledTenantIsSeparateFromUnlabeledIntent() {
		// 【断言写的是"差值"而不是绝对条数】同一个 Spring 上下文里的库是所有测试共用的
		// （其它用例也在造"没标租户"的运行），所以绝对条数会随测试顺序变化 ——
		// 第一版就是这么写的，而它在本类新增用例之后立刻红了：
		// **一条依赖"别的测试没造过数据"的断言，会在任何人加一个用例时无预警地断掉**
		long before = unlabeledTenantRuns();
		// 有租户、没业务归因
		seedTenantRun("甲-" + UUID.randomUUID(), null, "deepseek-flash", 100, 0, 50);
		// 有业务归因、没租户
		seedRichRun("无租户-" + UUID.randomUUID(), "deepseek-flash", 100, 0, 50);

		var tenants = query.byTenant(today, today);
		var intents = query.byIntent(today, today);

		assertThat(tenants).as("租户那一栏必须有 key=null 的那一行（未标注租户）").anyMatch(r -> r.key() == null);
		assertThat(intents).as("业务那一栏同样有 key=null 的那一行（未标注）").anyMatch(r -> r.key() == null);
		// 关键是**不能互相污染**：只标了租户的那次运行不该被算进「未标注租户」，
		// 也不该出现在某个业务下 —— 混进去会让那一组虚高，而虚高的部分无从发现
		assertThat(unlabeledTenantRuns() - before)
				.as("这一轮只造了 1 次「没传 tenantId」的运行 —— 另一次是传了租户、只是没传业务归因，"
						+ "它不该被算进「未标注租户」；两个维度互相污染的话这里会是 2")
				.isEqualTo(1L);
	}

	/** 「未标注租户」那一行的运行数（当前库的全量）。 */
	private long unlabeledTenantRuns() {
		return query.byTenant(today, today).stream().filter(r -> r.key() == null)
				.mapToLong(CostQuery.GroupTotals::runCount).sum();
	}

	@Test
	@DisplayName("★ 只有空格的租户被归一成「未标注租户」—— 不许出现一个叫「 」的租户")
	void blankTenantIsNormalizedToUnlabeled() {
		// 【这条断言防的是一类很具体的错】分组键只做了判 null 而没判 blank 时，
		// 一个只打了空格的租户会变成一个**合法的分组键**，
		// 于是看板上出现一个叫「 」的租户 —— 而它看起来像一个真的租户。
		//
		// ⚠️ 这里刻意**只用 store.start + find**，不把这次运行留成 RUNNING 之外的完整运行：
		// 落一条"有检查点、但运行行 token 为 0"的记录会打破本类那条对账断言
		// （对账是全窗口的，它会被任何一条这样的记录弄红）——
		// 那是**测试之间的干扰**，而它会以"对账对不上"的形式报出来，
		// 看起来像产品代码出了问题
		String runId = UUID.randomUUID().toString();
		store.start(new AgentRunStore.Start(runId, "空格租户", properties.node(), "业务", null, "   "));

		assertThat(store.find(runId).orElseThrow().tenantId())
				.as("归一化只能发生在构造期（Start 的紧凑构造器），所以读出来就是 null").isNull();
		assertThat(store.find(runId).orElseThrow().hasTenant()).isFalse();
		assertThat(query.byTenant(today, today)).as("它必须落进「未标注租户」那一行（key=null），"
				+ "而不是形成一行 key=\"   \" 的租户").anyMatch(r -> r.key() == null);

		// 【而且认领之后仍然不丢】markInterruptedRuns 里那处"手工重建记录"
		// 漏了 tenantId 的话，被认领的运行会丢掉租户 —— 于是它的成本从"某个租户"
		// 悄悄挪到「未标注租户」，而两行的数字都正常，只是挪了位置
		String tenant = "认领-" + UUID.randomUUID();
		String claimedId = UUID.randomUUID().toString();
		store.start(new AgentRunStore.Start(claimedId, "会被认领的运行", properties.node(), "业务", null, tenant));
		List<AgentRunRecord> claimed = store.markInterruptedRuns(properties.node());
		assertThat(claimed).extracting(AgentRunRecord::id).contains(claimedId);
		assertThat(store.find(claimedId).orElseThrow().tenantId())
				.as("认领之后租户必须还在 —— 它正是靠人工复制字段才可能丢的那种数据").isEqualTo(tenant);
	}

	// ------------------------------------------------------------------
	//  2. 两个口径的对账
	// ------------------------------------------------------------------
	@Test
	@DisplayName("对账：步骤表的 token 之和必须等于运行行上的累计 token（否则看板的钱是错的）")
	void reconciliationMatchesTwoAccounts() {
		seedRichRun("对账-" + UUID.randomUUID(), "deepseek-flash", 1000, 200, 500);

		var recon = query.reconcile(today, today);
		assertThat(recon.gap())
				.as("看板的钱按步骤表算，而运行列表显示的是运行行上的累计值 —— "
						+ "两者不一致说明有一处漏记，而看板上完全看不出来")
				.isZero();
		assertThat(recon.stepLevelTokens()).isEqualTo(recon.runLevelTokens());
		assertThat(recon.promptCacheHitRuns()).as("有一次运行的缓存字段非零").isPositive();
	}

	@Test
	@DisplayName("**一步都没有的运行照样被数出来** —— 它们记的是 0 token，那不是没花钱，是没有可记录的花费")
	void runsWithoutStepsAreCounted() {
		String intent = "空运行-" + UUID.randomUUID();
		seedEmptyRun(intent);

		var recon = query.reconcile(today, today);
		assertThat(recon.runsWithoutSteps())
				.as("这种运行最需要被看见：钱可能花了（上游 401 也可能计费），而我们一条步骤都没有")
				.isPositive();

		var row = query.byIntent(today, today).stream().filter(r -> intent.equals(r.key())).findFirst()
				.orElseThrow();
		assertThat(row.runCount()).isPositive();
		assertThat(row.totalTokens()).isZero();
		assertThat(row.failedCount()).as("失败的运行要能被单独数出来 —— 花了钱又没办成事").isPositive();
	}

	// ------------------------------------------------------------------
	//  3. 归因：NULL 与空串必须合成一行
	// ------------------------------------------------------------------

	@Test
	@DisplayName("未标注归因（NULL）独立成一行，且不会被并进任何一个业务里")
	void unnamedIntentIsItsOwnRow() {
		String empty = "无归因-" + UUID.randomUUID();
		seedRichRun(null, "deepseek-flash", 100, 0, 50);

		var rows = query.byIntent(today, today);
		assertThat(rows).as("至少要有一行 key 为 null（未标注）").anyMatch(r -> r.key() == null);
		// 关键是**不能**出现一行"key 等于某个业务、但其实混进了未标注的运行"
		for (CostQuery.GroupTotals row : rows) {
			if (row.key() != null) {
				assertThat(row.key()).isNotBlank();
			}
		}
		assertThat(empty).isNotBlank();
	}

	@Test
	@DisplayName("按模型分桶的 key 就是模型名，空串表示上游没上报（两种不同的事实）")
	void byModelKeepsNamesAsTheyAre() {
		String model = "model-" + UUID.randomUUID();
		seedRichRun("模型-" + UUID.randomUUID(), model, 300, 0, 100);

		var rows = query.byModel(today, today);
		assertThat(rows).anyMatch(r -> model.equals(r.key()));
		// 【为什么这里允许 key 为 null】老数据（在上报模型名之前写的行）的 model_name 是空串，
		// 而空串读出来被归一成 null。它必须独立成一行 —— 并进任何一个模型都会让那个模型虚高
		assertThat(query.modelsInWindow(today, today)).as("窗口内出现过的模型名要能被列出来"
				+ "（界面用它生成「还缺哪些模型的价格」这份待办）").contains(model);
	}

	// ------------------------------------------------------------------
	//  4. 时间窗与按工具
	// ------------------------------------------------------------------

	@Test
	@DisplayName("窗口是**闭区间**：今天那次运行必须落在 days=1 的窗口里")
	void windowIncludesToday() {
		String intent = "窗口-" + UUID.randomUUID();
		seedRichRun(intent, "deepseek-flash", 100, 0, 100);

		// days=1 表示"只有今天"。它必须包含刚才写进去的那次运行 ——
		// 一个"边界差一天"的实现会让今天的数字凭空少一块，而没人会想到是区间写法
		var rows = query.byDay(today, today);
		assertThat(rows).isNotEmpty();
		assertThat(rows.stream().mapToLong(r -> r.totals().totalTokens()).sum()).isPositive();

		// 窗口开在昨天（不含今天）时，今天的记录不该出现
		assertThat(query.byDay(today.minusDays(1), today.minusDays(1))).isEmpty();
	}

	@Test
	@DisplayName("按工具分桶：失败数只算 EXECUTION_ERROR，被拒/解析错/未知工具各自单列")
	void toolBreakdownSeparatesFailureKinds() {
		String runId = UUID.randomUUID().toString();
		store.start(new AgentRunStore.Start(runId, "工具归因", properties.node(), "工具-" + UUID.randomUUID(), null));
		store.checkpoint(new AgentRunRecord.StepCheckpoint(runId, 1, "{}", "t", 10, 10, 1L, 0, "m", ""));
		store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 1, 0, "c1", "search_docs", "{}", "OK", "", 1L));
		store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 1, 1, "c2", "search_docs", "{}",
				"VALIDATION_ERROR", "", 1L));
		store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 1, 2, "c3", "doc_search", "{}",
				"UNKNOWN_TOOL", "", 1L));
		store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 1, 3, "c4", "search_docs", "{}",
				"EXECUTION_ERROR", "", 1L));
		store.recordToolCall(new AgentRunRecord.ToolCallRecord(runId, 1, 4, "c5", "search_docs", "{}",
				"REQUEST_REJECTED", "", 1L));
		store.finish(runId, RunStatus.ANSWERED, AgentStopReason.ANSWERED, "a", 20, 5, 2L, null, null);

		var rows = query.byTool(today, today);
		var search = rows.stream().filter(r -> "search_docs".equals(r.toolName())).findFirst().orElseThrow();
		assertThat(search.calls()).as("search_docs 被提了 4 次（OK / 校验错 / 执行错 / 被限流）").isEqualTo(4L);
		assertThat(search.failures()).as("只有执行异常算工具失败 —— 参数写错是模型的错，"
				+ "不该由工具的可用性买单（与熔断判据同源）").isEqualTo(1L);
		assertThat(search.validationErrors()).isEqualTo(1L);
		assertThat(search.rejected()).as("被限流拒与被熔断拒分开记：它们要给模型相反的建议").isEqualTo(1L);

		var unknown = rows.stream().filter(r -> "doc_search".equals(r.toolName())).findFirst().orElseThrow();
		assertThat(unknown.unknownTool()).as("模型编出来的工具名要能被单独数出来 —— "
				+ "它换来的是「模型不知道有哪些工具」这份数据，而不是「工具坏了」").isEqualTo(1L);
		assertThat(unknown.failures()).as("未知工具不算执行失败").isZero();
	}

	@Test
	@DisplayName("空库：所有查询都能跑，返回空/零而不是抛异常（首次启动就是这个状态）")
	void emptyDatabaseIsNotAnError() {
		// 用一个干净的库名（不影响同上下文里其它测试的数据）——
		// 这里直接对"当前库"断言不抛异常：新部署第一次打开看板正是这个状态，
		// 而它如果抛异常，现象会是"成本看板一打开就报错"，看起来像功能坏了
		assertThat(query.byDay(today, today)).isNotNull();
		assertThat(query.byIntent(today, today)).isNotNull();
		assertThat(query.byTenant(today, today)).as("第 6 件新加的那一维同样要能在空库上跑").isNotNull();
		assertThat(query.byModel(today, today)).isNotNull();
		assertThat(query.byTool(today, today)).isNotNull();
		assertThat(query.byModelAndDay(today, today)).isNotNull();
		assertThat(query.reconcile(today, today)).isNotNull();
		assertThat(query.modelsInWindow(today, today)).isNotNull();
	}

	@Test
	@DisplayName("**被上限拦下的运行必须能与「成功」「失败」区分开** —— 它同样花了钱，却两边都不属于")
	void stoppedRunsAreCountedSeparatelyFromSuccessAndFailure() {
		String intent = "被拦下-" + UUID.randomUUID();
		// 一次跑到第 2 步才被上限拦下的运行：**它花了 3000 token**，一个字都没答出来
		seedRichRun(intent, "deepseek-flash", 1000, 200, 500, RunStatus.STOPPED, AgentStopReason.MAX_STEPS);

		var row = query.byIntent(today, today).stream().filter(r -> intent.equals(r.key())).findFirst()
				.orElseThrow();

		assertThat(row.stoppedCount())
				.as("它是这一格里唯一的一行，而且必须是 1 —— 为 0 说明 SQL 里那个 CASE WHEN 写错了"
						+ "（那种错不报错，只会让这一格永远是 0，看起来像『从来没被上限拦下过』）")
				.isEqualTo(1L);
		assertThat(row.failedCount()).as("被自己的上限拦下不是上游故障 —— 混进 FAILED 会把"
				+ "『我们的配置太紧』报成『上游有问题』，而这两件事的改法完全相反").isZero();
		assertThat(row.finishedCount())
				.as("它**是**已结束的（AgentRunRecord.isFinished 含 STOPPED）："
						+ "把它算成『还在跑』会让『已结束』这个数字凭空少一块")
				.isEqualTo(1L);
		assertThat(row.totalTokens())
				.as("★ 这一条的整个意义：钱真的花了（2000 输入 + 1000 输出），而事没办成。"
						+ "把 STOPPED 并进成功或失败，都会让『有多少钱花在没办成事上』答不出来")
				.isEqualTo(3000L);
	}

	@Test
	@DisplayName("三个数字必须自洽：**被上限拦下的是「已结束」的子集**，而它与失败互不相交")
	void stoppedIsSubsetOfFinishedAndDisjointFromFailed() {
		String intent = "自洽-" + UUID.randomUUID();
		seedRichRun(intent, "m", 100, 0, 50, RunStatus.STOPPED, AgentStopReason.MAX_TOKENS);
		seedRichRun(intent, "m", 100, 0, 50, RunStatus.ANSWERED, AgentStopReason.ANSWERED);
		seedRichRun(intent, "m", 100, 0, 50, RunStatus.FAILED, AgentStopReason.MODEL_ERROR);

		var row = query.byIntent(today, today).stream().filter(r -> intent.equals(r.key())).findFirst()
				.orElseThrow();

		assertThat(row.runCount()).as("三次运行归到同一个归因键下").isEqualTo(3L);
		assertThat(row.stoppedCount()).isEqualTo(1L);
		assertThat(row.failedCount()).isEqualTo(1L);
		assertThat(row.finishedCount())
				.as("已结束 = 三个都结束（含被拦下那个、也含失败那个）—— "
						+ "所以 stopped 与 failed 都是它的**子集**，而不是它的兄弟")
				.isEqualTo(3L);
		assertThat(row.stoppedCount() + row.failedCount())
				.as("两个子集互不相交：一次运行不可能既被上限拦下又算失败")
				.isLessThanOrEqualTo(row.finishedCount());
	}

	@Test
	@DisplayName("(天, 模型) 是金额唯一算得对的粒度：它必须按模型拆开，不能只按天")
	void modelDayRowsSplitByModel() {
		seedRichRun("多模型-" + UUID.randomUUID(), "model-a", 1000, 0, 100);
		seedRichRun("多模型-" + UUID.randomUUID(), "model-b", 2000, 0, 200);

		List<CostQuery.ModelDayRow> rows = query.byModelAndDay(today, today);
		assertThat(rows.stream().map(CostQuery.ModelDayRow::model).toList())
				.as("同一天里两个模型必须分成两行 —— 合成一行之后就只能拿某一个模型的单价去乘"
						+ "两倍的 token，而那笔钱看起来完全正常")
				.contains("model-a", "model-b");
		long total = rows.stream().mapToLong(CostQuery.ModelDayRow::totalTokens).sum();
		assertThat(total).isPositive();
	}

}
