package ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>文件类工具的边界</b>（前缀 {@code ai.agent.workspace.file}）。
 *
 * <h2>为什么与 {@link WorkspaceProperties} 合不到一个类里</h2>
 * {@code WorkspaceProperties} 管的是"工作区在哪"（发起任务时的必填校验与路径长度），
 * 那是<b>调用方</b>要遵守的规矩；这里管的是"读进来多少、写出去多大"，
 * 那是<b>工具回填给模型的上下文预算</b>（同 {@code ai.tool.feedback.max-chars} 那一族）。
 * 两件事的读者不同，合成一个类之后"改哪一行影响谁"会变得不明显。
 *
 * <h2>为什么每个上限都必须存在</h2>
 * 这些工具的返回值<b>直接进模型上下文</b>，而上下文是花钱的，也是有限窗口。
 * 一份 3 万行的日志一次性回填，代价不只是那一次的 token ——
 * 它会挤掉之前所有的检索结果，于是模型拿着半截上下文给出一个看起来完整的答案。
 * 所以上限不是"防崩"，是"防答错"。
 *
 * <h2>超限的后果<b>必须显式出现在回填里</b></h2>
 * 每个上限撞到时，工具都会在文本里写清"这不是全部、剩下的怎么看"。
 * 静默截断是本项目反复列过的一类风险：模型会把"我看到的就是全部"当成事实。
 *
 * @param maxLines {@code read_file} 单次最多返回多少行。
 *            2000 行的依据：按每行 60 字符估约 12 万字符，已经接近回填上限
 *            （{@code ai.tool.feedback.max-chars}）—— 再大就必然被回填层截断，
 *            而由工具自己截断能带上准确的"还有多少行"。
 * @param maxFileBytes 单文件读/改的字节上限（{@code read_file} 与 {@code edit_file} 共用）。
 *            1 MiB 的依据：它显著高于本项目自己的源码文件（最大的不到 200 KB），
 *            而明显低于"一眼看不完"的量级。同一份数据在 UTF-8 下与
 *            {@code write_file} 的字符上限（20 万字符）大致对应 —— 刚写出来的文件一定读得回来。
 * @param maxWriteChars {@code write_file} 单次写入的字符上限。
 *            20 万的依据：它与"一份完整源码文件"同量级，同时把一次工具调用的
 *            token 成本按在校验层（执行器在解析参数时就拒绝，不必等写完才发现太大）。
 * @param maxEntries {@code glob} 单次返回的条数上限。
 *            200 条的依据：一个目录树里"模型真正会去看的文件"通常不到这个数；
 *            撞到它说明 pattern 写得太宽（回填里就是这么说的）。
 * @param maxMatches {@code grep} 单次返回的命中条数上限（含 {@code files_with_matches} 模式的计数口径）。
 *            100 条的依据：100 行命中按每行 60 字符约 6000 字符，已经略超回填上限 ——
 *            于是"下一次该收窄 pattern"这件事会由回填层与工具自己一起说出来。
 */
@ConfigurationProperties(prefix = "ai.agent.workspace.file")
public record WorkspaceFileProperties(int maxLines, long maxFileBytes, int maxWriteChars, int maxEntries,
		int maxMatches) {

	public WorkspaceFileProperties {
		// 允许 0/负数会让每一把文件工具都直接判"超限"，而那个现象看起来像"文件读不出来"。
		// 与 WorkspaceProperties.maxPathChars 同一条规矩：写错的配置要在启动时就暴露
		requirePositive(maxLines, "max-lines");
		requirePositive(maxFileBytes, "max-file-bytes");
		requirePositive(maxWriteChars, "max-write-chars");
		requirePositive(maxEntries, "max-entries");
		requirePositive(maxMatches, "max-matches");
	}

	private static void requirePositive(long value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException(
					"ai.agent.workspace.file.%s 必须是正数（收到 %d）".formatted(name, value));
		}
	}

	/** 代码默认值（与 {@code application.properties} 里那几行逐字一致）。 */
	public static WorkspaceFileProperties defaults() {
		return new WorkspaceFileProperties(2000, 1_048_576L, 200_000, 200, 100);
	}

}
