import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Empty, Flex, Input, InputNumber, Modal, Spin, Switch, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { IKbCapabilities, IKbChunk, IKbDocument, IKnowledgeBase, KbRejectedError, knowledgeApi } from '../api/knowledge'
import type { UseKnowledgeBaseResult } from '../hooks/useKnowledgeBase'

const { Text } = Typography

/**
 * 知识库（阶段二 · 3.6 / 3.7 / 3.8）。
 *
 * 【这一页在回答三个不同的问题，所以有三层】
 *   ① 有哪些知识库、现在能不能用（能力条：embedding 配没配上、Milvus 连没连上）；
 *   ② 这个库里有哪些文档、各自索引到哪一步了（**四种状态必须长得不一样**，见 `docStatus`）；
 *   ③ 这一份文档切成了哪些块、检索能不能把它们捞回来（切片页 + 命中测试）。
 * 三层都在一个面板里切换（本项目没有路由，一个 mode 一个面板），靠 `kb.selectedKbId/selectedDocId` 决定看到哪一层。
 *
 * 【★ 上传的输入框必须每次清空，但清空之前必须先把文件复制出来】
 * 选同一个文件两次时 `change` 事件不会再触发 —— 不清空 `input.value` 的话，
 * 第二次点"选文件"看起来毫无反应（没有任何报错）。
 * 而 `input.value = ''` 会同时清空它的 `FileList`：Chromium 实测，清空之后
 * 之前存下的那个 `FileList.length` 是 0。先清空再读，循环一次都不进 ——
 * **一个请求都不发，也没有任何报错**。
 */
export function KnowledgePanel({ kb }: { kb: UseKnowledgeBaseResult }) {
  const [creating, setCreating] = useState(false)
  const [detail, setDetail] = useState<{ name: string; description: string; chunkStrategy: string }>({
    name: '',
    description: '',
    chunkStrategy: 'structural',
  })

  const list = kb.kbs.status === 'ok' ? kb.kbs.data : null
  const current = list?.knowledgeBases.find((x: IKnowledgeBase) => x.id === kb.selectedKbId) ?? null

  return (
    <div className="diag-panel kb-panel">
      <Feedback kb={kb} />

      {kb.kbs.status === 'loading' && <Spin description="正在读知识库列表…" />}
      {kb.kbs.status === 'error' && (
        <Alert type="error" showIcon title="读知识库列表失败" description={<div className="diag-note">{kb.kbs.error}</div>} />
      )}

      {list && (
        <>
        <CapabilityStrip capabilities={list.capabilities} />

        {current == null ? (
          <Card
            size="small"
            className="diag-card diag-card-wide"
            title={<Flex gap={8} align="center"><span>知识库（{list.total} 个）</span>{kb.polling && <Tag color="processing">索引中，自动刷新</Tag>}</Flex>}
            extra={
              <Flex gap={8}>
                <Button onClick={() => void kb.reload()} disabled={kb.busy}>
                  刷新
                </Button>
                <Button type="primary" onClick={() => setCreating(true)} disabled={!list.capabilities.embeddingAvailable}>
                  建一个知识库
                </Button>
              </Flex>
            }
          >
            {list.knowledgeBases.length === 0 ? (
              <Empty description="还没有知识库。点「建一个知识库」：填名字 → 选分块策略 → 建完就能传文档了。" />
            ) : (
              <KBTable kbs={list.knowledgeBases} kb={kb} />
            )}
          </Card>
        ) : (
          <>
            <Card
              size="small"
              className="diag-card diag-card-wide"
              title={
                <Flex gap={8} align="center" wrap>
                  <Button size="small" onClick={() => kb.selectKb(null)}>
                    ← 全部知识库
                  </Button>
                  <span>{current.name}</span>
                  {!current.enabled && <Tag color="default">已停用</Tag>}
                </Flex>
              }
              extra={
                <Button onClick={() => void kb.reloadDocs()} disabled={kb.busy}>
                  刷新
                </Button>
              }
            >
              <Flex vertical gap={6} className="kb-meta">
                <Text type="secondary">
                  集合 <Text code>{current.collectionName}</Text> · {current.embeddingProvider}/{current.embeddingModel}（
                  {current.embeddingDim} 维） · 分块 {current.chunkStrategyLabel} · top-k {current.topK} · 阈值{' '}
                  {current.similarityThreshold} · 重排 {current.rerankEnabled ? '开' : '关'}
                </Text>
                <Text type="secondary">
                  文档 {current.documentCount} 份 · 切片 {current.chunkCount} 块
                  {current.description ? ` · ${current.description}` : ''}
                </Text>
              </Flex>
            </Card>

            <DocumentCard kb={kb} kbId={current.id} />

            {kb.selectedDocId != null && <ChunkCard kb={kb} kbId={current.id} docId={kb.selectedDocId} />}

            <RetrieveCard kbId={current.id} capabilities={list.capabilities} />
          </>
        )}
        </>
      )}

      <CreateModal
        open={creating}
        busy={kb.busy}
        value={detail}
        onChange={setDetail}
        onClose={() => setCreating(false)}
        onSubmit={async () => {
          const ok = await kb.create({
            name: detail.name,
            description: detail.description,
            chunkStrategy: detail.chunkStrategy as 'length' | 'structural',
          })
          // 失败时**不关**弹窗：关掉会把用户填的东西丢掉
          if (ok) setCreating(false)
        }}
      />
    </div>
  )
}

