import { Fragment, useState } from 'react'
import { Alert, Button, Card, Empty, Flex, Input, Segmented, Spin, Table, Tag, Typography } from 'antd'
import type { UseRagDiagResult } from '../hooks/useRagDiag'
import type {
  CaseOutcome,
  CompareResponse,
  CorpusResponse,
  EvalSetResponse,
  PreviewResponse,
  RetrieveResponse,
  StrategyReport,
  TokensResponse,
} from '../api/rag'
import type { Remote } from '../types'

/**
 * 「RAG 分块评测」面板：把阶段二那条离线链路摆出来。
 *
 * <h2>这张页面的主角不是"检索结果"，是"两种分块策略的对照"</h2>
 * 阶段二的验收口径是**能用数字说明「换分块策略提升了多少」**。
 * 所以最下面那张对照表才是重点，上面几块（语料 / 预览 / 分词 / 召回）
 * 存在的意义是：当对照表给出一个数字时，你能顺着它们查回去 ——
 * 切出来的块长什么样、这个词被切成了什么、这条提问召回了谁。
 *
 * <h2>刻意把"这份数字不能证明什么"写在页面上</h2>
 * 实测结论是「换分块策略没有提升召回率，省的是 token」，
 * 而且**块大小这个混杂因素没排除**（块越大越容易碰到答案）。
 * 这两句话必须出现在界面上，不能只写在文档里 ——
 * 否则任何人看到 0.7674 和 0.8140 两个数，都会自行脑补出一个方向性结论。
 * 一个会被误读的界面，比没有界面更糟。
 */
export function RagPanel({ diag }: { diag: UseRagDiagResult }) {
  return (
    <div className="diag-panel">
      {diag.corpus.status === 'error' && (
        <Alert
          type="error"
          showIcon
          title="拿不到后端数据，这个面板的先决条件是后端在跑"
          description={
            <Flex vertical gap={6}>
              <span>{diag.corpus.error}</span>
              <Typography.Text type="secondary">
                面板上所有数字都来自后端的离线链路（解析 → 分块 → 检索），没有本地估算或假数据可退。
                Vite dev server 会把 /api 代理到 http://localhost:8080。
              </Typography.Text>
            </Flex>
          }
        />
      )}

      <CorpusCard remote={diag.corpus} onReload={diag.loadCorpus} />

      <PreviewCard
        remote={diag.preview}
        strategy={diag.strategy}
        onStrategyChange={diag.setStrategy}
        onRun={diag.runPreview}
      />

      <TokensCard remote={diag.tokens} onAnalyze={diag.analyzeTokens} evalSet={diag.evalSet} />

      <RetrieveCard
        remote={diag.retrieval}
        lastQuery={diag.lastQuery}
        onSearch={diag.search}
        evalSet={diag.evalSet}
      />

      <ComparisonCard remote={diag.comparison} onRun={diag.runComparison} running={diag.running} />

      <EvalSetCard remote={diag.evalSet} onReload={diag.loadEvalSet} />
    </div>
  )
}

// ---------------------------------------------------------------------------
//  1. 语料解析 —— 排查第一步
// ---------------------------------------------------------------------------

/**
 * 块类型分布是"解析有没有正确工作"最直接的证据。
 *
 * 这里最重要的一条是 **标题块数**：它是 `heading` 这一项。
 * 实测踩过的坑就是它变成 0 —— Tika 把 Markdown 当纯文本处理，
 * 13KB 带 19 个标题的文档被解析成 1 个段落，而**整条链路不报任何错**：
 * 文档读进来了、块也切出来了、检索也有结果，只是"按标题层级分块"从未发生。
 * 所以这一项单独拎出来做显眼的判定，而不是混在分布里。
 */
function CorpusCard({ remote, onReload }: { remote: Remote<CorpusResponse>; onReload: () => void }) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="语料解析：文档读成了什么"
      extra={
        <Button size="small" onClick={onReload} loading={remote.status === 'loading'}>
          重新解析
        </Button>
      }
    >
      {remote.status === 'loading' && <LoadingLine text="正在解析语料……" />}
      {remote.status === 'error' && <InlineError title="解析概览读取失败" message={remote.error} />}
      {remote.status === 'ok' && <CorpusBody data={remote.data} />}
    </Card>
  )
}

