package ai.agent.service.cost;

import ai.agent.entity.agent.AgentRun;
import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.service.agent.store.RunStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 成本看板的**取数契约**（阶段四）。
 *
 * <h2>为什么这些查询不放在 {@code AgentRunStore} 里</h2>
 * 因为它们的形态与那个接口完全不同：{@code AgentRunStore} 是**过程存储**
 * （写检查点、读检查点、恢复运行），每一次调用都围绕"某一次运行"；
 * 而这里是**聚合查询**，回答的是"这一批运行一共花了多少"。
 * 混在一起会让那个接口从"恢复一个运行需要什么"变成"什么东西都可能塞进来"。
 *
 * <h2>为什么聚合放在 SQL 里，而不是"把行都读进内存再算"</h2>
 * 这一条是看板能不能上生产的分界线：运行表是按天增长的，而看板的每一次刷新
 * 都要扫一个时间窗。全部读进内存的写法在本地几十条记录时完全没问题，
 * 上线几个月之后会变成"这个页面越开越慢、还吃内存" —— 而那时**没有报错**，
 * 只有慢。{@code GROUP BY} 让数据库只回几十行。
 *
 * <h2>为什么每个方法都带一个"分组维度"</h2>
 * 路线文档给成本看板的验收写的是「**成本归因**（按业务/租户/功能拆分）」——
 * 也就是说，"总共花了多少"是一个数字，而"**谁花的**"才是那个功能。
 * <b>五个维度各一个查询</b>，而不是一个"大而全"的查询：它们的粒度不同
 * （按天、按归因键、按租户、按模型、按工具），硬塞进一条 SQL 会得到一个行数被笛卡尔积
 * 放大的结果，而那种错在页面上看起来只是"数字偏大一点"。
 */
public interface CostQuery {

	/**
	 * 按天分桶。
	 *
	 * @param from 起点（含）
	 * @param to 终点（含）
	 */
	List<DayTotals> byDay(LocalDate from, LocalDate to);

	/**
	 * 按业务归因键分桶（路线文档的「按业务拆分」）。
	 *
	 * <p>返回的 {@link GroupTotals#key()} 为 {@code null} 的那一行表示"未标注归因" ——
	 * 它<b>必须独立成一行</b>，不能被并进任何一个业务里：并进去会让那个业务的成本虚高，
	 * 而虚高的部分无从发现。
	 */
	List<GroupTotals> byIntent(LocalDate from, LocalDate to);

	/**
	 * 按<b>租户</b>分桶（路线文档的「按租户拆分」，阶段四第 6 件）。
	 *
	 * <h2>为什么它是独立的一维，而不是"归因键的一种取值"</h2>
	 * 路线文档的原话是「成本归因（按<b>业务 / 租户 / 功能</b>拆分）」，读起来像
	 * 一个字段存三样东西。但把租户拼进 {@code intent}（{@code 京东-客服问答}）
	 * 有两个具体后果，而两个都不报错：
	 * <ol>
	 *   <li><b>"按租户看一共多少"要退化成前缀匹配</b>（{@code LIKE '京东-%'}）——
	 *       一次拼写不一致（多一个空格、大小写不同）就让同一个租户裂成两行，
	 *       而两行加起来才是真相，这种事没人会去加；</li>
	 *   <li><b>两个维度互相污染</b>：租户改名、或一个问题换业务归属，
	 *       都要去改历史数据 —— 而历史只追加（与 Prompt 版本管理同一条纪律）。</li>
	 * </ol>
	 * 所以它是另一列、另一条 GROUP BY。{@code (租户, 归因键)} 的组合才是"谁在哪个功能上花了多少"。
	 *
	 * <p>返回的 {@link GroupTotals#key()} 为 {@code null} 的那一行表示"未标注租户" ——
	 * 与 {@link #byIntent} 同一条规矩：它<b>必须独立成一行</b>，
	 * 不能被并进任何一个租户里（那会让那个租户的成本虚高，而虚高的部分无从发现）。
	 */
	List<GroupTotals> byTenant(LocalDate from, LocalDate to);

	/** 按模型分桶（口径来自**每一步**的记录，所以换模型的那个运行里两步会落在两个模型上）。 */
	List<GroupTotals> byModel(LocalDate from, LocalDate to);

