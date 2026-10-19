package ai.agent.config.rag;

/**
 * 知识库配置（{@code ai.rag.kb.*}）。
 *
 * <h2>与 {@code ai.rag.*} 其余部分的区别</h2>
 * {@code corpus} / {@code chunking} / {@code retrieval} / {@code evaluation} 那几棵管的是
 * 阶段二的离线评测链路：语料在类路径、向量在内存、不连任何外部服务、不花钱。
 * 这一棵管的是另一条链路：文档从 HTTP 传进来、向量落在 Milvus、embedding 与重排要调 DashScope。
 * 两者的**变更原因不同**（评测参数随时调；这里的参数跟着部署环境走），所以单开一棵子树。
 *
 * <h2>非法值在构造期抛</h2>
 * 与 {@code AgentTaskProperties} 同一条规矩：这里每个值都在启动时校验，
 * 认不出来就拒绝启动 —— 一个拼错的索引类型如果被宽容成默认值，
 * 现象是"检索质量莫名变差"，而没有人会想到是自己写错了配置。
 *
 * <p>本类作为 {@link RagProperties} 的 {@code kb} 字段嵌套注册，
 * 所以不自己带 {@code @ConfigurationProperties}（理由见 {@link EmbeddingProperties} 的类注释）。
 */
public class KnowledgeBaseProperties {

	private boolean enabled = true;

	private String storagePath = "./data/kb";

	private String collectionPrefix = "kb_";

	/**
	 * Milvus 索引类型。
	 *
	 * <p>取 {@code AUTOINDEX}（Milvus 自己按数据量与平台挑索引），而不取 Spring AI 那个
	 * {@code IVF_FLAT} + {@code {"nlist":1024}} 的默认值：一个几百条向量的知识库上，
	 * 1024 个聚类桶意味着每个桶不到一条，召回窗口只落到个别桶里 ——
	 * 表现是"文档明明在里面，就是搜不出来"，而这一类漏召回没有任何报错。
	 */
	private String indexType = "AUTOINDEX";

	/** 距离度量。取 COSINE 与项目既有口径一致：Milvus 返回的相似度越大越像，Document 的分数字段直接就是它。 */
	private String metricType = "COSINE";

	private final Indexing indexing = new Indexing();

	private final Search search = new Search();

	private final Rerank rerank = new Rerank();

	private final Embedding embedding = new Embedding();

	private final Upload upload = new Upload();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public void setStoragePath(String storagePath) {
		this.storagePath = storagePath;
	}

	public String getCollectionPrefix() {
		return collectionPrefix;
	}

	public void setCollectionPrefix(String collectionPrefix) {
		this.collectionPrefix = collectionPrefix;
	}

	public String getIndexType() {
		return indexType;
	}

	public void setIndexType(String indexType) {
		this.indexType = indexType;
	}

	public String getMetricType() {
		return metricType;
	}

	public void setMetricType(String metricType) {
		this.metricType = metricType;
	}

	public Indexing getIndexing() {
		return indexing;
	}

	public Search getSearch() {
		return search;
	}

	public Rerank getRerank() {
		return rerank;
	}

	public Embedding getEmbedding() {
		return embedding;
	}

	public Upload getUpload() {
		return upload;
	}

	/** 索引线程池（{@code ai.rag.kb.indexing.*}）。 */
	public static class Indexing {

		/**
		 * 索引线程数。
		 *
		 * <p>索引是"解析 + 切分 + 一次远端 embedding 调用"，CPU 与网络各占一半，
		 * 所以默认只要 2 条。上限 8：每个线程同时握着一份文档的正文与向量，
		 * 再多就是拿堆换吞吐。
		 */
		private int poolSize = 2;

		/**
		 * 队列容量。
		 *
		 * <p>队列满时**拒绝入队**并把文档标成 failed —— 不做无界队列，也不做 CallerRunsPolicy：
		 * 无界队列会把内存吃光，CallerRuns 会在 HTTP 线程上跑一次几十秒的解析（表现为上传接口卡住）。
		 */
		private int queueCapacity = 64;

