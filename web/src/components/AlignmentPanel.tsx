import { Alert, Button, Collapse, Flex, Spin, Tag, Typography } from 'antd'
import type { AlignmentResponse, BudgetAlignment, PairComparison, StrategyReport, SweepRow } from '../api/rag'
import type { Remote } from '../types'

/**
 * 「块大小对齐实验」面板 —— **把 `/compare` 那个混杂因素消掉之后的对照**。
 *
 * <h2>这一块为什么必须存在（否则上面那张表在制造错误结论）</h2>
 * `ComparisonCard` 里那张对照表量出"结构分块 Recall 更低、更省 token"，
 * 但那两组**平均块大小不同**（232.5 vs 200.6 token），而本项目的相关性判定是
 * 「检索到的块有没有覆盖答案所在章节」—— **块越大越容易覆盖答案**。
 * 于是那 4.66 个百分点的差距里，多少来自"切点选在哪"、多少来自"块更大"，
 * 光看那张表是分不开的。这份报告就是来分开它们的。
 *
 * <h2>它承载的结论与上一张表相反，这是刻意的</h2>
 * 把对照组尺寸二分收敛到与实验组同大小（**202.8 vs 202.4，差 0.41 token**）之后：
 * **固定字数硬切全面占优**，而且**三路检索下方向一致**。
 * 界面必须把这个方向如实呈现 —— 本项目的一条判断是「不让『我做的那个语义分块』自动赢」。
 *
 * <h2>三个容易读错的地方，都在界面上直接写了</h2>
 * <ol>
 *   <li><b>第 4–6 组（带面包屑那三组）不能用来判断"切点"</b>：它们对齐的是**渲染后**的
 *       块大小，结构分块的正文仍然比对照组小约 40 token，混杂因素只是换了个地方待着。
 *       这三组的作用恰恰是**反衬**第 1–3 组为什么干净。</li>
 *   <li><b>向量那一路是离线哈希近似品，不是语义嵌入</b>：它的绝对水平只有词法的一半，
 *       拿它说"向量检索更差"是错的。而且它**恰好能支撑**本报告要问的那个问题
 *       （"换了度量方式之后，分块策略的结论变了没有"）。</li>
 *   <li><b>等预算表只有词法那一路有数</b>：后端的 `budgetAlignment` 只对词法计算，
 *       向量/混合那两路预算表为空。**这不是"界面没渲染出来"** ——
 *       所以这里直接把这件事写在表头旁边，避免把"设计如此"看成"功能坏了"。</li>
 * </ol>
 */
export function AlignmentCard({
  remote,
  onRun,
  aligning,
  comparisonRunning,
}: {
  remote: Remote<AlignmentResponse>
  onRun: () => void
  aligning: boolean
  comparisonRunning: boolean
}) {
  return (
    <div className="align-card">
      <div className="align-head">
        <div>
          <Typography.Title level={5} className="align-title">
            块大小对齐实验：把混杂因素消掉之后，结论反了
          </Typography.Title>
          <Typography.Text type="secondary" className="diag-note">
            上面那张对照表里两组平均块大小不同（232.5 vs 200.6 token），而判定标准是
            「检索到的块有没有覆盖答案所在章节」—— <strong>块越大越容易覆盖答案</strong>。
            这一份报告把对照组的尺寸<strong>二分收敛</strong>到与实验组同大小，再重跑一遍：
            差异就只能来自「切点选在哪」了。
          </Typography.Text>
        </div>
        <Button
          type="primary"
          size="small"
          onClick={onRun}
          loading={aligning}
          // 与「跑对照」互斥（理由见 useRagDiag.runAlignment）：
          // 两个都会把后端 CPU 打满，并行跑只会让两边的数字互相影响
          disabled={comparisonRunning}
        >
          {remote.status === 'ok' ? '重跑对齐实验' : '跑对齐实验'}
        </Button>
      </div>

      {remote.status === 'idle' && (
        <Typography.Text type="secondary">
          进入本面板时会自动跑一次（后端实测 4.95 秒 / 185KB）。它比「跑对照」重约 4 倍：
          两次尺寸扫描 + 两次二分收敛（各十余个探针）+ <strong>5 个变体 × 3 路检索 = 15 次全量评测</strong>。
          点右上角可以重跑。
        </Typography.Text>
      )}
      {remote.status === 'loading' && (
        <Flex align="center" gap={10} className="diag-loading">
          <Spin size="small" />
          <Typography.Text type="secondary">
            正在跑对齐实验（尺寸扫描 → 二分收敛 → 5 个变体 × 3 路检索 × 43 条用例）……
          </Typography.Text>
        </Flex>
      )}
      {remote.status === 'error' && (
        <Alert type="error" showIcon title="对齐实验失败" description={remote.error} />
      )}
      {remote.status === 'ok' && <AlignmentBody data={remote.data} />}
    </div>
  )
}

