import { Alert, Button, Card, Empty, Flex, Segmented, Spin, Table, Tag, Tooltip, Typography } from 'antd'
import { COST_WINDOWS } from '../hooks/useCost'
import type { CostWindow, UseCostResult } from '../hooks/useCost'
import type { CostGroup, CostPriceRow, CostSummaryResponse, CostToolRow } from '../api/cost'
import type { Remote } from '../types'

/**
 * 「成本看板」面板（阶段四 · 工程化）：**钱花在哪、谁花的、花得值不值**。
 *
 * <h2>这一页要回答的三个问题（其余都是它们的证据）</h2>
 * <ol>
 *   <li><b>花了多少</b> —— 按天的走势与窗口合计；</li>
 *   <li><b>谁花的</b> —— 按<b>租户</b>（谁的钱）、按<b>业务归因键</b>（花在哪个功能上）、
 *       按模型的拆分（「成本归因：按业务/租户/功能拆分」）；</li>
 *   <li><b>花得值不值</b> —— 失败但同样花钱的那部分、缓存命中率、工具调用与延迟。</li>
 * </ol>
 * 第三问是这一页与"账单截图"的区别：账单只知道花了多少，
 * 而「花了钱又没办成事」（失败的运行）只在我们自己的数据里才有。
 *
 * <h2>★ 租户与业务归因是两栏，不是同一栏的两种叫法</h2>
 * 前者回答「谁在花钱」，后者回答「花在哪个功能上」。两栏的金额都是后端按 token 占比
 * <b>从合计分摊</b>来的（费用对 token 是线性的，所以这与逐模型算完再相加等价），
 * 因此两栏各自加起来都等于「按模型」那一栏的和。页面把这件事写在两栏里 ——
 * 不说的话，"这一栏的钱是哪来的"只能靠猜。
 *
 * <h2>本页最重要的一条纪律：**不显示我们算不出来的东西**</h2>
 * 后端给的 `amount` 是可空的，而 null 有两种含义（价格没配 / 模型没上报），
 * 两种都带着 `amountReason`。这一层**绝不允许**把 null 渲染成「¥0.0000」——
 * 那会被读成"这个功能不花钱"，而真相是"我们不知道它花多少钱"。
 * 所以每个金额格都走同一个 {@link Amount} 组件：它能显示数字，也能显示"算不出"与原因。
 *
 * <h2>第二个纪律：结论的边界必须与数字同屏</h2>
 * 后端把「这份数据不能证明什么」写在 `notes` 里（token 不是估算的、默认库是内存库、
 * 工具那一维没有金额…）。这一层把它原样、完整地显示出来，不做删减 ——
 * 一张只有数字的成本表，读者一定会自行脑补出它证明不了的东西。
 */
export function CostPanel({ cost }: { cost: UseCostResult }) {
  const { summary, prices, days } = cost
  const data = summary.status === 'ok' ? summary.data : null

  return (
    <div className="diag-panel">
      <Card
        size="small"
        className="diag-card diag-card-wide cost-head"
        title={
          <Flex gap={12} align="center" wrap>
            <span>成本看板：token 是事实，钱是「token × 单价」</span>
            {data && (
              <Tag color={data.pricingConfigured ? 'green' : 'orange'}>
                {data.pricingConfigured ? `按 ${data.priceTable.length} 条价格规则计价` : '价格未配置'}
              </Tag>
            )}
          </Flex>
        }
        extra={
          <Flex gap={8} align="center">
            <Segmented
              size="small"
              value={days}
              onChange={v => cost.setDays(v as CostWindow)}
              options={COST_WINDOWS.map(d => ({ label: d === 1 ? '今天' : `${d} 天`, value: d }))}
            />
            <Button size="small" onClick={cost.load} disabled={summary.status === 'loading'}>
              刷新
            </Button>
          </Flex>
        }
      >
        {summary.status === 'loading' && (
          <Flex align="center" gap={8}>
            <Spin size="small" />
            <Typography.Text type="secondary">正在汇总（四条 GROUP BY + 一次对账）……</Typography.Text>
          </Flex>
        )}
        {summary.status === 'error' && (
          <Alert type="error" showIcon title="拉取成本看板失败" description={summary.error} />
        )}
        {summary.status === 'idle' && <Empty description="切换到本面板后会自动加载" />}
        {data && <Headline data={data} />}
      </Card>

      {data && <PriceCard data={data} prices={prices} onReload={cost.loadPrices} />}

      {data && <DaysCard data={data} />}

      {data && <TenantCard data={data} />}

      {data && <IntentCard data={data} />}

      {data && <ModelCard data={data} />}

      {data && <ToolCard data={data} />}

      {data && <ReconcileCard data={data} />}

      {data && <NotesCard notes={data.notes} />}
    </div>
  )
}

