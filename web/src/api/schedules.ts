/**
 * 定时任务端点的契约（`ai.agent.controller.AgentTaskScheduleController`）。
 *
 * ---------------------------------------------------------------------------
 * 与 tasks.ts / permissions.ts 同一条规矩：**这里的类型必须与后端返回的 JSON 逐字对应**
 * ---------------------------------------------------------------------------
 * 后端返回的是手拼的 `Map`（不是 record 序列化），所以编译器帮不上忙 ——
 * 字段名写错只表现为页面上某一格是 `undefined`，而控制台干干净净。
 * 改后端时回来对一遍。
 *
 * ---------------------------------------------------------------------------
 * 五条与页面行为绑定的后端事实
 * ---------------------------------------------------------------------------
 * **一、`cron` 是"6 段式"（秒 分 时 日 月 周）。** Unix crontab 的 5 段式会被拒绝
 * （实测 Spring 7.0.9 只认 6 段）。界面上的构造器发出的永远是 6 段。
 *
 * **二、`enabled` 与 `scheduled` 是两件事。** 前者是"这条规则被启用了没有"（库里那一列），
 * 后者是"它此刻在不在调度线程池里"。启用但正则被手工改坏了时前者为真、后者为假 ——
 * 而那正是"配好了它就是不动"的答案，所以两格都要显示。
 *
 * **三、`nextRuns` 是后端算出来的（本地时区的 `yyyy-MM-dd HH:mm:ss` 文本）。**
 * 前端不自己算：线程池装线程用的是同一份判断，两处各算一次就会出现
 * "预览说 09:00、实际 10:00 跑"。停用的规则这一格是**空数组** ——
 * 那不是"算不出来"，而是"它现在不在池子里，没有下一次"。
 *
 * **四、改规则是 `PUT` 且四个可改列都要给全。** 发布人**不在可改项里**
 * （改它等于换一个人来背这条规则，那件事该由"停用旧的、建一条新的"表达）。
 *
 * **五、发布人 / 任务人在请求体里是用户 id，不是姓名。** `publisherId` / `assigneeId`
 * 就是 `permissionApi.searchUsers` 候选项里的 `id`（后端按 id 核一遍账号表，不在 → 400）。
 * 响应里的 `publisher` / `assignee` 是**姓名**（显示名优先），只用来显示 ——
 * 拿它回传会查不到人。
 *
 * **六、`runs` / `lastRunAt` 数的是"这条规则派过几条任务"。**
 * `runs=0` 与 `lastRunAt=null` 同时出现，界面据此说"一次都没派过"，
 * 而不是显示一个"0 次 / 时间空白"的组合让人猜。
 */

/** 一条定时规则（`GET /api/agent-task-schedules` 的 `rules[]`，也是每个写动作回执里的那一份）。 */
export interface AgentTaskSchedule {
  id: number
  /** 定时正则，**6 段式**（秒 分 时 日 月 周）。库里存的是归一之后的那一份。 */
  cron: string
  /** 后端给的中文说法（"每天 09:00 触发"）—— 界面直接显示它，不自己拼一份。 */
  cronDescription: string
  /**
   * 发布人的用户 id（`agent_user.id`）。
   *
   * <p>**它就是写请求里要回传的那一格**（配规则给 `publisherId`、改规则不用给 ——
   * 发布人不可改）。姓名那一份只用来显示。
   */
  publisherId: number
  /** 发布人姓名（后端关联账号表得到，显示名优先）。**只用于显示。** */
  publisher: string
  /** 任务人的用户 id —— 每次触发生成的那条任务派给他，也是改规则时要回传的那一格。 */
  assigneeId: number | null
  /**
   * 任务人的**姓名**（后端关联账号表得到，显示名优先）；任务人为空的行显示「未指定任务人」。
   * **界面显示的是它。**
   */
  assignee: string
  /**
   * 任务描述 —— <b>它是"模板"，不是最终那句话</b>。
   *
   * <p>到点会先让模型把里面的**周期说法**（"昨天""上个月""本周"）解析成那一刻的具体日期，
   * 再按解析后的那句派活。所以库里那条任务上的描述与这一格<b>通常不是同一句</b>：
   * 用户写"把昨天的失败运行汇总成晨报"是对的写法，而任务上是
   * "把 2026-10-11 的失败运行汇总成晨报"。
   *
   * <p>这是刻意的："昨天"在一条每天触发的规则里，每一条任务上指的都是不同的日子，
   * 而执行那条任务的 Agent 看不到"这是一条每天的规则"，它只看到描述那一句话。
   *
   * <p>⚠️ 解析失败时**这一次不派活**（留到下一个周期重试）——
   * 所以"某天少了一条任务"有一种原因在这里，而它在任务看板上没有痕迹。
   */
  description: string
  /** 启用状态。停用的规则**不在调度线程池里**（那一格没有线程）。 */
  enabled: boolean
  /** 它派过几条 agent 任务。0 = 一条都没派过。 */
  runs: number
  /** 最后一次派任务的时间（ISO-8601）；`null` = 一次都没派过。 */
  lastRunAt: string | null
  /** 接下来 5 次触发时刻（`yyyy-MM-dd HH:mm:ss`，本地时区）；停用时是空数组。 */
  nextRuns: string[]
  /**
   * 此刻它在不在调度线程池里。它与 `enabled` 是**两件事**（见文件头第二条）——
   * 界面上要分开说"停用了"与"启用了但装不上"。
   */
  scheduled: boolean
  createdAt: string | null
  updatedAt: string | null
}

