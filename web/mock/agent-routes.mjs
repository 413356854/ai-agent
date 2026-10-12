/**
 * Agent 端点的**假数据**（阶段三第 5 件，第九轮补 HITL）。
 *
 * ---------------------------------------------------------------------------
 * 为什么这些假数据要"照着真后端的形状写"，而不是随便编几个数字
 * ---------------------------------------------------------------------------
 * 与 `rag-routes.mjs` 同一条理由：界面自检要给确定性断言，真后端的数字每次都不同。
 * 所以这里的规则也是**数字是假的，形状是真的**：
 *   - `stage` 必须是真后端 `ToolCallStage` 里真有的值
 *     （`OK` / `UNKNOWN_TOOL` / `PARSE_ERROR` / `VALIDATION_ERROR` / `EXECUTION_ERROR`
 *      / `REQUEST_REJECTED` / `CIRCUIT_OPEN` / `AWAITING_APPROVAL` / `APPROVAL_DENIED`
 *      / `APPROVAL_TIMEOUT`）——
 *     编一个不存在的 stage，界面会走到"认不出的 stage"分支，而那条分支在真后端上永远不会走；
 *   - `countsAsToolFailure` 必须与后端的判据一致（**只有 `EXECUTION_ERROR` 是 true**）——
 *     假数据里把它写反，界面上"计入熔断"那一列的着色就是错的，
 *     而自检断言不了"颜色对不对"，只有人工看才会发现；
 *   - `failureCause` 必须是真后端 `AgentFailureCause` 里真有的值；
 *   - 审批的 `status` 必须是真后端 `ApprovalStatus` 里真有的值
 *     （`PENDING` / `APPROVED` / `DENIED` / `TIMED_OUT` / `EXPIRED`）。
 *
 * ---------------------------------------------------------------------------
 * 第九轮：待批是**有状态**的，所以这个假后端带一个内存状态机
 * ---------------------------------------------------------------------------
 * 别处的假数据都是"每次都返回同一份"，而 HITL 不行 —— 因为它的界面自检要验证的是
 * **一条状态转移**：点「批准」→ 那条待批从列表里消失、并且下一次 `/ask` 返回的
 * 轨迹是"写操作执行成功"而不是"停在等人"。
 *
 * 所以这里刻意做成一个极小的状态机（`approvalState`）：
 *   - 初始：有一条 PENDING 的写操作待批（由 `propose_review` 发起）；
 *   - 点「批准」：它变成 APPROVED，并且 `/ask` 的返回切到"批准之后"那条分支；
 *   - 点「拒绝」：它变成 DENIED，`/ask` 的返回切到"被拒绝后自愈"那条分支。
 *
 * 三条纪律仍然是"形状是真的"：状态取值、字段名、字段类型**全部照真后端**；
 * 只是转移规则被简化成确定性的（真后端的转移由 `AgentApprovalService` 的
 * `CompletableFuture` + 库里的 `WHERE status='PENDING'` 保证）。
 */

// ---------------------------------------------------------------------------
//  第九轮：HITL 的假状态（一个极小的状态机，理由见文件头注释）
// ---------------------------------------------------------------------------

/*
  技能目录的候选来自 skill-routes 那一份技能表（同一个导出，见 `mockSkillCatalogOf`）。
  各写一份的话，"agent 这一页说注入了两条、skill 那一页只有一条"只会表现为
  自检里一句对不上的数字，而没人知道是哪一边错了 —— 与 task-routes 读同一份账号表同一条理由。
*/
import { findMockAccountById } from './permission-routes.mjs'
import { mockSkillCatalogOf } from './skill-routes.mjs'

/** 等人时限。与真后端 `ai.agent.approval.timeout` 同一个量纲（真后端默认 120s，假后端给个短的便于演示） */
const TIMEOUT_SECONDS = 120

/**
 * 权限档位的中文标签。
 *
 * <p>与真后端 `AgentPermissionLevel.label()` 逐字一致 —— 界面上那两个地方
 * （单选组与回显行）读的是**同一个词**，所以假数据里换一个说法会让
 * "页面显示的是服务端给的那份还是自己写死的那份"分不出来。
 */
const PERMISSION_LABELS = {
  READ_ONLY: '仅可查看',
  WORKSPACE_WRITE: '工作区内修改',
  FULL_ACCESS: '完全权限',
}

/**
 * 一条待批的写操作。
 *
 * `arguments` 是**同一个字符串**在待批列表、`/ask` 返回、以及批准后执行的那一步里复用 ——
 * 这是刻意的：真后端里"人批的参数"与"执行的参数"必须逐字相同
 * （`ApprovalGrant` 就是按逐字匹配的），假数据也不该出现两者不一致的形态。
 */
const approvalState = {
  id: 'ap-demo-0001',
  runId: 'run-demo-pending',
  stepIndex: 1,
  toolName: 'propose_review',
  arguments: '{"title":"阶段二 · RAG：分块建议与实测结论相反","severity":"MEDIUM"}',
  hints: '写（非幂等，需人工确认），可能破坏性',
  summary: '向人工审查队列提交一条待处理工单（写操作：会在系统里创建一条记录，需要人工确认）。',
  /** null = 还没人处理；'APPROVED' / 'DENIED' = 已决定。初始为 null，也就是"有待批在等人" */
  decision: null,
  decidedBy: null,
  decisionNote: null,
}

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
          '【工具「search_docs」的返回结果｜call_id=call_3】\n在 193 个文档片段里找到 3 条相关内容（按相关度排序）：\n\n[1] 章节：Java 开发者 → AI Agent 岗位 · 专属路线 > 七、周计划（10 周，每周 10–15 小时） > 阶段二（2–4 周）RAG —— 重点在「定位问题」而非「跑通 Demo」\n    来源：roadmap-java-to-agent.md  估算 203 token\n    内容：分块：语义段落 / 标题层级切分 + Overlap，别按固定字数硬切……',
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
        '依据：「阶段二（2–4 周）RAG」一节。',
      totalTokens: 7234,
      toolCalls: toolCalls.length,
      elapsedMs: 7402,
      node: 'local',
      /*
        【运行权限（本次新增）】与 intent / tenantId 同一个规矩：
        真后端在这三个键上返回**空串而不是缺字段**，所以假数据也要给全。
        默认给一档 WORKSPACE_WRITE（运行详情里那一行会显示它 + 工作区），
        调用方要造别的档位就通过 overrides 覆盖
      */
      workspaceDir: 'D:\\work\\docs',
      permissionLevel: 'WORKSPACE_WRITE',
      permissionLabel: PERMISSION_LABELS.WORKSPACE_WRITE,
      ...overrides,
    },
    steps,
    toolCalls,
  }
}

