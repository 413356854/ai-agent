/**
 * 定时正则的浏览器侧构造与中文说法（应用管理区 · 定时任务）。
 *
 * ---------------------------------------------------------------------------
 * 一句话：**这里只负责把界面上的选择拼成一句 6 段正则，判断对错不在这里**
 * ---------------------------------------------------------------------------
 * "这句正则能不能用"、"它接下来什么时候跑"由后端算（`POST /api/agent-task-schedules/preview`，
 * 实现是 `ScheduleCron` + Spring 的 `CronExpression`）—— 线程池装线程时用的是同一份判断。
 * 前端再实现一遍解析的后果很具体：两处判断一旦不同，页面上会出现
 * **"预览说合法、保存时报错"**（或者反过来），而用户无从判断哪一个是准的。
 *
 * 所以这一层的职责只有两条：
 *   1. {@link buildCron} —— 把"每 N 分钟 / 每天 09:30 / 每周一三五 / 每月 1 号"这类选择
 *      拼成后端认的那 6 段表达式；
 *   2. {@link describeDraft} —— 把上面的选择说成一句中文，显示在输入框旁边
 *      （这是"我选的东西对不对"的即时反馈；"它什么时候跑"以后端给的 `nextRuns` 为准）。
 *
 * ---------------------------------------------------------------------------
 * 三条硬约束（都来自后端，写在 `ScheduleCron` 的类注释里）
 * ---------------------------------------------------------------------------
 * **一、6 段：秒 分 时 日 月 周。** Unix crontab 的 5 段式**不被接受**（实测 Spring 7.0.9
 * 的 `CronExpression.parse` 只认 6 段）。本文件发出的永远是 6 段。
 *
 * **二、「日」与「周」不能同时写具体值。** 后端解析 `0 0 9 1 * MON` 会**成功**，
 * 语义是"每月 1 号**或**每个周一"—— 一个不报错的错，所以写入路径直接拒绝。
 * 于是这里的做法是：选了"每周"就把「日」写成 `?`，选了"每月"就把「周」写成 `?`，
 * 选"每隔几天"时「周」也是 `?`。**没有任何一种选择会让两者同时是具体值。**
 *
 * **三、「每隔 N 天」用 `*` 开头。** 它属于"这一格没有指名某个日子"，
 * 与"每月 1 号"不是一回事 —— 这条判据在后端 `ScheduleCron#concrete` 里。
 *
 * ---------------------------------------------------------------------------
 * 时区
 * ---------------------------------------------------------------------------
 * 界面上不出现时区选项：后端算触发时刻用的是 JVM 默认时区（`ZonedDateTime.now()`），
 * 前端这一层不参与时间计算，所以两边不会各按一个时区理解同一句表达式。
 */

/** 触发间隔的单位。`second` / `minute` / `hour` 是"每 N 个单位"。 */
export type IntervalUnit = 'second' | 'minute' | 'hour'

/**
 * 界面上那几种配置形态。
 *
 * 刻意**只有五种**：每一种都对应一句能一眼读懂的规则。多加一种"每 N 天里的第几个小时"
 * 这类组合，正则就会开始需要人自己推 —— 而那时用户该做的是切到 `custom` 直接写表达式。
 */
export type ScheduleMode = 'interval' | 'daily' | 'weekly' | 'monthly' | 'custom'

/** 星期几的取值：0=周日 … 6=周六（与 Spring 的 cron 数字一致，`7` 也是周日）。 */
export interface WeekdayOption {
  /** 正则里那一位的值（数字，避免 `MON` / `MON-FRI` 这类名字在不同实现里的歧义） */
  value: number
  /** 界面上的字 */
  label: string
}

export const WEEKDAYS: WeekdayOption[] = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 0, label: '周日' },
]

/** 一份还没落库的配置（界面上的那几格）。 */
export interface ScheduleDraft {
  mode: ScheduleMode
  /** `interval`：每 N 个单位。 */
  every: number
  unit: IntervalUnit
  /** `daily` / `weekly` / `monthly`：几点几分。 */
  hour: number
  minute: number
  /** `weekly`：哪几天（0-6，可多选；空数组 = 用户还没选）。 */
  weekdays: number[]
  /** `monthly`：几号（1-31）。 */
  dayOfMonth: number
  /** `custom`：直接写的表达式（**只有这一种形态会把它原样发出去**）。 */
  expression: string
}

