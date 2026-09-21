/**
 * agent 任务端点的**假数据**（应用管理 · agent任务）。
 *
 * ---------------------------------------------------------------------------
 * 与 permission-routes / prompt-routes 同一条纪律：**有写端点，就必须有 __reset**
 * ---------------------------------------------------------------------------
 * 这一组有四个写端点（发布 / 推进 / 答复 / 取消），界面自检要验的正是
 * "点一下之后卡片真的换了列、确认项真的多了一条"。所以状态是**进程内可变**的，
 * 并且必须提供 `POST /api/agent-tasks/__reset` —— 否则自检跑第二遍时会继承第一遍改出来的任务。
 *
 * ---------------------------------------------------------------------------
 * ★ 假数据的判据照搬真后端（`DefaultAgentTaskService`），而不是"随便返回个成功"
 * ---------------------------------------------------------------------------
 * 这一页最需要被验证的两个分支都是**失败**分支：
 *   1. 状态不允许的动作 → 409（不是 500）—— 界面上要出现"这条任务的状态已经变了"，
 *      而"两个人在看同一张看板"正是这个码最常见的来源；
 *   2. 缺参数（没发布人 / 空答复 / 取消不写原因）→ 400 带一句能照做的话。
 * 另外三步推进（新发布 → 待确认需求 → 待确认计划 → 待授权 → 执行中 → 待验收 → 已完成）
 * 与"答复后自动生成下一条确认项"必须逐条照搬 —— 假后端不实现的话，
 * "卡片会不会换列"这件事在自检里永远走不到，而那是这一页的全部内容。
 *
 * ---------------------------------------------------------------------------
 * 初始数据刻意覆盖**每一列**
 * ---------------------------------------------------------------------------
 * 五列都要有卡片，看板的分组才验得出来；「已完成」那一列刻意放**两条**：
 * 一条验收通过的、一条已取消的（这一列收两个状态，只放一条就看不出这件事）——
 * 已取消那条带着取消原因，自检要断言"原因显示在卡片上"（它是两者唯一的区别）。
 */

