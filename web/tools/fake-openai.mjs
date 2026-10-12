/**
 * 一次性探针用的**假模型端点**（OpenAI 兼容的最小子集）。
 *
 * <h2>为什么要有它</h2>
 * 定时任务那条链路新加了一步"到点先让模型把描述模板解析成当下这句话"，
 * 而这一步的成败**全在模型那一步**：解析成功要能落库、调用失败要"这一次不派活"。
 * 本机只有假 key（真后端调真模型必然 401），所以这两条分支都验不到 ——
 * 除非把模型换成一个可控的替身。
 *
 * <p>它做两件事：
 * <ol>
 *   <li>{@code POST /v1/chat/completions} 返回一段可控的文本（默认：把"昨天"换成探针算好的日期）；</li>
 *   <li>把收到的**请求体**记下来并暴露在 {@code GET /__calls} 上 ——
 *       探针据此断言"提示词里真的带着算好的时间表"（那是这条链路可核对的前提）。</li>
 * </ol>
 *
 * <h2>三种可切换的返回（用 {@code GET /__mode/…}）</h2>
 * <ul>
 *   <li>{@code ok}（默认）：返回一句改写过的描述（带具体日期 + markdown 围栏，
 *       顺便验"围栏会被剥掉"）；</li>
 *   <li>{@code echo}：原样返回 <user> 里那句描述 —— 用来验"模板里没有时间说法"那一支；</li>
 *   <li>{@code fail}：返回 HTTP 500 —— 用来验"这一次不派活"那一支。</li>
 * </ul>
 * 清调用记录是 {@code GET /__reset}（<b>与切模式分开</b>：探针开头会切一次模式，
 * 而它随后要断言的是"这一次运行里模型收到了什么"）。
 *
 * 用法：`node tools/fake-openai.mjs`（默认 8098）。
 */
import { createServer } from 'node:http'

const PORT = Number(process.env.FAKE_PORT ?? 8098)

let mode = 'ok'
let calls = []

/** 从 user 提示词里抠出 <描述>…</描述> 之间的原文（与 TaskSchedulePrompt 的写法对应）。 */
function templateOf(userText) {
  const match = /<描述>\s*([\s\S]*?)\s*<\/描述>/.exec(userText ?? '')
  return match ? match[1] : '(没抠到描述)'
}

function reply(userText) {
  if (mode === 'fail') {
    return null
  }
  if (mode === 'echo') {
    return templateOf(userText)
  }
  // ok：学着"把周期说法换成具体日期"的样子改写，并**故意**用围栏包起来
  // （真模型也经常这么干，而围栏留在描述里会让那条任务读起来像坏了）
  return '```text\n把昨天的失败运行汇总成一份晨报（昨天 = 2026-10-11）\n```'
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://127.0.0.1:${PORT}`)

  if (req.method === 'GET' && url.pathname === '/__calls') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ mode, count: calls.length, calls }))
    return
  }
  if (req.method === 'GET' && url.pathname === '/__reset') {
    calls = []
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ ok: true, mode, count: 0 }))
    return
  }
  // ⚠️ 切模式**不动**调用记录：探针在开头就会切一次模式（把替身拨到确定的返回上），
  // 而它随后要断言的正是"这一次运行里模型收到了什么" —— 切模式时清账的话，
  // 那份记录在断言之前就被自己抹掉了（实测踩过：描述解析 0 次 / 全部 0 次）。
  // 要清账请单独打 `/__reset`。
  if (req.method === 'GET' && url.pathname.startsWith('/__mode/')) {
    mode = url.pathname.split('/').pop()
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ ok: true, mode, count: calls.length }))
    return
  }

  if (req.method !== 'POST' || !url.pathname.endsWith('/chat/completions')) {
    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end('{"error":{"message":"fake-openai 只实现 POST /v1/chat/completions"}}')
    return
  }

  let raw = ''
  req.on('data', chunk => { raw += chunk })
  req.on('end', () => {
    let body = {}
    try { body = raw ? JSON.parse(raw) : {} } catch { /* 保持空对象 */ }
    const messages = body.messages ?? []
    const system = messages.find(m => m.role === 'system')?.content ?? ''
    const user = messages.find(m => m.role === 'user')?.content ?? ''
    calls.push({ system, user, temperature: body.temperature ?? null, maxTokens: body.max_tokens ?? null })

    const content = reply(user)
    if (content === null) {
      res.writeHead(500, { 'Content-Type': 'application/json' })
      res.end('{"error":{"message":"fake-openai 按 mode=fail 返回 500"}}')
      return
    }
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({
      id: 'chatcmpl-fake',
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: body.model ?? 'fake',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 120, completion_tokens: 30, total_tokens: 150 },
    }))
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[fake-openai] 已启动：http://127.0.0.1:${PORT}（mode=${mode}）`)
  console.log('[fake-openai] 切模式：GET /__mode/ok | /__mode/echo | /__mode/fail；'
    + '看请求：GET /__calls；清记录：GET /__reset')
})
