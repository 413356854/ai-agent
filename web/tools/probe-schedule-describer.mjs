/**
 * 一次性探针：<b>定时任务到点时把描述模板解析成"当下这么说"</b>那条链路，打真后端。
 *
 * <h2>它要回答的三个问题</h2>
 * <ol>
 *   <li><b>解析真的发生了吗</b> —— 规则里那句是"把昨天的失败运行汇总成晨报"，
 *       派出来的任务描述里应该已经是具体日期；</li>
 *   <li><b>提示词里那份时间表是真的吗</b> —— 探针自己按同一个时区算一遍"昨天"，
 *       然后断言模型的入参里带着那个日期（这条链路的可核对性全在这里：日期由应用算好、
 *       模型只负责照抄）；</li>
 *   <li><b>模型调不通时真的不派活吗</b> —— 把替身切成失败，再触发一次，
 *       任务总数必须一点没变。</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   node tools/fake-openai.mjs                       # 终端 A：假模型 → 8098
 *   （后端指向它启动）mvn spring-boot:run -Dspring-boot.run.arguments=
 *       "--server.port=8099 --spring.ai.openai.base-url=http://127.0.0.1:8098"
 *   node tools/probe-schedule-describer.mjs          # 终端 C：本探针
 * </pre>
 *
 * ⚠️ 它**会写库**（建规则、派真任务），只对本机开发库用；收尾会把建的规则删掉。
 * ⚠️ 它**不会**让那条任务跑起来：任务落在「新发布」，而执行那一步要真模型
 * （替身只会返回那句描述，跑不动工具）—— 本探针只验"描述是怎么来的"。
 */
const BASE = process.env.BASE ?? 'http://127.0.0.1:8099'
const FAKE = process.env.FAKE ?? 'http://127.0.0.1:8098'
const SCHEDULES = `${BASE}/api/agent-task-schedules`
const ZONE = process.env.ZONE ?? 'Asia/Shanghai'

let failures = 0
const created = []

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
  const response = await fetch(`${SCHEDULES}${path}`, init)
  const text = await response.text()
  let parsed = {}
  try { parsed = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: response.status, body: parsed, text }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

/** 探针自己按同一个时区算"昨天"—— 用来核对提示词里那一行是不是真的算对了。 */
function yesterdayIn(zone) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date(Date.now() - 24 * 3600 * 1000))
  return parts // en-CA 给的就是 yyyy-mm-dd
}

async function fakeMode(mode) {
  await fetch(`${FAKE}/__mode/${mode}`)
}
/** 清掉替身的调用记录。**与切模式分开**：探针开头会切一次模式，而它随后要断言的正是
 *  "这一次运行里模型收到了什么" —— 切模式时顺手清账的话，那份记录在断言之前就被自己抹掉了。 */
async function fakeReset() {
  await fetch(`${FAKE}/__reset`)
}
async function fakeCalls() {
  return (await (await fetch(`${FAKE}/__calls`)).json())
}

async function boardTotal() {
  const board = await (await fetch(`${BASE}/api/agent-tasks`)).json()
  return board.columns.reduce((sum, column) => sum + (column.tasks?.length ?? 0), 0)
}

const TEMPLATE = '把昨天的失败运行汇总成一份晨报，按失败原因归类'

// ---------------------------------------------------------------------------
//  ① 建一条"每 2 秒"的规则，模板里带周期说法
// ---------------------------------------------------------------------------
console.log('--- ① 建规则（描述是模板，带「昨天」）---')
await fakeMode('ok')
await fakeReset()   // 读数只属于本次运行（替身在别的探针/判定里也会被调到）
const before = await boardTotal()
const rule = await call('POST', '', {
  publisher: 'admin', assignee: 'admin', description: TEMPLATE, cron: '*/2 * * * * *', enabled: true,
})
check('建一条「每 2 秒」的规则（描述里带「昨天」）', rule.status === 200,
  `HTTP ${rule.status} ${rule.body.rule?.cron ?? ''}`)
const ruleId = rule.body.rule?.id
if (ruleId) created.push(ruleId)
check('规则里存的就是那句模板（没被写入路径改过）', rule.body.rule?.description === TEMPLATE,
  JSON.stringify(rule.body.rule?.description))

// ---------------------------------------------------------------------------
//  ② 等它自己触发，读那条任务的描述
// ---------------------------------------------------------------------------
console.log('--- ② 到点触发之后，任务的描述是不是"当下这么说" ---')
const expectedYesterday = yesterdayIn(ZONE)

