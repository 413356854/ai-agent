/**
 * 一次性探针（不属于门禁）：对**真后端**逐个核对 /api/skills 的判据。
 *
 * 用法：先起后端（8080/8099 均可），再 `node scripts/probe-skill-api.mjs <baseUrl>`。
 * 它只往临时库（H2 内存 / 文件库）里写 —— **不要指向 db_ai_agent**。
 */
const BASE = process.argv[2] ?? 'http://127.0.0.1:8099'

let pass = 0
const fails = []
const check = (label, ok, detail = '') => {
  if (ok) { pass++; console.log(`  ✓ ${label}${detail ? ` — ${detail}` : ''}`) }
  else { fails.push(label); console.log(`  ✗ ${label}${detail ? ` — ${detail}` : ''}`) }
}

async function call(method, path, body) {
  const init = { method }
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const res = await fetch(`${BASE}${path}`, init)
  let parsed = null
  try { parsed = await res.json() } catch { /* 空体 */ }
  return { status: res.status, body: parsed }
}

const msg = r => String(r.body?.message ?? r.body?.error ?? '')

// ---- 账号：admin（引导程序建的，角色「管理员」有目录前缀）+ 一个没有任何角色的账号 ----
const users = (await call('GET', '/api/permissions')).body.users
const admin = users.find(u => u.name === 'admin')
console.log(`账号：admin id=${admin.id} roles=${JSON.stringify(admin.roles)}`)

const unique = `probe_${Date.now().toString(36)}`
const noRole = await call('POST', '/api/permissions/users', { name: unique, password: 'p@ss-1234' })
const noRoleId = noRole.body.user.id
console.log(`另建一个没有任何角色的账号：#${noRoleId}（${unique}）`)

console.log('\n[1] 未登录（不传 userId）')
const anonymous = await call('GET', '/api/skills')
check('200 且 userId=null', anonymous.status === 200 && anonymous.body.userId === null, JSON.stringify({ status: anonymous.status, userId: anonymous.body.userId }))
check('ownershipJudged=false', anonymous.body.ownershipJudged === false)
check('folders=[]（没有他，就没有这一份范围）', Array.isArray(anonymous.body.folders) && anonymous.body.folders.length === 0)

const anonymousWrite = await call('POST', '/api/skills', { folder: 'D:\\x', name: 'n' })
check('未登录写 → 400', anonymousWrite.status === 400, msg(anonymousWrite).slice(0, 60))
check('那句话说明去哪儿登录', /模拟登录/.test(msg(anonymousWrite)))

console.log('\n[2] admin 的文件夹选项集 = 它角色的目录前缀')
const mine = await call('GET', `/api/skills?userId=${admin.id}`)
const folders = mine.body.folders
check('folders 非空', folders.length > 0, JSON.stringify(folders))
check('ownershipJudged=true', mine.body.ownershipJudged === true)
check('userName 是显示名', mine.body.userName === (admin.displayName || admin.name), mine.body.userName)
check('一开始一条都没有', mine.body.total === 0, `total=${mine.body.total}`)
const folder = folders[0]

console.log('\n[3] 新建：拥有者与最后修改人由服务端填')
const created = await call('POST', `/api/skills?userId=${admin.id}`, {
  folder, name: '探针技能', description: '第一版说明', content: '第一版正文',
})
check('200', created.status === 200, `HTTP ${created.status} ${msg(created).slice(0, 60)}`)
const skill = created.body.skill
check('folder 存的是选项集里的原文', skill.folder === folder, skill.folder)
check('ownerId = 当前登录人', skill.ownerId === admin.id, `ownerId=${skill.ownerId}`)
check('lastModifiedBy = 当前登录人（新建时就写创建人）', skill.lastModifiedBy === admin.id, `lastModifiedBy=${skill.lastModifiedBy}`)
check('ownerName 是姓名', skill.ownerName === (admin.displayName || admin.name), skill.ownerName)
check('createdAt / updatedAt 都有值', !!skill.createdAt && !!skill.updatedAt, `${skill.createdAt} / ${skill.updatedAt}`)
const impostor = await call('POST', `/api/skills?userId=${admin.id}`, {
  folder, name: '冒名技能', ownerId: 999, lastModifiedBy: 999,
})
check('请求体里塞 ownerId / lastModifiedBy 不被采纳', impostor.body.skill.ownerId === admin.id,
  `ownerId=${impostor.body.skill?.ownerId}`)

