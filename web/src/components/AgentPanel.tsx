import { useState } from 'react'
import { Alert, Button, Card, Empty, Flex, Input, Spin, Table, Tag, Typography } from 'antd'
import type { UseAgentResult } from '../hooks/useAgent'
import type {
  AgentApprovalsResponse,
  AgentApprovalRow,
  AgentAskResponse,
  AgentLimitsResponse,
  AgentRunApprovalRow,
  AgentRunDetail,
  AgentRunRow,
  AgentToolCall,
  AgentTrace,
} from '../api/agent'
import type { Remote } from '../types'

/**
 * 「Agent 工具调用」面板（阶段三第 5 件 + 第九轮 HITL）：**把 Agent 到底干了什么摆出来**。
 *
 * <h2>这一页存在的理由（路线文档的验收原话）</h2>
 * 「能讲清「它为什么选这个工具、失败重试几次、什么时候让人确认、
 * **日志能不能还原执行过程**」」。
 *
 * <p>前三件（ReAct 循环、持久化恢复、并发保护）做完之后，这些信息**全部已经在库里**——
 * 但界面上看不见，等于"能还原"只对会查库的人成立。
 * 所以这一页不是"加个页面"，而是**把已经存在的事实摆到能被看见的地方**。
 *
 * <p>第九轮补上了那句话里的第三条：「什么时候让人确认」。在那之前，
 * 后端的写操作会**停在闸门上等人**，而界面上没有任何地方能让人点那个"批准" ——
 * 也就是说那个能力只能靠 curl 用起来。
 *
 * <h2>页面结构的决定</h2>
 * <ol>
 *   <li><b>待确认卡排在最上面</b>（在"跑一个任务"之上）。它不是"另一块信息"——
 *       它是全页**唯一有截止时间且正在阻塞别人**的东西：那条写操作背后有一个线程
 *       停在闸门上等，等不到就按拒绝处理。让它排在需要花钱的 `/ask` 下面是错的优先级；</li>
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
 *
 * <p>同理，审批那一块**不自己判断"这条该不该批"**：风险声明（`hints`）由后端给
 * （它来自工具的 `ToolHints`，与"要不要审批"的判据同源）。页面只把它显示出来。
 */
export function AgentPanel({ agent }: { agent: UseAgentResult }) {
  return (
    <div className="diag-panel">
      {/*
        待确认排第一：它是唯一"不处理就会自己失败"的东西。
        放在"跑一个任务"下面的话，人按顺序读下去会先看到那个更显眼的按钮，
        而真正的紧急项在下面 —— 优先级反了
      */}
      <ApprovalCard agent={agent} />

      <AskCard remote={agent.current} running={agent.running} onAsk={agent.ask} />

      {agent.detail.status !== 'idle' && <DetailCard remote={agent.detail} onClose={agent.closeDetail} />}

      {/*
        五层 trace 紧跟在运行详情下面（阶段四第 2 件）。
        它**不**塞进 DetailCard 里：那是另一个端点（要读五张表、要算金额），
        而且它读失败时详情那一块必须还能正常用 —— 排查入口不能因为一个次级端点挂了而全黑。
      */}
      {agent.trace.status !== 'idle' && <TraceCard remote={agent.trace} />}

      <RunsCard remote={agent.runs} onReload={agent.loadRuns} onOpen={agent.openDetail} />

      <LimitsCard remote={agent.limits} onReload={agent.loadLimits} />
    </div>
  )
}

// ---------------------------------------------------------------------------
//  0. 待我确认（HITL）—— 全页唯一有截止时间的东西
// ---------------------------------------------------------------------------

/**
 * 「待我确认」卡：写操作停在闸门上等人点头。
 *
 * <h2>为什么它必须存在（而不是"后端有端点就够了"）</h2>
 * 第九轮做完 HITL 时，后端已经能拦下写操作并落一条待批 —— 但那个能力**只能靠 curl 用**。
 * 更糟的是它的失败方式是安静的：闸门会一直等到超时（默认 120 秒）然后**按拒绝处理**，
 * 于是"没人点批准"与"这个功能坏了"在现象上完全一样。
 *
 * <h2>四个刻意的显示决定</h2>
 * <ol>
 *   <li><b>等了多少秒要显示、并且要显眼</b>：审批对任务耗时的影响，唯一可量化的口径就是它。
 *       不显示的话，"审批慢"永远只是一个感觉；</li>
 *   <li><b>参数原样显示、不折叠成摘要</b>：人批的**就是这份参数**，
 *       后端为此刻意不做脱敏与摘要（见 `ToolApprovalRecord` 的注释）。
 *       界面如果把参数折起来"帮你省地方"，就等于让人凭标题批准一个他没看清的操作；</li>
 *   <li><b>「批准」不用 primary 样式，「拒绝」也不替用户预填理由</b>：
 *       视觉上不诱导任何一个方向。列表本身对"批"是有吸引力的（点一下就继续），
 *       所以这里刻意不加强它；</li>
 *   <li><b>闸门关掉时必须明说</b>：一个空的待办列表有**两种**含义 ——
 *       "确实没有危险操作"与"审批功能压根没开"。后者时页面上必须写出来，
 *       否则空列表看起来像一切正常（这正是踩坑 #67 的形状：
 *       "我配了保护"与"保护真的装着"是两件事）。</li>
 * </ol>
 */
