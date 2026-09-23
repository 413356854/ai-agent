package ai.agent.service.skill;

import ai.agent.entity.agent.permission.Account;
import ai.agent.entity.agent.skill.AgentSkill;
import ai.agent.service.permission.PermissionInvalidException;
import ai.agent.service.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SkillService} 的实现 —— <b>技能管理唯一的写入口</b>。
 *
 * <h2>三条纪律，每条都对应一种"不报错的错"</h2>
 * <ol>
 *   <li><b>拥有者只在服务端赋值</b>：请求体里没有这个字段，写进去的永远是本次登录的那个人。
 *       让客户端传的写法，任何人打一次接口就能把记录写成别人的，而记录确实建出来了、界面上看起来正常；</li>
 *   <li><b>改与删先读再判</b>：判据是那一行的 {@code owner_id}，不是请求里说的谁。
 *       少了这一次读，"改别人的记录"与"改自己的"在代码上长得一模一样；</li>
 *   <li><b>文件夹必须在自己的选项集里</b>：选项集是"他全部角色的目录前缀"，
 *       而界面上的下拉框只是它的一个显示出口 —— 校验只能在这里做（curl 也走这条路）。</li>
 * </ol>
 *
 * <h2>归属判定只有一处</h2>
 * {@link #owned}：不存在与不是他的合成一句 400。分开成 404 与 403 也可以，
 * 但那样"这条记录存在但不是你的"会告诉调用方**记录确实存在** ——
 * 而这一页的读是完全公开的，所以藏这个事实没有意义；合成一句反而让人一眼知道下一步该做什么。
 *
 * <h2>为什么这里不接 {@code DataAccessException}</h2>
 * "撞唯一键"（409）与"库出了别的问题"（500）的分类发生在<b>包装最内层</b> ——
 * 就在 mapper 调用外面的 {@code JdbcSkillStore#writeFailure}。这一层再接一次就是第二份判据，
 * 而两份判据漂移的表现是"某一条路径上重名回 500"（实测踩过：同名创建与同名改名都回 500，
 * 因为当时分类写在这一层，而 store 已经把异常包成 {@code SkillStoreException} 了）。
 */
@Service
public class DefaultSkillService implements SkillService {

	private static final Logger log = LoggerFactory.getLogger(DefaultSkillService.class);

	private final SkillStore store;

	private final PermissionService permissions;

	public DefaultSkillService(SkillStore store, PermissionService permissions) {
		this.store = store;
		this.permissions = permissions;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public Snapshot snapshot(Long userId) {
		if (userId == null) {
			// 未登录：全部记录、没有文件夹选项（文件夹是"这个人的角色给的"，没有他就没有这一份）
			return new Snapshot(null, "", store.skills(), List.of(), false);
		}
		Account account = account(userId);
		return new Snapshot(userId, name(account), store.skillsOfOwner(userId), foldersOf(userId), true);
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@Override
	public AgentSkill create(Long userId, String folder, String name, String description, String content) {
		Account account = account(userId);
		List<String> folders = foldersOf(userId);
		// 文件夹按"原文"存，但校验是大小写不敏感的：Windows 路径不区分大小写，
		// 而选项集里的那一串来自角色配置（d:\work\demo 与 D:\work\Demo 指的是同一个目录）
		String chosen = inFolderSet(folder, folders);
		AgentSkill draft = new AgentSkill(null, chosen, name, description, content, account.id(), name(account),
				account.id(), name(account), null, null);
		// ⚠️ 这里**不接 DataAccessException**：撞唯一键的分类发生在 store 里（`JdbcSkillStore#writeFailure`），
		// 接在这里会分成第二处判据，而两处判据漂移的表现是"某一条路径上重名回 500"
		AgentSkill saved = store.create(draft);
		log.info("新建技能 #{}：{} / {}（拥有者 #{} {}）", saved.id(), saved.folder(), saved.name(), saved.ownerId(),
				saved.displayOwner());
		return saved;
	}

	@Override
	public AgentSkill update(Long userId, Long id, String folder, String name, String description, String content) {
		Account account = account(userId);
		AgentSkill current = owned(userId, id);
		List<String> folders = foldersOf(userId);
		// 空文件夹 = 不改这一列：界面上那个下拉总是带值提交，而"没带"的语义是"这一格我没动"。
		// 落成"把文件夹清空"会让一次只想改正文的编辑顺手把记录挪到一个空文件夹里
		String chosen = folder == null || folder.isBlank() ? current.folder() : inFolderSet(folder, folders);
		String nextName = name == null || name.isBlank() ? current.name() : name;
		// 说明与正文的两条空语义不同：不传 = 保留，空串 = 清空（界面上把内容删光就是清空）
		String nextDescription = description == null ? current.description() : blankToNull(description);
		String nextContent = content == null ? current.content() : blankToNull(content);
		new AgentSkill(current.id(), chosen, nextName, nextDescription, nextContent, current.ownerId(), null, null,
				null, null, null);
		AgentSkill saved = store.update(id, chosen, nextName, nextDescription, nextContent, account.id());
		log.info("更新技能 #{}：{} / {}（最后修改人 #{} {}）", saved.id(), saved.folder(), saved.name(),
				saved.lastModifiedBy(), saved.displayLastModifiedBy());
		return saved;
	}

	@Override
	public AgentSkill delete(Long userId, Long id) {
		account(userId);
		AgentSkill current = owned(userId, id);
		if (!store.delete(id)) {
			// 读到了、删的时候不在了：两个标签页同时删同一条。这不是失败，而是一句"它已经不在了"
			throw new PermissionInvalidException("技能 #" + id + " 刚刚已经被删掉了（这次没删到任何一行）");
		}
		log.warn("删除技能 #{}：{} / {}（拥有者 #{}）", id, current.folder(), current.name(), current.ownerId());
		return current;
	}

	// ------------------------------------------------------------------
	//  内部
	// ------------------------------------------------------------------

	/**
	 * 本次登录的人。<b>缺人是 400 而不是"当作未登录放行"</b> ——
	 * 放行的那个分支在界面上与正常路径长得一样，而写进去的归属会是错的。
	 */
	private Account account(Long userId) {
		if (userId == null) {
			throw new PermissionInvalidException("缺少 userId（当前登录的人）—— 未登录不能新增 / 修改 / 删除技能；"
					+ "请先在页头「模拟登录」里选一个人");
		}
		return permissions.findAccountById(userId).orElseThrow(() -> new PermissionInvalidException(
				"账号表里没有 id=" + userId + " 这个人 —— 拥有者必须是账号表里真实存在的那一个"));
	}

	/** 这一行在不在、是不是他的。判据只有这一处（见类注释）。 */
	private AgentSkill owned(Long userId, Long id) {
		if (id == null) {
			throw new PermissionInvalidException("缺少技能 id");
		}
		AgentSkill current = store.findById(id);
		if (current == null) {
			throw new PermissionInvalidException("技能不存在：#" + id);
		}
		if (!current.ownerId().equals(userId)) {
			throw new PermissionInvalidException(("技能 #%d 的拥有者是 #%d（%s），不是当前登录的 #%d —— "
					+ "只能修改 / 删除自己拥有的技能").formatted(id, current.ownerId(), current.displayOwner(), userId));
		}
		return current;
	}

	/** 这个人全部角色的目录前缀，去重后按原文排序（同一条前缀被两个角色授权时只出现一次）。 */
	private List<String> foldersOf(Long userId) {
		Map<String, String> unique = new LinkedHashMap<>();
		for (String prefix : store.folderPrefixesOfUser(userId)) {
			if (prefix != null && !prefix.isBlank()) {
				unique.putIfAbsent(prefix.toLowerCase(java.util.Locale.ROOT), prefix);
			}
		}
		List<String> folders = new ArrayList<>(unique.values());
		folders.sort(String::compareTo);
		return List.copyOf(folders);
	}

	/**
	 * 把界面上选的那个文件夹对上选项集，返回<b>选项集里的原文</b>。
	 *
	 * <p>对上之后存的是选项集里的写法而不是请求里的写法：同一个人两次提交
	 * {@code d:\work\demo} 与 {@code D:\work\demo} 时，库里只该有一种写法 ——
	 * 否则唯一键看起来拦不住重复（两个字符串确实不同），而列表上它们是同一个文件夹。
	 */
	private static String inFolderSet(String folder, List<String> folders) {
		if (folder == null || folder.isBlank()) {
			throw new PermissionInvalidException("缺少 folder（文件夹目录）—— 它是登录人角色的目录前缀之一，"
					+ "现在可选的：" + (folders.isEmpty() ? "一个都没有（他的角色都没有配目录前缀）" : folders));
		}
		String wanted = folder.strip();
		for (String candidate : folders) {
			if (candidate.equalsIgnoreCase(wanted)) {
				return candidate;
			}
		}
		throw new PermissionInvalidException(("文件夹「%s」不在当前登录人的可选范围里 —— 这个范围是他全部角色的"
				+ "目录前缀（agent_role_dir），只能建在其中之一；现在可选的：%s")
				.formatted(wanted, folders.isEmpty() ? "一个都没有（他的角色都没有配目录前缀）" : folders));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String name(Account account) {
		return account.displayName() == null || account.displayName().isBlank() ? account.name()
				: account.displayName();
	}

}
