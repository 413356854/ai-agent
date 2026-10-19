package ai.agent.service.impl.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import ai.agent.entity.rag.kb.KbChunk;
import ai.agent.entity.rag.kb.KnowledgeBase;
import ai.agent.entity.rag.kb.KnowledgeDocument;
import ai.agent.mapper.KbChunkMapper;
import ai.agent.mapper.KnowledgeDocumentMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.rag.kb.KbVectorStoreFactory;
import ai.agent.service.rag.kb.KnowledgeBaseException;
import ai.agent.service.rag.kb.KnowledgeBaseService;
import ai.agent.service.rag.kb.KbRerankService;
import ai.agent.service.rag.kb.KbRetrievalService;
import ai.agent.entity.rag.kb.KbHit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检索的默认实现。
 *
 * <h2>逐库并行，单库失败不带崩整次检索</h2>
 * 一次提问打多个库时，一个库超时不该让整次回答失败 —— 它带回 0 条 + 一条说明
 * （"知识库 X 查询失败：…"）。只有**所有**库都失败才由调用方报错：
 * 那时回一个空结果会被读成"语料里没有"，而真相是"没查成"。
 *
 * <h2>两个"分母"都要给</h2>
 * {@code indexSize}（参与检索的切片总数）与 {@code candidateCount}（进重排的候选数）分开记：
 * 前者回答"库里到底有没有东西"，后者回答"这次粗召回了多少"。
 * 只有一个数字时，"召回窗口被停用切片占满"与"库里本来就少"分不开。
 */
@Service
public class DefaultKbRetrievalService implements KbRetrievalService {

	private static final Logger log = LoggerFactory.getLogger(DefaultKbRetrievalService.class);

	/** 一次检索最多并发查几个库。没有再开配置键：它是"后端能同时承受几个查询"的上界，不是业务参数。 */
	private static final int MAX_PARALLEL_KBS = 4;

	private final KnowledgeBaseService knowledgeBases;

	private final KnowledgeDocumentMapper documentMapper;

	private final KbChunkMapper chunkMapper;

	private final KbVectorStoreFactory vectorStores;

	private final KbRerankService rerank;

	private final KnowledgeBaseProperties properties;

	private final ThreadPoolExecutor searchExecutor;

	public DefaultKbRetrievalService(KnowledgeBaseService knowledgeBases, KnowledgeDocumentMapper documentMapper,
			KbChunkMapper chunkMapper, KbVectorStoreFactory vectorStores, KbRerankService rerank, RagProperties rag) {
		this.knowledgeBases = knowledgeBases;
		this.documentMapper = documentMapper;
		this.chunkMapper = chunkMapper;
		this.vectorStores = vectorStores;
		this.rerank = rerank;
		this.properties = rag.getKb();
		this.searchExecutor = new ThreadPoolExecutor(MAX_PARALLEL_KBS, MAX_PARALLEL_KBS, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(64), new SearchThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy());
	}

	@PreDestroy
	public void shutdown() {
		searchExecutor.shutdownNow();
	}

