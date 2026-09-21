package ai.agent.service.permission;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;

import java.util.List;

/**
 * 权限数据的存取契约 —— 五个 mapper 的<b>一个收口</b>。
 *
 * <h2>为什么要有这一层，而不是让服务直接注入五个 mapper</h2>
 * 因为"权限"是一个整体：一次解析要读账号、它角色、每个角色的工具与目录。
 * 让调用方自己组合这几个 mapper，等于把"读的顺序与口径"复制到每一个调用点，
 * 而那正是 {@code PromptService} / {@code CanaryStore} 那两个包已经确立的写法：
 * <b>SQL 在 mapper 层，口径在 store 实现里，调用方只看契约</b>。
 *
 * <p>另一个好处是测试与替换：{@code DefaultPermissionService} 只认这个接口，
 * 于是"没有数据库也想验一遍判定"这件事只需要一个内存实现。
 */
public interface PermissionStore {

	Account findAccountById(Long id);

	Account findAccountByName(String name);

	List<Account> accounts();

	/** 按登录名搜索（子串匹配、大小写不敏感），最多 {@code limit} 条。 */
	List<Account> searchAccounts(String keyword, int limit);

	/** 按 id 列表取（角色侧"设成员"前确认这些账号都存在）。 */
	List<Account> findAccounts(List<Long> ids);

	List<Role> roles();

	List<Role> findRoles(List<Long> ids);

	List<Role> rolesOfAccount(Long accountId);

	List<RoleTool> toolsOfRole(Long roleId);

	List<RoleDirectory> directoriesOfRole(Long roleId);

	/** 全部角色的工具授权（权限页一次取回，避免逐个角色回查）。 */
	List<RoleTool> allRoleTools();

	/** 全部角色的目录授权。 */
	List<RoleDirectory> allRoleDirectories();

	/** 账号 ↔ 角色 的全部关系（带角色名）。 */
	List<AccountRoleLink> accountRoleLinks();

	/**
	 * 一条账号 ↔ 角色 关系。
	 *
	 * @param userId 账号 id
	 * @param roleId 角色 id
	 * @param roleName 角色名（页面上的标签就是它）
	 */
	record AccountRoleLink(Long userId, Long roleId, String roleName) {
	}

}
