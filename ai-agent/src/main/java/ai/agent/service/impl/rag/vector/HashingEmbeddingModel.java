package ai.agent.service.impl.rag.vector;

import ai.agent.config.rag.RagProperties;
import ai.agent.service.rag.retrieve.CjkBigramAnalyzer;
import ai.agent.config.rag.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>离线哈希向量</b>：不依赖任何外部服务，把文本编码成定长稠密向量。
 *
 * <p>它实现的是 <b>Spring AI 的 {@link EmbeddingModel} 接口</b>
 * （不是本项目自定义的接口）—— 这一点是刻意的，见下面"换真模型时改哪里"。
 *
 * <h2>为什么本机必须有一个"自己能算"的 Embedding（环境约束，不是设计偏好）</h2>
 * 2026-09-15 实测两道封锁：
 * <ol>
 *   <li><b>DeepSeek 没有 Embedding 模型</b>。{@code GET /v1/models} 只返回
 *       {@code deepseek-flash} 与 {@code deepseek-v4-pro}；
 *       {@code POST /v1/embeddings} 与 {@code /embeddings} 一律返回 <b>404</b>。
 *       本项目当前的供应商<b>在协议上就没有这个能力</b>。</li>
 *   <li><b>pgvector 装不上</b>：Docker 引擎在本机报 {@code OsNotSupported}，
 *       没有本机 PostgreSQL，也没有 MSVC 工具链去源码编译 pgvector。</li>
 * </ol>
 *
 * <p>面对这种事有两条路：<b>停在这里等环境</b>，或<b>把不依赖环境的那部分做完、
 * 并把缺的部分做成可替换的接口</b>。这里选后者 —— 因为"检索侧"要学的东西
 * （向量化 → 建索引 → 相似度检索 → 与词法检索对照 → 等预算比较）
 * <b>没有一条依赖具体是哪个模型算的向量</b>。
 *
 * <h2>换真模型时改哪里（这就是"面向接口编程"在这里的实际价值）</h2>
 * 把本类替换成任意一个 {@code EmbeddingModel} 实现即可 ——
 * 例如 Spring AI 的 {@code OpenAiEmbeddingModel} 指向某个提供 embedding 的端点，
 * 或 Ollama 本地模型。下游（{@code VectorRetriever} / 评测 / 界面）<b>一行都不用改</b>，
 * 因为它们只认接口。
 *
 * <p><b>但有一条不能忘</b>：换模型必须<b>全量重建索引</b>。
 * 不同模型的向量空间不可比，混着存会让相似度退化成随机数，
 * 而且不会报错 —— 这就是路线文档里的"Embedding 漂移"。
 * 所以索引里必须记下"这批向量是哪个模型、哪个维度算的"，
 * 并在检索时校验（见 {@code SimpleVectorIndex} 的 {@code modelName}）。
 *
 * <h2>它是怎么算的</h2>
 * ① 用与词法检索<b>同一套分析器</b>（{@link CjkBigramAnalyzer}：中文二元组 + 英文按词）切词项；
 * ② 每个词项用稳定哈希落到若干维（带符号，降低"碰撞总是相加"的放大）；
 * ③ 按 <b>IDF</b> 加权（不加权的话"的/是/在"会主导相似度）；
 * ④ 全语料<b>只拟合一次 IDF</b>，然后 L2 归一化（归一化后余弦相似度 = 点积）。
 *
 * <h2>它<b>不是</b>什么 —— 结论的边界</h2>
 * 它<b>不是语义嵌入</b>。它是"哈希技巧 + TF-IDF"的稠密版本，依然建立在
 * <b>词面重合</b>上，因此<b>不能</b>解决"提问说'内存泄漏'、文档写'OOM'"
 * 这类词汇不匹配 —— 而那恰恰是向量检索最值钱的场景。
 *
 * <p>所以由它得出的结论只有一处能被采信：<b>"换成稠密向量检索之后，
 * 分块策略的结论变了吗"</b>（因为比的是同一度量下的两种分块）。
 * 绝不能写成"本项目验证了向量检索的效果 / 混合检索比 BM25 强多少"——
 * 那是拿一个词法近似品冒充语义检索。
 * 同族的教训是：结论朝有利于自己的方向偏，且不报错。
 */
@Component
public class HashingEmbeddingModel implements EmbeddingModel {

