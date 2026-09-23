package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_skill} 表的 SQL —— 技能记录的读写。
 *
 * <h2>两个姓名由 SQL 关联出来，不存进表</h2>
 * {@code owner_id} / {@code last_modified_by} 存的是 {@code agent_user.id}，
 * 而列表上要显示姓名。这里的 JOIN 一律是 <b>LEFT JOIN</b>：
 * 账号那一行万一不在（手工清过库），这一条技能照样读得出来、姓名那一格落空，
 * 比"整张列表打不开"好得多（与 {@code AgentTaskMapper.xml} 的三个 LEFT JOIN 同一条规矩）。
 *
 * <h2>★ {@link #insert} 之后由 {@link #lastInsertId()} 显式取新 id</h2>
 * 理由与写法与 {@code AccountMapper} 逐条相同（那里写着三条走不通的自动回填路）：
 * MyBatis / 驱动那一侧要么交给 {@code BigInteger}、要么把受影响行数当返回值、
 * 要么在 {@code ParamMap} 上解析不到 {@code keyProperty}。
 * 于是取键独立成一次调用，<b>必须在同一个事务里紧接着插入调用</b>。
 * 这里的代价比账号那一处小（建完之后不必立刻拿这个 id 去写别的表），
 * 但接口沿用它：两条路径不一样的话，读代码的人得先判断"这一次为什么可以不取"。
 *
 * <h2>没有"按名字查一条"</h2>
 * 名字不是查找键 —— 它只在 {@code (owner_id, folder, name)} 这个唯一约束里有意义，
 * 而那个约束的用途是**拦下重复**（撞到时是 409），不是给查询当入口。
 */
@Mapper
public interface AgentSkillMapper {

	/**
	 * 全部技能（不筛拥有者），最近修改的在前。
	 *
	 * <p>排序按 {@code updated_at DESC, id DESC}：列表是给人看的，
	 * 而"刚改过的那条在最上面"是唯一不会让人找不到自己刚存的东西的顺序。
	 * 第二排序键 {@code id} 是必须的 —— 同一秒内写了两条时，
	 * {@code updated_at} 相等，只按它排会让顺序在两次读之间变来变去。
	 */
	List<MapperRows.AgentSkillRow> list();

	/** 某个人的技能（界面登录之后走这一条）。排序理由同 {@link #list()}。 */
	List<MapperRows.AgentSkillRow> byOwner(@Param("ownerId") Long ownerId);

	/** 按 id 取一条（改 / 删之前要判归属，那一步必须先拿到这一行）。 */
	MapperRows.AgentSkillRow findById(@Param("id") Long id);

	/** 建一条技能。返回受影响行数；新 id 用 {@link #lastInsertId()} 取。 */
	int insert(@Param("folder") String folder, @Param("name") String name,
			@Param("description") String description, @Param("content") String content,
			@Param("ownerId") Long ownerId, @Param("lastModifiedBy") Long lastModifiedBy);

	/**
	 * 改一条技能的内容。
	 *
	 * <p><b>刻意不改 {@code owner_id}</b>：归属不随编辑变化，而"整行回写"的写法
	 * 会让一次编辑顺手把拥有者改成当前编辑人 —— 那是一次静默的归属转移。
	 *
	 * <p>{@code updated_at} 不在这条 UPDATE 里：由 DDL 的
     * {@code ON UPDATE CURRENT_TIMESTAMP} 填，两处都写会让"谁写的这个时间"有两个答案。
	 */
	int update(@Param("id") Long id, @Param("folder") String folder, @Param("name") String name,
			@Param("description") String description, @Param("content") String content,
			@Param("lastModifiedBy") Long lastModifiedBy);

	/** 删一条技能。归属判定在服务层（它要在删除之前先读一次那一行）。 */
	int delete(@Param("id") Long id);

	/**
	 * 刚插入那一行的自增主键。
	 *
	 * <p><b>必须在同一个事务里紧接着插入调用</b>：它读的是当前连接的
	 * {@code LAST_INSERT_ID()}，换一条连接就变成 0（实测新连接上恒为 0）。
	 */
	Long lastInsertId();

}
