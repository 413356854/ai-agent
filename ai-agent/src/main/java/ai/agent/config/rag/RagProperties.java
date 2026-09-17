package ai.agent.config.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段二（RAG）配置，前缀 {@code ai.rag}。
 *
 * <p>为什么单开一个前缀而不是塞进 {@code ai.chat}：{@code ai.chat} 那棵树管的是
 * "一次模型调用怎么发、超时怎么算"（阶段一），而这里管的是
 * "文档进来之后怎么变成可检索的东西"（阶段二）。两者的变更节奏完全不同 ——
 * 分块参数要反复试、超时参数基本不动，混在一棵树下会让"改分块"和"改超时"
 * 在配置评审里看起来是同一类变更。
 */
@ConfigurationProperties(prefix = "ai.rag")
public class RagProperties {

	@NestedConfigurationProperty
	private Corpus corpus = new Corpus();

	@NestedConfigurationProperty
	private Chunking chunking = new Chunking();

	@NestedConfigurationProperty
	private Retrieval retrieval = new Retrieval();

	@NestedConfigurationProperty
	private Evaluation evaluation = new Evaluation();

	/**
	 * 向量检索（{@code ai.rag.vector.*}）。
	 *
	 * <p>放在这里而不是单独注册一个 {@code @ConfigurationProperties}：
	 * RAG 的参数应当只有一个来源，而"嵌套字段"让外部键自然变成
	 * {@code ai.rag.vector.*}，与 {@code ai.rag.retrieval.*} 平级。
	 */
	@NestedConfigurationProperty
	private EmbeddingProperties vector = new EmbeddingProperties();

	public EmbeddingProperties getVector() {
		return vector;
	}

	public void setVector(EmbeddingProperties vector) {
		this.vector = vector;
	}

	public Corpus getCorpus() {
		return corpus;
	}

	public void setCorpus(Corpus corpus) {
		this.corpus = corpus;
	}

	public Chunking getChunking() {
		return chunking;
	}

	public void setChunking(Chunking chunking) {
		this.chunking = chunking;
	}

	public Retrieval getRetrieval() {
		return retrieval;
	}

	public void setRetrieval(Retrieval retrieval) {
		this.retrieval = retrieval;
	}

	public Evaluation getEvaluation() {
		return evaluation;
	}

	public void setEvaluation(Evaluation evaluation) {
		this.evaluation = evaluation;
	}

	/**
	 * 语料库位置。
	 *
	 * <p>做成可配而不是写死类路径，是因为语料要从"项目自带文档"
	 * 换成"真实企业文档"——那是阶段二后期一定会发生的事，
	 * 而那一刻如果还要改代码重新打包，实验节奏会被拖慢很多。
	 */
	public static class Corpus {

		/** 类路径下的语料目录。用一个目录而不是逐个列文件，新增文档不需要改配置。 */
		private String location = "classpath:rag/corpus";

		/** 只收集这些扩展名的文件（留空表示用解析器自己的白名单）。 */
		private Set<String> extensions = new LinkedHashSet<>(Set.of("md", "markdown", "txt", "html", "htm", "pdf", "docx"));

		/**
		 * 文件大小上限。
		 *
		 * <p>存在的意义不是"省内存"，而是给"有人把 2GB 的日志丢进来"这件事一个明确的上界。
		 * 没有上界的后果是解析阶段把堆吃满，表现为整个应用 OOM ——
		 * 一个文档的问题变成全站不可用。
		 */
		private long maxFileSizeBytes = 32L * 1024 * 1024;

		public String getLocation() {
			return location;
		}

		public void setLocation(String location) {
			this.location = location;
		}

		public Set<String> getExtensions() {
			return extensions;
		}

		public void setExtensions(Set<String> extensions) {
			this.extensions = extensions;
		}

		public long getMaxFileSizeBytes() {
			return maxFileSizeBytes;
		}

		public void setMaxFileSizeBytes(long maxFileSizeBytes) {
			this.maxFileSizeBytes = maxFileSizeBytes;
		}
	}

	/**
	 * 分块参数。
	 *
	 * <p>两个策略各有自己的一组参数，因为它们的"长度"含义根本不同：
	 * 固定切分的长度是<b>字符</b>（它不懂 token），结构切分的长度是 <b>token</b>
	 * （因为最终限制来自模型窗口）。用同一组参数会掩盖这个差异 ——
	 * 而"字符数不等于 token 数"恰恰是阶段一实测出来的结论
	 * （中文 1 字 ≈ 0.61 token，英文 1 token ≈ 6.6 字符，差 4 倍）。
	 */
	public static class Chunking {

		@NestedConfigurationProperty
		private Fixed fixed = new Fixed();

