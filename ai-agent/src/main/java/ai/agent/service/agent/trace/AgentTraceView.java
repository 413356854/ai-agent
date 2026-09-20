package ai.agent.service.agent.trace;

import ai.agent.service.agent.approval.AgentApprovalService;
import ai.agent.entity.agent.store.AgentRunRecord;
import ai.agent.service.agent.store.AgentRunStore;
import ai.agent.service.cost.CostPricing;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.entity.tool.RetrievalTrace;
import ai.agent.service.tool.ToolCallStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>Agent trace 五层装配视图</b>（阶段四第 2 件）。
 *
 * <h2>五层是什么，以及"缺一层"当时意味着什么</h2>
 * <table border="1">
 *   <caption>五层与它们各自的落点</caption>
 *   <tr><th>#</th><th>层</th><th>落点</th><th>缺它时答不了的问题</th></tr>
 *   <tr><td>1</td><td>会话</td><td>{@code agent_run.conversation_id}</td>
 *       <td>"这段对话里一共跑了几次、谁挂了"</td></tr>
 *   <tr><td>2</td><td>轮次 / 运行</td><td>{@code agent_run}（含 intent 归因）</td>
 *       <td>"这次任务什么结局、花了多少"</td></tr>
 *   <tr><td>3</td><td>LLM 调用</td><td>{@code agent_step}（含 prompt_fingerprint）</td>
 *       <td>"这一步用的是哪一版提示词"</td></tr>
 *   <tr><td>4</td><td>工具调用</td><td>{@code agent_tool_invocation}</td>
 *       <td>"它想调什么、走到哪一步、被拒了几次"</td></tr>
 *   <tr><td>5</td><td><b>检索</b></td><td>{@code retrieval_call} + {@code retrieval_hit}</td>
 *       <td><b>"它到底看到了什么"</b></td></tr>
 * </table>
 * 前四层在本件之前就有了（三张表），而第 5 层一直藏在
 * {@code agent_tool_invocation.feedback} 的一段**文本**里 ——
 * 那段文本对模型是对的（它只需要判断相关性），对人则等于没有：
 * 要数"检索了几次"得数文本里出现了几次「在 516 个文档片段里找到」，
 * 而"检索塞进 prompt 的 token 有多少"在文本里<b>根本不存在</b>。
 *
 * <h2>为什么这个类做的是"装配"而不是"再查一遍库"</h2>
 * 每一层都已经有权威的读取入口（{@link AgentRunStore}、{@link AgentApprovalService}、
 * {@link CostPricing}）。本类<b>不重新写 SQL</b>，只把五层按同一份事实对齐 ——
 * 重写查询就意味着第二份口径，而两份口径迟早对不上（成本看板那条"JOIN 不能把 token 放大"
 * 就是这么来的）。
 *
 * <h2>两条不能破的纪律</h2>
 * <ol>
 *   <li><b>金额算不出来就说算不出来</b>：价格表没配时给 {@code null} + 原因，
 *       <b>不给 ¥0.00</b>。与成本看板同一条规矩 —— 那个 0 会被读成"不花钱"；</li>
 *   <li><b>检索 token 必须标成估算</b>：它来自块自带的 {@code tokenCount}
 *       （{@code TokenEstimator} 算的），而成本看板那边的 token 来自上游 {@code usage}。
 *       两个口径混在一张页面上而不加区分，等于把"估算"当成事实记账。</li>
 * </ol>
 */
@Component
public class AgentTraceView {

	private static final Logger log = LoggerFactory.getLogger(AgentTraceView.class);

	/** 会话内最多列出多少次运行 —— 防止一段长对话把响应撑大。 */
	public static final int MAX_CONVERSATION_RUNS = 50;

	private final AgentRunStore store;

	private final AgentApprovalService approvals;

	private final PromptService prompts;

	private final CostPricing pricing;