function AlignmentBody({ data }: { data: AlignmentResponse }) {
  // 按检索方式分组：7 组对照里 3+3+1，直接铺开会让人看不出"哪三组是一对"
  const groups = groupPairs(data.pairs)

  return (
    <Flex vertical gap={16}>
      {/* ---- 概览 ---- */}
      <div className="stat-grid">
        <div className="stat-cell is-accent">
          <div className="stat-label">对齐目标</div>
          <div className="stat-value">{data.alignTargetTokens.toFixed(1)}</div>
        </div>
        <div className="stat-cell is-accent">
          <div className="stat-label">收敛后实测</div>
          <div className="stat-value">{data.alignedMeasuredAvg.toFixed(1)}</div>
        </div>
        <div className="stat-cell">
          <div className="stat-label">对齐残差（token）</div>
          <div className="stat-value">{fmtSigned(data.alignedGapTokens, 2)}</div>
        </div>
        <div className="stat-cell">
          <div className="stat-label">对照组分块尺寸</div>
          <div className="stat-value">{data.alignedFixedSize} 字</div>
        </div>
        <div className="stat-cell">
          <div className="stat-label">用例 / 语料</div>
          <div className="stat-value">
            {data.caseCount} / {data.documents}
          </div>
        </div>
        <div className="stat-cell">
          <div className="stat-label">二分探针</div>
          <div className="stat-value">{data.refinementProbes}</div>
        </div>
      </div>

      <Typography.Text type="secondary" className="diag-note">
        「对齐」永远是近似的：残差 <strong>{fmtSigned(data.alignedGapTokens, 2)} token</strong>
        （占目标的 {pctOfTarget(data.alignedGapTokens, data.alignTargetTokens)}）。
        这个差够不够小<strong>要由读者自己判断</strong>，不能由实现方默认成立 ——
        所以它单独占一格，而不是藏在备注里。
      </Typography.Text>

      {/* ---- 主表：7 组对照，按检索方式分块 ---- */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          主表：每一路检索下的对齐对照（共 {data.pairs.length} 组）
        </Typography.Title>
        {groups.map(g => (
          <PairGroup key={g.label} group={g} />
        ))}
      </div>

      <ConvergenceBlock data={data} />

      <BudgetTable data={data} />

      <NotesBlock notes={data.notes} />

      {data.failedDocuments.length > 0 && (
        <Alert
          type="warning"
          showIcon
          title="有语料加载失败 —— 分数变化可能不是分块的问题"
          description={data.failedDocuments.join('、')}
        />
      )}
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  主表：一组 = 一路检索下的若干对齐对照
// ---------------------------------------------------------------------------

interface PairGroup {
  label: string
  pairs: PairComparison[]
}

/**
 * 把 7 组对照按检索方式归堆。
 *
 * 归类依据是 `fieldDescription` 里的「检索=xxx」标记 —— 那是后端拼进去的
 * （see RagAlignmentService.pair）。**从字符串里解析**这件事值得说明一下：
 * 报告本身没有单独的 "retriever" 字段，只有这一个可读名。
 * 与其在前端硬编码"前 3 组是词法"（一变就错），不如按名字归堆；
 * 万一后端改了命名，这里会退化成"每组一列"仍然正确显示，只是分组没那么好看。
 *
 * ⚠️ 这里**刻意不判断"这一组能不能用来判断切点"**。第一版在组级做了这个判断，
 * 结果 vector / hybrid 两个分组被整体打上"不能单独判断切点"的标签 —— 而它们的**第一组**
 * 恰恰就是最干净的那一组（关掉面包屑）。根因：一个分组里混着两种性质的对照
 * （对齐正文的 + 对齐渲染后大小的），只要有一组带警告就污染了整个分组。
 * **能/不能判断切点是"每一组对照"的属性，不是"每一路检索"的属性** ——
 * 所以标签挪到了每一行上（见 PairRows）。
 */
function groupPairs(pairs: PairComparison[]): PairGroup[] {
  const order: string[] = []
  const grouped = new Map<string, PairComparison[]>()
  for (const p of pairs) {
    const label = retrieveLabel(p.fieldDescription)
    if (!grouped.has(label)) {
      grouped.set(label, [])
      order.push(label)
    }
    grouped.get(label)!.push(p)
  }
  return order.map(label => ({ label, pairs: grouped.get(label)! }))
}

/** 从 fieldDescription 里取「检索=xxx」 */
function retrieveLabel(fieldDescription: string): string {
  const m = /检索=([^｜|]+)/.exec(fieldDescription)
  return m ? m[1].trim() : '未标注检索方式'
}

/**
 * 这一组对照对齐的是什么 —— 决定它**能不能**用来判断「切点」。
 *
 * 判据就是后端的字段描述里有没有「关掉面包屑」：关掉之后对齐的是**正文**大小，
 * 混杂因素被真正按住；不关掉时对齐的是**渲染后**大小，正文仍然更小。
 */
function isDefinitive(pair: PairComparison): boolean {
  return pair.fieldDescription.includes('关掉面包屑')
}

function PairGroup({ group }: { group: PairGroup }) {
  return (
    <div className="align-group">
      <Flex gap={8} align="center" wrap className="align-group-head">
        <Tag color="blue">检索 = {group.label}</Tag>
        <Typography.Text type="secondary">{group.pairs.length} 组对照</Typography.Text>
      </Flex>

      <div className="rag-table-scroll">
        <table className="rag-metric-table">
          <thead>
            <tr>
              {/* 方向定死：左＝参照方、右＝被考察方，差值一律"右 − 左"（后端契约） */}
              <th>对照（左 → 右）</th>
              <th>块数</th>
              <th>平均 token</th>
              <th>全语料 token</th>
              <th>重复率</th>
              <th>Recall@5</th>
              <th>Recall@10</th>
              <th>MRR</th>
              <th>前 3 条 token</th>
              <th>全部候选 token</th>
              <th>ΔRecall@5</th>
              <th>ΔMRR</th>
              <th>Δ前 3 条 token</th>
              <th>Δ重复率</th>
            </tr>
          </thead>
          <tbody>
            {group.pairs.map(p => (
              <PairRows key={p.fieldDescription} pair={p} />
            ))}
          </tbody>
        </table>
      </div>

      {/* 每一组自己的注意事项 —— 后端明确要求"结论的边界必须跟数字一起出现" */}
      {group.pairs.map(p => (
        <details key={`${p.fieldDescription}-notes`} className="align-details">
          <summary>
            {shortField(p.fieldDescription)} 的注意事项（{p.notes.length} 条）
          </summary>
          <ul className="issue-list">
            {p.notes.map((n, i) => (
              <li key={i}>{n}</li>
            ))}
          </ul>
        </details>
      ))}
    </div>
  )
}

/**
 * 一组对照渲染成两行：参照方 / 被考察方，**并排**。
 *
 * 两行都必须出现 —— 只显示一行就等于没做对照，而这正是这张报告存在的理由。
 * 后一行同时带上"右 − 左"的差值列，颜色按**每个指标自己的方向**上色
 * （token 类涨了是坏事，与 Recall 相反）。
 */
function PairRows({ pair }: { pair: PairComparison }) {
  const [left, right] = pair.reports
  if (!left || !right) {
    // 契约上恒为两项；真出现别的形态时明确说出来，而不是渲染半张表
    return (
      <tr>
        <td colSpan={14} className="is-dim">
          这一组没有按「参照方 + 被考察方」两项返回：{pair.fieldDescription}
        </td>
      </tr>
    )
  }

  const deltaOf = (metric: string): number | undefined => pair.deltas[metric]?.[right.strategy]
  const definitive = isDefinitive(pair)

  return (
    <>
      <tr className="align-row-ref">
        <td className="is-strategy">
          <Tag className="rag-role-tag">左 · 参照方</Tag>
          {/*
            这个标签是**每一组对照自己的属性**，不是整个分组的：
            同一个检索方式下既有"对齐正文"的干净对照，也有"对齐渲染后大小"的锚点。
            第一版把它打在分组表头上，于是 vector / hybrid 两个分组被整体标成
            "不能判断切点" —— 而它们的第一组恰恰是最干净的那一组。
          */}
          {definitive ? (
            <Tag color="green" className="rag-role-tag">
              在比「切点」
            </Tag>
          ) : (
            <Tag color="orange" className="rag-role-tag">
              对齐的是渲染后大小
            </Tag>
          )}
          <br />
          {left.strategy}
          <Typography.Text type="secondary" className="align-desc">
            {shortField(pair.fieldDescription, '左=')}
          </Typography.Text>
        </td>
        <MetricCells report={left} />
        <td className="is-dim" colSpan={4}>
          （差值的基准）
        </td>
      </tr>
      <tr className="align-row-subject">
        <td className="is-strategy">
          <Tag color="purple" className="rag-role-tag">
            右 · 被考察方
          </Tag>
          <br />
          {right.strategy}
        </td>
        <MetricCells report={right} />
        <DeltaCell value={deltaOf('recall@5')} higherIsBetter />
        <DeltaCell value={deltaOf('mrr')} higherIsBetter />
        <DeltaCell value={deltaOf('firstThreeHitsTokens')} />
        <DeltaCell value={deltaOf('duplicationRatio')} />
      </tr>
    </>
  )
}

/** 一行里那几个"绝对值"列 */
function MetricCells({ report }: { report: StrategyReport }) {
  return (
    <>
      <td className="is-mono">{report.chunkCount}</td>
      <td className="is-mono">{report.avgChunkTokens.toFixed(1)}</td>
      <td className="is-mono is-cost">{report.tokenCost.totalTokens}</td>
      <td className="is-mono is-cost">{fmtRatio(report.tokenCost.duplicationRatio)}</td>
      <td className="is-mono is-key">{fmt(report.metrics.recallAt['5'])}</td>
      <td className="is-mono">{fmt(report.metrics.recallAt['10'])}</td>
      <td className="is-mono is-key">{fmt(report.metrics.mrr)}</td>
      <td className="is-mono is-cost">{Math.round(report.metrics.firstThreeHitsTokens)}</td>
      <td className="is-mono is-cost">{Math.round(report.metrics.topKTokens)}</td>
    </>
  )
}

/**
 * 差值单元格。
 *
 * `higherIsBetter` **必须由每个指标自己声明**，不能统一按"涨了就是好"上色 ——
 * 这张表里 Recall/MRR 涨了是好事，token 成本与重复率涨了是坏事。
 * 一个统一方向的配色会稳定地把读者引向错误结论（这条在 `/compare` 那张表上已经踩过）。
 *
 * ⚠️ 只渲染**后端真的给了差值的指标**。第一版写了一个「Δ全语料 token」列，
 * 而 `deltasBetween` 根本没算这个键（它算的是 `firstThreeHitsTokens` / `topKTokens` /
 * `duplicationRatio`）—— 于是那一整列在所有 7 行里都是「-」，
 * 看起来像"差值为零"或"数据缺失"，而真相是**这个指标不存在**。
 * 表里永远为空的一列比没有这一列更糟：它把"我没实现"伪装成"结果就是没差别"。
 */
function DeltaCell({ value, higherIsBetter = false }: { value: number | undefined; higherIsBetter?: boolean }) {
  if (value === undefined) {
    // 这一格不该出现（列都是照着后端真有的键选的）。真出现了就直说，别显示成 0
    return <td className="is-dim">未提供</td>
  }
  const flat = Math.abs(value) < 1e-9
  const good = higherIsBetter ? value > 0 : value < 0
  const cls = flat ? 'is-flat' : good ? 'is-good' : 'is-bad'
  // 重复率是个比例（0.0711 = 7.11 个百分点），与 token 数不能同一种写法
  const isRatio = !higherIsBetter && value !== 0 && Math.abs(value) < 1
  const text = isRatio ? `${fmtSigned(value * 100, 2)}pp` : fmtSigned(value, 2)
  return <td className={`is-mono rag-inline-delta ${cls}`}>{text}</td>
}

// ---------------------------------------------------------------------------
//  收敛过程：尺寸扫描表 + 扫到了哪
// ---------------------------------------------------------------------------

/**
 * 尺寸扫描表。
 *
 * 它回答的是**"对齐是怎么收敛的"**，而这正是这份报告最容易被质疑的地方 ——
 * 这条实验的第一版把扫描区间写成了 `target × [0.6, 1.4]`
 * （把 token 数当成了字符数），于是整个区间都在目标以下，
 * 最后选了个 304 字 / 144.6 token 的点，**与目标差 50 token（25%）**，
 * 而报告里照样写着"已对齐"。
 *
 * 所以这张表要让人**一眼看出收敛过程没有乱撞**：最优的一行高亮，
 * 且它必须落在区间中间而不是两端（落在端点上说明真最优在区间外，那一版就是这么错的）。
 */
function ConvergenceBlock({ data }: { data: AlignmentResponse }) {
  const rows = data.sweep
  if (rows.length === 0) return null
  const best = rows.reduce((a, b) => (Math.abs(b.gapToTarget) < Math.abs(a.gapToTarget) ? b : a), rows[0])
  const bestIndex = rows.indexOf(best)
  // 最优点落在两端 = 真最优可能在扫描区间之外。第一版就是这么错的（差 50 token 还说"已对齐"）
  const atEdge = bestIndex === 0 || bestIndex === rows.length - 1

  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        收敛过程：尺寸扫描表（{rows.length} 个点，区间 {data.sweepRange[0]}–{data.sweepRange[1]} 字）
      </Typography.Title>

      {atEdge && (
        <Alert
          type="warning"
          showIcon
          title="最优点落在扫描区间的端点上：真正的最优可能在区间之外"
          description="这正是这条实验第一版的失败形态 —— 区间靠猜（把 token 数当成字符数），导致整个区间都在目标以下，最后选了个差 25% 的点，而报告里照样写着「已对齐」。"
        />
      )}

      <div className="rag-table-scroll">
        <table className="rag-metric-table">
          <thead>
            <tr>
              <th>字符数</th>
              <th>块数</th>
              <th>平均 token</th>
              <th>全语料 token</th>
              <th>重复率</th>
              <th>与目标差</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.fixedSize} className={r === best ? 'is-baseline' : ''}>
                <td className="is-mono">
                  {r.fixedSize}
                  {r === best && <Tag className="rag-role-tag">收敛点</Tag>}
                </td>
                <SweepCells row={r} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Typography.Text type="secondary" className="diag-note">
        收敛点：<code>{best.fixedSize} 字</code> → 实测平均 <code>{best.avgChunkTokens.toFixed(2)} token</code>，
        与目标差 <code>{fmtSigned(best.gapToTarget, 2)}</code> token。
        报告最终采用的对齐组是 <code>{data.alignedFixedSize} 字 / 实测 {data.alignedMeasuredAvg.toFixed(2)} token</code>
        （二分细化又跑了 {data.refinementProbes} 个探针）。
        <strong>判据用的是"实测平均 token"而不是字符数</strong> —— 要控的是 token，
        而"字符数 → token"不是线性关系（中英混排比例、块边界标点、每块的固定包装开销都会让它变）。
      </Typography.Text>
    </div>
  )
}

function SweepCells({ row }: { row: SweepRow }) {
  return (
    <>
      <td className="is-mono">{row.chunkCount}</td>
      <td className="is-mono">{row.avgChunkTokens.toFixed(2)}</td>
      <td className="is-mono is-cost">{row.totalTokens}</td>
      <td className="is-mono is-cost">{fmtRatio(row.duplicationRatio)}</td>
      <td className="is-mono">{fmtSigned(row.gapToTarget, 2)}</td>
    </>
  )
}

// ---------------------------------------------------------------------------
//  轴二：等 token 预算
// ---------------------------------------------------------------------------

/**
 * 等 token 预算对照（轴二）。
 *
 * **为什么光有轴一不够**：按"名次取前 10 条"比，大块那组天然花更多钱 ——
 * "花更多钱换来更高召回"不构成"策略更好"。所以再按固定预算归纳一遍：
 * **同一个预算里谁把答案找回来**，谁才真的划算。
 *
 * **为什么表里必须同时有"用掉多少 token"和"装进几条"**：
 * 后端的预算口径是"装到下一个块会超预算为止"，第一条无条件放行 ——
 * 于是 `avgTokensUsed` 明显低于预算，就是「块太大装不下」的直接证据。
 * 只报 Recall 会把"它根本没花到那么多钱"误读成"它更省"。
 */
function BudgetTable({ data }: { data: AlignmentResponse }) {
  const steps = data.budgetSteps
  const labels = data.budgetLabel
  if (steps.length === 0 || labels.length === 0) return null

  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        轴二 · 等 token 预算：同样的钱谁找得回答案（{steps.length} 档）
      </Typography.Title>

      {/*
        这一条必须写在表旁边：预算表**只有词法那一路有数**（后端只为词法计算
        budgetAlignment，见 RagAlignmentService 第 189 行附近）。不说清楚，
        读者会以为"向量/混合那两路的预算表没渲染出来"，或者更糟 ——
        以为预算结论适用于三种检索方式。
      */}
      <Typography.Text type="secondary" className="diag-note">
        预算档位 <code>{steps.join(' / ')}</code>。口径是「装到下一个块会超预算为止」，
        第一条无条件放行（现实中不会因为单个块太大就一条都不给）。
        ⚠️ <strong>这张表只有词法那一路有数</strong>：后端只为词法计算预算归纳，
        另外两路为空是<strong>设计如此</strong>，不是界面漏渲染 ——
        所以下面这张表里的每一组都来自 BM25。
      </Typography.Text>

      <div className="rag-table-scroll">
        <table className="rag-metric-table">
          <thead>
            <tr>
              <th>预算</th>
              <th>组</th>
              <th>预算内 Recall</th>
              <th>预算内 MRR</th>
              <th>实际用掉 token</th>
              <th>装进几条</th>
            </tr>
          </thead>
          <tbody>
            {steps.map(budget => {
              const row = data.budgetRows[String(budget)]
              if (!row) {
                return (
                  <tr key={budget}>
                    <td className="is-mono">{budget}</td>
                    <td className="is-dim" colSpan={5}>
                      这一档没有数据（后端未返回）
                    </td>
                  </tr>
                )
              }
              const entries = Object.entries(row)
              return entries.map(([label, a], i) => (
                <tr key={`${budget}-${label}`} className={i === 0 ? 'is-baseline' : ''}>
                  {/* 预算只在每组的第一行显示：一档一行预算、下面挂着各组，
                      这样"同一档里横向比"才是视觉上自然的动作 */}
                  <td className="is-mono">{i === 0 ? budget : ''}</td>
                  <td className="is-strategy">{label}</td>
                  <BudgetCells a={a} />
                </tr>
              ))
            })}
          </tbody>
        </table>
      </div>

      <Typography.Text type="secondary" className="diag-note">
        「实际用掉 token」明显低于预算档位就是<strong>块太大装不下</strong>的直接证据 ——
        这也是「把检索预算纳入 Token 账目」那一件要用的口径：
        这里量到的 <code>avgTokensUsed</code> 就是"这次检索真正塞进 prompt 的 token"。
      </Typography.Text>
    </div>
  )
}

