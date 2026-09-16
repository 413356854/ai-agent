/**
 * 成本看板的调用层（阶段四 · 工程化）。
 *
 * 与 `agent.ts` 的关系：那边回答「**某一次运行**干了什么」，
 * 这边回答「**一批运行**一共花了多少、谁花的」—— 取数粒度差一个数量级。
 *
 * 端点清单：
 *   GET /api/cost/summary?days=7   看板快照（合计 / 按天 / 按业务 / 按模型 / 按工具 / 对账）
 *   GET /api/cost/prices           价格表与「还缺哪些模型的价格」
 *
 * ⚠️ 这一层的类型里有两处**刻意可空**，它们不是偷懒，而是这一页最重要的两条纪律：
 *   - `amount: string | null`  —— null 表示「**我们算不出金额**」（价格没配 / 模型没上报）。
 *     它不是 0。把 null 渲染成 0 会让人得出「这个功能不花钱」的结论，
 *     而真相是「我们不知道它花多少钱」。
 *   - `cachedPromptTokens` 可能一整窗都是 0，而这**有两种成因**（真没命中 /
 *     上游不报这个字段），数据本身分不出来 —— 界面不许挑一个说。
 */
import { DiagError, request } from './diag'

/**
 * 只读端点的超时。
 *
 * 这个端点在服务端要跑四条 GROUP BY 加一次对账查询。本地几十条记录时是毫秒级，
 * 但它的耗时**随运行数增长**（不像 `/api/agent/runs` 那样有 LIMIT）。
 * 给 30 秒是因为：真慢到这个程度时，问题在数据库（缺索引 / 库太大），
 * 而不是"再多等一会儿就好了"。
 */
export const COST_READ_TIMEOUT_MS = 30_000

// ---------------------------------------------------------------------------
//  与后端契约对应的类型。
//  唯一事实来源是 ai.agent.cost.CostDashboardView（各端点的返回体），
//  改那边就要同步改这里。
// ---------------------------------------------------------------------------

/** 一组成本数字。按天 / 按业务 / 按模型的行都是这个形状。 */
export interface CostGroup {
  /** 分组键；`null` 表示这一组没有键（未标注归因 / 未上报模型名） */
  key: string | null
  /** 给人看的标签（key 为空时是「未标注」/「未上报模型名」） */
  keyLabel?: string
  /** key 为空时为真 —— 界面据此做特殊处理（比如提示去补标注），而不是当成一个真业务 */
  keyMissing?: boolean
  /** 只有按天的行有 */
  day?: string

  runs: number
  /** 已结束的运行数（含被上限拦下的那些，不含还在 RUNNING 的） */
  finishedRuns: number
  /**
   * 被**上限**拦下的运行数（步数 / token / 时长 / 重复动作 / 空回答）。
   *
   * 它是 `finishedRuns` 的**子集**，既不是成功也不是失败 —— 单独一格是为了回答
   * "有多少钱花在没办成事上"。注意别把它与 `failedRuns` 相加成"未成功"：
   * 那会把"我们自己的上限太紧"与"上游出问题"混成一个数字，而两者的改法相反。
   */
  stoppedRuns: number
  /** 失败的运行数 —— "花了钱又没办成事"的规模，账单里看不到 */
  failedRuns: number
  promptTokens: number
  /** 其中命中上游提示词缓存的部分（`promptTokens` 的子集，不是额外一档） */
  cachedPromptTokens: number
  completionTokens: number
  totalTokens: number
  /** 命中率 0~1 */
  cacheHitRate: number
  elapsedMs: number
  toolCalls: number
  toolFailures: number
  toolElapsedMs: number
  firstAt: string

  /**
   * 金额字符串（固定 4 位小数）。
   *
   * **`null` 表示算不出**，此时必须看 `amountReason`，并且**绝不能显示成 0**。
   */
  amount: string | null
  amountMicros?: number | null
  currency?: string
  /** 算不出金额的原因（后端给的，前端不自己编） */
  amountReason?: string | null
  /** 命中的价格规则（`*` 表示兜底规则）；多模型混在一起时没有这个字段 */
  priceRule?: string
  priceSource?: string
  /**
   * 这个金额是怎么来的（只有两条取值）。
   *
   * - `PER_MODEL`：在这一格上**直接算**出来的（这一格的 token 与某一个模型一一对应，
   *   单价唯一）—— 这是钱唯一被真正计算出来的粒度；
   * - `ALLOCATED_BY_TOKEN_SHARE`：从合计**按 token 占比分摊**来的
   *   （这一格里合法地混着多个模型，单一单价不适用）。
   *
   * <p>为什么必须把它交给界面：两种金额在页面上一模一样，而它们的**含义不同** ——
   * 一个是算出来的，一个是分摊的。不说口径的成本表会被读成"大约就是这么分的"，
   * 而那比"按 token 占比分摊（费用对 token 线性，与原值等价）"弱得多。
   */
  amountBasis?: 'PER_MODEL' | 'ALLOCATED_BY_TOKEN_SHARE'
}

