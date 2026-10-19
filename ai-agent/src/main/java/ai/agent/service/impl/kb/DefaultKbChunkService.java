package ai.agent.service.impl.kb;

import ai.agent.entity.rag.kb.KbChunk;
import ai.agent.entity.rag.kb.KnowledgeBase;
import ai.agent.entity.rag.kb.KnowledgeDocument;
import ai.agent.mapper.KbChunkMapper;
import ai.agent.mapper.KnowledgeBaseMapper;
import ai.agent.mapper.KnowledgeDocumentMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.rag.kb.KbChunkService;
import ai.agent.service.rag.kb.KbVectorStoreFactory;
import ai.agent.service.rag.kb.KnowledgeBaseException;
import ai.agent.service.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 切片的默认实现。
 *
 * <h2>为什么改正文要"先删后插"而不是"更新"</h2>
 * Milvus 的 SDK 只有 {@code insert} 与 {@code upsert}，两者都是**整行写**（没有局部更新），
 * 而 Spring AI 的 {@code VectorStore} 只暴露 {@code add/delete/search}。
 * 所以替换一行 = 删掉旧 uid 那一行 + 用同一个 uid 插一行新的：
 * uid 不变，于是清单与向量仍然指同一件事。
 *
 * <h2>块数（{@code kb_document.chunk_count}）跟着删减</h2>
 * 它是"上次索引切出几块"的记录，人为删掉几块之后不再等于清单行数 ——
 * 所以这里同步减一，让它与清单一致；界面上"文档说 12 块、清单里 11 条"这种不一致
 * 没有任何东西会报错，只能靠人看出来。
 */
@Service
public class DefaultKbChunkService implements KbChunkService {

	private static final Logger log = LoggerFactory.getLogger(DefaultKbChunkService.class);

	private final KbChunkMapper chunkMapper;

	private final KnowledgeDocumentMapper documentMapper;

	private final KnowledgeBaseMapper knowledgeBaseMapper;

	private final KbVectorStoreFactory vectorStores;

	private final TokenEstimator estimator;

	public DefaultKbChunkService(KbChunkMapper chunkMapper, KnowledgeDocumentMapper documentMapper,
			KnowledgeBaseMapper knowledgeBaseMapper, KbVectorStoreFactory vectorStores, TokenEstimator estimator) {
		this.chunkMapper = chunkMapper;
		this.documentMapper = documentMapper;
		this.knowledgeBaseMapper = knowledgeBaseMapper;
		this.vectorStores = vectorStores;
		this.estimator = estimator;
	}

	@Override
	public Paged list(long documentId, int offset, int limit) {
		requireDocument(documentId);
		int safeOffset = Math.max(0, offset);
		int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
		List<KbChunk> chunks = chunkMapper.listByDocument(documentId, safeOffset, safeLimit).stream()
			.map(MapperRows.KbChunkRow::toDomain)
			.toList();
		Long total = chunkMapper.countByDocument(documentId);
		return new Paged(chunks, total == null ? 0L : total, safeOffset, safeLimit);
	}

	@Override
	@Transactional
	public KbChunk updateText(String uid, String text) {
		KbChunk current = requireChunk(uid);
		String content = text == null ? "" : text.strip();
		if (content.isEmpty()) {
			throw new KnowledgeBaseException("切片正文不能改成空 —— 空块在检索里没有任何意义，"
					+ "要让它不参与检索请用「停用」");
		}
		KnowledgeDocument document = requireDocument(current.documentId());
		KnowledgeBase kb = requireKnowledgeBase(document.kbId());
		int tokens = estimator.estimate(content);
		int chars = content.length();

		chunkMapper.updateText(uid, content, tokens, chars);
		KbChunk updated = chunkMapper.findByUid(uid).toDomain();
		replaceVector(vectorStores.forKb(kb), updated, document);
		log.info("切片正文已改并重新向量化：uid={} 文档={} 字数={} token={}", uid, document.id(), chars, tokens);
		return updated;
	}

	@Override
	public KbChunk setEnabled(String uid, boolean enabled) {
		requireChunk(uid);
		chunkMapper.updateEnabled(uid, enabled);
		// Milvus 侧不动：检索时按这一列过滤（理由见 KbChunkService 的类注释）
		return chunkMapper.findByUid(uid).toDomain();
	}

	@Override
	@Transactional
	public void delete(String uid) {
		KbChunk chunk = requireChunk(uid);
		KnowledgeDocument document = requireDocument(chunk.documentId());
		KnowledgeBase kb = requireKnowledgeBase(document.kbId());
		vectorStores.forKb(kb).delete(List.of(uid));
		chunkMapper.deleteByUid(uid);
		documentMapper.decrementChunkCount(document.id());
		log.info("切片已删：uid={} 文档={} 剩余={} 块", uid, document.id(),
				chunkMapper.countByDocument(document.id()));
	}

	/** 用同一个 uid 替换 Milvus 里那一行（先删后插，理由见类注释）。 */
	private void replaceVector(VectorStore vectorStore, KbChunk chunk, KnowledgeDocument document) {
		vectorStore.delete(List.of(chunk.uid()));
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("doc_id", String.valueOf(document.id()));
		metadata.put("doc_name", document.name());
		vectorStore.add(List.of(Document.builder()
			.id(chunk.uid())
			.text(chunk.embeddingText())
			.metadata(metadata)
			.build()));
	}

	private KbChunk requireChunk(String uid) {
		if (uid == null || uid.isBlank()) {
			throw new KnowledgeBaseException("缺少切片 uid");
		}
		MapperRows.KbChunkRow row = chunkMapper.findByUid(uid);
		if (row == null) {
			throw new KnowledgeBaseException("切片不存在：" + uid
					+ " —— 它可能已经被「重新索引」替换掉了（重新索引会给这份文档换一批新切片）");
		}
		return row.toDomain();
	}

	private KnowledgeDocument requireDocument(long documentId) {
		MapperRows.KbDocumentRow row = documentMapper.findById(documentId);
		if (row == null) {
			throw new KnowledgeBaseException("文档不存在：#" + documentId);
		}
		return row.toDomain();
	}

	private KnowledgeBase requireKnowledgeBase(long kbId) {
		MapperRows.KnowledgeBaseRow row = knowledgeBaseMapper.findById(kbId);
		if (row == null) {
			throw new KnowledgeBaseException("知识库不存在：#" + kbId);
		}
		return row.toDomain();
	}

}
