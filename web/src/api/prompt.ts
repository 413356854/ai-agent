/**
 * Prompt 版本管理端点的契约（阶段四 · 工程化第 3 件）。
 *
 * ---------------------------------------------------------------------------
 * 与 cost.ts 同一条规矩：**这里的类型必须与后端返回的 JSON 逐字对应**
 * ---------------------------------------------------------------------------
 * 后端是 `ai.agent.prompt.PromptController`，它返回的是手拼的 `Map`
 * （不是 record 序列化），所以编译器**帮不上忙** —— 字段名写错只会表现为
 * 页面上某一格是 `undefined`，而控制台干干净净。所以两边的字段名在这里逐条列清，
 * 改后端的时候必须回来对一遍。
 *
 * ---------------------------------------------------------------------------
 * 这一页的三种"没有"
 * ---------------------------------------------------------------------------
 * 提示词这一块有三处可空，而它们各自的含义完全不同，页面上必须分开渲染：
 *   1. `activeVersion` 为 null —— **库里还没有任何版本**（理论上只在启动初始化失败时出现）。
 *      此时内容来自 classpath 兜底，`source` 是 `classpath`，界面必须说出来；
 *   2. `author` / `note` 为 null —— 那一版**没有署名 / 没有说明**。
 *      老数据可能长这样，而"没署名"与"署名成了空"是两件事，不能都渲染成空白；
 *   3. `createdVersion` 为 null —— 回滚**没有产生新版本**（内容已与目标版相同）。
 *      这不是失败，界面必须用 `noOp` 说明，否则读者会以为回滚没生效。
 *
 * 这三种"没有"如果都渲染成 `-`，读者就没法区分"功能坏了""老数据"与"这次没做事"。
 */

/** 受管理的提示词种类（与后端 PromptKind 的 defaultValue 一致）。 */
export type PromptKind = 'system-agent' | 'system-agent-tools'

/** 一条判据的结论。 */
export interface PromptCheck {
  rule: string
  /** REJECT = 被拒；WARN = 可疑但放行；OK = 通过。 */
  level: 'REJECT' | 'WARN' | 'OK'
  /** 具体数字或原因 —— 后端保证它非空，所以界面上可以原样显示。 */
  detail: string
}

export interface PromptVersionSummary {
  version: number
  /** SHA-256 前 12 位。用于"页面这一版 == 库里那一版"的肉眼比对与断言。 */
  hash: string
  chars: number
  tokens: number
  /** 可能为 null（老数据没写说明）。 */
  note: string | null
  author: string | null
  createdAt: string | null
  /** 复制自哪一版；手工新建为 null，回滚产生的版本会带上它。 */
  forkedFrom: number | null
  /** 是不是当前生效的那一版。 */
  active: boolean
}

export interface PromptVersionDetail extends PromptVersionSummary {
  content: string
  lines: number
}

/** 某一份提示词当前的概况。 */
export interface PromptInfo {
  kind: PromptKind
  summary: string
  classpathLocation: string
  versionCount: number
  /** 库里没有版本时为 null —— 此时 source 一定是 'classpath'。 */
  activeVersion: number | null
  activeHash: string | null
  activeChars: number
  activeTokens: number
  /** 'library' = 来自版本库；'classpath' = 兜底（库里还没有版本）。 */
  source: 'library' | 'classpath'
  /** 谁在读这一份（让"改了会影响哪些功能"在页面上一眼可见）。 */
  readBy: string
}

export interface PromptListResponse {
  prompts: PromptInfo[]
  hotReload: {
    takesEffect: string[]
    doesNotAffect: string[]
  }
  notes: string[]
}

export interface PromptVersionsResponse {
  prompt: PromptInfo
  versions: PromptVersionSummary[]
  count: number
}

export interface PromptVersionResponse {
  prompt: PromptInfo
  version: PromptVersionDetail
}

export interface PromptDiffLine {
  op: 'SAME' | 'ADD' | 'REMOVE' | 'GAP'
  fromLine: number | null
  toLine: number | null
  text: string
}

export interface PromptDiffResponse {
  from: number
  to: number
  identical: boolean
  added: number
  removed: number
  tokenDelta: number
  fromTokenEstimate: number
  toTokenEstimate: number
  lines: PromptDiffLine[]
  note: string
}

/** 存新版本的响应（201）。 */
export interface PromptSavedResponse {
  prompt: PromptInfo
  version: PromptVersionDetail
  validation: { checks: PromptCheck[]; warnings: number }
  note: string
}

/** 激活 / 回滚的响应。 */
export interface PromptActionResult {
  prompt: PromptInfo
  activeVersion: number
  activeHash: string
  activeTokens: number
  author: string | null
  action: 'activate' | 'rollback'
  message: string
  /** 只有回滚会带。 */
  noOp?: boolean
  /** 回滚**新增**的那一版；noOp 时为 null。 */
  createdVersion?: number | null
  targetVersion?: number
  note?: string
}

