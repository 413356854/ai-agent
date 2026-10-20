/**
 * Graph 演示页 —— 真实模型跑一张 9 节点的 RepoOps 生命周期图。
 *
 * 页面分五块：输入、拓扑、节点轨迹、最终状态、检查点历史。
 * 拓扑用固定布局的 SVG 画；节点/边的清单以 `/meta` 为准，前端布局常量只回答“画在哪”。
 * 这样后端加了节点而前端没画时，页面会显示一条一致性警告，而不是静默少一个节点。
 */
import { useState } from 'react'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Collapse,
  Descriptions,
  Empty,
  Flex,
  Input,
  Segmented,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type {
  GraphCheckpoint,
  GraphIssueType,
  GraphMetaResponse,
  GraphNodeMeta,
  GraphNodeTrace,
  GraphRunResponse,
} from '../api/graph'
import type { UseGraphDemoResult } from '../hooks/useGraphDemo'

const NODE_W = 150
const NODE_H = 54

const NODE_LAYOUT: Record<string, { x: number, y: number }> = {
  triage: { x: 24, y: 44 },
  route_issue: { x: 210, y: 44 },
  bug_diagnoser: { x: 24, y: 154 },
  feature_planner: { x: 210, y: 154 },
  question_responder: { x: 396, y: 154 },
  implementer: { x: 117, y: 274 },
  auditor: { x: 690, y: 274 },
  reviewer: { x: 117, y: 394 },
  review_gate: { x: 117, y: 504 },
}

const DEFAULT_TITLE = 'Regression: CI fails after dependency bump'
const DEFAULT_SUMMARY = 'CI log indicates a deterministic unit test regression. The failure appeared after a dependency bump.'

