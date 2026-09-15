package ai.agent.tool.impl;

import ai.agent.rag.chunk.Chunk;
import ai.agent.rag.pipeline.RagEvaluationService;
import ai.agent.rag.retrieve.LexicalRetriever;
import ai.agent.rag.retrieve.RetrievalHit;
import ai.agent.tool.AgentTool;
import ai.agent.tool.AgentToolResult;
import ai.agent.tool.model.AgentToolDefinition;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具 ①：在项目语料里检索相关片段（无参工具之外的<b>主力工具</b>，也是 ReAct 循环里最常被调用的那个）。
 *
 * <h2>为什么这是本项目里"最真实"的一把工具</h2>
 * 它不是为演示造出来的假工具 —— 它复用的就是阶段二那条评测链路
 * （{@link RagEvaluationService#chunkCorpus} 切块 + {@link LexicalRetriever} 建索引检索）。
 * 也就是说：<b>Agent 检索到的东西，与评测集里量过的召回率是同一套代码、同一套分块</b>。
 * 这一点很重要 —— 如果 Agent 另起一套检索，那么阶段二量出来的
 * "Recall@5 = 0.79" 就不能用来预测 Agent 的实际表现，那批数字就白测了。
 *
 * <h2>它在 ReAct 循环里的角色：<b>它是"观察"的来源</b></h2>
 * think → act → observe 三步里，它的返回值就是 observe。
 * 所以这里的返回文本是<b>给人读的</b>（带标题路径、带片段的开头），而不是抽象结构 ——
 * 模型要拿它去判断"够不够回答用户的问题"，字段名对判断没有帮助。
 *
 * <h2>两个刻意的取舍</h2>
 * <ol>
 *   <li><b>topK 有上限（{@code @Max(10)}），而不是"要多少给多少"</b>：
 *       模型对"多给点"这件事没有成本感。实测里它第一次调用经常直接要 20 条，
 *       而 20 个块约 4000 token，其中真正有用的通常只有 1~2 条。
 *       把上限压住，等于把 Token 成本的控制放在<b>工具契约</b>这一层 ——
 *       这比事后在循环里裁更可靠，因为模型看得到这个上限（schema 里有 maximum）。</li>
 *   <li><b>返回"没查到"时给出建议的下一步</b>：这是个正常的空结果（不是失败），
 *       但模型看到"0 条"时最常见的反应是<b>原样重试</b>。所以这里直接告诉它换同义词试试。</li>
 * </ol>
 *
 * <p><b>关于索引的时机</b>：每次调用都重建一次索引。语料只有 4 份文档 / 516 个块，
 * 实测单次建索引是毫秒级，而 4 份真实文档的检索与评测用同一套代码 —— 这个代价换的是
 * "Agent 看到的知识库与评测量过的知识库绝不可能是两个状态"。
 * 将来语料变大时，这里要换成一个带失效策略的缓存（届时"什么时候重建"本身会变成一个新问题：
 * 缓存旧了会给出过时答案且不报错）。
 */
@Component
public class SearchDocsTool implements AgentTool<SearchDocsTool.Params> {

	private static final Logger log = LoggerFactory.getLogger(SearchDocsTool.class);

	/** 默认召回条数（模型不指定 topK 时用）。 */
	private static final int DEFAULT_TOP_K = 5;

	private final RagEvaluationService evaluationService;

	public SearchDocsTool(RagEvaluationService evaluationService) {
		this.evaluationService = evaluationService;
	}

	@Override
	public String name() {
		return "search_docs";
	}

	@Override
	public AgentToolDefinition description() {
		return new AgentToolDefinition(name(),
				"在项目的技术文档库里做关键词检索，返回最相关的若干片段。",
				"当用户的问题涉及「项目里是怎么做的 / 踩过什么坑 / 某个结论是什么数字」这类需要查文档才能回答的内容时使用。"
						+ "例如「分块策略的结论是什么」「token 预算的系数怎么校准的」。",
				"不要用它回答常识问题、也不要用它做数学计算 —— 它只在项目语料里找文字。"
						+ "如果用户问的是「你叫什么」这种与文档无关的问题，直接回答即可。",
				"返回若干条片段，每条带：名次、所属章节（标题路径）、估算 token 数、片段开头截断。"
						+ "结果按相关度从高到低排列；如果一条都没有，说明没有匹配的文档。",
				"查询词过长或为空会被拒绝（会在回填里说明原因）；"
						+ "返回 0 条时不是错误，而是提示你换更宽泛或同义的词再试一次。");
	}

	@Override
	public Class<Params> parameterType() {
		return Params.class;
	}

	@Override
	public AgentToolResult invoke(Params parameters) {
		List<Chunk> chunks = evaluationService.chunkCorpus("structural");
		LexicalRetriever retriever = new LexicalRetriever();
		retriever.index(chunks);

		int topK = parameters.topK() == null ? DEFAULT_TOP_K : parameters.topK();
		List<RetrievalHit> hits = retriever.retrieve(parameters.query(), topK);
		log.info("search_docs query=\"{}\" topK={} 命中={} 索引块数={}", parameters.query(), topK, hits.size(),
				chunks.size());

		if (hits.isEmpty()) {
			// 空结果是正常结果 → Success，不是 Failure。
			// 但"下一步怎么办"必须写出来，否则模型会原样重试（见类注释）
			return AgentToolResult.ok(
					"在 %d 个文档片段里没有找到与「%s」相关的内容。\n建议：换用更宽泛的关键词、或改用文档里可能出现的同义词再试一次。"
							.formatted(chunks.size(), parameters.query()));
		}

		StringBuilder sb = new StringBuilder();
		sb.append("在 %d 个文档片段里找到 %d 条相关内容（按相关度排序）：\n".formatted(chunks.size(), hits.size()));
		for (RetrievalHit hit : hits) {
			Chunk chunk = hit.chunk();
			sb.append("\n[%d] 章节：%s\n".formatted(hit.rank(),
					chunk.breadcrumb().isBlank() ? "(无标题)" : chunk.breadcrumb()));
			sb.append("    来源：%s  估算 %d token\n".formatted(chunk.documentId(), chunk.tokenCount()));
			sb.append("    内容：%s\n".formatted(excerpt(chunk.text())));
		}
		return AgentToolResult.ok(sb.toString().strip());
	}

	/**
	 * 片段摘录。
	 *
	 * <p>每条片段本身要短：这是<b>喂给模型的上下文预算</b>，而不是给用户看的报告。
	 * 每条 220 字 × 5 条 ≈ 1100 字，加上外层回填格式约 1600 字符，
	 * 在 {@code ai.tool.feedback.max-chars}（默认 2000）之内不会被截断 ——
	 * 这个数字是配合那个上限定的，改一处要一起改。
	 */
	private static String excerpt(String text) {
		String flat = text.replace('\n', ' ').replaceAll("\\s+", " ").strip();
		return flat.length() <= 220 ? flat : flat.substring(0, 220) + "…";
	}

	/**
	 * 参数。
	 *
	 * <p>注意这里的注解<b>同时</b>是三件事的唯一来源：JSON Schema（模型看到的限制）、
	 * JSR-380 校验（Java 侧执行的限制）、以及参数说明。
	 * 加一个 {@code @Max(10)} 就同时改了模型看到的 maximum 和真实的校验 —— 不可能漂移。
	 */
	public record Params(
			@NotBlank(message = "查询词不能为空")
			@Size(max = 100, message = "查询词最长 100 字；它应该是几个关键词，而不是一整段话")
			String query,

			@Min(value = 1, message = "topK 至少为 1")
			@Max(value = 10, message = "topK 最大 10；这是上下文预算限制，需要更多内容请分多次、用更精确的词检索")
			Integer topK) {
	}

}
