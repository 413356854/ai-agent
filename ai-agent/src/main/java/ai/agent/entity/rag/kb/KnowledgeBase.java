package ai.agent.entity.rag.kb;

import java.time.Instant;

/**
 * 一个知识库（{@code knowledge_base} 表的一行，同时对应 Milvus 里的一个集合）。
 *
 * <h2>三个 embedding 字段是建库时的快照</h2>
 * {@code embeddingProvider / embeddingModel / embeddingDim} 在创建那一刻写死，
 * 之后**不随配置变化**：Milvus 集合的维度在建集合时就定了，换模型等于换一个向量空间
 * （同 {@code VectorIndex} 的指纹防线：两个向量空间之间的距离没有意义，混用会静默产出错结果）。
 * 所以更新接口不接受这三个字段，要换模型只能建新库重新上传。
 *
 * @param id 主键
 * @param name 名称（唯一；界面上按它认人）
 * @param description 说明，可为 null（界面显示「（未填说明）」）
 * @param embeddingProvider embedding 提供方，目前只有 {@code dashscope}
 * @param embeddingModel embedding 模型名（快照）
 * @param embeddingDim 向量维度（快照，且等于 Milvus 集合的维度）
 * @param collectionName Milvus 集合名，建库时由 id 生成，之后不可改
 * @param chunkStrategy 分块策略
 * @param topK 默认召回条数
 * @param similarityThreshold 默认相似度阈值
 * @param rerankEnabled 默认是否重排
 * @param enabled 是否参与检索（停用的库列表照常可见）
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record KnowledgeBase(Long id, String name, String description, String embeddingProvider,
		String embeddingModel, int embeddingDim, String collectionName, KbChunkStrategy chunkStrategy,
		int topK, double similarityThreshold, boolean rerankEnabled, boolean enabled,
		Instant createdAt, Instant updatedAt) {

	/** 与 {@code knowledge_base.name} 的列宽一致。 */
	public static final int MAX_NAME_CHARS = 128;

	/** 与 {@code knowledge_base.description} 的列宽一致。 */
	public static final int MAX_DESCRIPTION_CHARS = 512;

	/**
	 * 新建知识库的入参（id / 集合名 / 时间戳由服务层生成，不来自调用方）。
	 *
	 * <p>{@code embeddingDim / topK / similarityThreshold / rerankEnabled} 用包装类型：
	 * 它们可以留空，含义是"用 {@code ai.rag.kb.*} 里的当前值"——
	 * 这必须与"显式填了 0"区分开（0 是一个会被构造期校验拒绝的取值，而留空是正常用法）。
	 * 默认值由服务层填（配置只有一处，见 {@code DefaultKnowledgeBaseService#create}）。
	 */
	public record Draft(String name, String description, String embeddingProvider, String embeddingModel,
			Integer embeddingDim, KbChunkStrategy chunkStrategy, Integer topK, Double similarityThreshold,
			Boolean rerankEnabled) {
	}

	/**
	 * 构造期的校验：拦的是"写进库里就再也查不出为什么"的取值。
	 *
	 * <p>名称空/超宽会让界面上那一格什么都显示不出来；维度小于 1 会让 Milvus 建集合直接失败，
	 * 而失败信息指向的是 Milvus 而不是配置。
	 */
	public KnowledgeBase {
		name = name == null ? null : name.strip();
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("知识库名称必填");
		}
		if (name.length() > MAX_NAME_CHARS) {
			throw new IllegalArgumentException("知识库名称过长（%d 字符 > 上限 %d）"
					.formatted(name.length(), MAX_NAME_CHARS));
		}
		if (description != null && description.length() > MAX_DESCRIPTION_CHARS) {
			throw new IllegalArgumentException("知识库说明过长（%d 字符 > 上限 %d）"
					.formatted(description.length(), MAX_DESCRIPTION_CHARS));
		}
		if (embeddingDim < 1) {
			throw new IllegalArgumentException("向量维度至少是 1，实际是 " + embeddingDim
					+ "（它同时是 Milvus 集合的维度，建库时定死）");
		}
		if (topK < 1) {
			throw new IllegalArgumentException("召回条数至少是 1，实际是 " + topK);
		}
	}

	/** 改名的副本（其余字段原样）。 */
	public KnowledgeBase renamed(String newName, String newDescription) {
		return new KnowledgeBase(id, newName, newDescription, embeddingProvider, embeddingModel, embeddingDim,
				collectionName, chunkStrategy, topK, similarityThreshold, rerankEnabled, enabled, createdAt,
				updatedAt);
	}

	/** 改检索参数的副本。 */
	public KnowledgeBase withSearch(int newTopK, double newThreshold, boolean newRerankEnabled) {
		return new KnowledgeBase(id, name, description, embeddingProvider, embeddingModel, embeddingDim,
				collectionName, chunkStrategy, newTopK, newThreshold, newRerankEnabled, enabled, createdAt,
				updatedAt);
	}

	/** 启停的副本。 */
	public KnowledgeBase withEnabled(boolean newEnabled) {
		return new KnowledgeBase(id, name, description, embeddingProvider, embeddingModel, embeddingDim,
				collectionName, chunkStrategy, topK, similarityThreshold, rerankEnabled, newEnabled, createdAt,
				updatedAt);
	}

}
