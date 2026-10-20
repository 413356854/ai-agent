/**
 * Graph 演示的假后端。
 *
 * 形状与真后端 `/api/graph-demo/{meta,run,replay}` 一致；模型文本只用于展示，
 * 不代表真实模型会给出的内容。run/replay 的结果是确定性的：
 * 同一条输入永远得到同一条 route、同一条 gate 结果与同一组节点轨迹。
 *
 * 与 task-routes 相同的纪律：同步分支先 `json(...)` 再 `return true`，
 * 不要写 `return json(...)` —— 那会让分派方再写一次 404，进程直接退出。
 */
import { randomUUID } from 'node:crypto'

const MOCK_MODEL = 'mock-model（假后端，不调用模型）'
const MAX_REVIEW_ROUNDS = 2
const THREADS = new Map()

const NODES = [
  node('triage', '模型分诊', 'model', 'worker', true, '判断 issue 类型并给出 route_candidate'),
  node('route_issue', '确定性路由', 'deterministic', 'router', false, '把分诊结果转成最终 route'),
  node('bug_diagnoser', '缺陷诊断', 'model', 'worker', true, '复现、故障边界、回归测试建议'),
  node('feature_planner', '特性规划', 'model', 'worker', true, '用户故事、设计草案、验收标准'),
  node('question_responder', '问题答复', 'model', 'worker', true, '回答问题并跳过实现/评审'),
  node('implementer', '实现方案', 'model', 'worker', true, '生成可评审的实现方案'),
  node('reviewer', '方案评审', 'model', 'reviewer', true, '输出 PASS/FAIL 评审 JSON'),
  node('review_gate', '评审闸门', 'deterministic', 'gate', false, '转换评审结论并控制回环'),
  node('auditor', '审计汇总', 'model', 'auditor', true, '汇总最终生命周期审计报告'),
]

const EDGES = [
  edge('__START__', 'triage', 'fixed', null),
  edge('triage', 'route_issue', 'fixed', null),
  edge('route_issue', 'bug_diagnoser', 'conditional', 'bug'),
  edge('route_issue', 'feature_planner', 'conditional', 'feature'),
  edge('route_issue', 'question_responder', 'conditional', 'question'),
  edge('bug_diagnoser', 'implementer', 'fixed', null),
  edge('feature_planner', 'implementer', 'fixed', null),
  edge('question_responder', 'auditor', 'fixed', null),
  edge('implementer', 'reviewer', 'fixed', null),
  edge('reviewer', 'review_gate', 'fixed', null),
  edge('review_gate', 'auditor', 'conditional', 'pass'),
  edge('review_gate', 'implementer', 'conditional', 'fail'),
  edge('auditor', '__END__', 'fixed', null),
]

const REPLACE_KEYS = [
  'run_id', 'thread_id', 'started_at', 'title', 'requested_type', 'summary', 'force_first_review_fail',
  'triage_result', 'triage_route_candidate', 'route', 'route_reason', 'work_plan', 'implementation',
  'implementation_round', 'review_decision', 'review_verdict', 'review_round', 'review_gate_result',
  'review_gate_reason', 'review_gate_forced', 'review_gate_round', 'review_gate_skipped',
  'review_gate_degraded', 'audit_report', 'current_node',
]

const APPEND_KEYS = ['node_trace', 'route_history', 'evidence_bundle']

const META = {
  enabled: true,
  graphName: 'graph-demo-repoops',
  model: MOCK_MODEL,
  modelConfigured: true,
  checkpointSaver: 'MemorySaver（假后端；进程内）',
  checkpointScope: 'memory',
  recursionLimit: 100,
  maxReviewRounds: MAX_REVIEW_ROUNDS,
  forceFirstReviewFailSupported: true,
  modelCalls: { question: 3, bug: 5, feature: 5, forcedFirstReviewFailExtra: 2 },
  nodes: NODES,
  edges: EDGES,
  stateKeys: [
    ...REPLACE_KEYS.map(key => ({ key, strategy: 'replace' })),
    ...APPEND_KEYS.map(key => ({ key, strategy: 'append' })),
  ],
  warnings: [
    '假后端：不调用模型，输出是确定性契约样本。',
    '检查点由进程内 Map 保存，假后端重启后清空。',
  ],
}

