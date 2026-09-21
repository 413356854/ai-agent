package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_user} 表的 SQL。
 *
 * <h2>为什么没有 delete</h2>
 * 账号不做删除，只做停用（{@code status = 'DISABLED'}）。
 * 删掉一行会让它名下的授权关系被外键级联带走（DDL 里就是这么写的），
 * 于是"这个人当时是什么权限"这个复盘问题永远没有答案。
 * 方法不存在，调用方就没有"顺手删一下"的机会 —— 比在注释里写"不要删"可靠。
 *
 * <h2>★ {@link #insert} 之后由 {@link #lastInsertId()} 显式取新 id</h2>
 * 主键是判定与授权的唯一凭据：建完账号紧接着就要给它挂角色
 * （{@code agent_user_role.user_id}），拿不到新 id 就只能再按名字查一次 ——
 * 那是把"名字唯一"这条约束当成主键用，而它俩的失效方式不同（名字是可以改的）。
 *
 * <p><b>实测：MyBatis 的三条"自动回填"路在本项目上都不通</b>，所以现在是显式的两次调用：
 * <ol>
 *   <li>{@code @Options(useGeneratedKeys = true, keyProperty = "id")}：
 *       MySQL 9.7.0 驱动把生成键以 {@link java.math.BigInteger} 交给
 *       {@code getGeneratedKeys()}，而 MyBatis 3.5.19 的 {@code Jdbc3KeyGenerator}
 *       只对 String / Integer / Long 做转换 —— 类型对不上时它<b>不抛异常、也不写日志</b>，
 *       只是不赋值。现象是"INSERT 成功（{@code Updates: 1}），而 id 是 null"；</li>
 *   <li>把 mapper 方法的返回类型写成 {@code Long} 再加 {@code @SelectKey}：
 *       那个返回值是<b>受影响行数</b>（实测拿到 1，而库里那一行的 id 是 4）——
 *       selectKey 的结果进的是参数，不会变成返回值；</li>
 *   <li>{@code @SelectKey} + 调用方传一个"生成键载体"参数：
 *       只要方法上还有别的 {@code @Param}，MyBatis 就把所有参数包成
 *       {@code ParamMap}，而 {@code keyProperty="id"} 在那个 Map 上解析不到载体里的字段
 *       （要写 {@code param1.id} 才行，而那个名字取决于参数位置，改签名就会静默失效）。</li>
 * </ol>
 * 所以取键独立成一个方法 {@link #lastInsertId()}：它在<b>同一个事务的同一条连接</b>上取值
 * （{@code LAST_INSERT_ID()} 是连接级的，并发插入之间不会串），
 * 拿到的类型实测可直接 {@code getLong}（MySQL 8.0.17 与 H2 2.3.232）。
 * 代价是调用方必须自己调一次 —— 这个代价由
 * {@code DefaultPermissionService.createAccount} 承担，并在拿不到时抛异常。
 */
@Mapper
public interface AccountMapper {

	/** 建账号。返回受影响行数；新 id 用 {@link #lastInsertId()} 取。 */
	int insert(@Param("name") String name, @Param("passwordHash") String passwordHash,
			@Param("displayName") String displayName, @Param("status") String status);

	/**
	 * 刚插入那一行的自增主键。
	 *
	 * <p><b>必须在同一个事务里紧接着插入调用</b>：它读的是当前连接的
	 * {@code LAST_INSERT_ID()}，换一条连接就变成 0（实测新连接上恒为 0）。
	 */
	Long lastInsertId();

	MapperRows.AccountRow findById(@Param("id") Long id);

	MapperRows.AccountRow findByName(@Param("name") String name);

	/**
	 * 按 id 列表取（角色侧"设成员"时逐个确认账号存在，报错里要能说清缺的是哪几个）。
	 *
	 * <p>空列表时调用方不发这条查询 —— {@code IN ()} 在两种库上都是语法错，
	 * 与 {@code RoleMapper.byIds} 同一条约定。
	 */
	List<MapperRows.AccountRow> byIds(@Param("ids") List<Long> ids);

	List<MapperRows.AccountRow> list();

	/** 改显示名与状态。**不含口令列** —— 改口令是 {@link #updatePassword}，两件事不共用一条 SQL。 */
	int update(@Param("id") Long id, @Param("displayName") String displayName, @Param("status") String status);

	/** 改口令散列。传入的必须是 {@code PasswordHash.hash(...)} 的产物（实体构造期会拦）。 */
	int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

	/** 拥有某个角色的账号 id 列表（删角色前要看它有没有被用；DDL 里的级联会真的删掉这些关系）。 */
	List<Long> idsByRole(@Param("roleId") Long roleId);

}
