package ai.agent.service.rag.vector;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.service.impl.rag.vector.HashingEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量索引：<b>用第一性原理实现"向量检索到底在做什么"</b>。
 *
 * <h2>为什么不是直接接一个真向量库</h2>
 * 要求是"通过 Spring AI 的 {@code VectorStore} 接口编程，不绑死具体向量库"
 * （本地 PG+pgvector，生产 Milvus / ES）。这个要求分两半，
 * <b>本机只满足了后一半的条件</b>（2026-09-15 实测）：
 * <ul>
 *   <li>Docker 引擎在本机报 <b>{@code OsNotSupported}</b>（Docker Desktop 起不来）；</li>
 *   <li>本机没有 PostgreSQL（{@code psql} 不存在、5432 未监听、无 pg 安装目录）；</li>
 *   <li>没有 MSVC 工具链（{@code cl.exe} / {@code nmake} / Windows SDK 都没有），
 *       所以无法从源码编译 pgvector 扩展。</li>
 * </ul>
 *
 * <p>于是这里做了一个明确取舍：<b>把"向量检索"的每一步自己写一遍</b>，
 * 而不是等环境。这样做反而把三件平时被库藏起来的事摆到了明面上：
 * <ol>
 *   <li><b>向量是"算"出来再"存"起来的</b>——索引时必须校验"这批向量是哪个模型、多少维算的"，
 *       这正是 Embedding 漂移的防线；</li>
 *   <li><b>暴力 KNN 与 ANN 的区别就是"扫不扫全部"</b>——{@code scanLimit} 这个参数
 *       就是那条边界线的哨兵（真库里由 HNSW/IVF 这类索引承担）；</li>
 *   <li><b>余弦相似度是相对度量</b>——永远有"最像的一条"，所以没有阈值时
 *       一个完全无关的提问也会拿到一堆结果。</li>
 * </ol>
 *
 * <h2>交换成本（"面向接口编程"值多少）</h2>
 * 换成 PGvectorStore 时要改的只有这个文件：{@code add} 变成写表、
 * {@code search} 变成一条 {@code ORDER BY embedding <=> ?} 的 SQL。
 * {@code VectorRetriever}、评测链路、诊断端点、界面<b>都不动</b> ——
 * 因为它们只依赖 {@link ai.agent.service.rag.retrieve.Retriever}。
 */
@Component
public class VectorIndex {

