import { useCallback, useEffect, useRef, useState } from 'react'
import {
  PromptConflictError,
  PromptRejectedError,
  promptApi,
} from '../api/prompt'
import type {
  PromptActionResult,
  PromptCheck,
  PromptDiffResponse,
  PromptInfo,
  PromptKind,
  PromptVersionSummary,
} from '../api/prompt'
import type { Remote } from '../types'

/** 界面上可选的两份提示词。顺序就是页面上显示的顺序。 */
export const PROMPT_KINDS: PromptKind[] = ['system-agent-tools', 'system-agent']

export interface UsePromptResult {
  /** 提示词清单（含各自当前生效的版本）。 */
  list: Remote<PromptInfo[]>
  hotReload: { takesEffect: string[]; doesNotAffect: string[] } | null
  notes: string[]

  kind: PromptKind
  select: (kind: PromptKind) => void

  /** 当前这一份的版本历史（降序）。 */
  versions: Remote<PromptVersionSummary[]>
  /** 正在编辑的那一版正文（**草稿**，可能已经被改过）。 */
  draft: string
  setDraft: (text: string) => void
  /** 草稿相对它来源的那一版是否被改过 —— 「存」按钮据此启用。 */
  dirty: boolean
  /** 编辑器里显示的是哪一版（点了某一行"编辑"之后）。 */
  editingVersion: number | null
  /** 载入某一版的正文到编辑器（会丢掉未保存的草稿）。 */
  editVersion: (version: number) => Promise<void>

  /** 改动说明（必填，后端会拒）。 */
  note: string
  setNote: (note: string) => void
  /** 确认放行"警告级"判据（例如确实要把提示词压得很短）。 */
  force: boolean
  setForce: (force: boolean) => void

  /** 上一次操作的反馈。 */
  action: Remote<{ kind: 'saved' | 'activated' | 'rolledback'; message: string; result?: PromptActionResult }>
  /** 保存被拒时后端给的全部判据（含通过的项）。 */
  rejections: PromptCheck[]
  warnings: PromptCheck[]
  /** 并发冲突的提示（409）。它与"内容不合规"是两件事，界面必须分开说。 */
  conflict: string | null
  clearFeedback: () => void

  /** 存一个新版本（**不生效**）。 */
  save: () => Promise<void>
  /** 让某一版生效。 */
  activate: (version: number) => Promise<void>
  /** 回滚到某一版（后端会**新增**一版）。 */
  rollback: (version: number) => Promise<void>

  /** 版本对比。 */
  diff: Remote<PromptDiffResponse>
  diffPair: { from: number; to: number } | null
  compare: (from: number, to: number) => Promise<void>
  clearDiff: () => void

  busy: boolean
  reload: () => void
}

/**
 * Prompt 版本管理的状态机（阶段四 · 工程化第 3 件）。
 *
 * <h2>为什么草稿、说明、force 都在这个 hook 里，而不是留在面板的 useState</h2>
 * 因为"存"这个动作要用到它们三个，而后端会<b>因为说明为空而拒绝</b>。
 * 放在面板里的话，面板就要把三个值当作参数传给 save —— 而"哪个参数忘了传"
 * 在 TypeScript 里只要类型对就查不出来（比如把 force 传成 note）。
 *
 * <h2>为什么"上一版 → 最新版"这个规则不在前端实现</h2>
 * 后端 {@code /diff} 在省略 from/to 时自己会取"上一版 → 最新版"。
 * 前端再算一遍的话，两处规则迟早不一致 —— 而那时两边都"看起来对"，
 * 只是某一版的差异显示的是另一对版本之间的东西。这与成本看板那条
 * "金额只在 (天, 模型) 这个粒度上算"是同一种纪律：<b>规则只留一处</b>。
 *
 * <h2>为什么 400 与 409 要分别保存</h2>
 * 它们对应的**下一步动作完全相反**：
 * <ul>
 *   <li>400（内容不合规）→ 改内容。界面上要逐条列出"哪一条没过、具体数字是多少"；</li>
 *   <li>409（前提过期）→ <b>刷新</b>。重试同一个请求永远不会成功，
 *       而错误信息里必须带上"现在是第几版"。</li>
 * </ul>
 * 两者压成一句红字的后果很具体：使用者会对着 409 一遍遍点同一个按钮。
 * 这与阶段一那条"分清 429/5xx 和 4xx"是同一类判断。
 */