function CorpusBody({ data }: { data: CorpusResponse }) {
  const headings = data.blockTypes.heading ?? 0

  return (
    <Flex vertical gap={12}>
      <div className="stat-grid">
        <Stat label="文档数" value={data.documentCount} />
        <Stat label="文本块总数" value={data.blockCount} />
        <Stat label="标题块" value={headings} accent={headings > 0} />
        <Stat label="解析失败" value={data.failed.length} accent={data.failed.length > 0} />
      </div>

      {/*
        标题块为 0 是一个"必须当场喊出来"的状态：结构分块的地基没了。
        用 error 而不是 warning —— 它不是提醒，是结论已经不可信了。
      */}
      {headings === 0 ? (
        <Alert
          type="error"
          showIcon
          title="没有识别出任何标题块：『按标题层级分块』实际上没有生效"
          description="这通常意味着解析器把文档当成了纯文本（例如 Markdown 走了纯文本解析路径）。此时两种策略的差别会被抹平，下面的对照数字不可信。"
        />
      ) : (
        <Typography.Text type="secondary" className="diag-note">
          {data.note}
        </Typography.Text>
      )}

      <Table
        size="small"
        rowKey="documentId"
        pagination={false}
        dataSource={data.documents}
        columns={[
          { title: '文档', dataIndex: 'documentId', ellipsis: true },
          { title: '块数', dataIndex: 'blocks', width: 90, align: 'right' as const },
          { title: '字符数', dataIndex: 'chars', width: 110, align: 'right' as const },
        ]}
      />

      <Flex gap={8} wrap align="center">
        <Typography.Text type="secondary">块类型分布：</Typography.Text>
        {Object.entries(data.blockTypes)
          .filter(([, count]) => count > 0)
          .map(([type, count]) => (
            <Tag key={type} className="rag-type-tag" color={type === 'heading' ? 'blue' : undefined}>
              {TYPE_LABELS[type] ?? type} {count}
            </Tag>
          ))}
      </Flex>

      {data.failed.length > 0 && (
        <Alert
          type="warning"
          showIcon
          title={`有 ${data.failed.length} 份文档解析失败（已跳过，未中断整批）`}
          description={
            <ul className="issue-list">
              {data.failed.map(f => (
                <li key={f.documentId}>
                  <code>{f.documentId}</code>：{f.reason}
                </li>
              ))}
            </ul>
          }
        />
      )}
    </Flex>
  )
}

/** 块类型的中文名。键与后端 BlockType 的 name().toLowerCase() 一致 */
const TYPE_LABELS: Record<string, string> = {
  heading: '标题',
  paragraph: '段落',
  list_item: '列表项',
  table: '表格',
  code: '代码',
  quote: '引用',
  other: '其他',
}

// ---------------------------------------------------------------------------
//  2. 分块预览 —— 直接看块长什么样
// ---------------------------------------------------------------------------

/**
 * 预览这一块的价值在于"分块问题绝大部分是看一眼就明白了"。
 *
 * 面包屑是最值得看的字段：它是结构分块相对硬切的核心差异
 *（硬切恒为空 —— 它不知道标题在哪）。
 * 所以这里把面包屑单独一行显示，而不是混在正文里。
 */
function PreviewCard({
  remote,
  strategy,
  onStrategyChange,
  onRun,
}: {
  remote: Remote<PreviewResponse>
  strategy: string
  onStrategyChange: (s: string) => void
  onRun: (s?: string) => void
}) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="分块预览：切出来的块长什么样"
      extra={
        <Flex gap={8} align="center">
          <Segmented
            size="small"
            value={strategy}
            onChange={v => {
              // 切策略时立刻用新策略重跑，不必再点一次按钮 ——
              // 这个控件的目的就是对照，多一步点击纯属浪费
              const next = v as string
              onStrategyChange(next)
              onRun(next)
            }}
            options={[
              { label: '结构分块', value: 'structural' },
              { label: '固定硬切', value: 'fixed-size' },
            ]}
          />
          <Button size="small" onClick={() => onRun()}>
            切一次
          </Button>
        </Flex>
      }
    >
      {remote.status === 'idle' && (
        <Typography.Text type="secondary">
          点「切一次」看当前策略切出来的块。默认 3 条全文。
        </Typography.Text>
      )}
      {remote.status === 'loading' && <LoadingLine text="正在切分语料……" />}
      {remote.status === 'error' && <InlineError title="分块预览失败" message={remote.error} />}
      {remote.status === 'ok' && <PreviewBody data={remote.data} />}
    </Card>
  )
}

