/**
 * agent 任务端点的**假数据**（应用管理 · agent任务）。
 *
 * ---------------------------------------------------------------------------
 * 与 permission-routes / prompt-routes 同一条纪律：**有写端点，就必须有 __reset**
 * ---------------------------------------------------------------------------
 * 这一组有五个写端点（发布 / 推进 / 答复 / 重发 / 取消），界面自检要验的正是
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
 * 执行中 → 待验收 → 已完成，以及授权判断不通过时的「权限不足」）
 * 与"答复之后落到哪一格"必须逐条照搬 ——
 * 假后端不实现的话，"卡片会不会换列"这件事在自检里永远走不到，而那是这一页的全部内容。
 *
 * ---------------------------------------------------------------------------
 * ★ agent 那几格在这里是**显式推**的（`POST /api/agent-tasks/__run-agent`）
 * ---------------------------------------------------------------------------
 * 真后端那三格（新发布 / 已确认需求 / 已确认计划）由一个定时任务驱动，
 * 判定调模型、执行跑 Agent 运行。假后端不调模型、也不跑运行，而是给一套**可判定的规则**：
 *   - 描述里带「需要补充」→ 判成"有歧义"（真后端这一步是模型判定）；
 *   - 描述里带「小任务」→ 拆出 2 个任务点（走"跳过计划确认"那条路），否则拆出 5 个
 *     （阈值 5 与真后端的 `ai.agent.task.plan-confirm-threshold` 一致）；
 *   - **授权判断**（真后端是 `TaskFileAuthorization`）：任务人角色的目录前缀覆盖不覆盖
 *     这次要动的工作目录（常量 `TASK_WORKSPACE`）—— 不通过就进「权限不足」，
 *     通过就**直接开跑**（不生成授权确认项，链上没有"等人批授权"那一格）。
 *     任务人的角色数据读的是 permission-routes 那一份（账号表只有一份）。
 * 而且**不自动跑**：跟着写端点自动推的话，"发布之后卡片落在哪一列"会取决于推的时机，
 * 自检就没法断言了。所以用 `__run-agent` 显式推一轮（一轮 = 把所有待 agent 的任务推到推不动为止，
 * 与真后端"串行、一次一条、按变更时间"的结果一致）。
 *
 * ---------------------------------------------------------------------------
 * 初始数据刻意覆盖**每一列**
 * ---------------------------------------------------------------------------
 * 五列都要有卡片，看板的分组才验得出来；「执行中」那一列刻意放**三张**：
 * 已确认需求 / 已确认计划 / 执行中 —— 三个状态归并到一列是这一版的判据，
 * 只放一张就看不出这件事。「已完成」那一列放**三条**：一条验收通过的、一条取消于执行之前的、
 * 一条取消于执行之后的（这一列也收两个状态）—— 每条已取消的都带着取消原因，
 * 自检要断言"原因显示在卡片上"（它是两者唯一的区别）。
 * 「待处理」那一列放**四条**（两个"待确认"各两条 + 「权限不足」）：前者等的是确认项的答复、
 * 后者等的是"取消还是重走一遍"，两种动作在同一列里靠卡片上的状态标签与详情里的按钮区分。
 *
 * ⚠️ 任务人是 `zhou`（没有角色）的卡片只出现在**授权判断之前**的状态上
 * （新发布 / 待确认需求 / 待确认计划）或「权限不足」那一格 —— 他没有目录授权，
 * 按这套判据走不到「执行中」那些格子上。
 *
 * ---------------------------------------------------------------------------
 * ★ "我的任务"按状态挑列认人（`?userId=`），与"这一格球在谁手上"是两件事
 * ---------------------------------------------------------------------------
 * `GET /api/agent-tasks?userId=<用户 id>` 只给"与他有关、而且该由他看见"的那些，
 * 判据是 `partyOf`（与真后端 `AgentTaskStatus#party()` 逐条一致）：
 *   · 新发布 / 已完成 / 已取消 —— 发布人**或**任务人都算；
 *   · 待确认需求 / 待验收 —— 只有发布人；
 *   · 其余状态 —— 只有任务人。
 * **不传 `userId` 就是不筛**（全量），传了但账号表里没有这个人是 400（不是"筛出来是空的"）。
 * ⚠️ 它与 `executorFor`（卡片上"当前执行人"那一格显示谁）**不是同一条判据**：
 * 新发布与两个"待确认"上两者刻意不同，混用的后果都是静默的（漏卡片，或者写错那一格）。
 *
 * ---------------------------------------------------------------------------
 * 发布任务：两个名字都要在账号表里，agentId 不由发布人给
 * ---------------------------------------------------------------------------
 * `publisher` 与 `assignee` **都必填**，且都要能在 permission-routes 那份账号表里找到
 * （`realUser`，与真后端 `DefaultAgentTaskService#realUser` 是同一句话）——
 * 账号表只有一份，两份会让"假后端说这个人是真的、真端点说不存在"只表现为自检里一句 400。
 * 落库的是查出来的那两个账号 **id**（`agent_task.publisher_id` / `assignee_id`），
 * 姓名不落库：卡片上显示的姓名是按 id 查回来的（与真后端读侧那三个 LEFT JOIN 同一件事）。
 * 请求体里的 `agentId` **不被采纳**：它是平台第一次接手这条任务时写进去的（`AGENT_ID`）。
 */

