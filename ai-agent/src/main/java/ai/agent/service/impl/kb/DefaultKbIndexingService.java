package ai.agent.service.impl.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.entity.rag.kb.KbChunk;
import ai.agent.entity.rag.kb.KbChunkStrategy;
import ai.agent.entity.rag.kb.KbDocumentStatus;
import ai.agent.entity.rag.kb.KnowledgeBase;
import ai.agent.entity.rag.kb.KnowledgeDocument;
import ai.agent.entity.rag.parse.ParsedDocument;
import ai.agent.mapper.KbChunkMapper;
import ai.agent.mapper.KnowledgeBaseMapper;
import ai.agent.mapper.KnowledgeDocumentMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.impl.rag.chunk.FixedSizeChunker;
import ai.agent.service.impl.rag.chunk.StructuralChunker;
import ai.agent.service.rag.kb.KbEmbeddingModelFactory;
import ai.agent.service.rag.kb.KbFileStorage;
import ai.agent.service.rag.kb.KbIndexingService;
import ai.agent.service.rag.kb.KbVectorStoreFactory;
import ai.agent.service.rag.chunk.DocumentChunker;
import ai.agent.service.rag.parse.DocumentParser;
import jakarta.annotation.PreDestroy;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 索引流水线的默认实现。
 *
 * <h2>顺序：先清旧的，再写清单，最后写向量</h2>
 * <ol>
 *   <li><b>清旧的</b>：Spring AI 的 {@code MilvusVectorStore} 写的是 {@code insert} 而不是
 *       {@code upsert}（字节码核过），同一份文档再索引一次会**插入重复行** ——
 *       重复行不会报错，只会让同一段内容在召回里占两个位置、把别的块挤出去。
 *       所以"重新索引"与"改切片正文"都必须先删后插；</li>
 *   <li><b>写清单</b>（{@code kb_chunk}）：它是"这份文档应该有哪些块"的唯一描述，
 *       向量是它能被搜到的方式。先写它，然后再去算向量 —— 反过来的话，
 *       向量算完但清单写失败时，Milvus 里会留下谁都指不出来的行；</li>
 *   <li><b>写向量</b>：按 {@code ai.rag.kb.embedding.batch-size} 切批，
 *       一次调用算一批的向量。</li>
 * </ol>
 * 失败时状态落到 {@code FAILED} 并把**是哪一步**写进原因：解析失败与向量化失败
 * 的处置完全不同（前者换文档，后者多半是 key/网络），而只写一句"索引失败"两者分不开。
 */
@Service
public class DefaultKbIndexingService implements KbIndexingService {

	private static final Logger log = LoggerFactory.getLogger(DefaultKbIndexingService.class);

	private final KnowledgeDocumentMapper documentMapper;

	private final KnowledgeBaseMapper knowledgeBaseMapper;

	private final KbChunkMapper chunkMapper;

	private final KbVectorStoreFactory vectorStores;

	private final KbEmbeddingModelFactory embeddings;

	private final KbFileStorage storage;

	private final DocumentParser parser;

	private final FixedSizeChunker fixedSizeChunker;

	private final StructuralChunker structuralChunker;

	private final SqlSessionFactory sqlSessionFactory;

	private final KnowledgeBaseProperties properties;

	private final ThreadPoolExecutor executor;

