/**
 * Agent 端点的**假数据**（阶段三第 5 件）。
 *
 * ---------------------------------------------------------------------------
 * 为什么这些假数据要"照着真后端的形状写"，而不是随便编几个数字
 * ---------------------------------------------------------------------------
 * 与 `rag-routes.mjs` 同一条理由：界面自检要给确定性断言，真后端的数字每次都不同。
 * 所以这里的规则也是**数字是假的，形状是真的**：
 *   - `stage` 必须是真后端 `ToolCallStage` 里真有的值
 *     （`OK` / `UNKNOWN_TOOL` / `PARSE_ERROR` / `VALIDATION_ERROR` / `EXECUTION_ERROR`
 *      / `REQUEST_REJECTED` / `CIRCUIT_OPEN`）——
 *     编一个不存在的 stage，界面会走到"认不出的 stage"分支，而那条分支在真后端上永远不会走；
 *   - `countsAsToolFailure` 必须与后端的判据一致（**只有 `EXECUTION_ERROR` 是 true**）——
 *     假数据里把它写反，界面上"计入熔断"那一列的着色就是错的，
 *     而自检断言不了"颜色对不对"，只有人工看才会发现；
 *   - `failureCause` 必须是真后端 `AgentFailureCause` 里真有的值。
 *
 * ---------------------------------------------------------------------------
 * 数值直接取自真后端的一次实测结构（四步：列目录 → 检索失败 → 修正 → 作答）
 * ---------------------------------------------------------------------------
 * 这个脚本刻意造出一条**包含失败与自愈的**轨迹，而不是一条"一次就成功"的：
 *   - 一次就成功的轨迹，界面上看不出"过程"这一块到底有没有在工作
 *     （时间线只有一步，看起来和"没实现"差不多）；
 *   - 而"编错工具名 → 回填 → 改对 → 作答"这条轨迹正好把这一页要讲的东西全串起来：
 *     模型怎么选、失败在哪一步、回填长什么样、几次调用分别花了多久。
 */

/** 造一条"含失败与自愈"的四步轨迹 */
const SCRIPTED_STEPS = [
  {
    index: 1,
    thoughtText: '我先看看知识库里有哪些文档，再决定去哪一份里找。',
    promptTokens: 1180,
    completionTokens: 42,
    elapsedMs: 1420,
    usedTools: true,
    toolCalls: [
      {
        toolName: 'list_documents',
        arguments: '{}',
        stage: 'OK',
        elapsedMs: 12,
        fixableByModel: true,
        countsAsToolFailure: false,
        feedback:
          '【工具「list_documents」的返回结果｜call_id=call_1】\n知识库共 4 份文档：\n\n- ledger-progress.md\n    解析出 162 个块\n    主要章节：学习进度台账、一、总览、二、阶段一 · 大模型对接层（8 个学习点，完成 7 个）\n\n- roadmap-java-to-agent.md\n    解析出 156 个块\n    主要章节：Java 开发者 → AI Agent 岗位 · 专属路线、阶段二（2–4 周）RAG',
      },
    ],
  },
  {
    index: 2,
    thoughtText: '去查分块策略的结论。',
    promptTokens: 1620,
    completionTokens: 55,
    elapsedMs: 1680,
    usedTools: true,
    toolCalls: [
      {
        toolName: 'doc_search',
        arguments: '{"query":"分块策略"}',
        stage: 'UNKNOWN_TOOL',
        elapsedMs: 3,
        // 被拒绝的调用也值得看：报告要回答"模型一共想调几次、其中几次没让它调"
        fixableByModel: true,
        countsAsToolFailure: false,
        feedback:
          '工具调用被拒绝：没有名为「doc_search」的工具。\n你提出的参数是：{"query":"分块策略"}\n可用工具：list_documents、read_section、search_docs\n请从上面这些工具里重新选择。如果你认为缺少一个必要的工具，就直接用文字回答，不要编造工具名。',
      },
    ],
  },
  {
    index: 3,
    thoughtText: '工具名记错了，改成 search_docs 再查一次。',
    promptTokens: 1880,
    completionTokens: 61,
    elapsedMs: 1920,
    usedTools: true,
    toolCalls: [
      {
        toolName: 'search_docs',
        arguments: '{"query":"分块策略 结论","topK":3}',
        stage: 'OK',
        elapsedMs: 47,
        fixableByModel: true,
        countsAsToolFailure: false,
        feedback:
          '【工具「search_docs」的返回结果｜call_id=call_3】\n在 193 个文档片段里找到 3 条相关内容（按相关度排序）：\n\n[1] 章节：学习进度台账 > 二·六、阶段二 · 块大小对齐实验\n    来源：ledger-progress.md  估算 203 token\n    内容：块大小对齐之后，结论反了：结构分块并没有"更省 token"的优势……',
      },
    ],
  },
  {
    index: 4,
    thoughtText: '',
    promptTokens: 2240,
    completionTokens: 156,
    elapsedMs: 2380,
    usedTools: false,
    toolCalls: [],
  },
]