	/** 报告里的列名。带 {@code local-} 前缀，任何表格里都能一眼看出它不是真模型。 */
	public static final String NAME = "local-hash-idf";

	private static final Logger log = LoggerFactory.getLogger(HashingEmbeddingModel.class);

	private static final CjkBigramAnalyzer ANALYZER = new CjkBigramAnalyzer();

	private final EmbeddingProperties properties;

	/** 词项 → IDF 权重。**全语料拟合一次**，建索引前必须调用 {@link #fit}。 */
	private volatile Map<String, Double> idf = Map.of();

	/** 未登录词（提问里语料没见过的新词）的兜底权重 = 已拟合权重的均值。 */
	private volatile double defaultIdf = 1.0;

	/**
	 * 注意参数是 {@link RagProperties} 而不是 {@link EmbeddingProperties}：
	 * 后者是 {@code RagProperties.vector} 这个<b>嵌套字段</b>，不是独立 bean。
	 * 第一版按"配置类就该能直接注入"的直觉写成注入 {@code EmbeddingProperties}，
	 * 启动直接报 {@code No qualifying bean of type 'EmbeddingProperties'} ——
	 * 教训是：{@code @ConfigurationProperties} 注解本身<b>不会</b>让类变成 bean，
	 * 必须有 {@code @EnableConfigurationProperties} / {@code @ConfigurationPropertiesScan}
	 * 把它捡起来；而嵌套属性的"注册"是靠外层那个类被捡起来。
	 */
	public HashingEmbeddingModel(RagProperties properties) {
		this.properties = properties.getVector();
	}

