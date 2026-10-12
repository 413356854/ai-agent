/**
 * 定时任务端点的**假数据**（应用管理 · 定时任务）。
 *
 * ---------------------------------------------------------------------------
 * 与 task-routes / skill-routes 同一条纪律：**有写端点，就必须有 __reset**
 * ---------------------------------------------------------------------------
 * 这一组有五个写端点（配规则 / 改规则 / 启停 / 手动触发 / 删除），
 * 界面自检要验的正是"配一条之后列表上多一行、改一条之后 `cron` 与 `nextRuns` 都换了、
 * 删一条之后它不见了"。所以状态是**进程内可变**的，并且必须提供
 * `POST /api/agent-task-schedules/__reset` —— 否则自检跑第二遍时会继承第一遍建出来的规则。
 *
 * ---------------------------------------------------------------------------
 * ★ 它还要**兑现真后端那条最容易验错的行为**：改正则 = 删旧线程重加
 * ---------------------------------------------------------------------------
 * 真后端改规则的实现固定是"先摘掉旧线程、再按新正则装一条"，所以改完之后
 * `nextRuns` **一定**是按新表达式算的（见 `AgentTaskScheduleService` 的类注释）。
 * 假后端如果只是把 `cron` 那一格改掉、而 `nextRuns` 沿用旧的，自检就会去断言
 * "改了之后下一次还是原来那个时刻" —— 那条断言在真后端上是错的，而它**不会报错**，
 * 只会让人以为功能坏了。所以这里跟着改 `nextRuns`（见 `reschedule`）。
 *
 * ---------------------------------------------------------------------------
 * ★ 正则的解析与"下一次触发"照真后端的样子算，不引 cron 库
 * ---------------------------------------------------------------------------
 * 假后端认的形态就是界面构造器会发出的那几种（`web/src/cron.ts`）：
 *   · `0 *&#47;N * * * *`        每 N 分钟
 *   · `0 *&#47;N * * * *`（秒位） 每 N 秒
 *   · `0 0 0&#47;N * * *`        每 N 小时
 *   · `0 M H * * *`            每天 H:M
 *   · `0 M H ? * d1,d2`        每周几 H:M
 *   · `0 M H D * ?`            每月 D 号 H:M
 * 认不出来的（手写的那一档）按**每分钟一次**算：自检只需要一个稳定的、看得出变化的
 * `nextRuns`，而这里刻意不去半个半个地实现 cron —— 那会变成第二份判据，
 * 而它与真后端不一致时，自检红在一个看起来像界面问题的地方。
 *
 * ⚠️ 但**格式判据要照搬**：6 段、字段数不对就是 400；「日」与「周」同时写具体值也是 400
 * （真后端那一条最值钱的判断：`0 0 9 1 * MON` 解析**成功**、语义是"或"，不报错）。
 * 自检要验的正是"页面上配不出这种表达式，而手写那一档会被后端拦下"。
 *
 * ---------------------------------------------------------------------------
 * ★ 删除的判据：派过任务的不能删
 * ---------------------------------------------------------------------------
 * 与真后端同一条（`DefaultAgentTaskScheduleService#delete`）：`runs > 0` 时回 400
 * 并说明"那些任务还留着，而这条规则是它们唯一的由来；要停就停用"。
 * 假数据里刻意留**一条 runs > 0 的规则**（`RUN_HISTORY`），否则这条分支永远走不到。
 *
 * ---------------------------------------------------------------------------
 * ★★ 它**不模拟**「到点先让模型把描述模板解析成当下这句话」那一步
 * ---------------------------------------------------------------------------
 * 真后端到点时做两件事：① 把描述里的周期说法（"昨天""上个月""本周"）交给模型
 * 换成那一刻的具体日期；② 按解析后的那句生成 agent 任务记录。
 * 假后端**只做第 ② 件**，而且连任务都不真建（见下面那条：它只记账）。
 *
 * <p>所以这一页上与"解析"有关的东西，假后端这里**一条都验不到**：
 * 解析出来的描述长什么样、解析失败时"这一次不派活"、模板里没有时间说法时原样返回。
 * 那些由 `web/tools/probe-schedule-describer.mjs` 配一个可控的替身模型去打真后端验
 * （替身在 `web/tools/fake-openai.mjs`）。
 *
 * <p>⚠️ <b>刻意不在假后端里造一句带假日期的描述</b>：那会让自检里出现
 * "描述里有具体日期"这种看起来验过了、其实验的是假数据的断言 ——
 * 而这一页最不能出错的就是那个日期（它会被拿去执行）。
 */

