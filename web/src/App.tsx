import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Empty, Flex, Segmented, Tag, Typography } from 'antd'
import { Composer } from './components/Composer'
import { MessageBubble } from './components/MessageBubble'
import { StructuredResultCard } from './components/StructuredResultCard'
import { useChatStream } from './hooks/useChatStream'
import { useStructuredChat } from './hooks/useStructuredChat'

/**
 * 两种对话模式的对照台。
 *
 * **为什么做成"模式切换"而不是两个页面**：这两种模式的差别本身就是这个项目要讲的东西 ——
 * 同样一次模型调用，一个走 SSE 逐字推、回答是自由文本；另一个同步等、回答必须先过结构校验。
 * 放在同一个界面里能直接对比"同样的提问，两种契约下拿到的东西有什么不同"。
 *
 * 两个 hook 都挂在 App 上（而不是按模式条件挂载）：切换模式时对话记录不会被清空，
 * 来回切两次不会丢掉刚才的结果 —— 这也是把状态放在这一层的理由。
 */
export default function App() {
  const [mode, setMode] = useState<'stream' | 'structured'>('stream')

  const stream = useChatStream()
  const structured = useStructuredChat()

  const listRef = useRef<HTMLElement>(null)

  // 当前模式对应的视图数据，只用于"滚动到底部"和空状态判断
  const activeCount = mode === 'stream' ? stream.messages.length : structured.turns.length
  const busy = mode === 'stream' ? stream.streaming : structured.loading
  const conversationId = mode === 'stream' ? stream.conversationId : structured.conversationId

  // 新内容到达就贴到底部。流式那边 messages 每帧最多变一次（hook 里做了 rAF 合并），
  // 所以这里跟着计数走不会造成额外抖动
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [activeCount, mode])

  const empty = useMemo(
    () =>
      mode === 'stream'
        ? '还没有消息。发一条试试——同一次会话内后端会记住上下文，回答会逐字流出来。'
        : '还没有结果。发一个问题，后端会让模型按固定 JSON 结构回答，并逐字段校验；不合格会回灌重答，最多 3 轮。',
    [mode],
  )

  return (
    <div className="app-shell">
      <header className="app-header">
        <Flex align="center" gap={12} wrap>
          <Typography.Title level={4} className="app-title">
            AI Agent 对话台
          </Typography.Title>
          <Segmented
            value={mode}
            onChange={v => setMode(v as 'stream' | 'structured')}
            options={[
              { label: 'SSE 流式', value: 'stream' },
              { label: '结构化输出', value: 'structured' },
            ]}
          />
          <Tag color={mode === 'stream' ? 'blue' : 'purple'}>
            {mode === 'stream' ? '逐字推送 · 自由文本' : '同步返回 · JSON 校验'}
          </Tag>
        </Flex>
        <Flex align="center" gap={12}>
          <Typography.Text type="secondary" className="conversation-id" title={conversationId}>
            conversationId: {conversationId.slice(0, 8)}…
          </Typography.Text>
          <Button
            onClick={mode === 'stream' ? stream.newConversation : structured.newConversation}
            disabled={busy}
          >
            新会话
          </Button>
        </Flex>
      </header>

      <main className="message-list" ref={listRef}>
        {activeCount === 0 ? (
          <Empty description={empty} />
        ) : mode === 'stream' ? (
          stream.messages.map(m => (
            <MessageBubble
              key={m.id}
              message={m}
              onRetry={stream.retry}
              // 只有最后一条消息才允许重试：多条历史都挂着重试按钮，
              // 用户点哪一个是"重试哪一轮"就说不清了
              retryDisabled={stream.streaming || m.id !== stream.messages[stream.messages.length - 1]?.id}
            />
          ))
        ) : (
          structured.turns.map(turn => <StructuredResultCard key={turn.id} turn={turn} />)
        )}
      </main>

      <footer className="app-footer">
        {mode === 'stream' ? (
          <Composer streaming={stream.streaming} onSend={stream.send} onStop={stream.stop} />
        ) : (
          <Composer
            streaming={structured.loading}
            onSend={text => structured.ask(text)}
            // 刻意不传 onStop：结构化输出是全有或全无，取消拿不到半成品
            showTemperature={false}
            placeholder="提一个问题…后端会按固定 JSON 结构回答并校验（Enter 发送，Shift+Enter 换行）"
            hint="后端流程：请求模型 → 解析 JSON → JSR-380 校验 → 不合格则把「哪个字段错 + 你的原始输出」回灌重答（最多 3 轮）→ 仍不合格走兜底"
            busyHint="模型正在回答并校验，可能需要多轮回灌……"
          />
        )}
      </footer>
    </div>
  )
}
