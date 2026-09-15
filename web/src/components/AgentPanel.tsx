import { useState } from 'react'
import { Alert, Button, Card, Empty, Flex, Input, Spin, Table, Tag, Typography } from 'antd'
import type { UseAgentResult } from '../hooks/useAgent'
import type { AgentAskResponse, AgentLimitsResponse, AgentRunDetail, AgentRunRow, AgentToolCall } from '../api/agent'
import type { Remote } from '../types'

/**
 * 「Agent 工具调用」面板（阶段三第 5 件）：**把 Agent 到底干了什么摆出来**。
 *
 * <h2>这一页存在的理由（路线文档的验收原话）</h2>
 * 「能讲清「它为什么选这个工具、失败重试几次、什么时候让人确认、
 * **日志能不能还原执行过程**」」。
 *
 * <p>前三件（ReAct 循环、持久化恢复、并发保护）做完之后，这些信息**全部已经在库里**——
 * 但界面上看不见，等于"能还原"只对会查库的人成立。
 * 所以这一页不是"加个页面"，而是**把已经存在的事实摆到能被看见的地方**。
 *
 * <h2>页面结构的三个决定</h2>
 * <ol>
 *   <li><b>答案与过程同屏</b>：只显示答案的话，"它查了 3 次、失败 2 次、最后蒙对"
 *       与"它一次就答对"长得一模一样 —— 而这个功能的全部价值就在于区分这两者；</li>
 *   <li><b>每次工具调用都显示 `stage` 与回填文本</b>：`stage` 说明"死在哪一步"，
 *       回填文本说明"模型当时看到了什么"。两者合起来才能回答"它为什么这么选"；</li>
 *   <li><b>限额状态单独一块、并写明"全局共享"</b>：闸门与熔断是**全局**的
 *       （同一把工具被所有任务共用），把它放在某次运行的详情里会让人以为是那次的属性。</li>
 * </ol>
 *
 * <h2>刻意不在这层做的判断</h2>
 * 不给"这次运行算好还是不好"的结论，也不自己判断"哪个 stage 算失败"——
 * 后者由后端的 `countsAsToolFailure` 给（判据已经在 `CircuitBreaker` 里有一份）。
 * 前端再判一次就是第二份判据，而两份必然漂移。
 */
export function AgentPanel({ agent }: { agent: UseAgentResult }) {
  return (
    <div className="diag-panel">
      <AskCard remote={agent.current} running={agent.running} onAsk={agent.ask} />

      {agent.detail.status !== 'idle' && <DetailCard remote={agent.detail} onClose={agent.closeDetail} />}

      <RunsCard remote={agent.runs} onReload={agent.loadRuns} onOpen={agent.openDetail} />

      <LimitsCard remote={agent.limits} onReload={agent.loadLimits} />
    </div>
  )
}

// ---------------------------------------------------------------------------
//  1. 跑一个任务 —— 这一页唯一的写操作
// ---------------------------------------------------------------------------

/**
 * 提问卡。
 *
 * **它必须明确标出"这一步会真的调模型花钱"**：整张页面上只有这一个按钮有外部代价，
 * 而其余三块都是只读的。不标出来的话，用户点它和点"刷新"的心理预期是一样的。
 */
