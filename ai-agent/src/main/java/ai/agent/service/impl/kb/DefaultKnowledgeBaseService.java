package ai.agent.service.impl.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import ai.agent.entity.rag.kb.KbChunkStrategy;
import ai.agent.entity.rag.kb.KnowledgeBase;
import ai.agent.mapper.KbChunkMapper;
import ai.agent.mapper.KnowledgeBaseMapper;
import ai.agent.mapper.KnowledgeDocumentMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.rag.kb.KbEmbeddingModelFactory;
import ai.agent.service.rag.kb.KbRerankService;
import ai.agent.service.rag.kb.KbVectorStoreFactory;
import ai.agent.service.rag.kb.KnowledgeBaseConflictException;
import ai.agent.service.rag.kb.KnowledgeBaseException;
import ai.agent.service.rag.kb.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库的默认实现。
 *
 * <h2>建库：先落行拿 id，再建集合，最后回写集合名</h2>
 * 集合名要用 id（见 {@code KbVectorStoreFactory#collectionNameOf}），而 id 只有插进去才知道，
 * 所以顺序只能是"行 → 集合 → 回写"。中间失败时把那一行删掉：
 * 留下有行没集合的状态，界面会显示得好好的，直到第一次上传才炸 —— 而那一刻
 * 报的是 Milvus 的 "collection not found"，离"建库时没建起来"很远。
 *
 * <h2>删库：先删集合，再删行</h2>
 * 反过来的话，中途崩掉会留下一个孤儿集合（它的名字只写在刚被删掉的那一行里，没人再找得到它）。
 * 按现在的顺序，中途崩掉留下的是"有行没集合"，而删除接口再点一次能接着走完。
 */
@Service
public class DefaultKnowledgeBaseService implements KnowledgeBaseService {

	private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeBaseService.class);

	private final KnowledgeBaseMapper mapper;

	private final KnowledgeDocumentMapper documentMapper;

	private final KbChunkMapper chunkMapper;

	private final KbVectorStoreFactory vectorStores;

	private final KbEmbeddingModelFactory embeddings;

	private final KnowledgeBaseProperties properties;

	private final KbRerankService rerankService;

	public DefaultKnowledgeBaseService(KnowledgeBaseMapper mapper, KnowledgeDocumentMapper documentMapper,
			KbChunkMapper chunkMapper, KbVectorStoreFactory vectorStores, KbEmbeddingModelFactory embeddings,
			KbRerankService rerankService, RagProperties rag) {
		this.mapper = mapper;
		this.documentMapper = documentMapper;
		this.chunkMapper = chunkMapper;
		this.vectorStores = vectorStores;
		this.embeddings = embeddings;
		this.rerankService = rerankService;
		// 取嵌套的那一棵（同 KbEmbeddingModelFactory 的接法）
		this.properties = rag.getKb();
	}

	@Override
	public List<KnowledgeBase> list() {
		return mapper.list().stream().map(MapperRows.KnowledgeBaseRow::toDomain).toList();
	}

	@Override
	public KnowledgeBase get(long id) {
		MapperRows.KnowledgeBaseRow row = mapper.findById(id);
		if (row == null) {
			throw new KnowledgeBaseException("知识库不存在：#" + id);
		}
		return row.toDomain();
	}

	@Override
	public List<KnowledgeBase> findByIds(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return mapper.findByIds(ids).stream().map(MapperRows.KnowledgeBaseRow::toDomain).toList();
	}

	@Override
	@Transactional
	public KnowledgeBase create(KnowledgeBase.Draft draft) {
		// 【先拦"算了向量也没用"的情况】没配 embedding 时建库能建成功、上传才失败，
		// 而那时人已经在界面上花了功夫填名字、传文件 —— 越早报错越好
		String reason = embeddings.unavailableReason();
		if (reason != null) {
			throw new KnowledgeBaseException("现在不能建知识库：知识库的 embedding 不可用（" + reason
					+ "）。设置环境变量 DASHSCOPE_API_KEY 后重启即可");
		}
		if (mapper.findByName(draft.name()) != null) {
			throw new KnowledgeBaseConflictException("知识库名称已被占用：" + draft.name()
					+ "。名字要唯一 —— 否则界面上按名字找人时，「我删的是哪一个」没有答案");
		}

		// 入参留空时用配置里的当前值：默认值只在这一处解析（控制器不重复一遍）
		String provider = draft.embeddingProvider() == null || draft.embeddingProvider().isBlank()
				? properties.getEmbedding().getProvider() : draft.embeddingProvider().strip();
		String model = draft.embeddingModel() == null || draft.embeddingModel().isBlank()
				? properties.getEmbedding().getModel() : draft.embeddingModel().strip();
		int dim = draft.embeddingDim() == null ? properties.getEmbedding().getDimensions() : draft.embeddingDim();
		int topK = draft.topK() == null ? properties.getSearch().getTopK() : draft.topK();
		double threshold = draft.similarityThreshold() == null ? properties.getSearch().getSimilarityThreshold()
				: draft.similarityThreshold();
		boolean rerank = draft.rerankEnabled() != null ? draft.rerankEnabled()
				: properties.getRerank().isEnabled();

		mapper.insert(draft.name(), draft.description(), provider, model, dim, draft.chunkStrategy().value(), topK,
				threshold, rerank);
		Long id = mapper.lastInsertId();
		if (id == null || id == 0L) {
			throw new IllegalStateException("建知识库后取不到自增 id（LAST_INSERT_ID() 返回 "
					+ id + "）—— 集合名要用它拼，没有它这次建库不能继续");
		}

		mapper.updateCollectionName(id, properties.getCollectionPrefix() + id);
		KnowledgeBase created = get(id);

		try {
			vectorStores.createCollection(created);
		}
		catch (RuntimeException e) {
			// 回滚那一行：宁可"没建成"，也不要"看着建成了、第一次上传才炸"
			mapper.delete(id);
			throw e;
		}
		log.info("知识库已建：id={} name={} 集合={} 维度={} 策略={}", created.id(), created.name(),
				created.collectionName(), created.embeddingDim(), created.chunkStrategy().value());
		return created;
	}

	@Override
	@Transactional
	public KnowledgeBase update(long id, String name, String description, int topK, double similarityThreshold,
			boolean rerankEnabled) {
		get(id);
		// 查重时把自己排除掉：只改说明、不动名字时，findByName 找到的就是自己
		MapperRows.KnowledgeBaseRow sameName = mapper.findByName(name);
		if (sameName != null && !sameName.id().equals(id)) {
			throw new KnowledgeBaseConflictException("知识库名称已被占用：" + name);
		}
		mapper.updateNameDescription(id, name, description);
		mapper.updateSearch(id, topK, similarityThreshold, rerankEnabled);
		return get(id);
	}

	@Override
	public KnowledgeBase setEnabled(long id, boolean enabled) {
		get(id);
		mapper.updateEnabled(id, enabled);
		return get(id);
	}

	@Override
	@Transactional
	public void delete(long id) {
		KnowledgeBase kb = get(id);
		// 集合可能已经不在（上次删到一半），所以这一步是幂等的，不因为"集合没了"失败
		vectorStores.dropCollection(kb.collectionName());
		chunkMapper.deleteByKb(id);
		for (Long documentId : documentMapper.idsOfKb(id)) {
			documentMapper.delete(documentId);
		}
		mapper.delete(id);
		log.info("知识库已删：id={} name={} 集合={}", id, kb.name(), kb.collectionName());
	}

	@Override
	public long documentCount(long kbId) {
		Long total = documentMapper.countByKb(kbId);
		return total == null ? 0L : total;
	}

	@Override
	public long chunkCount(long kbId) {
		Long total = chunkMapper.countByKb(kbId);
		return total == null ? 0L : total;
	}

	@Override
	public Capabilities capabilities() {
		String reason = embeddings.unavailableReason();
		String rerankReason = rerankService.unavailableReason();
		return new Capabilities(reason == null, reason, properties.getEmbedding().getProvider(),
				properties.getEmbedding().getModel(), properties.getEmbedding().getDimensions(),
				vectorStores.reachable(), rerankReason == null, rerankReason,
				properties.getRerank().getModel());
	}

	/** 建库入参里那三个"取值必须认得出来"的字段的解析（控制器与批处理都走这里，判据只有一份）。 */
	public static KbChunkStrategy strategy(String raw) {
		try {
			return KbChunkStrategy.parse(raw);
		}
		catch (IllegalArgumentException e) {
			throw new KnowledgeBaseException(e.getMessage());
		}
	}

}
