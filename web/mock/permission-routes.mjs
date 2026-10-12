/**
 * 权限管理端点的**假数据**（应用管理 · 权限管理）。
 *
 * ---------------------------------------------------------------------------
 * 这一组有十个写端点，所以状态是**进程内可变**的
 * ---------------------------------------------------------------------------
 * 建账号 / 改账号 / 改口令 / 账号侧挂角色 / 建角色 / 改角色 / 删角色 /
 * 配工具 / 配目录 / 角色侧设成员 —— 点一下之后状态真的变，点第二下看到的是第一下的结果。
 *
 * ---------------------------------------------------------------------------
 * ★ 假数据刻意**照搬真后端的判据**，而不是"随便返回个成功"
 * ---------------------------------------------------------------------------
 * 这一页最需要被看清的三个分支，恰好都是**失败**分支：
 *   1. 工具名写错一个字母 → 400，且**报错里带可用清单**（没有它，下一个名字还会写错）；
 *   2. 同名账号 / 同名角色 → 409（不是 500：两个人同时点新建不是服务器故障）；
 *   3. 核对端点要能对**同一份数据**给出"允许"与"不允许"两种结论。
 * 假后端不实现这三条的话，"界面能不能把后端的拒绝显示清楚"这件事在假后端上永远走不到 ——
 * 而那正是这一页存在的意义。
 *
 * ---------------------------------------------------------------------------
 * 假数据刻意不是"万事如意"的那一份
 * ---------------------------------------------------------------------------
 *   1. **有一个账号没有角色**（`zhou`）→ 界面上必须出现「没有角色 → 没有任何权限」，
 *      而不是一行空白；
 *   2. **有一个角色清单为空**（`只读检索` 之外那个 `待配置`）→ 必须出现「一把工具都调不到」；
 *   3. **有一条目录前缀是 `demo-other` 形式的前缀陷阱**：`D:\work\demo` 不该命中它 ——
 *      这份数据把"按路径分段比"与"字符串前缀比"区分开。
 *
 * ✅ 响应体里**没有口令字段，也没有散列字段**：真后端就是这样装配的（见 PermissionController），
 * 假后端如果多给一个 `passwordHash`，前端就可能画出它。
 */

const AVAILABLE_TOOLS = ['query_sql', 'execute_sql', 'list_documents', 'read_section', 'propose_review', 'search_docs']

/** 用户搜索的默认条数与上限（与真后端 `PermissionController` 的 SEARCH_DEFAULT_LIMIT / SEARCH_MAX_LIMIT 一致）。 */
const SEARCH_DEFAULT_LIMIT = 5
const SEARCH_MAX_LIMIT = 20

/** 初始状态。进程启动时建一次（深拷贝，避免被就地改坏）。 */
function initialState() {
  return {
    users: [
      { id: 1, name: 'admin', displayName: '初始管理员', status: 'ENABLED', roles: ['管理员'] },
      // 没有角色的账号：界面上必须显示成「没有角色 → 没有任何权限」
      { id: 2, name: 'zhou', displayName: '周工', status: 'ENABLED', roles: [] },
      // 停用的账号：核对端点要对它给出"已停用"这个单独的理由
      { id: 3, name: 'li', displayName: '李工', status: 'DISABLED', roles: ['只读检索'] },
    ],
    roles: [
      {
        id: 1,
        name: '管理员',
        description: '全部工具 + 工作区根目录',
        access: 'ALL',
        tools: [],
        directories: [{ prefix: 'D:\\work\\demo', note: '' }],
      },
      {
        id: 2,
        name: '只读检索',
        description: '只能查知识库，不能改任何东西',
        access: 'LISTED',
        tools: [
          { toolName: 'search_docs', note: '' },
          { toolName: 'read_section', note: '' },
          { toolName: 'list_documents', note: '' },
        ],
        directories: [{ prefix: 'D:\\work\\demo', note: '' }],
      },
      // 清单为空的角色：界面上必须显示成「一把工具都调不到」
      { id: 3, name: '待配置', description: '刚建出来还没配', access: 'LISTED', tools: [], directories: [] },
    ],
    nextUserId: 4,
    nextRoleId: 4,
  }
}

const state = initialState()

function userView(u) {
  return {
    id: u.id,
    name: u.name,
    displayName: u.displayName ?? '',
    status: u.status,
    statusLabel: u.status === 'ENABLED' ? '启用' : '停用',
    usable: u.status === 'ENABLED',
    roles: u.roles,
  }
}

