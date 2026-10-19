/**
 * 知识库（第十四区）的假后端。
 *
 * 【假数据的判据照搬真后端，而不是"随便返回个成功"】
 * 这里刻意把**四个分支**都摆出来，因为它们在真后端上是四种不同的处置：
 *   ① 一份 PROCESSING（正在索引）；
 *   ② 一份 FAILED（带原因 —— 向量化那一步失败的那个真实报文）；
 *   ③ 一份 PROCESSED 但 chunkCount=0（"已索引，但一块都没切出来"）——
 *      它与①在只看块数时长得一样，而处置方式相反；
 *   ④ 一次重排不可用的检索（rerankSkipReason 非空）。
 * 形状（字段名/嵌套/类型）与真后端逐字对齐：形状不一致的假数据比没有假数据更坏 ——
 * 缺陷会被伪装成"这个功能没渲染"。
 *
 * 状态只在内存里，重启即回到初始。
 */

const INITIAL_KBS = [
  {
    id: 1,
    name: '产品手册',
    description: '安装、配置与常见问题',
    embeddingProvider: 'dashscope',
    embeddingModel: 'text-embedding-v4',
    embeddingDim: 1024,
    collectionName: 'kb_1',
    chunkStrategy: 'structural',
    chunkStrategyLabel: '按标题结构切分',
    topK: 5,
    similarityThreshold: 0.4,
    rerankEnabled: true,
    enabled: true,
    documentCount: 3,
    chunkCount: 128,
    createdAt: '2026-10-17T02:00:00Z',
    updatedAt: '2026-10-17T02:10:00Z',
  },
  {
    id: 2,
    name: '运维手册（已停用）',
    description: null,
    embeddingProvider: 'dashscope',
    embeddingModel: 'text-embedding-v4',
    embeddingDim: 1024,
    collectionName: 'kb_2',
    chunkStrategy: 'length',
    chunkStrategyLabel: '定长切分',
    topK: 3,
    similarityThreshold: 0.5,
    rerankEnabled: false,
    enabled: false,
    documentCount: 0,
    chunkCount: 0,
    createdAt: '2026-10-16T09:00:00Z',
    updatedAt: '2026-10-16T09:00:00Z',
  },
]

const INITIAL_DOCS = {
  1: [
    {
      id: 1,
      kbId: 1,
      name: '安装指南.md',
      format: 'md',
      sizeBytes: 24017,
      status: 'PROCESSED',
      statusLabel: '已索引',
      enabled: true,
      chunkCount: 30,
      tokenCount: 5225,
      error: null,
      indexedAt: '2026-10-17T02:05:00Z',
      createdAt: '2026-10-17T02:04:00Z',
      updatedAt: '2026-10-17T02:05:00Z',
      neverIndexed: false,
      indexedWithNoChunk: false,
    },
    {
      id: 2,
      kbId: 1,
      name: '扫描件-离线部署.pdf',
      format: 'pdf',
      sizeBytes: 1_204_000,
      status: 'PROCESSING',
      statusLabel: '索引中',
      enabled: true,
      chunkCount: 0,
      tokenCount: 0,
      error: null,
      indexedAt: null,
      createdAt: '2026-10-17T02:12:00Z',
      updatedAt: '2026-10-17T02:12:00Z',
      neverIndexed: true,
      indexedWithNoChunk: false,
    },
    {
      id: 3,
      kbId: 1,
      name: '旧版接口文档.docx',
      format: 'docx',
      sizeBytes: 88_120,
      status: 'FAILED',
      statusLabel: '索引失败',
      enabled: true,
      chunkCount: 0,
      tokenCount: 0,
      error: '第「向量化」步失败：400 InvalidParameter: batch size is invalid, it should not be larger than 10.',
      indexedAt: null,
      createdAt: '2026-10-17T02:08:00Z',
      updatedAt: '2026-10-17T02:08:20Z',
      neverIndexed: true,
      indexedWithNoChunk: false,
    },
    {
      id: 4,
      kbId: 1,
      name: '空白模板.txt',
      format: 'txt',
      sizeBytes: 12,
      status: 'PROCESSED',
      statusLabel: '已索引',
      enabled: true,
      chunkCount: 0,
      tokenCount: 0,
      error: null,
      indexedAt: '2026-10-17T02:09:00Z',
      createdAt: '2026-10-17T02:09:00Z',
      updatedAt: '2026-10-17T02:09:00Z',
      neverIndexed: false,
      indexedWithNoChunk: true,
    },
  ],
  2: [],
}

