/**
 * 本地假后端：用来在没有 API Key（或不想烧 token）时验证前端渲染。
 *
 * 用法：
 *   终端 A：node mock/sse-server.mjs            # 起在 8081
 *   终端 B：VITE_API_TARGET=http://localhost:8081 pnpm dev
 * 或直接跑：pnpm dev:mock（见 package.json）
 *
 * 它按真后端的契约实现这些端点：
 *   - POST /api/chat/stream      SSE：content（JSON {"delta":"..."}）/ done（JSON 用量）/ error（JSON 消息），
 *                                且和 Spring 一样写成 `data:xxx`（冒号后不加分隔空格）—— 这正是需要被验证的形态
 *   - POST /api/chat/structured  结构化输出：返回 { answer, attempts[], degraded }
 *   - GET  /api/chat/async       非流式对话（虚拟线程端点），返回纯文本
 *   - GET  /api/diag/threads*    线程与预算诊断：当前线程 / 统计 / 配置 / 载体复用 / 钉住对照
 *   - GET  /api/diag/rag*        RAG 诊断：语料 / 分块预览 / 分词 / 召回 / 评测集 / 策略对照
 *                                （实现在 mock/rag-routes.mjs，见那个文件里为什么假数据要照真形状写）
 *   - GET  /api/chat/ping        连通性
 *
 * 流式部分故意把一段中文和一个 emoji 拆成多个 chunk 发，用来验证「多字节字符被分包切断」不会乱码。
 * 结构化部分故意造一个"回灌过一轮"的结果（第 1 轮 confidence 写成百分制被拦），
 * 因为那才是这个功能真正值得看的样子；提问里带「降级」二字则返回 degraded 结果。
 *
 * 诊断端点的数字是**写死的**，但字段形状、嵌套结构、单位都和真后端一致 ——
 * 因为真后端那些值依赖运行时事实（每次都不一样），拿它做自检就只能写出很松的断言。
 * 假后端在这里扮演的是"契约样本"，验证的是界面能不能正确处理这份契约。
 */
import { createServer } from 'node:http'
import { handleAgentRoutes } from './agent-routes.mjs'
import { handleAlertsRoutes } from './alert-routes.mjs'
import { handleCostRoutes } from './cost-routes.mjs'
import { handlePermissionRoutes } from './permission-routes.mjs'
import { handlePromptRoutes } from './prompt-routes.mjs'
import { handleRagRoutes } from './rag-routes.mjs'
import { handleScheduleRoutes } from './schedule-routes.mjs'
import { handleSkillRoutes } from './skill-routes.mjs'
import { handleTaskRoutes } from './task-routes.mjs'

const PORT = Number(process.env.MOCK_PORT ?? 8081)

