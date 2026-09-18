package ai.agent.mapper;

import ai.agent.service.cost.CostQuery;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 成本看板 / 告警看板的全部聚合 SQL（原先手写在 {@code JdbcCostQuery} 里）。
 *
 * <h2>这些查询为什么必须手写 SQL</h2>
 * 它们是多层 {@code LEFT JOIN} 子查询 + {@code CASE WHEN} + {@code GROUP BY} 的报表查询
 * （按天 / 按业务 / 按租户 / 按模型 / 按工具 / 对账）。
 * 任何"条件构造器 + 分页 + 单表 CRUD"型的 ORM 工具在这些语句上一条都用不上 ——
 * 这正是选 MyBatis（而不是带 CRUD 生成器的增强版）的直接原因之一。
 *
 * <h2>SQL 里刻意不出现计价参数</h2>
 * 这些查询只回<b>token 与次数的合计</b>，一行钱都不算。换算由 {@code CostPricing} 做。
 * 两个理由：① 单价是 {@code BigDecimal}，塞进 SQL 会变成浮点参数，误差直接进钱数；
 * ② 把"聚合"与"计价"分开之后，计价那一半可以被逐条断言（而 SQL 里的算术不能）。
 *
 * <h2>参数为什么是枚举而不是字符串</h2>
 * 原先的手写代码里，状态与阶段是 {@code CostQuery.Names} 那些 String 常量，
 * 绑定靠 {@code setString(1, ...)} 的位置。改用枚举之后，"这个位置该是哪个状态"
 * 由<b>方法签名</b>说了算 —— 位置绑错的整类错误在编译期就没了。
 */
@Mapper
public interface CostMapper {

	/** 按天分桶（13 个聚合列）。 */
	List<MapperRows.DayTotalsRow> byDay(@Param("from") LocalDate from, @Param("to") LocalDate to);

	List<MapperRows.GroupTotalsRow> byIntent(@Param("from") LocalDate from, @Param("to") LocalDate to);

	List<MapperRows.GroupTotalsRow> byTenant(@Param("from") LocalDate from, @Param("to") LocalDate to);

	List<MapperRows.GroupTotalsRow> byModel(@Param("from") LocalDate from, @Param("to") LocalDate to);

	List<MapperRows.ModelDayRow> byModelAndDay(@Param("from") LocalDate from, @Param("to") LocalDate to);

	List<MapperRows.ToolTotalsRow> byTool(@Param("from") LocalDate from, @Param("to") LocalDate to);

	/**
	 * 对账：两个 token 口径并排摆出来。
	 *
	 * <p>返回<b>可能为 null</b>（一个聚合行都没有时）—— 调用方负责把它读成全 0。
	 * 这一点不能靠"永远不会空"来假设：窗口内一条运行都没有时它就空。
	 */
	MapperRows.ReconciliationRow reconcile(@Param("from") LocalDate from, @Param("to") LocalDate to);

	List<String> modelsInWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);

}