/**
 * 建规则之前**已经存在**的那些任务 id。
 *
 * <p>⚠️ 判据必须落在"**这一次新出现的**任务"上：库里可能躺着上一轮探针留下的任务，
 * 而它们的描述**已经是解析后的样子**了 —— 只按"描述里有没有晨报"去找的话，
 * 会一把抓到那张旧卡片、于是这一轮真正派出来的活根本没被读
 * （实测踩过：② 绿着，而 ③ 里 `__calls` 是 0，看起来像"模型没被调"）。
 */
const existingIds = new Set(
  (await (await fetch(`${BASE}/api/agent-tasks`)).json()).columns
    .flatMap(c => c.tasks).map(t => t.id))

let dispatched = null
for (let i = 0; i < 20 && !dispatched; i++) {
  await sleep(1200)
  const board = await (await fetch(`${BASE}/api/agent-tasks`)).json()
  // 只看本次运行新派出来的那些；其中"仍是模板原文"的那一种也要接住 ——
  // 它说明解析那一步没生效，而那正是下面要红着报出来的事
  dispatched = board.columns.flatMap(c => c.tasks).find(t => !existingIds.has(t.id))
}
check('★ 到点真的派出了任务', !!dispatched, dispatched ? `#${dispatched.id}` : '等了 24 秒没等到')
check('★ 任务的描述**不再是模板原文**（周期说法被换掉了）',
  !!dispatched && dispatched.description !== TEMPLATE,
  dispatched ? dispatched.description : '')
check(`★ 描述里带着"昨天"对应的那个具体日期（${expectedYesterday}）`,
  !!dispatched && dispatched.description.includes(expectedYesterday),
  dispatched ? dispatched.description : '')

// ---------------------------------------------------------------------------
//  ③ 模型收到的提示词里，那份时间表是真的（日期由应用算好、模型只照抄）
// ---------------------------------------------------------------------------
console.log('--- ③ 提示词里那份时间表 ---')
//
// ⚠️ **不能拿"最后一次调用"当描述解析**：任务一旦落库，agent 任务执行器会立刻接手去判需求，
// 而判定走的是同一个模型端点 —— 于是 `calls.at(-1)` 十有八九是**判定**那一次
// （实测就是这么错的：断言读到的 system 是「你是『agent 任务』的需求分析器」）。
// 判据用一个只有描述解析才会带上的记号：用户侧提示词里那段 `<描述>` 标记。
const allCalls = (await fakeCalls()).calls
const describerCalls = allCalls.filter(c => c.user.includes('<描述>'))
const call1 = describerCalls.at(-1)
check('★ 描述解析这一步真的调了模型（在派活之前）', !!call1,
  `描述解析 ${describerCalls.length} 次 / 全部 ${allCalls.length} 次（其余是任务判定）`)
check('★ 系统提示词是库里那一份 task-schedule（受版本管理的那一份）',
  !!call1 && call1.system.includes('描述解析器'),
  call1 ? call1.system.slice(0, 30).replace(/\n/g, ' ') : '')
check(`★ 用户侧提示词里写着算好的"昨天 = ${expectedYesterday}"（不是让模型自己推）`,
  !!call1 && call1.user.includes(`昨天 = ${expectedYesterday}`),
  call1 ? (call1.user.split('\n').find(l => l.includes('昨天 =')) ?? '(没有这一行)') : '')
check('提示词里带着"要改写的描述"那一段，且是模板原文',
  !!call1 && call1.user.includes(TEMPLATE))
check('改写用的温度是 0（同一句描述在同一天应给同一个结果）',
  call1?.temperature === 0, `temperature=${call1?.temperature}`)
check('输出带了明确的 token 上界（产物是一句话，不占默认那一档额度）',
  typeof call1?.maxTokens === 'number' && call1.maxTokens > 0, `max_tokens=${call1?.maxTokens}`)

// ---------------------------------------------------------------------------
//  ④ 围栏会被剥掉（真模型经常把整句包在 ``` 里）
// ---------------------------------------------------------------------------
console.log('--- ④ 输出被 markdown 围栏包着时 ---')
check('★ 描述里没有留下 ``` 围栏（围栏留着会让那条任务读起来像坏了）',
  !!dispatched && !dispatched.description.includes('```'),
  dispatched ? dispatched.description.slice(0, 60) : '')

