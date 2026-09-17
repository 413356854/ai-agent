package ai.agent.config.rag;

/**
 * 向量检索配置（{@code ai.rag.vector.*}）。
 *
 * <h2>为什么单开一棵子树，而不是塞进 {@code ai.rag.retrieval}</h2>
 * 因为两者的<b>变更原因不同</b>：{@code retrieval} 管的是"取几条、报几个 k"，
 * 换向量库/换 embedding 模型完全不动它；而这里管的是
 * "谁来算向量、算多少维、怎么比"。混在一棵树下会让"换模型"和"改 topK"
 * 在配置评审里看起来是同一类变更 —— 而前者是<b>必须全量重建索引</b>的那种变更。
 *
 * <h2>为什么是 {@code RagProperties} 里的嵌套属性，而不是自己带 {@code @ConfigurationProperties}</h2>
 * 两个原因：
 * <ol>
 *   <li><b>避免两个 bean 抢同一棵前缀</b>：RAG 的配置应当只有一个来源，
 *       分成两个 {@code @EnableConfigurationProperties} 之后，
 *       "这棵树下到底还有没有别的配置类"要靠翻代码才知道；</li>
 *   <li><b>键名要短</b>：作为 {@code RagProperties} 的 {@code vector} 字段嵌套进来，
 *       外部键就是 {@code ai.rag.vector.*}，与 {@code ai.rag.retrieval.*} 平级、
 *       读起来是一棵完整的树。</li>
 * </ol>
 * （这个类第一版带了 {@code @ConfigurationProperties} 却没被注册，
 * 启动直接报 {@code No qualifying bean of type 'EmbeddingProperties'} ——
 * 典型的"注解写了但没生效"：{@code @ConfigurationProperties} 本身<b>不会</b>
 * 让一个类变成 bean，必须有 {@code @EnableConfigurationProperties} 或
 * {@code @ConfigurationPropertiesScan} 把它捡起来。）
 */
public class EmbeddingProperties {

	private Hash hash = new Hash();

	private Search search = new Search();

	public Hash getHash() {
		return hash;
	}

	public void setHash(Hash hash) {
		this.hash = hash;
	}

	public Search getSearch() {
		return search;
	}

	public void setSearch(Search search) {
		this.search = search;
	}

	/**
	 * 离线哈希向量的参数（{@code ai.rag.vector.hash.*}）。
	 *
	 * <p>这三个参数是<b>一起决定"碰撞率"</b>的：维度越大碰撞越少，
	 * 每个词项落几维决定了"一个词项的表达能力"，而碰撞过多时
	 * 相似度会整体被抬高（所有块看起来都像），表现为"召回率虚高、MRR 很低"。
	 */
	public static class Hash {

		/**
		 * 向量维度。
		 *
		 * <p>取 512 是长度与碰撞率的折中：本语料拟合出的词项数在数千量级，
		 * 512 维下每个词项落 3 维，平均每维被约 30 个词项用到 ——
		 * 还没到"所有块互相相似"的程度，但也不算宽裕。
		 * 这也是本实现被明确标注为<b>近似品</b>的原因之一（见 {@code HashingEmbeddingModel} 的注释）。
		 */
		private int dimensions = 512;

		/**
		 * 每个词项落到几个维度。
		 *
		 * <p>1 维的方差太大（一个词的表达全靠一维的正负），3 维是常见的起点。
		 * 调大能降方差，代价是碰撞概率上升 —— 所以它和 {@code dimensions} 要一起看。
		 */
		private int termsPerDimension = 3;

		public int getDimensions() {
			return dimensions;
		}

		public void setDimensions(int dimensions) {
			this.dimensions = dimensions;
		}

		public int getTermsPerDimension() {
			return termsPerDimension;
		}

		public void setTermsPerDimension(int termsPerDimension) {
			this.termsPerDimension = termsPerDimension;
		}
	}

	/** 向量检索参数（{@code ai.rag.vector.search.*}）。 */
	public static class Search {

		/**
		 * 检索时最多扫描多少个块。
		 *
		 * <p>纯暴力检索，所以这个值就是"成本上界"。取 0 表示不限制（全扫）。
		 * 做成可配是因为"块数一多就全扫"是要在真实项目里被替换掉的地方 ——
		 * 真向量库靠 ANN 索引（HNSW/IVF）避免全扫，
		 * 而这个参数就是本实现与真库之间那条边界线上的哨兵。
		 */
		private int scanLimit = 0;

		/**
		 * 相似度阈值：低于它的结果不返回。
		 *
		 * <p>为什么要有：余弦相似度是<b>相对度量</b>，永远有"最像的一条"，
		 * 哪怕它跟提问毫无关系。没有阈值时，一个完全无关的提问也会拿到
		 * top-10 结果，而"召回了但全不相关"和"没召回"在指标上看起来完全不同
		 * （前者拉低 Precision，后者拉低 Recall）—— 阈值让"没有相关的"这件事
		 * 能被表达出来。
		 */
		private double minScore = 0.0;

		public int getScanLimit() {
			return scanLimit;
		}

		public void setScanLimit(int scanLimit) {
			this.scanLimit = scanLimit;
		}

		public double getMinScore() {
			return minScore;
		}

		public void setMinScore(double minScore) {
			this.minScore = minScore;
		}
	}

}