const CHUNKS = {
  1: [
    chunk(0, '开篇必读：版本基线', 'Spring AI 稳定版：2.0.1（2026-08-20 发布），GA 基线 2.0.0；要求 Java 21、Spring Boot 4.0.x 或 4.1.x。', 198),
    chunk(1, '开篇必读：版本基线 > 官方文档入口', '选对版本文档：Spring AI 的文档按版本分目录，1.0 与 2.0 的 API 差别很大。', 152),
    chunk(2, '最容易踩的坑', 'Spring AI 2.x 与 Spring Boot 3.x 不兼容 —— 底层架构重构，不可混用。', 121),
  ],
}

function chunk(seq, headingPath, text, tokenCount) {
  return {
    uid: `11111111-2222-3333-4444-${String(seq).padStart(12, '0')}`,
    documentId: 1,
    seq,
    text,
    headingPath,
    tokenCount,
    charCount: text.length,
    enabled: true,
  }
}

let kbs = structuredClone(INITIAL_KBS)
let docs = structuredClone(INITIAL_DOCS)
let chunks = structuredClone(CHUNKS)
let nextKbId = 3
let nextDocId = 5
/** 重排在这个假后端里"配上了但用不了"，用来把 rerankSkipReason 那条分支摆出来。 */
let rerankAvailable = false

function view(capabilities) {
  return {
    knowledgeBases: kbs,
    total: kbs.length,
    capabilities,
    howToWrite:
      'POST /api/knowledge-bases（建库）；改 PUT /{id}；启停 POST /{id}/enabled；删 DELETE /{id}；' +
      '传文档 POST /{id}/documents；文档与切片在 /api/knowledge-bases/{id}/documents',
  }
}

function capabilities() {
  return {
    embeddingAvailable: true,
    embeddingReason: null,
    embeddingProvider: 'dashscope',
    embeddingModel: 'text-embedding-v4',
    embeddingDimensions: 1024,
    milvusReachable: true,
    rerankAvailable,
    rerankReason: rerankAvailable ? null : 'ai.rag.kb.rerank.model 没开通（假后端固定这一条，用来把「没重排」的分支摆出来）',
    rerankModel: 'gte-rerank-v2',
  }
}

function readBody(req, res, json, handle) {
  let raw = ''
  req.on('data', (b) => {
    raw += b
  })
  req.on('end', () => {
    let parsed = {}
    if (raw) {
      try {
        parsed = JSON.parse(raw)
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: '请求体不是合法 JSON' }))
        return
      }
    }
    handle(parsed)
  })
}

