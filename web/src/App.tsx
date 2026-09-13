import { useEffect, useRef } from 'react'
import { Button, Empty, Flex, Tag, Typography } from 'antd'
import { Composer } from './components/Composer'
import { MessageBubble } from './components/MessageBubble'
import { useChatStream } from './hooks/useChatStream'

export default function App() {
  const { messages, streaming, conversationId, send, stop, newConversation } = useChatStream()
  const listRef = useRef<HTMLElement>(null)

  // 新内容到达就贴到底部。messages 每帧最多变一次（hook 里做了 rAF 合并），
  // 所以这里跟着 messages 走不会造成额外抖动。
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages])

  return (
    <div className="app-shell">
      <header className="app-header">
        <Flex align="center" gap={12}>
          <Typography.Title level={4} className="app-title">
            AI Agent 对话台
          </Typography.Title>
          <Tag color="blue">SSE 流式</Tag>
        </Flex>
        <Flex align="center" gap={12}>
          <Typography.Text type="secondary" className="conversation-id" title={conversationId}>
            conversationId: {conversationId.slice(0, 8)}…
          </Typography.Text>
          <Button onClick={newConversation} disabled={streaming}>
            新会话
          </Button>
        </Flex>
      </header>

      <main className="message-list" ref={listRef}>
        {messages.length === 0 ? (
          <Empty description="还没有消息。发一条试试——同一次会话内后端会记住上下文。" />
        ) : (
          messages.map(m => <MessageBubble key={m.id} message={m} />)
        )}
      </main>

      <footer className="app-footer">
        <Composer streaming={streaming} onSend={send} onStop={stop} />
      </footer>
    </div>
  )
}
