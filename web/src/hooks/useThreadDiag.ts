import { useCallback, useEffect, useRef, useState } from 'react'
import {
  askNonStreaming,
  getConfig,
  getCurrentThread,
  getStats,
  resetStats as postResetStats,
  runCarrierExperiment,
  runPinExperiment,
} from '../api/diag'
import type {
  AsyncMode,
  AsyncTurn,
  CarrierExperiment,
  DiagConfig,
  JvmStats,
  PinExperiment,
  ThreadResponse,
} from '../api/diag'

/** 一次远端读取的界面状态；三者互斥，所以用一个联合而不是三个平行字段 */
export type Remote<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; data: T }
  | { status: 'error'; error: string }

/** 面板上各块实验区的固定参数。学习点 7 要的是"对照"，所以这些值刻意写在界面上 */
export const CARRIER_TASKS = 16
export const CARRIER_SLEEP_MS = 300
export const PIN_HOLDERS = 16
export const PIN_HOLD_MS = 800

export interface UseThreadDiagResult {
  threadFacts: Remote<ThreadResponse>
  stats: Remote<JvmStats>
  config: Remote<DiagConfig>
  carriers: Remote<CarrierExperiment>
  pinning: Remote<PinExperiment>
  /** 非流式对话的记录，按序保留（与结构化模式同一个理由：切走再切回来不能丢） */
  asyncTurns: AsyncTurn[]
  /**
   * 有「占载体线程」的实验在跑。
   *
   * 暴露给界面是为了让按钮真的禁用掉，而不是点了没反应 ——
   * 守卫（inFlight）挡住的请求是静默返回的，用户会以为按钮坏了。
   */
  busy: boolean

  /** 手动刷新线程事实 + 线程账目（两个端点一起打，否则面板上的两组数字可能来自不同时刻） */
  refreshThreads: () => void
  /** 重置并发峰值后立刻用返回的最新账目覆盖界面 */
  resetPeaks: () => void
  loadConfig: () => void
  runCarriers: () => void
  runPinning: () => void
  callAsync: (message: string, mode: AsyncMode) => void
}

/**
 * 线程诊断 + 预算账目的状态机。
 *
 * <h2>为什么整个 hook 挂在 App 上（而不是挂在这个面板里）</h2>
 * 与 useChatStream / useStructuredChat 完全同一个理由：这两个诊断面板里
 * 装着好几组需要几十秒才能跑出来的实验数据（钉住实验一次要跑两组共 1.6 秒以上，
 * 对话还要等模型），切走再切回来就清空的话，用户会立刻失去"我刚跑的是什么"这个上下文，
 * 只能重跑一遍。挂在这一层之后，切模式只是换渲染，状态原地不动。
 *
 * <h2>为什么首次加载要等 active</h2>
 * 挂载在 App 上就意味着页面一打开就会执行 —— 但用户如果一直待在流式模式，
 * 这两次请求纯属白打（诊断端点的 liveVirtualThreads 还要遍历所有线程抓栈，不便宜）。
 * 所以首次加载推迟到"面板第一次真正显示"时，之后恢复正常，不再受 active 影响。
 *
 * <h2>为什么用一个 inFlight 守卫而不是各自一个 loading 状态</h2>
 * 这些端点共享同一批载体线程（carriers 一次占 16 个，pin 一次占 16 个），
 * 同时打两组实验会让两边的数字互相污染 —— 那正好毁掉"对照"这个前提。
 * 一把锁把所有实验串起来，比在每个按钮上写一遍 disabled 可靠。
 */
