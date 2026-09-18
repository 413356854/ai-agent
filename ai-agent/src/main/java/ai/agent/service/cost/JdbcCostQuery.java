package ai.agent.service.cost;

import ai.agent.mapper.CostMapper;
import ai.agent.mapper.MapperRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link CostQuery} 的存储实现 —— <b>SQL 在 {@link CostMapper} 里，本类只管窗口与兜底</b>。
 *
 * <h2>迁移后这个类还剩什么</h2>
 * 原先 439 行里，绝大部分是"绑定参数 → 遍历结果 → 逐列读取"的样板，以及
 * 那份**位置绑定**的契约（6 个 {@code ?} 分散在 4 个 {@code CASE WHEN} 与 1 个 {@code stage = ?} 里，
 * 绑错一位只会让"被上限拦下"与"失败"两个数字悄悄互换，而两个数字看起来都正常）。
 * 现在 SQL 与它的参数名在 XML 里成对出现，本类只剩三件事：
 * <ol>
 *   <li><b>时间窗的上界</b>：{@code to.plusDays(1)}。界面上给的是"到哪一天"，
 *       而 SQL 要的是"小于次日 0 点"。用 {@code <= 当天末} 会漏掉 23:59:59.5 这种带毫秒的时间，
 *       而漏掉的那条会让"今天"的数字比运行列表少一条 —— 一种只会让人怀疑数据的差异；</li>
 *   <li><b>对账的零值兜底</b>：窗口内一条运行都没有时那条聚合查询返回一行全 0，
 *       但"返回 null"也必须被读成 0，而不是 NPE；</li>
 *   <li><b>异常包装</b>：把 {@code DataAccessException} 转成明确说"这是看板查询失败"的异常。
 *       不吞是刻意的：看板报错比看板显示一个偏小的数字好得多 ——
 *       一个"部分数据"的成本表会被当成真的用。</li>
 * </ol>
 *
 * <h2>连接池不再由本类持有</h2>
 * 原先它通过构造器拿 {@code JdbcAgentRunStore} 来借池（顺便表达"建表要先发生"这个顺序依赖）。
 * 现在连接来自全应用唯一的 {@code DataSource}、表由 {@code spring.sql.init} 建，
 * 所以那个构造参数已删除 —— <b>顺序依赖不再需要靠构造参数表达</b>。
 */
@Component
public class JdbcCostQuery implements CostQuery {

	private static final Logger log = LoggerFactory.getLogger(JdbcCostQuery.class);

	private final CostMapper mapper;

	public JdbcCostQuery(CostMapper mapper) {
		this.mapper = mapper;
		log.info("成本看板查询就绪（SQL 在 CostMapper.xml；连接来自全应用唯一的 DataSource）");
	}

	// ------------------------------------------------------------------
	//  按天 / 按维度
	// ------------------------------------------------------------------

	@Override
	public List<DayTotals> byDay(LocalDate from, LocalDate to) {
		return read(() -> mapper.byDay(from, exclusiveEnd(to)).stream().map(MapperRows.DayTotalsRow::toDomain).toList(), "按天汇总成本");
	}

	@Override
	public List<GroupTotals> byIntent(LocalDate from, LocalDate to) {
		return read(() -> mapper.byIntent(from, exclusiveEnd(to)).stream().map(MapperRows.GroupTotalsRow::toDomain).toList(), "按业务归因汇总成本");
	}

	@Override
	public List<GroupTotals> byTenant(LocalDate from, LocalDate to) {
		return read(() -> mapper.byTenant(from, exclusiveEnd(to)).stream().map(MapperRows.GroupTotalsRow::toDomain).toList(), "按租户归因汇总成本");
	}

	@Override
	public List<GroupTotals> byModel(LocalDate from, LocalDate to) {
		return read(() -> mapper.byModel(from, exclusiveEnd(to)).stream().map(MapperRows.GroupTotalsRow::toDomain).toList(), "按模型汇总成本");
	}

	@Override
	public List<ModelDayRow> byModelAndDay(LocalDate from, LocalDate to) {
		return read(() -> mapper.byModelAndDay(from, exclusiveEnd(to)).stream().map(MapperRows.ModelDayRow::toDomain).toList(), "按天+模型汇总成本");
	}

	@Override
	public List<ToolTotals> byTool(LocalDate from, LocalDate to) {
		return read(() -> mapper.byTool(from, exclusiveEnd(to)).stream().map(MapperRows.ToolTotalsRow::toDomain).toList(), "按工具汇总调用");
	}

	@Override
	public Reconciliation reconcile(LocalDate from, LocalDate to) {
		MapperRows.ReconciliationRow row = read(() -> mapper.reconcile(from, exclusiveEnd(to)), "成本对账");
		// 一个聚合行都没有时（窗口内没有任何运行）读成全 0：调用方拿到的是"这个窗口很干净"，
		// 而不是一个 null —— 后者会以 NPE 的形式出现在很远的地方
		return row == null ? new Reconciliation(0, 0, 0, 0, 0, 0, 0) : row.toDomain();
	}

	@Override
	public List<String> modelsInWindow(LocalDate from, LocalDate to) {
		return read(() -> mapper.modelsInWindow(from, exclusiveEnd(to)), "窗口内出现过的模型");
	}

	// ------------------------------------------------------------------
	//  公共部分
	// ------------------------------------------------------------------

	/** 排他上界（次日 0 点）。只在这里算一次：六条查询共用同一个窗口语义。 */
	private static LocalDate exclusiveEnd(LocalDate to) {
		return to.plusDays(1);
	}

	private <T> T read(Supplier<T> call, String what) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new IllegalStateException("成本看板查询失败（" + what + "）：" + e.getMessage(), e);
		}
	}

}

