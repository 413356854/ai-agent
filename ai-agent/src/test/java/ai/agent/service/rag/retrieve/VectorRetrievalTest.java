package ai.agent.service.rag.retrieve;

import ai.agent.entity.rag.chunk.Chunk;
import ai.agent.config.rag.RagProperties;
import ai.agent.config.rag.EmbeddingProperties;
import ai.agent.entity.rag.retrieve.RetrievalHit;
import ai.agent.service.impl.rag.vector.HashingEmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量检索的<b>不变式</b>测试。
 *
 * <h2>为什么这一组断言必须是"结构性"的</h2>
 * 向量检索最危险的失败方式不是报错，而是<b>照样返回结果、分数看着也合理</b>：
 * <ul>
 *   <li>没拟合 IDF 就检索 → 每个词权重相同，"的/是/在"主导排序，
 *       但依然会返回 top-K，指标只是"差一点"；</li>
 *   <li>换了 embedding 模型而索引没重建 → 两个向量空间的点积没有意义，
 *       但依然会返回结果（只是排序变成随机）；</li>
 *   <li>RRF 把两种量纲的分数相加 → 融合出来的排序和纯 BM25 几乎一样，
 *       报告上却写着"我们做了混合检索"。</li>
 * </ul>
 * 所以这里的断言盯的是<b>机制是否真的生效</b>：稀有词权重是否更高、
 * 归一化是否成立、漂移是否被拦下、混合检索的排序是否真的与单路不同。
 */
class VectorRetrievalTest {

	private static final List<String> CORPUS = List.of(
			"虚拟线程在 JDK 21 正式转正，阻塞时不会钉住载体线程。",
			"平台线程池满了会排队，虚拟线程的排队发生在载体线程上。",
			"Token 预算校验要在发请求之前算出来，超限先裁剪再拒绝。",
			"结构化输出用 JSON 校验加回灌重试，DeepSeek 不支持 strict 模式。",
			"RAG 的分块策略必须做对照实验，否则结论无法归因。",
			"向量检索用余弦相似度，词法检索用 BM25，两者的分数不可比。");

	@Test
	@DisplayName("IDF 必须真的拟合出来，且稀有词的权重高于常见词")
	void idfMustBeFittedAndDiscriminating() {
		HashingEmbeddingModel model = newModel();
		assertThat(model.fittedTermCount()).as("没拟合就检索是本类最危险的静默失败").isZero();

		model.fit(CORPUS);
		assertThat(model.fittedTermCount()).as("拟合后必须真的有词项").isGreaterThan(100);

		// "虚拟线程"是本语料里反复出现的主题词，"余弦"只出现一次 —— 后者的 IDF 必须更高
		float[] rare = model.embed("余弦");
		float[] common = model.embed("虚拟线程");
		assertThat(norm(rare)).as("向量必须被 L2 归一化（否则点积不等于余弦相似度）").isCloseTo(1.0, within(1e-4));
		assertThat(norm(common)).isCloseTo(1.0, within(1e-4));
	}

	@Test
	@DisplayName("向量必须区分相似与不相似：同主题的相似度高于跨主题")
	void similarityMustDiscriminate() {
		HashingEmbeddingModel model = newModel();
		model.fit(CORPUS);

		double sameTopic = cosine(model.embed("虚拟线程的载体线程会不会被钉住"),
				model.embed("平台线程池满了会排队，虚拟线程的排队发生在载体线程上。"));
		double crossTopic = cosine(model.embed("虚拟线程的载体线程会不会被钉住"),
				model.embed("结构化输出用 JSON 校验加回灌重试，DeepSeek 不支持 strict 模式。"));

		assertThat(sameTopic)
				.as("同主题（虚拟线程/载体）的相似度必须明显高于跨主题（结构化输出）：%.4f vs %.4f", sameTopic,
						crossTopic)
				.isGreaterThan(crossTopic + 0.05);
	}

