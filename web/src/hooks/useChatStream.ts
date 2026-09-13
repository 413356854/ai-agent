import { useCallback, useRef, useState } from 'react'
import { streamChat } from '../api/chat'
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
  /** done 事件里的 token 用量 */
  usage?: TokenUsage
}

export interface UseChatStreamResult {
  messages: ChatMessage[]
  streaming: boolean
  conversationId: string
  send: (text: string, temperature: number) => void
  stop: () => void
  newConversation: () => void
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
              patch(assistantId, { error: message })
            },
          },
          controller.signal,
        )
      } catch (e) {
        // 主动停止会让 fetch 抛 AbortError —— 这是预期行为，不是故障
        if (controller.signal.aborted) {
          commit()
          patch(assistantId, { stopped: true })
        } else {
          commit()
          patch(assistantId, { error: e instanceof Error ? e.message : String(e) })
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
