import { useCallback, useState } from 'react'
import { STRUCTURED_TIMEOUT_MS, StructuredHttpError, askStructured } from '../api/structured'
import { isStreamTimeoutError } from '../api/sse'
import type { StructuredTurn } from '../types'

export interface UseStructuredChatResult {
  turns: StructuredTurn[]
  /** 至少有一轮在请求中 */
  loading: boolean
  conversationId: string
  ask: (question: string) => void
  newConversation: () => void
}

/**
 * 结构化输出状态机。
 *
 * 与 {@link useChatStream} 的关键差异（决定了它不能复用那个 hook）：
 *   1. **没有"增量"概念**：一次请求要么拿到完整结果、要么拿到错误，不需要 rAF 合并 buffer，
 *      也不需要 caret 光标。少了这一整块状态（bufferRef / rafRef / commit / scheduleCommit）。
 *   2. **一问一答要成对保留**：结构化结果里带着"回灌了几轮、每轮错在哪"，
 *      这些信息在下一轮提问后依然有查看价值，所以用 turns 数组按序留痕，
 *      而不是只保留"当前这一轮"。
 *   3. **没有停止按钮**：见 api/structured.ts 的注释 —— 中途取消拿不到半成品，
 *      还会白烧已经跑掉的 token，所以只提供"等它返回"。
 */
export function useStructuredChat(): UseStructuredChatResult {
  const [turns, setTurns] = useState<StructuredTurn[]>([])
  const [conversationId, setConversationId] = useState(newId)

  const patch = useCallback((id: string, changes: Partial<StructuredTurn>) => {
    setTurns(prev => prev.map(t => (t.id === id ? { ...t, ...changes } : t)))
  }, [])

  const ask = useCallback(
    async (question: string) => {
      const text = question.trim()
      if (!text) return

      const id = newId()
      setTurns(prev => [...prev, { id, question: text, loading: true }])

      try {
        const result = await askStructured({ conversationId, question: text })
        patch(id, { result, loading: false })
      } catch (e) {
        // 三分类的意义在于"用户下一步该做什么"完全不同：
        //   - 超时（链路或模型太慢）→ 值得再试一次
        //   - HTTP 错误（参数/配置问题）→ 重试没用，得改配置
        //   - 后端已降级（degraded）根本不是错误，是带标记的正常结果
        if (isStreamTimeoutError(e)) {
          patch(id, {
            error: `超过 ${STRUCTURED_TIMEOUT_MS / 1000} 秒仍未返回。结构化输出可能要跑多轮回灌，可稍后再试；若持续超时请调大后端的 ai.chat.structured.max-attempts 之外的超时预算。`,
            errorKind: 'timeout',
            loading: false,
          })
        } else if (e instanceof StructuredHttpError) {
          patch(id, { error: e.message, errorKind: 'http', loading: false })
        } else {
          patch(id, { error: e instanceof Error ? e.message : String(e), errorKind: 'http', loading: false })
        }
      }
    },
    [conversationId, patch],
  )

  const newConversation = useCallback(() => {
    setTurns([])
    // 与流式对话同一套约定：换会话必须换 conversationId，
    // 否则后端会把上一轮的上下文带进新会话的记忆里
    setConversationId(newId())
  }, [])

  return {
    turns,
    loading: turns.some(t => t.loading),
    conversationId,
    ask: (question: string) => void ask(question),
    newConversation,
  }
}

/** crypto.randomUUID 只在安全上下文可用，内网 http 部署时是 undefined，必须有兜底 */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
