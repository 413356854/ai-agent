/**
 * Agent 端点的调用层（阶段三：能看见 Agent 到底干了什么）。
 *
 * 与 `rag.ts` 的关系：那边是"文档切得怎么样、检索找不找得到"的取证入口（阶段二），
 * 这边是"Agent 怎么想的、调了什么、失败在哪一步"的取证入口（阶段三）。
 * 两者共用同一套错误分类与超时约定 —— 从 `diag.ts` 复用 `DiagError` 与 `request`。
 *
 * 端点清单（与 ai.agent.controller.AgentController 一一对应）：
 *   POST /api/agent/ask          跑一个任务（★ 会真的调模型、花钱）
 *   GET  /api/agent/runs         历史运行列表
 *   GET  /api/agent/runs/{id}    一次运行的完整过程
 *   GET  /api/agent/limits       四层保护的当前状态
 */
import { DiagError, request } from './diag'

/**
 * 跑任务的超时。
 *
 * **为什么给 180 秒**：一个 Agent 任务最多 6 轮模型往返（`ai.agent.loop.max-steps`），
 * 每轮 1~4 秒 → 正常 20 秒内；但循环自己的时间上限是 60 秒
 * （`ai.agent.loop.max-duration-ms`），加上每轮工具执行与网络抖动，
 * 前端必须**明显宽于后端的自我上限** —— 否则后端还在正常跑，前端先报超时了，
 * 而那是阶段一踩过的坑（钉住实验 19~26 秒 vs 前端 20 秒超时）。
 *
 * 取值依据：后端上限 60 秒 × 3 = 180 秒，留足余量。
 * **这个数字与 `ai.agent.loop.max-duration-ms` 是联动的**，改一个要一起改。
 */
export const AGENT_ASK_TIMEOUT_MS = 180_000

/** 只读端点的超时：它们不调模型，只是读库，秒级足够。 */
export const AGENT_READ_TIMEOUT_MS = 30_000

// ---------------------------------------------------------------------------
//  与后端契约对应的类型。
//
//  唯一事实来源是后端代码，改那边就要同步改这里：
//    - ai.agent.controller.AgentController（各端点的返回体）
//    - ai.agent.tool.ToolCallStage（stage 的取值）
//    - ai.agent.agent.AgentFailureCause（失败细分）
//    - ai.agent.agent.limit.AgentConcurrencyLimitsView（限额快照）
// ---------------------------------------------------------------------------

/** 一次运行里的一次工具调用（`/ask` 与 `/runs/{id}` 都有这个形状）。 */
export interface AgentToolCall {
  /** 只有 `/runs/{id}` 会给（它来自库里的明细表） */
  stepIndex?: number
  callIndex?: number
  callId?: string
  toolName: string
  arguments: string
  /**
   * 走到了哪一步。取值见后端 `ToolCallStage`：
   * `OK` / `UNKNOWN_TOOL` / `PARSE_ERROR` / `VALIDATION_ERROR` / `EXECUTION_ERROR`
   * / `REQUEST_REJECTED` / `CIRCUIT_OPEN`
   */
  stage: string
  elapsedMs: number
  /** 只有 `/ask` 会给 */
  fixableByModel?: boolean
  /**
   * **要不要把它算成"工具失败"——由后端给，前端不再判断。**
   *
   * 这个字段的存在是一条设计纪律：`stage` 到"好坏"的映射在
   * `CircuitBreaker` 里已经有一份判据（只有 `EXECUTION_ERROR` 算），
   * 前端再判一次就是**第二份判据**，而两份必然漂移 ——
   * 漂移之后的现象是"页面说工具没事，但熔断其实已经打开了"。
   */
  countsAsToolFailure: boolean
  /** 回填给模型的文本 —— "模型当时到底看到了什么"的唯一凭据 */
  feedback: string
}

/** 一步（一次模型往返）。 */
export interface AgentStep {
  index: number
  thoughtText: string
  promptTokens: number
  completionTokens: number
  elapsedMs: number
  usedTools: boolean
  toolCalls: AgentToolCall[]
  /** 只有 `/runs/{id}` 会给：这一步的检查点存了多少字符的上下文 */
  transcriptChars?: number
  stepIndex?: number
}

/**
 * `POST /api/agent/ask` 的响应 —— **一次任务的完整交代**。
 *
 * `steps` 与 `toolCallSummary` 一定要显示：只给答案的话，
 * "它查了 3 次文档、失败 2 次、最后蒙对"和"它一次就答对"在界面上长得一模一样。
 */
export interface AgentAskResponse {
  ok: boolean
  error?: string
  runId: string
  question: string
  answered: boolean
  finalAnswer: string
  stopReason: string
  /** 失败细分（只有 stopReason=MODEL_ERROR 时非空），取值见 `AgentFailureCause` */
  failureCause: string
  /** 失败时"下一步该做什么"的一句话（后端给的，前端不自己编） */
  failureAdvice: string
  totalTokens: number
  elapsedMs: number
  toolCallCount: number
  /** 例如「工具调用 2 次：UNKNOWN_TOOL=1 OK=1 ｜工具侧总耗时 12ms」 */
  toolCallSummary: string
  stepSummaries: string[]
  steps: AgentStep[]
}