function ApprovalCard({ agent }: { agent: UseAgentResult }) {
  const { approvals, decision, deciding, decisionError } = agent
  const data = approvals.status === 'ok' ? approvals.data : null
  const pending = data?.pending ?? []
  const count = pending.length

  return (
    <Card
      size="small"
      className={`diag-card diag-card-wide approval-card ${count > 0 ? 'has-pending' : ''}`}
      title={
        <Flex gap={8} align="center" wrap>
          <span>待我确认：高危写操作必须先有人点头</span>
          {count > 0 ? (
            <Tag color="red" className="approval-count">
              {count} 条在等我
            </Tag>
          ) : (
            <Tag color="green">当前没有待批</Tag>
          )}
        </Flex>
      }
      extra={
        <Flex gap={8} align="center">
          {data && <Tag color={data.enabled ? 'blue' : 'default'}>{data.enabled ? '闸门已启用' : '闸门已关闭'}</Tag>}
          {data && <Tag>时限 {data.timeoutSeconds}s</Tag>}
          <Button size="small" onClick={agent.loadApprovals} loading={approvals.status === 'loading' && count === 0}>
            刷新
          </Button>
        </Flex>
      }
    >
      {approvals.status === 'idle' && <LoadingLine text="正在读待确认列表……" />}
      {approvals.status === 'loading' && count === 0 && <LoadingLine text="正在读待确认列表……" />}
      {approvals.status === 'error' && <InlineError title="待确认列表读取失败" message={approvals.error} />}

      {/* 决定的结果条：只在刚决定过之后出现，人看过就可以关掉 */}
      {decision && (
        <DecisionBanner decision={decision} error={decisionError} onClose={agent.clearDecision} />
      )}
      {/* 决定失败但后端没给结构化结果（网络层失败）时也要说清楚 */}
      {!decision && decisionError && (
        <Alert type="error" showIcon title="这次决定没有成功" description={decisionError} />
      )}

      {data && <ApprovalBody agent={agent} data={data} />}

      {deciding && <LoadingLine text="正在提交决定……批准会唤醒那个正在等的任务" />}
    </Card>
  )
}

function ApprovalBody({ agent, data }: { agent: UseAgentResult; data: AgentApprovalsResponse }) {
  return (
    <Flex vertical gap={12}>
      {/*
        闸门被关掉时必须显眼说出来。
        这是一个**很容易被忽略的失效**：HITL 关掉之后，写操作全部放行、
        日志上一切正常、待办列表永远是空的 —— 看起来像"从来没有危险操作"。
      */}
      {!data.enabled && (
        <Alert
          type="warning"
          showIcon
          title="审批闸门当前是「关闭」的 —— 写操作不会被拦下"
          description={
            <Flex vertical gap={4}>
              <span>{data.note}</span>
              <span>
                打开它：把 <code>ai.agent.approval.enabled</code> 设为 <code>true</code> 并重启。
              </span>
            </Flex>
          }
        />
      )}

      {data.pending.length === 0 ? (
        <Flex vertical gap={8}>
          <Empty
            description={
              data.enabled
                ? '没有写操作在等人确认'
                : '闸门关闭，所以不会有待批 —— 这与"没有危险操作"不是一回事'
            }
          />
          {/*
            空列表的解释必须写清楚，因为"没有待批"有三种完全不同的原因，
            而它们的下一步动作完全不同
          */}
          <Typography.Text type="secondary" className="diag-note">
            这里为空有三种原因，别混起来读：
            <br />① <strong>确实没有危险操作</strong> —— 本项目的三把只读工具（检索 / 列目录 / 读章节）
            都是只读声明，按规矩不需要审批；
            <br />② <strong>那个写操作还没被发起</strong> —— 只有模型真的决定要建工单时才会出现待批；
            <br />③ <strong>闸门被关掉了</strong>（看上面的提示）。
          </Typography.Text>
          <Typography.Text type="secondary" className="diag-note">
            当前闸门状态：{data.note}
          </Typography.Text>
        </Flex>
      ) : (
        <Flex vertical gap={12}>
          <Alert
            type="warning"
            showIcon
            title={
              data.pending.length === 1
                ? '有一条高危写操作停在这里，等人点头'
                : `有 ${data.pending.length} 条高危写操作停在这里，等人点头`
            }
            description={
              <Flex vertical gap={4}>
                <span>
                  它背后的任务线程「正停在闸门上等」 —— 最长等 {data.timeoutSeconds} 秒。
                  <strong>超时按「拒绝」处理，而不是默认通过</strong>
                  （没人管事时系统该停下来，而不是照常写数据）。
                </span>
                <span>
                  这个列表每 2.5 秒自动刷新一次，所以你在别处做的决定也会在这里出现。
                </span>
              </Flex>
            }
          />
          {data.pending.map(row => (
            <ApprovalItem key={row.id} row={row} agent={agent} timeoutSeconds={data.timeoutSeconds} />
          ))}
        </Flex>
      )}

      {/*
        把"默认值"写在界面上是有必要的：这一块最容易出的错不是"批错了"，
        而是**没人知道超时会怎么处理**，于是以为"不理它就应该没事"
      */}
      {data.pending.length > 0 && (
        <Alert
          type="info"
          showIcon
          title="批准与拒绝对模型意味着相反的事"
          description={
            <Flex vertical gap={4}>
              <span>
                <strong>批准</strong>：那个调用立刻执行（模型接着把任务做完）。
                执行结论会挂回这条审批 —— 事后能从工单倒查到"是哪一次审批放行了它"。
              </span>
              <span>
                <strong>拒绝</strong>：那个调用<strong>不会执行</strong>，理由会原样回填给模型，
                并明确告诉它「不要再重新发起」—— 所以拒绝理由值得写清楚，它是模型换方案的唯一依据。
              </span>
              <span>
                <strong>改参数等于另一件事</strong>：人批的是上面那份参数的逐字副本。
                模型回头提出不一样的参数时会<strong>重新征求确认</strong>，不会沿用这次的决定。
              </span>
            </Flex>
          }
        />
      )}
    </Flex>
  )
}