/**
 * 搜索结果里的一个人 —— **没有 roles**，与真后端的 `userBrief` 逐条一致
 * （照搬 userView 的话响应里会写着 `roles: []`，那是"这个人没有任何角色"，
 * 与"这次没查"是两件事，而它们在 JSON 里长得一样）。
 */
function userBrief(u) {
  return {
    id: u.id,
    name: u.name,
    displayName: u.displayName ?? '',
    status: u.status,
    statusLabel: u.status === 'ENABLED' ? '启用' : '停用',
    usable: u.status === 'ENABLED',
  }
}

/**
 * 按登录名搜索账号（大小写不敏感的子串匹配），最多 limit 条 —— 与真后端
 * `AccountMapper.searchByName` 同一套判据：**只匹配登录名**（显示名不参与）、按名字排序、
 * 停用账号也在结果里、搜索词里的 % 与 _ 按字面处理（`includes` 本来就没有通配符语义）。
 *
 * <p>导出给 task-routes 用：发布任务时"发布人 / 任务人必须是真实用户"这条判据要读同一份账号表，
 * 两边各写一份的话，"假后端说这个人是真的、真端点说不存在"只会表现为自检里一句看不懂的 400。
 */
export function searchMockAccounts(keyword, limit) {
  const wanted = String(keyword ?? '').trim().toLowerCase()
  if (!wanted) return []
  return state.users
    .filter(u => u.name.toLowerCase().includes(wanted))
    .sort((a, b) => a.name.localeCompare(b.name))
    .slice(0, limit)
}

/** 按登录名取一个账号（大小写与前后空格不敏感，与真后端 `findAccount` 一致）；没有则 undefined。 */
export function findMockAccount(name) {
  const wanted = String(name ?? '').trim().toLowerCase()
  return state.users.find(u => u.name.toLowerCase() === wanted)
}

/**
 * 按 id 取一个账号 —— **导出给 task-routes 用**：任务记录里存的是用户 id
 * （`agent_task.publisher_id` / `assignee_id` / `current_executor_id`），
 * 卡片上显示的姓名要按 id 查回来（与真后端读侧的三个 LEFT JOIN 同一件事）。
 */
export function findMockAccountById(id) {
  return id == null ? undefined : state.users.find(u => u.id === Number(id))
}

/** 现有全部登录名（400 的报错里要能说清"可用的是谁"）。 */
export function mockAccountNames() {
  return state.users.map(u => u.name)
}

function roleView(r) {
  return {
    id: r.id,
    name: r.name,
    description: r.description ?? '',
    access: r.access,
    accessLabel: r.access === 'ALL' ? '全部工具' : '清单白名单',
    tools: r.tools,
    toolNames: r.tools.map(t => t.toolName),
    directories: r.directories,
    dirPrefixes: r.directories.map(d => d.prefix),
  }
}

