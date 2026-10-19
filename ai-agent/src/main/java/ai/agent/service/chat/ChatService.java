package ai.agent.service.chat;

import org.springframework.ai.chat.client.ChatClientResponse;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 对话服务<b>契约</b>（接口）。
 *
 * <p>为什么拆成接口（{@code ai.agent.service.chat}） + 实现（{@code ai.agent.service.impl.chat}）两个包：
 * <ul>
 *   <li>上层（{@code ChatController}）只依赖契约，不依赖 Spring AI 的装配细节，
 *       将来换实现（加缓存、换模型、加拦截）不必改调用方；</li>
 *   <li>便于按接口做单元测试替身，不必真的连模型；</li>
 *   <li>Spring 注入的是接口类型，由 {@code @Service} 的实现类提供 bean。</li>
 * </ul>
 *
 * <p>所有对话方法共同的一条硬约束：{@code conversationId} <b>必填</b>——2.0 起
 * memory advisor 每次调用都必须提供会话 ID，缺失会抛 {@code IllegalArgumentException}，没有默认值。
 */
public interface ChatService {

	/**
	 * 流式对话。
	 *
	 * @param conversationId 会话 ID（必填，用于对话记忆）
	 * @param userText       用户输入
	 * @param temperature    采样温度
	 * @return 增量内容流；结束时会发出一个携带 TokenUsage 的完整 ChatResponse
	 */
	Flux<ChatClientResponse> stream(String conversationId, String userText, double temperature);

	/**
	 * 与上面同一条流，多带一段<b>知识库材料块</b>（已经检索并拼好的那一段；没勾知识库时传 null）。
	 *
	 * <p>为什么参数是"拼好的文本"而不是"知识库 id 列表"：检索要花钱，而它必须在
	 * <b>流开始之前</b>做完 —— Token 预算要把它算进去（见 {@code ChatController}），
	 * 而 SSE 的响应头一旦发出就收不回来。这一层只负责把那段文本接到系统提示词后面。
	 */
	Flux<ChatClientResponse> stream(String conversationId, String userText, double temperature,
			String knowledgeBaseBlock);

	/** 非流式对话（对照用）。跑在 Servlet 栈上，会阻塞当前线程直到模型返回完毕。 */
	String call(String conversationId, String message);

	/** 非流式的那条，含义同上（多一段知识库材料块）。 */
	String call(String conversationId, String message, String knowledgeBaseBlock);

	/** 从流式响应中取出当前增量文本；非文本 chunk（如仅含元数据）返回空串。 */
	String deltaOf(ChatClientResponse response);

	/** 提取 token 用量，字段可能为 null。 */
	Map<String, Object> usageOf(ChatClientResponse response);

	/** 判断这个 chunk 是否带来真实增量，避免把空 chunk 推给前端。 */
	boolean hasDelta(String delta);

	/**
	 * 记录对话失败日志（堆栈只进日志，不出接口）。
	 *
	 * @param expected true 表示这是可预期的失败（超时 / 上游 4xx-5xx），按 WARN 记录；
	 *                 false 表示意外故障（如空指针），按 ERROR 记录。
	 *                 区分的目的：让"模型限流"这类常态不污染 ERROR 级别的告警。
	 */
	void logError(String conversationId, Throwable e, boolean expected);

}