		@NestedConfigurationProperty
		private Structural structural = new Structural();

		public Fixed getFixed() {
			return fixed;
		}

		public void setFixed(Fixed fixed) {
			this.fixed = fixed;
		}

		public Structural getStructural() {
			return structural;
		}

		public void setStructural(Structural structural) {
			this.structural = structural;
		}

		/**
		 * 固定字数硬切（对照组）的参数。
		 *
		 * <p>默认值刻意选了 500 字 / 50 字重叠：这是"教程里最常见的那组数字"，
		 * 拿它当基准，量出来的差距才具有"别人的朴素做法 vs 我的做法"的参考价值。
		 * 换一组更差的参数去比赢，结论没有意义。
		 */
		public static class Fixed {

			/** 每个块的字符数。 */
			private int size = 500;

			/**
			 * 相邻块重叠的字符数。
			 *
			 * <p>硬切也会加 overlap —— 这点常被忽略：<b>overlap 不是语义分块的专利</b>，
			 * 它是"防止边界切断语义"的通用手段。所以对比实验里两组都开 overlap，
			 * 差异才能归因到"切点选在哪"，而不是"有没有重叠"。
			 */
			private int overlap = 50;

			public int getSize() {
				return size;
			}

			public void setSize(int size) {
				this.size = size;
			}

			public int getOverlap() {
				return overlap;
			}

			public void setOverlap(int overlap) {
				this.overlap = overlap;
			}
		}

		/**
		 * 结构感知分块的参数。
		 *
		 * <p>三个参数（上限 / 下限 / 重叠）凑在一起才构成一个"策略"，
		 * 少任何一个都会退化：只有上限 → 短段落碎成一堆没信息的块；
		 * 只有下限 → 长章节被切成超窗的大块；没有重叠 → 跨段落的论述被切断。
		 */
		public static class Structural {

			/** 单块 token 上限（估算值）。 */
			private int maxTokens = 400;

			/**
			 * 单块 token 下限：小于它的相邻块<b>尽量合并</b>。
			 *
			 * <p>为什么下限和上限一样重要：一个小节只有一句话时，
			 * 单独成块几乎检索不到（关键词密度太低，BM25 与向量都吃亏），
			 * 而且喂给模型时"一个块一句话"会让上下文碎成渣。
			 * 合并到相邻块里，它至少能借助邻居的词汇被召回。
			 */
			private int minTokens = 80;

			/** 相邻块之间的重叠 token 数。 */
			private int overlapTokens = 50;

			/** 超过这个 token 数的单段（比如一整张表）必须硬拆，否则单块必然超窗。 */
			private int hardSplitThresholdTokens = 800;

			/**
			 * 全语料的 token 重复率上限（0.35 = 35%）。
			 *
			 * <h2>为什么要有这个上限（这是实测逼出来的一个参数）</h2>
			 * 重叠是"防边界切断"的手段，但它<b>对每一块都是重复计费</b>。
			 * 实测第一版结构分块的重复率达到 <b>52.63%</b>（对照组只有 13.70%），
			 * 根源是"贪心装填后只剩一点空位、却仍把上一组整个尾部搬过来"：
			 * 一个刚开头的块被重叠塞满，下一轮又立刻断开，如此反复。
			 * 后果不只是浪费 token —— 召回列表里挤满内容重复的块，
			 * 有效候选变少，MRR 反而下降。
			 *
			 * <p><b>为什么做成"事后检查 + 自动降重叠"而不是提前预防</b>：
			 * 重叠到底会产生多少重复，取决于文档结构（段落长短、标题密度），
			 * 事先算不准。与其在装填逻辑里塞一堆估算，不如让装填保持简单、
			 * 装完量一次真实重复率，超了就减半重来 ——
			 * 三到五轮必然收敛到 0 重叠，不会死循环。
			 */
			private double maxDuplicationRatio = 0.35;

			public int getMaxTokens() {
				return maxTokens;
			}

			public void setMaxTokens(int maxTokens) {
				this.maxTokens = maxTokens;
			}

			public int getMinTokens() {
				return minTokens;
			}

			public void setMinTokens(int minTokens) {
				this.minTokens = minTokens;
			}

			public int getOverlapTokens() {
				return overlapTokens;
			}

			public void setOverlapTokens(int overlapTokens) {
				this.overlapTokens = overlapTokens;
			}

			public int getHardSplitThresholdTokens() {
				return hardSplitThresholdTokens;
			}

			public void setHardSplitThresholdTokens(int hardSplitThresholdTokens) {
				this.hardSplitThresholdTokens = hardSplitThresholdTokens;
			}

			public double getMaxDuplicationRatio() {
				return maxDuplicationRatio;
			}