/**
 * 调度线程池的样子。
 *
 * <p>它在界面上是**必须显示**的一栏：这一页的失败形态是"规则配好了、它就是不动"，
 * 而那句话有两个完全不同的原因 —— 规则不在池子里（停用 / 装的时候失败了）
 * 与在池子里但还没到点。不把池子摆出来，这两件事在界面上长得一样。
 */
export interface SchedulePool {
  /** 池子的线程数上限（配置项 `ai.agent.schedule.pool-size`）—— 同一瞬间最多几条规则能真的触发。 */
  poolSize: number
  /** 正在跑触发动作的线程数（那个动作是"插一条任务记录"，毫秒级）。 */
  activeThreads: number
  /** 池子当前的线程数（用了才建）。 */
  poolSizeNow: number
  /** 现在池子里挂着几条规则的线程。 */
  scheduled: number
  /**
   * 已取消、但还没被池子清掉的队列节点数。
   * 它不是错误：改一次规则会取消旧线程，而那个节点要留到原定触发时刻才消失。
   */
  cancelledPending: number
}

/** `GET /api/agent-task-schedules` 的响应。 */
export interface ScheduleSnapshot {
  rules: AgentTaskSchedule[]
  /** 这次返回的条数（这一页**不按人筛** —— 一条别人配的规则照样会在半夜派活）。 */
  total: number
  /**
   * 后端回显的"当前登录的人"（界面传上去的那一个），没登录时是 `null`。
   * ⚠️ 它**不是筛选条件**：这一页的列表是全量，它与"看得见哪些规则"无关，
   * 只是让界面能说清"现在页头那个人是谁"。
   */
  userId: number | null
  pool: SchedulePool
  howToWrite: string
}

/** 一次正则预览（`POST /api/agent-task-schedules/preview`，**不写库**）。 */
export interface SchedulePreview {
  /** 归一之后的表达式（去多余空格）—— 保存时后端存的就是这一份。 */
  cron: string
  /** 中文说法。 */
  description: string
  /** 未来 5 次触发时刻（`yyyy-MM-dd HH:mm:ss`）。 */
  nextRuns: string[]
}

/** 手工触发的回执。 */
export interface ScheduleTriggerResult {
  ok: boolean
  scheduleId: number
  /** 真的落库了的那条 agent 任务 id（不是"预演"）。 */
  taskId: number
  /** 那条任务落在哪个状态（新发布 / 待确认需求…）。 */
  statusLabel: string
  message: string
}

/** 写动作的回执：ok + 改完之后的那一份（后端刻意不返回裸 ok，见控制器注释）。 */
export interface ScheduleWriteResult {
  ok: boolean
  rule: AgentTaskSchedule
  message: string
}