/**
 * 五层 trace 的假数据（阶段四第 2 件）。
 *
 * <h2>形状必须与 `AgentTraceView.of()` **逐字段一致**</h2>
 * 这是这个文件里反复吃过亏的一条（见 `/runs/{id}` 那段关于 `approvals` 层级的注释）：
 * 形状不一致的假数据比没有假数据更坏 —— 自检会红在一个与真实代码无关的地方，
 * 而排查方向会跑到组件上去。
 *
 * <h2>刻意造出的三件事（都是这一件要证明的性质）</h2>
 * <ol>
 *   <li><b>一次检索 + 五条命中</b>：第 3 步那次 search_docs 后面挂着真实形状的命中明细
 *       （名次 / 块 id / 来源 / 标题路径 / 每块 token / 得分 / 摘录）——
 *       界面"检索那一层"有没有画出来，靠它验证；</li>
 *   <li><b>一次 0 命中的检索</b>：第 1 步那次查了个不存在的词。0 条是**正常结果**，
 *       但界面必须把它显示成"这次没查到"，而不是"这一块没有数据" ——
 *       两者对排查的人含义完全相反；</li>
 *   <li><b>一条只列文档、不检索的运行</b>（第 4 步）：那一层为空且 `expected=false`。
 *       界面必须说"这次运行没有检索"，而不是画一个空的表格让人以为漏记了。</li>
 * </ol>
 */