function BudgetCells({ a }: { a: BudgetAlignment }) {
  // 预算没装满：装进来的条数明显少于"名次取前 10 条"那个上限
  const notFull = a.avgHitCount < 10 - 0.5
  return (
    <>
      <td className="is-mono is-key">{fmt(a.recall)}</td>
      <td className="is-mono">{fmt(a.mrr)}</td>
      <td className="is-mono is-cost">{Math.round(a.avgTokensUsed)}</td>
      <td className={notFull ? 'is-mono rag-underfilled' : 'is-mono'}>
        {a.avgHitCount.toFixed(1)}
        {notFull && ' ⚠'}
      </td>
    </>
  )
}

// ---------------------------------------------------------------------------
//  报告备注：**不能下的结论**都在这里
// ---------------------------------------------------------------------------

/**
 * 备注区默认展开，不折起来。
 *
 * 这些不是免责声明 —— 其中"向量那一路是离线哈希近似品"这一条**直接决定读者
 * 能不能拿表里的数字做比较**。界面上摆着 vector-local 的 Recall@5 = 0.34，
 * 不解释它是怎么来的，任何人都会拿它去和 BM25 的 0.74 比，然后得出
 * "向量检索差一半"这个**完全错误**的结论（差的是"近似品 vs 真检索"，不是检索方式）。
 */
