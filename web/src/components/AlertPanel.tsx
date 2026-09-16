import { Alert, Button, Card, Empty, Flex, Input, InputNumber, Segmented, Spin, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { ALERT_WINDOWS } from '../hooks/useAlerts'
import type { AlertWindow, UseAlertsResult } from '../hooks/useAlerts'
import type { AlertEventInfo, AlertRulesResponse, AlertSeverity, AlertState, AlertsResponse, CanaryResponse } from '../api/alerts'

/**
 * 「告警与灰度」面板（阶段四 · 工程化第 5 件）。
 *
 * <h2>这一页补的是哪半句话</h2>
 * 路线文档对阶段四的验收写的是「Agent 出问题**有人能查**、成本异常**有人能发现**、
 * Prompt 改坏**能回滚**」。在成本看板（第十轮）之后，第二句只做到了一半：
 * 看板能**看见**异常（`notes` 里那几条 ⚠️），但"看见"的前提是有人打开那一页 ——
 * 它的失败方式是最安静的一种：**没有人被通知**。
 *
 * <p>所以这一页有两块，而它们各自补一句话：
 * <ol>
 *   <li><b>告警</b>：把那些"人读的 ⚠️"变成**可查询、可确认、会自己出现和消失的事件**
 *       —— 于是"有没有出事"不再取决于有没有人恰好打开某一页；</li>
 *   <li><b>灰度</b>：把"新版到底比旧版好还是差"从"不可回答"变成"**有数据可答**"。
 *       Prompt 版本管理（第十二轮）答得了"改哪一版、能不能回滚、当时用的是哪一版"，
 *       但它答不了"哪一版更好"—— 那需要**分组流量**，而灰度就是那条链路。</li>
 * </ol>
 *
 * <h2>本页最重要的一条纪律：配置与实测必须同屏</h2>
 * 灰度那一块里，"我配了 20%"与"**实际分到几份**"是两个完全不同的东西，
 * 而它们分开显示的后果很具体：配置 20%、实际 0 份时，只看配置的页面
 * **一切正常** —— 而那次实验等于没做，使用者却会据此得出"新版没有区别"。
 * 所以 {@link SplitCard} 把它们并排摆出来，并且**样本不足时不许说"偏了多少"**
 * （10% 的重量在 5 份样本里一份都没分到，概率高达六成 —— 那是噪声，不是结论）。
 *
 * <h2>第二条纪律：构建阶段的判据必须显示成「这里不评估」</h2>
 * 判据目录里有四条是构建阶段的门禁（在 CI 上跑）。这一页不会评估它们 ——
 * 如果界面上不加区分地列出来，读的人会以为"告警面板说构建也没问题"。
 * 所以 {@link RulesCard} 给它们单独一段，并把 {@code executedBy}（真正的执行者）显示出来。
 */
export function AlertPanel({ alerts }: { alerts: UseAlertsResult }) {
  const { rules, alerts: list, sweep, canary, days } = alerts
  const data = list.status === 'ok' ? list.data : null

  return (
    <div className="diag-panel">
      <Card
        size="small"
        className="diag-card diag-card-wide alert-head"
        title={
          <Flex gap={12} align="center" wrap>
            <span>告警：把「有人打开这一页才会看见」变成「会自己出现和消失的事件」</span>
            {data && (
              <Tag color={data.openCount > 0 ? 'red' : data.firingCount > 0 ? 'orange' : 'green'}>
                {data.openCount > 0
                  ? `${data.openCount} 条未处理`
                  : data.firingCount > 0
                    ? `${data.firingCount} 条在响（都已确认）`
                    : '当前没有在响的告警'}
              </Tag>
            )}
          </Flex>
        }
        extra={
          <Flex gap={8} align="center">
            <Segmented
              size="small"
              value={days}
              onChange={v => alerts.setDays(v as AlertWindow)}
              options={ALERT_WINDOWS.map(d => ({ label: d === 1 ? '今天' : `${d} 天`, value: d }))}
            />
            <Button size="small" onClick={alerts.load} disabled={list.status === 'loading'}>
              刷新
            </Button>
            <Button
              size="small"
              type="primary"
              onClick={alerts.evaluate}
              disabled={sweep.status === 'loading'}
              title="会写库：新响的会被记下来、恢复的会被标成恢复"
            >
              {sweep.status === 'loading' ? '评估中……' : '跑一轮评估'}
            </Button>
          </Flex>
        }
      >
        {list.status === 'loading' && (
          <Flex align="center" gap={8}>
            <Spin size="small" />
            <Typography.Text type="secondary">正在拉取告警状态……</Typography.Text>
          </Flex>
        )}
        {list.status === 'error' && <Alert type="error" showIcon title="拉取告警失败" description={list.error} />}
        {list.status === 'idle' && <Empty description="切换到本面板后会自动加载" />}
        {data && <Headline data={data} sweep={sweep} />}
      </Card>

      {alerts.lastWrite && (
        <Alert
          className="alert-write-result"
          type={alerts.lastWrite.ok ? 'success' : alerts.lastWrite.stale ? 'warning' : 'error'}
          showIcon
          closable
          onClose={alerts.clearLastWrite}
          title={alerts.lastWrite.ok ? '操作已完成' : alerts.lastWrite.stale ? '前提已过期，这次操作没有执行' : '操作被拒绝'}
          description={
            alerts.lastWrite.ok
              ? undefined
              : `${alerts.lastWrite.message ?? ''}${alerts.lastWrite.stale ? '（刷新一次看到的是最新状态；反复点同一个按钮没有用）' : ''}`
          }
        />
      )}

      {data && <EventsCard data={data} alerts={alerts} />}

      {canary.status === 'ok' && <CanaryCard canary={canary.data} alerts={alerts} />}
      {canary.status === 'error' && (
        <Card size="small" className="diag-card diag-card-wide">
          <Alert type="error" showIcon title="拉取灰度状态失败" description={canary.error} />
        </Card>
      )}

      {rules.status === 'ok' && <RulesCard rules={rules.data} />}
      {rules.status === 'error' && (
        <Card size="small" className="diag-card diag-card-wide">
          <Alert type="error" showIcon title="拉取判据目录失败" description={rules.error} />
        </Card>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
//  头部：一句话说清现在的状态
// ---------------------------------------------------------------------------

function Headline({ data, sweep }: { data: AlertsResponse; sweep: UseAlertsResult['sweep'] }) {
  const result = sweep.status === 'ok' ? sweep.data : null
  return (
    <Flex vertical gap={8}>
      <Flex gap={24} wrap className="alert-stats">
        <Stat label="未处理（还没人看过）" value={data.openCount} tone={data.openCount > 0 ? 'bad' : 'good'} />
        <Stat label="在响（含已确认）" value={data.firingCount} tone={data.firingCount > 0 ? 'warn' : 'good'} />
        <Stat label="已确认（看过、暂时不管）" value={data.acknowledgedCount} tone="neutral" />
        <Stat label="最严重的在响" value={data.worstSeverity ?? '无'} tone={data.worstSeverity === 'WARNING' ? 'warn' : 'neutral'} />
      </Flex>

      {data.writeProtected ? null : (
        <Typography.Text type="secondary" className="alert-write-unprotected">
          当前部署的写操作未受保护（<code>ai.alert.write-token</code> 是空的）——
          任何能访问这个端口的人都能确认告警。本地开发够用，上生产要配上。
        </Typography.Text>
      )}

      {result && (
        <Typography.Text type="secondary" className="alert-sweep-summary">
          最近一轮评估（窗口 {result.window}）：新响 {result.fired?.length ?? 0} 条、
          恢复 {result.resolved?.length ?? 0} 条、
          {/* ⚠️ "仍然在响"必须单独说 —— 混进"新响"里会把一个持续了一周的老问题
              报成今天才出现的，而那是值班的人判断"要不要叫醒别人"的依据 */}
          仍然在响 {result.ongoingCount ?? 0} 条。
        </Typography.Text>
      )}
      {!result && (
        <Typography.Text type="secondary">
          还没有跑过评估。评估会写库（新响的会被记下来），所以要你点一下 ——
          进面板自动跑会让"打开这一页看看"这个动作本身改变告警状态。
        </Typography.Text>
      )}
    </Flex>
  )
}

function Stat({ label, value, tone }: { label: string; value: number | string; tone: 'good' | 'warn' | 'bad' | 'neutral' }) {
  return (
    <Flex vertical className={`alert-stat alert-stat-${tone}`}>
      <Typography.Text type="secondary" className="alert-stat-label">
        {label}
      </Typography.Text>
      <span className="alert-stat-value">{value}</span>
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  告警清单
// ---------------------------------------------------------------------------

function EventsCard({ data, alerts }: { data: AlertsResponse; alerts: UseAlertsResult }) {
  const [showResolved, setShowResolved] = useState(true)
  const rows = showResolved ? data.events : data.events.filter(e => e.state !== 'RESOLVED')

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide alert-events-card"
      title={
        <Flex gap={12} align="center" wrap>
          <span>告警清单</span>
          <Segmented
            size="small"
            value={showResolved ? 'all' : 'firing'}
            onChange={v => setShowResolved(v === 'all')}
            options={[
              { label: `全部（${data.events.length}）`, value: 'all' },
              { label: `只看在响（${data.firingCount}）`, value: 'firing' },
            ]}
          />
        </Flex>
      }
    >
      {rows.length === 0 ? (
        <Empty
          description={
            data.events.length === 0
              ? '一条告警记录都没有 —— 要么一切正常，要么还没有跑过评估（点右上角那个按钮）'
              : '没有在响的告警（已恢复的那些切到「全部」能看到）'
          }
        />
      ) : (
        <Table<AlertEventInfo>
          size="small"
          rowKey="fingerprint"
          dataSource={rows}
          pagination={false}
          scroll={{ x: 'max-content' }}
          expandable={{
            expandedRowRender: row => <EventDetail row={row} />,
            /*
              默认展开**未处理**的那些：它们是唯一"需要人去动"的一类。
              全都折叠起来的话，这一页看起来就只是一张表 ——
              而它的用途正是"一眼看出有哪些事等着人做"。
            */
            defaultExpandedRowKeys: rows.filter(r => r.state === 'OPEN').slice(0, 2).map(r => r.fingerprint),
          }}
          columns={[
            {
              title: '严重度',
              dataIndex: 'severityLabel',
              width: 90,
              render: (label: string, row) => <Tag color={severityColor(row.severity)}>{label}</Tag>,
            },
            {
              title: '判据',
              dataIndex: 'ruleCode',
              render: (code: string, row) => (
                <Flex vertical gap={2}>
                  <span className="alert-rule-code">{code}</span>
                  {row.subject && <Typography.Text type="secondary">主体：{row.subject}</Typography.Text>}
                </Flex>
              ),
            },
            {
              title: '状态',
              dataIndex: 'stateLabel',
              width: 130,
              render: (label: string, row) => <StateTag state={row.state} label={label} />,
            },
            { title: '观测值', dataIndex: 'observed', width: 160 },
            { title: '判据阈值', dataIndex: 'threshold', width: 220 },
            {
              title: '响了多久',
              dataIndex: 'firingSeconds',
              width: 110,
              render: (seconds: number) => formatDuration(seconds),
            },
            {
              title: '次数',
              dataIndex: 'occurrences',
              width: 80,
              render: (n: number) => (
                <span title="持续成立时这个数字会一直涨 —— 它是「这个异常是持续的还是偶尔的」的唯一线索">
                  {n}
                </span>
              ),
            },
            {
              title: '操作',
              key: 'action',
              width: 150,
              render: (_, row) => (
                <Flex gap={8} align="center">
                  <Button
                    size="small"
                    disabled={row.state !== 'OPEN' || alerts.busy}
                    onClick={() => void alerts.acknowledge(row.fingerprint, '界面')}
                  >
                    已确认
                  </Button>
                  {row.state === 'OPEN' && <Typography.Text type="secondary">未处理</Typography.Text>}
                </Flex>
              ),
            },
          ]}
        />
      )}
    </Card>
  )
}

function EventDetail({ row }: { row: AlertEventInfo }) {
  return (
    <Flex vertical gap={6} className="alert-detail">
      <Detail label="这条判据是什么" value={row.title ?? row.ruleCode} />
      <Detail label="为什么算异常" value={row.why} />
      <Detail label="该做什么" value={row.action ?? row.executedBy ?? '（判据目录里有处置说明）'} />
      <Detail label="判定窗口" value={row.window} />
      <Detail
        label="时间线"
        value={`第一次发现 ${formatTime(row.firstSeenAt)} · 最后一次发现 ${formatTime(row.lastSeenAt)}${
          row.resolvedAt ? ` · 已恢复 ${formatTime(row.resolvedAt)}` : ''
        }${row.acknowledgedAt ? ` · ${row.acknowledgedBy ?? '有人'} 于 ${formatTime(row.acknowledgedAt)} 确认` : ''}`}
      />
      <Detail label="指纹" value={row.fingerprint} />
    </Flex>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <Flex gap={8} align="flex-start">
      <Typography.Text type="secondary" className="alert-detail-label">
        {label}
      </Typography.Text>
      <Typography.Text className="alert-detail-value">{value}</Typography.Text>
    </Flex>
  )
}

/**
 * 状态标签。
 *
 * ⚠️ 三态必须**长得不一样**：未处理（红/橙）、已确认（蓝）、已恢复（灰）。
 * 把它们合成一个布尔（"处理过没有"）的后果很具体：值班的人确认完一轮之后，
 * 列表看起来与"完全没人管"一模一样，于是下一个人会从头再查一遍 ——
 * 而第三个人就开始忽略这个列表了。
 */
function StateTag({ state, label }: { state: AlertState; label: string }) {
  const color = state === 'OPEN' ? 'red' : state === 'ACKNOWLEDGED' ? 'blue' : 'default'
  return <Tag color={color}>{label}</Tag>
}

// ---------------------------------------------------------------------------
//  灰度：配置与实际分布并排
// ---------------------------------------------------------------------------

function CanaryCard({ canary, alerts }: { canary: CanaryResponse; alerts: UseAlertsResult }) {
  const cfg = canary.canary ?? canary.previous
  const [draft, setDraft] = useState<{ version: number; weight: number; note: string; endReason: string }>({
    version: canary.versions.find(v => v.version !== canary.activeVersion)?.version ?? 1,
    weight: 20,
    note: '',
    endReason: '',
  })

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide canary-card"
      title={
        <Flex gap={12} align="center" wrap>
          <span>灰度发布：让新版只作用于一部分运行，于是两组之间有得比</span>
          {canary.active ? (
            <Tag color="purple">进行中：v{cfg?.candidateVersion} 放量 {cfg?.weightPercent}%</Tag>
          ) : cfg ? (
            <Tag>上一轮实验已停止</Tag>
          ) : (
            <Tag>还没有开过灰度</Tag>
          )}
        </Flex>
      }
    >
      <Flex vertical gap={12}>
        <Typography.Text type="secondary">
          分流是「算出来的」，不是存下来的：按运行 id 取稳定哈希（SHA-256 取模 100）再与重量比。
          同一份运行 id 永远落同一个桶 —— 所以不需要一张"运行 → 分组"的映射表，
          也就不存在"映射表与配置不一致"这个状态。当前生效版本 v{canary.activeVersion}
          （指纹 <code>{canary.activeFingerprint}</code>）。
        </Typography.Text>

        <SplitCard canary={canary} />

        {canary.active && cfg ? (
          <Flex vertical gap={8} className="canary-controls">
            <Flex gap={12} align="center" wrap>
              <Input
                size="small"
                className="canary-end-reason"
                placeholder="为什么停（必填：推全 / 回滚 / 实验结束）"
                value={draft.endReason}
                onChange={e => setDraft({ ...draft, endReason: e.target.value })}
              />
              <Button
                size="small"
                danger
                disabled={alerts.busy || draft.endReason.trim() === ''}
                onClick={() => void alerts.stop({ kind: canary.kind, endReason: draft.endReason })}
              >
                停止这次灰度
              </Button>
            </Flex>
            <Typography.Text type="secondary">
              停止之后新的运行全部走稳定版；已经跑过的不受影响 ——
              它们的 trace 里仍然记着当时那一组，所以那次实验的数据读得出来。
            </Typography.Text>
          </Flex>
        ) : (
          <Flex vertical gap={8} className="canary-controls">
            {/*
              ★ 没有候选版本可选的场景**必须被解释**，不能只是让按钮变灰或让下拉框空着。
              这是真后端上第一次打开这一页的样子：库里只有出厂那一版（v1）且它正在生效，
              而"让生效中的那一版生效"是没有意义的 —— 于是候选版本列表是空的。
              只渲染一个空下拉框的后果是使用者以为界面坏了（而他什么也没做错）。
              这与第 9 节那条"空列表必须解释自己"是同一条规矩。
            */}
            {canary.versions.filter(v => v.version !== canary.activeVersion).length === 0 ? (
              <Alert
                type="info"
                showIcon
                className="canary-no-candidate"
                title="现在没有可以拿来灰度的候选版本"
                description={
                  `库里只有 ${canary.versions.length} 版，而且第 ${canary.activeVersion} 版正在生效 —— ` +
                  '灰度的两组必须跑不同的提示词，所以候选版本不能就是当前生效那一版。' +
                  '先去 Prompt 面板存一版新的（保存不会让它生效），再回到这里开灰度。'
                }
              />
            ) : (
              <>
                <Flex gap={12} align="center" wrap>
                  <span>候选版本</span>
                  <Segmented
                    size="small"
                    value={draft.version}
                    onChange={v => setDraft({ ...draft, version: Number(v) })}
                    options={canary.versions
                      // 候选版本不能就是当前生效那一版 —— 那样两组跑的是同一份提示词，
                      // 实验什么都证明不了。后端会拒绝，但这里干脆不让你选（少一次白跑）
                      .filter(v => v.version !== canary.activeVersion)
                      .map(v => ({ label: `v${v.version}${v.forkedFrom ? '（复制品）' : ''}`, value: v.version }))}
                  />
                  <span>放量</span>
                  <InputNumber
                    size="small"
                    min={1}
                    max={99}
                    value={draft.weight}
                    onChange={v => setDraft({ ...draft, weight: v ?? 20 })}
                    suffix="%"
                  />
                </Flex>
                <Input
                  size="small"
                  className="canary-note"
                  placeholder="这次实验想验证什么（必填：不写的话一周后没人记得，唯一结局是忘了关）"
                  value={draft.note}
                  onChange={e => setDraft({ ...draft, note: e.target.value })}
                />
                <Flex gap={12} align="center" wrap>
                  <Button
                    size="small"
                    type="primary"
                    disabled={alerts.busy || draft.note.trim() === ''}
                    onClick={() =>
                      void alerts.start({
                        kind: canary.kind,
                        candidateVersion: draft.version,
                        weightPercent: draft.weight,
                        note: draft.note,
                      })
                    }
                  >
                    开灰度
                  </Button>
                  <Typography.Text type="secondary">
                    重量必须在 1..99：0 等于没开却看起来开着，100 等于全量发布（那该走 Prompt 面板的「生效」）。
                  </Typography.Text>
                </Flex>
              </>
            )}
          </Flex>
        )}
      </Flex>
    </Card>
  )
}

/**
 * 配置与实际分布并排。
 *
 * <h2>★ 为什么这两栏必须同屏（这一件最容易做错的地方）</h2>
 * 一个只显示"配置了 20%"的界面会让人以为实验在进行中 ——
 * 而"配置 20%、实际一份都没分到"是一件完全可能发生、并且**不报任何错**的事
 * （分流函数没接上、窗口太短、运行 id 恒为同一个值）。
 * 那次实验等于没做，而使用者以为做过了 —— 并据此得出"新版没有区别"这个结论。
 *
 * <h2>为什么样本不足时要把"偏了多少"藏起来</h2>
 * 比例是一个**小样本下毫无意义**的数：3 份里分到 1 份既可能是 33%，
 * 也可能只是 10% 的重量碰巧。把 33% 与配置的 10% 并排摆出来，
 * 读的人一定会得出"分流偏了"这个结论 —— 而那是错的。
 * 所以这里显式给出"还差几份样本"。
 */
function SplitCard({ canary }: { canary: CanaryResponse }) {
  const split = canary.split
  return (
    <div className="canary-split">
      <Flex gap={24} wrap className="alert-stats">
        <Stat label="配置的重量" value={`${split.weightPercent}%`} tone="neutral" />
        <Stat label="参与分流的运行" value={split.participatingRuns} tone={split.noTraffic ? 'bad' : 'neutral'} />
        <Stat label="走候选版（实验组）" value={split.candidateRuns} tone={split.candidateRuns > 0 ? 'warn' : 'neutral'} />
        <Stat label="走稳定版（对照组）" value={split.stableRuns} tone="neutral" />
        <Stat
          label="实测占比"
          value={split.sampleEnough ? `${(split.observedRatio * 100).toFixed(2)}%` : '样本不足'}
          tone="neutral"
        />
      </Flex>

      <Alert
        className="canary-split-note"
        type={split.noTraffic ? 'warning' : split.sampleEnough ? 'info' : 'info'}
        showIcon
        title={split.noTraffic ? '这次实验等于没做' : split.sampleEnough ? '分流已可判定' : '样本还不够下结论'}
        description={split.note}
      />

      <Table
        size="small"
        rowKey={row => `${row.variant}-${row.version}`}
        dataSource={split.versions}
        pagination={false}
        columns={[
          { title: '版本', dataIndex: 'version', width: 90, render: (v: number) => `v${v}` },
          { title: '分组', dataIndex: 'variantLabel', width: 200 },
          { title: '运行数', dataIndex: 'runs', width: 100 },
        ]}
      />

      <Typography.Text type="secondary" className="canary-split-caveat">
        ⚠️ 这一栏回答的是「分流有没有按比例发生」，不是「新版更好还是更差」——
        后者要一批问题 + 一套评判口径，本项目还没有（与"自愈率没有真实数字"同族）。
      </Typography.Text>
    </div>
  )
}

// ---------------------------------------------------------------------------
//  判据目录
// ---------------------------------------------------------------------------

function RulesCard({ rules }: { rules: AlertRulesResponse }) {
  const observed = rules.rules.filter(r => r.phase === 'OBSERVE')
  const gates = rules.rules.filter(r => r.phase === 'GATE')
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide alert-rules-card"
      title={
        <Flex gap={12} align="center" wrap>
          <span>判据目录：「什么算异常」的唯一一份定义</span>
          <Tag color="cyan">共 {rules.rules.length} 条</Tag>
        </Flex>
      }
    >
      <Flex vertical gap={12}>
        <Typography.Text type="secondary">
          阈值写死在判据旁边（枚举里），不从配置读：一个可以被调低的阈值会在
          「告警响了但没人管」的时候被顺手调低 —— 而那一刻它就不再是判据了。
          当前两个阈值：连续空命中 <strong>{rules.thresholds.consecutiveEmptyRetrievals}</strong> 次、
          灰度分流最小样本 <strong>{rules.thresholds.canaryMinSample}</strong> 份、
          允许相对偏离 <strong>{(rules.thresholds.canarySplitTolerance * 100).toFixed(0)}%</strong>。
        </Typography.Text>

        <RuleTable
          title="运行阶段（这一页会评估它们）"
          note="上线后报：它响了说明已经在跑的这一版有问题。"
          rows={observed}
        />

        {/*
          ★ 门禁那一段必须存在，而且必须写明"这里不评估"。
          它们是同一个判据的另一个时间点（构建阶段）—— 只列运行阶段的话，
          读这个页面的人会以为"这个项目只检查这几件事"，
          而实际上还有四道门禁在 CI 上挡着。
        */}
        <RuleTable
          title="构建阶段（在 CI 上评估，这一页不评估）"
          note="上线前挡：它红了就说明这次改动不该合进去。"
          rows={gates}
        />

        {rules.notes.length > 0 && (
          <ul className="diag-notes">
            {rules.notes.map(note => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        )}
      </Flex>
    </Card>
  )
}

function RuleTable({ title, note, rows }: { title: string; note: string; rows: AlertRulesResponse['rules'] }) {
  return (
    <Flex vertical gap={6} className="alert-rule-section">
      <Flex gap={8} align="center" wrap>
        <Typography.Text strong>{title}</Typography.Text>
        <Typography.Text type="secondary">{note}</Typography.Text>
      </Flex>
      <Table
        size="small"
        rowKey="code"
        dataSource={rows}
        pagination={false}
        columns={[
          {
            title: '严重度',
            dataIndex: 'severity',
            width: 90,
            render: (severity: AlertSeverity, row) => (
              <Tag color={severityColor(severity)}>{row.severityLabel}</Tag>
            ),
          },
          { title: '代号', dataIndex: 'code', width: 240, render: (code: string) => <code>{code}</code> },
          { title: '这条判据是什么', dataIndex: 'title' },
          {
            title: '谁在执行',
            dataIndex: 'executedBy',
            width: 320,
            render: (executedBy: string, row) => (
              <Flex vertical gap={2}>
                <span>{executedBy}</span>
                {!row.evaluatedHere && (
                  <Typography.Text type="secondary">这一页不评估它 —— 结论要去 CI 上看</Typography.Text>
                )}
              </Flex>
            ),
          },
        ]}
      />
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  小工具
// ---------------------------------------------------------------------------

function severityColor(severity: AlertSeverity): string {
  if (severity === 'BLOCKER') return 'red'
  if (severity === 'WARNING') return 'orange'
  return 'blue'
}

/** 秒 → "3 分 20 秒" / "2 小时" / "5 天"。**不用相对时间库**：这里只要一个量级。 */
function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '刚刚'
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return `${hours} 小时`
  return `${Math.floor(hours / 24)} 天`
}

/** ISO 串 → "09-18 14:03"。空串原样返回（后端用空串表示"没有这个时间"）。 */
function formatTime(iso: string): string {
  if (!iso) return '（无）'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
