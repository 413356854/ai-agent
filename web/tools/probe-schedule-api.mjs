/**
 * 一次性探针：定时任务那一族端点打**真后端**（C:\Users\ljf\Desktop\ai agent\ai-agent）。
 *
 * 它验的是界面自检验不到的那一半：
 *   ① **真的到点触发** —— 建一条"每 2 秒"的规则，等它自己跑几轮，再数库里多了几条 agent 任务；
 *   ② **改正则真的重排线程** —— 改完之后 `nextRuns` 换成了新表达式算出来的时刻；
 *   ③ 停用会把线程摘掉（`scheduled` 变 false、`pool.scheduled` 减一）；
 *   ④ 派过任务的规则删不掉（400，且那句话说明要停就停用）；
 *   ⑤ `assigneeName`（登录名）与 `assignee`（姓名）是两格，而 `PUT` 认的是前者。
 *
 * 用法：
 *   node tools/fake-openai.mjs                            # 终端 A：假模型（8098）
 *   # 后端指向它：--spring.ai.openai.base-url=http://127.0.0.1:8098
 *   node tools/probe-schedule-api.mjs                     # 终端 B：本探针（默认打 8099）
 *   BASE=http://127.0.0.1:8080 node tools/probe-schedule-api.mjs
 *
 * ⚠️ **它需要一个能应答的模型端点**，理由见下面那条注释：定时任务到点会先让模型
 * 把描述模板解析成"当下这么说"，而这一步调不通时**那一次不派活** ——
 * 那样这个探针里"到点真的生成了任务记录"那条断言会红，而红的原因与调度无关。
 *
 * ⚠️ 它**会写库**（建规则、跑真任务、删规则），所以只对本机开发库用。
 * ⚠️ 它建的那条"每 2 秒"规则会真的派出任务，而真后端的任务执行器会去调模型
 * （替身只会返回那句描述，跑不动工具）—— 那是预期的：本探针只验"记录被生成了"，
 * 不验那条任务跑到哪一步（那属于 agent任务 那一页）。
 */
const BASE = process.env.BASE ?? 'http://127.0.0.1:8099'
const FAKE = process.env.FAKE ?? 'http://127.0.0.1:8098'
const PATH = `${BASE}/api/agent-task-schedules`

/**
 * 本探针建的那条规则有一条**描述模板**（不是最终那句话）。
 *
 * <p>见 {@code probe-schedule-describer.mjs}：到点时描述会先经模型解析，
 * 落库的是解析后的那一句。所以下面那条"描述就是规则里那一份"的断言要靠
 * 替身在 {@code ok} 模式下返回的固定文本 —— 本探针开头把替身拨到那个模式，
 * 于是"描述"这件事变成一个确定的读数（而不是"取决于模型今天怎么说"）。
 */
const RESOLVED = '把昨天的失败运行汇总成一份晨报（昨天 = 2026-10-11）'

/** 规则里存的**模板**（带一个周期说法，好让"解析前后不同"这件事看得出来）。 */
const TEMPLATE = '把昨天的失败运行汇总成一份晨报，按失败原因归类'

const created = []
let failures = 0

