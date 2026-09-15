package ai.agent.rag.chunk;

import java.util.List;

/**
 * 一个检索单元（分块结果）。
 *
 * <h2>为什么 {@code headingPath} 要跟着块一起走，而不是用时再算</h2>
 * 因为块被检索出来之后是"孤儿"——它离开了原文档，没人知道它属于哪一节。
 * 排查召回错误时最需要的一条信息恰恰是这个：<b>召回的块属于哪一节</b>
 * （召回错了，是分块把它切散了，还是它本来就该被召回？）。
 * 后面接重排（Rerank）时，标题路径也是常用的特征之一。
 *
 * @param id            块标识，全局唯一（{@code 文档id#序号}）
 * @param documentId    来源文档
 * @param index         在本文档内的序号，从 0 开始
 * @param text          块正文（**含标题面包屑**，即真正会被送去检索与喂给模型的文本）
 * @param headingPath   标题路径，例如 {@code ["四、踩坑知识库"]}；无标题文档为空列表
 * @param startBlock    起始块序号（对应 {@link ai.agent.rag.parse.ParsedDocument} 的下标，闭区间）
 * @param endBlock      结束块序号（闭区间）
 * @param tokenCount    估算 token 数（来自 {@code ai.agent.token.TokenEstimator}）
 * @param charCount     字符数
 */
public record Chunk(String id, String documentId, int index, String text, List<String> headingPath,
		int startBlock, int endBlock, int tokenCount, int charCount) {

	public Chunk {
		headingPath = List.copyOf(headingPath);
	}

	/** 面包屑字符串形式，便于展示与日志。 */
	public String breadcrumb() {
		return String.join(" > ", headingPath.stream().filter(s -> s != null && !s.isBlank()).toList());
	}

	/** 覆盖的解析块数量（含起止两端）。 */
	public int coveredBlocks() {
		return endBlock - startBlock + 1;
	}

}