		public int getPoolSize() {
			return poolSize;
		}

		public void setPoolSize(int poolSize) {
			if (poolSize < 1 || poolSize > 8) {
				throw new IllegalArgumentException("ai.rag.kb.indexing.pool-size 必须在 1..8 之间，实际是 " + poolSize
						+ "（每个索引线程同时持有一份文档正文与向量，再多是拿堆换吞吐）");
			}
			this.poolSize = poolSize;
		}

		/**
		 * 一份文档最多切出几个块，超过就以失败收场（不截断）。
		 *
		 * <p>存在的理由与 {@code corpus.max-file-size-bytes} 同一条：给"有人传进来一份 32MB 的日志"
		 * 一个明确的上界。1200 字的块切 32MB 文本会是几万块，而每一块都要调一次 embedding ——
		 * 那一次上传会把索引线程占住很久，而队列里后面的人只能等。
		 * ⚠️ 撞上限时**不截断**：截断之后的检索结果"少了后面一半"，
		 * 而它在界面上与"这份文档就这么多内容"完全一样（空值与零值那条纪律的又一次应用）。
		 */
		private int maxChunksPerDocument = 5000;

		public int getMaxChunksPerDocument() {
			return maxChunksPerDocument;
		}

		public void setMaxChunksPerDocument(int maxChunksPerDocument) {
			if (maxChunksPerDocument < 1) {
				throw new IllegalArgumentException("ai.rag.kb.indexing.max-chunks-per-document 至少是 1，实际是 "
						+ maxChunksPerDocument);
			}
			this.maxChunksPerDocument = maxChunksPerDocument;
		}

		public int getQueueCapacity() {
			return queueCapacity;
		}

		public void setQueueCapacity(int queueCapacity) {
			if (queueCapacity < 1) {
				throw new IllegalArgumentException("ai.rag.kb.indexing.queue-capacity 至少是 1，实际是 " + queueCapacity
						+ "（队列满了会拒绝入队并把文档标成失败；要「排不下就等着」请调大它）");
			}
			this.queueCapacity = queueCapacity;
		}

	}

	/** 默认检索参数（{@code ai.rag.kb.search.*}）。这三个是新建知识库时的初始值，之后按库存在库里。 */
	public static class Search {

		/** 默认召回条数。 */
		private int topK = 5;

		/**
		 * 默认相似度阈值。
		 *
		 * <p>余弦是相对度量：任何提问都能拿到"最像的几条"，所以没有阈值时
		 * 一个与语料无关的问题也会返回结果。0.4 是本项目既有的口径（同 {@code VectorIndex#search} 的 minScore 用法）。
		 */
		private double similarityThreshold = 0.4;

		/**
		 * 每库粗召回条数（进重排之前的候选数）。
		 *
		 * <p>它必须大于 top-k：重排只能重排它拿到的那些，粗召回太小等于重排没有素材。
		 */
		private int recallTopK = 20;

		public int getTopK() {
			return topK;
		}

		public void setTopK(int topK) {
			if (topK < 1) {
				throw new IllegalArgumentException("ai.rag.kb.search.top-k 至少是 1，实际是 " + topK);
			}
			this.topK = topK;
		}

		public double getSimilarityThreshold() {
			return similarityThreshold;
		}

		public void setSimilarityThreshold(double similarityThreshold) {
			this.similarityThreshold = similarityThreshold;
		}

		public int getRecallTopK() {
			return recallTopK;
		}

		public void setRecallTopK(int recallTopK) {
			if (recallTopK < 1) {
				throw new IllegalArgumentException("ai.rag.kb.search.recall-top-k 至少是 1，实际是 " + recallTopK);
			}
			this.recallTopK = recallTopK;
		}

	}

	/** 重排（{@code ai.rag.kb.rerank.*}）。 */
	public static class Rerank {

		/** 默认是否重排。默认关：重排要多一次 DashScope 调用（且按条计费）。 */
		private boolean enabled = false;

