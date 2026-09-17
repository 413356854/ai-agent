package ai.agent.service.rag.retrieve;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.entity.rag.retrieve.RetrievalHit;

import java.util.List;

/**
 * 检索契约 —— <b>本阶段（评测阶段）最需要被隔离的一层</b>。
 *
 * <h2>为什么评测阶段就要有检索层</h2>
 * 因为"分块策略好不好"没法直接测：文本切得怎么样，没有绝对标准。
 * 唯一可量化的判据是<b>拿它去检索，看正确答案能不能被找回来、排在第几位</b>。
 * 没有检索层就无法回答阶段二的验收问题："换分块策略提升了多少"。
 *
 * <h2>为什么是接口，而且现在只有词法实现</h2>
 * 当前实现 {@link LexicalRetriever} 用的是 BM25（关键词匹配）。
 * 最终形态要求是<b>混合检索</b>：向量兜语义 + BM25 兜精确 + RRF 融合 +
 * Rerank 分层。到那时只需要再写一个 {@code VectorRetriever} 和一个融合器，
 * <b>评测代码一行都不用改</b> —— 这正是把接口留在这里的唯一理由。
 *
 * <p>反过来说：本阶段用词法检索量出来的"分块策略差异"是<b>偏保守</b>的。
 * BM25 只认词面，所以它对"块里有没有正确的词"极其敏感 ——
 * 而向量检索能从语义上兜住词汇不匹配。也就是说：
 * <b>词法检索下表现好的分块，换成向量检索只会更好；反之不成立。</b>
 * 这是本阶段结论可以被采信的原因。
 */
public interface Retriever {

	/** 检索方式名，用作出现在评测报告里的列名。 */
	String name();

	/**
	 * 用索引一批块。
	 *
	 * <p>注意是"重建"语义而非"追加"：评测要求两种分块策略在<b>互不干扰</b>的索引上
	 * 各自检索，否则上一个策略残留的块会稀释分数，两次实验就不是同一个起点了。
	 *
	 * @param chunks 待索引的块
	 */
	void index(List<Chunk> chunks);

	/**
	 * 检索。
	 *
	 * @param query 用户提问
	 * @param topK  最多返回多少条
	 * @return 按相关性降序排列的结果，数量不超过 {@code topK}
	 */
	List<RetrievalHit> retrieve(String query, int topK);

}
