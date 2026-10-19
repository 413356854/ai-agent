import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ICreateKnowledgeBase,
  IKbCapabilities,
  IKbChunk,
  IKbDocument,
  IKnowledgeBase,
  KbRejectedError,
  knowledgeApi,
} from '../api/knowledge'
import { Remote } from '../types'

/** 写操作的即时回执：成功显示后端那句话，失败原样显示后端那句话或链路错误。 */
export type KbFeedback = { status: 'idle' } | { status: 'ok'; message: string } | { status: 'error'; error: string }

interface KbList {
  knowledgeBases: IKnowledgeBase[]
  total: number
  capabilities: IKbCapabilities
}

interface DocList {
  documents: IKbDocument[]
  total: number
  counts: Array<{ status: string; label: string; total: number }>
}

interface ChunkPage {
  chunks: IKbChunk[]
  total: number
  offset: number
  limit: number
}

/**
 * 索引中的轮询间隔与上限。
 *
 * ★ 为什么这一页**要**轮询，而别的块不轮询：索引是后台跑的，用户刚传完文件就盯着这一页等它变成
 * 「已索引」—— 不拉就看不到，而这正是"不拉就会出事"的那种（同 HITL 待办列表那条判据）。
 * ⚠️ 上界是必需的：一份卡住不动的文档会让页面一直拉下去，所以 5 分钟后停下来，
 * 并把手动「刷新」留在页面上兜底。
 */
const POLL_MS = 2_500
const POLL_MAX_MS = 5 * 60 * 1000

export interface UseKnowledgeBaseResult {
  kbs: Remote<KbList>
  docs: Remote<DocList>
  chunks: Remote<ChunkPage>
  selectedKbId: number | null
  selectedDocId: number | null
  feedback: KbFeedback
  busy: boolean
  polling: boolean
  selectKb: (id: number | null) => void
  selectDoc: (id: number | null) => void
  clearFeedback: () => void
  reload: () => Promise<void>
  reloadDocs: () => Promise<void>
  loadChunks: (offset: number, limit: number) => Promise<void>
  create: (payload: ICreateKnowledgeBase) => Promise<boolean>
  setEnabled: (kbId: number, enabled: boolean) => Promise<boolean>
  remove: (kbId: number) => Promise<boolean>
  upload: (kbId: number, file: File) => Promise<boolean>
  reIndex: (kbId: number, docId: number) => Promise<boolean>
  setDocumentEnabled: (kbId: number, docId: number, enabled: boolean) => Promise<boolean>
  removeDocument: (kbId: number, docId: number) => Promise<boolean>
  updateChunk: (kbId: number, docId: number, uid: string, text: string) => Promise<boolean>
  setChunkEnabled: (kbId: number, docId: number, uid: string, enabled: boolean) => Promise<boolean>
  removeChunk: (kbId: number, docId: number, uid: string) => Promise<boolean>
}