/** 每种形态的初始值：`daily` 09:00 —— 一个大多数"每天跑一次"的规则都用得上的时刻。 */
export const DEFAULT_DRAFT: ScheduleDraft = {
  mode: 'daily',
  every: 30,
  unit: 'minute',
  hour: 9,
  minute: 0,
  weekdays: [1, 2, 3, 4, 5],
  dayOfMonth: 1,
  expression: '0 0 9 * * *',
}

/** 后端那 6 个字段的中文名，按顺序 —— 界面上那一行提示直接用它拼。 */
export const CRON_FIELDS = ['秒', '分', '时', '日', '月', '周'] as const

/**
 * 一份配置 → 一句 6 段正则。
 *
 * 五种形态各自的产物：
 * <pre>
 * interval 每 5 秒         → &#42;/5 * * * * *     （步长在**秒**那一段）
 * interval 每 30 分钟      → 0 &#42;/30 * * * *     （步长在**分**那一段，秒固定 0）
 * interval 每 4 小时       → 0 0 0/4 * * *       （步长在**时**那一段，从 0 点起算）
 * daily    每天 09:30      → 0 30 9 * * *
 * weekly   每周一三五 09:30 → 0 30 9 ? * 1,3,5    （「日」写 ?：两者不能同时具体，见文件头）
 * monthly  每月 1 号 09:30  → 0 30 9 1 * ?        （「周」写 ?，同理）
 * custom   手写            → 原样（判断交给后端）
 * </pre>
 *
 * <h2>★ 步长写在哪一段，决定了它是"每 N 秒"还是"每 N 分"</h2>
 * 实测踩过：三种"每隔"共用了同一句 `0 &#42;/N * * * *`，于是界面上选「每 5 秒」拼出来的
 * 是"每 5 **分钟**" —— 而它在界面上、在后端预览里都读得通（"每 5 分钟"是一句正常的话），
 * 只是跑起来的次数差 60 倍。<b>秒那一段必须真的收到那个步长。</b>
 *
 * <p>"每 N 小时"的写法是 `0 0 0/N * * *`（**从 0 点起算**，不是"从当前时刻起算"）：
 * 这是 Spring 的语义，界面上那一句中文也必须照它说 —— 写成"每 4 小时"而不说起点，
 * 用户会以为"现在开始每 4 小时"，而实际是 0/4/8/12/16/20 点。
 *
 * @param draft 界面上的那几格
 * @returns 6 段表达式；`custom` 形态原样返回（可能是一句不合法的，由后端判）
 */
export function buildCron(draft: ScheduleDraft): string {
  if (draft.mode === 'custom') {
    return draft.expression.trim()
  }
  if (draft.mode === 'interval') {
    const step = Math.max(1, Math.floor(draft.every))
    if (draft.unit === 'second') {
      return `*/${step} * * * * *`
    }
    if (draft.unit === 'minute') {
      return `0 */${step} * * * *`
    }
    // 每 N 小时：从 0 点起算（`0/N` 的第一段是起点）
    return `0 0 0/${step} * * *`
  }
  const at = `0 ${draft.minute} ${draft.hour}`
  if (draft.mode === 'daily') {
    return `${at} * * *`
  }
  if (draft.mode === 'weekly') {
    // 一天都没选时给一个通配的「周」——不编一个假的"周一"，那样用户会以为已经选好了。
    // 这一份在界面上是"还没选完"，按钮也点不动（判据在面板那一层）
    const days = draft.weekdays.length === 0 ? '*' : [...draft.weekdays].sort().join(',')
    return `${at} ? * ${days}`
  }
  return `${at} ${draft.dayOfMonth} * ?`
}

/**
 * 一份配置的中文说法（显示在正则输入框旁边）。
 *
 * ⚠️ 它**只描述用户选的那几格**，不去解释正则本身：
 * `custom` 形态下用户手写的那一句由后端的中文说法解释（`preview.description`）——
 * 前端自己解释正则的话，两处说法迟早不一致，而"屏幕上那句中文与真正跑的时刻不是一件事"
 * 正是这个功能最不该有的错（用户会照着它确认"我配对了"）。
 */