export function useThreadDiag(active: boolean, chatConversationId: string): UseThreadDiagResult {
  const [threadFacts, setThreadFacts] = useState<Remote<ThreadResponse>>({ status: 'idle' })
  const [stats, setStats] = useState<Remote<JvmStats>>({ status: 'idle' })
  const [config, setConfig] = useState<Remote<DiagConfig>>({ status: 'idle' })
  const [carriers, setCarriers] = useState<Remote<CarrierExperiment>>({ status: 'idle' })
  const [pinning, setPinning] = useState<Remote<PinExperiment>>({ status: 'idle' })
  const [asyncTurns, setAsyncTurns] = useState<AsyncTurn[]>([])

  /** 有实验正在跑：按钮统一禁用，避免两组实验互相污染载体线程 */
  const inFlight = useRef(false)
  /** inFlight 的镜像 state：ref 改值不触发重渲染，而按钮的禁用状态需要渲染 */
  const [busy, setBusy] = useState(false)
  const initialized = useRef(false)

  /**
   * 记住最新的"当前本次会话的 conversationId"。
   *
   * 为什么必须走 ref：它只被用在异步回调里（请求"借用当前会话"），
   * 而回调必须在 conversationId 变化时不重建 —— 否则一次 in-flight 请求的被引用函数
   * 会在中途变化，回调不知道该写进哪条记录。
   * 顺带一提，这个值也正是"本次对话真的跑在虚拟线程上"的入口，
   * 所以它必须来自 App 里那个当前激活的会话，不能自己造一个。
   */
  const conversationIdRef = useRef(chatConversationId)
  useEffect(() => {
    conversationIdRef.current = chatConversationId
  }, [chatConversationId])

  /**
   * 占用/释放那把"实验锁"，并同步按钮的禁用状态。
   *
   * ref 和 state 各司其职不能合并：守卫必须在同一个 tick 内立刻生效（`await` 之后才读 ref 就已经晚了，
   * 两次点击会同时通过检查），而按钮的禁用状态必须触发渲染 —— ref 改值是不会重渲染的。
   */
  const acquire = useCallback(() => {
    if (inFlight.current) return false
    inFlight.current = true
    setBusy(true)
    return true
  }, [])

  const release = useCallback(() => {
    inFlight.current = false
    setBusy(false)
  }, [])

  const loadThreads = useCallback(async () => {
    if (!acquire()) return
    // 两个端点一起打：只刷一个的话，面板上"当前线程"和"线程账目"会来自不同时刻，
    // 而这两组数字恰恰是要放在一起看的
    setThreadFacts({ status: 'loading' })
    setStats({ status: 'loading' })
    try {
      const [facts, snapshot] = await Promise.all([getCurrentThread(), getStats()])
      setThreadFacts({ status: 'ok', data: facts })
      setStats({ status: 'ok', data: snapshot })
    } catch (e) {
      const message = describeError(e)
      setThreadFacts({ status: 'error', error: message })
      setStats({ status: 'error', error: message })
    } finally {
      release()
    }
  }, [acquire, release])

  /** 只重置峰值，不碰"当前线程"那一块 —— 重置不会改变当前请求跑在哪个线程上 */
  const resetPeaks = useCallback(async () => {
    if (!acquire()) return
    setStats({ status: 'loading' })
    try {
      // 重置接口返回的就已经是重置后的账目，直接用它覆盖，省掉一次多余的往返
      setStats({ status: 'ok', data: await postResetStats() })
    } catch (e) {
      setStats({ status: 'error', error: describeError(e) })
    } finally {
      release()
    }
  }, [acquire, release])

  const loadConfig = useCallback(async () => {
    // 配置读取不动线程、也不和 stats 争同一个 state，所以不与实验锁相干：它随时都能读
    setConfig({ status: 'loading' })
    try {
      setConfig({ status: 'ok', data: await getConfig() })
    } catch (e) {
      setConfig({ status: 'error', error: describeError(e) })
    }
  }, [])

  const runCarriers = useCallback(async () => {
    if (!acquire()) return
    setCarriers({ status: 'loading' })
    try {
      setCarriers({ status: 'ok', data: await runCarrierExperiment(CARRIER_TASKS, CARRIER_SLEEP_MS) })
    } catch (e) {
      setCarriers({ status: 'error', error: describeError(e) })
    } finally {
      release()
    }
  }, [acquire, release])

  const runPinning = useCallback(async () => {
    if (!acquire()) return
    setPinning({ status: 'loading' })
    try {
      setPinning({ status: 'ok', data: await runPinExperiment(PIN_HOLDERS, PIN_HOLD_MS) })
    } catch (e) {
      setPinning({ status: 'error', error: describeError(e) })
    } finally {
      release()
    }
  }, [acquire, release])

  const callAsync = useCallback(
    async (message: string, mode: AsyncMode) => {
      const content = message.trim()
      if (!content) return

      // 借用当前激活会话的 conversationId：这样对话记忆是连续的一条，
      // 面板上 "promptTokens 随轮次变大" 那类观察才有意义
      const conversationId = conversationIdRef.current
      // crypto.randomUUID 只在安全上下文可用，内网 http 部署时是 undefined，必须有兜底
      const id =
        typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID()
          : `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`

      setAsyncTurns(prev => [...prev, { id, mode, conversationId, message: content, loading: true }])

      try {
        const { text, elapsedMs } = await askNonStreaming(conversationId, content, mode)
        patchTurn(setAsyncTurns, id, { text, elapsedMs, loading: false })
      } catch (e) {
        patchTurn(setAsyncTurns, id, { error: describeError(e), loading: false })
      } finally {
        // 顺手把线程账目刷一遍：这次请求已经走过 /api/chat/async，用户立刻能在上面的账目卡里
        // 看到"累计请求数"涨了、虚拟线程数动过 —— 这条路径不再是嘴上说说。
        // 说明两点，免得误读：inFlight 是后端在处理期间的数字，响应读完时它已经减回去了，
        // 所以这里刷新看到的多半不是 1；而且请求级 onEnter 只覆盖 /api/chat/* 那条链路，
        // 诊断端点自己不算进去。
        // 不加延后：调用 loadThreads 时 inFlight 守卫一定已经释放（finally 早于后续微任务执行）
        void loadThreads()
      }
    },
    [loadThreads],
  )

  // 首次进入面板时把面板打开就要看的东西拉一遍。用 ref 而不是 state 当守卫：
  // StrictMode 下开发环境会把 effect 跑两遍，state 版本的守卫挡不住第二次（读到的还是旧值）
  useEffect(() => {
    if (!active || initialized.current || !chatConversationId) return
    initialized.current = true
    void loadThreads()
    void loadConfig()
  }, [active, chatConversationId, loadConfig, loadThreads])

  return {
    threadFacts,
    stats,
    config,
    carriers,
    pinning,
    asyncTurns,
    busy,
    refreshThreads: () => void loadThreads(),
    resetPeaks: () => void resetPeaks(),
    loadConfig: () => void loadConfig(),
    runCarriers: () => void runCarriers(),
    runPinning: () => void runPinning(),
    callAsync: (message, mode) => void callAsync(message, mode),
  }
}

/** 就地改一条记录（只有它自己在变，不必整表重建） */
function patchTurn(
  setTurns: React.Dispatch<React.SetStateAction<AsyncTurn[]>>,
  id: string,
  changes: Partial<AsyncTurn>,
): void {
  setTurns(prev => prev.map(t => (t.id === id ? { ...t, ...changes } : t)))
}

/**
 * 把异常翻译成一句用户能照着做的话。
 *
 * api/diag.ts 已经把所有链路层失败包成了 DiagError（含中文说明），
 * 这里的兜底只处理"理论上不该出现"的意外 —— 但一定要有，
 * 否则一个 TypeError 会让面板静默停在 loading 上，比报错更难排查。
 */
function describeError(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
