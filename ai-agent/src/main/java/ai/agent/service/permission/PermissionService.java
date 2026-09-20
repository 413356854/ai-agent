package ai.agent.service.permission;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 权限管理的读写契约。
 *
 * <h2>为什么写操作是"整体替换"而不是逐条增删</h2>
 * {@link #setRoleTools} 与 {@link #setUserRoles} 收的是一份<b>完整的清单</b>。
 * 界面上那些复选框提交的本来就是一整份，而让调用方自己算 diff 的写法有个固定的坏法：
 * diff 少算一条时它是"没撤掉一个授权"，页面看起来与撤干净了一模一样。
 * 整体替换把这件事变成一次事务里的"先清后写"，没有中间态可看。
 *
 * <h2>为什么没有 deleteAccount</h2>
 * 账号只停用不删除（见 {@code AccountMapper} 的注释）。方法不存在，就没有"顺手删一下"的机会。
 */
public interface PermissionService {

	/** 权限页需要的一切，一次取回：账号（含各自角色）、角色（含各自工具与目录）、可分配的工具目录。 */
	Snapshot snapshot();

	Optional<Account> findAccount(String name);

	/** 按 id 找账号（界面改完之后要用新状态回话，而界面上拿的是 id）。 */
	Optional<Account> findAccountById(Long id);

	/** 按登录名解析出一个账号的权限（并集）。名字不存在或账号已停用时为空。 */
	Optional<Resolved> resolveByName(String accountName);

	/** 按 id 解析。 */
	Optional<Resolved> resolveById(Long accountId);

	/** 建账号。口令在这里被散列（调用方给的是明文，不给散列的写法见 {@code Account.of}）。 */
	Account createAccount(String name, String plaintextPassword, String displayName, boolean enabled);

	void updateAccount(Long id, String displayName, boolean enabled);

	/** 改口令。与 {@link #updateAccount} 分开：改显示名不该顺带重置口令，反之亦然。 */
	void changePassword(Long id, String plaintextPassword);

	Role createRole(String name, String description, String access);

	void updateRole(Long id, String name, String description, String access);

	/** 删角色。返回被这枚级联影响到的账号数 —— 它是"这次删掉的到底影响了谁"的唯一凭据。 */
	int deleteRole(Long id);

	void setRoleTools(Long roleId, List<RoleTool> tools);

	void setRoleDirectories(Long roleId, List<RoleDirectory> directories);

	void setUserRoles(Long userId, List<Long> roleIds);

	/** 一次核对："这个人能不能在这把工具上、在这个目录里干活"。 */
	Verdict verify(String accountName, String toolName, String workspaceDir);

	/**
	 * 权限页的读模型。
	 *
	 * @param accounts 账号（按名字排序）
	 * @param accountRoles 账号 id → 它的角色名列表
	 * @param roles 角色（按名字排序）
	 * @param roleTools 角色 id → 它的工具授权
	 * @param roleDirectories 角色 id → 它的目录授权
	 * @param availableTools 现在可被授权的工具名（注册表里真实存在的那些）
	 */
	record Snapshot(List<Account> accounts, Map<Long, List<String>> accountRoles, List<Role> roles,
			Map<Long, List<RoleTool>> roleTools, Map<Long, List<RoleDirectory>> roleDirectories,
			List<String> availableTools) {
	}

	/** 解析出来的权限 + 它是什么时候读的（页面要显示"这个结论是刚算的"）。 */
	record Resolved(ai.agent.entity.agent.permission.UserPermissions permissions, String resolvedAt) {
	}

	/**
	 * 一次核对的结论。
	 *
	 * <p>字段刻意分成"结论"与"依据"两组：只给一个 boolean 的话，
	 * 界面上那句"不能"之后没有任何可以下钻的东西，人只能去翻库。
	 *
	 * @param accountName 被核对的人
	 * @param allowed 该项检查是否通过
	 * @param toolAllowed 工具那一半的结论
	 * @param directoryAllowed 目录那一半的结论
	 * @param reason 一句话说明为什么（通过时也写，说明是按哪一条放行的）
	 * @param toolName 被检查的工具名（未检查时为空串）
	 * @param workspaceDir 被检查的目录（未检查时为空串）
	 * @param matchedPrefix 命中的目录前缀（没命中时为空串）
	 * @param roleNames 这个人拥有的角色名
	 * @param effectiveTools 他实际能调的工具名（{@code ALL} 时就是全部已注册工具）
	 */
	record Verdict(String accountName, boolean allowed, boolean toolAllowed, boolean directoryAllowed, String reason,
			String toolName, String workspaceDir, String matchedPrefix, List<String> roleNames,
			List<String> effectiveTools) {
	}

}
