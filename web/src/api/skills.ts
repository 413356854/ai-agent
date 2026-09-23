/**
 * 技能管理端点的契约（`ai.agent.controller.SkillController`）。
 *
 * ---------------------------------------------------------------------------
 * 与 permissions.ts / tasks.ts 同一条规矩：**这里的类型必须与后端返回的 JSON 逐字对应**
 * ---------------------------------------------------------------------------
 * 后端返回的是手拼的 `Map`（不是 record 序列化），所以编译器帮不上忙 ——
 * 字段名写错只表现为页面上某一格是 `undefined`，而控制台干干净净。
 * 改后端时回来对一遍。
 *
 * ---------------------------------------------------------------------------
 * 四条与页面行为绑定的后端事实
 * ---------------------------------------------------------------------------
 * **一、`userId` 是查询参数，不是请求体字段**：它是"我是谁"，四个端点上只有这一种读法
 * （`DELETE` 没有请求体，而在 POST / PUT 里换成请求体字段会让"这个字段从哪儿读"每次都要看一眼）。
 *
 * **二、没有 `ownerId` 这个请求字段**：拥有者与最后修改人由后端按 `userId` 填。
 * 页面上不出现"拥有者"这个输入框（填了也不会被采纳），新增时的拥有者就是当前登录的人。
 *
 * **三、读是公开的，写要登录**：未登录时 `GET` 给全部记录、`folders` 是空的，
 * 而三个写端点一律 400。所以页面上未登录时按钮是禁用的，而不是点下去才报错。
 *
 * **四、`description` / `content` 的两条空语义不同**（`PUT`）：字段**缺席**（`undefined`）=
 * 不动这一列，**空串** = 清空这一列。界面照这条来：编辑框里删光再保存是一次明确的清空。
 */

/** 一条技能（`GET /api/skills` 的 `skills[]`，也是每个写动作回执里带的那一份）。 */
export interface AgentSkill {
  id: number
  /**
   * 文件夹目录。取值只能是当前登录人角色的目录前缀之一（`folders` 里给的那几个）——
   * 它是**授权范围**，不是随手打的字符串（写入时后端会对着那份选项集核一遍）。
   */
  folder: string
  name: string
  /** 这个技能是干什么的；没填时后端给空串。 */
  description: string
  /** 技能正文；没填时后端给空串（界面上"清空"就是把它删光再保存）。 */
  content: string
  /** 拥有者的用户 id（`agent_user.id`）。**由后端填**，请求体里没有这个字段。 */
  ownerId: number
  /** 拥有者姓名（后端关联账号表得到）—— 列表上显示的是它。 */
  ownerName: string
  /** 最后修改人的用户 id；更早的数据可能没有记下来（`null`）。 */
  lastModifiedBy: number | null
  /** 最后修改人姓名。 */
  lastModifiedByName: string
  /** ISO-8601 文本（`Instant.toString()`），直接显示。 */
  createdAt: string
  updatedAt: string
}

/** `GET /api/skills` 的响应：这一页要的一切。 */
export interface SkillSnapshot {
  skills: AgentSkill[]
  /** 这次返回的条数；按人筛过之后它是**筛完**的条数（不是全库的条数）。 */
  total: number
  /**
   * 后端回显的筛选依据：这份是"这个人拥有的"（用户 id），**不传就是不筛**（`null`）。
   * 界面不自己推一份 —— 它要与后端实际做了什么一致。
   *
   * <p>⚠️ 它**不是**每一行上的 `ownerId`（那是记录自己的归属）：两者在"筛过之后"恰好相等，
   * 而"没筛"时前者是 `null`、后者是各行的拥有者。
   */
  userId: number | null
  /** 那个人的姓名（未筛时是空串）—— 界面上那句"只看…"直接用它。 */
  userName: string
  /**
   * 本次是否按归属筛过。它与 `skills` 是否为空是两件事 ——
   * "他有 0 条技能"与"这次没筛"在 JSON 里长得一样，而界面上要说的话不同。
   */
  ownershipJudged: boolean
  /**
   * 登录人能选的文件夹（**他全部角色的目录前缀并集**，已去重）。
   * 未登录时是空数组 —— 那是"没有他，就没有这一份范围"，不是"一个文件夹都没有"。
   */
  folders: string[]
  howToWrite: string
}

/**
 * 写入被拒。
 *
 * 单独一个类型是为了让界面能分开 409 与 400：前者是"同一个文件夹下这个名字已经有了"
 * （换个名字，或者去改那一条），后者是"你填的东西不合规"（该改了重试）。
 * 压成一句红字会让用户对着重名一遍遍点保存。
 */
export class SkillRejectedError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'SkillRejectedError'
    this.status = status
  }
}

/** 写动作的回执：ok + 改完之后的那一份（后端刻意不返回裸 ok，见控制器注释）。 */
export interface SkillWriteResult {
  ok: boolean
  skill: AgentSkill
}

const BASE = '/api/skills'

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
  // SkillController 自己给的 {error}（409）。两种都读，界面拿到的都是一句话
  const message = typeof body.message === 'string'
    ? body.message
    : typeof body.error === 'string' ? body.error : `请求失败（HTTP ${response.status}）`
  throw new SkillRejectedError(message, response.status)
}

async function send<T>(method: string, path: string, payload?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (payload !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(payload)
  }
  return parse<T>(await fetch(`${BASE}${path}`, init))
}

/** `userId` 拼成查询串：不传（没登录）时是空串 —— 后端据此返回全部记录。 */
function of(userId?: number | null): string {
  return userId == null ? '' : `?userId=${userId}`
}

export const skillApi = {
  /**
   * 技能列表 + 当前登录人的文件夹选项。
   *
   * <p>`userId` 是**当前登录的人**（`useMockLogin` 记下的那个用户 id）：
   * 传了就是"他拥有的那些"，**不传（没登录）就是不筛** —— 全部记录，且 `folders` 为空。
   */
  snapshot: (userId?: number | null) => send<SkillSnapshot>('GET', of(userId)),

  /**
   * 建一条技能。拥有者与最后修改人由后端按 `userId` 填（请求体里没有这两个字段）。
   * 文件夹必须是 `folders` 里的一个 —— 后端会核，不在里面是 400 并列出可选的。
   */
  create: (userId: number, payload: { folder: string; name: string; description?: string; content?: string }) =>
    send<SkillWriteResult>('POST', of(userId), payload),

  /**
   * 改一条技能的内容。归属不变（只有拥有者能改，判据在后端）。
   *
   * <p>`description` / `content` 传 `undefined` 表示"不动这一列"，传空串表示"清空这一列"。
   */
  update: (
    userId: number,
    id: number,
    payload: { folder?: string; name?: string; description?: string; content?: string },
  ) => send<SkillWriteResult>('PUT', `/${id}${of(userId)}`, payload),

  /** 删一条技能。回执里带回被删掉的那一份（界面要说的是"已删除《名字》"）。 */
  remove: (userId: number, id: number) =>
    send<{ ok: boolean; skillId: number; skill: AgentSkill }>('DELETE', `/${id}${of(userId)}`),
}