// ---------------------------------------------------------------------------
//  金额格：**null 不许渲染成 0**
// ---------------------------------------------------------------------------

/**
 * 一个金额格的唯一渲染入口。
 *
 * <h2>为什么值得单独一个组件，而不是各处写 `{row.amount ?? '0'}`</h2>
 * 因为 `?? '0'` 正是那个必须被禁止的写法：它把"我们不知道"和"确实是 0"
 * 渲染成同一个东西。收成一个组件之后，这条纪律只需要在一处成立 ——
 * 而它同时还负责"把原因显示出来"，那是 null 唯一的补救。
 */
function Amount({ row, showReason = false }: { row: CostGroup; showReason?: boolean }) {
  if (row.amount === null || row.amount === undefined) {
    /*
      算不出金额时的渲染。
      【为什么把原因**显示出来**，而不是只放在 Tooltip 里】
      因为"为什么算不出"不是一个补充说明，它就是这一格的全部信息 ——
      而藏在悬浮提示里的信息，只有已经把鼠标放上去的人才知道它存在。
      第一次打开看板的人不会去悬浮一个写着"算不出"的词。
      （顺带一个实测的坑：给 antd 的 <Tooltip> 的直接子元素传 `title`，
      那个 title **不会落到 DOM 上** —— Tooltip 会把它吃掉当自己的内容。
      所以自检里"元素带 title 属性"这条断言当时是红的，而功能其实在。）
    */
    return (
      <span className="cost-amount-unknown-box">
        <Typography.Text type="warning" className="cost-amount-unknown">
          算不出
        </Typography.Text>
        {showReason && row.amountReason && (
          <span className="cost-amount-reason">{row.amountReason}</span>
        )}
      </span>
    )
  }
  const currency = row.currency ?? ''
  const symbol = currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : ''
  return (
    <span className="cost-amount" title={row.priceRule ? `按价格规则 ${row.priceRule} 计算` : undefined}>
      {symbol}
      {row.amount}
      {!symbol && currency ? ` ${currency}` : ''}
      {showReason && row.priceRule === '*' && <Tag className="cost-fallback-tag">兜底价</Tag>}
    </span>
  )
}

// ---------------------------------------------------------------------------
//  1. 头部：合计
// ---------------------------------------------------------------------------