	/**
	 * 按 (天, 模型) 两维分桶 —— <b>这是"按天的金额"唯一算得对的粒度</b>。
	 *
	 * <h2>为什么必须有它，而不能用"当天的总 token × 某个单价"</h2>
	 * 一天里可能跑过多个模型，而每个模型的单价不同。把当天的 token 加总之后乘任意一个单价
	 * 都是错的，而错出来的钱数**看起来完全正常**（只是偏大或偏小），不会报错。
	 * 有了 (天, 模型) 这个粒度，每一天的金额就能"按模型分别算完再相加"——
	 * 那是唯一有确切单价的粒度。
	 *
	 * <p>行数上界是"天 × 模型数"（默认窗口 7 天、模型通常个位数），
	 * 所以这条查询不会随数据量增长 —— 它随**配置里的模型数**增长。
	 */
	List<ModelDayRow> byModelAndDay(LocalDate from, LocalDate to);

	/**
	 * 按工具分桶。
	 *
	 * <p>⚠️ <b>这一维没有钱</b>，而且这是刻意的：token 花在"模型往返"上，
	 * 工具本身不直接消耗 token（它消耗的是延迟与下游容量）。
	 * 一个工具"花了多少钱"只能靠"它让模型多跑了几轮"来间接归因，
	 * 而那是**推测**，不是数据。所以这里给的是工具调用的次数、失败数、耗时 ——
	 * 它们是事实，而且它们正是"哪把工具在拖慢任务"的答案。
	 */
	List<ToolTotals> byTool(LocalDate from, LocalDate to);

	/**
	 * 对账：每一步的 token 之和 vs 运行行上的累计 token。
	 *
	 * <h2>为什么要专门查这个（这是整个看板最值钱的一条断言）</h2>
	 * 看板的每一个钱数都是从**步骤表**汇总出来的，而 {@code AgentRun.totalTokens} 是
	 * 循环自己累加的**另一个**口径（它决定 token 上限，也显示在运行列表上）。
	 * 两个口径如果对不上，说明其中一处漏记了 —— 而看板上完全看不出来：
	 * 只是钱比真实值少一点，或者多一点。
	 *
	 * <p>这一条与阶段二那次"对账上就发现差值表把 token 成本夸大了 100 倍"是同一类做法：
	 * <b>把两个本该一致的量并排摆出来</b>，让不一致自己现形，
	 * 而不是等有人拿着看板去和账单对。
	 */
	Reconciliation reconcile(LocalDate from, LocalDate to);

	/** 窗口内出现过哪些模型名（包括 {@code ''} 表示"上游没上报"）。 */
	List<String> modelsInWindow(LocalDate from, LocalDate to);

	// ------------------------------------------------------------------
	//  记录类型
	// ------------------------------------------------------------------

	/**
	 * 一组聚合结果（按天 / 按业务 / 按模型共用）。
	 *
	 * @param key 分组的键；品种含义由调用它的那个方法决定（日期串 / 归因键 / 模型名）。
	 *            {@code null} 表示"这一组没有键"（未标注归因 / 未上报模型）
	 * @param runCount 运行数
	 * @param finishedCount 其中已结束的（RUNNING 的那些还没跑完，成本还会涨）
	 * @param stoppedCount 其中被<b>上限</b>拦下的（{@code status=STOPPED}：步数 / token / 时长 /
	 *            重复动作 / 空回答 —— 见 {@code AgentRunStore} 的映射）。
	 *            <p>它<b>是</b> {@code finishedCount} 的一部分（{@code AgentRunRecord.isFinished()}
	 *            把它算作已结束），单独给一个数字是因为它回答的是一个
	 *            {@code finishedCount} 与 {@code failedCount} 都回答不了的问题：
	 *            <b>"有多少钱花在没办成事上"</b>。它既不是成功（没有答案），
	 *            也不是失败（没有报错）—— 把它并进任何一边都会让那边的数字说谎，
	 *            而它在账单上完全看不出来（只多了一笔钱）。
	 * @param failedCount 其中失败的（status=FAILED）——
	 *            单独给一个数字，因为"花了钱又没办成事"是成本看板上最该被看见的一格
	 * @param promptTokens 输入 token 合计（**含**命中缓存的部分）
	 * @param cachedPromptTokens 其中命中提示词缓存的部分（{@code promptTokens} 的子集）
	 * @param completionTokens 输出 token 合计
	 * @param toolCalls 工具调用次数（含被拒绝的）
	 * @param toolFailures 其中算作工具失败的（口径来自 {@code ToolCallStage}，在 SQL 里按
	 *            {@code stage='EXECUTION_ERROR'} 判 —— 与 {@code CircuitBreaker} 的判据同源）
	 * @param toolElapsedMs 工具侧耗时合计
	 * @param elapsedMs 运行耗时合计
	 * @param firstAt 这一组最早一次运行的时间（可能为 null）
	 */
	record GroupTotals(String key, long runCount, long finishedCount, long stoppedCount, long failedCount,
			long promptTokens,
			long cachedPromptTokens, long completionTokens, long toolCalls, long toolFailures, long toolElapsedMs,
			long elapsedMs, java.time.Instant firstAt) {

		/** 输出 + 输入 = 总量（"total" 这个口径到处都在用，所以只在这里定义一次）。 */
		public long totalTokens() {
			return promptTokens + completionTokens;
		}

		/** 未命中缓存的输入 token。 */
		public long uncachedPromptTokens() {
			return Math.max(0, promptTokens - cachedPromptTokens);
		}
	}