function AskCard({
  remote,
  running,
  onAsk,
}: {
  remote: Remote<AgentAskResponse>
  running: boolean
  onAsk: (q: string) => void
}) {
  const [question, setQuestion] = useState('分块策略的结论是什么？')

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="跑一个任务：它怎么想的、调了什么、失败在哪一步"
      extra={<Tag color="orange">会调模型 · 花钱</Tag>}
    >
      <Flex vertical gap={10}>
        <Flex gap={8} wrap>
          <Input
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onPressEnter={() => onAsk(question)}
            placeholder="问一个需要查文档的问题（Enter 发送）"
            className="rag-input"
            disabled={running}
          />
          <Button type="primary" onClick={() => onAsk(question)} loading={running}>
            跑一次
          </Button>
        </Flex>

        {/*
          例子刻意选"需要查文档"和"不需要查文档"各一个：
          后者能让人看到 Agent 会**直接回答而不调工具** ——
          那正是它"判断要不要查"这件事被验证的样子，而不是每次都必须调工具
        */}
        <Flex gap={6} wrap align="center">
          <Typography.Text type="secondary">试试：</Typography.Text>
          {['分块策略的结论是什么？', 'token 预算的系数怎么校准的？', '你是谁？'].map(sample => (
            <Tag
              key={sample}
              className="rag-tag-clickable"
              onClick={() => {
                setQuestion(sample)
                onAsk(sample)
              }}
            >
              {sample}
            </Tag>
          ))}
        </Flex>

        {remote.status === 'idle' && (
          <Typography.Text type="secondary" className="diag-note">
            点「跑一次」会真的发起一次 Agent 任务：最多 6 轮模型往返，每轮可能调用工具。
            任务会被记进库里（下面是历史列表），答案、过程与 token 成本一起返回。
          </Typography.Text>
        )}
        {remote.status === 'loading' && (
          <LoadingLine text="Agent 正在跑（think → act → observe，最多 6 轮）……" />
        )}
        {remote.status === 'error' && <InlineError title="任务失败" message={remote.error} />}
        {remote.status === 'ok' && <AskBody data={remote.data} />}
      </Flex>
    </Card>
  )
}

function AskBody({ data }: { data: AgentAskResponse }) {
  return (
    <Flex vertical gap={14}>
      {/* ---- 结论区 ---- */}
      <div className="stat-grid">
        <Stat label="停因" value={data.stopReason} accent={data.answered} />
        <Stat label="输出 token" value={data.totalTokens} />
        <Stat label="工具调用" value={data.toolCallCount} />
        <Stat label="耗时" value={`${data.elapsedMs} ms`} />
        <Stat label="步数" value={data.steps.length} />
      </div>

      {data.answered ? (
        <div className="agent-answer">
          <Typography.Text type="secondary">最终答案</Typography.Text>
          <div className="agent-answer-text">{data.finalAnswer}</div>
        </div>
      ) : (
        /*
          没答出来时要**把原因与建议摆出来**，而不是只显示一个空答案。
          `failureAdvice` 由后端给：那里面是"下一步该做什么"，
          而前端自己编一句的话，就会与后端对失败原因的理解慢慢漂移。
        */
        <Alert
          type="warning"
          showIcon
          title={`没有给出最终答案（停因：${data.stopReason}${data.failureCause ? ` · ${data.failureCause}` : ''}）`}
          description={
            <Flex vertical gap={6}>
              <span>{data.failureAdvice || '循环被某个上限拦下了 —— 见下面每一步的过程。'}</span>
              <span>
                <strong>但已经跑过的步骤与花掉的 token 都保留了</strong>
                （{data.totalTokens} tokens / {data.steps.length} 步）——
                这正是断点恢复能"接着跑"的前提。
              </span>
            </Flex>
          }
        />
      )}

      {/* ---- 工具调用汇总：一句话说清"调了几次、分别死在哪 ---- */}
      <Typography.Text type="secondary" className="diag-note">
        {data.toolCallSummary}
      </Typography.Text>

      {/* ---- 过程时间线：这一页的主角 ---- */}
      <StepTimeline steps={data.steps} />
    </Flex>
  )
}

/**
 * 过程时间线：每一步的 think + 它要调的工具 + 每次调用的结论。
 *
 * 用**时间线**而不是表格，是因为这一页要回答的是"为什么"——
 * 而"为什么"是顺序性的（它先看了目录、再检索、失败后换了关键词）。
 * 表格擅长横向比大小，不擅长讲顺序。
 */