function traceFromScript(runId, known) {
  const isFailed = known && known.status === 'FAILED'
  const isStopped = known && known.status === 'STOPPED'
  const isInterrupted = known && known.status === 'INTERRUPTED'

  /* 会话里共 3 次运行，且必须有一条 current=true —— 界面靠它标出"你在看哪一次" */
  const conversationId = 'conv-mock-0001'
  const conversationRuns = [
    { id: 'run-demo-0003', question: 'token 预算的系数怎么校准的？', status: 'ANSWERED', stopReason: 'ANSWERED', totalTokens: 5108, createdAt: '2026-09-15T21:38:02', current: false },
    { id: 'run-demo-0004', question: '分块策略的结论是什么？', status: 'ANSWERED', stopReason: 'ANSWERED', totalTokens: 7234, createdAt: '2026-09-15T21:40:12', current: false },
    { id: 'run-demo-0005', question: '那个自相矛盾的地方是哪一处？', status: 'ANSWERED', stopReason: 'ANSWERED', totalTokens: 3110, createdAt: '2026-09-15T21:42:30', current: false },
  ].map(r => ({ ...r, current: r.id === runId }))
  // 被查的那个运行也要出现在会话里 —— 否则"我在看的那次"标不出来
  if (!conversationRuns.some(r => r.current)) {
    conversationRuns.push({
      id: runId,
      question: (known && known.question) || '（未命名运行）',
      status: (known && known.status) || 'RUNNING',
      stopReason: (known && known.stopReason) || '',
      totalTokens: (known && known.totalTokens) || 0,
      createdAt: (known && known.createdAt) || '2026-09-15T21:44:00',
      current: true,
    })
  }

  const retrievalCallStep3 = {
    stepIndex: 3,
    callIndex: 0,
    toolName: 'search_docs',
    query: '分块策略 结论',
    topK: 3,
    hitCount: 3,
    indexSize: 516,
    hitTokens: 612,
    elapsedMs: 47,
    shortOfTopK: false,
    hits: [
      {
        rank: 1,
        chunkId: 'roadmap-java-to-agent.md#31',
        documentId: 'roadmap-java-to-agent.md',
        breadcrumb: 'Java 开发者 → AI Agent 岗位 · 专属路线 > 七、周计划（10 周，每周 10–15 小时） > 阶段二（2–4 周）RAG —— 重点在「定位问题」而非「跑通 Demo」',
        tokenCount: 203,
        score: 14.8271,
        excerpt: '分块：语义段落 / 标题层级切分 + Overlap，别按固定字数硬切……',
      },
      {
        rank: 2,
        chunkId: 'roadmap-java-to-agent.md#34',
        documentId: 'roadmap-java-to-agent.md',
        breadcrumb: 'Java 开发者 → AI Agent 岗位 · 专属路线 > 七、周计划（10 周，每周 10–15 小时） > 阶段二（2–4 周）RAG —— 重点在「定位问题」而非「跑通 Demo」',
        tokenCount: 218,
        score: 11.2309,
        excerpt: '验收：能用数字说明「换分块策略 / 加 Rerank 提升了多少」，而不是「好像变好了」……',
      },
      {
        rank: 3,
        chunkId: 'ledger-progress.md#55',
        documentId: 'ledger-progress.md',
        breadcrumb: '学习进度台账 > 五、下一步计划（下次从这里开始）',
        tokenCount: 191,
        score: 8.6644,
        excerpt: '建 30–50 条评测集 —— 阶段二的硬性产出……',
      },
    ],
  }

  /* 第 1 步那次 0 命中的检索：query / indexSize 都在，hits 是空数组 */
  const retrievalCallStep1Empty = {
    stepIndex: 1,
    callIndex: 1,
    toolName: 'search_docs',
    query: 'qqzzxxvvjwkp',
    topK: 3,
    hitCount: 0,
    indexSize: 516,
    hitTokens: 0,
    elapsedMs: 12,
    shortOfTopK: true,
    hits: [],
  }

  const steps = [
    {
      stepIndex: 1,
      thoughtText: '先把工具名记错的教训用上：查一个不存在的词看看它会不会自己纠偏。',
      promptTokens: 1520,
      completionTokens: 44,
      totalTokens: 1564,
      cacheReadTokens: 1200,
      modelName: 'deepseek-flash',
      elapsedMs: 1180,
      promptFingerprint: 'v3:9ab12cd34ef5',
      transcriptChars: 1630,
      toolCalls: [
        {
          callIndex: 0,
          callId: 'call_1_0',
          toolName: 'doc_search',
          arguments: '{"query":"分块策略"}',
          stage: 'UNKNOWN_TOOL',
          countsAsToolFailure: false,
          elapsedMs: 1,
          feedback: '工具调用被拒绝：没有名为「doc_search」的工具。\n可用工具：list_documents、read_section、search_docs',
          retrievals: [],
        },
        {
          callIndex: 1,
          callId: 'call_1_1',
          toolName: 'search_docs',
          arguments: '{"query":"qqzzxxvvjwkp","topK":3}',
          stage: 'OK',
          countsAsToolFailure: false,
          elapsedMs: 12,
          feedback: '在 516 个文档片段里没有找到与「qqzzxxvvjwkp」相关的内容。\n建议：换用更宽泛的关键词、或改用文档里可能出现的同义词再试一次。',
          retrievals: [retrievalCallStep1Empty],
        },
      ],
      retrievalCalls: [retrievalCallStep1Empty],
      retrievalCount: 1,
      retrievalHitTokens: 0,
    },
    {
      stepIndex: 2,
      thoughtText: '这个查询词太窄了，换成文档里会出现的说法再查一次。',
      promptTokens: 1880,
      completionTokens: 61,
      totalTokens: 1941,
      cacheReadTokens: 1500,
      modelName: 'deepseek-flash',
      elapsedMs: 1920,
      promptFingerprint: 'v3:9ab12cd34ef5',
      transcriptChars: 2490,
      toolCalls: [
        {
          callIndex: 0,
          callId: 'call_2_0',
          toolName: 'read_section',
          arguments: '{"documentId":"roadmap-java-to-agent.md","section":"阶段二（2–4 周）RAG"}',
          stage: 'OK',
          countsAsToolFailure: false,
          elapsedMs: 3,
          feedback: '【工具「read_section」的返回结果】已读取 roadmap-java-to-agent.md 的「七、周计划 > 阶段二（2–4 周）RAG」一节。',
          retrievals: [],
        },
      ],
      retrievalCalls: [],
      retrievalCount: 0,
      retrievalHitTokens: 0,
    },
    {
      stepIndex: 3,
      thoughtText: '工具名记错了，改成 search_docs 再查一次。',
      promptTokens: 2040,
      completionTokens: 58,
      totalTokens: 2098,
      cacheReadTokens: 1700,
      modelName: 'deepseek-flash',
      elapsedMs: 1860,
      promptFingerprint: 'v3:9ab12cd34ef5',
      transcriptChars: 3120,
      toolCalls: [
        {
          callIndex: 0,
          callId: 'call_3_0',
          toolName: 'search_docs',
          arguments: '{"query":"分块策略 结论","topK":3}',
          stage: 'OK',
          countsAsToolFailure: false,
          elapsedMs: 47,
          feedback: '在 516 个文档片段里找到 3 条相关内容（按相关度排序）：\n\n[1] 章节：Java 开发者 → AI Agent 岗位 · 专属路线 > 七、周计划（10 周，每周 10–15 小时） > 阶段二（2–4 周）RAG —— 重点在「定位问题」而非「跑通 Demo」',
          retrievals: [retrievalCallStep3],
        },
      ],
      retrievalCalls: [retrievalCallStep3],
      retrievalCount: 1,
      retrievalHitTokens: 612,
    },
    {
      stepIndex: 4,
      thoughtText: '',
      promptTokens: 2240,
      completionTokens: 156,
      totalTokens: 2396,
      cacheReadTokens: 1900,
      modelName: 'deepseek-flash',
      elapsedMs: 2380,
      promptFingerprint: 'v3:9ab12cd34ef5',
      transcriptChars: 4180,
      toolCalls: [],
      retrievalCalls: [],
      retrievalCount: 0,
      retrievalHitTokens: 0,
    },
  ]

  // 被上限拦下 / 失败 / 中断的运行：把 trace 截到它真正跑到的那一步 ——
  // 【这一步很重要】一个"停在第 2 步"的运行如果在假数据里显示 4 步，
  // 自检就会以为界面读错了数据，而真相是假数据不自洽
  const visibleSteps = isInterrupted ? steps.slice(0, 1) : isStopped ? steps.slice(0, 2) : isFailed ? steps.slice(0, 2) : steps
  const retrievals = visibleSteps.flatMap(s => s.retrievalCalls)
  const hitTokens = retrievals.reduce((sum, r) => sum + r.hitTokens, 0)
  const hitCount = retrievals.reduce((sum, r) => sum + r.hitCount, 0)
  const promptTokens = visibleSteps.reduce((sum, s) => sum + s.promptTokens, 0)

  return {
    ok: true,
    run: {
      id: runId,
      question: (known && known.question) || '（未命名运行）',
      status: (known && known.status) || 'ANSWERED',
      stopReason: (known && known.stopReason) || 'ANSWERED',
      failureCause: (known && known.failureCause) || '',
      failureAdvice: '',
      finalAnswer: known && known.status === 'ANSWERED' ? '块大小对齐之后的结论是：固定字数硬切全面占优。' : '',
      totalTokens: (known && known.totalTokens) || 7234,
      toolCalls: (known && known.toolCalls) || 3,
      elapsedMs: (known && known.elapsedMs) || 7402,
      node: 'local',
      intent: '文档问答',
      // 【第 6 件】租户与业务是两个维度，trace 两个都带（与真后端 AgentTraceView 一致）
      tenantId: '示例租户',
      // 【本次新增】工作区与档位同样两个都带。
      // trace 是排查入口，而"完全权限档位下写操作不产生审核请求"意味着
      // 一张空的审批表有两种含义 —— 区分它们的唯一依据就是这两列。
      // 取值从 known 那一行来（真后端 trace 读的就是同一条 agent_run）
      workspaceDir: (known && known.workspaceDir) || '',
      permissionLevel: (known && known.permissionLevel) || '',
      permissionLabel: PERMISSION_LABELS[(known && known.permissionLevel) || ''] ?? '',
      createdAt: (known && known.createdAt) || '2026-09-15T21:40:12',
      updatedAt: '2026-09-15T21:40:19',
    },
    conversation: {
      present: true,
      id: conversationId,
      runCount: conversationRuns.length,
      truncated: false,
      runs: conversationRuns,
    },
    steps: visibleSteps,
    retrieval: {
      calls: retrievals.length,
      hitCount,
      hitTokens,
      hitTokensEstimated: true,
      emptyCalls: retrievals.filter(r => r.hitCount === 0).length,
      steps: visibleSteps.length,
      promptTokensForShare: promptTokens,
      shareOfPrompt: promptTokens === 0 ? 0 : Math.round((hitTokens * 10000) / promptTokens) / 10000,
      note: 'hitTokens 是估算的（来自块自带的估算器计数），与成本看板那边「来自上游 usage」的 token 不是同一个口径。',
    },
    cost: {
      // 【刻意的默认态】价格表在本项目里默认是空的 —— 所以这里 `amount` 必须是 null，
      // 而不是 0。假数据里写一个数字的话，那面"算不出来就显示算不出"的红旗
      // 就永远不会被自检看到，而它恰恰是成本那一块最重要的一条纪律
      pricingConfigured: false,
      currency: 'CNY',
      uncachedPromptTokens: promptTokens - visibleSteps.reduce((s, x) => s + x.cacheReadTokens, 0),
      cachedPromptTokens: visibleSteps.reduce((s, x) => s + x.cacheReadTokens, 0),
      completionTokens: visibleSteps.reduce((s, x) => s + x.completionTokens, 0),
      totalTokens: promptTokens + visibleSteps.reduce((s, x) => s + x.completionTokens, 0),
      byModel: {
        'deepseek-flash': {
          model: 'deepseek-flash',
          uncachedPromptTokens: promptTokens - visibleSteps.reduce((s, x) => s + x.cacheReadTokens, 0),
          cachedPromptTokens: visibleSteps.reduce((s, x) => s + x.cacheReadTokens, 0),
          completionTokens: visibleSteps.reduce((s, x) => s + x.completionTokens, 0),
          amount: null,
          amountMicros: null,
          amountReason: '没有匹配的价格规则（model=deepseek-flash）—— 在 ai.cost.prices 里补一条',
        },
      },
      unpricedModels: ['deepseek-flash'],
      amount: null,
      amountMicros: null,
      amountReason: '价格未配置（ai.cost.prices 是空的）—— 这里刻意不显示 0：0 会被读成「这次不花钱」，而真相是「我们不知道它花多少」',
    },
    approvals: [],
    layers: [
      { index: 1, name: '会话', key: 'conversation', storage: 'agent_run.conversation_id', expected: true },
      { index: 2, name: '轮次 / 运行', key: 'run', storage: 'agent_run', expected: true },
      { index: 3, name: 'LLM 调用', key: 'step', storage: 'agent_step', expected: visibleSteps.length > 0 },
      { index: 4, name: '工具调用', key: 'toolCall', storage: 'agent_tool_invocation', expected: visibleSteps.some(s => s.toolCalls.length > 0) },
      { index: 5, name: '检索', key: 'retrieval', storage: 'retrieval_call + retrieval_hit', expected: retrievals.length > 0 },
    ],
    notes: [
      '提示词指纹形如 v3:9ab12cd34ef5 —— 版本号是「指针」、指纹是「内容」。',
      '检索明细里的 token 是估算的（块自带的估算器计数），用来回答「RAG 大概占了输入多大一块」。',
    ],
  }
}

