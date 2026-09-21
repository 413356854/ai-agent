package ai.agent.service.impl.tool.file;

import ai.agent.config.WorkspaceFileProperties;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.workspace.FileAccessDeniedException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具 ⑩：<b>按原文精确匹配，改一个文件里的一处</b>。
 *
 * <h2>为什么"唯一性"是这个工具的核心判据（而不是一个附加检查）</h2>
 * 替换类工具最坏的行为是<b>静默改错地方</b>：模型想改第 3 行的 {@code timeout=30}，
 * 而文件里还有第 87 行一模一样的一句。宽松实现会把两处都改掉，
 * 回填一句"替换成功"，然后任务继续往下跑 —— 直到某个测试失败，
 * 而那时没人能想到是这里动的。
 *
 * <p>所以匹配次数必须<b>恰好一次</b>：0 次说明模型记错了原文（让它去 re-read），
 * 多于 1 次说明这次替换本身是有歧义的（让它带上更多上下文重提）。
 * 两种都返回可操作的说明，而不是"失败"两个字。
 *
 * <h2>为什么它比 {@code write_file} 更适合"改一处"</h2>
 * 整份覆盖要求模型把全文一字不差地重写一遍 —— 那是在赌它的复制不会出错，
 * 而文件越大越容易漏。本工具只要求它给出"要改的那一段"，其余内容根本不经过模型。
 */
@Component
public class EditFileTool extends FileToolSupport<EditFileTool.Params> {

	/** 单文件字节上限：与 {@code read_file} 一致（能读回来的文件一定改得动）。 */
	private static final long MAX_BYTES = 1_048_576L;

	public EditFileTool(WorkspaceFileProperties limits) {
		super(limits);
	}

