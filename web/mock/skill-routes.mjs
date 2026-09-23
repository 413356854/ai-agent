/**
 * skill 管理端点的**假数据**（应用管理 · skill管理）。
 *
 * ---------------------------------------------------------------------------
 * 与 permission-routes / task-routes 同一条纪律：**有写端点，就必须有 __reset**
 * ---------------------------------------------------------------------------
 * 这一组有三个写端点（新建 / 修改 / 删除），界面自检要验的正是"点一下之后表格真的多了一行、
 * 那一行的归属真的写的是当前登录的人"。所以状态是**进程内可变**的，
 * 并且必须提供 `POST /api/skills/__reset` —— 否则自检跑第二遍时会继承第一遍建出来的技能
 * （与 prompt-routes 的 `__reset`、permission-routes 的 `__reset` 是同一条经验）。
 *
 * ---------------------------------------------------------------------------
 * ★ 假数据的判据照搬真后端，而不是"随便返回个成功"
 * ---------------------------------------------------------------------------
 * 这一页最需要被验证的三个分支都是**失败**或**边界**分支：
 *   1. **未登录**（不传 `?userId=`）→ 200 给全部记录、`folders` 为空，
 *      而三个写端点回 **400** 并说清"先去登录"。假后端不实现的话，
 *      "未登录时按钮禁用 + 那句话显示出来"这件事在自检里永远走不到；
 *   2. **文件夹越界** → 400 且**报错里带可选项**（与真后端 `inFolderSet` 同一句话）——
 *      没有它，下一个文件夹还会填错；而"下拉框拦住了"这件事只在下拉里成立，
 *      curl 打一次就绕过去了，所以自检要按接口打一次；
 *   3. **不是他的记录** → 400（改 / 删都判）—— 归属判据是那一行的 `owner_id`，
 *      不是"请求里说的谁"。假后端放行的话，"只有拥有者能改"这条在自检里是假绿。
 * 另外同名唯一键（同一个人的同一个文件夹下）回 **409**：与真后端
 * `uk_agent_skill_owner_folder_name` 同一条判据，界面上的下一步动作也是照着这个码分的。
 *
 * ---------------------------------------------------------------------------
 * ★ 文件夹选项集读的是 permission-routes 那一份角色数据（账号表只有一份）
 * ---------------------------------------------------------------------------
 * `folders`（登录人能选的文件夹）= 他的角色 → 各角色的目录前缀。
 * 那份数据只有一份来源（`mockPermissionsOfId`），两个模块各写一份的话，
 * "假后端说这个人有这个目录、真端点说没有"只会表现为自检里一句看不懂的 400 ——
 * 与 task-routes 读同一份账号表是同一条理由。
 *
 * ---------------------------------------------------------------------------
 * 初始数据刻意覆盖三种情况
 * ---------------------------------------------------------------------------
 *   1. **同一个人有两条**（admin 两条，都在 `D:\work\demo` 下）→ "按拥有者筛"筛出来的是一批，不是一条；
 *   2. **另一个人有一条**（zhou，他没有角色 → 没有目录前缀）→ 登录 admin 时那一条**不该出现**，
 *      而它证明"筛"是真的在筛（一条都不剩与筛成空在界面上是两件事）；
 *   3. **有一条的说明为空** → 界面上必须显示「（未填说明）」，而不是一格空白
 *      （空白会被读成"这一页没读出来"）。
 */

import { findMockAccountById, mockPermissionsOfId } from './permission-routes.mjs'

/** 初始状态。每次 __reset 都从这里重建（深拷贝，避免被就地改坏）。 */
function initialState() {
  return {
    skills: [
      {
        id: 1,
        folder: 'D:\\work\\demo',
        name: '日报汇总',
        description: '把当天各条线的进展汇成一份日报，按「已完成 / 在办 / 卡住」三栏写。',
        content: '读当天的运行记录与任务看板，按三个栏位汇总；卡住的那一栏必须写出卡在哪一步。',
        ownerId: 1,
        lastModifiedBy: 1,
      },
      {
        id: 2,
        folder: 'D:\\work\\demo',
        name: '接口自检',
        description: '',
        content: '对一个 HTTP 端点做一次冒烟：连通性、状态码、响应体是不是合法 JSON。',
        ownerId: 1,
        lastModifiedBy: 1,
      },
      {
        // 别人的一条：登录 admin 时它不该出现在列表里（`?userId=` 那一条的判据）
        id: 3,
        folder: 'D:\\work\\demo',
        name: '检索调参',
        description: '换分块策略各跑一遍评测，把指标并排摆出来。',
        content: '固定语料与评测集，只换分块参数，对比 Recall / MRR。',
        ownerId: 2,
        lastModifiedBy: 2,
      },
    ],
    nextId: 4,
  }
}