/**
 * 一条待批：参数 + 风险 + 批准/拒绝按钮。
 *
 * <p><b>「理由」与「决定人」都是每个条目自己的本地 state</b>，不提升到面板上 ——
 * 因为同一时刻可能有多条待批（并行工具调用），而共用一个输入框会让
 * "我写给 A 的理由"跑到 B 上去，且**不会报错**：它只是把一条错误的理由
 * 记进了一条真实的审计记录里。
 */
function ApprovalItem({
  row,
  agent,
  timeoutSeconds,
}: {
  row: AgentApprovalRow
  agent: UseAgentResult
  timeoutSeconds: number
}) {
  const [decidedBy, setDecidedBy] = useState('页面操作人')
  const [note, setNote] = useState('')
  const [rejecting, setRejecting] = useState(false)

  // 已经等了多久，以及"还剩多久就超时"。两个都显示：
  // 只有"已等"看不出紧迫程度（时限是可配的），只有"剩余"看不出它已经等了很久
  const waitedSec = Math.round(row.waitedMs / 1000)
  const remainingSec = Math.max(0, timeoutSeconds - waitedSec)
  const urgent = remainingSec <= Math.max(15, timeoutSeconds * 0.25)

  return (
    <div className={`approval-item ${urgent ? 'is-urgent' : ''}`} data-approval-id={row.id}>
      <Flex gap={8} wrap align="center" className="approval-head">
        <Tag color="red">需要人工确认</Tag>
        <Typography.Text strong>{row.toolName}</Typography.Text>
        {/*
          已等秒数用「秒」而不是「毫秒」：这一块要看的是"它卡了多久"，
          毫秒的精度在这里没有意义，反而让人读不出量级
        */}
        <Tag color={urgent ? 'red' : 'gold'} className="approval-waited">
          已等 {waitedSec}s · 还剩 {remainingSec}s 就按拒绝处理
        </Tag>
        {row.stepIndex >= 0 && <Tag>第 {row.stepIndex} 步</Tag>}
        {row.runId && (
          <Typography.Text type="secondary" className="approval-run" title={row.runId}>
            运行 {row.runId.slice(0, 8)}…
          </Typography.Text>
        )}
        {!row.runId && (
          /* 没有 runId 的行来自外部 MCP 客户端那条链路：它没有"运行"这个对象 */
          <Tag color="purple" title="它来自外部 MCP 客户端 —— 那条链路没有'运行'这个对象">
            外部 MCP 调用
          </Tag>
        )}
      </Flex>

      <div className="approval-summary">{row.summary}</div>

      <div className="agent-tool-args">
        <span className="agent-tool-label">风险评估（由工具自己的声明给出）：</span>
        <code className="approval-hints">{row.hints || '(未声明)'}</code>
      </div>

      {/*
        参数原样、可滚动、不折行截断。
        这里刻意**不做**"只显示前 N 个字符"的处理：人批的就是这份参数，
        看到一半就批等于批了一个自己没看清的操作
      */}
      <div className="approval-args">
        <span className="agent-tool-label">它将会执行的参数（原样，未加工）：</span>
        <pre>{row.arguments || '{}'}</pre>
      </div>

      <Flex gap={8} wrap align="center" className="approval-actions">
        <Input
          size="small"
          value={decidedBy}
          onChange={e => setDecidedBy(e.target.value)}
          placeholder="谁做的决定（记进审计）"
          className="approval-by"
          /*
            `data-testid` 是给界面自检用的稳定锚点。
            为什么不用 `.approval-by input`：antd 的 `Input` **不会**把 className 传到内层
            `<input>` 上（它落在包裹的 span 上），所以那个选择器在不同 antd 版本里时有时无 ——
            而"选择器找不到元素"的失败信息看起来像"这个功能没渲染"，
            排查方向会跑到组件上去。**给自检用的锚点应当由我们自己的属性提供，而不是依赖组件库的 DOM。**
          */
          data-testid="approval-decided-by"
        />
        <Button
          size="small"
          className="approval-approve"
          loading={agent.deciding}
          onClick={() => agent.decide(row.id, true, decidedBy, note)}
        >
          批准并执行
        </Button>
        <Button
          size="small"
          danger
          className="approval-reject"
          onClick={() => {
            // 先展开理由输入，而不是直接拒绝 —— 拒绝理由会原样回填给模型，
            // 它是模型换方案的**唯一依据**；一键拒绝会稳定地产生一堆没有理由的拒绝，
            // 而那种记录对事后排查几乎没有价值
            if (!rejecting) {
              setRejecting(true)
              return
            }
            agent.decide(row.id, false, decidedBy, note)
          }}
          loading={agent.deciding && rejecting}
        >
          {rejecting ? '确认拒绝' : '拒绝…'}
        </Button>
      </Flex>

      {rejecting && (
        <Flex vertical gap={6} className="approval-reject-box">
          <Input.TextArea
            size="small"
            value={note}
            onChange={e => setNote(e.target.value)}
            autoSize={{ minRows: 2, maxRows: 4 }}
            placeholder="拒绝的理由（会原样回填给模型，并明确告诉它不要再重新发起）"
          />
          <Typography.Text type="secondary" className="diag-note">
            理由留空也可以拒绝，但模型只会收到一句「未填写理由」——
            它下一次很可能提出一个同样会被你拒绝的方案。
          </Typography.Text>
        </Flex>
      )}
    </div>
  )
}