	private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);

	private final EmbeddingModel embeddingModel;

	/** 块 id → 向量。 */
	private final Map<String, float[]> vectors = new HashMap<>();

	private final Map<String, Chunk> chunksById = new HashMap<>();

	/** 归一化之后的向量可以用点积当余弦相似度，所以搜索时只要点积。 */
	private List<Entry> entries = List.of();

	/**
	 * 索引指纹：模型的**名字**与**维度**。
	 *
	 * <h2>为什么要存这个，而且要拿它做校验</h2>
	 * "Embedding 漂移"的经典事故是：先用了模型 A 建索引，后来换成模型 B 只对新文档算向量，
	 * 于是同一个库里混着两个向量空间。查询向量来自 B，而老向量来自 A ——
	 * <b>它们之间的距离是没有意义的</b>，但检索照样返回结果、照样有分数排序，
	 * 表现是"最近搜索结果变得有点怪"，几乎不可能靠观察发现。
	 *
	 * <p>所以指纹存在索引里、检索时校验：一旦对不上就<b>直接抛</b>，
	 * 而不是"给个警告继续跑"。理由：这种错误没有"降级可用"的中间状态，
	 * 继续跑只会产出看起来正常的错数据（同族：结论朝有利于自己的方向偏，而且不报错）。
	 */
	private volatile String indexedModelName = "";

	private volatile int indexedDimensions = 0;

	private volatile int fitTermCount = 0;

	public VectorIndex(HashingEmbeddingModel embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

	/** 索引指纹，{@code 模型名/维度}。 */
	public String fingerprint() {
		return indexedModelName + "/" + indexedDimensions;
	}

	public int size() {
		return entries.size();
	}

	public String modelName() {
		return indexedModelName;
	}

	public int dimensions() {
		return indexedDimensions;
	}

	/** 拟合 IDF 用的词项数（诊断端点用：0 表示没拟合就检索了）。 */
	public int fittedTermCount() {
		return fitTermCount;
	}

	/**
	 * 重建索引（不是追加）。
	 *
	 * <p>与词法检索同一个约定：评测的两组实验必须在<b>互不干扰</b>的索引上各自检索。
	 * 追加语义会让上一组残留的块继续参与召回，两次实验就不是同一起点了。
	 */
	public void index(List<Chunk> chunks) {
		vectors.clear();
		chunksById.clear();

		// 【顺序不能反】先用语料拟合 IDF，再算向量。
		// 反过来的话，第一批向量是"无权重"算出来的，与后面的权重不一致，
		// 而索引里会同时存在两种口径的向量 —— 又一个静默的不一致
		if (embeddingModel instanceof HashingEmbeddingModel hashing) {
			hashing.fit(chunks.stream().map(Chunk::text).toList());
			fitTermCount = hashing.fittedTermCount();
		}

		List<Entry> built = new ArrayList<>(chunks.size());
		for (Chunk chunk : chunks) {
			float[] vector = embeddingModel.embed(chunk.text());
			if (vector == null || vector.length == 0) {
				throw new IllegalStateException("Embedding 返回了空向量，块：" + chunk.id());
			}
			vectors.put(chunk.id(), vector);
			chunksById.put(chunk.id(), chunk);
			built.add(new Entry(chunk, vector));
		}
		this.entries = List.copyOf(built);
		this.indexedModelName = embeddingModel instanceof HashingEmbeddingModel hashing ? hashing.name()
				: embeddingModel.getClass().getSimpleName();
		this.indexedDimensions = entries.isEmpty() ? embeddingModel.dimensions() : entries.get(0).vector().length;
		log.info("向量索引重建完成 chunks={} 维度={} 模型={} IDF词项={}", entries.size(), indexedDimensions,
				indexedModelName, fitTermCount);
	}

	/**
	 * 检索。
	 *
	 * @param query     提问
	 * @param topK      最多几条
	 * @param scanLimit 最多扫几个块（0 = 全扫）。这是"暴力 KNN vs ANN 索引"的边界哨兵
	 * @param minScore  相似度阈值（相对度量的兜底：低于它的结果不返回）
	 */
	public List<Result> search(String query, int topK, int scanLimit, double minScore) {
		if (entries.isEmpty() || query == null || query.isBlank() || topK <= 0) {
			return List.of();
		}
		float[] queryVector = embeddingModel.embed(query);
		// 漂移防线：查询向量与索引向量必须来自同一个模型/维度
		if (queryVector.length != indexedDimensions) {
			throw new IllegalStateException(("Embedding 维度不一致：索引是 %d 维（模型 %s），"
					+ "而查询向量是 %d 维。这说明**换了模型而索引没有全量重建** —— "
					+ "两个向量空间之间的距离没有意义，混用会静默产出错误结果（Embedding 漂移）")
					.formatted(indexedDimensions, indexedModelName, queryVector.length));
		}

		List<Result> scored = new ArrayList<>(entries.size());
		int scanned = 0;
		for (Entry entry : entries) {
			if (scanLimit > 0 && scanned >= scanLimit) {
				break;
			}
			scanned++;
			// 向量已 L2 归一化 ⇒ 点积 == 余弦相似度
			double score = dot(queryVector, entry.vector());
			if (score >= minScore) {
				scored.add(new Result(entry.chunk(), score));
			}
		}
		scored.sort(Comparator.comparingDouble(Result::score).reversed());
		List<Result> top = scored.size() > topK ? scored.subList(0, topK) : scored;
		List<Result> results = new ArrayList<>(top.size());
		for (int i = 0; i < top.size(); i++) {
			Result result = top.get(i);
			results.add(new Result(result.chunk(), result.score(), i + 1));
		}
		return results;
	}

	private static double dot(float[] a, float[] b) {
		int length = Math.min(a.length, b.length);
		double sum = 0;
		for (int i = 0; i < length; i++) {
			sum += (double) a[i] * b[i];
		}
		return sum;
	}

	/** 一条向量检索结果。 */
	public record Result(Chunk chunk, double score, int rank) {

		public Result(Chunk chunk, double score) {
			this(chunk, score, 0);
		}

	}

	private record Entry(Chunk chunk, float[] vector) {
	}

}
