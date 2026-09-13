/**
 * SSE 解析器：把 fetch 的响应体切成一个个事件帧，并负责"卡住"的检测。
 *
 * 为什么不直接用浏览器原生 EventSource：
 *   1. EventSource 只支持 GET，参数只能塞 URL —— message 会进访问日志、还受请求行长度限制；
 *   2. 不能带自定义 header（将来加 Authorization 就没法弄）；
 *   3. 连接结束会「自动重连」，得手动 close，否则一轮对话被重复请求。
 * 换成 fetch + ReadableStream 后上面三条都没了，代价是要自己按 SSE 规范切帧 —— 就是这个文件。
 *
 * 规范要点（踩坑点都在这）：
 *   - 帧之间用空行分隔，\n\n、\r\n\r\n 都合法；
 *   - 一个帧内多行 `data:` 要用 \n 拼起来（模型输出里的换行就是这么过来的）；
 *   - `:` 开头的行是注释（可当心跳），要忽略；
 *   - 必须用 TextDecoder 的 stream 模式：一个中文占 3 字节，被 TCP 分包切断时
 *     不加 { stream: true } 会解出乱码（这正是「SSE 对换行/编码敏感」的根因）。
 */

export interface SseFrame {
  /** 事件名；后端没给 `event:` 时按规范默认 message */
  event: string
  data: string
}

/** SSE 流超时的三种场景；用户可读文案见 StreamTimeoutError 的构造逻辑 */
export type StreamTimeoutPhase = 'first-byte' | 'streaming' | 'total'

export interface ParseSseOptions {
  /**
   * 相邻两帧之间的最大允许间隔（毫秒），超时抛 StreamTimeoutError。
   *
   * 为什么必须在这个位置做超时，而不是用 AbortController 的 setTimeout：
   * fetch 的 body 流**不响应 abort** —— abort 只中断请求本身（连接、响应头阶段），
   * 一旦流已经开始、reader.read() 正在挂起等待，abort 不会让这个已挂起的 Promise 落地。
   * 实测：靠 abort 做超时的写法，超时后页面永远停在「正在思考…」。
   * 所以超时必须做成"和 read() 赛跑"，见下方 waitForRead。
   */
  timeoutMs?: number
  /** 超时时抛出的错误里带上"卡在哪一段"，供上层给出不同提示 */
  phase?: StreamTimeoutPhase
  /** 每收到一帧回调一次（用于上层刷新自己的心跳/看门狗） */
  onFrame?: (frame: SseFrame) => void
}

/** 流超时：「后端一个字都没回」/「回答到一半断了」/「整轮太久」三种，靠 phase 区分 */
export class StreamTimeoutError extends Error {
  readonly timedOut: true = true

  constructor(
    readonly timeoutMs: number,
    readonly phase: StreamTimeoutPhase,
  ) {
    super(describeTimeout(timeoutMs, phase))
    this.name = 'StreamTimeoutError'
  }
}

/** 超时文案：必须让用户知道"该不该再点一次"，所以要说清卡在哪一段 */
function describeTimeout(timeoutMs: number, phase: StreamTimeoutPhase): string {
  switch (phase) {
    case 'first-byte':
      return `超过 ${timeoutMs / 1000} 秒没有收到任何返回，已中断。可能原因：后端未启动、模型无响应。`
    case 'streaming':
      return `回答中断：超过 ${timeoutMs / 1000} 秒没有收到新内容，连接可能已断开。`
    default:
      return `本轮对话超过 ${timeoutMs / 1000} 秒仍未结束，已强制中断。`
  }
}

export function isStreamTimeoutError(e: unknown): e is StreamTimeoutError {
  return e instanceof StreamTimeoutError
}

const FRAME_SEPARATOR = /\r?\n\r?\n/

export async function* parseSse(
  stream: ReadableStream<Uint8Array>,
  options: ParseSseOptions = {},
): AsyncGenerator<SseFrame, void, void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await waitForRead(reader, options)
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      for (;;) {
        const match = FRAME_SEPARATOR.exec(buffer)
        if (!match) break
        const raw = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        const frame = toFrame(raw)
        if (frame) {
          options.onFrame?.(frame)
          yield frame
        }
      }
    }

    // 流结束：把解码器里残留的半个字符吐出来，再处理没有以空行结尾的最后一帧
    buffer += decoder.decode()
    const tail = toFrame(buffer)
    if (tail) {
      options.onFrame?.(tail)
      yield tail
    }
  } finally {
    // 消费者提前退出（例如点了「停止」）时把底层连接一并取消，别让连接悬着
    reader.cancel().catch(() => undefined)
  }
}

/**
 * 读一块数据，最多等 timeoutMs。
 *
 * 用 Promise.race 而不是 AbortController：见 ParseSseOptions.timeoutMs 的说明。
 * 超时后这里直接抛错，外层 finally 会 cancel reader 把底层连接收掉。
 * 定时器必须在每种结局里都清掉，否则浏览器里会攒下一堆没意义的 setTimeout。
 */
function waitForRead(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  options: ParseSseOptions,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  const { timeoutMs, phase = 'streaming' } = options
  if (!timeoutMs || timeoutMs <= 0) {
    return reader.read()
  }

  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new StreamTimeoutError(timeoutMs, phase)), timeoutMs)
  })

  return Promise.race([reader.read(), timeout]).finally(() => {
    if (timer !== undefined) clearTimeout(timer)
  })
}

/** 把一个原始帧文本解析成事件；没有 data 的帧（例如只有 `event:` 或只有注释）丢弃 */
function toFrame(raw: string): SseFrame | null {
  let event = 'message'
  const dataLines: string[] = []

  for (const line of raw.split(/\r?\n/)) {
    if (line === '' || line.startsWith(':')) continue
    if (line.startsWith('data:')) {
      // 规范：`data:` 后可有一个空格，要去掉
      dataLines.push(line.slice(5).replace(/^ /, ''))
    } else if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    }
    // id: / retry: 本项目用不到，忽略
  }

  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}
