package ai.agent.service.permission;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.AccountStatus;
import ai.agent.entity.agent.permission.PasswordHash;
import ai.agent.entity.agent.permission.PermissionAccess;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;
import ai.agent.entity.agent.permission.UserPermissions;
import ai.agent.mapper.AccountMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.mapper.RoleDirectoryMapper;
import ai.agent.mapper.RoleMapper;
import ai.agent.mapper.RoleToolMapper;
import ai.agent.mapper.UserRoleMapper;
import ai.agent.service.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PermissionService} 的实现 —— <b>权限管理唯一的写入口</b>。
 *
 * <h2>三条纪律，每条都对应一种"不报错的错"</h2>
 * <ol>
 *   <li><b>工具名必须逐个对上注册表</b>：写错一个字母的后果是这把工具永远调不到，
 *       而那次失败发生在运行期、被归因成「模型选错了工具」。所以 {@link #validated} 拿
 *       {@link ToolRegistry} 对一遍，报错里带上可用清单；</li>
 *   <li><b>权限的"整体替换"在事务里做</b>：先清后写，中间那个"清的做完、写的还没做"的状态
 *       不能被别人看到。没有事务时，一次失败会把角色变成"一条权限都没有" ——
 *       而那是一次静默的降权（下次运行少了几把工具，没人知道是这次编辑造成的）；</li>
 *   <li><b>旧口令与新口令不共用一条 SQL</b>：见 {@link #changePassword}。</li>
 * </ol>
 *
 * <h2>为什么这个类直接注入五个 mapper</h2>
 * 读侧走 {@link PermissionStore}（口径在那里），写侧本来就是"五张表各写各的"，
 * 再包一层 store 只会多出一层只有一行的方法。写侧的复杂度不在转发上，在下面这几条校验上。
 */
@Service
public class DefaultPermissionService implements PermissionService {

	private static final Logger log = LoggerFactory.getLogger(DefaultPermissionService.class);

	/**
	 * {@code agent_user_role.granted_by} 的取值。
	 *
	 * <p>账号侧与角色侧写的是同一张表，而 {@code granted_by} 记的是"这次授权是谁给的" ——
	 * 从哪个端点写进来不改变这个答案，所以两侧共用同一个值
	 * （另一处取值在 {@code PermissionBootstrap} 的启动引导里）。
	 */
	private static final String GRANTED_BY = "permission-api";

	private final AccountMapper accountMapper;

	private final RoleMapper roleMapper;

	private final RoleToolMapper roleToolMapper;

	private final RoleDirectoryMapper roleDirectoryMapper;

	private final UserRoleMapper userRoleMapper;

	/** 读侧的收口（解析、列表口径都在它那边）。 */
	private final PermissionStore store;

	private final PermissionResolver resolver;

	private final ToolRegistry toolRegistry;

	public DefaultPermissionService(AccountMapper accountMapper, RoleMapper roleMapper, RoleToolMapper roleToolMapper,
			RoleDirectoryMapper roleDirectoryMapper, UserRoleMapper userRoleMapper, PermissionStore store,
			PermissionResolver resolver, ToolRegistry toolRegistry) {
		this.accountMapper = accountMapper;
		this.roleMapper = roleMapper;
		this.roleToolMapper = roleToolMapper;
		this.roleDirectoryMapper = roleDirectoryMapper;
		this.userRoleMapper = userRoleMapper;
		this.store = store;
		this.resolver = resolver;
		this.toolRegistry = toolRegistry;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public Snapshot snapshot() {
		List<Account> accounts = store.accounts().stream()
				.sorted(Comparator.comparing(Account::name))
				.toList();
		List<Role> roles = store.roles().stream()
				.sorted(Comparator.comparing(Role::name))
				.toList();

		Map<Long, List<RoleTool>> toolsByRole = new LinkedHashMap<>();
		for (RoleTool tool : store.allRoleTools()) {
			toolsByRole.computeIfAbsent(tool.roleId(), key -> new ArrayList<>()).add(tool);
		}
		Map<Long, List<RoleDirectory>> dirsByRole = new LinkedHashMap<>();
		for (RoleDirectory directory : store.allRoleDirectories()) {
			dirsByRole.computeIfAbsent(directory.roleId(), key -> new ArrayList<>()).add(directory);
		}
		for (List<RoleTool> list : toolsByRole.values()) {
			list.sort(Comparator.comparing(RoleTool::toolName));
		}
		for (List<RoleDirectory> list : dirsByRole.values()) {
			list.sort(Comparator.comparing(RoleDirectory::prefix));
		}
		return new Snapshot(accounts, resolver.roleNamesByAccount(), roles, toolsByRole, dirsByRole,
				toolRegistry.names());
	}

	@Override
	public Optional<Account> findAccount(String name) {
		return resolver.account(name);
	}

	@Override
	public List<Account> searchAccounts(String keyword, int limit) {
		return store.searchAccounts(keyword, limit);
	}

	@Override
	public Optional<Account> findAccountById(Long id) {
		return Optional.ofNullable(id == null ? null : store.findAccountById(id));
	}

	@Override
	public Optional<Resolved> resolveByName(String accountName) {
		return resolver.resolve(accountName)
				.map(permissions -> new Resolved(permissions, Instant.now().toString()));
	}

	@Override
	public Optional<Resolved> resolveById(Long accountId) {
		return resolver.resolveById(accountId).map(permissions -> new Resolved(permissions, Instant.now().toString()));
	}

	// ------------------------------------------------------------------
	//  账号
	// ------------------------------------------------------------------

	@Override
	@Transactional
	public Account createAccount(String name, String plaintextPassword, String displayName, boolean enabled) {
		// Account.of 里做散列：调用方给明文，散列由实体算（见 Account 的注释）
		Account account = Account.of(name, plaintextPassword, displayName,
				enabled ? AccountStatus.ENABLED : AccountStatus.DISABLED);
		Long id;
		try {
			accountMapper.insert(account.name(), account.passwordHash(), account.displayName(),
					account.status().storedValue());
			// 取键必须是紧接着的下一句（LAST_INSERT_ID() 是连接级的，中间隔一次别的写就会取错）
			id = accountMapper.lastInsertId();
		}
		catch (DataAccessException e) {
			throw conflictOr(e, "创建账号失败 name=" + account.name(),
					"账号「%s」已经存在 —— 登录名是唯一键，两个同名账号会让「我是谁」失去答案"
							.formatted(account.name()));
		}
		Account saved = new Account(requiredKey(id, "账号"), account.name(), account.passwordHash(),
				account.displayName(), account.status());
		log.info("新建账号 {}（口令以 {} 保存）", saved.describe(), PasswordHash.describe());
		return saved;
	}

	@Override
	public void updateAccount(Long id, String displayName, boolean enabled) {
		Account current = require(id);
		Account next = new Account(current.id(), current.name(), current.passwordHash(), displayName,
				enabled ? AccountStatus.ENABLED : AccountStatus.DISABLED);
		int affected = accountMapper.update(next.id(), next.displayName(), next.status().storedValue());
		if (affected != 1) {
			throw new PermissionInvalidException("账号不存在：#" + id);
		}
		log.info("更新账号 {} -> {}", current.describe(), next.describe());
	}

	/**
	 * 改口令。
	 *
	 * <p><b>与 {@link #updateAccount} 分成两个方法</b>，因为它们是两件事：
	 * 改显示名不该顺带把口令重置掉，而"顺手更新整行"的写法正是那么干的 ——
	 * 界面上改个备注，口令要么被原样回写、要么变成空。
	 * 两条 SQL 各自只碰自己那几列，这个可能就不存在。
	 */
	@Override
	public void changePassword(Long id, String plaintextPassword) {
		require(id);
		int affected = accountMapper.updatePassword(id, PasswordHash.hash(plaintextPassword));
		if (affected != 1) {
			throw new PermissionInvalidException("账号不存在：#" + id);
		}
		// 日志里不写口令、也不写散列：散列本身也是敏感值（离线爆破的输入）
		log.info("账号 #{} 的口令已更新", id);
	}

	// ------------------------------------------------------------------
	//  角色
	// ------------------------------------------------------------------

	@Override
	@Transactional
	public Role createRole(String name, String description, String access) {
		Role role = Role.of(name, description, PermissionAccess.parse(access));
		Long id;
		try {
			roleMapper.insert(role.name(), role.description(), role.access().storedValue());
			id = roleMapper.lastInsertId();
		}
		catch (DataAccessException e) {
			throw conflictOr(e, "创建角色失败 name=" + role.name(),
					"角色「%s」已经存在 —— 两个同名角色在界面上长得一模一样，而它们挂的工具可以不同"
							.formatted(role.name()));
		}
		Role saved = new Role(requiredKey(id, "角色"), role.name(), role.description(), role.access());
		log.info("新建角色 {}", saved.describe());
		return saved;
	}

	@Override
	public void updateRole(Long id, String name, String description, String access) {
		Role current = requireRole(id);
		Role next = new Role(current.id(), name, description, PermissionAccess.parse(access));
		int affected;
		try {
			affected = roleMapper.update(next.id(), next.name(), next.description(), next.access().storedValue());
		}
		catch (DataAccessException e) {
			throw conflictOr(e, "更新角色失败 id=" + id, "角色名「%s」已经被另一个角色用了".formatted(next.name()));
		}
		if (affected != 1) {
			throw new PermissionInvalidException("角色不存在：#" + id);
		}
		log.info("更新角色 {} -> {}", current.describe(), next.describe());
	}

	@Override
	@Transactional
	public int deleteRole(Long id) {
		requireRole(id);
		// 先数清楚这次会波及多少账号，再删 —— 那个数字要回到界面上。
		// 级联删除本身由数据库做（DDL 里的外键），这里不自己删那三张表：
		// 两处都删的写法会在有人改掉外键行为之后静默分叉
		int affectedAccounts = userRoleMapper.countByRole(id);
		int affected = roleMapper.delete(id);
		if (affected != 1) {
			throw new PermissionInvalidException("角色不存在：#" + id);
		}
		log.warn("删除角色 #{}：{} 个账号失去了这个角色（agent_role_tool / agent_role_dir / agent_user_role 已级联删除）",
				id, affectedAccounts);
		return affectedAccounts;
	}

	@Override
	@Transactional
	public void setRoleTools(Long roleId, List<RoleTool> tools) {
		requireRole(roleId);
		List<RoleTool> validated = validated(roleId, tools);
		roleToolMapper.deleteAllOfRole(roleId);
		for (RoleTool tool : validated) {
			roleToolMapper.insert(roleId, tool.toolName(), tool.note());
		}
		log.info("角色 #{} 的工具清单已替换为 {} 把：{}", roleId, validated.size(),
				validated.stream().map(RoleTool::toolName).toList());
	}

	@Override
	@Transactional
	public void setRoleDirectories(Long roleId, List<RoleDirectory> directories) {
		requireRole(roleId);
		// 同一条前缀写两遍会被复合主键拦下（重复键）。在写入前合成一行，而不是让界面收到一个 400 ——
		// 界面上"同一个前缀出现两次"本来就是一次手误，没有歧义
		Map<String, RoleDirectory> unique = new LinkedHashMap<>();
		for (RoleDirectory directory : directories == null ? List.<RoleDirectory>of() : directories) {
			// 归一 roleId：请求体里的那一份可能是 null 或别人的（前端只需要给出前缀）
			RoleDirectory normalized = RoleDirectory.of(roleId, directory.prefix(), directory.note());
			unique.putIfAbsent(normalized.prefix(), normalized);
		}
		roleDirectoryMapper.deleteAllOfRole(roleId);
		for (RoleDirectory directory : unique.values()) {
			roleDirectoryMapper.insert(roleId, directory.prefix(), directory.note());
		}
		log.info("角色 #{} 的目录前缀已替换为 {} 条：{}", roleId, unique.size(), unique.keySet());
	}

	@Override
	@Transactional
	public void setUserRoles(Long userId, List<Long> roleIds) {
		require(userId);
		List<Long> wanted = roleIds == null ? List.of()
				: roleIds.stream().filter(Objects::nonNull).distinct().toList();
		if (!wanted.isEmpty()) {
			List<Role> found = store.findRoles(wanted);
			if (found.size() != wanted.size()) {
				List<Long> missing = new ArrayList<>(wanted);
				found.forEach(role -> missing.remove(role.id()));
				throw new PermissionInvalidException("这些角色不存在：" + missing + "（可用的是 "
						+ store.roles().stream().map(Role::name).toList() + "）");
			}
		}
		userRoleMapper.deleteAllOfUser(userId);
		for (Long roleId : wanted) {
			userRoleMapper.insert(userId, roleId, GRANTED_BY);
		}
		log.info("账号 #{} 的角色已替换为 {} 个：{}", userId, wanted.size(), wanted);
	}

	/**
	 * 替换角色的成员账号 —— {@link #setUserRoles} 的另一侧，写的是同一张 {@code agent_user_role}。
	 *
	 * <p><b>停用的账号允许挂进角色</b>：停用即无权限那条判据只在解析期生效
	 * （{@code UserPermissions} 那一处），写入口再判一次就是第二份判据 ——
	 * 而它的形态是"停用期间配好的角色，重新启用之后莫名其妙没配上"。
	 */
	@Override
	@Transactional
	public void setRoleUsers(Long roleId, List<Long> userIds) {
		requireRole(roleId);
		List<Long> wanted = userIds == null ? List.of()
				: userIds.stream().filter(Objects::nonNull).distinct().toList();
		if (!wanted.isEmpty()) {
			List<Account> found = store.findAccounts(wanted);
			if (found.size() != wanted.size()) {
				List<Long> missing = new ArrayList<>(wanted);
				found.forEach(account -> missing.remove(account.id()));
				throw new PermissionInvalidException("这些账号不存在：" + missing + "（可用的是 "
						+ usernames() + "）");
			}
		}
		userRoleMapper.deleteAllOfRole(roleId);
		for (Long userId : wanted) {
			userRoleMapper.insert(userId, roleId, GRANTED_BY);
		}
		log.info("角色 #{} 的成员已替换为 {} 个：{}", roleId, wanted.size(), wanted);
	}

	// ------------------------------------------------------------------
	//  核对
	// ------------------------------------------------------------------

	@Override
	public Verdict verify(String accountName, String toolName, String workspaceDir) {
		Optional<Account> found = resolver.account(accountName);
		if (found.isEmpty()) {
			return new Verdict(accountName, false, false, false,
					"账号不存在：" + accountName + "（可用的是 " + usernames() + "）", text(toolName),
					text(workspaceDir), "", List.of(), List.of());
		}
		Account account = found.get();
		if (!account.usable()) {
			return new Verdict(accountName, false, false, false,
					"账号已停用（status=" + account.status().name() + "）—— 停用的账号一律没有权限", text(toolName),
					text(workspaceDir), "", List.of(), List.of());
		}
		UserPermissions permissions = resolver.permissionsOf(account);
		boolean toolChecked = toolName != null && !toolName.isBlank();
		boolean toolAllowed = !toolChecked || permissions.allowsTool(toolName);
		// 目录那一半只在调用方给了目录时才检查 —— 它是可选参数（只想问工具时不必编一个目录出来）
		boolean directoryChecked = workspaceDir != null && !workspaceDir.isBlank();
		boolean directoryAllowed = !directoryChecked || permissions.coversDirectory(workspaceDir);
		String reason;
		if (!toolAllowed) {
			reason = "不在任何角色的工具白名单里（能调的是 " + permissions.sortedTools() + "）";
		}
		else if (!directoryAllowed) {
			reason = "不在任何角色的目录前缀里（已授权的前缀："
					+ permissions.directories().stream().map(RoleDirectory::prefix).toList() + "）";
		}
		else if (!toolChecked && !directoryChecked) {
			reason = "既没给工具也没给目录 —— 只回答了「这个账号存在且可用」";
		}
		else if (permissions.allTools() && toolChecked) {
			reason = "角色里有「" + PermissionAccess.ALL.label() + "」，工具不受白名单限制";
		}
		else if (directoryChecked) {
			reason = "按前缀「" + permissions.matchedDirectory(workspaceDir).map(RoleDirectory::prefix).orElse("")
					+ "」放行";
		}
		else {
			reason = "在白名单里";
		}
		List<String> effective = permissions.allTools() ? toolRegistry.names() : permissions.sortedTools();
		return new Verdict(account.name(), toolAllowed && directoryAllowed, toolAllowed, directoryAllowed, reason,
				text(toolName), text(workspaceDir),
				permissions.matchedDirectory(workspaceDir).map(RoleDirectory::prefix).orElse(""),
				permissions.roles().stream().map(Role::name).toList(), effective);
	}

	// ------------------------------------------------------------------
	//  内部
	// ------------------------------------------------------------------

	/** 工具名逐个对注册表。报告里带上可用清单 —— 只说"这个名字不存在"，下一个名字还会写错。 */
	private List<RoleTool> validated(Long roleId, List<RoleTool> tools) {
		List<RoleTool> validated = new ArrayList<>();
		for (RoleTool tool : tools == null ? List.<RoleTool>of() : tools) {
			if (!toolRegistry.contains(tool.toolName())) {
				throw new PermissionInvalidException(("工具「%s」不在注册表里 —— 写错一个字母的后果是"
						+ "这把工具永远调不到，而那会在运行期表现为「模型选错了工具」。"
						+ "现在可用的是：%s").formatted(tool.toolName(), toolRegistry.names()));
			}
			validated.add(RoleTool.of(roleId, tool.toolName(), tool.note()));
		}
		// 同一把工具写两遍会被复合主键拦下，先去重（理由同 setRoleDirectories）
		Map<String, RoleTool> unique = new LinkedHashMap<>();
		for (RoleTool tool : validated) {
			unique.putIfAbsent(tool.toolName(), tool);
		}
		return List.copyOf(unique.values());
	}

	private Account require(Long id) {
		Account account = store.findAccountById(id);
		if (account == null) {
			throw new PermissionInvalidException("账号不存在：#" + id);
		}
		return account;
	}

	private Role requireRole(Long id) {
		MapperRows.RoleRow row = roleMapper.findById(id);
		if (row == null) {
			throw new PermissionInvalidException("角色不存在：#" + id);
		}
		return row.toDomain();
	}

	private List<String> usernames() {
		return store.accounts().stream().map(Account::name).toList();
	}

	/**
	 * 插入之后必须拿到新主键。
	 *
	 * <p>拿不到的后果不是"这次创建失败"，而是<b>创建成功、返回的 id 是 null</b> ——
	 * 于是紧接着那一步（给它挂角色 / 给它配权限）会报成"对象不存在"，
	 * 而真正的原因是取键没生效。这条判断把那种失败留在原地。
	 */
	private static Long requiredKey(Long id, String what) {
		if (id == null) {
			throw new IllegalStateException("插入" + what + "之后没有拿到自增主键 —— "
					+ "SELECT LAST_INSERT_ID() 没有写进载体（写法与两条实测的弯路见 AccountMapper 的注释）。"
					+ "这一行已经写进库里了，但调用方拿不到它的 id");
		}
		return id;
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

	/**
	 * 把写失败分成"撞了唯一约束"（409）与"别的原因"（500）。
	 *
	 * <p>判据与 {@code JdbcCanaryStore#isDuplicate} 逐条相同，且同样是三档并存：
	 * Spring 的异常翻译接住标准 SQLState，底层 {@link SQLException} 接住 H2 的 23000 与 MySQL 的 1062
	 * （这两个码不在 Spring 的"重复键"表里）。这个判断失效时的表现是"并发创建时回 500 而不是 409"，
	 * 而它只在并发下出现。
	 */
	private static RuntimeException conflictOr(DataAccessException e, String whatFailed, String conflictMessage) {
		if (e instanceof DataIntegrityViolationException || hasDuplicateSqlState(e)) {
			return new PermissionConflictException(conflictMessage);
		}
		return new PermissionStoreException(whatFailed, e);
	}

	private static boolean hasDuplicateSqlState(Throwable root) {
		for (Throwable t = root; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				String state = sql.getSQLState();
				if ("23505".equals(state) || "23000".equals(state) || sql.getErrorCode() == 23505
						|| sql.getErrorCode() == 1062) {
					return true;
				}
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

}
