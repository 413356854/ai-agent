package ai.agent.service.skill;

/**
 * 写技能时撞上了唯一约束（同一个人、同一个文件夹下的同名技能）。
 *
 * <p>它是 409 而不是 500：两个标签页同时点"新建"不是服务器故障，
 * 而"这次没写成"与"写成了"必须能被调用方分开 —— 混成一个成功响应，
 * 界面会显示"已创建"，而库里那一行其实是另一次提交写的。
 *
 * <p>并发保护靠的是<b>数据库的唯一约束</b>（{@code uk_agent_skill_owner_folder_name}），
 * 不是"先查再插"：后者在两个人同时提交时会双双通过检查（check-then-act），
 * 而那种重复在列表上看起来只是"这个名字出现了两次"。
 */
public class SkillConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SkillConflictException(String message) {
		super(message);
	}

}
