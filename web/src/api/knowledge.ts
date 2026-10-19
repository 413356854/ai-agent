/**
 * 知识库（阶段二 · 3.6 / 3.7 / 3.8）的 HTTP 契约。
 *
 * 后端控制器：`KnowledgeBaseController`（`/api/knowledge-bases`）、
 * `KnowledgeDocumentController`（`/api/knowledge-bases/{kbId}/documents`）。
 *
 * 【两条与页面行为绑定的后端事实】
 *   ① 建库 / 传文档 / 重新索引都是**异步**的：接口返回时文档还是「已上传」，
 *      真正的索引在后台线程上跑。所以页面上那些状态值必须在"有人正在等它"时刷新 ——
 *      见 `useKnowledgeBase` 的轮询条件（只在这个库有文档处于未完成态时拉）。
 *   ② 文档的「没有块」有三种，后端分别给了 `neverIndexed` / `indexedWithNoChunk` / `status=FAILED`：
 *      界面必须给三种说法（"还没轮到" / "索引了但一块都没切出来" / "失败 + 原因"）。
 *      只看 `chunkCount` 的话前两种长得一样，而处置方式相反。
 *
 * 【错误】写接口失败时后端回 `{ok:false,error}`（400/409/413）或
 * `{status,error,message}`（`ErrorResponseAdvice` 统一形状）。两种都要能读出那句人话，
 * 并按状态码区分"你填的东西不对"与"链路出问题了"。
 */

/** 建库入参。embedding 的四个字段留空时用后端的 `ai.rag.kb.embedding.*` 当前值。 */
export interface ICreateKnowledgeBase {
  name: string
  description?: string
  chunkStrategy: 'length' | 'structural'
  embeddingProvider?: string
  embeddingModel?: string
  embeddingDim?: number
  topK?: number
  similarityThreshold?: number
  rerankEnabled?: boolean
}

export interface IKnowledgeBase {
  id: number
  name: string
  description: string | null
  embeddingProvider: string
  embeddingModel: string
  embeddingDim: number
  collectionName: string
  chunkStrategy: string
  chunkStrategyLabel: string
  topK: number
  similarityThreshold: number
  rerankEnabled: boolean
  enabled: boolean
  documentCount: number
  chunkCount: number
  createdAt: string | null
  updatedAt: string | null
}

/** 当前这套知识库能用哪些能力：false 时界面要给出**原因**，而不是只显示一个红点。 */
export interface IKbCapabilities {
  embeddingAvailable: boolean
  embeddingReason: string | null
  embeddingProvider: string
  embeddingModel: string
  embeddingDimensions: number
  milvusReachable: boolean
  /** 重排能不能用；false 时界面要给原因（模型没开通 / 没配 key） */
  rerankAvailable: boolean
  rerankReason: string | null
  rerankModel: string
}

export type KbDocStatus = 'UPLOADED' | 'PROCESSING' | 'PROCESSED' | 'FAILED'

export interface IKbDocument {
  id: number
  kbId: number
  name: string
  format: string
  sizeBytes: number
  status: KbDocStatus
  statusLabel: string
  enabled: boolean
  chunkCount: number
  tokenCount: number
  error: string | null
  indexedAt: string | null
  createdAt: string | null
  updatedAt: string | null
  /** 从没索引成功过（与"索引了 0 块"不同）。 */
  neverIndexed: boolean
  /** 索引成功但一块都没切出来。 */
  indexedWithNoChunk: boolean
}

export interface IKbChunk {
  /** 预览里的块还没有 uid（没写库、没算向量），后端给 null。 */
  uid: string | null
  documentId: number
  seq: number
  text: string
  headingPath: string | null
  tokenCount: number
  charCount: number
  enabled?: boolean
}

export interface IKbHit {
  rank: number
  kbId: number
  kbName: string
  documentId: number
  documentName: string
  chunkUid: string
  seq: number
  headingPath: string | null
  tokenCount: number
  vectorScore: number
  rerankScore: number | null
  text: string
}

export interface IKbRetrieveResult {
  query: string
  hits: IKbHit[]
  hitCount: number
  /** 参与检索的切片总数（"0 条命中"的分母）。 */
  indexSize: number
  candidateCount: number
  reranked: boolean
  /** 没重排的原因；重排了则为 null。 */
  rerankSkipReason: string | null
  notes: string[]
  elapsedMs: number
}

export interface IKbConsistency {
  collectionName: string
  /** -1 表示集合不存在。 */
  vectorRows: number
  manifestRows: number
  sumOfDocumentCounts: number
  documents: Array<{
    documentId: number
    name: string
    status: KbDocStatus
    chunkCountOnRow: number
    chunkRowsInTable: number
    rowMatchesTable: boolean
  }>
  note: string
}

/** 写接口被业务拒绝（400/409/413）时带状态码抛出来：界面据此决定说"改内容"还是"改名字"。 */
export class KbRejectedError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'KbRejectedError'
    this.status = status
  }
}

const BASE = '/api/knowledge-bases'

