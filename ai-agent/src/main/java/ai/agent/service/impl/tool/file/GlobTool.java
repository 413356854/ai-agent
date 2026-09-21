package ai.agent.service.impl.tool.file;

import ai.agent.config.WorkspaceFileProperties;
import ai.agent.entity.model.tool.AgentToolDefinition;
import ai.agent.entity.model.tool.ToolHints;
import ai.agent.service.impl.tool.file.support.GlobPattern;
import ai.agent.service.impl.tool.file.support.WorkspaceWalker;
import ai.agent.service.tool.AgentToolResult;
import ai.agent.service.tool.workspace.FileAccessDeniedException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具 ⑪：<b>按文件名找文件</b>（不搜内容，搜内容用 {@code grep}）。
 *
 * <h2>它为什么必须在写操作之前被调用</h2>
 * 模型对工作区的了解只有用户那句话（"改一下配置文件"），而它编路径的能力很强 ——
 * 实测里它能连编三个不存在的文件名，每一次都是一轮白往返。
 * 本工具的作用是<b>把"猜"换成"看"</b>：先列出来，再照着结果调 {@code read_file} / {@code edit_file}。
 *
 * <h2>三条边界</h2>
 * <ol>
 *   <li><b>只在工作区内遍历</b>：搜索目录与命中的每一个结果都过一遍边界判定
 *       （{@link WorkspaceWalker}）—— 越出工作区的一律不返回；</li>
 *   <li><b>跳过依赖与版本库目录</b>：{@code .git} / {@code node_modules} / {@code target} 这类目录里
 *       动辄几万个文件，模型要的从来不是它们。不看它们的代价是"想看时看不到"（有日志可查），
 *       看它们的代价是**每一次 glob 都慢一个数量级、结果里全是噪音**；</li>
 *   <li><b>结果有上限，且撞到上限会说</b>：超限时明确写出"还有更多"，并建议缩小 pattern ——
 *       不说的话模型会以为"工作区里就这些文件"。</li>
 * </ol>
 */
@Component
public class GlobTool extends FileToolSupport<GlobTool.Params> {

	public GlobTool(WorkspaceFileProperties limits) {
		super(limits);
	}

	@Override
	public String name() {
		return "glob";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"按文件名模式在工作区里找文件（只看文件名，不看内容）。返回相对路径。",
				"当你不确定某个文件在哪个目录、或者文件名只记得一部分时使用（例如 *Controller.java、**/*.properties）。"
						+ "典型用法：glob 找到文件 → read_file 读它 → edit_file 改它。"
						+ "想按内容找请用 grep。",
				"⚠️ 它不搜文件内容 —— 找「哪份文件里有这个函数」要用 grep。"
						+ "也不要用它列整个工作区：pattern 太宽（例如只有 *）会撞上结果上限，"
						+ "那时你只看到了一部分文件，却很容易当成全部。请把 pattern 写具体一些。",
				"每行一个相对工作区的路径，前面带 [文件] / [目录] 标记；末尾给出命中数量。"
						+ "撞到上限时会明确写着「只显示了前 N 条」，那不是全部。",
				"搜索目录不存在、目录不在工作区内、pattern 不合法 → 返回明确说明；"
						+ "一条都没匹配到时返回的是**正常结果**（附一句「换个更宽的模式试试」），不是失败。",
				ToolHints.readOnly());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		try {
			return glob(parameters);
		}
		catch (FileAccessDeniedException e) {
			return failure(e);
		}
	}

	private AgentToolResult glob(Params parameters) {
		Call call = invocation();
		String dirParam = parameters.path() == null || parameters.path().isBlank() ? "." : parameters.path();
		Path dir = call.access().resolve(dirParam, false);

		if (!Files.exists(dir)) {
			log.info("glob 搜索目录不存在 {}", dir);
			return AgentToolResult.Failure.notFound(
					("搜索目录「%s」在工作区里不存在（解析成 %s）。"
							+ "请先用 path=\".\" 在根上看一眼有哪些顶层目录，再决定去哪找。")
							.formatted(dirParam, dir));
		}
		if (!Files.isDirectory(dir)) {
			return AgentToolResult.Failure.notFound(
					("搜索目录「%s」是一个文件，不是目录。要找单个文件用 read_file；要按名字找请把 path 给成它所在的目录。")
							.formatted(dirParam));
		}

		GlobPattern pattern = GlobPattern.of(parameters.pattern());
		List<String> matched = new ArrayList<>();
		boolean hitLimit = false;
		int scanned = 0;
		try {
			for (Path candidate : WorkspaceWalker.walk(dir, call.access())) {
				scanned++;
				String relative = WorkspaceWalker.relativeTo(dir, candidate);
				if (!pattern.matches(relative)) {
					continue;
				}
				if (matched.size() >= limits().maxEntries()) {
					hitLimit = true;
					break;
				}
				matched.add((Files.isDirectory(candidate) ? "[目录] " : "[文件] ") + relative);
			}
		}
		catch (IOException e) {
			log.warn("glob 遍历失败 dir={}", dir, e);
			return AgentToolResult.Failure.upstream(withMissingNote(call.access(),
					"遍历「%s」失败：%s。".formatted(call.access().display(dir), e.getMessage())));
		}

		log.info("glob pattern={} dir={} 扫描={} 命中={} 截断={}", parameters.pattern(),
				call.access().display(dir), scanned, matched.size(), hitLimit);

		String header = "在「%s」下按「%s」匹配：命中 %d 个%s\n"
				.formatted(call.access().display(dir), parameters.pattern(), matched.size(),
						hitLimit ? "（已截断）" : "");
		if (matched.isEmpty()) {
			return AgentToolResult.ok(header + "\n一个都没有匹配到。"
					+ "可以换一个更宽的模式（例如加上 **/ 前缀，或只写扩展名 *.java），"
					+ "或者用 grep 按内容找 —— 文件名与你记的不一样是常事。");
		}
		String payload = header + "\n" + String.join("\n", matched);
		if (hitLimit) {
			payload += ("\n\n⚠ 这不是全部：结果在上限 %d 条处被截断了，工作区里还有更多匹配的文件。"
					+ "请把 pattern 写得更具体一些（例如指定扩展名或子目录），再查一次。")
					.formatted(limits().maxEntries());
			return new AgentToolResult.Success(payload, true);
		}
		return AgentToolResult.ok(payload);
	}

	/**
	 * 参数。
	 *
	 * <p>{@code pattern} 写成"相对于 {@code path} 的文件名模式"，支持 {@code *}（一段名字内）、
	 * {@code **}（跨目录）、{@code ?} 与花括号并列写法。刻意不要求"必须以 ** 开头"这类格式规矩：
	 * 模型给 {@code *.java} 时它想说的显然是"这个目录树里的 java 文件"，
	 * 而"按规矩拒绝"只会换来一轮重试（见 {@link GlobPattern} 里对 {@code *} 的处理）。
	 */
	public record Params(
			@NotBlank(message = "pattern 不能为空：例如 **/*.java 或 *.properties")
			@Size(max = 200, message = "pattern 过长（上限 200 字符）")
			String pattern,

			@Size(max = 512, message = "path 过长（工作区路径上限是 512 字符）")
			String path) {
	}

}