/**
 * 决定之后的结论条。
 *
 * <h2>为什么 `awakened` / `resumed` 必须显示出来</h2>
 * 「决定生效」与「任务继续」是**两件事**，而它们的组合有三种含义完全不同的处境：
 * <ul>
 *   <li>`awakened=true` —— 有线程正在等，任务**当场继续跑完**（正常路径）；</li>
 *   <li>`resumed=true` —— 没人等了（进程重启过 / 客户端断开了），
 *       后端**从检查点接着跑**起了任务；</li>
 *   <li>两者都是 false 且 runId 非空 —— 决定已记录，**但任务还停着**。</li>
 * </ul>
 * 把这三种压成一句"操作成功"的话，第三种就会被当成正常，
 * 而它恰恰是需要人去处理的那一种。
 */
function DecisionBanner({
  decision,
  error,
  onClose,
}: {
  decision: { ok: boolean; status: string; awakened: boolean; resumed: boolean; runId?: string; stopReason?: string; note?: string; resumeError?: string }
  error: string | null
  onClose: () => void
}) {
  const approved = decision.status === 'APPROVED'
  const continued = decision.awakened || decision.resumed
  const type = error ? 'error' : approved ? 'success' : 'info'

  return (
    <Alert
      type={type}
      showIcon
      title={
        error
          ? '这次决定没有被采纳'
          : approved
            ? continued
              ? '已批准，任务已经继续执行'
              : '已批准，但任务没有自动继续（见下）'
            : '已拒绝，那个写操作不会执行'
      }
      description={
        <Flex vertical gap={4}>
          <span>审批状态：{decision.status}</span>
          <span>
            唤醒等待线程：{decision.awakened ? '是' : '否'} · 从检查点接着跑：
            {decision.resumed ? '是' : '否'}
            {decision.stopReason ? ` · 任务的停因：${decision.stopReason}` : ''}
          </span>
          {error && <span>{error}</span>}
          {decision.resumeError && <span>恢复失败的原因：{decision.resumeError}</span>}
          {decision.note && <span>{decision.note}</span>}
          {!continued && !error && approved && !decision.resumeError && (
            <span>
              决定已经记录在案，但没有任何线程在等它、也没有可恢复的检查点 ——
              这一次写操作不会被执行。要让它继续，需要重新发起这次任务。
            </span>
          )}
        </Flex>
      }
      action={
        <Button size="small" type="text" onClick={onClose}>
          知道了
        </Button>
      }
    />
  )
}

