import { useCallback, useEffect, useRef, useState } from 'react'
import { getCostPrices, getCostSummary } from '../api/cost'
import type { CostPricesResponse, CostSummaryResponse } from '../api/cost'
import type { Remote } from '../types'

/** 可选的时间窗。放在这里而不是面板里：hook 与面板共用同一份取值。 */
export const COST_WINDOWS = [1, 7, 30, 90] as const

export type CostWindow = (typeof COST_WINDOWS)[number]

export interface UseCostResult {
  summary: Remote<CostSummaryResponse>
  prices: Remote<CostPricesResponse>
  /** 当前时间窗（天） */
  days: CostWindow
  setDays: (days: CostWindow) => void
  load: () => void
  loadPrices: () => void
}

/**
 * 成本看板的状态机（阶段四 · 工程化）。
 *
 * <h2>为什么这个 hook 挂在 App 上</h2>
 * 与另外几个 hook 同一个理由：**切模式不能丢结果**。看板的查询比运行列表重
 * （四条 GROUP BY + 一次对账），来回切一次就重跑一遍是白白浪费。
 *
 * <h2>为什么它**不轮询**</h2>
 * 这个项目里唯一轮询的是 HITL 的待办列表，理由写在那里：**有人在等它**。
 * 成本看板整好相反 —— 它是一个"回头看"的页面，数字变了也不影响任何人做决定，
 * 所以"用户主动刷新"就够了。加轮询只会让人盯着一个不断跳动的金额，
 * 而那个金额的意义在于趋势，不在于秒级变化。
 *
 * <h2>为什么价格表单独一个 Remote 态</h2>
 * 两者的问题不同：summary 回答"花了多少"，prices 回答"这个数字是怎么来的、还差什么"。
 * 一个没有价格配置的部署会**反复**需要后者（那是一份待办清单），
 * 而 summary 是日常刷新的那一个。分开之后，界面能把"待配置"做成一个独立的可操作入口。
 */
export function useCost(active: boolean): UseCostResult {
  const [summary, setSummary] = useState<Remote<CostSummaryResponse>>({ status: 'idle' })
  const [prices, setPrices] = useState<Remote<CostPricesResponse>>({ status: 'idle' })
  const [days, setDaysState] = useState<CostWindow>(7)

  const initialized = useRef(false)
  /** 同一时刻只允许一个请求在飞：连点窗口按钮不该叠出四个并发查询。 */
  const loading = useRef(false)

  /**
   * 当前窗口的**权威值**放在 ref 里，`load()` 从它读。
   *
   * <h2>为什么要这么绕（这是本 hook 唯一一个容易写错的地方）</h2>
   * 直觉写法是 `const load = useCallback(..., [days])`，然后 `setDays(d)` 之后再 `load()`。
   * 那样有两个问题，而且**都不会报错**：
   * <ol>
   *   <li><b>闭包读到旧值</b>：`load` 捕获的是**渲染那一刻**的 `days`。
   *       点了"最近 30 天"之后调用的还是 7 天那次渲染的 `load` ——
   *       于是界面写着 30 天、数字是 7 天的。**数字看起来完全正常**，没人会发现；</li>
   *   <li><b>effect 依赖漂移</b>：把 `load` 放进 effect 依赖里（eslint 会要求），
   *       于是每次 `days` 变化都会触发一次额外的自动加载 —— 一次点击发两次请求，
   *       而第二次的结果可能先回来（乱序覆盖）。</li>
   * </ol>
   * 把窗口放进 ref 之后：`load` 变成零依赖（引用稳定），点一次只发一次，
   * 而且拿到的必然是刚设的那个值。
   */
  const windowRef = useRef<number>(days)

  const load = useCallback(async () => {
    if (loading.current) return
    loading.current = true
    setSummary({ status: 'loading' })
    try {
      setSummary({ status: 'ok', data: await getCostSummary(windowRef.current) })
    }
    catch (e) {
      setSummary({ status: 'error', error: describeError(e) })
    }
    finally {
      loading.current = false
    }
  }, [])

  const loadPrices = useCallback(async () => {
    setPrices({ status: 'loading' })
    try {
      setPrices({ status: 'ok', data: await getCostPrices() })
    }
    catch (e) {
      setPrices({ status: 'error', error: describeError(e) })
    }
  }, [])

  /** 切窗口：先改 ref 再拉数据 —— 顺序反了就会拉到旧窗口的数据。 */
  const setDays = useCallback((next: CostWindow) => {
    windowRef.current = next
    setDaysState(next)
    void load()
  }, [load])

  // 首次进入面板：拉一次快照与价格表。
  // 依赖里**没有** days —— 换窗口由 setDays 显式触发（理由见上面 windowRef 的注释）
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void load()
    void loadPrices()
  }, [active, load, loadPrices])

  return {
    summary,
    prices,
    days,
    setDays,
    load: () => void load(),
    loadPrices: () => void loadPrices(),
  }
}

function describeError(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
