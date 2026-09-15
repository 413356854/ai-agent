import { useCallback, useEffect, useRef, useState } from 'react'
import { askAgent, getAgentLimits, getAgentRunDetail, getAgentRuns } from '../api/agent'
import type { AgentAskResponse, AgentLimitsResponse, AgentRunDetail, AgentRunsResponse } from '../api/agent'
import type { Remote } from '../types'

export interface UseAgentResult {
  /** 当前这一轮任务的结果（含每步、每次工具调用） */
  current: Remote<AgentAskResponse>
  /** 历史运行列表 */
  runs: Remote<AgentRunsResponse>
  /** 点开某一次运行的完整过程；null 表示没在看详情 */
  detail: Remote<AgentRunDetail>
  /** 四层保护的状态 */
  limits: Remote<AgentLimitsResponse>
  /** 任务正在跑 */
  running: boolean

  ask: (question: string) => void
  loadRuns: () => void
  openDetail: (runId: string) => void
  closeDetail: () => void
  loadLimits: () => void
}

/**
 * Agent 面板的状态机（阶段三第 5 件）。
 *
 * <h2>为什么整个 hook 挂在 App 上</h2>
 * 与另外四个 hook 同一个理由：**切模式不能丢结果**。
 * 一次 Agent 任务最多 6 轮模型往返、要花钱、要等十几秒 ——
 * 用户切去看一眼 RAG 再切回来就全没了，只能重跑一遍（再花一次钱）。
 *
 * <h2>为什么首次进入只自动拉"只读"的三项，不自动跑任务</h2>
 * `/ask` 会**真的调模型花钱**，所以它必须由用户明确点。
 * 而列表 / 限额这两项是纯读库，进面板就该看到（否则页面是空的，
 * 而空页面看起来像"功能没做"）。
 *
 * <p>这里与对齐实验那次的取舍不同（那次我把自动跑改成了默认执行），
 * 差别在于**代价的性质**：对齐实验是本地 CPU（不花钱、不动外部系统），
 * 而 `/ask` 是"调模型 + 执行工具"—— **会花钱、可能有副作用**。
 * 一条简单的判据：**自动执行的前提是"零外部代价"**。
 *
 * <h2>为什么详情单独一个 Remote 态</h2>
 * "看某一次历史运行的详情"与"当前这次任务的结果"是两件事，
 * 混成一个 state 会让"打开详情 → 关掉"把当前结果也清掉，
 * 而那个结果是刚花了钱跑出来的。
 */
export function useAgent(active: boolean): UseAgentResult {
  const [current, setCurrent] = useState<Remote<AgentAskResponse>>({ status: 'idle' })
  const [runs, setRuns] = useState<Remote<AgentRunsResponse>>({ status: 'idle' })
  const [detail, setDetail] = useState<Remote<AgentRunDetail>>({ status: 'idle' })
  const [limits, setLimits] = useState<Remote<AgentLimitsResponse>>({ status: 'idle' })
  const [running, setRunning] = useState(false)

  /** 任务锁：ref 用于同 tick 内立刻生效（两次快速点击不能起两个任务）。 */
  const asking = useRef(false)
  const initialized = useRef(false)

  const loadRuns = useCallback(async () => {
    setRuns({ status: 'loading' })
    try {
      setRuns({ status: 'ok', data: await getAgentRuns(20) })
    }
    catch (e) {
      setRuns({ status: 'error', error: describeError(e) })
    }
  }, [])

  const loadLimits = useCallback(async () => {
    setLimits({ status: 'loading' })
    try {
      setLimits({ status: 'ok', data: await getAgentLimits() })
    }
    catch (e) {
      setLimits({ status: 'error', error: describeError(e) })
    }
  }, [])

  const ask = useCallback(async (question: string) => {
    const content = question.trim()
    if (!content) return
    // 守卫必须在同一个 tick 内生效：await 之后再读 ref 就晚了。
    // 这个面板上"点两次"的代价是**跑两个任务、花两份钱**，比别处更该防
    if (asking.current) return
    asking.current = true
    setRunning(true)
    setCurrent({ status: 'loading' })
    try {
      const data = await askAgent(content)
      setCurrent({ status: 'ok', data })
      // 跑完顺手刷一次列表：刚跑的任务应该立刻出现在历史里。
      // 不刷的话用户会以为"没记下来"（而它其实记了）——
      // 这种"界面没更新 vs 功能没做"的混淆，正是这个项目里反复强调的一类误判
      void loadRuns()
    }
    catch (e) {
      setCurrent({ status: 'error', error: describeError(e) })
    }
    finally {
      asking.current = false
      setRunning(false)
    }
  }, [loadRuns])

  const openDetail = useCallback(async (runId: string) => {
    setDetail({ status: 'loading' })
    try {
      setDetail({ status: 'ok', data: await getAgentRunDetail(runId) })
    }
    catch (e) {
      setDetail({ status: 'error', error: describeError(e) })
    }
  }, [])

  const closeDetail = useCallback(() => setDetail({ status: 'idle' }), [])

  // 首次进入面板：只拉只读的两项（理由见上面的 hook 注释）。
  // 用 ref 当守卫而不是 state：StrictMode 下 effect 会跑两遍，state 守卫挡不住第二次
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadRuns()
    void loadLimits()
  }, [active, loadRuns, loadLimits])

  return {
    current,
    runs,
    detail,
    limits,
    running,
    ask: q => void ask(q),
    loadRuns: () => void loadRuns(),
    openDetail: id => void openDetail(id),
    closeDetail,
    loadLimits: () => void loadLimits(),
  }
}

/**
 * 把异常翻译成一句用户能照着做的话。
 *
 * 与另外三个 hook 同一条兜底理由：api 层已经把链路层失败包成了 DiagError（含中文说明），
 * 这里的兜底只处理"理论上不该出现"的意外 —— 但一定要有，
 * 否则一个 TypeError 会让面板静默停在 loading 上，比报错更难排查。
 */
function describeError(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
