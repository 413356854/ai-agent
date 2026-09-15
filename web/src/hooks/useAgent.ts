import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AGENT_APPROVAL_POLL_MS,
  askAgent,
  decideAgentApproval,
  getAgentApprovals,
  getAgentLimits,
  getAgentRunDetail,
  getAgentRuns,
} from '../api/agent'
import type {
  AgentApprovalsResponse,
  AgentApprovalDecisionResponse,
  AgentAskResponse,
  AgentLimitsResponse,
  AgentRunDetail,
  AgentRunsResponse,
} from '../api/agent'
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

  // ---- 第九轮新增：人工确认（HITL）----

  /**
   * 现在有哪些写操作在等人确认。
   *
   * ⚠️ 它**会被轮询**（面板激活期间每 `AGENT_APPROVAL_POLL_MS` 一次），
   * 与这个 hook 里"进面板只拉一次"的另外三项刻意不同 —— 理由见下面 hook 注释里那一节。
   */
  approvals: Remote<AgentApprovalsResponse>
  /** 最近一次决定的结论（成功/失败），null 表示还没决定过 */
  decision: AgentApprovalDecisionResponse | null
  /** 决定请求正在发 */
  deciding: boolean
  /** 决定失败的原因（后端拒绝、超时、记录已被别人处理…） */
  decisionError: string | null

  ask: (question: string) => void
  loadRuns: () => void
  openDetail: (runId: string) => void
  closeDetail: () => void
  loadLimits: () => void
  /** 刷新待办列表（决定之后、或用户点刷新时调用） */
  loadApprovals: () => void
  /**
   * 批准 / 拒绝某一条。
   *
   * 决定之后**立刻刷新待办与运行列表**，因为：
   *   - 待办：那条记录的状态变了，不刷的话界面上它还在（人会以为没生效，然后再点一次 ——
   *     而第二次点会得到一个"已经决定过了"的错误，那体验比第一次就没反应更糟）；
   *   - 运行列表：批准会**唤醒那个正在等 `/ask` 的请求**，它跑完之后状态会从
   *     `WAITING_APPROVAL` 变成 `ANSWERED` —— 但那个变化发生在 `/ask` 的响应里，
   *     这里刷一次能让列表尽快反映出来。
   */
  decide: (approvalId: string, approve: boolean, decidedBy: string, note: string) => void
  /** 清掉上一次决定的结论条（人已经看过了） */
  clearDecision: () => void
}

