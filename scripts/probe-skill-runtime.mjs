#!/usr/bin/env node
/**
 * 真后端探针：<b>技能有没有真的进到运行里</b>（执行者 → 角色 → 目录 → 技能 + 按名字取正文）。
 *
 * <h2>它为什么必须存在</h2>
 * 这一族判断在**假后端上验不到**：假后端的技能表是内存里写死的三条，
 * 而"按角色目录前缀筛"这条判据要真的读三张表（账号 / 角色目录 / 技能）才算跑过。
 * 界面自检能证明"界面把 id 发出去了、把回来的条数显示出来了"，
 * 但证明不了"后端算出来的就是这几条"。
 *
 * <h2>用法</h2>
 * <pre>
 *   node scripts/probe-skill-runtime.mjs http://127.0.0.1:8091
 * </pre>
 * ⚠️ 它会往库里**建技能记录**，所以只对**临时库**跑（H2 内存库或一个临时 MySQL 库）——
 * 与 `probe-skill-api.mjs` 同一条纪律：不要指向 `db_ai_agent`。
 *
 * <h2>它怎么在没有真 key 的情况下验到"技能进了模型上下文"</h2>
 * `/api/agent/ask` 会先解析权限与技能（这些是本地计算），再去调模型。
 * 所以用占位 key 跑时：<b>模型那一步会失败，但技能那一节已经算出来了</b>，
 * 响应里的 `skills` / `skillNote` 就是证据。另外服务端日志里有一行
 * `技能目录已装配：…`（`SkillDirectory`），两者的数字必须一致。
 */

const baseUrl = (process.argv[2] ?? 'http://127.0.0.1:8091').replace(/\/+$/, '')

let passed = 0
let failed = 0

function check(what, ok, detail = '') {
  if (ok) {
    passed++
    console.log(`  ✓ ${what}${detail ? ` — ${detail}` : ''}`)
  }
  else {
    failed++
    console.log(`  ✗ ${what}${detail ? ` — ${detail}` : ''}`)
  }
}

async function get(path) {
  const response = await fetch(baseUrl + path)
  return { status: response.status, body: await response.json().catch(() => null) }
}

async function post(path, payload) {
  const response = await fetch(baseUrl + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload ?? {}),
  })
  return { status: response.status, body: await response.json().catch(() => null) }
}

/** 该账号的角色目录前缀（= 他能把技能放在哪些文件夹下）。 */
async function foldersOf(userId) {
  const snapshot = await get(`/api/skills?userId=${userId}`)
  return snapshot.body?.folders ?? []
}