let state = initialState()

/** 现在时间（ISO-8601 文本，与真后端 `Instant.toString()` 同形）。 */
function now() {
  return new Date().toISOString()
}

/**
 * 姓名那一份：显示名优先、没填（空串）退回登录名 —— 与真后端读侧那句
 * `COALESCE(NULLIF(TRIM(display_name), ''), name)` 同一条判据。
 * 两种都取不到（账号行不在）时给「未知用户 #id」，与 `AgentSkill#displayOwner` 一致。
 */
function nameOf(userId) {
  const user = findMockAccountById(userId)
  if (!user) return `未知用户 #${userId}`
  return user.displayName || user.name
}

function skillView(skill) {
  return {
    id: skill.id,
    folder: skill.folder,
    name: skill.name,
    description: skill.description ?? '',
    content: skill.content ?? '',
    ownerId: skill.ownerId,
    ownerName: nameOf(skill.ownerId),
    lastModifiedBy: skill.lastModifiedBy ?? null,
    lastModifiedByName: skill.lastModifiedBy == null ? '未知用户' : nameOf(skill.lastModifiedBy),
    createdAt: skill.createdAt ?? now(),
    updatedAt: skill.updatedAt ?? now(),
  }
}

/** 这个账号的文件夹选项集（他全部角色的目录前缀，去重、按原文排序）—— 与真后端同一套判据。 */
function foldersOf(userId) {
  const permissions = mockPermissionsOfId(userId)
  if (!permissions) return []
  const unique = new Map()
  for (const prefix of permissions.dirPrefixes) {
    if (prefix) unique.set(prefix.toLowerCase(), prefix)
  }
  return [...unique.values()].sort((a, b) => a.localeCompare(b))
}

/** 最近修改的在前（与真后端 `ORDER BY s.updated_at DESC, s.id DESC` 同一条）。 */
function sorted(list) {
  return [...list].sort((a, b) => {
    const at = a.updatedAt ?? ''
    const bt = b.updatedAt ?? ''
    if (at !== bt) return at < bt ? 1 : -1
    return b.id - a.id
  })
}

function scopeOf(rawUserId, json, res) {
  if (rawUserId == null) {
    // 未登录：全部记录、没有文件夹选项。**不是 400** —— 读是公开的（见文件头）
    return { userId: null, userName: '' }
  }
  const user = findMockAccountById(Number(rawUserId))
  if (!user) {
    // 一个人不在账号表里 = 这条登录记录已经失效。回 400 而不是"筛出来是空的"：
    // 后者会被读成"我没有技能"，而真相是这次登录已经不成立了
    json(res, {
      ok: false,
      error: `账号表里没有 id=${rawUserId} 这个人 —— 界面上传的是「当前登录的人」，`
        + '他不在账号表里就判不出归属。本地存着的那条登录记录已经过期，重新登录一次。',
    }, 400)
    return null
  }
  return { userId: user.id, userName: user.displayName || user.name }
}

/** 写路径上的"当前登录人"：缺了就 400，且那句话要能照做（与真后端 `account(...)` 同一句）。 */
function writerOf(rawUserId, json, res) {
  if (rawUserId == null) {
    json(res, {
      ok: false,
      error: '缺少 userId（当前登录的人）—— 未登录不能新增 / 修改 / 删除技能；'
        + '请先在页头「模拟登录」里选一个人',
    }, 400)
    return null
  }
  const user = findMockAccountById(Number(rawUserId))
  if (!user) {
    json(res, {
      ok: false,
      error: `账号表里没有 id=${rawUserId} 这个人 —— 拥有者必须是账号表里真实存在的那一个`,
    }, 400)
    return null
  }
  return user
}

