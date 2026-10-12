import { useCallback, useEffect, useRef, useState } from 'react'
import { ScheduleRejectedError, scheduleApi } from '../api/schedules'
import type { SchedulePreview, ScheduleSnapshot } from '../api/schedules'
import type { Remote } from '../types'

/** 一份写规则的表单（新建与编辑共用同一份形状）。 */
export interface ScheduleForm {
  /** 任务人的**用户 id**（只能从 `UserPicker` 的候选里来，见 `useSchedules.create`）。 */
  assigneeId: number
  description: string
  /** 界面构造器拼出来的 6 段正则（也可以手写）。 */
  cron: string
  enabled: boolean
}

export interface UseSchedulesResult {
  /** 规则列表 + 调度线程池。 */
  snapshot: Remote<ScheduleSnapshot>

  /**
   * 当前那句正则的预览（中文说法 + 未来 5 次），**由后端算**。
   *
   * `idle` = 还没发过（表单刚打开）。`error` 的 `error` 就是后端那句"这句正则哪里不对"，
   * 界面原样显示 —— 不套一句"输入有误"，因为用户需要的是那句具体的话。
   */
  preview: Remote<SchedulePreview>
  /** 预览失败时后端给的那句话（与 `preview` 的 error 分开，是为了让输入框下面那行只管它）。 */
  previewError: string | null
  /** 正在预览（用户还在打字时用得上：按钮该禁用，但不必显示一个转圈）。 */
  previewing: boolean
  /** 发一次预览（防抖在面板那一层做，这一层只负责发）。 */
  runPreview: (cron: string) => void
  clearPreview: () => void

  /** 上一次写操作的反馈。message 就是后端给的那句话（不在这里另写一份）。 */
  feedback: Remote<{ ok: boolean; message: string }>
  clearFeedback: () => void
  busy: boolean
  reload: () => void

  create: (form: ScheduleForm & { publisherId: number }) => Promise<boolean>
  update: (id: number, form: ScheduleForm) => Promise<boolean>
  setEnabled: (id: number, enabled: boolean) => Promise<boolean>
  trigger: (id: number) => Promise<boolean>
  remove: (id: number) => Promise<boolean>
}

/**
 * 定时任务的状态机（应用管理区 · 定时任务）。
 *
 * <h2>为什么每次写操作后重拉整个列表</h2>
 * 一次写会同时改两处：库里那一行（正则 / 任务人 / 描述 / 启停），
 * 以及**调度线程池里那一条线程**（后端的做法固定是"删旧线程、按新正则重加"）。
 * 就地改本地状态的写法要把"这次改动让池子变成了什么样"再算一遍 ——
 * 那是把后端的调度逻辑复制到前端，而两份判据迟早不一致
 * （不一致的表现是"列表上写着新正则、池子里还是旧的"）。
 * 重拉一次拿到的是后端那一刻的真实状态，含 `pool.scheduled` 与每条的 `nextRuns`。
 *
 * <h2>为什么预览是**后端**算的</h2>
 * "这句正则下一次什么时候跑"由后端的 `ScheduleCron` 算，而调度线程池装线程时用的是
 * 同一份判断 —— 前端自己算一份就会出现"预览说 09:00、实际 10:00 跑"。
 * 前端只负责把选择拼成一句 6 段表达式（`web/src/cron.ts`）。
 *
 * <h2>为什么错误信息分两种</h2>
 * 与 `useTasks` / `usePermissions` 同一条：`ScheduleRejectedError.status === 400` 时
 * 那句话是"你填的东西哪里不对"（该改了重试），界面要原样显示它；
 * 其余（网络、500）是链路问题，界面说"请求没发出去"。
 *
 * <h2>为什么不轮询</h2>
 * 与成本看板 / Prompt 面板同一条：这一页的数据只被这一页自己改。
 * 唯一会随着时间变的是"下一次触发时刻"，而那是**算出来的**（不是被谁改的）——
 * 盯着它没有意义，点一次刷新就够了。
 */
