package ai.agent.controller;

import ai.agent.config.PermissionProperties;
import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.PasswordHash;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;
import ai.agent.entity.agent.permission.UserPermissions;
import ai.agent.service.permission.PermissionConflictException;
import ai.agent.service.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>Agent 权限管理的界面入口</b>：用户 / 角色 / 角色能调哪些工具 / 能在哪些目录下干活。
 *
 * <h2>四个端点族，对应四件不同的事</h2>
 * <table border="1">
 *   <caption>端点与它回答的问题</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/permissions}</td><td>"现在有谁、有哪些角色、每个角色配了什么"（一页一次取回）</td></tr>
 *   <tr><td>{@code POST/PUT/DELETE /api/permissions/users…}</td><td>"加个人 / 停用他 / 给他角色 / 改他的口令"</td></tr>
 *   <tr><td>{@code POST/PUT/DELETE /api/permissions/roles…}</td><td>"建个角色 / 它能有哪几把工具、哪几个目录"</td></tr>
 *   <tr><td>{@code GET /api/permissions/verify}</td><td>"这个人到底能不能在这把工具上、在这个目录里干活"</td></tr>
 * </table>
 *
 * <h2>★ 这个类的返回体里永远没有口令</h2>
 * 账号被装配成 {@code account(...)} 那一个小 Map，字段只有 id / name / displayName / status / roles。
 * 口令散列<b>不进任何响应</b> —— 它不是"要不要脱敏"的问题，而是"响应里根本不存在这个键"。
 * 一个把整行实体序列化出去的写法做不到这一点（那样加一列就等于自动多暴露一列）。
 *
 * <h2>为什么写操作返回的是"改完之后的那一份"，而不是一个 ok</h2>
 * 与 {@code PromptController} / {@code AgentApprovalController} 同一条取舍：
 * 界面拿到 {@code {"ok":true}} 之后只能重新拉一次全量，而那次拉取与这次写入之间
 * 可能隔着别人的一次修改 —— 于是页面上显示的是"别人改完之后的样子"，
 * 而用户以为是自己这次的结果。返回新状态就没有这个缝。
 *
 * <h2>为什么错误码分开：400 / 404 / 409</h2>
 * 三种失败的处置完全不同：参数写错要改了重试、对象不存在要看列表、撞名字要换个名字。
 * 混成一个 500 会让这三种都变成"服务器出错了"。
 */
@RestController
@RequestMapping(path = "/api/permissions", produces = MediaType.APPLICATION_JSON_VALUE)
public class PermissionController {

	private static final Logger log = LoggerFactory.getLogger(PermissionController.class);

	private final PermissionService permissions;

	private final PermissionProperties properties;