/** 把轨迹摊平成 `/runs/{id}` 那种"步骤 + 工具调用"两份列表的形状 */
function detailFromScript(runId, question, overrides = {}) {
  const steps = SCRIPTED_STEPS.map(step => ({
    index: step.index,
    stepIndex: step.index,
    thoughtText: step.thoughtText,
    promptTokens: step.promptTokens,
    completionTokens: step.completionTokens,
    elapsedMs: step.elapsedMs,
    usedTools: step.usedTools,
    transcriptChars: 1200 + step.index * 430,
    toolCalls: step.toolCalls,
  }))
  const toolCalls = []
  SCRIPTED_STEPS.forEach(step => {
    step.toolCalls.forEach((call, i) => {
      toolCalls.push({ ...call, stepIndex: step.index, callIndex: i, callId: `call_${step.index}_${i}` })
    })
  })
  return {
    ok: true,
    run: {
      id: runId,
      question,
      status: 'ANSWERED',
      stopReason: 'ANSWERED',
      failureCause: '',
      failureAdvice: '',
      finalAnswer:
        '块大小对齐之后的结论是：固定字数硬切全面占优（Recall@5 0.7907 vs 0.7442、MRR 0.6347 vs 0.5244），' +
        '而且三路检索下方向一致。上一轮那句"结构分块省 token"是"它的块更小"的副作用。\n\n' +
        '依据：学习进度台账「二·六 块大小对齐实验」。',
      totalTokens: 7234,
      toolCalls: toolCalls.length,
      elapsedMs: 7402,
      node: 'local',
      ...overrides,
    },
    steps,
    toolCalls,
  }
}

/** 最近几次运行（含一条被中断的 —— 那是"等恢复"提示的触发条件） */
const RUNS = [
  {
    id: 'run-demo-0004',
    question: '分块策略的结论是什么？',
    status: 'ANSWERED',
    stopReason: 'ANSWERED',
    failureCause: null,
    totalTokens: 7234,
    toolCalls: 3,
    elapsedMs: 7402,
    createdAt: '2026-09-15T21:40:12',
    node: 'local',
  },
  {
    id: 'run-demo-0003',
    question: 'token 预算的系数怎么校准的？',
    status: 'ANSWERED',
    stopReason: 'ANSWERED',
    failureCause: null,
    totalTokens: 5108,
    toolCalls: 2,
    elapsedMs: 5210,
    createdAt: '2026-09-15T21:38:02',
    node: 'local',
  },
  {
    id: 'run-demo-0002',
    // 停因是被上限拦下 —— 它不是故障，但没有答案，界面必须区分这两件事
    question: '把所有工具的失败原因都列一遍',
    status: 'STOPPED',
    stopReason: 'MAX_STEPS',
    failureCause: null,
    totalTokens: 11980,
    toolCalls: 6,
    elapsedMs: 21400,
    createdAt: '2026-09-15T21:35:44',
    node: 'local',
  },
  {
    id: 'run-demo-0001',
    // 【这条是刻意放的】失败细分必须显示出来 —— MODEL_ERROR 下面藏着 6 种原因，
    // 而"我们自己的并发闸门拒绝"与"上游鉴权失败"的排查方向完全相反
    question: '介绍一下这个项目的 Agent 是怎么防重复副作用的',
    status: 'FAILED',
    stopReason: 'MODEL_ERROR',
    failureCause: 'LOCAL_CONCURRENCY_LIMIT',
    totalTokens: 2890,
    toolCalls: 1,
    elapsedMs: 3120,
    createdAt: '2026-09-15T21:30:11',
    node: 'local',
  },
  {
    id: 'run-demo-0000',
    // 被中断且没跑完 —— 它在等恢复（默认配置不会自动恢复）
    question: '这个任务会在进程被杀之后接着跑吗？',
    status: 'INTERRUPTED',
    stopReason: null,
    failureCause: null,
    totalTokens: 3310,
    toolCalls: 2,
    elapsedMs: 4180,
    createdAt: '2026-09-15T21:20:03',
    node: 'local',
  },
]

