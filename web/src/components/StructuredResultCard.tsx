import { useMemo } from 'react'
import { Alert, Card, Collapse, Flex, Progress, Spin, Tag, Timeline, Typography } from 'antd'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import type { StructuredAttempt, StructuredTurn } from '../types'

// 与 MessageBubble 用同一套渲染策略：模型输出是不可信输入，
// markdown-it 关掉 html（第一层）+ DOMPurify 白名单清洗（第二层）
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

/**
 * 结构化输出结果卡片。
 *
 * 设计要点：这张卡片的重点是**把"过程"露出来**，而不只是把答案渲染得好看。
 * 结构化输出真正值得看的东西是「模型第一次错在哪、回灌之后改对没有、最后是不是降级了」——
 * 只显示最终答案是看不出这些的，而这个功能的全部价值恰恰在那条链路上。
 */
export function StructuredResultCard({ turn }: { turn: StructuredTurn }) {
  const { result } = turn

  const answerHtml = useMemo(() => {
    if (!result) return ''
    return DOMPurify.sanitize(md.render(result.answer.answer))
  }, [result])

  return (
    <div className="structured-turn">
      {/* 提问 */}
      <div className="bubble-row is-user">
        <div className="bubble">
          <div className="plain-text">{turn.question}</div>
        </div>
      </div>

      <div className="bubble-row is-assistant">
        <Card
          className="structured-card"
          size="small"
          title={
            <Flex align="center" gap={8} wrap>
              <Typography.Text strong>结构化输出</Typography.Text>
              {result && <AttemptBadge attempts={result.attempts} />}
              {result?.degraded && <Tag color="orange">已降级</Tag>}
            </Flex>
          }
        >
          {turn.loading && (
            <Flex align="center" gap={12}>
              <Spin size="small" />
              <Typography.Text type="secondary">
                正在请求模型……结构化输出可能需要多轮回灌校验，通常 1~5 秒
              </Typography.Text>
            </Flex>
          )}

          {turn.error && (
            <Alert
              type="error"
              showIcon
              title={turn.errorKind === 'timeout' ? '请求超时' : '请求失败'}
              description={turn.error}
            />
          )}

          {result && (
            <Flex vertical gap={12}>
              {/* 答案正文 */}
              <div className="markdown" dangerouslySetInnerHTML={{ __html: answerHtml }} />

              {/* 自评置信度 + 关键词 */}
              <Flex vertical gap={8} className="structured-meta">
                <Flex align="center" gap={10}>
                  <Typography.Text type="secondary" className="confidence-label">
                    模型自评置信度
                  </Typography.Text>
                  <Progress
                    className="confidence-bar"
                    percent={Math.round((result.answer.confidence ?? 0) * 100)}
                    size="small"
                    // 置信度低要显眼：它是"这个答案该不该直接采信"的唯一信号
                    status={(result.answer.confidence ?? 0) < 0.5 ? 'exception' : 'normal'}
                  />
                  <Typography.Text code>{result.answer.confidence ?? '-'}</Typography.Text>
                </Flex>
                <Flex align="center" gap={6} wrap>
                  <Typography.Text type="secondary">关键词</Typography.Text>
                  {result.answer.keywords.map(k => (
                    <Tag key={k}>{k}</Tag>
                  ))}
                </Flex>
                <Typography.Text type="secondary" className="confidence-hint">
                  置信度是<b>模型自己报的</b>，不是系统评估的可信度 —— 它用来触发「不确定」协议
                  （低于阈值会被判不合格并回灌重答），不要直接当作质量指标。
                </Typography.Text>
              </Flex>

              {/* 校验与回灌过程 —— 这张卡片存在的理由 */}
              <Collapse
                size="small"
                defaultActiveKey={result.attempts.length > 1 || result.degraded ? ['attempts'] : []}
                items={[
                  {
                    key: 'attempts',
                    label: (
                      <Typography.Text>
                        校验与回灌过程（共 {result.attempts.length} 轮
                        {result.attempts.length > 1 ? '，含回灌' : '，一次通过'}）
                      </Typography.Text>
                    ),
                    children: <AttemptTimeline attempts={result.attempts} />,
                  },
                ]}
              />
            </Flex>
          )}
        </Card>
      </div>
    </div>
  )
}

/** 头部徽标：一眼看出"是不是靠回灌救回来的" */
function AttemptBadge({ attempts }: { attempts: StructuredAttempt[] }) {
  const failed = attempts.filter(a => !a.parsed || a.issues.length > 0).length
  if (attempts.length <= 1) {
    return <Tag color="green">1 轮通过</Tag>
  }
  return (
    <Tag color="gold">
      {attempts.length} 轮（{failed} 轮不合格 → 回灌修正）
    </Tag>
  )
}

/**
 * 回灌过程时间线。
 *
 * 把每一轮的「原始输出 + 校验错误」都列出来，因为这是排查"模型为什么总是不合格"的唯一线索：
 * 只看最终答案的话，`confidence` 到底是给了 95 还是漏了字段，全都看不出来。
 */
function AttemptTimeline({ attempts }: { attempts: StructuredAttempt[] }) {
  return (
    <Timeline
      className="attempt-timeline"
      items={attempts.map(attempt => {
        const ok = attempt.parsed !== null && attempt.issues.length === 0
        return {
          color: ok ? 'green' : 'red',
          // antd 6 起 Timeline item 的 children 改名 content（旧名会打 deprecation 警告）。
          // 和 Alert 的 message→title 是同一批重命名 —— 这个项目里已经踩到两个了
          content: (
            <Flex vertical gap={6}>
              <Flex align="center" gap={8} wrap>
                <Typography.Text strong>第 {attempt.index} 轮</Typography.Text>
                <Tag color={ok ? 'green' : 'red'}>{ok ? '通过校验' : '校验未通过'}</Tag>
                <Typography.Text type="secondary">{attempt.elapsedMs} ms</Typography.Text>
              </Flex>

              {attempt.issues.length > 0 && (
                <div>
                  <Typography.Text type="secondary">校验发现的问题（这些内容会被回灌给模型）：</Typography.Text>
                  <ul className="issue-list">
                    {attempt.issues.map(issue => (
                      <li key={issue}>
                        <Typography.Text type="danger">{issue}</Typography.Text>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <div>
                <Typography.Text type="secondary">模型原始输出：</Typography.Text>
                <pre className="raw-output">{attempt.rawOutput ?? '(调用失败，无输出)'}</pre>
              </div>
            </Flex>
          ),
        }
      })}
    />
  )
}