import { findMockAccount, findMockAccountById } from './permission-routes.mjs'

/** 初始规则：四条覆盖四种要显示的状态（正常启用 / 启用但停用 / 停用 / 派过任务）。 */
const INITIAL_RULES = [
  {
    id: 1,
    cron: '0 0 9 ? * 1,2,3,4,5',
    publisherId: 1,
    assigneeId: 1,
    description: '每个工作日早上汇总昨天的运行记录，把失败的那些按原因归类，写成一份晨报草稿。',
    enabled: true,
    runs: 0,
    lastRunAt: null,
  },
  {
    id: 2,
    cron: '0 */30 * * * *',
    publisherId: 1,
    assigneeId: 2,
    description: '每半小时看一眼待验收的任务有没有卡超过两小时的，卡住的在描述里点名。',
    enabled: true,
    runs: 0,
    lastRunAt: null,
  },
  {
    id: 3,
    cron: '0 0 2 1 * ?',
    publisherId: 1,
    assigneeId: 2,
    description: '每月 1 号凌晨整理上个月的技能库，把重复的规则合并成一条。',
    enabled: false,
    runs: 0,
    lastRunAt: null,
  },
  {
    // ★ 刻意留一条**派过任务**的：它是"不能删"那条分支唯一的数据来源。
    // 停用着：真后端里"停用"与"派过任务"是两件互不影响的事（能停用、不能删）。
    id: 4,
    cron: '0 0 18 * * *',
    publisherId: 1,
    assigneeId: 2,
    description: '每天 18:00 生成一份当天的成本小结（这一条已经派过任务，用来验「不能删」那条分支）。',
    enabled: false,
    runs: 3,
    lastRunAt: '2026-10-11T10:00:00Z',
  },
]

/**
 * 一条**曾经启用、正则被手工改坏**的规则。
 *
 * 它只在 `__break` 那一条自检辅助端点上被塞进列表 —— 真后端里出现这种行的唯一途径是
 * 有人手工改库（界面上的写入路径会拒掉算不出触发时刻的表达式）。
 * 自检要断的是界面上**说得出来**这件事：「启用但不在池子里 → 它不会自动触发」。
 */
const BROKEN_RULE = {
  id: 99,
  cron: '0 0 9 31 2 *',
  publisherId: 1,
  assigneeId: 1,
  description: '这一条的正则算不出下一次触发（2 月 31 号）—— 用来验界面上那句"它不会自己跑"。',
  enabled: true,
  runs: 0,
  lastRunAt: null,
}

/** 派过的任务（`POST /{id}/trigger` 与自检用的读数）。 */
function initialState() {
  return {
    rules: INITIAL_RULES.map(rule => ({ ...rule })),
    nextId: 5,
    /** 手工触发派出去的任务：`{ taskId, scheduleId, statusLabel }`。 */
    dispatched: [],
    /** 每次写操作的调用记录 —— 自检用它断"改正则真的删了旧线程重加"（见文件头）。 */
    audits: [],
  }
}

let state = initialState()

/** 现在时间（ISO-8601 文本，与真后端 `Instant.toString()` 同形）。 */
function now() {
  return new Date().toISOString()
}

// ---------------------------------------------------------------------------
//  正则：格式判据 + 下一次触发
// ---------------------------------------------------------------------------