		/**
		 * DashScope 重排模型名。
		 *
		 * <p>取 {@code gte-rerank-v2} 而不是 Spring AI 那边默认的 {@code gte-rerank}：
		 * 后者的取值在本机的 key 上实测回 <b>403 AccessDenied</b>（模型没开通），
		 * 而 v2 同一把 key 直接可用（实测 2026-10-17：同一句提问，
		 * {@code gte-rerank-v2} 返回重排分 0.8576/0.6351/0.5610 且确实改变了名次）。
		 */
		private String model = "gte-rerank-v2";

		/** 重排后保留几条。0 表示"跟 top-k 一样"，见 KnowledgeBaseRerankService。 */
		private int topN = 0;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public int getTopN() {
			return topN;
		}

		public void setTopN(int topN) {
			this.topN = topN;
		}

	}

	/** embedding（{@code ai.rag.kb.embedding.*}）。 */
	public static class Embedding {

		/** 提供方。目前只有 dashscope 一处实现。 */
		private String provider = "dashscope";

		/** 默认模型名。建库时写进 knowledge_base 行，之后改这里不影响已有库。 */
		private String model = "text-embedding-v4";

		/**
		 * 默认向量维度。
		 *
		 * <p>建库时写进 knowledge_base 行，并决定 Milvus 集合的维度 —— 之后不可改。
		 */
		private int dimensions = 1024;

		/**
		 * DashScope API Key。
		 *
		 * <p>为空时**不拒绝启动**：知识库是可选功能，而没有这把 key 时对话、agent、
		 * 任务这些链路都照常工作。空值的表现是知识库接口回一个明确的
		 * {@code embeddingAvailable=false} + 原因，上传的文档以 failed 结束并写明缺哪把 key。
		 */
		private String apiKey = "";

		/**
		 * 单次 embedding 请求的条数上限。
		 *
		 * <p>取 10 是因为<b>上游就卡在 10</b>：DashScope 的文本 embedding 一次最多收 10 条，
		 * 超了整批失败并回 {@code InvalidParameter: batch size is invalid, it should not be larger
		 * than 10.}（实测 2026-10-17，把 30 条一次发出去拿到这个 400）。
		 * ⚠️ 这里调大不会"更流畅"，只会让整篇文档的向量化直接失败 ——
		 * 而失败信息来自上游，与本项目无关，看起来很突兀。
		 */
		private int batchSize = 10;

		public String getProvider() {
			return provider;
		}

		public void setProvider(String provider) {
			this.provider = provider;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public int getDimensions() {
			return dimensions;
		}

		public void setDimensions(int dimensions) {
			if (dimensions < 1) {
				throw new IllegalArgumentException("ai.rag.kb.embedding.dimensions 至少是 1，实际是 " + dimensions
						+ "（它是 Milvus 集合的维度，建库时定死）");
			}
			this.dimensions = dimensions;
		}

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public int getBatchSize() {
			return batchSize;
		}

		public void setBatchSize(int batchSize) {
			if (batchSize < 1) {
				throw new IllegalArgumentException("ai.rag.kb.embedding.batch-size 至少是 1，实际是 " + batchSize);
			}
			this.batchSize = batchSize;
		}

	}

	/** 上传（{@code ai.rag.kb.upload.*}）。 */
	public static class Upload {

		/**
		 * 单个文件的字节上限。
		 *
		 * <p>与 {@code ai.rag.corpus.max-file-size-bytes} 取同一个数（32MB）是刻意的：
		 * 那条限制回答的是同一个问题（"多大的一份文档不该被整个读进内存"），
		 * 两个不同的数等于两个没人负责的限制。
		 * 容器层还有一道 {@code spring.servlet.multipart.max-file-size}，两者都要过。
		 */
		private long maxFileBytes = 32L * 1024 * 1024;

		public long getMaxFileBytes() {
			return maxFileBytes;
		}

		public void setMaxFileBytes(long maxFileBytes) {
			this.maxFileBytes = maxFileBytes;
		}

	}

}
