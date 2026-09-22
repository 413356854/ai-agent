import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Empty, Flex, Typography } from 'antd'
import { AgentPanel } from './components/AgentPanel'
import { AgentTaskPanel } from './components/AgentTaskPanel'
import { AlertPanel } from './components/AlertPanel'
import { AppSidebar } from './components/AppSidebar'
import { Composer } from './components/Composer'
import { CostPanel } from './components/CostPanel'
import { MessageBubble } from './components/MessageBubble'
import { MockLoginBar } from './components/MockLoginBar'
import { PermissionPanel } from './components/PermissionPanel'
import { PromptPanel } from './components/PromptPanel'
import { RagPanel } from './components/RagPanel'
import { StructuredResultCard } from './components/StructuredResultCard'
import { ThemeToggle } from './components/ThemeToggle'
import { ThreadBudgetPanel } from './components/ThreadBudgetPanel'
import { UserPanel } from './components/UserPanel'
import { useAgent } from './hooks/useAgent'
import { useAlerts } from './hooks/useAlerts'
import { useChatStream } from './hooks/useChatStream'
import { useCost } from './hooks/useCost'
import { useMockLogin } from './hooks/useMockLogin'
import { usePermissions } from './hooks/usePermissions'
import { usePrompt } from './hooks/usePrompt'
import { useRagDiag } from './hooks/useRagDiag'
import { useStructuredChat } from './hooks/useStructuredChat'
import { useTasks } from './hooks/useTasks'
import { useThreadDiag } from './hooks/useThreadDiag'
import type { ThemeMode } from './hooks/useThemeMode'
import { sectionOf } from './nav'
import type { Mode } from './nav'

/**
 * 类后台的三段式外壳：**左侧目录 / 页头 / 内容**。
 *
 * 加一个视图要改两处：`nav.ts` 里的 `NAV_SECTIONS`，以及下面那串渲染分支。
 * （旧版是三处：联合类型、Segmented 的 options、头部那排 Tag 文案 ——
 * 现在模式名、分组标题、顶栏标签都从 `NAV_SECTIONS` 读，只剩这两处。）
 *
 * <h2>为什么做成"目录 + 视图切换"而不是多开几个页面</h2>
 * 这些视图的差别本身就是这个项目要讲的东西 ——
 * 同样一次模型调用，一个走 SSE 逐字推、回答是自由文本；另一个同步等、回答必须先过结构校验；
 * 第三个干脆不是"回答"，而是把线程模型和 token 预算的运行时证据摆出来；
 * 第四个更不同 —— 它**不调用模型**，量的是"文档怎么切、检索找不找得到"；
 * 第五个（Agent）**会调模型、还会自己决定调哪些工具**，所以那一页要回答的是
 * "它为什么这么选、失败在哪一步、花的钱值不值"；
 * 第六个（成本看板）是**回头看**的一页：前五个都在回答"这一次怎么样"，
 * 只有它回答"这一批一共花了多少、谁花的、花得值不值"；
 * 第七个（Prompt 版本）是**唯一一个会改"前面这些页面的行为"的视图** ——
 * 它管的不是一次调用，而是"以后所有调用的指令"。
 * 这一点决定了它的交互必须比别的页更谨慎：存与生效分成两次，回滚永远可见。
 * 第八个（告警与灰度）回答的是"**谁被通知**"：前面七个面板都在等人打开页面看，
 * 只有它会在没人看的时候自己出现、自己消失。
 * 第九个（应用管理 · 用户管理）与第十个（应用管理 · 权限管理）换了一层：
 * 前面八个都是"跑一次看看"，这两页管的是"以后每次调用按什么配置走"，而且是这一层里**与人有关的那一半**：
 * 谁可以用（账号 / 口令 / 启停）、能用哪几把工具、能在哪些目录下干活、谁属于哪个角色。
 * 它们是应用管理区里仅有的两页写端点，而账号的返回体里刻意**没有**口令
 * （那一列只以散列存在，响应里根本没有那个键）。
 * 第十一个（应用管理 · agent任务）是这一区里唯一**不配东西**的一页：
 * 它是派给 Agent 的活在人之间怎么流转（发布 / 确认 / 执行 / 验收），
 * 所以它画的是卡片看板而不是配置表 —— 一张卡片停在哪一列，就等于现在等谁。
 *
 * 十一个 hook 都挂在 App 上（而不是按视图条件挂载）：切换视图时对话记录和实验数据不会被清空，
 * 来回切两次不会丢掉刚才的结果 —— 这也是把状态放在这一层的理由。
 * **Agent 那一页尤其需要这样**：一次任务会真的调模型花钱、要等十几秒，
 * 切走再切回来就重跑一遍（再花一次钱）的代价太大。
 * 成本看板同理：它的查询比运行列表重（四条 GROUP BY + 一次对账），来回切一次重跑一遍是浪费。
 * Prompt 那一页则是"编辑到一半切走再回来"的场景 —— 丢掉草稿的代价是重写一遍整段提示词。
 *
 * <h2>主题（浅色 / 深色）为什么是 props 而不是各面板自己读</h2>
 * 主题状态挂在 `main.tsx` 上：antd 的 `ConfigProvider` 要有它才切算法，
 * 而页头那个开关在 `App` 里 —— 中间只隔这一层，所以由 props 往下传。
 * 颜色本身**一个都不从这里传下去**：面板只管写语义变量（`styles.css` 的 `:root`），
 * 深浅两套取值在那里换。把颜色当 props 传的后果是每加一个面板都要多接一根线。
 *
 * <h2>模拟登录为什么也在这一层</h2>
 * 它记的是"现在以谁的身份在用这一页"（`useMockLogin`），页头那块标签只是它的一个显示出口。
 * 后续要按人区分的业务（谁发布的任务、谁确认的）从 `mockLogin.user.id` 取 ——
 * 拿到 id 的路径与主题一样是 props，所以不存在"页头显示的是张三、业务用的是李四"这种分裂。
 */