/** 一句正则的格式判据（与真后端 `ScheduleCron#validate` 同口径，见文件头）。 */
function validateCron(expression) {
  const normalized = String(expression ?? '').trim().replace(/\s+/g, ' ')
  if (!normalized) {
    return { error: '定时正则不能为空 —— 格式是 6 段「秒 分 时 日 月 周」，例：0 0 9 ? * 1,2,3,4,5' }
  }
  const fields = normalized.split(' ')
  if (fields.length !== 6) {
    return {
      error: `Cron expression must consist of 6 fields (found ${fields.length} in "${normalized}") —— `
        + '「6 段式」是「秒 分 时 日 月 周」，Unix crontab 的 5 段式这里不接受：秒那一段必须写出来。',
    }
  }
  const concrete = field => !field.startsWith('*') && !field.startsWith('?')
  if (concrete(fields[3]) && concrete(fields[5])) {
    return {
      error: `「日」与「周」不能同时写具体值：「${normalized}」里的「${fields[3]}」与「${fields[5]}」`
        + '会被解析成「日 或 周」—— 两个日子都会触发，而且不报错。'
        + '要按星期几跑就把「日」写成 ? 或 *（例：0 0 9 ? * 1），要按几号跑就把「周」写成 ? 或 *（例：0 0 9 1 * ?）。',
    }
  }
  return { normalized, fields }
}

/** 六个字段的中文说法（与真后端 `ScheduleCron#describe` 的覆盖面对齐）。 */
function describeCron(fields) {
  const [second, minute, hour, dayOfMonth, , dayOfWeek] = fields
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const number = value => (/^\d+$/.test(value) ? Number(value) : null)
  const pad = value => String(value).padStart(2, '0')

  let time
  if (minute.startsWith('*/') && hour === '*') {
    time = `每 ${minute.slice(2)} 分钟`
  }
  else if (second.startsWith('*/') && minute === '*' && hour === '*') {
    time = `每 ${second.slice(2)} 秒`
  }
  else if (hour.startsWith('0/')) {
    time = `每 ${hour.slice(2)} 小时（从 0 点起算）`
  }
  else if (number(minute) != null && number(hour) != null) {
    time = `${pad(number(hour))}:${pad(number(minute))}`
  }
  else {
    time = `在这几个时刻（秒=${second}、分=${minute}、时=${hour}）`
  }

  let day = ''
  if (dayOfWeek !== '*' && dayOfWeek !== '?') {
    const names = dayOfWeek.split(',').map(Number).map(value => week[value % 7])
    day = `每周${names.join('、')}`
  }
  else if (dayOfMonth !== '*' && dayOfMonth !== '?') {
    day = number(dayOfMonth) != null ? `每月 ${dayOfMonth} 号` : `每月 ${dayOfMonth}`
  }
  return `${day}${time}触发`
}

const WEEK_NAMES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
const MONTH_NAMES = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']

/**
 * 每个月最多几天（2 月按闰年算 29）。
 *
 * <p>它只为一条判断服务：{@code 0 0 9 31 2 *}（2 月 31 号）在真后端上
 * <b>解析成功但 {@code next()} 返回 null</b> —— 而界面上它是"算不出下一次触发"，
 * 也就是"这条规则不会跑"。假后端不判这一条的话，自检会拿一条在真后端上装不进池子的规则
 * 去断"界面上说它没问题"。
 */
const MAX_DAYS_IN_MONTH = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]

/** 星期 / 月份的名字 → 数字（真后端认这些名字，而手写那一档用户会写 `MON-FRI`）。 */
function namedValue(field, names) {
  const index = names.indexOf(field.toUpperCase())
  return index < 0 ? null : index
}

/**
 * 这一格允许哪些取值。
 *
 * @returns 升序数组 = 这一格限定了这些值；{@code null} = **这一格没有约束**
 *          （`*` / `?`，以及认不出的写法如月末 `L`）。调用方对 {@code null} **放行** ——
 *          宁可多算几次，也不要因为"这一步没懂"而给出一个空的 `nextRuns`：
 *          界面上"没有下一次"是一句很重的话（它表示这条规则不会跑）。
 */
