package ai.agent.controller;

import ai.agent.entity.rag.kb.KbChunkStrategy;
import ai.agent.entity.rag.kb.KnowledgeBase;
import ai.agent.service.rag.kb.KbRetrievalService;
import ai.agent.service.rag.kb.KbVectorStoreFactory;
import ai.agent.service.rag.kb.KnowledgeBaseConflictException;
import ai.agent.service.rag.kb.KnowledgeBaseException;
import ai.agent.service.rag.kb.KnowledgeBaseService;
import ai.agent.service.rag.kb.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库的 HTTP 契约（建库 / 列表 / 详情 / 改配置 / 启停 / 删库）。
 *
 * <h2>为什么整个家族挂在一个开关下</h2>
 * {@code ai.rag.kb.enabled=false} 时这一族端点<b>不存在</b>（404），不是"存在但报错"：
 * 一个点进去全是红字的只读页面会让人以为功能坏了，而它其实是被关掉的
 * （同 {@code ai.agent.permission.management-enabled} 那条接法）。
 * ⚠️ 关掉它只影响知识库这一族；对话、agent、任务都不受影响。
 *
 * <h2>写动作返回"改完之后的那一份"</h2>
 * 与 {@code AgentTaskScheduleController} / {@code PermissionController} 同一条取舍：
 * 只回 {@code {"ok":true}} 的话，界面要再拉一次，而那次拉取与这次写入之间可能隔着别人的修改 ——
 * 页面显示的是别人改完的样子，用户以为是自己这次的结果。
 *
 * <h2>错误码</h2>
 * 400：请求里填的东西不对、或者 id 不存在（{@code KnowledgeBaseException}，由
 * {@code ErrorResponseAdvice} 统一成 {@code {"status":400,"error":...,"message":...}}）。
 * 409：名字已被占用（{@link KnowledgeBaseConflictException}，本类本地处理）——
 * 与 400 分开是因为**下一步不同**：一个要改内容，一个要改名字。
 */