/** 与真后端 `RoleDirectory.covers` 同一套判据：按路径分段比，不按字符串前缀比。 */
function covers(prefix, dir) {
  if (!dir) return false
  const normalize = p => p
    .replace(/\//g, '\\')
    .replace(/\\+$/, '')
    .split('\\')
    .reduce((acc, seg) => {
      if (seg === '..') acc.pop()
      else if (seg !== '' && seg !== '.') acc.push(seg)
      return acc
    }, [])
  const base = normalize(prefix)
  const target = normalize(dir)
  if (target.length < base.length) return false
  return base.every((seg, i) => seg.toLowerCase() === target[i].toLowerCase())
}

/** 一个账号当前实际拥有的权限（并集），与真后端的 UserPermissions 同形。 */
function permissionsOf(user) {
  const roles = state.roles.filter(r => user.roles.includes(r.name))
  const allTools = roles.some(r => r.access === 'ALL')
  const tools = allTools ? AVAILABLE_TOOLS : [...new Set(roles.flatMap(r => r.tools.map(t => t.toolName)))]
  const directories = roles.flatMap(r => r.directories)
  return { roles, allTools, tools, directories }
}

/**
 * 一个账号当前实际拥有的权限（并集）+ 它可不可用 —— **导出给 task-routes 用**。
 *
 * <p>任务的授权判断（真后端是 `TaskFileAuthorization`）读的就是这份数据：
 * 账号表与角色表只有一份，两边各写一份的话，"假后端说这个人有目录授权、真端点说没有"
 * 只会表现为自检里一句看不懂的结论差异。
 *
 * @returns 账号不存在时 null；否则 `{name, usable, allTools, toolNames, dirPrefixes}`
 */
export function mockPermissionsOf(name) {
  return permissionsViewOf(findMockAccount(name))
}

/**
 * 同一个东西，按**用户 id** 取。
 *
 * <p>任务那一边走这一条：真后端的 `TaskFileAuthorization.judge` 拿的是
 * `agent_task.assignee_id`（用户 id），不是姓名 —— 姓名是可以改的。
 *
 * @returns 账号不存在时 null；否则与 {@link mockPermissionsOf} 同形
 */
export function mockPermissionsOfId(userId) {
  return permissionsViewOf(findMockAccountById(userId))
}

function permissionsViewOf(user) {
  if (!user) return null
  const permissions = permissionsOf(user)
  return {
    name: user.name,
    usable: user.status === 'ENABLED',
    allTools: permissions.allTools,
    toolNames: permissions.tools,
    dirPrefixes: permissions.directories.map(d => d.prefix),
  }
}

/**
 * 某条目录前缀覆盖不覆盖一个目录（与真后端 `RoleDirectory.covers` 同一套判据）。
 *
 * <p>导出给 task-routes：判据（怎么比路径）留在这里，结论怎么写（哪些话要说给用户）
 * 留任务那一侧 —— 与真后端"`RoleDirectory` 管比较、`TaskFileAuthorization` 管措辞"同一分工。
 */
export function mockCoversDirectory(prefix, dir) {
  return covers(prefix, dir)
}

function verdictFor(accountName, toolName, dir) {
  const user = state.users.find(u => u.name === accountName)
  if (!user) {
    return {
      account: accountName, allowed: false, toolAllowed: false, directoryAllowed: false,
      reason: `账号不存在：${accountName}（可用的是 ${state.users.map(u => u.name).join(', ')}）`,
      tool: toolName ?? '', dir: dir ?? '', matchedPrefix: '', roles: [], effectiveTools: [],
    }
  }
  if (user.status !== 'ENABLED') {
    return {
      account: accountName, allowed: false, toolAllowed: false, directoryAllowed: false,
      reason: `账号已停用（status=${user.status}）—— 停用的账号一律没有权限`,
      tool: toolName ?? '', dir: dir ?? '', matchedPrefix: '', roles: [], effectiveTools: [],
    }
  }
  const p = permissionsOf(user)
  const toolChecked = !!toolName
  const dirChecked = !!dir
  const toolAllowed = !toolChecked || p.allTools || p.tools.includes(toolName)
  const matched = p.directories.find(d => covers(d.prefix, dir))
  const directoryAllowed = !dirChecked || !!matched
  let reason
  if (!toolAllowed) {
    reason = `不在任何角色的工具白名单里（能调的是 ${JSON.stringify(p.tools)}）`
  }
  else if (!directoryAllowed) {
    reason = `不在任何角色的目录前缀里（已授权的前缀：${JSON.stringify(p.directories.map(d => d.prefix))}）`
  }
  else if (p.allTools && toolChecked) {
    reason = '角色里有「全部工具」，工具不受白名单限制'
  }
  else if (dirChecked) {
    reason = `按前缀「${matched.prefix}」放行`
  }
  else {
    reason = '在白名单里'
  }
  return {
    account: user.name,
    allowed: toolAllowed && directoryAllowed,
    toolAllowed,
    directoryAllowed,
    reason,
    tool: toolName ?? '',
    dir: dir ?? '',
    matchedPrefix: matched ? matched.prefix : '',
    roles: p.roles.map(r => r.name),
    effectiveTools: p.allTools ? AVAILABLE_TOOLS : p.tools,
  }
}

function conflict(res, json, message) {
  json(res, { ok: false, error: message }, 409)
}

/**
 * @returns true 表示这条请求已被本模块处理（调用方不要再往后走）
 */
export function handlePermissionRoutes(req, res, url, json) {
  const { pathname } = url
  if (!pathname.startsWith('/api/permissions')) return false

  if (req.method === 'GET' && pathname === '/api/permissions') {
    json(res, {
      users: state.users.map(userView),
      roles: state.roles.map(roleView),
      availableTools: AVAILABLE_TOOLS,
      managementEnabled: true,
      bootstrapNote: '库里一个账号都没有时，启动会建一个初始管理员（假后端：这条不会真的发生）',
      passwordNote: '口令只以散列保存：PBKDF2 · 60000 次迭代 · 16 字节盐，响应里不含这一列',
      howToVerify: 'GET /api/permissions/verify?account=<账号>&tool=<工具>&dir=<目录>',
    })
    return true
  }

  if (req.method === 'GET' && pathname === '/api/permissions/verify') {
    json(res, verdictFor(url.searchParams.get('account') ?? '', url.searchParams.get('tool') ?? undefined,
      url.searchParams.get('dir') ?? undefined))
    return true
  }

  if (req.method === 'GET' && pathname === '/api/permissions/tools') {
    json(res, { tools: AVAILABLE_TOOLS, count: AVAILABLE_TOOLS.length })
    return true
  }

  // 按搜索词查用户（发布任务的发布人 / 任务人下拉用）—— 与真后端同一条判据：
  // 空搜索词是 400（它不退化成"把前几个账号给你"），limit 超上限时**夹住并回显实际值**
  //
  // ⚠️ 这里必须 `json(...)` 之后**显式 return true**：本函数返回假值时，sse-server 会接着往下试
  // 别的路由、最后走到 404 那一支再写一次响应头 —— 现象是进程直接崩在
  // `ERR_HTTP_HEADERS_SENT`，而它看起来像"假后端自己挂了"
  if (req.method === 'GET' && pathname === '/api/permissions/users/search') {
    const keyword = (url.searchParams.get('keyword') ?? '').trim()
    if (!keyword) {
      json(res, {
        ok: false,
        error: '缺少 keyword（搜索词）—— 这个端点按登录名做子串匹配；要一次取回全部账号请用 GET /api/permissions',
      }, 400)
      return true
    }
    const raw = Number(url.searchParams.get('limit'))
    const limit = Number.isFinite(raw) && raw > 0
      ? Math.min(Math.trunc(raw), SEARCH_MAX_LIMIT)
      : SEARCH_DEFAULT_LIMIT
    const users = searchMockAccounts(keyword, limit)
    json(res, { keyword, limit, count: users.length, users: users.map(userBrief) })
    return true
  }

  // ---------------- 账号 ----------------

  if (req.method === 'POST' && pathname === '/api/permissions/users') {
    readBody(req, res, json, b => {
      if (!b.name) return json(res, { ok: false, error: '缺少 name' }, 400)
      if (!b.password) return json(res, { ok: false, error: '缺少 password —— 建账号必须给一个初始口令' }, 400)
      if (state.users.some(u => u.name.toLowerCase() === String(b.name).toLowerCase())) {
        return conflict(res, json, `账号「${b.name}」已经存在 —— 登录名是唯一键`)
      }
      const user = {
        id: state.nextUserId++,
        name: b.name,
        displayName: b.displayName ?? '',
        status: b.enabled === false ? 'DISABLED' : 'ENABLED',
        roles: [],
      }
      state.users.push(user)
      json(res, { ok: true, user: userView(user) })
    })
    return true
  }

  const userMatch = pathname.match(/^\/api\/permissions\/users\/(\d+)(\/password|\/roles)?$/)
  if (userMatch) {
    const id = Number(userMatch[1])
    const sub = userMatch[2]
    const user = state.users.find(u => u.id === id)
    if (!user) {
      json(res, { ok: false, error: `账号不存在：#${id}` }, 400)
      return true
    }
    if (req.method === 'PUT' && sub === '/password') {
      readBody(req, res, json, b => {
        if (!b.password) return json(res, { ok: false, error: '缺少 password' }, 400)
        // 响应里**不含口令也不含散列**：只报告这件事发生了（与真后端一致）
        json(res, { ok: true, userId: id, note: '口令已按新散列保存（旧口令立即失效）' })
      })
      return true
    }
    if (req.method === 'PUT' && sub === '/roles') {
      readBody(req, res, json, b => {
        const ids = Array.isArray(b.roleIds) ? b.roleIds : []
        const missing = ids.filter(rid => !state.roles.some(r => r.id === rid))
        if (missing.length > 0) {
          return json(res, { ok: false, error: `这些角色不存在：${JSON.stringify(missing)}` }, 400)
        }
        user.roles = state.roles.filter(r => ids.includes(r.id)).map(r => r.name)
        json(res, { ok: true, userId: id, roles: user.roles })
      })
      return true
    }
    if (req.method === 'PUT') {
      readBody(req, res, json, b => {
        user.displayName = b.displayName ?? ''
        user.status = b.enabled === false ? 'DISABLED' : 'ENABLED'
        json(res, { ok: true, user: userView(user) })
      })
      return true
    }
  }

  // ---------------- 角色 ----------------

  if (req.method === 'POST' && pathname === '/api/permissions/roles') {
    readBody(req, res, json, b => {
      if (!b.name) return json(res, { ok: false, error: '缺少 name' }, 400)
      if (state.roles.some(r => r.name === b.name)) {
        return conflict(res, json, `角色「${b.name}」已经存在 —— 两个同名角色在界面上长得一模一样`)
      }
      const role = {
        id: state.nextRoleId++,
        name: b.name,
        description: b.description ?? '',
        access: b.access === 'ALL' ? 'ALL' : 'LISTED',
        tools: [],
        directories: [],
      }
      state.roles.push(role)
      json(res, { ok: true, role: roleView(role) })
    })
    return true
  }

  const roleMatch = pathname.match(/^\/api\/permissions\/roles\/(\d+)(\/tools|\/directories|\/users)?$/)
  if (roleMatch) {
    const id = Number(roleMatch[1])
    const sub = roleMatch[2]
    const role = state.roles.find(r => r.id === id)
    if (!role) {
      json(res, { ok: false, error: `角色不存在：#${id}` }, 400)
      return true
    }
    if (req.method === 'DELETE' && !sub) {
      // 影响面在删除**之前**数出来（级联之后就查不到了）—— 与真后端一致
      const affected = state.users.filter(u => u.roles.includes(role.name))
      state.roles = state.roles.filter(r => r.id !== id)
      affected.forEach(u => { u.roles = u.roles.filter(n => n !== role.name) })
      json(res, {
        ok: true, roleId: id, affectedAccounts: affected.length,
        note: affected.length === 0 ? '没有账号拥有这个角色'
          : `${affected.length} 个账号失去了这个角色 —— 它们的权限按剩下的角色重新取并集`,
      })
      return true
    }
    if (req.method === 'PUT' && sub === '/tools') {
      readBody(req, res, json, b => {
        const wanted = Array.isArray(b.tools) ? b.tools.map(t => t.toolName) : []
        // 与真后端同一条判据：认不出的工具名当场 400，且**报错里带可用清单**
        const unknown = wanted.filter(name => !AVAILABLE_TOOLS.includes(name))
        if (unknown.length > 0) {
          return json(res, {
            ok: false,
            error: `工具「${unknown[0]}」不在注册表里 —— 写错一个字母的后果是这把工具永远调不到，`
              + `而那会在运行期表现为「模型选错了工具」。现在可用的是：${JSON.stringify(AVAILABLE_TOOLS)}`,
          }, 400)
        }
        role.tools = [...new Set(wanted)].map(toolName => ({ toolName, note: '' }))
        json(res, { ok: true, roleId: id, tools: role.tools.map(t => t.toolName) })
      })
      return true
    }
    if (req.method === 'PUT' && sub === '/users') {
      readBody(req, res, json, b => {
        const wanted = Array.isArray(b.userIds) ? [...new Set(b.userIds)] : []
        // 与真后端同一条判据：认不出的账号 id 当场 400，且**报错里带可用账号清单**
        const missing = wanted.filter(uid => !state.users.some(u => u.id === uid))
        if (missing.length > 0) {
          return json(res, {
            ok: false,
            error: `这些账号不存在：${JSON.stringify(missing)}（可用的是 ${state.users.map(u => u.name).join(', ')}）`,
          }, 400)
        }
        // 整份替换：先从每个账号上摘掉这个角色，再按 ids 挂上（顺序不能反）
        state.users.forEach(u => { u.roles = u.roles.filter(n => n !== role.name) })
        state.users.filter(u => wanted.includes(u.id)).forEach(u => { u.roles.push(role.name) })
        // 回执**包含停用账号**：成员关系本身与"停用即无权限"（解析期判据）是两件事
        json(res, {
          ok: true,
          roleId: id,
          accounts: state.users.filter(u => u.roles.includes(role.name)).map(u => u.name),
        })
      })
      return true
    }
    if (req.method === 'PUT' && sub === '/directories') {
      readBody(req, res, json, b => {
        const wanted = Array.isArray(b.directories) ? b.directories.map(d => d.prefix) : []
        if (wanted.some(p => !p || !String(p).trim())) {
          return json(res, { ok: false, error: '目录前缀不能为空（空前缀等于把这个角色的范围放到根目录）' }, 400)
        }
        role.directories = [...new Set(wanted)].map(prefix => ({ prefix, note: '' }))
        json(res, { ok: true, roleId: id, dirPrefixes: role.directories.map(d => d.prefix) })
      })
      return true
    }
    if (req.method === 'PUT') {
      readBody(req, res, json, b => {
        if (state.roles.some(r => r.id !== id && r.name === b.name)) {
          return conflict(res, json, `角色名「${b.name}」已经被另一个角色用了`)
        }
        role.name = b.name ?? role.name
        role.description = b.description ?? ''
        role.access = b.access === 'ALL' ? 'ALL' : 'LISTED'
        json(res, { ok: true, role: roleView(role) })
      })
      return true
    }
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
