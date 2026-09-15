package ai.agent.tool.impl;

import ai.agent.rag.parse.ParsedBlock;
import ai.agent.rag.parse.ParsedDocument;
import ai.agent.rag.pipeline.CorpusLoader;
import ai.agent.tool.AgentTool;
import ai.agent.tool.AgentToolResult;
import ai.agent.tool.model.AgentToolDefinition;
import ai.agent.tool.model.ToolHints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具 ③：按章节标题读出<b>完整正文</b>。
 *
 * <h2>它为什么必须存在：检索的"片段"回答不了"完整"的问题</h2>
 * {@link SearchDocsTool} 返回的是片段（每条 220 字截断），这适合"定位到哪一节"，
 * 但用户的问题经常是"这一节到底怎么说的" —— 那时片段不够，模型只能靠拼凑，
 * 而拼凑的结果看起来像答案、实际上缺了关键的一句。
 *
 * <p>这正是路线文档里「父子块（small-to-big）」想解决的问题：
 * <b>用小块去检索（精确），用大块去回答（完整）</b>。
 * 本工具就是那个"大块"的入口 —— 检索负责找到章节名，本工具负责把那一节完整取出来。
 *
 * <h2>它是"未知值必须回填可用值"的最佳样本</h2>
 * 它有一个 {@code documentId} 参数，而模型<b>极其容易编造文档名</b>
 * （它会推测出 {@code "学习进度台账.md"} 这种看起来合理的名字）。
 * 所以这里对"文档不存在"的处理方式，是这条路线上最值得看的一段代码：
 * <b>拒绝它、并把真实存在的文档名全部列出来</b>。
 * 只说"文档不存在"的话，模型的下一个动作是再编一个 —— 实测里它能连编三次。
 *
 * <h2>为什么"章节没找到"是成功而不是失败</h2>
 * 与上面相反：文档存在、检索方式也对，只是这一节的名字没匹配上 ——
 * 这是<b>正常的空结果</b>（就像搜索没有命中）。
 * 所以它走 {@link AgentToolResult.Success}，并在文本里给出该文档真实的章节名，
 * 让模型自己意识到"我记错了标题"。
 */
@Component
public class ReadSectionTool implements AgentTool<ReadSectionTool.Params> {

	private static final Logger log = LoggerFactory.getLogger(ReadSectionTool.class);

	/** 单次返回的正文上限（字符）。超过就截断并显式说明 —— 见 ToolFeedbackFormatter 的规则 3。 */
	private static final int MAX_CHARS = 1800;

	private final CorpusLoader corpusLoader;

	public ReadSectionTool(CorpusLoader corpusLoader) {
		this.corpusLoader = corpusLoader;
	}

	@Override
	public String name() {
		return "read_section";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"读出指定文档里某一节的完整正文（不是片段）。",
				"当 search_docs 已经告诉你在哪一节、而你需要那一节的完整内容才能回答时使用。"
						+ "典型的两步配合：先用 search_docs 找到章节，再用本工具把那一节读全。",
				"不要在还不知道有哪份文档时就调用它（会浪费一轮）—— 先调 list_documents 或 search_docs。"
						+ "也不要指望它返回整份文档：它只返回一节，且正文有长度上限。",
				"返回该节的完整正文（过长时截断并说明），以及这一节在文档里的位置。",
				"文档名不存在会被拒绝，并在回填里列出真实可用的文档名 —— 请照抄那些名字，不要自己推测。"
						+ "章节名没匹配上时返回的是正常结果（附该文档的真实章节列表），换一个标题再试即可。",
				ToolHints.readOnly());
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		CorpusLoader.CorpusLoadResult corpus = corpusLoader.load();

		// ---- 文档名必须真实存在 ----
		ParsedDocument document = corpus.documents().stream()
				.filter(d -> d.documentId().equals(parameters.documentId()))
				.findFirst()
				.orElse(null);

