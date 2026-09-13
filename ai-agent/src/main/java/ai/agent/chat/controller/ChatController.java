package ai.agent.chat.controller;

import ai.agent.chat.dto.ChatRequest;
import ai.agent.chat.service.ChatService;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话端点（SSE 流式 + 非流式对照）。
 *
 * <p>分层约定：Controller 只做「协议层」的事——收参数、把服务返回的流翻译成 SSE 事件、
 * 兜住异常；模型调用与响应解析都在 {@link ChatService} 背后。因此 Controller 只依赖
 * 服务<b>接口</b>（{@code ai.agent.chat.service}），不认识 {@code ChatServiceImpl}。
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
 * 实测依据见根目录「学习进度台账.md」的踩坑知识库。
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
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final ChatService chatService;

	public ChatController(ChatService chatService) {
		this.chatService = chatService;
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
		return sseStream(request.conversationId(), request.message(), request.temperatureOrDefault());
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
			@RequestParam(defaultValue = "0.3") double temperature) {
		return sseStream(conversationId, message, temperature);
	}

	/**
	 * 把服务层的 {@code Flux<ChatClientResponse>} 翻译成 SSE 事件流。
	 *
	 * <p>GET / POST 共用这一份逻辑：事件组装规则只写一遍，避免两条通路行为漂移。
	 */
	private Flux<ServerSentEvent<Object>> sseStream(String conversationId, String message, double temperature) {
		requireText(conversationId, "conversationId");
		requireText(message, "message");

		// 用于在流结束时读取最后一个非空的 token 用量
		AtomicReference<Map<String, Object>> lastUsage = new AtomicReference<>(Map.of());

		return chatService.stream(conversationId, message, temperature)
				.doOnNext(r -> lastUsage.set(chatService.usageOf(r)))
				.mapNotNull(r -> {
					String delta = chatService.deltaOf(r);
					if (!chatService.hasDelta(delta)) {
						return null; // 空 chunk 不推给前端
					}
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
					chatService.logError(conversationId, e);
					return Flux.just(ServerSentEvent.builder()
							.event("error")
							.data((Object) Map.of("message", safeMessage(e)))
							.build());
				});
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

	private static String safeMessage(Throwable e) {
		String m = e.getMessage();
		if (m == null) {
			return e.getClass().getSimpleName();
		}
		// 避免把可能含密钥/内部地址的完整信息透出
		return m.length() > 300 ? m.substring(0, 300) + "..." : m;
	}

}