export function useSchedules(active: boolean, userId: number | null): UseSchedulesResult {
  const [snapshot, setSnapshot] = useState<Remote<ScheduleSnapshot>>({ status: 'idle' })
  const [preview, setPreview] = useState<Remote<SchedulePreview>>({ status: 'idle' })
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [feedback, setFeedback] = useState<Remote<{ ok: boolean; message: string }>>({ status: 'idle' })
  const [busy, setBusy] = useState(false)
  const initialized = useRef(false)
  /**
   * 预览请求的序号：**只有最后发出去的那一次的结果会被采纳**。
   *
   * <p>用户在输入框里连续改几下会连着发几次预览，而它们的返回**顺序不保证**
   * （后发的可能先回来）—— 不挡住的话，屏幕上那句中文与未来触发时刻会属于
   * 前一次输入，而输入框里显示的是后一次。这类"两份东西各说一套"不报错，
   * 只表现为"预览偶尔不对"，最容易归因到"后端算错了"。
   */
  const previewSeq = useRef(0)

  const load = useCallback(async () => {
    setSnapshot({ status: 'loading' })
    try {
      setSnapshot({ status: 'ok', data: await scheduleApi.list(userId) })
    }
    catch (e) {
      setSnapshot({ status: 'error', error: describe(e) })
    }
  }, [userId])

  const runPreview = useCallback((cron: string) => {
    if (!cron.trim()) {
      // 空表达式不必打后端：后端也会回 400，但那一趟往返只是让用户多等一会儿
      setPreview({ status: 'idle' })
      setPreviewError(null)
      return
    }
    const seq = ++previewSeq.current
    setPreviewing(true)
    setPreviewError(null)
    void scheduleApi.preview(cron).then(
      result => {
        if (seq !== previewSeq.current) return
        setPreview({ status: 'ok', data: result })
      },
      (e: unknown) => {
        if (seq !== previewSeq.current) return
        // 这一支是**正常的业务失败**（用户写的正则不合法）：保留那句话，
        // 而不是把它当成链路错误 —— 用户正需要照着它改
        setPreview({ status: 'idle' })
        setPreviewError(e instanceof ScheduleRejectedError ? e.message : describe(e))
      },
    ).finally(() => {
      if (seq === previewSeq.current) setPreviewing(false)
    })
  }, [])

  const clearPreview = useCallback(() => {
    previewSeq.current++
    setPreview({ status: 'idle' })
    setPreviewError(null)
    setPreviewing(false)
  }, [])

  /**
   * 所有写操作的公共外壳：清反馈 → 调后端 → 重拉列表。
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
      await load()
      return true
    }
    catch (e) {
      const prefix = e instanceof ScheduleRejectedError ? '' : `${what}失败：`
      setFeedback({ status: 'error', error: prefix + describe(e) })
      return false
    }
    finally {
      setBusy(false)
    }
  }, [load])

  const create = useCallback(async (form: ScheduleForm & { publisherId: number }) =>
    mutate('配规则', async () => {
      const r = await scheduleApi.create({
        publisherId: form.publisherId,
        assigneeId: form.assigneeId,
        description: form.description,
        cron: form.cron,
        enabled: form.enabled,
      })
      return r.message
    }), [mutate])

  const update = useCallback(async (id: number, form: ScheduleForm) =>
    mutate('改规则', async () => {
      const r = await scheduleApi.update(id, {
        assigneeId: form.assigneeId,
        description: form.description,
        cron: form.cron,
        enabled: form.enabled,
      })
      return r.message
    }), [mutate])

  const setEnabled = useCallback(async (id: number, enabled: boolean) =>
    mutate(enabled ? '启用' : '停用', async () => {
      const r = await scheduleApi.setEnabled(id, enabled)
      return r.message
    }), [mutate])

  const trigger = useCallback(async (id: number) =>
    mutate('手工触发', async () => {
      const r = await scheduleApi.trigger(id)
      return r.message
    }), [mutate])

  const remove = useCallback(async (id: number) =>
    mutate('删除规则', async () => {
      const r = await scheduleApi.remove(id)
      return r.message
    }), [mutate])

  const clearFeedback = useCallback(() => setFeedback({ status: 'idle' }), [])

  /**
   * 换人（登录 / 退出）就让下一次进面板重新取一份。
   *
   * <p>这里刻意**不顺手调一次 `load`**：要在"进面板"与"换人"两个入口都拉一次的话，
   * 那个 initialized 标记就挡不住重复请求（与 `useTasks` 同一条）。
   * 而这一页的列表**不按人筛**，换人只影响"回显的那个人是谁"这一格。
   */
  useEffect(() => {
    initialized.current = false
  }, [userId])

  // 进面板拉一次（不轮询，理由见类注释）
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void load()
  }, [active, load])

  return {
    snapshot,
    preview,
    previewError,
    previewing,
    runPreview,
    clearPreview,
    feedback,
    clearFeedback,
    busy,
    reload: () => {
      void load()
    },
    create,
    update,
    setEnabled,
    trigger,
    remove,
  }
}

function describe(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
