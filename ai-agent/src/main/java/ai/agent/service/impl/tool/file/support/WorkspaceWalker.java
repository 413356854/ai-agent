package ai.agent.service.impl.tool.file.support;

import ai.agent.service.tool.workspace.FileAccess;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>在工作区里遍历文件</b> —— {@code glob} 与 {@code grep} 共用的一份走法。
 *
 * <h2>它保证的三件事</h2>
 * <ol>
 *   <li><b>不越界</b>：每个访问到的路径都过一遍
 *       {@link FileAccess#isInside(Path)}。起点由调用方保证在区内，
 *       但符号链接与 {@code ..} 都可能把结果带到区外 —— 所以逐个再判一次，
 *       而不是"起点对了就都对了"；</li>
 *   <li><b>不进噪音目录</b>：{@link #SKIP_DIRECTORIES} 里的目录整棵子树都不进
 *       （用的是 {@code SKIP_SUBTREE}，不是"进去再过滤"——后者在大仓上会慢一个数量级）。
 *       理由：{@code .git} / {@code node_modules} / {@code target} 里动辄几万个文件，
 *       而模型要的从来不是它们。这个取舍的代价是"用户真想看 {@code .git} 时看不到"，
 *       所以它由 {@code grep} / {@code glob} 的描述里明写出来；</li>
 *   <li><b>按名字排序</b>：结果顺序稳定，于是"同一次任务跑两遍"的回填文本一致
 *       （文件系统的遍历顺序取决于实现，不排序会让报告不可复现）。</li>
 * </ol>
 *
 * <h2>为什么不跟随符号链接</h2>
 * {@code Files.walkFileTree} 默认不跟随，这里保持默认。跟随的话有两个后果：
 * ① 环（链接指回祖先目录）会让遍历不终止；② 链接指向工作区之外时，
 * 遍历会走进那个目录 —— 而按 {@link FileAccess} 的判定，那些结果<b>都不该被返回</b>，
 * 于是白白走一遍再把它们全滤掉。
 */
public final class WorkspaceWalker {

	/**
	 * 整棵跳过的目录名。
	 *
	 * <p>判据是"它里面的东西几乎永远不是模型要找的，而且数量极大"：
	 * 版本库内部结构、依赖目录、编译产物、编辑器与构建工具的缓存。
	 * 刻意<b>不</b>包含 {@code build} —— 有项目真的把源码放在叫 build 的目录里，
	 * 而"看不到"这类错误的排查成本比"看到一堆噪音"高。
	 */
	public static final Set<String> SKIP_DIRECTORIES = Set.of(".git", ".hg", ".svn", ".idea", ".vscode", ".gradle",
			"node_modules", "target", "dist", ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache",
			".next", ".nuxt", ".cache");

	private WorkspaceWalker() {
	}

	/**
	 * 遍历一个目录（含它自己），返回<b>工作区内、且不在噪音目录里</b>的全部路径。
	 *
	 * @param start 起点；调用方已经保证它在工作区内
	 * @param access 这次运行的工作区边界
	 * @throws IOException 遍历本身失败（起点不存在、权限不足等）—— 由调用方转成可读说明
	 */
	public static List<Path> walk(Path start, FileAccess access) throws IOException {
		if (!Files.isDirectory(start)) {
			// 单个文件（grep 允许 path 直接指向一个文件）：最省的一条路，连排序都不用
			return access.isInside(start) ? List.of(start) : List.of();
		}
		List<Path> visited = new ArrayList<>();
		Files.walkFileTree(start, new SimpleFileVisitor<>() {

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(start) && SKIP_DIRECTORIES.contains(dir.getFileName().toString())) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				if (access.isInside(dir)) {
					visited.add(dir);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (access.isInside(file)) {
					visited.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		visited.sort(java.util.Comparator.comparing(Path::toString));
		return visited;
	}

	/**
	 * 一个路径相对起点的写法（用 {@code /} 分隔，便于匹配与显示）。
	 *
	 * <p>起点自己返回 {@code "."}：模式 {@code *.java} 展开成"任意深度"之后不该把起点算进去，
	 * 而返回空串会让它意外命中"任意层"那一条。
	 */
	public static String relativeTo(Path start, Path target) {
		if (target.equals(start)) {
			return ".";
		}
		return start.relativize(target).toString().replace('\\', '/');
	}

}
