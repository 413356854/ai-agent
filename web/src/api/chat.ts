import { parseSse, StreamTimeoutError } from './sse'
import type { StreamTimeoutPhase } from './sse'
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
 * 前端超时策略（毫秒）。
 *
 * 【这三个值与后端是一套联动的约定，改一个必须回头看其余几个】
 *
 *   前端首字节 30s  >  后端首字超时 20s + 第 1 次重试退避 ≈1.1s + …
 *   前端整体 180s   >  后端"首字超时 × (重试次数+1) + 各次退避"的最坏情况
 *
 * 为什么前端超时必须**大于**后端：后端的重试是有价值的补救（用户无感知），
 * 如果前端先超时，用户会看到"失败"，而后端其实正在成功返回的路上 —— 这叫假故障，
 * 排查时最费时间（日志里明明成功了，用户却说报错）。
 * 反过来前端超时必须**存在**：后端进程被 kill -9、部署重启、防火墙静默丢包时，
 * 后端没有任何机会发事件，只能靠前端自己把"卡住"变成"明确失败"。
 */
export const FIRST_BYTE_TIMEOUT_MS = 30_000

/** 已经收到过内容后，相邻两帧之间的最大间隔（流中途卡死的兜底） */
export const IDLE_TIMEOUT_MS = 30_000

/** 整轮请求的硬上限；防止"每 29 秒来一个字"的情况把页面无限期占住 */
export const TOTAL_TIMEOUT_MS = 180_000

/**
 * 调 POST /api/chat/stream 并消费 SSE。
 *
 * 用 POST 而不是 GET：message 走请求体，不进 URL（长度 + 日志泄露两个问题一起解决）。
 * AbortSignal 用于「停止生成」：abort 后 fetch 会抛 AbortError，调用方按主动取消处理即可。
 *
 * 三类失败在这里被区分开，因为它们需要的用户动作完全不同：
 *   1. 超时（StreamTimeoutError）—— 该重试，界面给"重试"按钮；
 *   2. HTTP 错误（后端在响应头阶段就拒绝）—— 多半是配置/参数问题，重试没用；
 *   3. 后端 event:error —— 业务失败，原因由后端给出。
 */
export async function streamChat(
  request: ChatRequest,
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const controller = new AbortController()

  // 为什么需要这个标记：整体超时也是通过 abort 实现的，而"用户点了停止"同样是 abort。
  // 不区分的话，两者都会表现为 AbortError，代码只能把"超时"误报成"用户主动停止"，
  // 用户就会看到"已停止"而不知道其实是失败了。
  let abortedByTotalTimeout = false

  // 整体硬上限。这个 abort 能正常中断"响应头都没回来"的请求；
  // 若响应体流已经开始，则由 parseSse 的帧级超时负责（body 流不响应 abort，见 sse.ts）
  const totalTimer = setTimeout(() => {
    abortedByTotalTimeout = true
    controller.abort()
  }, TOTAL_TIMEOUT_MS)

  // 外部（停止生成 / 切换新会话）要求中断时，同步给内部 controller
  const onOuterAbort = () => controller.abort()
  signal.addEventListener('abort', onOuterAbort, { once: true })

  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // 明确要求 SSE；即便后端 produces 已声明，写上更好排查问题
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(request),
      signal: controller.signal,
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

    // 还没收到任何一帧 → 用首字节超时；收到过 → 换成帧间隔（空闲）超时。
    // 两个阈值不同，所以用变量而不是把常量直接传进去
    let timeoutMs = FIRST_BYTE_TIMEOUT_MS
    let phase: StreamTimeoutPhase = 'first-byte'

    for await (const frame of parseSse(response.body, {
      timeoutMs,
      phase,
      onFrame: () => {
        timeoutMs = IDLE_TIMEOUT_MS
        phase = 'streaming'
      },
    })) {
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
  } catch (e) {
    // 整体超时触发的中断，对调用方来说就是"超时"，不能让它退化成"已停止"
    if (abortedByTotalTimeout) {
      throw new StreamTimeoutError(TOTAL_TIMEOUT_MS, 'total')
    }
    throw e
  } finally {
    clearTimeout(totalTimer)
    signal.removeEventListener('abort', onOuterAbort)
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
