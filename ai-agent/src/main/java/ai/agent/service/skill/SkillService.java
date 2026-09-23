package ai.agent.service.skill;

import ai.agent.entity.agent.skill.AgentSkill;

import java.util.List;

/**
 * 技能管理的读写契约（应用管理区 · skill管理）。
 *
 * <h2>★ "当前登录人"是每一个写方法的第一个参数</h2>
 * 这一层<b>没有登录链路</b>（见 {@code SkillController} 的类注释：能访问端口的人就是全部身份），
 * 所以"我是谁"由界面从页头的模拟登录里带上来。带上来之后它就不是一个可选参数了：
 * 服务层拿它判两件事 —— <b>拥有者是谁</b>（新增时写进去）与<b>这条记录是不是你的</b>（改 / 删时）。
 *
 * <p>把它做成"可选"的后果是：不传时只能放行，而放行的那个分支在界面上与正常路径长得一样。
 * 所以三个写方法都要求它非空，缺了当场 400。
 *
 * <h2>为什么没有"按 id 直接改 / 直接删"</h2>
 * 改与删都以 {@code findById} 开头：归属判定要读那一行的 {@code owner_id}，
 * 而"先删再判"这种顺序在并发下会让两个人同时越过检查。
 * 读一次、判一次、写一次 —— 判据只有一处（{@link #owned}）。
 *
 * <h2>文件夹选项集由服务层算，不由客户端给</h2>
 * {@link #snapshot} 里的 {@code folders} 是<b>登录人自己能用的那些目录前缀</b>
 * （他全部角色的并集）。客户端只送一个字符串，而它必须落在这份选项集里 ——
 * 一个只靠下拉框拦的校验，用 curl 打一次就绕过去了。
 */
public interface SkillService {

	/** 技能页要的一切，一次取回：记录（{@code userId} 非空时只给这个人的）+ 他能用的文件夹。 */
	Snapshot snapshot(Long userId);

	/**
	 * 建一条技能。<b>拥有者与最后修改人都写 {@code userId}</b> —— 请求体里没有这两个字段。
	 *
	 * @throws SkillConflictException 同一个人的同一个文件夹下已经有同名技能（409）
	 * @throws ai.agent.service.permission.PermissionInvalidException 缺人 / 少字段 / 文件夹越界（400）
	 */
	AgentSkill create(Long userId, String folder, String name, String description, String content);

	/**
	 * 改一条技能的内容（归属不变）。<b>只有拥有者能改</b>。
	 *
	 * @throws ai.agent.service.permission.PermissionInvalidException 缺人 / 记录不存在 / 不是他的（400）
	 */
	AgentSkill update(Long userId, Long id, String folder, String name, String description, String content);

	/**
	 * 删一条技能。<b>只有拥有者能删</b>。
	 *
	 * <p>返回<b>被删掉的那一份</b>：界面上要说的是"已删除《名字》"，
	 * 而删完之后"删的是哪一条"就查不到了（与 {@code deleteRole} 在删除之前数
	 * {@code affectedAccounts} 是同一条理由）。它由归属判定那一次读顺手带出来 ——
	 * 为了回话再查一次列表是白读一次。
	 *
	 * @throws ai.agent.service.permission.PermissionInvalidException 缺人 / 记录不存在 / 不是他的（400）
	 */
	AgentSkill delete(Long userId, Long id);

	/**
	 * 技能页的读模型。
	 *
	 * @param userId 这次按谁筛（回显给界面用；未登录时为空 —— 界面据此显示"这是全部记录"）
	 * @param userName 那个人的姓名（界面上的说明句直接用它；未筛时为空串）
	 * @param skills 记录（按最近修改排序）
	 * @param folders 这个人的文件夹选项集（角色目录前缀的并集，已去重；未登录时为空）
	 * @param ownershipJudged 本次是否按归属筛过。它与 {@code skills} 是否为空是两件事 ——
	 *        "他有 0 条技能"与"这次没筛"在 JSON 里长得一样，而界面上要说的话不同
	 */
	record Snapshot(Long userId, String userName, List<AgentSkill> skills, List<String> folders,
			boolean ownershipJudged) {
	}

}
