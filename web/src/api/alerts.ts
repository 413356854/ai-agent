/**
 * 告警 + 灰度的调用层（阶段四 · 工程化第 5 件）。
 *
 * 与 `cost.ts` 的关系：那边回答「花了多少」，这边回答
 * 「**花了多少之外，有没有出事、以及有没有人在管**」——
 * 也就是说，这一页是成本看板那半句"能发现"的补完：
 * 看板能**看见**异常，而这里把异常变成**能查询、能确认、会自己出现和消失的事件**。
 *
 * 端点清单：
 *   GET  /api/alerts/rules               判据目录（含构建阶段的门禁，一处定义、两处使用）
 *   GET  /api/alerts                     当前状态（在响的 + 已恢复的）
 *   GET  /api/alerts/sweep?days=7        跑一轮评估（`dryRun=true` 则只算不写）
 *   POST /api/alerts/{fingerprint}/ack   确认一条（"我看过了"）
 *   GET  /api/canary?kind=agent-loop     当前灰度 + **实测分流分布**
 *   POST /api/canary/start               开一次实验
 *   POST /api/canary/stop                停一次实验（必须写清为什么停）
 *
 * ⚠️ 这一层有三个**故意**不是"普通字符串"的字段，它们各自对应一条纪律：
 *   - `phase` —— 判据属于构建阶段还是运行阶段。构建阶段的判据在本页**不会被评估**
 *     （那要去 CI 上看），界面上必须显示成"这里不评估"，否则读的人会以为
 *     "告警面板说构建也没问题"；
 *   - `state` —— 未处理 / 已确认 / 已恢复三态。"已确认"不等于"已解决"，
 *     而"未处理的条数"是值班交接的依据 —— 三个状态在界面上不能长得一样；
 *   - `split.participatingRuns` —— **实测参与分流的运行数**，而不是配置的重量。
 *     配置 20% 而实际 0 份是一件完全可能发生、并且不报任何错的事，
 *     所以界面上这两栏必须并排显示（见 `CanarySplit` 的注释）。
 */
import { DiagError, request } from './diag'

/**
 * 评估端点会写库（除非 `dryRun`），所以给的时间比只读端点宽一些：
 * 它要跑一次快照采集（四条聚合 + 两次检索统计）。
 */
export const ALERT_READ_TIMEOUT_MS = 30_000

// ---------------------------------------------------------------------------
//  与后端契约对应的类型
//  唯一事实来源是 ai.agent.alert.AlertController / ai.agent.canary.CanaryController，
//  改那边就要同步改这里。
// ---------------------------------------------------------------------------

/** 判据的阶段。**不是严重度** —— 分类标准是"这个判断在构建阶段能不能得出结论"。 */
export type AlertPhase = 'GATE' | 'OBSERVE'

export type AlertSeverity = 'INFO' | 'WARNING' | 'BLOCKER'

/** 告警状态。⚠️ 三态，不是布尔。 */
export type AlertState = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'

/** 判据目录里的一条。 */
export interface AlertRuleInfo {
  code: string
  title: string
  severity: AlertSeverity
  severityLabel: string
  severityMeaning: string
  phase: AlertPhase
  phaseLabel: string
  action: string
  /** 谁在执行它 —— 构建阶段那几条是别的类（例如 RagRegressionTest） */
  executedBy: string
  /** **本页会不会评估它**。构建阶段的判据是 false —— 这一格必须显示出来 */
  evaluatedHere: boolean
}

/** `GET /api/alerts/rules` 的响应 */
export interface AlertRulesResponse {
  ok: boolean
  /** 写操作是否需要令牌（false = 当前部署的写操作未受保护） */
  writeProtected: boolean
  thresholds: {
    consecutiveEmptyRetrievals: number
    canarySplitTolerance: number
    canaryMinSample: number
  }
  phases: Array<{
    phase: AlertPhase
    label: string
    meaning: string
    evaluatedHere: boolean
    count: number
  }>
  rules: AlertRuleInfo[]
  notes: string[]
}

