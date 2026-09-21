import { Alert, Button, Card, Empty, Flex, Input, Modal, Space, Spin, Tag, Typography } from 'antd'
import { useState } from 'react'
import type { AgentTaskCard, AgentTaskConfirm } from '../api/tasks'
import type { UseTasksResult } from '../hooks/useTasks'

const { Text, Paragraph } = Typography

/**
 * 应用管理区 · agent任务（对接 `agent_task` / `agent_task_confirm` 两张表）。
 *
 * <h2>这一页是什么：一张按状态分列的卡片看板</h2>
 * 五列对应任务从发布到结束的五段（新任务 / 待处理 / 执行中 / 待验收 / 已完成），
 * 状态与列的归属**由后端算**（`AgentTaskService.Column`），前端只按 key 画 ——
 * 前端再定一份"哪个状态进哪一列"就是第二份判据，而两份不一致时卡片会出现在错误的列上。
 *
 * <h2>★ 三条不能省的说法</h2>
 * <ol>
 *   <li><b>卡片停在哪一列 = 现在等谁</b>：看板不解释这一点，"待处理"就只是一个堆着东西的筐；
 *       所以列头写出这一列收的是哪几种状态，卡片上写出当前执行人；</li>
 *   <li><b>"新发布"不等于"待确认需求"</b>：前者还没有确认项可答（它等 agent 判需求有没有歧义），
 *       后者有一条在等他答 —— 两个状态挨着，说反了会让用户去找一个不存在的按钮；</li>
 *   <li><b>取消原因要显示出来</b>：取消是这一页唯一"没有下文"的结局，
 *       原因不显示的话，那条卡片与"被误操作取消的"看起来完全一样。</li>
 * </ol>
 *
 * <h2>为什么按钮来自后端的 actions，而不是这里 if 状态</h2>
 * 哪几个动作现在可用是一条状态机判断（后端 {@code DefaultAgentTaskService#actionsOf}）。
 * 前端再判一次的话，它会与后端漂移，而漂移的表现是"按钮点得下去、点完回 409"——
 * 用户会以为是自己操作错了。
 *
 * <h2>「已取消」与「已完成」同列（后端的列定义就是这么给的）</h2>
 * 看板只画"这件事结束了没有"，而两者都是结束；给已取消单开一列会让一条流程里
 * 最边缘的结局占掉一整列宽度。两者的区别靠卡片上的状态标签与取消原因表达 ——
 * 所以那两样必须显示出来（见上面第 3 条）。
 */
export function AgentTaskPanel({ tasks }: { tasks: UseTasksResult }) {
  const { board } = tasks
  const data = board.status === 'ok' ? board.data : null
  const [createOpen, setCreateOpen] = useState(false)

  const columns = data?.columns ?? []

  if (board.status === 'loading' || board.status === 'idle') {
    // antd 6 里 Spin 的提示文案叫 description（写成旧名 tip 只在控制台留一条警告）
    return <div className="diag-panel"><Spin description="正在读任务看板…" /></div>
  }
  if (board.status === 'error') {
    return (
      <div className="diag-panel">
        <Alert
          type="error"
          showIcon
          title="读任务看板失败"
          description={<div className="diag-note">
            <p>{board.error}</p>
            <p>这一族端点在 <Text code>/api/agent-tasks</Text>，后端没起或端口不同时都会走到这里。</p>
          </div>}
        />
      </div>
    )
  }

  return (
    <div className="diag-panel task-panel">
      <TaskFeedback tasks={tasks} />

      <Card
        size="small"
        className="diag-card diag-card-wide"
        title={<Flex gap={8} align="center" wrap>
          <span>任务看板（{data?.total ?? 0} 条）</span>
          <Tag color="geekblue">按状态分列 · 卡片停在哪一列就等于现在等谁</Tag>
        </Flex>}
        extra={<Space>
          <Button onClick={tasks.reload} disabled={tasks.busy}>刷新</Button>
          <Button type="primary" onClick={() => setCreateOpen(true)}>发布任务</Button>
        </Space>}
      >
        {data && data.total === 0
          ? <Empty description="还没有任务。点「发布任务」发一条：它会先落在「新任务」，随后由 agent 任务执行判断需求。" />
          : (
            <div className="task-board">
              {columns.map(column => (
                <section className="task-column" key={column.key} data-column={column.key}>
                  <header className="task-column-head">
                    <span className="task-column-name">{column.label}</span>
                    <span className="task-column-count" data-count={column.count}>{column.count}</span>
                  </header>
                  {/* 列的归属写在列头上：「待处理」收三种、「已完成」收两种（含已取消），
                      不写的话用户不知道它们与另外三列的区别 */}
                  <div className="task-column-hint">{column.statusLabels.join(' / ')}</div>
                  <div className="task-column-cards">
                    {column.tasks.length === 0
                      ? <div className="task-column-empty">（空）</div>
                      : column.tasks.map(task => (
                        <TaskCard key={task.id} task={task} onOpen={() => tasks.openTask(task.id)} />
                      ))}
                  </div>
                </section>
              ))}
            </div>
          )}
      </Card>

      <NewTaskModal open={createOpen} onClose={() => setCreateOpen(false)} tasks={tasks} />
      <TaskDetailModal tasks={tasks} />
    </div>
  )
}

