package ai.agent.entity.tool;

import ai.agent.entity.agent.AgentPermissionLevel;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * <b>这一次运行的执行前提</b>：在哪个目录下、被授予了多大权限。
 *
 * <h2>为什么把两件事放进同一个对象</h2>
 * 因为它们的来源、生命周期与断言方式完全一致：都是<b>调用方在任务开始之前就给出的事实</b>
 * （与 {@code intent} / {@code tenantId} 同一类），都会被落进 {@code agent_run} 的同一行，
 * 并且都要在断点恢复时被重新构造出来。分成两个参数传一路，
 * 只会在每个签名上多一个位置参数 —— 而那正是 {@code AgentRunStore.Start} 当初被包成对象的原因。
 *
 * <h2>工作区目录当前不改变任何工具的行为</h2>
 * 本项目的工具全是"读知识库 / 读本应用自己的数据库 / 建一条工单"，
 * <b>没有任何一把工具会去读写这个目录</b>。所以本字段现在的作用是三件：
 * <ol>
 *   <li><b>必填校验</b>：调用方必须给出一个真实存在、且确实是目录的路径 ——
 *       一个填错的目录会当场被拒绝，而不是在任务跑完之后才被当成"没用上"；</li>
 *   <li><b>权限判定的依据</b>：{@link AgentPermissionLevel#WORKSPACE_WRITE} 的语义是
 *       "允许在这个目录里改东西"，所以这个目录必须是一次运行的一部分；</li>
 *   <li><b>审计字段</b>：落库并全程显示。将来真的加上文件工具时，
 *       "那次是在哪个目录下跑的"已经有据可查，不必回头补历史。</li>
 * </ol>
 *
 * <h2>为什么"目录已经不存在"不算致命</h2>
 * 新任务在入口就被拦下（{@link #validateExistingDirectory()}）。
 * 但<b>恢复一次历史运行</b>时，那个目录完全可能已经被删掉 ——
 * 那时抛出去会让一次本来能跑完的任务永远卡住。所以恢复路径只记一条 WARN、
 * 按"没有额外授权"继续（见 {@code ResumableAgentService.resume}），
 * 而不是把整个恢复流程炸掉。
 *
 * @param workspaceDir 工作区目录（<b>去掉了尾部斜杠的原始写法</b>，可以是相对路径 ——
 *            相对路径按 Java 进程的工作目录解析，与 {@code ai.rag.corpus.location} 里
 *            {@code file:} 那条路径同一条规矩）。<b>不允许为空</b>：
 *            "没填"与"填了一个不存在的目录"是两件事，但它们的后果一样（这次运行没有工作区），
 *            所以都在构造期拒绝，而不是让 null 一路传到落库那一层
 * @param level 权限档位；{@code null} 一律当成 {@link AgentPermissionLevel#READ_ONLY}
 *            （"没写明"等于"没有额外授权"）
 */
public record RunPermission(String workspaceDir, AgentPermissionLevel level) {

	/**
	 * 工作区路径的长度上限。
	 *
	 * <p>取值依据：Windows 的 {@code MAX_PATH} 是 260 字符，而更长的路径要加
	 * {@code \\?\} 前缀才可用；Linux 的 {@code PATH_MAX} 是 4096。
	 * 512 覆盖两边的日常使用，同时保证 {@code agent_run.workspace_dir} 这一列
	 * 是一个短字段（同长度的字符串出现在每一行的历史列表里）。
	 *
	 * <p>超限<b>不静默截断</b>：截断一个路径得到的目录与它指向的可能是两个完全不同的地方，
	 * 而那种错不会报错。
	 */
	public static final int MAX_WORKSPACE_CHARS = 512;

	/**
	 * 校验 + 归一。
	 *
	 * <p><b>空路径是允许的</b>，但只能由 {@link #none()} 与 {@link #ofStored} 造出来 ——
	 * 界面上那条"必填"由 {@code AgentController} 判（它要给的是一句能读的中文提示，
	 * 而不是一个异常），这里管的是"存下来的事实"。
	 * 两处的判据指向同一件事，但只有一处负责措辞。
	 */
	public RunPermission {
		if (workspaceDir == null) {
			workspaceDir = "";
		}
		workspaceDir = workspaceDir.isBlank() ? "" : stripTrailingSeparators(workspaceDir.strip());
		if (workspaceDir.length() > MAX_WORKSPACE_CHARS) {
			throw new IllegalArgumentException(
					"工作区目录过长（%d 字符 > 上限 %d）：%s…".formatted(workspaceDir.length(), MAX_WORKSPACE_CHARS,
							workspaceDir.substring(0, 80)));
		}
		level = level == null ? AgentPermissionLevel.READ_ONLY : level;
	}

	/**
	 * 没有工作区、没有额外授权。
	 *
	 * <p>只给<b>非界面</b>的调用方用（历史数据、外部链路、以及那些签名里
	 * 还没有工作区概念的老入口）。它<b>不是</b>"随便一个默认目录"——
	 * 用 {@link #none()} 起的新任务在 {@code AgentController} 那一层就会被拦下，
	 * 因为 {@link #hasWorkspace()} 是 false。
	 */
	public static RunPermission none() {
		return new RunPermission("", AgentPermissionLevel.READ_ONLY);
	}

	/**
	 * 从库里的两列重建（恢复历史运行时用）。
	 *
	 * <p>与 {@link #none()} 一样<b>不校验目录是否存在</b>：一个被删掉的工作区
	 * 不该让一次本来能跑完的恢复永远卡住。判据由调用方按需要自己调
	 * {@link #validateExistingDirectory()}。
	 */
	public static RunPermission ofStored(String workspaceDir, AgentPermissionLevel level) {
		return new RunPermission(workspaceDir == null ? "" : workspaceDir,
				level == null ? AgentPermissionLevel.READ_ONLY : level);
	}

	/** 这次运行有没有工作区（界面发起的一律有；历史数据与外部链路可能没有）。 */
	public boolean hasWorkspace() {
		return !workspaceDir.isBlank();
	}

	/** 这一次的写操作要不要人点头（读操作不看档位，见 {@link AgentPermissionLevel}）。 */
	public boolean allowsWriteWithoutApproval() {
		return level.allowsWriteWithoutApproval();
	}

	/**
	 * 校验这个目录<b>现在</b>确实存在、而且确实是一个目录。
	 *
	 * <p>两条都查，因为它们的后果不同却都拦在这里最省事：
	 * 路径不存在 → 多半是打错字；路径存在但是个文件 → 多半是把"文件"当成了"目录"填。
	 * 后者如果放过，将来真的按目录去读时会得到一个"读取文件当目录"的错误，
	 * 而那个错误的位置离填错的地方很远。
	 *
	 * @throws IllegalArgumentException 路径不合法、不存在、或者不是目录
	 * @throws SecurityException 路径存在但当前进程无权访问
	 */
	public void validateExistingDirectory() {
		Path path;
		try {
			path = Path.of(workspaceDir);
		}
		catch (InvalidPathException e) {
			throw new IllegalArgumentException("工作区目录不是一个合法的路径：" + workspaceDir);
		}
		if (!Files.exists(path)) {
			throw new IllegalArgumentException(
					"工作区目录不存在：" + workspaceDir + "（相对路径按 Java 进程的工作目录解析）");
		}
		if (!Files.isDirectory(path)) {
			throw new IllegalArgumentException("工作区目录指向的不是一个目录：" + workspaceDir);
		}
	}

	/**
	 * 去掉尾部的路径分隔符，并且<b>只在确实有剩余字符时</b>去掉。
	 *
	 * <p>边界很具体：{@code "/"} 去成空串就变成了"没有工作区"，
	 * 而它本来是一个合法的目录（根目录）；{@code "C:\"} 同族。
	 */
	private static String stripTrailingSeparators(String value) {
		String out = value;
		while (out.length() > 1 && (out.endsWith("/") || out.endsWith("\\"))) {
			// "C:\" 这种"盘符 + 分隔符"只剩 3 个字符时不能再削，否则会变成 "C:"
			if (out.length() == 3 && out.charAt(1) == ':') {
				break;
			}
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	/** 给日志与界面的一行说明（形如 {@code 完全权限 · D:\work\demo}）。 */
	public String describe() {
		return label() + " · " + (hasWorkspace() ? workspaceDir : "(没有工作区)");
	}

	/** 档位的中文标签（没有工作区时也要能显示档位，所以单独一个方法）。 */
	public String label() {
		return level.label();
	}

}
