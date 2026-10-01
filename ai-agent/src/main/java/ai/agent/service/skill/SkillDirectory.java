package ai.agent.service.skill;

import ai.agent.config.SkillPromptProperties;
import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.permission.Role;
import ai.agent.entity.agent.permission.RoleDirectory;
import ai.agent.entity.agent.permission.UserPermissions;
import ai.agent.entity.agent.skill.AgentSkill;
import ai.agent.entity.agent.skill.SkillCatalog;
import ai.agent.service.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * <b>「执行者 → 角色 → 目录 → 技能」这一次解析</b> —— 技能终于被运行期读走的那一处。
 *
 * <h2>三段各回答一个问题，缺一段这条链路就不成立</h2>
 * <ol>
 *   <li><b>执行者是谁</b>：{@link PermissionService#resolveById(Long)} —— 返回的
 *       {@link UserPermissions} 里既有角色（写进提示词，让模型知道自己代表谁）也有目录前缀；</li>
 *   <li><b>他能用哪些目录</b>：就是那些角色目录前缀的原文（与 skill 管理那一页的
 *       「文件夹目录」选项集是<b>同一个来源</b>，见 {@code SkillService.Snapshot#folders}）；</li>
 *   <li><b>这些目录下有哪些技能</b>：按 {@link RoleDirectory#covers} 逐条判 ——
 *       <b>不是字符串前缀比较</b>，所以 {@code D:\work\demo} 不会命中 {@code D:\work\demo-other}，
 *       {@code ..} 也会先被折叠掉。判据与"能不能在这个目录下干活"是同一份。</li>
 * </ol>
 *
 * <h2>为什么按"文件夹落在前缀内"筛，而不是按技能的拥有者筛</h2>
 * 因为技能是一份<b>自足的内容</b>（谁写的只记在 {@code owner_id} 里，不参与运行期判定），
 * 而它放在哪个文件夹才是"它是给哪个范围用的"这件事的唯一表达。
 * 按拥有者筛的后果是：同一个人给团队写的技能，别人跑同一个目录时看不到 ——
 * 而"写了却没人用得上"正是这条链路最想解决的问题。
 *
 * <h2>解析不出来的情况一律退成"没有技能"，而不是抛</h2>
 * 账号不存在、账号停用、一个角色都没有 —— 这三种都返回 {@link SkillCatalog#none()}。
 * 理由与 {@code RunPermission.none()} 那条一致：<b>没有角色 = 没有权限</b>是正常状态，
 * 而不是故障；把它变成异常会让"新账号还没配权限"表现为"这个任务跑不起来"。
 * 唯一会抛的是调用方自己传了一个<b>指名道姓却不存在</b>的执行者 —— 那由
 * {@link #requireExecutor(Long)} 负责（界面上的入口要的是一句能读的提示）。
 */
@Service
public class SkillDirectory {

	private static final Logger log = LoggerFactory.getLogger(SkillDirectory.class);

	private final PermissionService permissions;

	private final SkillStore skills;

	private final SkillPromptProperties properties;

	public SkillDirectory(PermissionService permissions, SkillStore skills, SkillPromptProperties properties) {
		this.permissions = permissions;
		this.skills = skills;
		this.properties = properties;
	}

	/**
	 * 按执行者解析这一次能用的技能。
	 *
	 * @param executorId 执行者（{@code agent_user.id}）；{@code null} 表示没有指定执行者
	 *            （老链路、外部调用）—— 那时返回空目录，<b>不</b>退化成"注入全部技能"：
	 *            那会让"没指定执行者"看起来与"这个人的权限很宽"一模一样
	 */
	public SkillCatalog forExecutor(Long executorId) {
		if (executorId == null) {
			return SkillCatalog.none();
		}
		Optional<PermissionService.Resolved> resolved = permissions.resolveById(executorId);
		if (resolved.isEmpty()) {
			// 账号不存在 / 已停用：权限解析的既有口径就是"解析不出来"，这里沿用
			log.info("按执行者 #{} 解析技能目录：解析不出权限（账号不存在或已停用），本次不注入技能", executorId);
			return SkillCatalog.none();
		}
		return forPermissions(resolved.get().permissions());
	}

	/**
	 * 按已经解析好的权限装配。
	 *
	 * <p>它与 {@link #forExecutor(Long)} 的差别只有"权限从哪来"：那个自己去解析，
	 * 这个收一份现成的。两条链路各用一条 —— {@code /api/agent/ask} 那条只有执行者 id
	 * （它没有权限对象），而恢复路径与任务链路为了别的判断已经解析过一次
	 * （恢复要重建 {@code RunPermission}、任务要判授权），那份权限顺手传进来即可。
	 *
	 * <p>两个入口最终都走这里，所以"哪些技能算看得见"只有一份判据。
	 */
	public SkillCatalog forPermissions(UserPermissions permissions) {
		List<String> folders = foldersOf(permissions);
		List<AgentSkill> matched = skillsInFolderSet(permissions);
		SkillCatalog catalog = SkillCatalog.of(permissions.account().id(), displayName(permissions.account()),
				roleNamesOf(permissions), folders, matched, properties.maxCatalogSkills());
		log.info("技能目录已装配：{}（提示词上限 {} 条）", catalog.describe(), properties.maxCatalogSkills());
		return catalog;
	}

	/**
	 * <b>指定了执行者就必须是真实账号</b> —— 界面入口用它换一句能读的提示。
	 *
	 * <p>与 {@link #forExecutor(Long)} 的区别是"谁在问"：那个问的是"这次运行能注入什么"
	 * （解析不出来就不注入，任务照跑）；这个问的是"界面填的这个 id 到底是谁"
	 * （填错了要当场说出来，而不是跑一次没有技能的任务）。
	 *
	 * @throws IllegalArgumentException 账号表里没有这个人，或账号已停用
	 */
	public SkillCatalog requireExecutor(Long executorId) {
		if (executorId == null) {
			return SkillCatalog.none();
		}
		UserPermissions resolved = permissions.resolveById(executorId).map(PermissionService.Resolved::permissions)
				.orElseThrow(() -> new IllegalArgumentException(
						("执行者 id=%d 在账号表里解析不出权限（账号不存在，或已停用）—— "
								+ "技能的文件夹来自执行者的角色目录，所以它必须是一个真实可用的账号；"
								+ "留空则不注入任何技能。可用账号见 GET /api/permissions")
								.formatted(executorId)));
		return forPermissions(resolved);
	}

	/**
	 * <b>某个执行者能用哪些文件夹</b>（他全部角色的目录前缀，去重后按原文排序）——
	 * 与 {@link #forExecutor(Long)} 是同一份判据，只是不要技能那一半。
	 *
	 * <p>调用点只有一处：{@code SkillRuleSupplement} 在"任务人一条技能都没有"时
	 * 要给他建一条，而技能必须落在他有权限的目录下 —— 落在谁都取不到的目录里等于没写
	 * （技能是按"文件夹落在角色目录前缀内"筛的），而它会在列表上安安静静地躺着。
	 *
	 * <p>刻意<b>不</b>与 {@code DefaultSkillService#foldersOf} 合并：那一处服务的是界面上
	 * "当前登录人"的选项集，这一处问的是"任务人的角色目录" —— 主语不同，
	 * 合并会把界面那一条链路的改动牵到这里。
	 *
	 * <p>解析不出来（账号不存在 / 已停用 / 一个角色都没有）时返回空列表，
	 * 与 {@link #forExecutor(Long)} 的取向一致：那是正常状态，不是故障。
	 */
	public List<String> foldersForExecutor(Long executorId) {
		if (executorId == null) {
			return List.of();
		}
		return permissions.resolveById(executorId)
				.map(resolved -> foldersOf(resolved.permissions()))
				.orElseGet(List::of);
	}

	/**
	 * 一个账号全部角色的目录前缀，<b>按原文去重</b>（大小写不敏感地合并）。
	 *
	 * <p>去重的键是小写形式而留下的是原文：Windows 路径不区分大小写，
	 * 同一条前缀被两个角色授权时提示词里只该出现一次 ——
	 * 而"它是哪几个角色给的"这个事实由 {@code agent_role_dir} 那一侧保留。
	 * 与 {@code DefaultSkillService#foldersOf} 同一条口径（那一处服务的是界面上的选项集）。
	 */
	public static List<String> foldersOf(UserPermissions permissions) {
		Map<String, String> unique = new LinkedHashMap<>();
		for (RoleDirectory directory : permissions.directories()) {
			String prefix = directory.prefix();
			if (prefix != null && !prefix.isBlank()) {
				unique.putIfAbsent(prefix.toLowerCase(Locale.ROOT), prefix);
			}
		}
		List<String> folders = new ArrayList<>(unique.values());
		folders.sort(String::compareTo);
		return List.copyOf(folders);
	}

	/** 某个账号的角色名（提示词里写清"你是谁"用）；没有角色时为空列表。 */
	public static List<String> roleNamesOf(UserPermissions permissions) {
		return permissions.roles().stream().map(Role::name).toList();
	}

	/**
	 * 全库技能按"这次授权的目录集合"筛。
	 * <p>读的是<b>全部技能</b>（一次 {@code list()}）而不是"按文件夹逐次查"：
	 * 技能表是配置量级（几十条），一次读回再在内存里判前缀比 N 次查询更省，
	 * 而且判据（{@link UserPermissions#coversSkillFolder}）只有一份、在 Java 侧 ——
	 * SQL 里再写一遍前缀匹配就是第二份判据，两份必然漂移
	 * （{@code D:\work\demo} 命中 {@code demo-other} 那类错就是这么来的）。
	 */
	private List<AgentSkill> skillsInFolderSet(UserPermissions permissions) {
		if (permissions.directories().isEmpty()) {
			return List.of();
		}
		List<AgentSkill> matched = new ArrayList<>();
		for (AgentSkill skill : skills.skills()) {
			if (permissions.coversSkillFolder(skill.folder())) {
				matched.add(skill);
			}
		}
		return matched;
	}

	private static String displayName(Account account) {
		return account.displayName() == null || account.displayName().isBlank() ? account.name()
				: account.displayName();
	}

}