function Headline({ data }: { data: CostSummaryResponse }) {
  const t = data.totals
  return (
    <div className="cost-headline">
      <div className="cost-headline-main">
        <Typography.Text type="secondary">窗口内合计（{data.window.from} ~ {data.window.to}）</Typography.Text>
        <div className="cost-headline-amount">
          {t.amount === null ? (
            /*
              【算不出金额时的头部】
              ① 术语与「按模型」那一栏的格子**用同一个词**（都叫「算不出」）：
                 两处措辞不同会让人以为是两件事；
              ② 原因**直接显示在下面**，不放进 Tooltip —— 见 Amount 组件的注释
                 （给 antd Tooltip 的直接子元素传 title，那个 title 不会落到 DOM 上）。
            */
            <Typography.Title level={4} className="cost-amount-unknown">
              算不出金额
            </Typography.Title>
          ) : (
            <Typography.Title level={4}>
              <Amount row={t} />
            </Typography.Title>
          )}
          {/*
            这一行是头部最重要的一句：金额算不出时**必须给出原因**，
            否则一个大大的"算不出"会让人以为功能坏了，而不是"缺一条配置"。
            而原因里已经写清了该去配什么。
          */}
          {t.amount === null && (
            <Typography.Text type="warning" className="cost-headline-reason">
              {t.amountReason}
            </Typography.Text>
          )}
        </div>
      </div>

      <Flex gap={24} wrap className="cost-headline-stats">
        <Stat label="运行次数" value={`${t.runs}`} sub={`已结束 ${t.finishedRuns}`} />
        <Stat
          label="失败的运行"
          value={`${t.failedRuns}`}
          sub={t.runs > 0 ? `占 ${pct(t.failedRuns / t.runs)}` : ''}
          danger={t.failedRuns > 0}
        />
        {/*
          被上限拦下的那些单独一格。

          为什么不并进「失败的运行」：两者都是"花了钱没办成事"，但**改法相反** ——
          失败要去看上游（限流 / 鉴权 / 超时），被拦下要去看我们自己的上限
          （步数 6 / token 12000 / 时长 60s 配得太紧）。合成一个数字之后，
          "这周失败率上升"这句话就没法回答"该动哪一边"。

          为什么也不并进「已结束」了事：它是一个**子集**（`finishedRuns` 含它），
          单独摆出来才看得见 —— 一个只显示"已结束 12"的页面，读不出其中有 5 次
          一个字都没答出来。
        */}
        <Stat
          label="被上限拦下"
          value={`${t.stoppedRuns}`}
          sub="花了钱、没办成事"
          danger={t.stoppedRuns > 0}
        />
        <Stat label="总 token" value={fmt(t.totalTokens)} sub={`输入 ${fmt(t.promptTokens)} / 输出 ${fmt(t.completionTokens)}`} />
        <Stat
          label="缓存命中"
          value={pct(t.cacheHitRate)}
          sub={`${fmt(t.cachedPromptTokens)} / ${fmt(t.promptTokens)} 输入`}
          danger={data.cache.suspicious}
        />
        <Stat label="工具调用" value={`${t.toolCalls}`} sub={`其中失败 ${t.toolFailures}`} />
      </Flex>
    </div>
  )
}

function Stat({ label, value, sub, danger }: { label: string; value: string; sub?: string; danger?: boolean }) {
  return (
    <div className="cost-stat">
      <div className="cost-stat-label">{label}</div>
      <div className={`cost-stat-value ${danger ? 'is-danger' : ''}`}>{value}</div>
      {sub && <div className="cost-stat-sub">{sub}</div>}
    </div>
  )
}

// ---------------------------------------------------------------------------
//  2. 价格表：金额是怎么来的，还差什么
// ---------------------------------------------------------------------------

