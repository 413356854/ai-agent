import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Empty, Flex, Segmented, Tag, Typography } from 'antd'
import { AgentPanel } from './components/AgentPanel'
import { Composer } from './components/Composer'
import { CostPanel } from './components/CostPanel'
import { MessageBubble } from './components/MessageBubble'
import { PromptPanel } from './components/PromptPanel'
import { RagPanel } from './components/RagPanel'
import { StructuredResultCard } from './components/StructuredResultCard'
import { ThreadBudgetPanel } from './components/ThreadBudgetPanel'
import { useAgent } from './hooks/useAgent'
import { useChatStream } from './hooks/useChatStream'
import { useCost } from './hooks/useCost'
import { usePrompt } from './hooks/usePrompt'
import { useRagDiag } from './hooks/useRagDiag'
import { useStructuredChat } from './hooks/useStructuredChat'
import { useThreadDiag } from './hooks/useThreadDiag'

/** 七种模式共用一个壳。加模式时只改三处：这个联合类型、Segmented 的 options、头部那个 Tag 的文案表 */
type Mode = 'stream' | 'structured' | 'threads' | 'rag' | 'agent' | 'cost' | 'prompt'

/**
 * 七种模式的对照台。
 *
 * **为什么做成"模式切换"而不是七个页面**：这些模式的差别本身就是这个项目要讲的东西 ——
 * 同样一次模型调用，一个走 SSE 逐字推、回答是自由文本；另一个同步等、回答必须先过结构校验；
 * 第三个干脆不是"回答"，而是把线程模型和 token 预算的运行时证据摆出来；
 * 第四个更不同 —— 它**不调用模型**，量的是"文档怎么切、检索找不找得到"；
 * 第五个（Agent）**会调模型、还会自己决定调哪些工具**，所以那一页要回答的是
 * "它为什么这么选、失败在哪一步、花的钱值不值"；
 * 第六个（成本看板）是**回头看**的一页：前五个都在回答"这一次怎么样"，
 * 只有它回答"这一批一共花了多少、谁花的、花得值不值"；
 * 第七个（Prompt 版本）是**唯一一个会改"前六个的行为"的页面** ——
 * 它管的不是一次调用，而是"以后所有调用的指令"。
 * 这一点决定了它的交互必须比别的页更谨慎：存与生效分成两次，回滚永远可见。
 * 放在同一个界面里能直接对比"同一个系统里，不同的契约 / 线程模型 / 检索链路 / 自主程度各自长什么样"。
 *
 * 七个 hook 都挂在 App 上（而不是按模式条件挂载）：切换模式时对话记录和实验数据不会被清空，
 * 来回切两次不会丢掉刚才的结果 —— 这也是把状态放在这一层的理由。
 * **Agent 那一页尤其需要这样**：一次任务会真的调模型花钱、要等十几秒，
 * 切走再切回来就重跑一遍（再花一次钱）的代价太大。
 * 成本看板同理：它的查询比运行列表重（四条 GROUP BY + 一次对账），来回切一次重跑一遍是浪费。
 * Prompt 那一页则是"编辑到一半切走再回来"的场景 —— 丢掉草稿的代价是重写一遍整段提示词。
 */
