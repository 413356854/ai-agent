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
 * ★ 假数据的判据照搬真后端，而不是"随便返回个成功"
 * ---------------------------------------------------------------------------
 * 这一页最需要被验证的两个分支都是**失败**分支：
 *   1. 状态不允许的动作 → 409（不是 500）—— 界面上要出现"这条任务的状态已经变了"，
 *      而"两个人在看同一张看板"正是这个码最常见的来源；
 *   2. 缺参数（没发布人 / 空答复 / 取消不写原因）→ 400 带一句能照做的话。
 * 另外整条状态链（新发布 → 待确认需求 → 已确认需求 → 待确认计划 → 已确认计划 →
 * 待授权 → 已授权 → 执行中 → 待验收 → 已完成）与"答复之后落到哪一格"必须逐条照搬 ——
 * 假后端不实现的话，"卡片会不会换列"这件事在自检里永远走不到，而那是这一页的全部内容。
 *
 * ---------------------------------------------------------------------------
 * ★ agent 那几格在这里是**显式推**的（`POST /api/agent-tasks/__run-agent`）
 * ---------------------------------------------------------------------------
 * 真后端那四格（新发布 / 已确认需求 / 已确认计划 / 已授权）由一个定时任务驱动，
 * 判定调模型、执行跑 Agent 运行。假后端不调模型、也不跑运行，而是给一套**可判定的规则**：
 *   - 描述里带「需要补充」→ 判成"有歧义"（真后端这一步是模型判定）；
 *   - 描述里带「小任务」→ 拆出 2 个任务点（走"跳过计划确认"那条路），否则拆出 5 个
 *     （阈值 5 与真后端的 `ai.agent.task.plan-confirm-threshold` 一致）。
 * 而且**不自动跑**：跟着写端点自动推的话，"发布之后卡片落在哪一列"会取决于推的时机，
 * 自检就没法断言了。所以用 `__run-agent` 显式推一轮（一轮 = 把所有待 agent 的任务推到推不动为止，
 * 与真后端"串行、一次一条、按变更时间"的结果一致）。
 *
 * ---------------------------------------------------------------------------
 * 初始数据刻意覆盖**每一列**
 * ---------------------------------------------------------------------------
 * 五列都要有卡片，看板的分组才验得出来；「执行中」那一列刻意放**四张**：
 * 已确认需求 / 已确认计划 / 已授权 / 执行中 —— 四个状态归并到一列是这一版新增的判据，
 * 只放一张就看不出这件事。「已完成」那一列放**两条**：一条验收通过的、一条已取消的
 * （这一列也收两个状态），已取消那条带着取消原因，自检要断言"原因显示在卡片上"
 * （它是两者唯一的区别）。
 *
 * ---------------------------------------------------------------------------
 * 发布任务：两个名字都要在账号表里，agentId 不由发布人给
 * ---------------------------------------------------------------------------
 * `publisher` 与 `assignee` **都必填**，且都要能在 permission-routes 那份账号表里找到
 * （`realUser`，与真后端 `DefaultAgentTaskService#realUser` 是同一句话）——
 * 账号表只有一份，两份会让"假后端说这个人是真的、真端点说不存在"只表现为自检里一句 400。
 * 请求体里的 `agentId` **不被采纳**：它是平台第一次接手这条任务时写进去的（`AGENT_ID`）。
 */

import { findMockAccount, mockAccountNames } from './permission-routes.mjs'

