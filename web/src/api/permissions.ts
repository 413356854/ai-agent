/**
 * 权限管理端点的契约（`ai.agent.controller.PermissionController`）。
 *
 * ---------------------------------------------------------------------------
 * 与 prompt.ts 同一条规矩：**这里的类型必须与后端返回的 JSON 逐字对应**
 * ---------------------------------------------------------------------------
 * 后端返回的是手拼的 `Map`（不是 record 序列化），所以编译器帮不上忙 ——
 * 字段名写错只表现为页面上某一格是 `undefined`，而控制台干干净净。
 * 改后端时回来对一遍。
 *
 * ---------------------------------------------------------------------------
 * 两条与页面行为绑定的后端事实
 * ---------------------------------------------------------------------------
 * **口令只以散列保存，响应里根本没有这一列**（后端的 `account(...)` 装配里就没有那个键）。
 * 页面上不能出现"重置口令"这类看起来能读出原口令的操作，也不能把散列画出来 ——
 * 一个把整行实体序列化出去的实现做不到这一点，而那种实现在加一列时会自动多暴露一列。
 *
 * 另一处是 `managementEnabled`：这一族端点**没有登录**（能访问端口的人可以给自己开全部工具），
 * 所以配置里有一个开关。为 false 时端点整族 404（不是 403）—— 页面必须把这件事说成
 * "这个功能在这台机器上被关掉了"，而不是渲染成一个空列表（空列表看起来像"还没有人配权限"）。
 */

/** 一个账号。**没有口令字段，也没有散列字段**（见文件头）。 */
export interface PermissionUser {
  id: number
  name: string
  /** 可能为空串（没填显示名时后端给空串而不是 null）。 */
  displayName: string
  /** ENABLED / DISABLED。 */
  status: string
  statusLabel: string
  /** 停用的账号一律没有权限（判据在后端，这里只用来渲染）。 */
  usable: boolean
  roles: string[]
}

/** 一个角色。 */
export interface PermissionRole {
  id: number
  name: string
  description: string
  /** LISTED = 只允许清单里的工具；ALL = 全部已注册工具。 */
  access: 'LISTED' | 'ALL' | string
  accessLabel: string
  tools: { toolName: string; note: string }[]
  toolNames: string[]
  directories: { prefix: string; note: string }[]
  dirPrefixes: string[]
}

/** `GET /api/permissions` 的响应：权限页要的一切。 */
export interface PermissionSnapshot {
  users: PermissionUser[]
  roles: PermissionRole[]
  /** 可被授权的工具名 —— 取值就是注册表里真实存在的那些（写错一个字母会被后端拒）。 */
  availableTools: string[]
  managementEnabled: boolean
  bootstrapNote: string
  passwordNote: string
  howToVerify: string
}

/** `GET /api/permissions/verify` 的响应。 */
export interface PermissionVerdict {
  account: string
  allowed: boolean
  /** 工具那一半的结论。 */
  toolAllowed: boolean
  /** 目录那一半的结论。 */
  directoryAllowed: boolean
  /** 为什么（通过时也有一句，说明是按哪一条放行的）。 */
  reason: string
  tool: string
  dir: string
  /** 命中的目录前缀（没命中时为空串）。 */
  matchedPrefix: string
  roles: string[]
  /** 他实际能调的工具名（角色是 ALL 时就是全部已注册工具）。 */
  effectiveTools: string[]
}

/**
 * 写入被拒（HTTP 400）。
 *
 * 单独一个类型是为了让界面能把它显示成"这句话该怎么改"，
 * 而它与 409（换个名字）在交互上完全不同 —— 压成一句红字会让人对着重名一遍遍点保存。
 */
export class PermissionRejectedError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'PermissionRejectedError'
    this.status = status
  }
}

const BASE = '/api/permissions'