function PriceCard({
  data,
  prices,
  onReload,
}: {
  data: CostSummaryResponse
  prices: Remote<import('../api/cost').CostPricesResponse>
  onReload: () => void
}) {
  const priceData = prices.status === 'ok' ? prices.data : null
  const needs = data.needsPriceFor ?? []

  return (
    <Card
      size="small"
      className={`diag-card diag-card-wide ${needs.length > 0 ? 'cost-needs-price' : ''}`}
      title={
        <Flex gap={8} align="center" wrap>
          <span>价格表：这一页的钱是按这些单价算出来的</span>
          {needs.length > 0 ? <Tag color="orange">{needs.length} 个模型还没有价</Tag> : <Tag color="green">齐全</Tag>}
        </Flex>
      }
      extra={
        <Button size="small" onClick={onReload}>
          刷新价格
        </Button>
      }
    >
      {/*
        「还缺哪些模型的价格」是一份**能照着做的待办**，所以它放在最前面。
        放在价格表下面的话，一个没配价格的部署要先读完整张空表才知道自己该干什么。

        ⚠️ 判据是 `!pricingConfigured || needs.length > 0`，**不是** `needs.length > 0`。
        实测踩到：一个全新的库 + 价格没配时，`needsPriceFor` 是空的
        （没有运行过任何模型，所以"哪些模型缺价"无从谈起）—— 于是这条提示整个不显示，
        而这个状态恰恰是**第一次打开这一页最常见的样子**。
        "没有模型缺价"与"价格压根没配"是两件事，前者不需要提示，后者必须提示。
      */}
      {(!data.pricingConfigured || needs.length > 0) && (
        <Alert
          type="warning"
          showIcon
          className="cost-needs-alert"
          title={
            data.pricingConfigured
              ? `这些模型还没有价格：${needs.join('、')}`
              : '价格未配置 —— 这一页只显示 token，不显示金额'
          }
          description={
            <div>
              <div>
                {data.pricingConfigured ? (
                  <>
                    它们的 token 计入了总量，但<b>不在金额里</b> —— 所以上面的合计是<strong>下界</strong>而不是真值。
                  </>
                ) : (
                  <>
                    不是"不花钱"，而是<b>我们不知道花多少钱</b> —— 所以这里刻意不给 0。
                  </>
                )}
              </div>
              <pre className="cost-config-sample">
                {priceData?.howToConfigure ?? '在 application.properties 里配 ai.cost.prices[0].*（见 /api/cost/prices）'}
              </pre>
            </div>
          }
        />
      )}

      {data.priceTable.length === 0 ? (
        <Empty
          description={
            <span>
              一条价格规则都没有。金额来自「token × 单价」，而单价是外部世界的商业条款 ——
              它没法从代码里猜出来，所以这里空着，而不是编一个数字。
            </span>
          }
        />
      ) : (
        <Table<CostPriceRow>
          size="small"
          rowKey={r => `${r.model}@${r.effectiveFrom}`}
          pagination={false}
          dataSource={data.priceTable}
          columns={[
            { title: '模型', dataIndex: 'model', render: (v: string) => (v === '*' ? <Tag>兜底规则 *</Tag> : v) },
            { title: '输入 / 百万', dataIndex: 'inputPerMillion' },
            {
              title: '缓存命中 / 百万',
              dataIndex: 'cacheHitPerMillion',
              render: (v: string, row: CostPriceRow) =>
                row.cacheHitIsAssumed ? (
                  <Tooltip title="没配缓存价，按未命中价算（偏保守）。这是「假设」而不是事实 —— 配了之后金额会降下来">
                    <span>
                      {v} <Tag color="orange">假设</Tag>
                    </span>
                  </Tooltip>
                ) : (
                  v
                ),
            },
            { title: '输出 / 百万', dataIndex: 'outputPerMillion' },
            { title: '币种', dataIndex: 'currency', width: 70 },
            { title: '生效日', dataIndex: 'effectiveFrom', render: (v: string) => v || '不限' },
            { title: '出处', dataIndex: 'source', render: (v: string) => v || '—' },
          ]}
        />
      )}

      {data.configNotes.length > 0 && (
        <Alert
          type="warning"
          showIcon
          className="cost-config-notes"
          title="价格表本身有问题"
          description={
            <ul>
              {data.configNotes.map(n => (
                <li key={n}>{n}</li>
              ))}
            </ul>
          }
        />
      )}
    </Card>
  )
}

// ---------------------------------------------------------------------------
//  3. 按天
// ---------------------------------------------------------------------------

function DaysCard({ data }: { data: CostSummaryResponse }) {
  return (
    <Card size="small" className="diag-card diag-card-wide" title="按天：钱是怎么涨上去的">
      {data.days.length === 0 ? (
        <Empty description="这个窗口里一次运行都没有 —— 先去「Agent 工具调用」面板跑一个任务" />
      ) : (
        <Table<CostGroup>
          size="small"
          rowKey={r => r.day ?? ''}
          pagination={false}
          dataSource={data.days}
          columns={[
            { title: '日期', dataIndex: 'day', width: 110 },
            { title: '运行', dataIndex: 'runs', width: 70 },
            { title: '已结束', dataIndex: 'finishedRuns', width: 80 },
            {
              title: '被上限拦下',
              dataIndex: 'stoppedRuns',
              width: 100,
              render: (v: number) => (v > 0 ? <Tag color="gold">{v}</Tag> : '0'),
            },
            {
              title: '失败',
              dataIndex: 'failedRuns',
              width: 70,
              render: (v: number) => (v > 0 ? <Tag color="red">{v}</Tag> : '0'),
            },
            { title: '输入 token', dataIndex: 'promptTokens', render: (v: number) => fmt(v) },
            {
              title: '其中命中缓存',
              dataIndex: 'cachedPromptTokens',
              render: (v: number, row: CostGroup) => `${fmt(v)}（${pct(row.cacheHitRate)}）`,
            },
            { title: '输出 token', dataIndex: 'completionTokens', render: (v: number) => fmt(v) },
            { title: '金额', dataIndex: 'amount', render: (_: unknown, row: CostGroup) => <Amount row={row} /> },
          ]}
        />
      )}
    </Card>
  )
}

