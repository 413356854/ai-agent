package ai.agent.service.skill;

import ai.agent.entity.agent.skill.AgentSkill;

import java.util.List;

/**
 * 技能数据的存取契约 —— 两个 mapper 的一个收口。
 *
 * <h2>为什么要有这一层，而不是让服务直接注入 mapper</h2>
 * 与 {@code PermissionStore} / {@code PromptStore} 同一条规矩：
 * <b>SQL 在 mapper 层、口径在 store 实现里、调用方只看契约</b>。
 * 代价是每个方法多一行转发；换来的是"哪一次读查了哪张表"在服务层不必数，
 * 以及"没有数据库也想验一遍归属判定"这件事只需要一个内存实现。
 *
 * <h2>写侧只有一个 {@link #create}</h2>
 * 改与删都以"先按 id 读出来、判完归属再写"的形态出现在服务层 ——
 * 归属判定要读 {@code owner_id}，所以那一次读不能省。
 * store 里没有"按 id 删"这种一步到位的写：它没有主语可判。
 */
public interface SkillStore {

	/** 全部技能（不筛拥有者），最近修改的在前。 */
	List<AgentSkill> skills();

	/** 某个人的技能（界面登录之后走这一条）。 */
	List<AgentSkill> skillsOfOwner(Long ownerId);

	/** 按 id 取一条；不存在返回 {@code null}（调用方把"不存在"与"读失败"分开）。 */
	AgentSkill findById(Long id);

	/**
	 * 建一条技能，返回落库之后的那一份（带 id、时间与姓名）。
	 *
	 * <p>写入用的是记录里的 {@code ownerId} 与 {@code lastModifiedBy}；返回的是**重新读回来**的那一行 ——
	 * 拿调用方传进来的那一份回话的话，响应里的 id 与两个时间会是空的，
	 * 而界面上"刚建的那条"在列表里的位置（按 {@code updated_at} 排）也只有库里那一份说了算。
	 */
	AgentSkill create(AgentSkill skill);

	/** 改一条技能的内容（归属、创建时间、最后修改人之外的四列）。返回落库之后的那一份。 */
	AgentSkill update(Long id, String folder, String name, String description, String content, Long lastModifiedBy);

	/**
	 * 删一条技能。
	 *
	 * @return 真的删掉了返回 true；那一行已经不在了返回 false ——
	 *         两次点击 / 两个标签页同时删同一条时会出现后者，而它在界面上该说成"这条已经不在了"
	 *         而不是"删除失败"
	 */
	boolean delete(Long id);

	/** 某个账号全部角色的目录前缀（界面上的「文件夹目录」选项集）。 */
	List<String> folderPrefixesOfUser(Long userId);

}
