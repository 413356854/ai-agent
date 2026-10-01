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

  create: (payload: { publisher: string; assignee: string; description: string }) => Promise<boolean>
  advance: (id: number, result?: string) => Promise<boolean>
  /**
   * 验收通过：`PENDING_ACCEPTANCE` → 已完成。
   * 后端会顺带把这条任务的经验汇成规则补进技能（异步、失败不影响验收）。
   */
  accept: (id: number) => Promise<boolean>
  /**
   * 记为任务未完成：`PENDING_ACCEPTANCE` → 未完成。`reason` 是**选填**的原因
   * （没写就不带；> 512 字符后端回 400）。
   */
  reject: (id: number, reason?: string) => Promise<boolean>
  answer: (id: number, confirmId: number, answer: string) => Promise<boolean>
  /**
   * 答复「已修改任务」：权限不足 → 新发布（整条链重走一遍）。
   * `description` 是详情里那一格改过之后的完整描述，与这次选择一起落库。
   */
  revise: (id: number, description: string) => Promise<boolean>
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
 *
 * <h2>看板按"当前登录的人"筛（`userId`）</h2>
 * 筛的是"此刻的球在谁手上"（后端按 `current_executor_id` 筛，见 `taskApi.board`），
 * 不是"与他有关的全部任务" —— 所以同一个人既是发布人又是任务人时，看到的也只是现在归他的那些。
 * 没登录（`userId` 为空）时**不筛**：那时没有"我"这个主语，给一份空看板会被读成"我没有待办"。
 *
 * <p>登录态变了要重拉：它是**服务端**的筛选条件，本地那份看板是按旧条件取回来的
 * （切走再切回来才刷新的话，用户会看着一列别人的任务点下去，然后收到 409 —— 而那条待办
 * 本来就不该在他眼前）。
 */
export function useTasks(active: boolean, userId: number | null): UseTasksResult {
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
      setBoard({ status: 'ok', data: await taskApi.board(userId) })
    }
    catch (e) {
      setBoard({ status: 'error', error: describe(e) })
    }
  }, [userId])

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

  const create = useCallback(async (payload: { publisher: string; assignee: string; description: string }) =>
    mutate('发布任务', async () => {
      const r = await taskApi.create(payload)
      return `已发布任务 #${r.task.id}（${r.task.statusLabel}）—— 下一步由 agent 任务执行判断需求`
    }), [mutate])

  const advance = useCallback(async (id: number, result?: string) =>
    mutate('登记执行结果', async () => {
      const r = await taskApi.advance(id, result)
      return `任务 #${id} 已推进到「${r.task.statusLabel}」（第 ${r.task.currentStep} 步）`
    }), [mutate])

  const accept = useCallback(async (id: number) =>
    mutate('验收', async () => {
      const r = await taskApi.accept(id)
      return `任务 #${id} 已验收为「${r.task.statusLabel}」（第 ${r.task.currentStep} 步）—— `
        + '这次问过、答过的内容由后端汇成规则补进技能；有没有补上，去 skill 管理那一页看'
    }), [mutate])

  const reject = useCallback(async (id: number, reason?: string) =>
    mutate('记为任务未完成', async () => {
      const r = await taskApi.reject(id, reason)
      return `任务 #${id} 已记为「${r.task.statusLabel}」（第 ${r.task.currentStep} 步）`
        + (r.task.incompleteReason ? `：${r.task.incompleteReason}` : '（没写原因）')
        + ' —— 这条任务到此结束，要重做请重新发布一条'
    }), [mutate])

  const answer = useCallback(async (id: number, confirmId: number, text: string) =>
    mutate('答复确认项', async () => {
      const r = await taskApi.answer(id, confirmId, text)
      return `已答复「${text}」—— 任务 #${id} 现在是「${r.task.statusLabel}」（第 ${r.task.currentStep} 步）`
    }), [mutate])

  const revise = useCallback(async (id: number, description: string) =>
    mutate('重新发布任务', async () => {
      const r = await taskApi.revise(id, description)
      return `已答复「已修改任务」—— 任务 #${id} 回到「${r.task.statusLabel}」（第 ${r.task.currentStep} 步），`
        + '这一份任务描述与这次选择一起保存了，整条链会按它重走一遍'
    }), [mutate])

  const cancel = useCallback(async (id: number, reason: string) =>
    mutate('取消任务', async () => {
      const r = await taskApi.cancel(id, reason)
      return `任务 #${id} 已取消：${r.task.cancelReason ?? reason}`
    }), [mutate])

  const clearFeedback = useCallback(() => setFeedback({ status: 'idle' }), [])

  /**
   * 换人（登录 / 退出）就让下一次进面板重新取一份。
   *
   * <p>这里刻意**不顺手调一次 `loadBoard`**：要在"进面板"与"换人"两个入口都拉一次的话，
   * 那个 initialized 标记就挡不住重复请求，而重复请求的后果是后到的那一份覆盖先到的
   * （两份可能按不同的人筛）。只把它复位，取数的时机仍然只有一处 —— 下面那个 effect。
   */
  useEffect(() => {
    initialized.current = false
  }, [userId])

  // 进面板拉一次（不轮询，理由见类注释）。loadBoard 认得当前的人，所以这里不用把 userId 写进依赖
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
    accept,
    reject,
    answer,
    revise,
    cancel,
  }
}

function describe(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
