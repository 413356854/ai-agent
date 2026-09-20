package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_role_dir} 表的 SQL —— 角色 → 工作区目录前缀 的授权。
 *
 * <h2>库里不做前缀匹配</h2>
 * 没有 {@code WHERE ? LIKE CONCAT(dir_prefix, '%')} 这种查询，而且它被刻意排除：
 * <ul>
 *   <li>{@code LIKE} 里 {@code _} 与 {@code %} 是通配符，而 Windows 路径里 {@code _} 很常见 ——
 *       一个叫 {@code D:\work\my_project} 的前缀会连带授权 {@code D:\work\myXproject}；</li>
 *   <li>字符串前缀会把 {@code D:\work\demo-other} 算进 {@code D:\work\demo}
 *       （这正是 {@code RoleDirectory.covers} 按路径分段判的原因）。</li>
 * </ul>
 * 判定在 Java 里只有一处（{@code RoleDirectory.covers}），这里只负责存取事实。
 */
@Mapper
public interface RoleDirectoryMapper {

	List<MapperRows.RoleDirectoryRow> byRole(@Param("roleId") Long roleId);

	/** 一次取回全部角色的目录行（权限页的 N+1 问题，同 {@link RoleToolMapper#all()}）。 */
	List<MapperRows.RoleDirectoryRow> all();

	int insert(@Param("roleId") Long roleId, @Param("dirPrefix") String dirPrefix, @Param("note") String note);

	int delete(@Param("roleId") Long roleId, @Param("dirPrefix") String dirPrefix);

	/** 清空一个角色的目录清单（与 {@link RoleToolMapper#deleteAllOfRole} 同一条"整体替换"的规矩）。 */
	int deleteAllOfRole(@Param("roleId") Long roleId);

}