/**
 * 四层保护的快照。
 *
 * 【刻意造出"有拒绝、有熔断"的状态】全 0 的快照看不出界面有没有在工作 ——
 * 而这一块的价值恰恰在于"能看出哪把工具病了"。
 * 所以这里给：一把健康工具、一把正在被限流的工具、一把已经熔断的工具。
 */
const LIMITS = {
  config: {
    maxConcurrentModelRequests: 8,
    maxConcurrentToolRequests: 4,
    acquireTimeoutMs: 5000,
    circuitEnabled: true,
    failureRateThreshold: 0.5,
    minimumCalls: 5,
    windowSize: 20,
    openDurationMs: 10000,
  },
  modelGate: {
    available: true,
    maxConcurrent: 8,
    inFlight: 1,
    peakInFlight: 3,
    acquired: 42,
    rejected: 1,
    averageWaitMs: 12.5,
    maxWaitMs: 208,
  },
  tools: [
    {
      toolName: 'list_documents',
      maxConcurrent: 4,
      inFlight: 0,
      peakInFlight: 2,
      acquired: 18,
      rejected: 0,
      averageWaitMs: 0.4,
      maxWaitMs: 3,
      circuitState: 'CLOSED',
      windowSize: 18,
      failureRate: 0,
      circuitOpenCount: 0,
      shortCircuited: 0,
    },
    {
      toolName: 'read_section',
      maxConcurrent: 4,
      inFlight: 3,
      peakInFlight: 4,
      acquired: 26,
      rejected: 7,
      averageWaitMs: 61.2,
      maxWaitMs: 201,
      // 被限流但**没熔断** —— 这两件事在界面上必须能分开看
      circuitState: 'CLOSED',
      windowSize: 20,
      failureRate: 0.1,
      circuitOpenCount: 0,
      shortCircuited: 0,
    },
    {
      toolName: 'search_docs',
      maxConcurrent: 4,
      inFlight: 0,
      peakInFlight: 4,
      acquired: 31,
      rejected: 12,
      averageWaitMs: 88.4,
      maxWaitMs: 402,
      // 已熔断：下游失败率过高
      circuitState: 'OPEN',
      windowSize: 20,
      failureRate: 0.75,
      circuitOpenCount: 1,
      shortCircuited: 5,
    },
  ],
  totals: {
    toolsTracked: 3,
    totalRejected: 19,
    totalShortCircuited: 5,
    openCircuits: 1,
  },
  note:
    '闸门与熔断都是**全局共享**的：同一把工具被所有任务共用，所以这里的数字不是某一次运行的属性。' +
    '「拒绝」是限流生效（稍后重试即可）；「熔断」是下游已判为不可用（别再用它）。',
}

