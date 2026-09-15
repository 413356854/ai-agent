/**
 * RAG 诊断端点的调用层（阶段二：文档解析 + 语义分块 + 检索评测）。
 *
 * 与 diag.ts 的关系：那边是"线程模型与 Token 预算"的取证入口（阶段一），
 * 这边是"文档切得怎么样、检索找不找得到"的取证入口（阶段二）。
 * 两者共用同一套错误分类与超时约定 —— 从 diag.ts 复用 `DiagError` 与 `request`，
 * 而不是再抄一份：抄一份的结果必然是两边对"后端没启动"的文案慢慢漂移。
 *
 * 端点清单（与 ai.agent.controller.RagDiagnosticsController 一一对应）：
 *   GET  /api/diag/rag/corpus          语料解析概览 + 块类型分布
 *   GET  /api/diag/rag/preview         某个策略切出来的块长什么样
 *   GET  /api/diag/rag/tokens          一段文本被切成哪些 token
 *   GET  /api/diag/rag/retrieve        单条提问的召回结果
 *   GET  /api/diag/rag/eval-set        评测集条数与分组
 *   GET  /api/diag/rag/compare         ★ 两种分块策略的并排对照
 */
import { DiagError, request } from './diag'

/**
 * 对照评测的超时。
 *
 * **为什么单独给一个 120 秒**：`/compare` 要真跑一遍全量评测 ——
 * 4 份语料分块 + 建索引 + 43 条用例 × 2 个策略检索。
 * 本机实测是**秒级**，但它明显不是"纯本地小计算"那一档，
 * 套诊断端点通用的 20 秒会为一个本来正常的请求留下很窄的余量
 *（机器忙、语料变大、评测集变长都会顶上去）。
 *
 * 这个教训是阶段一用真实代价换来的：钉住实验在后端天然要跑 19~26 秒，
 * 而前端给它套了 20 秒通用超时 —— 于是**一个完全正常的实验必然报超时**，
 * 而假后端瞬时返回、自检全绿，把问题掩盖到了真后端。
 * 一句话：**超时值必须大于被调用者的固有耗时，而固有耗时只能靠实测得知。**
 */
export const RAG_COMPARE_TIMEOUT_MS = 120_000

// ---------------------------------------------------------------------------
//  与后端契约对应的类型。
//
//  唯一事实来源是后端代码，改那边就要同步改这里：
//    - ai.agent.controller.RagDiagnosticsController（各端点的返回体）
//    - ai.agent.rag.eval.RetrievalMetrics / ChunkingComparison（指标结构）
//    - ai.agent.rag.chunk.Chunk（块结构）
// ---------------------------------------------------------------------------

/** GET /api/diag/rag/corpus 里每份文档的一行 */
export interface CorpusDocument {
  documentId: string
  blocks: number
  chars: number
}

/** 解析失败的一份文档（不阻断整批，但必须可见） */
export interface FailedDocument {
  documentId: string
  reason: string
}

/**
 * GET /api/diag/rag/corpus 的响应。
 *
 * `blockTypes` 里 `heading` 为 0 是一个**必须显眼**的信号：
 * 那说明标题层级压根没被识别出来，于是"按标题层级分块"从未发生 ——
 * 而下游的分数下降会被归因成"分块策略不好"。界面要把这件事说出来。
 */
export interface CorpusResponse {
  location: string
  documentCount: number
  blockCount: number
  blockTypes: Record<string, number>
  documents: CorpusDocument[]
  failed: FailedDocument[]
  availableStrategies: string[]
  note: string
}

/** GET /api/diag/rag/preview 里的一个块 */
export interface ChunkPreview {
  id: string
  index: number
  tokens: number
  chars: number
  /** 标题面包屑（结构分块才有内容；硬切恒为空串） */
  breadcrumb: string
  startBlock: number
  endBlock: number
  coveredBlocks: number
  text: string
}

/** GET /api/diag/rag/preview 的响应 */
export interface PreviewResponse {
  strategy: string
  chunkCount: number
  avgTokens: number
  maxTokens: number
  minTokens: number
  previewShown: number
  previews: ChunkPreview[]
}

/** GET /api/diag/rag/tokens 的响应 */
export interface TokensResponse {
  text: string
  tokenCount: number
  tokens: string[]
  note: string
}

/** GET /api/diag/rag/retrieve 里的一条召回 */
export interface RetrieveHit {
  rank: number
  score: number
  chunkId: string
  breadcrumb: string
  tokens: number
  excerpt: string
}

/** GET /api/diag/rag/retrieve 的响应 */
export interface RetrieveResponse {
  query: string
  strategy: string
  indexedChunks: number
  hitCount: number
  hits: RetrieveHit[]
  /** 提问被切成的 token —— 检索不到结果时，第一现场就是这里 */
  queryTokens: string[]
}

/** GET /api/diag/rag/eval-set 里的一条用例 */
export interface EvalCaseItem {
  source: string
  id: string
  question: string
  matchHeadings: string[]
  tag: string
}

/** GET /api/diag/rag/eval-set 的响应 */
export interface EvalSetResponse {
  location: string
  caseCount: number
  byTag: Record<string, number>
  /** 达到"结论可用"门槛的用例数（路线文档要求 30–50 条） */
  minUsableCases: number
  sampleSufficient: boolean
  cases: EvalCaseItem[]
}