	public DefaultKbIndexingService(KnowledgeDocumentMapper documentMapper, KnowledgeBaseMapper knowledgeBaseMapper,
			KbChunkMapper chunkMapper, KbVectorStoreFactory vectorStores, KbEmbeddingModelFactory embeddings,
			KbFileStorage storage, DocumentParser parser, FixedSizeChunker fixedSizeChunker,
			StructuralChunker structuralChunker, SqlSessionFactory sqlSessionFactory, RagProperties rag) {
		this.documentMapper = documentMapper;
		this.knowledgeBaseMapper = knowledgeBaseMapper;
		this.chunkMapper = chunkMapper;
		this.vectorStores = vectorStores;
		this.embeddings = embeddings;
		this.storage = storage;
		this.parser = parser;
		this.fixedSizeChunker = fixedSizeChunker;
		this.structuralChunker = structuralChunker;
		this.sqlSessionFactory = sqlSessionFactory;
		this.properties = rag.getKb();
		int poolSize = properties.getIndexing().getPoolSize();
		this.executor = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(properties.getIndexing().getQueueCapacity()),
				new NamedThreadFactory("kb-index-"), new ThreadPoolExecutor.AbortPolicy());
		log.info("知识库索引队列已装配：线程 {}、队列 {}", poolSize, properties.getIndexing().getQueueCapacity());
	}

	@PreDestroy
	public void shutdown() {
		// 直接 shutdownNow：正在跑的解析会被中断，而中断留下的 PROCESSING 由下一次启动清扫处理
		executor.shutdownNow();
	}

	@Override
	public void submit(long documentId) {
		try {
			executor.execute(() -> process(documentId));
		}
		catch (RejectedExecutionException e) {
			// 队列满：不阻塞、不丢件，而是把这份文档标成失败并给出下一步
			documentMapper.markFailed(documentId,
					"索引队列已满（容量 %d，当前 %d 个排队）—— 文件已保存，点「重新索引」即可重来"
						.formatted(properties.getIndexing().getQueueCapacity(), executor.getQueue().size()));
			log.warn("索引队列已满，文档 #{} 标记为失败", documentId);
		}
	}

	@Override
	public void submitAfterCommit(long documentId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			submit(documentId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				submit(documentId);
			}
		});
	}

	@Override
	public void recoverOnStartup() {
		List<MapperRows.KbDocumentRow> unfinished = documentMapper.listUnfinished();
		int requeued = 0;
		int failed = 0;
		for (MapperRows.KbDocumentRow row : unfinished) {
			KnowledgeDocument document = row.toDomain();
			if (document.status() == KbDocumentStatus.UPLOADED) {
				submit(document.id());
				requeued++;
			}
			else {
				documentMapper.markFailed(document.id(),
						"进程在索引过程中退出（重启后发现它停在处理中）—— 点「重新索引」重来");
				failed++;
			}
		}
		if (requeued > 0 || failed > 0) {
			log.info("启动索引恢复：{} 份重新入队，{} 份判为失败（原先停在处理中）", requeued, failed);
		}
	}

	/**
	 * 跑一份文档的索引。
	 *
	 * <p>包内可见是为了让"重新索引"能同步跑一次（写测试与排查时用），
	 * 常规路径一律走 {@link #submit(long)}。
	 */
	void process(long documentId) {
		MapperRows.KbDocumentRow row = documentMapper.findById(documentId);
		if (row == null) {
			log.warn("索引任务到期，但文档 #{} 已经不在了 —— 跳过", documentId);
			return;
		}
		KnowledgeDocument document = row.toDomain();
		if (!document.status().canMoveTo(KbDocumentStatus.PROCESSING)) {
			// 已经有别的任务在跑（重复点"重新索引"、或恢复流程与手动入队撞上）
			log.warn("文档 #{} 当前状态是 {}，不接受新的索引任务 —— 跳过这次", documentId, document.status());
			return;
		}

		long startedAt = System.currentTimeMillis();
		String step = "开始";
		try {
			documentMapper.markProcessing(documentId);

			KnowledgeBase kb = knowledgeBaseOf(document);
			VectorStore vectorStore = vectorStores.forKb(kb);

			step = "清理旧切片";
			clearPrevious(vectorStore, document);

			step = "读原件";
			byte[] content = storage.read(document.path());

			step = "解析";
			ParsedDocument parsed = parser.parse(String.valueOf(document.id()), content, document.name());

			step = "分块";
			List<Chunk> chunks = chunkerFor(kb).chunk(parsed);
			int max = properties.getIndexing().getMaxChunksPerDocument();
			if (chunks.size() > max) {
				throw new IllegalStateException("切出 %d 块，超过上限 %d（ai.rag.kb.indexing.max-chunks-per-document）"
						.formatted(chunks.size(), max));
			}

			step = "写清单";
			List<KbChunk> manifest = writeManifest(document, kb, chunks);

			step = "向量化";
			embedAndStore(vectorStore, document, manifest);

			int tokens = manifest.stream().mapToInt(KbChunk::tokenCount).sum();
			documentMapper.markProcessed(documentId, manifest.size(), tokens);
			if (manifest.isEmpty()) {
				log.warn("文档 #{}（{}）索引成功，但一个块都没切出来 —— 可能是扫描件或空文件",
						documentId, document.name());
			}
			log.info("文档索引完成：id={} 名称={} 块数={} token={} 耗时={}ms", documentId, document.name(),
					manifest.size(), tokens, System.currentTimeMillis() - startedAt);
		}
		catch (Exception e) {
			String reason = "第「%s」步失败：%s".formatted(step, e.getMessage());
			documentMapper.markFailed(documentId, KnowledgeDocument.clampError(reason));
			log.warn("文档索引失败：id={} 名称={} 步={} 耗时={}ms", documentId, document.name(), step,
					System.currentTimeMillis() - startedAt, e);
		}
	}

	private KnowledgeBase knowledgeBaseOf(KnowledgeDocument document) {
		MapperRows.KnowledgeBaseRow kbRow = knowledgeBaseMapper.findById(document.kbId());
		if (kbRow == null) {
			throw new IllegalStateException("文档 #%d 挂在一个不存在的知识库 #%d 上"
				.formatted(document.id(), document.kbId()));
		}
		return kbRow.toDomain();
	}

	/**
	 * 清掉上一次的切片（向量 + 清单）。
	 *
	 * <p>向量按 metadata 里的 {@code doc_id} 整批删 —— 这正是把 doc_id 写进 metadata 的唯一原因
	 * （集合里除了它只有向量与正文，检索回来的是 uid，而"这一行属于哪份文档"只能从 metadata 读）。
	 */
	private void clearPrevious(VectorStore vectorStore, KnowledgeDocument document) {
		var expression = new FilterExpressionBuilder().eq("doc_id", String.valueOf(document.id())).build();
		vectorStore.delete(expression);
		chunkMapper.deleteByDocument(document.id());
	}

	private DocumentChunker chunkerFor(KnowledgeBase kb) {
		return kb.chunkStrategy() == KbChunkStrategy.LENGTH ? fixedSizeChunker : structuralChunker;
	}

	/** 把分块结果写成清单行，返回带 uid 的那一份（向量化要用同一批对象）。 */
	private List<KbChunk> writeManifest(KnowledgeDocument document, KnowledgeBase kb, List<Chunk> chunks) {
		List<KbChunk> manifest = new ArrayList<>(chunks.size());
		for (Chunk chunk : chunks) {
			String heading = KbChunk.clampHeading(chunk.breadcrumb());
			manifest.add(new KbChunk(null, newChunkUid(), kb.id(), document.id(), chunk.index(), chunk.text(),
					heading, chunk.tokenCount(), chunk.charCount(), true, null, null));
		}
		if (manifest.isEmpty()) {
			return manifest;
		}
		// 批处理会话：与本方法所在的事务同步，出异常时一起回滚（同 JdbcAgentRunStore 的做法）
		try (SqlSession batch = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
			KbChunkMapper batchMapper = batch.getMapper(KbChunkMapper.class);
			for (KbChunk chunk : manifest) {
				batchMapper.insertChunk(chunk.uid(), chunk.kbId(), chunk.documentId(), chunk.seq(), chunk.text(),
						chunk.headingPath(), chunk.tokenCount(), chunk.charCount());
			}
			batch.flushStatements();
		}
		return manifest;
	}

	private void embedAndStore(VectorStore vectorStore, KnowledgeDocument document, List<KbChunk> manifest) {
		if (manifest.isEmpty()) {
			return;
		}
		int batchSize = properties.getEmbedding().getBatchSize();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("doc_id", String.valueOf(document.id()));
		metadata.put("doc_name", document.name());
		for (int start = 0; start < manifest.size(); start += batchSize) {
			List<Document> batch = new ArrayList<>(batchSize);
			for (KbChunk chunk : manifest.subList(start, Math.min(start + batchSize, manifest.size()))) {
				batch.add(Document.builder()
					.id(chunk.uid())
					.text(chunk.embeddingText())
					.metadata(new LinkedHashMap<>(metadata))
					.build());
			}
			vectorStore.add(batch);
		}
	}

	/**
	 * 切片 uid：36 字符的 UUID。
	 *
	 * <p>长度是有约束的 —— Milvus 集合里主键那一列是 {@code VarChar(36)}
	 * （Spring AI 建集合时写死的 {@code max_length}），超长会在写入时报错。
	 * 所以这里不用带前缀的写法。
	 */
	private static String newChunkUid() {
		return UUID.randomUUID().toString();
	}

	/** 线程名带前缀，方便在日志里把索引线程与请求线程分开。 */
	private static final class NamedThreadFactory implements ThreadFactory {

		private static final AtomicLong SEQ = new AtomicLong();

		private final String prefix;

		private NamedThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, prefix + SEQ.incrementAndGet());
			// 守护线程：应用退出时不因为它而挂住（正在跑的解析会由 shutdownNow 中断）
			thread.setDaemon(true);
			return thread;
		}

	}

}
