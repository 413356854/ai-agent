import { useCallback, useEffect, useRef, useState } from 'react'
import { TaskRejectedError, taskApi } from '../api/tasks'
import type { AgentTaskBoard, AgentTaskDetail } from '../api/tasks'
import type { Remote } from '../types'

export interface UseTasksResult {
  /** 看板：全部任务按列分好。 */
  board: Remote<AgentTaskBoard>

  /** 当前打开的那条任务的详情（没打开时 idle）。 */
  detail: Remote<AgentTaskDetail>
  openTask: (id: number) => void
  closeTask: () => void

  /** 上一次写操作的反馈。message 就是后端给的那句话（不在这里另写一份）。 */
  feedback: Remote<{ ok: boolean; message: string }>
  clearFeedback: () => void
  busy: boolean
  reload: () => void

  create: (payload: { publisher: string; assignee?: string; description: string; agentId?: number | null }) => Promise<boolean>
  advance: (id: number, result?: string) => Promise<boolean>
  answer: (id: number, confirmId: number, answer: string) => Promise<boolean>
  cancel: (id: number, reason: string) => Promise<boolean>
}

/**
 * agent 任务的状态机（应用管理区 · agent任务）。
 *
 * <h2>为什么每次写操作后既重拉看板、又重拉详情</h2>
 * 一次写会同时改变两处：卡片所在的列（看板），以及确认项清单与可用动作（详情）。
 * 就地改本地状态的写法要把"这次改动波及了哪一列、哪几条确认项"再算一遍 ——
 * 那是把后端的判定复制到前端，而两份判据迟早不一致
 * （不一致的表现是"卡片还在旧列里、点开却已经是下一步"）。
 *
 * <h2>为什么错误信息分 409 与 400 两种前缀</h2>
 * 与 usePermissions 同一条理由，但这里的 409 尤其常见：同一张看板可能被两个人看着，
 * 别人先答过一条确认项之后，你这一边的按钮**看起来还能点**。
 * 那时后端回的是 409，界面要说"刷新看看它到哪一步了"，而不是说"你填错了"。
 *
 * <h2>为什么不轮询</h2>
 * 与成本看板 / Prompt 面板同一条：这一页的数据只被这一页自己改。
 * 唯一会因为等待而轮询的是 HITL 待办（"有人在等它"），而这张看板上的"等"
 * 是等人来点界面，不是等系统跑完 —— 点一次刷新就够了。
 */
export function useTasks(active: boolean): UseTasksResult {
  const [board, setBoard] = useState<Remote<AgentTaskBoard>>({ status: 'idle' })
  const [detail, setDetail] = useState<Remote<AgentTaskDetail>>({ status: 'idle' })
  const [feedback, setFeedback] = useState<UseTasksResult['feedback']>({ status: 'idle' })
  const [busy, setBusy] = useState(false)
  const initialized = useRef(false)
  /** 当前打开的任务 id：写完之后要按它重拉详情（用 ref 免得把它写进 useCallback 的依赖） */
  const openId = useRef<number | null>(null)

  const loadBoard = useCallback(async () => {
    setBoard({ status: 'loading' })
    try {
      setBoard({ status: 'ok', data: await taskApi.board() })
    }
    catch (e) {
      setBoard({ status: 'error', error: describe(e) })
    }
  }, [])

  const loadDetail = useCallback(async (id: number) => {
    try {
      setDetail({ status: 'ok', data: await taskApi.detail(id) })
    }
    catch (e) {
      setDetail({ status: 'error', error: describe(e) })
    }
  }, [])

  const openTask = useCallback((id: number) => {
    openId.current = id
    setDetail({ status: 'loading' })
    void loadDetail(id)
  }, [loadDetail])

  const closeTask = useCallback(() => {
    openId.current = null
    setDetail({ status: 'idle' })
  }, [])

  /**
   * 所有写操作的公共外壳：清反馈 → 调后端 → 重拉看板与（若开着）详情。
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
      await loadBoard()
      if (openId.current !== null) {
        await loadDetail(openId.current)
      }
      return true
    }
    catch (e) {
      // 409 = "这条任务的状态变了，刷新看看"；其余（含 400）= "你填的东西有问题"
      const prefix = e instanceof TaskRejectedError && e.status === 409
        ? '这条任务的状态已经变了：' : `${what}失败：`
      setFeedback({ status: 'error', error: prefix + describe(e) })
      // 状态冲突时把看板与详情一起刷新 —— 让用户直接看到"它到哪一步了"
      if (e instanceof TaskRejectedError && e.status === 409) {
        await loadBoard()
        if (openId.current !== null) {
          await loadDetail(openId.current)
        }
      }
      return false
    }
    finally {
      setBusy(false)
    }
  }, [loadBoard, loadDetail])

  const create = useCallback(async (payload: { publisher: string; assignee?: string; description: string; agentId?: number | null }) =>
    mutate('发布任务', async () => {
      const r = await taskApi.create(payload)
      return `已发布任务 #${r.task.id}（${r.task.statusLabel}）—— 下一步由 agent 任务执行判断需求`
    }), [mutate])

  const advance = useCallback(async (id: number, result?: string) =>
    mutate('推进任务', async () => {
      const r = await taskApi.advance(id, result)
      return `任务 #${id} 已推进到「${r.task.statusLabel}」（第 ${r.task.currentStep} 步）`
    }), [mutate])

  const answer = useCallback(async (id: number, confirmId: number, text: string) =>
    mutate('答复确认项', async () => {
      const r = await taskApi.answer(id, confirmId, text)
      return `已答复「${text}」—— 任务 #${id} 现在是「${r.task.statusLabel}」（第 ${r.task.currentStep} 步）`
    }), [mutate])

  const cancel = useCallback(async (id: number, reason: string) =>
    mutate('取消任务', async () => {
      const r = await taskApi.cancel(id, reason)
      return `任务 #${id} 已取消：${r.task.cancelReason ?? reason}`
    }), [mutate])

  const clearFeedback = useCallback(() => setFeedback({ status: 'idle' }), [])

  // 进面板拉一次（不轮询，理由见类注释）
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadBoard()
  }, [active, loadBoard])

  return {
    board,
    detail,
    openTask,
    closeTask,
    feedback,
    clearFeedback,
    busy,
    reload: () => {
      void loadBoard()
    },
    create,
    advance,
    answer,
    cancel,
  }
}

function describe(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
