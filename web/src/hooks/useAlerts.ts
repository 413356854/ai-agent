import { useCallback, useEffect, useRef, useState } from 'react'
import {
  acknowledgeAlert,
  getAlertRules,
  getAlerts,
  getCanary,
  runAlertSweep,
  startCanary,
  stopCanary,
} from '../api/alerts'
import type { AlertsResponse, AlertRulesResponse, AlertSweepResponse, CanaryResponse } from '../api/alerts'
import { DiagError } from '../api/diag'
import type { Remote } from '../types'

/** 时间窗（与成本看板同一组取值 —— 两处不一样会让人对不上数）。 */
export const ALERT_WINDOWS = [1, 7, 30] as const

export type AlertWindow = (typeof ALERT_WINDOWS)[number]

/**
 * 可以被灰度的提示词种类 —— **只有 Agent 循环那一份**。
 *
 * <h2>⚠️ 这个字符串是 {@code PromptKind.AGENT_LOOP.defaultValue()}，不是枚举名</h2>
 * 第一版这里（以及后端的默认参数）写的是 {@code "agent-loop"} —— 那是 Java 枚举常量的
 * 名字，而接口上用的名字是 {@code "system-agent-tools"}。后果是真后端上
 * {@code GET /api/canary} 直接 400：「没有这一份提示词：「agent-loop」」，
 * 而假后端照着手写的那个串写了一遍，所以它绿着 ——
 * <b>这正是"假数据替真代码圆了场"的又一例</b>，而这一次是界面自检的
 * 「真后端路径」把它抓出来的（那条路径存在的全部意义就是这个）。
 *
 * <p>为什么灰度只支持这一份：灰度成立的前提是"提示词在<b>每次运行开始时</b>读一次"，
 * 而只有 Agent 循环满足（见 {@code PromptKind} 里"为什么两份的读法不一样"）。
 */
export const CANARY_KIND = 'system-agent-tools'

/**
 * 一次写操作的结果。
 *
 * <h2>为什么要带 `status` 与 `rejected`</h2>
 * 这一组写端点的失败分成两类，而**处置完全相反**（见 alerts.ts 里那段注释）：
 * <ul>
 *   <li><b>400 —— 参数不合规</b>（重量必须在 1..99 / 必须写 note）：改输入再点；</li>
 *   <li><b>409 —— 前提过期</b>（已经有一次进行中的灰度 / 这条告警已恢复）：
 *       **刷新后重试才有意义**，反复点同一个按钮没有用。</li>
 * </ul>
 * 两者都渲染成一句红字的后果很具体：使用者会对着 409 反复点同一个按钮。
 * 所以结果里带上状态码，界面据此说两句不同的话。
 */
export interface WriteResult {
  ok: boolean
  message?: string
  status?: number
  /** 是不是"前提过期"那一类（409）—— 界面据此提示"刷新后再决定" */
  stale?: boolean
}

export interface UseAlertsResult {
  /** 判据目录（一次库都不查） */
  rules: Remote<AlertRulesResponse>
  /** 当前状态（在响的 + 已恢复的） */
  alerts: Remote<AlertsResponse>
  /** 上一轮评估的产出（新响 / 恢复 / 仍在响） */
  sweep: Remote<AlertSweepResponse>
  /** 灰度状态 + 实测分流分布 */
  canary: Remote<CanaryResponse>
  days: AlertWindow
  setDays: (days: AlertWindow) => void
  /** 正在跑的写操作（按钮据此禁用，避免连点叠出两次开灰度） */
  busy: boolean
  /** 上一次写操作的结果（成功与失败都放这里，由面板决定怎么显示） */
  lastWrite: WriteResult | null
  clearLastWrite: () => void
  load: () => void
  evaluate: () => void
  acknowledge: (fingerprint: string, by: string) => Promise<WriteResult>
  start: (input: { kind: string; candidateVersion: number; weightPercent: number; note: string }) => Promise<WriteResult>
  stop: (input: { kind: string; endReason: string }) => Promise<WriteResult>
}

/**
 * 告警 + 灰度的状态机（阶段四 · 工程化第 5 件）。
 *
 * <h2>为什么这个 hook 也挂在 App 上</h2>
 * 与另外几个 hook 同一个理由：**切模式不能丢结果**。而这一页尤其明显 ——
 * "跑一轮评估"是一次会写库的动作，来回切一次模式就重跑一遍会让告警的
 * `occurrences` 无意义地涨（而那个数字是"这个异常是持续的还是偶尔的"的唯一线索）。
 *
 * <h2>为什么它**不轮询**</h2>
 * 与成本看板同一条理由：这一页是"回头看 + 手动处置"的地方，
 * 数字变了不影响任何人做决定。项目里唯一轮询的是 HITL 待办列表，
 * 理由写在那里 —— **有人在等它**。
 *
 * <h2>为什么"跑一轮评估"要用户明确点（而不是进面板就自动跑）</h2>
 * 因为它**会写库**（除非 dryRun）。进面板就自动跑的话，"打开这一页看看"
 * 这个动作本身会改变告警的状态（新响的会被记下来、恢复的会被标成恢复）——
 * 而那让"我什么都没做"与"我确认过了"变得难以区分。
 * 所以进面板只拉只读的三样（判据 / 当前状态 / 灰度），评估要点按钮。
 *
 * <p>⚠️ 但**这一条有代价，必须写清**：没有人点，就永远没有告警记录 ——
 * 而那正是"没有人被通知"这个原始缺口。真上生产要接一个定时器
 * （`GET /api/alerts/sweep` 是个普通 GET，cron 或监控探针直接打就行）。
 * 界面这一侧刻意不替它做决定。
 */
