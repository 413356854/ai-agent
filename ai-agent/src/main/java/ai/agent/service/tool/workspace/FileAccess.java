package ai.agent.service.tool.workspace;

import ai.agent.entity.tool.RunPermission;
import ai.agent.entity.agent.AgentPermissionLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * <b>一次文件工具调用的工作区边界</b>：路径按哪个目录解析、哪些路径算在边界之内。
 *
 * <h2>它存在的理由：路径判据只能有一处</h2>
 * 五把文件工具（{@code read_file} / {@code write_file} / {@code edit_file} / {@code glob} / {@code grep}）
 * 都要回答同样三个问题：这个路径解析到哪、它算不算在工作区里、写它要不要先把父目录的真实路径查出来。
 * 每个工具各写一遍的话，五份判据迟早漂移 —— 而漂移的表现是
 * <b>某一把工具能写到工作区之外</b>，没有报错、也没有日志。
 *
 * <p>所以边界判定收在这里，而<b>要不要人工审批</b>留在
 * {@link AgentPermissionPolicy#coversWrite} 与 {@link WorkspaceApprovalGate}：
 * 那两个问题的输入不同（一个只看路径在不在区内，一个还要看这一次运行的档位），
 * 合进一个类就会出现一个方法既解析路径又读档位。
 *
 * <h2>判据：<b>按路径分段比，不用字符串前缀</b></h2>
 * 工作区 {@code D:\work\demo} 与 {@code D:\work\demo-other} 在字符串上前者是后者的前缀，
 * 而它们是两个并列目录 —— 用前缀比会让 {@code demo-other} 里的文件被当成"工作区内"。
 * 这里逐段比较（见 {@link #isInside}），{@code demo-other} 因此正确落在边界之外。
 *
 * @param workspaceDir 这一次运行的工作区目录（{@code RunPermission} 里的原始写法：相对路径按
 *            Java 进程的工作目录解析，与 {@link RunPermission#validateExistingDirectory()} 同一套规矩）
 * @param root 工作区目录的<b>绝对化 + 归一化</b>路径（{@code ..} 已在词法上折叠）
 */
public record FileAccess(String workspaceDir, Path root) {

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	/**
	 * 有工作区的一次调用。
	 *
	 * <p><b>不在这里校验目录存不存在</b>：新任务在入口就被
	 * {@link RunPermission#validateExistingDirectory()} 拦下了，
	 * 而恢复一次历史运行时那个目录完全可能已经被删掉 —— 那时在构造期抛异常会让
	 * 一次本来还能跑完的任务永远卡住（同 {@code RunPermission} 那条取舍）。
	 * 目录不在的事实由每次操作的 IO 失败如实报告，见 {@link #missingNote()}。
	 */
	public static FileAccess of(String workspaceDir) {
		String raw = workspaceDir == null ? "" : workspaceDir.strip();
		if (raw.isEmpty()) {
			throw new FileAccessDeniedException(FileAccessDeniedException.NO_WORKSPACE,
					"本次运行没有工作区目录（RunPermission 里没有 workspaceDir），文件工具没有可解析路径的根。");
		}
		try {
			return new FileAccess(raw, Path.of(raw).toAbsolutePath().normalize());
		}
		catch (InvalidPathException e) {
			throw new FileAccessDeniedException(FileAccessDeniedException.NO_WORKSPACE,
					("本次运行的工作区目录不是一个合法路径：%s。文件工具无法解析相对路径 —— "
							+ "请告诉用户重新发起任务并填一个存在的目录。").formatted(workspaceDir));
		}
	}

	/**
	 * 没有工作区的那一种访问。
	 *
	 * <p>它不是 null：判据里"没有工作区"与"工作区存在但这次动的东西在区外"归到同一侧
	 * （都不在区内），所以两者可以用同一个对象表达，调用方也就少一处空判断。
	 */
	public static FileAccess none() {
		return new FileAccess("", null);
	}

	public boolean hasWorkspace() {
		return root != null;
	}

	/**
	 * 解析一个工具参数里的路径：<b>相对路径按工作区解析</b>，绝对路径必须在工作区内。
	 *
	 * <h2>为什么绝对路径不"就地当成相对路径"</h2>
	 * 把 {@code /etc/passwd} 剥成 {@code etc/passwd} 看起来更宽容，但那是
	 * <b>把一条越界路径静默改写成另一个文件</b>：模型说的是 A，工具动的是 B，
	 * 而两边都不会发现。所以越界一律拒绝，并在回填里给出工作区的绝对路径。
	 *
	 * <h2>为什么 {@code ..} 必须在解析阶段折叠</h2>
	 * 边界判定是按分段比的，{@code a/../../x} 这种写法只有在归一化之后才看得出它越界。
	 * 先归一、后判边界，顺序反过来就会漏掉它。
	 */
	public Path resolve(String rawPath) {
		return resolve(rawPath, false);
	}

	/**
	 * 解析一个路径，并按需把它解析成<b>真实路径</b>（写操作用）。
	 *
	 * <h2>它<b>不</b>判权限档位</h2>
	 * 边界与档位是两件事，判据也分在两处：
	 * <ul>
	 *   <li><b>边界</b>（这里）：路径必须在工作区内。越界一律拒绝，任何档位都一样 ——
	 *       "完全权限"放宽的是"要不要人点头"，不是"能写到工作区之外"；</li>
	 *   <li><b>档位</b>（{@code AgentPermissionPolicy} + 闸门）：这一次的写要不要人点头。
	 *       所以"完全权限 + 工作区外的路径"在闸门那一层就已经被拒绝过了，
	 *       这里再判一次只会得到同一句话的第二份实现 —— 而两份判据迟早漂移
	 *       （实测过：多判这一次会让"完全权限"下所有绝对路径都写不进去，
	 *       包括工作区之内的绝对路径）。</li>
	 * </ul>
	 *
	 * @param resolveParentRealPath 是否把<b>已存在的那一层父目录</b>解析成真实路径。
	 *            词法归一判不出符号链接，而 {@code <工作区>/link -> C:\Windows} 这种写法
	 *            在字符串上完全落在工作区内 —— 写之前必须查一次父目录。
	 *            只有父目录能查：目标文件本身可能还不存在（那是"新建文件"的正常形态）。
	 */
	public Path resolve(String rawPath, boolean resolveParentRealPath) {
		Path target = normalize(rawPath);
		if (!isInside(target)) {
			throw outside(rawPath);
		}
		if (!resolveParentRealPath) {
			return target;
		}
		Path parent = target.getParent();
		if (parent == null || !Files.exists(parent)) {
			return target;
		}
		try {
			return parent.toRealPath().resolve(target.getFileName());
		}
		catch (IOException e) {
			// 解析不出真实路径就按词法结果走：下面的 IO 会给出真正的失败原因
			// （权限不足、路径过长等），这里再抛一次只会把它盖掉
			return target;
		}
	}

	/** 这个路径算不算在工作区内（读操作不需要问档位：三档里没有一档禁止查看）。 */
	public boolean isInside(Path target) {
		return root != null && isInside(root, target);
	}

	/**
	 * 逐段比较判断从属关系。
	 *
	 * <p>Windows 上盘符那一段忽略大小写；其余段按平台语义比较
	 * （NTFS 默认也不区分大小写，但那取决于卷的配置，不能一概而论）。
	 */
	static boolean isInside(Path root, Path target) {
		if (root == null || target == null) {
			return false;
		}
		int rootCount = root.getNameCount();
		if (target.getNameCount() < rootCount || !sameRootComponent(root, target)) {
			return false;
		}
		for (int i = 0; i < rootCount; i++) {
			String a = root.getName(i).toString();
			String b = target.getName(i).toString();
			if (!(WINDOWS ? a.equalsIgnoreCase(b) : a.equals(b))) {
				return false;
			}
		}
		return true;
	}

	private static boolean sameRootComponent(Path root, Path target) {
		Path a = root.getRoot();
		Path b = target.getRoot();
		if (a == null || b == null) {
			return a == b;
		}
		String left = a.toString();
		String right = b.toString();
		return WINDOWS ? left.equalsIgnoreCase(right) : left.equals(right);
	}

	/**
	 * 把目标路径写成<b>相对工作区</b>的样子（回填文本里用它，模型据此拼下一次调用）。
	 *
	 * <p>给绝对路径对模型没有坏处，但相对写法让"工作区内"这件事在回填里也看得见 ——
	 * 于是模型下一次不会顺手写一个绝对路径。
	 */
	public String display(Path target) {
		if (target == null) {
			return "";
		}
		if (root != null && target.startsWith(root) && !target.equals(root)) {
			return root.relativize(target).toString().replace('\\', '/');
		}
		return target.toString();
	}

	/** 工作区目录不存在时的一行说明（读/写失败时补在错误信息后面）。 */
	public Optional<String> missingNote() {
		if (root == null || Files.isDirectory(root)) {
			return Optional.empty();
		}
		return Optional.of("（工作区目录「%s」现在不是一个存在的目录 —— 它可能已被删除或改名）".formatted(workspaceDir));
	}

	/** 给日志与报错的一句话（形如 {@code D:\work\demo}；没有工作区时是一句说明）。 */
	public String describe() {
		return root == null ? "(本次运行没有工作区)" : workspaceDir;
	}

	/** 档位标签，只用于措辞（判据在 {@link AgentPermissionPolicy}）。 */
	public static String labelOf(AgentPermissionLevel level) {
		return level == null ? AgentPermissionLevel.READ_ONLY.label() : level.label();
	}

	/** 解析入口：空白、非法字符、以及"没有工作区"都在这里被拦下。 */
	private Path normalize(String rawPath) {
		if (rawPath == null || rawPath.isBlank()) {
			throw new FileAccessDeniedException("empty_path", "path 不能为空。", true);
		}
		if (root == null) {
			throw new FileAccessDeniedException(FileAccessDeniedException.NO_WORKSPACE,
					("本次运行没有工作区目录，文件工具无法解析路径「%s」。"
							+ "请告诉用户：需要重新发起任务并填入工作区目录，或者直接用文字回答 —— "
							+ "不要改写成绝对路径重试，那条路同样走不通。").formatted(rawPath));
		}
		Path raw;
		try {
			raw = Path.of(rawPath.strip());
		}
		catch (InvalidPathException e) {
			throw new FileAccessDeniedException("invalid_path",
					("path 不是一个合法路径：%s（%s）。").formatted(rawPath, e.getMessage()), true);
		}
		Path resolved = raw.isAbsolute() ? raw : root.resolve(raw);
		return resolved.toAbsolutePath().normalize();
	}

	private FileAccessDeniedException outside(String rawPath) {
		return new FileAccessDeniedException(FileAccessDeniedException.OUTSIDE_WORKSPACE,
				("路径「%s」解析到工作区之外，文件工具只在工作区内工作（工作区：%s）。"
						+ "请改用相对于工作区的路径（例如 src/main/java/...）；"
						+ "同样的路径再试一次一定还是被拒，不要原样重试。").formatted(rawPath, describe()));
	}

}