function describe(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export function useKnowledgeBase(active: boolean): UseKnowledgeBaseResult {
  const [kbs, setKbs] = useState<Remote<KbList>>({ status: 'idle' })
  const [docs, setDocs] = useState<Remote<DocList>>({ status: 'idle' })
  const [chunks, setChunks] = useState<Remote<ChunkPage>>({ status: 'idle' })
  const [selectedKbId, setSelectedKbId] = useState<number | null>(null)
  const [selectedDocId, setSelectedDocId] = useState<number | null>(null)
  const [feedback, setFeedback] = useState<KbFeedback>({ status: 'idle' })
  const [busy, setBusy] = useState(false)
  const [polling, setPolling] = useState(false)

  const initialized = useRef(false)
  // 防竞态：只有最后一次请求的结果被接受（与 useSchedules 的 previewSeq 同一种做法）
  const docsSeq = useRef(0)
  const pollStartedAt = useRef(0)

  const loadKbs = useCallback(async () => {
    setKbs({ status: 'loading' })
    try {
      setKbs({ status: 'ok', data: await knowledgeApi.list() })
    } catch (e) {
      setKbs({ status: 'error', error: describe(e) })
    }
  }, [])

  const loadDocs = useCallback(async (kbId: number) => {
    const seq = ++docsSeq.current
    setDocs({ status: 'loading' })
    try {
      const data = await knowledgeApi.documents(kbId)
      if (seq === docsSeq.current) setDocs({ status: 'ok', data })
    } catch (e) {
      if (seq === docsSeq.current) setDocs({ status: 'error', error: describe(e) })
    }
  }, [])

  const loadChunks = useCallback(async (offset: number, limit: number) => {
    const docId = selectedDocId
    const kbId = selectedKbId
    if (docId == null || kbId == null) return
    setChunks({ status: 'loading' })
    try {
      setChunks({ status: 'ok', data: await knowledgeApi.chunks(kbId, docId, offset, limit) })
    } catch (e) {
      setChunks({ status: 'error', error: describe(e) })
    }
  }, [selectedDocId, selectedKbId])

  // 首次进入这一页时拉一次列表
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadKbs()
  }, [active, loadKbs])

  // 选中某个库 → 拉它的文档；清掉上一个库的文档与切片状态（否则会看起来"这个库里还是别人的文档"）
  useEffect(() => {
    if (!active || selectedKbId == null) return
    pollStartedAt.current = Date.now()
    void loadDocs(selectedKbId)
  }, [active, selectedKbId, loadDocs])

  // 有文档处于未完成态时才轮询（并且有上界）
  const hasUnfinished = useMemo(() => {
    if (docs.status !== 'ok') return false
    return docs.data.documents.some((d: { status: string }) => d.status === 'UPLOADED' || d.status === 'PROCESSING')
  }, [docs])

  useEffect(() => {
    if (!active || selectedKbId == null || !hasUnfinished) {
      setPolling(false)
      return
    }
    setPolling(true)
    const timer = setInterval(() => {
      if (Date.now() - pollStartedAt.current > POLL_MAX_MS) {
        setPolling(false)
        return
      }
      void loadDocs(selectedKbId)
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [active, selectedKbId, hasUnfinished, loadDocs])

  /** 写操作的统一外壳：忙标志、回执、成功后刷新。返回"成没成"，调用方据此决定要不要关弹窗。 */
  const mutate = useCallback(
    async (what: string, action: () => Promise<string>, after?: () => Promise<void>): Promise<boolean> => {
      setBusy(true)
      setFeedback({ status: 'idle' })
      try {
        const message = await action()
        setFeedback({ status: 'ok', message })
        if (after) await after()
        return true
      } catch (e) {
        // 400/409/413 是"你填的东西/时机不对"，后端那句话原样显示；其余是链路问题，带个前缀
        const prefix = e instanceof KbRejectedError ? '' : `${what}失败：`
        setFeedback({ status: 'error', error: prefix + describe(e) })
        return false
      } finally {
        setBusy(false)
      }
    },
    [],
  )

  return {
    kbs,
    docs,
    chunks,
    selectedKbId,
    selectedDocId,
    feedback,
    busy,
    polling,
    selectKb: (id) => {
      setSelectedKbId(id)
      setSelectedDocId(null)
      setChunks({ status: 'idle' })
      setDocs(id == null ? { status: 'idle' } : { status: 'loading' })
    },
    selectDoc: (id) => {
      setSelectedDocId(id)
      setChunks({ status: 'idle' })
    },
    clearFeedback: () => setFeedback({ status: 'idle' }),
    reload: loadKbs,
    reloadDocs: async () => {
      if (selectedKbId != null) await loadDocs(selectedKbId)
    },
    loadChunks,

    create: (payload) =>
      mutate('建知识库', async () => {
        const r = await knowledgeApi.create(payload)
        return `已建「${r.knowledgeBase.name}」，集合 ${r.knowledgeBase.collectionName}（${r.knowledgeBase.embeddingDim} 维）`
      }, loadKbs),

    setEnabled: (kbId, enabled) =>
      mutate('改启停', async () => (await knowledgeApi.setEnabled(kbId, enabled)).note, loadKbs),

    remove: (kbId) =>
      mutate('删知识库', async () => (await knowledgeApi.remove(kbId)).note, async () => {
        if (selectedKbId === kbId) setSelectedKbId(null)
        await loadKbs()
      }),

    upload: (kbId, file) =>
      mutate(`传「${file.name}」`, async () => `「${file.name}」${(await knowledgeApi.upload(kbId, file)).note}`, () =>
        loadDocs(kbId),
      ),

    reIndex: (kbId, docId) =>
      mutate('重新索引', async () => (await knowledgeApi.reIndex(kbId, docId)).note, () => {
        pollStartedAt.current = Date.now()
        return loadDocs(kbId)
      }),

    setDocumentEnabled: (kbId, docId, enabled) =>
      mutate('改文档启停', async () => (await knowledgeApi.setDocumentEnabled(kbId, docId, enabled)).note, () =>
        loadDocs(kbId),
      ),

    removeDocument: (kbId, docId) =>
      mutate('删文档', async () => (await knowledgeApi.removeDocument(kbId, docId)).note, async () => {
        if (selectedDocId === docId) setSelectedDocId(null)
        await loadDocs(kbId)
      }),

    updateChunk: (kbId, docId, uid, text) =>
      mutate('改切片正文', async () => (await knowledgeApi.updateChunk(kbId, docId, uid, text)).note),

    setChunkEnabled: (kbId, docId, uid, enabled) =>
      mutate('改切片启停', async () => {
        await knowledgeApi.setChunkEnabled(kbId, docId, uid, enabled)
        return enabled ? '这一片已参与检索' : '这一片已停用（不参与检索）'
      }),

    removeChunk: (kbId, docId, uid) =>
      mutate('删切片', async () => (await knowledgeApi.removeChunk(kbId, docId, uid)).note, () =>
        loadChunks(0, 20),
      ),
  }
}