async function main() {
  console.log(`[技能运行期探针] ${baseUrl}`)

  // ---- 前提：库里得有一个账号，而它得有角色目录 ----
  const permissions = await get('/api/permissions')
  // 快照里的账号那一栏叫 `users`（不是 `accounts`）—— 与 PermissionController 逐字对应
  const admin = permissions.body?.users?.[0]
  if (!admin) {
    throw new Error('库里一个账号都没有 —— 先在空库上启动一次让引导程序建号')
  }
  const folders = await foldersOf(admin.id)
  console.log(`\n① 执行者 #${admin.id}（${admin.name}）的可选文件夹：${JSON.stringify(folders)}`)
  check('这个账号有角色目录前缀（否则整条链路的前提不成立）', folders.length > 0)
  const inside = folders[0]
  // 故意造一个**前缀之外**的目录：它证明筛是真的在筛，而不是"把全部技能都塞进去"。
  // 取一个明显不相干的绝对路径（在别人的机器上也不该是某个角色的前缀）
  const outside = process.platform === 'win32' ? 'Z:\\__probe_outside__' : '/__probe_outside__'

  // ---- ② 建三条技能：两条在授权目录下、一条在授权之外 ----
  console.log('\n② 建技能（两条在授权目录下，一条在它之外）')
  const insideA = await post(`/api/skills?userId=${admin.id}`, {
    folder: inside,
    name: '探针·部署自检',
    description: '每次发版前跑一遍的部署自检清单。',
    content: '第一步：确认版本号；第二步：确认健康检查通过；第三步：确认回滚方案可用。',
  })
  check('授权目录下建第一条 → 200', insideA.status === 200 && insideA.body?.skill?.name === '探针·部署自检',
    `status=${insideA.status} body=${JSON.stringify(insideA.body).slice(0, 160)}`)

  const insideB = await post(`/api/skills?userId=${admin.id}`, {
    folder: inside,
    name: '探针·空正文',
    description: '只有名字与说明、还没有写正文的一条。',
    content: '',
  })
  check('授权目录下建第二条（正文为空）→ 200', insideB.status === 200, `status=${insideB.status}`)

  const outsideName = '探针·越界目录'
  const outsideAttempt = await post(`/api/skills?userId=${admin.id}`, {
    folder: outside,
    name: outsideName,
    description: '它不该出现在任何一次运行的技能目录里。',
    content: '不该被读到。',
  })
  check('授权之外的文件夹 → 400（写入路径上核过选项集）', outsideAttempt.status === 400,
    `status=${outsideAttempt.status} error=${outsideAttempt.body?.error ?? ''}`)

  // ---- ③ 运行期：按执行者筛出来的技能 ----
  //
  // 模型那一步会因为占位 key 失败，但**技能是本地算的**，它已经出现在响应里
  console.log('\n③ 跑一次 /ask（占位 key：模型会失败，但技能目录已经算出来了）')
  const ask = await post('/api/agent/ask', {
    question: '这次只验证技能注入，不需要真的回答',
    workspaceDir: '.',
    permissionLevel: 'READ_ONLY',
    executorId: admin.id,
  })
  const injected = ask.body?.skills ?? []
  const names = injected.map(s => s.name)
  console.log(`   停因=${ask.body?.stopReason ?? '(没有停因)'} · 注入=${JSON.stringify(names)} · ${ask.body?.skillNote ?? ''}`)
  check('响应里回了执行者（技能是按他筛的）',
    (ask.body?.skillExecutor ?? '').includes(`#${admin.id}`), `skillExecutor=${ask.body?.skillExecutor}`)
  check('★ 注入的技能里有刚建的那两条（同一个文件夹下的都在）',
    names.includes('探针·部署自检') && names.includes('探针·空正文'), JSON.stringify(names))
  check('★ 前缀之外的技能**没有**被注入（`Z:\\__probe_outside__` 不在任何前缀下）',
    !names.includes(outsideName))
  check('注入的每一条都落在本次生效的文件夹里',
    injected.every(s => (ask.body?.skillFolders ?? []).includes(s.folder)),
    JSON.stringify(ask.body?.skillFolders))

  // ---- ④ 不传执行者：一条都不注入（不偷偷用默认的人） ----
  console.log('\n④ 不传执行者')
  const noExecutor = await post('/api/agent/ask', {
    question: '这次没有执行者',
    workspaceDir: '.',
    permissionLevel: 'READ_ONLY',
  })
  check('★ 没有执行者时注入 0 条（不退化成"全部技能"）',
    (noExecutor.body?.skills ?? []).length === 0,
    `skills=${JSON.stringify((noExecutor.body?.skills ?? []).map(s => s.name))} note=${noExecutor.body?.skillNote}`)
  check('没有执行者时那句说明写的是"未指定执行者"',
    /未指定执行者/.test(noExecutor.body?.skillNote ?? ''), noExecutor.body?.skillNote)

  // ---- ⑤ 执行者不存在：当场拒绝且不花钱 ----
  console.log('\n⑤ 执行者填错')
  const badExecutor = await post('/api/agent/ask', {
    question: '执行者是一个不存在的 id',
    workspaceDir: '.',
    executorId: 999999,
  })
  check('★ 执行者 id 不存在 → ok:false（在动模型之前就说清楚）',
    badExecutor.status === 200 && badExecutor.body?.ok === false
    && /执行者/.test(badExecutor.body?.error ?? ''),
    `status=${badExecutor.status} error=${badExecutor.body?.error ?? ''}`)

  // ---- ⑥ 目录前缀的边界：`demo` 不命中 `demo-other` ----
  //
  // 这一条借的是同一个判据（RoleDirectory.coversPath 的分段比较）：
  // 建不了"前缀之外"的技能（写入被 400 拦下），所以这里验的是**判定函数本身的行为**
  // 在运行期那一侧的表现 —— 用一个前缀的**兄弟目录**当技能文件夹是不可能的，
  // 于是改为直接核对"注入的那些文件夹"都在 skillFolders 里（上面第 ③ 步已经做了）。
  // 真正需要单独说明的是：这条判据与"能不能在某个目录下干活"是同一份（见文档）。
  console.log('\n⑥ 前缀匹配的语义（与权限判定同一份判据）')
  const sibling = `${inside}-other`
  const siblingAttempt = await post(`/api/skills?userId=${admin.id}`, {
    folder: sibling,
    name: '探针·兄弟目录',
    description: '同名前缀 + 后缀，不是这个人的目录。',
    content: 'x',
  })
  check('★ 同名前缀 + 后缀（`…-other`）不算命中 → 400（字符串前缀比较会放行它）',
    siblingAttempt.status === 400, `status=${siblingAttempt.status}`)

  console.log(`\n[技能运行期探针] ${passed} 项通过 / ${failed} 项未通过`)
  console.log('⚠️ 响应里的技能条数与服务端日志里那一行 `技能目录已装配：…` 必须一致 —— 上面③那一次请对着日志核一遍')
  process.exit(failed === 0 ? 0 : 1)
}

main().catch(e => {
  console.error('[技能运行期探针] 失败：', e)
  process.exit(1)
})