			public void setMaxDuplicationRatio(double maxDuplicationRatio) {
				this.maxDuplicationRatio = maxDuplicationRatio;
			}

			/**
			 * 是否把标题面包屑拼进块正文。
			 *
			 * <p>做成开关是因为它<b>有代价也有收益，得用数字决定</b>：
			 * 收益是"块里出现了标题中的词"（用户提问常直接用标题词，而正文里可能一次都没提），
			 * 召回率会上升；代价是每块多占几十 token，检索回来后喂给模型的上下文变贵。
			 *
			 * <p>注意：本项目的实现是<b>给块正文留出 maxTokens 之外的空间</b>，
			 * 即面包屑不占 `maxTokens` 额度 —— 否则开开关会让实际块变小，
			 * 两组对比就混进了"块大小也变了"这个额外变量。
			 */
			private boolean includeHeadingBreadcrumb = true;

			/**
			 * 每个块前面加的面包屑最多保留几层。
			 *
			 * <p>不限制的话，深文档里会看到"总则 > 第二章 > 第三节 > 2.3.1 > ……"
			 * 占掉一大截；限制在最贴近的几层，既保住了定位信息，又不会喧宾夺主。
			 */
			private int headingDepth = 3;

			public boolean isIncludeHeadingBreadcrumb() {
				return includeHeadingBreadcrumb;
			}

			public void setIncludeHeadingBreadcrumb(boolean includeHeadingBreadcrumb) {
				this.includeHeadingBreadcrumb = includeHeadingBreadcrumb;
			}

			public int getHeadingDepth() {
				return headingDepth;
			}

			public void setHeadingDepth(int headingDepth) {
				this.headingDepth = headingDepth;
			}
		}
	}

	/**
	 * 检索参数（本阶段只有词法检索，向量检索在下一步接 VectorStore）。
	 */
	public static class Retrieval {

		/**
		 * 粗召回条数。
		 *
		 * <p>路线文档给的分层参数是「粗召回 30–100 → Rerank 后 5–10 → 进上下文 3–6」。
		 * 评测用 10 就够了：这里的目的是<b>比较分块策略</b>，不是调参 ——
		 * 指标太多会让报告失焦，而"取多少条"本身是可调的。
		 */
		private int topK = 10;

		/** 评测报告里同时给出 Recall@1/3/5/10，便于看出"正确答案排在第几位"。 */
		private List<Integer> reportAtK = List.of(1, 3, 5, 10);

		public int getTopK() {
			return topK;
		}

		public void setTopK(int topK) {
			this.topK = topK;
		}

		public List<Integer> getReportAtK() {
			return reportAtK;
		}

		public void setReportAtK(List<Integer> reportAtK) {
			this.reportAtK = reportAtK;
		}
	}

	/**
	 * 评测参数。
	 *
	 * <p>这里只有一个真正重要的字段：{@code maxCasesBeforeComparison} 之类的"要不要跑"的门槛。
	 * 之所以要它，是因为"跑几条用例就下结论"是评测里最常见也最贵的错误 ——
	 * 路线文档专门写了「别只问两三个问题觉得还行」。
	 */
	public static class Evaluation {

		/**
		 * 评测集路径。
		 *
		 * <p>⚠️ 这里的默认值必须与实际存在的资源一致。曾经写成 {@code classpath:rag/eval-set.json}
		 * —— 那个文件**从来不存在**，而 {@code application.properties} 里把它覆盖成了 {@code .tsv}，
		 * 所以问题一直没被触发；但任何一处"漏配"或"直接 new 出来测试"都会读到这个路径并报文件不存在，
		 * 而那时最自然的误判是"评测集丢了"。改掉它不改变运行时行为（properties 仍然显式覆盖），
		 * 目的是让**代码里的默认值与资源目录说的是同一件事**。
		 */
		private String evalSetLocation = "classpath:rag/eval-set.tsv";

		/**
		 * 少于这么多条用例时，报告里明确标注"样本量不足，结论不可用"。
		 *
		 * <p>不直接拒绝执行，而是<b>把警告写进报告</b>：调参阶段确实会想用几条用例快速试，
		 * 一刀切禁止会把人逼去绕过工具；但结论必须带上"样本不够"的标签，
		 * 防止那几条用例的数字被当成结论引用出去。
		 */
		private int minUsableCases = 30;

		public String getEvalSetLocation() {
			return evalSetLocation;
		}

		public void setEvalSetLocation(String evalSetLocation) {
			this.evalSetLocation = evalSetLocation;
		}

		public int getMinUsableCases() {
			return minUsableCases;
		}

		public void setMinUsableCases(int minUsableCases) {
			this.minUsableCases = minUsableCases;
		}
	}

}