import {
  findMockAccount,
  findMockAccountById,
  mockAccountNames,
  mockCoversDirectory,
  mockPermissionsOfId,
} from './permission-routes.mjs'

const STATUS_LABELS = {
  NEW: '新发布',
  PENDING_REQUIREMENT: '待确认需求',
  CONFIRMED_REQUIREMENT: '已确认需求',
  PENDING_PLAN: '待确认计划',
  CONFIRMED_PLAN: '已确认计划',
  INSUFFICIENT_PERMISSION: '权限不足',
  EXECUTING: '执行中',
  PENDING_ACCEPTANCE: '待验收',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

/**
 * 确认类型的中文名。`AUTHORIZATION` 是历史取值（真后端 `ConfirmType` 里也留着它）：
 * 链上不再生成授权确认项，这里留着只是为了认得出那几条旧记录。
 */
const CONFIRM_LABELS = {
  REQUIREMENT: '需求确认',
  PLAN: '计划确认',
  AUTHORIZATION: '授权确认',
}

/**
 * 列的定义与顺序，与真后端 `AgentTaskService.Column` 逐条一致（前端按 key 画）。
 *
 * ⚠️ 「待处理」一列收**三个**状态（两个"待确认" + 权限不足）：它们等的东西不同
 * （答复确认项 / 处置一次权限不足），但对看板是同一个答案 —— 球都在人手上。
 * ⚠️ 「已完成」一列收**两个**状态（已完成与已取消）：两者都是"这件事结束了"，
 * 而给已取消单开一列会让最边缘的结局占掉一整列宽度。区别靠卡片上的状态标签与取消原因表达。
 * ⚠️ 「执行中」一列收**三个**状态：它们对看板是同一件事 —— 球在 agent 手上，人不必点它。
 */
const COLUMNS = [
  { key: 'new', label: '新任务', statuses: ['NEW'] },
  {
    key: 'pending',
    label: '待处理',
    statuses: ['PENDING_REQUIREMENT', 'PENDING_PLAN', 'INSUFFICIENT_PERMISSION'],
  },
  {
    key: 'executing',
    label: '执行中',
    statuses: ['CONFIRMED_REQUIREMENT', 'CONFIRMED_PLAN', 'EXECUTING'],
  },
  { key: 'acceptance', label: '待验收', statuses: ['PENDING_ACCEPTANCE'] },
  { key: 'completed', label: '已完成', statuses: ['COMPLETED', 'CANCELLED'] },
]

/** 等 agent 处理的那几格（与真后端 `AgentTaskStatus.awaitingAgent()` 一致）。 */
const AGENT_STATUSES = ['NEW', 'CONFIRMED_REQUIREMENT', 'CONFIRMED_PLAN']

/**
 * 本应用在 Agent 应用注册表里的 id —— 与真后端的 `ai.agent.task.agent-id` 同一个键。
 *
 * <p>平台第一次接手一条任务时把它写进 `agent_id`（真后端是
 * `DefaultAgentTaskExecution#stampAgent`），发布人不填这一列。
 * 它与真后端一样是**字符串**（那一列是 VARCHAR(128)：应用 id 由注册表定义，本仓不假设它像数字）。
 */
const AGENT_ID = '1'

/**
 * 各确认类型默认的选项集（与真后端的缺省按钮同源，见 AgentTaskTransitions#defaultOptions）。
 *
 * `AUTHORIZATION` 那一行是历史取值：链上不再生成授权确认项（授权由平台判，判过就开跑）。
 */
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
 * <p>返回的是账号表里那一行本身：落库用它**自己的 id**，卡片上显示它**自己的姓名**
 * （界面把 "Admin" 打进来时，落库指向 id=1、显示的是 "admin"）。
 *
 * @returns {{account: object}|{error: string}}
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
  return { account }
}

function breakdownOf(description) {
  return description.includes('小任务')
    ? ['改 A 文件', '改 B 文件']
    : ['读取现状', '列出要改的点', '逐个改动', '跑一遍自检', '整理改动清单']
}

