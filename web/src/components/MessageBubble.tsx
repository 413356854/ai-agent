import { useMemo } from 'react'
import { Alert, Button, Flex, Tag, Typography } from 'antd'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
// 只引 common 子集（约 40 种常用语言）：引默认入口 'highlight.js' 会把 190+ 种语言
// 全打进包里，实测产物从 1.6MB 掉到 600KB 级别
import hljs from 'highlight.js/lib/common'
import type { ChatMessage } from '../hooks/useChatStream'

const md = new MarkdownIt({
  // 不让模型输出裸 HTML —— 防 XSS 的第一道防线（第二道是下面的 DOMPurify）
  html: false,
  linkify: true,
  // 模型输出常用单个 \n 换行，按聊天习惯渲染成 <br>
  breaks: true,
  highlight: highlightCode,
})

function highlightCode(code: string, lang: string): string {
  if (lang && hljs.getLanguage(lang)) {
    try {
      const html = hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
      return `<pre class="hljs"><code>${html}</code></pre>`
    } catch {
      // 语法高亮失败就退化成纯代码块，绝不让它把整条消息搞崩
    }
  }
  return `<pre class="hljs"><code>${escapeHtml(code)}</code></pre>`
}

function escapeHtml(text: string): string {
  const map: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
  return text.replace(/[&<>"']/g, c => map[c] ?? c)
}

export function MessageBubble({
  message,
  onRetry,
  retryDisabled,
}: {
  message: ChatMessage
  onRetry: () => void
  retryDisabled: boolean
}) {
  const isUser = message.role === 'user'

  // 模型输出是 Markdown，渲染前统一过一遍 DOMPurify：
  // html:false 已经挡掉大部分注入，但链接协议、属性这类还得靠白名单清洗，双保险成本极低
  const html = useMemo(() => {
    if (isUser) return ''
    return DOMPurify.sanitize(md.render(message.content))
  }, [isUser, message.content])

  return (
    <div className={`bubble-row ${isUser ? 'is-user' : 'is-assistant'}`}>
      <div className="bubble">
        {isUser ? (
          <div className="plain-text">{message.content}</div>
        ) : (
          <>
            {message.content ? (
              <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
            ) : (
              !message.error && <Typography.Text type="secondary">正在思考…</Typography.Text>
            )}
            {message.streaming && <span className="caret" />}
          </>
        )}

        {message.error && (
          <Alert
            className="bubble-alert"
            type="error"
            showIcon
            // antd 6 起 Alert 的 message 改名 title，用旧名会打 deprecation 警告
            title={message.errorKind === 'timeout' ? '本轮超时' : '本轮失败'}
            description={
              // 超时是"没结论"而不是"结论是失败"，所以给一键重试；
              // HTTP 4xx/业务错误重试没用（同样的请求再来一遍结果一样），
              // 给按钮反而误导用户 —— 该改的是配置或提问方式
              message.errorKind === 'timeout' ? (
                <Flex vertical gap={8}>
                  <span>{message.error}</span>
                  <Button size="small" type="primary" ghost disabled={retryDisabled} onClick={onRetry}>
                    重试本轮
                  </Button>
                </Flex>
              ) : (
                <span>{message.error}</span>
              )
            }
          />
        )}

        {message.stopped && (
          <div className="bubble-meta">
            <Tag color="default">已停止</Tag>
          </div>
        )}

        {message.usage && <UsageLine message={message} />}
      </div>
    </div>
  )
}

/**
 * token 用量：第一个 chunk 的计数通常是 0，后端取的是最后一个非空值。
 * 这里把 promptTokens 单独标出来，因为它是「对话记忆是否生效」最直观的证据
 * （同 conversationId 第二轮 prompt 会变大）。
 */
function UsageLine({ message }: { message: ChatMessage }) {
  const { usage } = message
  if (!usage) return null
  const { promptTokens, completionTokens, totalTokens, model } = usage
  if (promptTokens === null && completionTokens === null && totalTokens === null && !model) return null

  return (
    <div className="bubble-meta">
      <Typography.Text type="secondary" className="usage-text">
        {model ?? 'unknown'} · prompt {promptTokens ?? '-'} / completion {completionTokens ?? '-'} / total{' '}
        {totalTokens ?? '-'}
      </Typography.Text>
    </div>
  )
}