	/** 按天分桶的一行。 */
	record DayTotals(LocalDate day, GroupTotals totals) {
	}

	/**
	 * (天, 模型) 的一行 —— 金额能被正确计算的**最小粒度**。
	 *
	 * @param day 哪一天（按运行创建时间）
	 * @param model 哪个模型（空串 = 上游没上报模型名）
	 * @param promptTokens 输入 token（含命中缓存的部分）
	 * @param cachedPromptTokens 其中命中缓存的部分
	 * @param completionTokens 输出 token
	 */
	record ModelDayRow(LocalDate day, String model, long promptTokens, long cachedPromptTokens,
			long completionTokens) {

		public long uncachedPromptTokens() {
			return Math.max(0, promptTokens - cachedPromptTokens);
		}

		public long totalTokens() {
			return promptTokens + completionTokens;
		}
	}

	/** 按工具分桶的一行。 */
	record ToolTotals(String toolName, long calls, long failures, long unknownTool, long validationErrors,
			long parseErrors, long rejected, long circuitOpen, long elapsedMs, long runs) {

		/** 调用成功率。注意分母是"模型提出的调用次数"（含被拒的）—— 与报告口径一致。 */
		public double successRate() {
			return calls == 0 ? 0.0 : (double) (calls - failures) / calls;
		}
	}

	/**
	 * 对账结果。
	 *
	 * @param runLevelTokens 运行行上的 token 合计（{@code agent_run.total_tokens}）
	 * @param stepLevelTokens 步骤表里的 token 合计（看板的钱数就是按它算的）
	 * @param runsWithoutSteps 窗口内"有运行行、但一个步骤都没有"的次数 ——
	 *            它们是最可疑的一类：花钱了吗？没有任何步骤能证明
	 * @param promptCacheHitRuns 至少有一次缓存命中的运行数（衡量缓存到底有没有起作用）
	 */
	record Reconciliation(long runLevelTokens, long stepLevelTokens, long runsWithoutSteps, long runs,
			long promptCacheHitRuns, long promptTokens, long cachedPromptTokens) {

		/** 两个口径的差（步骤口径 − 运行口径）。0 才算对上了。 */
		public long gap() {
			return stepLevelTokens - runLevelTokens;
		}
	}

	/**
	 * 用到的枚举名，写成常量以免各处拼字符串时手滑。
	 *
	 * <p>拼错一个枚举名在这类统计里不会报错：{@code WHERE stage='EXECUTION_ERRROR'} 只是
	 * 恒不成立，于是"工具失败数"永远是 0 —— 一个看起来"工具从来没坏过"的健康报告。
	 * 写成常量至少让拼错只可能发生在这一处。
	 */
	final class Names {

		private Names() {
		}

		public static final String RUNNING = RunStatus.RUNNING.name();

		public static final String FAILED = RunStatus.FAILED.name();

		/**
		 * 被上限拦下的那些（步数 / token / 时长 / 重复动作 / 空回答）。
		 *
		 * <p>它同样"花了钱"，但既不是成功也不是失败 —— 所以它必须从 {@code finished_count}
		 * 里被单独数出来（成本看板要能回答"有多少钱花在没办成事上"）。
		 */
		public static final String STOPPED = RunStatus.STOPPED.name();

		public static final String EXECUTION_ERROR = ai.agent.service.tool.ToolCallStage.EXECUTION_ERROR.name();

		public static final String UNKNOWN_TOOL = ai.agent.service.tool.ToolCallStage.UNKNOWN_TOOL.name();

		public static final String PARSE_ERROR = ai.agent.service.tool.ToolCallStage.PARSE_ERROR.name();

		public static final String VALIDATION_ERROR = ai.agent.service.tool.ToolCallStage.VALIDATION_ERROR.name();

		public static final String REQUEST_REJECTED = ai.agent.service.tool.ToolCallStage.REQUEST_REJECTED.name();

		public static final String CIRCUIT_OPEN = ai.agent.service.tool.ToolCallStage.CIRCUIT_OPEN.name();

	}

}