function allowedValues(field, min, max, names) {
  // `*` 与 `?` 都是"这一格不限定"（`?` 只是"日 / 周"里的说法）—— 直接返回 null。
  // ⚠️ 不能让它走下面的"展开成 min..max"那一支：展开之后「日」那一格会变成一个
  // 1~31 的具体集合，而后面那条"日与月能不能同时成立"的判断就会拿它去算
  // （2 月 × 31 号），把一句完全正常的 `0 0 9 ? * 1,2,3,4,5` 判成"永远等不到"。
  if (field === '*' || field === '?') return null

  const single = value => {
    const text = value.trim()
    if (!/^\d+$/.test(text)) {
      const named = names ? namedValue(text, names) : null
      return named == null ? null : named
    }
    const parsed = Number(text)
    return parsed < min || parsed > max ? null : parsed
  }

  const out = new Set()
  for (const part of field.split(',')) {
    const [range, stepRaw] = part.split('/')
    const step = stepRaw == null ? 1 : Number(stepRaw)
    if (!Number.isFinite(step) || step < 1) return null
    if (range === '*' || range === '?') {
      for (let value = min; value <= max; value += step) out.add(value)
      continue
    }
    const [fromRaw, toRaw] = range.split('-')
    const from = single(fromRaw)
    if (from == null) return null
    const to = toRaw == null ? (stepRaw == null ? from : max) : single(toRaw)
    if (to == null) return null
    for (let value = from; value <= to; value += step) out.add(value)
  }
  return [...out].sort((a, b) => a - b)
}

/**
 * 未来 5 次触发时刻（`yyyy-MM-dd HH:mm:ss`，本地时区）—— 真后端用 `CronExpression#next` 算。
 *
 * <h2>为什么它必须真的算，而不是"给几个看起来像的时刻"</h2>
 * 界面上那块预览是"我配的对不对"唯一的证据（`verify-ui.mjs` 有一条断言直接拿它
 * 与后端返回的这一份逐字比）。假后端随便给几个时刻，那条断言就永远为真 ——
 * 而"预览与真正跑的时刻对不上"正是这个功能最该被断住的一种错。
 *
 * <h2>算法：按最小步长往前走，逐格核对</h2>
 * 步长 = 秒/分/时三段里最小的那个间隔（`*&#47;5` 是 5 分钟、`0/4` 是 4 小时、普通值是 1 天），
 * 每一步核对「秒 分 时 日 月 周」五格。真后端的 `CronExpression` 也是这么走的
 * （它的实现是"逐字段往后跳"）—— 差别只在效率，而这一页的规模不需要跳。
 *
 * ⚠️ 认不出的格子（`L`、非法的月份）由 {@link allowedValues} 返回 {@code null}，
 * 这里**放行**那一格。于是"算不出下一次"在假后端上只可能是真的一个都凑不齐
 * （例如月份写了 13）—— 而不是"这一步没懂"。
 */
