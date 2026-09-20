package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_role} 表的 SQL。
 *
 * <h2>{@code access_scope} 列在 SQL 里是字符串，在 Java 里是枚举</h2>
 * 转换写在 {@link MapperRows.RoleRow#toDomain()} 与写入口的 {@code storedValue()} 上，
 * 不给 MyBatis 配 {@code EnumTypeHandler} —— 理由与 {@code PromptKindTypeHandler} 那条一样：
 * 默认的枚举处理器用的是 {@code name()}，而 {@code PermissionAccess} 的宽松解析规则
 * （认不出落 LISTED）只能自己写。两处各写一半的后果是"库里存了小写就认不出"，
 * 而它不报错，只表现为某个角色突然没了全部工具。
 *
 * <h2>★ {@link #insert} 的取键写法与三条实测的弯路见 {@link AccountMapper}</h2>
 * 同一个坑，同一套办法：插入与取键分两次调用，中间不隔任何别的写操作。
 */
@Mapper
public interface RoleMapper {

	/** 建角色。返回受影响行数；新 id 用 {@link #lastInsertId()} 取。 */
	int insert(@Param("name") String name, @Param("description") String description,
			@Param("access") String access);

	/** 刚插入那一行的自增主键（必须在同一个事务里紧接着插入调用，理由见 {@link AccountMapper}）。 */
	Long lastInsertId();

	MapperRows.RoleRow findById(@Param("id") Long id);

	MapperRows.RoleRow findByName(@Param("name") String name);

	List<MapperRows.RoleRow> list();

	/** 按 id 列表取（用于把"这个账号的角色 id"变成角色对象）。空列表时调用方不应发出这条查询（IN () 是语法错）。 */
	List<MapperRows.RoleRow> byIds(@Param("ids") List<Long> ids);

	int update(@Param("id") Long id, @Param("name") String name, @Param("description") String description,
			@Param("access") String access);

	/** 删角色。{@code agent_role_tool} / {@code agent_role_dir} / {@code agent_user_role} 由外键级联。 */
	int delete(@Param("id") Long id);

}
