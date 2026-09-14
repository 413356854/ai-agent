package ai.agent.chat.structured.controller;

import ai.agent.chat.structured.StructuredChatService;
import ai.agent.chat.structured.StructuredResult;
import ai.agent.chat.structured.dto.StructuredChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 结构化输出端点：让模型按固定结构回答，并保证返回值<b>通过 JSR-380 校验</b>。
 *
 * <p>示例：
 * <pre>
 * curl -X POST http://localhost:8080/api/chat/structured \
 *   -H 'Content-Type: application/json' \
 *   -d '{"conversationId":"s1","question":"SSE 是什么？"}'
 * </pre>
 *
 * <p><b>为什么返回体里带上 attempts 这个"过程"</b>：这个端点是阶段一验收的证据载体。
 * 验收要求"能正确处理 JSON 校验失败"，而"处理得对不对"只能从过程看出来 ——
 * 只看最终对象的话，"第一次就成功"和"回灌两轮才救回来"长得一模一样。
 * 带上每一轮的原始输出与错误清单，才能一眼看出：模型第一次错在哪、回灌有没有用、
 * 最后是不是降级了。生产环境若不想暴露这些，把 attempts 去掉即可（它是纯附加信息）。
 *
 * <p><b>为什么同步返回而不是 SSE</b>：结构化输出是全有或全无 —— JSON 必须完整才能反序列化，
 * 边流边解析没有意义（这也是它与 {@code /api/chat/stream} 的本质区别）。
 */
@RestController
@RequestMapping("/api/chat/structured")
public class StructuredChatController {

	private static final Logger log = LoggerFactory.getLogger(StructuredChatController.class);

	private final StructuredChatService structuredChatService;

	public StructuredChatController(StructuredChatService structuredChatService) {
		this.structuredChatService = structuredChatService;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public StructuredResult ask(@RequestBody StructuredChatRequest request) {
		requireText(request.conversationId(), "conversationId");
		requireText(request.question(), "question");

		StructuredResult result = structuredChatService.ask(request.conversationId(), request.question());

		log.info("结构化输出完成 conversationId={} attempts={} degraded={}",
				request.conversationId(), result.totalAttempts(), result.degraded());
		return result;
	}

	/**
	 * 参数校验：不合法就 400。
	 *
	 * <p>这里刻意<b>不</b>用 {@code @Valid} + Bean Validation：那两个字段是"请求参数完整性"，
	 * 与响应体的"业务结构校验"是两件事。混用会让人误以为"项目里已经有校验了"，
	 * 而实际上真正需要校验的地方（模型输出）恰恰是注解管不到的 —— 模型输出不是一个入参对象，
	 * 是运行时的一段字符串，只能手动 {@code validator.validate(...)}。
	 */
	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必填");
		}
	}

}