/** 一条告警的当前状态。 */
export interface AlertEventInfo {
  /** 去重键：`判据代号 + '|' + 主体`（确认端点用它） */
  fingerprint: string
  ruleCode: string
  /** 说的是谁（模型名 / 检索 / 灰度版本 / 空串 = 没有主体） */
  subject: string
  title?: string
  severity: AlertSeverity
  severityLabel: string
  severityMeaning?: string
  phase: AlertPhase
  state: AlertState
  stateLabel: string
  firstSeenAt: string
  lastSeenAt: string
  /** 空串 = 还在响 */
  resolvedAt: string
  acknowledgedAt: string
  acknowledgedBy: string | null
  /** 一共被发现了多少次（持续成立会一直涨） */
  occurrences: number
  /** 第一次发现到现在的秒数（"这条响了多久了"） */
  firingSeconds: number
  /** 观测到的值 —— 报告里那句话的主角 */
  observed: string
  /** 判据阈值 + 它的形态（"跟什么比"） */
  threshold: string
  /** 为什么这个阈值是这个数 */
  why: string
  action?: string
  executedBy?: string
  /** 判定时的窗口 —— 没有它，上面的数字不可复现 */
  window: string
  needsAttention: boolean
}

/** `GET /api/alerts` 的响应 */
export interface AlertsResponse {
  ok: boolean
  /** 未处理的条数（界面顶部那个红点用它） */
  openCount: number
  firingCount: number
  acknowledgedCount: number
  worstSeverity: AlertSeverity | null
  events: AlertEventInfo[]
  writeProtected: boolean
}

/** 一轮评估里"如果现在判定，会响哪些"（dryRun 用） */
export interface AlertOutcomeInfo {
  ruleCode: string
  title: string
  subject: string
  severity: AlertSeverity
  observed: string
  threshold: string
  why: string
  note: string
}

/** `GET /api/alerts/sweep` 的响应（dryRun 与真实两态） */
export interface AlertSweepResponse {
  ok: boolean
  dryRun: boolean
  window: string
  openCount?: number
  fired?: AlertEventInfo[]
  resolved?: AlertEventInfo[]
  /** ⚠️ 单独一格："这一轮新响的"与"一直响着的"是两件事 */
  ongoingCount?: number
  ongoing?: AlertEventInfo[]
  wouldFire?: AlertOutcomeInfo[]
  quiet?: string[]
  skipped?: Array<{ ruleCode: string; title: string; note: string }>
  note?: string
}

/** 逐（版本, 变体）的运行数。 */
export interface CanaryVersionRuns {
  version: number
  /** `canary` / `stable` / 空串（**没参与这次实验** —— 与"对照组"是两件事） */
  variant: string
  variantLabel: string
  runs: number
}

/**
 * 一次灰度的**配置**。
 *
 * ⚠️ 它与 `CanarySplit` 是两个不同的东西：这个说"我配了多少"，
 * 那个说"**实际分到了多少**"。只显示前者是最容易犯的错 ——
 * 配置 20%、实际 0 份时，页面看起来一切正常，而那次实验等于没做。
 */
export interface CanaryInfo {
  kind: string
  candidateVersion: number
  weightPercent: number
  note: string
  startedBy: string | null
  startedAt: string
  active: boolean
  endedAt: string
  endedBy: string | null
  endReason: string | null
}

/** 观测到的分流分布。 */
export interface CanarySplit {
  candidateVersion: number
  weightPercent: number
  /** 窗口内全部有新步骤的运行数（含灰度之前起的） */
  runs: number
  /** 参与了分流的运行数（灰度期间新起的那些） */
  participatingRuns: number
  candidateRuns: number
  stableRuns: number
  observedRatio: number
  minSampleForJudgement: number
  /** 样本够不够下结论 —— 不够时界面**不许**显示"偏了多少" */
  sampleEnough: boolean
  runsStillNeeded: number
  /** ⚠️ 一份都没分到（这次实验等于没做） */
  noTraffic: boolean
  versions: CanaryVersionRuns[]
  note: string
}

/** `GET /api/canary` 的响应 */
export interface CanaryResponse {
  ok: boolean
  kind: string
  kindSummary: string
  kinds: string[]
  windowDays: number
  active: boolean
  canary: CanaryInfo | null
  /** 上一轮实验（停了之后仍然读得出来 —— 一次实验的结论不该随"点了停止"消失） */
  previous: CanaryInfo | null
  versions: Array<{
    version: number
    hash: string
    fingerprint: string
    charCount: number
    tokenEstimate: number
    note: string
    author: string | null
    forkedFrom: number | null
    createdAt: string
  }>
  activeVersion: number
  activeFingerprint: string
  split: CanarySplit
}

// ---------------------------------------------------------------------------
//  调用函数
// ---------------------------------------------------------------------------