/** 写操作的反馈（原样显示后端那句话，与权限两页同一条规矩）。 */
function TaskFeedback({ tasks }: { tasks: UseTasksResult }) {
  return (
    <>
      {tasks.feedback.status === 'ok' && (
        <Alert type="success" showIcon closable title={tasks.feedback.data.message}
          onClose={tasks.clearFeedback} />
      )}
      {tasks.feedback.status === 'error' && (
        <Alert type="error" showIcon closable title={tasks.feedback.error}
          onClose={tasks.clearFeedback} />
      )}
    </>
  )
}

/* ------------------------------------------------------------------ */
/*  卡片                                                                */
/* ------------------------------------------------------------------ */

function TaskCard({ task, onOpen }: { task: AgentTaskCard; onOpen: () => void }) {
  return (
    <button
      type="button"
      className={`task-card is-${task.status.toLowerCase()}`}
      data-task-id={task.id}
      onClick={onOpen}
    >
      <span className="task-card-title">{task.description}</span>
      <span className="task-card-meta">
        {task.publisher} → {task.assignee || '（未指派任务人）'}
      </span>
      <span className="task-card-meta">
        第 {task.currentStep} 步 · 当前执行人 {task.currentExecutor || '（无）'}
        {task.agentId != null ? ` · agent#${task.agentId}` : ''}
      </span>
      <span className="task-card-foot">
        <Tag color={statusColor(task.status)}>{task.statusLabel}</Tag>
      </span>
      {/* 执行结果与取消原因都只在自己那一段出现：没有的时候不画一行空白 */}
      {task.result && <span className="task-card-result">执行结果：{task.result}</span>}
      {task.cancelReason && <span className="task-card-cancel">取消原因：{task.cancelReason}</span>}
    </button>
  )
}

function statusColor(status: string): string {
  if (status === 'COMPLETED') return 'green'
  if (status === 'CANCELLED') return 'default'
  if (status === 'EXECUTING') return 'processing'
  if (status.startsWith('PENDING')) return 'orange'
  // 「已确认需求 / 已确认计划 / 已授权」与「执行中」同列：球都在 agent 手上。
  // 用同一个颜色是刻意的 —— 这四格对用户是同一件事（不必点，等它跑）
  if (status.startsWith('CONFIRMED') || status === 'AUTHORIZED') return 'processing'
  return 'blue'
}

/* ------------------------------------------------------------------ */
/*  详情：任务 + 确认项 + 动作                                          */
/* ------------------------------------------------------------------ */

