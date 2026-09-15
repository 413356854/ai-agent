package ai.agent.tool.impl;

import ai.agent.rag.pipeline.CorpusLoader;
import ai.agent.tool.AgentTool;
import ai.agent.tool.AgentToolResult;
import ai.agent.tool.model.AgentToolDefinition;
import ai.agent.tool.model.ToolHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具 ②：列出知识库里有哪些文档。
 *
 * <h2>它为什么值得单独存在（而不是"查不到就多试几次"）</h2>
 * 这是 ReAct 循环里很典型的一类工具：<b>它不直接回答用户的问题，它让模型知道"能查什么"。</b>
 * 模型不知道语料里有什么时，检索行为会退化成"拿用户的原话去碰运气" ——
 * 而语料用的是完全不同的术语体系（用户说"切块"，文档写"分块"）。
 * 先列一次目录，模型才能把用户的话翻译成语料里的词。
 *
 * <h2>它同时是一个"无参工具"的样本</h2>
 * 无参工具在协议上有个容易踩的地方：模型可能给出 {@code {}}、{@code ""}、
 * 甚至干脆不给 {@code arguments} 字段。{@link ai.agent.tool.ToolCallExecutor}
 * 必须把这三种都当成"合法的空参数"，而不是"解析失败"。
 * 本工具就是那条路径的活体测试用例 —— 有它在，这个分支不会被静默改坏。
 */
@Component
public class ListDocumentsTool implements AgentTool<ListDocumentsTool.NoParams> {

	private static final Logger log = LoggerFactory.getLogger(ListDocumentsTool.class);

	private final CorpusLoader corpusLoader;

	public ListDocumentsTool(CorpusLoader corpusLoader) {
		this.corpusLoader = corpusLoader;
	}

	@Override
	public String name() {
		return "list_documents";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"列出知识库里全部文档的名字、规模与主题（不需要任何参数）。",
				"当你不确定知识库里有没有相关内容、或需要先了解文档构成再决定查什么时使用。"
						+ "典型用法是先调它看一眼，再用 search_docs 去具体检索。",
				"不要在一次任务里反复调用它 —— 语料是固定的快照，结果不会变。调一次就够。",
				"返回每份文档的名字、解析出的块数、字符数，以及它覆盖的顶层章节名（便于判断该去哪份文档里找）。",
				"如果语料目录读取失败会返回明确说明；正常情况下不会失败，本工具无参数因此不存在参数错误。",
				ToolHints.readOnly());
	}

	@Override
	public Class<NoParams> parameterType() {
		return NoParams.class;
	}

	@Override
	public AgentToolResult invoke(NoParams parameters) {
		CorpusLoader.CorpusLoadResult corpus = corpusLoader.load();
		log.info("list_documents 文档数={} 失败数={}", corpus.documents().size(), corpus.failed().size());

		if (corpus.documents().isEmpty()) {
			return AgentToolResult.Failure.upstream(
					"知识库里没有加载到任何文档。这通常意味着语料快照缺失（不是查询方式的问题），请告诉用户知识库当前不可用。");
		}

		StringBuilder sb = new StringBuilder();
		sb.append("知识库共 %d 份文档：\n".formatted(corpus.documents().size()));
		corpus.documents().stream().sorted(Comparator.comparing(d -> d.documentId())).forEach(document -> {
			sb.append("\n- %s\n".formatted(document.documentId()));
			sb.append("    解析出 %d 个块\n".formatted(document.blocks().size()));
			String topSections = topLevelSections(document.blocks());
			if (!topSections.isBlank()) {
				sb.append("    主要章节：%s\n".formatted(topSections));
			}
		});

		if (!corpus.failed().isEmpty()) {
			// 失败文档必须报出来：只有成功的那些被列出来时，模型会以为"知识库就这么大"，
			// 而它因此漏掉的正是那些没解析成功的文档 —— 一个静默的知识缺口
			sb.append("\n⚠ 有 %d 份文档解析失败，它们的内容不在知识库里：%s\n"
					.formatted(corpus.failed().size(),
							corpus.failed().stream().map(CorpusLoader.FailedDocument::documentId).toList()));
		}
		return AgentToolResult.ok(sb.toString().strip());
	}

	/**
	 * 汇总顶层章节名（去重、保序、限量）。
	 *
	 * <p>限量是刻意的：完整标题路径可能有几十个，全列出来会把这把"目录工具"的输出
	 * 变成第二份语料。只给顶层 + 最多 6 个，够模型判断"该去哪个文档找"，又不至于淹掉上下文。
	 */
	private static String topLevelSections(java.util.List<ai.agent.rag.parse.ParsedBlock> blocks) {
		Map<String, Boolean> seen = new LinkedHashMap<>();
		for (var block : blocks) {
			if (block.type() == ai.agent.rag.parse.BlockType.HEADING) {
				String title = block.text() == null ? "" : block.text().strip();
				if (!title.isEmpty()) {
					seen.putIfAbsent(title, Boolean.TRUE);
				}
			}
			if (seen.size() >= 6) {
				break;
			}
		}
		return String.join("、", seen.keySet());
	}

	/**
	 * 空参数占位。
	 *
	 * <p>用空 record 而不是"让 parameterType() 返回 null 并在执行器里特判"：
	 * 后者会让执行器多出一条只对无参工具有效的分支，而那条分支平时不会被走到 ——
	 * 正是"静默退化"最爱的位置。空 record 让无参工具与有参工具走<b>完全相同</b>的解析与校验路径。
	 */
	public record NoParams() {
	}

}