function StepTimeline({ steps }: { steps: AgentAskResponse['steps'] }) {
  if (steps.length === 0) return null
  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        执行过程（每一步：模型说了什么 → 要调什么 → 结果如何）
      </Typography.Title>
      <div className="agent-timeline">
        {steps.map(step => (
          <div key={step.index} className={`agent-step ${step.usedTools ? '' : 'is-answer'}`}>
            <div className="agent-step-head">
              <Tag color={step.usedTools ? 'blue' : 'green'}>
                第 {step.index} 步 · {step.usedTools ? '调工具' : '作答'}
              </Tag>
              <Typography.Text type="secondary">
                prompt {step.promptTokens} / completion {step.completionTokens} · {step.elapsedMs} ms
              </Typography.Text>
            </div>

            {step.thoughtText && (
              <div className="agent-thought">
                <span className="agent-thought-label">模型这一轮说：</span>
                {step.thoughtText}
              </div>
            )}

            {step.toolCalls.map((call, i) => (
              <ToolCallRow key={`${step.index}-${i}`} call={call} />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * 一次工具调用。
 *
 * **`stage` 用中文短语显示，而不是原样打印枚举名**：`VALIDATION_ERROR` 对开发者清楚，
 * 对读页面的人是一串噪音。但**枚举名仍然要能查到**（放在 title 里），
 * 否则排查时无法把页面上的现象和日志里的字样对上。
 */
function ToolCallRow({ call }: { call: AgentToolCall }) {
  const meta = STAGE_META[call.stage] ?? { label: call.stage, color: 'default' }
  return (
    <div className={`agent-tool-call ${call.countsAsToolFailure ? 'is-failure' : ''}`}>
      <Flex gap={8} wrap align="center" className="agent-tool-head">
        <Tag color={meta.color} title={call.stage}>
          {meta.label}
        </Tag>
        <Typography.Text strong>{call.toolName}</Typography.Text>
        <Typography.Text type="secondary">{call.elapsedMs} ms</Typography.Text>
        {/*
          这一条只在"工具侧失败"时出现，而那个判断来自后端（countsAsToolFailure）。
          前端不自己判：判据在 CircuitBreaker 里已经有一份，再判一次必然漂移
        */}
        {call.countsAsToolFailure && (
          <Tag color="red" title="这个 stage 会被计入熔断失败率">
            计入熔断
          </Tag>
        )}
      </Flex>

      <div className="agent-tool-args">
        <span className="agent-tool-label">参数：</span>
        <code>{call.arguments || '{}'}</code>
      </div>

      <div className="agent-tool-feedback">
        <span className="agent-tool-label">回填给模型的内容：</span>
        <pre>{call.feedback}</pre>
      </div>

      {meta.hint && (
        <Typography.Text type="secondary" className="diag-note">
          {meta.hint}
        </Typography.Text>
      )}
    </div>
  )
}

/**
 * stage 的中文说明。
 *
 * `hint` 只对**需要解释**的几种给 —— 全部都给会让页面被注解淹掉，
 * 而"OK"这种不需要解释。
 */
const STAGE_META: Record<string, { label: string; color: string; hint?: string }> = {
  OK: { label: '成功', color: 'green' },
  UNKNOWN_TOOL: {
    label: '工具不存在',
    color: 'orange',
    hint: '模型编了一个工具名。回填里带了可用工具列表，所以它下一轮通常能改对 —— 这是"自愈"最常见的形态。',
  },
  PARSE_ERROR: {
    label: '参数不是合法 JSON',
    color: 'orange',
    hint: '半截 JSON 或不认识的字段。回填里带了完整的 schema。',
  },
  VALIDATION_ERROR: {
    label: '参数不合法',
    color: 'orange',
    hint: 'JSON 合法但过不了约束（缺必填、越界）。回填里带了具体字段名 —— 这一类最容易被模型自己修好。',
  },
  EXECUTION_ERROR: {
    label: '工具执行出错',
    color: 'red',
    hint: '参数没问题、工具自己炸了。**只有这一类会计入熔断失败率** —— 模型写错参数不算工具故障。',
  },
  REQUEST_REJECTED: {
    label: '被并发限制拒绝',
    color: 'gold',
    hint: '本机闸门满了，这次调用没有执行。回填会告诉模型「稍后用同样的参数重试是有意义的」。',
  },
  CIRCUIT_OPEN: {
    label: '熔断打开',
    color: 'red',
    hint: '这把工具的下游最近失败率过高，这次调用压根没有发出去。回填会告诉模型「别再用同样的参数重试」。',
  },
}

// ---------------------------------------------------------------------------
//  2. 历史运行
// ---------------------------------------------------------------------------

/**
 * 历史列表。
 *
 * **`awaitingRecovery > 0` 要显眼**：默认配置下不会自动恢复
 * （`ai.agent.store.auto-resume-on-startup=false`），所以那些被中断的运行会一直躺在库里。
 * 不说出来的话，现象是"那个任务永远没结果"，而没有人知道它其实在等着被恢复。
 */
function RunsCard({
  remote,
  onReload,
  onOpen,
}: {
  remote: Remote<AgentRunList>
  onReload: () => void
  onOpen: (runId: string) => void
}) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="历史运行：每次任务都记在库里"
      extra={
        <Button size="small" onClick={onReload} loading={remote.status === 'loading'}>
          刷新
        </Button>
      }
    >
      {remote.status === 'loading' && <LoadingLine text="正在读运行列表……" />}
      {remote.status === 'error' && <InlineError title="运行列表读取失败" message={remote.error} />}
      {remote.status === 'ok' && <RunsBody data={remote.data} onOpen={onOpen} />}
    </Card>
  )
}

/** 局部类型别名，避免在一个文件里反复写泛型参数 */
type AgentRunList = import('../api/agent').AgentRunsResponse

function RunsBody({ data, onOpen }: { data: AgentRunList; onOpen: (id: string) => void }) {
  return (
    <Flex vertical gap={12}>
      {data.awaitingRecovery > 0 && (
        <Alert
          type="warning"
          showIcon
          title={`有 ${data.awaitingRecovery} 次运行被中断，正在等待恢复`}
          description="当前配置不会自动恢复（ai.agent.store.auto-resume-on-startup=false）。它们会一直留在库里，直到有人打开该开关或手工调用恢复接口。"
        />
      )}

      {data.runs.length === 0 ? (
        <Empty description="还没有运行记录 —— 上面跑一次就会出现" />
      ) : (
        <Table
          size="small"
          rowKey="id"
          pagination={{ pageSize: 8, size: 'small' }}
          dataSource={data.runs}
          onRow={row => ({ onClick: () => onOpen(row.id), style: { cursor: 'pointer' } })}
          columns={[
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (status: string, row: AgentRunRow) => {
                const meta = STATUS_META[status] ?? { label: status, color: 'default' }
                return (
                  <Tag color={meta.color} title={row.failureCause ?? undefined}>
                    {meta.label}
                  </Tag>
                )
              },
            },
            { title: '问题', dataIndex: 'question', ellipsis: true },
            {
              title: '停因 / 失败细分',
              width: 220,
              render: (_: unknown, row: AgentRunRow) =>
                row.failureCause ? (
                  /*
                    失败细分必须显示出来：`MODEL_ERROR` 这一个出口下面藏着
                    鉴权失败 / 限流 / 超时 / 连不上 / **我们自己的并发闸门拒绝** ——
                    而"太挤"与"上游挂了"的排查方向完全相反
                  */
                  <Typography.Text type="danger">{row.failureCause}</Typography.Text>
                ) : (
                  <Typography.Text type="secondary">{row.stopReason ?? '-'}</Typography.Text>
                ),
            },
            { title: '工具', dataIndex: 'toolCalls', width: 70, align: 'right' as const },
            { title: 'token', dataIndex: 'totalTokens', width: 90, align: 'right' as const },
            {
              title: '耗时',
              dataIndex: 'elapsedMs',
              width: 90,
              align: 'right' as const,
              render: (ms: number) => `${ms} ms`,
            },
          ]}
        />
      )}
    </Flex>
  )
}

/** 运行状态的中文说明。与后端 `RunStatus` 一一对应。 */
const STATUS_META: Record<string, { label: string; color: string }> = {
  ANSWERED: { label: '已回答', color: 'green' },
  STOPPED: { label: '被上限拦下', color: 'orange' },
  FAILED: { label: '失败', color: 'red' },
  RUNNING: { label: '运行中', color: 'blue' },
  INTERRUPTED: { label: '被中断', color: 'gold' },
}

// ---------------------------------------------------------------------------
//  3. 某一次运行的详情
// ---------------------------------------------------------------------------

function DetailCard({ remote, onClose }: { remote: Remote<AgentRunDetail>; onClose: () => void }) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="运行详情：还原这一次的执行过程"
      extra={
        <Button size="small" onClick={onClose}>
          关闭
        </Button>
      }
    >
      {remote.status === 'loading' && <LoadingLine text="正在读这次运行的完整记录……" />}
      {remote.status === 'error' && <InlineError title="详情读取失败" message={remote.error} />}
      {remote.status === 'ok' && <DetailBody data={remote.data} />}
    </Card>
  )
}

function DetailBody({ data }: { data: AgentRunDetail }) {
  if (!data.ok) {
    return <InlineError title="读不到这次运行" message={data.error ?? '未知原因'} />
  }

  return (
    <Flex vertical gap={14}>
      <div className="stat-grid">
        <Stat label="状态" value={STATUS_META[data.run.status]?.label ?? data.run.status} />
        <Stat label="停因" value={data.run.stopReason || '-'} />
        <Stat label="token" value={data.run.totalTokens} />
        <Stat label="工具调用" value={data.run.toolCalls} />
        <Stat label="耗时" value={`${data.run.elapsedMs} ms`} />
        <Stat label="实例" value={data.run.node || '-'} />
      </div>

      <Typography.Text type="secondary" className="diag-note">
        问题：<strong>{data.run.question}</strong>
      </Typography.Text>

      {data.run.failureCause && (
        <Alert type="error" showIcon title={`失败细分：${data.run.failureCause}`} description={data.run.failureAdvice} />
      )}

      {data.run.finalAnswer && (
        <div className="agent-answer">
          <Typography.Text type="secondary">最终答案</Typography.Text>
          <div className="agent-answer-text">{data.run.finalAnswer}</div>
        </div>
      )}

      {/*
        步骤表里显示 `transcriptChars`：它证明**每一步都存了完整对话检查点**。
        没有它的话，"能断点恢复"在页面上就没有任何痕迹 ——
        而那正是阶段三第 2 件的核心产出
      */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          步骤（每步一个检查点）
        </Typography.Title>
        <div className="rag-table-scroll">
          <table className="rag-metric-table">
            <thead>
              <tr>
                <th>步</th>
                <th>模型说了什么</th>
                <th>prompt</th>
                <th>completion</th>
                <th>耗时</th>
                <th>检查点上下文</th>
              </tr>
            </thead>
            <tbody>
              {data.steps.map(step => (
                <tr key={step.stepIndex ?? step.index}>
                  <td className="is-mono">{step.stepIndex ?? step.index}</td>
                  <td className="is-excerpt">{step.thoughtText || <span className="is-dim">（这一步没有文字，直接调工具）</span>}</td>
                  <td className="is-mono">{step.promptTokens}</td>
                  <td className="is-mono">{step.completionTokens}</td>
                  <td className="is-mono">{step.elapsedMs}</td>
                  <td className="is-mono">{step.transcriptChars ?? 0} 字符</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div>
        <Typography.Title level={5} className="rag-subtitle">
          每次工具调用（含被拒绝的）
        </Typography.Title>
        <div className="rag-table-scroll">
          <table className="rag-metric-table">
            <thead>
              <tr>
                <th>步</th>
                <th>工具</th>
                <th>结果</th>
                <th>耗时</th>
                <th>计入熔断</th>
                <th>参数</th>
              </tr>
            </thead>
            <tbody>
              {data.toolCalls.map(call => {
                const meta = STAGE_META[call.stage] ?? { label: call.stage, color: 'default' }
                return (
                  <tr key={`${call.stepIndex}-${call.callIndex}`}>
                    <td className="is-mono">{call.stepIndex}</td>
                    <td className="is-strategy">{call.toolName}</td>
                    <td>
                      <Tag color={meta.color} title={call.stage}>
                        {meta.label}
                      </Tag>
                    </td>
                    <td className="is-mono">{call.elapsedMs}</td>
                    <td className="is-mono">{call.countsAsToolFailure ? '是' : '否'}</td>
                    <td className="is-excerpt">
                      <code>{call.arguments || '{}'}</code>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <Typography.Text type="secondary" className="diag-note">
          「计入熔断」这一列由**后端**判定（只有"工具执行出错"算失败）——
          模型写错参数、被限流拒绝都不算工具故障。页面不自己判，
          否则就会有第二份判据，而两份必然漂移。
        </Typography.Text>
      </div>
    </Flex>
  )
}

// ---------------------------------------------------------------------------
//  4. 四层保护的状态
// ---------------------------------------------------------------------------

/**
 * 限额与熔断状态。
 *
 * **必须写明"全局共享"**：这些数字不属于某一次运行 ——
 * 同一把工具被所有任务共用，所以"某个任务把工具打熔断了"会影响所有人。
 * 放在运行详情里会让人以为是那次的属性，而那是错的。
 */
function LimitsCard({ remote, onReload }: { remote: Remote<AgentLimitsResponse>; onReload: () => void }) {
  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title="四层保护：并发闸门 / 排队超时 / 熔断"
      extra={
        <Flex gap={8} align="center">
          <Tag>全局共享</Tag>
          <Button size="small" onClick={onReload} loading={remote.status === 'loading'}>
            刷新
          </Button>
        </Flex>
      }
    >
      {remote.status === 'loading' && <LoadingLine text="正在读限额状态……" />}
      {remote.status === 'error' && <InlineError title="限额状态读取失败" message={remote.error} />}
      {remote.status === 'ok' && <LimitsBody data={remote.data} />}
    </Card>
  )
}

function LimitsBody({ data }: { data: AgentLimitsResponse }) {
  const { config, modelGate, tools, totals } = data

  return (
    <Flex vertical gap={14}>
      <div className="stat-grid">
        <Stat label="模型并发上限" value={config.maxConcurrentModelRequests} />
        <Stat label="工具并发上限（每把）" value={config.maxConcurrentToolRequests} />
        <Stat label="排队超时" value={`${config.acquireTimeoutMs} ms`} />
        <Stat label="熔断" value={config.circuitEnabled ? '已启用' : '已关闭'} accent={config.circuitEnabled} />
        <Stat label="被拒绝次数" value={totals.totalRejected} accent={totals.totalRejected > 0} />
        <Stat label="熔断打开数" value={totals.openCircuits} accent={totals.openCircuits > 0} />
      </div>

      {/* ---- 模型侧闸门 ---- */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          模型侧闸门
        </Typography.Title>
        {modelGate.available ? (
          <div className="stat-grid">
            <Stat label="在途" value={`${modelGate.inFlight ?? 0} / ${modelGate.maxConcurrent}`} />
            <Stat label="在途峰值" value={modelGate.peakInFlight ?? 0} />
            <Stat label="已放行" value={modelGate.acquired ?? 0} />
            <Stat label="被拒绝" value={modelGate.rejected ?? 0} accent={(modelGate.rejected ?? 0) > 0} />
            <Stat label="平均排队" value={`${modelGate.averageWaitMs ?? 0} ms`} />
            <Stat label="最长排队" value={`${modelGate.maxWaitMs ?? 0} ms`} />
          </div>
        ) : (
          /*
            【为什么明确显示"未启用"而不是一堆 0】
            一堆 0 看起来像"闸门在工作但没有流量"，而真相是"这一层没装上"。
            两者对排查的含义完全不同：前者正常，后者是缺陷。
          */
          <Alert type="info" showIcon title="模型侧并发闸门未启用" description={modelGate.note} />
        )}
      </div>

      {/* ---- 每把工具 ---- */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          每把工具（闸门与熔断并排 —— 它们是两种不同的病）
        </Typography.Title>
        {tools.length === 0 ? (
          <Empty description="还没有工具被调用过 —— 跑一次任务就会出现" />
        ) : (
          <div className="rag-table-scroll">
            <table className="rag-metric-table">
              <thead>
                <tr>
                  <th>工具</th>
                  <th>在途 / 上限</th>
                  <th>在途峰值</th>
                  <th>被拒绝</th>
                  <th>最长排队</th>
                  <th>熔断状态</th>
                  <th>失败率 / 阈值</th>
                  <th>熔断打开次数</th>
                  <th>短路次数</th>
                </tr>
              </thead>
              <tbody>
                {tools.map(row => (
                  <tr key={row.toolName} className={row.circuitState !== 'CLOSED' ? 'is-baseline' : ''}>
                    <td className="is-strategy">{row.toolName}</td>
                    <td className="is-mono">
                      {row.inFlight} / {row.maxConcurrent}
                    </td>
                    <td className="is-mono">{row.peakInFlight}</td>
                    <td className="is-mono">{row.rejected}</td>
                    <td className="is-mono">{row.maxWaitMs} ms</td>
                    <td>
                      <Tag color={CIRCUIT_META[row.circuitState]?.color ?? 'default'} title={row.circuitState}>
                        {CIRCUIT_META[row.circuitState]?.label ?? row.circuitState}
                      </Tag>
                    </td>
                    {/*
                      失败率与阈值并排显示：**页面不下"高不高"的结论**，只把两个数字摆出来。
                      替读者下结论意味着判据出现第二份，而两份必然漂移
                    */}
                    <td className="is-mono">
                      {row.failureRate} / {config.failureRateThreshold}
                    </td>
                    <td className="is-mono">{row.circuitOpenCount}</td>
                    <td className="is-mono">{row.shortCircuited}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Typography.Text type="secondary" className="diag-note">
          {data.note}
        </Typography.Text>
      </div>

      <Alert
        type="info"
        showIcon
        title="怎么读这两列"
        description={
          <Flex vertical gap={6}>
            <span>
              <strong>「被拒绝」是限流生效</strong>：闸门满了，这次调用没有执行。
              这是**暂时的** —— 回填会告诉模型"稍后用同样的参数重试是有意义的"。
            </span>
            <span>
              <strong>「熔断状态」是下游已判为不可用</strong>：打开期间调用压根不发出，
              回填会告诉模型"别再用同样的参数重试"。两者的下一步动作相反，所以界面上必须分开。
            </span>
            <span>
              <strong>判据只有一份</strong>：只有"工具执行出错"计入失败率 ——
              模型写错参数、被限流拒绝都**不算**工具故障。
              否则模型连写几个错参数就能把一个健康工具熔断掉。
            </span>
          </Flex>
        }
      />
    </Flex>
  )
}

const CIRCUIT_META: Record<string, { label: string; color: string }> = {
  CLOSED: { label: '正常放行', color: 'green' },
  OPEN: { label: '已熔断', color: 'red' },
  HALF_OPEN: { label: '试探恢复', color: 'gold' },
}

// ---------------------------------------------------------------------------
//  小部件（与另外几个面板保持同一套视觉语言）
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

function InlineError({ title, message }: { title: string; message: string }) {
  return <Alert type="error" showIcon title={title} description={message} />
}
