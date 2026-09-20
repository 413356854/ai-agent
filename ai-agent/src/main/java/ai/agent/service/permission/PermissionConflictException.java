package ai.agent.service.permission;

/**
 * 写权限时撞上了唯一约束（同名账号、同名角色）。
 *
 * <p>它是 409 而不是 500：两个人都点了"新建"不是服务器故障，
 * 而"这次没写成"与"写成了"必须能被调用方分开 —— 混成一个成功响应，
 * 界面会显示"已创建"，而库里那一行其实是别人的。
 *
 * <p>并发保护靠的是<b>数据库的唯一约束</b>，不是"先查再插"：
 * 后者在两个人同时提交时会双双通过检查（check-then-act），
 * 而那种重复在界面上看起来只是"这个名字出现了两次"。
 */
public class PermissionConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PermissionConflictException(String message) {
		super(message);
	}

}