/** `/runs` 列表里的一行 */
export interface AgentRunRow {
  id: string
  question: string
  /** `RUNNING` / `ANSWERED` / `STOPPED` / `FAILED` / `INTERRUPTED` */
  status: string
  stopReason: string | null
  failureCause: string | null
  totalTokens: number
  toolCalls: number
  elapsedMs: number
  createdAt: string | null
  node: string | null
}

/** `GET /api/agent/runs` 的响应 */
export interface AgentRunsResponse {
  runs: AgentRunRow[]
  count: number
  /** 有多少次被中断的运行在等恢复（默认配置下不会自动恢复，所以这个数字要显眼） */
  awaitingRecovery: number
}

/** `GET /api/agent/runs/{id}` 的响应 */
export interface AgentRunDetail {
  ok: boolean
  error?: string
  run: {
    id: string
    question: string
    status: string
    stopReason: string
    failureCause: string
    failureAdvice: string
    finalAnswer: string
    totalTokens: number
    toolCalls: number
    elapsedMs: number
    node: string
  }
  steps: AgentStep[]
  toolCalls: AgentToolCall[]
}

/** 模型侧闸门的快照。`available=false` 表示这一层**没装上**，不是"没有流量"。 */
export interface ModelGateSnapshot {
  available: boolean
  maxConcurrent: number
  inFlight?: number
  peakInFlight?: number
  acquired?: number
  rejected?: number
  averageWaitMs?: number
  maxWaitMs?: number
  note?: string
}

/** 每把工具一行的闸门 + 熔断状态 */
export interface ToolLimitRow {
  toolName: string
  maxConcurrent: number
  inFlight: number
  peakInFlight: number
  acquired: number
  rejected: number
  averageWaitMs: number
  maxWaitMs: number
  /** `CLOSED` / `OPEN` / `HALF_OPEN` */
  circuitState: string
  windowSize: number
  failureRate: number
  circuitOpenCount: number
  shortCircuited: number
}

/** `GET /api/agent/limits` 的响应 */
export interface AgentLimitsResponse {
  config: {
    maxConcurrentModelRequests: number
    maxConcurrentToolRequests: number
    acquireTimeoutMs: number
    circuitEnabled: boolean
    failureRateThreshold: number
    minimumCalls: number
    windowSize: number
    openDurationMs: number
  }
  modelGate: ModelGateSnapshot
  tools: ToolLimitRow[]
  totals: {
    toolsTracked: number
    totalRejected: number
    totalShortCircuited: number
    openCircuits: number
  }
  note: string
}

// ---------------------------------------------------------------------------
//  调用函数
// ---------------------------------------------------------------------------

/**
 * 跑一个任务。
 *
 * **它会真的调模型并花钱**，所以界面上必须有明确的动作（按钮 + 提示成本），
 * 不做任何自动触发。
 */
export function askAgent(question: string): Promise<AgentAskResponse> {
  return postJson<AgentAskResponse>('/api/agent/ask', { question }, '跑 Agent 任务')
}

/** 历史运行列表。 */
export function getAgentRuns(limit = 20): Promise<AgentRunsResponse> {
  return getJson<AgentRunsResponse>(`/api/agent/runs?limit=${limit}`, 'Agent 运行列表')
}

/** 一次运行的完整过程。 */
export function getAgentRunDetail(runId: string): Promise<AgentRunDetail> {
  return getJson<AgentRunDetail>(`/api/agent/runs/${encodeURIComponent(runId)}`, 'Agent 运行详情')
}

/** 四层保护的当前状态。 */
export function getAgentLimits(): Promise<AgentLimitsResponse> {
  return getJson<AgentLimitsResponse>('/api/agent/limits', 'Agent 限额状态')
}

// ---------------------------------------------------------------------------
//  内部工具
// ---------------------------------------------------------------------------

async function getJson<T>(path: string, what: string, timeoutMs = AGENT_READ_TIMEOUT_MS): Promise<T> {
  const response = await request(path, what, 'GET', timeoutMs)
  return parseBody<T>(response, what)
}

async function postJson<T>(path: string, body: unknown, what: string): Promise<T> {
  const response = await request(path, what, 'POST', AGENT_ASK_TIMEOUT_MS, JSON.stringify(body))
  return parseBody<T>(response, what)
}

/** 200 但不是 JSON（例如被代理换成了首页 HTML）时，"解析失败"才是真相。 */
async function parseBody<T>(response: Response, what: string): Promise<T> {
  const text = await response.text().catch(() => '')
  try {
    return JSON.parse(text) as T
  }
  catch {
    throw new DiagError(`拿「${what}」时后端返回的不是 JSON：${text.slice(0, 200) || '(空响应体)'}`)
  }
}