export function GraphDemoPanel({ graph }: { graph: UseGraphDemoResult }) {
  const { modal } = AntApp.useApp()
  const { meta, result, feedback, busy, replaying, run, replay, loadMeta } = graph
  const [title, setTitle] = useState(DEFAULT_TITLE)
  const [type, setType] = useState<GraphIssueType>('bug')
  const [summary, setSummary] = useState(DEFAULT_SUMMARY)
  const [force, setForce] = useState(false)

  if (meta.status === 'idle' || meta.status === 'loading') {
    return (
      <div className="diag-panel graph-panel">
        <Spin description="正在加载 Graph 元信息…" />
      </div>
    )
  }

  if (meta.status === 'error') {
    return (
      <div className="diag-panel graph-panel">
        <Alert
          type="error"
          showIcon
          title="Graph 演示不可用"
          description={`${meta.error}　后端可能没启动，或 ai.graph-demo.enabled=false（整族端点 404）。`}
        />
        <Flex>
          <Button onClick={loadMeta}>重试</Button>
        </Flex>
      </div>
    )
  }

  const metaData = meta.data
  const plannedCalls = metaData.modelCalls[type]
    + (force && type !== 'question' ? metaData.modelCalls.forcedFirstReviewFailExtra : 0)
  const canRun = title.trim().length > 0 && summary.trim().length > 0 && !busy
  const resultData = result.status === 'ok' ? result.data : null

  return (
    <div className="diag-panel graph-panel">
      <Card className="diag-card diag-card-wide" title="Graph 演示：RepoOps 生命周期">
        <Flex vertical gap={12}>
          <Alert
            type="warning"
            showIcon
            title="真实模型演示"
            description={`每次运行会串行调用 3–7 次模型（当前模型 ${metaData.model ?? '未上报'}），单次通常 30–90 秒；检查点由 MemorySaver 保存在进程内，后端重启后清空。演示直接调 ChatModel，本次 token 不记入 agent_run / 成本看板。`}
          />
          <Flex gap={12} wrap>
            <div className="graph-input-title">
              <Typography.Text type="secondary">issue 标题</Typography.Text>
              <Input
                value={title}
                maxLength={200}
                onChange={e => setTitle(e.target.value)}
                placeholder="例如：登录接口偶发 504"
              />
            </div>
            <div>
              <Typography.Text type="secondary">初始类型（模型路由优先）</Typography.Text>
              <Segmented
                value={type}
                onChange={value => setType(value as GraphIssueType)}
                options={[
                  { label: '缺陷 bug', value: 'bug' },
                  { label: '需求 feature', value: 'feature' },
                  { label: '答疑 question', value: 'question' },
                ]}
              />
            </div>
          </Flex>
          <div>
            <Typography.Text type="secondary">issue 描述</Typography.Text>
            <Input.TextArea
              value={summary}
              rows={5}
              maxLength={4000}
              showCount
              onChange={e => setSummary(e.target.value)}
              placeholder="描述现象、期望、复现方式或缺失的信息"
            />
          </div>
          <Flex gap={16} align="center" wrap>
            <Flex gap={8} align="center">
              <Switch
                checked={force}
                disabled={type === 'question' || busy}
                onChange={setForce}
              />
              <Typography.Text>强制首轮评审 FAIL（演示回环）</Typography.Text>
            </Flex>
            <Typography.Text type="secondary">
              {type === 'question'
                ? 'question 分支不进入实现/评审，预计 3 次模型调用'
                : `预计 ${plannedCalls} 次模型调用`}
            </Typography.Text>
            <Button
              type="primary"
              loading={busy}
              disabled={!canRun}
              onClick={() => void run({ title, type, summary, forceFirstReviewFail: force })}
            >
              运行图谱（真实模型）
            </Button>
          </Flex>
          {feedback.status === 'ok' && (
            <Alert type="success" showIcon title={feedback.data.message} />
          )}
          {feedback.status === 'error' && result.status === 'ok' && (
            <Alert type="error" showIcon title="请求失败" description={feedback.error} />
          )}
          <Typography.Text type="secondary" className="diag-note">
            拓扑：triage 分诊 → 确定性路由 → bug/feature/question 三路 → 实现 → 独立评审 → 评审闸门。
            FAIL 回到实现（最多 2 轮）；question 直接进审计。模型给出的 route_candidate 优先，
            这里选的类型是回退。
          </Typography.Text>
        </Flex>
      </Card>

      <Card className="diag-card diag-card-wide" title="图拓扑">
        <GraphTopology meta={metaData} result={resultData} />
      </Card>

      <Card className="diag-card diag-card-wide" title="节点轨迹">
        {result.status === 'idle' ? (
          <Empty description="还没有运行。填好输入后点「运行图谱」。" />
        ) : result.status === 'loading' ? (
          <Spin description="真实模型运行中…bug/feature 通常 30–90 秒" />
        ) : result.status === 'error' ? (
          <Alert type="error" showIcon title="Graph 运行失败" description={result.error} />
        ) : (
          <Flex vertical gap={12}>
            <RunSummary data={result.data} />
            <TraceTable data={result.data} />
          </Flex>
        )}
      </Card>

      {resultData && (
        <Card className="diag-card diag-card-wide" title="最终状态">
          <FinalState data={resultData} />
        </Card>
      )}

      {resultData && (
        <Card className="diag-card diag-card-wide" title="检查点历史与回放">
          <Typography.Text type="secondary" className="diag-note">
            检查点由 MemorySaver 保存在进程内。回放会从所选检查点之后重新执行节点，
            可能再次调用真实模型并产生费用；模型输出也可能与第一次不同。
          </Typography.Text>
          <CheckpointTable
            data={resultData}
            busy={busy}
            replaying={replaying}
            onReplay={(checkpointId) => {
              modal.confirm({
                title: '从检查点回放？',
                content: resultData.kind === 'replay'
                  ? '这会从所选检查点之后重新执行节点，可能再次调用真实模型并产生费用。'
                  : '这会从所选检查点之后重新执行节点，可能再次调用真实模型并产生费用；结果也可能与第一次不同。',
                okText: '回放',
                cancelText: '取消',
                onOk: () => replay(resultData.threadId, checkpointId),
              })
            }}
          />
        </Card>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
//  拓扑
// ---------------------------------------------------------------------------

function GraphTopology({ meta, result }: { meta: GraphMetaResponse, result: GraphRunResponse | null }) {
  const visited = new Set((result?.nodes ?? []).map(node => node.nodeId))
  const replayed = new Set(result?.replayedNodeIds ?? [])
  const route = result?.route
  const gateResult = result?.reviewGate.result
  const gateFailed = (result?.nodes ?? []).some(node => node.nodeId === 'review_gate' && node.outcome === 'fail')
  const layoutIds = Object.keys(NODE_LAYOUT)
  const mismatch = layoutIds.length !== meta.nodes.length || meta.nodes.some(node => !NODE_LAYOUT[node.id])

  const edgeClass = (active: boolean, loop = false) => {
    const classes = ['graph-edge']
    if (active) classes.push('is-active')
    if (loop) classes.push('is-loop')
    return classes.join(' ')
  }

  return (
    <Flex vertical gap={8}>
      {mismatch && (
        <Alert
          type="warning"
          showIcon
          title="前端拓扑与后端节点清单不一致"
          description="GraphDemoPanel 的 NODE_LAYOUT 需要同步 /api/graph-demo/meta 的 nodes。"
        />
      )}
      <div className="graph-map">
        <svg viewBox="0 0 940 600" role="img" aria-label="Graph 演示拓扑">
          <defs>
            <marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
              <path className="graph-arrow-head" d="M0,0 L8,4 L0,8 z" />
            </marker>
          </defs>

          <text className="graph-edge-label" x="8" y="26">START</text>
          <text className="graph-edge-label" x="884" y="290">END</text>

          <path className={edgeClass(visited.has('triage'))} d="M 8 34 L 8 71 L 24 71" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(visited.has('route_issue'))} d="M 174 71 L 210 71" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(route === 'bug')} d="M 285 98 L 285 122 L 99 122 L 99 154" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(route === 'feature')} d="M 285 98 L 285 154" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(route === 'question')} d="M 285 98 L 285 122 L 471 122 L 471 154" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(visited.has('implementer'))} d="M 99 208 L 99 238 L 192 238 L 192 274" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(visited.has('implementer'))} d="M 285 208 L 285 238 L 192 238 L 192 274" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(route === 'question' && visited.has('auditor'))} d="M 546 181 L 650 181 L 650 301 L 690 301" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(visited.has('reviewer'))} d="M 192 328 L 192 394" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(visited.has('review_gate'))} d="M 192 448 L 192 504" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(gateResult === 'pass')} d="M 267 531 L 620 531 L 620 328 L 765 328" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(gateFailed || gateResult === 'fail', true)} d="M 117 531 L 40 531 L 40 301 L 117 301" markerEnd="url(#graph-arrow)" />
          <path className={edgeClass(visited.has('auditor'))} d="M 840 301 L 884 301" markerEnd="url(#graph-arrow)" />

          <text className="graph-edge-label" x="112" y="116">bug</text>
          <text className="graph-edge-label" x="296" y="116">feature</text>
          <text className="graph-edge-label" x="480" y="116">question</text>
          <text className="graph-edge-label" x="500" y="520">PASS</text>
          <text className="graph-edge-label" x="46" y="420">FAIL 回环</text>

          {meta.nodes.map(node => {
            const pos = NODE_LAYOUT[node.id]
            if (!pos) return null
            return (
              <GraphNodeBox
                key={node.id}
                node={node}
                x={pos.x}
                y={pos.y}
                visited={visited.has(node.id)}
                replayed={replayed.has(node.id)}
                failed={node.id === 'review_gate' && gateResult === 'fail'}
              />
            )
          })}
        </svg>
      </div>
      <Space size={12} wrap className="graph-legend">
        <span><i className="graph-legend-box is-model" /> 模型节点</span>
        <span><i className="graph-legend-box is-deterministic" /> 确定性节点</span>
        <span><i className="graph-legend-box is-visited" /> 本次已执行</span>
        <span><i className="graph-legend-box is-replayed" /> 本次重放</span>
        <span><i className="graph-legend-box is-failed" /> 评审 FAIL</span>
      </Space>
    </Flex>
  )
}