/**
 * 最近几次运行（含一条被中断的 —— 那是"等恢复"提示的触发条件）。
 *
 * <h2>★ 每一行都带 intent 与 tenantId（阶段四第 6 件）</h2>
 * 真后端从这一件起在这两个键上返回**空串而不是缺字段**（未标注时也是空串），
 * 所以假数据必须照同一个形状给 —— 漏了的话，"界面按这两个字段渲染"这件事
 * 在假后端路径上永远走不到（`undefined` 与 `''` 在界面上长得不一样，
 * 而那种差别只在真路径上现形）。
 *
 * <p>这五行的两个维度刻意**各不相同**（有的只有租户、有的只有业务、有的都没有）：
 * 一一对应的话，"两个维度是独立的"这个结构在自检里就走不到。
 *
 * <h2>★ 工作区目录与权限档位（本次新增）也照同一个规矩给</h2>
 * 真后端在这两个键上返回**空串而不是缺字段**（没有权限上下文时也是空串），
 * 所以假数据必须有几行是空串 —— 否则"界面上那两个格子显示「未记录」"
 * 这条分支在假后端路径上永远走不到，而它正是老数据/外部调用的形状。
 *
 * <p>有一条刻意写成 {@code FULL_ACCESS}：那一档在页面上是**红色标签**，
 * 而它是"这次为什么没有待批"的唯一线索 —— 不造一条的话，那条渲染与着色
 * 在自检里没有任何东西覆盖。
 */
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
    intent: '文档问答',
    tenantId: '示例租户',
    workspaceDir: 'D:\\work\\docs',
    permissionLevel: 'READ_ONLY',
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
    // 只有业务归因、没有租户 —— 两个维度的独立性靠这种组合验证
    intent: '成本排查',
    tenantId: '',
    // 【完全权限】列表里它会渲染成红色标签，而这一行是"这次为什么没有待批"的唯一线索
    workspaceDir: 'D:\\work\\reports',
    permissionLevel: 'FULL_ACCESS',
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
    // 只有租户、没有业务归因（反过来的那一半）
    intent: '',
    tenantId: '内部试用',
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
    // 两个都没有（curl 直接调用的形状）
    intent: '',
    tenantId: '',
    // 工作区与档位也都没有 —— 这一个形状**必须**出现在假数据里：
    // 真后端的老数据与外部链路就是这样，而界面上那两个格子要如实显示「未记录」，
    // 不编一个默认档位（编出来的那句看起来具体、实际是假的）
    workspaceDir: '',
    permissionLevel: '',
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
    intent: '闲聊',
    tenantId: '示例租户',
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
    '闸门与熔断都是全局共享的：同一把工具被所有任务共用，所以这里的数字不是某一次运行的属性。' +
    '「拒绝」是限流生效（稍后重试即可）；「熔断」是下游已判为不可用（别再用它）。',
}

/**
 * 造一次 `/ask` 的响应。
 *
 * 【故意睡 900ms】理由与 RAG 那边一样：让"正在跑"那个 loading 分支**真的被渲染到一次**，
 * 否则自检里"点完立刻断言结果"会跳过 loading 分支，那条路径就永远没被验证过。
 * 但别睡太久：真后端要十几秒，自检每次多等十几秒并不值得（超时是 180 秒，0.9 秒和 15 秒都在余量内）。
 *
 * @param workspaceDir 请求体里带来的工作区目录（**原样回显**，与真后端一样）
 * @param permissionLevel 请求体里带来的档位枚举名（原样回显）
 * @param executorId 请求体里带来的执行者 id；它决定下面那一节技能目录
 */