/**
 * 造一次 `/ask` 的响应。
 *
 * 【故意睡 900ms】理由与 RAG 那边一样：让"正在跑"那个 loading 分支**真的被渲染到一次**，
 * 否则自检里"点完立刻断言结果"会跳过 loading 分支，那条路径就永远没被验证过。
 * 但别睡太久：真后端要十几秒，自检每次多等十几秒并不值得（超时是 180 秒，0.9 秒和 15 秒都在余量内）。
 */
function askResponse(question) {
  const detail = detailFromScript('run-demo-live', question)
  return {
    ok: true,
    runId: detail.run.id,
    question,
    answered: true,
    finalAnswer: detail.run.finalAnswer,
    stopReason: 'ANSWERED',
    failureCause: '',
    failureAdvice: '',
    totalTokens: detail.run.totalTokens,
    elapsedMs: detail.run.elapsedMs,
    toolCallCount: detail.run.toolCalls,
    toolCallSummary: '工具调用 3 次：UNKNOWN_TOOL=1 OK=2 ｜工具侧总耗时 62ms',
    stepSummaries: [
      '第 1 步：调用 [list_documents] ×1（1222 tokens, 1420ms）',
      '第 2 步：调用 [doc_search] ×1（1675 tokens, 1680ms）',
      '第 3 步：调用 [search_docs] ×1（1941 tokens, 1920ms）',
      '第 4 步：直接作答（2396 tokens, 2380ms）',
    ],
    steps: detail.steps,
  }
}

/**
 * 安装 Agent 假端点。返回 true 表示这个请求已被接管。
 *
 * 与 `rag-routes.mjs` 同一个模式：单独一个函数，避免主文件的 if 链被数据淹没。
 */
export function handleAgentRoutes(req, res, url, json) {
  const pathname = url.pathname

  if (req.method === 'POST' && pathname === '/api/agent/ask') {
    // 读一下 body 再回 —— 不读的话 Node 可能在某些情况下不结束请求
    let body = ''
    req.on('data', chunk => {
      body += chunk
    })
    req.on('end', () => {
      let question = '（假后端没有收到问题）'
      try {
        question = JSON.parse(body || '{}').question ?? question
      } catch {
        // 解析失败就用默认值：这是**假后端**，它的职责是让界面能跑起来，
        // 不是严格校验输入（严格校验在真后端，而且那里有测试）
      }
      setTimeout(() => json(res, askResponse(question)), 900)
    })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/agent/runs') {
    json(res, {
      runs: RUNS,
      count: RUNS.length,
      // 1 条 INTERRUPTED —— 界面上那条"等待恢复"的告警靠它触发
      awaitingRecovery: 1,
    })
    return true
  }

  if (req.method === 'GET' && pathname.startsWith('/api/agent/runs/')) {
    const runId = decodeURIComponent(pathname.slice('/api/agent/runs/'.length))
    const known = RUNS.find(r => r.id === runId)
    if (!known) {
      json(res, { ok: false, error: `没有这个运行：${runId}` })
      return true
    }
    const detail = detailFromScript(known.id, known.question, {
      status: known.status,
      stopReason: known.stopReason ?? '',
      failureCause: known.failureCause ?? '',
      totalTokens: known.totalTokens,
      toolCalls: known.toolCalls,
      elapsedMs: known.elapsedMs,
      // 失败那条要带上建议 —— 页面靠它显示"下一步该做什么"
      failureAdvice:
        known.failureCause === 'LOCAL_CONCURRENCY_LIMIT'
          ? '本机并发闸门满了（这是我们自己的限制，不是上游的问题）。稍后重试即可；如果经常出现，按实际配额调大 ai.agent.concurrency.max-concurrent-model-requests。'
          : '',
      finalAnswer: known.status === 'ANSWERED' ? detailFromScript(known.id, known.question).run.finalAnswer : '',
    })
    json(res, detail)
    return true
  }

  if (req.method === 'GET' && pathname === '/api/agent/limits') {
    json(res, LIMITS)
    return true
  }

  return false
}
