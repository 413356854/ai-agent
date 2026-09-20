import { useCallback, useEffect, useRef, useState } from 'react'
import { PermissionRejectedError, permissionApi } from '../api/permissions'
import type { PermissionSnapshot, PermissionVerdict } from '../api/permissions'
import type { Remote } from '../types'

export interface UsePermissionsResult {
  snapshot: Remote<PermissionSnapshot>

  /** 上一次写操作的反馈。它的 message 就是后端给的那句话（不在这里另写一份）。 */
  feedback: Remote<{ ok: boolean; message: string }>
  clearFeedback: () => void
  busy: boolean
  reload: () => void

  createUser: (payload: { name: string; password: string; displayName?: string; enabled?: boolean }) => Promise<boolean>
  updateUser: (id: number, payload: { displayName?: string; enabled?: boolean }) => Promise<boolean>
  changePassword: (id: number, password: string) => Promise<boolean>
  setUserRoles: (id: number, roleIds: number[]) => Promise<boolean>

  createRole: (payload: { name: string; description?: string; access?: string }) => Promise<boolean>
  updateRole: (id: number, payload: { name: string; description?: string; access?: string }) => Promise<boolean>
  deleteRole: (id: number) => Promise<boolean>
  setRoleTools: (id: number, toolNames: string[]) => Promise<boolean>
  setRoleDirectories: (id: number, prefixes: string[]) => Promise<boolean>

  /** 核对一个账号的工具/目录权限。 */
  verdict: Remote<PermissionVerdict>
  verify: (account: string, tool?: string, dir?: string) => Promise<void>
  clearVerdict: () => void
}

/**
 * 权限管理的状态机（应用管理区 · 权限管理）。
 *
 * <h2>为什么每个写操作都重新拉一次全量，而不是就地改本地状态</h2>
 * 因为这一页的东西<b>互相影响</b>：删一个角色会改掉所有拥有它的账号的权限，
 * 改一个角色的工具会改掉每个拥有它的账号能调的工具。
 * 就地改本地状态要求前端把"这次改动波及了谁"再算一遍 ——
 * 那是把后端已经算好的并集逻辑复制到前端，而两份判据迟早不一致
 * （不一致的表现是"页面上显示有权限、跑起来没有"，且不报错）。
 *
 * <p>代价是每次写操作多一次读。这一页的读是 5 条小查询，而写操作是人手点的。
 *
 * <h2>为什么错误信息不在这里另写一份文案</h2>
 * 后端的报错本来就是给人看的（"工具「search_doc」不在注册表里……现在可用的是：[…]"），
 * 前端再翻译一遍只会让两处文案漂移。这里只负责把它原样显示出来。
 * 唯一在这里分的是 409（重名 → 换个名字）与 400（内容不合规 → 改内容），
 * 因为它们的下一步动作不同。
 */
