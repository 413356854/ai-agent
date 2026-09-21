package ai.agent.service.tool.workspace;

/**
 * <b>文件工具拒绝执行一次调用</b> —— 路径越出工作区、或授权被权限档位拦住。
 *
 * <h2>为什么它不是实现 bug，而是一条要回填给模型的结论</h2>
 * 本项目的分层是：工具自己表达"没查到 / 不合法"（{@code AgentToolResult.Failure}），
 * 只有"实现写错了"才抛异常（{@code ToolCallExecutor} 会把它记成
 * {@code EXECUTION_ERROR} 并算进熔断失败率）。
 *
 * <p>抛异常在归属上是对的：这个判断发生在工具自己的代码里，而它拦下的是一场
 * <b>不希望发生的文件操作</b>；把它算进"工具失败率"也正确 —— 一把文件工具
 * 被同一份越界路径反复调用时，那正是"这道闸门在起作用"的样子。
 *
 * <p>所以下面每一个抛出点都必须经过 {@link FileTool#failure} 转成回填文本，
 * 而不是让异常逃到执行器那里变成一句"工具内部错误"。判据很直接：
 * <b>模型改一个参数就能接着干活，而它必须知道该改哪里。</b>
 *
 * @param code 机器可读的短码（报告与断言用它，不用中文）
 * @param message 给模型看的一句话，必须带上下一步能做什么
 * @param retriable 同样的参数再试一次有没有可能成功（越界路径一律 false）
 */
public class FileAccessDeniedException extends RuntimeException {

	/** 路径解析到工作区之外（读与写都拒；写在第②条之外还要过档位）。 */
	public static final String OUTSIDE_WORKSPACE = "path_outside_workspace";

	/** 这次运行没有工作区目录 —— 文件工具没有可解析的相对路径根。 */
	public static final String NO_WORKSPACE = "no_workspace";

	/** 权限档位不允许这次写（仅可查看档位下的任何写、工作区内修改档位下写工作区之外）。 */
	public static final String NOT_PERMITTED = "not_permitted_by_permission_level";

	private final String code;

	private final boolean retriable;

	public FileAccessDeniedException(String code, String message) {
		this(code, message, false);
	}

	public FileAccessDeniedException(String code, String message, boolean retriable) {
		super(message);
		this.code = code;
		this.retriable = retriable;
	}

	public String code() {
		return code;
	}

	public boolean retriable() {
		return retriable;
	}

}
