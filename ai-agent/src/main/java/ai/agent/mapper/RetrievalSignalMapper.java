package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * {@code retrieval_call} 表上的两个告警信号
 * （原先手写在 {@code JdbcAlertMetricsQuery} 里）。
 *
 * <p>为什么只有这两个方法：它们服务的是"检索到底有没有在用"这条判据
 * （连续空命中 + 索引大小），而表本身属于 trace 那一层，写侧在 {@code AgentRunMapper} 里。
 * 放在这里是因为**读的人不同**：告警要的是"最近的趋势"，trace 要的是"某一次运行看到了什么"。
 */
@Mapper
public interface RetrievalSignalMapper {

	/**
	 * 最近 N 次检索的命中数与当时的索引大小，<b>按时间倒序</b>。
	 *
	 * <p>倒序是有用途的：调用方只需要扫一遍就能数出"从最新往前连续几次空命中"。
	 * 所以这里不做聚合 —— 聚合掉之后"连续"这个概念就没法表达了。
	 *
	 * <p>⚠️ {@code LIMIT} 用参数绑定，不再拼字符串。
	 * 返回值是"数据库行"（{@link MapperRows.RetrievalSignalRow}）：两列都可空，
	 * 拆箱与兜底在 store 侧做（理由见 {@link MapperRows} 的类注释）。
	 */
	List<MapperRows.RetrievalSignalRow> recentSignals(@Param("since") Instant since, @Param("limit") int limit);

	/** 窗口内的检索总次数（判据明细里要能看出"3 次是 3 次里的，还是 300 次里的"）。 */
	long countSince(@Param("since") Instant since);

}
