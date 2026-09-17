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
 *   GET  /api/diag/rag/align           ★ 块大小对齐实验（消掉混杂因素后的对照）
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

/**
 * 对齐实验的超时。
 *
 * **为什么要比 `/compare` 还宽（180 秒，是它的 1.5 倍）**：这一件事台账里写得很直白 ——
 * 「它比 `/compare` 重约 4 倍」。实际跑的东西是：两次尺寸扫描 + 两次二分收敛（各 11 个探针）
 * + **5 个变体 × 3 路检索 = 15 次全量评测**，而 `/compare` 只是 2 个策略 × 1 路检索。
 *
 * 本机实测 **2.2–3.4 秒**（真启动的后端，HTTP 200 / 124KB 响应），所以 180 秒不是"刚好够"，
 * 是**留足了机器忙 / 语料变大 / 评测集变长时的余量**。
 *
 * 这里沿用 `RAG_COMPARE_TIMEOUT_MS` 上面那段教训，但把结论再推一步：
 * **同一个面板里的不同端点，超时值必须各自按自己的固有耗时定**。
 * `/compare` 的 120 秒是照着"2 个策略"定的，直接拿来给"4 倍工作量"的 `/align` 用，
 * 就是在重复阶段一那个坑（钉住实验 19~26 秒 vs 前端 20 秒超时）——
 * 只不过这次是我们自己主动埋的。
 */
export const RAG_ALIGN_TIMEOUT_MS = 180_000

// ---------------------------------------------------------------------------
//  与后端契约对应的类型。
//
//  唯一事实来源是后端代码，改那边就要同步改这里：
//    - ai.agent.controller.RagDiagnosticsController（各端点的返回体）
//    - ai.agent.entity.rag.eval.RetrievalMetrics / ChunkingComparison（指标结构）
//    - ai.agent.entity.rag.chunk.Chunk（块结构）
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

/**
 * 等 token 预算那一档里，某一组的指标。
 *
 * `avgTokensUsed` 与 `avgHitCount` 必须**一起**看 —— 这是后端注释里的原话：
 * 预算没装满就是「块太大装不下」的直接证据。只看 Recall 会把"它根本没花到那么多钱"
 * 误读成"它更省"。
 */
export interface BudgetAlignment {
  budget: number
  recall: number
  mrr: number
  /** 这次检索真正塞进 prompt 的 token（= 台账里"检索预算入账"要用的口径） */
  avgTokensUsed: number
  avgHitCount: number
}

/**
 * 尺寸扫描表的一行：某个字符数下切出来的规模。
 *
 * `gapToTarget` 是这一行与对齐目标的差 —— 扫描表的意义就是**让"对齐是怎么收敛的"可重放**，
 * 只报最后那个收敛点，读者没法判断收敛过程是不是在乱撞。
 */
export interface SweepRow {
  fixedSize: number
  chunkCount: number
  avgChunkTokens: number
  totalTokens: number
  duplicationRatio: number
  gapToTarget: number
}

/**
 * 一组对齐对照。
 *
 * ⚠️ **`reports` 恒为两项，且第 0 项是参照方、第 1 项是被考察方**，
 * 差值一律"第 1 项 − 第 0 项"。方向是后端定死的（它自己的注释里写了为什么：
 * "在一张有 6 个数字的对照表里，搞错方向会让读者得出完全相反的结论"）。
 * 界面这边**不要**再去猜方向，直接按 `[0]` / `[1]` 取。
 */
export interface PairComparison {
  /** 这一组对齐的是哪个字段 —— 后端已经把「左=…｜右=…」也拼进来了 */
  fieldDescription: string
  /** 对齐轴：平均块大小 / 等 token 预算 */
  dimension: string
  reports: StrategyReport[]
  /** 指标名 → 被考察方策略名 → 差值（百分点） */
  deltas: Record<string, Record<string, number>>
  /** 这一组自己的注意事项（哪一组能说什么、不能说什么 —— 必须显示） */
  notes: string[]
}

/**
 * GET /api/diag/rag/align 的响应 —— **把 `/compare` 里那个混杂因素消掉之后的对照**。
 *
 * `/compare` 的结论只能说"结构分块省 token"，不能说"哪个策略更好"：
 * 两组平均块大小不同（234.8 vs 200.9），而判定标准是"块有没有覆盖答案所在章节"，
 * **块越大越容易覆盖答案**。这个报告就是来消掉它的。
 *
 * `notes` 里装着**不能下的结论**（尤其是"向量那一路是离线哈希近似品"那条），
 * 必须显示出来 —— 界面上摆着 vector-local 的 Recall，不解释它是什么，
 * 读者一定会拿去和 BM25 比。
 */
export interface AlignmentResponse {
  evalSetLocation: string
  caseCount: number
  sampleSufficient: boolean
  documents: number
  failedDocuments: string[]
  /** 对齐轴（后端给的是「平均块大小」） */
  dimension: string
  /** 对齐目标：结构分块的平均块 token，也就是"靶子" */
  alignTargetTokens: number
  /** 扫描区间的字符数范围（含两端） */
  sweepRange: number[]
  sweep: SweepRow[]
  /** 收敛得到的对照组分块字符数 */
  alignedFixedSize: number
  /** 用该尺寸实测到的平均块 token */
  alignedMeasuredAvg: number
  /** 与对齐目标的差（token）—— "对齐"永远是近似的，差多少要看得到 */
  alignedGapTokens: number
  /** 二分细化实际跑了几次（收敛过程的可复现性） */
  refinementProbes: number
  /** 各组对齐对照 —— 7 组：3 路检索 × 2 个对齐组 + 1 组"块更大的效应" */
  pairs: PairComparison[]
  budgetSteps: number[]
  /** 各对齐组的可读名，与 budgetRows 里的键一一对应 */
  budgetLabel: string[]
  /** 预算档位 → 组名 → 该档位下的指标 */
  budgetRows: Record<string, Record<string, BudgetAlignment>>
  /** 对照里跑过的检索方式（词法 / 向量 / 混合） */
  retrievalMethods: string[]
  /** 向量那一路的 Embedding 实现名。带 `local-` 前缀说明它是离线近似品，不是真语义嵌入 */
  embeddingModel: string
  embeddingDimensions: number
  /** 索引指纹（模型/维度），用于校验 Embedding 漂移 */
  vectorFingerprint: string
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

/**
 * 块大小对齐实验（走单独的 **180 秒**超时，理由见 RAG_ALIGN_TIMEOUT_MS）。
 *
 * `budgets` 一律不传：档位由后端的 `DEFAULT_BUDGETS` 定（1000/2000/3000/4000/6000/8000），
 * 界面**不做"选档位"的控件**。理由：档位是实验设计的一部分而不是用户的偏好，
 * 让界面能改它，就等于让用户悄悄地把两组对照比在不同档位上 ——
 * 而"同一个预算里谁把答案找回来"是这张表唯一的说服力。
 */
export function getAlignment(): Promise<AlignmentResponse> {
  return getJson<AlignmentResponse>('/api/diag/rag/align', '块大小对齐实验', RAG_ALIGN_TIMEOUT_MS)
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