export function usePrompt(active: boolean): UsePromptResult {
  const [list, setList] = useState<Remote<PromptInfo[]>>({ status: 'idle' })
  const [hotReload, setHotReload] = useState<UsePromptResult['hotReload']>(null)
  const [notes, setNotes] = useState<string[]>([])

  const [kind, setKind] = useState<PromptKind>('system-agent-tools')
  const [versions, setVersions] = useState<Remote<PromptVersionSummary[]>>({ status: 'idle' })

  const [draft, setDraft] = useState('')
  const [editingVersion, setEditingVersion] = useState<number | null>(null)
  /** 草稿来源那一版的正文 —— "有没有改过"必须与它比，而不是与"上一次渲染"比。 */
  const pristine = useRef('')
  const dirty = draft !== pristine.current

  const [note, setNote] = useState('')
  const [force, setForce] = useState(false)

  const [action, setAction] = useState<UsePromptResult['action']>({ status: 'idle' })
  const [rejections, setRejections] = useState<PromptCheck[]>([])
  const [warnings, setWarnings] = useState<PromptCheck[]>([])
  const [conflict, setConflict] = useState<string | null>(null)

  const [diff, setDiff] = useState<Remote<PromptDiffResponse>>({ status: 'idle' })
  const [diffPair, setDiffPair] = useState<{ from: number; to: number } | null>(null)

  const [busy, setBusy] = useState(false)
  const initialized = useRef(false)

  /**
   * 当前选中的 kind 放在 ref 里，供 `load()` 读取。
   *
   * <h2>为什么（与 useCost 里那个 windowRef 是同一个坑）</h2>
   * 直觉写法是 `useCallback(..., [kind])`。那样在 `select(k)` 里先 `setKind(k)` 再 `load()` 时，
   * 调用的还是**上一次渲染**那个闭包 —— 于是界面显示的是 A、列表里是 B，
   * 而<b>两边看起来都正常</b>（就是内容对不上，像"切了没反应"）。
   */
  const kindRef = useRef<PromptKind>(kind)

  const loadList = useCallback(async () => {
    setList({ status: 'loading' })
    try {
      const data = await promptApi.list()
      setList({ status: 'ok', data: data.prompts })
      setHotReload(data.hotReload)
      setNotes(data.notes)
    }
    catch (e) {
      setList({ status: 'error', error: describeError(e) })
    }
  }, [])

  const loadVersions = useCallback(async () => {
    setVersions({ status: 'loading' })
    try {
      const data = await promptApi.versions(kindRef.current)
      setVersions({ status: 'ok', data: data.versions })
    }
    catch (e) {
      setVersions({ status: 'error', error: describeError(e) })
    }
  }, [])

  /** 清掉上一次操作的反馈 —— 否则用户改了内容之后，屏幕上还挂着上一轮的红字。 */
  const clearFeedback = useCallback(() => {
    setAction({ status: 'idle' })
    setRejections([])
    setWarnings([])
    setConflict(null)
  }, [])

  const editVersion = useCallback(async (version: number) => {
    setBusy(true)
    try {
      const data = await promptApi.version(kindRef.current, version)
      setDraft(data.version.content)
      pristine.current = data.version.content
      setEditingVersion(version)
      clearFeedback()
    }
    catch (e) {
      setAction({ status: 'error', error: describeError(e) })
    }
    finally {
      setBusy(false)
    }
  }, [clearFeedback])

  const select = useCallback((next: PromptKind) => {
    // 【顺序：先改 ref 再触发加载】反了就会拉到上一个 kind 的版本列表
    kindRef.current = next
    setKind(next)
    // 切走时把编辑器清空：留着上一份的草稿会让人以为"这份提示词现在是这个内容"，
    // 而它其实属于另一份 —— 那种混淆没有任何提示能救回来
    setDraft('')
    pristine.current = ''
    setEditingVersion(null)
    clearFeedback()
    setDiff({ status: 'idle' })
    setDiffPair(null)
    void loadVersions()
  }, [clearFeedback, loadVersions])

  const save = useCallback(async () => {
    setBusy(true)
    clearFeedback()
    try {
      const result = await promptApi.save(kindRef.current, {
        content: draft,
        note,
        author: 'web-ui',
        force,
      })
      setAction({
        status: 'ok',
        data: { kind: 'saved', message: result.note },
      })
      // 【含警告也要显示】警告级判据的整个意义就是"放行了但你要知道"。
      // 只显示被拒的那部分，等于把"这次改动有可疑之处"这个信息丢掉
      setWarnings(result.validation.checks.filter(c => c.level === 'WARN'))
      setNote('')
      setForce(false)
      await loadVersions()
      await loadList()
    }
    catch (e) {
      if (e instanceof PromptRejectedError) {
        setRejections(e.checks.filter(c => c.level !== 'OK'))
        setAction({ status: 'error', error: e.message })
      }
      else {
        setAction({ status: 'error', error: describeError(e) })
      }
    }
    finally {
      setBusy(false)
    }
  }, [clearFeedback, draft, force, loadList, loadVersions, note])

  /**
   * 取当前生效的版本号 —— 并发保护要用的那个 `expectedVersion`。
   *
   * 从**刚加载的列表**里读，而不是从 state 快照里读：
   * 用旧快照就等于"我以为没人动过"，而那正是静默覆盖发生的前提。
   */
  const currentActive = useCallback((): number | null => {
    if (versions.status !== 'ok') return null
    const activeRow = versions.data.find(v => v.active)
    return activeRow ? activeRow.version : null
  }, [versions])

  const activate = useCallback(async (version: number) => {
    setBusy(true)
    clearFeedback()
    try {
      const result = await promptApi.activate(kindRef.current, {
        version,
        expectedVersion: currentActive(),
        author: 'web-ui',
      })
      setAction({ status: 'ok', data: { kind: 'activated', message: result.message, result } })
      await loadVersions()
      await loadList()
    }
    catch (e) {
      if (e instanceof PromptConflictError) {
        setConflict(e.message)
      }
      else {
        setAction({ status: 'error', error: describeError(e) })
      }
    }
    finally {
      setBusy(false)
    }
  }, [clearFeedback, currentActive, loadList, loadVersions])

  const rollback = useCallback(async (version: number) => {
    setBusy(true)
    clearFeedback()
    try {
      const result = await promptApi.rollback(kindRef.current, {
        version,
        author: 'web-ui',
        note: `从界面回滚到第 ${version} 版`,
      })
      // 【为什么显示 `result.message` 而不是 `result.note`】
      // `message` 是后端对这一**动作**的结论（"回滚：第 2 版的内容被复制为新的一版（v4）并生效"），
      // 两个版本号都在里面 —— 它才是"这次做了什么"的权威描述。
      // `note` 是补充说明（为什么这么设计），适合放在下面当解释，不适合当标题。
      //
      // 上一版这里用的是 `result.note ?? result.message`，于是界面上只出现那句解释，
      // 而"回滚到了第几版、产生了哪一版"这个最要紧的信息**丢在了接口里没人用** ——
      // 自检里的断言因此红了，而它指出的其实是真问题：**接口给了正确的话，界面没用它**。
      // 这是"两处各写一份文案"最典型的形态：改一处不会让另一处跟着变。
      setAction({ status: 'ok', data: { kind: 'rolledback', message: result.message, result } })
      await loadVersions()
      await loadList()
    }
    catch (e) {
      setAction({ status: 'error', error: describeError(e) })
    }
    finally {
      setBusy(false)
    }
  }, [clearFeedback, loadList, loadVersions])

  const compare = useCallback(async (from: number, to: number) => {
    setBusy(true)
    setDiff({ status: 'loading' })
    setDiffPair({ from, to })
    try {
      setDiff({ status: 'ok', data: await promptApi.diff(kindRef.current, from, to) })
    }
    catch (e) {
      setDiff({ status: 'error', error: describeError(e) })
    }
    finally {
      setBusy(false)
    }
  }, [])

  const clearDiff = useCallback(() => {
    setDiff({ status: 'idle' })
    setDiffPair(null)
  }, [])

  // 首次进入面板：拉清单 + 当前这一份的版本历史。
  // 【不轮询】这一页的数据只被本页面自己改（别人改了会以 409 的形式告诉我们），
  // 而"有人在等它"这个理由（HITL 待办轮询的唯一理由）在这里不成立
  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadList()
    void loadVersions()
  }, [active, loadList, loadVersions])

  return {
    list,
    hotReload,
    notes,
    kind,
    select,
    versions,
    draft,
    setDraft,
    dirty,
    editingVersion,
    editVersion,
    note,
    setNote,
    force,
    setForce,
    action,
    rejections,
    warnings,
    conflict,
    clearFeedback,
    save,
    activate,
    rollback,
    diff,
    diffPair,
    compare,
    clearDiff,
    busy,
    reload: () => {
      void loadList()
      void loadVersions()
    },
  }
}

function describeError(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
