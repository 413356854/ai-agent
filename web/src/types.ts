/**
 * 与后端契约对应的类型。
 *
 * 唯一事实来源是后端代码，改后端时同步改这里：
 *   - 流式请求体：ai.agent.chat.dto.ChatRequest
 *   - done 事件：ai.agent.chat.service.impl.ChatServiceImpl#usageOf
 *   - error 事件：ai.agent.chat.controller.ChatController#safeMessage
 *   - 结构化请求体：ai.agent.chat.structured.dto.StructuredChatRequest
 *   - 结构化响应：ai.agent.chat.structured.StructuredResult / StructuredAnswer
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

// ---------------------------------------------------------------------------
//  结构化输出（POST /api/chat/structured）
// ---------------------------------------------------------------------------

/** 对应后端 ai.agent.chat.structured.dto.StructuredChatRequest */
export interface StructuredChatRequest {
  conversationId: string
  question: string
}

/**
 * 对应后端 ai.agent.chat.structured.StructuredAnswer。
 *
 * 注意 confidence 是「模型自评」而不是系统算出来的可信度 —— 它只反映模型自己有多确定。
 * 界面上必须标明这一点，否则会被当成质量指标误用。
 */
export interface StructuredAnswer {
  answer: string
  confidence: number | null
  keywords: string[]
}

/**
 * 一次尝试的记录。对应后端 StructuredResult.Attempt。
 *
 * parsed 为 null 有两种可能，靠 issues 区分：
 *   - issues 非空 → 解析或校验没过（这就是「回灌」的触发原因）
 *   - issues 为空 → 理论上不会出现（成功必然有 parsed）
 */
export interface StructuredAttempt {
  index: number
  /** 模型这一次的原始输出；调用彻底失败时可能为 null */
  rawOutput: string | null
  parsed: StructuredAnswer | null
  /** 失败原因清单（后端已转成可读中文）；成功时为空数组 */
  issues: string[]
  elapsedMs: number
}

/** 对应后端 ai.agent.chat.structured.StructuredResult */
export interface StructuredResult {
  /** degraded=true 时这里是后端的兜底文案，不是模型给的答案 */
  answer: StructuredAnswer
  attempts: StructuredAttempt[]
  /** 模型在限定轮数内始终没产出合格结果，后端已降级 */
  degraded: boolean
}

/** 界面层包装：把结果和它对应的提问绑在一起（历史记录要能各自回看） */
export interface StructuredTurn {
  id: string
  question: string
  /** 请求进行中（多个模型调用，可能好几秒） */
  loading: boolean
  result?: StructuredResult
  /** 网络层 / HTTP 层失败（不是模型的错，是链路问题） */
  error?: string
  errorKind?: 'timeout' | 'http'
}