function Feedback({ kb }: { kb: UseKnowledgeBaseResult }) {
  return (
    <>
      {kb.feedback.status === 'ok' && (
        <Alert type="success" showIcon closable title={kb.feedback.message} onClose={kb.clearFeedback} />
      )}
      {kb.feedback.status === 'error' && (
        <Alert type="error" showIcon closable title={kb.feedback.error} onClose={kb.clearFeedback} />
      )}
    </>
  )
}

/** 能力条：`false` 时必须给出**原因**（"没配上"与"配上了但这次没查到东西"是两件事）。 */
function CapabilityStrip({ capabilities }: { capabilities: IKbCapabilities }) {
  const problems: string[] = []
  if (!capabilities.embeddingAvailable) problems.push(`embedding 不可用：${capabilities.embeddingReason}`)
  if (!capabilities.milvusReachable) problems.push('Milvus 连不上：先跑 pwsh scripts/milvus-start.ps1')
  if (problems.length > 0) {
    return <Alert type="error" showIcon title="知识库现在不能用" description={<div className="diag-note">{problems.join('；')}</div>} />
  }
  return (
    <Alert
      type="success"
      showIcon
      title={`知识库可用：${capabilities.embeddingProvider}/${capabilities.embeddingModel}（默认 ${capabilities.embeddingDimensions} 维）· Milvus 连上了`}
    />
  )
}

function KBTable({ kbs, kb }: { kbs: IKnowledgeBase[]; kb: UseKnowledgeBaseResult }) {
  const columns: ColumnsType<IKnowledgeBase> = [
    {
      title: '名称',
      key: 'name',
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Flex gap={6} align="center">
            <Text strong>{row.name}</Text>
            {!row.enabled && <Tag>已停用</Tag>}
          </Flex>
          <Text type="secondary" className="diag-note">{row.description ?? '（未填说明）'}</Text>
        </Flex>
      ),
    },
    { title: '集合', key: 'collection', render: (_, row) => <Text code>{row.collectionName}</Text> },
    {
      title: '向量模型',
      key: 'model',
      render: (_, row) => <Text type="secondary">{row.embeddingModel}／{row.embeddingDim} 维</Text>,
    },
    { title: '分块', key: 'chunk', render: (_, row) => <Text type="secondary">{row.chunkStrategyLabel}</Text> },
    {
      title: '文档 / 切片',
      key: 'counts',
      render: (_, row) => (
        <Text type="secondary">
          {row.documentCount} / {row.chunkCount}
        </Text>
      ),
    },
    {
      title: '启用',
      key: 'enabled',
      render: (_, row) => (
        <Switch size="small" checked={row.enabled} disabled={kb.busy} onChange={(v) => void kb.setEnabled(row.id, v)} />
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_, row) => (
        <Flex gap={6}>
          <Button size="small" type="link" onClick={() => kb.selectKb(row.id)}>
            打开
          </Button>
          <ModalDelete kbId={row.id} name={row.name} docCount={row.documentCount} kb={kb} />
        </Flex>
      ),
    },
  ]
  return <Table rowKey="id" size="small" pagination={false} columns={columns} dataSource={kbs} onRow={(row) => ({ 'data-kb-row': row.id } as never)} />
}

