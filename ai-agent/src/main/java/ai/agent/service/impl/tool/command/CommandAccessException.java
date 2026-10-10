package ai.agent.service.impl.tool.command;

/**
 * <b>命令执行工具拒绝一次调用</b> —— 本次运行没有工作区，或权限档位不许写。
 *
 * <h2>为什么它不复用 {@code FileAccessDeniedException}</h2>
 * 那个异常的名字与它的注释都锁在"文件路径"这件事上（"路径解析到工作区之外"）。
 * 本工具没有路径参数：它拒绝的两种理由分别是"没有可执行命令的根目录"与"这一档不许改"，
 * 两者都<b>不是</b>路径问题。共用一个类型会让"这次到底为什么被拒"在日志里要靠读消息才分得清
 * —— 而机器可读的 {@code code} 存在的意义正是"不用读消息就能分类"。
 *
 * <p>两条 {@code code} 的取值与文件工具那一侧<b>逐字相同</b>（不另起一套词）：
 * 报告与审计按 code 分类时，同一个原因不该有两个名字。
 *
 * <h2>为什么它要抛异常而不是返回 {@code Failure}</h2>
 * 判据与文件工具那一侧一致：这个判断发生在工具自己的代码里，拦下的是一场<b>不希望发生的执行</b>；
 * 把它算进"工具失败率"（{@code EXECUTION_ERROR}）也正确 ——
 * 一把命令工具被同一条越权的调用反复试探时，那正是"这道闸门在起作用"的样子。
 * 但每一个抛出点都<b>必须</b>在 {@code RunCommandTool} 里被转成回填文本，
 * 而不是让异常逃到执行器那里变成一句"工具内部错误"：模型要能看懂该改什么。
 */
public class CommandAccessException extends RuntimeException {

	/** 本次运行没有工作区目录 —— 命令没有可执行的工作目录。 */
	public static final String NO_WORKSPACE = "no_workspace";

	/** 权限档位不允许写（仅可查看档位下的任何执行）。 */
	public static final String NOT_PERMITTED = "not_permitted_by_permission_level";

	private final String code;

	public CommandAccessException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}

}
