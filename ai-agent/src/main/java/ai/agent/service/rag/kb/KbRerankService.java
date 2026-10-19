package ai.agent.service.rag.kb;

import ai.agent.entity.rag.kb.KbHit;

import java.util.List;

/**
 * 用 DashScope 的重排模型给候选重新排序。
 *
 * <h2>"没开"与"开了但没生效"必须分得开</h2>
 * 重排有五种不生效的情形，每一种都带一句自己的原因回给调用方
 * （见 {@link Outcome#skipReason()}）：
 * 总开关关着 / 这次请求没要求 / 没配 key / 候选不足两条 / 调用失败。
 * 前四种是"装上了没开"，第五种是"开了但失败了" —— 后者必须让人看见，
 * 因为它会静默地把结果退回向量顺序，而人以为排过序了。
 */
public interface KbRerankService {

	/** 现在能不能重排。 */
	boolean available();

	/** 为什么不能；能的时候是 null。 */
	String unavailableReason();

	/**
	 * 给候选重排。
	 *
	 * <p>只重排、不改候选集合：返回的 hit 一定是入参里的那些（按重排分降序），
	 * 因为只有调用方知道每个 uid 对应的正文与来源。
	 *
	 * @param topN 重排后保留几条；小于等于 0 时保留全部候选
	 */
	Outcome rerank(String query, List<KbHit> candidates, int topN);

	/**
	 * 重排结果。
	 *
	 * @param hits 重排后的候选（带 {@code rerankScore} 与新的名次）；没重排时是按向量分排好的原候选
	 * @param applied 这次真的调了重排模型吗
	 * @param skipReason 没调的原因；调了则为 null
	 */
	record Outcome(List<KbHit> hits, boolean applied, String skipReason) {
	}

}
