package ai.agent.rag.chunk;

import ai.agent.rag.parse.ParsedDocument;

import java.util.List;

/**
 * 分块策略契约：把解析后的文档切成检索单元。
 *
 * <h2>为什么做成可替换的策略，而不是写一个"好"的分块器</h2>
 * 阶段二的验收口径是：<b>能用数字说明「换分块策略提升了多少」</b>。
 * 这句话成立的前提是"两种策略能在同一份语料、同一套评测集上并排跑"——
 * 所以分块必须是个可替换的策略，而不是唯一实现。
 *
 * <p>本项目的两个实现刻意代表了两种思路：
 * <ul>
 *   <li>{@link FixedSizeChunker} —— <b>对照组</b>。按固定字数硬切，
 *       也就是路线文档明确警告的"别按固定字数硬切"的那个做法。
 *       留着它不是偷懒，是因为<b>没有对照组就没有结论</b>：
 *       "语义分块更好"这句话只有在"硬切有多差"有具体数字时才站得住。</li>
 *   <li>{@link StructuralChunker} —— 按标题层级 + 段落边界切，带 overlap 与标题面包屑。</li>
 * </ul>
 *
 * <p>注意接口只依赖 {@link ParsedDocument}，不依赖任何解析器实现 ——
 * 将来把 Tika 换成 Layout-Aware 解析器（Docling / 多模态转 Markdown）时，
 * 两个分块器和评测代码一行都不用改。
 */
public interface DocumentChunker {

	/** 策略名，用作评测报告里的列名与诊断端点参数。 */
	String name();

	/**
	 * 切分。
	 *
	 * @param document 解析后的文档
	 * @return 块序列，按文档顺序排列；文档没有内容时返回空列表（不返回 {@code null}）
	 */
	List<Chunk> chunk(ParsedDocument document);

}