function TaskDetailModal({ tasks }: { tasks: UseTasksResult }) {
  const detail = tasks.detail
  const [resultText, setResultText] = useState('')
  const [freeText, setFreeText] = useState('')
  const [cancelOpen, setCancelOpen] = useState(false)

  if (detail.status === 'idle') {
    return null
  }
  const data = detail.status === 'ok' ? detail.data : null
  const task = data?.task
  const pending = data?.confirms.find(confirm => !confirm.answered)
  const actions = data?.actions ?? []
  const advanceAction = actions.find(action => action.key === 'advance')
  const canAnswer = actions.some(action => action.key === 'answer')
  const canCancel = actions.some(action => action.key === 'cancel')
  // 「登记执行结果」那一步要多一个输入框：结果不是选项，得让人写
  const needsResult = task?.status === 'EXECUTING'
  /**
   * 这一步在等 agent，人现在点不动。
   *
   * <p>判据是"有动作、但既不能答复也不能推进" —— **不列状态名**：
   * 状态有哪些、哪几个由 agent 处理是后端的定义（`AgentTaskStatus.awaitingAgent()`），
   * 前端再抄一份清单，就会在后端新增一格时漏掉它，而漏掉的表现是这一格什么都不显示。
   */
  const awaitingAgent = actions.length > 0 && !advanceAction && !canAnswer

  const close = () => {
    setResultText(''); setFreeText(''); setCancelOpen(false)
    tasks.closeTask()
  }

  const submitAnswer = async (text: string) => {
    if (!task || !pending || !text.trim()) return
    const ok = await tasks.answer(task.id, pending.id, text.trim())
    if (ok) setFreeText('')
  }

  return (
    <Modal
      open
      width={760}
      title={task ? `任务 #${task.id} · ${task.statusLabel}（第 ${task.currentStep} 步）` : '任务'}
      onCancel={close}
      footer={<Button onClick={close}>关闭</Button>}
    >
      {detail.status === 'loading' && <Spin description="正在读任务详情…" />}
      {detail.status === 'error' && <Alert type="error" showIcon title="读任务详情失败" description={detail.error} />}
      {task && (
        <Flex vertical gap={12}>
          <Paragraph className="task-detail-desc">{task.description}</Paragraph>
          <Flex gap={8} wrap className="task-detail-meta">
            <Text type="secondary">发布人：{task.publisher}</Text>
            <Text type="secondary">任务人：{task.assignee || '（未指派）'}</Text>
            <Text type="secondary">当前执行人：{task.currentExecutor || '（无）'}</Text>
            <Text type="secondary">agent：{task.agentId != null ? `#${task.agentId}` : '（未指定）'}</Text>
          </Flex>
          {task.result && <Alert type="success" showIcon title="执行结果" description={task.result} />}
          {task.cancelReason && <Alert type="warning" showIcon title="取消原因" description={task.cancelReason} />}

          <div className="task-confirms">
            <Text strong>确认项（每一步是谁怎么答的）</Text>
            {data.confirms.length === 0
              ? <div className="task-column-empty">还没有确认项 —— 「新发布」的任务等 agent 判断需求有没有歧义</div>
              : data.confirms.map(confirm => <ConfirmRow key={confirm.id} confirm={confirm} />)}
          </div>

          {/* 动作区：按钮来自后端的 actions（见文件头），这里只负责把它们画出来并收集输入 */}
          <div className="task-actions">
            {awaitingAgent && (
              // 没有可点的推进按钮时要说清"为什么没有"：否则用户会以为按钮该出现却没出现
              <Alert
                type="info"
                showIcon
                title="这一步由 agent 任务执行做"
                description="它按状态往下走：新发布判需求有没有歧义、已确认需求拆任务点、已确认计划问授权、已授权开跑。跑完会自己转到「待验收」，页面上不需要点。"
              />
            )}
            {canAnswer && pending && (
              <Flex vertical gap={8}>
                <Text strong>答复这条确认项</Text>
                {/* 选项集是几个按钮：点一下就是一次答复（与"自由回复"走同一个端点） */}
                {pending.options.length > 0 && (
                  <Space size={4} wrap>
                    {pending.options.map(option => (
                      <Button key={option} size="small" disabled={tasks.busy}
                        onClick={() => void submitAnswer(option)}>
                        {option}
                      </Button>
                    ))}
                  </Space>
                )}
                <Space.Compact style={{ width: '100%' }}>
                  <Input
                    placeholder="或者直接写一段回复（选项之外想补充的话就写在这里）"
                    value={freeText}
                    onChange={e => setFreeText(e.target.value)}
                    onPressEnter={() => void submitAnswer(freeText)}
                  />
                  <Button type="primary" disabled={!freeText.trim() || tasks.busy}
                    onClick={() => void submitAnswer(freeText)}>
                    答复
                  </Button>
                </Space.Compact>
              </Flex>
            )}
            {(advanceAction || canCancel) && (
              <Space size={8} wrap>
                {advanceAction && needsResult && (
                  <Input.TextArea
                    rows={2}
                    placeholder="执行结果（可留空 —— 跑完了但没有产出也是正常结果）"
                    value={resultText}
                    onChange={e => setResultText(e.target.value)}
                  />
                )}
                {advanceAction && (
                  <Button type="primary" disabled={tasks.busy}
                    onClick={() => void tasks.advance(task.id, needsResult ? resultText : undefined)}>
                    {advanceAction.label}
                  </Button>
                )}
                {canCancel && (
                  <Button danger disabled={tasks.busy} onClick={() => setCancelOpen(true)}>取消任务</Button>
                )}
              </Space>
            )}
            {actions.length === 0 && <Text type="secondary">这条任务已经结束，没有可做的动作。</Text>}
          </div>
        </Flex>
      )}

      <CancelModal open={cancelOpen} taskId={task?.id} onClose={() => setCancelOpen(false)} tasks={tasks} />
    </Modal>
  )
}