function NotesBlock({ notes }: { notes: string[] }) {
  if (notes.length === 0) return null
  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        报告备注（{notes.length} 条，含"不能下的结论"）
      </Typography.Title>
      <Collapse
        defaultActiveKey={['notes']}
        size="small"
        items={[
          {
            key: 'notes',
            label: '读之前先看这些 —— 尤其是「向量那一路是什么」',
            children: (
              <ul className="issue-list">
                {notes.map((n, i) => (
                  <li key={i}>{n}</li>
                ))}
              </ul>
            ),
          },
        ]}
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
//  小工具
// ---------------------------------------------------------------------------

/** 与 RagPanel 一致：4 位小数（2 位会把 0.62→0.65 这种真实提升抹平） */
function fmt(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(4)
}

function fmtSigned(value: number, digits: number): string {
  const s = value.toFixed(digits)
  return value > 0 ? `+${s}` : s
}

/** 重复率是个比例（0.1374 = 13.74%）—— 表格里按百分比显示更好读 */
function fmtRatio(value: number): string {
  return `${(value * 100).toFixed(2)}%`
}

function pctOfTarget(gap: number, target: number): string {
  if (!target) return '-'
  return `${((Math.abs(gap) / target) * 100).toFixed(2)}%`
}

/**
 * 把 `fieldDescription` 缩短到只留"这一组对齐的是什么"。
 *
 * 完整字段串长这样（后端拼的）：
 *   `平均块大小 == 202.4 token（结构分块关掉面包屑，正文也对齐） ｜ 检索=lexical-bm25 ｜ 左=… ｜ 右=…`
 * 检索方式与左右两侧的身份已经分别由分组标签和两行行头表达了，
 * 表格单元格里再放一遍只会挤爆这一列。**缩短只影响显示，delta 与结论都用原始字段**。
 */
function shortField(fieldDescription: string, from = ''): string {
  const head = fieldDescription.split('｜')[0].trim()
  if (!from) return head
  const parts = fieldDescription.split('｜').map(s => s.trim())
  const found = parts.find(p => p.startsWith(from))
  return found ?? head
}
