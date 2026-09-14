import { StreamTimeoutError } from './sse'
import type { StructuredChatRequest, StructuredResult } from '../types'

/**
 * 结构化输出的前端超时。
 *
 * 比流式端点宽松得多（流式是首字节 30s），因为后端的结构化输出是**同步的、可能要跑好几轮**：
 * 一轮回灌重试 = 再一次完整模型调用，实测单轮 1~4.6 秒，默认最多 3 轮。
 * 所以 60s 是"3 轮 + 网络余量"的量级，不是随手写的数字。
 *
 * 更要紧的是它**必须大于后端的整轮预算**：前端先超时会让用户看到"失败"，
 * 而后端其实正在成功返回的路上 —— 这种假故障最费排查时间（后端日志显示成功，用户说报错）。
 */
export const STRUCTURED_TIMEOUT_MS = 60_000

/** 结构化输出的 HTTP / 网络层错误（区别于"模型给了不合格内容"——那是业务结果，不是异常） */
export class StructuredHttpError extends Error {
  constructor(
    readonly status: number,
    detail: string,
  ) {
    super(`HTTP ${status}：${detail}`)
    this.name = 'StructuredHttpError'
  }
}

/**
 * 调 POST /api/chat/structured。
 *
 * 与流式端点（`streamChat`）的三点不同，都是刻意为之：
 *   1. **没有 AbortSignal 参数**：结构化输出是"全有或全无"，中途取消拿不到半成品，
 *      取消一个已经跑了两轮回灌的请求等于白烧 token。要停就等它返回。
 *   2. **超时用最朴素的一刀切**（整体上限），不需要流式那套"与 reader.read() 赛跑"——
 *      这里没有长连接，超时直接 abort 就有效。
 *   3. **失败时抛异常**而不是回调 onError：这里只有一个结果，用 Promise 语义更直接，
 *      调用方 try/catch 即可。（流式必须用回调，因为增量是多次到达的。）
 */
export async function askStructured(request: StructuredChatRequest): Promise<StructuredResult> {
  const controller = new AbortController()
  let timedOut = false

  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, STRUCTURED_TIMEOUT_MS)

  try {
    const response = await fetch('/api/chat/structured', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(request),
      signal: controller.signal,
    })

    if (!response.ok) {
      const text = await response.text().catch(() => '')
      throw new StructuredHttpError(response.status, extractHttpError(text))
    }

    return (await response.json()) as StructuredResult
  } catch (e) {
    // 超时也是通过 abort 实现的，所以这里必须先把"是不是超时"认出来，
    // 否则会退化成一句没信息量的 "The operation was aborted"
    if (timedOut) {
      throw new StreamTimeoutError(STRUCTURED_TIMEOUT_MS, 'total')
    }
    throw e
  } finally {
    clearTimeout(timer)
  }
}

/** Spring 的错误响应可能是 ProblemDetail JSON（detail 字段），也可能是空体 */
function extractHttpError(body: string): string {
  if (!body) return '无响应体'
  try {
    const parsed = JSON.parse(body) as { detail?: string; message?: string; error?: string }
    return parsed.detail ?? parsed.message ?? parsed.error ?? body.slice(0, 300)
  } catch {
    return body.slice(0, 300)
  }
}
