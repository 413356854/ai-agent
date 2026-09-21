package ai.agent.service.impl.tool.file;

import ai.agent.config.WorkspaceFileProperties;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.workspace.FileAccessDeniedException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 工具 ⑨：<b>整份写入工作区里的一个文本文件</b>（新建或整体覆盖）。
 *
 * <h2>它为什么是"覆盖"而不是"追加"</h2>
 * 追加看起来更安全，实际更危险：模型说"改一下这个文件"，追加会让文件里出现两份内容，
 * 而回填文本说"写成功" —— 于是"改"这件事静默地变成了"多了一份"。
 * 改动已有的文件应当走 {@code edit_file}（按原文匹配），
 * 本工具的语义只有一条：<b>这个文件的全部内容，从这一刻起就是你给的这份。</b>
 *
 * <h2>三道闸门，缺一不可</h2>
 * <ol>
 *   <li><b>路径在工作区内</b>（{@link ai.agent.service.tool.workspace.FileAccess}）；
 *       写之前还会把已存在的父目录解析成真实路径 —— 拦住"符号链接指向工作区之外"；</li>
 *   <li><b>档位允许写</b>（{@code requireWritable}）：「仅可查看」这一档直接拒绝，
 *       而不是转成人工确认 —— 见 {@code AgentPermissionPolicy} 里那段判据；</li>
 *   <li><b>人工确认</b>：工作区外的写在工作区内修改档位下要人点头；
 *       「完全权限」两处都不点头（那是这一档的定义）。</li>
 * </ol>
 *
 * <h2>{@code purpose} 是给谁看的</h2>
 * 审批页面上原样显示的是参数 JSON，而一份文件内容在 JSON 里是一大段转义文本 ——
 * 人能读它，但很难一眼看出"这次到底要干什么"。{@code purpose} 补的正是这一层，
 * 它与 {@code execute_sql} 的 {@code purpose} 是同一条规矩（那边有完整的判据）。
 */
@Component
public class WriteFileTool extends FileToolSupport<WriteFileTool.Params> {

	public WriteFileTool(WorkspaceFileProperties limits) {
		super(limits);
	}

	@Override
	public String name() {
		return "write_file";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"把内容写进工作区里的一个文件（不存在就新建，已存在就**整份覆盖**）。"
						+ "⚠️ 这是写操作：在工作区外的路径上写、或档位没放开时，**要经过人工确认才会执行**。",
				"当用户明确要求新建一个文件、或整份重写一个文件时使用（例如「生成一份 README」"
						+ "「把这个配置改写成新的格式」）。"
						+ "新建文件时父目录不存在会自动创建，所以不必先建目录。",
				"⚠️ **改动已有文件里的某一处**不要用它 —— 用 edit_file（按原文匹配替换）："
						+ "整份覆盖要求你把全文一字不差地重写一遍，漏掉的地方会被静默删掉。"
						+ "也不要用它做「追加一行」：本工具没有追加语义，写进来的就是文件的全部内容。",
				"返回写入结果：新建还是覆盖、多少行、多少字符，以及相对工作区的路径。"
						+ "⚠️ 参数**在工作区外的路径上写时先经过人工确认**：被批准后才落盘，被拒绝或超时则完全没有写。",
				"路径在工作区之外、档位是「仅可查看」、父路径不是一个目录 —— 都会返回明确说明，"
						+ "且**不会写入任何内容**。这类失败原样重试不会有不同结果，请按说明换路径或让用户换档位。",
				ToolHints.writeRequiringApproval());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		try {
			return write(parameters);
		}
		catch (FileAccessDeniedException e) {
			return failure(e);
		}
	}

	private AgentToolResult write(Params parameters) {
		Call call = invocation();
		requireWritable(call, parameters.path());
		Path file = call.access().resolve(parameters.path(), true);
		String display = call.access().display(file);
		String content = parameters.content();
		boolean existed = Files.exists(file);

		if (existed && Files.isDirectory(file)) {
			log.info("write_file 目标是一个目录 {}", file);
			return AgentToolResult.Failure.notFound(
					("「%s」是一个目录，不能当文件写。请给一个文件路径（目录可以创建，文件不能被目录顶替）。")
							.formatted(display));
		}
		Path parent = file.getParent();
		if (parent != null && !Files.isDirectory(parent) && Files.exists(parent)) {
			return AgentToolResult.Failure.notFound(
					("「%s」的父路径不是一个目录（%s），写不进去。请换一个路径。")
							.formatted(display, parent));
		}

		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		}
		catch (IOException e) {
			log.warn("write_file 写入失败 path={}", file, e);
			return AgentToolResult.Failure.upstream(withMissingNote(call.access(),
					"写入「%s」失败：%s。这次**没有**写成功，文件保持原样（如果它原本存在的话）。"
							.formatted(display, e.getMessage())));
		}

		long bytes = content.getBytes(StandardCharsets.UTF_8).length;
		long lines = content.isEmpty() ? 0 : content.lines().count();
		log.info("write_file path={} {} {} 字符 / {} 字节 purpose=\"{}\"", display,
				existed ? "覆盖" : "新建", content.length(), bytes, parameters.purpose());
		return AgentToolResult.ok(("已%s文件「%s」：%d 字符 / %d 字节 / %d 行（UTF-8）。"
				+ "用途：%s。要确认写进去的内容，用 read_file 读回来看。")
				.formatted(existed ? "覆盖" : "新建", display, content.length(), bytes, lines, parameters.purpose()));
	}

	/**
	 * 参数。
	 *
	 * <p>{@code content} 用 {@code @NotNull} + {@code @Size(max)}：<b>空字符串是合法的</b>
	 * （"清空这个文件"是一个真实需求），所以不能用 {@code @NotBlank} ——
	 * 那会让清空文件这件事永远调不通，而说明看起来毫无问题。
	 */
	public record Params(
			@NotBlank(message = "path 不能为空：它是相对于工作区的文件路径")
			@Size(max = 512, message = "path 过长（工作区路径上限是 512 字符）")
			String path,

			@NotNull(message = "content 不能为空；要清空文件就传空字符串 \"\"（不要省略这个字段）")
			@Size(max = 200000, message = "content 过长（上限 200000 字符）；请拆成多个文件，或改用 edit_file 只改一处")
			String content,

			@NotBlank(message = "purpose 不能为空：用一句话说明这个文件是干什么的（人要靠它决定批不批）")
			@Size(max = 200, message = "purpose 是一句话，不要超过 200 字符")
			String purpose) {
	}

}