	@Override
	public Result retrieve(Request request) {
		long startedAt = System.currentTimeMillis();
		if (request.query() == null || request.query().isBlank()) {
			throw new KnowledgeBaseException("检索词不能为空");
		}
		if (request.kbIds() == null || request.kbIds().isEmpty()) {
			throw new KnowledgeBaseException("没有指定知识库（kbIds 为空）");
		}
		List<KnowledgeBase> kbs = knowledgeBases.findByIds(request.kbIds());
		if (kbs.size() != new HashSet<>(request.kbIds()).size()) {
			Set<Long> found = new HashSet<>();
			for (KnowledgeBase kb : kbs) {
				found.add(kb.id());
			}
			List<Long> missing = request.kbIds().stream().filter(id -> !found.contains(id)).distinct().toList();
			// 悄悄丢掉找不到的 id 会让"那个库没有相关内容"与"那个库不存在"变成同一件事
			throw new KnowledgeBaseException("知识库不存在：" + missing);
		}

		List<String> notes = new ArrayList<>();
		List<KnowledgeBase> participants = new ArrayList<>();
		for (KnowledgeBase kb : kbs) {
			if (kb.enabled()) {
				participants.add(kb);
			}
			else {
				notes.add("知识库「%s」已停用，本次跳过".formatted(kb.name()));
			}
		}
		int indexSize = 0;
		for (KnowledgeBase kb : participants) {
			indexSize += enabledChunkCount(kb.id());
		}
		if (participants.isEmpty()) {
			return new Result(List.of(), indexSize, 0, false, null, notes,
					System.currentTimeMillis() - startedAt);
		}

		int recallTopK = Math.max(properties.getSearch().getRecallTopK(), requestedTopK(request, participants));
		double threshold = request.similarityThreshold() != null ? request.similarityThreshold()
				: minThreshold(participants);

		Map<Long, CompletableFuture<List<Candidate>>> futures = new LinkedHashMap<>();
		for (KnowledgeBase kb : participants) {
			futures.put(kb.id(), CompletableFuture.supplyAsync(() -> searchOne(kb, request.query(), recallTopK,
					threshold, notes), searchExecutor));
		}

		List<Candidate> candidates = new ArrayList<>();
		int failedKbs = 0;
		for (Map.Entry<Long, CompletableFuture<List<Candidate>>> entry : futures.entrySet()) {
			try {
				candidates.addAll(entry.getValue().get());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failedKbs++;
				notes.add("知识库 #%d 检索被中断".formatted(entry.getKey()));
			}
			catch (ExecutionException e) {
				failedKbs++;
				String reason = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
				notes.add("知识库 #%d 检索失败：%s".formatted(entry.getKey(), reason));
				log.warn("知识库 #{} 检索失败：{}", entry.getKey(), reason);
			}
		}
		if (failedKbs == participants.size()) {
			throw new KnowledgeBaseException("所有知识库都查不了：" + String.join("；", notes)
					+ "。请检查 Milvus 是否在跑（scripts/milvus-start.ps1）");
		}

		// 粗召回 → 停用过滤（应用层做，见 KbRetrievalService 的类注释）
		List<KbHit> hits = toHits(candidates, notes);
		List<KbHit> ordered = new ArrayList<>(hits);
		ordered.sort(Comparator.comparingDouble(KbHit::vectorScore).reversed());

		int wantRerank = request.finalTopK() != null ? request.finalTopK() : maxTopK(participants);
		boolean rerankWanted = request.enableRerank() != null ? request.enableRerank() : anyRerankEnabled(participants);
		if (rerankWanted && request.explicitRerank() && !rerank.available()) {
			// 显式要求重排却做不到 → 报错，而不是"悄悄按向量顺序返回"（见 KbRerankService 的类注释）
			throw new KnowledgeBaseException("这次请求要求重排，但重排不可用：" + rerank.unavailableReason());
		}

		KbRerankService.Outcome outcome = rerankWanted
				? rerank.rerank(request.query(), ordered, Math.max(wantRerank, ordered.size()))
				: new KbRerankService.Outcome(ordered, false, request.enableRerank() == null ? "本次请求未要求重排"
						: "重排未启用（ai.rag.kb.rerank.enabled=false）");

		List<KbHit> finalHits = new ArrayList<>();
		for (KbHit hit : outcome.hits()) {
			if (finalHits.size() >= wantRerank) {
				break;
			}
			finalHits.add(hit.withRank(finalHits.size() + 1));
		}
		long elapsed = System.currentTimeMillis() - startedAt;
		log.info("知识库检索：库={} 召回候选={} 命中={} 重排={} 耗时={}ms 提问「{}」", participants.size(),
				candidates.size(), finalHits.size(), outcome.applied(), elapsed, abbreviate(request.query()));
		return new Result(finalHits, indexSize, candidates.size(), outcome.applied(), outcome.skipReason(),
				List.copyOf(notes), elapsed);
	}

	/** 查一个库的粗召回（Milvus 侧只按向量相似度取 topK，不做停用过滤）。 */
	private List<Candidate> searchOne(KnowledgeBase kb, String query, int recallTopK, double threshold,
			List<String> notes) {
		VectorStore vectorStore = vectorStores.forKb(kb);
		// 集合本身就是这个库，所以不需要 filterExpression（多库隔离靠"一个库一个集合"，
		// 而不是靠 metadata 里的一列 —— 后者要有人保证每条都写对了那个值）
		SearchRequest searchRequest = SearchRequest.builder()
			.query(query)
			.topK(recallTopK)
			.similarityThreshold(threshold)
			.build();
		List<Document> documents = vectorStore.similaritySearch(searchRequest);
		List<Candidate> candidates = new ArrayList<>();
		for (Document document : documents) {
			if (document.getId() == null) {
				continue;
			}
			candidates.add(new Candidate(kb, document.getId(),
					document.getScore() == null ? 0.0 : document.getScore()));
		}
		return candidates;
	}

