import { useCallback, useEffect, useRef, useState } from 'react'
import { SkillRejectedError, skillApi } from '../api/skills'
import type { SkillSnapshot } from '../api/skills'
import type { Remote } from '../types'

/** 建 / 改技能时提交的那一份（`id` 为空是新建）。 */
export interface SkillDraft {
  id?: number
  folder: string
  name: string
  description: string
  content: string
}

export interface UseSkillsResult {
  snapshot: Remote<SkillSnapshot>

  /** 上一次写操作的反馈。message 就是后端给的那句话（不在这里另写一份）。 */
  feedback: Remote<{ ok: boolean; message: string }>
  clearFeedback: () => void
  busy: boolean
  reload: () => void

  create: (draft: SkillDraft) => Promise<boolean>
  update: (draft: SkillDraft & { id: number }) => Promise<boolean>
  remove: (id: number, name: string) => Promise<boolean>
}

/**
 * 技能管理的状态机（应用管理区 · skill管理）。
 *
 * <h2>为什么每次写操作都重新拉一次全量，而不是就地改本地状态</h2>
 * 与 usePermissions 同一条理由：这一页的文件夹选项集是**角色的目录前缀**，
 * 而一次写之后后端才知道两件事 —— 那条记录现在排在哪（按最近修改排）、
 * 以及这次登录的人还能选哪些文件夹。就地改本地状态要求前端把这两件事也算一遍，
 * 而两份判据迟早不一致（不一致的表现是"下拉里有这个文件夹、点保存却被拒"）。
 * 代价是每次写多一次读，而这一页的读是一次查询、写是人手点的。
 *
 * <h2>为什么错误信息不在这里另写一份文案</h2>
 * 后端的报错本来就是给人看的（"文件夹「D:\x」不在当前登录人的可选范围里……现在可选的：[…]"），
 * 前端再翻译一遍只会让两处文案漂移。这里只负责把它原样显示出来。
 * 唯一在这里分的是 409（同名 → 换个名字）与 400（内容不合规 → 改内容），
 * 因为它们的下一步动作不同。
 *
 * <h2>登录态变了要重拉</h2>
 * 筛选条件（`userId`）与文件夹选项都是**服务端**算的，本地那一份是按旧的人取回来的。
 * 不重拉的话，退出之后页面上留着的还是"那个人的技能"，而列表标题写着"这是全部记录" ——
 * 一处显示与一份数据对不上，且不会报错。
 */
export function useSkills(active: boolean, userId: number | null): UseSkillsResult {
  const [snapshot, setSnapshot] = useState<Remote<SkillSnapshot>>({ status: 'idle' })
  const [feedback, setFeedback] = useState<UseSkillsResult['feedback']>({ status: 'idle' })
  const [busy, setBusy] = useState(false)
  const initialized = useRef(false)

  const reload = useCallback(async () => {
    setSnapshot({ status: 'loading' })
    try {
      setSnapshot({ status: 'ok', data: await skillApi.snapshot(userId) })
    }
    catch (e) {
      setSnapshot({ status: 'error', error: describe(e) })
    }
  }, [userId])

  /**
   * 所有写操作的公共外壳：清反馈 → 调后端 → 重新拉全量。
   *
   * 返回 boolean 让调用方决定要不要关掉弹窗 —— 写失败时把弹窗关掉，
   * 用户填的内容就没了，而他正需要照着报错改一改再提交。
   */
  const mutate = useCallback(async (what: string, action: () => Promise<string>): Promise<boolean> => {
    // 未登录时写端点是 400。这一条在界面上已经被禁用的按钮挡住了，
    // 这里再判一次是因为"禁用"只挡住了按钮那一条路（快捷键 / 旧标签页都还能提交）
    if (userId == null) {
      setFeedback({ status: 'error', error: `${what}失败：还没有登录 —— 请先在页头「模拟登录」里选一个人` })
      return false
    }
    setBusy(true)
    setFeedback({ status: 'idle' })
    try {
      const message = await action()
      setFeedback({ status: 'ok', data: { ok: true, message } })
      await reload()
      return true
    }
    catch (e) {
      const prefix = e instanceof SkillRejectedError && e.status === 409
        ? '这个名字已经用过了：' : `${what}失败：`
      setFeedback({ status: 'error', error: prefix + describe(e) })
      return false
    }
    finally {
      setBusy(false)
    }
  }, [reload, userId])

  const create = useCallback(async (draft: SkillDraft) =>
    mutate('新建技能', async () => {
      const r = await skillApi.create(userId as number, {
        folder: draft.folder,
        name: draft.name,
        description: draft.description,
        content: draft.content,
      })
      return `已建技能《${r.skill.name}》，放在「${r.skill.folder}」，拥有者是 ${r.skill.ownerName}`
    }), [mutate, userId])

  const update = useCallback(async (draft: SkillDraft & { id: number }) =>
    mutate('修改技能', async () => {
      const r = await skillApi.update(userId as number, draft.id, {
        folder: draft.folder,
        name: draft.name,
        // 空串照原样送出去（= 清空这一列）：界面上把内容删光再保存就是一次明确的清空。
        // 换成 undefined 的话那一次会变成"这一列没动"，而用户以为已经删掉了
        description: draft.description,
        content: draft.content,
      })
      return `技能 #${r.skill.id}《${r.skill.name}》已更新（最后修改人 ${r.skill.lastModifiedByName}）`
    }), [mutate, userId])

  const remove = useCallback(async (id: number, name: string) =>
    mutate('删除技能', async () => {
      const r = await skillApi.remove(userId as number, id)
      return `已删除技能 #${r.skillId}《${r.skill.name || name}》`
    }), [mutate, userId])

  const clearFeedback = useCallback(() => setFeedback({ status: 'idle' }), [])

  /**
   * 换人（登录 / 退出）就让下一次进面板重新取一份。
   *
   * <p>只复位标记、**不顺手调一次 reload**：进面板与换人是同一个取数时机，
   * 两个入口都拉的话那两次请求会互相覆盖（两份按不同的人筛）。
   * 与 useTasks 的做法一致。
   */
  useEffect(() => {
    initialized.current = false
  }, [userId])

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
    create,
    update,
    remove,
  }
}

function describe(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