/**
 * 这条任务要动的工作目录。
 *
 * <p>真后端由模型按任务描述判（`TaskBreakdown.workspaceDir`），假后端固定成一个常量：
 * 授权判断要回答的是"目录在不在任务人角色的前缀里"，而不是"这个目录是怎么得出来的"。
 * 它刻意取成与 `permission-routes` 里两个角色都授权过的那个前缀**同一条**，
 * 于是"有角色的账号能过、没有角色的账号过不了"这两条路都能在自检里走到。
 */
const TASK_WORKSPACE = 'D:\\work\\demo'

/**
 * 授权判断 —— 与真后端 `TaskFileAuthorization.judge` 同一套判据与同一套措辞。
 *
 * <p>真后端在这里读的是任务记录里的任务人 **id**（`agent_task.assignee_id`）+
 * `PermissionResolver` 解析出的角色目录前缀；假后端读的是同一个 id + `mockPermissionsOfId`
 * （同一份账号与角色数据）。判据只有一条：**这次要动的工作目录必须落在任务人角色的目录前缀里**。
 * 工具清单只写进结论作提示（真后端也一样：它不参与通过 / 不通过）。
 *
 * @returns {{allowed: boolean, reason: string}}
 */
function judgeAuthorization(assigneeId) {
  const permissions = mockPermissionsOfId(assigneeId)
  if (!permissions) {
    return {
      allowed: false,
      reason: `账号表里没有 id=${assigneeId} 这个人（任务人），判断不了目录「${TASK_WORKSPACE}」的授权 —— `
        + '他可能已被删掉，请重新指派任务人。',
    }
  }
  if (!permissions.usable) {
    return {
      allowed: false,
      reason: `任务人「${permissions.name}」已停用（status=DISABLED）—— 停用的账号一律没有权限，`
        + `目录「${TASK_WORKSPACE}」因此不在授权范围内。启用这个账号，或把任务人换成别人。`,
    }
  }
  const matched = permissions.dirPrefixes.find(prefix => mockCoversDirectory(prefix, TASK_WORKSPACE))
  const tools = grantedWriteTools(permissions)
  if (matched) {
    return {
      allowed: true,
      reason: `目录「${TASK_WORKSPACE}」在任务人「${permissions.name}」的角色授权范围内（按前缀「${matched}」放行）。`
        + `任务人角色授予的写文件工具：${tools}。`,
    }
  }
  const authorized = permissions.dirPrefixes.length ? JSON.stringify(permissions.dirPrefixes) : '无'
  const why = permissions.dirPrefixes.length
    ? `目录「${TASK_WORKSPACE}」不在任务人「${permissions.name}」的任何角色目录前缀里。`
    : `任务人「${permissions.name}」没有任何角色 —— 没有角色就是没有任何目录授权。`
  return {
    allowed: false,
    reason: `${why}已授权的目录前缀：${authorized}。任务人角色授予的写文件工具：${tools}。`
      + `要放行这条任务：在「权限管理」里给这个人的角色加一条覆盖「${TASK_WORKSPACE}」的目录前缀，`
      + '或者把这条任务要动的目录改到已授权的范围内。',
  }
}

/** 任务人角色授予的写文件工具（只作提示，不参与判定；`ALL` 的角色说"全部工具"）。 */
function grantedWriteTools(permissions) {
  if (permissions.allTools) return '全部工具'
  const granted = permissions.toolNames.filter(name => name === 'write_file' || name === 'edit_file')
  return granted.length ? granted.join('、') : '无'
}

function planText(actions) {
  return '请确认执行计划（假后端：真后端这一步由模型按任务生成）：\n先读取现状，再按清单逐项改，最后跑一遍自检。'
    + `\n\n拆出来的任务点：\n${actions.map((a, i) => `${i + 1}. ${a}`).join('\n')}`
}

/**
 * 初始状态。每次 __reset 都从这里重建（深拷贝，避免被就地改坏）。
 *
 * ⚠️ 三个"人"的列存的是**账号表里的用户 id**（admin=1 / zhou=2 / li=3，见 permission-routes
 * 的初始数据）：发布一条任务时那两个名字要过一遍账号表、落库的是查出来的 id，
 * 而看板上摆着一批"不是账号的 id"会让这份假数据自相矛盾 ——
 * 自检也就验不出"下拉里选的人与卡片上显示的是同一个人"。
 * li 是**停用**账号，它出现在一条已取消的任务上：停用与"当时是谁派的、派给谁"是两件事。
 * `currentExecutorId` 为 null 的含义与真后端一致：球在 agent 手上，或这件事已经结束。
 *
 * ⚠️ `currentExecutorId` 逐条按 `executorFor` 那份判据摆（不是随手填的）：需求确认与待验收是
 * **发布人**、其余在**任务人**手上、两个"已确认"是空（球在 agent 手上）。
 * `publisherId` / `assigneeId` 那一对决定的是**这条任务出现在谁的列表里**（`partyOf`）——
 * 假数据里两条判据各有取值，自检才断得到"两者不是同一条"。
 */