export function handleKnowledgeRoutes(req, res, url, json) {
  const path = url.pathname
  if (!path.startsWith('/api/knowledge-bases')) return false

  // GET /api/knowledge-bases
  if (path === '/api/knowledge-bases' && req.method === 'GET') {
    json(res, view(capabilities()))
    return true
  }

  // POST /api/knowledge-bases
  if (path === '/api/knowledge-bases' && req.method === 'POST') {
    readBody(req, res, json, (body) => {
      if (!body.name || !String(body.name).trim()) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: '缺少 name（知识库名称）' }))
        return
      }
      if (kbs.some((k) => k.name === body.name)) {
        // 409 与 400 分开：一个要改名字，一个要改内容
        res.writeHead(409, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: `知识库名称已被占用：${body.name}` }))
        return
      }
      if (body.chunkStrategy && !['length', 'structural'].includes(body.chunkStrategy)) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: '不认识的分块策略，可用取值：length=定长切分、structural=按标题结构切分' }))
        return
      }
      const kb = {
        id: nextKbId,
        name: body.name,
        description: body.description ?? null,
        embeddingProvider: 'dashscope',
        embeddingModel: 'text-embedding-v4',
        embeddingDim: 1024,
        collectionName: `kb_${nextKbId}`,
        chunkStrategy: body.chunkStrategy ?? 'structural',
        chunkStrategyLabel: body.chunkStrategy === 'length' ? '定长切分' : '按标题结构切分',
        topK: 5,
        similarityThreshold: 0.4,
        rerankEnabled: false,
        enabled: true,
        documentCount: 0,
        chunkCount: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      nextKbId += 1
      kbs = [...kbs, kb]
      docs[kb.id] = []
      json(res, { ok: true, knowledgeBase: kb, note: 'Milvus 集合已建好，可以传文档了' })
    })
    return true
  }

  // POST /api/knowledge-bases/retrieve
  if (path === '/api/knowledge-bases/retrieve' && req.method === 'POST') {
    readBody(req, res, json, (body) => {
      if (!body.query || !String(body.query).trim()) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: '缺少 query（要检索的那句话）' }))
        return
      }
      const wantRerank = body.rerank === true
      const hits = [
        hit(1, '开篇必读：版本基线 > Spring AI 稳定版：2.0.1', 0.7634, wantRerank ? null : null),
        hit(2, '开篇必读：版本基线 > 官方文档入口', 0.7364, null),
        hit(3, '最容易踩的坑', 0.5869, null),
      ]
      json(res, {
        query: body.query,
        hits: wantRerank ? hits : hits,
        hitCount: hits.length,
        indexSize: 30,
        candidateCount: 20,
        reranked: false,
        rerankSkipReason: wantRerank
          ? '重排调用失败：403 AccessDenied（假后端固定这一条）'
          : '重排未启用（ai.rag.kb.rerank.enabled=false）',
        notes: ['知识库 #1：粗召回里有 2 条切片已停用，被丢掉'],
        elapsedMs: 379,
      })
    })
    return true
  }

  const kbMatch = path.match(/^\/api\/knowledge-bases\/(\d+)(\/.*)?$/)
  if (kbMatch) {
    const kbId = Number(kbMatch[1])
    const rest = kbMatch[2] ?? ''
    const kb = kbs.find((k) => k.id === kbId)
    if (!kb) {
      res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
      res.end(JSON.stringify({ status: 400, error: 'Bad Request', message: `知识库不存在：#${kbId}` }))
      return true
    }

    if (rest === '' && req.method === 'GET') {
      json(res, { knowledgeBase: kb, capabilities: capabilities() })
      return true
    }

    if (rest === '' && req.method === 'DELETE') {
      kbs = kbs.filter((k) => k.id !== kbId)
      delete docs[kbId]
      json(res, {
        ok: true,
        knowledgeBaseId: kbId,
        deleted: kb,
        note: `Milvus 集合 ${kb.collectionName}、切片与文档记录都已删除。上传的原文件仍留在磁盘上（ai.rag.kb.storage-path 下），要一并清理请手工删目录`,
      })
      return true
    }

    if (rest === '/enabled' && req.method === 'POST') {
      readBody(req, res, json, (body) => {
        kb.enabled = body.enabled === true
        json(res, {
          ok: true,
          knowledgeBase: kb,
          note: kb.enabled ? '已启用，会参与检索' : '已停用：不参与检索，文档与切片都还在',
        })
      })
      return true
    }

    if (rest === '/consistency' && req.method === 'GET') {
      const list = docs[kbId] ?? []
      json(res, {
        collectionName: kb.collectionName,
        vectorRows: list.reduce((sum, d) => sum + d.chunkCount, 0),
        manifestRows: list.reduce((sum, d) => sum + d.chunkCount, 0),
        sumOfDocumentCounts: list.reduce((sum, d) => sum + d.chunkCount, 0),
        documents: list.map((d) => ({
          documentId: d.id,
          name: d.name,
          status: d.status,
          chunkCountOnRow: d.chunkCount,
          chunkRowsInTable: d.chunkCount,
          rowMatchesTable: true,
        })),
        note: 'vectorRows 为 -1 表示集合不存在；两份数字不等时，对相关文档点「重新索引」即可把它们对齐',
      })
      return true
    }

    if (rest === '/documents' && req.method === 'GET') {
      const list = docs[kbId] ?? []
      json(res, {
        documents: list,
        total: list.length,
        counts: ['UPLOADED', 'PROCESSING', 'PROCESSED', 'FAILED'].map((status) => ({
          status,
          label: { UPLOADED: '已上传', PROCESSING: '索引中', PROCESSED: '已索引', FAILED: '索引失败' }[status],
          total: list.filter((d) => d.status === status).length,
        })),
        howToWrite:
          '传文档 POST 这个地址（multipart，字段名 file，一次一个）；重新索引 POST /{docId}/re-index；启停 POST /{docId}/enabled；删 DELETE /{docId}；切片 POST /{docId}/chunks/preview 预览、GET /{docId}/chunks 列表',
      })
      return true
    }

    // 传文档：假后端不解析 multipart，只回一份"已上传"的文档
    if (rest === '/documents' && req.method === 'POST') {
      const doc = {
        id: nextDocId,
        kbId,
        name: '新上传的文档.md',
        format: 'md',
        sizeBytes: 4096,
        status: 'UPLOADED',
        statusLabel: '已上传',
        enabled: true,
        chunkCount: 0,
        tokenCount: 0,
        error: null,
        indexedAt: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        neverIndexed: true,
        indexedWithNoChunk: false,
      }
      nextDocId += 1
      docs[kbId] = [...(docs[kbId] ?? []), doc]
      json(res, { ok: true, document: doc, note: '已入队，状态会在处理完成后变成「已索引」（列表页刷新可见）' })
      return true
    }

    const docMatch = rest.match(/^\/documents\/(\d+)(\/.*)?$/)
    if (docMatch) {
      const docId = Number(docMatch[1])
      const docRest = docMatch[2] ?? ''
      const doc = (docs[kbId] ?? []).find((d) => d.id === docId)
      if (!doc) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: `文档不存在：#${docId}` }))
        return true
      }

      if (docRest === '/re-index' && req.method === 'POST') {
        doc.status = 'UPLOADED'
        doc.statusLabel = '已上传'
        doc.neverIndexed = true
        json(res, { ok: true, document: doc, note: '旧的切片与向量已清掉，重新入队；跑完之后切片 id 会换一批' })
        return true
      }

      if (docRest === '/enabled' && req.method === 'POST') {
        readBody(req, res, json, (body) => {
          doc.enabled = body.enabled === true
          json(res, {
            ok: true,
            document: doc,
            note: doc.enabled ? '已启用：这份文档的切片会重新参与检索' : '已停用：这份文档的所有切片都不再被召回（切片本身没删）',
          })
        })
        return true
      }

      if (docRest === '' && req.method === 'DELETE') {
        docs[kbId] = (docs[kbId] ?? []).filter((d) => d.id !== docId)
        json(res, { ok: true, deleted: doc, note: '向量、切片、文档记录与上传的原件都已删除' })
        return true
      }

      if (docRest === '/chunks/preview' && req.method === 'POST') {
        json(res, {
          chunks: (chunks[docId] ?? []).map((c) => ({ ...c, uid: null })),
          total: (chunks[docId] ?? []).length,
          totalTokens: (chunks[docId] ?? []).reduce((sum, c) => sum + c.tokenCount, 0),
          note: '预览不写库、不算向量；保存走「重新索引」',
        })
        return true
      }

      if (docRest === '/chunks' && req.method === 'GET') {
        const list = chunks[docId] ?? []
        const offset = Number(url.searchParams.get('offset') ?? 0)
        const limit = Number(url.searchParams.get('limit') ?? 20)
        json(res, { chunks: list.slice(offset, offset + limit), total: list.length, offset, limit })
        return true
      }

      const chunkMatch = docRest.match(/^\/chunks\/([^/]+)(\/enabled)?$/)
      if (chunkMatch) {
        const uid = decodeURIComponent(chunkMatch[1])
        const target = (chunks[docId] ?? []).find((c) => c.uid === uid)
        if (!target) {
          res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
          res.end(JSON.stringify({ ok: false, error: `切片不存在：${uid}` }))
          return true
        }
        if (chunkMatch[2] === '/enabled' && req.method === 'POST') {
          readBody(req, res, json, (body) => {
            target.enabled = body.enabled === true
            json(res, { ok: true, chunk: target })
          })
          return true
        }
        if (req.method === 'PUT') {
          readBody(req, res, json, (body) => {
            target.text = body.text ?? target.text
            target.charCount = target.text.length
            json(res, { ok: true, chunk: target, note: '已重新向量化' })
          })
          return true
        }
        if (req.method === 'DELETE') {
          chunks[docId] = (chunks[docId] ?? []).filter((c) => c.uid !== uid)
          json(res, { ok: true, deletedChunkUid: uid, note: '这一片已从向量库与清单里删掉；文档的块数已减一' })
          return true
        }
      }
    }
  }

  return false
}

function hit(rank, headingPath, vectorScore, rerankScore) {
  return {
    rank,
    kbId: 1,
    kbName: '产品手册',
    documentId: 1,
    documentName: '安装指南.md',
    chunkUid: `11111111-2222-3333-4444-${String(rank - 1).padStart(12, '0')}`,
    seq: rank - 1,
    headingPath,
    tokenCount: 198,
    vectorScore,
    rerankScore,
    text: 'Spring AI 稳定版：2.0.1（2026-08-20 发布），GA 基线 2.0.0；要求 Java 21、Spring Boot 4.0.x 或 4.1.x。',
  }
}