@RestController
@RequestMapping(path = "/api/knowledge-bases", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "ai.rag.kb.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeBaseController {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

	private final KnowledgeBaseService knowledgeBases;

	private final KnowledgeDocumentService documentService;

	private final KbVectorStoreFactory vectorStoreFactory;

	private final KbRetrievalService retrievalService;

	public KnowledgeBaseController(KnowledgeBaseService knowledgeBases, KnowledgeDocumentService documentService,
			KbVectorStoreFactory vectorStoreFactory, KbRetrievalService retrievalService) {
		this.knowledgeBases = knowledgeBases;
		this.documentService = documentService;
		this.vectorStoreFactory = vectorStoreFactory;
		this.retrievalService = retrievalService;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	/**
	 * 知识库列表 + 当前能力。
	 *
	 * <p>{@code capabilities} 与列表一起给，而不是另开一个端点：界面上"能不能建库"
	 * 与"有哪些库"是同一屏要回答的问题，分两次请求会出现"列表说没有库、能力说不能建"的中间态。
	 */
	@GetMapping
	public Map<String, Object> list() {
		List<Map<String, Object>> views = new ArrayList<>();
		for (KnowledgeBase kb : knowledgeBases.list()) {
			views.add(view(kb));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("knowledgeBases", views);
		body.put("total", views.size());
		body.put("capabilities", capabilities());
		body.put("howToWrite", "POST /api/knowledge-bases（建库）；改 PUT /{id}；启停 POST /{id}/enabled；"
				+ "删 DELETE /{id}；传文档 POST /{id}/documents；文档与切片在 /api/knowledge-bases/{id}/documents");
		return body;
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable Long id) {
		KnowledgeBase kb = knowledgeBases.get(id);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("knowledgeBase", view(kb));
		body.put("capabilities", capabilities());
		return body;
	}

	/**
	 * 一致性核对：<b>清单说有几块</b>与<b>Milvus 里真有几条</b>的比对，逐文档给出。
	 *
	 * <p>它存在的原因是这套设计里有一处天然的双写：切片清单在库里、向量在 Milvus 里。
	 * 两者会不一致的场景不止一种（删到一半进程退出、手工动过库、Milvus 数据被清过），
	 * 而每一个都不报错 —— 表现只是"搜不到本该搜到的东西"。
	 * 所以这里把两份数字摆在一起，让人能自己看。
	 *
	 * <p>⚠️ Milvus 侧按文档数是全集合 count 之后按 doc_id 过滤的近似值：
	 * 精确到每份文档要按 metadata 的 JSON 路径查询（`metadata["doc_id"] == "…"`），
	 * 那在同一份文档上没有问题，但一份文档一次查询在文档很多时会很慢 ——
	 * 这里给的是"全库总数 + 每份文档的清单数"，比对的是**总和**。
	 */
	@GetMapping("/{id}/consistency")
	public Map<String, Object> consistency(@PathVariable Long id) {
		KnowledgeBase kb = knowledgeBases.get(id);
		long manifestTotal = knowledgeBases.chunkCount(id);
		List<Map<String, Object>> perDocument = new ArrayList<>();
		long sumOfDocuments = 0;
		for (ai.agent.entity.rag.kb.KnowledgeDocument document : documentService.list(id)) {
			long inManifest = documentService.chunkCountOf(document.id());
			sumOfDocuments += inManifest;
			Map<String, Object> cell = new LinkedHashMap<>();
			cell.put("documentId", document.id());
			cell.put("name", document.name());
			cell.put("status", document.status().name());
			cell.put("chunkCountOnRow", document.chunkCount());
			cell.put("chunkRowsInTable", inManifest);
			cell.put("rowMatchesTable", document.chunkCount() == inManifest);
			perDocument.add(cell);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("collectionName", kb.collectionName());
		body.put("vectorRows", vectorStoreFactory.countRows(kb.collectionName()));
		body.put("manifestRows", manifestTotal);
		body.put("sumOfDocumentCounts", sumOfDocuments);
		body.put("documents", perDocument);
		body.put("note", "vectorRows 为 -1 表示集合不存在；两份数字不等时，"
				+ "对相关文档点「重新索引」即可把它们对齐");
		return body;
	}

	/**
	 * 命中测试：拿一句提问直接检索（<b>只读</b>，不改任何状态）。
	 *
	 * <p>它是"这套东西到底能不能用"的唯一直接证据来源 —— 语料切得怎么样、向量算得对不对、
	 * 阈值定得合不合适，都只能靠真的问一句、看召回结果与分数。
	 * 所以响应里把三个数字一起给：{@code indexSize}（分母，库里有几片）、
	 * {@code candidateCount}（这次粗召回几条）、每条的两个分数（向量分与重排分）。
	 *
	 * <p>重排的处理分两种（见 {@code KbRerankService}）：
	 * 请求里**显式**写了 {@code rerank=true} 而重排不可用 → 400（你要的东西给不了，现在就说）；
	 * 没写而配置里也没开 → 200 + {@code rerankSkipReason}，界面上按红色显示 —— 那是"没配上"，不是"排过了"。
	 */
	@PostMapping(path = "/retrieve", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> retrieve(@RequestBody RetrieveRequest request) {
		if (request == null || request.query() == null || request.query().isBlank()) {
			throw new KnowledgeBaseException("缺少 query（要检索的那句话）");
		}
		if (request.kbIds() == null || request.kbIds().isEmpty()) {
			throw new KnowledgeBaseException("缺少 kbIds（要查哪些知识库）");
		}
		KbRetrievalService.Result result = retrievalService
			.retrieve(new KbRetrievalService.Request(request.kbIds(), request.query(), request.topK(),
					request.similarityThreshold(), request.rerank(), Boolean.TRUE.equals(request.rerank())));

		List<Map<String, Object>> hits = new ArrayList<>();
		for (ai.agent.entity.rag.kb.KbHit hit : result.hits()) {
			Map<String, Object> cell = new LinkedHashMap<>();
			cell.put("rank", hit.rank());
			cell.put("kbId", hit.kbId());
			cell.put("kbName", hit.kbName());
			cell.put("documentId", hit.documentId());
			cell.put("documentName", hit.documentName());
			cell.put("chunkUid", hit.chunkUid());
			cell.put("seq", hit.seq());
			cell.put("headingPath", hit.headingPath());
			cell.put("tokenCount", hit.tokenCount());
			cell.put("vectorScore", hit.vectorScore());
			cell.put("rerankScore", hit.rerankScore());
			cell.put("text", hit.text());
			hits.add(cell);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", request.query());
		body.put("hits", hits);
		body.put("hitCount", hits.size());
		body.put("indexSize", result.indexSize());
		body.put("candidateCount", result.candidateCount());
		body.put("reranked", result.reranked());
		body.put("rerankSkipReason", result.rerankSkipReason());
		body.put("notes", result.notes());
		body.put("elapsedMs", result.elapsedMs());
		return body;
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			return badRequest("缺少 name（知识库名称）");
		}
		// 留空的字段由服务层按 ai.rag.kb.* 的当前值补齐（默认值只有一处解析）
		KnowledgeBase.Draft draft = new KnowledgeBase.Draft(request.name(), request.description(),
				request.embeddingProvider(), request.embeddingModel(), request.embeddingDim(),
				strategyOrBadRequest(request.chunkStrategy()), request.topK(), request.similarityThreshold(),
				request.rerankEnabled());
		KnowledgeBase created;
		try {
			created = knowledgeBases.create(draft);
		}
		catch (IllegalArgumentException e) {
			// KnowledgeBase 的构造期校验（名字过长、维度小于 1…）也是"请求填得不对"
			return badRequest(e.getMessage());
		}
		log.info("建知识库：id={} name={} 集合={}", created.id(), created.name(), created.collectionName());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("knowledgeBase", view(created));
		body.put("note", "Milvus 集合已建好，可以传文档了");
		return ResponseEntity.ok(body);
	}

	/**
	 * 改配置。
	 *
	 * <p>可改的只有名字、说明、召回条数、相似度阈值、是否重排。
	 * <b>embedding 的模型与维度不给改</b>：它们是建库时的快照，且等于 Milvus 集合的维度 ——
	 * 改它等于换一个向量空间，已有向量全部作废（见 {@link KnowledgeBase} 的类注释）。
	 */
	@PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			return badRequest("缺少 name（知识库名称）");
		}
		// 没提交的字段按现状保留：PUT 提交的是"这个库现在应该是什么样"，
		// 但界面允许只改名字（那时三个检索字段是缺省的，不能把它们写成 0）
		KnowledgeBase current = knowledgeBases.get(id);
		int topK = request.topK() == null ? current.topK() : request.topK();
		double threshold = request.similarityThreshold() == null ? current.similarityThreshold()
				: request.similarityThreshold();
		boolean rerank = request.rerankEnabled() == null ? current.rerankEnabled() : request.rerankEnabled();
		KnowledgeBase updated = knowledgeBases.update(id, request.name(), request.description(), topK, threshold,
				rerank);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("knowledgeBase", view(updated));
		return ResponseEntity.ok(body);
	}

	@PostMapping(path = "/{id}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable Long id, @RequestBody EnabledRequest request) {
		if (request == null || request.enabled() == null) {
			return badRequest("缺少 enabled（true 启用 / false 停用）");
		}
		KnowledgeBase updated = knowledgeBases.setEnabled(id, request.enabled());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("knowledgeBase", view(updated));
		body.put("note", updated.enabled() ? "已启用，会参与检索" : "已停用：不参与检索，文档与切片都还在");
		return ResponseEntity.ok(body);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
		KnowledgeBase kb = knowledgeBases.get(id);
		knowledgeBases.delete(id);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("knowledgeBaseId", id);
		body.put("deleted", view(kb));
		body.put("note", "Milvus 集合 %s、切片与文档记录都已删除。上传的原文件仍留在磁盘上（ai.rag.kb.storage-path 下），"
				+ "要一并清理请手工删目录".formatted(kb.collectionName()));
		return ResponseEntity.ok(body);
	}

	// ------------------------------------------------------------------
	//  错误
	// ------------------------------------------------------------------

	/** 名字撞车 → 409（与 400 分开，见类注释）。 */
	@ExceptionHandler(KnowledgeBaseConflictException.class)
	public ResponseEntity<Map<String, Object>> conflict(KnowledgeBaseConflictException e) {
		return ResponseEntity.status(409).body(Map.of("ok", false, "error", String.valueOf(e.getReason())));
	}

	// ------------------------------------------------------------------
	//  组装
	// ------------------------------------------------------------------

	private Map<String, Object> capabilities() {
		KnowledgeBaseService.Capabilities caps = knowledgeBases.capabilities();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("embeddingAvailable", caps.embeddingAvailable());
		body.put("embeddingReason", caps.embeddingReason());
		body.put("embeddingProvider", caps.embeddingProvider());
		body.put("embeddingModel", caps.embeddingModel());
		body.put("embeddingDimensions", caps.embeddingDimensions());
		body.put("milvusReachable", caps.milvusReachable());
		body.put("rerankAvailable", caps.rerankAvailable());
		body.put("rerankReason", caps.rerankReason());
		body.put("rerankModel", caps.rerankModel());
		return body;
	}

	private Map<String, Object> view(KnowledgeBase kb) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", kb.id());
		body.put("name", kb.name());
		// null 不写成空串：界面上「（未填说明）」与"填了一段看不见的字"是两件事
		body.put("description", kb.description());
		body.put("embeddingProvider", kb.embeddingProvider());
		body.put("embeddingModel", kb.embeddingModel());
		body.put("embeddingDim", kb.embeddingDim());
		body.put("collectionName", kb.collectionName());
		body.put("chunkStrategy", kb.chunkStrategy().value());
		body.put("chunkStrategyLabel", kb.chunkStrategy().label());
		body.put("topK", kb.topK());
		body.put("similarityThreshold", kb.similarityThreshold());
		body.put("rerankEnabled", kb.rerankEnabled());
		body.put("enabled", kb.enabled());
		body.put("documentCount", knowledgeBases.documentCount(kb.id()));
		body.put("chunkCount", knowledgeBases.chunkCount(kb.id()));
		body.put("createdAt", kb.createdAt() == null ? null : kb.createdAt().toString());
		body.put("updatedAt", kb.updatedAt() == null ? null : kb.updatedAt().toString());
		return body;
	}

	private static KbChunkStrategy strategyOrBadRequest(String raw) {
		if (raw == null || raw.isBlank()) {
			return KbChunkStrategy.STRUCTURAL;
		}
		try {
			return KbChunkStrategy.parse(raw);
		}
		catch (IllegalArgumentException e) {
			throw new ai.agent.service.rag.kb.KnowledgeBaseException(e.getMessage());
		}
	}

	private static ResponseEntity<Map<String, Object>> badRequest(String message) {
		return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
	}

	// ------------------------------------------------------------------
	//  请求体（放在控制器里，与 AgentTaskScheduleController 同一种做法）
	// ------------------------------------------------------------------

	/**
	 * 建库。
	 *
	 * <p>embedding 的四个字段都是可选的：不填就用配置里的当前值（见
	 * {@code ai.rag.kb.embedding.*}）。之所以允许填，是因为建库那一刻写进去的是**快照**，
	 * 之后改配置不影响它 —— 于是"这个库用的是哪个模型"必须在这一刻定下来。
	 */
	public record CreateRequest(String name, String description, String embeddingProvider, String embeddingModel,
			Integer embeddingDim, String chunkStrategy, Integer topK, Double similarityThreshold,
			Boolean rerankEnabled) {
	}

	public record UpdateRequest(String name, String description, Integer topK, Double similarityThreshold,
			Boolean rerankEnabled) {
	}

	public record EnabledRequest(Boolean enabled) {
	}

	/**
	 * 命中测试入参。
	 *
	 * <p>三个可选参数留空时按库自己的配置：{@code topK}（取参与检索的那些库里最大的那个）、
	 * {@code similarityThreshold}（取最小的）、{@code rerank}（任一库开了就重排）。
	 */
	public record RetrieveRequest(List<Long> kbIds, String query, Integer topK, Double similarityThreshold,
			Boolean rerank) {
	}

}