// ---------------------------------------------------------------------------
//  ⑤ 手工触发走的是同一套解析
// ---------------------------------------------------------------------------
console.log('--- ⑤ 界面上的「现在跑一次」也要解析 ---')
await sleep(2500)   // 让"每 2 秒"那条先安静下来，避免把它的产出当成手工那一次的
const beforeManual = await boardTotal()
const manual = await call('POST', `/${ruleId}/trigger`, {})
check('手工触发成功（HTTP 200）', manual.status === 200, `HTTP ${manual.status} ${manual.body.message ?? manual.text}`)
const manualTask = manual.body.taskId
const manualBoard = await (await fetch(`${BASE}/api/agent-tasks`)).json()
const manualCard = manualBoard.columns.flatMap(c => c.tasks).find(t => t.id === manualTask)
check('★ 手工派出来的那条任务，描述也是解析后的那句（不是模板原文）',
  !!manualCard && manualCard.description !== TEMPLATE && manualCard.description.includes(expectedYesterday),
  manualCard ? manualCard.description : '(没读到)')
check('任务总数确实多了一条（手工这一次真的派了活）',
  (await boardTotal()) > beforeManual)

// ---------------------------------------------------------------------------
//  ⑥ 模型调不通 → 这一次不派活
// ---------------------------------------------------------------------------
console.log('--- ⑥ 模型调不通时：不派活 ---')
await call('POST', `/${ruleId}/enabled`, { enabled: false })   // 先停掉那条每 2 秒的
await sleep(3000)
await fakeMode('fail')
const beforeFail = await boardTotal()
const failManual = await call('POST', `/${ruleId}/trigger`, {})
check('★ 手工触发时模型失败 → HTTP 502（不是假成功、也不是 400）',
  failManual.status === 502, `HTTP ${failManual.status} ${String(failManual.body.error ?? '')}`)
check('★ 响应体里那句话说清了"这一次没派活、下一个周期会重试"',
  /这一次没有派活/.test(String(failManual.body.message ?? ''))
  && /重试/.test(String(failManual.body.message ?? '')),
  String(failManual.body.message ?? '(空的)').slice(0, 90))
check('响应体里另给了一格技术原因（给排障的人看，与给用户看的那句话分开）',
  typeof failManual.body.cause === 'string' && failManual.body.cause.length > 0,
  String(failManual.body.cause ?? '').slice(0, 70))
await sleep(1000)
check('★ 任务总数一点没变（失败时**不派活**，而不是拿模板原文派出去）',
  (await boardTotal()) === beforeFail, `${beforeFail} → ${await boardTotal()}`)

// 到点触发那一条路：重新启用（模式仍是失败），等两个周期，任务数也应当不变
await call('POST', `/${ruleId}/enabled`, { enabled: true })
const beforeTickFail = await boardTotal()
await sleep(6000)
check('★ 到点触发遇到模型失败也不派活（它留到下一个周期重试）',
  (await boardTotal()) === beforeTickFail, `${beforeTickFail} → ${await boardTotal()}`)

// ---------------------------------------------------------------------------
//  ⑦ 模板里没有时间说法 → 原样返回（不编、不改）
// ---------------------------------------------------------------------------
console.log('--- ⑦ 模板里没有时间说法时原样返回 ---')
await fakeMode('echo')
const plain = '把成本看板补一条"算不出来"的说明'
const plainRule = await call('POST', '', {
  publisher: 'admin', assignee: 'admin', description: plain, cron: '*/2 * * * * *', enabled: true,
})
if (plainRule.status === 200) created.push(plainRule.body.rule.id)
const plainManual = await call('POST', `/${plainRule.body.rule.id}/trigger`, {})
check('（准备）再建一条描述里没有时间说法的规则并手工触发', plainManual.status === 200,
  `HTTP ${plainManual.status}`)
const plainBoard = await (await fetch(`${BASE}/api/agent-tasks`)).json()
const plainCard = plainBoard.columns.flatMap(c => c.tasks).find(t => t.id === plainManual.body.taskId)
check('★ 描述与模板逐字相同（模型原样返回时不做任何加工：不补日期、不加解释）',
  !!plainCard && plainCard.description === plain, plainCard ? Json(plainCard.description) : '(没读到)')

function Json(text) { return JSON.stringify(text) }

// ---------------------------------------------------------------------------
//  收尾
// ---------------------------------------------------------------------------
console.log('--- 收尾 ---')
await fakeMode('ok')
for (const id of created) {
  await call('POST', `/${id}/enabled`, { enabled: false })
  // 派过任务的删不掉（那是刻意的限制），所以只停用；库里的探针规则由 SQL 清
  console.log(`   规则 #${id} 已停用`)
}
console.log(failures === 0 ? '\n✅ 全部通过' : `\n❌ ${failures} 项失败`)
console.log('（请用 SQL 清掉探针规则：DELETE FROM agent_task_schedule;）')
process.exit(failures === 0 ? 0 : 1)
