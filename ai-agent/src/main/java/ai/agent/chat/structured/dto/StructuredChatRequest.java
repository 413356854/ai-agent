package ai.agent.chat.structured.dto;

/**
 * 结构化对话的请求体。
 *
 * <p>和流式对话一样用 POST + 请求体传参：问题可能很长（贴一段代码、一段需求），
 * 塞进 URL 会撞长度上限，还会明文进访问日志。
 */
public record StructuredChatRequest(String conversationId, String question) {
}