async function parse<T>(response: Response): Promise<T> {
  let body: Record<string, unknown> = {}
  try {
    body = (await response.json()) as Record<string, unknown>
  } catch {
    // 非 JSON 的错误体（代理返回的 HTML 错误页）—— 下面退化成用状态码说话
  }
  if (response.ok) {
    return body as T
  }
  // 后端两种错误体形状：ErrorResponseAdvice 的 {message}（400/500）与
  // PermissionController 自己给的 {error}（409）。两种都读，界面拿到的都是一句话
  const message = typeof body.message === 'string'
    ? body.message
    : typeof body.error === 'string' ? body.error : `请求失败（HTTP ${response.status}）`
  throw new PermissionRejectedError(message, response.status)
}

async function send<T>(method: string, path: string, payload?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (payload !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(payload)
  }
  return parse<T>(await fetch(`${BASE}${path}`, init))
}

export const permissionApi = {
  snapshot: () => send<PermissionSnapshot>('GET', ''),

  verify: (account: string, tool?: string, dir?: string) => {
    const query = new URLSearchParams({ account })
    if (tool) query.set('tool', tool)
    if (dir) query.set('dir', dir)
    return send<PermissionVerdict>('GET', `/verify?${query.toString()}`)
  },

  createUser: (payload: { name: string; password: string; displayName?: string; enabled?: boolean }) =>
    send<{ ok: boolean; user: PermissionUser }>('POST', '/users', payload),

  /** 改显示名与启用状态。**不含口令** —— 改口令是 `changePassword`。 */
  updateUser: (id: number, payload: { displayName?: string; enabled?: boolean }) =>
    send<{ ok: boolean; user: PermissionUser }>('PUT', `/users/${id}`, payload),

  changePassword: (id: number, password: string) =>
    send<{ ok: boolean; userId: number; note: string }>('PUT', `/users/${id}/password`, { password }),

  /**
   * 给账号**替换**角色清单（整份替换，不是增删 —— 理由见后端接口注释）。
   *
   * 界面只在角色侧编辑成员（见 `setRoleUsers`），这个方法没有调用方 ——
   * 但端点本身仍是契约的一部分（本文件头一条就是"与后端逐字对应"），所以留着。
   */
  setUserRoles: (id: number, roleIds: number[]) =>
    send<{ ok: boolean; userId: number; roles: string[] }>('PUT', `/users/${id}/roles`, { roleIds }),

  createRole: (payload: { name: string; description?: string; access?: string }) =>
    send<{ ok: boolean; role: PermissionRole }>('POST', '/roles', payload),

  updateRole: (id: number, payload: { name: string; description?: string; access?: string }) =>
    send<{ ok: boolean; role: PermissionRole }>('PUT', `/roles/${id}`, payload),

  /** 删角色。返回体带回被波及的账号数 —— 它在删除**之前**数出来（级联之后查不到了）。 */
  deleteRole: (id: number) =>
    send<{ ok: boolean; roleId: number; affectedAccounts: number; note: string }>('DELETE', `/roles/${id}`),

  setRoleTools: (id: number, tools: { toolName: string; note?: string }[]) =>
    send<{ ok: boolean; roleId: number; tools: string[] }>('PUT', `/roles/${id}/tools`, { tools }),

  setRoleDirectories: (id: number, directories: { prefix: string; note?: string }[]) =>
    send<{ ok: boolean; roleId: number; dirPrefixes: string[] }>('PUT', `/roles/${id}/directories`, { directories }),

  /**
   * 给角色**替换**成员账号（整份替换）。
   *
   * 界面编辑账号↔角色关系只走这一侧（账号表上的角色列是只读的）：
   * 同一份关系有两个界面入口就是两份判据，而它们不一致时页面上看不出来。
   * 回执里是登录名（与 `setUserRoles` 回执里是角色名对称）—— 停用的账号也在里面。
   */
  setRoleUsers: (id: number, userIds: number[]) =>
    send<{ ok: boolean; roleId: number; accounts: string[] }>('PUT', `/roles/${id}/users`, { userIds }),
}