export function handleGraphRoutes(req, res, url, json) {
  const { pathname } = url
  if (!pathname.startsWith('/api/graph-demo')) return false

  if (req.method === 'GET' && pathname === '/api/graph-demo/meta') {
    json(res, META)
    return true
  }

  if (req.method === 'POST' && pathname === '/api/graph-demo/run') {
    readBody(req, res, json, body => {
      const checked = validateRun(body)
      if (!checked.ok) {
        json(res, { ok: false, error: checked.error }, 400)
        return
      }
      const response = buildRun({ ...checked.value, threadId: body.threadId })
      THREADS.set(response.threadId, {
        nodes: response.nodes,
        history: response.history,
        request: response.request,
        runId: response.runId,
        threadId: response.threadId,
        startedAt: response.startedAt,
        finalState: response.finalState,
        reviewGate: response.reviewGate,
        route: response.route,
        routeReason: response.routeReason,
        triageSuggestedRoute: response.triageSuggestedRoute,
      })
      // 700ms 延迟：让页面的 loading 状态真的能被看到
      setTimeout(() => json(res, response), 700)
    })
    return true
  }

  if (req.method === 'POST' && pathname === '/api/graph-demo/replay') {
    readBody(req, res, json, body => {
      const threadId = String(body.threadId || '').trim()
      const checkpointId = String(body.checkpointId || '').trim()
      if (!threadId || !checkpointId) {
        json(res, { ok: false, error: 'threadId 与 checkpointId 都不能为空' }, 400)
        return
      }
      const record = THREADS.get(threadId)
      if (!record) {
        json(res, { ok: false, error: '假后端里没有这个 thread（进程内检查点，重启后清空）' }, 400)
        return
      }
      const checkpoint = record.history.find(item => item.checkpointId === checkpointId)
      if (!checkpoint) {
        json(res, { ok: false, error: '假后端里没有这个检查点' }, 400)
        return
      }
      if (!checkpoint.next || checkpoint.next === '__END__') {
        json(res, { ok: false, error: '该检查点之后已经没有可执行的节点' }, 400)
        return
      }
      const response = buildReplay(record, checkpoint)
      record.nodes = response.nodes
      record.history = response.history
      setTimeout(() => json(res, response), 500)
    })
    return true
  }

  json(res, { ok: false, error: `假后端不支持 ${req.method} ${pathname}` }, 404)
  return true
}

// ---------------------------------------------------------------------------
//  构造响应
// ---------------------------------------------------------------------------

function buildRun({ title, type, summary, forceFirstReviewFail }) {
  const threadId = 'graph-' + randomUUID()
  const runId = randomUUID()
  const startedAt = new Date().toISOString()
  const nodes = buildTrace(type, forceFirstReviewFail)
  const history = buildHistory(nodes, { runId, threadId, title, type })
  const finalState = buildFinalState({ runId, threadId, title, type, summary, forceFirstReviewFail })
  const reviewGate = buildGate(type, forceFirstReviewFail)

  return {
    ok: true,
    kind: 'run',
    threadId,
    runId,
    model: MOCK_MODEL,
    graphName: 'graph-demo-repoops',
    startedAt,
    elapsedMs: 860,
    request: { title, type, summaryChars: summary.length, forceFirstReviewFail },
    route: type,
    routeReason: '假后端按选择的类型路由（真后端模型 route_candidate 优先）',
    triageSuggestedRoute: type,
    reviewGate,
    nodes,
    finalState,
    history,
    replayedFromTraceIndex: 0,
    replayedNodeIds: [],
    warnings: [
      '假后端：不调用模型，输出是确定性契约样本。',
      '检查点由进程内 Map 保存，假后端重启后清空。',
    ],
  }
}

