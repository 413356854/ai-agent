/**
 * 本地假后端：用来在没有 API Key（或不想烧 token）时验证前端渲染。
 *
 * 用法：
 *   终端 A：node mock/sse-server.mjs            # 起在 8081
 *   终端 B：VITE_API_TARGET=http://localhost:8081 pnpm dev
 * 或直接跑：pnpm dev:mock（见 package.json）
 *
 * 它按真后端的契约实现三个端点：
 *   - POST /api/chat/stream      SSE：content（JSON {"delta":"..."}）/ done（JSON 用量）/ error（JSON 消息），
 *                                且和 Spring 一样写成 `data:xxx`（冒号后不加分隔空格）—— 这正是需要被验证的形态
 *   - POST /api/chat/structured  结构化输出：返回 { answer, attempts[], degraded }
 *   - GET  /api/chat/ping        连通性
 *
 * 流式部分故意把一段中文和一个 emoji 拆成多个 chunk 发，用来验证「多字节字符被分包切断」不会乱码。
 * 结构化部分故意造一个"回灌过一轮"的结果（第 1 轮 confidence 写成百分制被拦），
 * 因为那才是这个功能真正值得看的样子；提问里带「降级」二字则返回 degraded 结果。
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

  if (req.method === 'POST' && req.url === '/api/chat/structured') {
    let body = ''
    req.on('data', chunk => {
      body += chunk
    })
    req.on('end', () => {
      const parsed = body ? JSON.parse(body) : {}
      console.log('[mock] 收到结构化请求', parsed)

      // 默认造一个"回灌过一轮"的结果，因为那才是这个功能真正值得看的样子：
      // 第一次模型把 confidence 写成了百分制，被校验拦下，回灌后改对。
      // 想看降级就把 query 里带 degraded=1（见下）
      const wantDegraded = String(parsed.question ?? '').includes('降级')

      const badOutput = JSON.stringify({
        answer: '（假后端）SSE 是服务器发送事件。',
        confidence: 95,
        keywords: ['SSE'],
      })
      const goodOutput = JSON.stringify({
        answer: '（假后端）**SSE（Server-Sent Events）** 是服务器向浏览器单向推送文本的 HTTP 长连接技术。',
        confidence: 0.92,
        keywords: ['SSE', 'EventSource', 'text/event-stream'],
      })

      const firstAttempt = {
        index: 1,
        rawOutput: badOutput,
        parsed: null,
        issues: ['字段 confidence：confidence 不能大于 1（注意：要用 0~1 的小数，不是百分制）'],
        elapsedMs: 1180,
      }
      const secondAttempt = {
        index: 2,
        rawOutput: goodOutput,
        parsed: JSON.parse(goodOutput),
        issues: [],
        elapsedMs: 940,
      }

      const payload = wantDegraded
        ? {
            answer: { answer: '模型连续 3 轮未能产出符合结构要求的答案，已降级返回。', confidence: 0, keywords: ['降级'] },
            attempts: [firstAttempt, { ...firstAttempt, index: 2 }, { ...firstAttempt, index: 3 }],
            degraded: true,
          }
        : { answer: JSON.parse(goodOutput), attempts: [firstAttempt, secondAttempt], degraded: false }

      // 故意延迟一下：真实的结构化输出要跑好几秒（可能多轮模型调用），
      // 前端如果没处理好 loading 态，这里就能看出来
      setTimeout(() => {
        res.writeHead(200, {
          'Content-Type': 'application/json; charset=utf-8',
          'Access-Control-Allow-Origin': '*',
        })
        res.end(JSON.stringify(payload))
      }, 1200)
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
  res.end(
    JSON.stringify({
      message: 'mock server: 支持 POST /api/chat/stream、POST /api/chat/structured、GET /api/chat/ping',
    }),
  )
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock] SSE 假后端已启动：http://127.0.0.1:${PORT}`)
  console.log('[mock] 用法：VITE_API_TARGET=http://127.0.0.1:%d pnpm dev', PORT)
})
