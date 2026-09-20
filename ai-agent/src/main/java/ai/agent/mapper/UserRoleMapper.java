package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_user_role} 表的 SQL —— 账号 ↔ 角色。
 *
 * <h2>为什么"给账号设角色"是一次整体替换</h2>
 * 与 {@code agent_role_tool} 同一条规矩（见 {@link RoleToolMapper#deleteAllOfRole}）：
 * 界面上那一组复选框提交的是一份完整的角色列表，而不是一串增删。
 * 逐条增删要求前端算出 diff，而 diff 算错的形态恰好是"少撤了一个角色" ——
 * 多出来的权限不会报错，也没人会去核对。
 */
@Mapper
public interface UserRoleMapper {

	List<MapperRows.AccountRoleRow> byUser(@Param("userId") Long userId);

	/** 一次取回全部账号的角色关系（账号列表要显示"每人有哪些角色"，逐账号回查是 N+1）。 */
	List<MapperRows.AccountRoleRow> all();

	int insert(@Param("userId") Long userId, @Param("roleId") Long roleId, @Param("grantedBy") String grantedBy);

	int delete(@Param("userId") Long userId, @Param("roleId") Long roleId);

	int deleteAllOfUser(@Param("userId") Long userId);

	/** 有多少账号拥有这个角色（删角色前要把它告诉人：这个数字会让级联变成一次有意识的动作）。 */
	int countByRole(@Param("roleId") Long roleId);

}
