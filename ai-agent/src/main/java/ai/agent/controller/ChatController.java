package ai.agent.controller;

import ai.agent.entity.dto.chat.ChatRequest;
import ai.agent.service.support.chat.ChatRetryPolicy;
import ai.agent.config.ChatCallProperties;
import ai.agent.service.chat.ChatService;
import ai.agent.service.rag.kb.KnowledgeBaseContextInjector;
import ai.agent.service.token.TokenBudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话端点（SSE 流式 + 非流式对照）。
 *
 * <p>分层约定：Controller 只做「协议层」的事——收参数、把服务返回的流翻译成 SSE 事件、
 * 兜住异常；模型调用与响应解析都在 {@link ChatService} 背后。因此 Controller 只依赖
 * 服务<b>接口</b>（{@code ai.agent.service.chat}），不认识 {@code ChatServiceImpl}。
 *
 * <p><b>SSE 事件契约</b>（前端按此解析，GET / POST 两条通路完全一致）：
 * <ul>
 *   <li>{@code event: content} —— data 为 JSON：{@code {"delta":"..."}}（增量文本）</li>
 *   <li>{@code event: done}    —— data 为 JSON：{@code promptTokens / completionTokens / totalTokens / model}</li>
 *   <li>{@code event: error}   —— data 为 JSON：{@code {"message": "..."}}（不暴露堆栈）</li>
 * </ul>
 *
 * <p><b>为什么 content 也要包一层 JSON，而不是直接发纯文本</b>（这是实测出来的坑，不是洁癖）：
 * Spring 的 {@code ServerSentEventHttpMessageWriter} 写出的是 {@code data:xxx}，
 * <b>冒号后没有分隔空格</b>；而 SSE 规范要求客户端「若值以一个空格开头，吃掉那一个空格」。
 * 两条规则一撞，任何<b>以空格开头</b>的增量分片都会丢一个空格 ——
 * 实测模型输出会渲染成 {@code publicFlux}（正确应是 {@code public Flux}）。
 * 包成 JSON 后 data 以 <code>{</code> 开头，空格在 JSON 字符串里被引号保护，
 * 无论对端是「带分隔空格」还是「不带分隔空格」的写法都不会再被吃掉；
 * 顺带把换行也交给 JSON 转义，不必依赖服务端的多行 data 拆分行为。
 * 实测依据是本项目的实测数据。
 *
 * <p><b>两条通路怎么选</b>：
 * <ul>
 *   <li>{@code GET /api/chat/stream} —— 保留给浏览器原生 {@code EventSource} 与 curl 调试；
 *       注意原生 EventSource 只支持 GET，也就意味着 message 只能走 URL。</li>
 *   <li>{@code POST /api/chat/stream} —— <b>推荐</b>。message 放请求体，避免 URL 长度上限与
 *       明文进日志；代价是前端不能用 EventSource，得用 {@code fetch} + {@code ReadableStream}
 *       手动切 SSE 帧（见 {@code web/src/api/sse.ts}）。</li>
 * </ul>
 *
 * <p>实现依据（官方 ChatClient 文档 "Streaming Responses" + "Implementation Notes"）：
 * <ul>
 *   <li>{@code stream()} 返回 {@code Flux<String> content()} 或
 *       {@code Flux<ChatResponse> chatResponse()} 或 {@code Flux<ChatClientResponse> chatClientResponse()}。</li>
 *   <li>流式只能跑在 <b>Reactive 栈</b>上；非流式只能跑在 Servlet 栈上。因此两种栈都要在 classpath 中。</li>
 * </ul>
 *
 * <p><b>超时与重试（本类承担，不在 Service 里做）</b>
 * <ul>
 *   <li>两条超时：<b>首字超时</b>（等第一个增量最多多久）与<b>空闲超时</b>
 *       （相邻两个增量之间最多间隔多久）。用"多久没动静"而不是"总共花了多久"，
 *       是因为流式回答的正常耗时本来就长，用总耗时会杀掉正常但话多的回答。</li>
 *   <li>重试只发生在<b>首个增量到达之前</b>。理由见 {@link #withRetryAndTimeout} 的注释 ——
 *       已经吐了半句再重发，用户会看到两段拼接的回答。</li>
 *   <li>超时/重试的<b>数字全部来自 {@link ChatCallProperties}</b>，排障时不用改代码。
 *       重试"该不该退"的判定逻辑在 {@link ChatRetryPolicy}（纯函数，可单测）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	private final ChatService chatService;

	private final ChatRetryPolicy retryPolicy;

	private final ChatCallProperties properties;

	/**
	 * 对话记忆的读取口 —— 重试前摘掉孤儿用户消息时要用它（见 {@link #dropOrphanedUserMessage}）。
	 *
	 * <p><b>它背后是内存还是数据库，由 {@code ChatClientConfig#chatMemoryRepository()} 那一个方法决定</b>
	 * （默认是内存实现：重启即丢、多实例不共享）。这里只依赖接口，所以换存储不影响本类。
	 */
	private final ChatMemoryRepository chatMemoryRepository;

	private final TokenBudgetGuard budgetGuard;

	/** 知识库材料注入（勾了知识库时检索一次，拼进系统提示词并作为第一帧发给前端）。 */
	private final ai.agent.service.rag.kb.KnowledgeBaseContextInjector knowledgeBaseInjector;

	/** 只用来把材料块折算成 token（预算要把它算进去）。 */
	private final ai.agent.service.token.TokenEstimator tokenEstimator;

	public ChatController(ChatService chatService, ChatRetryPolicy retryPolicy, ChatCallProperties properties,
			ChatMemoryRepository chatMemoryRepository, TokenBudgetGuard budgetGuard,
			ai.agent.service.rag.kb.KnowledgeBaseContextInjector knowledgeBaseInjector,
			ai.agent.service.token.TokenEstimator tokenEstimator) {
		this.chatService = chatService;
		this.retryPolicy = retryPolicy;
		this.properties = properties;
		this.chatMemoryRepository = chatMemoryRepository;
		this.budgetGuard = budgetGuard;
		this.knowledgeBaseInjector = knowledgeBaseInjector;
		this.tokenEstimator = tokenEstimator;
	}

	/**
	 * 流式对话（<b>推荐</b>：请求体传参）。
	 *
	 * <p>示例：
	 * <pre>
	 * curl -N -X POST http://localhost:8080/api/chat/stream \
	 *   -H 'Content-Type: application/json' \
	 *   -d '{"conversationId":"c1","message":"你好","temperature":0.3}'
	 * </pre>
	 */
	@PostMapping(
			value = "/stream",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Object>> streamByPost(@RequestBody ChatRequest request) {
		return sseStream(request.conversationId(), request.message(), request.temperatureOrDefault(),
				request.knowledgeBaseIds());
	}

	/**
	 * 流式对话（兼容通路，便于原生 EventSource / 浏览器地址栏直接测）。
	 *
	 * <p>示例：{@code curl -N "http://localhost:8080/api/chat/stream?conversationId=c1&message=你好"}
	 */
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Object>> streamByGet(
			@RequestParam String conversationId,
			@RequestParam String message,
			@RequestParam(defaultValue = "0.3") double temperature,
			@RequestParam(name = "kbIds", required = false) List<Long> knowledgeBaseIds) {
		return sseStream(conversationId, message, temperature, knowledgeBaseIds);
	}

	/**
	 * 把服务层的 {@code Flux<ChatClientResponse>} 翻译成 SSE 事件流。
	 *
	 * <p>GET / POST 共用这一份逻辑：事件组装规则只写一遍，避免两条通路行为漂移。
	 */
	private Flux<ServerSentEvent<Object>> sseStream(String conversationId, String message, double temperature,
			List<Long> knowledgeBaseIds) {
		requireText(conversationId, "conversationId");
		requireText(message, "message");

		// 【知识库材料】勾了知识库就在这里检索一次；结果既拼进系统提示词，也作为第一帧发给前端。
		//
		// 顺序上它必须排在预算校验**之前**：那段材料会真的进请求，
		// 不先算出来，预算就是按"不含材料的提示词"算的 —— 而那笔差额正好是这次新增的。
		Optional<KnowledgeBaseContextInjector.Injection> injection = knowledgeBaseInjector.inject(message,
				knowledgeBaseIds);
		int extraSystemPromptTokens = injection
			.map(value -> tokenEstimator.estimate(value.block()))
			.orElse(0);
		// 拼进系统提示词的那段文本；没勾知识库时为 null（那时提示词与以前逐字相同）
		String knowledgeBaseBlock = injection.map(KnowledgeBaseContextInjector.Injection::block).orElse(null);

		// Token 预算校验（学习点 2）：必须在流开始之前做。
		//
		// 为什么不能等流跑到一半再说：SSE 的响应头一旦发出就收不回来，
		// 超预算只能表现为"流中途报错"——用户已经看到半句话了。
		// 放在这里则是一个普通的 400 响应，前端能给出准确提示（差多少、该怎么办）。
		// 顺带一个好处：这一步在 Controller 的构造阶段之外、在返回 Flux 之前执行，
		// 所以它是"发请求之前"的本地计算，不花任何模型调用成本。
		TokenBudgetGuard.BudgetCheck budget = budgetGuard.prepare(conversationId, message, extraSystemPromptTokens);
		if (budget.enabled()) {
			if (budget.compacted()) {
				log.warn("上下文超预算 → 压缩掉最旧的 {} 条历史（累计 {} 轮已进摘要，这次发出 {} 条）"
						+ "conversationId={} 需要={} 可用={}",
						budget.compactedMessages(), budget.coveredTurns(), budget.promptMessages(), conversationId,
						budget.neededTokens(), budget.allowedTokens());
			}
			else {
				log.info("预算账目 conversationId={} 需要={} 可用={} 历史条数={} 系统提示词={}",
						conversationId, budget.neededTokens(), budget.allowedTokens(),
						budget.historyMessages(), budget.systemPromptTokens());
			}
			if (budget.fallbackNudge()) {
				// 【必须留这一行】它是"这次压缩没成功"的唯一痕迹。
				// 不留的话，表现只是"模型对很早的事答得含糊" —— 而那看起来像模型能力问题
				log.warn("这次历史没能压进摘要（{}）conversationId={} 历史条数={} 需要={} 可用={}",
						budget.needsModelNudge(), conversationId, budget.historyMessages(),
						budget.neededTokens(), budget.allowedTokens());
			}
		}

		// 用于在流结束时读取最后一个非空的 token 用量
		AtomicReference<Map<String, Object>> lastUsage = new AtomicReference<>(Map.of());

		// 记录"这次对话有没有等到过首字"，只用于把失败原因说清楚（见 safeMessage）
		AtomicBoolean sawFirstToken = new AtomicBoolean(false);

		// 【第一帧：知识库召回明细】排在 content 之前发 ——
		// 前端要在正文开始之前就能显示"这次带上了哪几条材料"，而 content 帧一开始推，
		// 这一帧就没有位置了（第一条 content 之后到达的明细看起来像"中途插播"）。
		// 不勾知识库时这一帧不发：没有任何明细可报，发一个空帧只会让前端多一个分支
		Flux<ServerSentEvent<Object>> kbFrame = injection
			.map(value -> Flux.just(ServerSentEvent.builder()
				.event("kb")
				.data((Object) knowledgeBaseFrame(value))
				.build()))
			.orElseGet(Flux::empty);

		// 正文那一条流（首帧之外的其余帧）单独成形：知识库帧要在它前面，而它内部的
		// mapNotNull/done/error 三段只认 ChatClientResponse —— 把知识库帧混进这条链里，
		// 那三段就会拿到 ServerSentEvent（编译不过；就算强转过去，deltaOf 也读不出东西）
		Flux<ServerSentEvent<Object>> contentStream = withRetryAndTimeout(conversationId, message, temperature,
				sawFirstToken, knowledgeBaseBlock)
				.doOnNext(r -> lastUsage.set(chatService.usageOf(r)))
				.mapNotNull(r -> {
					String delta = chatService.deltaOf(r);
					if (!chatService.hasDelta(delta)) {
						return null; // 空 chunk 不推给前端
					}
					// 以"真正推出了正文"为准，而不是"模型吐了任意一个 chunk"：
					// 有些模型会先发一个只含用量/元数据的 chunk，那种情况下用户屏幕上还是空的，
					// 报"回答中断、内容不完整"会让用户去找根本不存在的半截回答
					sawFirstToken.set(true);
					return ServerSentEvent.builder()
							.event("content")
							.data((Object) Map.of("delta", delta))
							.build();
				})
				.concatWith(Flux.defer(() -> Flux.just(ServerSentEvent.builder()
						.event("done")
						.data((Object) lastUsage.get())
						.build())))
				.onErrorResume(e -> {
					chatService.logError(conversationId, e, isExpectedFailure(e));
					return Flux.just(ServerSentEvent.builder()
							.event("error")
							.data((Object) Map.of("message", safeMessage(e, sawFirstToken.get())))
							.build());
				});
		return kbFrame.concatWith(contentStream);
	}

	/**
	 * 知识库那一帧的内容（给前端看，不含给模型看的那段文本）。
	 *
	 * <p>它同时带着"重排到底生效没有"：重排失败会静默退回向量顺序，
	 * 而人以为排过序了 —— 所以 {@code reranked} 与 {@code rerankSkipReason} 必须跟着明细一起发。
	 */
	private Map<String, Object> knowledgeBaseFrame(KnowledgeBaseContextInjector.Injection injection) {
		var result = injection.result();
		Map<String, Object> body = new LinkedHashMap<>();
		if (result == null) {
			// 检索本身失败：给前端的说法与给模型的说法一致（这次没有材料，且原因是失败而不是"没有内容"）
			body.put("failed", true);
			body.put("note", "知识库检索失败，本次回答没有带知识库材料");
			return body;
		}
		List<Map<String, Object>> hits = new ArrayList<>();
		for (var hit : result.hits()) {
			Map<String, Object> cell = new LinkedHashMap<>();
			cell.put("rank", hit.rank());
			cell.put("kbId", hit.kbId());
			cell.put("kbName", hit.kbName());
			cell.put("documentId", hit.documentId());
			cell.put("documentName", hit.documentName());
			cell.put("headingPath", hit.headingPath());
			cell.put("vectorScore", hit.vectorScore());
			cell.put("rerankScore", hit.rerankScore());
			hits.add(cell);
		}
		body.put("hits", hits);
		body.put("hitCount", hits.size());
		body.put("indexSize", result.indexSize());
		body.put("reranked", result.reranked());
		body.put("rerankSkipReason", result.rerankSkipReason());
		body.put("notes", result.notes());
		return body;
	}

	/**
	 * 这个失败是不是"计划内"的。
	 *
	 * <p>用途是决定日志级别。超时和 4xx/5xx 属于<b>正常业务预期</b>：模型供应商会限流、
	 * 网络会抖，这些都是运行时的常态，用 ERROR 记录会让日志监控里堆满"看起来像系统故障"
	 * 的噪声（真出事时反而淹没在噪声里）。真正的意外（空指针、状态异常）才该是 ERROR。
	 */
	private boolean isExpectedFailure(Throwable e) {
		Throwable cause = ChatRetryPolicy.rootCause(e);
		return cause instanceof TimeoutException || cause instanceof RestClientResponseException;
	}

	/**
	 * 给模型调用套上<b>首字超时 + 空闲超时 + 首字前重试</b>。
	 *
	 * <p><b>为什么重试必须限定在"首字之前"</b>（这是本次实现的核心决策）：
	 * SSE 的响应头一旦发出就无法撤回。若模型已经吐了半句再失败，重发请求会导致
	 * <ol>
	 *   <li>用户看到两段拼接的回答（前端已经在气泡里拼过第一段了）；</li>
	 *   <li>对话记忆里留下半截 assistant 消息，下一轮模型会基于这段残缺上下文继续，行为变得难以解释。</li>
	 * </ol>
	 * 所以这里的规则很硬：<b>没吐过字就整体重来，吐过字就认账并明确报错</b>。
	 * 用户看到"回答到一半断了"远比看到"两段矛盾的回答"好排查 —— 前者是故障，后者像灵异事件。
	 *
	 * <p><b>重试的代价与边界</b>：
	 * <ul>
	 *   <li>失败尝试不会在对话记忆里留下"半截回答"：{@code MessageChatMemoryAdvisor} 只在流
	 *       <b>正常完成</b>时才把回答写进记忆（{@code ChatClientMessageAggregator} 的
	 *       onComplete 回调），超时走的是取消路径，所以不会有半个 assistant 消息进上下文。</li>
	 *   <li>但失败尝试<b>会</b>留下一条"没有回答的用户消息"——它在发起调用前就写好了。
	 *       所以每次重试前必须把它摘掉，见 {@link #dropOrphanedUserMessage}。</li>
	 *   <li>退避等待期间用户看不到任何东西，所以退避时长必须小
	 *       （默认 0.5s/1s，上限 5s）—— 重试是"悄悄补救"，不是让用户干等。</li>
	 * </ul>
	 *
	 * <p><b>超时算子的一个坑（实测踩到，不是推演出来的）</b>：
	 * 直觉上"一个 {@code timeout(Duration)} 配合 switchOnFirst 就能同时覆盖首字等待和空闲检测"，
	 * 但 {@code timeout} 的计时器<b>只在第一个信号到达后才开始工作</b>，
	 * 所以 {@code flux.timeout(idle)} 这种写法对"一个信号都没有"的挂起状态完全无效 ——
	 * 实测表现是测试直接挂到测试框架的 5 秒上限，而配置的超时（150ms）根本没触发。
	 * 正确做法是 {@code switchOnFirst} 的两个分支都必须带上超时：
	 * 无首字的分支（含空信号 / 错误信号 / 什么都还没来）用 firstToken 兜住，
	 * 有首字的分支才换成 idle。这样"模型一直不响应"和"回答到一半不动了"才都有上限。
	 */
	private Flux<ChatClientResponse> withRetryAndTimeout(String conversationId, String message, double temperature,
			AtomicBoolean sawFirstToken, String knowledgeBaseBlock) {
		ChatCallProperties.Retry retryCfg = properties.getRetry();

		// 每个订阅（= 每次尝试）独立计数：用局部变量，天然随重订阅重置
		AtomicInteger attempt = new AtomicInteger(0);

		// 是否已经产出过真实增量 —— 重试的闸门。
		// 跨订阅共享是刻意的：只要历史上有过一次增量，就永远不许再重发。
		AtomicBoolean hasContent = new AtomicBoolean(false);

		return Flux.defer(() -> {
					int n = attempt.incrementAndGet();
					long startedAt = System.nanoTime();
					log.info("模型调用开始 conversationId={} attempt={}/{}", conversationId, n, retryCfg.getMaxAttempts() + 1);

					return chatService.stream(conversationId, message, temperature, knowledgeBaseBlock)
							// 首个 chunk 到达 → 关闭重试闸门。
							// 注意这里用"任意 chunk"而不是"有正文"：一旦模型的流已经开始产出，
							// 重发请求就会让模型侧上下文错乱，所以闸门要尽早关上；
							// 而给用户看的文案用的是另一套判断（真的推出正文才算"收到内容"）
							.doOnNext(r -> {
								if (hasContent.compareAndSet(false, true)) {
									log.debug("首个 chunk 已到达 conversationId={} attempt={} elapsedMs={}",
											conversationId, n, (System.nanoTime() - startedAt) / 1_000_000);
								}
							})
							// 超时组合（这里有三个坑，都是实测撞出来的，别凭直觉改）：
							//
							// 坑 1：超时不能只放在 switchOnFirst 的分支里。分支只在"源发出第一个信号"
							//   时才执行，而我们要防的正是"源什么都不发"（模型挂起）—— 那种情况下
							//   超时永远不触发，页面永远停在「正在思考…」。
							//   实测：配了 100ms 超时，流却一直挂到测试框架的 5 秒上限。
							//
							// 坑 2：分支里不能把首字再拼回去。switchOnFirst 传给转换函数的第二个参数
							//   <b>已经包含首元素</b>，写成 Flux.concat(Flux.just(signal.get()), flux)
							//   会把第一个增量发两遍（实测输出"第一段第一段"）。
							//
							// 坑 3：两个阈值各管一段，别合并成一个。曾经把"两者较小值"传给单个
							//   timeout，结果是 idle 配小时连带把首字等待也掐短（idle=1ms 时
							//   10s 的首字超时形同虚设）。现在的写法：源上 firstToken 只管
							//   "等第一个信号"，进分支后 idle 只管"等下一个信号"。
							//   真实模型侧用 idle=400ms 实测过
							//   一次 5.5 秒的完整回答（首字约 1.6s）不再被误判
							.timeout(properties.getTimeout().getFirstToken())
							.switchOnFirst((signal, flux) -> signal.hasValue()
									? flux.timeout(properties.getTimeout().getIdle())
									: flux);
				})
				.retryWhen(Retry.backoff(retryCfg.getMaxAttempts(), retryCfg.getInitialBackoff())
						// 退避参数与 ChatRetryPolicy.delayForAttempt 是同一套语义
						//（首间隔 × 倍数^n，上限截断，再叠抖动），配置只有一个来源：
						// ai.chat.retry.*。改配置时策略与算子会一起变，不会漂移。
						.maxBackoff(retryCfg.getMaxBackoff())
						.jitter(retryCfg.getJitter())
						// 双重闸门：① 已经吐过字就不许重试（见方法注释）
						//           ② 只有"等一会儿可能成功"的失败才值得重试
						.filter(error -> !hasContent.get() && retryPolicy.isRetryable(error))
						.doBeforeRetry(signal -> {
							log.warn("模型调用失败，准备第 {} 次重试（共 {} 次）conversationId={} reason={}",
									signal.totalRetries() + 1, retryCfg.getMaxAttempts(), conversationId,
									ChatRetryPolicy.rootCause(signal.failure()).toString());
							dropOrphanedUserMessage(conversationId, message);
						}))
				// 重试耗尽后把真实原因抬出来：Retry.backoff 默认抛 RetryExhaustedException，
				// 它自己的 message 信息量极少（"Retries exhausted: N/N"），
				// 而我们要让日志和前端看到"到底是 503、超时还是 401" —— 这才有排障价值
				.onErrorMap(Throwable.class, ChatController::unwrapExhausted);
	}

	/**
	 * 拆掉 Reactor 的重试包装，露出真实失败原因。
	 *
	 * <p>要处理两层包装，缺一层都看不出真因：
	 * <ol>
	 *   <li><b>重试耗尽</b> —— {@code retryWhen} 用完次数后自己包的一层，
	 *       它自己的 message 是 {@code "Retries exhausted: N/N"}，
	 *       不含"到底是 503、超时还是 401"，对排障毫无价值；</li>
	 *   <li><b>受检异常外壳</b> —— Reactor 在 {@code Flux} 里传递受检异常
	 *       （如 {@code java.util.concurrent.TimeoutException}）时套的壳，
	 *       真实原因藏在它的 cause 里。</li>
	 * </ol>
	 *
	 * <p>为什么用 {@link Exceptions#isRetryExhausted} / {@link Exceptions#unwrap}
	 * 而不是 {@code instanceof} 自己判：Reactor 这两个异常类是<b>包级私有</b>的
	 * （{@code final class Exceptions$RetryExhaustedException}，没有 public 修饰符），
	 * 业务代码 import 不到，编译期就会报"不是公共的，无法从外部程序包中对其进行访问"。
	 * Reactor 因此专门提供了这两个 public 静态方法作为判断入口 —— 这是它留给外部的唯一正门。
	 *
	 * <p>两层都不匹配时原样返回，绝不吞异常。
	 */
	private static Throwable unwrapExhausted(Throwable error) {
		if (Exceptions.isRetryExhausted(error) && error.getCause() != null) {
			return error.getCause();
		}
		// unwrap 会剥掉 ReactiveException 外壳；对没有外壳的异常原样返回，是安全的空操作
		return Exceptions.unwrap(error);
	}

	/**
	 * 重试前把上一次失败尝试留在对话记忆里的孤儿用户消息摘掉。
	 *
	 * <p><b>为什么需要这一步（实测发现，不是推理出来的）</b>：
	 * {@code MessageChatMemoryAdvisor} 在流式链路里的写记忆时机是两段式的——
	 * <ul>
	 *   <li><b>发起调用之前</b>：把本轮用户消息写进记忆（{@code before()} 阶段）；</li>
	 *   <li><b>流正常结束之后</b>：把聚合好的整段回答写进记忆
	 *       （{@code ChatClientMessageAggregator} 只在 onComplete 时回调）。</li>
	 * </ul>
	 * 于是"请求已经发出去、但第一个增量迟迟不来"这种超时，会在记忆里留下一条<b>没有回答的
	 * 用户消息</b>（孤儿消息）；重试再走一遍同样的 advisor 链，就再写一条。实测日志
	 * （首字超时 1ms、重试 2 次）里第 3 次尝试的 prompt 是：
	 * <pre>
	 * UserMessage{'你好'}, UserMessage{'你好'}, UserMessage{'你好'}
	 * </pre>
	 * 白烧 token 之外，还会让模型看到一个把同一句话问了三遍的奇怪对话。
	 *
	 * <p><b>清理时机是"每次重试前"，而不是"发现重复时才清"</b>（这里踩过一次坑）：
	 * 一开始写的是"只有末尾两条内容相同才删"。结果第一次失败后记忆里只有一条"你好"、
	 * 判不出重复，于是不清理；等第 2 次尝试再写一条，重复才出现，而那时已经没有下一次
	 * 重试来触发清理了 —— 重复项就永久留在了上下文里。正确的做法是：
	 * <b>失败的那次尝试留下的用户消息本身就是垃圾，重试前一律摘掉</b>，
	 * 由下一次尝试重新写入，这样每次尝试看到的上下文都是干净的。
	 *
	 * <p><b>为什么用 {@code saveAll} 而不是 {@code chatMemory.add(...)}</b>（第二个坑）：
	 * {@code ChatMemory.add()} 是<b>追加合并</b>语义 —— 内部先
	 * {@code findByConversationId()} 读出旧消息、和新消息合并后再存回。
	 * 拿"裁剪后的列表"去调 {@code add()}，等于把裁剪前的历史又加了一遍：
	 * 实测"清理前 1 条、清理后 2 条"，越清越多。真正"整段替换"的入口只有仓储层的
	 * {@link ChatMemoryRepository#saveAll}。
	 *
	 * <p>清理失败只记警告、不影响重试：让 prompt 稍微变长，远好过放弃一次重试机会。
	 */
	private void dropOrphanedUserMessage(String conversationId, String message) {
		try {
			List<Message> history = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));

			// 只处理"末尾恰好是本次提问的那条用户消息"这一种确定无害的情形：
			// 内容对不上就说明记忆里是别的轮次，动它会破坏正常上下文。宁可少清，也不误删。
			if (history.isEmpty()) {
				return;
			}
			Message last = history.get(history.size() - 1);
			if (last.getMessageType() != MessageType.USER || !message.equals(last.getText())) {
				return;
			}

			history.remove(history.size() - 1);
			// 整段替换（saveAll），不是追加：ChatMemory.add 会把旧消息重新合并一遍，删不掉东西
			chatMemoryRepository.saveAll(conversationId, history);
			log.info("重试前摘掉本次失败尝试留下的孤儿用户消息 conversationId={} remaining={}",
					conversationId, history.size());
		} catch (RuntimeException e) {
			// 记忆清理是"锦上添花"，绝不能因为它失败而让本该发生的重试流产
			log.warn("重试前清理对话记忆失败（不影响重试）conversationId={} cause={}", conversationId, e.toString());
		}
	}

	/**
	 * 非流式对话（对照用，跑在 Servlet 栈上）。返回纯文本。
	 * 示例：{@code curl "http://localhost:8080/api/chat?conversationId=c1&message=你好"}
	 */
	@GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
	public String call(@RequestParam String conversationId, @RequestParam String message) {
		return chatService.call(conversationId, message);
	}

	/** 心跳/连通性检查，便于确认 SSE 通路与配置是否生效。 */
	@GetMapping(value = "/ping", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> ping() {
		return Flux.interval(Duration.ZERO, Duration.ofSeconds(2))
				.take(3)
				.map(i -> ServerSentEvent.builder("ping-" + i).event("content").build());
	}

	/**
	 * 参数校验：不合法就直接 400，不要进入流式阶段。
	 *
	 * <p>为什么不用 {@code @Valid} + Bean Validation：{@code spring-boot-starter-web} 并不带
	 * validation 实现，引一个 starter 只为两个非空判断不划算。等参数变多再换。
	 */
	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必填");
		}
	}

	/**
	 * 把异常翻译成"用户能看懂、且不泄露内部信息"的一句话。
	 *
	 * <p>超时是这里最需要翻译准的一类：{@code Flux.timeout(...)} 抛出的
	 * {@code TimeoutException} 默认 message 是 {@code null}，直接用会退化成一个干巴巴的类名。
	 * 而"卡在哪一段"决定了用户下一步该做什么，所以必须分开说：
	 * <ul>
	 *   <li>已经收到正文 → 「回答中断」，而且<b>不能再重试</b>（重发会让页面上出现两段
	 *       拼接的回答），所以文案里不能写"已重试"，只能说明已收到的内容不完整；</li>
	 *   <li>一个字都没收到 → 「没有返回任何内容」，这时重试是有效的，文案里带上重试次数。</li>
	 * </ul>
	 * 判断依据是 {@code sawFirstToken}，含义是"<b>真正推出了正文</b>"，
	 * 而不是"模型吐了任意一个 chunk"（后者可能只是用量元数据，用户屏幕上还是空的）。
	 */
	private String safeMessage(Throwable e, boolean sawFirstToken) {
		Throwable cause = ChatRetryPolicy.rootCause(e);

		if (cause instanceof TimeoutException) {
			ChatCallProperties.Timeout t = properties.getTimeout();
			if (sawFirstToken) {
				return "回答中断：模型超过 %s 没有继续输出（空闲超时），已收到的内容不完整。"
						.formatted(humanDuration(t.getIdle()));
			}
			return "模型超过 %s 没有返回任何内容（首字超时），已重试 %d 次仍失败，请稍后重试。"
					.formatted(humanDuration(t.getFirstToken()), properties.getRetry().getMaxAttempts());
		}
		if (cause instanceof RestClientResponseException httpError) {
			// 上游返回的 body 可能带内部信息，但"状态码 + 前 200 字"对排障价值远大于泄露风险
			// （模型供应商的 4xx/5xx body 里就是错误原因本身，不是我们的内部实现）
			String body = httpError.getResponseBodyAsString();
			String tail = body == null || body.isBlank() ? "" : " body=" + truncate(body, 200);
			return "模型接口返回 HTTP %d%s".formatted(httpError.getStatusCode().value(), tail);
		}

		String m = cause.getMessage();
		if (m == null || m.isBlank()) {
			return cause.getClass().getSimpleName();
		}
		return truncate(m, 300);
	}

	private static String truncate(String text, int max) {
		return text.length() > max ? text.substring(0, max) + "..." : text;
	}

	/**
	 * 把 {@link Duration} 转成人话。
	 *
	 * <p>为什么不直接拼 {@code Duration}：它的 {@code toString()} 是 ISO-8601 格式，
	 * 实测会把用户看到的报错变成「模型超过 <b>PT0.001S</b> 没有返回任何内容」——
	 * 这行字对使用者毫无意义（对人来说那不是"1 毫秒"）。配置项允许多种写法
	 * （{@code 20s} / {@code 500ms}），所以这里统一按毫秒判断，
	 * 不足 1 秒的按毫秒说、够 1 秒的按秒说。
	 */
	private static String humanDuration(Duration duration) {
		if (duration == null) {
			return "未知时长";
		}
		long ms = duration.toMillis();
		if (ms < 1000) {
			// 不写 "1 毫秒"：和后面的中文连读会变成「超过 1 毫秒 没有返回」，多个空格很别扭
			return ms + "毫秒";
		}
		// 整秒就别显示 .0；否则保留一位小数（1.5 秒这种配置也能如实表达）
		if (ms % 1000 == 0) {
			return (ms / 1000) + " 秒";
		}
		return "%.1f 秒".formatted(ms / 1000.0);
	}

}
