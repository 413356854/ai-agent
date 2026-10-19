package ai.agent.service.rag.kb;

import ai.agent.entity.rag.kb.KbHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 把知识库召回结果拼成一段**注入系统提示词**的材料块。
 *
 * <h2>三种形状，缺一不可</h2>
 * <ol>
 *   <li><b>有命中</b>：列出每条的来源文档、标题路径与正文摘录；</li>
 *   <li><b>查了但没命中</b>：写明"在这些知识库里查了 N 个片段，没有与本次问题相关的内容，
 *       请如实说明知识库里没有"，而不是留空；</li>
 *   <li><b>检索失败</b>：写明失败原因（Milvus 连不上、库被删了…），并说明"这次回答里没有知识库内容"。</li>
 * </ol>
 * ② 与 ③ 对模型的指令是相反的：②要求它承认"没有"，③要求它承认"没查成"但**不能**因此
 * 编造"知识库里没有"。合成一种就会让它在两种情况下都按常识硬答。
 * 第四种（什么都不注入）只有一种情况：调用方没勾任何知识库。
 *
 * <h2>块的大小要有上界</h2>
 * 摘录每条 220 字（与 {@code search_docs} 工具同一个数），块里最多列
 * {@code ai.rag.kb.search.top-k} 条 —— 不然一次提问可能往系统提示词里塞进几万字，
 * 而那笔账会在 token 预算里以"莫明其妙超了"的形式出现。
 */
@Component
public class KnowledgeBaseContextInjector {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseContextInjector.class);

	/** 每条摘录的字符数（与 {@code SearchDocsTool} 同一个数，理由见那个类的注释）。 */
	private static final int EXCERPT_CHARS = 220;

	private final KbRetrievalService retrieval;

	public KnowledgeBaseContextInjector(KbRetrievalService retrieval) {
		this.retrieval = retrieval;
	}

	/**
	 * 检索并拼块。
	 *
	 * @return 没指定知识库时返回空；指定了就一定有一块（哪怕是"没命中"/"失败了"那两种）
	 */
	public Optional<Injection> inject(String query, List<Long> kbIds) {
		if (kbIds == null || kbIds.isEmpty()) {
			return Optional.empty();
		}
		try {
			KbRetrievalService.Result result = retrieval.retrieve(KbRetrievalService.Request.of(kbIds, query));
			return Optional.of(new Injection(render(result), result));
		}
		catch (RuntimeException e) {
			// 注入失败不能打断对话：把失败**写进提示词**，让模型知道这次没有材料可用
			log.warn("知识库注入失败（本次回答不带材料）：{}", e.getMessage());
			return Optional.of(new Injection(failureBlock(e.getMessage()), null));
		}
	}

	/** 注入结果：给模型看的文本 + 给界面看的明细（失败时为 null）。 */
	public record Injection(String block, KbRetrievalService.Result result) {
	}

	private String render(KbRetrievalService.Result result) {
		if (result.hits().isEmpty()) {
			return """
					## 知识库检索结果

					已经在 %d 个文档片段里检索过，**没有找到与本次问题相关的内容**。
					如果问题超出这些知识库的范围，请如实说明「知识库里没有相关内容」，不要凭常识作答。
					""".formatted(result.indexSize());
		}
		StringBuilder sb = new StringBuilder();
		sb.append("## 知识库检索结果\n\n");
		sb.append("本次召回 %d 条（按相关度排序），回答时优先依据它们，并说明内容来自哪个文档：\n"
			.formatted(result.hits().size()));
		for (KbHit hit : result.hits()) {
			sb.append("\n[%d] 来源：%s".formatted(hit.rank(), hit.source()));
			if (hit.headingPath() != null && !hit.headingPath().isBlank()) {
				sb.append("  ／  %s".formatted(hit.headingPath()));
			}
			sb.append("\n    正文：%s\n".formatted(excerpt(hit.text())));
		}
		if (!result.notes().isEmpty()) {
			sb.append("\n（检索过程中的偏差：%s）\n".formatted(String.join("；", result.notes())));
		}
		return sb.toString();
	}

	private static String failureBlock(String reason) {
		return """
				## 知识库检索结果

				⚠️ 知识库检索失败（原因：%s）。这次回答里**没有**知识库内容 ——
				请在回答中说明这一点，不要把它说成「知识库里没有相关内容」。
				""".formatted(reason);
	}

	private static String excerpt(String text) {
		String flat = text.replace('\n', ' ').strip();
		return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS) + "…";
	}

}