function askResponse(question, workspaceDir = '', permissionLevel = '', executorId = null) {
  /*
    【本次新增：这一次注入的技能】
    真后端按「执行者 → 他的角色 → 角色目录 → 那些目录下的技能」筛出一份目录，
    把 name + desc 拼进系统提示词，正文由 `get_skill_content` 按需取。
    这里的候选来自 skill-routes 那一份技能表（同一个导出，见 `mockSkillCatalogOf`）——
    **不做前缀比较的第二份实现**：判据只有那一处，否则
    "agent 这一页说注入了两条、skill 那一页只有一条"就没人说得清是哪边错了。
  */
  const skills = mockSkillCatalogOf(executorId)
  const executor = executorId == null ? null : findMockAccountById(Number(executorId))
  /**
   * 【第九轮】`/ask` 的返回按**审批状态**分三条分支。
   *
   * 这不只是为了好看：真后端上这三条分支的行为完全不同，而它们恰好是
   * "HITL 到底有没有拦住东西"的唯一可观察证据 ——
   * 界面自检要能分别验证"停在等人""批准后执行""拒绝后没执行"三种结局。
   *
   * 用一个局部变量取一次快照（而不是在几个分支里各读一次全局状态）：
   * 那样在"人正好在这中间点了批准"时会读到两种状态，构造出一个真后端不可能出现的响应。
   */
  const decision = approvalState.decision
  const detail = detailFromScript('run-demo-live', question)
  const base = {
    ok: true,
    runId: detail.run.id,
    question,
    totalTokens: detail.run.totalTokens,
    elapsedMs: detail.run.elapsedMs,
    toolCallCount: detail.run.toolCalls,
    /*
      【本次新增】把请求体里的执行前提**原样回显**，与真后端的 `assemble(...)` 一致。

      三条都带上是刻意的：真后端回的是**服务端实际用的那一份**（目录被规范化过），
      而界面上显示的是这一份 —— 所以假数据也不能自己编一个，
      否则"界面显示的是服务端那份还是自己填的那份"这件事就分不出来了。
    */
    workspaceDir,
    permissionLevel,
    permissionLabel: PERMISSION_LABELS[permissionLevel] ?? '',
    permissionNote: workspaceDir ? `${PERMISSION_LABELS[permissionLevel] ?? '未记录'} · ${workspaceDir}` : '',
    /*
      【本次注入的技能】字段名与真后端 `AgentController.assemble` 逐字对应。
      没有执行者时 `skillExecutor` 是空串、技能为空数组 —— 与真后端一样：
      界面据此显示"这次没有注入技能（没指定执行者）"，而不是一片空白。
    */
    skillExecutor: executor ? `${executor.displayName || executor.name}(#${executor.id})` : '',
    skillFolders: [...new Set(skills.map(s => s.folder))].sort(),
    skillCount: skills.length,
    skillTruncated: false,
    skillTotalMatched: skills.length,
    skillNote: skills.length === 0
      ? (executorId == null ? '未指定执行者 → 本次不注入技能' : '这个人的角色目录下没有技能（目录 0 个）')
      : `${executor.displayName || executor.name}：${new Set(skills.map(s => s.folder)).size} 个文件夹 / ${skills.length} 条技能`,
    skills: skills.map(s => ({
      id: s.id,
      folder: s.folder,
      name: s.name,
      description: s.description ?? '',
      ownerId: s.ownerId,
      ownerName: s.ownerName,
    })),
    stepSummaries: [
      '第 1 步：调用 [list_documents] ×1（1222 tokens, 1420ms）',
      '第 2 步：调用 [doc_search] ×1（1675 tokens, 1680ms）',
      '第 3 步：调用 [search_docs] ×1（1941 tokens, 1920ms）',
      '第 4 步：直接作答（2396 tokens, 2380ms）',
    ],
  }

  // ---- 分支 ①：还没人做过决定 → 任务停在写操作上等人确认 ----
  //
  // 【这一条分支必须存在】它是 HITL 界面的**主场景**：如果假后端只造"批准之后"的结局，
  // 那"待我确认"那张卡片在自检里永远不会被渲染到 —— 而它正是这一轮全部工作的落点。
  if (decision === null) {
    return {
      ...base,
      answered: false,
      finalAnswer: '',
      stopReason: 'WAITING_FOR_APPROVAL',
      failureCause: '',
      failureAdvice: '',
      toolCallSummary: '工具调用 1 次：AWAITING_APPROVAL=1 ｜工具侧总耗时 8ms',
      waitingApproval: true,
      approvalId: approvalState.id,
      approvalToolName: 'propose_review',
      approvalArguments: approvalState.arguments,
      approvalHints: approvalState.hints,
      approvalTimeoutSeconds: TIMEOUT_SECONDS,
      // 停在写操作上的那一步**不落进 steps**（它还没有结束）—— 真后端也是这样，
      // 于是时间线只有前面几步（这里造的是"第 1 步就提出写操作"的形态，所以是空的）
      steps: [],
    }
  }

  // ---- 分支 ②：批准之后 → 写操作真的执行了 ----
  if (decision === 'APPROVED') {
    /*
      【这里刻意返回"原来的四步轨迹 + 写操作那一步"，而不是只有写操作那一步】

      踩过一次，值得记：第一版只返回了写操作那一步，于是界面上
      "执行过程时间线"只有 1 步、"被拒绝的调用还在不在"这类断言全红 ——
      而它们**本来是这一页最该被验证的东西**（模型怎么选、失败在哪一步、回填长什么样）。

      这里的选择是**保留那条含失败与自愈的轨迹**，再把它与"人在闸门上的介入"接在一起。
      理由不只是"让断言通过"，而是它更接近真后端上会发生的事：
      模型先查文档（可能编错工具名、自愈），查到问题之后再决定建工单。
      一条只有"建工单"一步的轨迹，反而看不出 Agent 到底会不会判断。
    */
    return {
      ...base,
      answered: true,
      finalAnswer:
        '工单已经建好了（id: RV-0001，标题「阶段二 · RAG：分块建议与实测结论相反」，级别 MEDIUM）。\n\n' +
        '这是一次写操作，按规矩它先停在了人工确认闸门上；有人在界面上点了批准之后它才真正执行 —— ' +
        '执行结论里带着那条审批的 id，所以事后可以从工单倒查到"是哪一次审批放行了它"。',
      stopReason: 'ANSWERED',
      failureCause: '',
      failureAdvice: '',
      // 四步轨迹 + 写操作那一步 = 五步。前四步里含一次"编错工具名被拒 → 改对"的自愈过程
      toolCallSummary: '工具调用 4 次：UNKNOWN_TOOL=1 OK=3 ｜工具侧总耗时 70ms',
      steps: [...atScriptedSteps(), WRITE_APPROVED_STEP],
    }
  }

  // ---- 分支 ③：拒绝之后 → 写操作**没有执行**，模型换了方案 ----
  return {
    ...base,
    answered: true,
    finalAnswer:
      '没有建工单 —— 人工确认被拒绝了，理由是「这条已经有对应的工单了，别重复建」。\n\n' +
      '我按回填的提示改了做法：把这个问题直接写在这里，而不是再建一条记录。',
    stopReason: 'ANSWERED',
    failureCause: '',
    failureAdvice: '',
    toolCallSummary: '工具调用 1 次：APPROVAL_DENIED=1 ｜工具侧总耗时 8ms',
    steps: [WRITE_DENIED_STEP],
  }
}

/** 原来的四步轨迹（列目录 → 编错工具名被拒 → 改对 → 作答），供各分支复用。 */
function atScriptedSteps() {
  return SCRIPTED_STEPS
}

