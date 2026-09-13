import { parseSse } from './sse'
import { EMPTY_USAGE } from '../types'
import type { ChatRequest, TokenUsage } from '../types'

export interface StreamHandlers {
  /** 增量文本片段，直接往当前气泡后面拼 */
  onContent: (delta: string) => void
  /** 流正常结束，带 token 用量 */
  onDone: (usage: TokenUsage) => void
  /** 业务失败（后端 event:error），message 已由后端截断，不含堆栈 */
  onError: (message: string) => void
}

/**
 * 调 POST /api/chat/stream 并消费 SSE。
 *
 * 用 POST 而不是 GET：message 走请求体，不进 URL（长度 + 日志泄露两个问题一起解决）。
 * AbortSignal 用于「停止生成」：abort 后 fetch 会抛 AbortError，调用方按主动取消处理即可。
 */
export async function streamChat(
  request: ChatRequest,
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // 明确要求 SSE；即便后端 produces 已声明，写上更好排查问题
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(request),
    signal,
  })

  if (!response.ok) {
    // 参数校验失败(400) 等场景返回的是 JSON/空体，不是 SSE，按文本兜住给用户看
    const text = await response.text().catch(() => '')
    handlers.onError(extractHttpError(text, response.status))
    return
  }
  if (!response.body) {
    handlers.onError('响应没有 body，无法流式读取')
    return
  }

  for await (const frame of parseSse(response.body)) {
    switch (frame.event) {
      case 'content':
        handlers.onContent(parseDelta(frame.data))
        break
      case 'done':
        handlers.onDone(parseUsage(frame.data))
        break
      case 'error':
        handlers.onError(parseErrorMessage(frame.data))
        break
      default:
        // 未知事件（例如后端将来加心跳）静默忽略，保证前端不被新事件搞崩
        break
    }
  }
}

/**
 * content 事件的 data 是 {"delta":"..."}，不是纯文本。
 *
 * 为什么后端要包这一层：Spring 写出的是 `data:xxx`（冒号后无分隔空格），而 SSE 规范要求
 * 客户端吃掉 data 后的一个空格 —— 纯文本分片只要以空格开头就会丢空格（实测渲染成
 * "publicFlux"）。包成 JSON 后 data 以 `{` 开头，空格被引号保护。
 */
function parseDelta(data: string): string {
  try {
    const parsed = JSON.parse(data) as { delta?: unknown }
    return typeof parsed.delta === 'string' ? parsed.delta : ''
  } catch {
    // 兼容「直接发纯文本」的老实现/其他后端：解析不出来就当纯文本用，别把页面搞崩
    return data
  }
}

function parseUsage(data: string): TokenUsage {
  try {
    const parsed = JSON.parse(data) as Partial<TokenUsage>
    return { ...EMPTY_USAGE, ...parsed }
  } catch {
    return EMPTY_USAGE
  }
}

function parseErrorMessage(data: string): string {
  try {
    const parsed = JSON.parse(data) as { message?: string }
    return parsed.message ?? data
  } catch {
    return data
  }
}

/** Spring 的错误响应可能是 ProblemDetail JSON，也可能是空体，尽量给出人能看懂的原因 */
function extractHttpError(body: string, status: number): string {
  if (body) {
    try {
      const parsed = JSON.parse(body) as { detail?: string; message?: string; error?: string }
      const detail = parsed.detail ?? parsed.message ?? parsed.error
      if (detail) return `HTTP ${status}：${detail}`
    } catch {
      return `HTTP ${status}：${body.slice(0, 300)}`
    }
  }
  return `HTTP ${status}`
}
