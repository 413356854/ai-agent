package ai.agent.controller;

import ai.agent.service.rag.chunk.Chunk;
import ai.agent.config.rag.RagProperties;
import ai.agent.service.rag.eval.AlignmentExperiment;
import ai.agent.service.rag.eval.ChunkingComparison;
import ai.agent.service.rag.eval.EvalCase;
import ai.agent.service.rag.eval.EvalSetLoader;
import ai.agent.service.rag.parse.BlockType;
import ai.agent.service.rag.parse.ParsedBlock;
import ai.agent.service.rag.parse.ParsedDocument;
import ai.agent.service.rag.pipeline.CorpusLoader;
import ai.agent.service.rag.pipeline.RagAlignmentService;
import ai.agent.service.rag.pipeline.RagEvaluationService;
import ai.agent.service.rag.retrieve.LexicalRetriever;
import ai.agent.service.rag.retrieve.RetrievalHit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 诊断端点：<b>把"分块好不好"这件事变成看得见、量得出的东西</b>。
 *
 * <h2>为什么要有这些端点，而不是"跑一遍测试看日志"</h2>
 * 与阶段一的 {@code /api/diag/token/*}、{@code /api/diag/threads/*} 同一个理由：
 * 调 RAG 参数是一个<b>反复试</b>的过程（换块大小、开关面包屑、改重叠），
 * 每次都要重新编译打包，实验节奏会被拖死。做成端点之后，
 * 改一个查询参数就能看到结果，而且这些数字随时可以重新量一遍 ——
 * 这比"当时跑过一次，截图存在某个地方"有用得多。
 *
 * <h2>端点分工</h2>
 * <ul>
 *   <li>{@code /corpus} —— 语料到底被读成了什么（块类型分布）。<b>排查第一步</b>：
 *       解析出错时后面全是白费功夫。</li>
 *   <li>{@code /preview} —— 直接看块长什么样。分块问题绝大部分是"看一眼就明白了"。</li>
 *   <li>{@code /tokens} —— 提问被切成了什么 token。检索不出结果时先看这里。</li>
 *   <li>{@code /retrieve} —— 单条提问的召回，带名次与分数。</li>
 *   <li>{@code /compare} —— 两种策略并排对照，<b>输出阶段二的验收数字</b>。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/diag/rag")
public class RagDiagnosticsController {

	/** 预览时最多返回多少个块，避免一个请求把整个语料的块都吐出来。 */
	private static final int PREVIEW_LIMIT = 5;

	private final CorpusLoader corpusLoader;

	private final EvalSetLoader evalSetLoader;

	private final RagEvaluationService evaluationService;

	private final RagAlignmentService alignmentService;

	private final RagProperties properties;

	public RagDiagnosticsController(CorpusLoader corpusLoader, EvalSetLoader evalSetLoader,
			RagEvaluationService evaluationService, RagAlignmentService alignmentService, RagProperties properties) {
		this.corpusLoader = corpusLoader;
		this.evalSetLoader = evalSetLoader;
		this.evaluationService = evaluationService;
		this.alignmentService = alignmentService;
		this.properties = properties;
	}

	/**
	 * 语料库概览：文档数、块类型分布、解析失败的文档。
	 *
	 * <p><b>块类型分布是解析是否正确最直接的证据</b>：
	 * 如果 {@code heading} 计数是 0，说明标题层级没被识别出来 ——
	 * 那么"按标题层级分块"这件事根本没发生，后面所有对比都没有意义。
	 * 这种"解析静默退化"是 RAG 里最常见的失败起点。
	 */
	@GetMapping(value = "/corpus", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> corpus() {
		CorpusLoader.CorpusLoadResult result = corpusLoader.load();

		Map<String, Integer> typeCounts = new LinkedHashMap<>();
		for (BlockType type : BlockType.values()) {
			typeCounts.put(type.name().toLowerCase(), 0);
		}
		List<Map<String, Object>> documents = new ArrayList<>();
		for (ParsedDocument document : result.documents()) {
			for (ParsedBlock block : document.blocks()) {
				typeCounts.merge(block.type().name().toLowerCase(), 1, Integer::sum);
			}
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("documentId", document.documentId());
			entry.put("blocks", document.blockCount());
			entry.put("chars", document.plainText().length());
			documents.add(entry);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("location", properties.getCorpus().getLocation());
		body.put("documentCount", result.documents().size());
		body.put("blockCount", result.blockCount());
		body.put("blockTypes", typeCounts);
		body.put("documents", documents);
		body.put("failed", result.failed().stream()
				.map(f -> Map.of("documentId", f.documentId(), "reason", f.reason())).toList());
		body.put("availableStrategies", evaluationService.availableStrategies());
		body.put("note", "blockTypes 里 heading 为 0 说明标题层级没被识别 —— 那『按标题分块』就没有真正生效，"
				+ "后面的对比结果都不可信");
		return body;
	}

	/**
	 * 预览某个策略切出来的块。
	 *
	 * <p>{@code detail=0}（默认）只返回摘要：块一旦多了，把全文都吐出来
	 * 响应会大到没法用 —— 而调参时想看的是"每块多大、面包屑对不对"，
	 * 不是把整篇文档再从接口读一遍。
	 */
	@GetMapping(value = "/preview", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> preview(@RequestParam(defaultValue = "structural") String strategy,
			@RequestParam(defaultValue = "0") int detail) {
		List<Chunk> chunks = evaluationService.chunkCorpus(strategy);
		int limit = detail > 0 ? detail : PREVIEW_LIMIT;

		List<Map<String, Object>> previews = new ArrayList<>();
		for (Chunk chunk : chunks.stream().limit(limit).toList()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", chunk.id());
			entry.put("index", chunk.index());
			entry.put("tokens", chunk.tokenCount());
			entry.put("chars", chunk.charCount());
			entry.put("breadcrumb", chunk.breadcrumb());
			entry.put("startBlock", chunk.startBlock());
			entry.put("endBlock", chunk.endBlock());
			entry.put("coveredBlocks", chunk.coveredBlocks());
			entry.put("text", chunk.text());
			previews.add(entry);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("strategy", strategy);
		body.put("chunkCount", chunks.size());
		body.put("avgTokens", chunks.isEmpty() ? 0
				: round(chunks.stream().mapToInt(Chunk::tokenCount).average().orElse(0)));
		body.put("maxTokens", chunks.stream().mapToInt(Chunk::tokenCount).max().orElse(0));
		body.put("minTokens", chunks.stream().mapToInt(Chunk::tokenCount).min().orElse(0));
		body.put("previewShown", previews.size());
		body.put("previews", previews);
		return body;
	}

	/** 分词诊断：一段文本（通常是评测集里的提问）被切成了哪些 token。 */
	@GetMapping(value = "/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> tokens(@RequestParam String text) {
		LexicalRetriever retriever = new LexicalRetriever();
		List<String> tokens = retriever.analyze(text);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("text", text);
		body.put("tokenCount", tokens.size());
		body.put("tokens", tokens);
		body.put("note", "中文被切成二元组（虚拟线程 → 虚拟/拟线/线程），英文按词切。"
				+ "检索不到结果时先看这里：提问与文档的 token 完全对不上，才是真正的原因");
		return body;
	}

	/**
	 * 单条提问的召回结果。
	 *
	 * <p>带名次与分数，并且显示每条命中的章节归属 ——
	 * 排查"召回错了"时，需要知道的是<b>召回了哪个章节的块</b>，
	 * 而不是只看一个分数。
	 */
	@GetMapping(value = "/retrieve", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> retrieve(@RequestParam String query,
			@RequestParam(defaultValue = "structural") String strategy,
			@RequestParam(defaultValue = "0") int topK) {
		int k = topK > 0 ? topK : properties.getRetrieval().getTopK();
		List<Chunk> chunks = evaluationService.chunkCorpus(strategy);

		LexicalRetriever retriever = new LexicalRetriever();
		retriever.index(chunks);
		List<RetrievalHit> hits = retriever.retrieve(query, k);

		List<Map<String, Object>> rows = new ArrayList<>();
		for (RetrievalHit hit : hits) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("rank", hit.rank());
			row.put("score", round(hit.score()));
			row.put("chunkId", hit.chunk().id());
			row.put("breadcrumb", hit.chunk().breadcrumb());
			row.put("tokens", hit.chunk().tokenCount());
			row.put("excerpt", excerpt(hit.chunk().text()));
			rows.add(row);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", query);
		body.put("strategy", strategy);
		body.put("indexedChunks", chunks.size());
		body.put("hitCount", rows.size());
		body.put("hits", rows);
		body.put("queryTokens", retriever.analyze(query));
		return body;
	}

	/**
	 * 评测集概览：条数与分组。<b>跑对照之前先确认用例集是完整的</b> ——
	 * 加载失败会直接抛异常（见 {@code EvalSetLoader}），不会静默少几条。
	 */
	@GetMapping(value = "/eval-set", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> evalSet() {
		List<EvalCase> cases = evalSetLoader.load();
		Map<String, Integer> byTag = new LinkedHashMap<>();
		for (EvalCase evalCase : cases) {
			byTag.merge(evalCase.tag(), 1, Integer::sum);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("location", properties.getEvaluation().getEvalSetLocation());
		body.put("caseCount", cases.size());
		body.put("byTag", byTag);
		body.put("minUsableCases", properties.getEvaluation().getMinUsableCases());
		body.put("sampleSufficient", cases.size() >= properties.getEvaluation().getMinUsableCases());
		body.put("cases", cases);
		return body;
	}

	/**
	 * <b>核心端点：分块策略对照。</b>
	 *
	 * <p>用 GET 是为了能在浏览器里直接打开，但注意它会真实跑完整评测
	 * （43 条用例 × 2 个策略 × 全语料分块 + 建索引），本机实测秒级，
	 * 不是那种"点一下卡十秒"的接口。
	 */
	@GetMapping(value = "/compare", produces = MediaType.APPLICATION_JSON_VALUE)
	public ChunkingComparison compare(@RequestParam(required = false) List<String> strategies,
			@RequestParam(defaultValue = "0") int topK) {
		return evaluationService.compare(strategies, topK);
	}

	/**
	 * POST 版本：策略列表走 body。
	 *
	 * <p>存在的理由与阶段一校准端点的 POST 版本完全相同（台账踩坑库 #32）：
	 * GET 把参数放在 query string 里，参数一多就会被 URL 长度和转义规则坑到，
	 * 而且<b>失败常常是静默的</b>（脚本吞掉 4xx，把上一轮结果当成本轮数据）。
	 */
	@PostMapping(value = "/compare", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ChunkingComparison compareByPost(@org.springframework.web.bind.annotation.RequestBody CompareRequest request) {
		return evaluationService.compare(request == null ? null : request.strategies(),
				request == null ? 0 : request.topK());
	}

	/** 对照请求体。 */
	public record CompareRequest(List<String> strategies, int topK) {
	}

	/**
	 * <b>块大小对齐实验</b>：把原始对照里那个混杂因素消掉。
	 *
	 * <p>为什么单开一个端点而不是给 {@code /compare} 加参数：它跑的东西不一样 ——
	 * 尺寸扫描 + 二分收敛 + 4 组对照 + 6 档预算归纳，
	 * 是 {@code /compare} 的 4 倍工作量。混在一个端点里，
	 * 前端就没法为"轻量对照"和"重量实验"分别设超时，
	 * 而超时给错正是台账踩坑库 #38 那个坑（实验正常、等待时间配错）。
	 */
	@GetMapping(value = "/align", produces = MediaType.APPLICATION_JSON_VALUE)
	public AlignmentExperiment align(@RequestParam(required = false) List<Integer> budgets,
			@RequestParam(defaultValue = "0") int points) {
		return alignmentService.run(budgets, points);
	}

	private static String excerpt(String text) {
		String flat = text.replace('\n', ' ').strip();
		return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

}