/**
 * 写操作在三种结局下的那一步。
 *
 * 三条都保留**同一份参数**（`approvalState.arguments`）—— 这是刻意的：
 * 它让"人批的就是被执行的那一份"这件事在假数据里也成立，
 * 而不是"批准之后模型换了个参数"（那在真后端会触发**重新审批**，是另一条路径）。
 */
const WRITE_APPROVED_STEP = {
  index: 5,
  thoughtText: '文档里那个自相矛盾的地方值得让人跟进一下，我提交一条审查工单。',
  promptTokens: 1520,
  completionTokens: 64,
  elapsedMs: 2140,
  usedTools: true,
  toolCalls: [
    {
      toolName: 'propose_review',
      arguments: approvalState.arguments,
      stage: 'OK',
      elapsedMs: 8,
      fixableByModel: true,
      countsAsToolFailure: false,
      feedback:
        '【工具「propose_review」的返回结果｜call_id=call_1】\n工单已提交成功（记录已写入）。\n' +
        '- 工单 id：RV-0001\n- 标题：阶段二 · RAG：分块建议与实测结论相反\n- 级别：MEDIUM\n' +
        '请在最终回答里告诉用户这个 id —— 那是他后续跟进这件事的凭据。',
    },
  ],
}

const WRITE_DENIED_STEP = {
  index: 1,
  thoughtText: '文档里那个自相矛盾的地方值得让人跟进一下，我提交一条审查工单。',
  promptTokens: 1520,
  completionTokens: 64,
  elapsedMs: 8430,
  usedTools: true,
  toolCalls: [
    {
      toolName: 'propose_review',
      arguments: approvalState.arguments,
      stage: 'APPROVAL_DENIED',
      elapsedMs: 8430,
      /**
       * ⚠️ `fixableByModel: false` 是**真后端的判据**，不是随手写的：
       * 人工拒绝属于"原样重提会被再拦一次"，所以它**不值得再给模型一轮**。
       * 假数据里写成 true，界面上就不会体现这个区别，
       * 而"哪一类失败值得再给一轮"正是这一页要讲的东西之一。
       */
      fixableByModel: false,
      countsAsToolFailure: false,
      feedback:
        '工具「propose_review」的人工确认被拒绝，所以这次没有执行（审批编号：ap-demo-0001）。\n' +
        '原因：这条已经有对应的工单了，别重复建\n' +
        '⚠️ 不要再重新发起同样的调用：它会被再次拦住，需要人再确认一次，' +
        '而这不会比现在更快得到批准。\n' +
        '请改用只读工具收集更多信息、换一个不涉及写操作的方案，' +
        '或者直接把情况告诉用户（说明你本来打算做什么、为什么被拦下）。',
    },
  ],
}

