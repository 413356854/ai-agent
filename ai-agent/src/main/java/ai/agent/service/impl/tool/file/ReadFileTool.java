package ai.agent.service.impl.tool.file;

import ai.agent.config.WorkspaceFileProperties;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.workspace.FileAccessDeniedException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 工具 ⑧：<b>读工作区里一个文本文件</b>（带行号、可分页）。
 *
 * <h2>它与 {@code read_section} 的分工</h2>
 * {@code read_section} 读的是<b>知识库语料</b>（打包在应用里的那几份文档，按章节定位），
 * 本工具读的是<b>用户工作区里的文件</b>（路径定位）。两把工具解决的是不同的问题：
 * 前者回答"项目文档里怎么说"，后者回答"这个目录下的代码/配置现在是什么样"。
 * 混成一把的话，参数里就得同时有"章节名"和"路径"，而模型会在两个体系之间来回猜。
 *
 * <h2>为什么行号是必须的（不是"好看"）</h2>
 * 模型读到一段内容之后的下一步通常是 {@code edit_file}，而那个工具按<b>原文精确匹配</b>替换。
 * 没有行号时它只能凭印象重写一遍那几行 —— 少一个空格就匹配不上。
 * 行号让"改哪一行"在对话里是一个可以指着说的东西（回填文本里行号与内容同排）。
 *
 * <h2>三条边界，每条都对应一种"看起来成功了其实没有"</h2>
 * <ol>
 *   <li><b>路径必须在工作区内</b>（判定见 {@link ai.agent.service.tool.workspace.FileAccess}）——
 *       越界一律拒绝，不把 {@code /etc/passwd} 静默改写成工作区里的同名文件；</li>
 *   <li><b>二进制文件拒读</b>：按 ISO-8859-1 逐字节读一遍，出现 NUL 就停。
 *       用 UTF-8 直接读的话会抛格式异常，而那个错误看起来像"文件坏了"；
 *       更糟的是宽松解码会把二进制解成一片替换字符，模型会拿它当内容去分析；</li>
 *   <li><b>超限必须显式说明</b>：单文件字节上限与本次行数上限撞到时，
 *       回填文本里写清"这不是全部、剩下的怎么看"——
 *       截断了不说，模型会以为那就是整个文件（见 {@code ToolFeedbackFormatter} 的规则 3）。</li>
 * </ol>
 */
@Component
public class ReadFileTool extends FileToolSupport<ReadFileTool.Params> {

	/** 单文件字节上限。一倍于 write_file 的上限，于是"刚写出来的文件一定读得回来"。 */
	private static final long MAX_BYTES = 1_048_576L;

	/** 二进制探测的窗口：只看开头这一段（一个文本文件的前 8KB 里出现 NUL 的概率可以忽略）。 */
	private static final int SNIFF_BYTES = 8192;

	/** 回填文本里每行内容的截断长度（超长行只影响显示，不影响行号）。 */
	private static final int MAX_CHARS_PER_LINE = 500;

	public ReadFileTool(WorkspaceFileProperties limits) {
		super(limits);
	}