async function parse<T>(response: Response): Promise<T> {
  const text = await response.text()
  if (!response.ok) {
    let message = `HTTP ${response.status}`
    if (text) {
      try {
        const body = JSON.parse(text) as { error?: string; message?: string }
        // 后端的两种错误形状：控制器手写的 {ok,error} 与 ErrorResponseAdvice 的 {status,error,message}
        message = body.message || body.error || message
      } catch {
        message = text.slice(0, 300)
      }
    }
    throw new KbRejectedError(message, response.status)
  }
  return (text ? JSON.parse(text) : {}) as T
}

async function send<T>(method: string, path: string, payload?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (payload !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(payload)
  }
  return parse<T>(await fetch(`${BASE}${path}`, init))
}

/**
 * 传文件。
 *
 * ⚠️ **不要自己设 Content-Type**：multipart 的 boundary 由浏览器生成，
 * 手写一个 `multipart/form-data`（没有 boundary）会让后端解析出空表单，
 * 而现象是"后端说没收到文件"——看起来像后端的锅。
 * 一次一个文件：多个文件时由调用方发多次请求，每份各自回执（一个超限就整批失败的话，
 * "哪一个不行"要靠猜）。
 */
async function sendFile<T>(path: string, file: File): Promise<T> {
  const form = new FormData()
  form.append('file', file, file.name)
  return parse<T>(await fetch(`${BASE}${path}`, { method: 'POST', body: form }))
}

export const knowledgeApi = {
  list: () => send<{ knowledgeBases: IKnowledgeBase[]; total: number; capabilities: IKbCapabilities }>('GET', ''),

  detail: (kbId: number) =>
    send<{ knowledgeBase: IKnowledgeBase; capabilities: IKbCapabilities }>('GET', `/${kbId}`),

  create: (payload: ICreateKnowledgeBase) =>
    send<{ ok: true; knowledgeBase: IKnowledgeBase; note: string }>('POST', '', payload),

  update: (kbId: number, payload: Partial<ICreateKnowledgeBase>) =>
    send<{ ok: true; knowledgeBase: IKnowledgeBase }>('PUT', `/${kbId}`, payload),

  setEnabled: (kbId: number, enabled: boolean) =>
    send<{ ok: true; knowledgeBase: IKnowledgeBase; note: string }>('POST', `/${kbId}/enabled`, { enabled }),

  remove: (kbId: number) =>
    send<{ ok: true; knowledgeBaseId: number; note: string }>('DELETE', `/${kbId}`),

  consistency: (kbId: number) => send<IKbConsistency>('GET', `/${kbId}/consistency`),

  retrieve: (kbIds: number[], query: string, topK?: number, rerank?: boolean) =>
    send<IKbRetrieveResult>('POST', '/retrieve', { kbIds, query, topK, rerank }),

  documents: (kbId: number) =>
    send<{ documents: IKbDocument[]; total: number; counts: Array<{ status: string; label: string; total: number }> }>(
      'GET',
      `/${kbId}/documents`,
    ),

  upload: (kbId: number, file: File) =>
    sendFile<{ ok: true; document: IKbDocument; note: string }>(`/${kbId}/documents`, file),

  renameDocument: (kbId: number, docId: number, name: string) =>
    send<{ ok: true; document: IKbDocument }>('PUT', `/${kbId}/documents/${docId}`, { name }),

  setDocumentEnabled: (kbId: number, docId: number, enabled: boolean) =>
    send<{ ok: true; document: IKbDocument; note: string }>('POST', `/${kbId}/documents/${docId}/enabled`, {
      enabled,
    }),

  removeDocument: (kbId: number, docId: number) =>
    send<{ ok: true; note: string }>('DELETE', `/${kbId}/documents/${docId}`),

  reIndex: (kbId: number, docId: number) =>
    send<{ ok: true; document: IKbDocument; note: string }>('POST', `/${kbId}/documents/${docId}/re-index`),

  previewChunks: (kbId: number, docId: number) =>
    send<{ chunks: IKbChunk[]; total: number; totalTokens: number; note: string }>(
      'POST',
      `/${kbId}/documents/${docId}/chunks/preview`,
    ),

  chunks: (kbId: number, docId: number, offset: number, limit: number) =>
    send<{ chunks: IKbChunk[]; total: number; offset: number; limit: number }>(
      'GET',
      `/${kbId}/documents/${docId}/chunks?offset=${offset}&limit=${limit}`,
    ),

  updateChunk: (kbId: number, docId: number, uid: string, text: string) =>
    send<{ ok: true; chunk: IKbChunk; note: string }>(
      'PUT',
      `/${kbId}/documents/${docId}/chunks/${uid}`,
      { text },
    ),

  setChunkEnabled: (kbId: number, docId: number, uid: string, enabled: boolean) =>
    send<{ ok: true; chunk: IKbChunk }>('POST', `/${kbId}/documents/${docId}/chunks/${uid}/enabled`, { enabled }),

  removeChunk: (kbId: number, docId: number, uid: string) =>
    send<{ ok: true; note: string }>('DELETE', `/${kbId}/documents/${docId}/chunks/${uid}`),
}
