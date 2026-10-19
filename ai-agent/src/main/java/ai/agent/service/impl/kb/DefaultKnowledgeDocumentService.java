package ai.agent.service.impl.kb;

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
import ai.agent.service.rag.chunk.DocumentChunker;
import ai.agent.service.rag.kb.KbFileStorage;
import ai.agent.service.rag.kb.KbIndexingService;
import ai.agent.service.rag.kb.KbVectorStoreFactory;
import ai.agent.service.rag.kb.KnowledgeBaseConflictException;
import ai.agent.service.rag.kb.KnowledgeBaseException;
import ai.agent.service.rag.kb.KnowledgeDocumentService;
import ai.agent.service.rag.parse.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档的默认实现。
 *
 * <h2>上传：落盘 → 落行 → 排队</h2>
 * 三件事的顺序不能换：先落盘再落行（落行成功而文件没写进去的话，索引任务会因为"文件不存在"
 * 失败，而那句失败指向的是磁盘而不是上传这一步）；先落行再排队（排队在前、落行在后的窗口里，
 * 索引线程会找不到这条记录）。
 *
 * <h2>删除与重新索引都先清向量，再清清单，最后清文档行</h2>
 * 与建库删库同一条顺序理由：向量是"能被搜到的方式"，清单是"应该有哪些块"的描述。
 * 先删向量，中途崩掉留下的是"清单还在、搜不到"（再点一次删除能接着走完）；
 * 反过来则留下"清单没了、向量还在"，而那些向量已经没有任何东西能指出它们属于谁。
 */
@Service
public class DefaultKnowledgeDocumentService implements KnowledgeDocumentService {