function buildReplay(record, checkpoint) {
  const traceBefore = checkpoint.index - 1
  const replayNodes = record.nodes.slice(traceBefore)
  const prefix = record.nodes.slice(0, traceBefore)
  let nextIndex = prefix.length + 1
  const replayed = replayNodes.map(item => ({
    ...item,
    index: nextIndex++,
    startedAt: new Date().toISOString(),
    elapsedMs: item.usesModel ? 640 : 3,
  }))
  const nodes = [...prefix, ...replayed]
  const newCheckpoints = buildHistory(replayed, {
    runId: record.runId,
    threadId: record.threadId,
    title: record.request.title,
    type: record.request.type,
  }).slice(1).map((item, offset) => ({
    ...item,
    index: record.history.length + offset + 1,
    checkpointId: 'mock-cp-' + randomUUID(),
  }))
  const history = [...record.history, ...newCheckpoints]
  return {
    ok: true,
    kind: 'replay',
    threadId: record.threadId,
    runId: record.runId,
    model: MOCK_MODEL,
    graphName: 'graph-demo-repoops',
    startedAt: record.startedAt,
    elapsedMs: 420,
    request: record.request,
    route: record.route,
    routeReason: record.routeReason,
    triageSuggestedRoute: record.triageSuggestedRoute,
    reviewGate: record.reviewGate,
    nodes,
    finalState: record.finalState,
    history,
    replayedFromTraceIndex: traceBefore + 1,
    replayedNodeIds: replayNodes.map(item => item.nodeId),
    warnings: [
      '假后端：回放不调用模型，输出是确定性契约样本。',
      '检查点由进程内 Map 保存，假后端重启后清空。',
      '真后端回放会重新执行后续节点，可能再次调用模型并产生费用。',
    ],
  }
}

function buildFinalState({ runId, threadId, title, type, summary, forceFirstReviewFail }) {
  const isQuestion = type === 'question'
  const round = isQuestion ? 0 : (forceFirstReviewFail ? 2 : 1)
  return {
    runId,
    threadId,
    title,
    requestedType: type,
    summary,
    summaryChars: summary.length,
    route: type,
    routeReason: '假后端按选择的类型路由（真后端模型 route_candidate 优先）',
    triageSuggestedRoute: type,
    triageResult: `（假后端）分诊结果：route_candidate: ${type}`,
    workPlan: '（假后端）工作计划：复现、边界、验证与风险。',
    implementation: isQuestion
      ? 'question 分支不需要实现方案。'
      : '（假后端）实现方案：改动点、测试、风险。',
    reviewDecision: isQuestion
      ? 'PASS: question 分支不进入实现与评审。'
      : '{"verdict":"PASS","summary":"假后端评审通过","blockingIssues":[],"suggestions":[]}',
    reviewVerdict: 'PASS',
    reviewRound: round,
    reviewGateResult: 'pass',
    reviewGateReason: isQuestion ? 'question 分支跳过实现与评审' : '假后端评审闸门通过',
    reviewGateForced: forceFirstReviewFail && !isQuestion,
    reviewGateSkipped: isQuestion,
    reviewGateDegraded: false,
    auditReport: '（假后端）审计报告：route、计划、实现、评审与证据均已汇总。',
  }
}

function buildGate(type, forceFirstReviewFail) {
  const isQuestion = type === 'question'
  return {
    result: 'pass',
    round: isQuestion ? 0 : (forceFirstReviewFail ? 2 : 1),
    maxRounds: MAX_REVIEW_ROUNDS,
    forced: forceFirstReviewFail && !isQuestion,
    skipped: isQuestion,
    degraded: false,
    reason: isQuestion ? 'question 分支跳过实现与评审' : '假后端评审闸门通过',
  }
}

function buildTrace(type, forceFirstReviewFail) {
  const nodes = []
  let index = 1
  nodes.push(trace(index++, 'triage', '模型分诊', 'model', 'worker', true,
    ['triage_result', 'triage_route_candidate'], `（假后端）分诊：${type}`, null, null))
  nodes.push(trace(index++, 'route_issue', '确定性路由', 'deterministic', 'router', false,
    ['route', 'route_reason'], type, type, null))

  if (type === 'question') {
    nodes.push(trace(index++, 'question_responder', '问题答复', 'model', 'worker', true,
      ['work_plan', 'review_gate_result', 'review_gate_skipped'], '（假后端）问答完成', 'pass', null))
  }
  else {
    const branch = type === 'bug' ? 'bug_diagnoser' : 'feature_planner'
    const branchLabel = type === 'bug' ? '缺陷诊断' : '特性规划'
    nodes.push(trace(index++, branch, branchLabel, 'model', 'worker', true,
      ['work_plan'], '（假后端）计划完成', null, null))
    nodes.push(trace(index++, 'implementer', '实现方案', 'model', 'worker', true,
      ['implementation', 'implementation_round'], '（假后端）实现方案第 1 轮', null, 1))
    nodes.push(trace(index++, 'reviewer', '方案评审', 'model', 'reviewer', true,
      ['review_decision', 'review_verdict', 'review_round'], '（假后端）评审第 1 轮', null, 1))
    if (forceFirstReviewFail) {
      nodes.push(trace(index++, 'review_gate', '评审闸门', 'deterministic', 'gate', false,
        ['review_gate_result', 'review_gate_reason'], 'fail', 'fail', 1))
      nodes.push(trace(index++, 'implementer', '实现方案', 'model', 'worker', true,
        ['implementation', 'implementation_round'], '（假后端）实现方案第 2 轮', null, 2))
      nodes.push(trace(index++, 'reviewer', '方案评审', 'model', 'reviewer', true,
        ['review_decision', 'review_verdict', 'review_round'], '（假后端）评审第 2 轮', null, 2))
      nodes.push(trace(index++, 'review_gate', '评审闸门', 'deterministic', 'gate', false,
        ['review_gate_result', 'review_gate_reason'], 'pass', 'pass', 2))
    }
    else {
      nodes.push(trace(index++, 'review_gate', '评审闸门', 'deterministic', 'gate', false,
        ['review_gate_result', 'review_gate_reason'], 'pass', 'pass', 1))
    }
  }
  nodes.push(trace(index++, 'auditor', '审计汇总', 'model', 'auditor', true,
    ['audit_report'], '（假后端）审计报告', null, null))
  return nodes
}

