package ai.agent.service.rag.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import ai.agent.entity.rag.kb.KnowledgeBase;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.QueryResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按知识库构造 Milvus 集合与 {@link VectorStore}：<b>一个知识库一个集合</b>。
 *
 * <h2>为什么不是自动装配那一个 VectorStore</h2>
 * 自动装配只能给出一个集合（{@code spring.ai.vectorstore.milvus.collection-name}），
 * 而知识库之间必须隔离：不同库的维度可以不同（各自建库时的模型快照不同），
 * 合并到一个集合里就得靠 metadata 过滤维度不同的向量 —— 那是不可能的。
 * 复用的是自动装配给出的 {@link MilvusServiceClient}（连接只有一条）。
 *
 * <h2>★ {@code build()} 不会建集合，必须自己调一次 {@code afterPropertiesSet()}</h2>
 * 字节码核过：{@code MilvusVectorStore.Builder#build()} 只做
 * {@code new MilvusVectorStore(builder)}，建集合发生在 {@code afterPropertiesSet()} 里。
 * 自动装配那个 Bean 之所以能建出集合，是 Spring 按 {@code InitializingBean} 替它调的；
 * 这里是我们自己 new 的，所以必须显式调 —— 不调的话集合永远不存在，
 * 而报错出现在很久之后的第一次 {@code add()} 上（"collection not found"），
 * 离原因很远。{@link #createCollection} 建完立刻 {@code hasCollection} 复验一遍，就是这个原因。
 */
@Component
public class KbVectorStoreFactory {

	private static final Logger log = LoggerFactory.getLogger(KbVectorStoreFactory.class);

	private final MilvusServiceClient client;

	private final KbEmbeddingModelFactory embeddings;

	private final KnowledgeBaseProperties properties;

	/** 按集合名缓存：VectorStore 本身线程安全，每次检索都新建会反复打开索引。 */
	private final Map<String, MilvusVectorStore> cache = new ConcurrentHashMap<>();

	public KbVectorStoreFactory(MilvusServiceClient client, KbEmbeddingModelFactory embeddings, RagProperties rag) {
		this.client = client;
		this.embeddings = embeddings;
		// 取嵌套的那一棵（同 KbEmbeddingModelFactory 的接法）
		this.properties = rag.getKb();
	}

	/** 集合名 = 前缀 + 库 id（id 由自增主键生成，天然满足 Milvus 的集合名约束）。 */
	public String collectionNameOf(KnowledgeBase kb) {
		return properties.getCollectionPrefix() + kb.id();
	}

	/** 取这个库的向量库句柄（集合必须已经存在；不存在时由 {@link #createCollection} 负责建）。 */
	public VectorStore forKb(KnowledgeBase kb) {
		return cache.computeIfAbsent(collectionNameOf(kb), name -> build(kb, name));
	}

	/** 建库时调：建集合 + 立刻复验（见类注释）。 */
	public void createCollection(KnowledgeBase kb) {
		String name = collectionNameOf(kb);
		MilvusVectorStore store = build(kb, name);
		try {
			store.afterPropertiesSet();
		}
		catch (Exception e) {
			throw new KnowledgeBaseException("建 Milvus 集合失败（集合名 %s，维度 %d）：%s"
				.formatted(name, kb.embeddingDim(), e.getMessage()), e);
		}
		if (!collectionExists(name)) {
			throw new KnowledgeBaseException("Milvus 报建集合成功，但复查时集合 %s 不存在 —— "
					+ "这一次不能算建好了".formatted(name));
		}
		cache.put(name, store);
		log.info("知识库集合已建：{}（维度 {}，度量 {}，索引 {}）", name, kb.embeddingDim(),
				properties.getMetricType(), properties.getIndexType());
	}

	/** 集合在不在。 */
	public boolean collectionExists(String collectionName) {
		R<Boolean> response = client.hasCollection(
				HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
		if (response.getStatus() != 0 || response.getData() == null) {
			throw new KnowledgeBaseException("问 Milvus「集合 %s 在不在」失败：%s"
				.formatted(collectionName, response.getMessage()));
		}
		return response.getData();
	}

	/**
	 * 删集合。集合不存在时只记日志 —— 删知识库这条路上，
	 * "集合已经没了"是正常状态（上次删到一半退出），不该让删除第二次失败。
	 */
	public void dropCollection(String collectionName) {
		cache.remove(collectionName);
		R<?> response = client.dropCollection(
				DropCollectionParam.newBuilder().withCollectionName(collectionName).build());
		if (response.getStatus() != 0) {
			throw new KnowledgeBaseException("删 Milvus 集合 %s 失败：%s"
				.formatted(collectionName, response.getMessage()));
		}
	}

	/**
	 * Milvus 现在连得上吗。
	 *
	 * <p>客户端 Bean 是启动时建起来的（连不上就起不来），但"启动时连得上"与"现在连得上"
	 * 是两件事 —— 服务停掉之后每一次调用都会失败，而界面需要一个能分辨的说法。
	 * 所以这里做一次最轻的请求，失败就返回 false（不抛：这是一个探针）。
	 */
	public boolean reachable() {
		try {
			R<?> response = client.showCollections(ShowCollectionsParam.newBuilder().build());
			return response.getStatus() == 0;
		}
		catch (RuntimeException e) {
			log.warn("Milvus 探针失败：{}", e.getMessage());
			return false;
		}
	}

	/** 集合里的行数（一致性核对、以及"到底写进去没有"的判据）。 */
	public long countRows(String collectionName) {
		if (!collectionExists(collectionName)) {
			return -1;
		}
		QueryParam param = QueryParam.newBuilder()
			.withCollectionName(collectionName)
			.withExpr("")
			.withOutFields(List.of("count(*)"))
			// STRONG：刚写完就数，用默认的 Bounded 会读到过期视图，
			// 而"少了几条"会被当成写丢了的证据
			.withConsistencyLevel(ConsistencyLevelEnum.STRONG)
			.build();
		R<QueryResults> response = client.query(param);
		if (response.getStatus() != 0 || response.getData() == null) {
			throw new KnowledgeBaseException("数集合 %s 的行数失败：%s"
				.formatted(collectionName, response.getMessage()));
		}
		QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
		long total = 0;
		for (QueryResultsWrapper.RowRecord row : wrapper.getRowRecords()) {
			Object value = row.get("count(*)");
			if (value instanceof Number number) {
				total += number.longValue();
			}
		}
		return total;
	}

	private MilvusVectorStore build(KnowledgeBase kb, String collectionName) {
		return MilvusVectorStore.builder(client, embeddings.resolve(kb.embeddingProvider(), kb.embeddingModel(),
						kb.embeddingDim()))
			.collectionName(collectionName)
			.embeddingDimension(kb.embeddingDim())
			.metricType(MetricType.valueOf(properties.getMetricType().strip().toUpperCase()))
			.indexType(IndexType.valueOf(properties.getIndexType().strip().toUpperCase()))
			.indexParameters(indexParameters())
			// autoId=false：主键取我们给的 uid（切片清单里的那一列），两个库靠它对齐
			.autoId(false)
			.initializeSchema(true)
			.build();
	}

	/**
	 * 索引参数。
	 *
	 * <p>Spring AI 的默认值是 {@code {"nlist":1024}}，那是给 IVF 系列用的；
	 * 而本项目默认的 {@code AUTOINDEX} 与 {@code FLAT} 都不接受它，
	 * 所以这两种索引传空对象。
	 */
	private String indexParameters() {
		String type = properties.getIndexType().strip().toUpperCase();
		if ("AUTOINDEX".equals(type) || "FLAT".equals(type)) {
			return "{}";
		}
		return "{\"nlist\":1024}";
	}

}
