package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_role_tool} 表的 SQL —— 角色 → 工具 的白名单。
 *
 * <h2>为什么只有"按角色读"与"全量按角色读"，没有"按工具反查角色"</h2>
 * 反查（"哪些角色能调 read_section"）在界面上确实会有人想看，
 * 但那个问题的答案**不该由这张表回答**：权限是角色上的白名单，
 * 而"谁能用这把工具"是运行期解析的结果（还要考虑 {@code ALL} 那个开关）。
 * 用一条 SQL 去回答它，就必须在这里再实现一遍 {@code PermissionAccess.ALL} 的语义 ——
 * 于是同一件事有了两份判据。
 */
@Mapper
public interface RoleToolMapper {

	List<MapperRows.RoleToolRow> byRole(@Param("roleId") Long roleId);

	/** 一次取回全部角色的工具行。权限页要显示"每个角色各有哪些工具"，逐角色回查是 N+1。 */
	List<MapperRows.RoleToolRow> all();

	int insert(@Param("roleId") Long roleId, @Param("toolName") String toolName, @Param("note") String note);

	int delete(@Param("roleId") Long roleId, @Param("toolName") String toolName);

	/**
	 * 清空一个角色的工具清单。
	 *
	 * <p>{@link #replaceAll} 用它：先清后插，让"改权限"是一次<b>整体替换</b>而不是逐条增删 ——
	 * 逐条增删时，界面上取消勾选的那一把如果没被提交（前端漏了一个 diff），
	 * 它就静默地留着，而"少撤了一把"在页面上看起来与"撤干净了"一模一样。
	 */
	int deleteAllOfRole(@Param("roleId") Long roleId);

}