function trace(index, nodeId, label, nodeType, role, usesModel, outputKeys, summary, outcome, reviewRound) {
  return {
    index,
    nodeId,
    label,
    nodeType,
    role,
    usesModel,
    model: usesModel ? MOCK_MODEL : null,
    inputKeys: ['title', 'requested_type', 'summary', 'triage_result'],
    outputKeys,
    elapsedMs: usesModel ? 900 + index * 70 : 4,
    startedAt: new Date().toISOString(),
    summary,
    outcome,
    reviewRound,
  }
}

function buildHistory(nodes, { runId, threadId, title, type }) {
  const history = []
  history.push({
    index: 1,
    checkpointId: 'mock-cp-' + randomUUID(),
    kind: 'start',
    node: '__START__',
    next: nodes[0]?.nodeId ?? '__END__',
    keys: ['run_id', 'thread_id', 'started_at', 'title', 'requested_type', 'summary'],
    selected: { route: '', review_gate_result: '', review_round: 0, title, requested_type: type, run_id: runId },
  })
  nodes.forEach((item, offset) => {
    const next = nodes[offset + 1]?.nodeId ?? '__END__'
    history.push({
      index: offset + 2,
      checkpointId: 'mock-cp-' + randomUUID(),
      kind: 'node',
      node: item.nodeId,
      next,
      keys: [...item.outputKeys, 'run_id', 'thread_id', 'title'],
      selected: {
        route: item.outcome && item.nodeId === 'route_issue' ? item.outcome : type,
        review_gate_result: item.outcome && item.nodeId === 'review_gate' ? item.outcome : '',
        review_round: item.reviewRound ?? 0,
        title,
        requested_type: type,
        run_id: runId,
      },
    })
  })
  return history
}

// ---------------------------------------------------------------------------
//  校验与小工具
// ---------------------------------------------------------------------------

function validateRun(body) {
  const title = String(body.title ?? '').trim()
  const summary = String(body.summary ?? '').trim()
  const type = String(body.type ?? '').trim().toLowerCase()
  if (!title) return { ok: false, error: '标题不能为空' }
  if (!summary) return { ok: false, error: '描述不能为空' }
  if (title.length > 200) return { ok: false, error: '标题过长（> 200 字符）' }
  if (summary.length > 4000) return { ok: false, error: '描述过长（> 4000 字符）' }
  if (!['bug', 'feature', 'question'].includes(type)) {
    return { ok: false, error: 'type 只允许 bug / feature / question' }
  }
  return {
    ok: true,
    value: { title, summary, type, forceFirstReviewFail: Boolean(body.forceFirstReviewFail) },
  }
}

function readBody(req, res, json, handle) {
  let raw = ''
  req.on('data', chunk => { raw += chunk })
  req.on('end', () => {
    let body = {}
    try {
      body = raw ? JSON.parse(raw) : {}
    }
    catch {
      json(res, { ok: false, error: '请求体不是合法 JSON' }, 400)
      return
    }
    handle(body)
  })
}

function node(id, label, type, role, usesModel, description) {
  return { id, label, type, role, usesModel, description }
}

function edge(from, to, kind, label) {
  return { from, to, kind, label }
}
