package ai.agent.entity.dto.chat;

/**
 * 流式对话的请求体（HTTP 层的入参对象）。
 *
 * <p>为什么要有这个 DTO，而不是继续用 query 参数：
 * <ul>
 *   <li>{@code message} 放 URL 有长度上限（Tomcat 默认请求行约 8KB），长 prompt 会被截断；</li>
 *   <li>URL 会进 access log、浏览器历史、Referer，<b>用户输入等于明文外泄</b>；</li>
 *   <li>后续加字段（历史消息、tools、结构化输出 schema）时不必再动 URL 契约。</li>
 * </ul>
 *
 * <p>{@code temperature} 允许不传（null）——默认值放在 {@link #temperatureOrDefault()}，
 * 避免默认值散落在 Controller 各处。
 */
public record ChatRequest(String conversationId, String message, Double temperature,
		java.util.List<Long> knowledgeBaseIds) {

	/** 与 GET 接口的 {@code defaultValue} 保持一致，避免两条通路行为不一致。 */
	public static final double DEFAULT_TEMPERATURE = 0.3;

	public double temperatureOrDefault() {
		return temperature == null ? DEFAULT_TEMPERATURE : temperature;
	}

}