/**
 * Agent 面板的状态机（阶段三第 5 件 + 第九轮 HITL）。
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
 * <p>⚠️ 但"轮询待办"不违反这条判据：它是纯读库（零外部代价），见下一节。
 *
 * <h2>为什么待办列表要轮询，而别的东西不用（第九轮新增，这是本 hook 唯一"破例"的地方）</h2>
 * 这个项目在其他所有地方都刻意避免轮询：进面板拉一次、点刷新再拉一次。
 * 待办列表是**唯一的例外**，因为它有一个别处没有的性质 —— **有人正卡在它上面**。
 *
 * <p>写操作被闸门拦住时，发起 `/ask` 的那个请求线程**正停在那里等**
 * （最长 `ai.agent.approval.timeout`，默认 120 秒）。所以：
 * 「待办出现了但界面没刷新」的后果不是"看到旧数据"，而是
 * **那个任务会一直停到超时、然后按拒绝处理** —— 人明明坐在页面前，却因为它没显示而失败。
 * 换句话说，这一块的刷新语义与别处相反：别处是"用户想看才拉"，这里是"不拉就会出事"。
 *
 * <p>三条纪律把轮询的代价按住：
 * <ol>
 *   <li><b>只在面板激活时轮询</b>（`active` 为 false 就停）—— 切走不留后台请求；</li>
 *   <li><b>不重叠</b>：上一次还没回来就跳过这一轮（用一个 ref 守卫）。
 *       没有它，慢响应会堆成一串并发请求，而它们的结果还会乱序覆盖；</li>
 *   <li><b>失败不打断轮询，也不把已有数据清掉</b>：保持上一次的 `ok` 结果，
 *       只记一个"上次刷新失败"的标记。轮询失败时把界面清空，等于把一个临时故障
 *       放大成"功能坏了"。</li>
 * </ol>
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

  const [approvals, setApprovals] = useState<Remote<AgentApprovalsResponse>>({ status: 'idle' })
  const [decision, setDecision] = useState<AgentApprovalDecisionResponse | null>(null)
  const [deciding, setDeciding] = useState(false)
  const [decisionError, setDecisionError] = useState<string | null>(null)

  /** 任务锁：ref 用于同 tick 内立刻生效（两次快速点击不能起两个任务）。 */
  const asking = useRef(false)
  const initialized = useRef(false)

  /**
   * 轮询的不重叠守卫。
   *
   * <p>没有它，一个慢响应会让每 2.5 秒叠一个请求，而它们的结果**乱序**回来 ——
   * 于是界面上显示的是某一次较旧的响应，看起来像"刚决定的又变回待办了"。
   * 这类 bug 只在网络慢的时候出现，是最难在本地复现的一种。
   */
  const polling = useRef(false)

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

  /**
   * 拉一次待办。
   *
   * <p>它与 `loadRuns` / `loadLimits` 的写法有一个刻意的差别：**失败时保留旧数据**。
   * 那两个是"用户主动点刷新"，失败就该显示错误；而这个是后台轮询 ——
   * 一次临时失败把整个待办区清空，会让人以为"待办没了"，而实际上
   * **那个任务还在那里等人**（并且在倒计时）。
   * 所以这里失败时只在旧的 `ok` 数据上挂一个标记，不清数据。
   */
  const loadApprovals = useCallback(async () => {
    if (polling.current) return
    polling.current = true
    try {
      const data = await getAgentApprovals()
      setApprovals({ status: 'ok', data })
    }
    catch (e) {
      const message = describeError(e)
      setApprovals(prev => (prev.status === 'ok' ? prev : { status: 'error', error: message }))
    }
    finally {
      polling.current = false
    }
  }, [])

  const ask = useCallback(
    async (question: string) => {
      const content = question.trim()
      if (!content) return
      // 守卫必须在同一个 tick 内生效：await 之后再读 ref 就晚了。
      // 这个面板上"点两次"的代价是**跑两个任务、花两份钱**，比别处更该防
      if (asking.current) return
      asking.current = true
      setRunning(true)
      setCurrent({ status: 'loading' })
      // 开工前先把待办刷一次：如果上一次任务留下了一条待批，
      // 人应当在按下"跑一次"的瞬间就看到它（而不是等 2.5 秒后的下一次轮询）
      void loadApprovals()
      try {
        const data = await askAgent(content)
        setCurrent({ status: 'ok', data })
        // 跑完顺手刷一次列表：刚跑的任务应该立刻出现在历史里。
        // 不刷的话用户会以为"没记下来"（而它其实记了）——
        // 这种"界面没更新 vs 功能没做"的混淆，正是这个项目里反复强调的一类误判
        void loadRuns()
        // 待办也刷一次：这次任务可能自己留下了一条待批（stopReason=WAITING_FOR_APPROVAL）
        void loadApprovals()
      }
      catch (e) {
        setCurrent({ status: 'error', error: describeError(e) })
      }
      finally {
        asking.current = false
        setRunning(false)
      }
    },
    [loadRuns, loadApprovals],
  )

  const decide = useCallback(
    async (approvalId: string, approve: boolean, decidedBy: string, note: string) => {
      if (deciding) return
      setDeciding(true)
      setDecisionError(null)
      try {
        const result = await decideAgentApproval(approvalId, approve, decidedBy, note)
        setDecision(result)
        if (!result.ok) {
          // 后端明确说"这次决定没有被采纳"（记录已被超时或另一个人抢先写入）——
          // 这不是异常，而是一个**需要立刻让用户看到**的结论
          setDecisionError(result.error ?? '这次决定没有被采纳')
        }
      }
      catch (e) {
        setDecisionError(describeError(e))
      }
      finally {
        setDeciding(false)
        // 无论成败都刷：失败最常见的原因就是"记录已经不是待批了"，
        // 那正是待办列表需要更新的时刻
        void loadApprovals()
        void loadRuns()
      }
    },
    [deciding, loadApprovals, loadRuns],
  )

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

  const clearDecision = useCallback(() => {
    setDecision(null)
    setDecisionError(null)
  }, [])

  // 首次进入面板：只拉只读的那几项（理由见上面的 hook 注释）。
  // 用 ref 当守卫而不是 state：StrictMode 下 effect 会跑两遍，state 守卫挡不住第二次
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadRuns()
    void loadLimits()
    void loadApprovals()
  }, [active, loadRuns, loadLimits, loadApprovals])

  /**
   * 待办的轮询（只在面板激活时）。
   *
   * <p>用 `setInterval` 而不是递归 `setTimeout`：间隔是固定的，
   * 而"不重叠"由 `loadApprovals` 里的守卫保证 —— 两者各管一件事，
   * 混在一起（例如"等上一次回来再排下一次"）会让间隔在慢响应时被悄悄拉长，
   * 而那个拉长的后果直接等于任务多等的时间。
   *
   * <p>清理函数必须清掉定时器：StrictMode 下这个 effect 会挂载两次，
   * 不清理就会留下一个永不停止的轮询 —— 而它不会有任何报错，只是偷偷多打一倍请求。
   */
  useEffect(() => {
    if (!active) return
    const timer = setInterval(() => void loadApprovals(), AGENT_APPROVAL_POLL_MS)
    return () => clearInterval(timer)
  }, [active, loadApprovals])

  return {
    current,
    runs,
    detail,
    limits,
    running,
    approvals,
    decision,
    deciding,
    decisionError,
    ask: q => void ask(q),
    loadRuns: () => void loadRuns(),
    openDetail: id => void openDetail(id),
    closeDetail,
    loadLimits: () => void loadLimits(),
    loadApprovals: () => void loadApprovals(),
    decide: (id, approve, by, note) => void decide(id, approve, by, note),
    clearDecision,
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
