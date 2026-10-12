package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_task_schedule} 表的 SQL（应用管理区 · 定时任务）。
 *
 * <h2>★ 为什么这里<b>有</b> delete，而 {@link AgentTaskMapper} 刻意没有</h2>
 * 两张表是两类东西：{@code agent_task} 记的是"一件事"（取消之后要留下"当时为什么取消"），
 * 而这张表记的是"一条配置" —— 停用（{@code enabled=0}）就是它的"取消"。
 * 配置可以删：它不承载历史，删掉之后"这条规则存在过"这件事没有复盘价值。
 *
 * <p>⚠️ 但**已经派出去的任务不随规则消失**：{@code agent_task} 与这张表之间没有外键
 * （见 {@code db/schedule-schema.sql} 的头注），所以删规则不会连带删任务 ——
 * 这正是把外键留空的那条理由。调用方（{@code DefaultScheduleService#delete}）
 * 因此在删之前先拦一道"它已经派过 N 条任务"，理由是"那些任务还在，
 * 而这条规则是它们唯一的由来"。
 *
 * <h2>★ {@link #insert} 之后由 {@link #lastInsertId()} 显式取新 id</h2>
 * 与 {@link AgentTaskMapper} / {@link AgentSkillMapper} 逐条相同：MyBatis 的三条"自动回填"路
 * （{@code useGeneratedKeys} / {@code @SelectKey} 的两种写法）在本项目的驱动组合上都不通，
 * 且失效时**不抛异常、只留一个 null**。代价是调用方必须紧接着调一次，
 * 而且必须在同一个事务里（{@code LAST_INSERT_ID()} 是连接级的）。
 *
 * <h2>为什么 {@link #update} 一条 SQL 写全部可改列，而不是每个字段一条</h2>
 * 与 {@link AgentTaskMapper} 那条"每个 update 分开写"<b>不矛盾</b>，判据是"这几列会不会被
 * 分开改"：任务那边状态推进 / 结果登记 / 取消原因各有各的触发时机，合起来写就会用旧值
 * 覆盖新值；而规则的四个可改列（cron / 任务人 / 描述 / 启停）在界面上<b>只有两个入口</b>
 * ——"改规则"与"启停"，而启停那一个只有 {@code enabled} 在变（走 {@link #updateEnabled}）。
 * 于是一条 {@link #update} 覆盖"重新配置"这一件事，语义干净：调用方提交的是
 * "这条规则现在应该是什么样"，而不是"改哪一列"。
 */
@Mapper
public interface AgentTaskScheduleMapper {

	/**
	 * 建规则。返回受影响行数；新 id 用 {@link #lastInsertId()} 取。
	 *
	 * <p>发布人 / 任务人传的是<b>用户 id</b>（{@code agent_user.id}），姓名不落库 ——
	 * 读侧关联查询出来（见 {@code AgentTaskScheduleMapper.xml}）。
	 * 这两个 id 由服务层先核一遍账号表（见 {@code DefaultAgentTaskScheduleService#realUser}）：
	 * 这张表的 insert 不会因为一个人不存在而拒绝。
	 */
	int insert(@Param("cron") String cron, @Param("publisherId") Long publisherId,
			@Param("assigneeId") Long assigneeId, @Param("description") String description,
			@Param("enabled") boolean enabled);

	/**
	 * 刚插入那一行的自增主键。
	 *
	 * <p><b>必须在同一个事务里紧接着插入调用</b>：它读的是当前连接的 {@code LAST_INSERT_ID()}，
	 * 换一条连接就变成 0。
	 */
	Long lastInsertId();

	MapperRows.AgentTaskScheduleRow findById(@Param("id") Long id);

	/**
	 * 全部规则（列表一次取全量）。
	 *
	 * <p>没有分页参数：这一页按人手工配规则的规模长，而"哪几条规则现在会跑"
	 * 是一个要一眼看全的问题 —— 分页会让答案取决于翻到第几页
	 * （与 {@code AgentTaskMapper#list} 同一条理由）。
	 *
	 * <p>排序：最近变更在前（同秒的用 id 倒序兜底 —— {@code updated_at} 是秒精度）。
	 */
	List<MapperRows.AgentTaskScheduleRow> list();

	/**
	 * <b>要装进调度线程池的那些规则</b>（启动时与每次写入之后都要这一份）。
	 *
	 * <p>排序按 id 升序：装线程的顺序要稳定，否则同一批规则在两次启动/两次刷新之后
	 * 落到线程池里的顺序不同，"哪一条占着线程"这件事每次都变 —— 出问题时对不上。
	 *
	 * <p>{@code enabled} 的判据做在 SQL 里而不是取回来再过滤：取回来再过滤的话，
	 * "停用的规则不占线程"这句话就成了调用方的自觉，而漏掉那一步的表现是
	 * <b>停用了却还在跑</b>（不报错）。
	 */
	List<MapperRows.AgentTaskScheduleRow> listEnabled();

	/** 一次规则改动的完整落库（cron / 任务人 / 描述 / 启停四列一起写）。 */
	int update(@Param("id") Long id, @Param("cron") String cron, @Param("assigneeId") Long assigneeId,
			@Param("description") String description, @Param("enabled") boolean enabled);

	/** 只改启停那一格（界面上那个开关）。 */
	int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

	/**
	 * 删掉一条规则。**不碰它派出去的那些任务**（见类注释）。
	 *
	 * <p>返回受影响行数：调用方据此判断"这一行还在不在"，
	 * 而不是先查一次再删（那两次之间别人可能已经删掉了）。
	 */
	int delete(@Param("id") Long id);

}