export default function App() {
  const [mode, setMode] = useState<Mode>('stream')

  const stream = useChatStream()
  const structured = useStructuredChat()
  // 第三个 hook 挂在 App 上，但它自己知道"面板没显示过就别发请求"：
  // 诊断端点里的虚拟线程计数要遍历所有线程抓栈，用户一直待在流式模式时不该白打这两次请求
  const diag = useThreadDiag(mode === 'threads', stream.conversationId)
  // 第四个同理：语料解析要读 4 份文档、几百个块，没进过这个面板就不该付这份代价
  const rag = useRagDiag(mode === 'rag')
  // 第五个：进面板只自动拉"只读"的运行列表与限额；跑任务必须用户明确点 —— 它会真的花钱
  const agent = useAgent(mode === 'agent')
  // 第六个：进面板拉一次快照与价格表。**不轮询** —— 成本是"回头看"的数据，
  // 它变了不影响任何人做决定（唯一轮询的是 HITL 待办，理由是"有人在等它"）
  const cost = useCost(mode === 'cost')
  // 第七个：进面板拉一次清单与版本历史。同样不轮询 —— 别人改了会以 409 的形式告诉我们，
  // 那比"每 2.5 秒重拉一次列表"更准确（一次冲突只在真的发生时出现一次）
  const prompt = usePrompt(mode === 'prompt')

  const listRef = useRef<HTMLElement>(null)

  // 当前模式对应的视图数据，只用于"滚动到底部"和空状态判断。
  // 三个诊断模式没有这两件事（面板自己滚、没有空状态），所以这里不参与，免得白白触发一次 effect
  const isDiagnostic = mode === 'threads' || mode === 'rag' || mode === 'agent' || mode === 'cost'
    || mode === 'prompt'
  const activeCount = mode === 'stream' ? stream.messages.length : structured.turns.length
  const busy = mode === 'stream' ? stream.streaming : structured.loading
  const conversationId = mode === 'structured' ? structured.conversationId : stream.conversationId

  // 新内容到达就贴到底部。流式那边 messages 每帧最多变一次（hook 里做了 rAF 合并），
  // 所以这里跟着计数走不会造成额外抖动
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [activeCount, mode])

  const empty = useMemo(() => {
    if (mode === 'stream') {
      return '还没有消息。发一条试试——同一次会话内后端会记住上下文，回答会逐字流出来。'
    }
    if (mode === 'structured') {
      return '还没有结果。发一个问题，后端会让模型按固定 JSON 结构回答，并逐字段校验；不合格会回灌重答，最多 3 轮。'
    }
    return ''
  }, [mode])

  return (
    <div className="app-shell">
      <header className="app-header">
        <Flex align="center" gap={12} wrap>
          <Typography.Title level={4} className="app-title">
            AI Agent 对话台
          </Typography.Title>
          <Segmented
            value={mode}
            onChange={v => setMode(v as Mode)}
            options={[
              { label: 'SSE 流式', value: 'stream' },
              { label: '结构化输出', value: 'structured' },
              { label: '线程与预算', value: 'threads' },
              { label: 'RAG 分块评测', value: 'rag' },
              { label: 'Agent 工具调用', value: 'agent' },
              { label: '成本看板', value: 'cost' },
              { label: 'Prompt 版本', value: 'prompt' },
            ]}
          />
          {mode === 'stream' && <Tag color="blue">逐字推送 · 自由文本</Tag>}
          {mode === 'structured' && <Tag color="purple">同步返回 · JSON 校验</Tag>}
          {mode === 'threads' && <Tag color="cyan">运行时取证 · 不写死结论</Tag>}
          {mode === 'rag' && <Tag color="green">离线链路 · 调模型 0 次</Tag>}
          {mode === 'agent' && <Tag color="orange">会调模型 · 自主选工具</Tag>}
          {mode === 'cost' && <Tag color="gold">只读聚合 · 不估算 token</Tag>}
          {mode === 'prompt' && <Tag color="magenta">改的是以后的每一次调用 · 可回滚</Tag>}
        </Flex>
        {/*
          右侧这一段只在对话模式里有意义。两个诊断模式刻意不显示 conversationId 和「新会话」：
          那两个东西属于对话状态，摆在一个纯诊断页面上只会让人以为"这个面板也有会话"，
          而线程面板借用的其实是流式模式那个 conversationId（见 useThreadDiag 的注释）。
          RAG 面板更是完全无会话 —— 它跑的是离线语料，跟对话没有半点关系。
        */}
        {!isDiagnostic && (
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
        )}
      </header>

      {mode === 'threads' ? (
        // 面板自己带滚动容器：诊断内容比对话长得多，而且没有"新消息贴底"这个语义
        <ThreadBudgetPanel diag={diag} />
      ) : mode === 'rag' ? (
        <RagPanel diag={rag} />
      ) : mode === 'agent' ? (
        <AgentPanel agent={agent} />
      ) : mode === 'cost' ? (
        <CostPanel cost={cost} />
      ) : mode === 'prompt' ? (
        <PromptPanel prompt={prompt} />
      ) : (
        // 诊断模式没有输入区，所以 main/footer 只在对话模式下渲染 —— 空着的 footer 会白白吃掉一块高度
        <>
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
                  retryDisabled={
                    stream.streaming || m.id !== stream.messages[stream.messages.length - 1]?.id
                  }
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
        </>
      )}
    </div>
  )
}