/** 单条用例的结果 */
export interface CaseOutcome {
  caseId: string
  tag: string
  question: string
  /** 第一个正确答案的名次；0 表示前 k 条里一个都没有 */
  hitRank: number
  matchedTitles: string[]
  retrievedTitles: string[]
  retrievedTokens: number
}

/** 按分组聚合的指标 */
export interface TagMetrics {
  caseCount: number
  recallAt5: number
  recallAt10: number
  mrr: number
}

/** 一个策略的检索指标 */
export interface RetrievalMetrics {
  strategy: string
  caseCount: number
  chunkCount: number
  avgChunkTokens: number
  /** 名次 k → Recall@k，键是字符串形式的数字（JSON 的键只能是字符串） */
  recallAt: Record<string, number>
  mrr: number
  contextPrecisionAt: Record<string, number>
  /** 前 3 条命中的 token 之和的均值 ≈ 真正进上下文的量 */
  firstThreeHitsTokens: number
  /** 前 k 条候选的 token 之和的均值 = 粗召回的成本 */
  topKTokens: number
  misses: CaseOutcome[]
  perTag: Record<string, TagMetrics>
}

/** 方案自身的规模代价 */
export interface TokenCost {
  totalTokens: number
  duplicationRatio: number
  minTokens: number
  undersizedChunks: number
}

/** 一个策略的完整报告 */
export interface StrategyReport {
  strategy: string
  chunkCount: number
  avgChunkTokens: number
  maxChunkTokens: number
  metrics: RetrievalMetrics
  tokenCost: TokenCost
}

/**
 * GET /api/diag/rag/compare 的响应 —— **阶段二验收口径的载体**。
 *
 * `notes` 必须显示给用户，不能只当成日志：其中有两条会直接改变结论的解释 ——
 * ① 标注解析失败（分数偏低不是检索的问题）；
 * ② 样本量不足（这份结果能用来调参，但不能作为结论引用）。
 * 把它们藏起来，用户只会得出"这个分块策略不行"的错误结论。
 */
export interface CompareResponse {
  evalSetLocation: string
  caseCount: number
  sampleSufficient: boolean
  documents: number
  failedDocuments: string[]
  strategies: StrategyReport[]
  /** 指标名 → 策略名 → 相对对照组的百分点差值 */
  deltas: Record<string, Record<string, number>>
  notes: string[]
}

// ---------------------------------------------------------------------------
//  调用函数
// ---------------------------------------------------------------------------

/** 语料解析概览。**排查第一步**：解析不对，后面全是白费功夫 */
export function getCorpus(): Promise<CorpusResponse> {
  return getJson<CorpusResponse>('/api/diag/rag/corpus', '语料解析概览')
}

/** 看某个策略切出来的块（detail 控制返回几条全文） */
export function getPreview(strategy: string, detail = 3): Promise<PreviewResponse> {
  return getJson<PreviewResponse>(
    `/api/diag/rag/preview?strategy=${encodeURIComponent(strategy)}&detail=${detail}`,
    '分块预览',
  )
}

/** 一段文本被切成哪些 token */
export function getTokens(text: string): Promise<TokensResponse> {
  return getJson<TokensResponse>(`/api/diag/rag/tokens?text=${encodeURIComponent(text)}`, '分词诊断')
}

/** 单条提问的召回结果 */
export function retrieve(query: string, strategy: string, topK = 0): Promise<RetrieveResponse> {
  return getJson<RetrieveResponse>(
    `/api/diag/rag/retrieve?query=${encodeURIComponent(query)}&strategy=${encodeURIComponent(strategy)}&topK=${topK}`,
    '召回结果',
  )
}

/** 评测集条数与分组 */
export function getEvalSet(): Promise<EvalSetResponse> {
  return getJson<EvalSetResponse>('/api/diag/rag/eval-set', '评测集')
}

/** 分块策略对照（走单独的 120 秒超时，理由见 RAG_COMPARE_TIMEOUT_MS） */
export function compareStrategies(topK = 0): Promise<CompareResponse> {
  return getJson<CompareResponse>('/api/diag/rag/compare?topK=' + topK, '分块策略对照', RAG_COMPARE_TIMEOUT_MS)
}

// ---------------------------------------------------------------------------
//  内部工具
// ---------------------------------------------------------------------------

/**
 * 发请求并解析 JSON。
 *
 * 复用 `diag.ts` 的 `request`（它已经把链路层失败翻译成了可照做的中文，
 * 并区分了"超时"与"连不上"）：这样才能保证**同一个后端没启动的提示**
 * 在整个界面上只有一份实现，不会两处慢慢漂移。
 */
async function getJson<T>(path: string, what: string, timeoutMs?: number): Promise<T> {
  const response = await request(path, what, 'GET', timeoutMs)
  const body = await response.text().catch(() => '')
  try {
    return JSON.parse(body) as T
  } catch {
    // 200 但内容不是 JSON（例如被代理/网关换成了首页 HTML）——
    // 此时"解析失败"才是真相，别把 HTML 当数据往下传
    throw new DiagError(`拿「${what}」时后端返回的不是 JSON：${body.slice(0, 200) || '(空响应体)'}`)
  }
}