	@Override
	public String name() {
		return "read_file";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"读工作区里的一个文本文件，返回带行号的内容（可分页与限行）。",
				"当用户提到工作区里的某个文件、或你从 glob / grep 的结果里已经知道要读哪个文件时使用。"
						+ "典型的三步配合：glob 找到文件名 → read_file 看清内容 → edit_file 改其中一处。"
						+ "不知道文件在哪时先用 glob 或 grep，不要猜路径。",
				"它读不了工作区之外的文件（会被拒绝，并告诉你工作区是什么）。"
						+ "读**项目知识库**（那几份文档）不要用它 —— 用 list_documents / search_docs / read_section。"
						+ "二进制文件（图片、压缩包、class）会被拒绝：这里只处理文本。",
				"返回带行号的内容（每行形如 12| 代码，行号从 1 开始）；"
						+ "被 limit 截断、或文件超过 1 MB 时，文本里会明确说明「还有多少没显示」以及怎么看剩下的（用 offset）。",
				"路径不在工作区内、文件不存在、是目录、是二进制、是空文件 —— 这几种都会返回明确说明；"
						+ "路径类失败原样重试不会有不同结果，请先 glob 确认文件名与位置。",
				ToolHints.readOnly());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		try {
			return read(parameters);
		}
		catch (FileAccessDeniedException e) {
			return failure(e);
		}
	}

	private AgentToolResult read(Params parameters) {
		Call call = invocation();
		Path file = call.access().resolve(parameters.path(), false);
		String display = call.access().display(file);
		int offset = parameters.offset() == null ? 1 : parameters.offset();
		int limit = parameters.limit() == null ? limits().maxLines() : parameters.limit();

		if (!Files.exists(file)) {
			log.info("read_file 文件不存在 {}", file);
			return AgentToolResult.Failure.notFound(
					("工作区里没有文件「%s」（解析成 %s）。"
							+ "请先用 glob 按文件名找一遍，不要靠猜路径；如果它本该存在，就把这件事告诉用户。")
							.formatted(parameters.path(), display));
		}
		if (Files.isDirectory(file)) {
			log.info("read_file 收到目录 {}", file);
			return AgentToolResult.Failure.notFound(
					("「%s」是一个目录，不是文件。请给它一个文件路径（目录里有哪些文件可以用 glob 查）。")
							.formatted(display));
		}
		try {
			long size = Files.size(file);
			if (size > MAX_BYTES) {
				log.warn("read_file 文件超过上限 {} 字节：{}", MAX_BYTES, file);
				return AgentToolResult.Failure.notFound(
						("文件「%s」有 %d 字节，超过单文件读取上限 %d 字节 —— 本次没有读它。"
								+ "请改用 grep 按关键词在这份文件里定位，或让用户说明要看哪一段。")
								.formatted(display, size, MAX_BYTES));
			}
			if (isBinary(file, size)) {
				log.info("read_file 拒绝二进制文件 {}", file);
				return AgentToolResult.Failure.notFound(
						("文件「%s」看着是二进制（前 %d 字节里有 NUL 字节），本工具只读文本文件。"
								+ "如果它是图片或压缩包，请把结论建立在别的证据上。").formatted(display, SNIFF_BYTES));
			}

			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			if (lines.isEmpty()) {
				return AgentToolResult.ok("文件「%s」是空的（0 行、0 字节）。".formatted(display));
			}
			if (offset > lines.size()) {
				return AgentToolResult.ok(
						("offset=%d 超过了文件行数（「%s」一共 %d 行）。请把 offset 改到 1~%d 之间 —— "
								+ "你想看的那一段大概就在末尾附近。").formatted(offset, display, lines.size(),
										lines.size()));
			}

			int from = offset - 1;
			int to = (int) Math.min((long) lines.size(), (long) from + limit);
			StringBuilder body = new StringBuilder();
			for (int i = from; i < to; i++) {
				body.append("%5d| %s%n".formatted(i + 1, clip(lines.get(i))));
			}
			String payload = "文件「%s」：共 %d 行\n（本次显示第 %d~%d 行）\n\n%s"
					.formatted(display, lines.size(), offset, to, body);
			boolean truncated = to < lines.size();

			log.info("read_file 命中 path={} 总行数={} 显示={}~{} 截断={}", display, lines.size(), offset, to, truncated);

			if (truncated) {
				payload += ("\n⚠ 这不是全部：文件还有 %d 行没显示（第 %d~%d 行）。"
						+ "★ 不要用这些内容下「整份文件就是这样」的结论 —— "
						+ "要接着看就用同一个 path 加上 offset=%d 再读一次。")
						.formatted(lines.size() - to, to + 1, lines.size(), to + 1);
				return new AgentToolResult.Success(payload, true);
			}
			return AgentToolResult.ok(payload);
		}
		catch (CharacterCodingException e) {
			log.info("read_file 解码失败（不是 UTF-8）：{}", file);
			return AgentToolResult.Failure.notFound(
					("文件「%s」不是 UTF-8 文本（解码失败）。如果它是 GBK 之类的编码，"
							+ "请告诉用户编码对不上，不要凭猜测解释内容。").formatted(display));
		}
		catch (IOException e) {
			log.warn("read_file 读取失败 path={}", file, e);
			return AgentToolResult.Failure.upstream(withMissingNote(call.access(),
					"读取「%s」失败：%s。".formatted(display, e.getMessage())));
		}
	}

	/**
	 * 二进制探测：按字节读前 {@link #SNIFF_BYTES} 个字节，出现 {@code 0x00} 就判为二进制。
	 *
	 * <p>判据只用 NUL 一个：它是"这段不是文本"最可靠的信号，而更复杂的启发式
	 * （控制字符比例等）会把某些合法的文本文件误判成二进制 —— 那类误判的代价是
	 * "模型明明该读到这个文件却读不到"，且原因看不出来。
	 */
	private static boolean isBinary(Path file, long size) throws IOException {
		if (size == 0) {
			return false;
		}
		byte[] head = new byte[(int) Math.min(SNIFF_BYTES, size)];
		try (var in = Files.newInputStream(file)) {
			int read = in.readNBytes(head, 0, head.length);
			for (int i = 0; i < read; i++) {
				if (head[i] == 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** 单行过长时截断显示：行号仍然准，但那一行的内容不占满整个上下文。 */
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
	 * <p>{@code offset} 与 {@code limit} 用包装类型：模型不传时是 {@code null}（走默认值），
	 * 而基本类型 {@code int} 不传会反序列化成 {@code 0} —— 一个看起来完全合法的值，
	 * 而 {@code offset=0} 与"没给"的含义完全不同（本工具的语义是行号从 1 开始）。
	 */
	public record Params(
			@NotBlank(message = "path 不能为空：它是相对于工作区的文件路径")
			@Size(max = 512, message = "path 过长（工作区路径上限是 512 字符）")
			String path,

			@Min(value = 1, message = "offset 是起始行号，从 1 开始")
			Integer offset,

			@Min(value = 1, message = "limit 至少是 1 行")
			Integer limit) {
	}

}