/**
 * 把请求里那个文件夹对上选项集，返回**选项集里的原文**（与真后端 `inFolderSet` 逐条一致）。
 *
 * @returns 对上了返回字符串；没对上已经发过 400、返回 null
 */
function inFolderSet(folder, folders, json, res) {
  if (folder == null || String(folder).trim() === '') {
    json(res, {
      ok: false,
      error: '缺少 folder（文件夹目录）—— 它是登录人角色的目录前缀之一，现在可选的：'
        + (folders.length === 0 ? '一个都没有（他的角色都没有配目录前缀）' : folders.join('、')),
    }, 400)
    return null
  }
  const wanted = String(folder).trim()
  const hit = folders.find(candidate => candidate.toLowerCase() === wanted.toLowerCase())
  if (!hit) {
    json(res, {
      ok: false,
      error: `文件夹「${wanted}」不在当前登录人的可选范围里 —— 这个范围是他全部角色的目录前缀`
        + `（agent_role_dir），只能建在其中之一；现在可选的：`
        + (folders.length === 0 ? '一个都没有（他的角色都没有配目录前缀）' : folders.join('、')),
    }, 400)
    return null
  }
  return hit
}

/** 读请求体并解析成对象。**同步**：假后端的请求体很小，await 化只会让下面的分支多一层缩进。 */
function bodyOf(req) {
  return new Promise(resolve => {
    let raw = ''
    req.on('data', chunk => { raw += chunk })
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {})
      }
      catch {
        resolve(null)
      }
    })
  })
}

/**
 * @returns true 表示这条请求已被本模块处理（调用方不要再往后走）
 *
 * ⚠️ 同步分支里**不能写成 `return json(res, …)`**：`json()` 没有返回值（undefined），
 * 那个 return 会让分派方以为这条请求没被处理，于是又往同一个响应写一次 404 头 ——
 * 报错是 `ERR_HTTP_HEADERS_SENT`，而现象是**假后端整个进程退出**（自检里表现为"页面打不开"）。
 * 所以失败分支一律写成"先 json(...)，再 return true"。
 */