/** 判据目录（**一次库都不查** —— 它是说明书）。 */
export function getAlertRules(): Promise<AlertRulesResponse> {
  return getJson<AlertRulesResponse>('/api/alerts/rules', '判据目录')
}

/** 当前有什么在响、响多久了、有人看过吗。 */
export function getAlerts(includeResolved = true): Promise<AlertsResponse> {
  return getJson<AlertsResponse>(`/api/alerts?includeResolved=${includeResolved}`, '告警列表')
}

/**
 * 跑一轮评估。
 *
 * `dryRun=true` 时**只算不写** —— 那是这一组端点里唯一的纯读入口，
 * 给"我只想看现在是什么状态"用。默认 false：页面刷新时会真的把结论落库，
 * 因为"没人刷新就永远没有告警记录"等于这一件没做。
 */
export function runAlertSweep(days: number, dryRun = false): Promise<AlertSweepResponse> {
  return getJson<AlertSweepResponse>(`/api/alerts/sweep?days=${days}&dryRun=${dryRun}`, '告警评估')
}

/** 确认一条（"我看过了"）。409 = 它已经恢复了，不该被标成已处理。 */
export function acknowledgeAlert(fingerprint: string, by: string): Promise<{ ok: boolean; event?: AlertEventInfo; message?: string }> {
  return postJson(`/api/alerts/${encodeURIComponent(fingerprint)}/ack`, { by })
}

/** 当前灰度与它的实测分布。 */
export function getCanary(kind: string, days: number): Promise<CanaryResponse> {
  return getJson<CanaryResponse>(`/api/canary?kind=${encodeURIComponent(kind)}&days=${days}`, '灰度状态')
}

/** 开一次灰度。四个前置条件的拒绝信息由后端给（前端不重复那套判据）。 */
export function startCanary(input: {
  kind: string
  candidateVersion: number
  weightPercent: number
  note: string
  by?: string
}): Promise<{ ok: boolean; canary?: CanaryInfo; next?: string; message?: string }> {
  return postJson('/api/canary/start', input)
}

/** 停一次灰度（`endReason` 必填）。 */
export function stopCanary(input: {
  kind: string
  endReason: string
  by?: string
}): Promise<{ ok: boolean; canary?: CanaryInfo; next?: string; message?: string }> {
  return postJson('/api/canary/stop', input)
}

// ---------------------------------------------------------------------------
//  内部工具（与 api/cost.ts 同一套：复用 diag.ts 的 request 与 DiagError）
// ---------------------------------------------------------------------------

async function getJson<T>(path: string, what: string): Promise<T> {
  const response = await request(path, what, 'GET', ALERT_READ_TIMEOUT_MS)
  return parse<T>(response, what)
}

/**
 * POST 一个 JSON 体。
 *
 * ⚠️ 与 `getJson` 有一处关键差别：**400 / 409 的响应体也要被解析出来读**。
 * 那一句话正是"为什么这次操作没执行"的答案（重量必须在 1..99 / 已经有一次
 * 进行中的灰度 / 它已经恢复了），丢了它界面就只能显示一句"请求失败" ——
 * 而使用者会反复点同一个按钮。
 */
async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await request(path, '写操作', 'POST', ALERT_READ_TIMEOUT_MS, JSON.stringify(body))
  return parse<T>(response, '写操作')
}

/**
 * 解析响应体。
 *
 * ⚠️ **这里刻意不再自己判 `response.ok`**：`diag.ts` 的 `request()` 已经处理过了，
 * 而且它做得更好 —— 它会把 400 / 409 响应体里那句说明提取出来（`extractHttpError`），
 * 并把 HTTP 状态码挂在 `DiagError.status` 上。自己再判一次的话会有两个后果：
 * 一句话有两份实现（迟早分叉），以及**状态码被丢掉** ——
 * 而这一组端点恰恰要靠状态码区分两件处置完全不同的事：
 * 400（改参数再试）与 409（先停掉 / 它已经恢复了，重试没用）。
 * 界面按 `DiagError.status` 分类，所以它必须一路传上来。
 */
async function parse<T>(response: Response, what: string): Promise<T> {
  const text = await response.text().catch(() => '')
  try {
    return (text ? JSON.parse(text) : {}) as T
  }
  catch {
    throw new DiagError(`拿「${what}」时后端返回的不是 JSON：${text.slice(0, 200) || '(空响应体)'}`)
  }
}