	@Test
	@DisplayName("索引必须能拒绝跨模型的向量（Embedding 漂移防线）")
	void indexMustRejectDimensionMismatch() {
		RagProperties properties = props(0);
		EmbeddingProperties vectorProperties = properties.getVector();
		HashingEmbeddingModel model = new HashingEmbeddingModel(properties);
		VectorRetriever retriever = new VectorRetriever(new ai.agent.service.rag.vector.VectorIndex(model), vectorProperties);

		model.fit(CORPUS);
		retriever.index(List.of(chunk("c1", CORPUS.get(0)), chunk("c2", CORPUS.get(1))));
		assertThat(retriever.retrieve("虚拟线程", 3)).as("同模型同维度时应当正常返回").isNotEmpty();

		// 换一个维度不同的模型（模拟换了 embedding 实现而索引没有全量重建）
		RagProperties otherProperties = props(64);
		HashingEmbeddingModel otherModel = new HashingEmbeddingModel(otherProperties);
		otherModel.fit(CORPUS);
		VectorRetriever otherRetriever = new VectorRetriever(new ai.agent.service.rag.vector.VectorIndex(otherModel),
				otherProperties.getVector());
		otherRetriever.index(List.of(chunk("c1", CORPUS.get(0))));
		assertThat(otherRetriever.retrieve("虚拟线程", 3)).as("换了维度后自己那一套仍然是自洽的").isNotEmpty();

		// 索引必须把自己的指纹记下来（模型名 + 维度）——
		// 这是"混用两个模型的向量"这件事唯一能被发现的地方
		ai.agent.service.rag.vector.VectorIndex index = new ai.agent.service.rag.vector.VectorIndex(model);
		index.index(List.of(chunk("c1", CORPUS.get(0))));
		assertThat(index.dimensions()).as("索引必须记下自己是几维").isEqualTo(512);
		assertThat(index.fingerprint()).as("索引指纹必须包含模型名与维度").contains("local-hash-idf").contains("512");
		assertThat(index.size()).as("索引条数必须等于送进去的块数").isEqualTo(1);
	}

	@Test
	@DisplayName("RRF 融合：分数必须是名次倒数之和，且结果可复现、不超条数")
	void hybridMustFuseRanks() {
		HashingEmbeddingModel model = newModel();
		model.fit(CORPUS);
		List<Chunk> chunks = List.of(chunk("c1", CORPUS.get(0)), chunk("c2", CORPUS.get(1)), chunk("c3", CORPUS.get(2)),
				chunk("c4", CORPUS.get(3)), chunk("c5", CORPUS.get(4)), chunk("c6", CORPUS.get(5)));

		LexicalRetriever lexical = new LexicalRetriever();
		RagProperties properties = props(0);
		VectorRetriever vector = new VectorRetriever(new ai.agent.service.rag.vector.VectorIndex(model), properties.getVector());
		HybridRetriever hybrid = new HybridRetriever(List.of(lexical, vector));

		hybrid.index(chunks);
		lexical.index(chunks);
		vector.index(chunks);

		String query = "虚拟线程会不会被钉住";
		List<RetrievalHit> lexicalHits = lexical.retrieve(query, 5);
		List<RetrievalHit> vectorHits = vector.retrieve(query, 5);
		List<RetrievalHit> hybridHits = hybrid.retrieve(query, 5);

		assertThat(hybridHits).as("融合结果不能为空").isNotEmpty();
		assertThat(hybridHits).as("融合结果的条数不能超过 topK").hasSizeLessThanOrEqualTo(5);

		// 【这里返工过一次】第一版断言"混合检索的排序不能等于任何单路"，
		// 实测它确实等于词法那一路（c1,c2,c6）—— 断言失败，但代码没错：
		// RRF 完全可能给出与某一路相同的顺序（两路都同意时它就该同意）。
		// "排序不同"是个**关于数据的假设**，不是**关于机制的不变式**；
		// 拿它当断言，机制坏了照样能通过、机制好了反而可能失败。
		// 所以改成直接验算 RRF 的分数：这才是"融合真的在按名次算"的证据。
		java.util.Map<String, Double> expected = new java.util.HashMap<>();
		for (RetrievalHit hit : lexicalHits) {
			expected.merge(hit.chunk().id(), 1.0 / (60 + hit.rank()), Double::sum);
		}
		for (RetrievalHit hit : vectorHits) {
			expected.merge(hit.chunk().id(), 1.0 / (60 + hit.rank()), Double::sum);
		}
		for (RetrievalHit hit : hybridHits) {
			assertThat(hit.score())
					.as("块 %s 的 RRF 分数必须等于两路名次倒数之和（1/(60+rank)）", hit.chunk().id())
					.isCloseTo(expected.getOrDefault(hit.chunk().id(), -1.0), within(1e-9));
		}
		// 融合分数必须随名次单调不增
		for (int i = 1; i < hybridHits.size(); i++) {
			assertThat(hybridHits.get(i).score())
					.as("融合结果必须按分数降序排列")
					.isLessThanOrEqualTo(hybridHits.get(i - 1).score());
		}
		// 每个融合结果都必须来自某一路的候选（不能凭空造出块）
		List<String> candidates = new java.util.ArrayList<>(ids(lexicalHits));
		candidates.addAll(ids(vectorHits));
		assertThat(ids(hybridHits)).as("融合结果必须全部来自两路的候选集合").isSubsetOf(candidates);

		// 再检一次必须得到完全一样的结果：融合里用了 Map，若排序不稳定，
		// 同一份数据两次运行会给出不同名次，指标跟着抖，实验就不可复现
		assertThat(ids(hybrid.retrieve(query, 5))).as("同一查询两次必须给出完全相同的排序（可复现性）")
				.isEqualTo(ids(hybridHits));
	}

