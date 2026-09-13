/**
 * SSE 解析器：把 fetch 的响应体切成一个个事件帧。
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

const FRAME_SEPARATOR = /\r?\n\r?\n/

export async function* parseSse(stream: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame, void, void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      for (;;) {
        const match = FRAME_SEPARATOR.exec(buffer)
        if (!match) break
        const raw = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        const frame = toFrame(raw)
        if (frame) yield frame
      }
    }

    // 流结束：把解码器里残留的半个字符吐出来，再处理没有以空行结尾的最后一帧
    buffer += decoder.decode()
    const tail = toFrame(buffer)
    if (tail) yield tail
  } finally {
    // 消费者提前退出（例如点了「停止」）时把底层连接一并取消，别让连接悬着
    reader.cancel().catch(() => undefined)
  }
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
