package ai.agent.service;

import ai.agent.chat.structured.StructuredAnswer;
import ai.agent.chat.structured.StructuredResult;

/**
 * 结构化输出的<b>契约</b>。
 *
 * <p>与 {@link ai.agent.service.ChatService}（流式对话）分开定义是刻意的：两者的失败语义完全不同。
 * 流式对话"吐出多少算多少"，失败了给 error 事件；结构化输出是<b>全有或全无</b>——
 * 要么拿到一个通过校验的对象，要么拿到明确标记的降级结果，不存在"半成品"。
 * 契约不同就不该塞进同一个接口。
 */
public interface StructuredChatService {

	/**
	 * 让模型按 {@link StructuredAnswer} 的结构回答，并保证返回值<b>通过 JSR-380 校验</b>。
	 *
	 * <p>实现约定：
	 * <ol>
	 *   <li>用 {@code json_object} 响应格式 + 把 JSON Schema 写进提示词，让模型知道该输出什么；</li>
	 *   <li>解析成 record，跑一遍 Bean Validation；</li>
	 *   <li>不合格就把"具体哪几个字段错了 + 模型的原始输出"一起回灌，让它自己改（最多 N 轮）；</li>
	 *   <li>N 轮仍不合格 → 返回 {@link StructuredAnswer#degraded} 兜底，<b>不抛异常</b>。</li>
	 * </ol>
	 *
	 * @param conversationId 会话 ID（用于对话记忆；与流式对话共用同一套记忆）
	 * @param question       用户问题
	 * @return 最终答案 + 完整的尝试记录
	 */
	StructuredResult ask(String conversationId, String question);
}