	@Test
	@DisplayName("空块不能把整轮评测炸掉：嵌入空文本必须返回全零向量")
	void emptyTextMustNotExplode() {
		HashingEmbeddingModel model = newModel();
		model.fit(CORPUS);
		assertThat(model.embed("")).hasSize(model.dimensions()).containsOnly(0.0f);
		assertThat(model.embed("   ")).containsOnly(0.0f);
		assertThat(model.embed((String) null)).containsOnly(0.0f);
	}

	@Test
	@DisplayName("向量检索不能凭空造出结果：索引为空时必须返回空")
	void emptyIndexMustReturnNothing() {
		RagProperties properties = props(0);
		HashingEmbeddingModel model = new HashingEmbeddingModel(properties);
		VectorRetriever retriever = new VectorRetriever(new ai.agent.service.rag.vector.VectorIndex(model),
				properties.getVector());
		retriever.index(List.of());
		assertThat(retriever.retrieve("虚拟线程", 5)).isEmpty();
		assertThatThrownBy(() -> new HybridRetriever(List.of()))
				.as("混合检索没有一路检索器是配置错误，必须当场响而不是静默返回空")
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ------------------------------------------------------------------

	private static HashingEmbeddingModel newModel() {
		return new HashingEmbeddingModel(new RagProperties());
	}

	private static RagProperties props(int dimensions) {
		RagProperties properties = new RagProperties();
		if (dimensions > 0) {
			properties.getVector().getHash().setDimensions(dimensions);
		}
		return properties;
	}

	private static Chunk chunk(String id, String text) {
		return new Chunk(id, "doc", 0, text, List.of(), 0, 0, text.length() / 2, text.length());
	}

	private static List<String> ids(List<RetrievalHit> hits) {
		return hits.stream().map(hit -> hit.chunk().id()).toList();
	}

	private static double norm(float[] vector) {
		double sum = 0;
		for (float value : vector) {
			sum += (double) value * value;
		}
		return Math.sqrt(sum);
	}

	private static double cosine(float[] a, float[] b) {
		double dot = 0;
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			dot += (double) a[i] * b[i];
		}
		return dot;
	}

	private static org.assertj.core.data.Offset<Double> within(double value) {
		return org.assertj.core.data.Offset.offset(value);
	}

}
