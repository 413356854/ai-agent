package ai.agent.service.impl.tool.file;

import ai.agent.config.WorkspaceFileProperties;
import ai.agent.entity.agent.AgentPermissionLevel;
import ai.agent.entity.tool.RunPermission;
import ai.agent.entity.tool.ToolCallContext;
import ai.agent.service.tool.AgentTool;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.ToolCallExecutor;
import ai.agent.service.tool.workspace.FileAccess;
import ai.agent.service.tool.workspace.FileAccessDeniedException;
import ai.agent.service.tool.workspace.WorkspaceBoundTool;
import ai.agent.service.tool.workspace.WorkspaceCallContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 五把文件工具的公共骨架：<b>把"这一次调用的工作区边界"定下来，并把拒绝原因转成回填文本</b>。
 *
 * <h2>它解决的两件事</h2>
 * <ol>
 *   <li><b>边界只解析一次</b>：{@link #onContext} 从上下文取工作区目录、按本次参数里的路径解析一次、
 *       把结果记下来。人工确认闸门读的是<b>同一个对象</b>（经
 *       {@link WorkspaceCallContext#access()}），工具 {@code invoke} 时用的也是它 ——
 *       两边不可能不一致（判据写在 {@link WorkspaceCallContext} 的类注释里）；</li>
 *   <li><b>拒绝的措辞收在 {@link #failure} 一处</b>：越界、没有工作区、档位不许，
 *       这三种都不是"实现炸了"，而是"模型该改一个参数"——
 *       它们必须变成带回填文本的结论，而不是让异常逃到执行器那里变成一句
 *       「工具内部错误」（那会让模型去重试同样的路径）。</li>
 * </ol>
 *
 * <h2>为什么状态是"一次调用一个对象"</h2>
 * 工具 bean 是单例，而工作区是<b>一次运行</b>的属性（阶段一那条教训：共享可变状态在虚拟线程下会串味）。
 * 所以这里只存一个<b>不可变的 {@link Call} 引用</b>，由 {@link #onContext} 整个替换 ——
 * 不做字段级的部分更新。并发下"我读到的是不是自己那一次"靠调用序保证：
 * 执行器在<b>同一个线程上</b>依次调 {@code onContext}、{@code invoke}，中间不切线程
 * （工具执行是同步阻塞调用）。
 *
 * <h2>写操作还有一道前置检查：档位</h2>
 * {@link #requireWritable} 是"仅可查看档位下不许写"的唯一落点。
 * 它不做成"让人确认"：那一档的字面意思就是不许改，转成人工确认等于给人一个
 * "点通过就绕过档位"的后门（判据写在 {@code AgentPermissionPolicy} 的类注释里）。
 */
public abstract class FileToolSupport<P> implements AgentTool<P>, WorkspaceBoundTool, WorkspaceCallContext {

	/** 参数 JSON 里的路径字段名。 */
	private static final String PATH_FIELD = "path";

	protected final Logger log = LoggerFactory.getLogger(getClass());

	private final WorkspaceFileProperties limits;

	/** 最近一次调用的边界。默认值是"最严的一侧"：既没有工作区、也不在工作区内。 */
	private volatile Call current = Call.none();

	protected FileToolSupport(WorkspaceFileProperties limits) {
		this.limits = limits;
	}

	/**
	 * 一次调用的边界（不可变）。
	 *
	 * @param access 这一次运行的工作区访问；没有工作区时是 {@link FileAccess#none()}（**不是** null）
	 * @param level 本次运行的权限档位；{@code null} 表示没有权限上下文（按最严的一档算）
	 * @param targetPath 本次参数里那条路径的原始写法（空串 = 没解析出路径）
	 * @param workspaceBound 这条路径算不算在工作区内（没有工作区时为 false）
	 * @param note 给日志与审批记录的一行说明
	 */
	protected record Call(FileAccess access, AgentPermissionLevel level, String targetPath, boolean workspaceBound,
			String note) {

		static Call none() {
			return new Call(FileAccess.none(), null, "", false, "（本次调用还没有确定工作区边界）");
		}

		boolean hasWorkspace() {
			return access.hasWorkspace();
		}
	}

	// ------------------------------------------------------------------
	//  WorkspaceCallContext：给闸门与 MCP 暴露侧看的两个答案
	// ------------------------------------------------------------------

	@Override
	public final FileAccess access() {
		return current.access();
	}

	@Override
	public final String targetPath() {
		return current.targetPath();
	}

	@Override
	public final String workspaceNote() {
		return current.note();
	}

	/**
	 * 执行器在闸门之前调用它：<b>解析这一次的路径并记下边界</b>。
	 *
	 * <p>路径从<b>原始参数 JSON</b>里取，而不是从强类型参数取：闸门读的必须是
	 * "真正会执行的那份参数"的结论，而强类型参数在这一层拿不到（执行器刻意没把它交给闸门）。
	 * 取法只有一条字段（{@value #PATH_FIELD}），并且只做"取字符串"这一件事 ——
	 * 真正的参数校验仍然由执行器的 JSR-380 那一道负责，这里不做第二套。
	 */
	@Override
	public final void onContext(ToolCallContext context) {
		setCallContext(context);
		if (!current.workspaceBound()) {
			// 只记一行日志：这里**不抛**。抛了就没有"这次在区内还是区外"这个结论，
			// 而闸门正需要它才能判档位（没有工作区 = 最严的一侧 = 写操作要人点头）。
			// 真正要用路径时由 invocation() 抛出同一条可读说明
			log.debug("文件工具「{}」的本次调用不在工作区内：{}", name(), current.note());
		}
	}

	@Override
	public final void setCallContext(ToolCallContext context) {
		RunPermission permission = context == null ? null : context.runPermission();
		current = resolve(permission, pathFromArguments(context));
	}

	/**
	 * 把上下文与参数里的路径解析成边界。
	 *
	 * <p>解析失败（越界、非法路径）在这里<b>不抛</b>：那种情况下这次调用算"不在工作区内"，
	 * 于是写操作会去问人 —— 而工具真正执行时会抛出同一条说明。判据是：
	 * 这一步只负责"定边界"，不负责"下结论"。
	 */
	private Call resolve(RunPermission permission, String targetPath) {
		if (permission == null || !permission.hasWorkspace()) {
			return new Call(FileAccess.none(), permission == null ? null : permission.level(), targetPath, false,
					"本次运行没有工作区目录（或没有权限上下文）");
		}
		FileAccess access = FileAccess.of(permission.workspaceDir());
		AgentPermissionLevel level = permission.level();
		if (targetPath == null || targetPath.isBlank()) {
			return new Call(access, level, "", true, "工作区：" + access.describe());
		}
		boolean inside;
		try {
			access.resolve(targetPath, false);
			inside = true;
		}
		catch (FileAccessDeniedException e) {
			inside = false;
		}
		return new Call(access, level, targetPath, inside,
				"路径「%s」%s（工作区：%s）".formatted(targetPath, inside ? "在工作区内" : "在工作区之外",
						access.describe()));
	}

	// ------------------------------------------------------------------
	//  子类用的入口与小工具
	// ------------------------------------------------------------------

	/**
	 * 取本次调用的边界，并确认它有工作区可用。
	 *
	 * @throws FileAccessDeniedException 上下文里没有工作区 —— 文件工具没有可解析路径的根
	 */
	protected final Call invocation() {
		Call call = current;
		if (!call.hasWorkspace()) {
			throw new FileAccessDeniedException(FileAccessDeniedException.NO_WORKSPACE,
					("本次运行没有工作区目录（发起任务时的 workspaceDir 为空），文件工具「%s」无法解析路径。"
							+ "请告诉用户：重新发起任务并填入工作区目录；或者直接用文字回答 —— "
							+ "不要改写成绝对路径重试，那条路同样走不通。").formatted(name()));
		}
		return call;
	}

	/**
	 * 写操作的前置检查：<b>仅可查看档位下不许写</b>。
	 *
	 * <p>其余两档放行 —— 「工作区内修改」与「完全权限」的差别不在"能不能写"，
	 * 而在"要不要人点头"，那个判断在闸门里（它读的正是 {@link #access()}）。
	 */
	protected final void requireWritable(Call call, String rawPath) {
		AgentPermissionLevel level = call.level();
		if (level == null || !level.workspaceWrite()) {
			throw new FileAccessDeniedException(FileAccessDeniedException.NOT_PERMITTED,
					("权限档位是「%s」，这一档不允许改任何东西（它是「仅可查看」）。这次要写的路径是「%s」。"
							+ "请把这一点告诉用户：要写文件需要重新发起任务并选「工作区内修改」或「完全权限」——"
							+ "原样重试不会有不同结果。").formatted(FileAccess.labelOf(level), rawPath));
		}
	}

	/**
	 * 把一次拒绝转成回填给模型的结论。
	 *
	 * <p>三种拒绝（越界 / 没有工作区 / 档位不许）都<b>不可重试</b>：
	 * 同样的参数再试一次一定还是被拒，而回填里已经写清了该改什么。
	 * 这一点必须显式说 —— 不说的话模型最常见的动作就是原样重试。
	 */
	protected final AgentToolResult failure(FileAccessDeniedException e) {
		log.warn("文件工具「{}」拒绝了一次调用 code={}：{}", name(), e.code(), e.getMessage());
		return new AgentToolResult.Failure(e.code(), e.getMessage(), e.retriable());
	}

	/** 工作区目录不存在时，补一句能读懂的原因（读/写失败时用）。 */
	protected static String withMissingNote(FileAccess access, String message) {
		return access.missingNote().map(note -> message + note).orElse(message);
	}

	/** 日志里的一行摘要（超长截断，避免把整份文件写进日志）。 */
	protected static String oneLine(String text, int maxChars) {
		String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
		return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
	}

	/** 文件类工具的上限（读多少行、写多大、返回多少条都在里面）。 */
	protected WorkspaceFileProperties limits() {
		return limits;
	}

	/**
	 * 从原始参数 JSON 里取出路径字段。
	 *
	 * <p>用手写的 Jackson 树的读法而不是"反序列化成参数对象再反射取 path"：
	 * 后者会在参数**还没通过校验**时就去构造强类型对象，于是"校验失败"与"路径取不到"
	 * 会混成一件事。这里只读一个字符串，读不到就是空串（= 按最严的一侧算）。
	 */
	private static String pathFromArguments(ToolCallContext context) {
		String raw = context == null ? null : context.arguments();
		if (raw == null || raw.isBlank()) {
			return "";
		}
		try {
			JsonNode tree = ToolCallExecutor.strictMapper().readTree(raw);
			JsonNode path = tree == null ? null : tree.get(PATH_FIELD);
			return path != null && path.isTextual() ? path.asText() : "";
		}
		catch (Exception e) {
			// 参数不是合法 JSON：那是执行器门禁 ③ 的事（PARSE_ERROR），这里不重复报错
			return "";
		}
	}

}