function ModalDelete({ kbId, name, docCount, kb }: { kbId: number; name: string; docCount: number; kb: UseKnowledgeBaseResult }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button size="small" danger disabled={kb.busy} onClick={() => setOpen(true)}>
        删
      </Button>
      <Modal
        open={open}
        title={`删掉知识库《${name}》？`}
        okText="删（没有回头路）"
        cancelText="取消"
        okButtonProps={{ danger: true, disabled: kb.busy }}
        confirmLoading={kb.busy}
        onOk={async () => {
          if (await kb.remove(kbId)) setOpen(false)
        }}
        onCancel={() => setOpen(false)}
        width={520}
      >
        <Flex vertical gap={8}>
          <Text>
            会删掉：Milvus 集合、这个库的全部切片与 {docCount} 份文档记录。
          </Text>
          <Text type="secondary">
            ⚠️ 上传的原文件仍留在磁盘上（`ai.rag.kb.storage-path` 下），要一并清理得手工删目录。
          </Text>
        </Flex>
      </Modal>
    </>
  )
}

/** 文档列表 + 上传。四种状态的写法在这里（见 `docStatus`）。 */
function DocumentCard({ kb, kbId }: { kb: UseKnowledgeBaseResult; kbId: number }) {
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [dragOver, setDragOver] = useState(false)

  const pick = () => inputRef.current?.click()
  /** 入参是一份数组快照：清空 input 之后，`FileList` 的旧引用也读不到文件。 */
  const upload = async (files: File[]) => {
    for (const file of files) {
      // 一次一个：后端一次只收一个文件，逐个回执能分清是哪一个失败
      await kb.upload(kbId, file)
    }
  }

  const columns: ColumnsType<IKbDocument> = [
    {
      title: '文档',
      key: 'name',
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Text>{row.name}</Text>
          <Text type="secondary" className="diag-note">
            {row.format} · {formatBytes(row.sizeBytes)}
            {row.chunkCount > 0 ? ` · ${row.chunkCount} 块 / ${row.tokenCount} token` : ''}
          </Text>
        </Flex>
      ),
    },
    { title: '状态', key: 'status', render: (_, row) => docStatus(row) },
    {
      title: '启用',
      key: 'enabled',
      render: (_, row) => (
        <Switch
          size="small"
          checked={row.enabled}
          disabled={kb.busy}
          onChange={(v) => void kb.setDocumentEnabled(kbId, row.id, v)}
        />
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_, row) => (
        <Flex gap={6} wrap>
          <Button size="small" type="link" onClick={() => kb.selectDoc(kb.selectedDocId === row.id ? null : row.id)}>
            {kb.selectedDocId === row.id ? '收起切片' : '看切片'}
          </Button>
          <Button
            size="small"
            type="link"
            disabled={kb.busy || row.status === 'PROCESSING'}
            title={row.status === 'PROCESSING' ? '正在索引中：等它跑完再点' : '清掉旧切片与向量，重新解析切分'}
            onClick={() => void kb.reIndex(kbId, row.id)}
          >
            重新索引
          </Button>
          <Button size="small" type="link" danger disabled={kb.busy} onClick={() => void kb.removeDocument(kbId, row.id)}>
            删
          </Button>
        </Flex>
      ),
    },
  ]

  const docs = kb.docs.status === 'ok' ? kb.docs.data : null

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title={
        <Flex gap={8} align="center" wrap>
          <span>文档{docs ? `（${docs.total} 份）` : ''}</span>
          {docs?.counts.filter((c: { total: number }) => c.total > 0).map((c: { status: string; label: string; total: number }) => (
            <Tag key={c.status}>{c.label} {c.total}</Tag>
          ))}
        </Flex>
      }
      extra={
        <Button type="primary" onClick={pick} disabled={kb.busy}>
          传文档
        </Button>
      }
    >
      {/* 一次一个文件、上传后**清空 input**（同名的文件第二次才能再选） */}
      <input
        ref={inputRef}
        type="file"
        hidden
        onChange={async (e) => {
          // 顺序不能换：先复制成数组，再清空（清空会把 FileList 一起清掉）
          const files = Array.from(e.target.files ?? [])
          e.target.value = ''
          await upload(files)
        }}
      />
      <div
        className={`kb-drop ${dragOver ? 'is-over' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={async (e) => {
          e.preventDefault()
          setDragOver(false)
          // 拖放的 DataTransfer 在事件处理器返回后失效，同样先复制
          await upload(Array.from(e.dataTransfer.files ?? []))
        }}
      >
        把文件拖到这里，或点右上角「传文档」（一次一个；支持 md / txt / html / pdf / docx）
      </div>

      {kb.docs.status === 'loading' && <Spin description="正在读文档…" />}
      {kb.docs.status === 'error' && <Alert type="error" showIcon title="读文档失败" description={<div className="diag-note">{kb.docs.error}</div>} />}
      {docs && (docs.documents.length === 0
        ? <Empty description="这个库还没有文档。" />
        : <Table rowKey="id" size="small" pagination={false} columns={columns} dataSource={docs.documents} onRow={(row) => ({ 'data-kb-doc-status': row.status, 'data-kb-doc-id': row.id } as never)} />)}
    </Card>
  )
}

/**
 * 文档的四种状态 —— **必须长得不一样**：
 * 未索引（还没轮到）/ 索引中 / 已索引 N 块 / **已索引但一块都没切出来** / 失败（带原因）。
 * 第四种是最容易被糊掉的一种：它与"未索引"都表现为"没有块"，而一个是换文档、一个是等。
 */
function docStatus(row: IKbDocument) {
  if (row.status === 'FAILED') {
    return (
      <Flex vertical gap={2}>
        <Tag color="error">索引失败</Tag>
        <Text type="danger" className="diag-note">{row.error ?? '（没有记下原因）'}</Text>
      </Flex>
    )
  }
  if (row.status === 'PROCESSING') return <Tag color="processing">索引中…</Tag>
  if (row.status === 'UPLOADED') {
    return row.neverIndexed ? <Tag>未索引</Tag> : <Tag>等待重新索引</Tag>
  }
  if (row.indexedWithNoChunk) {
    return (
      <Flex vertical gap={2}>
        <Tag color="warning">已索引，但没有切出任何块</Tag>
        <Text type="secondary" className="diag-note">文档可能是扫描件或空文件 —— 换一份，或换个分块策略</Text>
      </Flex>
    )
  }
  return <Tag color="success">已索引 {row.chunkCount} 块</Tag>
}

/** 切片列表（服务端分页）+ 改正文（会重新算一次向量）+ 启停 + 删除 + 切分预览。 */
function ChunkCard({ kb, kbId, docId }: { kb: UseKnowledgeBaseResult; kbId: number; docId: number }) {
  const [page, setPage] = useState({ offset: 0, limit: 20 })
  const [preview, setPreview] = useState<IKbChunk[] | null>(null)
  const [editing, setEditing] = useState<{ uid: string; text: string } | null>(null)
  const loadedDoc = useRef<number | null>(null)

  // 换文档时拉第一页（放在 effect 里而不是渲染期：渲染期发请求在严格模式下会跑两遍）
  useEffect(() => {
    if (loadedDoc.current === docId) return
    loadedDoc.current = docId
    setPage({ offset: 0, limit: 20 })
    void kb.loadChunks(0, 20)
  }, [docId, kb])

  const page_data = kb.chunks.status === 'ok' ? kb.chunks.data : null

  const columns: ColumnsType<IKbChunk> = [
    { title: '#', key: 'seq', width: 60, render: (_, row) => <Text code>{row.seq}</Text> },
    {
      title: '切片',
      key: 'text',
      render: (_, row) => (
        <Flex vertical gap={4}>
          {row.headingPath && <Text type="secondary" className="diag-note">{row.headingPath}</Text>}
          <Text className="kb-chunk-text">{row.text.length > 160 ? `${row.text.slice(0, 160)}…` : row.text}</Text>
          <Text type="secondary" className="diag-note">{row.tokenCount} token · {row.charCount} 字</Text>
        </Flex>
      ),
    },
    {
      title: '参与检索',
      key: 'enabled',
      width: 110,
      render: (_, row) =>
        row.uid && (
          <Switch
            size="small"
            checked={Boolean(row.enabled)}
            disabled={kb.busy}
            onChange={(v) => void kb.setChunkEnabled(kbId, docId, row.uid as string, v)}
          />
        ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      render: (_, row) =>
        row.uid && (
          <Flex gap={6}>
            <Button size="small" type="link" onClick={() => setEditing({ uid: row.uid as string, text: row.text })}>
              改正文
            </Button>
            <Button size="small" type="link" danger disabled={kb.busy} onClick={() => void kb.removeChunk(kbId, docId, row.uid as string)}>
              删
            </Button>
          </Flex>
        ),
    },
  ]

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title={
        <Flex gap={8} align="center">
          <span>切片{docId ? `（这份文档共 ${page_data?.total ?? '…'} 块）` : ''}</span>
        </Flex>
      }
      extra={
        <Flex gap={8}>
          <Button
            onClick={async () => setPreview((await knowledgeApi.previewChunks(kbId, docId)).chunks)}
            disabled={kb.busy}
            title="按当前分块策略重新解析一遍，只给你看会切成什么样：不写库、不算向量、不花钱"
          >
            切分预览
          </Button>
          <Button onClick={() => void kb.loadChunks(page.offset, page.limit)} disabled={kb.busy}>
            刷新
          </Button>
        </Flex>
      }
    >
      {kb.chunks.status === 'loading' && <Spin description="正在读切片…" />}
      {kb.chunks.status === 'error' && <Alert type="error" showIcon title="读切片失败" description={<div className="diag-note">{kb.chunks.error}</div>} />}
      {page_data && (
        <>
          <Table
            rowKey={(row) => row.uid ?? `preview-${row.seq}`}
            size="small"
            columns={columns}
            dataSource={page_data.chunks}
            onRow={(row) => ({ 'data-kb-chunk-seq': row.seq } as never)}
            pagination={{
              // ⚠️ total 必须来自服务端：绑 dataSource.length 的话，5000 块的文档会显示"共 20 条"
              total: page_data.total,
              pageSize: page_data.limit,
              current: Math.floor(page_data.offset / page_data.limit) + 1,
              size: 'small',
              showSizeChanger: false,
              onChange: (p) => {
                const next = { offset: (p - 1) * page_data.limit, limit: page_data.limit }
                setPage(next)
                void kb.loadChunks(next.offset, next.limit)
              },
            }}
          />
          <Text type="secondary" className="diag-note">
            改正文会**重新算一次向量**（花一次钱、动两个库）；启停只改库里那一列，检索时按它过滤。
          </Text>
        </>
      )}

      <Modal
        open={editing != null}
        width={720}
        title="改切片正文"
        okText="保存（重新向量化）"
        cancelText="取消"
        confirmLoading={kb.busy}
        onOk={async () => {
          if (!editing) return
          if (await kb.updateChunk(kbId, docId, editing.uid, editing.text)) setEditing(null)
        }}
        onCancel={() => setEditing(null)}
      >
        <Input.TextArea
          rows={10}
          value={editing?.text ?? ''}
          onChange={(e) => setEditing((prev) => (prev ? { ...prev, text: e.target.value } : prev))}
        />
      </Modal>

      <Modal open={preview != null} width={860} title="切分预览（不写库、不算向量）" footer={null} onCancel={() => setPreview(null)}>
        <Flex vertical gap={10}>
          <Text type="secondary">
            共 {preview?.length ?? 0} 块。它用的是**真正会入库的那一套**解析器与分块器 ——
            这里看到的就是保存之后的结果。
          </Text>
          <div className="kb-preview-list">
            {preview?.slice(0, 50).map((c) => (
              <Flex key={c.seq} vertical gap={2} className="kb-preview-item">
                <Text code>#{c.seq} · {c.tokenCount} token</Text>
                {c.headingPath && <Text type="secondary" className="diag-note">{c.headingPath}</Text>}
                <Text>{c.text.length > 200 ? `${c.text.slice(0, 200)}…` : c.text}</Text>
              </Flex>
            ))}
          </div>
          {(preview?.length ?? 0) > 50 && <Text type="secondary">（只列了前 50 块）</Text>}
        </Flex>
      </Modal>
    </Card>
  )
}

/** 命中测试：只读。分数分两个显示（向量分与重排分不同量纲），并如实报"这次重排生效没有"。 */
function RetrieveCard({ kbId, capabilities }: { kbId: number; capabilities: IKbCapabilities }) {
  const [query, setQuery] = useState('')
  const [topK, setTopK] = useState<number | null>(3)
  const [result, setResult] = useState<Awaited<ReturnType<typeof knowledgeApi.retrieve>> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const seq = useRef(0)

  const run = async (withRerank: boolean) => {
    if (!query.trim()) {
      setError('先写一句要检索的话')
      return
    }
    const mySeq = ++seq.current
    setBusy(true)
    setError(null)
    try {
      const data = await knowledgeApi.retrieve([kbId], query.trim(), topK ?? undefined, withRerank)
      if (mySeq === seq.current) setResult(data)
    } catch (e) {
      if (mySeq !== seq.current) return
      setResult(null)
      setError(e instanceof KbRejectedError ? e.message : e instanceof Error ? e.message : String(e))
    } finally {
      if (mySeq === seq.current) setBusy(false)
    }
  }

  return (
    <Card size="small" className="diag-card diag-card-wide" title="命中测试（只读，不改任何状态）">
      <Flex vertical gap={10}>
        <Flex gap={8} align="center" wrap>
          <Input
            style={{ maxWidth: 480 }}
            placeholder="拿一句真问题试试：它能不能把该找的那块捞回来"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onPressEnter={() => void run(false)}
          />
          <Text type="secondary">top-k</Text>
          <InputNumber min={1} max={20} value={topK} onChange={(v) => setTopK(v)} />
          <Button type="primary" loading={busy} onClick={() => void run(false)}>
            只向量检索
          </Button>
          <Button
            loading={busy}
            disabled={!capabilities.rerankAvailable}
            title={
              capabilities.rerankAvailable
                ? `多一次 ${capabilities.rerankModel} 调用（按条计费），它会重新排序候选`
                : `重排不可用：${capabilities.rerankReason}`
            }
            onClick={() => void run(true)}
          >
            带重排检索
          </Button>
        </Flex>

        {error && <Alert type="error" showIcon title={error} />}
        {result && (
          <Flex vertical gap={8}>
            <Flex gap={12} wrap>
              <Text type="secondary">命中 <Text strong>{result.hitCount}</Text> 条</Text>
              <Text type="secondary">分母 indexSize=<Text strong>{result.indexSize}</Text></Text>
              <Text type="secondary">粗召回 {result.candidateCount} 条</Text>
              <Text type="secondary">{result.elapsedMs} ms</Text>
              {result.reranked ? (
                <Tag color="success">已重排</Tag>
              ) : (
                <Tag color="warning">未重排：{result.rerankSkipReason}</Tag>
              )}
            </Flex>
            {result.notes.length > 0 && (
              <Alert type="warning" showIcon title={result.notes.join('；')} />
            )}
            {result.hits.length === 0 ? (
              <Empty description={`在 ${result.indexSize} 个片段里没有找到相关内容 —— 换个说法，或确认文档索引成功了（分母为 0 说明这个库还没有可检索的切片）`} />
            ) : (
              <div className="kb-hit-list">
                {result.hits.map((hit) => (
                  <Flex key={hit.chunkUid} vertical gap={4} className="kb-hit" data-kb-hit-rank={hit.rank}>
                    <Flex gap={8} wrap align="center">
                      <Tag color="blue">#{hit.rank}</Tag>
                      <Text strong>{hit.documentName}</Text>
                      <Text type="secondary" className="diag-note">
                        向量分 {hit.vectorScore.toFixed(4)}
                        {hit.rerankScore != null ? ` · 重排分 ${hit.rerankScore.toFixed(4)}` : ' · 未重排'}
                      </Text>
                    </Flex>
                    {hit.headingPath && <Text type="secondary" className="diag-note">{hit.headingPath}</Text>}
                    <Text>{hit.text.length > 320 ? `${hit.text.slice(0, 320)}…` : hit.text}</Text>
                  </Flex>
                ))}
              </div>
            )}
          </Flex>
        )}
      </Flex>
    </Card>
  )
}

function CreateModal({
  open,
  busy,
  value,
  onChange,
  onClose,
  onSubmit,
}: {
  open: boolean
  busy: boolean
  value: { name: string; description: string; chunkStrategy: string }
  onChange: (v: { name: string; description: string; chunkStrategy: string }) => void
  onClose: () => void
  onSubmit: () => void | Promise<void>
}) {
  return (
    <Modal
      open={open}
      width={680}
      title="建一个知识库"
      okText="建库（同时建 Milvus 集合）"
      cancelText="取消"
      confirmLoading={busy}
      okButtonProps={{ disabled: !value.name.trim() || busy }}
      onOk={() => void onSubmit()}
      onCancel={onClose}
    >
      <Flex vertical gap={12}>
        <Flex vertical gap={4}>
          <Text strong>名称</Text>
          <Input value={value.name} onChange={(e) => onChange({ ...value, name: e.target.value })} placeholder="例：产品手册" />
          <Text type="secondary" className="diag-note">唯一：界面上按名字认人，重名会让「我删的是哪一个」没有答案。</Text>
        </Flex>
        <Flex vertical gap={4}>
          <Text strong>说明（选填）</Text>
          <Input value={value.description} onChange={(e) => onChange({ ...value, description: e.target.value })} />
        </Flex>
        <Flex vertical gap={4}>
          <Text strong>分块策略</Text>
          <Flex gap={16}>
            <label>
              <input
                type="radio"
                checked={value.chunkStrategy === 'structural'}
                onChange={() => onChange({ ...value, chunkStrategy: 'structural' })}
              />{' '}
              按标题结构切（带标题路径，推荐）
            </label>
            <label>
              <input
                type="radio"
                checked={value.chunkStrategy === 'length'}
                onChange={() => onChange({ ...value, chunkStrategy: 'length' })}
              />{' '}
              定长切
            </label>
          </Flex>
          <Text type="secondary" className="diag-note">
            只有这两种：admin 界面上那四个选项里 page/title 实际都落到同一个切分器 —— 摆一个不生效的选项更误导。
          </Text>
        </Flex>
        <Alert
          type="info"
          showIcon
          title="建库时会定下三件之后不可改的事"
          description={
            <div className="diag-note">
              embedding 模型、向量维度（= Milvus 集合的维度）、集合名（由 id 生成）。
              换模型等于换一个向量空间 —— 只能建新库重新传文档。
            </div>
          }
        />
      </Flex>
    </Modal>
  )
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