	@Override
	public String name() {
		return "edit_file";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"在一个已有文本文件里，把一段原文精确替换成新内容（只改这一处）。"
						+ "⚠️ 这是写操作：在工作区外的路径上写、或档位没放开时，**要经过人工确认才会执行**。",
				"当用户要求改文件里的某一处时使用（改一行配置、换一个函数名、修正一段文案）。"
						+ "用法：先用 read_file 看清原文，再把要改的那一段**逐字**抄进 oldText，"
						+ "把新内容写进 newText。oldText 在文件里必须**只出现一次**。",
				"⚠️ 不要用它新建文件（那用 write_file）；也不要一次改多处 —— 一次调用只替换一处，"
						+ "多处就多调几次。oldText 不要只写「空格」或「}」这种到处都有的片段："
						+ "那样既匹配不上唯一一处，也说明这次替换本身没想清楚。",
				"返回改动的行号范围、新文件的行数，以及「唯一匹配」这件事本身。"
						+ "⚠️ 参数**在工作区外的路径上写时先经过人工确认**：被批准后才落盘，被拒绝或超时则文件完全没有变。",
				"oldText 一次都没匹配上（多半是原文抄错或文件已经变了）→ 让你先 read_file 再重试；"
						+ "匹配到多处 → 让你把 oldText 换成更长、更独特的片段。"
						+ "这两种情况**都不会改动文件**；oldText 与 newText 相同会被拒绝（那是一次空操作，"
						+ "而它会让人误以为「改过了」）。",
				ToolHints.writeRequiringApproval());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		try {
			return edit(parameters);
		}
		catch (FileAccessDeniedException e) {
			return failure(e);
		}
	}

	private AgentToolResult edit(Params parameters) {
		Call call = invocation();
		requireWritable(call, parameters.path());
		Path file = call.access().resolve(parameters.path(), true);
		String display = call.access().display(file);

		if (parameters.oldText().equals(parameters.newText())) {
			log.info("edit_file 收到空操作 path={}", display);
			return AgentToolResult.Failure.notFound(
					"oldText 与 newText 完全相同，这次调用不会改动任何东西，所以没有执行。"
							+ "如果你要改的地方就在这段文字里，请把 newText 写成改动之后的样子；"
							+ "如果本来就不需要改，直接用文字回答用户即可。");
		}
		if (!Files.exists(file)) {
			log.info("edit_file 文件不存在 {}", file);
			return AgentToolResult.Failure.notFound(
					("工作区里没有文件「%s」（解析成 %s）—— 编辑只能改已存在的文件。"
							+ "要新建文件请用 write_file；文件名不确定先用 glob 找一遍。")
							.formatted(parameters.path(), display));
		}
		if (Files.isDirectory(file)) {
			return AgentToolResult.Failure.notFound(
					"「%s」是一个目录，不是文件。请给它一个文件路径。".formatted(display));
		}

		try {
			long size = Files.size(file);
			if (size > MAX_BYTES) {
				return AgentToolResult.Failure.notFound(
						("文件「%s」有 %d 字节，超过单文件编辑上限 %d 字节 —— 本次没有改它。"
								+ "请让用户说明要改哪一段，或改用更小的文件。").formatted(display, size, MAX_BYTES));
			}
			String original = Files.readString(file, StandardCharsets.UTF_8);
			int occurrences = countOccurrences(original, parameters.oldText());

			if (occurrences == 0) {
				log.info("edit_file 未匹配 path={} oldText={}", display, oneLine(parameters.oldText(), 80));
				return AgentToolResult.Failure.notFound(
						("在「%s」里找不到 oldText 那段原文（一处都没有匹配上）。"
								+ "多半是原文抄得与文件里不一致（少一个空格、全角半角不同），或者你用的是文件编辑之前的旧内容。"
								+ "请先用 read_file 把那一带**原样**读一遍，再照抄一次；"
								+ "同样的 oldText 再试一次一定还是找不到。文件**没有被改动**。")
								.formatted(display));
			}
			if (occurrences > 1) {
				log.info("edit_file 匹配到 {} 处，拒绝 path={} oldText={}", occurrences, display,
						oneLine(parameters.oldText(), 80));
				return AgentToolResult.Failure.notFound(
						("oldText 在「%s」里出现了 %d 次，无法确定要改哪一处，所以这次**没有改动文件**。"
								+ "请把 oldText 换成更长、更独特的片段（带上它前后的那一两行，或带上一句只有这里才有的注释），"
								+ "让它只匹配一处，然后重新提交。").formatted(display, occurrences));
			}

			int at = original.indexOf(parameters.oldText());
			String updated = original.substring(0, at) + parameters.newText()
					+ original.substring(at + parameters.oldText().length());
			Files.writeString(file, updated, StandardCharsets.UTF_8);

			int lineOfEdit = (int) original.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
			long oldLines = original.isEmpty() ? 0 : original.lines().count();
			long newLines = updated.isEmpty() ? 0 : updated.lines().count();
			log.info("edit_file path={} 第 {} 行起替换一处 purpose=\"{}\" 行数 {} -> {}", display, lineOfEdit,
					parameters.purpose(), oldLines, newLines);
			return AgentToolResult.ok(("已改「%s」的第 %d 行起的一处（唯一匹配）：替换了 %d 个字符，"
					+ "文件行数 %d → %d。用途：%s。要确认结果，用 read_file 读回来看。")
					.formatted(display, lineOfEdit, parameters.oldText().length(), oldLines, newLines,
							parameters.purpose()));
		}
		catch (CharacterCodingException e) {
			log.info("edit_file 目标不是 UTF-8：{}", file);
			return AgentToolResult.Failure.notFound(
					("文件「%s」不是 UTF-8 文本（解码失败），本工具不改它 —— 否则会把整份文件的编码改坏。")
							.formatted(display));
		}
		catch (IOException e) {
			log.warn("edit_file 失败 path={}", file, e);
			return AgentToolResult.Failure.upstream(withMissingNote(call.access(),
					"改「%s」失败：%s。文件**没有被改动**。".formatted(display, e.getMessage())));
		}
	}

	/** 数一段文本在另一段里出现了几次（重叠不算两次：{@code indexOf} 每次从匹配之后继续）。 */
	private static int countOccurrences(String haystack, String needle) {
		if (needle.isEmpty()) {
			return 0;
		}
		int count = 0;
		int from = 0;
		while (true) {
			int at = haystack.indexOf(needle, from);
			if (at < 0) {
				return count;
			}
			count++;
			from = at + needle.length();
		}
	}

	/**
	 * 参数。
	 *
	 * <p>三个字段都必填，而且 {@code oldText} / {@code newText} 都不能为空：
	 * 空 {@code oldText} 会匹配到文件的第 0 个位置（那不是任何一处内容），
	 * 空 {@code newText} 则是"删掉这一段"—— 后者是一个真实需求，但把它做成
	 * 一个空字符串会与"模型漏填了这个字段"完全同形（都是空串），
	 * 而两者的后果差得很远。所以删东西请把 {@code newText} 写成替换后的完整片段。
	 */
	public record Params(
			@NotBlank(message = "path 不能为空：它是相对于工作区的文件路径")
			@Size(max = 512, message = "path 过长（工作区路径上限是 512 字符）")
			String path,

			@NotBlank(message = "oldText 不能为空：它是要被替换掉的原文，必须与文件里逐字一致")
			@Size(max = 20000, message = "oldText 过长（上限 20000 字符）；要整份重写请用 write_file")
			String oldText,

			@NotBlank(message = "newText 不能为空：要改成什么。要删掉这一段就写它替换后的完整片段")
			@Size(max = 20000, message = "newText 过长（上限 20000 字符）；要整份重写请用 write_file")
			String newText,

			@NotBlank(message = "purpose 不能为空：用一句话说明这次改动要达成什么（人要靠它决定批不批）")
			@Size(max = 200, message = "purpose 是一句话，不要超过 200 字符")
			String purpose) {
	}

}
