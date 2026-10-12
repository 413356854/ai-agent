/**
 * 左侧目录（类后台）的定义。
 *
 * 单独一个文件是因为**它是两个组件的唯一数据源**：
 * `AppSidebar` 按 `NAV_SECTIONS` 画目录，`App` 按 `sectionOf(mode)` 画顶栏那行标题。
 * 定义留在任一个组件里，另一个就成了"靠字符串再猜一遍"的第二份判据。
 */

/**
 * 导航项标识 = 视图标识。前八个是对话台的模式，对应后端的八条链路
 * （逐条见 docs/项目功能点与代码索引.md 第 6 节）；
 * `users` / `permissions` / `tasks` / `skill` / `schedules` 是应用管理区的五项。
 */
export type Mode =
  | 'stream' | 'structured' | 'threads' | 'rag' | 'agent' | 'cost' | 'prompt' | 'alerts'
  | 'users' | 'permissions' | 'tasks' | 'skill' | 'schedules'

/** 导航分组 = 一级目录。它不是视觉分组：每个分组是一个独立的功能区，顶栏标题跟着它走 */
export interface NavGroup {
  key: string
  /** 目录上显示的一级名 */
  label: string
  /** 顶栏标题。它比目录名长：目录只占一列，标题要能一句话说清这一区是干什么的 */
  title: string
  /** 选中这一区时顶栏那枚标签的文案与颜色 */
  tag: { color: string; text: string }
  items: { mode: Mode; label: string }[]
}

/**
 * 目录顺序 = 界面上的排列顺序。
 *
 * 「对话台」收的是**能点出一次运行**的八个模式（点一次提问 / 点一次实验）；
 * 「应用管理」收的是"配一次、以后一直生效"的东西，以及**一件活在人之间怎么流转的记录**
 * （agent 任务：它自己不点模型，点的是人的确认）。
 * 定时任务也落在应用管理区，判据与 agent 任务那一条相同：**它自己不点模型** ——
 * 到点它只生成一条 agent 任务记录，真正跑模型的是那条任务（在 agent任务 那一页）。
 * 这条界线决定了新功能该往哪一区放：会花钱跑一次的进对话台，
 * 改配置、或者决定"以后什么时候会有一件活"的进应用管理。
 */
export const NAV_SECTIONS: NavGroup[] = [
  {
    key: 'console',
    label: 'AI Agent 对话台',
    title: 'AI Agent 对话台',
    tag: { color: 'blue', text: '八个模式 · 各自的契约摆在一起才比得出来' },
    items: [
      { mode: 'stream', label: 'SSE 流式' },
      { mode: 'structured', label: '结构化输出' },
      { mode: 'threads', label: '线程与预算' },
      { mode: 'rag', label: 'RAG 分块评测' },
      { mode: 'agent', label: 'Agent 工具调用' },
      { mode: 'cost', label: '成本看板' },
      { mode: 'prompt', label: 'Prompt 版本' },
      { mode: 'alerts', label: '告警与灰度' },
    ],
  },
  {
    key: 'apps',
    label: 'AI Agent 应用管理',
    title: 'AI Agent 应用管理：账号 / 角色 / 权限 / 任务 / 技能 / 定时',
    tag: { color: 'geekblue', text: '配一次 · 之后每次调用都按它生效' },
    items: [
      { mode: 'users', label: '用户管理' },
      { mode: 'permissions', label: '权限管理' },
      { mode: 'tasks', label: 'agent任务' },
      { mode: 'skill', label: 'skill管理' },
      { mode: 'schedules', label: '定时任务' },
    ],
  },
]

/**
 * 当前视图在目录里那一项的名字（`SSE 流式` / `权限管理` …）。
 *
 * 目录收起来之后整条不在眼前，"现在在哪一项"就只剩页头这一处能读（见 App.tsx）——
 * 名字从 `NAV_SECTIONS` 取，所以它与目录里那一项永远是同一个字符串。
 */
export function labelOf(mode: Mode): string {
  const hit = NAV_SECTIONS.flatMap(section => section.items).find(item => item.mode === mode)
  return hit ? hit.label : mode
}

/** 当前模式属于哪一区。顶栏的标题、标签、以及"要不要显示 conversationId"都由它决定 */
export function sectionOf(mode: Mode): NavGroup {
  const hit = NAV_SECTIONS.find(section => section.items.some(item => item.mode === mode))
  // 目录里必然有当前模式的归属（NAV_SECTIONS 的 items 与 Mode 是同一批字面量），兜底只为让返回类型非空
  return hit ?? NAV_SECTIONS[0]
}
