package ai.agent.service.impl.tool.file;

import ai.agent.config.WorkspaceFileProperties;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.service.impl.tool.file.support.GlobPattern;
import ai.agent.service.impl.tool.file.support.WorkspaceWalker;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.workspace.FileAccess;
import ai.agent.service.tool.workspace.FileAccessDeniedException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具 ⑫：<b>按内容搜文件</b>（正则，返回命中行与行号）。
 *
 * <h2>它和 {@code glob} 是互补的，不是重复的两把</h2>
 * {@code glob} 按<b>文件名</b>找（"哪份文件叫 *Controller.java"），
 * 本工具按<b>内容</b>找（"哪个文件里写了 refreshToken"）。
 * 真实任务里最常见的第一步是后者 —— 用户描述的是行为，不是文件名。
 *
 * <h2>为什么必须报出"搜了多少个文件"</h2>
 * "一个都没匹配到"有两种完全不同的原因：<b>真的没有</b>，以及<b>压根没搜到那些文件</b>
 * （{@code path} 指错了目录、噪音目录被跳过、文件太大被跳过）。
 * 只报"没找到"的话，模型会得出前一个结论并据此回答用户 ——
 * 所以回填里必须带上本次实际搜索的文件数与被跳过的文件数。
 *
 * <h2>为什么正则是模型写的，而"不合法"要说清楚</h2>
 * 正则确实比关键词更强（{@code refresh(Token)?}），而模型写错正则的方式很固定
 * （括号不配对、{@code \} 结尾）。{@link PatternSyntaxException} 的消息是英文且带 Java 的
 * 术语，直接回填对模型价值有限 —— 这里把它转成一句"哪个位置不合法 + 请改用普通关键词"。
 */
@Component
public class GrepTool extends FileToolSupport<GrepTool.Params> {

	/** 单文件字节上限：超过它的文件跳过（并计数），避免在一个大日志上把时间全花掉。 */
	private static final long MAX_FILE_BYTES = 262_144L;

	/** 本次最多扫多少个文件。撞到时结果里会写"还有文件没扫"。 */
	private static final int MAX_FILES = 2000;

	/** 回填文本里每行内容的截断长度（行号与文件路径不受影响）。 */
	private static final int MAX_CHARS_PER_LINE = 300;

	public GrepTool(WorkspaceFileProperties limits) {
		super(limits);
	}