/**
 * 安装 Agent 假端点。返回 true 表示这个请求已被接管。
 *
 * 与 `rag-routes.mjs` 同一个模式：单独一个函数，避免主文件的 if 链被数据淹没。
 *
 * ⚠️ **匹配顺序有讲究**，而它踩过一次的那种坑很典型：
 * 下面 `/api/agent/runs/{id}` 用的是 `startsWith` 前缀匹配，所以
 * `/api/agent/runs/{id}/approvals` 会被它**先吃掉**（于是详情端点收到一个带斜杠的 runId，
 * 返回"没有这个运行"）。所以"更具体的路径必须写在更宽的前缀之前" ——
 * 这不是风格问题，顺序反了的现象是"子路径永远 404"，而排查方向会跑到前端路由上去。
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
      // 业务归因键（阶段四成本看板）：假后端不拿它做任何事 ——
      // **刻意如此**，因为真实链路里它只是被写进 agent_run.intent 的一个字符串，
      // 不参与任何判断。假后端在这里"顺手也做点什么"反而会造出一个真后端没有的行为
      let intent = ''
      // 租户（阶段四第 6 件）与归因键同一条规矩：假后端**不拿它做任何事**，
      // 因为真实链路里它也只是被写进 agent_run.tenant_id 的一个字符串。
      // 这里解析它只有一个目的：让"请求体里字段名写错了"这件事在假路径上也现形
      // （它同时是"注入的技能与执行者对不对得上"唯一的读数）
      let tenantId = ''
      /*
        【运行权限（本次新增）：这两个字段与上面三个不同，假后端要**用**它们】
        真后端拿它们做三件事：必填校验、权限判定、落库后回显。
        假后端这边只需要做第三件（回显），但**必须真的从请求体里读** ——
        假数据里写死一个档位的话，"界面选的档位有没有真的传出去"这件事
        在假路径上永远验不到，而它恰恰是这一次最该被验证的东西
        （请求体字段名写错 = 后端按默认档位跑，界面上却显示你选了的那一档）。
      */
      let workspaceDir = ''
      let permissionLevel = ''
      /*
        【执行者（本次新增）】请求体里的 `executorId`。
        它与上面两个字段不同的一点：**假后端要拿它去筛技能**（真后端也是），
        所以"字段名写错了"在这里的表现不是一句报错，而是"技能那一节空了" ——
        自检里那条断言（注入条数与执行者对上）正是为了接住它。
      */
      let executorId = null
      try {
        const parsed = JSON.parse(body || '{}')
        question = parsed.question ?? question
        intent = parsed.intent ?? ''
        tenantId = parsed.tenantId ?? ''
        workspaceDir = parsed.workspaceDir ?? ''
        permissionLevel = parsed.permissionLevel ?? ''
        executorId = parsed.executorId ?? null
      } catch {
        // 解析失败就用默认值：这是**假后端**，它的职责是让界面能跑起来，
        // 不是严格校验输入（严格校验在真后端，而且那里有测试）
      }
      void intent
      void tenantId
      setTimeout(() => json(res, askResponse(question, workspaceDir, permissionLevel, executorId)), 900)
    })
    return true
  }

  // ---- HITL：审批闸门的状态 ----
  //
  // ⚠️ 这一条必须在 `/api/agent/approvals/{id}/decide` **之前**吗？不必 ——
  // 那一条是精确匹配，两者不会撞。放在这里只是让 4 个审批端点在文件里连成一片，便于对照。
  if (req.method === 'GET' && pathname === '/api/agent/approvals/status') {
    json(res, {
      enabled: true,
      timeoutSeconds: TIMEOUT_SECONDS,
      defaultDecider: 'unknown',
      pendingCount: approvalState.decision === null ? 1 : 0,
      describe:
        approvalState.decision === null
          ? `已启用：写操作等人确认，最长 ${TIMEOUT_SECONDS}s；当前待批 1 条`
          : `已启用：写操作等人确认，最长 ${TIMEOUT_SECONDS}s；当前待批 0 条`,
    })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/agent/approvals') {
    json(res, {
      // 已决定之后列表就空了 —— 这正是"点批准，那条待批消失"那条状态转移的证据
      pending: approvalState.decision === null ? [pendingRow()] : [],
      count: approvalState.decision === null ? 1 : 0,
      enabled: true,
      timeoutSeconds: TIMEOUT_SECONDS,
      note:
        approvalState.decision === null
          ? `已启用：写操作等人确认，最长 ${TIMEOUT_SECONDS}s；当前待批 1 条`
          : `已启用：写操作等人确认，最长 ${TIMEOUT_SECONDS}s；当前待批 0 条`,
      howToDecide:
        'POST /api/agent/approvals/{id}/decide  body: {"approve":true|false,"decidedBy":"你的名字","note":"理由（拒绝时必填）"}',
    })
    return true
  }

  if (req.method === 'POST' && /^\/api\/agent\/approvals\/[^/]+\/decide$/.test(pathname)) {
    const approvalId = decodeURIComponent(pathname.split('/')[4])
    let body = ''
    req.on('data', chunk => {
      body += chunk
    })
    req.on('end', () => {
      let approve = false
      let decidedBy = 'unknown'
      let note = ''
      try {
        const parsed = JSON.parse(body || '{}')
        /*
          【`?? false` 而不是 `?? true`】缺字段时的默认值必须是**拒绝**方向 ——
          真后端就是这样（`DecideRequest.approve` 是原始 boolean，缺字段 Jackson 按 false）。
          假后端里写反的话，自检会看到"批准了"，而真后端上同一个请求是拒绝 ——
          那是最坏的一种假数据：它让测试通过，却掩盖了真实行为。
        */
        approve = parsed.approve ?? false
        decidedBy = parsed.decidedBy || 'unknown'
        note = parsed.note || ''
      } catch {
        // 同 `/ask`：假后端不严格校验输入
      }

      if (approvalId !== approvalState.id) {
        json(res, { ok: false, error: `没有这条人工审批记录：${approvalId}` })
        return
      }
      if (approvalState.decision !== null) {
        // 真后端的这条分支是**抛异常**（"不能重复决定"），
        // 假后端返回同样的形状，好让界面那条"没有被采纳"的分支也能被验证到
        json(res, {
          ok: false,
          error: `审批 #${approvalId} 已经是 ${approvalState.decision}，不能重复决定（重复决定会让审计记录与真实执行对不上）`,
        })
        return
      }

      approvalState.decision = approve ? 'APPROVED' : 'DENIED'
      approvalState.decidedBy = decidedBy
      approvalState.decisionNote = note

      /*
        【`resumed` 恒为 true、`awakened` 恒为 false】这是假后端与真后端的一处
        **已知的形状差异**，必须写下来：真后端上如果那个 `/ask` 请求的线程还在等，
        决定会当场唤醒它（`awakened=true`），任务在同一个请求里跑完。
        假后端没有"正在等的线程"这个对象（`/ask` 900 毫秒就返回了），
        所以它模拟的是另一条路径：**没人等 → 从检查点接着跑**。
        两条分支的字段名与含义一致，界面两种都能渲染。
      */
      json(res, {
        ok: true,
        approvalId,
        status: approvalState.decision,
        awakened: false,
        resumed: true,
        stopReason: 'ANSWERED',
        totalTokens: 1820,
      })
    })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/agent/runs') {
    json(res, {
      // 把那条"停在等人确认"的运行也放进列表 —— 否则界面上那个 magenta 标签
      // （WAITING_APPROVAL）永远没机会被验证到
      runs: approvalState.decision === null ? [pendingRunRow(), ...RUNS] : RUNS,
      count: approvalState.decision === null ? RUNS.length + 1 : RUNS.length,
      // 1 条 INTERRUPTED —— 界面上那条"等待恢复"的告警靠它触发
      awaitingRecovery: 1,
    })
    return true
  }

  /*
    ⚠️ 这一条必须在下面那条更宽的 `startsWith('/api/agent/runs/')` 之前 ——
    否则 `runId` 会变成 `${id}/trace`，详情端点收到一个带斜杠的 id。
    与 `/approvals` 那一条是同一条规矩（见函数头注释）。
  */
  if (req.method === 'GET' && pathname.endsWith('/trace') && pathname.startsWith('/api/agent/runs/')) {
    const runId = decodeURIComponent(pathname.slice('/api/agent/runs/'.length, -'/trace'.length))
    const known = RUNS.find(r => r.id === runId)
    const isPendingRun = runId === approvalState.runId
    if (!known && !isPendingRun) {
      json(res, { ok: false, error: `没有这个运行：${runId}` })
      return true
    }
    const source = known ?? { id: runId, question: '把文档里那个自相矛盾的地方提交成一条审查工单' }
    // trace 那一层的运行记录也跟着这一行的档位走（真后端读的是同一行 agent_run）
    json(res, traceFromScript(source.id, known ?? {
      id: runId,
      question: source.question,
      status: 'WAITING_APPROVAL',
      workspaceDir: 'D:\\work\\docs',
      permissionLevel: 'READ_ONLY',
    }))
    return true
  }

  // ⚠️ 这一条必须在下面那条更宽的 `startsWith('/api/agent/runs/')` 之前（见函数头注释）
  if (req.method === 'GET' && pathname.endsWith('/approvals') && pathname.startsWith('/api/agent/runs/')) {
    const runId = decodeURIComponent(pathname.slice('/api/agent/runs/'.length, -'/approvals'.length))
    const rows = runApprovals(runId)
    json(res, {
      approvals: rows,
      count: rows.length,
      pending: rows.filter(r => r.pending).length,
    })
    return true
  }

  if (req.method === 'GET' && pathname.startsWith('/api/agent/runs/')) {
    const runId = decodeURIComponent(pathname.slice('/api/agent/runs/'.length))
    const known = RUNS.find(r => r.id === runId)
    const isPendingRun = runId === approvalState.runId
    if (!known && !isPendingRun) {
      json(res, { ok: false, error: `没有这个运行：${runId}` })
      return true
    }

    const source = known ?? { id: runId, question: '把文档里那个自相矛盾的地方提交成一条审查工单' }
    const rows = runApprovals(runId)
    const detail = detailFromScript(source.id, source.question, {
      status: known ? known.status : 'WAITING_APPROVAL',
      stopReason: known ? (known.stopReason ?? '') : 'WAITING_FOR_APPROVAL',
      failureCause: known ? (known.failureCause ?? '') : '',
      totalTokens: known ? known.totalTokens : 3310,
      toolCalls: known ? known.toolCalls : 1,
      elapsedMs: known ? known.elapsedMs : 8430,
      // 失败那条要带上建议 —— 页面靠它显示"下一步该做什么"
      failureAdvice:
        known && known.failureCause === 'LOCAL_CONCURRENCY_LIMIT'
          ? '本机并发闸门满了（这是我们自己的限制，不是上游的问题）。稍后重试即可；如果经常出现，按实际配额调大 ai.agent.concurrency.max-concurrent-model-requests。'
          : '',
      // 【运行权限】详情页那一行显示它 —— 从**这一行自己的**列里取，
      // 而不是给所有运行写同一个档位：那样"档位是每一次运行各自的属性"这件事在假数据里就不成立
      workspaceDir: known ? (known.workspaceDir ?? '') : 'D:\\work\\docs',
      permissionLevel: known ? (known.permissionLevel ?? '') : 'READ_ONLY',
      permissionLabel: known ? (PERMISSION_LABELS[known.permissionLevel] ?? '') : PERMISSION_LABELS.READ_ONLY,
      finalAnswer: known && known.status === 'ANSWERED' ? detailFromScript(known.id, known.question).run.finalAnswer : '',
    })

    /*
      ⚠️ `approvals` 三个字段必须挂在**顶层**，而不是塞进 `run` 里。

      这里踩过一次，而且它是一个典型的"假数据形状不对"的坑：
      第一版把它们写成了 `detailFromScript(..., { approvals: rows, ... })` ——
      而那个函数的 overrides 是展开到 `run` 对象上的，于是字段跑到了 `detail.run.approvals`。
      真后端（`AgentController.runDetail`）把它们放在**顶层**，前端也读顶层 ——
      结果是界面上"人工确认（HITL）"那一节永远显示
      "这次运行没有触发人工确认"，而**假数据里明明有审批记录**。

      失败信息看起来像"详情页没渲染审批历史"，排查方向会跑到组件上去；
      而真相是假后端把字段放错了层级 —— **形状不一致的假数据比没有假数据更坏**：
      它让自检红在一个与真实代码无关的地方。
    */
    detail.approvals = rows
    detail.approvalCount = rows.length
    detail.pendingApprovals = rows.filter(r => r.pending).length

    // 停在等人确认的那次运行：它的步骤里应当有那次"等人工确认"的调用
    if (isPendingRun && approvalState.decision === null) {
      detail.steps = [PENDING_WRITE_STEP]
      detail.toolCalls = [{ ...PENDING_WRITE_STEP.toolCalls[0], stepIndex: 1, callIndex: 0, callId: 'call_pending_0' }]
    }
    json(res, detail)
    return true
  }

  if (req.method === 'GET' && pathname === '/api/agent/limits') {
    json(res, LIMITS)
    return true
  }

  return false
}

