import { Alert, Button, Card, Empty, Flex, Input, Modal, Space, Spin, Tag, Typography } from 'antd'
import { useState } from 'react'
import type { AgentTaskCard, AgentTaskConfirm } from '../api/tasks'
import type { UseTasksResult } from '../hooks/useTasks'
import { Clamped } from './Clamped'
import { UserPicker } from './UserPicker'

const { Text, Paragraph } = Typography

/**
 * 应用管理区 · agent任务（对接 `agent_task` / `agent_task_confirm` 两张表）。
 *
 * <h2>这一页是什么：一张按状态分列的卡片看板</h2>
 * 五列对应任务从发布到结束的五段（新任务 / 待处理 / 执行中 / 待验收 / 已完成），
 * 状态与列的归属**由后端算**（`AgentTaskService.Column`），前端只按 key 画 ——
 * 前端再定一份"哪个状态进哪一列"就是第二份判据，而两份不一致时卡片会出现在错误的列上。
 *
 * <h2>★ 五条不能省的说法</h2>
 * <ol>
 *   <li><b>卡片停在哪一列 = 现在等谁</b>：看板不解释这一点，"待处理"就只是一个堆着东西的筐；
 *       所以列头写出这一列收的是哪几种状态，卡片上写出当前执行人；</li>
 *   <li><b>"新发布"不等于"待确认需求"</b>：前者还没有确认项可答（它等 agent 判需求有没有歧义），
 *       后者有一条在等他答 —— 两个状态挨着，说反了会让用户去找一个不存在的按钮；</li>
 *   <li><b>取消原因要显示出来</b>：取消是这一页唯一"没有下文"的结局，
 *       原因不显示的话，那条卡片与"被误操作取消的"看起来完全一样；</li>
 *   <li><b>发布人 / 任务人只能从用户列表里选</b>：那两个名字在发出去的时候核一遍账号表，
 *       而库里存的是核出来的用户 id（姓名是读侧关联查询出来的）——
 *       所以那两格是搜索选择器（`UserPicker`），而 <b>agentId 根本不在表单里</b>：
 *       它由平台在第一次接手这条任务时写入（配置项 `ai.agent.task.agent-id`）；</li>
 *   <li><b>这份看板是"与我有关的活"，要说清它筛过了</b>：登录之后看板只收与他有关的那些
 *       （后端按状态挑列，见 `taskApi.board`），而屏幕上少了的那几条
 *       没有任何痕迹 —— 所以卡片标题下面那一行必须写出"按谁筛的"。没登录时不筛，
 *       那一行也要说出来（否则"没有任务"与"没筛"看起来一样）。</li>
 * </ol>
 *
 * <h2>「权限不足」那一格：原因要显示，两个按钮要说清各做什么</h2>
 * 它的结论与执行结果落在同一个字段（`task.result`），所以卡片与详情都要<b>按状态换标签</b>：
 * 写成"执行结果"会让人以为这次跑过了 —— 而它一次都没开始。
 * 两个按钮各是一次选择：`revise`（「已修改任务」）= 任务或权限已经改好，放回「新发布」重走一遍；
 * `cancel` = 这条不做了。按钮同样来自后端的 `actions`，这里只负责画出来。
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
 *
 * <h2>卡片上的长文字默认夹住，夹掉了就在卡片上给一个「展开全文」</h2>
 * 标题与执行结果各最多 6 行（`Clamped`，量出真的夹掉了才画开关）。
 * 判据是**看板要能一眼看全几件事**：一张写着长产出的卡片把整列撑高之后，
 * 同一个窗口里就只剩两三张卡可见，"有几件事在等谁"这个看板唯一的答案就看不出来了。
 * 展开入口必须在卡片上（而不是"想看就去点详情"）：一列里摆着好几张卡时，
 * 哪一张被夹掉靠比对各卡高度是看不出来的。
 */