/**
 * 写入被拒。
 *
 * <p>这一族只有一种失败：请求里填的东西不对（正则不合法、账号不存在、规则不存在、
 * 派过任务的不能删），后端一律回 <b>400 + 一句能照着改的话</b>。
 * 单独一个类型是为了让界面能把那句话原样显示出来，而不是套一句"操作失败"。
 */
export class ScheduleRejectedError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ScheduleRejectedError'
    this.status = status
  }
}

const BASE = '/api/agent-task-schedules'

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
  // 控制器自己给的 {error}（缺字段那一族）。两种都读，界面拿到的都是一句话
  const message = typeof body.message === 'string'
    ? body.message
    : typeof body.error === 'string' ? body.error : `请求失败（HTTP ${response.status}）`
  throw new ScheduleRejectedError(message, response.status)
}

async function send<T>(method: string, path: string, payload?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (payload !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(payload)
  }
  return parse<T>(await fetch(`${BASE}${path}`, init))
}

export const scheduleApi = {
  /**
   * 规则列表 + 调度线程池。
   *
   * `userId` 只是回显"当前登录的人"，**不是筛选条件**：这一页要回答的是
   * "现在哪些规则会跑"，而那个答案与"我是谁"无关。
   */
  list: (userId?: number | null) => send<ScheduleSnapshot>(
    'GET', userId == null ? '' : `?userId=${userId}`),

  /** 一条规则现在长什么样。 */
  detail: (id: number) => send<AgentTaskSchedule>('GET', `/${id}`),

  /**
   * 校验一句正则并拿回"中文说法 + 未来 5 次触发时刻"——**不写库、不装线程**。
   *
   * 界面上"页面配置化定时规则"那一块靠它：用户改一次配置就发一次，
   * 拿回来的 `nextRuns` 是唯一能让人当场确认"我配的对不对"的证据。
   * ⚠️ 保存时后端**会再校验一遍**：预览与保存之间用户可能又改过输入框。
   */
  preview: (cron: string) => send<SchedulePreview>('POST', '/preview', { cron }),

  /**
   * 配一条规则（落库 + 当场把线程装进调度线程池）。
   *
   * `publisherId` / `assigneeId` 是**用户 id**（账号表里的 `agent_user.id`）——
   * 它们只能来自 `permissionApi.searchUsers` 的候选（后端会核一遍账号表，不存在 → 400）。
   * 发布人不可填：它就是当前登录的人。
   */
  create: (payload: {
    publisherId: number; assigneeId: number; description: string; cron: string; enabled: boolean
  }) => send<ScheduleWriteResult>('POST', '', payload),

  /**
   * 改一条规则：正则 / 任务人 / 描述 / 启停**四列都要给全**（没改就原样传回来）。
   * 后端固定做"删旧线程、按新正则重加"—— 所以改完之后 `nextRuns` 一定是按新表达式算的。
   */
  update: (id: number, payload: {
    assigneeId: number; description: string; cron: string; enabled: boolean
  }) => send<ScheduleWriteResult>('PUT', `/${id}`, payload),

  /** 停用 / 启用。停用会把线程从池子里摘掉（不是"留着线程但不做事"）。 */
  setEnabled: (id: number, enabled: boolean) =>
    send<ScheduleWriteResult>('POST', `/${id}/enabled`, { enabled }),

  /**
   * 现在就跑一次：立刻按这条规则派一条 agent 任务。
   *
   * <b>不重排下一次触发</b> —— 它是"按人点的一次"，而不是"这条规则的周期变了"。
   * 停用的规则也能手工触发（那正是"先配好、确认一次再启用"的用法）。
   */
  trigger: (id: number) => send<ScheduleTriggerResult>('POST', `/${id}/trigger`, {}),

  /**
   * 删掉一条规则。**派过任务的会被拒绝（400）**：那些任务还留在 agent任务 那一页，
   * 而这条规则是它们唯一的由来。要停就停用 —— 停用同时会把线程摘掉，效果一样而后路还在。
   */
  remove: (id: number) => send<{ ok: boolean; message: string }>('DELETE', `/${id}`),
}
