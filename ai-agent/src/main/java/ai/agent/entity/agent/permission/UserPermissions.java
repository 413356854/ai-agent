package ai.agent.entity.agent.permission;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>一个账号当前实际拥有的权限</b> —— 它几个角色的并集，解析一次之后的结果。
 *
 * <h2>为什么要有一个"解析后的对象"，而不是每次现查</h2>
 * 因为它要回答的三个问题（这个人能调哪几把工具 / 能在哪些目录下干活 / 是不是全部工具）
 * 在一次运行里会被问很多次，而它们各自的来源分散在三张表上。
 * 把一个账号的权限收成一个不可变对象之后：
 * <ul>
 *   <li>判定是纯计算，不再连库 —— 一次运行里的每一次工具调用都不会产生查询；</li>
 *   <li>"这次的权限是什么"可以原样落进报告与日志，事后的凭据就是它本身；</li>
 *   <li>权限来源只有一处装配（{@link #of}），换存储不会碰到判定。</li>
 * </ul>
 *
 * <h2>★ 没有角色 = 没有权限，这不是"没配置"</h2>
 * 并集是空集时 {@link #allowsTool} 恒为 false、{@link #coversDirectory} 恒为 false。
 * 让"新账号默认能用全部工具"看起来更友好，但它把最危险的那种状态设成了默认值 ——
 * 而"这个账号还没配权限"与"这个账号什么都不能干"在系统里本来就应该是同一件事
 * （同一条取舍见 {@link PermissionAccess}）。
 *
 * <h2>三个"是不是全部"的开关各自独立</h2>
 * {@link #allTools} 来自角色的 {@link PermissionAccess}：
 * 任一角色是 {@link PermissionAccess#ALL}，这个账号的工具集合就是全部。
 * 但<b>它不影响目录</b> —— 目录范围始终是清单的并集。
 * 两者合成一个开关（"全部工具"顺带"全部目录"）会让一次"给他管理员权限"的选择
 * 悄悄把文件系统范围也放开了，而界面上那一行写的是"全部工具"。
 *
 * @param account 账号本身
 * @param roles 它拥有的角色（按名字排序，去重）
 * @param tools 允许调用的工具名（各角色清单的并集，顺序稳定）
 * @param directories 允许的工作区目录前缀（各角色清单的并集）
 * @param allTools 是否存在一个 {@link PermissionAccess#ALL} 的角色
 */
public record UserPermissions(Account account, List<Role> roles, Set<String> tools, List<RoleDirectory> directories,
		boolean allTools) {

	public UserPermissions {
		roles = List.copyOf(roles);
		tools = Set.copyOf(tools);
		directories = List.copyOf(directories);
	}

	/**
	 * 装配。
	 *
	 * <p>同一个工具被两个角色都授权时只留一条（<b>并集</b>），
	 * 而"它来自哪个角色"这个问题由 {@link #roles} 回答 —— 权限判定不需要知道来源，
	 * 复盘时人才需要。
	 */
	public static UserPermissions of(Account account, List<Role> roles, List<RoleTool> tools,
			List<RoleDirectory> directories) {
		Set<String> names = new LinkedHashSet<>();
		boolean all = false;
		for (Role role : roles) {
			if (role.allowsEveryTool()) {
				all = true;
			}
		}
		if (!all) {
			for (RoleTool tool : tools) {
				names.add(tool.toolName());
			}
		}
		return new UserPermissions(account, roles, names, directories, all);
	}

	/** 这个人能不能调这把工具。工具名逐字比较（白名单键的取值空间就是 {@code AgentTool.name()}）。 */
	public boolean allowsTool(String toolName) {
		if (toolName == null) {
			return false;
		}
		return allTools || tools.contains(toolName);
	}

	/** 这个工作区目录算不算在授权范围里（任一条前缀命中即算）。 */
	public boolean coversDirectory(String workspaceDir) {
		for (RoleDirectory directory : directories) {
			if (directory.covers(workspaceDir)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 技能的文件夹算不算在授权范围里。
	 *
	 * <p>它与 {@link #coversDirectory(String)} 是<b>同一个判据</b>（同一批前缀、同一个
	 * {@link RoleDirectory#coversPath}），差别只在问的对象不同：那个问"这次运行的工作区"，
	 * 这个问"这条技能放在哪个文件夹"。技能的文件夹本来就是从这份目录前缀里选的
	 * （见 skill 管理那一页的「文件夹目录」选项集），所以两处必须给出同一个答案 ——
	 * 各写一遍的后果是"能在某个目录下干活、却看不到那个目录下的技能"，
	 * 而那种不一致在页面上看不出来。
	 */
	public boolean coversSkillFolder(String folder) {
		for (RoleDirectory directory : directories) {
			if (RoleDirectory.coversPath(directory.prefix(), folder)) {
				return true;
			}
		}
		return false;
	}

	/** 命中这条工作区目录的前缀（没有命中时为空）—— 报告里要写清"是按哪一条放行的"。 */
	public Optional<RoleDirectory> matchedDirectory(String workspaceDir) {
		return directories.stream().filter(d -> d.covers(workspaceDir)).findFirst();
	}

	/** 一个角色都没有的账号：什么都不能干。 */
	public boolean hasNoRole() {
		return roles.isEmpty();
	}

	/** 允许的工具名，按字母序（报告可复现）。 */
	public List<String> sortedTools() {
		return tools.stream().sorted().toList();
	}

	/** 一行说明（日志与报告用）。**不含口令散列**。 */
	public String describe() {
		return "%s · 角色 %d 个 [%s] · 工具 %s · 目录前缀 %d 条".formatted(account.label(), roles.size(),
				roles.stream().map(Role::name).reduce((a, b) -> a + "、" + b).orElse("无"),
				allTools ? "全部" : (tools.isEmpty() ? "无" : sortedTools().toString()), directories.size());
	}

}