export function usePermissions(active: boolean): UsePermissionsResult {
  const [snapshot, setSnapshot] = useState<Remote<PermissionSnapshot>>({ status: 'idle' })
  const [feedback, setFeedback] = useState<UsePermissionsResult['feedback']>({ status: 'idle' })
  const [busy, setBusy] = useState(false)
  const [verdict, setVerdict] = useState<Remote<PermissionVerdict>>({ status: 'idle' })
  const initialized = useRef(false)

  const reload = useCallback(async () => {
    setSnapshot({ status: 'loading' })
    try {
      setSnapshot({ status: 'ok', data: await permissionApi.snapshot() })
    }
    catch (e) {
      setSnapshot({ status: 'error', error: describe(e) })
    }
  }, [])

  /**
   * 所有写操作的公共外壳：清反馈 → 调后端 → 重新拉全量。
   *
   * 返回 boolean 让调用方决定要不要关掉弹窗 —— 写失败时把弹窗关掉，
   * 用户填的内容就没了，而他正需要照着报错改一改再提交。
   */
  const mutate = useCallback(async (what: string, action: () => Promise<string>): Promise<boolean> => {
    setBusy(true)
    setFeedback({ status: 'idle' })
    try {
      const message = await action()
      setFeedback({ status: 'ok', data: { ok: true, message } })
      await reload()
      return true
    }
    catch (e) {
      const prefix = e instanceof PermissionRejectedError && e.status === 409
        ? '名字已被占用：' : `${what}失败：`
      setFeedback({ status: 'error', error: prefix + describe(e) })
      return false
    }
    finally {
      setBusy(false)
    }
  }, [reload])

  const createUser = useCallback(async (payload: { name: string; password: string; displayName?: string; enabled?: boolean }) =>
    mutate('建账号', async () => {
      const r = await permissionApi.createUser(payload)
      return `已建账号「${r.user.name}」—— 它现在一个角色都没有，因此没有任何权限`
    }), [mutate])

  const updateUser = useCallback(async (id: number, payload: { displayName?: string; enabled?: boolean }) =>
    mutate('改账号', async () => {
      const r = await permissionApi.updateUser(id, payload)
      return `账号「${r.user.name}」已更新（${r.user.statusLabel}）`
    }), [mutate])

  const changePassword = useCallback(async (id: number, password: string) =>
    mutate('改口令', async () => {
      const r = await permissionApi.changePassword(id, password)
      return r.note
    }), [mutate])

  const setUserRoles = useCallback(async (id: number, roleIds: number[]) =>
    mutate('改角色', async () => {
      const r = await permissionApi.setUserRoles(id, roleIds)
      return r.roles.length === 0
        ? '这个账号现在一个角色都没有 —— 它的权限是空的（不是"默认全部"）'
        : `账号的角色已改为：${r.roles.join('、')}`
    }), [mutate])

  const createRole = useCallback(async (payload: { name: string; description?: string; access?: string }) =>
    mutate('建角色', async () => {
      const r = await permissionApi.createRole(payload)
      return `已建角色「${r.role.name}」（${r.role.accessLabel}）`
    }), [mutate])

  const updateRole = useCallback(async (id: number, payload: { name: string; description?: string; access?: string }) =>
    mutate('改角色', async () => {
      const r = await permissionApi.updateRole(id, payload)
      return `角色「${r.role.name}」已更新（${r.role.accessLabel}）`
    }), [mutate])

  const deleteRole = useCallback(async (id: number) =>
    mutate('删角色', async () => {
      const r = await permissionApi.deleteRole(id)
      return r.note
    }), [mutate])

  const setRoleTools = useCallback(async (id: number, toolNames: string[]) =>
    mutate('改工具清单', async () => {
      const r = await permissionApi.setRoleTools(id, toolNames.map(toolName => ({ toolName })))
      return r.tools.length === 0
        ? '这个角色的工具清单已清空 —— 它的成员一把工具都调不到'
        : `工具清单已替换为 ${r.tools.length} 把：${r.tools.join('、')}`
    }), [mutate])

  const setRoleDirectories = useCallback(async (id: number, prefixes: string[]) =>
    mutate('改目录前缀', async () => {
      const r = await permissionApi.setRoleDirectories(id, prefixes.map(prefix => ({ prefix })))
      return r.dirPrefixes.length === 0
        ? '这个角色的目录前缀已清空 —— 它的成员在任何目录下都不被授权'
        : `目录前缀已替换为 ${r.dirPrefixes.length} 条：${r.dirPrefixes.join('、')}`
    }), [mutate])

  const verify = useCallback(async (account: string, tool?: string, dir?: string) => {
    setVerdict({ status: 'loading' })
    try {
      setVerdict({ status: 'ok', data: await permissionApi.verify(account, tool, dir) })
    }
    catch (e) {
      setVerdict({ status: 'error', error: describe(e) })
    }
  }, [])

  const clearFeedback = useCallback(() => setFeedback({ status: 'idle' }), [])
  const clearVerdict = useCallback(() => setVerdict({ status: 'idle' }), [])

  // 进面板拉一次。**不轮询**：这一页的数据只被本页面自己改，
  // 而"有人在等它"这个理由（HITL 待办轮询的唯一理由）在这里不成立
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void reload()
  }, [active, reload])

  return {
    snapshot,
    feedback,
    clearFeedback,
    busy,
    reload: () => {
      void reload()
    },
    createUser,
    updateUser,
    changePassword,
    setUserRoles,
    createRole,
    updateRole,
    deleteRole,
    setRoleTools,
    setRoleDirectories,
    verdict,
    verify,
    clearVerdict,
  }
}

function describe(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