export function AgentTaskPanel({ tasks, loginName }: {
  tasks: UseTasksResult
  /** 当前登录那个人的显示名（`mockLoginLabel` 算出来的那一份）；没登录是 `null`。 */
  loginName: string | null
}) {
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
        {/* 筛没筛、按谁筛，由**后端回显的那一格**说了算（`board.userId`）——
            这里再推一份"我登录了没有"就是第二份判据。没有这张说明的话，看板看起来
            与"全部任务"完全一样，而用户会以为别人的任务不见了 */}
        <div className="task-board-scope">
          {data?.userId == null
            ? '未登录：这是全部任务（先在上方「模拟登录」里选一个人，看板就只看与他有关的那些）。'
            : `只看与 ${loginName ?? '（没记下名字）'} 有关的任务（用户 id ${data.userId}）`
              + ' —— 归别人在办的那些不在这一份里，退出登录即可看全部。'}
        </div>
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

/**
 * 一张卡片。
 *
 * <h2>默认夹住：标题与执行结果各最多 6 行，夹掉了才给一个「展开全文」</h2>
 * 任务描述可以很长（执行结果更是一份多行产出），不夹住的话一张卡片能占满整列，
 * 看板就不再是看板。夹住之后必须在**卡片上**给出展开入口，而不是只让人"点开详情看"：
 * 那一列摆着好几张卡时，「哪一张是被夹掉的那张」靠比较各卡的高度是看不出来的。
 *
 * 开关画在状态标签那一行的右端，管的正是上面那一段被夹住的文字 ——
 * 它必须画在被夹的那一段**外面**，否则一收起就连开关一起看不见了。
 *
 * 三处判断各归各的：`overflow`（夹掉了没有）由 `Clamped` 量出来，`expanded` 是用户点的，
 * 而两者都要为真才画 `is-open`（放开上限）。少了 `overflow` 这一半，
 * 短描述的卡片也会挂一个点了没反应的「展开全文」。
 */
function TaskCard({ task, onOpen }: { task: AgentTaskCard; onOpen: () => void }) {
  /** 上面的文字有没有被夹掉 —— 由 Clamped 量出来（见那个组件）。 */
  const [overflow, setOverflow] = useState(false)
  /** 每张卡片自己的展开态：看板上同时摆着十几张，共用一个状态会让它们一起展开。 */
  const [expanded, setExpanded] = useState(false)
  const open = overflow && expanded
  /**
   * 这一格放的是权限不足的结论，而不是执行结果。
   *
   * <p>两者在**同一个字段**里（`task.result`），标签按状态换 —— 这一格一次都没跑，
   * 标成"执行结果"会让人以为跑过了。样式也跟着换（原来是绿色成功底）。
   */
  const denied = task.status === 'INSUFFICIENT_PERMISSION'

  return (
    <div className={`task-card is-${task.status.toLowerCase()}`} data-task-id={task.id}>
      {/* 卡片整张可点（点开详情）。它是一个铺满卡片的 button，文字那一层不接收指针事件 ——
          button 里不能嵌 button，而「展开全文」必须是真按钮（键盘用户要能按到）。
          于是：这一层负责点击，文字层只负责显示，开关层浮在最上面（见 styles.css）。 */}
      <button type="button" className="task-card-open" onClick={onOpen} aria-hidden="true" tabIndex={-1} />
      <div className="task-card-body">
        <span className="task-card-text">
          <Clamped text={task.description} className="task-card-title" maxHeight={66}
            open={open} onOverflow={(_region, more) => setOverflow(more)} />
          {/* 执行结果 / 权限不足的结论 / 取消原因都只在自己那一段出现：没有的时候不画一行空白。
              三者的标签由这里给（后端只给字段值）：「权限不足」与「执行结果」共用 result */}
          {task.result && (
            <Clamped text={`${denied ? '权限不足' : '执行结果'}：${task.result}`}
              className={denied ? 'task-card-reason' : 'task-card-result'} maxHeight={84}
              open={open} onOverflow={(_region, more) => setOverflow(more)} />
          )}
          {task.cancelReason && (
            <Clamped text={`取消原因：${task.cancelReason}`} className="task-card-cancel" maxHeight={62}
              open={open} onOverflow={(_region, more) => setOverflow(more)} />
          )}
        </span>
        <span className="task-card-meta">
          {task.publisher} → {task.assignee || '（未指派任务人）'}
        </span>
        {/* 当前执行人那一格的字由后端给（姓名，或球在 agent 手上时的 agent#…），这里不自己拼 ——
            再拼一份的话，两处说法迟早不一致（与状态标签同一条规矩） */}
        <span className="task-card-meta">
          第 {task.currentStep} 步 · 当前执行人 {task.currentExecutor || '（无）'}
        </span>
        <span className="task-card-foot">
          <Tag color={statusColor(task.status)}>{task.statusLabel}</Tag>
          {overflow && (
            <button type="button" className="task-card-toggle"
              onClick={() => setExpanded(!expanded)}>
              {expanded ? '收起' : '展开全文'}
            </button>
          )}
        </span>
      </div>
    </div>
  )
}

function statusColor(status: string): string {
  if (status === 'COMPLETED') return 'green'
  if (status === 'CANCELLED') return 'default'
  if (status === 'EXECUTING') return 'processing'
  // 「权限不足」与三个「待确认」同列（都在等人），但它等的不是答复而是一次处置 ——
  // 同色会让它在那一列里混成"又一条待确认"，而它要的动作完全不同
  if (status === 'INSUFFICIENT_PERMISSION') return 'red'
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
  const reviseAction = actions.find(action => action.key === 'revise')
  const canAnswer = actions.some(action => action.key === 'answer')
  const canCancel = actions.some(action => action.key === 'cancel')
  // 「登记执行结果」那一步要多一个输入框：结果不是选项，得让人写
  const needsResult = task?.status === 'EXECUTING'
  /** 这一格的结论是"权限不足"，不是执行结果 —— 两者共用 `result`，标签按状态换。 */
  const denied = task?.status === 'INSUFFICIENT_PERMISSION'
  /**
   * 这一步在等 agent，人现在点不动。
   *
   * <p>判据是"有动作、但既不能答复也不能推进，也不能重发" —— **不列状态名**：
   * 状态有哪些、哪几个由 agent 处理是后端的定义（`AgentTaskStatus.awaitingAgent()`），
   * 前端再抄一份清单，就会在后端新增一格时漏掉它，而漏掉的表现是这一格什么都不显示。
   */
  const awaitingAgent = actions.length > 0 && !advanceAction && !reviseAction && !canAnswer

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
            {/* 为空只表示"还没有 agent 接手过它"（发布时为空、平台第一次接手时才写）。
                刻意不写「未指定」：这一列没有人能填，那句话会让人以为是自己漏填了 */}
            <Text type="secondary">agent：{task.agentId ?? '（还没有 agent 接手）'}</Text>
          </Flex>
          {task.result && (denied
            ? (
              // 权限不足：这一格一次都没跑，所以不能用"执行结果"那个绿色成功框 ——
              // 结论（缺哪条目录前缀、怎么放行）由后端给，这里原样显示，不自己拼一份
              <Alert
                type="error"
                showIcon
                title="权限不足：这次执行没有开始"
                description={<div className="diag-note">
                  <p>{task.result}</p>
                  <p>
                    两条路：「已修改任务」= 任务或权限已经改好，把它放回「新发布」重新走一遍
                    （重新判需求 → 拆任务点 → 判授权）；「取消任务」= 这条不做了。
                  </p>
                </div>}
              />
            )
            : <Alert type="success" showIcon title="执行结果" description={task.result} />)}
          {task.cancelReason && <Alert type="warning" showIcon title="取消原因" description={task.cancelReason} />}

          <div className="task-confirms">
            <Text strong>确认项（每一步是谁怎么答的）</Text>
            {/* 没有确认项有两种原因，说反了会让用户去找一条不存在的待办：
                新发布的任务还没生成过确认项（等 agent 判需求有没有歧义），
                而权限不足的任务可能是判断发生在生成授权确认项**之前** */}
            {data.confirms.length === 0
              ? <div className="task-column-empty">{denied
                ? '这条任务没有确认项 —— 授权判断在生成授权确认项之前就没通过（上一条结论里写着缺什么）'
                : '还没有确认项 —— 「新发布」的任务等 agent 判断需求有没有歧义'}</div>
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
            {(advanceAction || reviseAction || canCancel) && (
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
                {/* 「已修改任务」：按钮上那句话由后端给（它同时是这一格的语义），这里只画出来。
                    点了就回「新发布」重走一遍，所以文案必须让人看清这不是"再试一次原样执行" */}
                {reviseAction && (
                  <Button type="primary" disabled={tasks.busy}
                    onClick={() => void tasks.revise(task.id)}>
                    {reviseAction.label}
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

  const reset = () => {
    setPublisher(''); setAssignee(''); setDescription('')
  }
  const close = () => { reset(); onClose() }

  const submit = async () => {
    const ok = await tasks.create({ publisher, assignee, description })
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
      okButtonProps={{ disabled: !publisher || !assignee || !description.trim() }}
    >
      <Flex vertical gap={10}>
        {/* 发布人 / 任务人两格都是**搜索选择器**，不是能打字的输入框：
            发出去的时候要把名字核到账号表上（落库的是查出来的用户 id），
            而账号表里查不到的名字不会有任何约束拦下那条任务。
            这个组件与页头的「模拟登录」共用（见 UserPicker 的文件头） */}
        <UserPicker value={publisher} onChange={name => setPublisher(name)}
          placeholder="发布人（必填）—— 搜索登录名，从候选里选一个真实用户" />
        <UserPicker value={assignee} onChange={name => setAssignee(name)}
          placeholder="任务人（必填）—— 搜索登录名，从候选里选一个真实用户" />
        <Input.TextArea rows={3} placeholder="任务描述（必填）—— 要做什么；判定需求有没有歧义、拆任务点都以它为准"
          value={description} onChange={e => setDescription(e.target.value)} />
        <Text type="secondary">
          发布后任务落在<strong>「新任务」</strong>列、步骤 1、当前执行人是任务人 ——
          随后由 <strong>agent 任务执行</strong>判断需求：有歧义就生成一条需求确认项（进「待处理」等你答），没有就直接进「执行中」。
          交给哪个 Agent 应用不由发布人填：平台在第一次接手这条任务时写入（配置项 <Text code>ai.agent.task.agent-id</Text>）。
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
