/**
 * 本地 SSE 假后端：用来在没有 API Key（或不想烧 token）时验证前端流式渲染。
 *
 * 用法：
 *   终端 A：node mock/sse-server.mjs            # 起在 8081
 *   终端 B：VITE_API_TARGET=http://localhost:8081 pnpm dev
 * 或直接跑：pnpm mock:dev（见 package.json）
 *
 * 它完全按真后端的契约发事件：content（JSON {"delta":"..."}）/ done（JSON 用量）/ error（JSON 消息），
 * 且和 Spring 一样写成 `data:xxx`（冒号后不加分隔空格）——这正是需要被验证的形态。
 * 故意把一段中文和一个 emoji 拆成多个 chunk 发，用来验证「多字节字符被分包切断」不会乱码。
 */
import { createServer } from 'node:http'

const PORT = Number(process.env.MOCK_PORT ?? 8081)

const PARAGRAPHS = [
  '这是**假后端**返回的流式文本，用来在没配 API Key 的情况下验证前端。\n\n',
  '它按 40ms 一个分片推送，所以你应该能看到文字**逐字长出来**、光标一直在闪。\n\n',
  '代码块也会被正确渲染：\n\n```java\npublic Flux<ChatClientResponse> stream(String conversationId) {\n\treturn chatClient.prompt().stream().chatClientResponse();\n}\n```\n\n',
  '结束时会发一个 done 事件，带 token 用量——真后端取的是最后一个非空 chunk 的计数。\n',
]

function sse(res, event, data) {
  res.write(`event:${event}\n`)
  // data 里的换行必须拆成多行 data: —— SSE 规范要求，前端会把它们用 \n 拼回来
  for (const line of String(data).split('\n')) {
    res.write(`data:${line}\n`)
  }
  res.write('\n')
}

const server = createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/api/chat/stream') {
    let body = ''
    req.on('data', chunk => {
      body += chunk
    })
    req.on('end', () => {
      const parsed = body ? JSON.parse(body) : {}
      console.log('[mock] 收到请求', parsed)

      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
        // 万一前端不经 Vite 代理直连本服务，这个头能避免跨域被拦
        'Access-Control-Allow-Origin': '*',
      })

      const text = `（假后端收到你的话：「${parsed.message ?? ''}」）\n\n` + PARAGRAPHS.join('')
      const chars = [...text] // 按「码点」切，别按 UTF-16 码元切，否则 emoji 会被切碎
      let i = 0

      const timer = setInterval(() => {
        if (i >= chars.length) {
          clearInterval(timer)
          sse(res, 'done', JSON.stringify({ promptTokens: 128, completionTokens: 256, totalTokens: 384, model: 'mock-model' }))
          res.end()
          return
        }
        // 每次只发 1~3 个码点，模拟真实的分片粒度 ——
        // 故意保持这种细粒度：分片经常以空格开头，正是「空格被 SSE 吃掉」那个 bug 的触发条件
        const size = 1 + Math.floor(Math.random() * 3)
        sse(res, 'content', JSON.stringify({ delta: chars.slice(i, i + size).join('') }))
        i += size
      }, 40)

      // 注意：必须监听 res 的 close，不能监听 req 的 close。
      // Node >=16 里 req 的 'close' 表示「请求体读完了」，会在 'end' 之后立刻触发，
      // 挂在那里会刚开流就把定时器清掉 —— 表现为客户端一直挂着、一个字都收不到。
      res.on('close', () => clearInterval(timer))
    })
    return
  }

  if (req.method === 'GET' && req.url.startsWith('/api/chat/ping')) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache' })
    let i = 0
    const timer = setInterval(() => {
      sse(res, 'content', `ping-${i}`)
      if (++i >= 3) {
        clearInterval(timer)
        res.end()
      }
    }, 300)
    return
  }

  res.writeHead(404, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ message: 'mock server: 只有 POST /api/chat/stream 和 GET /api/chat/ping' }))
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock] SSE 假后端已启动：http://127.0.0.1:${PORT}`)
  console.log('[mock] 用法：VITE_API_TARGET=http://127.0.0.1:%d pnpm dev', PORT)
})
