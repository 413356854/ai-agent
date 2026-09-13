/**
 * 与后端契约对应的类型。
 *
 * 唯一事实来源是后端代码，改后端时同步改这里：
 *   - 请求体：ai.agent.chat.dto.ChatRequest
 *   - done 事件：ai.agent.chat.service.impl.ChatServiceImpl#usageOf
 *   - error 事件：ai.agent.chat.controller.ChatController#safeMessage
 */

/** 对应后端 ai.agent.chat.dto.ChatRequest */
export interface ChatRequest {
  conversationId: string
  message: string
  /** 不传则后端用 0.3（ChatRequest.DEFAULT_TEMPERATURE） */
  temperature?: number
}

/** done 事件的 data：字段后端可能给 null（模型没回用量时） */
export interface TokenUsage {
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  model: string | null
}

/** 后端定义的三种 SSE 事件名（自定义事件名，不是默认的 message） */
export type SseEventName = 'content' | 'done' | 'error'

/** content 事件的 data：{"delta":"..."} —— 包 JSON 是为了躲开 SSE 规范吃掉前导空格的规则 */
export interface ChatDelta {
  delta: string
}

export const EMPTY_USAGE: TokenUsage = {
  promptTokens: null,
  completionTokens: null,
  totalTokens: null,
  model: null,
}
