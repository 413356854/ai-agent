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

/**
 * 看板上的一张卡片（也是详情里的任务本体）。
 *
 * 三个"人"各给两份：`publisherId` / `assigneeId` / `currentExecutorId` 是库里存的那一份
 * （用户 id，`agent_user.id`），`publisher` / `assignee` / `currentExecutor` 是后端关联账号表
 * 查出来的姓名 —— **界面显示的是姓名那一份**，id 那一份是"这条记录指向谁"的原始依据
 * （姓名可以改，id 不会变）。
 */
export interface AgentTaskCard {
  id: number
  /** 发布人的用户 id。 */
  publisherId: number
  /** 发布人姓名（后端关联查询得到）—— 卡片上显示的就是它。 */
  publisher: string
  /** 任务人的用户 id；库里任务人为空的行是 `null`。 */
  assigneeId: number | null
  /** 任务人姓名。发布时必填；空串 = 那一行没有任务人，界面显示「未指派任务人」。 */
  assignee: string
  description: string
  /**
   * NEW / PENDING_REQUIREMENT / PENDING_PLAN / PENDING_AUTHORIZATION / INSUFFICIENT_PERMISSION /
   * CONFIRMED_REQUIREMENT / CONFIRMED_PLAN / AUTHORIZED / EXECUTING / PENDING_ACCEPTANCE /
   * COMPLETED / CANCELLED。
   */
  status: string
  statusLabel: string
  /** 当前执行人的用户 id；球在 agent 手上（三个"已确认"）或任务已结束时为 `null`。 */
  currentExecutorId: number | null
  /**
   * 那一格该显示的字，**由后端给**：姓名 / `agent#<agentId>`（球在 agent 手上）/ 空串（没人）。
   *
   * <p>它不只是库里那一列的原值 —— 球在 agent 手上时那一列是空的，`agent#…` 是后端按
   * `agentId` 拼出来的。前端不再自己拼一份（与 `statusLabel` 同一条规矩）。
   *
   * <p>「执行中」那一格写的是**任务人**：那一格确实是 agent 在跑（"是哪个 agent"看 `agentId`），
   * 而跑失败时它停在执行中等任务人处置 —— 那一列是看板按人筛选的依据（`board(userId)`）。
   */
  currentExecutor: string
  /**
   * 任务当前步骤，从 1 开始 —— 它是"这条任务经过了几格"（每次落进新状态 +1），
   * 唯一不递增的是「新发布 → 待确认需求」（那表示第 1 步开始了）。
   */
  currentStep: number
  /**
   * 交给哪个 Agent 应用（应用注册表里的 id，**字符串**）。**发布时是 `null`**
   * （发布请求体里没有这个字段）—— 它由平台在第一次接手这条任务时写入（后端 `ai.agent.task.agent-id`）。
   * 为 `null` 只表示"还没有 agent 接手过它"。
   */
  agentId: string | null
  /**
   * 执行结果；还没执行完时为 null。
   *
   * <p>⚠️ 状态是 `INSUFFICIENT_PERMISSION` 时它是**权限不足的结论**
   * （缺哪一条目录前缀、已授权的是哪些、怎么放行），页面按状态换一个标签显示它。
   */
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

/**
 * 现在能做的动作。`key` 取值：`advance` / `answer` / `revise` / `cancel`（后端是唯一判据）。
 *
 * <p>`revise` 只有一个状态会给（`INSUFFICIENT_PERMISSION`），按钮上是「已修改任务」：
 * 它把任务放回「新发布」整条链重走一遍。
 */
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
  /** 这次返回的任务总数；筛过之后它是**筛完**的条数（不是全库的条数）。 */
  total: number
  /**
   * 后端回显的筛选依据：这一份是"与这个用户有关、且该由他看见"的那些（用户 id），
   * **不传就是不筛**（`null`）。界面不自己推一份 —— 它要与后端实际做了什么一致。
   *
   * <p>⚠️ 它**不是**卡片上那个 `currentExecutorId`（那一格是"此刻球在谁手上"）：
   * 两者刻意不同 —— 新发布与已完成时那一格是空的/任务人，而发布人照样在自己的列表里看得见它。
   */
  userId: number | null
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
  /**
   * 看板。`userId` 是**当前登录的人**（`useMockLogin` 记下的那个用户 id）：
   * 传了就是"与他有关、且该由他看见的那些"，**不传（没登录）就是不筛** —— 全量。
   *
   * <p>判断按状态挑列（后端 `AgentTaskStatus#party()`），不是某一列上的等值比较：
   * 新发布 / 已完成 / 已取消 —— 他是发布人**或**任务人都算（刚发出来、已经结束、被取消的，
   * 两个人都该在自己的列表里找得到）；待确认需求 / 待验收 —— 只认发布人（那两件只有他能做）；
   * 其余状态（含待确认计划 / 待授权 / 权限不足 / 执行中）—— 只认任务人（球在他手上）。
   * 球在 agent 手上的三格（三个"已确认"）与谁都不相关，两张列表里都不出现。
   */
  board: (userId?: number | null) => send<AgentTaskBoard>(
    'GET', userId == null ? '' : `?userId=${userId}`),

  detail: (id: number) => send<AgentTaskDetail>('GET', `/${id}`),

  /**
   * 发布任务。`publisher` 与 `assignee` 是**真实账号的登录名**（后端会核一遍账号表，
   * 不存在 → 400），落库的是查出来的那两个用户 id —— 它们只能来自
   * `permissionApi.searchUsers` 的候选，不是随便打的名字。
   * **没有 agentId**：那一列由平台在第一次接手这条任务时写入。
   */
  create: (payload: { publisher: string; assignee: string; description: string }) =>
    send<{ ok: boolean; task: AgentTaskCard }>('POST', '', payload),

  /** 推进当前事件。`result` 只在「执行中 → 待验收」那一步有意义（可空）。 */
  advance: (id: number, result?: string) =>
    send<AgentTaskWriteResult>('POST', `/${id}/advance`, { result }),

  /** 答复一条确认项。`answer` 是用户选的选项或自由回复（必填，判据在后端）。 */
  answer: (id: number, confirmId: number, answer: string) =>
    send<AgentTaskWriteResult>('POST', `/${id}/confirms/${confirmId}/answer`, { answer }),

  /**
   * 答复「已修改任务」：`INSUFFICIENT_PERMISSION` → 新发布（整条链重走一遍）。
   * **没有请求体** —— 这一格要的是一次选择（取消还是重走一遍），不是一段说明。
   */
  revise: (id: number) => send<AgentTaskWriteResult>('POST', `/${id}/revise`),

  /** 取消任务。`reason` 必填 —— 一条没有原因的取消记录事后只能靠回忆。 */
  cancel: (id: number, reason: string) =>
    send<AgentTaskWriteResult>('POST', `/${id}/cancel`, { reason }),
}