export default function App(
  { themeMode, onToggleTheme }: { themeMode: ThemeMode; onToggleTheme: () => void },
) {
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
  // 第八个：进面板只拉三样**只读**的（判据 / 当前状态 / 灰度）。
  // 「跑一轮评估」要点按钮 —— 它会写库，自动跑会让'打开这一页看看'本身改变告警状态
  const alerts = useAlerts(mode === 'alerts')
  // 第九个（用户管理 + 权限管理）：进面板拉一次全量（账号 + 角色 + 可分配工具）。
  // 同样不轮询 —— 这一页的数据只被这一区自己改，而每次写完之后 hook 会自己重拉一次。
  // 两个页面共用一个 hook（账号与角色本来就是一次取回的一份快照），所以两边的 active 都要算上
  const permissions = usePermissions(mode === 'users' || mode === 'permissions')
  // 第十个（agent任务）：进面板拉一次看板。写操作（发布 / 推进 / 答复 / 取消）之后
  // hook 会自己把看板与打开着的那条详情一起重拉 —— 一次写会同时改这两处的形状
  const tasks = useTasks(mode === 'tasks')
  // 第十一个：页头的模拟登录。它不发请求（选人那一步走搜索端点），也不跟着视图走 ——
  // 它记的是"现在以谁的身份在用这一页"，后续要按人区分的业务从这里取 id
  const mockLogin = useMockLogin()

  const listRef = useRef<HTMLElement>(null)

  // 当前视图对应的数据，只用于"滚动到底部"和空状态判断。
  // 诊断/只读面板没有这两件事（面板自己滚、没有空状态），所以这里不参与，免得白白触发一次 effect；
  // 它们同时也都不显示页头那块 conversationId + 「新会话」—— 那两样属于对话状态。
  const isDiagnostic = mode === 'threads' || mode === 'rag' || mode === 'agent' || mode === 'cost'
    || mode === 'prompt' || mode === 'alerts' || mode === 'users' || mode === 'permissions'
    || mode === 'tasks'
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

  // 顶栏标题与标签跟着"当前视图属于哪一区"走，所以它们只有一份定义（nav.ts）
  const section = sectionOf(mode)

  return (
    <div className="app-shell">
      <AppSidebar mode={mode} onSelect={setMode} />

      <div className="app-main">
        <header className="app-topbar">
          <Flex align="center" gap={12} wrap>
            <Typography.Title level={4} className="app-title">
              {section.title}
            </Typography.Title>
            <span className={`app-section-tag is-${section.tag.color}`}>{section.tag.text}</span>
          </Flex>
          {/*
            右侧这一段只在两个对话模式里有意义。六个诊断面板刻意不显示 conversationId 和「新会话」：
            那两个东西属于对话状态，摆在一个纯诊断页面上只会让人以为"这个面板也有会话"，
            而线程面板借用的其实是流式模式那个 conversationId（见 useThreadDiag 的注释）。
            RAG 面板更是完全无会话 —— 它跑的是离线语料，跟对话没有半点关系。
          */}
          <Flex align="center" gap={12}>
            {!isDiagnostic && (
              <>
                <Typography.Text type="secondary" className="conversation-id" title={conversationId}>
                  conversationId: {conversationId.slice(0, 8)}…
                </Typography.Text>
                <Button
                  onClick={mode === 'stream' ? stream.newConversation : structured.newConversation}
                  disabled={busy}
                >
                  新会话
                </Button>
              </>
            )}
            {/* 模拟登录在**所有十一个视图**上都在（与主题开关同理）：它说的是
                "现在以谁的身份在用这一页"，后续按人区分的业务认的是它记下的那个 id */}
            <MockLoginBar login={mockLogin} />
            {/* 主题开关在**所有十一个视图**上都在：它换的是整页的底色，
                属于"看这一页的方式"，不跟着某个视图出现或消失 */}
            <ThemeToggle mode={themeMode} onToggle={onToggleTheme} />
          </Flex>
        </header>

        {mode === 'users' ? (
          <UserPanel permissions={permissions} />
        ) : mode === 'permissions' ? (
          <PermissionPanel permissions={permissions} />
        ) : mode === 'tasks' ? (
          <AgentTaskPanel tasks={tasks} />
        ) : mode === 'threads' ? (
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
        ) : mode === 'alerts' ? (
          <AlertPanel alerts={alerts} />
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
    </div>
  )
}
