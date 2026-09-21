/**
 * agent 任务端点的契约（`ai.agent.controller.AgentTaskController`）。
 *
 * ---------------------------------------------------------------------------
 * 与 permissions.ts / prompt.ts 同一条规矩：**这里的类型必须与后端返回的 JSON 逐字对应**
 * ---------------------------------------------------------------------------
 * 后端返回的是手拼的 `Map`（不是 record 序列化），所以编译器帮不上忙 ——
 * 字段名写错只表现为页面上某一格是 `undefined`，而控制台干干净净。
 * 改后端时回来对一遍。
 *
 * ---------------------------------------------------------------------------
 * 三条与页面行为绑定的后端事实
 * ---------------------------------------------------------------------------
 * **一、`result` 与 `cancelReason` 可能是 `null`**：它们的"还没有"是有含义的
 * （还没执行完 / 没取消），页面据此不画那一块，而不是画一行空白。
 *
 * **二、能做什么由后端算**（`actions`）：哪几个按钮该出现，取决于任务当前状态。
 * 前端再对一遍状态就是第二份判据 —— 而两份判据不一致时的现象是"点得下去、点完报错"，
 * 用户会以为是自己操作错了。所以这一页只渲染 `actions` 里给的东西。
 *
 * **三、状态冲突是 409**：`TaskRejectedError.status === 409` 表示"这条任务现在的状态
 * 不允许这个动作"（别人刚答过、或状态已经变了），界面上要说"刷新看看它到哪一步了"，
 * 而不是把用户填的内容当成错的。400 才是"请求里少填了东西"。
 */

/** 看板上的一张卡片（也是详情里的任务本体）。 */
export interface AgentTaskCard {
  id: number
  publisher: string
  /** 可能为空串（还没指派任务人）。 */
  assignee: string
  description: string
  /**
   * NEW / PENDING_REQUIREMENT / PENDING_PLAN / PENDING_AUTHORIZATION / CONFIRMED_REQUIREMENT /
   * CONFIRMED_PLAN / AUTHORIZED / EXECUTING / PENDING_ACCEPTANCE / COMPLETED / CANCELLED。
   */
  status: string
  statusLabel: string
  /** 这一条现在流转到谁手里：人 或 `agent#<id>`；任务结束后为空串。 */
  currentExecutor: string
  /**
   * 任务当前步骤，从 1 开始 —— 它是"这条任务经过了几格"（每次落进新状态 +1），
   * 唯一不递增的是「新发布 → 待确认需求」（那表示第 1 步开始了）。
   */
  currentStep: number
  agentId: number | null
  /** 执行结果；还没执行完时为 null。 */
  result: string | null
  /** 取消原因；没取消时为 null。 */
  cancelReason: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 一条确认项。 */
export interface AgentTaskConfirm {
  id: number
  taskId: number
  stepIndex: number
  /** REQUIREMENT / PLAN / AUTHORIZATION；认不出的类型为空串。 */
  type: string
  typeLabel: string
  description: string
  /** 选项集；空数组 = 只收自由回复。 */
  options: string[]
  /** 用户确认项；null = 还没答（与「答了但空」不是一回事，后者写不进来）。 */
  answer: string | null
  /** 那一格上该显示的字（没答时是「（未答复）」）—— 文案由后端给，前端不自己拼。 */
  answerLabel: string
  answered: boolean
}

/** 现在能做的动作。`key` 取值：`advance` / `answer` / `cancel`（后端是唯一判据）。 */
export interface AgentTaskAction {
  key: string
  label: string
}

/** 看板的一列。 */
export interface AgentTaskColumn {
  key: string
  label: string
  /** 这一列收的状态名（「待处理」那列靠它说清自己收的是哪三种）。 */
  statusLabels: string[]
  count: number
  tasks: AgentTaskCard[]
}

/** `GET /api/agent-tasks` 的响应。列的顺序由后端定，前端按顺序画。 */
export interface AgentTaskBoard {
  columns: AgentTaskColumn[]
  total: number
  howToAdvance: string
}

/** `GET /api/agent-tasks/{id}` 的响应（也是每个写动作的回执里带的那一份）。 */
export interface AgentTaskDetail {
  task: AgentTaskCard
  confirms: AgentTaskConfirm[]
  actions: AgentTaskAction[]
}

/**
 * 写入被拒。
 *
 * 单独一个类型是为了让界面能分开 409 与 400：前者是"这条任务的状态变了"（该刷新），
 * 后者是"你填的东西不合规"（该改了重试）。压成一句红字会让用户对着正确的按钮反复点。
 */
export class TaskRejectedError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'TaskRejectedError'
    this.status = status
  }
}

/** 写动作的回执：ok + 改完之后的那一份详情（后端刻意不返回裸 ok，见控制器注释）。 */
export interface AgentTaskWriteResult extends AgentTaskDetail {
  ok: boolean
}

const BASE = '/api/agent-tasks'

async function parse<T>(response: Response): Promise<T> {
  let body: Record<string, unknown> = {}
  try {
    body = (await response.json()) as Record<string, unknown>
  } catch {
    // 非 JSON 的错误体（代理返回的 HTML 错误页）—— 下面退化成用状态码说话
  }
  if (response.ok) {
    return body as T
  }
  // 后端两种错误体形状：ErrorResponseAdvice 的 {message}（400/500）与
  // AgentTaskController 自己给的 {error}（409）。两种都读，界面拿到的都是一句话
  const message = typeof body.message === 'string'
    ? body.message
    : typeof body.error === 'string' ? body.error : `请求失败（HTTP ${response.status}）`
  throw new TaskRejectedError(message, response.status)
}

async function send<T>(method: string, path: string, payload?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (payload !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(payload)
  }
  return parse<T>(await fetch(`${BASE}${path}`, init))
}

export const taskApi = {
  board: () => send<AgentTaskBoard>('GET', ''),

  detail: (id: number) => send<AgentTaskDetail>('GET', `/${id}`),

  create: (payload: { publisher: string; assignee?: string; description: string; agentId?: number | null }) =>
    send<{ ok: boolean; task: AgentTaskCard }>('POST', '', payload),

  /** 推进当前事件。`result` 只在「执行中 → 待验收」那一步有意义（可空）。 */
  advance: (id: number, result?: string) =>
    send<AgentTaskWriteResult>('POST', `/${id}/advance`, { result }),

  /** 答复一条确认项。`answer` 是用户选的选项或自由回复（必填，判据在后端）。 */
  answer: (id: number, confirmId: number, answer: string) =>
    send<AgentTaskWriteResult>('POST', `/${id}/confirms/${confirmId}/answer`, { answer }),

  /** 取消任务。`reason` 必填 —— 一条没有原因的取消记录事后只能靠回忆。 */
  cancel: (id: number, reason: string) =>
    send<AgentTaskWriteResult>('POST', `/${id}/cancel`, { reason }),
}
