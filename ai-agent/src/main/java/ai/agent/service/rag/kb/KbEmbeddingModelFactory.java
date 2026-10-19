package ai.agent.service.rag.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.text.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.text.DashScopeEmbeddingOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按知识库行里那份 embedding 快照（provider / model / dim）构造 {@link EmbeddingModel}。
 *
 * <h2>为什么不去用自动装配给的 EmbeddingModel Bean</h2>
 * 那样只能有一个模型（容器里多于一个就要报 "expected single matching bean"，
 * 而本项目已经为此把 {@code spring.ai.model.embedding} 设成了 {@code none}）。
 * 知识库是**每个库一份快照**：不同库可以来自不同的建库时间、不同的模型与维度，
 * 而集合维度建库时就定死了 —— 于是模型必须按行现取。
 *
 * <h2>key 为空时不抛在构造期</h2>
 * 空 key 在这里的语义是"这把 key 没配"，而不是"配置写错了"：
 * 知识库是可选功能，没有它时对话、agent、任务这些链路照常工作。
 * 所以构造期只 WARN 一次，真正要花钱的那一刻（建库、上传、检索）才抛，
 * 并把原因说清楚 —— {@link #unavailableReason()} 就是给接口用的那个说法。
 */
@Component
public class KbEmbeddingModelFactory {

	private static final Logger log = LoggerFactory.getLogger(KbEmbeddingModelFactory.class);

	/** 目前只有一处实现。取值写死在这里，认不出来的 provider 建库时直接拒。 */
	public static final String PROVIDER_DASHSCOPE = "dashscope";

	private final KnowledgeBaseProperties properties;

	/** 按 {@code provider|model|dim} 缓存：同一份模型每次索引都新建一遍会白白多开 HTTP 客户端。 */
	private final Map<String, EmbeddingModel> cache = new ConcurrentHashMap<>();

	public KbEmbeddingModelFactory(RagProperties rag) {
		// 取嵌套的那一棵：KnowledgeBaseProperties 不是 Bean（注册的是 RagProperties，
		// 与 EmbeddingProperties / Chunking 等同一种接法）
		this.properties = rag.getKb();
		if (!available()) {
			log.warn("知识库的 embedding 未配置（{}）：知识库的建库 / 上传 / 检索会明确报错，"
					+ "其余功能不受影响。要启用请设置环境变量 DASHSCOPE_API_KEY",
					unavailableReason());
		}
	}

	/** 现在能不能算向量。 */
	public boolean available() {
		return unavailableReason() == null;
	}

	/** 为什么不能算向量；能算时返回 null。这段文字会原样出现在接口响应里。 */
	public String unavailableReason() {
		String provider = properties.getEmbedding().getProvider();
		if (provider == null || !PROVIDER_DASHSCOPE.equalsIgnoreCase(provider.strip())) {
			return "ai.rag.kb.embedding.provider 只支持 " + PROVIDER_DASHSCOPE + "，当前是「" + provider + "」";
		}
		String apiKey = properties.getEmbedding().getApiKey();
		if (apiKey == null || apiKey.isBlank()) {
			return "ai.rag.kb.embedding.api-key 为空（环境变量 DASHSCOPE_API_KEY 没读到）";
		}
		return null;
	}

	/**
	 * 取一个模型实例。
	 *
	 * @param provider 建库时记下的提供方
	 * @param model 建库时记下的模型名
	 * @param dimensions 建库时记下的维度（必须与 Milvus 集合的维度一致）
	 */
	public EmbeddingModel resolve(String provider, String model, int dimensions) {
		String reason = unavailableReason();
		if (reason != null) {
			throw new KnowledgeBaseException("知识库的 embedding 不可用：" + reason
					+ "。设置环境变量 DASHSCOPE_API_KEY 后重启即可");
		}
		if (provider == null || !PROVIDER_DASHSCOPE.equalsIgnoreCase(provider.strip())) {
			throw new KnowledgeBaseException("不认识的 embedding 提供方「" + provider + "」，目前只支持 "
					+ PROVIDER_DASHSCOPE);
		}
		String key = provider.strip().toLowerCase() + "|" + model + "|" + dimensions;
		return cache.computeIfAbsent(key, ignored -> build(model, dimensions));
	}

	private EmbeddingModel build(String model, int dimensions) {
		DashScopeApi api = DashScopeApi.builder().apiKey(properties.getEmbedding().getApiKey().strip()).build();
		DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
			.model(model)
			.dimensions(dimensions)
			.build();
		// MetadataMode.EMBED：只把正文送去算向量，metadata 不参与 ——
		// 我们的 metadata 里有 doc_id 这类纯粹用来过滤的键，混进向量里只会污染相似度
		return DashScopeEmbeddingModel.builder()
			.dashScopeApi(api)
			.metadataMode(MetadataMode.EMBED)
			.defaultOptions(options)
			.retryTemplate(retryTemplate())
			.build();
	}

	/**
	 * 重试策略：默认的 {@code withDefaults()}。
	 *
	 * <p>不自己调参数：这里的一次调用失败会由索引线程记进 {@code kb_document.error}，
	 * 而"多试几次"与"早点把失败摆到人面前"在本项目里选后者（同 tool 层那条：
	 * 不确定的失败要尽快带着 stage 回到人这里，而不是自己闷着重试）。
	 */
	private static RetryTemplate retryTemplate() {
		return new RetryTemplate(RetryPolicy.withDefaults());
	}

}