const STATUS_LABELS = {
  NEW: '新发布',
  PENDING_REQUIREMENT: '待确认需求',
  CONFIRMED_REQUIREMENT: '已确认需求',
  PENDING_PLAN: '待确认计划',
  CONFIRMED_PLAN: '已确认计划',
  PENDING_AUTHORIZATION: '待授权',
  AUTHORIZED: '已授权',
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
 * ⚠️ 「执行中」一列收**四个**状态：它们对看板是同一件事 —— 球在 agent 手上，人不必点它。
 */
const COLUMNS = [
  { key: 'new', label: '新任务', statuses: ['NEW'] },
  { key: 'pending', label: '待处理', statuses: ['PENDING_REQUIREMENT', 'PENDING_PLAN', 'PENDING_AUTHORIZATION'] },
  {
    key: 'executing',
    label: '执行中',
    statuses: ['CONFIRMED_REQUIREMENT', 'CONFIRMED_PLAN', 'AUTHORIZED', 'EXECUTING'],
  },
  { key: 'acceptance', label: '待验收', statuses: ['PENDING_ACCEPTANCE'] },
  { key: 'completed', label: '已完成', statuses: ['COMPLETED', 'CANCELLED'] },
]

/** 等 agent 处理的那几格（与真后端 `AgentTaskStatus.awaitingAgent()` 一致）。 */
const AGENT_STATUSES = ['NEW', 'CONFIRMED_REQUIREMENT', 'CONFIRMED_PLAN', 'AUTHORIZED']

/**
 * 本应用在 Agent 应用注册表里的 id —— 与真后端的 `ai.agent.task.agent-id` 同一个键。
 *
 * <p>平台第一次接手一条任务时把它写进 `agent_id`（真后端是
 * `DefaultAgentTaskExecution#stampAgent`），发布人不填这一列。
 */
const AGENT_ID = 1

/** 三种确认各自默认的选项集（与真后端的缺省按钮同源，见 AgentTaskTransitions#defaultOptions）。 */
const DEFAULT_OPTIONS = {
  REQUIREMENT: ['确认', '需要补充'],
  PLAN: ['同意', '不同意'],
  AUTHORIZATION: ['授权', '拒绝'],
}

/* ------------------------------------------------------------------ */
/*  判定与拆分：真后端调模型，这里用两条可判定的规则                      */
/* ------------------------------------------------------------------ */

function isAmbiguous(description) {
  return description.includes('需要补充')
}

/**
 * 发布人 / 任务人必须是从账号表里选出来的真实用户 —— 与真后端
 * `DefaultAgentTaskService#realUser` 同一条判据（含同一句话）。
 *
 * <p>名字取账号表里那一行自己的写法：界面把 "Admin" 打进来时，卡片上显示的是 "admin"。
 *
 * @returns {{name: string}|{error: string}}
 */
function realUser(raw, what) {
  const wanted = raw == null ? '' : String(raw).trim()
  if (!wanted) {
    return { error: `${what}必填 —— 发布人与任务人都要从用户列表里选一个真实用户`
      + '（搜索：GET /api/permissions/users/search?keyword=…）' }
  }
  const account = findMockAccount(wanted)
  if (!account) {
    return { error: `账号表里没有「${wanted}」这个人，不能当${what} —— 发布人与任务人都是真实用户；`
      + `名字要从用户列表里搜出来选（GET /api/permissions/users/search?keyword=…），`
      + `打错一个字不会有任何约束拦下这条任务，它只会挂在一个不存在的人名下。`
      + `假后端现有账号：${mockAccountNames().join('、')}` }
  }
  return { name: account.name }
}

function breakdownOf(description) {
  return description.includes('小任务')
    ? ['改 A 文件', '改 B 文件']
    : ['读取现状', '列出要改的点', '逐个改动', '跑一遍自检', '整理改动清单']
}

function planText(actions) {
  return '请确认执行计划（假后端：真后端这一步由模型按任务生成）：\n先读取现状，再按清单逐项改，最后跑一遍自检。'
    + `\n\n拆出来的任务点：\n${actions.map((a, i) => `${i + 1}. ${a}`).join('\n')}`
}

function authorizationText(actions) {
  return '请确认授权范围（假后端：真后端这一步由模型按任务生成）：\n允许在工作目录内读写文件、执行自检命令。'
    + `\n\n这次要做的事：\n${actions.map((a, i) => `${i + 1}. ${a}`).join('\n')}`
}

/**
 * 初始状态。每次 __reset 都从这里重建（深拷贝，避免被就地改坏）。
 *
 * ⚠️ 卡片上的发布人 / 任务人**用的是账号表里真实存在的名字**（admin / zhou / li，见
 * permission-routes 的初始数据）：发布一条任务时那两个名字要过一遍账号表，
 * 而看板上摆着一批"不是账号的人"会让这份假数据自相矛盾 ——
 * 自检也就验不出"下拉里选的人与卡片上显示的是同一个"。
 * li 是**停用**账号，它出现在一条已取消的任务上：停用与"当时是谁派的、派给谁"是两件事。
 */
function initialState() {
  return {
    tasks: [
      // 新发布：还没有确认项，等 agent 判需求（真后端由定时任务调度）
      {
        id: 1, publisher: 'admin', assignee: 'zhou', description: '整理 9 月项目周报：按周汇总进展与风险',
        status: 'NEW', currentExecutor: 'zhou', currentStep: 1, agentId: AGENT_ID, result: null, cancelReason: null,
      },
      // 待确认需求：需求确认项还没答
      {
        id: 2, publisher: 'zhou', assignee: 'admin', description: '把检索评测的对比数据整理成一页结论',
        status: 'PENDING_REQUIREMENT', currentExecutor: 'zhou', currentStep: 1, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 待确认计划：第 1 步已答、agent 拆出 5 个任务点，第 3 步在等
      {
        id: 3, publisher: 'admin', assignee: 'zhou', description: '给工具调用那一页补一份失败分类的说明',
        status: 'PENDING_PLAN', currentExecutor: 'admin', currentStep: 3, agentId: null, result: null,
        cancelReason: null,
      },
      // 待授权：计划已答、agent 问到了授权这一格，第 5 步在等
      {
        id: 4, publisher: 'zhou', assignee: 'admin', description: '把成本看板的单价表换成新合同价',
        status: 'PENDING_AUTHORIZATION', currentExecutor: 'zhou', currentStep: 5, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 已确认需求：需求答完了，球在 agent 手上（等它拆任务点）
      {
        id: 5, publisher: 'admin', assignee: 'zhou', description: '清理 dev 分支上过期的实验脚本',
        status: 'CONFIRMED_REQUIREMENT', currentExecutor: 'agent#1', currentStep: 2, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 已确认计划：计划批了，等 agent 问授权
      {
        id: 6, publisher: 'zhou', assignee: 'admin', description: '给告警规则那一页补三条自检断言',
        status: 'CONFIRMED_PLAN', currentExecutor: 'agent#1', currentStep: 4, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 已授权：可以开跑了，等 agent 动
      {
        id: 7, publisher: 'admin', assignee: 'zhou', description: '把 README 里的端口说明改成环境变量法',
        status: 'AUTHORIZED', currentExecutor: 'agent#1', currentStep: 6, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 执行中：agent 正在跑
      {
        id: 8, publisher: 'zhou', assignee: 'admin', description: '把语料快照脚本的输出目录改成可配置',
        status: 'EXECUTING', currentExecutor: 'agent#1', currentStep: 7, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 待验收：执行结果已经写回来了，等发布人点头
      {
        id: 9, publisher: 'admin', assignee: 'zhou', description: '给成本看板补一条"算不出来"的说明',
        status: 'PENDING_ACCEPTANCE', currentExecutor: 'admin', currentStep: 8, agentId: AGENT_ID,
        result: '【产出】已补上说明段落。\n\n【改动】成功的才算数：\n- edit_file {"path":"web/src/components/CostPanel.tsx"}',
        cancelReason: null,
      },
      // 已完成
      {
        id: 10, publisher: 'zhou', assignee: 'admin', description: '把 CI 的端口说明改成环境变量法',
        status: 'COMPLETED', currentExecutor: '', currentStep: 9, agentId: AGENT_ID,
        result: '【产出】已改成 CI_MOCK_PORT / CI_UI_PORT 两行说明。', cancelReason: null,
      },
      // 已取消：这一列只在有卡片时出现，所以假数据里必须有一条。
      // 任务人是停用账号 li：停用与"当时派给谁"是两件事（与真后端 realUser 的注释同一条）
      {
        id: 11, publisher: 'admin', assignee: 'li', description: '调研换一个向量库（先搁置）',
        status: 'CANCELLED', currentExecutor: 'li', currentStep: 1, agentId: null, result: null,
        cancelReason: '需求方撤回了这条调研，等定下来再重新发布',
      },
    ],
    confirms: [
      { id: 11, taskId: 2, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把检索评测的对比数据整理成一页结论', options: DEFAULT_OPTIONS.REQUIREMENT, answer: null },
      { id: 12, taskId: 3, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：给工具调用那一页补一份失败分类的说明', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 13, taskId: 3, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: null },
      { id: 14, taskId: 4, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把成本看板的单价表换成新合同价', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 15, taskId: 4, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
      { id: 16, taskId: 4, stepIndex: 5, type: 'AUTHORIZATION', description: '请确认授权范围（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.AUTHORIZATION, answer: null },
      { id: 17, taskId: 5, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：清理 dev 分支上过期的实验脚本', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 18, taskId: 6, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：给告警规则那一页补三条自检断言', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 19, taskId: 6, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
      { id: 20, taskId: 7, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把 README 里的端口说明改成环境变量法', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 21, taskId: 7, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
      { id: 22, taskId: 7, stepIndex: 5, type: 'AUTHORIZATION', description: '请确认授权范围（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.AUTHORIZATION, answer: '授权' },
      { id: 23, taskId: 8, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把语料快照脚本的输出目录改成可配置', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 24, taskId: 8, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
      { id: 25, taskId: 8, stepIndex: 5, type: 'AUTHORIZATION', description: '请确认授权范围（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.AUTHORIZATION, answer: '授权' },
    ],
    nextTaskId: 12,
    nextConfirmId: 26,
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

/**
 * 现在能做的动作，与真后端的 actionsOf 逐条一致。
 *
 * ⚠️ 等 agent 的那四格（新发布 / 已确认需求 / 已确认计划 / 已授权）只有「取消任务」：
 * 下一步由 agent 做，人点不动 —— 给它们画一个"看起来能推进"的按钮比不画更坏。
 */
function actionsOf(status) {
  if (status === 'COMPLETED' || status === 'CANCELLED') return []
  if (status === 'NEW') return [{ key: 'cancel', label: '取消任务' }]
  if (status === 'EXECUTING') return [{ key: 'advance', label: '登记执行结果' }, { key: 'cancel', label: '取消任务' }]
  if (status === 'PENDING_ACCEPTANCE') return [{ key: 'advance', label: '验收通过' }, { key: 'cancel', label: '取消任务' }]
  if (status === 'CONFIRMED_REQUIREMENT' || status === 'CONFIRMED_PLAN' || status === 'AUTHORIZED') {
    return [{ key: 'cancel', label: '取消任务' }]
  }
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

function openConfirmation(task, type, step, description) {
  state.confirms.push({
    id: state.nextConfirmId++,
    taskId: task.id,
    stepIndex: step,
    type,
    description,
    options: DEFAULT_OPTIONS[type],
    answer: null,
  })
}

/** 进入"等 agent"的一格：步骤 +1，当前执行人换成 agent（与 AgentTaskTransitions#enterAgent 一致）。 */
function enterAgent(task, status) {
  task.status = status
  task.currentStep += 1
  task.currentExecutor = task.agentId != null ? `agent#${task.agentId}` : 'agent'
}

/** 进入"等确认项答复"的一格：步骤 +1，当前执行人回到发布人，并生成那一步的确认项。 */
function enterPending(task, status, type, description) {
  task.status = status
  task.currentStep += 1
  task.currentExecutor = task.publisher
  openConfirmation(task, type, task.currentStep, description)
}

function advance(task, result) {
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
  return `当前状态「${STATUS_LABELS[task.status]}」没有可推进的事件 —— 能推进的只有「执行中」（登记执行结果）`
    + '与「待验收」（验收通过）；三个「待确认」在等确认项的答复，「新发布」与三个「已确认」由 agent 任务执行处理。'
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

  // 答复之后落到对应的"已确认"那一格：球交给 agent，下一条确认项由 agent 那一步生成
  // （与真后端 DefaultAgentTaskService#answer 一致）
  if (waiting === 'REQUIREMENT') enterAgent(task, 'CONFIRMED_REQUIREMENT')
  else if (waiting === 'PLAN') enterAgent(task, 'CONFIRMED_PLAN')
  else enterAgent(task, 'AUTHORIZED')
  return null
}

/**
 * agent 那一步（真后端是 `DefaultAgentTaskExecution`）。
 *
 * 一格一格推，每格做的事与真后端逐个对应；判定用上面那两条可判定的规则代替模型调用。
 */
function runAgentStep(task) {
  // 平台第一次接手这条任务：写入"交给哪个 Agent 应用"（真后端见 DefaultAgentTaskExecution#stampAgent）。
  // 发布人不填这一列，所以这里补上 —— 只在为空时写，与真后端一致
  if (task.agentId == null) task.agentId = AGENT_ID
  if (task.status === 'NEW') {
    if (isAmbiguous(task.description)) {
      // 第 1 步不递增：新发布与待确认需求都停在第 1 步
      task.status = 'PENDING_REQUIREMENT'
      task.currentExecutor = task.publisher
      openConfirmation(task, 'REQUIREMENT', task.currentStep,
        '请补充：这条任务描述里没有说清交付物与验收标准。')
      return
    }
    enterAgent(task, 'CONFIRMED_REQUIREMENT')
    return
  }
  if (task.status === 'CONFIRMED_REQUIREMENT') {
    if (isAmbiguous(task.description)) {
      enterPending(task, 'PENDING_REQUIREMENT', 'REQUIREMENT',
        '答复之后需求仍有歧义，请再补充一处：这次要动的文件是哪些？')
      return
    }
    const actions = breakdownOf(task.description)
    if (actions.length >= 5) {
      enterPending(task, 'PENDING_PLAN', 'PLAN', planText(actions))
    }
    else {
      enterPending(task, 'PENDING_AUTHORIZATION', 'AUTHORIZATION', authorizationText(actions))
    }
    return
  }
  if (task.status === 'CONFIRMED_PLAN') {
    enterPending(task, 'PENDING_AUTHORIZATION', 'AUTHORIZATION', authorizationText(breakdownOf(task.description)))
    return
  }
  if (task.status === 'AUTHORIZED') {
    enterAgent(task, 'EXECUTING')
    // 真后端这里是"跑一次 Agent 运行"：假后端直接给一份形状相同的执行结果
    task.status = 'PENDING_ACCEPTANCE'
    task.currentStep += 1
    task.currentExecutor = task.publisher
    task.result = '【产出】假后端：这一步在真后端是一次 Agent 运行，产出由模型汇总。'
      + '\n\n【改动】这次运行没有写操作（只调用了只读工具，或没有调用工具）'
      + '\n\n【运行】runId=（假后端不产生运行记录） · 1 步 · 0 次工具调用 · 0 tokens · 0ms · 停因 ANSWERED'
  }
}

/**
 * 推一轮：把所有"等 agent"的任务推到推不动为止。
 *
 * 真后端一轮是"串行、一次一条、按变更时间从早到晚"，结果与这里一致 ——
 * 差别只在顺序细节，而自检断言的是"谁是第几列"，不是顺序。
 *
 * @param id 只推这一条（自检要盯住某一条的每一格）；不传则推全部
 * @param maxSteps 每条最多推几格。默认 10 —— 它是**防规则写错时打转**的上限，
 *                 而自检会显式传 1：一次推到底就看不出中间那几格了
 */
function runAgentRound(id, maxSteps) {
  const targets = id == null ? state.tasks : state.tasks.filter(t => t.id === id)
  const moved = []
  for (const task of [...targets]) {
    const from = task.status
    let guard = 0
    while (AGENT_STATUSES.includes(task.status) && guard++ < maxSteps) {
      runAgentStep(task)
    }
    if (from !== task.status) moved.push({ id: task.id, from, to: task.status })
  }
  return moved
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

  // 显式推一轮 agent 那几格（真后端由定时任务驱动，见文件头）
  // `?id=` 只推一条、`?steps=` 每条最多推几格（自检要盯住"推到哪一格"）
  if (req.method === 'POST' && pathname === '/api/agent-tasks/__run-agent') {
    const id = url.searchParams.get('id')
    const steps = url.searchParams.get('steps')
    json(res, {
      ok: true,
      moved: runAgentRound(id == null ? null : Number(id), steps == null ? 10 : Number(steps)),
      note: '假后端：判定用的是可判定的规则，不是模型调用',
    })
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
      const publisher = realUser(b.publisher, '发布人')
      if (publisher.error) return json(res, { ok: false, error: publisher.error }, 400)
      const assignee = realUser(b.assignee, '任务人')
      if (assignee.error) return json(res, { ok: false, error: assignee.error }, 400)
      if (!b.description || !String(b.description).trim()) {
        return json(res, { ok: false, error: '缺少 description（任务描述）' }, 400)
      }
      const task = {
        id: state.nextTaskId++,
        publisher: publisher.name,
        assignee: assignee.name,
        description: String(b.description).trim(),
        status: 'NEW',
        // 与真后端 AgentTask.draft 一致：当前执行人就是任务人（球在他手上）
        currentExecutor: assignee.name,
        currentStep: 1,
        // 发布人不填 agentId（真后端的请求体里没有这个字段）：平台接手时才写，
        // 见 runAgentStep 的第一行
        agentId: null,
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