/** 一条确认项：第几步、哪一类、问的是什么、给了哪些选项、答的是什么。 */
function ConfirmRow({ confirm }: { confirm: AgentTaskConfirm }) {
  return (
    <div className={`task-confirm${confirm.answered ? ' is-answered' : ' is-waiting'}`}>
      <Flex gap={6} align="center" wrap>
        <Tag color="purple">第 {confirm.stepIndex} 步</Tag>
        <Text strong>{confirm.typeLabel}</Text>
        {!confirm.answered && <Tag color="orange">待答复</Tag>}
      </Flex>
      {confirm.description && <div className="task-confirm-desc">{confirm.description}</div>}
      {confirm.options.length > 0 && (
        <div className="task-confirm-options">选项：{confirm.options.join(' / ')}</div>
      )}
      {/* 答复文案用的是后端给的 answerLabel（没答时是「（未答复）」）——
          这里再拼一份的话，两处说法迟早不一致 */}
      <div className="task-confirm-answer">用户确认项：{confirm.answerLabel}</div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  弹窗：发布任务 / 取消任务                                            */
/* ------------------------------------------------------------------ */

function NewTaskModal({ open, onClose, tasks }: {
  open: boolean
  onClose: () => void
  tasks: UseTasksResult
}) {
  const [publisher, setPublisher] = useState('')
  const [assignee, setAssignee] = useState('')
  const [description, setDescription] = useState('')
  const [agentId, setAgentId] = useState('')

  const reset = () => {
    setPublisher(''); setAssignee(''); setDescription(''); setAgentId('')
  }
  const close = () => { reset(); onClose() }

  const submit = async () => {
    const parsedAgent = agentId.trim() === '' ? null : Number(agentId.trim())
    const ok = await tasks.create({
      publisher,
      assignee,
      description,
      agentId: parsedAgent !== null && Number.isFinite(parsedAgent) ? parsedAgent : null,
    })
    // 成功才关：失败时关掉会把用户填的内容丢掉，而他正需要照着报错改一改
    if (ok) close()
  }

  return (
    <Modal
      open={open}
      title="发布任务"
      okText="发布"
      cancelText="取消"
      confirmLoading={tasks.busy}
      onOk={() => void submit()}
      onCancel={close}
      okButtonProps={{ disabled: !publisher.trim() || !description.trim() }}
    >
      <Flex vertical gap={10}>
        <Input placeholder="发布人（必填）—— 指派出任务的人" value={publisher}
          onChange={e => setPublisher(e.target.value)} />
        <Input placeholder="任务人（可留空）—— 交付任务的人" value={assignee}
          onChange={e => setAssignee(e.target.value)} />
        <Input.TextArea rows={3} placeholder="任务描述（必填）—— 要做什么；判定需求有没有歧义、拆任务点都以它为准"
          value={description} onChange={e => setDescription(e.target.value)} />
        <Input placeholder="agentId（可留空）—— 这条任务交给哪个 Agent 应用" value={agentId}
          onChange={e => setAgentId(e.target.value)} />
        <Text type="secondary">
          发布后任务落在<strong>「新任务」</strong>列、步骤 1、当前执行人是任务人 ——
          随后由 <strong>agent 任务执行</strong>判断需求：有歧义就生成一条需求确认项（进「待处理」等你答），没有就直接进「执行中」。
        </Text>
      </Flex>
    </Modal>
  )
}

function CancelModal({ open, taskId, onClose, tasks }: {
  open: boolean
  taskId: number | undefined
  onClose: () => void
  tasks: UseTasksResult
}) {
  const [reason, setReason] = useState('')

  const close = () => { setReason(''); onClose() }

  const submit = async () => {
    if (taskId === undefined || !reason.trim()) return
    const ok = await tasks.cancel(taskId, reason.trim())
    if (ok) close()
  }

  return (
    <Modal
      open={open}
      title={taskId === undefined ? '取消任务' : `取消任务 #${taskId}`}
      okText="确认取消"
      cancelText="再想想"
      okButtonProps={{ danger: true, disabled: !reason.trim() }}
      confirmLoading={tasks.busy}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={10}>
        <Alert
          type="warning"
          showIcon
          title="取消原因必填，而且它会被留下"
          description="任务不删除，只置为「已取消」；这条原因会一直显示在那张卡片上 —— 事后要看的是「当时为什么取消」。"
        />
        <Input.TextArea rows={3} placeholder="取消原因" value={reason}
          onChange={e => setReason(e.target.value)} />
      </Flex>
    </Modal>
  )
}
