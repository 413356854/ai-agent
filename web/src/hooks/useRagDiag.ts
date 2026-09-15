import { useCallback, useEffect, useRef, useState } from 'react'
import {
  compareStrategies,
  getCorpus,
  getEvalSet,
  getPreview,
  getTokens,
  retrieve as retrieveApi,
} from '../api/rag'
import type {
  CompareResponse,
  CorpusResponse,
  EvalSetResponse,
  PreviewResponse,
  RetrieveResponse,
  TokensResponse,
} from '../api/rag'
import type { Remote } from '../types'

/** 默认要预览/检索的策略 —— 与后端 RagProperties 里两个策略名一致 */
export const DEFAULT_STRATEGY = 'structural'

/** 对照组的策略名。与后端 RagEvaluationService.BASELINE_STRATEGY 必须一致 */
export const BASELINE_STRATEGY = 'fixed-size'

export interface UseRagDiagResult {
  corpus: Remote<CorpusResponse>
  evalSet: Remote<EvalSetResponse>
  preview: Remote<PreviewResponse>
  tokens: Remote<TokensResponse>
  retrieval: Remote<RetrieveResponse>
  comparison: Remote<CompareResponse>

  /** 当前选中的分块策略（预览与检索共用） */
  strategy: string
  /** 对照评测正在跑 */
  running: boolean
  /** 最近一次检索用的提问（结果卡要显示"这条是问什么问出来的"） */
  lastQuery: string

  loadCorpus: () => void
  loadEvalSet: () => void
  setStrategy: (strategy: string) => void
  runPreview: (strategy?: string) => void
  analyzeTokens: (text: string) => void
  search: (query: string) => void
  runComparison: () => void
}

/**
 * RAG 诊断面板的状态机。
 *
 * <h2>为什么整个 hook 挂在 App 上</h2>
 * 与 useChatStream / useStructuredChat / useThreadDiag 同一个理由：
 * **切模式不能丢结果**。这个面板里最贵的一项是「分块策略对照」——
 * 它要在后端真跑一遍全量评测（分块 + 建索引 + 43 条用例 × 2 个策略检索）。
 * 如果状态挂在面板组件里，用户切去看一眼流式再切回来就全没了，
 * 只能重跑一遍。挂在 App 上之后，切模式只是换渲染。
 *
 * <h2>为什么首次加载要等 active</h2>
 * 挂在 App 上就意味着页面一打开就会执行。用户如果一直待在流式模式，
 * 语料解析（4 份文档、几百个块）+ 评测集加载这两次请求纯属白打。
 * 所以推迟到"面板第一次真正显示"，之后不再受 active 影响。
 *
 * <h2>为什么对照评测要独占一把锁，而其它几个不用</h2>
 * `/compare` 会在后端把两种策略各跑一遍、并各自建一份内存索引，
 * 期间 CPU 和内存都是满的。这时再并发打 `/retrieve` 只会让两边都变慢，
 * 而且**数字会互相影响**（检索耗时虽然不进指标，但用户会以为界面卡了）。
 * 其它几个端点（语料 / 预览 / 分词）是轻量只读的，不抢锁 ——
 * 全串起来反而会让"点一下分词"也要排队，体验更差。
 */
