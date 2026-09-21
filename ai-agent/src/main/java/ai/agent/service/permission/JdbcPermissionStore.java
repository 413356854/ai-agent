package ai.agent.service.permission;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.PermissionAccess;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;
import ai.agent.mapper.AccountMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.mapper.RoleDirectoryMapper;
import ai.agent.mapper.RoleMapper;
import ai.agent.mapper.RoleToolMapper;
import ai.agent.mapper.UserRoleMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link PermissionStore} 的 JDBC 实现 —— <b>每个方法就是一次 mapper 调用</b>。
 *
 * <h2>为什么这一层这么薄还要存在</h2>
 * 因为它把"哪个 mapper 回答哪个问题"这件事写下来了一次。
 * 服务层只认 {@link PermissionStore}，而这里逐行对应到 mapper ——
 * 少一层的话，"这次解析一共查了几张表"要在服务层的方法体里数。
 *
 * <p>口径（排序、并集、宽严解析）一律不在这里：它们全在
 * {@link PermissionResolver} 与实体 record 里，各自只有一处。
 */
@Component
public class JdbcPermissionStore implements PermissionStore {

	private final AccountMapper accounts;

	private final RoleMapper roles;

	private final RoleToolMapper roleTools;

	private final RoleDirectoryMapper roleDirectories;

	private final UserRoleMapper userRoles;

	public JdbcPermissionStore(AccountMapper accounts, RoleMapper roles, RoleToolMapper roleTools,
			RoleDirectoryMapper roleDirectories, UserRoleMapper userRoles) {
		this.accounts = accounts;
		this.roles = roles;
		this.roleTools = roleTools;
		this.roleDirectories = roleDirectories;
		this.userRoles = userRoles;
	}

	@Override
	public Account findAccountById(Long id) {
		MapperRows.AccountRow row = read(() -> accounts.findById(id), "读账号失败 id=" + id);
		return row == null ? null : row.toDomain();
	}

	@Override
	public Account findAccountByName(String name) {
		MapperRows.AccountRow row = read(() -> accounts.findByName(name), "读账号失败 name=" + name);
		return row == null ? null : row.toDomain();
	}

	@Override
	public List<Account> accounts() {
		return read(accounts::list, "读账号列表失败").stream().map(MapperRows.AccountRow::toDomain).toList();
	}

	@Override
	public List<Account> searchAccounts(String keyword, int limit) {
		return read(() -> accounts.searchByName(keyword, limit), "搜索账号失败 keyword=" + keyword).stream()
				.map(MapperRows.AccountRow::toDomain)
				.toList();
	}

	@Override
	public List<Account> findAccounts(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			// `IN ()` 在两种库上都是语法错，而它只在"这次一个账号都没勾"时出现
			return List.of();
		}
		return read(() -> accounts.byIds(ids), "读账号失败 ids=" + ids).stream()
				.map(MapperRows.AccountRow::toDomain)
				.toList();
	}

	@Override
	public List<Role> roles() {
		return read(roles::list, "读角色列表失败").stream().map(MapperRows.RoleRow::toDomain).toList();
	}

	@Override
	public List<Role> findRoles(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			// `IN ()` 在两种库上都是语法错，而它只在"这个账号一个角色都没有"时出现
			return List.of();
		}
		return read(() -> roles.byIds(ids), "读角色失败 ids=" + ids).stream()
				.map(MapperRows.RoleRow::toDomain)
				.toList();
	}

	@Override
	public List<Role> rolesOfAccount(Long accountId) {
		List<Long> ids = read(() -> userRoles.byUser(accountId), "读账号角色失败 userId=" + accountId).stream()
				.map(MapperRows.AccountRoleRow::roleId)
				.toList();
		return findRoles(ids);
	}

	@Override
	public List<RoleTool> toolsOfRole(Long roleId) {
		return read(() -> roleTools.byRole(roleId), "读角色工具失败 roleId=" + roleId).stream()
				.map(MapperRows.RoleToolRow::toDomain)
				.toList();
	}

	@Override
	public List<RoleDirectory> directoriesOfRole(Long roleId) {
		return read(() -> roleDirectories.byRole(roleId), "读角色目录失败 roleId=" + roleId).stream()
				.map(MapperRows.RoleDirectoryRow::toDomain)
				.toList();
	}

	@Override
	public List<RoleTool> allRoleTools() {
		return read(roleTools::all, "读全部角色工具失败").stream().map(MapperRows.RoleToolRow::toDomain).toList();
	}

	@Override
	public List<RoleDirectory> allRoleDirectories() {
		return read(roleDirectories::all, "读全部角色目录失败").stream()
				.map(MapperRows.RoleDirectoryRow::toDomain)
				.toList();
	}

	@Override
	public List<AccountRoleLink> accountRoleLinks() {
		return read(userRoles::all, "读账号角色关系失败").stream()
				.map(row -> new AccountRoleLink(row.userId(), row.roleId(), row.roleName()))
				.toList();
	}

	/**
	 * 读操作的异常包装。
	 *
	 * <p>与 {@code JdbcCanaryStore} / {@code JdbcToolApprovalStore} 一致：
	 * {@link DataAccessException} 换成带"在做什么"的 {@link PermissionStoreException}，
	 * 否则端点只能看到一句 "BadSqlGrammarException"，而它不告诉人是在读哪一张表。
	 */
	private <T> T read(java.util.function.Supplier<T> call, String whatFailed) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new PermissionStoreException(whatFailed, e);
		}
	}

	/** 只有 {@link PermissionAccess} 的库值需要转换（服务层写入时用）。 */
	static String stored(PermissionAccess access) {
		return access.storedValue();
	}

}
