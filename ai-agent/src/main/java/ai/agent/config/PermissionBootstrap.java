package ai.agent.config;

import ai.agent.entity.agent.permission.PermissionAccess;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.RoleTool;
import ai.agent.service.permission.PermissionService;
import ai.agent.service.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>初始管理员</b>：库里一个账号都没有时，建一个能进权限页的账号。
 *
 * <h2>为什么需要它，以及它为什么只在"一个账号都没有"时动作</h2>
 * 没有它，权限页打开是一张空表，而"怎么建第一个账号"这件事只能靠手工 SQL ——
 * 手工 SQL 的写入口没有校验（口令明文能插进去、工具名写错也不报错），
 * 于是第一个账号往往就是最不规范的那一个。
 *
 * <p>它<b>只在库是空的时候</b>动作，而且<b>不做任何更新</b>：
 * 一个"每次启动都把人恢复到某个状态"的引导程序，会让人改过的口令在某次重启后失效，
 * 而那种现象看起来像"系统把我的密码忘了"。库里只要有任意一个账号，这里就完全不碰。
 *
 * <h2>初建的管理员为什么是「全部工具」</h2>
 * 因为不给他全部工具，第一个账号就进不了任何一把工具，"权限管理"这个功能自己就没有入口。
 * 代价是它是本项目权限最宽的一个账号 —— 所以它的建立会打一条 WARN 级别的日志，
 * 并且口令来自配置（{@code ai.agent.permission.bootstrap-admin-password}），
 * 不是一个写死在代码里的默认值。
 */
@Configuration
@EnableConfigurationProperties(PermissionProperties.class)
@ConditionalOnProperty(prefix = "ai.agent.permission", name = "bootstrap-enabled", havingValue = "true",
		matchIfMissing = true)
public class PermissionBootstrap {

	private static final Logger log = LoggerFactory.getLogger(PermissionBootstrap.class);

	/** 初始角色的名字。**中文**：它在界面上就是一个角色名，与其它角色并列显示。 */
	static final String ADMIN_ROLE = "管理员";

	@Bean
	ApplicationRunner permissionBootstrapRunner(PermissionService permissions, PermissionProperties properties,
			ToolRegistry registry) {
		return args -> {
			List<ai.agent.entity.agent.permission.Account> existing = permissions.snapshot().accounts();
			if (!existing.isEmpty()) {
				log.info("权限库已有 {} 个账号（{}），不做任何引导动作", existing.size(),
						existing.stream().map(ai.agent.entity.agent.permission.Account::name).toList());
				return;
			}
			if (properties.bootstrapAdminPassword().isBlank()) {
				log.warn("权限库是空的，但没有配 ai.agent.permission.bootstrap-admin-password —— "
						+ "不建初始账号。要用界面就得先建一个能进权限页的账号");
				return;
			}

			var role = permissions.snapshot().roles().stream()
					.filter(r -> ADMIN_ROLE.equals(r.name()))
					.findFirst()
					.orElse(null);
			if (role == null) {
				role = permissions.createRole(ADMIN_ROLE, "初始管理员：全部工具 + 工作区根目录",
						PermissionAccess.ALL.storedValue());
			}
			// 工具的"全部"由角色的 access=ALL 表达，所以这里不逐个登记 ——
			// 逐个登记的话，将来新增一把工具就得回来补一行，而漏掉的那把表现为"管理员也用不了它"
			permissions.setRoleDirectories(role.id(), bootstrapDirectories(properties, role.id()));

			var account = permissions.createAccount(properties.bootstrapAdminName(),
					properties.bootstrapAdminPassword(), "初始管理员", true);
			permissions.setUserRoles(account.id(), List.of(role.id()));

			log.warn("已建立初始管理员账号「{}」（口令来自 ai.agent.permission.bootstrap-admin-password）—— "
					+ "它是本项目权限最宽的账号（角色「{}」= 全部工具），请在接上登录链路之前先改口令",
					account.name(), ADMIN_ROLE);
			log.info("权限库现状：账号 {} 个、角色 {} 个、可分配工具 {} 把", 1,
					permissions.snapshot().roles().size(), registry.names().size());
		};
	}

	/**
	 * 初始管理员的目录前缀。
	 *
	 * <p>取配置里的 {@code ai.agent.permission.bootstrap-dir-prefix}，<b>没有默认值</b>：
	 * 默认成 {@code "."}（= 进程工作目录）看起来方便，但它把一个"范围取决于从哪儿启动进程"的
	 * 授权写进了库 —— 换个工作目录重启，同一个前缀就指向别的地方，
	 * 而权限判定按绝对路径比较，于是它永远命中不了（一次静默的"管理员反而没有权限"）。
	 * 所以没有配就是没有目录授权，页面上会显示成 0 条前缀，一眼看得见。
	 *
	 * <p>配的那一条是相对路径时按进程工作目录解析成绝对路径再存，理由同上。
	 *
	 * @param roleId 这条前缀挂在哪个角色上。<b>必须传进来，不能省</b>：
	 *            {@link RoleDirectory} 的构造期拒收空 roleId（一条没有主语的前缀授权
	 *            在库里就是一行孤儿数据），而 {@code setRoleDirectories} 那一层虽然会
	 *            用参数覆盖它，却<b>在覆盖之前</b>先拿它去查角色（{@code requireRole}）——
	 *            传 null 会得到"角色不存在：#null"。
	 *            实测：这条路径只在"库是空的时候启动"才会走到，所以它曾经带着这个 bug
	 *            通过了全部自检（那时库里已经有账号，引导程序根本不动作）
	 */
	private List<RoleDirectory> bootstrapDirectories(PermissionProperties properties, Long roleId) {
		String root = properties.bootstrapDirPrefix();
		if (root.isBlank()) {
			log.warn("没有配 ai.agent.permission.bootstrap-dir-prefix —— 初始管理员不获得任何目录前缀"
					+ "（工具权限不受影响，他仍然是全部工具）");
			return List.of();
		}
		String prefix = root;
		try {
			Path path = Path.of(root);
			if (!path.isAbsolute()) {
				prefix = path.toAbsolutePath().normalize().toString();
				log.info("ai.agent.permission.bootstrap-dir-prefix 是相对路径，按进程工作目录解析为绝对路径：{}", prefix);
			}
		}
		catch (RuntimeException e) {
			log.warn("ai.agent.permission.bootstrap-dir-prefix「{}」不是一个可用路径，初始管理员不获得任何目录前缀",
					root);
			return List.of();
		}
		if (!Files.isDirectory(Path.of(prefix))) {
			// 目录现在不存在不是错误（工作区可以在别处建），但要留一句话：
			// 之后在工作区那一栏填路径时，人对不上就会来查这一行日志
			log.warn("初始管理员的目录前缀「{}」现在不是一个存在的目录 —— 等它建好之后权限判定才会命中", prefix);
		}
		List<RoleDirectory> out = new ArrayList<>();
		out.add(RoleDirectory.of(roleId, prefix,
				"初始管理员的目录范围（来自 ai.agent.permission.bootstrap-dir-prefix）"));
		return out;
	}

}