// ---------------------------------------------------------------------------
//  1. 跑一个任务 —— 这一页唯一"花钱"的操作
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
  onAsk: (q: string, intent: string) => void
}) {
  const [question, setQuestion] = useState('分块策略的结论是什么？')
  /**
   * 业务归因键（阶段四成本看板）。
   *
   * <h2>为什么这个输入框必须存在，而不是"后端有字段就够了"</h2>
   * 成本看板的「按业务归因」那一栏，唯一的**写入方**就是这里 ——
   * 界面上不给入口的话，那一栏会永远只有一行「未标注」，
   * 而那正是"这个能力只能靠 curl 用"的又一次重演（第九轮 HITL 踩过的同一个形状）。
   *
   * <p>默认值给了「客服问答」而不是空：一个空输入框的结果是所有人都不填，
   * 于是归因永远为空 —— 而一个**能改的默认值**至少让那一栏有内容，
   * 人看到"客服问答"之后会顺手改成自己那条业务。
   */
  const [intent, setIntent] = useState('客服问答')

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
            onPressEnter={() => onAsk(question, intent)}
            placeholder="问一个需要查文档的问题（Enter 发送）"
            className="rag-input"
            disabled={running}
          />
          <Button type="primary" onClick={() => onAsk(question, intent)} loading={running}>
            跑一次
          </Button>
        </Flex>

        {/*
          归因键单独一行、并且写清它去哪儿：它是**成本看板那一栏的分组键**，
          而分组键的取值质量直接决定那一栏有没有用。
          不说清的话，没人知道"业务归因"这四个字是干什么的
        */}
        <Flex gap={8} align="center" wrap>
          <Typography.Text type="secondary" className="cost-intent-label">
            业务归因
          </Typography.Text>
          <Input
            value={intent}
            onChange={e => setIntent(e.target.value)}
            placeholder="这次运行为哪个业务花钱（留空则记成「未标注」）"
            maxLength={64}
            disabled={running}
            className="cost-intent-input"
          />
          <Typography.Text type="secondary" className="cost-intent-hint">
            它会成为「成本看板」里按业务归因的那一行
          </Typography.Text>
        </Flex>

        {/*
          例子刻意选"需要查文档"和"不需要查文档"各一个：
          后者能让人看到 Agent 会**直接回答而不调工具** ——
          那正是它"判断要不要查"这件事被验证的样子，而不是每次都必须调工具
        */}
        <Flex gap={6} wrap align="center">
          <Typography.Text type="secondary">试试：</Typography.Text>
          {[
            { q: '分块策略的结论是什么？', intent: '文档问答' },
            { q: 'token 预算的系数怎么校准的？', intent: '成本排查' },
            { q: '你是谁？', intent: '闲聊' },
          ].map(sample => (
            <Tag
              key={sample.q}
              className="rag-tag-clickable"
              onClick={() => {
                setQuestion(sample.q)
                // 点例子时把归因键也一起切过去：三个例子的业务归属本来就不同，
                // 全按输入框里那个值记的话，成本看板上这三个业务会被并成一个
                setIntent(sample.intent)
                onAsk(sample.q, sample.intent)
              }}
            >
              {sample.q}
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

      {/*
        【第九轮】"停在等人确认"必须单独一条、并且指向那张待办卡片。
        它既不是"答出来了"也不是"失败了" —— 用前两条分支渲染都会说谎：
        渲染成成功会让答案框空着（看起来像"答了但是空的"），
        渲染成失败会让人以为那次写操作出错了（而它一条记录都没建）。
      */}
      {data.waitingApproval && (
        <Alert
          type="warning"
          showIcon
          className="agent-waiting"
          title={`这次任务停在了一次写操作上，正在等人确认（工具：${data.approvalToolName ?? '?'}）`}
          description={
            <Flex vertical gap={4}>
              <span>
                它<strong>没有执行、也没有失败</strong>。上面的「待我确认」卡片是它的处理入口 ——
                批准之后任务会接着跑完，拒绝则按拒绝回填给模型。
              </span>
              {data.approvalArguments && (
                <span>
                  将执行的参数：<code>{data.approvalArguments}</code>
                </span>
              )}
              {data.approvalHints && <span>风险声明：{data.approvalHints}</span>}
              {data.approvalTimeoutSeconds !== undefined && (
                <span>等人时限 {data.approvalTimeoutSeconds} 秒，超时按拒绝处理。</span>
              )}
            </Flex>
          }
        />
      )}
      {/*
        【这条是最该显眼的一致性告警】停因说是等审批，但待办里查不到对应记录 ——
        它意味着记录被清理掉了、或审批已被别人处理。两种情况下界面都**不能**
        显示一个空的待批框让人以为还有救
      */}
      {data.approvalMissing && (
        <Alert
          type="error"
          showIcon
          title="停因显示它在等人确认，但待办里找不到对应的审批记录"
          description="这通常意味着那条审批已经被别人处理过、或随进程重启被作废了。这次写操作不会执行；如果要继续，需要重新发起任务。"
        />
      )}

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
    hint: '参数没问题、工具自己炸了。「只有这一类」会计入熔断失败率 —— 模型写错参数不算工具故障。',
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
  // ---- 第九轮新增：HITL（人工确认）的三个 stage ----
  AWAITING_APPROVAL: {
    label: '等人工确认',
    color: 'red',
    hint: '这是一次写操作，按规矩需要有人点头才能执行。它「既没成功也没失败」 —— 界面上方那张「待我确认」卡片就是它的处理入口。',
  },
  APPROVAL_DENIED: {
    label: '人工拒绝',
    color: 'purple',
    hint: '有人在界面上拒绝了这次写操作，所以它「没有执行」。拒绝理由会原样回填给模型，并明确告诉它「不要再重新发起」。',
  },
  APPROVAL_TIMEOUT: {
    label: '等确认超时',
    color: 'purple',
    hint: '在时限内没有人处理，于是「按拒绝处理」（不是默认通过）。它与「人工拒绝」分开显示：前者是流程没接上，后者是业务判断。',
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
  /*
    第九轮新增。它必须与「运行中」分开：
    待批的运行**并没有在跑**（没有线程在跑它），但**也没有结束**。
    标成「运行中」会让人一直等一个不会自己动的东西；
    标成「失败」更糟 —— 它会让人以为那次写操作出错了，而它其实一条记录都没建。
  */
  WAITING_APPROVAL: { label: '等人工确认', color: 'magenta' },
}

// ---------------------------------------------------------------------------
//  3. 某一次运行的详情
// ---------------------------------------------------------------------------

/**
 * **五层 trace**（阶段四第 2 件）：把一次运行按"会话 → 运行 → LLM 调用 → 工具调用 → 检索"还原。
 *
 * <h2>它与上面那张"运行详情"的分工</h2>
 * 运行详情回答「这次运行怎么样」（状态 / 答案 / 每步 token / 每次工具调用）；
 * 这一张回答「这次运行<b>到底发生了什么</b>」，多出来的是三样：
 * <ol>
 *   <li><b>检索那一层</b>（第 5 层）：命中了哪几个块、各占多少 token、得分多少。
 *       在此之前它只存在于回填给模型的<u>一段中文文本</u>里 ——
 *       要数"检索了几次"得数文本里出现了几次「在 516 个文档片段里找到」，
 *       而"检索占了输入多大一块"那段文本里根本没有这个数；</li>
 *   <li><b>会话那一层</b>（第 1 层）：这段对话里一共跑了几次、各自什么结局；</li>
 *   <li><b>提示词指纹</b>：这一步用的是哪一版系统提示词 ——
 *       没有它，"某天的答案变差了是不是因为那天换了提示词"永远追不到。</li>
 * </ol>
 *
 * <h2>界面上的三条纪律（都是这一页最容易做错的地方）</h2>
 * <ol>
 *   <li><b>金额算不出来时显示「算不出」，绝不显示 ¥0.00</b>。
 *       那个 0 会被读成"这次不花钱"，而真相是"我们不知道它花多少"。
 *       这与成本看板是同一条纪律，两处的写法必须一致；</li>
 *   <li><b>检索 token 必须标成「估算」</b>。它来自块自带的估算器，
 *       而账目口径的 token 来自上游 usage —— 两个口径混排而不加区分，
 *       等于把估算当事实记账；</li>
 *   <li><b>「空」必须区分「本来就不该有」与「漏记了」</b>。
 *       判据由后端的 `layers[].expected` 给（前端不自己判 —— 那会是第二份判据）。
 *       一次没调工具的运行在第 4、5 层本来就是空的，那是正常的；
 *       而一次调了检索却查不到明细，说明落库链路有问题。两者长得一样、含义相反。
 * </ol>
 */
function TraceCard({ remote }: { remote: Remote<AgentTrace> }) {
  return (
    <Card size="small" className="diag-card diag-card-wide" title="五层 trace：会话 → 运行 → LLM 调用 → 工具调用 → 检索">
      {remote.status === 'loading' && <LoadingLine text="正在读五层 trace（会话 / 步骤 / 工具 / 检索 / 金额）……" />}
      {remote.status === 'error' && <InlineError title="trace 读取失败" message={remote.error} />}
      {remote.status === 'ok' && <TraceBody data={remote.data} />}
    </Card>
  )
}

function TraceBody({ data }: { data: AgentTrace }) {
  if (!data.ok) {
    return <InlineError title="读不到这次 trace" message={data.error ?? '未知原因'} />
  }

  return (
    <Flex vertical gap={14}>
      {/*
        ── 五层到场清单排在最上面 ──
        理由与"待确认卡排第一"同一条：它是**读这一页之前必须先知道的事**。
        不看它就直接往下读，会把"这一层本来就没内容"误读成"漏记了"。
      */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          五层到场情况
        </Typography.Title>
        <Flex wrap gap={8} data-testid="trace-layers">
          {data.layers.map(layer => (
            <Tag
              key={layer.index}
              /* 颜色编码的是**这一层有没有内容**，而判断依据是后端给的 expected：
                 有内容=绿；没内容但不该有=默认灰（正常）；没内容却该有=红（缺陷） */
              color={layer.expected ? 'green' : 'default'}
              data-testid={`trace-layer-${layer.key}`}
              data-expected={layer.expected ? '1' : '0'}
              title={layer.storage}
            >
              {layer.index}. {layer.name}
              {layer.expected ? ' ✓' : '（本次无）'}
            </Tag>
          ))}
        </Flex>
      </div>

      {/* ── 第 1 层：会话 ── */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          ① 会话{data.conversation.present ? `：这段对话里一共 ${data.conversation.runCount} 次运行` : ''}
        </Typography.Title>
        {!data.conversation.present ? (
          /*
            【这两种情况必须分开说】
            "这段对话只有一次运行"与"这次运行没有会话信息"在界面上都会渲染成一个空列表 ——
            而它们对使用者是两件不同的事：后者说明调用方没传会话 id。
            后端给了 present 布尔与一句 note，这里只负责显示，不自己猜。
          */
          <Alert
            type="info"
            showIcon
            data-testid="trace-conversation-absent"
            title="这次运行没有会话信息"
            description={data.conversation.note ?? '调用方没有传会话 id。'}
          />
        ) : (
          <>
            <Typography.Text type="secondary" className="diag-note">
              会话 id：<code>{data.conversation.id}</code>
              {data.conversation.truncated ? '（只显示最近若干次）' : ''}
            </Typography.Text>
            <div className="rag-table-scroll">
              <table className="rag-metric-table">
                <thead>
                  <tr>
                    <th>运行</th>
                    <th>问题</th>
                    <th>状态</th>
                    <th>token</th>
                    <th>时间</th>
                  </tr>
                </thead>
                <tbody>
                  {data.conversation.runs.map(run => (
                    <tr key={run.id} data-current={run.current ? '1' : '0'} className={run.current ? 'is-baseline' : undefined}>
                      <td className="is-mono">
                        {run.current && <Tag color="blue">正在看</Tag>}
                        {run.id}
                      </td>
                      <td className="is-excerpt">{run.question}</td>
                      <td>{STATUS_META[run.status]?.label ?? run.status}</td>
                      <td className="is-mono">{run.totalTokens}</td>
                      <td className="is-mono">{run.createdAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>

      {/* ── 第 5 层：检索（本件的主角） ── */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          ⑤ 检索：这次任务查了 {data.retrieval.calls} 次
          {data.retrieval.emptyCalls > 0 ? `，其中 ${data.retrieval.emptyCalls} 次一条都没查到` : ''}
        </Typography.Title>
        {data.retrieval.calls === 0 ? (
          <Typography.Text type="secondary" className="diag-note" data-testid="trace-retrieval-none">
            {data.layers.find(l => l.key === 'retrieval')?.expected === false
              ? '这次运行没有检索 —— 它没有调用过检索类工具，所以这一层为空是正常的（不是漏记）。'
              : '没读到检索明细，但这次运行调用过检索类工具 —— 这说明落库链路可能有问题。'}
          </Typography.Text>
        ) : (
          <>
            <div className="stat-grid">
              <Stat label="检索次数" value={data.retrieval.calls} />
              <Stat label="命中条数" value={data.retrieval.hitCount} />
              {/* 「估算」两个字必须显示出来 —— 见类注释第 2 条纪律 */}
              <Stat label="命中 token（估算）" value={data.retrieval.hitTokens} />
              <Stat
                label="占输入比例"
                value={`${(data.retrieval.shareOfPrompt * 100).toFixed(1)}%`}
                accent={data.retrieval.shareOfPrompt > 0.3}
              />
              <Stat label="空命中次数" value={data.retrieval.emptyCalls} accent={data.retrieval.emptyCalls > 0} />
            </div>
            <Typography.Text type="secondary" className="diag-note">
              {data.retrieval.note}
            </Typography.Text>

            {data.steps
              .filter(step => step.retrievalCalls.length > 0)
              .map(step => (
                <div key={step.stepIndex} style={{ marginTop: 12 }}>
                  {step.retrievalCalls.map(rc => (
                    <div key={`${rc.stepIndex}-${rc.callIndex}`} className="rag-chunk" data-testid="trace-retrieval-call">
                      <div className="rag-chunk-head">
                        <Typography.Text strong>
                          第 {rc.stepIndex} 步 · 第 {rc.callIndex + 1} 次调用 · {rc.toolName}
                        </Typography.Text>
                        <Tag color={rc.hitCount === 0 ? 'orange' : 'blue'}>
                          {rc.hitCount === 0 ? '一条都没查到' : `命中 ${rc.hitCount} 条`}
                        </Tag>
                        {/*
                          「召回不足」单独一个标签：它**不是错误**（语料里可能真的只有两条相关），
                          但它是"语料不够"与"检索没找着"分界线上最便宜的一个信号 ——
                          而这两者要改的东西完全相反
                        */}
                        {rc.shortOfTopK && rc.hitCount > 0 && (
                          <Tag color="gold">少于要的 {rc.topK} 条（可能召回不足）</Tag>
                        )}
                      </div>
                      {/*
                        查询词、要几条、索引多大 —— 这三个数就是"0 条命中"的全部上下文。
                        没有索引规模就分不清"库是空的"与"没匹配上"，而那两种的改法相反
                      */}
                      <Typography.Text type="secondary" className="diag-note">
                        查询词：<code>{rc.query}</code>　要 {rc.topK} 条　索引 {rc.indexSize} 块　
                        {rc.hitTokens} token（估算）　耗时 {rc.elapsedMs} ms
                      </Typography.Text>
                      {rc.hits.length > 0 && (
                        <div className="rag-table-scroll">
                          <table className="rag-hit-table">
                            <thead>
                              <tr>
                                <th>名次</th>
                                <th>得分</th>
                                <th>来源 / 章节</th>
                                <th>token</th>
                                <th>片段开头</th>
                              </tr>
                            </thead>
                            <tbody>
                              {rc.hits.map(hit => (
                                <tr key={hit.rank}>
                                  {/* 名次就是**模型看到的名次** —— 后端断言了它连续且从 1 开始 */}
                                  <td className="is-rank">{hit.rank}</td>
                                  <td className="is-mono" title="只在同一次检索内可比：不同引擎的量纲不同">
                                    {hit.score.toFixed(4)}
                                  </td>
                                  <td className="is-breadcrumb">
                                    <div className="is-mono">{hit.documentId}</div>
                                    <div className="rag-breadcrumb">{hit.breadcrumb || '（无标题）'}</div>
                                  </td>
                                  <td className="is-mono">{hit.tokenCount}</td>
                                  <td className="is-excerpt">{hit.excerpt}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              ))}
          </>
        )}
      </div>

      {/* ── 第 3 层：LLM 调用（含提示词指纹） ── */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          ③ LLM 调用：每一步用的是哪一版提示词
        </Typography.Title>
        {data.steps.length === 0 ? (
          <Typography.Text type="secondary" className="diag-note">
            这次运行一个步骤都没有（大多死在第一步之前，例如上游鉴权失败）—— 记的是 0 token，
            那不是"没花钱"，而是"没有可记录的花费"。
          </Typography.Text>
        ) : (
          <div className="rag-table-scroll">
            <table className="rag-metric-table">
              <thead>
                <tr>
                  <th>步</th>
                  <th>提示词指纹</th>
                  <th>模型</th>
                  <th>prompt</th>
                  <th>命中缓存</th>
                  <th>completion</th>
                  <th>检索</th>
                </tr>
              </thead>
              <tbody>
                {data.steps.map(step => (
                  <tr key={step.stepIndex} data-testid="trace-step">
                    <td className="is-mono">{step.stepIndex}</td>
                    <td className="is-mono">
                      {step.promptFingerprint ? (
                        <span className="prompt-hash" title="版本号是「指针」、指纹是「内容」">
                          {step.promptFingerprint}
                        </span>
                      ) : (
                        /*
                          空指纹有两种成因（老数据 / 提示词服务当时读不到当前版本），
                          它们要做的动作不同 —— 所以不合并成一句"未知"
                        */
                        <Typography.Text type="secondary" title="可能是这一列加上之前的老数据，也可能是提示词服务当时读不到当前版本（日志里有 WARN）">
                          未记录
                        </Typography.Text>
                      )}
                    </td>
                    <td className="is-mono">{step.modelName || '(未上报)'}</td>
                    <td className="is-mono">{step.promptTokens}</td>
                    <td className="is-mono">{step.cacheReadTokens}</td>
                    <td className="is-mono">{step.completionTokens}</td>
                    <td className="is-mono">
                      {step.retrievalCount === 0 ? '—' : `${step.retrievalCount} 次 / ${step.retrievalHitTokens} token`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── 金额：算不出来就说算不出来 ── */}
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          这次运行花了多少
        </Typography.Title>
        <div className="stat-grid">
          <Stat label="输入（未命中缓存）" value={data.cost.uncachedPromptTokens} />
          <Stat label="输入（命中缓存）" value={data.cost.cachedPromptTokens} />
          <Stat label="输出" value={data.cost.completionTokens} />
          <Stat label="token 合计" value={data.cost.totalTokens} />
        </div>
        {data.cost.amount === null ? (
          /* ★ 这里**绝不能**显示 ¥0.00 —— 见类注释第 1 条纪律 */
          <Alert
            type="warning"
            showIcon
            className="cost-needs-alert"
            data-testid="trace-cost-unknown"
            title="算不出金额"
            description={data.cost.amountReason ?? '价格未配置'}
          />
        ) : (
          <Typography.Text className="cost-amount" data-testid="trace-cost-amount">
            {data.cost.amount} {data.cost.currency}
          </Typography.Text>
        )}
        {Object.values(data.cost.byModel).map(row => (
          <Typography.Text key={row.model} type="secondary" className="diag-note">
            模型 <code>{row.model || '(未上报)'}</code>：
            {row.amount === null ? `算不出（${row.amountReason ?? '无价格'}）` : `${row.amount} ${data.cost.currency}`}
            {row.priceRule ? `　价格规则：${row.priceRule}` : ''}
          </Typography.Text>
        ))}
      </div>

      {/* ── 边界说明 ── */}
      {data.notes.length > 0 && (
        <div>
          <Typography.Title level={5} className="rag-subtitle">
            这一页的边界
          </Typography.Title>
          <ul className="cost-notes">
            {data.notes.map((note, i) => (
              <li key={i}>{note}</li>
            ))}
          </ul>
        </div>
      )}
    </Flex>
  )
}

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
          「计入熔断」这一列由<strong>后端</strong>判定（只有"工具执行出错"算失败）——
          模型写错参数、被限流拒绝、等人确认都不算工具故障。页面不自己判，
          否则就会有第二份判据，而两份必然漂移。
        </Typography.Text>
      </div>

      <RunApprovals runId={data.run.id} rows={data.approvals ?? []} />
    </Flex>
  )
}

/**
 * 这次运行里人一共被问过几次（第九轮 HITL）。
 *
 * <h2>为什么它挂在运行详情里，而不是只用那张待办列表</h2>
 * 待办列表回答的是**当下**："现在要我批什么"。而这里回答的是**历史**：
 * "这次任务的过程中，我被问过几次、各等了多久、谁批的"。
 * 路线文档那句验收原话是「什么时候让人确认」—— 而"什么时候"只有挂在
 * 那次运行的时间线上才有意义（第几步、等了多少秒）。
 *
 * <p>合并成一个列表的代价很具体：人分不清"这条是要我处理的"还是"这条是已经处理过的"。
 */
function RunApprovals({ runId, rows }: { runId: string; rows: AgentRunApprovalRow[] }) {
  if (rows.length === 0) {
    return (
      <div>
        <Typography.Title level={5} className="rag-subtitle">
          人工确认（HITL）
        </Typography.Title>
        <Typography.Text type="secondary" className="diag-note">
          这次运行<strong>没有触发人工确认</strong> —— 它用到的全是只读工具，
          而按规矩只有非幂等的写操作才需要审批。
        </Typography.Text>
      </div>
    )
  }

  return (
    <div>
      <Typography.Title level={5} className="rag-subtitle">
        人工确认（HITL）：这次任务里人一共被问过 {rows.length} 次
      </Typography.Title>
      <div className="rag-table-scroll">
        <table className="rag-metric-table">
          <thead>
            <tr>
              <th>步</th>
              <th>工具</th>
              <th>结果</th>
              <th>等了多久</th>
              <th>谁决定的</th>
              <th>决定说明</th>
              <th>参数</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(row => {
              const meta = APPROVAL_STATUS_META[row.status] ?? { label: row.status, color: 'default' }
              return (
                <tr key={row.id} className={row.pending ? 'is-baseline' : ''}>
                  <td className="is-mono">{row.stepIndex >= 0 ? row.stepIndex : '-'}</td>
                  <td className="is-strategy">{row.toolName}</td>
                  <td>
                    <Tag color={meta.color} title={row.status}>
                      {meta.label}
                    </Tag>
                  </td>
                  {/*
                    「等了多久」是这一列存在的全部理由：审批对任务耗时的影响，
                    唯一可量化的口径就是它。没有它，"审批慢不慢"永远只是一个感觉
                  */}
                  <td className="is-mono">{Math.round(row.waitedMs / 1000)}s</td>
                  <td>{row.decidedBy || <span className="is-dim">—</span>}</td>
                  <td className="is-excerpt">{row.decisionNote || <span className="is-dim">—</span>}</td>
                  <td className="is-excerpt">
                    <code>{row.arguments || '{}'}</code>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <Typography.Text type="secondary" className="diag-note">
        运行 {runId.slice(0, 8)}… 的审批历史。状态与后端 <code>ApprovalStatus</code> 一一对应：
        「超时」与「人工拒绝」<strong>刻意分开</strong> —— 它们对执行是同一个结果（不执行），
        但要通知的人完全不同：前者是流程没接上，后者是业务判断。
      </Typography.Text>
    </div>
  )
}

/** 审批状态的中文说明。与后端 `ApprovalStatus` 一一对应。 */
const APPROVAL_STATUS_META: Record<string, { label: string; color: string }> = {
  PENDING: { label: '等确认中', color: 'red' },
  APPROVED: { label: '已批准', color: 'green' },
  DENIED: { label: '人工拒绝', color: 'purple' },
  TIMED_OUT: { label: '超时（按拒绝处理）', color: 'gold' },
  EXPIRED: { label: '已作废', color: 'default' },
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
              这是<strong>暂时的</strong> —— 回填会告诉模型"稍后用同样的参数重试是有意义的"。
            </span>
            <span>
              <strong>「熔断状态」是下游已判为不可用</strong>：打开期间调用压根不发出，
              回填会告诉模型"别再用同样的参数重试"。两者的下一步动作相反，所以界面上必须分开。
            </span>
            <span>
              <strong>判据只有一份</strong>：只有"工具执行出错"计入失败率 ——
              模型写错参数、被限流拒绝都<strong>不算</strong>工具故障。
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