function check(label, ok, detail = '') {
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ' — ' + detail : ''}`)
  if (!ok) failures++
}

async function call(method, path, body) {
  const init = { method }
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const response = await fetch(`${PATH}${path}`, init)
  const text = await response.text()
  let parsed = {}
  try { parsed = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: response.status, body: parsed, text }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

// ---------------------------------------------------------------------------
//  ① 预览：正则的判据与中文说法
// ---------------------------------------------------------------------------
console.log('--- ① 预览（只读）---')
for (const [cron, expectOk] of [
  ['0 0 9 ? * 1,2,3,4,5', true],
  ['0 0 9 * * *', true],
  ['0 0 0/4 * * *', true],
  ['*/2 * * * * *', true],
  ['0 9 * * 1', false],          // 5 段式：Unix crontab 的直觉写法
  ['0 0 9 1 * MON', false],      // 日 + 周同时具体值：解析成功但语义是"或"
  ['0 0 9 31 2 *', false],       // 2 月 31 号：解析成功但永远等不到
]) {
  const r = await call('POST', '/preview', { cron })
  const ok = expectOk ? r.status === 200 && r.body.nextRuns?.length === 5 : r.status === 400
  check(`预览 [${cron}]`, ok, r.status === 200
    ? `${r.body.description} | ${r.body.nextRuns?.[0]}`
    : String(r.body.message ?? r.text).slice(0, 60))
}

// ---------------------------------------------------------------------------
//  ② 建一条"每 2 秒"的规则，等它自己触发
// ---------------------------------------------------------------------------
console.log('--- ② 真的到点触发（每 2 秒）---')
// 把替身拨到 ok 模式：描述解析那一步要有个确定的返回，否则"到点真的派活了"会因为
// **模型没应答**而变成"这一次不派活"（那条断言就红得与调度无关了）
await fetch(`${FAKE}/__mode/ok`).catch(() => null)
const before = await (await fetch(`${BASE}/api/agent-tasks`)).json()
const beforeNew = before.columns.find(c => c.key === 'new')?.tasks.length ?? 0
console.log(`   建之前「新任务」列有 ${beforeNew} 条`)

const fast = await call('POST', '', {
  publisher: 'admin', assignee: 'admin', description: TEMPLATE,
  cron: '*/2 * * * * *', enabled: true,
})
check('建一条「每 2 秒」的规则', fast.status === 200, `HTTP ${fast.status} ${fast.body.rule?.cron ?? ''}`)
if (fast.status === 200) created.push(fast.body.rule.id)
const fastId = fast.body.rule?.id

check('新建的规则立刻挂进了调度线程池', fast.body.rule?.scheduled === true,
  `scheduled=${fast.body.rule?.scheduled}`)
check('★ `assigneeName` 是登录名、`assignee` 是姓名（两格不是同一个字符串）',
  fast.body.rule?.assigneeName === 'admin' && typeof fast.body.rule?.assignee === 'string',
  `assignee=${fast.body.rule?.assignee} / assigneeName=${fast.body.rule?.assigneeName}`)

const poolAfterCreate = (await call('GET', '')).body.pool
check('池子里多了一条规则', poolAfterCreate.scheduled >= 1, `scheduled=${poolAfterCreate.scheduled}`)

// 等它自己跑：每 2 秒一次，等 7 秒应该至少有 2 次
await sleep(7000)
const after = await (await fetch(`${BASE}/api/agent-tasks`)).json()
const afterNew = after.columns.find(c => c.key === 'new')?.tasks.length ?? 0
check('★ 到点真的生成了 agent 任务记录（不是靠人点）', afterNew > beforeNew,
  `「新任务」列 ${beforeNew} → ${afterNew}`)

const mine = (await call('GET', '')).body.rules.find(r => r.id === fastId)
check('★ 规则上数得出"派过几条"（`agent_task.schedule_id` 那一列真的写上了）', mine?.runs >= 2,
  `runs=${mine?.runs} lastRunAt=${mine?.lastRunAt}`)

// 那些任务的由来那一列写的是这条规则；**描述是解析后的那一句**（不是规则里的模板）
const newTasks = after.columns.find(c => c.key === 'new').tasks.filter(
  t => t.description.includes('晨报'))
check('派出来的任务是这条规则的产出', newTasks.length >= 2, `${newTasks.length} 条`)
check('★ 描述是**解析后**那一句（替身固定返回的那份），而不是规则里存的模板',
  newTasks.length > 0 && newTasks.every(t => t.description === RESOLVED),
  newTasks[0] ? newTasks[0].description : '(没读到)')
check('规则那一格仍然存着**模板原文**（解析只影响派出去的任务，不改规则）',
  mine?.description === TEMPLATE,
  JSON.stringify(mine?.description))

// ---------------------------------------------------------------------------
//  ③ 停用：线程被摘掉
// ---------------------------------------------------------------------------
console.log('--- ③ 停用 / 启用 ---')
const off = await call('POST', `/${fastId}/enabled`, { enabled: false })
check('停用成功，且 `nextRuns` 变成空数组（不是"算不出来"，是"它不会跑"）',
  off.status === 200 && off.body.rule?.enabled === false && off.body.rule?.nextRuns?.length === 0,
  `scheduled=${off.body.rule?.scheduled} nextRuns=${off.body.rule?.nextRuns?.length}`)
check('停用之后池子里少了一条', (await call('GET', '')).body.pool.scheduled === poolAfterCreate.scheduled - 1)

const countWhileOff = await (await fetch(`${BASE}/api/agent-tasks`)).json()
await sleep(5000)
const countAfterOff = await (await fetch(`${BASE}/api/agent-tasks`)).json()
/** 看板一次取全量：把每一列的条数加起来就是任务总数。 */
const totalOf = board => board.columns.reduce((sum, column) => sum + (column.tasks?.length ?? 0), 0)
check('★ 停用之后它**不再派活**（等 5 秒，任务总数没变）',
  totalOf(countWhileOff) === totalOf(countAfterOff),
  `${totalOf(countWhileOff)} → ${totalOf(countAfterOff)}`)

const on = await call('POST', `/${fastId}/enabled`, { enabled: true })
check('再启用：线程装回去、`nextRuns` 又有了', on.body.rule?.scheduled === true
  && on.body.rule?.nextRuns?.length === 5)

// ---------------------------------------------------------------------------
//  ④ 改正则 = 删旧线程、按新正则重加
// ---------------------------------------------------------------------------
console.log('--- ④ 改正则 ---')
const beforeUpdate = (await call('GET', `/${fastId}`)).body
// ⚠️ 这一次把 enabled 传 true：停用的规则**没有** nextRuns（它不在池子里），
// 而下面要断的正是"下一次按新正则算"——让它停着的话那一条断的是空气
const updated = await call('PUT', `/${fastId}`, {
  assignee: beforeUpdate.assigneeName, description: beforeUpdate.description,
  cron: '0 0 3 * * *', enabled: true,
})
check('★ `PUT` 认的是 `assigneeName`（登录名），改规则成功',
  updated.status === 200, `HTTP ${updated.status} ${updated.body.message ?? updated.text}`)
check('改完之后库里的正则逐字是提交的那一句', updated.body.rule?.cron === '0 0 3 * * *',
  `${updated.body.rule?.cron}`)
check('★ 下一次触发是按**新**正则算的（3:00 而不是每 2 秒）',
  updated.body.rule?.nextRuns?.[0]?.endsWith('03:00:00') === true,
  `${beforeUpdate.nextRuns?.[0]} → ${updated.body.rule?.nextRuns?.[0]}`)
check('改完之后回执与列表里都带着中文说法（它不落库，是服务层算出来的）',
  typeof updated.body.rule?.cronDescription === 'string' && updated.body.rule.cronDescription.includes('3:00'),
  `cronDescription=${updated.body.rule?.cronDescription}`)

// 拿姓名去提交要被挡下来（那正是"两格不是一个字符串"的证据）
const wrong = await call('PUT', `/${fastId}`, {
  assignee: updated.body.rule.assignee, description: updated.body.rule.description,
  cron: '0 0 3 * * *', enabled: true,
})
check('★ 拿**姓名**当登录名提交会被挡下来（>@code realUser} 那一层），而那句话说得清是账号不存在',
  updated.body.rule.assignee === updated.body.rule.assigneeName || wrong.status === 400,
  `assignee=${updated.body.rule.assignee} name=${updated.body.rule.assigneeName} → HTTP ${wrong.status}`)

// ---------------------------------------------------------------------------
//  ⑤ 删除：派过任务的删不掉
// ---------------------------------------------------------------------------
console.log('--- ⑤ 删除 ---')
const deleted = await call('DELETE', `/${fastId}`)
check('★ 已经派过任务的规则删不掉（那些任务还留着，而这条规则是它们唯一的由来）',
  deleted.status === 400 && String(deleted.body.message ?? '').includes('不能删'),
  `HTTP ${deleted.status} ${String(deleted.body.message ?? '').slice(0, 70)}`)

const emptied = await call('PUT', `/${fastId}`, {
  assignee: updated.body.rule.assigneeName, description: updated.body.rule.description,
  cron: '0 0 3 * * *', enabled: false,
})
check('（准备）把它留在停用态（不让它继续派活）', emptied.status === 200)

// ---------------------------------------------------------------------------
//  ⑥ 建一条"没派过任务"的规则，删得掉
// ---------------------------------------------------------------------------
console.log('--- ⑥ 没派过任务的规则可以删 ---')
const clean = await call('POST', '', {
  publisher: 'admin', assignee: 'admin', description: '探针：建了就删（不派活）',
  cron: '0 0 4 1 * ?', enabled: false,
})
check('建一条停用的规则', clean.status === 200 && clean.body.rule?.scheduled === false,
  `HTTP ${clean.status} nextRuns=${clean.body.rule?.nextRuns?.length}`)
if (clean.status === 200) {
  created.push(clean.body.rule.id)
  const gone = await call('DELETE', `/${clean.body.rule.id}`)
  check('一条都没派过的规则删得掉', gone.status === 200, `HTTP ${gone.status}`)
  check('删完之后列表里没有它了',
    !(await call('GET', '')).body.rules.some(r => r.id === clean.body.rule.id))
}

// ---------------------------------------------------------------------------
//  ⑦ 参数校验（400 那一族）
// ---------------------------------------------------------------------------
console.log('--- ⑦ 参数校验 ---')
const noPublisher = await call('POST', '', { assignee: 'admin', description: 'x', cron: '0 0 9 * * *' })
check('缺发布人是 400（发布人是必填的）', noPublisher.status === 400)
const ghost = await call('POST', '', {
  publisher: 'admin', assignee: '查无此人', description: 'x', cron: '0 0 9 * * *',
})
check('★ 任务人不在账号表里是 400（规则会在"触发的那一刻"失败，所以要在建的时候就拦）',
  ghost.status === 400 && String(ghost.body.message ?? '').includes('查无此人'),
  String(ghost.body.message ?? '').slice(0, 70))
const badCron = await call('POST', '', {
  publisher: 'admin', assignee: 'admin', description: 'x', cron: 'not a cron',
})
check('正则不合法是 400', badCron.status === 400)

// ---------------------------------------------------------------------------
//  收尾
// ---------------------------------------------------------------------------
console.log('--- 收尾 ---')
for (const id of created) {
  const r = await call('DELETE', `/${id}`)
  console.log(`   删 #${id} → HTTP ${r.status}`)
}
const finalPool = (await call('GET', '')).body.pool
console.log(`   收尾后池子：scheduled=${finalPool.scheduled} / 上限 ${finalPool.poolSize}`)
console.log(`   库里还剩 ${(await call('GET', '')).body.total} 条规则`)

console.log(failures === 0 ? '\n✅ 全部通过' : `\n❌ ${failures} 项失败`)
process.exit(failures === 0 ? 0 : 1)