	public AgentTraceView(AgentRunStore store, AgentApprovalService approvals, PromptService prompts,
			CostPricing pricing) {
		this.store = store;
		this.approvals = approvals;
		this.prompts = prompts;
		this.pricing = pricing;
	}

	/**
	 * 装配一次运行的完整 trace。
	 *
	 * @return 运行不存在时为空（调用方给 404 —— "没有这次运行"与"这次运行什么都没有"
	 *         是两件事，用同一个空响应表达会让排查的人以为任务跑了但没留痕）
	 */
	public Optional<Map<String, Object>> of(String runId) {
		Optional<AgentRunRecord> found = store.find(runId);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		AgentRunRecord run = found.get();

		List<AgentRunRecord.StepCheckpoint> steps = store.stepsOf(runId);
		List<AgentRunRecord.ToolCallRecord> toolCalls = store.toolCallsOf(runId);
		List<AgentRunRecord.RetrievalRecord> retrievals = store.retrievalsOf(runId);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("run", run(run));
		body.put("conversation", conversation(run));
		body.put("steps", steps(steps, toolCalls, retrievals));
		body.put("retrieval", retrievalSummary(steps, retrievals));
		body.put("cost", cost(steps));
		body.put("approvals", approvalsOf(runId));
		body.put("layers", layers(steps, toolCalls, retrievals));
		body.put("notes", notes(run, steps, toolCalls, retrievals));

		log.info("Agent trace 装配完成 runId={} 步骤={} 工具调用={} 检索={} 会话={}", runId, steps.size(),
				toolCalls.size(), retrievals.size(), run.hasConversation() ? run.conversationId() : "(无)");
		return Optional.of(body);
	}

	// ------------------------------------------------------------------
	//  第 1、2 层：会话与运行
	// ------------------------------------------------------------------

