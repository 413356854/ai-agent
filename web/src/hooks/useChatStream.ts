import { useCallback, useRef, useState } from 'react'
import { streamChat } from '../api/chat'
import { isStreamTimeoutError } from '../api/sse'
import type { TokenUsage } from '../types'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** 该气泡是否还在接收增量 */
  streaming: boolean
  /** 用户点了「停止」，内容可能不完整 */
  stopped?: boolean
  /** 后端 event:error 或网络层错误 */
  error?: string
  /** 失败类别：决定界面上要不要给「重试」按钮 */
  errorKind?: 'timeout' | 'http' | 'business'
  /** done 事件里的 token 用量 */
  usage?: TokenUsage
}

export interface UseChatStreamResult {
  messages: ChatMessage[]
  streaming: boolean
  conversationId: string
  send: (text: string, temperature: number) => void
  stop: () => void
  /** 一键重试最后一轮（重新提问，而不是"让模型接着说"） */
  retry: () => void
  newConversation: () => void
}

/** 是否值得给用户一个「重试」按钮 */
export function isRetryable(kind: ChatMessage['errorKind']): boolean {
  return kind === 'timeout'
}

/**
 * 流式对话状态机。
 *
 * 两个关键实现点：
 *   1. 增量不直接 setState —— 一个 token 一次 setState，一次长回复会把 React 重渲染上百次，
 *      配合 Markdown 解析直接卡顿。做法是先攒进 ref 里的 buffer，用 requestAnimationFrame
 *      合并成「每帧最多渲染一次」。
 *   2. 流结束时必须先把 buffer 冲干净再收尾，否则最后几个字符会丢在 buffer 里。
 */
export function useChatStream(): UseChatStreamResult {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [streaming, setStreaming] = useState(false)
  const [conversationId, setConversationId] = useState(newId)

  const abortRef = useRef<AbortController | null>(null)
  const assistantIdRef = useRef<string | null>(null)
  const bufferRef = useRef('')
  const rafRef = useRef<number | null>(null)
  /** 记住最后一次用的 temperature，重试时沿用（否则重试会偷偷换一套采样参数） */
  const lastTemperatureRef = useRef(0.3)

  /** 立即把 buffer 里的增量写进当前气泡（取消待执行的 rAF） */
  const commit = useCallback(() => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current)
      rafRef.current = null
    }
    const pending = bufferRef.current
    const id = assistantIdRef.current
    bufferRef.current = ''
    if (!pending || !id) return
    setMessages(prev => prev.map(m => (m.id === id ? { ...m, content: m.content + pending } : m)))
  }, [])

  const scheduleCommit = useCallback(() => {
    if (rafRef.current !== null) return
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null
      const pending = bufferRef.current
      const id = assistantIdRef.current
      bufferRef.current = ''
      if (!pending || !id) return
      setMessages(prev => prev.map(m => (m.id === id ? { ...m, content: m.content + pending } : m)))
    })
  }, [])

  const patch = useCallback((id: string, changes: Partial<ChatMessage>) => {
    setMessages(prev => prev.map(m => (m.id === id ? { ...m, ...changes } : m)))
  }, [])

  const send = useCallback(
    async (text: string, temperature: number) => {
      const content = text.trim()
      if (!content || streaming) return

      const assistantId = newId()
      assistantIdRef.current = assistantId
      bufferRef.current = ''
      lastTemperatureRef.current = temperature

      setMessages(prev => [
        ...prev,
        { id: newId(), role: 'user', content, streaming: false },
        { id: assistantId, role: 'assistant', content: '', streaming: true },
      ])
      setStreaming(true)

      const controller = new AbortController()
      abortRef.current = controller

      try {
        await streamChat(
          { conversationId, message: content, temperature },
          {
            onContent: delta => {
              bufferRef.current += delta
              scheduleCommit()
            },
            onDone: usage => {
              commit()
              patch(assistantId, { usage })
            },
            onError: message => {
              commit()
              patch(assistantId, { error: message, errorKind: 'business' })
            },
          },
          controller.signal,
        )
      } catch (e) {
        commit()
        // 主动停止会让 fetch 抛 AbortError —— 这是预期行为，不是故障。
        // 注意判断的是"外层 controller 被 abort"，而不是"任意 AbortError"：
        // 超时在 api 层用的是内部 controller，外层信号没被 abort，
        // 所以超时不会被误判成"用户点了停止"
        if (controller.signal.aborted) {
          patch(assistantId, { stopped: true })
        } else if (isStreamTimeoutError(e)) {
          patch(assistantId, { error: e.message, errorKind: 'timeout' })
        } else {
          patch(assistantId, {
            error: e instanceof Error ? e.message : String(e),
            errorKind: 'http',
          })
        }
      } finally {
        commit()
        patch(assistantId, { streaming: false })
        setStreaming(false)
        abortRef.current = null
        assistantIdRef.current = null
      }
    },
    [commit, conversationId, patch, scheduleCommit, streaming],
  )

  /**
   * 重试最后一轮。
   *
   * 语义是<b>重新提问</b>，不是"让模型接着说"：先把上一轮的用户消息和那个失败的
   * assistant 气泡一起摘掉，再用同样的文本、同样的 conversationId 重发。
   *
   * 为什么必须换掉气泡而不是往里面追加：超时意味着这一轮<b>没有任何有效产出</b>
   * （一个字都没回来）或产出已被判定为不完整，把它留在页面上会让用户以为
   * "模型说过这些"，而重试成功后新旧两段内容并排显示更没法看。
   * conversationId 保持不变是刻意的：失败的那次调用没有写入后端记忆
   * （超时是取消而非完成），所以沿用同一个会话不会污染上下文。
   */
  const retry = useCallback(() => {
    if (streaming) return

    const lastUser = [...messages].reverse().find(m => m.role === 'user')
    if (!lastUser) return

    // 摘掉尾部这一轮（用户消息 + 它的回答），让 send 重新建一对气泡
    setMessages(prev => {
      const cut = prev.lastIndexOf(lastUser)
      return cut === -1 ? prev : prev.slice(0, cut)
    })

    // 不复用 send 的 streaming 守卫：上面已经判过，且此时 setStreaming 还没生效
    void send(lastUser.content, lastTemperatureRef.current)
  }, [messages, send, streaming])

  const stop = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  const newConversation = useCallback(() => {
    abortRef.current?.abort()
    bufferRef.current = ''
    assistantIdRef.current = null
    setMessages([])
    // 新会话必须换 conversationId：后端按它做对话记忆，沿用旧 ID 会把上一轮上下文带进来
    setConversationId(newId())
  }, [])

  return {
    messages,
    streaming,
    conversationId,
    send: (text: string, temperature: number) => void send(text, temperature),
    stop,
    retry,
    newConversation,
  }
}

/**
 * crypto.randomUUID 只在「安全上下文」（https / localhost）可用。
 * 内网 http://192.168.x.x 部署时它是 undefined，所以必须有兜底。
 */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