const STATUS_LABELS = {
  NEW: '新发布',
  PENDING_REQUIREMENT: '待确认需求',
  PENDING_PLAN: '待确认计划',
  PENDING_AUTHORIZATION: '待授权',
  EXECUTING: '执行中',
  PENDING_ACCEPTANCE: '待验收',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

const CONFIRM_LABELS = {
  REQUIREMENT: '需求确认',
  PLAN: '计划确认',
  AUTHORIZATION: '授权确认',
}

/**
 * 列的定义与顺序，与真后端 `AgentTaskService.Column` 逐条一致（前端按 key 画）。
 *
 * ⚠️ 「已完成」一列收**两个**状态（已完成与已取消）：两者都是"这件事结束了"，
 * 而给已取消单开一列会让最边缘的结局占掉一整列宽度。区别靠卡片上的状态标签与取消原因表达。
 */
const COLUMNS = [
  { key: 'new', label: '新任务', statuses: ['NEW'] },
  { key: 'pending', label: '待处理', statuses: ['PENDING_REQUIREMENT', 'PENDING_PLAN', 'PENDING_AUTHORIZATION'] },
  { key: 'executing', label: '执行中', statuses: ['EXECUTING'] },
  { key: 'acceptance', label: '待验收', statuses: ['PENDING_ACCEPTANCE'] },
  { key: 'completed', label: '已完成', statuses: ['COMPLETED', 'CANCELLED'] },
]

/** 三种确认各自默认的选项集（与真后端的缺省文案同源，见 DefaultAgentTaskService）。 */
const DEFAULT_OPTIONS = {
  REQUIREMENT: ['确认', '需要补充'],
  PLAN: ['同意', '不同意'],
  AUTHORIZATION: ['授权', '拒绝'],
}

function defaultConfirmDescription(task, type) {
  if (type === 'REQUIREMENT') return `请确认任务需求：${task.description}`
  if (type === 'PLAN') return '请确认执行计划（脚手架缺省：计划正文待接入 Agent 按任务生成）'
  return '请确认授权范围（脚手架缺省：授权正文待接入 Agent 按任务生成）'
}

/** 初始状态。每次 __reset 都从这里重建（深拷贝，避免被就地改坏）。 */
function initialState() {
  return {
    tasks: [
      // 新发布：还没有确认项，等任务人「发起需求确认」
      {
        id: 1, publisher: '张三', assignee: '李四', description: '整理 9 月项目周报：按周汇总进展与风险',
        status: 'NEW', currentExecutor: '李四', currentStep: 1, agentId: 7, result: null, cancelReason: null,
      },
      // 待确认需求：第 1 条确认项还没答
      {
        id: 2, publisher: '王五', assignee: '赵六', description: '把检索评测的对比数据整理成一页结论',
        status: 'PENDING_REQUIREMENT', currentExecutor: '王五', currentStep: 1, agentId: 3, result: null,
        cancelReason: null,
      },
      // 待确认计划：第 1 步已答，第 2 步在等
      {
        id: 3, publisher: '张三', assignee: '李四', description: '给工具调用那一页补一份失败分类的说明',
        status: 'PENDING_PLAN', currentExecutor: '张三', currentStep: 2, agentId: null, result: null,
        cancelReason: null,
      },
      // 待授权：前两步已答，第 3 步在等
      {
        id: 4, publisher: '王五', assignee: '赵六', description: '把成本看板的单价表换成新合同价',
        status: 'PENDING_AUTHORIZATION', currentExecutor: '王五', currentStep: 3, agentId: 7, result: null,
        cancelReason: null,
      },
      // 执行中：授权已给，agent 在跑
      {
        id: 5, publisher: '张三', assignee: '李四', description: '清理 dev 分支上过期的实验脚本',
        status: 'EXECUTING', currentExecutor: 'agent#7', currentStep: 4, agentId: 7, result: null,
        cancelReason: null,
      },
      // 待验收：执行结果已经登记，等发布人点头
      {
        id: 6, publisher: '王五', assignee: '赵六', description: '给告警规则那一页补三条自检断言',
        status: 'PENDING_ACCEPTANCE', currentExecutor: '王五', currentStep: 5, agentId: 3,
        result: '三条断言已补：判据目录 / 灰度开关 / 通知点。', cancelReason: null,
      },
      // 已完成
      {
        id: 7, publisher: '张三', assignee: '李四', description: '把 README 里的端口说明改成环境变量法',
        status: 'COMPLETED', currentExecutor: '', currentStep: 6, agentId: 7,
        result: '已改成 CI_MOCK_PORT / CI_UI_PORT 两行说明。', cancelReason: null,
      },
      // 已取消：这一列只在有卡片时出现，所以假数据里必须有一条
      {
        id: 8, publisher: '王五', assignee: '赵六', description: '调研换一个向量库（先搁置）',
        status: 'CANCELLED', currentExecutor: '赵六', currentStep: 1, agentId: null, result: null,
        cancelReason: '需求方撤回了这条调研，等定下来再重新发布',
      },
    ],
    confirms: [
      { id: 11, taskId: 2, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把检索评测的对比数据整理成一页结论', options: DEFAULT_OPTIONS.REQUIREMENT, answer: null },
      { id: 12, taskId: 3, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：给工具调用那一页补一份失败分类的说明', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 13, taskId: 3, stepIndex: 2, type: 'PLAN', description: '请确认执行计划（脚手架缺省：计划正文待接入 Agent 按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: null },
      { id: 14, taskId: 4, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把成本看板的单价表换成新合同价', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 15, taskId: 4, stepIndex: 2, type: 'PLAN', description: '请确认执行计划（脚手架缺省：计划正文待接入 Agent 按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
      { id: 16, taskId: 4, stepIndex: 3, type: 'AUTHORIZATION', description: '请确认授权范围（脚手架缺省：授权正文待接入 Agent 按任务生成）', options: DEFAULT_OPTIONS.AUTHORIZATION, answer: null },
      { id: 17, taskId: 5, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：清理 dev 分支上过期的实验脚本', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 18, taskId: 5, stepIndex: 2, type: 'PLAN', description: '请确认执行计划（脚手架缺省：计划正文待接入 Agent 按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
      { id: 19, taskId: 5, stepIndex: 3, type: 'AUTHORIZATION', description: '请确认授权范围（脚手架缺省：授权正文待接入 Agent 按任务生成）', options: DEFAULT_OPTIONS.AUTHORIZATION, answer: '授权' },
    ],
    nextTaskId: 9,
    nextConfirmId: 20,
  }
}

let state = initialState()

/* ------------------------------------------------------------------ */
/*  视图                                                                */
/* ------------------------------------------------------------------ */

function taskView(task) {
  return {
    id: task.id,
    publisher: task.publisher,
    assignee: task.assignee ?? '',
    description: task.description,
    status: task.status,
    statusLabel: STATUS_LABELS[task.status] ?? task.status,
    currentExecutor: task.currentExecutor ?? '',
    currentStep: task.currentStep,
    agentId: task.agentId ?? null,
    result: task.result ?? null,
    cancelReason: task.cancelReason ?? null,
    createdAt: null,
    updatedAt: null,
  }
}

function confirmView(confirm) {
  return {
    id: confirm.id,
    taskId: confirm.taskId,
    stepIndex: confirm.stepIndex,
    type: confirm.type,
    typeLabel: CONFIRM_LABELS[confirm.type] ?? '（认不出的类型）',
    description: confirm.description,
    options: confirm.options,
    answer: confirm.answer ?? null,
    // 与真后端一致：没答时那一格显示的是这个说法（前端不自己拼）
    answerLabel: confirm.answer ?? '（未答复）',
    answered: confirm.answer != null,
  }
}

/** 现在能做的动作，与真后端的 actionsOf 逐条一致。 */
function actionsOf(status) {
  if (status === 'COMPLETED' || status === 'CANCELLED') return []
  if (status === 'NEW') return [{ key: 'advance', label: '发起需求确认' }, { key: 'cancel', label: '取消任务' }]
  if (status === 'EXECUTING') return [{ key: 'advance', label: '登记执行结果' }, { key: 'cancel', label: '取消任务' }]
  if (status === 'PENDING_ACCEPTANCE') return [{ key: 'advance', label: '验收通过' }, { key: 'cancel', label: '取消任务' }]
  return [{ key: 'answer', label: '答复确认项' }, { key: 'cancel', label: '取消任务' }]
}

function confirmsOf(taskId) {
  return state.confirms.filter(c => c.taskId === taskId)
}

function detailView(task) {
  return {
    task: taskView(task),
    confirms: confirmsOf(task.id).map(confirmView),
    actions: actionsOf(task.status),
  }
}

/** 该状态在等哪种确认项（与真后端 AgentTaskStatus#waitingConfirmation 一致）。 */
function waitingType(status) {
  if (status === 'PENDING_REQUIREMENT') return 'REQUIREMENT'
  if (status === 'PENDING_PLAN') return 'PLAN'
  if (status === 'PENDING_AUTHORIZATION') return 'AUTHORIZATION'
  return null
}

/* ------------------------------------------------------------------ */
/*  写（与真后端的状态机逐条对应）                                       */
/* ------------------------------------------------------------------ */

function openConfirmation(task, type, step) {
  state.confirms.push({
    id: state.nextConfirmId++,
    taskId: task.id,
    stepIndex: step,
    type,
    description: defaultConfirmDescription(task, type),
    options: DEFAULT_OPTIONS[type],
    answer: null,
  })
}

function advance(task, result) {
  if (task.status === 'NEW') {
    // 步骤不递增：新发布与待确认需求都停在第 1 步（见真后端的 openFirstConfirmation）
    task.status = 'PENDING_REQUIREMENT'
    task.currentExecutor = task.publisher
    openConfirmation(task, 'REQUIREMENT', task.currentStep)
    return null
  }
  if (task.status === 'EXECUTING') {
    task.status = 'PENDING_ACCEPTANCE'
    task.currentStep += 1
    task.currentExecutor = task.publisher
    if (result != null && String(result).trim()) task.result = String(result).trim()
    return null
  }
  if (task.status === 'PENDING_ACCEPTANCE') {
    task.status = 'COMPLETED'
    task.currentStep += 1
    task.currentExecutor = ''
    return null
  }
  return `当前状态「${STATUS_LABELS[task.status]}」没有可推进的事件 —— 能推进的只有「新发布」（发起需求确认）、`
    + '「执行中」（登记执行结果）与「待验收」（验收通过）；三个「待确认」在等确认项的答复，终态不再流转。'
}

function answer(task, confirmId, text) {
  if (text == null || !String(text).trim()) {
    return { error: '答复不能为空 —— 空答复在库里与「还没答」（answer 为 NULL）长得一样，而两者的处置完全不同：前者要推进状态，后者要继续等' }
  }
  const waiting = waitingType(task.status)
  if (!waiting) {
    return { conflict: `当前状态「${STATUS_LABELS[task.status]}」不在等确认项的答复 —— 能答复的只有「待确认需求」/「待确认计划」/「待授权」；要推进请用 advance 那个动作。` }
  }
  const confirm = state.confirms.find(c => c.id === confirmId && c.taskId === task.id)
  if (!confirm) {
    return { error: `确认项不存在或不属于这条任务：确认项 #${confirmId}、任务 #${task.id}` }
  }
  if (confirm.type !== waiting || confirm.stepIndex !== task.currentStep) {
    return { conflict: `这条确认项不属于任务当前这一步 —— 它问的是「${CONFIRM_LABELS[confirm.type]}」（第 ${confirm.stepIndex} 步），`
      + `而任务现在在第 ${task.currentStep} 步、等的是「${CONFIRM_LABELS[waiting]}」。多半是另一处已经推进过这条任务，刷新一次就能看到新的待办。` }
  }
  if (confirm.answer != null) {
    return { conflict: '这条确认项已经答复过了 —— 重复答复会覆盖掉第一次的选择，而覆盖之后界面上只剩一个答案，没有任何痕迹说明它曾经是另一个。刷新后看到的就是当时的答复' }
  }
  confirm.answer = String(text).trim()

  // 答复之后按链推进（每步一条确认项，见真后端的 answer）
  if (waiting === 'REQUIREMENT') {
    task.status = 'PENDING_PLAN'
    task.currentStep += 1
    task.currentExecutor = task.publisher
    openConfirmation(task, 'PLAN', task.currentStep)
  }
  else if (waiting === 'PLAN') {
    task.status = 'PENDING_AUTHORIZATION'
    task.currentStep += 1
    task.currentExecutor = task.publisher
    openConfirmation(task, 'AUTHORIZATION', task.currentStep)
  }
  else {
    task.status = 'EXECUTING'
    task.currentStep += 1
    task.currentExecutor = task.agentId != null ? `agent#${task.agentId}` : 'agent'
  }
  return null
}

/* ------------------------------------------------------------------ */
/*  路由                                                                */
/* ------------------------------------------------------------------ */

/**
 * @returns true 表示这条请求已被本模块处理（调用方不要再往后走）
 */
export function handleTaskRoutes(req, res, url, json) {
  const { pathname } = url
  if (!pathname.startsWith('/api/agent-tasks')) return false

  // 自检脚本靠它做到确定性（见文件头）
  if (req.method === 'POST' && pathname === '/api/agent-tasks/__reset') {
    state = initialState()
    json(res, { ok: true, note: 'agent 任务假后端已复位' })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/agent-tasks') {
    const columns = COLUMNS.map(column => {
      const tasks = state.tasks.filter(task => column.statuses.includes(task.status))
      return {
        key: column.key,
        label: column.label,
        statusLabels: column.statuses.map(s => STATUS_LABELS[s]),
        count: tasks.length,
        tasks: tasks.map(taskView),
      }
    })
    json(res, {
      columns,
      total: state.tasks.length,
      howToAdvance: 'POST /api/agent-tasks/{id}/advance | /confirms/{confirmId}/answer | /cancel',
    })
    return true
  }

  const detailMatch = pathname.match(/^\/api\/agent-tasks\/(\d+)$/)
  if (detailMatch && req.method === 'GET') {
    const task = state.tasks.find(t => t.id === Number(detailMatch[1]))
    if (!task) return json(res, { ok: false, error: `任务不存在：#${detailMatch[1]}` }, 400)
    json(res, detailView(task))
    return true
  }

  if (req.method === 'POST' && pathname === '/api/agent-tasks') {
    readBody(req, res, json, b => {
      if (!b.publisher || !String(b.publisher).trim()) {
        return json(res, { ok: false, error: '缺少 publisher（发布人）' }, 400)
      }
      if (!b.description || !String(b.description).trim()) {
        return json(res, { ok: false, error: '缺少 description（任务描述）' }, 400)
      }
      const assignee = b.assignee && String(b.assignee).trim() ? String(b.assignee).trim() : null
      const task = {
        id: state.nextTaskId++,
        publisher: String(b.publisher).trim(),
        assignee,
        description: String(b.description).trim(),
        status: 'NEW',
        // 与真后端 AgentTask.draft 一致：当前执行人就是任务人（球在他手上）
        currentExecutor: assignee,
        currentStep: 1,
        agentId: typeof b.agentId === 'number' ? b.agentId : null,
        result: null,
        cancelReason: null,
      }
      state.tasks.push(task)
      json(res, { ok: true, task: taskView(task) })
    })
    return true
  }

  const advanceMatch = pathname.match(/^\/api\/agent-tasks\/(\d+)\/advance$/)
  if (advanceMatch && req.method === 'POST') {
    const task = state.tasks.find(t => t.id === Number(advanceMatch[1]))
    if (!task) return json(res, { ok: false, error: `任务不存在：#${advanceMatch[1]}` }, 400)
    readBody(req, res, json, b => {
      const conflict = advance(task, b ? b.result : null)
      if (conflict) return json(res, { ok: false, error: conflict }, 409)
      json(res, { ok: true, ...detailView(task) })
    })
    return true
  }

  const answerMatch = pathname.match(/^\/api\/agent-tasks\/(\d+)\/confirms\/(\d+)\/answer$/)
  if (answerMatch && req.method === 'POST') {
    const task = state.tasks.find(t => t.id === Number(answerMatch[1]))
    if (!task) return json(res, { ok: false, error: `任务不存在：#${answerMatch[1]}` }, 400)
    readBody(req, res, json, b => {
      const outcome = answer(task, Number(answerMatch[2]), b ? b.answer : null)
      if (outcome && outcome.conflict) return json(res, { ok: false, error: outcome.conflict }, 409)
      if (outcome && outcome.error) return json(res, { ok: false, error: outcome.error }, 400)
      json(res, { ok: true, ...detailView(task) })
    })
    return true
  }

  const cancelMatch = pathname.match(/^\/api\/agent-tasks\/(\d+)\/cancel$/)
  if (cancelMatch && req.method === 'POST') {
    const task = state.tasks.find(t => t.id === Number(cancelMatch[1]))
    if (!task) return json(res, { ok: false, error: `任务不存在：#${cancelMatch[1]}` }, 400)
    readBody(req, res, json, b => {
      const reason = b && b.reason ? String(b.reason).trim() : ''
      if (!reason) {
        return json(res, {
          ok: false,
          error: '取消原因必填 —— 一条没有原因的取消记录，事后只能靠回忆（与「谁批的」同一条规矩：那一列空着等于没有这条记录）',
        }, 400)
      }
      if (task.status === 'COMPLETED' || task.status === 'CANCELLED') {
        return json(res, { ok: false, error: `任务已经是「${STATUS_LABELS[task.status]}」，不能再取消` }, 409)
      }
      task.status = 'CANCELLED'
      // 步骤与执行人保持不动：它们回答"取消的那一刻球在谁手上"（与真后端一致）
      task.cancelReason = reason
      json(res, { ok: true, ...detailView(task) })
    })
    return true
  }

  return false
}

/**
 * 读请求体（假后端的最小实现）。
 *
 * 请求体是**异步到达**的（data / end 两个事件），所以这里没有返回值可用：
 * 路径已经匹配上之后一律返回 true，具体响应在回调里发。
 */
function readBody(req, res, json, handle) {
  let raw = ''
  req.on('data', chunk => { raw += chunk })
  req.on('end', () => {
    let body = {}
    try {
      body = raw ? JSON.parse(raw) : {}
    } catch {
      json(res, { ok: false, error: '请求体不是合法 JSON' }, 400)
      return
    }
    handle(body)
  })
}
