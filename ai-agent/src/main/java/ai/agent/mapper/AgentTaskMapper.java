package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_task} 表的 SQL。
 *
 * <h2>为什么没有 delete</h2>
 * 任务只做"已取消"，不删除（见 {@code db/task-schema.sql} 的头注）：
 * 删掉一行会让"当时为什么取消"失去主语。方法不存在，调用方就没有"顺手删一下"的机会。
 *
 * <h2>★ {@link #insert} 之后由 {@link #lastInsertId()} 显式取新 id</h2>
 * 与 {@link AccountMapper} 逐条相同：MyBatis 的三条"自动回填"路（{@code useGeneratedKeys} /
 * {@code @SelectKey} 的两种写法）在本项目的驱动组合上都不通，
 * 且失效时**不抛异常、只留一个 null** —— 那会让"建完任务紧接着写确认项"报成
 * "任务不存在"，而真正的原因是取键没生效。代价是调用方必须紧接着调一次。
 *
 * <h2>为什么三个 update 分开写，而不是一个"更新整行"</h2>
 * 因为它们的触发时机不同，而合成一条的写法有个固定的坏法：
 * 状态推进时把 {@code result} / {@code cancel_reason} 一并写成 null ——
 * 一次"验收通过"会顺手把之前登记的执行结果抹掉，且不报错。
 * 每条 SQL 只碰自己那几列，这个可能就不存在。
 */
@Mapper
public interface AgentTaskMapper {

	/** 建任务。返回受影响行数；新 id 用 {@link #lastInsertId()} 取。 */
	int insert(@Param("publisher") String publisher, @Param("assignee") String assignee,
			@Param("description") String description, @Param("status") String status,
			@Param("currentExecutor") String currentExecutor, @Param("currentStep") int currentStep,
			@Param("agentId") Long agentId);

	/**
	 * 刚插入那一行的自增主键。
	 *
	 * <p><b>必须在同一个事务里紧接着插入调用</b>：它读的是当前连接的 {@code LAST_INSERT_ID()}，
	 * 换一条连接就变成 0（实测新连接上恒为 0）。
	 */
	Long lastInsertId();

	MapperRows.AgentTaskRow findById(@Param("id") Long id);

	/**
	 * 全部任务（看板一次取全量）。
	 *
	 * <p>没有分页参数：这一页按列分组画卡片，分页会让"某一列里还有没有卡片"取决于翻到第几页。
	 * 任务表是**人手工发起**的规模（不是运行记录那种按请求增长的量），
	 * 真长到需要分页时，该改的是这一页的形态（按列分页），而不是给它加个 offset。
	 *
	 * <p>排序：最近变更在前（同秒的用 id 倒序兜底 —— {@code updated_at} 是秒精度）。
	 */
	List<MapperRows.AgentTaskRow> list();

	/**
	 * 推进状态：状态、当前执行人、当前步骤三列一起换。
	 *
	 * <p>它们必须是同一条 SQL：拆成三条会有"状态已经变了、执行人还没变"的中间态，
	 * 而那时界面若正好刷新，看到的是"待确认需求，但执行人还是任务人"——
	 * 一张卡片的两个字段各说一套，没人能从界面上看出哪一个是旧的。
	 */
	int updateProgress(@Param("id") Long id, @Param("status") String status,
			@Param("currentExecutor") String currentExecutor, @Param("currentStep") int currentStep);

	/** 登记执行结果（{@code EXECUTING → 待验收} 那一步）。 */
	int updateResult(@Param("id") Long id, @Param("result") String result);

	/** 取消：状态与取消原因同一次写入（写原因单独一条 SQL 会留下"取消了但没写原因"的窗口）。 */
	int updateCancel(@Param("id") Long id, @Param("cancelReason") String cancelReason);

}