// ---------------------------------------------------------------------------
//  4. 两个归因维度：租户（谁的钱）与业务（哪个功能）
// ---------------------------------------------------------------------------

/**
 * 「按租户」这一栏（阶段四第 6 件）。
 *
 * <h2>为什么它与「按业务」是两个组件调用同一份渲染，而不是两段各写一遍</h2>
 * 两栏的**形状完全一样**（同一份后端结构 `byTenant` / `byIntent`），差别只有三个字符串：
 * 标题、空行的名字（「未标注租户」/「未标注」）、以及提示里那句"该去哪个面板填"。
 * 各写一遍的代价不是重复本身，而是它们会**慢慢分叉** ——
 * 比如有人给业务那一栏加了"未标注"的提示，却忘了租户那一栏也有同一个空洞。
 */
function TenantCard({ data }: { data: CostSummaryResponse }) {
  return (
    <AttributionCard
      rows={data.byTenant}
      title="按租户：钱是谁花的"
      columnTitle="租户"
      howToFill="在「Agent 工具调用」面板发起任务时填「租户」就会归到这里。"
      missingNote={
        <>
          它们单独占一行（未标注租户），<b>没有</b>被并进任何一个租户里 ——{' '}
          并进去会让那个租户的成本虚高，而虚高的部分无从发现。
          它与「未标注」（业务归因缺失）是<b>两件事</b>：前者是不知道算谁的钱，后者是不知道属于哪个功能。
        </>
      }
    />
  )
}

function IntentCard({ data }: { data: CostSummaryResponse }) {
  return (
    <AttributionCard
      rows={data.byIntent}
      title="按业务归因：钱花在哪个功能上"
      columnTitle="业务 / 功能"
      howToFill="在「Agent 工具调用」面板发起任务时填「业务归因」就会归到这里。"
      missingNote={
        <>
          它们单独占一行（未标注），<b>没有</b>被并进任何一个业务里 ——{' '}
          并进去会让那个业务的成本虚高，而虚高的部分无从发现。
        </>
      }
    />
  )
}

/**
 * 归因维度那一栏的共用渲染。
 *
 * <h2>金额那一格为什么**有数字**（第 6 件之前这里写的是「算不出」）</h2>
 * 后端现在按 token 占比把这批花费从合计分摊到每一行（`amountBasis = ALLOCATED_BY_TOKEN_SHARE`）。
 * 为什么这不算"猜"：费用对 token 是线性的（三档单价各自是常数），
 * 所以"按占比分摊"与"逐模型算完再相加"是同一个数的两种算法。
 * 页面上把口径写在提示里 —— 一张不说口径的成本表，读者会自行脑补一个更弱的说法
 * （"大约是这么分的"），而那不是事实。
 */
function AttributionCard({
  rows,
  title,
  columnTitle,
  howToFill,
  missingNote,
}: {
  rows: CostGroup[]
  title: string
  columnTitle: string
  /** 该去哪儿填这个维度（两个维度的提示语不同，所以它是参数而不是写死的一句）。 */
  howToFill: string
  missingNote: React.ReactNode
}) {
  const missing = rows.some(r => r.keyMissing)
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title={
        <Flex gap={8} align="center" wrap>
          <span>{title}</span>
          {missing && <Tag color="orange">有未标注的运行</Tag>}
        </Flex>
      }
    >
      {missing && (
        <Alert type="info" showIcon className="cost-needs-alert" title="有一部分运行没有标注" description={missingNote} />
      )}

      {rows.length === 0 ? (
        <Empty description="还没有运行记录" />
      ) : (
        <Table<CostGroup>
          size="small"
          rowKey={r => r.keyLabel ?? ''}
          pagination={false}
          dataSource={rows}
          columns={[
            {
              title: columnTitle,
              dataIndex: 'keyLabel',
              render: (v: string, row: CostGroup) =>
                row.keyMissing ? <Typography.Text type="warning">{v}</Typography.Text> : v,
            },
            { title: '运行', dataIndex: 'runs', width: 70 },
            {
              title: '失败',
              dataIndex: 'failedRuns',
              width: 70,
              render: (v: number) => (v > 0 ? <Tag color="red">{v}</Tag> : '0'),
            },
            { title: '总 token', dataIndex: 'totalTokens', render: (v: number) => fmt(v) },
            { title: '缓存命中', dataIndex: 'cacheHitRate', render: (v: number) => pct(v) },
            { title: '工具调用', dataIndex: 'toolCalls', width: 90 },
            {
              title: '金额',
              dataIndex: 'amount',
              render: (_: unknown, row: CostGroup) => (
                <Tooltip
                  title={
                    row.amount === null
                      ? row.amountReason ?? ''
                      : row.amountBasis === 'ALLOCATED_BY_TOKEN_SHARE'
                        ? `这一栏混着多个模型，单一单价不适用 —— 金额是从合计按 token 占比分摊的`
                        : ''
                  }
                >
                  <span>
                    <Amount row={row} />
                  </span>
                </Tooltip>
              ),
            },
          ]}
        />
      )}

      {rows.length > 0 && (
        <Typography.Paragraph type="secondary" className="cost-note-line">
          这一栏的金额是<b>按 token 占比从合计分摊</b>的（费用对 token 是线性的，
          所以这与逐模型算完再相加等价）—— 所以各行相加必然等于「按模型」那一栏的和。
          {howToFill}
        </Typography.Paragraph>
      )}
    </Card>
  )
}