	public PermissionController(PermissionService permissions, PermissionProperties properties) {
		this.permissions = permissions;
		this.properties = properties;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	/** 权限页要的一切：账号（含角色）、角色（含工具与目录）、可分配的工具目录。 */
	@GetMapping
	public Map<String, Object> snapshot() {
		PermissionService.Snapshot snapshot = permissions.snapshot();

		List<Map<String, Object>> users = new ArrayList<>();
		for (Account account : snapshot.accounts()) {
			users.add(account(account, snapshot.accountRoles().getOrDefault(account.id(), List.of())));
		}

		List<Map<String, Object>> roles = new ArrayList<>();
		for (Role role : snapshot.roles()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", role.id());
			row.put("name", role.name());
			row.put("description", role.description() == null ? "" : role.description());
			row.put("access", role.access().name());
			row.put("accessLabel", role.access().label());
			List<Map<String, Object>> tools = new ArrayList<>();
			for (RoleTool tool : snapshot.roleTools().getOrDefault(role.id(), List.of())) {
				tools.add(Map.of("toolName", tool.toolName(), "note", tool.note() == null ? "" : tool.note()));
			}
			List<Map<String, Object>> dirs = new ArrayList<>();
			for (RoleDirectory directory : snapshot.roleDirectories().getOrDefault(role.id(), List.of())) {
				dirs.add(Map.of("prefix", directory.prefix(), "note", directory.note() == null ? "" : directory.note()));
			}
			row.put("tools", tools);
			row.put("toolNames", tools.stream().map(t -> t.get("toolName")).toList());
			row.put("directories", dirs);
			row.put("dirPrefixes", dirs.stream().map(d -> d.get("prefix")).toList());
			roles.add(row);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("users", users);
		body.put("roles", roles);
		body.put("availableTools", snapshot.availableTools());
		body.put("managementEnabled", properties.managementEnabled());
		body.put("bootstrapNote", properties.bootstrapEnabled()
				? "库里一个账号都没有时，启动会建一个初始管理员（口令来自 ai.agent.permission.bootstrap-admin-password）"
				: "初始管理员已关闭（ai.agent.permission.bootstrap-enabled=false）");
		// 权限怎么存的：界面顶部要把这句话显示出来，因为"口令存的是散列"是使用者最该知道的一条
		body.put("passwordNote", "口令只以散列保存：" + PasswordHash.describe() + "，响应里不含这一列");
		body.put("howToVerify", "GET /api/permissions/verify?account=<账号>&tool=<工具>&dir=<目录>");
		return body;
	}

	/**
	 * 一次核对：这个人能不能调这把工具、能不能在这个目录里干活。
	 *
	 * <p>它是这一层对外的<b>判定入口</b>：运行期那条链路（Agent 循环）读的是同一份解析结果，
	 * 所以这个端点的结论与运行期一致 —— 而不是"页面上写着能、跑起来不能"。
	 */
	@GetMapping("/verify")
	public Map<String, Object> verify(@RequestParam String account,
			@RequestParam(required = false) String tool, @RequestParam(required = false) String dir) {
		PermissionService.Verdict verdict = permissions.verify(account, tool, dir);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("account", verdict.accountName());
		body.put("allowed", verdict.allowed());
		body.put("toolAllowed", verdict.toolAllowed());
		body.put("directoryAllowed", verdict.directoryAllowed());
		body.put("reason", verdict.reason());
		body.put("tool", verdict.toolName());
		body.put("dir", verdict.workspaceDir());
		body.put("matchedPrefix", verdict.matchedPrefix());
		body.put("roles", verdict.roleNames());
		body.put("effectiveTools", verdict.effectiveTools());
		return body;
	}

	/** 已注册的工具名（分配工具时的候选清单，也是"写错一个字母"的判据来源）。 */
	@GetMapping("/tools")
	public Map<String, Object> tools() {
		List<String> names = permissions.snapshot().availableTools();
		return Map.of("tools", names, "count", names.size());
	}

	// ------------------------------------------------------------------
	//  账号
	// ------------------------------------------------------------------

	@PostMapping(path = "/users", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> createUser(@RequestBody UserRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			return badRequest("缺少 name");
		}
		if (request.password() == null || request.password().isBlank()) {
			return badRequest("缺少 password —— 建账号必须给一个初始口令");
		}
		Account created = permissions.createAccount(request.name(), request.password(), request.displayName(),
				request.enabledOrDefault());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("user", account(created, List.of()));
		return ResponseEntity.ok(body);
	}

	/** 改显示名与启用状态。<b>不含口令</b> —— 改口令是 {@code /users/{id}/password}。 */
	@PutMapping(path = "/users/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> updateUser(@PathVariable Long id, @RequestBody UserRequest request) {
		permissions.updateAccount(id, request == null ? null : request.displayName(),
				request == null || request.enabledOrDefault());
		Account updated = permissions.findAccountById(id).orElse(null);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("user", updated == null ? Map.of() : account(updated, rolesOfUser(id)));
		return body;
	}

	@PutMapping(path = "/users/{id}/password", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> changePassword(@PathVariable Long id, @RequestBody PasswordRequest request) {
		if (request == null || request.password() == null || request.password().isBlank()) {
			throw new ai.agent.service.permission.PermissionInvalidException("缺少 password");
		}
		permissions.changePassword(id, request.password());
		// 返回体里**不含口令也不含散列**：只报告这件事发生了
		return Map.of("ok", true, "userId", id, "note", "口令已按新散列保存（旧口令立即失效）");
	}

	/** 给账号替换角色清单（整份替换，理由见 PermissionService 的接口注释）。 */
	@PutMapping(path = "/users/{id}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> setUserRoles(@PathVariable Long id, @RequestBody RolesRequest request) {
		permissions.setUserRoles(id, request == null ? List.of() : request.roleIdsOrDefault());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("userId", id);
		body.put("roles", rolesOfUser(id));
		return body;
	}

	// ------------------------------------------------------------------
	//  角色
	// ------------------------------------------------------------------

	@PostMapping(path = "/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> createRole(@RequestBody RoleRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			return badRequest("缺少 name");
		}
		Role created = permissions.createRole(request.name(), request.description(), request.access());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("role", role(created, List.of(), List.of()));
		return ResponseEntity.ok(body);
	}

	@PutMapping(path = "/roles/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> updateRole(@PathVariable Long id, @RequestBody RoleRequest request) {
		permissions.updateRole(id, request.name(), request.description(), request.access());
		PermissionService.Snapshot snapshot = permissions.snapshot();
		Role updated = snapshot.roles().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
		return Map.of("ok", true, "role", updated == null ? Map.of()
				: role(updated, snapshot.roleTools().getOrDefault(id, List.of()),
						snapshot.roleDirectories().getOrDefault(id, List.of())));
	}

	/**
	 * 删角色。
	 *
	 * <p>返回体里带回 {@code affectedAccounts}，而且它在删除<b>之前</b>数出来：
	 * 外键级联会真的把 {@code agent_user_role} 里那些行删掉，
	 * 于是"这次删掉的角色影响了几个人"这个数字事后就查不到了。
	 */
	@DeleteMapping("/roles/{id}")
	public Map<String, Object> deleteRole(@PathVariable Long id) {
		int affected = permissions.deleteRole(id);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("roleId", id);
		body.put("affectedAccounts", affected);
		body.put("note", affected == 0 ? "没有账号拥有这个角色"
				: affected + " 个账号失去了这个角色 —— 它们的权限按剩下的角色重新取并集");
		return body;
	}

	/** 替换角色的工具清单。工具名逐个对注册表，认不出的当场 400（带可用清单）。 */
	@PutMapping(path = "/roles/{id}/tools", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> setRoleTools(@PathVariable Long id, @RequestBody RoleToolsRequest request) {
		List<RoleTool> tools = new ArrayList<>();
		for (ToolEntry entry : request == null ? List.<ToolEntry>of() : request.toolsOrDefault()) {
			tools.add(RoleTool.of(id, entry.toolName(), entry.note()));
		}
		permissions.setRoleTools(id, tools);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("roleId", id);
		body.put("tools", tools.stream().map(RoleTool::toolName).toList());
		return body;
	}

	/** 替换角色的目录前缀清单。前缀的合法性（非空、是路径、长度）在这一层之下判。 */
	@PutMapping(path = "/roles/{id}/directories", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> setRoleDirectories(@PathVariable Long id, @RequestBody RoleDirsRequest request) {
		List<RoleDirectory> directories = new ArrayList<>();
		for (DirEntry entry : request == null ? List.<DirEntry>of() : request.directoriesOrDefault()) {
			directories.add(RoleDirectory.of(id, entry.prefix(), entry.note()));
		}
		permissions.setRoleDirectories(id, directories);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("roleId", id);
		body.put("dirPrefixes", directories.stream().map(RoleDirectory::prefix).toList());
		return body;
	}

	// ------------------------------------------------------------------
	//  错误处理
	// ------------------------------------------------------------------

	/**
	 * 重名 → 409。
	 *
	 * <p>它不能是 500：两个人同时点"新建同名角色"不是服务器故障，
	 * 而界面要能给出"换一个名字"这句话 —— 那需要它知道自己撞的是唯一约束。
	 */
	@ExceptionHandler(PermissionConflictException.class)
	public ResponseEntity<Map<String, Object>> conflict(PermissionConflictException e) {
		log.info("权限写入冲突：{}", e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
	}

	/**
	 * 业务层的 {@code ResponseStatusException}（400 那一族）自带 HTTP 状态，
	 * 会由 {@code ErrorResponseAdvice} 处理成标准错误体 —— 这里不再接一遍。
	 * 接两遍的后果是同一个错误有两种响应体形状，而界面只能按其中一种解析。
	 */

	private ResponseEntity<Map<String, Object>> badRequest(String message) {
		return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
	}

	// ------------------------------------------------------------------
	//  装配
	// ------------------------------------------------------------------

	private Map<String, Object> account(Account account, List<String> roleNames) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", account.id());
		row.put("name", account.name());
		row.put("displayName", account.displayName() == null ? "" : account.displayName());
		row.put("status", account.status().name());
		row.put("statusLabel", account.status().label());
		row.put("usable", account.usable());
		row.put("roles", roleNames);
		// 刻意没有 passwordHash：口令散列不进任何响应（见类注释）
		return row;
	}

	private Map<String, Object> role(Role role, List<RoleTool> tools, List<RoleDirectory> directories) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", role.id());
		row.put("name", role.name());
		row.put("description", role.description() == null ? "" : role.description());
		row.put("access", role.access().name());
		row.put("accessLabel", role.access().label());
		row.put("toolNames", tools.stream().map(RoleTool::toolName).toList());
		row.put("dirPrefixes", directories.stream().map(RoleDirectory::prefix).toList());
		return row;
	}

	private List<String> rolesOfUser(Long userId) {
		return permissions.resolveById(userId)
				.map(resolved -> resolved.permissions().roles().stream().map(Role::name).toList())
				.orElse(List.of());
	}

	// ------------------------------------------------------------------
	//  请求体
	// ------------------------------------------------------------------

	/**
	 * 建号 / 改号的请求体。
	 *
	 * <p>{@code enabled} 用包装类型：不传时按 {@link #enabledOrDefault()} 走"启用"，
	 * 而<b>改号时</b>不传这三个字意味着"别动它"这个语义在界面上是明确的
	 * （编辑框里那个开关总是在）。
	 */
	public record UserRequest(String name, String password, String displayName, Boolean enabled) {

		boolean enabledOrDefault() {
			return enabled == null || enabled;
		}

	}

	public record PasswordRequest(String password) {
	}

	public record RolesRequest(List<Long> roleIds) {

		List<Long> roleIdsOrDefault() {
			return roleIds == null ? List.of() : roleIds;
		}

	}

	public record RoleRequest(String name, String description, String access) {
	}

	public record ToolEntry(String toolName, String note) {
	}

	public record RoleToolsRequest(List<ToolEntry> tools) {

		List<ToolEntry> toolsOrDefault() {
			return tools == null ? List.of() : tools;
		}

	}

	public record DirEntry(String prefix, String note) {
	}

	public record RoleDirsRequest(List<DirEntry> directories) {

		List<DirEntry> directoriesOrDefault() {
			return directories == null ? List.of() : directories;
		}

	}

}