	private Map<String, Object> run(AgentRunRecord run) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", run.id());
		body.put("question", run.question());
		body.put("status", run.status().name());
		body.put("stopReason", run.stopReason() == null ? "" : run.stopReason().name());
		body.put("failureCause", run.failureCause() == null ? "" : run.failureCause().name());
		body.put("failureAdvice", run.failureAdvice() == null ? "" : run.failureAdvice());
		body.put("finalAnswer", run.finalAnswer() == null ? "" : run.finalAnswer());
		body.put("totalTokens", run.totalTokens());
		body.put("toolCalls", run.toolCallCount());
		body.put("elapsedMs", run.elapsedMs());
		body.put("node", run.node() == null ? "" : run.node());
		// 归因键：trace 与成本看板是**同一个事实的两个视图**，
		// 所以这里也把它带上 —— 否则"这次运行算在哪个业务头上"要在两个页面之间来回对
		body.put("intent", run.intent() == null ? "" : run.intent());
		// 【阶段四第 6 件】租户同理，而且它是**第二个维度**：
		// "谁花的钱"与"花在哪个功能上"是两个问题，报告里缺任何一个都得回到库里查
		body.put("tenantId", run.tenantId() == null ? "" : run.tenantId());
		// 【运行权限】这次运行在哪个目录下、拿了多大权限。
		// trace 是排查入口，而"完全权限档位下写操作不产生审核请求"这条规则意味着
		// 一张空的审批表既可能表示"没有写操作"，也可能表示"这一次被放宽了" ——
		// 区分这两者的唯一依据就是这里。空串 = 没有权限上下文（老数据、外部链路）
		body.put("workspaceDir", run.hasWorkspace() ? run.workspaceDir() : "");
		body.put("permissionLevel", run.permissionLevel() == null ? "" : run.permissionLevel().name());
		body.put("permissionLabel", run.permissionLevel() == null ? "" : run.permissionLevel().label());
		body.put("createdAt", run.createdAt() == null ? "" : run.createdAt().toString());
		body.put("updatedAt", run.updatedAt() == null ? "" : run.updatedAt().toString());
		return body;
	}

	/**
	 * 会话那一层。
	 *
	 * <h2>为什么没有会话 id 时也要给一个结构，而不是省略整个字段</h2>
	 * "这段对话只有一次运行"与"这次运行没有会话信息"在界面上会渲染成同一个空列表 ——
	 * 而它们对使用者是两件完全不同的事：前者是正常的，后者说明<b>调用方没传会话 id</b>
	 * （界面会自动生成，所以出现它基本只可能是 curl / 外部系统调的）。
	 * 所以显式给一个 {@code present=false} 的标记，让界面能说清是哪种。
	 */
	private Map<String, Object> conversation(AgentRunRecord run) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("present", run.hasConversation());
		if (!run.hasConversation()) {
			body.put("id", "");
			body.put("runCount", 0);
			body.put("runs", List.of());
			body.put("note", "这次运行没有会话信息（调用方没有传）—— 所以它无法与同一段对话里的其它运行串起来。"
					+ "界面上发起任务是会自动带上会话 id 的，出现这一句通常说明这次运行是 curl 或外部系统直接调用的");
			return body;
		}
		List<AgentRunRecord> siblings = store.runsOfConversation(run.conversationId());
		body.put("id", run.conversationId());
		body.put("runCount", siblings.size());
		body.put("truncated", siblings.size() > MAX_CONVERSATION_RUNS);
		body.put("runs", siblings.stream().limit(MAX_CONVERSATION_RUNS).map(r -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", r.id());
			row.put("question", r.question());
			row.put("status", r.status().name());
			row.put("stopReason", r.stopReason() == null ? "" : r.stopReason().name());
			row.put("totalTokens", r.totalTokens());
			row.put("createdAt", r.createdAt() == null ? "" : r.createdAt().toString());
			// 【阶段四第 6 件】同一段对话里的运行也可能属于不同租户（同一段对话被
			// 两个租户共用时，这不是奇怪的场景）。**如实显示**，不假设一段对话只有一个租户 ——
			// 假设成立的话页面更整齐，但那个整齐是假的：它会让人以为"这段对话的成本"
			// 是一个租户的成本
			row.put("tenantId", r.tenantId() == null ? "" : r.tenantId());
			// 哪一次是"我正在看的这一次" —— 不给这个标记，界面上就分不清
			// "这段对话里这次是哪一次"（一段对话里所有运行的形状都一样）
			row.put("current", r.id().equals(run.id()));
			return row;
		}).toList());
		return body;
	}

	// ------------------------------------------------------------------
	//  第 3 / 4 / 5 层：按步骤对齐
	// ------------------------------------------------------------------

	/**
	 * 把每一步的三种记录对齐到一行里。
	 *
	 * <h2>为什么按步对齐，而不是把三张表分别列出来</h2>
	 * 因为"还原执行过程"问的是<b>顺序</b>：第 2 步先检索了一次（查到 5 条），
	 * 又读了一节，然后第 3 步才作答。三张表分开列时，这个顺序要靠人自己按
	 * {@code stepIndex} 在几张表之间来回对 —— 而一旦某一步有多个工具调用，
	 * 那件对账就变成了心算。
	 *
	 * <p>对齐是<b>纯读取</b>：只按 (step, call) 归拢已有记录，不重新查库、不计算判据。
	 */
	private List<Map<String, Object>> steps(List<AgentRunRecord.StepCheckpoint> steps,
			List<AgentRunRecord.ToolCallRecord> toolCalls, List<AgentRunRecord.RetrievalRecord> retrievals) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (AgentRunRecord.StepCheckpoint step : steps) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("stepIndex", step.stepIndex());
			row.put("thoughtText", step.thoughtText() == null ? "" : step.thoughtText());
			row.put("promptTokens", step.promptTokens());
			row.put("completionTokens", step.completionTokens());
			row.put("totalTokens", step.promptTokens() + step.completionTokens());
			row.put("cacheReadTokens", step.cacheReadTokens());
			row.put("modelName", step.modelName());
			row.put("elapsedMs", step.elapsedMs());
			// 【这一列是本件最想要的："这一步用的是哪一版提示词"】
			row.put("promptFingerprint", step.promptFingerprint());
			row.put("transcriptChars", step.transcriptJson() == null ? 0 : step.transcriptJson().length());

			List<Map<String, Object>> calls = new ArrayList<>();
			for (AgentRunRecord.ToolCallRecord call : toolCalls) {
				if (call.stepIndex() != step.stepIndex()) {
					continue;
				}
				Map<String, Object> callRow = new LinkedHashMap<>();
				callRow.put("callIndex", call.callIndex());
				callRow.put("callId", call.callId() == null ? "" : call.callId());
				callRow.put("toolName", call.toolName());
				callRow.put("arguments", call.arguments() == null ? "" : call.arguments());
				callRow.put("stage", call.stage());
				callRow.put("countsAsToolFailure", countsAsFailure(call.stage()));
				callRow.put("elapsedMs", call.elapsedMs());
				callRow.put("feedback", call.feedback() == null ? "" : call.feedback());
				// 这一步里的这次调用有没有检索明细（界面据此决定要不要画检索那一块）
				callRow.put("retrievals", retrievals.stream()
						.filter(r -> r.stepIndex() == call.stepIndex() && r.callIndex() == call.callIndex())
						.map(this::retrieval).toList());
				calls.add(callRow);
			}
			row.put("toolCalls", calls);

			List<Map<String, Object>> stepRetrievals = retrievals.stream()
					.filter(r -> r.stepIndex() == step.stepIndex()).map(this::retrieval).toList();
			row.put("retrievalCalls", stepRetrievals);
			row.put("retrievalCount", stepRetrievals.size());
			row.put("retrievalHitTokens", stepRetrievals.stream()
					.mapToInt(r -> ((Number) r.get("hitTokens")).intValue()).sum());
			rows.add(row);
		}
		return List.copyOf(rows);
	}

	/** 一次检索的完整明细（第 5 层）。 */
	private Map<String, Object> retrieval(AgentRunRecord.RetrievalRecord record) {
		RetrievalTrace trace = record.trace();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("stepIndex", record.stepIndex());
		body.put("callIndex", record.callIndex());
		body.put("toolName", record.toolName() == null ? "" : record.toolName());
		body.put("query", trace.query() == null ? "" : trace.query());
		body.put("topK", trace.topK());
		body.put("hitCount", trace.hitCount());
		body.put("indexSize", trace.indexSize());
		body.put("hitTokens", trace.totalHitTokens());
		body.put("elapsedMs", trace.elapsedMs());
		// 【召回不足：模型要的比拿到的多】
		// 它不是错误（语料里可能真的只有两条相关），但它是"语料不够"与
		// "检索没找着"分界线上最便宜的一个信号 —— 而两者要改的东西完全不同
		body.put("shortOfTopK", trace.hitCount() < trace.topK());
		body.put("hits", trace.hits().stream().map(hit -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("rank", hit.rank());
			row.put("chunkId", hit.chunkId() == null ? "" : hit.chunkId());
			row.put("documentId", hit.documentId() == null ? "" : hit.documentId());
			row.put("breadcrumb", hit.breadcrumb() == null ? "" : hit.breadcrumb());
			row.put("tokenCount", hit.tokenCount());
			row.put("score", hit.score());
			row.put("excerpt", hit.excerpt() == null ? "" : hit.excerpt());
			return row;
		}).toList());
		return body;
	}

	/** 整次运行的检索汇总 —— 回答"这次任务查了几次、一共看了多少字"。 */
	private Map<String, Object> retrievalSummary(List<AgentRunRecord.StepCheckpoint> steps,
			List<AgentRunRecord.RetrievalRecord> retrievals) {
		Map<String, Object> body = new LinkedHashMap<>();
		int hitTokens = retrievals.stream().mapToInt(r -> r.trace().totalHitTokens()).sum();
		long hitCount = retrievals.stream().mapToLong(r -> r.trace().hitCount()).sum();
		// "一条都没查到"的检索次数。它是最该被单独看见的一个数字：
		// 一次 0 命中的检索会让模型白跑一轮（那一轮的 token 照样花钱），
		// 而它混在"检索了 6 次"里完全看不出来
		long emptyCalls = retrievals.stream().filter(r -> r.trace().hitCount() == 0).count();
		body.put("calls", retrievals.size());
		body.put("hitCount", hitCount);
		body.put("hitTokens", hitTokens);
		body.put("hitTokensEstimated", true);
		body.put("emptyCalls", emptyCalls);
		body.put("steps", steps.size());
		// 命中块占整次运行输入 token 的比例。分母用的是**步骤口径的 promptTokens**
		// （含命中缓存），因为那正是模型真正读进去的量 ——
		// 用 totalTokens 会让比例失真的方向不明显地偏小
		int promptTokens = steps.stream().mapToInt(AgentRunRecord.StepCheckpoint::promptTokens).sum();
		body.put("promptTokensForShare", promptTokens);
		body.put("shareOfPrompt", promptTokens == 0 ? 0.0
				: Math.round((double) hitTokens * 10_000.0 / promptTokens) / 10_000.0);
		body.put("note", "hitTokens 是「估算」的（来自块自带的 TokenEstimator 计数），"
				+ "与成本看板那边『来自上游 usage』的 token 不是同一个口径 —— "
				+ "它用来回答『RAG 大概占了输入的多大一块』，不能当成账目。");
		return body;
	}

	// ------------------------------------------------------------------
	//  成本：算得出来才算
	// ------------------------------------------------------------------

	/**
	 * 这次运行的金额。
	 *
	 * <h2>为什么按模型分别算完再相加</h2>
	 * 一次运行只用一个模型是最常见的形态，但<b>不是唯一形态</b>：
	 * 换模型跑（{@code runWithId} 支持）与灰度期间都可能让同一次运行的
	 * 不同步骤落在不同模型上。把两步的 token 加总再乘任意一个单价都是错的，
	 * 而错出来的钱数看起来完全正常（只是偏大或偏小），不会报错。
	 * 所以口径与成本看板一致：<b>只在"单一模型"这个粒度上算钱，更大粒度靠相加</b>。
	 *
	 * <p>价格没配时返回 {@code null} + 原因，<b>不给 0</b>。
	 */
	private Map<String, Object> cost(List<AgentRunRecord.StepCheckpoint> steps) {
		// 模型 → 三档 token（顺序用 LinkedHashMap 固定，便于断言与肉眼比对）
		Map<String, long[]> byModel = new LinkedHashMap<>();
		for (AgentRunRecord.StepCheckpoint step : steps) {
			String model = step.modelName() == null ? "" : step.modelName();
			long[] acc = byModel.computeIfAbsent(model, k -> new long[3]);
			acc[0] += Math.max(0, step.promptTokens() - step.cacheReadTokens());
			acc[1] += step.cacheReadTokens();
			acc[2] += step.completionTokens();
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("pricingConfigured", pricing.configured());
		body.put("currency", pricing.defaultCurrency());
		long uncached = 0;
		long cached = 0;
		long completion = 0;
		for (long[] acc : byModel.values()) {
			uncached += acc[0];
			cached += acc[1];
			completion += acc[2];
		}
		body.put("uncachedPromptTokens", uncached);
		body.put("cachedPromptTokens", cached);
		body.put("completionTokens", completion);
		body.put("totalTokens", uncached + cached + completion);

		Map<String, Object> models = new LinkedHashMap<>();
		List<String> unpriced = new ArrayList<>();
		Long totalMicros = null;
		String currency = pricing.defaultCurrency();
		for (Map.Entry<String, long[]> entry : byModel.entrySet()) {
			String model = entry.getKey();
			long[] acc = entry.getValue();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("model", model);
			row.put("uncachedPromptTokens", acc[0]);
			row.put("cachedPromptTokens", acc[1]);
			row.put("completionTokens", acc[2]);
			// 【空模型名绝不用兜底价去算】
			// 上游没上报模型名时，价格规则里的 `*` 兜底会给它配一个价，
			// 于是页面上出现一个**按别的模型定价**的假数字。
			// 那正是成本看板最不能有的东西：一个看起来正常的错金额
			Optional<CostPricing.Cost> priced = model.isBlank() ? Optional.empty()
					: pricing.price(model, acc[0], acc[1], acc[2], Instant.now());
			if (priced.isPresent()) {
				CostPricing.Cost value = priced.get();
				row.put("amount", value.amount());
				row.put("amountMicros", value.micros());
				row.put("priceRule", value.ruleModel());
				row.put("priceSource", value.source() == null ? "" : value.source());
				row.put("amountReason", null);
				currency = value.currency();
				totalMicros = (totalMicros == null ? 0L : totalMicros) + value.micros();
			}
			else {
				row.put("amount", null);
				row.put("amountMicros", null);
				row.put("amountReason", model.isBlank()
						? "上游没有上报模型名 —— 没有模型名就没有单价可用（刻意不用兜底价猜，那会算出一个假数字）"
						: "没有匹配的价格规则（model=" + model + "）—— 在 ai.cost.prices 里补一条");
				if (acc[0] + acc[1] + acc[2] > 0) {
					unpriced.add(model.isBlank() ? "未上报模型名" : model);
				}
			}
			models.put(model, row);
		}

		body.put("byModel", models);
		body.put("unpricedModels", List.copyOf(unpriced));
		body.put("amount", totalMicros == null ? null : CostPricing.format(currency, totalMicros));
		body.put("amountMicros", totalMicros);
		body.put("amountReason", totalMicros != null ? null
				: !pricing.configured()
						? "价格未配置（ai.cost.prices 是空的）—— 这里刻意不显示 0："
								+ "0 会被读成『这次不花钱』，而真相是『我们不知道它花多少』"
						: "这些模型还没有价格：" + String.join("、", unpriced) + " —— 补上之后金额才会出现");
		return body;
	}

	// ------------------------------------------------------------------
	//  HITL 与五层清单
	// ------------------------------------------------------------------

	private List<Map<String, Object>> approvalsOf(String runId) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (var approval : approvals.ofRun(runId)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", approval.id());
			row.put("stepIndex", approval.stepIndex() == null ? -1 : approval.stepIndex());
			row.put("toolName", approval.toolName());
			// 【运行权限】这次为什么弹了确认（同一把工具在不同档位下结局不同）
			row.put("permissionNote", approval.permissionNote() == null ? "" : approval.permissionNote());
			row.put("status", approval.status().name());
			row.put("pending", approval.pending());
			row.put("decidedBy", approval.decidedBy() == null ? "" : approval.decidedBy());
			row.put("decisionNote", approval.decisionNote() == null ? "" : approval.decisionNote());
			row.put("waitedMs", approval.waitedMs(Instant.now()));
			rows.add(row);
		}
		return List.copyOf(rows);
	}

	/**
	 * 五层的<b>到场清单</b>：每一层有没有数据。
	 *
	 * <h2>为什么单独给一个"哪层是空的"清单</h2>
	 * 这是这一页最容易被误读的地方：一次<b>没调工具</b>的运行（模型直接回答）
	 * 在第 4、5 层上本来就是空的 —— 那是正常的。而一次<b>调了工具却查不到检索明细</b>
	 * 的运行，同样在第 5 层是空的，那说明<b>落库链路有问题</b>。
	 *
	 * <p>两者在页面上长得完全一样（都是"这一块没有内容"），而它们的含义正好相反。
	 * 所以让后端把判断说出来：{@code expected=false} 表示"这一层本来就不该有内容"。
	 * 这与阶段三那条"HITL 闸门开着没有必须显式给一个布尔"是同一个形状 ——
	 * 空列表本身从来不足以说明任何事。
	 */
	private List<Map<String, Object>> layers(List<AgentRunRecord.StepCheckpoint> steps,
			List<AgentRunRecord.ToolCallRecord> toolCalls, List<AgentRunRecord.RetrievalRecord> retrievals) {
		long retrievalToolCalls = toolCalls.stream()
				.filter(c -> ToolCallStage.OK.name().equals(c.stage()))
				.filter(c -> isRetrievalTool(c.toolName()))
				.count();
		List<Map<String, Object>> rows = new ArrayList<>();
		rows.add(layer(1, "会话", "conversation_id", "agent_run.conversation_id", true));
		rows.add(layer(2, "轮次 / 运行", "run", "agent_run", true));
		rows.add(layer(3, "LLM 调用", "step", "agent_step", !steps.isEmpty()));
		rows.add(layer(4, "工具调用", "toolCall", "agent_tool_invocation", !toolCalls.isEmpty()));
		// 第 5 层的"该不该有"判据是"有没有成功的检索类工具调用"，
		// 而不是"有没有工具调用" —— 一次只调了 read_section 的运行不该期待检索明细
		rows.add(layer(5, "检索", "retrieval", "retrieval_call + retrieval_hit",
				retrievalToolCalls > 0));
		return List.copyOf(rows);
	}

	private Map<String, Object> layer(int index, String name, String key, String storage, boolean expected) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("index", index);
		row.put("name", name);
		row.put("key", key);
		row.put("storage", storage);
		row.put("expected", expected);
		return row;
	}

	/**
	 * 这次工具名是不是"检索类"。
	 *
	 * <p>⚠️ 这里**必须**按名字判，而落库那一侧刻意不按名字判（它按"有没有带回明细"判）。
	 * 两者不是一个判断：
	 * <ul>
	 *   <li>落库：问"这次调用产生了明细吗"→ 答案是数据本身，不需要名单；</li>
	 *   <li>本方法：问"这次运行<b>应该</b>有检索明细吗"→ 那需要知道哪些工具是检索类的，
	 *       而这份名单只能在这里（判断"缺了什么"必须有预期）。</li>
	 * </ul>
	 * 名单很短且只有一处，所以"新加一把检索工具忘了登记"的表现是这一页少报一次异常
	 * （而不是丢数据）—— 失败的代价被限制在观测层。
	 */
	private static boolean isRetrievalTool(String toolName) {
		return "search_docs".equals(toolName);
	}

	/** 与成本看板同源的一条规则：stage → "算不算工具失败"只能有一份判据。 */
	private static boolean countsAsFailure(String stage) {
		try {
			return ToolCallStage.valueOf(stage).countsAsToolFailure();
		}
		catch (IllegalArgumentException e) {
			log.warn("库里有一条无法识别的工具调用 stage：{}", stage);
			return false;
		}
	}

	// ------------------------------------------------------------------
	//  边界说明
	// ------------------------------------------------------------------

	/**
	 * 这一页的边界与"哪些数是估算的"。
	 *
	 * <p>⚠️ 这些字符串里不许出现 markdown 星号（「纯文本里写 markdown 星号会原样显示」：界面是纯文本渲染，
	 * 星号会原样印出来）。强调一律用中文书名号。
	 */
	private List<String> notes(AgentRunRecord run, List<AgentRunRecord.StepCheckpoint> steps,
			List<AgentRunRecord.ToolCallRecord> toolCalls, List<AgentRunRecord.RetrievalRecord> retrievals) {
		List<String> notes = new ArrayList<>();
		notes.add("提示词指纹形如 v3:9ab12cd34ef5 —— 版本号是『指针』、指纹是『内容』。"
				+ "把它落成每一列之后，『某天的答案变差了是不是因为换了提示词』才追得下去。");

		// 【一次运行里出现了两个指纹 = 一次真实的不一致】
		List<String> fingerprints = steps.stream().map(AgentRunRecord.StepCheckpoint::promptFingerprint)
				.filter(f -> f != null && !f.isBlank()).distinct().toList();
		if (fingerprints.size() > 1) {
			notes.add("⚠️ 这次运行的步骤之间出现了 " + fingerprints.size() + " 个不同的提示词指纹（"
					+ String.join("、", fingerprints) + "）。"
					+ "热更新的粒度是『下一次运行』，所以同一次运行内「不该」换版 —— "
					+ "出现多个指纹说明有人改了提示词、而这次运行跨过了那个时点，"
					+ "或者恢复路径把指纹记错了。这会让『这次用的是哪一版』没有唯一答案。");
		}
		else if (fingerprints.isEmpty() && !steps.isEmpty()) {
			notes.add("⚠️ 这次运行没有记录提示词指纹 —— 它可能是这一列加上之前的老数据，"
					+ "也可能是提示词服务当时读不到当前版本（日志里有对应的 WARN）。"
					+ "两种情况的动作不同，所以不合并成一句话。");
		}

		if (toolCalls.isEmpty()) {
			notes.add("这次运行一次工具都没调（模型直接作答）—— 所以工具与检索那两层为空是「正常的」，"
					+ "不是漏记。");
		}
		if (!retrievals.isEmpty()) {
			notes.add("检索明细里的 token 是「估算」的（块自带的估算器计数），"
					+ "用来回答『RAG 大概占了输入多大一块』；账目口径的 token 在成本看板那边，"
					+ "那一个来自上游 usage。两个口径不要互相引用。");
		}
		if (!run.finished()) {
			notes.add("这次运行还没结束（status=" + run.status().name() + "）—— trace 还会继续增长。");
		}
		// 【阶段四第 6 件】两个归因维度各自缺失时要各自说清 ——
		// 它们**不是**同一件事：没有租户意味着"这次花费没有被算到任何一个租户头上"，
		// 而没有归因键意味着"不知道它属于哪个功能"。合并成一句"没有归因"会让
		// 看板上那两栏的空洞看起来是同一个问题
		if (!run.hasTenant()) {
			notes.add("这次运行没有租户信息（调用方没有传 tenantId）—— 所以它的花费在看板的"
					+ "「按租户」那一栏会落在『未标注租户』那一行，而「不会」被并进任何一个租户里。");
		}
		if (run.intent() == null || run.intent().isBlank()) {
			notes.add("这次运行没有业务归因键（调用方没有传 intent）—— 所以它的花费在看板的"
					+ "「按业务」那一栏会落在『未标注』那一行。它与『未标注租户』是两件事："
					+ "前者是不知道属于哪个功能，后者是不知道算谁的钱。");
		}
		// 【完全权限必须在这里说一句】它唯一的可见症状是"审批记录少了一条"，
		// 而"没有写操作"与"写操作被放宽了"在数据上完全一样。
		// 所以只要档位是完全权限，就明确写出"这次不会有审核请求"这个后果
		if (run.permissionLevel() == ai.agent.entity.agent.AgentPermissionLevel.FULL_ACCESS) {
			notes.add("⚠️ 这次运行是在「完全权限」档位下跑的：写操作**直接执行、不产生审核请求**。"
					+ "所以这次运行的审批记录为空是档位的后果，不是「没有写操作」的证据。"
					+ "工作区目录：" + (run.hasWorkspace() ? run.workspaceDir() : "(未记录)"));
		}
		if (!run.hasWorkspace()) {
			notes.add("这次运行没有工作区目录（调用方没有传 workspaceDir，或这一列加上之前就写入的运行）—— "
					+ "它按「仅可查看」处理：写操作会停下来等人确认。");
		}
		notes.add("库是 MySQL（ai.agent.store.url 指向 db_ai_agent）：运行记录跨重启保留，"
				+ "所以『历史运行里没有昨天那次』不是预期现象（默认只取最近若干条，见这一页的条数上限）。");
		return List.copyOf(notes);
	}

}