export function useRagDiag(active: boolean): UseRagDiagResult {
  const [corpus, setCorpus] = useState<Remote<CorpusResponse>>({ status: 'idle' })
  const [evalSet, setEvalSet] = useState<Remote<EvalSetResponse>>({ status: 'idle' })
  const [preview, setPreview] = useState<Remote<PreviewResponse>>({ status: 'idle' })
  const [tokens, setTokens] = useState<Remote<TokensResponse>>({ status: 'idle' })
  const [retrieval, setRetrieval] = useState<Remote<RetrieveResponse>>({ status: 'idle' })
  const [comparison, setComparison] = useState<Remote<CompareResponse>>({ status: 'idle' })
  const [strategy, setStrategy] = useState(DEFAULT_STRATEGY)
  const [lastQuery, setLastQuery] = useState('')

  /** 对照评测专用的锁；ref 用于同 tick 内立刻生效，state 用于驱动按钮禁用 */
  const comparing = useRef(false)
  const [running, setRunning] = useState(false)
  const initialized = useRef(false)

  const loadCorpus = useCallback(async () => {
    setCorpus({ status: 'loading' })
    try {
      setCorpus({ status: 'ok', data: await getCorpus() })
    } catch (e) {
      setCorpus({ status: 'error', error: describeError(e) })
    }
  }, [])

  const loadEvalSet = useCallback(async () => {
    setEvalSet({ status: 'loading' })
    try {
      setEvalSet({ status: 'ok', data: await getEvalSet() })
    } catch (e) {
      setEvalSet({ status: 'error', error: describeError(e) })
    }
  }, [])

  const runPreview = useCallback(async (which?: string) => {
    const target = which ?? DEFAULT_STRATEGY
    setPreview({ status: 'loading' })
    try {
      setPreview({ status: 'ok', data: await getPreview(target, 3) })
    } catch (e) {
      setPreview({ status: 'error', error: describeError(e) })
    }
  }, [])

  const analyzeTokens = useCallback(async (text: string) => {
    const content = text.trim()
    if (!content) return
    setTokens({ status: 'loading' })
    try {
      setTokens({ status: 'ok', data: await getTokens(content) })
    } catch (e) {
      setTokens({ status: 'error', error: describeError(e) })
    }
  }, [])

  const search = useCallback(async (query: string) => {
    const content = query.trim()
    if (!content) return
    // 提问先记下来：结果卡要显示"这是问什么问出来的"，
    // 而失败时（status=error）响应体里没有 query，只有这个 state 还记得
    setLastQuery(content)
    setRetrieval({ status: 'loading' })
    try {
      const data = await retrieveApi(content, DEFAULT_STRATEGY, 0)
      setRetrieval({ status: 'ok', data })
    } catch (e) {
      setRetrieval({ status: 'error', error: describeError(e) })
    }
  }, [])

  const runComparison = useCallback(async () => {
    // 守卫必须在同一个 tick 内生效：await 之后再读 ref 就晚了，
    // 两次快速点击会同时通过检查，然后两个全量评测并行跑
    if (comparing.current) return
    comparing.current = true
    setRunning(true)
    setComparison({ status: 'loading' })
    try {
      setComparison({ status: 'ok', data: await compareStrategies(0) })
    } catch (e) {
      setComparison({ status: 'error', error: describeError(e) })
    } finally {
      comparing.current = false
      setRunning(false)
    }
  }, [])

  // 首次进入面板时把"打开就该看到"的内容拉一遍。用 ref 当守卫而不是 state：
  // StrictMode 下开发环境会把 effect 跑两遍，state 版本的守卫挡不住第二次（读到的还是旧值）
  //
  // 为什么连**分块预览**也一起自动拉：
  //   - 语料解析给的是统计数字（几份文档、多少个块），而"块到底长什么样"要看预览；
  //     用户进这个面板的第一件事就是想看这个，让他先点一次按钮纯属多一道手续；
  //   - 更要紧的是**它让界面上永远有内容**：一个只有统计数字的面板，
  //     和"分块没跑起来"在视觉上几乎一样 —— 而自检脚本里那种
  //     "点了按钮才出内容"的路径，也很容易在改动中被静默跳过（本次就踩到了：
  //     断言在按钮点击之前跑，拿到 0 条，看起来像"结构分块没产出块"）。
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadCorpus()
    void loadEvalSet()
    // 预览用默认策略先拉一份；切策略时由界面再触发一次
    void runPreview(DEFAULT_STRATEGY)
  }, [active, loadCorpus, loadEvalSet, runPreview])

  return {
    corpus,
    evalSet,
    preview,
    tokens,
    retrieval,
    comparison,
    strategy,
    running,
    lastQuery,
    loadCorpus: () => void loadCorpus(),
    loadEvalSet: () => void loadEvalSet(),
    setStrategy,
    runPreview: which => void runPreview(which),
    analyzeTokens: text => void analyzeTokens(text),
    search: query => void search(query),
    runComparison: () => void runComparison(),
  }
}

/**
 * 把异常翻译成一句用户能照着做的话。
 *
 * api/rag.ts 已经把链路层失败包成了 DiagError（含中文说明），
 * 这里的兜底只处理"理论上不该出现"的意外 —— 但一定要有：
 * 否则一个 TypeError 会让面板静默停在 loading 上，比报错更难排查。
 */
function describeError(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