/**
 * 提示词写入被拒（HTTP 400）。
 *
 * 单独一个类型而不是"所有错误都变成一句 message"：
 * 这个错误的**价值在判据清单里** —— 界面要能逐条列出"哪一条没过、具体数字是多少"，
 * 使用者才能照着改。压成一句话会让这个端点最主要的交互价值消失。
 */
export class PromptRejectedError extends Error {
  readonly checks: PromptCheck[]

  constructor(message: string, checks: PromptCheck[]) {
    super(message)
    this.name = 'PromptRejectedError'
    this.checks = checks
  }
}

/**
 * 切换时的并发冲突（HTTP 409）。
 *
 * 它必须与 400 分开：**刷新之后重试是有意义的**。
 * 界面上要给的是"现在是第 N 版、你看到的是第 M 版"，以及一个"刷新"的入口 ——
 * 而不是一个红色的"操作失败"。
 */
export class PromptConflictError extends Error {
  readonly expectedVersion: number | null
  readonly actualVersion: number | null

  constructor(message: string, expectedVersion: number | null, actualVersion: number | null) {
    super(message)
    this.name = 'PromptConflictError'
    this.expectedVersion = expectedVersion
    this.actualVersion = actualVersion
  }
}

/* ------------------------------------------------------------------ */
/*  请求                                                                */
/* ------------------------------------------------------------------ */

const BASE = '/api/prompts'

/**
 * 统一的响应处理。
 *
 * 【为什么必须在这里把 400 / 409 挑出来，而不是让调用方看 status】
 * 因为这两个码在界面上要变成**两种完全不同的交互**（逐条列出问题 / 提示刷新），
 * 而如果它们只以"一个带 status 的通用错误"流到组件里，
 * 迟早会有人图省事把两者都渲染成一句红字 —— 那时 409 的"可重试"这个信息就丢了。
 */
async function parse<T>(response: Response): Promise<T> {
  if (response.ok) {
    return (await response.json()) as T
  }
  let body: Record<string, unknown> = {}
  try {
    body = (await response.json()) as Record<string, unknown>
  } catch {
    // 非 JSON 的错误体（比如代理返回的 HTML 错误页）—— 保持 body 为空，
    // 下面会退化成用状态码说话，而不是抛一个"Unexpected token <"这种没人看得懂的错
  }
  const message = typeof body.message === 'string' ? body.message : `请求失败（HTTP ${response.status}）`
  if (response.status === 400) {
    throw new PromptRejectedError(message, (body.checks as PromptCheck[]) ?? [])
  }
  if (response.status === 409) {
    throw new PromptConflictError(message, (body.expectedVersion as number) ?? null,
      (body.actualVersion as number) ?? null)
  }
  throw new Error(message)
}

async function getJson<T>(path: string): Promise<T> {
  return parse<T>(await fetch(`${BASE}${path}`))
}

async function postJson<T>(path: string, payload: unknown): Promise<T> {
  return parse<T>(await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }))
}

export const promptApi = {
  list: () => getJson<PromptListResponse>(''),

  versions: (kind: PromptKind) => getJson<PromptVersionsResponse>(`/${kind}/versions`),

  version: (kind: PromptKind, version: number) =>
    getJson<PromptVersionResponse>(`/${kind}/versions/${version}`),

  diff: (kind: PromptKind, from?: number, to?: number) => {
    const query = new URLSearchParams()
    if (from !== undefined) query.set('from', String(from))
    if (to !== undefined) query.set('to', String(to))
    const suffix = query.toString()
    // from/to 全省略时后端会自己取"上一版 → 最新版" —— 这个规则只有一处实现，
    // 不让前端也算一遍（两处实现迟早不一致，而那时两边都"看起来对"）
    return getJson<PromptDiffResponse>(`/${kind}/diff${suffix ? `?${suffix}` : ''}`)
  },

  /**
   * 存一个新版本（**不生效**）。
   *
   * `force` 是"确认放行"：只对警告级判据生效，压不住拒绝级判据。
   * 界面上它必须是一个**显式的勾选**，而不是保存失败后自动重试带上的参数 ——
   * 自动带上就等于把校验关掉了，而人不会知道自己刚刚略过了什么。
   */
  save: (kind: PromptKind, payload: { content: string; note: string; author?: string; force?: boolean }) =>
    postJson<PromptSavedResponse>(`/${kind}/versions`, payload),

  /**
   * 让某一版生效。
   *
   * `expectedVersion` 是**并发保护**，界面必须总是带上它（取当前列表里那个 active 值）。
   * 不带就等于声明"我在看页面的时候没人动过"，而那正是静默覆盖发生的前提。
   */
  activate: (kind: PromptKind, payload: { version: number; expectedVersion: number | null; author?: string }) =>
    postJson<PromptActionResult>(`/${kind}/activate`, payload),

  rollback: (kind: PromptKind, payload: { version: number; author?: string; note?: string }) =>
    postJson<PromptActionResult>(`/${kind}/rollback`, payload),
}