	/**
	 * 召回结果 → 业务命中：回表取正文，并按 {@code enabled} 丢掉停用的。
	 *
	 * <p>丢掉多少要记下来：粗召回窗口被停用切片占满时，人会看到"结果很少"，
	 * 而那条说明是唯一能把这件事说清楚的东西。
	 */
	private List<KbHit> toHits(List<Candidate> candidates, List<String> notes) {
		if (candidates.isEmpty()) {
			return List.of();
		}
		Set<String> uids = new HashSet<>();
		for (Candidate candidate : candidates) {
			uids.add(candidate.uid());
		}
		Map<String, KbChunk> chunksByUid = new HashMap<>();
		for (MapperRows.KbChunkRow row : chunkMapper.findByUids(new ArrayList<>(uids))) {
			KbChunk chunk = row.toDomain();
			chunksByUid.put(chunk.uid(), chunk);
		}
		// 文档的 enabled 与名字一次查完（不逐个查：那是 N+1）
		Set<Long> documentIds = new HashSet<>();
		for (KbChunk chunk : chunksByUid.values()) {
			documentIds.add(chunk.documentId());
		}
		Map<Long, KbDocumentStatusView> documents = new HashMap<>();
		for (MapperRows.KbDocumentRow row : documentMapper.findByIds(new ArrayList<>(documentIds))) {
			KnowledgeDocument document = row.toDomain();
			documents.put(document.id(), new KbDocumentStatusView(document.name(), document.enabled()));
		}

		List<KbHit> hits = new ArrayList<>();
		Map<Long, Integer> droppedByKb = new LinkedHashMap<>();
		Map<Long, Integer> missingByKb = new LinkedHashMap<>();
		for (Candidate candidate : candidates) {
			KbChunk chunk = chunksByUid.get(candidate.uid());
			if (chunk == null) {
				// Milvus 里有、清单里没有：两边不一致的那种情况，计数带上
				missingByKb.merge(candidate.kb().id(), 1, Integer::sum);
				continue;
			}
			KbDocumentStatusView document = documents.get(chunk.documentId());
			if (!chunk.enabled() || document == null || !document.enabled()) {
				droppedByKb.merge(candidate.kb().id(), 1, Integer::sum);
				continue;
			}
			hits.add(new KbHit(candidate.kb().id(), candidate.kb().name(), chunk.documentId(), document.name(),
					chunk.uid(), chunk.seq(), chunk.text(), chunk.headingPath(), chunk.tokenCount(),
					candidate.score(), null, 0));
		}
		for (Map.Entry<Long, Integer> entry : droppedByKb.entrySet()) {
			notes.add("知识库 #%d：粗召回里有 %d 条切片已停用，被丢掉"
				.formatted(entry.getKey(), entry.getValue()));
		}
		for (Map.Entry<Long, Integer> entry : missingByKb.entrySet()) {
			notes.add("知识库 #%d：有 %d 条向量在清单里找不到（两边不一致，可用一致性核对端点复查）"
				.formatted(entry.getKey(), entry.getValue()));
		}
		return hits;
	}

	private int enabledChunkCount(long kbId) {
		Long total = chunkMapper.countEnabledByKb(kbId);
		return total == null ? 0 : total.intValue();
	}

	private static int requestedTopK(Request request, List<KnowledgeBase> kbs) {
		if (request.finalTopK() != null) {
			return request.finalTopK();
		}
		return maxTopK(kbs);
	}

	private static int maxTopK(List<KnowledgeBase> kbs) {
		return kbs.stream().mapToInt(KnowledgeBase::topK).max().orElse(5);
	}

	private static double minThreshold(List<KnowledgeBase> kbs) {
		return kbs.stream().mapToDouble(KnowledgeBase::similarityThreshold).min().orElse(0.0);
	}

	private static boolean anyRerankEnabled(List<KnowledgeBase> kbs) {
		return kbs.stream().anyMatch(KnowledgeBase::rerankEnabled);
	}

	private static String abbreviate(String query) {
		String flat = query.replace('\n', ' ').strip();
		return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
	}

	/** Milvus 召回的一条：uid + 分数 + 它来自哪个库。 */
	private record Candidate(KnowledgeBase kb, String uid, double score) {
	}

	/** 文档侧的展示信息（名字 + 是否停用），一次查完之后按 id 取。 */
	private record KbDocumentStatusView(String name, boolean enabled) {
	}

	private static final class SearchThreadFactory implements ThreadFactory {

		private static final AtomicLong SEQ = new AtomicLong();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "kb-search-" + SEQ.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}

	}

}