// 从这一条之后的条数断言一律按**相对**比较：写死数字的话，"多建了一条探针记录"就会
// 变成一串看起来像筛选坏了的假阴性（第一版就是这么红的）
const afterCreate = (await call('GET', `/api/skills?userId=${admin.id}`)).body.total

console.log('\n[4] 唯一键 (owner, folder, name)')
const dup = await call('POST', `/api/skills?userId=${admin.id}`, { folder, name: '探针技能' })
check('同一个人 + 同一个文件夹 + 同名 → 409', dup.status === 409, `HTTP ${dup.status} ${msg(dup).slice(0, 60)}`)
const second = await call('POST', `/api/skills?userId=${admin.id}`, { folder, name: '探针技能二' })
check('换个名字 → 200', second.status === 200, `HTTP ${second.status}`)
const caseVariant = await call('POST', `/api/skills?userId=${admin.id}`, { folder: folder.toLowerCase(), name: '大小写变体' })
check('文件夹大小写不同 → 200，且存的是选项集里的原文',
  caseVariant.status === 200 && caseVariant.body.skill.folder === folder,
  `HTTP ${caseVariant.status} · ${caseVariant.body?.skill?.folder}`)
if (caseVariant.status === 200) {
  await call('DELETE', `/api/skills/${caseVariant.body.skill.id}?userId=${admin.id}`)
}
const caseDup = await call('POST', `/api/skills?userId=${admin.id}`, { folder: folder.toLowerCase(), name: '探针技能' })
check('大小写不同的同名 → 409（归一之后撞唯一键）', caseDup.status === 409, `HTTP ${caseDup.status} ${msg(caseDup).slice(0, 60)}`)

console.log('\n[5] 文件夹必须在自己的选项集里')
const outside = await call('POST', `/api/skills?userId=${admin.id}`, { folder: 'Z:\\__probe_outside__', name: '越界' })
check('越界 → 400', outside.status === 400, `HTTP ${outside.status}`)
check('报错里带着可选项', msg(outside).includes(folder), msg(outside).slice(0, 90))
const afterOutside = await call('GET', `/api/skills?userId=${admin.id}`)
check('越界那一次没有留下记录', afterOutside.body.total === afterCreate + 1,
  `${afterCreate} → ${afterOutside.body.total}（多出来的那一条是「探针技能二」）`)
const noFolder = await call('POST', `/api/skills?userId=${admin.id}`, { name: '没有文件夹' })
check('缺 folder → 400 且报错里带可选清单', noFolder.status === 400 && msg(noFolder).includes(folder), `HTTP ${noFolder.status}`)

console.log('\n[6] 没有角色的账号：一条目录前缀都没有')
const emptyFolders = await call('GET', `/api/skills?userId=${noRoleId}`)
check('folders=[]（他的角色一个目录前缀都没配）', emptyFolders.body.folders.length === 0)
const noFolderWrite = await call('POST', `/api/skills?userId=${noRoleId}`, { folder, name: '借用别人的文件夹' })
check('拿别人的文件夹建 → 400（选项集为空，一个都不放行）', noFolderWrite.status === 400, `HTTP ${noFolderWrite.status}`)

console.log('\n[7] 归属：改 / 删只对自己的记录有效')
const mine2 = await call('GET', `/api/skills?userId=${admin.id}`)
const target = mine2.body.skills.find(s => s.name === '探针技能')
const stealPut = await call('PUT', `/api/skills/${target.id}?userId=${noRoleId}`, { name: '改别人的' })
check('改别人的 → 400', stealPut.status === 400, `HTTP ${stealPut.status}`)
check('理由里写着"只能修改 / 删除自己拥有的技能"', /只能修改 \/ 删除自己拥有的技能/.test(msg(stealPut)))
const stealDelete = await call('DELETE', `/api/skills/${target.id}?userId=${noRoleId}`)
check('删别人的 → 400', stealDelete.status === 400, `HTTP ${stealDelete.status}`)
check('那一条一动没动', (await call('GET', '/api/skills')).body.skills.some(s => s.id === target.id))
const notFound = await call('PUT', '/api/skills/999999?userId=1', { name: 'x' })
check('不存在的 id → 400', notFound.status === 400, msg(notFound).slice(0, 50))
const badUser = await call('GET', '/api/skills?userId=999999')
check('userId 不在账号表里 → 400（不是"筛出来是空的"）', badUser.status === 400, msg(badUser).slice(0, 50))