// ---------------------------------------------------------------------------
//  5. 按模型
// ---------------------------------------------------------------------------

function ModelCard({ data }: { data: CostSummaryResponse }) {
  return (
    <Card size="small" className="diag-card diag-card-wide" title="按模型：换成别的模型会差多少">
      {data.byModel.length === 0 ? (
        <Empty description="还没有运行记录" />
      ) : (
        <Table<CostGroup>
          size="small"
          rowKey={r => r.keyLabel ?? ''}
          pagination={false}
          dataSource={data.byModel}
          columns={[
            {
              title: '模型',
              dataIndex: 'keyLabel',
              render: (v: string, row: CostGroup) =>
                row.keyMissing ? (
                  <Tooltip title="上游没有返回模型名。刻意不给它用兜底价 —— 那会算出一个按别的模型定价的假金额">
                    <Typography.Text type="warning">{v}</Typography.Text>
                  </Tooltip>
                ) : (
                  v
                ),
            },
            { title: '用到它的运行', dataIndex: 'runs', width: 120 },
            { title: '输入 token', dataIndex: 'promptTokens', render: (v: number) => fmt(v) },
            { title: '命中缓存', dataIndex: 'cachedPromptTokens', render: (v: number) => fmt(v) },
            { title: '输出 token', dataIndex: 'completionTokens', render: (v: number) => fmt(v) },
            { title: '总 token', dataIndex: 'totalTokens', render: (v: number) => fmt(v) },
            {
              title: '金额',
              dataIndex: 'amount',
              render: (_: unknown, row: CostGroup) => <Amount row={row} showReason />,
            },
          ]}
        />
      )}
    </Card>
  )
}

// ---------------------------------------------------------------------------
//  6. 按工具（**这一栏没有金额**）
// ---------------------------------------------------------------------------

function ToolCard({ data }: { data: CostSummaryResponse }) {
  const empty = data.byTool.length === 0
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title={
        <Flex gap={8} align="center" wrap>
          <span>按工具：哪把工具在拖慢任务</span>
          <Tag>这一栏没有金额</Tag>
        </Flex>
      }
    >
      {/*
        为什么不给工具算钱：token 花在**模型往返**上，工具消耗的是延迟与下游容量。
        "这把工具花了多少钱"只能靠"它让模型多跑了几轮"间接归因，而那是推测不是数据。
        页面必须写清楚这一点 —— 否则一栏没有金额的表会被读成"漏了"
      */}
      <Typography.Paragraph type="secondary" className="cost-tool-note">
        工具不直接消耗 token：token 花在模型往返上，工具消耗的是延迟与下游容量。
        所以这里给的是<b>次数、失败分类与耗时</b> —— 它们是「哪把工具在拖慢任务」的答案。
      </Typography.Paragraph>
      {empty ? (
        <Empty description="这个窗口里没有工具调用" />
      ) : (
        <Table<CostToolRow>
          size="small"
          rowKey={r => r.toolName}
          pagination={false}
          dataSource={data.byTool}
          columns={[
            { title: '工具', dataIndex: 'toolName' },
            { title: '调用', dataIndex: 'calls', width: 70 },
            {
              title: '执行失败',
              dataIndex: 'failures',
              width: 90,
              render: (v: number) => (v > 0 ? <Tag color="red">{v}</Tag> : '0'),
            },
            { title: '模型编错工具名', dataIndex: 'unknownTool', width: 130 },
            { title: '参数不合格', dataIndex: 'validationErrors', width: 110 },
            {
              title: '被限流拒绝',
              dataIndex: 'rejected',
              width: 110,
              render: (v: number) => (v > 0 ? <Tooltip title="这是保护措施在生效，不是故障 —— 稍后重试即可"><span>{v}</span></Tooltip> : '0'),
            },
            { title: '熔断拒绝', dataIndex: 'circuitOpen', width: 90 },
            { title: '工具侧耗时', dataIndex: 'elapsedMs', render: (v: number) => `${fmt(v)} ms` },
          ]}
        />
      )}
    </Card>
  )
}