	private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeDocumentService.class);

	private final KnowledgeDocumentMapper documentMapper;

	private final KnowledgeBaseMapper knowledgeBaseMapper;

	private final KbChunkMapper chunkMapper;

	private final KbVectorStoreFactory vectorStores;

	private final KbFileStorage storage;

	private final KbIndexingService indexing;

	private final DocumentParser parser;

	private final FixedSizeChunker fixedSizeChunker;

	private final StructuralChunker structuralChunker;

	private final Set<String> allowedExtensions;

	public DefaultKnowledgeDocumentService(KnowledgeDocumentMapper documentMapper,
			KnowledgeBaseMapper knowledgeBaseMapper, KbChunkMapper chunkMapper, KbVectorStoreFactory vectorStores,
			KbFileStorage storage, KbIndexingService indexing, DocumentParser parser, FixedSizeChunker fixedSizeChunker,
			StructuralChunker structuralChunker, RagProperties rag) {
		this.documentMapper = documentMapper;
		this.knowledgeBaseMapper = knowledgeBaseMapper;
		this.chunkMapper = chunkMapper;
		this.vectorStores = vectorStores;
		this.storage = storage;
		this.indexing = indexing;
		this.parser = parser;
		this.fixedSizeChunker = fixedSizeChunker;
		this.structuralChunker = structuralChunker;
		// 能用哪些格式只有一处判据：ai.rag.corpus.extensions（离线语料用的是同一份清单）
		this.allowedExtensions = rag.getCorpus().getExtensions().stream()
			.map(extension -> extension.toLowerCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
	}

	@Override
	@Transactional
	public KnowledgeDocument upload(long kbId, String originalName, byte[] content) {
		KnowledgeBase kb = knowledgeBase(kbId);
		if (!kb.enabled()) {
			throw new KnowledgeBaseException("知识库「%s」已停用 —— 先启用再传文档"
				.formatted(kb.name()));
		}
		String name = requireName(originalName);
		String extension = extensionOf(name);
		if (!allowedExtensions.contains(extension)) {
			throw new KnowledgeBaseException("不支持的格式「.%s」（文件 %s）。支持的格式：%s"
				.formatted(extension, name, String.join("、", allowedExtensions)));
		}
		if (content == null || content.length == 0) {
			throw new KnowledgeBaseException("文件是空的：" + name);
		}

		// 先落一行拿到 id：落盘名要用它（见 KbFileStorage 的类注释）
		documentMapper.insert(kbId, name, extension, content.length, "");
		Long documentId = documentMapper.lastInsertId();
		if (documentId == null || documentId == 0L) {
			throw new IllegalStateException("传文档后取不到自增 id（LAST_INSERT_ID() 返回 " + documentId
					+ "）—— 落盘名要用它，这次上传不能继续");
		}
		String path = storage.store(kbId, documentId, extension, content);
		documentMapper.updateSourcePath(documentId, path);
		// 提交之后再入队：索引线程在另一条连接上，读不到还没提交的这一行（见 KbIndexingService）
		indexing.submitAfterCommit(documentId);

		KnowledgeDocument document = get(documentId);
		log.info("文档已上传并入队：kb={} id={} 名称={} {} 字节", kbId, documentId, name, content.length);
		return document;
	}

	@Override
	public List<KnowledgeDocument> list(long kbId) {
		knowledgeBase(kbId);
		return documentMapper.listByKb(kbId).stream().map(MapperRows.KbDocumentRow::toDomain).toList();
	}

	@Override
	public KnowledgeDocument get(long documentId) {
		MapperRows.KbDocumentRow row = documentMapper.findById(documentId);
		if (row == null) {
			throw new KnowledgeBaseException("文档不存在：#" + documentId);
		}
		return row.toDomain();
	}

	@Override
	public List<StatusCount> countsByStatus(long kbId) {
		List<StatusCount> counts = new ArrayList<>();
		List<MapperRows.KbDocumentCountRow> rows = documentMapper.countByStatus(kbId);
		for (KbDocumentStatus status : KbDocumentStatus.values()) {
			long total = rows.stream()
				.filter(row -> status.name().equalsIgnoreCase(row.status()))
				.mapToLong(row -> row.total() == null ? 0L : row.total())
				.sum();
			counts.add(new StatusCount(status.name(), status.label(), total));
		}
		return counts;
	}

	@Override
	public long chunkCountOf(long documentId) {
		Long total = chunkMapper.countByDocument(documentId);
		return total == null ? 0L : total;
	}

	@Override
	public KnowledgeDocument setEnabled(long documentId, boolean enabled) {
		KnowledgeDocument document = get(documentId);
		documentMapper.updateEnabled(documentId, enabled);
		// 切片行不动：检索侧按 doc_id 过滤这一列（切片行里的 enabled 管的是"这一块"，
		// 两者是两件事 —— 把文档级开关铺到几十上百行上，既慢又会在中途失败时留下半开半关的状态）
		log.info("文档启停：id={} enabled={}（切片行不变，检索侧按文档过滤）", documentId, enabled);
		return get(document.id());
	}

	@Override
	public KnowledgeDocument rename(long documentId, String name) {
		get(documentId);
		documentMapper.updateName(documentId, requireName(name));
		return get(documentId);
	}

	@Override
	@Transactional
	public void delete(long documentId) {
		KnowledgeDocument document = get(documentId);
		clearChunks(document);
		documentMapper.delete(documentId);
		// 原件最后删：记录留着而文件没了，是一次"重新索引会失败"的处境；
		// 文件留着而记录没了没人发现（占着磁盘而已）。选后者。
		storage.delete(document.path());
		log.info("文档已删：id={} 名称={} kb={}", documentId, document.name(), document.kbId());
	}

	@Override
	@Transactional
	public KnowledgeDocument reIndex(long documentId) {
		KnowledgeDocument document = get(documentId);
		if (document.status() == KbDocumentStatus.PROCESSING) {
			throw new KnowledgeBaseConflictException("文档「%s」正在索引中，现在不能重新索引 —— "
					+ "等它跑完（或失败）再点".formatted(document.name()));
		}
		clearChunks(document);
		documentMapper.markUploaded(documentId);
		// 同样要等提交：否则索引线程读到的是"清掉切片之前"的那一行
		indexing.submitAfterCommit(documentId);
		log.info("文档重新索引已入队：id={} 名称={}", documentId, document.name());
		return get(documentId);
	}

	@Override
	public List<KbChunk> previewChunks(long documentId) {
		KnowledgeDocument document = get(documentId);
		KnowledgeBase kb = knowledgeBase(document.kbId());
		byte[] content = storage.read(document.path());
		ParsedDocument parsed = parser.parse(String.valueOf(document.id()), content, document.name());
		DocumentChunker chunker = kb.chunkStrategy() == KbChunkStrategy.LENGTH ? fixedSizeChunker : structuralChunker;
		List<Chunk> chunks = chunker.chunk(parsed);
		List<KbChunk> preview = new ArrayList<>(chunks.size());
		for (Chunk chunk : chunks) {
			// uid 留空：这些块还不存在（没有写库、没有算向量），编一个 uid 会让人以为它能被引用
			preview.add(new KbChunk(null, null, kb.id(), document.id(), chunk.index(), chunk.text(),
					KbChunk.clampHeading(chunk.breadcrumb()), chunk.tokenCount(), chunk.charCount(), true, null, null));
		}
		return preview;
	}

	/** 清掉一份文档的全部切片：先向量、后清单（顺序理由见类注释）。 */
	private void clearChunks(KnowledgeDocument document) {
		KnowledgeBase kb = knowledgeBase(document.kbId());
		VectorStore vectorStore = vectorStores.forKb(kb);
		var expression = new FilterExpressionBuilder().eq("doc_id", String.valueOf(document.id())).build();
		vectorStore.delete(expression);
		chunkMapper.deleteByDocument(document.id());
	}

	private KnowledgeBase knowledgeBase(long kbId) {
		MapperRows.KnowledgeBaseRow row = knowledgeBaseMapper.findById(kbId);
		if (row == null) {
			throw new KnowledgeBaseException("知识库不存在：#" + kbId);
		}
		return row.toDomain();
	}

	private static String requireName(String name) {
		String stripped = name == null ? null : name.strip();
		if (stripped == null || stripped.isEmpty()) {
			throw new KnowledgeBaseException("文件名不能为空");
		}
		if (stripped.length() > KnowledgeDocument.MAX_NAME_CHARS) {
			throw new KnowledgeBaseException("文件名过长（%d 字符 > 上限 %d）"
				.formatted(stripped.length(), KnowledgeDocument.MAX_NAME_CHARS));
		}
		return stripped;
	}

	/** 取扩展名（小写、不含点）。没有扩展名时返回空串，由调用方给出可读的拒绝理由。 */
	private static String extensionOf(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return "";
		}
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

}