function nextRuns(fields, count = 5) {
  if (!fields) return []
  const [second, minute, hour, dayOfMonth, month, dayOfWeek] = fields
  const seconds = allowedValues(second, 0, 59)
  const minutes = allowedValues(minute, 0, 59)
  const hours = allowedValues(hour, 0, 23)
  const months = allowedValues(month, 1, 12, MONTH_NAMES)
  const days = allowedValues(dayOfMonth, 1, 31)
  const weeks = allowedValues(dayOfWeek, 0, 7)

  // 步长：**只看带步长的那些格子**（`*/5` / `0/4`）。
  // ⚠️ 不能把"具体值"的格子也算进来：实测第一版把「时」的固定值当成 24 小时，
  // 于是 `0 0 9 * * *`（每天 09:00）的步长是 86400 秒，而整天整天加了 366 天
  // 一次都没落进 09:00:00 —— 算出来是空的 nextRuns，界面上会显示"这条规则不会自己跑"。
  // 一格步长都没有时按分钟走（一句表达式最多也就走 24×60 步）。
  const stepSeconds = (() => {
    const gaps = []
    const stepOf = field => {
      const part = field.split(',').find(item => item.includes('/'))
      return part ? Number(part.split('/')[1]) : null
    }
    if (stepOf(second) != null) gaps.push(stepOf(second))
    if (stepOf(minute) != null) gaps.push(stepOf(minute) * 60)
    if (stepOf(hour) != null) gaps.push(stepOf(hour) * 3600)
    if (gaps.length === 0) return 60
    return Math.max(1, Math.min(...gaps))
  })()

  // 「日」与「月」的组合是不是根本不存在（2 月 31 号）——真后端那种情况下 `next()` 返回 null，
  // 所以这里也要给空数组，否则自检会在假后端上看到一条"能用"的规则而真后端上它装不进池子
  const impossible = days != null && months != null
    && !months.some(month => days.some(day => day <= MAX_DAYS_IN_MONTH[month - 1]))

  const runs = []
  // 起点：现在，毫秒清零（真后端的 next() 给的是"严格晚于现在"的第一个时刻）
  const cursor = new Date()
  cursor.setMilliseconds(0)
  // ★ 起点必须先**对齐到步长的整数倍**，否则整条走法永远踩不到点上。
  // 实测踩过：`0 */30 * * * *`（每 30 分钟）在 00:12:01 起按 30 分钟走，
  // 落点是 00:12:01 / 00:42:01 / …，秒永远是 1，而这一格要求 0 —— 算出来是空的 nextRuns，
  // 界面上显示"这条规则不会自己跑"。
  // 对齐的判据：步长是 30 秒 → 纪元秒数取整到 30 的倍数（也就是 :00 与 :30）；
  // 是 5 分钟 / 4 小时同理（3600 秒一格的情况下，取整到 3600 的倍数就是整点）。
  const stepMs = stepSeconds * 1000
  const base = Math.floor((cursor.getTime() - cursor.getTimezoneOffset() * 60_000) / stepMs) * stepMs
    + cursor.getTimezoneOffset() * 60_000
  let at = base + stepMs
  if (at <= cursor.getTime()) {
    at += stepMs
  }
  // 上限是防死循环：一句永远凑不齐的正则走到这里就停，
  // 而不是把假后端挂在那里（与真后端 `next()` 返回 null 同一个效果）
  const limit = 366 * 24 * 60 * 60 * 1000
  const deadline = cursor.getTime() + limit

  while (!impossible && runs.length < count && at <= deadline) {
    const date = new Date(at)
    const ok = (seconds == null || seconds.includes(date.getSeconds()))
      && (minutes == null || minutes.includes(date.getMinutes()))
      && (hours == null || hours.includes(date.getHours()))
      && (months == null || months.includes(date.getMonth() + 1))
      && (days == null || days.includes(date.getDate()))
      && (weeks == null || weeks.includes(date.getDay()) || weeks.includes(date.getDay() + 7))
    if (ok) runs.push(format(date))
    at += stepMs
  }
  return runs
}