// ---------------------------------------------------------------------------
//  7. 对账与缓存（看板的仪表自检）
// ---------------------------------------------------------------------------

function ReconcileCard({ data }: { data: CostSummaryResponse }) {
  const r = data.reconciliation
  return (
    <Card
      size="small"
      className={`diag-card diag-card-wide ${r.matched ? '' : 'cost-reconcile-bad'}`}
      title={
        <Flex gap={8} align="center" wrap>
          <span>口径对账：这一页的钱和运行列表的 token 对不对得上</span>
          {r.matched ? <Tag color="green">一致</Tag> : <Tag color="red">差 {r.gap}</Tag>}
        </Flex>
      }
    >
      {/*
        这一块是"看板自己的仪表自检"。看板的金额按**步骤表**汇总，
        而运行列表显示的是 agent_run.total_tokens（循环自己累加的、也决定 token 上限）。
        两者本该一致，不一致说明有一处漏记 —— 而看板上完全看不出来（只是钱多或少一点）。
        所以这里把两个数字并排摆出来，让不一致自己现形
      */}
      <Flex gap={24} wrap>
        <Stat label="步骤表 token（金额的依据）" value={fmt(r.stepLevelTokens)} />
        <Stat label="运行行 token（列表显示的值）" value={fmt(r.runLevelTokens)} />
        <Stat
          label="差值"
          value={`${r.gap}`}
          danger={!r.matched}
          sub={r.matched ? '两个口径一致' : '有一处漏记，见下方说明'}
        />
        <Stat label="一步都没有的运行" value={`${r.runsWithoutSteps}`} danger={r.runsWithoutSteps > 0} sub="0 token 不等于没花钱" />
        <Stat
          label="缓存命中"
          value={pct(data.cache.hitRate)}
          sub={`${data.cache.runsWithCacheHit} 次运行命中过`}
          danger={data.cache.suspicious}
        />
      </Flex>
      <Typography.Paragraph type="secondary" className="cost-note-line">
        {r.note}
      </Typography.Paragraph>
      <Typography.Paragraph type="secondary" className="cost-note-line">
        {data.cache.note}
      </Typography.Paragraph>
    </Card>
  )
}

// ---------------------------------------------------------------------------
//  8. 结论的边界（与数字一起交付）
// ---------------------------------------------------------------------------

function NotesCard({ notes }: { notes: string[] }) {
  return (
    <Card size="small" className="diag-card diag-card-wide" title="这份数据能说什么、不能说什么">
      {/*
        后端把边界说明随数字一起返回（见 CostDashboardView.notes）。
        不显示它们的话，一张只有数字的成本表一定会被读成"它证明了更多东西"
      */}
      <ul className="cost-notes">
        {notes.map(n => (
          <li key={n}>{n}</li>
        ))}
      </ul>
    </Card>
  )
}

// ---------------------------------------------------------------------------

/** 千分位整数（token 数动辄六位数，不分组读起来很费劲）。 */
function fmt(value: number | undefined): string {
  if (value === undefined || value === null) return '—'
  return value.toLocaleString('en-US')
}

/** 比率 → 百分比，两位小数。 */
function pct(value: number): string {
  return `${(value * 100).toFixed(2)}%`
}
