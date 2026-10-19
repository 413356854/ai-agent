package ai.agent.service.impl.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import ai.agent.entity.rag.kb.KbHit;
import ai.agent.service.rag.kb.KbEmbeddingModelFactory;
import ai.agent.service.rag.kb.KbRerankService;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排的默认实现（DashScope）。
 *
 * <h2>怎么把结果对回候选</h2>
 * 送进去的是我们自己的 {@link Document}（id = 切片 uid），而
 * {@code DashScopeRerankModel} 在组装结果时用的是 {@code request.getInstructions().get(index)}
 * —— 也就是**原样返回我们给的那些对象**（源码核过）。所以按 {@code getOutput().getId()} 就能精确对上，
 * 不需要按正文模糊匹配（那样在"两个块正文相同"时会串位）。
 *
 * <p>对上不上的那一条**跳过并记一条说明**，而不是硬塞一个空位：
 * 重排结果与候选错位在界面上表现为"标题与正文不是同一块"，很难想到是重排做的。
 */
@Service
public class DefaultKbRerankService implements KbRerankService {

	private static final Logger log = LoggerFactory.getLogger(DefaultKbRerankService.class);

	private final KnowledgeBaseProperties properties;

	private final KbEmbeddingModelFactory embeddings;

	public DefaultKbRerankService(RagProperties rag, KbEmbeddingModelFactory embeddings) {
		this.properties = rag.getKb();
		this.embeddings = embeddings;
	}

	@Override
	public boolean available() {
		return unavailableReason() == null;
	}

	@Override
	public String unavailableReason() {
		// key 与 embedding 是同一把：embedding 不可用时重排同样不可用
		String embeddingReason = embeddings.unavailableReason();
		if (embeddingReason != null) {
			return embeddingReason;
		}
		String model = properties.getRerank().getModel();
		if (model == null || model.isBlank()) {
			return "ai.rag.kb.rerank.model 为空";
		}
		return null;
	}

	@Override
	public Outcome rerank(String query, List<KbHit> candidates, int topN) {
		if (candidates == null || candidates.isEmpty()) {
			return new Outcome(List.of(), false, "没有候选可重排");
		}
		if (candidates.size() == 1) {
			// 一条候选重排之后还是它自己 —— 说"重排过"是不诚实的
			return new Outcome(ranked(candidates), false, "只有 1 条候选，重排不会改变顺序");
		}
		String reason = unavailableReason();
		if (reason != null) {
			return new Outcome(ranked(candidates), false, reason);
		}

		int keep = topN > 0 ? Math.min(topN, candidates.size()) : candidates.size();
		DashScopeApi api = DashScopeApi.builder().apiKey(properties.getEmbedding().getApiKey().strip()).build();
		DashScopeRerankOptions options = DashScopeRerankOptions.builder()
			.model(properties.getRerank().getModel().strip())
			.topN(keep)
			// 正文我们自己有（清单里那份是权威），不需要上游再回一份
			.returnDocuments(false)
			.build();
		DashScopeRerankModel model = new DashScopeRerankModel(api, options);

		Map<String, KbHit> byUid = new LinkedHashMap<>();
		List<Document> documents = new ArrayList<>(candidates.size());
		for (KbHit hit : candidates) {
			byUid.put(hit.chunkUid(), hit);
			documents.add(Document.builder().id(hit.chunkUid()).text(hit.text()).build());
		}

		try {
			RerankResponse response = model.call(new RerankRequest(query, documents, options));
			List<KbHit> reranked = new ArrayList<>();
			int unmatched = 0;
			for (DocumentWithScore result : response.getResults()) {
				Document document = result.getOutput();
				KbHit origin = document == null ? null : byUid.get(document.getId());
				if (origin == null) {
					unmatched++;
					continue;
				}
				reranked.add(origin.withRerankScore(result.getScore() == null ? 0.0 : result.getScore()));
			}
			if (unmatched > 0) {
				log.warn("重排返回了 {} 条对不上候选的结果 —— 已跳过（候选 {} 条）", unmatched, candidates.size());
			}
			return new Outcome(ranked(reranked), true, null);
		}
		catch (RuntimeException e) {
			// 不降级成"静默按向量顺序返回"：调用方与界面必须知道这次没排成
			log.warn("重排调用失败，本次按向量顺序返回：{}", e.getMessage());
			return new Outcome(ranked(candidates), false, "重排调用失败：" + e.getMessage());
		}
	}

	/** 按重排分（没有就按向量分）降序排好并重新编号。 */
	private static List<KbHit> ranked(List<KbHit> hits) {
		List<KbHit> sorted = new ArrayList<>(hits);
		sorted.sort((left, right) -> {
			double leftScore = left.rerankScore() != null ? left.rerankScore() : left.vectorScore();
			double rightScore = right.rerankScore() != null ? right.rerankScore() : right.vectorScore();
			return Double.compare(rightScore, leftScore);
		});
		List<KbHit> ranked = new ArrayList<>(sorted.size());
		for (int i = 0; i < sorted.size(); i++) {
			ranked.add(sorted.get(i).withRank(i + 1));
		}
		return ranked;
	}

}