	@Override
	public String name() {
		return "grep";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"按内容在工作区里搜文件：给一个正则，返回命中的文件、行号与该行内容。",
				"当你知道「哪段代码或配置写了什么」而不知道它在哪个文件时使用。"
						+ "例如搜 refreshToken 看谁在用它、搜 TODO 看还没做的事、"
						+ "搜某个报错文案定位它在哪抛出。找到文件之后用 read_file 看上下文。",
				"⚠️ 它按**内容**搜，按**文件名**找要用 glob（两者互补，别拿它当 ls 用）。"
						+ "它不看版本库内部结构与依赖目录（.git / node_modules / target 等整棵跳过），"
						+ "所以「哪里都搜不到」时先确认文件不在那些目录里。"
						+ "也不要用它读整份文件（那用 read_file）：它的输出是命中行，不是上下文。",
				"每行形如 相对路径:行号: 该行内容（行号从 1 开始），末尾给出命中数与本次搜索范围。"
						+ "output 选 files_with_matches 时只列文件名与每个文件的命中次数（先摸清范围时更省）。"
						+ "撞到上限（最多 " + MAX_FILES + " 个文件）时会明确写出「这不是全部」。",
				"正则不合法会返回明确说明（含不合法的大致位置）—— 请改成普通关键词，或把括号配对好；"
						+ "路径不存在 / 不在工作区内也会明确说明。一条都没匹配到是**正常结果**（不是失败），"
						+ "但请先看回填里的「实际搜索了 N 个文本文件」：N 很小时说明 path 选错了。",
				ToolHints.readOnly());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		try {
			return grep(parameters);
		}
		catch (FileAccessDeniedException e) {
			return failure(e);
		}
	}

	private AgentToolResult grep(Params parameters) {
		Call call = invocation();
		FileAccess access = call.access();
		String pathParam = parameters.path() == null || parameters.path().isBlank() ? "." : parameters.path();
		Path start = access.resolve(pathParam, false);
		String display = access.display(start);

		if (!Files.exists(start)) {
			log.info("grep 路径不存在 {}", start);
			return AgentToolResult.Failure.notFound(
					("搜索路径「%s」在工作区里不存在（解析成 %s）。请先用 glob 确认文件名与目录，"
							+ "或把 path 改成 \".\" 从工作区根上搜。").formatted(pathParam, display));
		}

		Pattern regex;
		try {
			regex = Pattern.compile(parameters.pattern());
		}
		catch (PatternSyntaxException e) {
			log.info("grep 正则不合法 pattern={} 原因={}", oneLine(parameters.pattern(), 80), e.getDescription());
			return AgentToolResult.Failure.notFound(
					("pattern 不是合法的正则：%s（大致位置：第 %d 个字符附近）。"
							+ "如果不确定正则怎么写，就填一个普通关键词（例如 refreshToken）—— "
							+ "它同样是合法的正则，而且更容易命中。")
							.formatted(e.getDescription(), e.getIndex() + 1));
		}

		GlobPattern include = null;
		if (parameters.include() != null && !parameters.include().isBlank()) {
			try {
				include = GlobPattern.forFileNames(parameters.include());
			}
			catch (GlobPattern.InvalidGlobException e) {
				return AgentToolResult.Failure.notFound(
						"include 不是合法的文件名模式：%s。请写成 *.java 这样的形式，或干脆不传这个字段。"
								.formatted(e.getMessage()));
			}
		}

		List<Path> candidates;
		try {
			candidates = WorkspaceWalker.walk(start, access);
		}
		catch (IOException e) {
			log.warn("grep 遍历失败 start={}", start, e);
			return AgentToolResult.Failure.upstream(withMissingNote(access,
					"遍历「%s」失败：%s。".formatted(display, e.getMessage())));
		}

		Params.Output mode = parameters.output() == null ? Params.Output.content : parameters.output();
		List<String> output = new ArrayList<>();
		List<String> perFile = new ArrayList<>();
		int scanned = 0;
		int skippedLarge = 0;
		int skippedBinary = 0;
		int matches = 0;
		int filesWithMatches = 0;
		boolean hitLimit = false;
		boolean hitFileLimit = false;

		for (Path candidate : candidates) {
			if (!Files.isRegularFile(candidate)) {
				continue;
			}
			String relative = access.display(candidate);
			if (include != null && !include.matches(fileNameOf(relative))) {
				continue;
			}
			if (scanned >= MAX_FILES) {
				hitFileLimit = true;
				break;
			}
			scanned++;

			int[] found = searchFile(candidate, regex, relative, output, mode == Params.Output.content);
			if (found[0] == -1) {
				skippedLarge++;
				continue;
			}
			if (found[0] == -2) {
				skippedBinary++;
				continue;
			}
			if (found[0] > 0) {
				filesWithMatches++;
				matches += found[0];
				if (mode == Params.Output.files_with_matches) {
					perFile.add("%s（%d 处）".formatted(relative, found[0]));
				}
				if (matches >= limits().maxMatches()) {
					hitLimit = true;
					break;
				}
			}
		}

		String scope = ("搜索范围：%s；实际搜索 %d 个文本文件%s%s。")
				.formatted(display, scanned,
						skippedLarge == 0 ? "" : "，跳过 " + skippedLarge + " 个超过 "
								+ MAX_FILE_BYTES + " 字节的文件",
						skippedBinary == 0 ? "" : "，跳过 " + skippedBinary + " 个二进制文件");
		log.info("grep pattern={} path={} 扫描={} 命中={} 命中文件={} 截断={}", oneLine(parameters.pattern(), 80),
				display, scanned, matches, filesWithMatches, hitLimit);

		if (matches == 0) {
			return AgentToolResult.ok(("按「%s」在「%s」里搜内容：一条都没匹配到。\n%s\n"
					+ "如果上面的搜索文件数很小，多半是 path 指错了目录；"
					+ "如果数量正常，就换一个更短的关键词（正则里的元字符会缩小匹配面），"
					+ "或者用 glob 先确认目标文件在不在这里。")
					.formatted(parameters.pattern(), display, scope));
		}

		String header = "按「%s」在「%s」里搜内容：命中 %d 处，分布在 %d 个文件%s\n%s\n\n"
				.formatted(parameters.pattern(), display, matches, filesWithMatches,
						hitLimit ? "（已截断）" : "", scope);
		String payload = header + String.join("\n",
				mode == Params.Output.content ? output : perFile);
		if (hitLimit || hitFileLimit) {
			payload += ("\n\n⚠ 这不是全部：%s。请把 pattern 写得更具体一些，"
					+ "或者用 include 限定文件类型（例如 include=\"*.java\"）之后再搜一次。")
					.formatted(hitLimit ? "结果在上限 " + limits().maxMatches() + " 条命中处被截断"
							: "只搜了前 " + MAX_FILES + " 个文件，还有文件没扫");
			return new AgentToolResult.Success(payload, true);
		}
		return AgentToolResult.ok(payload);
	}

	/**
	 * 搜一个文件。
	 *
	 * @param collectLines 是否把命中行写进 {@code output}（{@code files_with_matches} 模式只数数）
	 * @return {@code [命中数]}；{@code [-1]} 表示文件读不了或太大被跳过；{@code [-2]} 表示二进制被跳过
	 */
	private int[] searchFile(Path file, Pattern regex, String relative, List<String> output, boolean collectLines) {
		try {
			long size = Files.size(file);
			if (size > MAX_FILE_BYTES) {
				return new int[] { -1 };
			}
			byte[] head = new byte[(int) Math.min(size, 512)];
			try (var in = Files.newInputStream(file)) {
				int read = in.readNBytes(head, 0, head.length);
				for (int i = 0; i < read; i++) {
					if (head[i] == 0) {
						return new int[] { -2 };
					}
				}
			}
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int found = 0;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (!regex.matcher(line).find()) {
					continue;
				}
				found++;
				if (collectLines && output.size() < limits().maxMatches()) {
					output.add("%s:%d: %s".formatted(relative, i + 1, clip(line.strip())));
				}
			}
			return new int[] { found };
		}
		catch (CharacterCodingException e) {
			// 名字看着像文本但不是 UTF-8：按二进制跳过并计数（不报错 —— 一个目录里有一份
			// GBK 文件不该让整次搜索失败）
			return new int[] { -2 };
		}
		catch (IOException e) {
			log.debug("grep 读取失败（跳过）file={} 原因={}", file, e.getMessage());
			return new int[] { -1 };
		}
	}

	private static String fileNameOf(String relative) {
		int slash = relative.lastIndexOf('/');
		return slash < 0 ? relative : relative.substring(slash + 1);
	}

	private static String clip(String line) {
		if (line.length() <= MAX_CHARS_PER_LINE) {
			return line;
		}
		return line.substring(0, MAX_CHARS_PER_LINE) + "…（此行还有 %d 字符未显示）"
				.formatted(line.length() - MAX_CHARS_PER_LINE);
	}

	/**
	 * 参数。
	 *
	 * <p>{@code path} 与 {@code include} 都可空：前者默认工作区根（最常见的形态是"整个工作区里找"），
	 * 后者默认不限类型。{@code output} 用枚举而不是布尔：它表达的是<b>三种互斥的输出形态</b>，
	 * 而布尔字段名（{@code onlyFiles}）在模型手里经常被理解反。
	 */
	public record Params(
			@NotBlank(message = "pattern 不能为空：它是要在文件内容里找的正则（普通关键词也可以）")
			@Size(max = 500, message = "pattern 过长（上限 500 字符）—— 请缩短到真正要找的那一段")
			String pattern,

			@Size(max = 512, message = "path 过长（工作区路径上限是 512 字符）")
			String path,

			@Size(max = 200, message = "include 是文件名模式，例如 *.java（上限 200 字符）")
			String include,

			Output output) {

		/** 命中之后要看到什么。 */
		public enum Output {

			/** 文件名 + 行号 + 该行内容（默认；定位"写在哪一行"用它）。 */
			content,

			/** 只列文件名与每个文件的命中次数（先摸清范围时更省）。 */
			files_with_matches;

			/**
			 * 进 JSON Schema 的取值（小写下划线，与 OpenAI 工具调用的习惯一致）。
			 *
			 * <p>覆写它是因为 {@code JsonSchemaGenerator} 给 enum 生成 {@code enum: [...]} 时
			 * 用的是 {@code toString()} —— 不覆写的话模型看到的合法取值是
			 * {@code CONTENT} / {@code FILES_WITH_MATCHES}，而它照着传回来的东西
			 * Jackson 也认（枚举名相同），只是"说明里写的"与"我们想让它写的"从此不一致。
			 */
			@Override
			public String toString() {
				return name();
			}
		}
	}

}