/** 工具维度的一行。**它没有金额**，见后端 `CostQuery.byTool` 的注释。 */
export interface CostToolRow {
  toolName: string
  calls: number
  failures: number
  /** 0~1；分母是"模型提出的调用次数"（含被拒的），与报告口径一致 */
  successRate: number
  unknownTool: number
  validationErrors: number
  parseErrors: number
  rejected: number
  circuitOpen: number
  elapsedMs: number
  runs: number
  amount: null
  amountReason: string
}

/** `GET /api/cost/summary` 的响应 */
export interface CostSummaryResponse {
  ok: boolean
  window: { from: string; to: string; days: number; maxDays: number }
  currency: string
  pricingConfigured: boolean
  /** 「还缺哪些模型的价格」—— 一份能照着做的待办 */
  needsPriceFor: string[]
  priceTable: CostPriceRow[]
  configNotes: string[]
  totals: CostGroup & { allModelsPriced?: boolean; daysWithData?: number }
  days: CostGroup[]
  /**
   * 按**租户**分账（阶段四第 6 件）：谁的钱。
   *
   * 与 {@link byIntent} 是**两个维度、两栏**，不是同一栏的两种叫法：
   * 这一栏回答"谁在花钱"，那一栏回答"花在哪个功能上"。
   */
  byTenant: CostGroup[]
  /** 按**业务归因键**分账：花在哪个功能上。 */
  byIntent: CostGroup[]
  byModel: CostGroup[]
  byTool: CostToolRow[]
  reconciliation: {
    runLevelTokens: number
    stepLevelTokens: number
    gap: number
    runs: number
    runsWithoutSteps: number
    matched: boolean
    note: string
  }
  cache: {
    promptTokens: number
    cachedPromptTokens: number
    hitRate: number
    runsWithCacheHit: number
    /** 整窗命中率都是 0 —— 可能是真没命中，也可能是上游不报这个字段 */
    suspicious: boolean
    note: string
  }
  /** 结论的边界，与数字一起交付 */
  notes: string[]
}

/** 价格表的一行 */
export interface CostPriceRow {
  model: string
  inputPerMillion: string
  cacheHitPerMillion: string
  /** 为真表示"缓存价是假设的"（没配，按未命中价顶）—— 与"真的这么便宜"是两件事 */
  cacheHitIsAssumed: boolean
  outputPerMillion: string
  currency: string
  effectiveFrom: string
  source: string
}

/** `GET /api/cost/prices` 的响应 */
export interface CostPricesResponse {
  ok: boolean
  currency: string
  configured: boolean
  prices: CostPriceRow[]
  notes: string[]
  howToConfigure: string
}

// ---------------------------------------------------------------------------
//  调用函数
// ---------------------------------------------------------------------------

/** 看板快照。`days` 由后端夹到 [1, 90] —— 前端不重复这个上限（第二份判据必然漂移）。 */
export function getCostSummary(days: number): Promise<CostSummaryResponse> {
  return getJson<CostSummaryResponse>(`/api/cost/summary?days=${days}`, '成本看板')
}

/** 价格表与还缺什么。 */
export function getCostPrices(): Promise<CostPricesResponse> {
  return getJson<CostPricesResponse>('/api/cost/prices', '价格表')
}

// ---------------------------------------------------------------------------
//  内部工具（与 api/agent.ts 同一套：复用 diag.ts 的 request 与 DiagError）
// ---------------------------------------------------------------------------

async function getJson<T>(path: string, what: string): Promise<T> {
  const response = await request(path, what, 'GET', COST_READ_TIMEOUT_MS)
  const text = await response.text().catch(() => '')
  try {
    return JSON.parse(text) as T
  }
  catch {
    throw new DiagError(`拿「${what}」时后端返回的不是 JSON：${text.slice(0, 200) || '(空响应体)'}`)
  }
}
