package ai.agent.service.rag.kb;

import ai.agent.entity.rag.kb.KbHit;

import java.util.List;

/**
 * 知识库检索：多个库并行召回 → 合并排序 → 可选重排 → 取前 K。
 *
 * <h2>停用过滤在应用层，不在 Milvus</h2>
 * 切片的 {@code enabled} 与文档的 {@code enabled} 都只存在库里。召回之后按 uid 回表一次，
 * 把停用的丢掉 —— 之所以不在 Milvus 侧过滤：
 * <ul>
 *   <li>Milvus 那边翻一位要整行重写（没有局部更新，见 {@code db/knowledge-schema.sql} 的头注）；</li>
 *   <li>同一个判断存两份，早晚会不一致，而不一致的表现是"停用了还是被搜出来"。</li>
 * </ul>
 * 代价写在 {@link Result#notes()} 里：粗召回窗口被停用切片占满时，结果会变少，
 * 而那看起来像"语料里没有"。所以只要真的丢掉了候选，就带一条说明。
 */
public interface KbRetrievalService {

	/** 检索。{@code request.finalTopK()/threshold/rerank} 留空时按每个库自己的配置。 */
	Result retrieve(Request request);

	/**
	 * 检索入参。
	 *
	 * @param kbIds 参与检索的知识库（空列表由调用方拦住）
	 * @param query 提问
	 * @param finalTopK 最终取几条；null = 用各库配置里的最大值
	 * @param similarityThreshold 向量相似度下限；null = 用各库配置里的最小值
	 * @param enableRerank 是否重排；null = 用各库配置（任一库开了就重排）
	 * @param explicitRerank 调用方是否**显式**要求重排（true 时重排不可用要报错，见类注释）
	 */
	record Request(List<Long> kbIds, String query, Integer finalTopK, Double similarityThreshold,
			Boolean enableRerank, boolean explicitRerank) {

		public static Request of(List<Long> kbIds, String query) {
			return new Request(kbIds, query, null, null, null, false);
		}
	}

	/**
	 * 检索结果。
	 *
	 * @param hits 命中，按名次排好
	 * @param indexSize 参与检索的切片总数（{@code enabled} 的那些）——"0 条命中"的分母，
	 *            没有它的 0 条命中与"库里是空的"分不开
	 * @param candidateCount 进入重排之前的候选数
	 * @param reranked 这次真的重排了吗
	 * @param rerankSkipReason 没重排的原因；重排了则为 null（"没开"与"开了但没生效"必须分得开）
	 * @param notes 需要用户知道的偏差（例如"粗召回里 4 条已停用被丢掉"）
	 * @param elapsedMs 检索耗时（不含重排的单独计时）
	 */
	record Result(List<KbHit> hits, int indexSize, int candidateCount, boolean reranked, String rerankSkipReason,
			List<String> notes, long elapsedMs) {

		/** 一次都没查成的库名（全部失败时调用方要报 503，而不是回一个空结果）。 */
		public static Result empty(List<String> notes) {
			return new Result(List.of(), 0, 0, false, null, notes, 0L);
		}
	}

}