function initialState() {
  return {
    tasks: [
      // 新发布：还没有确认项，等 agent 判需求（真后端由定时任务调度）。
      // 任务人刻意用**有目录授权**的 admin（他那个角色授权了 D:\work\demo，见 TASK_WORKSPACE）：
      // 自检要把这一条一路推到「执行中 → 待验收」，而权限不足那一条路由下面 id 12（任务人 zhou 没有角色）走
      {
        id: 1, publisherId: 2, assigneeId: 1, description: '整理 9 月项目周报：按周汇总进展与风险',
        status: 'NEW', currentExecutorId: 1, currentStep: 1, agentId: AGENT_ID, result: null, cancelReason: null,
      },
      // 待确认需求：需求确认项还没答
      {
        id: 2, publisherId: 2, assigneeId: 1, description: '把检索评测的对比数据整理成一页结论',
        status: 'PENDING_REQUIREMENT', currentExecutorId: 2, currentStep: 1, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 待确认计划：第 1 步已答、agent 拆出 5 个任务点，第 3 步在等
      {
        id: 3, publisherId: 1, assigneeId: 2, description: '给工具调用那一页补一份失败分类的说明',
        status: 'PENDING_PLAN', currentExecutorId: 2, currentStep: 3, agentId: null, result: null,
        cancelReason: null,
      },
      // 待确认计划：需求已答、agent 拆出 5 个任务点，计划确认项在等答复。
      // 它与 id 3 同状态不同人：id 3 的发布人是 admin、这儿的发布人是 zhou ——
      // 「只有任务人看得见」那一组的取值因此有两条，自检换个人取列表时才分得清
      {
        id: 4, publisherId: 2, assigneeId: 1, description: '把成本看板的单价表换成新合同价',
        status: 'PENDING_PLAN', currentExecutorId: 1, currentStep: 3, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 已确认需求：需求答完了，球在 agent 手上（等它拆任务点）—— 执行人那一格因此是空的
      {
        id: 5, publisherId: 1, assigneeId: 2, description: '清理 dev 分支上过期的实验脚本',
        status: 'CONFIRMED_REQUIREMENT', currentExecutorId: null, currentStep: 2, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 已确认计划：计划批了，球在 agent 手上（它下一步就判授权、判过直接开跑）
      {
        id: 6, publisherId: 2, assigneeId: 1, description: '给告警规则那一页补三条自检断言',
        status: 'CONFIRMED_PLAN', currentExecutorId: null, currentStep: 4, agentId: AGENT_ID, result: null,
        cancelReason: null,
      },
      // 执行中：agent 判定失败过 —— 它停在执行中等任务人处置（「登记执行结果」或取消），
      // 所以 result 里是那句"本次执行没有正常结束"，而**不是**一段产出。
      // 一条好的"执行中"卡片看不出这件事，自检也就断不到"停住的执行中要有人处置"
      {
        id: 7, publisherId: 1, assigneeId: 1, description: '把 README 里的端口说明改成环境变量法',
        status: 'EXECUTING', currentExecutorId: 1, currentStep: 6, agentId: AGENT_ID,
        result: '【本次执行没有正常结束】停因：MODEL_ERROR\n\n【产出】（模型没有给出最终答复：MODEL_ERROR）'
          + '\n\n【改动】这次运行没有写操作（只调用了只读工具，或没有调用工具）'
          + '\n\n【运行】runId=（假后端不产生运行记录） · 0 步 · 0 次工具调用 · 0 tokens · 0ms · 停因 MODEL_ERROR',
        cancelReason: null,
      },
      // 已取消（执行之前就被叫停的那一种）：它**一条确认项都没有** ——
      // 确认项是链上每一步生成出来的，而这条任务在第 2 步（判需求那一格）就被取消了。
      // 于是"没有确认项"在这份假数据里有两种来源（新发布 / 取消于生成之前），
      // 与真后端那句按状态分岔的兜底文案对得上。
      // 执行人**保持取消那一刻的值**（与真后端 `cancel` 一致：它回答"取消的那一刻球在谁手上"）
      {
        id: 8, publisherId: 2, assigneeId: 1, description: '把语料快照脚本的输出目录改成可配置',
        status: 'CANCELLED', currentExecutorId: 1, currentStep: 2, agentId: AGENT_ID, result: null,
        cancelReason: '口径还没定，先不换目录了',
      },
      // 待验收：执行结果已经写回来了，等发布人点头
      {
        id: 9, publisherId: 1, assigneeId: 2, description: '给成本看板补一条"算不出来"的说明',
        status: 'PENDING_ACCEPTANCE', currentExecutorId: 1, currentStep: 8, agentId: AGENT_ID,
        result: '【产出】已补上说明段落。\n\n【改动】成功的才算数：\n- edit_file {"path":"web/src/components/CostPanel.tsx"}',
        cancelReason: null,
      },
      // 已完成。描述刻意写长：它要盖住"卡片上的文字默认夹住"这件事 ——
      // 一列里所有描述都只有一行时，「展开全文」永远不出现，界面自检也就断不到它。
      // ⚠️ 执行人那一格写着**任务人**（真后端在完成时会清空它）。这一条出现在"我的任务"里
      // 靠的不是这一列，而是状态（[11] 那一段在登录态下看这一页："已完成"按
      // 发布人**或**任务人认人，与 `current_executor_id` 无关）
      {
        id: 10, publisherId: 2, assigneeId: 1,
        description: '把 CI 的端口说明改成环境变量法：两套服务各用一对变量，'
          + '并写清"代理指错了端口"与"服务没起来"这两种失败长得完全一样、该先查哪一个',
        status: 'COMPLETED', currentExecutorId: 1, currentStep: 9, agentId: AGENT_ID,
        result: '【产出】已改成 CI_MOCK_PORT / CI_UI_PORT 两行说明。', cancelReason: null,
      },
      // 已取消：这一列只在有卡片时出现，所以假数据里必须有一条。执行人**保持取消那一刻的值**
      // （与真后端 `cancel` 一致：它回答"取消的那一刻球在谁手上"），这里就是当时拿着球的那位。
      // 任务人是停用账号 li：停用与"当时派给谁"是两件事（与真后端 realUser 的注释同一条）
      {
        id: 11, publisherId: 1, assigneeId: 3, description: '调研换一个向量库（先搁置）',
        status: 'CANCELLED', currentExecutorId: 3, currentStep: 1, agentId: null, result: null,
        cancelReason: '需求方撤回了这条调研，等定下来再重新发布',
      },
      // 权限不足：授权判断不通过（任务人 zhou 没有任何角色 ⇒ 没有任何目录授权），
      // 等用户选「已修改任务」或「取消任务」。结论落在 result 上（与执行结果同一个字段），
      // 界面按状态换标签显示 —— 这一格一次都没跑，"执行结果"四个字会读成"跑过了"
      {
        id: 12, publisherId: 1, assigneeId: 2, description: '把周报模板里的统计口径换成新版',
        status: 'INSUFFICIENT_PERMISSION', currentExecutorId: 1, currentStep: 3, agentId: AGENT_ID,
        result: '任务人「zhou」没有任何角色 —— 没有角色就是没有任何目录授权。'
          + `已授权的目录前缀：无。任务人角色授予的写文件工具：无。`
          + `要放行这条任务：在「权限管理」里给这个人的角色加一条覆盖「${TASK_WORKSPACE}」的目录前缀，`
          + '或者把这条任务要动的目录改到已授权的范围内。',
        cancelReason: null,
      },
    ],
    confirms: [
      { id: 11, taskId: 2, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把检索评测的对比数据整理成一页结论', options: DEFAULT_OPTIONS.REQUIREMENT, answer: null },
      { id: 12, taskId: 3, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：给工具调用那一页补一份失败分类的说明', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 13, taskId: 3, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: null },
      { id: 14, taskId: 4, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把成本看板的单价表换成新合同价', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 16, taskId: 4, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: null },
      { id: 17, taskId: 5, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：清理 dev 分支上过期的实验脚本', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 18, taskId: 6, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：给告警规则那一页补三条自检断言', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 19, taskId: 6, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
      { id: 20, taskId: 7, stepIndex: 1, type: 'REQUIREMENT', description: '请确认任务需求：把 README 里的端口说明改成环境变量法', options: DEFAULT_OPTIONS.REQUIREMENT, answer: '确认' },
      { id: 21, taskId: 7, stepIndex: 3, type: 'PLAN', description: '请确认执行计划（假后端：真后端这一步由模型按任务生成）', options: DEFAULT_OPTIONS.PLAN, answer: '同意' },
    ],
    nextTaskId: 13,
    nextConfirmId: 26,  }
}

let state = initialState()

/* ------------------------------------------------------------------ */
/*  视图                                                                */
/* ------------------------------------------------------------------ */

/**
 * 当前执行人那一格该显示的字 —— 与真后端 `AgentTask.executorLabel()` 同一套判据：
 * 那一列有值（球在某个账号手上）→ 按 id 查出姓名；那一列为空而任务没结束、agent 也接手过 →
 * `agent#<agentId>`；其余（任务已结束 / 还没有 agent 接手）→ 空串。
 */
function executorLabel(task) {
  const executor = findMockAccountById(task.currentExecutorId)
  if (executor) return executor.name
  if (task.currentExecutorId != null) return `用户#${task.currentExecutorId}`
  const terminal = task.status === 'COMPLETED' || task.status === 'CANCELLED'
  return !terminal && task.agentId != null ? `agent#${task.agentId}` : ''
}

/**
 * 一条任务装配成响应体。
 *
 * <p>三个"人"各给两份：`…Id` 是库里存的那一份，姓名是按 id 从账号表里查回来的那一份
 * （与真后端读侧那三个 LEFT JOIN 同一件事）—— 界面上显示的是姓名那一份。
 */
function taskView(task) {
  const publisher = findMockAccountById(task.publisherId)
  const assignee = findMockAccountById(task.assigneeId)
  return {
    id: task.id,
    publisherId: task.publisherId,
    publisher: publisher ? publisher.name : '',
    assigneeId: task.assigneeId,
    assignee: assignee ? assignee.name : '',
    description: task.description,
    status: task.status,
    statusLabel: STATUS_LABELS[task.status] ?? task.status,
    currentExecutorId: task.currentExecutorId,
    currentExecutor: executorLabel(task),
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
 * ⚠️ 等 agent 的那三格（新发布 / 已确认需求 / 已确认计划）只有「取消任务」：
 * 下一步由 agent 做，人点不动 —— 给它们画一个"看起来能推进"的按钮比不画更坏。
 * ⚠️ 「权限不足」给的是 `revise`（「已修改任务」）与「取消任务」：它等的不是"推进当前事件"，
 * 而是"这条任务已经改过了"（回到新发布重走一遍）。按钮上的字由这里给，界面不自己拼。
 */
function actionsOf(status) {
  if (status === 'COMPLETED' || status === 'CANCELLED') return []
  if (status === 'NEW') return [{ key: 'cancel', label: '取消任务' }]
  if (status === 'EXECUTING') return [{ key: 'advance', label: '登记执行结果' }, { key: 'cancel', label: '取消任务' }]
  if (status === 'PENDING_ACCEPTANCE') return [{ key: 'advance', label: '验收通过' }, { key: 'cancel', label: '取消任务' }]
  if (status === 'INSUFFICIENT_PERMISSION') {
    return [{ key: 'revise', label: '已修改任务' }, { key: 'cancel', label: '取消任务' }]
  }
  if (status === 'CONFIRMED_REQUIREMENT' || status === 'CONFIRMED_PLAN') {
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

/** 进入"等 agent"的一格：步骤 +1，当前执行人写空（agent 不是账号，与 AgentTaskTransitions#enterAgent 一致）。 */
function enterAgent(task, status) {
  task.status = status
  task.currentStep += 1
  task.currentExecutorId = null
}

/**
 * "我的任务"按哪一列认人 —— 与真后端 `AgentTaskStatus#party()` **逐条一致**。
 *
 * <p>它不是"这一格球在谁手上"（那是 `executorFor`，回答的是卡片上那一格显示谁）：
 * 两者在几处刻意不同，混用的后果都是静默的。
 *
 * @returns 'BOTH'（发布人或任务人）/ 'PUBLISHER'（只有发布人）/ 'ASSIGNEE'（只有任务人）
 */
function partyOf(status) {
  if (status === 'NEW' || status === 'COMPLETED' || status === 'CANCELLED') return 'BOTH'
  if (status === 'PENDING_REQUIREMENT' || status === 'PENDING_ACCEPTANCE') return 'PUBLISHER'
  return 'ASSIGNEE'
}

/** 这条任务该由谁在自己的列表里看见（三组状态见 partyOf）。 */
function visibleTo(task, userId) {
  const party = partyOf(task.status)
  if (party === 'PUBLISHER') return task.publisherId === userId
  if (party === 'ASSIGNEE') return task.assigneeId === userId
  return task.publisherId === userId || task.assigneeId === userId
}

/**
 * "这一格球在谁手上" —— 与真后端 `AgentTaskTransitions.executorFor` **逐条一致**。
 *
 * <p>它填的是卡片上那一格显示谁（`current_executor_id`），**不是**看板按人筛选的依据 ——
 * 收件人判据是 `partyOf`，两者在新发布与两个"待确认"上刻意不同。
 *
 * <p>「执行中」那一格写的是**任务人**：那一格确实是 agent 在跑（"是哪个 agent"看 `agentId`），
 * 而跑失败时它停在执行中等任务人处置。
 *
 * @returns 用户 id；`null` = 球在 agent 手上，或这件事已经结束
 */
function executorFor(task, status) {
  if (status === 'PENDING_REQUIREMENT' || status === 'PENDING_ACCEPTANCE') return task.publisherId
  if (status === 'CONFIRMED_REQUIREMENT' || status === 'CONFIRMED_PLAN') return null
  if (status === 'COMPLETED') return null
  if (status === 'CANCELLED') return task.currentExecutorId
  // 新发布、「待确认计划」、「权限不足」、「执行中」：任务人
  // （任务人为空的行只可能是早期的行 —— 发布时必填）
  return task.assigneeId ?? task.publisherId
}

/** 进入"等确认项答复"的一格：步骤 +1，当前执行人按 `executorFor` 算，并生成那一步的确认项。 */
function enterPending(task, status, type, description) {
  enterWaiting(task, status)
  openConfirmation(task, type, task.currentStep, description)
}

/**
 * 进入一个"球在人手上"的格子但不生成确认项（步骤 +1，当前执行人按 `executorFor` 算）。
 *
 * <p>「待验收」是唯一这样的格子：人要做的是"点头"而不是"答一条确认项"，
 * 所以它的确认项永远不存在 —— 真后端 `run()` 也是直接 `move` 过去的。
 */
function enterWaiting(task, status) {
  task.status = status
  task.currentStep += 1
  task.currentExecutorId = executorFor(task, status)
}

/**
 * 进入「权限不足」：步骤 +1，当前执行人按 `executorFor` 算，结论写进 result ——
 * 与真后端 `AgentTaskTransitions#enterPermissionDenied` 逐条一致。
 */
function enterPermissionDenied(task, reason) {
  task.status = 'INSUFFICIENT_PERMISSION'
  task.currentStep += 1
  task.currentExecutorId = executorFor(task, 'INSUFFICIENT_PERMISSION')
  task.result = reason
}

/**
 * 判断授权那一格：先判权限，通过就开跑 ——
 * 与真后端 `DefaultAgentTaskExecution#judgeAuthorization` 一致（两条路都从这里进）。
 */
function judgeThenOpen(task, actions) {
  const verdict = judgeAuthorization(task.assigneeId)
  if (!verdict.allowed) {
    enterPermissionDenied(task, verdict.reason)
    return
  }
  // 判过就进「执行中」：链上没有"等人批授权"那一格（授权由平台判）
  enterAgent(task, 'EXECUTING')
}

function advance(task, result) {
  if (task.status === 'EXECUTING') {
    task.status = 'PENDING_ACCEPTANCE'
    task.currentStep += 1
    task.currentExecutorId = executorFor(task, 'PENDING_ACCEPTANCE')
    if (result != null && String(result).trim()) task.result = String(result).trim()
    return null
  }
  if (task.status === 'PENDING_ACCEPTANCE') {
    task.status = 'COMPLETED'
    task.currentStep += 1
    task.currentExecutorId = null
    return null
  }
  return `当前状态「${STATUS_LABELS[task.status]}」没有可推进的事件 —— 能推进的只有「执行中」（登记执行结果）`
    + '与「待验收」（验收通过）；两个「待确认」在等确认项的答复、「权限不足」要答复的是 revise'
    + '（「已修改任务」/「取消任务」），「新发布」与两个「已确认」由 agent 任务执行处理。'
}

/**
 * 权限不足 → 新发布：用户答复「已修改任务」。
 *
 * <p>与真后端 `DefaultAgentTaskService#revise` 一致：步骤 +1、执行人回到任务人，
 * 上一次「权限不足」的结论留在 result 里（它是"当时为什么被打回"的唯一记录）。
 *
 * @returns 出错时返回一句给界面的话；成功返回 null
 */
function revise(task) {
  if (task.status !== 'INSUFFICIENT_PERMISSION') {
    return `当前状态「${STATUS_LABELS[task.status]}」不是「权限不足」，没有可重发的任务 —— `
      + '「已修改任务」只在权限不足时出现（权限那一关过不了，改了之后再重走一遍）。'
  }
  const previous = task.result == null ? '' : String(task.result).trim()
  task.status = 'NEW'
  task.currentStep += 1
  task.currentExecutorId = executorFor(task, 'NEW')
  task.result = '用户答复「已修改任务」：这条任务已回到「新发布」重新走一遍'
    + '（重新判需求 → 拆任务点 → 判授权）。'
    + (previous ? `\n\n上一次「权限不足」的结论（留档）：\n${previous}` : '')
  return null
}

function answer(task, confirmId, text) {
  if (text == null || !String(text).trim()) {
    return { error: '答复不能为空 —— 空答复在库里与「还没答」（answer 为 NULL）长得一样，而两者的处置完全不同：前者要推进状态，后者要继续等' }
  }
  const waiting = waitingType(task.status)
  if (!waiting) {
    return { conflict: `当前状态「${STATUS_LABELS[task.status]}」不在等确认项的答复 —— 能答复的只有「待确认需求」/「待确认计划」；要推进请用 advance 那个动作。` }
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
  else enterAgent(task, 'CONFIRMED_PLAN')
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
      task.currentExecutorId = executorFor(task, 'PENDING_REQUIREMENT')
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
      // 任务点少于阈值：跳过计划确认，直接判授权（与真后端 reviewConfirmed 的那条路一致）
      judgeThenOpen(task, actions)
    }
    return
  }
  if (task.status === 'CONFIRMED_PLAN') {
    judgeThenOpen(task, breakdownOf(task.description))
    return
  }
  if (task.status === 'EXECUTING') {
    // 真后端这一步是一次 Agent 运行（模型调工具、写文件），假后端只写回一份固定的三段汇总
    //
    // ⚠️ 落点必须显式写出来：不能靠"当前这一格"推下一步 —— 「执行中」那一格写的是任务人
    // （真后端 `executorFor` 就是这么算的，跑失败时他在这一格用「登记执行结果」处置），
    // 真后端 run() 也是显式 move 到 PENDING_ACCEPTANCE 的
    enterWaiting(task, 'PENDING_ACCEPTANCE')
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
 *
 * ⚠️ 同步分支里**不能写成 `return json(res, …)`**：`json()` 没有返回值（undefined），
 * 那个 return 会让分派方以为这条请求没被处理，于是又往同一个响应写一次 404 头 ——
 * 报错是 `ERR_HTTP_HEADERS_SENT`，而现象是**假后端整个进程退出**（自检里表现为"页面打不开"）。
 * 所以同步的失败分支一律写成"先 json(...)，再 return true"。
 * （`readBody(...)` 的回调里无所谓：那个 return 的返回值没人看。）
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
    // 与真后端 `AgentTaskService#board(userId)` 同一套判据：按状态挑列认人（`visibleTo`），
    // **不传就是不筛**。不实现的话，自检里"登录之后看板只剩与他有关的"永远断不到 ——
    // 而那是这一版的全部内容
    const rawUserId = url.searchParams.get('userId')
    if (rawUserId != null && !findMockAccountById(Number(rawUserId))) {
      json(res, {
        ok: false,
        error: `账号表里没有 id=${rawUserId} 这个人 —— 界面上传的是「当前登录的人」，`
          + '他不在账号表里就取不出「与他有关」的任务。本地存着的那条登录记录已经过期，重新登录一次。',
      }, 400)
      return true
    }
    const userId = rawUserId == null ? null : Number(rawUserId)
    const visible = userId == null ? state.tasks : state.tasks.filter(task => visibleTo(task, userId))
    const columns = COLUMNS.map(column => {
      const tasks = visible.filter(task => column.statuses.includes(task.status))
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
      total: visible.length,
      userId,
      howToAdvance: 'POST /api/agent-tasks/{id}/advance | /confirms/{confirmId}/answer | /revise | /cancel',
    })
    return true
  }

  const detailMatch = pathname.match(/^\/api\/agent-tasks\/(\d+)$/)
  if (detailMatch && req.method === 'GET') {
    const task = state.tasks.find(t => t.id === Number(detailMatch[1]))
    if (!task) {
      json(res, { ok: false, error: `任务不存在：#${detailMatch[1]}` }, 400)
      return true
    }
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
        publisherId: publisher.account.id,
        assigneeId: assignee.account.id,
        description: String(b.description).trim(),
        status: 'NEW',
        // 与真后端 AgentTask.draft 一致：当前执行人就是任务人（球在他手上）
        currentExecutorId: assignee.account.id,
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
    if (!task) {
      json(res, { ok: false, error: `任务不存在：#${advanceMatch[1]}` }, 400)
      return true
    }
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
    if (!task) {
      json(res, { ok: false, error: `任务不存在：#${answerMatch[1]}` }, 400)
      return true
    }
    readBody(req, res, json, b => {
      const outcome = answer(task, Number(answerMatch[2]), b ? b.answer : null)
      if (outcome && outcome.conflict) return json(res, { ok: false, error: outcome.conflict }, 409)
      if (outcome && outcome.error) return json(res, { ok: false, error: outcome.error }, 400)
      json(res, { ok: true, ...detailView(task) })
    })
    return true
  }

  // 「已修改任务」：权限不足 → 新发布。**没有请求体**（这一格要的是一次选择，不是一段说明）
  const reviseMatch = pathname.match(/^\/api\/agent-tasks\/(\d+)\/revise$/)
  if (reviseMatch && req.method === 'POST') {
    const task = state.tasks.find(t => t.id === Number(reviseMatch[1]))
    if (!task) {
      json(res, { ok: false, error: `任务不存在：#${reviseMatch[1]}` }, 400)
      return true
    }
    const conflict = revise(task)
    if (conflict) {
      json(res, { ok: false, error: conflict }, 409)
      return true
    }
    json(res, { ok: true, ...detailView(task) })
    return true
  }

  const cancelMatch = pathname.match(/^\/api\/agent-tasks\/(\d+)\/cancel$/)
  if (cancelMatch && req.method === 'POST') {
    const task = state.tasks.find(t => t.id === Number(cancelMatch[1]))
    if (!task) {
      json(res, { ok: false, error: `任务不存在：#${cancelMatch[1]}` }, 400)
      return true
    }
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