function GraphNodeBox({ node, x, y, visited, replayed, failed }: {
  node: GraphNodeMeta
  x: number
  y: number
  visited: boolean
  replayed: boolean
  failed: boolean
}) {
  const classes = ['graph-node']
  classes.push(node.usesModel ? 'is-model' : 'is-deterministic')
  if (visited) classes.push('is-visited')
  if (replayed) classes.push('is-replayed')
  if (failed) classes.push('is-failed')
  return (
    <g className={classes.join(' ')} data-node={node.id}>
      <title>{node.description}</title>
      <rect x={x} y={y} width={NODE_W} height={NODE_H} rx={8} />
      <text x={x + NODE_W / 2} y={y + 22}>{node.label}</text>
      <text className="graph-node-sub" x={x + NODE_W / 2} y={y + 40}>
        {node.usesModel ? 'model' : 'deterministic'}
      </text>
    </g>
  )
}

// ---------------------------------------------------------------------------
//  运行摘要与轨迹
// ---------------------------------------------------------------------------

function RunSummary({ data }: { data: GraphRunResponse }) {
  const gate = data.reviewGate
  return (
    <Flex vertical gap={8}>
      <Flex gap={8} align="center" wrap>
        <Tag color="blue">route: {data.route}</Tag>
        <Typography.Text type="secondary">{data.routeReason}</Typography.Text>
        <Tag color={gate.result === 'pass' ? 'green' : 'red'}>gate: {gate.result}</Tag>
        {gate.skipped && <Tag>skipped</Tag>}
        {gate.forced && <Tag color="orange">forced</Tag>}
        {gate.degraded && <Tag color="orange">degraded</Tag>}
        <Typography.Text type="secondary">
          第 {gate.round} / {gate.maxRounds} 轮 · 总耗时 {(data.elapsedMs / 1000).toFixed(1)} 秒
        </Typography.Text>
      </Flex>
      <Flex gap={8} align="center" wrap>
        <Typography.Text type="secondary">threadId</Typography.Text>
        <Typography.Text code copyable>{data.threadId}</Typography.Text>
        <Typography.Text type="secondary">runId</Typography.Text>
        <Typography.Text code copyable>{data.runId}</Typography.Text>
        {data.kind === 'replay' && (
          <Tag color="purple">replay · 重放 {data.replayedNodeIds.length} 个节点</Tag>
        )}
      </Flex>
      {data.kind === 'replay' && (
        <Alert
          type="info"
          showIcon
          title={`从第 ${data.replayedFromTraceIndex} 条轨迹之后回放`}
          description="回放会重新执行后续节点，可能再次调用真实模型并产生费用。"
        />
      )}
      <Alert
        type="warning"
        showIcon
        title="运行提示"
        description={<ul className="graph-warning-list">{data.warnings.map(w => <li key={w}>{w}</li>)}</ul>}
      />
    </Flex>
  )
}

