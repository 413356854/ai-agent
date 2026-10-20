/**
 * Graph 演示页的数据状态。
 *
 * <h2>与其它 hook 相同的两条约束</h2>
 * <ol>
 *   <li>只在 `active` 第一次为真时拉 meta；切走再切回来不重新请求；</li>
 *   <li>run / replay 用 `busyRef` 防连点 —— 服务端还有一道 409，
 *       但前端先挡住能少发一次真实模型请求。</li>
 * </ol>
 *
 * <h2>replay 失败时保留上一次结果</h2>
 * 重放失败只改 feedback，不清空 `result`：把整页结果清掉会让用户以为原来那次运行也没了，
 * 而它明明还在检查点里。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import type { Remote } from '../types'
import {
  graphApi,
  type GraphMetaResponse,
  type GraphRunRequest,
  type GraphRunResponse,
} from '../api/graph'

export interface GraphFeedback {
  ok: boolean
  message: string
}

export interface UseGraphDemoResult {
  meta: Remote<GraphMetaResponse>
  result: Remote<GraphRunResponse>
  feedback: Remote<GraphFeedback>
  busy: boolean
  replaying: string | null
  loadMeta: () => void
  run: (request: GraphRunRequest) => Promise<boolean>
  replay: (threadId: string, checkpointId: string) => Promise<boolean>
  clearFeedback: () => void
}

export function useGraphDemo(active: boolean): UseGraphDemoResult {
  const [meta, setMeta] = useState<Remote<GraphMetaResponse>>({ status: 'idle' })
  const [result, setResult] = useState<Remote<GraphRunResponse>>({ status: 'idle' })
  const [feedback, setFeedback] = useState<Remote<GraphFeedback>>({ status: 'idle' })
  const [busy, setBusy] = useState(false)
  const [replaying, setReplaying] = useState<string | null>(null)

  const initialized = useRef(false)
  const busyRef = useRef(false)

  const loadMeta = useCallback(async () => {
    setMeta({ status: 'loading' })
    try {
      setMeta({ status: 'ok', data: await graphApi.meta() })
    }
    catch (e) {
      setMeta({ status: 'error', error: describeError(e) })
    }
  }, [])

  useEffect(() => {
    if (!active || initialized.current) return
    initialized.current = true
    void loadMeta()
  }, [active, loadMeta])

  const run = useCallback(async (request: GraphRunRequest) => {
    if (busyRef.current) return false
    busyRef.current = true
    setBusy(true)
    setResult({ status: 'loading' })
    setFeedback({ status: 'idle' })
    try {
      const data = await graphApi.run(request)
      setResult({ status: 'ok', data })
      setFeedback({
        status: 'ok',
        data: {
          ok: true,
          message: `运行完成：${data.nodes.length} 个节点，耗时 ${(data.elapsedMs / 1000).toFixed(1)} 秒`,
        },
      })
      return true
    }
    catch (e) {
      const message = describeError(e)
      setResult({ status: 'error', error: message })
      setFeedback({ status: 'error', error: message })
      return false
    }
    finally {
      busyRef.current = false
      setBusy(false)
    }
  }, [])

  const replay = useCallback(async (threadId: string, checkpointId: string) => {
    if (busyRef.current) return false
    busyRef.current = true
    setBusy(true)
    setReplaying(checkpointId)
    setFeedback({ status: 'idle' })
    try {
      const data = await graphApi.replay(threadId, checkpointId)
      setResult({ status: 'ok', data })
      setFeedback({
        status: 'ok',
        data: {
          ok: true,
          message: `已从检查点回放：重放 ${data.replayedNodeIds.length} 个节点`,
        },
      })
      return true
    }
    catch (e) {
      setFeedback({ status: 'error', error: describeError(e) })
      return false
    }
    finally {
      busyRef.current = false
      setBusy(false)
      setReplaying(null)
    }
  }, [])

  const clearFeedback = useCallback(() => setFeedback({ status: 'idle' }), [])

  return { meta, result, feedback, busy, replaying, loadMeta: () => void loadMeta(), run, replay, clearFeedback }
}

function describeError(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