/** 待批列表里那一行（形状照 `ToolApprovalRecord` → `AgentApprovalController.pending()`）。 */
function pendingRow(waitedMs = 12800) {
  return {
    id: approvalState.id,
    runId: approvalState.runId,
    stepIndex: approvalState.stepIndex,
    toolName: approvalState.toolName,
    arguments: approvalState.arguments,
    hints: approvalState.hints,
    /*
      【本次新增】这次为什么弹了确认。
      真后端从 `ToolApprovalRecord.permissionNote` 给（形如 `仅可查看 · D:\work\docs`），
      它回答的是"同一把 propose_review 在完全权限下不会弹，这一次为什么弹了" ——
      只看 hints（工具固有的风险声明）分不出这两种情况
    */
    permissionNote: '仅可查看 · D:\\work\\docs',
    summary: approvalState.summary,
    requestedAt: '2026-09-16T10:12:03',
    waitedMs,
  }
}

/** 历史列表里那条"停在等人确认"的运行。 */
function pendingRunRow() {
  return {
    id: approvalState.runId,
    question: '把文档里那个自相矛盾的地方提交成一条审查工单',
    status: 'WAITING_APPROVAL',
    stopReason: 'WAITING_FOR_APPROVAL',
    failureCause: null,
    totalTokens: 3310,
    toolCalls: 1,
    elapsedMs: 8430,
    createdAt: '2026-09-16T10:12:03',
    node: 'local',
    intent: '文档问答',
    tenantId: '示例租户',
    // 停在等人确认的那次运行：它是**仅可查看**档位下的写操作 ——
    // 与列表里那条 FULL_ACCESS 构成对照（同一把工具，两档下结局完全不同）
    workspaceDir: 'D:\\work\\docs',
    permissionLevel: 'READ_ONLY',
  }
}

/** 某次运行的人工确认历史（已决 + 待批）。没有就返回空 —— 大多数运行本来就没触发过审批。 */
function runApprovals(runId) {
  if (runId !== approvalState.runId) {
    return []
  }
  const decided = approvalState.decision
  return [
    {
      id: approvalState.id,
      stepIndex: approvalState.stepIndex,
      toolName: approvalState.toolName,
      arguments: approvalState.arguments,
      status: decided ?? 'PENDING',
      pending: decided === null,
      requestedAt: '2026-09-16T10:12:03',
      decidedAt: decided === null ? '' : '2026-09-16T10:12:16',
      decidedBy: approvalState.decidedBy ?? '',
      decisionNote: approvalState.decisionNote ?? '',
      waitedMs: decided === null ? 12800 : 13200,
    },
  ]
}

/** 写操作"停在等人确认"的那一步（stage 是 AWAITING_APPROVAL）。 */
const PENDING_WRITE_STEP = {
  index: 1,
  thoughtText: '文档里那个自相矛盾的地方值得让人跟进一下，我提交一条审查工单。',
  promptTokens: 1520,
  completionTokens: 64,
  elapsedMs: 8430,
  usedTools: true,
  toolCalls: [
    {
      toolName: 'propose_review',
      arguments: approvalState.arguments,
      stage: 'AWAITING_APPROVAL',
      elapsedMs: 8400,
      // 待批不是"模型能靠改参数解决的失败"：原样重提还是会被拦，改参数则要重新审批
      fixableByModel: false,
      countsAsToolFailure: false,
      feedback:
        '工具「propose_review」是一次写操作，按规则需要人工确认后才能执行。这次调用已被挂起：\n' +
        `- 审批编号：${approvalState.id}\n` +
        `- 它将会执行的参数：${approvalState.arguments}\n` +
        `- 风险声明：${approvalState.hints}\n` +
        '它还没有执行（也没有失败）。在有人确认之前，请不要重新发起这次调用 —— ' +
        '重复发起只会产生多条待批记录，而不会让它更快被批准。\n' +
        '如果你现在无法继续，就把当前进展和"在等什么确认"直接告诉用户。',
    },
  ],
}