/** 统一的 JSON 响应（诊断端点用）。 */
function json(res, payload, status = 200) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(payload))
}

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
  // 解析出 pathname 与 query。RAG 那几个端点要读 query 参数
  // （strategy / text / query），而 req.url 是带 query 的整串，
  // 直接用 startsWith 比对会把 `/api/diag/rag/tokens?text=...` 也当成 tokens 端点的精确匹配 ——
  // 所以统一解析一次，按 pathname 分发。
  const url = new URL(req.url ?? '/', `http://127.0.0.1:${PORT}`)

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

  // ---------------------------------------------------------------
  //  线程与预算诊断端点（学习点 7 + 2）
  //
  //  为什么假后端也要实现这几个：真后端那几个端点的返回依赖**运行时事实**
  //  （当前线程是不是虚拟线程、JVM 里有多少个平台线程、synchronized 有没有钉住载体），
  //  也就是说它们的**数值每次都不一样**。而界面自检需要确定性 ——
  //  拿真后端跑，"钉住延迟 6405ms vs 0ms"这种数字每次都在变，断言只能写得很松，
  //  松到失去意义。
  //
  //  所以这里返回**一组固定但形态完整**的数据：数字是假的，但只要界面
  //  依赖的字段形状、嵌套结构、单位（毫秒字符串）都和真后端一致，
  //  界面逻辑就能被真实验证。这正是假后端存在的意义 ——
  //  它验证的是"界面能不能正确处理这份契约"，不是"后端算得对不对"。
  // ---------------------------------------------------------------
  if (req.method === 'GET' && req.url.startsWith('/api/diag/threads/config')) {
    json(res, {
      containerVirtualThreads: true,
      asyncExecutor: 'virtual',
      platformPoolSize: 8,
      samplingLatency: 'PT0.4S',
      standInActive: true,
      budgetEnabled: true,
      contextWindow: 128000,
      maxOutputTokens: 2048,
      safetyMargin: 0.15,
      usableBudget: 108800,
      inputAllowance: 106752,
    })
    return
  }

  if (req.method === 'GET' && req.url === '/api/diag/threads') {
    json(res, {
      currentThread: {
        virtual: true,
        name: 'tomcat-vt-42',
        threadId: 128,
        daemon: true,
        priority: 5,
        configuredMode: 'virtual',
        containerVirtualThreadsEnabled: true,
      },
      note: 'virtual=true 的含义：本次请求跑在虚拟线程上，并发上限不再等于容器线程池大小',
    })
    return
  }

  if (req.method === 'GET' && req.url.startsWith('/api/diag/threads/stats')) {
    json(res, {
      livePlatformThreads: 31,
      peakPlatformThreads: 34,
      daemonThreads: 27,
      liveVirtualThreads: 3,
      inFlight: 2,
      peakInFlight: 400,
      totalRequests: 812,
      availableProcessors: 8,
      platformPoolSize: 8,
      containerVirtualThreadsEnabled: true,
      configuredMode: 'virtual',
    })
    return
  }

  if (req.method === 'POST' && req.url.startsWith('/api/diag/threads/stats/reset')) {
    json(res, { peakInFlight: 0, totalRequests: 0 })
    return
  }

  if (req.method === 'GET' && req.url.startsWith('/api/diag/threads/carriers')) {
    // 16 个虚拟线程只用 4 个载体 —— 这个"远小于"正是要展示的复用关系
    const carriers = ['ForkJoinPool-1-worker-1', 'ForkJoinPool-1-worker-2', 'ForkJoinPool-1-worker-3', 'ForkJoinPool-1-worker-4']
    json(res, {
      virtualThreads: 16,
      sleepMs: 300,
      distinctCarriers: carriers.length,
      carrierParallelism: 8,
      samples: Array.from({ length: 16 }, (_, i) => ({
        virtualThread: '',
        carrier: carriers[i % carriers.length],
      })),
      note: 'virtualThreads 远大于 distinctCarriers 即为正常：虚拟线程复用了少量载体线程',
    })
    return
  }

  if (req.method === 'GET' && req.url.startsWith('/api/diag/threads/pin')) {
    json(res, {
      holders: 16,
      holdMsEach: 800,
      carrierParallelism: 8,
      probeLatencyWithSynchronized: '6405ms',
      probeLatencyWithReentrantLock: '0ms',
      verdict: '确认钉住：synchronized 占住载体线程后，新任务必须排队等载体空出来',
      note: 'probeLatency = 『提交一个空任务 → 它真正开始执行』的最坏延迟，等价于用户感知到的排队时间。两组实验除阻塞方式外完全相同',
      fix: '把包着阻塞 IO 的 synchronized 换成 ReentrantLock，或缩小临界区、把 IO 移到锁外',
    })
    return
  }

  if (req.method === 'GET' && req.url.startsWith('/api/chat/async')) {
    // 非流式对话（异步端点与 /sync 对照组共用）：返回纯文本，带一点延迟以模拟模型
    setTimeout(() => {
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('[mock] 这是非流式对话的响应，由假后端返回。')
    }, 300)
    return
  }

  // ---------------------------------------------------------------
  //  RAG 诊断端点（阶段二）
  //
  //  单独放在 rag-routes.mjs 里：这一组端点的假数据有真实的多层嵌套
  //  （指标 → 分组 → 用例、差值 → 指标 → 策略），继续堆在这个文件的 if 链里
  //  会让"路由"和"数据"彻底混在一起。
  //
  //  注意这里必须传 url 对象（new URL），不能传 req.url 字符串 ——
  //  这几个端点都要读 query 参数（strategy / text / query）。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/diag/rag') && handleRagRoutes(req, res, url, json)) {
    return
  }

  // ---------------------------------------------------------------
  //  Agent 端点（阶段三第 5 件）
  //
  //  同样单独放一个文件：这里的假数据要造出一条"含失败与自愈"的四步轨迹，
  //  而那条轨迹的数据量比真后端任何端点都大 —— 堆在这个文件里会让
  //  "路由"和"剧本"混在一起，改一处看不全。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/agent') && handleAgentRoutes(req, res, url, json)) {
    return
  }

  // ---------------------------------------------------------------
  //  成本看板端点（阶段四 · 工程化）
  //
  //  同样单独一个文件。这一组的假数据有两处与其他几组**不同性质**的地方：
  //    - 它是**聚合**数据（按天 / 按业务 / 按模型 / 按工具四个视图
  //      再加上一次对账），内部必须自洽（"按模型加起来等于合计"），
  //      否则自检里那条一致性断言永远红，而人会去改断言而不是改数据；
  //    - 它刻意**不是一份"万事如意"的数据** —— 有一个模型没价、有一条运行没归因、
  //      有对账不一致、有缓存命中率为 0。那几个分支正是这一页最需要被验证的部分，
  //      而"所有数据都正常"的假数据会让它们一条都走不到。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/cost') && handleCostRoutes(req, res, url, json)) {
    return
  }

  // ---------------------------------------------------------------
  //  Prompt 版本管理端点（阶段四 · 工程化第 3 件）
  //
  //  ⚠️ 这一组与上面所有组**性质不同**：它有四个**写**端点（存 / 生效 / 回滚 / 复位），
  //  而且状态是**进程内可变**的 —— 点一下之后状态真的变，点第二下看到的是第一下的结果。
  //  配套的纪律写在 prompt-routes.mjs 开头。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/prompts') && handlePromptRoutes(req, res, url, json)) {
    return
  }

  // ---------------------------------------------------------------
  //  告警 + 灰度端点（阶段四 · 工程化第 5 件）
  //
  //  ⚠️ 这一组与上面所有组有一个**结构性**的区别：它同时有**读与写**，
  //  而且写的是**进程内状态**（确认一条告警 → 它从"未处理"变成"已确认"；
  //  开一次灰度 → 状态页上多出一条进行中的实验）。
  //
  //  所以它必须放在**所有 `req.method !== 'GET'` 的守卫之后、且在 404 之前** ——
  //  这里的 if 链是按前缀分派的，POST 请求会在更上面的分支里被放过去。
  // ---------------------------------------------------------------
  if (
    (url.pathname.startsWith('/api/alerts') || url.pathname.startsWith('/api/canary')) &&
    handleAlertsRoutes(req, res, url, json)
  ) {
    return
  }

  // ---------------------------------------------------------------
  //  权限管理端点（应用管理 · 权限管理）
  //
  //  ⚠️ 与 alerts / prompts 同一类：**有写端点，所以状态是进程内可变的**。
  //  这一组与上面所有组还有一处**性质不同**：它的假数据要**照搬真后端的判据**
  //  （工具名对注册表、同名 409、目录按路径分段比），
  //  否则"界面能不能把后端的拒绝显示清楚"这个分支在假后端上永远走不到。
  //  三条纪律写在 permission-routes.mjs 开头。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/permissions') && handlePermissionRoutes(req, res, url, json)) {
    return
  }

  // ---------------------------------------------------------------
  //  定时任务端点（应用管理 · 定时任务）
  //
  //  ⚠️ 与上面三组同一类：**有写端点，所以状态是进程内可变的**。
  //  这一组的假数据要照搬真后端的判据：正则必须是 6 段、「日」与「周」不能同时写具体值、
  //  **改规则 = 删旧线程按新正则重加**（所以改完之后 nextRuns 一定是按新表达式算的）、
  //  两个"人"按用户 id 核账号表。四条纪律写在 schedule-routes.mjs 开头。
  //
  //  ⚠️ **它必须排在 `/api/agent-tasks` 之前**：`/api/agent-task-schedules`.startsWith(
  //  '/api/agent-tasks') 是**真的**（前缀到 "task" 都一样），排在后面时这一族请求会先被
  //  agent 任务那一组接走、返回 404，而现象是"定时任务那一页读不出数据"。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/agent-task-schedules') && handleScheduleRoutes(req, res, url, json)) {
    return
  }

  // ---------------------------------------------------------------
  //  agent 任务端点（应用管理 · agent任务）
  //
  //  ⚠️ 与 permissions 同一类：**有写端点，所以状态是进程内可变的**。
  //  这一组的假数据还要**照搬真后端的状态链**（推进到哪、生成哪条确认项、
  //  当前状态不允许的动作 → 409），否则"点一下卡片真的换列了吗"这件事
  //  在假后端上永远走不到 —— 而那正是这一页的全部内容。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/agent-tasks') && handleTaskRoutes(req, res, url, json)) {
    return
  }

  // ---------------------------------------------------------------
  //  skill 端点（应用管理 · skill管理）
  //
  //  ⚠️ 与 permissions / agent-tasks 同一类：**有写端点，所以状态是进程内可变的**。
  //  这一组的假数据要照搬真后端的四条判据（未登录写操作 400、文件夹必须在
  //  自己角色的目录前缀里、归属判据是那一行的 owner_id、同名 409）。
  //  三条纪律写在 skill-routes.mjs 开头。
  // ---------------------------------------------------------------
  if (url.pathname.startsWith('/api/skills') && handleSkillRoutes(req, res, url, json)) {
    return
  }

  res.writeHead(404, { 'Content-Type': 'application/json' })
  res.end(
    JSON.stringify({
      message:
        'mock server: 支持 POST /api/chat/stream、POST /api/chat/structured、GET /api/chat/ping、GET /api/diag/threads*、GET /api/diag/rag*、GET /api/chat/async、POST /api/agent/ask、GET /api/agent/*、GET /api/cost/*、GET|POST /api/prompts/*、GET|POST /api/alerts/*、GET|POST /api/canary/*、GET|POST|PUT|DELETE /api/permissions/*、GET|POST /api/agent-tasks/*、GET|POST|PUT|DELETE /api/skills/*',
    }),
  )
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock] SSE 假后端已启动：http://127.0.0.1:${PORT}`)
  console.log('[mock] 用法：VITE_API_TARGET=http://127.0.0.1:%d pnpm dev', PORT)
})