console.log('\n[8] 改：字段缺席 = 不动，空串 = 清空')
const onlyContent = await call('PUT', `/api/skills/${target.id}?userId=${admin.id}`, { content: '第二版正文' })
check('只送 content → 200', onlyContent.status === 200, `HTTP ${onlyContent.status}`)
check('description 保持原值（缺席 = 不动这一列）', onlyContent.body.skill.description === '第一版说明',
  JSON.stringify(onlyContent.body.skill.description))
check('folder / name 保持原值', onlyContent.body.skill.folder === folder && onlyContent.body.skill.name === '探针技能')
check('ownerId 不变（编辑不转移归属）', onlyContent.body.skill.ownerId === admin.id)
check('lastModifiedBy 是当前登录人', onlyContent.body.skill.lastModifiedBy === admin.id)
const cleared = await call('PUT', `/api/skills/${target.id}?userId=${admin.id}`, { description: '' })
check('送空串 → 那一列被清空（空串 = 清空）', cleared.body.skill.description === '' || cleared.body.skill.description === null,
  JSON.stringify(cleared.body.skill.description))
check('正文没有被顺手清掉', cleared.body.skill.content === '第二版正文', JSON.stringify(cleared.body.skill.content))
const renameDup = await call('PUT', `/api/skills/${target.id}?userId=${admin.id}`, { name: '探针技能二' })
check('改成同一个文件夹下已有的名字 → 409', renameDup.status === 409, `HTTP ${renameDup.status}`)

console.log('\n[9] 列表排序与筛')
const all = await call('GET', '/api/skills')
const adminIds = new Set((await call('GET', `/api/skills?userId=${admin.id}`)).body.skills.map(s => s.id))
check('不传 userId 时给的是全部记录（含别人的；库里此刻只有 admin 的，所以两者相等）',
  all.body.skills.every(s => adminIds.has(s.id)), JSON.stringify(all.body.skills.map(s => s.id)))
check('按 userId 筛出来的都是他的', (await call('GET', `/api/skills?userId=${admin.id}`)).body
  .skills.every(s => s.ownerId === admin.id))
const noRoleList = await call('GET', `/api/skills?userId=${noRoleId}`)
check('没有角色的账号看不到别人的记录（他自己的是空的）', noRoleList.body.total === 0, `total=${noRoleList.body.total}`)
const ids = all.body.skills.map(s => s.id)
check('最近改过的排在最前', ids[0] === target.id, JSON.stringify(ids))

console.log('\n[10] 删：回执里带被删掉的那一份')
const removed = await call('DELETE', `/api/skills/${target.id}?userId=${admin.id}`)
check('200', removed.status === 200, `HTTP ${removed.status}`)
check('回执里带 skill（名字没丢）', removed.body.skill?.name === '探针技能', JSON.stringify(removed.body.skill?.name))
check('库里那一条真的没了', !(await call('GET', '/api/skills')).body.skills.some(s => s.id === target.id))
const removedAgain = await call('DELETE', `/api/skills/${target.id}?userId=${admin.id}`)
check('再删同一条 → 400（已经不在了）', removedAgain.status === 400, `HTTP ${removedAgain.status}`)

// 自清：把探针建的两条技能删掉、把那个临时账号停用（账号只停用不删除）
for (const s of (await call('GET', '/api/skills')).body.skills) {
  if (s.name.startsWith('探针技能') || s.name.startsWith('大小写') || s.name.startsWith('冒名')) {
    await call('DELETE', `/api/skills/${s.id}?userId=${s.ownerId}`)
  }
}
await call('PUT', `/api/permissions/users/${noRoleId}`, { displayName: unique, enabled: false })

console.log(`\n${fails.length === 0 ? '✅' : '❌'} ${pass} 项通过、${fails.length} 项未通过`)
if (fails.length > 0) {
  console.log('未通过：')
  for (const f of fails) console.log(`  - ${f}`)
  process.exit(1)
}
