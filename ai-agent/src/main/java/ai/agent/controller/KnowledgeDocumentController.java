package ai.agent.controller;

import ai.agent.entity.rag.kb.KbChunk;
import ai.agent.entity.rag.kb.KnowledgeDocument;
import ai.agent.service.rag.kb.KbChunkService;
import ai.agent.service.rag.kb.KnowledgeBaseConflictException;
import ai.agent.service.rag.kb.KnowledgeBaseException;
import ai.agent.service.rag.kb.KnowledgeDocumentService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档与切片（都挂在知识库下面：一份文档属于某个库，界面上也是从库点进去的）。
 *
 * <h2>★ 这是本项目第一处 multipart（文件上传）</h2>
 * 三件必须一起成立，少一件都表现为"上传失败"而不是"上传被限制"：
 * <ol>
 *   <li>{@code spring.servlet.multipart.max-file-size}/{@code max-request-size}
 *       写在 {@code application.properties} 里 —— Boot 的默认值是 1MB/10MB，
 *       不写的话超过 1MB 的文件根本进不到这个方法里；</li>
 *   <li>{@link #tooLarge} 把 {@code MaxUploadSizeExceededException} 变成 <b>413</b>：
 *       不处理时它是一个 500 —— 于是"文件太大"看起来像"服务器坏了"；</li>
 *   <li>业务额度（{@code ai.rag.kb.upload.max-file-bytes}）在服务层再拦一道，
 *       它的说法里带文件名。两道限制都要能分清是哪一道拦下的。</li>
 * </ol>
 *
 * <h2>一次一个文件</h2>
 * 请求体里只有一个 {@code file}。多文件时界面发多次请求、每份各自回执 ——
 * 一次传十个文件、其中一个超限就整批失败的话，"哪一个不行"要靠猜，
 * 而用户要的是一个能逐个重试的结果。
 */
@RestController
@RequestMapping(path = "/api/knowledge-bases/{kbId}/documents", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "ai.rag.kb.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeDocumentController {

	private final KnowledgeDocumentService documents;

	private final KbChunkService chunks;

	public KnowledgeDocumentController(KnowledgeDocumentService documents, KbChunkService chunks) {
		this.documents = documents;
		this.chunks = chunks;
	}

	// ------------------------------------------------------------------
	//  文档
	// ------------------------------------------------------------------

	@GetMapping
	public Map<String, Object> list(@PathVariable Long kbId) {
		List<Map<String, Object>> views = new ArrayList<>();
		for (KnowledgeDocument document : documents.list(kbId)) {
			views.add(view(document));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("documents", views);
		body.put("total", views.size());
		body.put("counts", counts(kbId));
		body.put("howToWrite", "传文档 POST 这个地址（multipart，字段名 file，一次一个）；"
				+ "重新索引 POST /{docId}/re-index；启停 POST /{docId}/enabled；删 DELETE /{docId}；"
				+ "切片 POST /{docId}/chunks/preview 预览、GET /{docId}/chunks 列表");
		return body;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<Map<String, Object>> upload(@PathVariable Long kbId,
			@RequestPart("file") MultipartFile file) {
		if (file == null || file.isEmpty()) {
			return badRequest("没有收到文件（表单字段名要是 file）");
		}
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException e) {
			throw new KnowledgeBaseException("读上传内容失败：" + e.getMessage(), e);
		}
		KnowledgeDocument document = documents.upload(kbId, file.getOriginalFilename(), content);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("document", view(document));
		body.put("note", "已入队，状态会在处理完成后变成「已索引」（列表页刷新可见）");
		return ResponseEntity.ok(body);
	}

	@GetMapping("/{docId}")
	public Map<String, Object> detail(@PathVariable Long kbId, @PathVariable Long docId) {
		KnowledgeDocument document = requireInKb(kbId, docId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("document", view(document));
		body.put("chunkTotal", chunks.list(docId, 0, 1).total());
		return body;
	}

	/** 改展示名。不动落盘路径、不影响已索引的切片。 */
	@PutMapping(path = "/{docId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> rename(@PathVariable Long kbId, @PathVariable Long docId,
			@RequestBody RenameRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			return badRequest("缺少 name（新的文件名）");
		}
		requireInKb(kbId, docId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("document", view(documents.rename(docId, request.name())));
		return ResponseEntity.ok(body);
	}

	@PostMapping(path = "/{docId}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable Long kbId, @PathVariable Long docId,
			@RequestBody EnabledRequest request) {
		if (request == null || request.enabled() == null) {
			return badRequest("缺少 enabled（true 启用 / false 停用）");
		}
		requireInKb(kbId, docId);
		KnowledgeDocument document = documents.setEnabled(docId, request.enabled());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("document", view(document));
		body.put("note", document.enabled()
				? "已启用：这份文档的切片会重新参与检索"
				: "已停用：这份文档的所有切片都不再被召回（切片本身没删）");
		return ResponseEntity.ok(body);
	}

	@DeleteMapping("/{docId}")
	public ResponseEntity<Map<String, Object>> delete(@PathVariable Long kbId, @PathVariable Long docId) {
		KnowledgeDocument document = requireInKb(kbId, docId);
		documents.delete(docId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("deleted", view(document));
		body.put("note", "向量、切片、文档记录与上传的原件都已删除");
		return ResponseEntity.ok(body);
	}

	@PostMapping("/{docId}/re-index")
	public ResponseEntity<Map<String, Object>> reIndex(@PathVariable Long kbId, @PathVariable Long docId) {
		requireInKb(kbId, docId);
		KnowledgeDocument document = documents.reIndex(docId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("document", view(document));
		body.put("note", "旧的切片与向量已清掉，重新入队；跑完之后切片 id 会换一批");
		return ResponseEntity.ok(body);
	}

	// ------------------------------------------------------------------
	//  切片
	// ------------------------------------------------------------------

	/**
	 * 切分预览：解析原件、按当前策略切一遍，**不写库、不算向量**。
	 *
	 * <p>它给的是"真正会入库的那些块"（同一套解析器与分块器），所以界面上看到的
	 * 就是按下保存之后的结果 —— 预览另算一遍的话，两边一旦不同就是"预览说切 12 块、实际 15 块"。
	 */
	@PostMapping("/{docId}/chunks/preview")
	public Map<String, Object> preview(@PathVariable Long kbId, @PathVariable Long docId) {
		requireInKb(kbId, docId);
		List<KbChunk> preview = documents.previewChunks(docId);
		List<Map<String, Object>> views = new ArrayList<>();
		for (KbChunk chunk : preview) {
			views.add(chunkView(chunk, false));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("chunks", views);
		body.put("total", views.size());
		body.put("totalTokens", preview.stream().mapToInt(KbChunk::tokenCount).sum());
		body.put("note", "预览不写库、不算向量；保存走「重新索引」");
		return body;
	}

	@GetMapping("/{docId}/chunks")
	public Map<String, Object> listChunks(@PathVariable Long kbId, @PathVariable Long docId,
			@RequestParam(name = "offset", defaultValue = "0") int offset,
			@RequestParam(name = "limit", defaultValue = "20") int limit) {
		requireInKb(kbId, docId);
		KbChunkService.Paged page = chunks.list(docId, offset, limit);
		List<Map<String, Object>> views = new ArrayList<>();
		for (KbChunk chunk : page.chunks()) {
			views.add(chunkView(chunk, true));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("chunks", views);
		body.put("total", page.total());
		body.put("offset", page.offset());
		body.put("limit", page.limit());
		return body;
	}

	/**
	 * 改切片正文：会重新算一次向量（花一次钱、动两个库）。
	 *
	 * <p>同步返回"改完的那一片"，所以保存完立刻搜就能搜到新内容。
	 */
	@PutMapping(path = "/{docId}/chunks/{uid}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> updateChunk(@PathVariable Long kbId, @PathVariable Long docId,
			@PathVariable String uid, @RequestBody ChunkTextRequest request) {
		if (request == null || request.text() == null) {
			return badRequest("缺少 text（切片正文）");
		}
		requireInKb(kbId, docId);
		KbChunk updated = chunks.updateText(uid, request.text());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("chunk", chunkView(updated, true));
		body.put("note", "已重新向量化");
		return ResponseEntity.ok(body);
	}

	@PostMapping(path = "/{docId}/chunks/{uid}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> setChunkEnabled(@PathVariable Long kbId, @PathVariable Long docId,
			@PathVariable String uid, @RequestBody EnabledRequest request) {
		if (request == null || request.enabled() == null) {
			return badRequest("缺少 enabled（true 参与检索 / false 不参与）");
		}
		requireInKb(kbId, docId);
		KbChunk updated = chunks.setEnabled(uid, request.enabled());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("chunk", chunkView(updated, true));
		return ResponseEntity.ok(body);
	}

	@DeleteMapping("/{docId}/chunks/{uid}")
	public ResponseEntity<Map<String, Object>> deleteChunk(@PathVariable Long kbId, @PathVariable Long docId,
			@PathVariable String uid) {
		requireInKb(kbId, docId);
		chunks.delete(uid);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("deletedChunkUid", uid);
		body.put("note", "这一片已从向量库与清单里删掉；文档的块数已减一");
		return ResponseEntity.ok(body);
	}

	// ------------------------------------------------------------------
	//  错误
	// ------------------------------------------------------------------

	@ExceptionHandler(KnowledgeBaseConflictException.class)
	public ResponseEntity<Map<String, Object>> conflict(KnowledgeBaseConflictException e) {
		return ResponseEntity.status(409).body(Map.of("ok", false, "error", String.valueOf(e.getReason())));
	}

	/** 超过容器上限 → 413（不处理时它是个 500，见类注释）。 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(413).body(Map.of("ok", false, "error",
				"文件超过上传上限（spring.servlet.multipart.max-file-size 与 "
						+ "ai.rag.kb.upload.max-file-bytes 两道里的一道拦下了）。拆小一点再传"));
	}

	// ------------------------------------------------------------------
	//  组装
	// ------------------------------------------------------------------

	/** 确认这份文档属于 URL 里的那个库：路径上两个 id 对不上时给 400，而不是"静默按 docId 干活"。 */
	private KnowledgeDocument requireInKb(Long kbId, Long docId) {
		KnowledgeDocument document = documents.get(docId);
		if (document.kbId() != kbId) {
			throw new KnowledgeBaseException("文档 #%d 不属于知识库 #%d（它属于 #%d）"
				.formatted(docId, kbId, document.kbId()));
		}
		return document;
	}

	private List<Map<String, Object>> counts(Long kbId) {
		List<Map<String, Object>> counts = new ArrayList<>();
		for (KnowledgeDocumentService.StatusCount count : documents.countsByStatus(kbId)) {
			Map<String, Object> cell = new LinkedHashMap<>();
			cell.put("status", count.status());
			cell.put("label", count.label());
			cell.put("total", count.total());
			counts.add(cell);
		}
		return counts;
	}

	private Map<String, Object> view(KnowledgeDocument document) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", document.id());
		body.put("kbId", document.kbId());
		body.put("name", document.name());
		body.put("format", document.format());
		body.put("sizeBytes", document.sizeBytes());
		body.put("status", document.status().name());
		body.put("statusLabel", document.status().label());
		body.put("enabled", document.enabled());
		body.put("chunkCount", document.chunkCount());
		body.put("tokenCount", document.tokenCount());
		body.put("error", document.error());
		body.put("indexedAt", document.indexedAt() == null ? null : document.indexedAt().toString());
		body.put("createdAt", document.createdAt() == null ? null : document.createdAt().toString());
		body.put("updatedAt", document.updatedAt() == null ? null : document.updatedAt().toString());
		// 三种"没有块"的分辨位：没索引过 / 索引了但 0 块 / 失败。界面按它们给不同的话术
		body.put("neverIndexed", document.neverIndexed());
		body.put("indexedWithNoChunk", document.indexedWithNoChunk());
		return body;
	}

	private Map<String, Object> chunkView(KbChunk chunk, boolean persisted) {
		Map<String, Object> body = new LinkedHashMap<>();
		// 预览里的块还没有 uid（没写库），给 null 而不是空串：空串看起来像一个真的 uid
		body.put("uid", chunk.uid());
		body.put("documentId", chunk.documentId());
		body.put("seq", chunk.seq());
		body.put("text", chunk.text());
		body.put("headingPath", chunk.headingPath());
		body.put("tokenCount", chunk.tokenCount());
		body.put("charCount", chunk.charCount());
		if (persisted) {
			body.put("enabled", chunk.enabled());
		}
		return body;
	}

	private static ResponseEntity<Map<String, Object>> badRequest(String message) {
		return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
	}

	public record RenameRequest(String name) {
	}

	public record EnabledRequest(Boolean enabled) {
	}

	public record ChunkTextRequest(String text) {
	}

}