	/**
	 * 用语料拟合 IDF。
	 *
	 * <h2>为什么 IDF 必须单独拟合、而且只用语料拟合一次</h2>
	 * 它是<b>语料级统计量</b>。若拿每个块自己的统计去算权重，同一个词在不同块里
	 * 权重就不可比，相似度随之失去意义。词法检索那边有完全相同的性质 ——
	 * "换一批块必须重开 reader，否则打分用的还是上一批语料的统计量"
	 * 讲的就是这件事。两个检索器在这点上必须同样处理，否则差异归因不到"度量方式"。
	 *
	 * <p>中文用字符二元组，因此"文档频率"统计的单位也是二元组 ——
	 * 与 BM25 同一份词项，这正是两组实验可比的前提。
	 */
	public void fit(List<String> documents) {
		Map<String, Integer> documentFrequency = new HashMap<>();
		int total = 0;
		for (String document : documents) {
			if (document == null || document.isBlank()) {
				continue;
			}
			total++;
			// 用 Set 去重：统计的是"文档频率"（出现过没有），不是"词频"（出现几次）
			for (String term : new LinkedHashSet<>(analyze(document))) {
				documentFrequency.merge(term, 1, Integer::sum);
			}
		}
		if (total == 0) {
			log.warn("IDF 拟合收到空语料，向量检索将退化为无权重（结果不可用于结论）");
			return;
		}
		Map<String, Double> fitted = new HashMap<>();
		double sum = 0;
		for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
			// 平滑版 IDF，与 Lucene BM25 的形式一致，避免出现 0 或负权重
			double value = Math.log(1.0 + (total - entry.getValue() + 0.5) / (entry.getValue() + 0.5));
			fitted.put(entry.getKey(), value);
			sum += value;
		}
		this.idf = Map.copyOf(fitted);
		this.defaultIdf = fitted.isEmpty() ? 1.0 : sum / fitted.size();
		log.info("IDF 拟合完成 文档数={} 词项数={} 未登录词权重={}", total, fitted.size(), round(this.defaultIdf));
	}

	/** 已拟合的词项数（诊断端点用：为 0 说明没拟合就检索了，结论不可用）。 */
	public int fittedTermCount() {
		return idf.size();
	}

	public String name() {
		return NAME;
	}

	@Override
	public int dimensions() {
		int configured = properties.getHash().getDimensions();
		return configured > 0 ? configured : 512;
	}

	/** 按接口要求实现：单条文本编码。 */
	@Override
	public float[] embed(String text) {
		return vectorize(text);
	}

	/** 按接口要求实现：Document 编码（取正文）。 */
	@Override
	public float[] embed(Document document) {
		return vectorize(document == null ? null : document.getText());
	}

	/**
	 * 按接口要求实现：一次调用编码多条。
	 *
	 * <p>返回 {@link EmbeddingResponse} 而不是裸 list，是因为 Spring AI 的
	 * 向量库实现会走这条路 —— 保持与真模型完全相同的形状，
	 * 将来把本类换成真模型时，向量库那一侧不需要任何适配。
	 */
	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<String> instructions = request == null || request.getInstructions() == null ? List.of()
				: request.getInstructions();
		List<Embedding> embeddings = new ArrayList<>(instructions.size());
		for (int i = 0; i < instructions.size(); i++) {
			embeddings.add(new Embedding(vectorize(instructions.get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		return texts.stream().map(this::vectorize).toList();
	}

	// ------------------------------------------------------------------

	private float[] vectorize(String text) {
		float[] vector = new float[dimensions()];
		if (text == null || text.isBlank()) {
			return vector;
		}
		Map<String, Integer> termFrequency = new HashMap<>();
		for (String term : analyze(text)) {
			termFrequency.merge(term, 1, Integer::sum);
		}
		for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
			String term = entry.getKey();
			// 亚线性词频（1 + log tf）：一个词出现 10 次不该比出现 1 次值钱 10 倍 ——
			// 与 BM25 的词频饱和是同一个动机
			double weight = (1.0 + Math.log(entry.getValue())) * idf.getOrDefault(term, defaultIdf);
			long signBits = murmur(term, 0x5bf03635);
			int terms = Math.max(1, properties.getHash().getTermsPerDimension());
			for (int slot = 0; slot < terms; slot++) {
				long mixed = murmur(term + "#" + slot, 0x1b873593 + slot);
				int dimension = (int) Math.floorMod(mixed, vector.length);
				boolean negative = ((signBits >>> (slot % 63)) & 1L) == 1L;
				vector[dimension] += negative ? (float) -weight : (float) weight;
			}
		}
		return l2Normalize(vector);
	}

	private static List<String> analyze(String text) {
		return ANALYZER.analyze(text).stream().map(term -> term.toLowerCase(Locale.ROOT)).toList();
	}

	/** L2 归一化：归一化之后余弦相似度就等于点积，内积计算省一半时间。 */
	private static float[] l2Normalize(float[] vector) {
		double norm = 0;
		for (float value : vector) {
			norm += (double) value * value;
		}
		if (norm <= 0) {
			return vector;
		}
		double scale = 1.0 / Math.sqrt(norm);
		for (int i = 0; i < vector.length; i++) {
			vector[i] = (float) (vector[i] * scale);
		}
		return vector;
	}

	/**
	 * 稳定哈希（murmur3 风格）。
	 *
	 * <p><b>不能用 {@code String.hashCode()}</b>：它的低位分布差，
	 * 而我们恰恰拿低位去当维号（{@code % dimensions}）。
	 * 哈希聚簇在这里的后果是"某些维度被过度使用 → 所有块互相更像"，
	 * 表现为召回虚高而 MRR 低，且<b>不报错</b>。
	 */
	private static long murmur(String value, int seed) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		long hash = 0x9747b28cL ^ seed;
		final long c1 = 0x87c37b91114253d5L;
		final long c2 = 0x4cf5ad432745937fL;
		int i = 0;
		while (i + 8 <= bytes.length) {
			long k = 0;
			for (int j = 0; j < 8; j++) {
				k |= (long) (bytes[i + j] & 0xFF) << (8 * j);
			}
			k *= c1;
			k = Long.rotateLeft(k, 31);
			k *= c2;
			hash ^= k;
			hash = Long.rotateLeft(hash, 27) * 5 + 0x52dce729L;
			i += 8;
		}
		long tail = 0;
		for (int j = 0; i + j < bytes.length; j++) {
			tail |= (long) (bytes[i + j] & 0xFF) << (8 * j);
		}
		if (tail != 0) {
			tail *= c1;
			tail = Long.rotateLeft(tail, 31);
			tail *= c2;
			hash ^= tail;
		}
		hash ^= bytes.length;
		hash ^= hash >>> 33;
		hash *= 0xff51afd7ed558ccdL;
		hash ^= hash >>> 33;
		hash *= 0xc4ceb9fe1a85ec53L;
		hash ^= hash >>> 33;
		return hash;
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

}