export function describeDraft(draft: ScheduleDraft): string {
  const at = `${pad(draft.hour)}:${pad(draft.minute)}`
  switch (draft.mode) {
    case 'interval': {
      const unit = draft.unit === 'second' ? '秒' : draft.unit === 'minute' ? '分钟' : '小时'
      if (draft.unit === 'hour') {
        return `每 ${draft.every} 小时（从 0 点起算，即 ${hourList(draft.every)}）`
      }
      return `每 ${draft.every} ${unit}`
    }
    case 'daily':
      return `每天 ${at}`
    case 'weekly': {
      if (draft.weekdays.length === 0) {
        return '每周的哪几天还没选'
      }
      const names = WEEKDAYS.filter(day => draft.weekdays.includes(day.value)).map(day => day.label)
      return `每周${names.join('、')} ${at}`
    }
    case 'monthly':
      return `每月 ${draft.dayOfMonth} 号 ${at}`
    default:
      return '手写的表达式：格式对不对、下一次什么时候跑，看下面后端给的预览'
  }
}

/** "每 4 小时"实际会跑的那几个整点（只说前几个，够看出规律）。 */
function hourList(every: number): string {
  const hours: number[] = []
  for (let hour = 0; hour < 24 && hours.length < 6; hour += Math.max(1, every)) {
    hours.push(hour)
  }
  return hours.map(hour => `${pad(hour)}:00`).join('、') + (hours.length < 24 / Math.max(1, every) ? ' …' : '')
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/**
 * 把一句表达式塞回"哪一种形态"——**只用于"从一条已有规则开始改"时给表单定初值**。
 *
 * 认不出来的（用户手写的、或者后端存的形态不在这五种里）一律落到 `custom`：
 * 那时表达式原样填进输入框，人能看到自己面对的是什么。
 * ⚠️ 刻意**不做**"猜最接近的那一种"：猜错的后果是用户改的其实不是他想改的那一格，
 * 而保存之后表达式变了、他不知道为什么。
 */
export function draftOf(expression: string): ScheduleDraft {
  const fields = expression.trim().split(/\s+/)
  if (fields.length !== 6) {
    return { ...DEFAULT_DRAFT, mode: 'custom', expression }
  }
  const [second, minute, hour, dayOfMonth, , dayOfWeek] = fields
  const custom: ScheduleDraft = { ...DEFAULT_DRAFT, mode: 'custom', expression }

  // 每天 HH:mm:ss（秒与分都是固定值、日/月/周通配）
  if (second === '0' && isNumber(minute) && isNumber(hour) && dayOfMonth === '*' && dayOfWeek === '*') {
    return { ...DEFAULT_DRAFT, mode: 'daily', hour: Number(hour), minute: Number(minute) }
  }
  // 每周几 HH:mm:ss（日那一格是 ? 或 *）
  if (second === '0' && isNumber(minute) && isNumber(hour)
    && (dayOfMonth === '?' || dayOfMonth === '*') && dayOfWeek !== '*') {
    const days = dayOfWeek.split(',').map(Number).filter(value => !Number.isNaN(value))
    return { ...DEFAULT_DRAFT, mode: 'weekly', hour: Number(hour), minute: Number(minute), weekdays: days }
  }
  // 每月几号 HH:mm:ss（周那一格是 ? 或 *）
  if (second === '0' && isNumber(minute) && isNumber(hour) && isNumber(dayOfMonth)
    && (dayOfWeek === '?' || dayOfWeek === '*')) {
    return { ...DEFAULT_DRAFT, mode: 'monthly', hour: Number(hour), minute: Number(minute), dayOfMonth: Number(dayOfMonth) }
  }
  // 每 N 秒 / 每 N 分（日/月/周通配）
  if (second === '0' && minute.startsWith('*/') && hour === '*' && dayOfMonth === '*' && dayOfWeek === '*') {
    const every = Number(minute.slice(2))
    if (!Number.isNaN(every)) {
      return { ...DEFAULT_DRAFT, mode: 'interval', unit: 'minute', every }
    }
  }
  if (second.startsWith('*/') && minute === '*' && hour === '*') {
    const every = Number(second.slice(2))
    if (!Number.isNaN(every)) {
      return { ...DEFAULT_DRAFT, mode: 'interval', unit: 'second', every }
    }
  }
  return custom
}

/** 这一格是不是一个整数（`*` / `?` / `1-5` 都不算）。 */
function isNumber(field: string): boolean {
  return /^\d+$/.test(field)
}