function PreviewBody({ data }: { data: PreviewResponse }) {
  return (
    <Flex vertical gap={12}>
      <div className="stat-grid">
        <Stat label="块数" value={data.chunkCount} />
        <Stat label="平均 token" value={data.avgTokens} />
        <Stat label="最大 token" value={data.maxTokens} />
        <Stat label="最小 token" value={data.minTokens} />
      </div>

      <Typography.Text type="secondary" className="diag-note">
        策略 <code>{data.strategy}</code>，下面显示前 {data.previewShown} 条。
        「固定硬切」的面包屑恒为空 —— 它只知道字符位置，不知道标题在哪，这正是两者最本质的差别。
      </Typography.Text>

      <Flex vertical gap={10}>
        {data.previews.map(chunk => (
          <div key={chunk.id} className="rag-chunk">
            <Flex gap={10} wrap align="center" className="rag-chunk-head">
              <Typography.Text strong>#{chunk.index}</Typography.Text>
              <Tag>{chunk.tokens} token</Tag>
              <Tag>{chunk.chars} 字符</Tag>
              <Typography.Text type="secondary">覆盖 {chunk.coveredBlocks} 个解析块</Typography.Text>
            </Flex>
            {chunk.breadcrumb ? (
              <div className="rag-breadcrumb">{chunk.breadcrumb}</div>
            ) : (
              <div className="rag-breadcrumb is-empty">（无标题路径）</div>
            )}
            <pre className="rag-chunk-text">{chunk.text}</pre>
          </div>
        ))}
      </Flex>
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  3. 分词诊断
// ---------------------------------------------------------------------------

/**
 * 「检索不到结果时，第一现场是分词」。
 *
 * 中文走的是二元组：`虚拟线程` → `虚拟/拟线/线程`。
 * 所以提问与文档的 token 对不上，是检索失败最常见的原因 ——
 * 而这件事**看不见**（块切得对不对肉眼能看出来，token 看不出来）。
 * 所以给它单独一块，并且允许一键拿评测集里的真实提问来试。
 */
function TokensCard({
  remote,
  onAnalyze,
  evalSet,
}: {
  remote: Remote<TokensResponse>
  onAnalyze: (text: string) => void
  evalSet: Remote<EvalSetResponse>
}) {
  const [text, setText] = useState('虚拟线程在高并发下的吞吐量提升了多少')

  // 从评测集里挑几条真实提问当快捷入口：手打容易打成"很规范"的问法，
  // 而检索真正的难点恰恰是口语化、词汇不匹配的提问
  const samples = evalSet.status === 'ok' ? evalSet.data.cases.slice(0, 6) : []

  return (
    <Card size="small" className="diag-card diag-card-wide" title="分词诊断：这句话被切成了什么">
      <Flex vertical gap={10}>
        <Flex gap={8} wrap>
          <Input
            value={text}
            onChange={e => setText(e.target.value)}
            onPressEnter={() => onAnalyze(text)}
            placeholder="输入一段提问（Enter 分析）"
            className="rag-input"
          />
          <Button type="primary" onClick={() => onAnalyze(text)} loading={remote.status === 'loading'}>
            分析
          </Button>
        </Flex>

        {samples.length > 0 && (
          <Flex gap={6} wrap align="center">
            <Typography.Text type="secondary">试试评测集里的真实提问：</Typography.Text>
            {samples.map(c => (
              <Tag
                key={c.id}
                className="rag-tag-clickable"
                onClick={() => {
                  setText(c.question)
                  onAnalyze(c.question)
                }}
              >
                {c.id}
              </Tag>
            ))}
          </Flex>
        )}

        {remote.status === 'error' && <InlineError title="分词失败" message={remote.error} />}
        {remote.status === 'ok' && (
          <>
            <Flex gap={8} wrap align="center">
              <Typography.Text type="secondary">共 {remote.data.tokenCount} 个 token：</Typography.Text>
              {remote.data.tokens.map((token, i) => (
                // 用下标当 key 是安全的：token 列表整体替换，不存在增删导致的重用错位
                <span key={`${token}-${i}`} className="rag-token">
                  {token}
                </span>
              ))}
            </Flex>
            <Typography.Text type="secondary" className="diag-note">
              {remote.data.note}
            </Typography.Text>
          </>
        )}
      </Flex>
    </Card>
  )
}

// ---------------------------------------------------------------------------
//  4. 单条提问的召回
// ---------------------------------------------------------------------------

/**
 * 召回结果这一块的读法：`名次 + 分数 + 归属章节`。
 *
 * **为什么不显示分数高低就完事**：排查"召回错了"时，真正需要知道的是
 * "它把哪个章节的块捞上来了" —— 分数只说明排序，不说明对错。
 * 所以面包屑（归属章节）是这个表里最重要的一列。
 */
function RetrieveCard({
  remote,
  lastQuery,
  onSearch,
  evalSet,
}: {
  remote: Remote<RetrieveResponse>
  lastQuery: string
  onSearch: (q: string) => void
  evalSet: Remote<EvalSetResponse>
}) {
  const [query, setQuery] = useState('怎么防止块被从中间切断')

  // 取几条不同分组的提问，便于看出"哪一类问题检索得更差"
  const samples = evalSet.status === 'ok' ? evalSet.data.cases.slice(0, 6) : []

  return (
    <Card size="small" className="diag-card diag-card-wide" title="召回：这条提问捞回了哪些块">
      <Flex vertical gap={10}>
        <Flex gap={8} wrap>
          <Input
            value={query}
            onChange={e => setQuery(e.target.value)}
            onPressEnter={() => onSearch(query)}
            placeholder="输入一条提问（Enter 检索）"
            className="rag-input"
          />
          <Button type="primary" onClick={() => onSearch(query)} loading={remote.status === 'loading'}>
            检索
          </Button>
        </Flex>

        {samples.length > 0 && (
          <Flex gap={6} wrap align="center">
            <Typography.Text type="secondary">快捷提问：</Typography.Text>
            {samples.map(c => (
              <Tag
                key={c.id}
                className="rag-tag-clickable"
                onClick={() => {
                  setQuery(c.question)
                  onSearch(c.question)
                }}
              >
                {c.id}
              </Tag>
            ))}
          </Flex>
        )}

        {remote.status === 'error' && (
          <InlineError title={`检索「${lastQuery}」失败`} message={remote.error} />
        )}
        {remote.status === 'ok' && <RetrieveBody data={remote.data} />}
      </Flex>
    </Card>
  )
}

function RetrieveBody({ data }: { data: RetrieveResponse }) {
  return (
    <Flex vertical gap={10}>
      <Typography.Text type="secondary" className="diag-note">
        提问 <code>{data.query}</code> 在 {data.indexedChunks} 个块里召回了 {data.hitCount} 条
        （策略 <code>{data.strategy}</code>）。提问本身被切成：
        {data.queryTokens.map((t, i) => (
          <span key={`${t}-${i}`} className="rag-token">
            {t}
          </span>
        ))}
      </Typography.Text>

      {data.hits.length === 0 ? (
        <Empty description="一条都没召回 —— 先看上面的分词，多半是提问与文档切出来的 token 对不上" />
      ) : (
        <table className="rag-hit-table">
          <thead>
            <tr>
              <th>名次</th>
              <th>分数</th>
              {/* 这一列最重要：排查"召回错了"时看的就是它 */}
              <th>归属章节</th>
              <th>token</th>
              <th>开头</th>
            </tr>
          </thead>
          <tbody>
            {data.hits.map(hit => (
              <tr key={hit.chunkId}>
                <td className="is-rank">{hit.rank}</td>
                <td className="is-mono">{hit.score.toFixed(3)}</td>
                <td className="is-breadcrumb">{hit.breadcrumb || <span className="is-dim">（无标题路径）</span>}</td>
                <td className="is-mono">{hit.tokens}</td>
                <td className="is-excerpt">{hit.excerpt}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  5. 分块策略对照 —— 这张页面存在的原因
// ---------------------------------------------------------------------------

function ComparisonCard({
  remote,
  onRun,
  running,
}: {
  remote: Remote<CompareResponse>
  onRun: () => void
  running: boolean
}) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="分块策略对照：阶段二的验收数字"
      extra={
        <Button type="primary" size="small" onClick={onRun} loading={running}>
          跑对照
        </Button>
      }
    >
      {remote.status === 'idle' && (
        <Typography.Text type="secondary">
          点「跑对照」会在后端真跑一遍全量评测：把语料分别按两种策略切分、各建一份索引、
          再用 43 条用例逐条检索并算指标。本机实测是秒级，但它确实是"跑一遍"而不是读缓存。
        </Typography.Text>
      )}
      {remote.status === 'loading' && (
        <LoadingLine text="正在跑全量对照评测（分块 + 建索引 + 43 条用例 × 2 个策略）……" />
      )}
      {remote.status === 'error' && <InlineError title="对照评测失败" message={remote.error} />}
      {remote.status === 'ok' && <ComparisonBody data={remote.data} />}
    </Card>
  )
}

function ComparisonBody({ data }: { data: CompareResponse }) {
  const baseline = data.strategies[0]
  const other = data.strategies[1]

  return (
    <Flex vertical gap={14}>
      <div className="stat-grid">
        <Stat label="用例数" value={data.caseCount} accent={data.sampleSufficient} />
        <Stat label="语料文档" value={data.documents} />
        <Stat label="策略数" value={data.strategies.length} />
        <Stat label="样本量达标" value={data.sampleSufficient ? '是' : '否'} />
      </div>

      {/*
        主表：把 Recall / MRR 与 token 成本**并排**放。
        分开放是危险的 —— 只看到 Recall 会得出"应该选分块大的"，
        只看到 token 会得出"应该切得越小越好"，两者都是错的。
      */}
      <div className="rag-table-scroll">
        <table className="rag-metric-table">
          <thead>
            <tr>
              <th>策略</th>
              <th>块数</th>
              <th>平均 token</th>
              <th>最大 token</th>
              <th>Recall@5</th>
              <th>Recall@10</th>
              <th>MRR</th>
              <th>前 3 条 token</th>
              <th>全部候选 token</th>
            </tr>
          </thead>
          <tbody>
            {data.strategies.map(s => (
              <tr key={s.strategy} className={s.strategy === baseline?.strategy ? 'is-baseline' : ''}>
                <td className="is-strategy">
                  {s.strategy}
                  {s.strategy === baseline?.strategy && <Tag className="rag-role-tag">对照组</Tag>}
                </td>
                <td className="is-mono">{s.chunkCount}</td>
                <td className="is-mono">{s.avgChunkTokens}</td>
                <td className="is-mono">{s.maxChunkTokens}</td>
                <td className="is-mono is-key">{fmt(s.metrics.recallAt['5'])}</td>
                <td className="is-mono">{fmt(s.metrics.recallAt['10'])}</td>
                <td className="is-mono is-key">{fmt(s.metrics.mrr)}</td>
                {/* 这两个是最容易被忽略、却决定成本的两列 */}
                <td className="is-mono is-cost">{Math.round(s.metrics.firstThreeHitsTokens)}</td>
                <td className="is-mono is-cost">{Math.round(s.metrics.topKTokens)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/*
        差值表：指标是相对的，所以必须并排给出"提升了多少"。
        这条也是验收口径的原话。单位写清是**百分点**（不是百分比）——
        0.7674 → 0.8140 说成 "+4.66 个百分点"是准确的，说成 "+6%" 会被误读成 6 个百分点。
      */}
      {baseline && other && (
        <div>
          <Typography.Title level={5} className="rag-subtitle">
            差值（百分点，相对 <code>{baseline.strategy}</code>）
          </Typography.Title>
          <Flex gap={10} wrap>
            {DELTA_ROWS.map(({ key, label, higherIsBetter }) => {
              const delta = data.deltas[key]?.[other.strategy]
              if (delta === undefined) return null
              return (
                <div key={key} className="rag-delta">
                  <div className="rag-delta-label">{label}</div>
                  <div className={`rag-delta-value ${deltaClass(delta, higherIsBetter)}`}>
                    {delta > 0 ? '+' : ''}
                    {delta.toFixed(2)}
                  </div>
                </div>
              )
            })}
          </Flex>
          <Typography.Text type="secondary" className="diag-note">
            绿色表示对 <code>{other.strategy}</code> 有利。注意「平均 token」这类指标是<strong>越低越好</strong>，
            所以它的颜色方向与 Recall 相反 —— 这正是为什么每个数字都要标明方向，不能统一按"涨了就是好"上色。
          </Typography.Text>
        </div>
      )}

      {/*
        解读区：把"这份数字能证明什么 / 不能证明什么"直接写在界面上。
        这不是免责声明 —— 0.7674 与 0.8140 摆在一起，任何人都会自行脑补一个方向性结论，
        而**块大小这个混杂因素没排除**（块越大越容易碰到答案）。
        不写出来，这个界面就在制造错误结论。
      */}
      <Alert
        type="info"
        showIcon
        title="怎么读这两个数（别只看 Recall）"
        description={
          <Flex vertical gap={6}>
            <span>
              <strong>1. 换分块策略并没有提升召回率。</strong>
              这组数字里结构分块的 Recall 通常低于对照组，而且后端把 max-tokens 从 300 扫到 800
              都低于对照组 —— 换参数救不回来，说明这是这份语料上的真实性质，不是没调好。
            </span>
            <span>
              <strong>2. 它真正省的是 token。</strong>
              看「前 3 条 token」那一列（那才是真正进上下文的量）与「全部候选 token」那一列。
            </span>
            <span>
              <strong>3. 不能说"语义分块更好"或"更差"。</strong>
              相关性判定用的是「检索到的块有没有覆盖答案所在章节」，而<strong>块越大越容易碰到答案</strong>
              —— 对照组平均块更大，所以这个 Recall 差异里混着块大小的差异。
              要下结论，必须先做"块大小对齐"的实验。
            </span>
          </Flex>
        }
      />

      {other && <DeltaChart baseline={baseline} other={other} />}

      <PerTagTable data={data} />

      <MissesList data={data} />

      {data.failedDocuments.length > 0 && (
        <Alert
          type="warning"
          showIcon
          title="有语料加载失败 —— 分数下降可能不是分块的问题"
          description={data.failedDocuments.join('、')}
        />
      )}

      {data.notes.length > 0 && (
        <div>
          <Typography.Title level={5} className="rag-subtitle">
            报告备注（后端给的，含两类会改变结论的警告）
          </Typography.Title>
          <ul className="issue-list">
            {data.notes.map((n, i) => (
              <li key={i}>{n}</li>
            ))}
          </ul>
        </div>
      )}
    </Flex>
  )
}

/** 差值表要显示哪些指标，以及"越大越好"还是"越小越好" */
const DELTA_ROWS: { key: string; label: string; higherIsBetter: boolean }[] = [
  { key: 'recall@1', label: 'Recall@1', higherIsBetter: true },
  { key: 'recall@3', label: 'Recall@3', higherIsBetter: true },
  { key: 'recall@5', label: 'Recall@5', higherIsBetter: true },
  { key: 'recall@10', label: 'Recall@10', higherIsBetter: true },
  { key: 'mrr', label: 'MRR', higherIsBetter: true },
  { key: 'firstThreeHitsTokens', label: '前 3 条 token', higherIsBetter: false },
  { key: 'chunkCount', label: '块数', higherIsBetter: false },
]

/**
 * 差值的颜色方向。
 *
 * 刻意不写成"涨了就变绿"：token 类指标涨了是坏事。
 * 一个统一的"涨=好"配色会稳定地把读者引向错误结论，
 * 所以方向必须由每个指标自己声明（见 DELTA_ROWS）。
 * 差值为 0 时不加颜色 —— "没有变化"不是好消息也不是坏消息。
 */
function deltaClass(delta: number, higherIsBetter: boolean): string {
  if (Math.abs(delta) < 1e-9) return 'is-flat'
  const good = higherIsBetter ? delta > 0 : delta < 0
  return good ? 'is-good' : 'is-bad'
}

/** Recall 与成本的取舍图：同一张图里看"多花的 token 换回了多少召回" */
function DeltaChart({ baseline, other }: { baseline: StrategyReport; other: StrategyReport }) {
  const maxTokens = Math.max(baseline.metrics.topKTokens, other.metrics.topKTokens, 1)
  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        Recall@5 与 token 成本的取舍
      </Typography.Title>
      {[baseline, other].map(s => (
        <div key={s.strategy} className="rag-tradeoff-row">
          <div className="rag-tradeoff-name">{s.strategy}</div>
          <div className="rag-tradeoff-bars">
            <div className="rag-tradeoff-bar">
              <span className="rag-tradeoff-cap">Recall@5</span>
              <span className="rag-bar-track">
                <span
                  className="rag-bar is-recall"
                  style={{ width: `${Math.round((s.metrics.recallAt['5'] ?? 0) * 100)}%` }}
                />
              </span>
              <span className="rag-tradeoff-num">{fmt(s.metrics.recallAt['5'])}</span>
            </div>
            <div className="rag-tradeoff-bar">
              <span className="rag-tradeoff-cap">候选 token</span>
              <span className="rag-bar-track">
                <span
                  className="rag-bar is-cost"
                  style={{ width: `${Math.round((s.metrics.topKTokens / maxTokens) * 100)}%` }}
                />
              </span>
              <span className="rag-tradeoff-num">{Math.round(s.metrics.topKTokens)}</span>
            </div>
          </div>
        </div>
      ))}
      <Typography.Text type="secondary" className="diag-note">
        两条 bar 的量纲不同（一个是 0~1 的比例，一个是 token 数按最大值归一），
        所以它们**只能各自纵向对比**，不能横向比长短 —— 混着比会得出没有意义的结论。
      </Typography.Text>
    </div>
  )
}

/** 分组表现：总平均会把"某一类问题全军覆没"藏起来 */
function PerTagTable({ data }: { data: CompareResponse }) {
  const tags = Array.from(new Set(data.strategies.flatMap(s => Object.keys(s.metrics.perTag))))
  if (tags.length === 0) return null

  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        分组表现（看哪一类问题最差）
      </Typography.Title>
      <div className="rag-table-scroll">
        <table className="rag-metric-table">
          <thead>
            <tr>
              <th>分组</th>
              {data.strategies.map(s => (
                <th key={s.strategy} colSpan={3}>
                  {s.strategy}
                </th>
              ))}
            </tr>
            <tr>
              <th />
              {data.strategies.map(s => (
                <Fragment key={`${s.strategy}-head`}>
                  <th>用例</th>
                  <th>Recall@5</th>
                  <th>MRR</th>
                </Fragment>
              ))}
            </tr>
          </thead>
          <tbody>
            {tags.map(tag => (
              <tr key={tag}>
                <td className="is-strategy">{tag}</td>
                {data.strategies.map(s => {
                  const t = s.metrics.perTag[tag]
                  // 用 Fragment 并带上 key：map 返回的兄弟节点必须有 key，
                  // 否则 React 会警告"列表缺少 key"—— 而自检脚本断言控制台必须干净，
                  // 这类警告会让自检挂掉（这正是它存在的意义之一）
                  if (!t) {
                    return (
                      <Fragment key={`${s.strategy}-${tag}`}>
                        <td className="is-dim">-</td>
                        <td className="is-dim">-</td>
                        <td className="is-dim">-</td>
                      </Fragment>
                    )
                  }
                  return (
                    <Fragment key={`${s.strategy}-${tag}`}>
                      <td className="is-mono">{t.caseCount}</td>
                      <td className="is-mono is-key">{fmt(t.recallAt5)}</td>
                      <td className="is-mono">{fmt(t.mrr)}</td>
                    </Fragment>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/**
 * 完全未命中的用例清单。
 *
 * 这是整张报告里最该被人看的部分：总指标说"提升了多少"，
 * 而这份清单说"具体是哪些问题还答不出来" —— 后者才能指导下一步做什么。
 */
function MissesList({ data }: { data: CompareResponse }) {
  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        完全未命中的用例（该看的部分）
      </Typography.Title>
      {data.strategies.map(s => (
        <div key={s.strategy} className="rag-miss-group">
          <Typography.Text type="secondary">
            <code>{s.strategy}</code> 未命中 {s.metrics.misses.length} 条：
          </Typography.Text>
          {s.metrics.misses.length === 0 ? (
            <Typography.Text type="secondary">（全部命中）</Typography.Text>
          ) : (
            <ul className="issue-list">
              {s.metrics.misses.map((m: CaseOutcome) => (
                <li key={m.caseId}>
                  <Tag>{m.caseId}</Tag>
                  <Tag>{m.tag}</Tag>
                  {m.question}
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
//  6. 评测集
// ---------------------------------------------------------------------------

function EvalSetCard({ remote, onReload }: { remote: Remote<EvalSetResponse>; onReload: () => void }) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="评测集：43 条标注用例"
      extra={
        <Button size="small" onClick={onReload} loading={remote.status === 'loading'}>
          重新加载
        </Button>
      }
    >
      {remote.status === 'loading' && <LoadingLine text="正在加载评测集……" />}
      {remote.status === 'error' && <InlineError title="评测集加载失败" message={remote.error} />}
      {remote.status === 'ok' && <EvalSetBody data={remote.data} />}
    </Card>
  )
}

function EvalSetBody({ data }: { data: EvalSetResponse }) {
  return (
    <Flex vertical gap={12}>
      <Flex gap={12} wrap align="center">
        <Tag color={data.sampleSufficient ? 'green' : 'orange'}>
          共 {data.caseCount} 条（门槛 {data.minUsableCases} 条）
        </Tag>
        {Object.entries(data.byTag).map(([tag, count]) => (
          <Tag key={tag}>
            {tag} {count}
          </Tag>
        ))}
      </Flex>

      {!data.sampleSufficient && (
        <Alert
          type="warning"
          showIcon
          title="样本量不足：这份结果可以用来调参，但不能作为结论引用"
          description="路线文档的原话是「别只问两三个问题觉得还行」—— 样本太少时指标会剧烈波动。"
        />
      )}

      <Table
        size="small"
        rowKey="id"
        pagination={{ pageSize: 10, size: 'small' }}
        dataSource={data.cases}
        columns={[
          { title: '编号', dataIndex: 'id', width: 70 },
          { title: '分组', dataIndex: 'tag', width: 100 },
          { title: '提问', dataIndex: 'question', ellipsis: true },
          {
            title: '标准答案章节',
            dataIndex: 'matchHeadings',
            width: 220,
            render: (headings: string[]) => (
              <Flex gap={4} wrap>
                {headings.map(h => (
                  <Tag key={h} className="rag-type-tag">
                    {h}
                  </Tag>
                ))}
              </Flex>
            ),
          },
          { title: '语料', dataIndex: 'source', width: 170, ellipsis: true },
        ]}
      />
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  小部件
// ---------------------------------------------------------------------------

function Stat({ label, value, accent }: { label: string; value: string | number; accent?: boolean }) {
  return (
    <div className={`stat-cell ${accent ? 'is-accent' : ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  )
}

function LoadingLine({ text }: { text: string }) {
  return (
    <Flex align="center" gap={10} className="diag-loading">
      <Spin size="small" />
      <Typography.Text type="secondary">{text}</Typography.Text>
    </Flex>
  )
}

/** 内联错误：每个部件自己承担"失败了"的表达，不让失败静默变成空白 */
function InlineError({ title, message }: { title: string; message: string }) {
  return <Alert type="error" showIcon title={title} description={message} />
}

/** 指标显示：保留 4 位小数，与后端报告一致（2 位会把 0.62→0.65 这种真实提升抹平） */
function fmt(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(4)
}