export function handleSkillRoutes(req, res, url, json) {
  const { pathname } = url
  if (!pathname.startsWith('/api/skills')) return false

  // 自检脚本靠它做到确定性（见文件头）
  if (req.method === 'POST' && pathname === '/api/skills/__reset') {
    state = initialState()
    json(res, { ok: true, note: 'skill 假后端已复位' })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/skills') {
    const scope = scopeOf(url.searchParams.get('userId'), json, res)
    if (!scope) return true
    const skills = scope.userId == null
      ? state.skills
      : state.skills.filter(skill => skill.ownerId === scope.userId)
    json(res, {
      skills: sorted(skills).map(skillView),
      total: skills.length,
      userId: scope.userId,
      userName: scope.userName,
      ownershipJudged: scope.userId != null,
      // 未登录时是空数组：那是"没有他，就没有这一份范围"，不是"一个文件夹都没有"
      folders: scope.userId == null ? [] : foldersOf(scope.userId),
      howToWrite: 'POST /api/skills?userId=<当前登录的人>；改 PUT /api/skills/{id}?userId=…；'
        + '删 DELETE /api/skills/{id}?userId=…',
    })
    return true
  }

  // ---- 写路径 ----

  const rawUserId = url.searchParams.get('userId')

  if (req.method === 'POST' && pathname === '/api/skills') {
    void bodyOf(req).then(body => {
      if (body === null) {
        json(res, { ok: false, error: '请求体不是合法 JSON' }, 400)
        return
      }
      const user = writerOf(rawUserId, json, res)
      if (!user) return
      if (body.name == null || String(body.name).trim() === '') {
        json(res, { ok: false, error: '缺少 name（技能名）' }, 400)
        return
      }
      const folders = foldersOf(user.id)
      const folder = inFolderSet(body.folder, folders, json, res)
      if (folder === null) return
      const name = String(body.name).trim()
      // 同名唯一键的判据与真后端 `uk_agent_skill_owner_folder_name` 一致：
      // 同一个人的同一个文件夹下不能重名（不同的人可以，那是两份互不相干的记录）
      if (state.skills.some(s => s.ownerId === user.id && s.folder === folder && s.name === name)) {
        json(res, {
          ok: false,
          error: `「${name}」在文件夹「${folder}」下已经有一条同名的技能 —— 同一个人的同一个文件夹下技能名是唯一键；`
            + '要改的是那一条，或者换一个名字',
        }, 409)
        return
      }
      const stamp = now()
      const saved = {
        id: state.nextId++,
        folder,
        name,
        description: body.description ?? '',
        content: body.content ?? '',
        // ★ 拥有者与最后修改人都写**请求里那个人**，请求体里给的 ownerId 一律不采纳 ——
        // 采纳它的话，任何人打一次接口就能把记录写成别人的（与真后端同一条）
        ownerId: user.id,
        lastModifiedBy: user.id,
        createdAt: stamp,
        updatedAt: stamp,
      }
      state.skills.push(saved)
      json(res, { ok: true, skill: skillView(saved) })
    })
    return true
  }

  const putMatch = /^\/api\/skills\/(\d+)$/.exec(pathname)
  if (putMatch && req.method === 'PUT') {
    const id = Number(putMatch[1])
    void bodyOf(req).then(body => {
      if (body === null) {
        json(res, { ok: false, error: '请求体不是合法 JSON' }, 400)
        return
      }
      const user = writerOf(rawUserId, json, res)
      if (!user) return
      const current = state.skills.find(s => s.id === id)
      if (!current) {
        json(res, { ok: false, error: `技能不存在：#${id}` }, 400)
        return
      }
      // ★ 归属判据是**那一行**的 ownerId，不是"请求里说的谁"
      if (current.ownerId !== user.id) {
        json(res, {
          ok: false,
          error: `技能 #${id} 的拥有者是 #${current.ownerId}（${nameOf(current.ownerId)}），`
            + `不是当前登录的 #${user.id} —— 只能修改 / 删除自己拥有的技能`,
        }, 400)
        return
      }
      const folders = foldersOf(user.id)
      const folder = body.folder == null || String(body.folder).trim() === ''
        ? current.folder
        : inFolderSet(body.folder, folders, json, res)
      if (folder === null) return
      const next = {
        ...current,
        folder,
        name: body.name == null || String(body.name).trim() === '' ? current.name : String(body.name).trim(),
        // 两条空语义不同：**字段缺席** = 保留原值，空串 = 清空（与真后端逐条一致）
        description: body.description === undefined ? current.description : String(body.description),
        content: body.content === undefined ? current.content : String(body.content),
        lastModifiedBy: user.id,
        updatedAt: now(),
      }
      if (state.skills.some(s => s.id !== id && s.ownerId === user.id && s.folder === next.folder
        && s.name === next.name)) {
        json(res, {
          ok: false,
          error: `「${next.name}」在文件夹「${next.folder}」下已经有一条同名的技能`,
        }, 409)
        return
      }
      state.skills = state.skills.map(s => (s.id === id ? next : s))
      json(res, { ok: true, skill: skillView(next) })
    })
    return true
  }

  const deleteMatch = /^\/api\/skills\/(\d+)$/.exec(pathname)
  if (deleteMatch && req.method === 'DELETE') {
    const id = Number(deleteMatch[1])
    const user = writerOf(rawUserId, json, res)
    if (!user) return true
    const current = state.skills.find(s => s.id === id)
    if (!current) {
      json(res, { ok: false, error: `技能不存在：#${id}` }, 400)
      return true
    }
    if (current.ownerId !== user.id) {
      json(res, {
        ok: false,
        error: `技能 #${id} 的拥有者是 #${current.ownerId}（${nameOf(current.ownerId)}），`
          + `不是当前登录的 #${user.id} —— 只能修改 / 删除自己拥有的技能`,
      }, 400)
      return true
    }
    state.skills = state.skills.filter(s => s.id !== id)
    // 回执里带被删掉的那一份：界面上要说的是"已删除《名字》"（与真后端同一件事）
    json(res, { ok: true, skillId: id, skill: skillView(current) })
    return true
  }

  return false
}
