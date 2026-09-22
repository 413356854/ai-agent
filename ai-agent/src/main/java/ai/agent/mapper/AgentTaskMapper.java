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

	/**
	 * 建任务。返回受影响行数；新 id 用 {@link #lastInsertId()} 取。
	 *
	 * <p>发布人 / 任务人传的是<b>用户 id</b>（{@code agent_user.id}），姓名不落库 ——
	 * 读侧关联查询出来（见 {@code AgentTaskMapper.xml}）。
	 *
	 * <p><b>不写 {@code agent_id}</b>：发布人不填那一列，它由平台在第一次接手这条任务时
	 * 用 {@link #updateAgentId} 写入（见 {@code AgentTaskProperties#agentId}）。
	 */
	int insert(@Param("publisherId") Long publisherId, @Param("assigneeId") Long assigneeId,
			@Param("description") String description, @Param("status") String status,
			@Param("currentExecutorId") Long currentExecutorId, @Param("currentStep") int currentStep);

	/**
	 * 刚插入那一行的自增主键。
	 *
	 * <p><b>必须在同一个事务里紧接着插入调用</b>：它读的是当前连接的 {@code LAST_INSERT_ID()}，
	 * 换一条连接就变成 0（实测新连接上恒为 0）。
	 */
	Long lastInsertId();

	MapperRows.AgentTaskRow findById(@Param("id") Long id);

	/**
	 * <b>下一条该由 agent 处理的任务</b>：状态在 {@code statuses} 里，按变更时间从早到晚取第一条。
	 *
	 * <h2>为什么一次只取一条，而不是把待办一次取回来</h2>
	 * 任务执行是<b>串行</b>的（一条跑完才轮到下一条，见 {@code AgentTaskScheduler}）。
	 * 一次取一批的写法会让"这一批里每条现在是什么状态"在开始执行时就被定死，
	 * 而执行本身会改状态、也可能改别的任务 —— 于是后面几条要按一份过期的快照去跑。
	 * 一次一条则每条开跑前重新读一次，读到的一定是最新的状态。
	 *
	 * <h2>为什么是 updated_at 正序</h2>
	 * 先变更的先处理（FIFO）：倒序会让一条老任务在持续有新任务进来时永远排不上，
	 * 而"它明明在待办里却一直不动"是这一类队列最难查的一种坏法。
	 * 撞上同一秒的用 id 兜底（{@code updated_at} 是秒精度）。
	 *
	 * @param statuses 库里存的状态名（{@link ai.agent.entity.agent.task.AgentTaskStatus#storedValue()}）；
	 *            清单由 {@code AgentTaskStatus.awaitingAgentStatuses()} 给出，不在这里硬编码
	 * @param excluded 不要再取的任务 id（这一轮已经失败过的那些；空集合表示不排除）。
	 *            排除必须做在 SQL 里而不是取回来再跳过：取回来再跳过的话，
	 *            排在最前面的那条失败任务会让查询每次都返回它，后面的任务永远轮不到
	 */
	MapperRows.AgentTaskRow findNextPending(@Param("statuses") List<String> statuses,
			@Param("excluded") List<Long> excluded);

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
	 * <b>与某个人有关、而且该由他看见的任务</b>（界面上的"我的任务"）。
	 *
	 * <h2>★ 关联的不是一列，而是"按状态看哪一列"</h2>
	 * 同一个"有他"的判断，在链上的几段落在不同的列上：
	 * <ul>
	 *   <li>{@code NEW} / {@code COMPLETED} / {@code CANCELLED} —— 发布人<b>或</b>任务人都算
	 *       （刚发出来还没指派给谁做、已经结束的、被取消的：三个人里的谁都应该在自己的列表里找得到它）；</li>
	 *   <li>{@code PENDING_REQUIREMENT} / {@code PENDING_ACCEPTANCE} —— 只有<b>发布人</b>
	 *       （答复需求与验收是他一个人的事，任务人在那两格插不上手）；</li>
	 *   <li>其余状态 —— 只有<b>任务人</b>（计划 / 授权 / 权限不足 / 执行中都在他手上）。</li>
	 * </ul>
	 * 所以这条查询是"按状态挑列"的 OR，而不是某一列上的等值比较 ——
	 * 写成 {@code current_executor_id = ?} 会在三处漏掉卡片（新发布与终态最明显：
	 * 发布人看不到自己刚发的那条、任务人也看不到它结束了）。
	 *
	 * <p>球在 agent 手上的三格（三个"已确认"，那一列是空的）<b>与谁都不相关</b>，
	 * 两张列表里都不出现 —— 那时没有人的名字挂在这件事上。
	 *
	 * <p>{@code statuses} 由调用方给（{@code AgentTaskStatus} 的那几组），SQL 里不写状态名：
	 * 状态链只有一份定义，抄进 SQL 之后加一个状态就会漏在这里，而漏掉的表现是
	 * "那条任务在谁的列表里都不出现" —— 静默。
	 *
	 * <p>没有为它加索引：判断横跨两列且按状态分组，单列索引用不上（MySQL 至多退化成 index merge），
	 * 而任务表是**人手工发起**的规模（与 {@link #list()} 同一条理由 —— 真长到需要按列分页时，
	 * 该改的是这一页的形态）。
	 *
	 * @param userId 那个人的用户 id（发布人 / 任务人的 {@code agent_user.id}）
	 * @param terminalStatuses 第一组（发布人 **或** 任务人）：{@code NEW} / {@code COMPLETED} / {@code CANCELLED}
	 * @param publisherStatuses 第二组（只有发布人）：{@code PENDING_REQUIREMENT} / {@code PENDING_ACCEPTANCE}
	 * @param assigneeStatuses 第三组（只有任务人）：其余全部状态
	 */
	List<MapperRows.AgentTaskRow> listForUser(@Param("userId") Long userId,
			@Param("terminalStatuses") List<String> terminalStatuses,
			@Param("publisherStatuses") List<String> publisherStatuses,
			@Param("assigneeStatuses") List<String> assigneeStatuses);

	/**
	 * 推进状态：状态、当前执行人、当前步骤三列一起换。
	 *
	 * <p>它们必须是同一条 SQL：拆成三条会有"状态已经变了、执行人还没变"的中间态，
	 * 而那时界面若正好刷新，看到的是"待确认需求，但执行人还是任务人"——
	 * 一张卡片的两个字段各说一套，没人能从界面上看出哪一个是旧的。
	 *
	 * <p>{@code currentExecutorId} 传 {@code null} 是有含义的：球在 agent 手上
	 * （三个"已确认"与"执行中"）或没有执行人（已完成），见
	 * {@code AgentTaskTransitions#executorFor}。
	 */
	int updateProgress(@Param("id") Long id, @Param("status") String status,
			@Param("currentExecutorId") Long currentExecutorId, @Param("currentStep") int currentStep);

	/** 登记执行结果（{@code EXECUTING → 待验收} 那一步）。 */
	int updateResult(@Param("id") Long id, @Param("result") String result);

	/**
	 * 写入"这条任务交给哪个 Agent 应用"（发布时不写，由平台在第一次接手时写）。
	 *
	 * <p>单独一条 SQL，不并进 {@link #updateProgress}：那一条每次状态推进都要跑，
	 * 把 agent_id 一起写进去就意味着每次推进都要带上它 ——
	 * 而调用点手上那份任务记录可能是几步之前读的，一次推进就会把这一列写回旧值。
	 */
	int updateAgentId(@Param("id") Long id, @Param("agentId") String agentId);

	/** 取消：状态与取消原因同一次写入（写原因单独一条 SQL 会留下"取消了但没写原因"的窗口）。 */
	int updateCancel(@Param("id") Long id, @Param("cancelReason") String cancelReason);

}