function format(date) {
  const pad = value => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * 改一条规则之后**重算 nextRuns** —— 这一步对应真后端的"删旧线程、按新正则重加"。
 * 见文件头：不重算的话，自检会去断一条在真后端上是错的结论。
 */
function ruleView(rule) {
  const parsed = validateCron(rule.cron)
  const fields = parsed.fields
  // 停用的规则不在池子里 → 没有下一次（真后端给的是空数组，不是"算不出来"）
  const upcoming = rule.enabled && fields ? nextRuns(fields) : []
  return {
    id: rule.id,
    cron: parsed.normalized ?? rule.cron,
    cronDescription: fields ? describeCron(fields) : '（这句正则现在算不出下一次触发）',
    publisherId: rule.publisherId,
    publisher: nameOf(rule.publisherId),
    // ★ 写请求里要的是**登录名**，所以这里必须另给一格 `assigneeName`。
    // 只给 `assignee`（姓名）的话，界面把它当成登录名回传，而姓名与登录名通常是两个字符串
    // —— 后端按登录名查账号查不到，于是"改规则"永远失败，报的还是"账号表里没有「李工」这个人"。
    // 真后端那两格与这里逐字对应（`AgentTaskScheduleController#rule`）。
    assigneeId: rule.assigneeId,
    assignee: rule.assigneeId == null ? '未指定任务人' : nameOf(rule.assigneeId),
    assigneeName: rule.assigneeId == null ? '' : loginOf(rule.assigneeId),
    description: rule.description,
    enabled: rule.enabled,
    runs: rule.runs,
    lastRunAt: rule.lastRunAt,
    nextRuns: upcoming,
    // ★ 判据是**算不算得出下一次**，不是"正则解析过不过"：
    // 两者在"正则合法但日历上永远等不到"（`0 0 9 31 2 *`，2 月 31 号）这一格上分道扬镳 ——
    // 拿"解析过了"当判据会把那条规则报成"已挂上线程"，而它的 nextRuns 是空的。
    // 真后端那两格也是分开算的（`scheduled` 由"装进池子"决定，而算不出下一次的表达式会被拒），
    // 界面上"启用却没挂上线程"那条提示正是靠这两格一起判出来的
    scheduled: upcoming.length > 0,
    createdAt: '2026-10-01T02:00:00Z',
    updatedAt: now(),
  }
}

function poolView() {
  const scheduled = state.rules.filter(rule => ruleView(rule).scheduled).length
  return {
    poolSize: 4,
    activeThreads: 0,
    // 池子按需建线程：挂了规则就有线程，一条都没挂时是 0（真后端实测就是这个数）
    poolSizeNow: scheduled === 0 ? 0 : Math.min(4, scheduled),
    scheduled,
    cancelledPending: state.audits.filter(entry => entry.action === 'reschedule').length,
  }
}

function snapshot(userId) {
  return {
    rules: state.rules.map(ruleView),
    total: state.rules.length,
    userId: userId ?? null,
    pool: poolView(),
    howToWrite: 'POST /api/agent-task-schedules（配一条）；改 PUT /{id}；'
      + '停用启停 POST /{id}/enabled；手动跑一次 POST /{id}/trigger；删 DELETE /{id}；预览正则 POST /preview',
  }
}

/** 姓名那一份：显示名优先、没填退回登录名（与真后端读侧那句 COALESCE 同一判据）。 */
function nameOf(userId) {
  const user = findMockAccountById(userId)
  if (!user) return `用户#${userId}`
  return user.displayName || user.name
}

/** 登录名那一份（{@link #nameOf} 的对照）：写请求里要的是**它**。 */
function loginOf(userId) {
  const user = findMockAccountById(userId)
  if (!user) return `用户#${userId}`
  return user.name
}

/** 发布人 / 任务人都必须是账号表里真实存在的用户（与真后端 `realUser` 同一句话）。 */
function realUser(name, what) {
  if (name == null || String(name).trim() === '') {
    return { error: `${what}必填 —— 发布人与任务人都要从用户列表里选一个真实用户` }
  }
  const account = findMockAccount(String(name).trim())
  if (!account) {
    return {
      error: `账号表里没有「${name}」这个人，不能当${what} —— 定时任务的发布人与任务人都必须是真实用户，`
        + '因为每次触发都要用这两个 id 换回登录名去建任务；打错一个字不会有任何东西拦下这条规则，'
        + '它只会在**触发的那一刻**失败，而那时你不在这一页上。',
    }
  }
  return { account }
}

// ---------------------------------------------------------------------------
//  路由
// ---------------------------------------------------------------------------

export function handleScheduleRoutes(req, res, url, json) {
  const pathname = url.pathname

  if (req.method === 'POST' && pathname === '/api/agent-task-schedules/__reset') {
    state = initialState()
    json(res, { ok: true, rules: state.rules.length })
    return true
  }

  // 自检辅助：塞一条"启用了但正则算不出下一次"的规则（见 BROKEN_RULE 的注释）
  if (req.method === 'POST' && pathname === '/api/agent-task-schedules/__break') {
    if (!state.rules.some(rule => rule.id === BROKEN_RULE.id)) {
      state.rules = [...state.rules, { ...BROKEN_RULE }]
    }
    json(res, { ok: true, rules: state.rules.length })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/agent-task-schedules') {
    json(res, snapshot(url.searchParams.get('userId') ? Number(url.searchParams.get('userId')) : null))
    return true
  }

  // 预览：**不写库**。它必须排在 `/{id}` 之前，否则 "preview" 会被当成一个 id
  if (req.method === 'POST' && pathname === '/api/agent-task-schedules/preview') {
    readBody(req, res, json, body => {
      const parsed = validateCron(body.cron)
      if (parsed.error) {
        json(res, { message: parsed.error }, 400)
        return
      }
      json(res, {
        ok: true,
        cron: parsed.normalized,
        description: describeCron(parsed.fields),
        nextRuns: nextRuns(parsed.fields),
      })
    })
    return true
  }

  if (req.method === 'POST' && pathname === '/api/agent-task-schedules') {
    readBody(req, res, json, body => {
      const publisher = realUser(body.publisher, '发布人')
      if (publisher.error) { json(res, { ok: false, error: publisher.error }, 400); return }
      const assignee = realUser(body.assignee, '任务人')
      if (assignee.error) { json(res, { ok: false, error: assignee.error }, 400); return }
      if (!body.description || !String(body.description).trim()) {
        json(res, { ok: false, error: '缺少 description（任务描述）—— 它同时是每次触发生成的那条任务的描述' }, 400)
        return
      }
      const parsed = validateCron(body.cron)
      if (parsed.error) { json(res, { ok: false, error: parsed.error }, 400); return }

      const rule = {
        id: state.nextId++,
        cron: parsed.normalized,
        publisherId: publisher.account.id,
        assigneeId: assignee.account.id,
        description: String(body.description).trim(),
        enabled: body.enabled == null ? true : Boolean(body.enabled),
        runs: 0,
        lastRunAt: null,
      }
      state.rules = [...state.rules, rule]
      state.audits.push({ action: 'create', id: rule.id, cron: rule.cron })
      const view = ruleView(rule)
      json(res, {
        ok: true,
        rule: view,
        message: `定时任务「${view.cron}」（${view.cronDescription}）→ 发布人 ${view.publisher}、`
          + `任务人 ${view.assignee}，下一次触发 ${view.nextRuns[0] ?? '（算不出下一次）'}`,
      })
    })
    return true
  }

  const enabledMatch = /^\/api\/agent-task-schedules\/(\d+)\/enabled$/.exec(pathname)
  if (req.method === 'POST' && enabledMatch) {
    const rule = state.rules.find(item => item.id === Number(enabledMatch[1]))
    if (!rule) { json(res, { ok: false, error: `定时规则不存在：#${enabledMatch[1]}` }, 400); return true }
    readBody(req, res, json, body => {
      if (body.enabled == null) {
        json(res, { ok: false, error: '缺少 enabled（true 启用 / false 停用）' }, 400)
        return
      }
      rule.enabled = Boolean(body.enabled)
      state.audits.push({ action: rule.enabled ? 'enable' : 'disable', id: rule.id })
      const view = ruleView(rule)
      json(res, {
        ok: true,
        rule: view,
        message: `规则 #${rule.id}` + (rule.enabled
          ? ` 已启用，线程已装回调度线程池${view.nextRuns[0] ? `；下一次触发 ${view.nextRuns[0]}` : ''}`
          : ' 已停用，线程已从调度线程池摘除'),
      })
    })
    return true
  }

  const triggerMatch = /^\/api\/agent-task-schedules\/(\d+)\/trigger$/.exec(pathname)
  if (req.method === 'POST' && triggerMatch) {
    const rule = state.rules.find(item => item.id === Number(triggerMatch[1]))
    if (!rule) { json(res, { ok: false, error: `定时规则不存在：#${triggerMatch[1]}` }, 400); return true }
    // 手工触发：**真的派一条任务**（记账 + 计数），不重排下一次触发（与真后端一致）
    const taskId = 9000 + state.dispatched.length + 1
    state.dispatched.push({ taskId, scheduleId: rule.id, statusLabel: '新发布' })
    rule.runs += 1
    rule.lastRunAt = now()
    state.audits.push({ action: 'trigger', id: rule.id, taskId })
    json(res, {
      ok: true,
      scheduleId: rule.id,
      taskId,
      statusLabel: '新发布',
      message: `已按规则 #${rule.id} 派出 agent 任务 #${taskId}（新发布）—— 这次是手工点的，`
        + '规则的触发周期没有变；它接下来怎么走，在 agent任务 那一页看',
    })
    return true
  }

  const idMatch = /^\/api\/agent-task-schedules\/(\d+)$/.exec(pathname)
  if (req.method === 'PUT' && idMatch) {
    const rule = state.rules.find(item => item.id === Number(idMatch[1]))
    if (!rule) { json(res, { ok: false, error: `定时规则不存在：#${idMatch[1]}` }, 400); return true }
    readBody(req, res, json, body => {
      const assignee = realUser(body.assignee, '任务人')
      if (assignee.error) { json(res, { ok: false, error: assignee.error }, 400); return }
      if (!body.description || !String(body.description).trim()) {
        json(res, { ok: false, error: '缺少 description（任务描述）—— 改规则时它也要给全' }, 400)
        return
      }
      const parsed = validateCron(body.cron)
      if (parsed.error) { json(res, { ok: false, error: parsed.error }, 400); return }

      const previous = rule.cron
      rule.cron = parsed.normalized
      rule.assigneeId = assignee.account.id
      rule.description = String(body.description).trim()
      rule.enabled = body.enabled == null ? true : Boolean(body.enabled)
      // ★ 真后端固定做"删旧线程、按新正则重加"，所以这里也重算 nextRuns（见文件头）
      state.audits.push({ action: 'reschedule', id: rule.id, from: previous, to: rule.cron })
      const view = ruleView(rule)
      json(res, {
        ok: true,
        rule: view,
        message: `规则 #${rule.id} 已改，旧线程已删除、按新正则重新装进调度线程池：`
          + `${view.cron}（${view.cronDescription}）`,
      })
    })
    return true
  }

  if (req.method === 'DELETE' && idMatch) {
    const id = Number(idMatch[1])
    const rule = state.rules.find(item => item.id === id)
    if (!rule) { json(res, { ok: false, error: `定时规则不存在：#${id}` }, 400); return true }
    // 派过任务的不能删（与真后端同一条判据，见文件头）
    if (rule.runs > 0) {
      json(res, {
        ok: false,
        error: `定时规则 #${id} 已经派过 ${rule.runs} 条任务，不能删 —— 那些任务还留在 agent任务 那一页，`
          + '而这条规则是它们唯一的由来（任务上没有外键指向它，删了之后「这些活是谁派的」就再也答不出来）。'
          + '要停就**停用**：停用同时会把它的线程从调度线程池摘掉，效果与删除一样，而由来还在。',
      }, 400)
      return true
    }
    state.rules = state.rules.filter(item => item.id !== id)
    state.audits.push({ action: 'delete', id })
    json(res, {
      ok: true,
      message: `规则 #${id} 已删除，它的线程已从调度线程池摘除`
        + '（它派出去的那些任务不受影响 —— 它们本来就不依赖这条规则存在）',
    })
    return true
  }

  if (req.method === 'GET' && idMatch) {
    const rule = state.rules.find(item => item.id === Number(idMatch[1]))
    if (!rule) { json(res, { ok: false, error: `定时规则不存在：#${idMatch[1]}` }, 400); return true }
    json(res, ruleView(rule))
    return true
  }

  return false
}

/**
 * 派过几条任务 / 最近一次调用记录 —— 自检与探针读它。
 *
 * <p>它导出给 `sse-server.mjs` 挂一个只读诊断端点用：自检要断
 * **"改正则之后旧线程真的被删了重加"**（`audits` 里那一条 `reschedule`），
 * 而"改完之后界面上显示的是新正则"只证明界面**显示**对了。
 */
export function mockScheduleAudits() {
  return { audits: [...state.audits], dispatched: [...state.dispatched], rules: state.rules.length }
}

/**
 * 读请求体（假后端的最小实现，与 task-routes / skill-routes 里那一个逐字相同）。
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