function TraceTable({ data }: { data: GraphRunResponse }) {
  const columns: ColumnsType<GraphNodeTrace> = [
    { title: '#', dataIndex: 'index', width: 48 },
    {
      title: '节点',
      dataIndex: 'nodeId',
      width: 170,
      render: (_value, row) => (
        <Flex vertical gap={2}>
          <Typography.Text strong>{row.label}</Typography.Text>
          <Typography.Text type="secondary" code>{row.nodeId}</Typography.Text>
        </Flex>
      ),
    },
    {
      title: '类型',
      dataIndex: 'nodeType',
      width: 150,
      render: (_value, row) => (
        <Space size={4} wrap>
          <Tag color={row.usesModel ? 'blue' : 'default'}>{row.nodeType}</Tag>
          <Typography.Text type="secondary">{row.role}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '输入 keys',
      dataIndex: 'inputKeys',
      width: 230,
      render: (keys: string[]) => <KeyTags keys={keys} />,
    },
    {
      title: '输出 keys',
      dataIndex: 'outputKeys',
      width: 230,
      render: (keys: string[]) => <KeyTags keys={keys} />,
    },
    {
      title: '耗时',
      dataIndex: 'elapsedMs',
      width: 90,
      align: 'right',
      render: (ms: number) => `${ms} ms`,
    },
    {
      title: '摘要',
      dataIndex: 'summary',
      width: 300,
      render: (summary: string) => (
        <Typography.Text ellipsis={{ tooltip: summary }} className="graph-trace-summary">
          {summary || '—'}
        </Typography.Text>
      ),
    },
  ]
  return (
    <Table
      size="small"
      tableLayout="fixed"
      rowKey={row => `${row.index}-${row.nodeId}`}
      columns={columns}
      dataSource={data.nodes}
      pagination={false}
      scroll={{ x: 1220 }}
      rowClassName={(row) => {
        const classes: string[] = []
        if (row.usesModel) classes.push('graph-row-model')
        if (row.nodeId === 'review_gate' && row.outcome === 'fail') classes.push('graph-row-gate-fail')
        if (data.replayedNodeIds.includes(row.nodeId)) classes.push('graph-row-replayed')
        return classes.join(' ')
      }}
    />
  )
}

function KeyTags({ keys }: { keys: string[] }) {
  if (!keys || keys.length === 0) return <Typography.Text type="secondary">—</Typography.Text>
  const shown = keys.slice(0, 6)
  return (
    <Space size={4} wrap>
      {shown.map(key => <Tag key={key}>{key}</Tag>)}
      {keys.length > shown.length && <Tag>+{keys.length - shown.length}</Tag>}
    </Space>
  )
}

// ---------------------------------------------------------------------------
//  最终状态与检查点
// ---------------------------------------------------------------------------

function FinalState({ data }: { data: GraphRunResponse }) {
  const state = data.finalState
  const gate = data.reviewGate
  const panels = [
    { key: 'triage', label: '分诊结论', text: state.triageResult },
    { key: 'plan', label: '分支产出', text: state.workPlan },
    { key: 'implementation', label: '实现方案', text: state.implementation },
    { key: 'review', label: '评审原文', text: state.reviewDecision },
    { key: 'audit', label: '审计报告', text: state.auditReport },
  ].filter(panel => panel.text && panel.text.trim().length > 0)

  return (
    <Flex vertical gap={12}>
      <Descriptions size="small" column={2}>
        <Descriptions.Item label="最终路由">
          <Space size={6}>
            <Tag color="blue">{state.route || '—'}</Tag>
            <Typography.Text type="secondary">{state.routeReason}</Typography.Text>
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="模型建议">{state.triageSuggestedRoute || '—'}</Descriptions.Item>
        <Descriptions.Item label="评审闸门">
          <Space size={4} wrap>
            <Tag color={gate.result === 'pass' ? 'green' : 'red'}>{gate.result}</Tag>
            {gate.skipped && <Tag>skipped</Tag>}
            {gate.forced && <Tag color="orange">forced</Tag>}
            {gate.degraded && <Tag color="orange">degraded</Tag>}
            <Typography.Text type="secondary">{gate.reason}</Typography.Text>
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="复审轮数">{state.reviewRound}</Descriptions.Item>
        <Descriptions.Item label="模型">{data.model ?? '未上报'}</Descriptions.Item>
        <Descriptions.Item label="耗时">{(data.elapsedMs / 1000).toFixed(1)} 秒</Descriptions.Item>
        <Descriptions.Item label="threadId"><Typography.Text code copyable>{data.threadId}</Typography.Text></Descriptions.Item>
        <Descriptions.Item label="runId"><Typography.Text code copyable>{data.runId}</Typography.Text></Descriptions.Item>
      </Descriptions>
      {panels.length > 0 && (
        <Collapse
          size="small"
          items={panels.map(panel => ({
            key: panel.key,
            label: panel.label,
            children: <pre className="graph-state-text">{panel.text}</pre>,
          }))}
        />
      )}
    </Flex>
  )
}

function CheckpointTable({ data, busy, replaying, onReplay }: {
  data: GraphRunResponse
  busy: boolean
  replaying: string | null
  onReplay: (checkpointId: string) => void
}) {
  const columns: ColumnsType<GraphCheckpoint> = [
    { title: '#', dataIndex: 'index', width: 48 },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_value, row) => (
        <Button
          size="small"
          loading={replaying === row.checkpointId}
          disabled={busy || !row.checkpointId || !row.next || row.next === '__END__'}
          onClick={() => onReplay(row.checkpointId)}
        >
          回放
        </Button>
      ),
    },
    {
      title: 'checkpointId',
      dataIndex: 'checkpointId',
      width: 230,
      render: (id: string) => <Typography.Text code copyable={{ text: id }} className="graph-checkpoint-id">{id || '—'}</Typography.Text>,
    },
    { title: 'kind', dataIndex: 'kind', width: 80, render: (kind: string) => <Tag>{kind}</Tag> },
    { title: 'node', dataIndex: 'node', width: 130 },
    { title: 'next', dataIndex: 'next', width: 130 },
    {
      title: 'selected',
      dataIndex: 'selected',
      width: 300,
      render: (selected: Record<string, string | number | boolean>) => {
        const entries = Object.entries(selected ?? {})
        if (entries.length === 0) return <Typography.Text type="secondary">—</Typography.Text>
        return (
          <Flex vertical gap={2}>
            {entries.map(([key, value]) => (
              <Typography.Text key={key} type="secondary" ellipsis={{ tooltip: `${key}=${String(value)}` }}>
                {key}={String(value)}
              </Typography.Text>
            ))}
          </Flex>
        )
      },
    },
  ]
  return (
    <Table
      size="small"
      tableLayout="fixed"
      rowKey={row => `${row.index}-${row.checkpointId}`}
      columns={columns}
      dataSource={data.history}
      pagination={false}
      scroll={{ x: 1080 }}
    />
  )
}