		if (document == null) {
			List<String> available = corpus.documents().stream().map(ParsedDocument::documentId).sorted().toList();
			// 用 Failure 而不是 Success：模型必须"改"而不是"换一种读法"。
			// 不带 retriable：同样的文档名再试一次一定还是不存在
			log.warn("read_section 收到不存在的文档名 documentId={}", parameters.documentId());
			return AgentToolResult.Failure.notFound(("文档「%s」不存在。可用的文档名（请照抄，不要推测）：%s")
					.formatted(parameters.documentId(), available.isEmpty() ? "（知识库为空）" : String.join("、", available)));
		}

		// ---- 按标题层级定位这一节的范围 ----
		List<ParsedBlock> blocks = document.blocks();
		int start = -1;
		int level = 0;
		for (int i = 0; i < blocks.size(); i++) {
			ParsedBlock block = blocks.get(i);
			if (block.isHeading() && matches(block.text(), parameters.section())) {
				start = i;
				level = block.level();
				break;
			}
		}

		if (start < 0) {
			List<String> headings = blocks.stream().filter(ParsedBlock::isHeading).map(ParsedBlock::text).limit(12)
					.toList();
			log.info("read_section 章节未匹配 documentId={} section={}", parameters.documentId(), parameters.section());
			return AgentToolResult.ok(("文档「%s」里没有找到标题匹配「%s」的章节。\n该文档的章节有：%s\n"
					+ "提示：请从上一条消息里提到的章节名中挑一个，或改用 search_docs 先定位。")
					.formatted(parameters.documentId(), parameters.section(),
							headings.isEmpty() ? "（该文档没有识别出标题）" : String.join("、", headings)));
		}

		// ---- 收集这一节的正文：到"下一个同级或更高级标题"为止 ----
		StringBuilder body = new StringBuilder();
		int contentBlocks = 0;
		for (int i = start; i < blocks.size(); i++) {
			ParsedBlock block = blocks.get(i);
			if (i > start && block.isHeading() && block.level() > 0 && block.level() <= level) {
				break;
			}
			if (block.isBlank()) {
				continue;
			}
			contentBlocks++;
			body.append(block.text().strip()).append('\n');
		}

		String text = body.toString().strip();
		log.info("read_section 命中 documentId={} section=\"{}\" 块下标={} 层级={} 正文长度={}",
				parameters.documentId(), parameters.section(), start, level, text.length());

		if (text.isEmpty()) {
			return AgentToolResult.ok(
					"文档「%s」的章节「%s」（块下标 %d）是空的 —— 它可能只是一个分组标题，正文在它的子章节里。"
							.formatted(parameters.documentId(), parameters.section(), start));
		}

		boolean truncated = text.length() > MAX_CHARS;
		String payload = ("文档「%s」第 %d 个块开始、标题「%s」这一节的完整正文（共 %d 个块）：\n\n%s")
				.formatted(parameters.documentId(), start, parameters.section(), contentBlocks,
						truncated ? text.substring(0, MAX_CHARS) : text);
		return truncated ? AgentToolResult.truncatedAt(payload) : AgentToolResult.ok(payload);
	}

	/** 标题匹配：允许模型只给标题的一部分（它经常省略编号或后半句）。 */
	private static boolean matches(String headingText, String wanted) {
		if (headingText == null || wanted == null) {
			return false;
		}
		String a = headingText.strip();
		String b = wanted.strip();
		return a.equals(b) || a.contains(b) || b.contains(a);
	}

	/**
	 * 参数。
	 *
	 * <p>{@code section} 刻意<b>不</b>加正则约束：标题里常带书名号、括号、数字，
	 * 一个 {@code @Pattern} 会变成"看起来合理的限制"却拦掉大量正常标题。
	 * 约束的目的是"让模型少犯错"，而不是"展示我会写正则"——
	 * 拦错正常输入的代价（模型永远调不通、且说明看起来没问题）远大于收益。
	 */
	public record Params(
			@NotBlank(message = "documentId 不能为空")
			@Size(max = 120, message = "documentId 过长；它应该是文档名，例如 roadmap-java-to-agent.md")
			String documentId,

			@NotBlank(message = "section 不能为空")
			@Size(max = 120, message = "section 过长；它应该是章节标题，可以只写标题的一部分")
			String section) {
	}

}
