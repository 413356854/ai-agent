/**
 * Graph 演示的调用层。
 *
 * 三个端点：
 *   GET  /api/graph-demo/meta     拓扑、状态键、模型名与调用次数
 *   POST /api/graph-demo/run      同步运行一次图
 *   POST /api/graph-demo/replay   从某个检查点之后重放
 *
 * 运行端点的超时给到 300 秒：bug/feature 一次 5 次真实模型调用，
 * 强制首轮评审 FAIL 时 7 次；前端超时不能比后端的固有耗时短。
 */
import { DiagError, request } from './diag'

export const GRAPH_READ_TIMEOUT_MS = 20_000

export const GRAPH_RUN_TIMEOUT_MS = 300_000

export type GraphIssueType = 'bug' | 'feature' | 'question'

export interface GraphNodeMeta {
  id: string
  label: string
  type: 'model' | 'deterministic'
  role: string
  usesModel: boolean
  description: string
}

export interface GraphEdgeMeta {
  from: string
  to: string
  kind: 'fixed' | 'conditional'
  label: string | null
}

export interface GraphStateKeyMeta {
  key: string
  strategy: 'replace' | 'append'
}

export interface GraphMetaResponse {
  enabled: boolean
  graphName: string
  model: string | null
  modelConfigured: boolean
  checkpointSaver: string
  checkpointScope: 'memory'
  recursionLimit: number
  maxReviewRounds: number
  forceFirstReviewFailSupported: boolean
  modelCalls: {
    question: number
    bug: number
    feature: number
    forcedFirstReviewFailExtra: number
  }
  nodes: GraphNodeMeta[]
  edges: GraphEdgeMeta[]
  stateKeys: GraphStateKeyMeta[]
  warnings: string[]
}

export interface GraphRunRequest {
  title: string
  type: GraphIssueType
  summary: string
  forceFirstReviewFail: boolean
}

export interface GraphNodeTrace {
  index: number
  nodeId: string
  label: string
  nodeType: 'model' | 'deterministic'
  role: string
  usesModel: boolean
  model: string | null
  inputKeys: string[]
  outputKeys: string[]
  elapsedMs: number
  startedAt: string
  summary: string
  outcome: string | null
  reviewRound: number | null
}

export interface GraphReviewGate {
  result: 'pass' | 'fail'
  round: number
  maxRounds: number
  forced: boolean
  skipped: boolean
  degraded: boolean
  reason: string
}

export interface GraphCheckpoint {
  index: number
  checkpointId: string
  kind: 'start' | 'node' | 'end'
  node: string
  next: string
  keys: string[]
  selected: Record<string, string | number | boolean>
}

export interface GraphFinalState {
  runId: string
  threadId: string
  title: string
  requestedType: string
  summary: string
  summaryChars: number
  route: string
  routeReason: string
  triageSuggestedRoute: string
  triageResult: string
  workPlan: string
  implementation: string
  reviewDecision: string
  reviewVerdict: string
  reviewRound: number
  reviewGateResult: string
  reviewGateReason: string
  reviewGateForced: boolean
  reviewGateSkipped: boolean
  reviewGateDegraded: boolean
  auditReport: string
}

export interface GraphRunResponse {
  ok: true
  kind: 'run' | 'replay'
  threadId: string
  runId: string
  model: string | null
  graphName: string
  startedAt: string
  elapsedMs: number
  request: {
    title: string
    type: string
    summaryChars: number
    forceFirstReviewFail: boolean
  }
  route: string
  routeReason: string
  triageSuggestedRoute: string
  reviewGate: GraphReviewGate
  nodes: GraphNodeTrace[]
  finalState: GraphFinalState
  history: GraphCheckpoint[]
  replayedFromTraceIndex: number
  replayedNodeIds: string[]
  warnings: string[]
}

async function getJson<T>(path: string, what: string): Promise<T> {
  const response = await request(path, what, 'GET', GRAPH_READ_TIMEOUT_MS)
  return parseJson<T>(response, what)
}

async function postJson<T>(path: string, what: string, body: unknown): Promise<T> {
  const response = await request(path, what, 'POST', GRAPH_RUN_TIMEOUT_MS, JSON.stringify(body))
  return parseJson<T>(response, what)
}

async function parseJson<T>(response: Response, what: string): Promise<T> {
  const text = await response.text()
  try {
    return JSON.parse(text) as T
  }
  catch {
    throw new DiagError(`「${what}」返回的不是 JSON：${text.slice(0, 200)}`, response.status)
  }
}

export const graphApi = {
  meta: () => getJson<GraphMetaResponse>('/api/graph-demo/meta', 'Graph 元信息'),
  run: (payload: GraphRunRequest) => postJson<GraphRunResponse>('/api/graph-demo/run', 'Graph 运行', payload),
  replay: (threadId: string, checkpointId: string) =>
    postJson<GraphRunResponse>('/api/graph-demo/replay', 'Graph 回放', { threadId, checkpointId }),
}