export function useAlerts(active: boolean): UseAlertsResult {
  const [rules, setRules] = useState<Remote<AlertRulesResponse>>({ status: 'idle' })
  const [alerts, setAlerts] = useState<Remote<AlertsResponse>>({ status: 'idle' })
  const [sweep, setSweep] = useState<Remote<AlertSweepResponse>>({ status: 'idle' })
  const [canary, setCanary] = useState<Remote<CanaryResponse>>({ status: 'idle' })
  const [days, setDaysState] = useState<AlertWindow>(7)
  const [busy, setBusy] = useState(false)
  const [lastWrite, setLastWrite] = useState<WriteResult | null>(null)

  const initialized = useRef(false)
  /** 同一时刻只允许一个请求在飞（连点窗口按钮不该叠出三个并发查询）。 */
  const loading = useRef(false)

  /**
   * 当前窗口的**权威值**放在 ref 里。
   *
   * 理由与 `useCost` 里那段完全相同，而且同样是**不报错的那一类错**：
   * `useCallback(..., [days])` 捕获的是渲染那一刻的 days，于是点了"最近 30 天"
   * 之后调用的还是 7 天那次渲染的函数 —— 界面写着 30 天、数字是 7 天的。
   */
  const windowRef = useRef<number>(days)

  const loadCanary = useCallback(async () => {
    try {
      // 灰度只有 system-agent-tools 这一份受管（CHAT_SYSTEM 的注入点在每次请求上，
      // 与 AgentLoop 的"每次运行读一次"不是同一条路径 —— 见 PromptKind 的注释）。
      // 所以这里固定传 system-agent-tools（库里存的是 PromptKind.defaultValue，**不是枚举名**）：
      // 做成可切换的下拉框会让读者以为两份都能灰度
      setCanary({ status: 'ok', data: await getCanary(CANARY_KIND, windowRef.current) })
    }
    catch (e) {
      setCanary({ status: 'error', error: describeError(e) })
    }
  }, [])

  const load = useCallback(async () => {
    if (loading.current) return
    loading.current = true
    setAlerts({ status: 'loading' })
    try {
      setAlerts({ status: 'ok', data: await getAlerts(true) })
    }
    catch (e) {
      setAlerts({ status: 'error', error: describeError(e) })
    }
    finally {
      loading.current = false
    }
    await loadCanary()
  }, [loadCanary])

  const loadRules = useCallback(async () => {
    setRules({ status: 'loading' })
    try {
      setRules({ status: 'ok', data: await getAlertRules() })
    }
    catch (e) {
      setRules({ status: 'error', error: describeError(e) })
    }
  }, [])

  /** 跑一轮评估（会写库），然后重新拉一次状态 —— 两件事必须成对做。 */
  const evaluate = useCallback(async () => {
    setSweep({ status: 'loading' })
    try {
      const result = await runAlertSweep(windowRef.current, false)
      setSweep({ status: 'ok', data: result })
    }
    catch (e) {
      setSweep({ status: 'error', error: describeError(e) })
    }
    // 【为什么评估完要重拉状态】评估改变的是"告警当前状态"（新响 / 恢复），
    // 而 sweep 的返回体只讲**这一轮**发生了什么。不看列表的话，
    // 使用者会以为"这一轮没报什么"就等于"现在没有告警"
    await load()
  }, [load])

  const setDays = useCallback((next: AlertWindow) => {
    windowRef.current = next
    setDaysState(next)
    void load()
  }, [load])

  /** 写操作的公共外壳：置 busy、统一把异常翻译成 WriteResult、完事重拉状态。 */
  const runWrite = useCallback(async (action: () => Promise<unknown>, what: string): Promise<WriteResult> => {
    setBusy(true)
    try {
      await action()
      const result: WriteResult = { ok: true }
      setLastWrite(result)
      return result
    }
    catch (e) {
      // 状态码在这一组里很重要：400 改参数、409 先刷新（见 WriteResult 的注释）
      const status = e instanceof DiagError ? e.status : undefined
      const result: WriteResult = {
        ok: false,
        message: `${what}没有执行：${describeError(e)}`,
        status,
        stale: status === 409,
      }
      setLastWrite(result)
      return result
    }
    finally {
      setBusy(false)
      // 无论成功失败都重拉一次：失败时那份状态可能也变了（例如别人先停掉了灰度），
      // 而"页面停在旧状态上让人反复点同一个按钮"正是这条 409 要避免的事
      await load()
    }
  }, [load])

  const acknowledge = useCallback(
    (fingerprint: string, by: string) => runWrite(() => acknowledgeAlert(fingerprint, by), '确认'),
    [runWrite],
  )

  const start = useCallback(
    (input: { kind: string; candidateVersion: number; weightPercent: number; note: string }) =>
      runWrite(() => startCanary(input), '开灰度'),
    [runWrite],
  )

  const stop = useCallback(
    (input: { kind: string; endReason: string }) => runWrite(() => stopCanary(input), '停灰度'),
    [runWrite],
  )

  // 首次进入面板：拉三样只读的。**不自动跑评估**（它会写库，理由见类注释）
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadRules()
    void load()
  }, [active, load, loadRules])

  return {
    rules,
    alerts,
    sweep,
    canary,
    days,
    setDays,
    busy,
    lastWrite,
    clearLastWrite: () => setLastWrite(null),
    load: () => void load(),
    evaluate: () => void evaluate(),
    acknowledge,
    start,
    stop,
  }
}

function describeError(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
