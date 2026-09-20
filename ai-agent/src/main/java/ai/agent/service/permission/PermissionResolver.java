package ai.agent.service.permission;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;
import ai.agent.entity.agent.permission.UserPermissions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 权限的读取与装配 —— <b>SQL 在 {@code ai.agent.mapper} 里，本类只管口径</b>。
 *
 * <h2>三条口径写在这里，因为它们都不在 SQL 的表达能力里</h2>
 * <ol>
 *   <li><b>角色按名字排序，不按 id</b>：id 是自增的，但一次失败的插入也会消耗一个 id
 *       （实测：写两条同名账号、第二条撞唯一键失败之后，下一个 id 从 3 开始）。
 *       按 id 排的列表顺序会因此"跳号"，而按名字排是稳定的、可复现的；</li>
 *   <li><b>停用的账号解析出来是"没有权限"，而不是"查不到"</b>：两者对调用方的后果一样
 *       （拒绝），但报告里要能分清"这个人不存在"与"这个人被停用了" ——
 *       前者是配置错，后者是正常运维；</li>
 *   <li><b>装配并集时不合并 {@code ALL}</b>：{@code allTools} 只影响工具集合，
 *       目录始终是清单的并集（理由见 {@code UserPermissions} 的类注释）。</li>
 * </ol>
 *
 * <h2>为什么这里没有缓存</h2>
 * 权限页与运行期读的是同一份数据，而"改了权限之后运行期还用着旧的"
 * 是这个功能最不能有的失效形态（它的表现是"撤了权限但还能用"，且不报错）。
 * 一次解析是 5 条小查询、全在主键索引上，而它发生在<b>一次运行的开头</b>，
 * 不在每次工具调用上（{@code UserPermissions} 就是为此存在的）。
 */
@Component
public class PermissionResolver {

	private final PermissionStore store;

	public PermissionResolver(PermissionStore store) {
		this.store = store;
	}

	/** 按登录名解析。名字不存在、或账号被停用时为空。 */
	public Optional<Account> account(String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(store.findAccountByName(name.strip()));
	}

	/**
	 * 解析一个账号的完整权限。
	 *
	 * @return 账号不存在或已停用时为空
	 */
	public Optional<UserPermissions> resolve(String accountName) {
		return account(accountName).filter(Account::usable).map(this::permissionsOf);
	}

	/** 按 id 解析（恢复路径用：库里存的是名字，而名字是可以改的）。 */
	public Optional<UserPermissions> resolveById(Long accountId) {
		if (accountId == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(store.findAccountById(accountId)).filter(Account::usable).map(this::permissionsOf);
	}

	/** 把已经取到的账号装配成权限对象（调用方已经确认过它存在且可用）。 */
	public UserPermissions permissionsOf(Account account) {
		List<Role> roles = rolesOf(account.id());
		List<RoleTool> tools = new ArrayList<>();
		List<RoleDirectory> directories = new ArrayList<>();
		for (Role role : roles) {
			tools.addAll(store.toolsOfRole(role.id()));
			directories.addAll(store.directoriesOfRole(role.id()));
		}
		return UserPermissions.of(account, roles, tools, directories);
	}

	/** 账号的角色，按名字排序（口径 1）。 */
	public List<Role> rolesOf(Long accountId) {
		if (accountId == null) {
			return List.of();
		}
		List<Role> roles = store.rolesOfAccount(accountId);
		return roles.stream()
				.sorted(Comparator.comparing(Role::name))
				.toList();
	}

	/** 一次取回全部账号与角色（权限页用）：避免逐账号回查角色名。 */
	public Map<Long, List<String>> roleNamesByAccount() {
		Map<Long, List<String>> byAccount = new LinkedHashMap<>();
		for (PermissionStore.AccountRoleLink link : store.accountRoleLinks()) {
			byAccount.computeIfAbsent(link.userId(), key -> new ArrayList<>()).add(link.roleName());
		}
		for (List<String> names : byAccount.values()) {
			names.sort(Comparator.naturalOrder());
		}
		return byAccount;
	}

}
