/**
 * Agent 端点的调用层（阶段三：能看见 Agent 到底干了什么）。
 *
 * 与 `rag.ts` 的关系：那边是"文档切得怎么样、检索找不找得到"的取证入口（阶段二），
 * 这边是"Agent 怎么想的、调了什么、失败在哪一步"的取证入口（阶段三）。
 * 两者共用同一套错误分类与超时约定 —— 从 `diag.ts` 复用 `DiagError` 与 `request`。
 *
 * 端点清单：
 *   POST /api/agent/ask                     跑一个任务（★ 会真的调模型、花钱）
 *   GET  /api/agent/runs                    历史运行列表
 *   GET  /api/agent/runs/{id}               一次运行的完整过程
 *   GET  /api/agent/runs/{id}/approvals     这一次运行里的人工确认历史
 *   GET  /api/agent/limits                  四层保护的当前状态
 *   GET  /api/agent/approvals               现在有哪些写操作在等人确认（第九轮）
 *   POST /api/agent/approvals/{id}/decide   批准 / 拒绝（第九轮）
 *   GET  /api/agent/approvals/status        审批闸门的当前状态（第九轮）
 *
 * ⚠️ 第九轮加的四个端点不与上面四个同源：它们对应
 * `ai.agent.controller.AgentApprovalController` + `AgentApprovalService`。
 */
import { DiagError, request } from './diag'

/**
 * 跑任务的超时。
 *
 * **为什么给 180 秒**：一个 Agent 任务最多 6 轮模型往返（`ai.agent.loop.max-steps`），
 * 每轮 1~4 秒 → 正常 20 秒内；但循环自己的时间上限是 60 秒
 * （`ai.agent.loop.max-duration-ms`），加上每轮工具执行与网络抖动，
 * 前端必须**明显宽于后端的自我上限** —— 否则后端还在正常跑，前端先报超时了，
 * 而那是阶段一踩过的坑（钉住实验 19~26 秒 vs 前端 20 秒超时）。
 *
 * 取值依据：后端上限 60 秒 × 3 = 180 秒，留足余量。
 * **这个数字与 `ai.agent.loop.max-duration-ms` 是联动的**，改一个要一起改。
 */
export const AGENT_ASK_TIMEOUT_MS = 180_000

/** 只读端点的超时：它们不调模型，只是读库，秒级足够。 */
export const AGENT_READ_TIMEOUT_MS = 30_000

/**
 * **待批列表的轮询间隔**（第九轮 HITL）。
 *
 * <h2>为什么这一块必须轮询，而别的块不需要</h2>
 * 这个项目在其他地方都刻意避免轮询（进面板拉一次、点刷新再拉一次）。
 * 但待批列表不一样，它有一个别处没有的性质：**有人在等它**。
 *
 * 写操作被闸门拦住时，发起那个 `/ask` 请求的线程**正停在那里等**（最长
 * `ai.agent.approval.timeout`，默认 120 秒）。也就是说：
 * "待办出现了但界面没刷新"这件事的后果不是"看到旧数据"，
 * 而是**那个任务会一直停到超时、然后按拒绝处理** —— 人明明在页面前坐着，却因为它没看见而失败。
 *
 * <p>所以这一块的刷新语义与别处相反：**别的块是"用户想看才拉"，这一块是"不拉就会出事"**。
 *
 * <h2>为什么是 2.5 秒</h2>
 * 取值要同时满足两头：
 * <ul>
 *   <li><b>不能太长</b>：人看到待办的延迟直接等于任务的额外等待时间。
 *       5 秒意味着最坏情况下每个写操作白白多等 5 秒；</li>
 *   <li><b>不能太短</b>：这是一个纯读库的端点（H2 本地查询，毫秒级），
 *       但每 200 毫秒一次只会在人盯着页面不动时白烧 CPU 与日志。</li>
 * </ul>
 * 2.5 秒是"人几乎察觉不到延迟"与"请求量可忽略"之间的量级选择。
 *
 * <p>只在**面板处于激活状态**时轮询（见 `useAgent`）—— 切走就停，
 * 这与"进面板才发请求"那条既有规矩不冲突：这里多出来的不是"别的面板的代价"，而是这个面板该有的活性。
 */
export const AGENT_APPROVAL_POLL_MS = 2_500

// ---------------------------------------------------------------------------
//  与后端契约对应的类型。
//
//  唯一事实来源是后端代码，改那边就要同步改这里：
//    - ai.agent.controller.AgentController（各端点的返回体）
//    - ai.agent.service.tool.ToolCallStage（stage 的取值）
//    - ai.agent.service.agent.AgentFailureCause（失败细分）
//    - ai.agent.service.agent.limit.AgentConcurrencyLimitsView（限额快照）
// ---------------------------------------------------------------------------

/** 一次运行里的一次工具调用（`/ask` 与 `/runs/{id}` 都有这个形状）。 */
export interface AgentToolCall {
  /** 只有 `/runs/{id}` 会给（它来自库里的明细表） */
  stepIndex?: number
  callIndex?: number
  callId?: string
  toolName: string
  arguments: string
  /**
   * 走到了哪一步。取值见后端 `ToolCallStage`：
   * `OK` / `UNKNOWN_TOOL` / `PARSE_ERROR` / `VALIDATION_ERROR` / `EXECUTION_ERROR`
   * / `REQUEST_REJECTED` / `CIRCUIT_OPEN`
   */
  stage: string
  elapsedMs: number
  /** 只有 `/ask` 会给 */
  fixableByModel?: boolean
  /**
   * **要不要把它算成"工具失败"——由后端给，前端不再判断。**
   *
   * 这个字段的存在是一条设计纪律：`stage` 到"好坏"的映射在
   * `CircuitBreaker` 里已经有一份判据（只有 `EXECUTION_ERROR` 算），
   * 前端再判一次就是**第二份判据**，而两份必然漂移 ——
   * 漂移之后的现象是"页面说工具没事，但熔断其实已经打开了"。
   */
  countsAsToolFailure: boolean
  /** 回填给模型的文本 —— "模型当时到底看到了什么"的唯一凭据 */
  feedback: string
}

/** 一步（一次模型往返）。 */
export interface AgentStep {
  index: number
  thoughtText: string
  promptTokens: number
  completionTokens: number
  elapsedMs: number
  usedTools: boolean
  toolCalls: AgentToolCall[]
  /** 只有 `/runs/{id}` 会给：这一步的检查点存了多少字符的上下文 */
  transcriptChars?: number
  stepIndex?: number
}

/**
 * `POST /api/agent/ask` 的响应 —— **一次任务的完整交代**。
 *
 * `steps` 与 `toolCallSummary` 一定要显示：只给答案的话，
 * "它查了 3 次文档、失败 2 次、最后蒙对"和"它一次就答对"在界面上长得一模一样。
 */
export interface AgentAskResponse {
  ok: boolean
  error?: string
  runId: string
  question: string
  answered: boolean
  finalAnswer: string
  stopReason: string
  /** 失败细分（只有 stopReason=MODEL_ERROR 时非空），取值见 `AgentFailureCause` */
  failureCause: string
  /** 失败时"下一步该做什么"的一句话（后端给的，前端不自己编） */
  failureAdvice: string
  totalTokens: number
  elapsedMs: number
  toolCallCount: number
  /** 例如「工具调用 2 次：UNKNOWN_TOOL=1 OK=1 ｜工具侧总耗时 12ms」 */
  toolCallSummary: string
  stepSummaries: string[]
  steps: AgentStep[]

  // ---- 运行权限（本次新增）----
  /**
   * **这一次运行的执行前提：在哪个目录下、拿了多大权限。**
   *
   * 回的是**服务端实际用的那一份**，不是界面刚才填的那一份 —— 两者会不一样：
   * 目录被规范化过（去掉尾部斜杠）。让界面显示服务端那份，人与模型看到的才是同一个事实。
   *
   * `permissionLevel` 为空串有两种含义，后端会分别说明：
   * ① 这条链路没有工作区（`ai.agent.workspace.required-on-ask=false` 时的 curl 调用）；
   * ② 老数据。界面上按"未记录"显示，**不编一个默认档位**。
   */
  workspaceDir?: string
  permissionLevel?: string
  /** 档位的中文标签（"仅可查看" / "工作区内修改" / "完全权限"），由后端给，前端不自己映射 */
  permissionLabel?: string
  /** 一行可读说明，形如 `完全权限 · D:\work\demo` */
  permissionNote?: string

  // ---- 本次新增：这一次注入的技能 ----
  /**
   * **这一次运行代表谁**（形如 `初始管理员(#1)`）；空串 = 没有指定执行者。
   *
   * <p>技能的候选范围来自执行者的角色目录，所以"注入了哪些技能"这件事
   * 只有在知道"代表谁"之后才可解释。空串时后端一条都不注入。
   */
  skillExecutor?: string
  /** 这次生效的文件夹目录（执行者全部角色的目录前缀）。空数组 = 没有执行者 / 他没有角色 */
  skillFolders?: string[]
  /** 注入了几条技能的 name + desc（进系统提示词的那一节） */
  skillCount?: number
  /** 是否因为超过 `ai.agent.skill.max-catalog-skills` 被截断 */
  skillTruncated?: boolean
  /** 截断前一共匹配到几条 —— 与 `skillCount` 一起说清"被拦掉了多少条" */
  skillTotalMatched?: number
  /** 一行可读说明，形如 `初始管理员(#1)：1 个文件夹 / 2 条技能` */
  skillNote?: string
  /**
   * 注入了哪些技能（**只有 name 与 desc，没有正文**）。
   *
   * <p>它是"技能到底对模型起了什么作用"的唯一凭据：提示词指纹覆盖不到技能那一节
   * （它是按执行者动态拼的）。不显示的话，"模型没按技能做"与"技能压根没注入"
   * 在页面上长得一模一样。
   */
  skills?: AgentSkillBrief[]

  // ---- 第九轮新增：HITL（人工确认）----
  /**
   * **这次任务停在一次写操作上等人确认**（`stopReason === 'WAITING_FOR_APPROVAL'` 的便捷布尔）。
   *
   * 它由后端给而不是前端比字符串：停因到"是不是在等人"的映射属于后端语义，
   * 前端再判一次就是第二份判据。
   */
  waitingApproval?: boolean
  /** 在等哪一条审批（拿它去下面那张待办卡片里对上） */
  approvalId?: string
  /** 等的是哪把工具 */
  approvalToolName?: string
  /** 它将会执行的参数 —— **人批的就是这一份**，所以必须显示 */
  approvalArguments?: string
  /** 风险声明的可读描述（例如「写（非幂等，需人工确认），可能破坏性」） */
  approvalHints?: string
  /** 等人时限（秒），用于显示"还有多久就按拒绝处理" */
  approvalTimeoutSeconds?: number
  /** 停因说是等审批、但待办里查不到对应记录 —— 这是**必须报出来的不一致** */
  approvalMissing?: boolean
}

/**
 * 一次运行里注入的一条技能（`/ask` 响应里 `skills[]` 的一行）。
 *
 * 对应后端 `ai.agent.entity.agent.skill.SkillCatalog#row`。**没有 content** ——
 * 正文由模型按需用工具取，它不进提示词、也不该进这个响应（那是几万字的量级）。
 */
export interface AgentSkillBrief {
  id: number
  /** 它所在的文件夹目录（= 执行者某个角色的目录前缀） */
  folder: string
  name: string
  /** 这个技能是干什么的；空串 = 库里没填说明 */
  description: string
  ownerId: number
  /** 拥有者的姓名（账号行不在时后端给「未知用户 #id」） */
  ownerName: string
}

/**
 * 一条待批的人工确认（`GET /api/agent/approvals` 里的一行）。
 *
 * 对应后端 `ai.agent.entity.agent.approval.ToolApprovalRecord`。
 */
export interface AgentApprovalRow {
  id: string
  /** 属于哪次运行；来自外部 MCP 客户端那条链路的调用为空串（它没有"运行"这个对象） */
  runId: string
  /** 第几步；-1 表示未知 */
  stepIndex: number
  toolName: string
  /**
   * **它将会执行的参数（原样，未加工）。**
   *
   * 后端刻意不脱敏、不摘要，理由写在 `ToolApprovalRecord` 的注释里：
   * 一旦"展示的参数"与"执行的参数"是两次不同的序列化，就存在两者不一致的可能 ——
   * 而**人批的是他看到的那份**。
   */
  arguments: string
  /** 风险声明的可读描述，来自 `ToolHints.describe()` */
  hints: string
  /**
   * **这一次为什么弹了确认**（形如 `仅可查看 · D:\work\docs`）。
   *
   * <p>它与 {@link hints} 是两个问题：`hints` 是**工具固有**的风险声明
   * （对所有运行都一样），而这一条取决于**这次运行的档位** ——
   * 同一把 `propose_review` 在「完全权限」下不会弹确认。
   * 只看 `hints` 分不出"这次为什么弹了"。
   *
   * <p>空串 = 没有权限上下文（外部 MCP 链路、老数据）。**不编默认值**。
   */
  permissionNote?: string
  /** 工具自己的一句话摘要（来自四段描述） */
  summary: string
  requestedAt: string
  /** 已经等了多久（毫秒）—— 它是"审批有没有拖慢任务"的唯一量化口径 */
  waitedMs: number
}

/** `GET /api/agent/approvals` 的响应 */
export interface AgentApprovalsResponse {
  pending: AgentApprovalRow[]
  count: number
  /**
   * **闸门开着没有。**
   *
   * 一个空的待办列表有**两种**含义：确实没有危险操作，或者审批功能压根没开。
   * 不区分的话，界面上的空列表看起来像一切正常 —— 而那正是「卡片在」不等于「列表有行」的形状
   * （"我配了保护"与"保护真的装着"是两件事）。
   */
  enabled: boolean
  /** 等人时限（秒） */
  timeoutSeconds: number
  /** 闸门当前状态的可读说明（后端 `AgentApprovalService.describe()`） */
  note: string
  howToDecide: string
}

/**
 * `POST /api/agent/approvals/{id}/decide` 的响应。
 *
 * `awakened` / `resumed` 是这里最重要的两个字段，而它们回答的是**两个不同的问题**：
 * - `awakened`：有没有唤醒一个**正在等**的线程 → 有的话任务当场继续跑完；
 * - `resumed`：有没有真的**从检查点接着跑**起来（进程重启过、或没人等的时候走这条）。
 *
 * 两者都是 false 且 `runId` 非空时，说明决定已记录、**但任务还没能继续** ——
 * 界面必须明确提示这件事，否则"点了批准、显示成功、任务却停着"与
 * "批准根本没生效"在使用者眼里完全一样。
 */
export interface AgentApprovalDecisionResponse {
  ok: boolean
  error?: string
  approvalId: string
  /** `APPROVED` / `DENIED`，或**没有被采纳**时的实际状态（`TIMED_OUT` 等） */
  status: string
  awakened: boolean
  resumed: boolean
  stopReason?: string
  finalAnswer?: string
  totalTokens?: number
  /** 恢复失败时的原因（审批已记录，但任务没能继续） */
  resumeError?: string
  note?: string
}

/** `GET /api/agent/approvals/status` 的响应 */
export interface AgentApprovalStatusResponse {
  enabled: boolean
  timeoutSeconds: number
  defaultDecider: string
  pendingCount: number
  describe: string
}

/** `/runs/{id}` 详情里的一条人工确认历史（已决 + 待批）。 */
export interface AgentRunApprovalRow {
  id: string
  stepIndex: number
  toolName: string
  arguments: string
  /** 这一次为什么弹了确认（与待办列表同名字段；空串 = 没有权限上下文） */
  permissionNote?: string
  /** `PENDING` / `APPROVED` / `DENIED` / `TIMED_OUT` / `EXPIRED` */
  status: string
  pending: boolean
  requestedAt: string
  decidedAt: string
  decidedBy: string
  decisionNote: string
  waitedMs: number
}

/** `/runs` 列表里的一行 */
export interface AgentRunRow {
  id: string
  question: string
  /** `RUNNING` / `ANSWERED` / `STOPPED` / `FAILED` / `INTERRUPTED` */
  status: string
  stopReason: string | null
  failureCause: string | null
  totalTokens: number
  toolCalls: number
  elapsedMs: number
  createdAt: string | null
  node: string | null
  /**
   * 业务归因键，空串 = 未标注（阶段四第 6 件把它加进列表：
   * 看板的数字要能在运行自己的列表里**核对**，否则只能信）。
   */
  intent: string
  /** 租户，空串 = 未标注租户。与 `intent` 是两个维度，别当成一回事。 */
  tenantId: string
  /**
   * 这一次运行的工作区目录；空串 = 没有权限上下文（老数据、curl 直接调）。
   *
   * 它与 {@link AgentPermissionLevelValue} 一起回答"这次是在什么前提下跑的"——
   * 而在「完全权限」档位下**写操作不产生审核请求**，所以这一列是事后回答
   * "那次为什么没弹确认"的唯一依据。
   */
  workspaceDir?: string
  /** 档位枚举名；空串 = 未记录。取值见 {@link AgentPermissionLevelValue} */
  permissionLevel?: string
}

/** `GET /api/agent/runs` 的响应 */
export interface AgentRunsResponse {
  runs: AgentRunRow[]
  count: number
  /** 有多少次被中断的运行在等恢复（默认配置下不会自动恢复，所以这个数字要显眼） */
  awaitingRecovery: number
}

/** `GET /api/agent/runs/{id}` 的响应 */
export interface AgentRunDetail {
  ok: boolean
  error?: string
  run: {
    id: string
    question: string
    status: string
    stopReason: string
    failureCause: string
    failureAdvice: string
    finalAnswer: string
    totalTokens: number
    toolCalls: number
    elapsedMs: number
    node: string
    /** 业务归因键，空串 = 未标注 */
    intent: string
    /** 租户，空串 = 未标注租户（与 `intent` 是两个维度） */
    tenantId: string
    /** 工作区目录，空串 = 没有权限上下文（老数据、外部链路） */
    workspaceDir?: string
    /** 档位枚举名，空串 = 未记录 */
    permissionLevel?: string
    /** 档位的中文标签，空串 = 未记录 */
    permissionLabel?: string
  }
  steps: AgentStep[]
  toolCalls: AgentToolCall[]
  /**
   * 这次运行里人一共被问过几次、结果如何、各等了多久。
   *
   * 与 `approvals` 那张**待办**列表分开：待办回答"现在要我批什么"（当下），
   * 这里回答"这次任务里我被问过几次"（历史）。混在一起人分不清
   * "这条是要我处理的"还是"这条是已经处理过的"。
   */
  approvals?: AgentRunApprovalRow[]
  approvalCount?: number
  pendingApprovals?: number
}

// ---------------------------------------------------------------------------
//  五层 trace（阶段四第 2 件）
//
//  唯一事实来源：ai.agent.service.agent.trace.AgentTraceView。改那边就要同步改这里。
//  ⚠️ 这个文件里已经写过一次教训了（见运行详情那条注释）：形状不一致的契约
//  会让界面报一个与真实代码无关的错 —— 所以这里的字段名逐一对照后端。
// ---------------------------------------------------------------------------

/** 一条检索命中（trace 第 5 层的一行）。 */
export interface AgentTraceHit {
  /** 名次，从 1 开始 —— **就是模型看到的名次** */
  rank: number
  /** 块 id（`文档id#序号`）—— 能直接回到分块结果 */
  chunkId: string
  documentId: string
  /** 标题路径（"这条命中属于哪一节"） */
  breadcrumb: string
  /** 这个块**估算**的 token（估算口径，不是上游 usage） */
  tokenCount: number
  /** 相关性得分。**只在同一次检索内可比** —— 不同引擎的量纲不同 */
  score: number
  excerpt: string
}

/** 一次检索调用（阶段四第 2 件的核心：原先只存在于回填文本里的那一层）。 */
export interface AgentTraceRetrieval {
  stepIndex: number
  callIndex: number
  toolName: string
  /** 实际拿去检索的查询词（模型给的原词，未归一化） */
  query: string
  /** 模型要了几条 */
  topK: number
  /** 实际命中几条。**0 是正常结果，不是故障** */
  hitCount: number
  /** 当时索引里有多少块 —— 它是"0 条命中"的分母 */
  indexSize: number
  /** 命中块的估算 token 合计 */
  hitTokens: number
  elapsedMs: number
  /** 命中小等于 topK（召回可能不足） */
  shortOfTopK: boolean
  hits: AgentTraceHit[]
}

/** trace 里的一步（比 `/runs/{id}` 的步骤多出提示词指纹与检索明细）。 */
export interface AgentTraceStep extends AgentStep {
  stepIndex: number
  totalTokens: number
  cacheReadTokens: number
  modelName: string
  /**
   * **这一步用的是哪一版系统提示词**（形如 `v3:9ab12cd34ef5`）。
   *
   * 空串 = 库里读不到那一版（老数据 / 提示词服务当时不可用）——
   * 页面必须把这两种情况都说出来，而不是显示成一个空白格。
   */
  promptFingerprint: string
  /** 这一步的每一次工具调用，各自带着它的检索明细 */
  toolCalls: (AgentToolCall & { retrievals: AgentTraceRetrieval[] })[]
  retrievalCalls: AgentTraceRetrieval[]
  retrievalCount: number
  retrievalHitTokens: number
}

/** 会话里的一次运行（trace 第 1 层）。 */
export interface AgentTraceConversationRun {
  id: string
  question: string
  status: string
  stopReason: string
  totalTokens: number
  createdAt: string
  /** **我正在看的是哪一次** —— 一段对话里所有运行的形状都一样，不标就分不清 */
  current: boolean
}

/** 会话那一层。`present=false` 表示**这次运行没有会话信息**（调用方没传）。 */
export interface AgentTraceConversation {
  present: boolean
  id: string
  runCount: number
  truncated?: boolean
  runs: AgentTraceConversationRun[]
  note?: string
}

/** 检索汇总：回答"这次任务查了几次、一共看了多少字"。 */
export interface AgentTraceRetrievalSummary {
  calls: number
  hitCount: number
  /** **估算**口径，见 `note` */
  hitTokens: number
  hitTokensEstimated: boolean
  /** 一次都没查到的检索次数 —— 它最该被单独看见（白跑一轮照样花钱） */
  emptyCalls: number
  steps: number
  promptTokensForShare: number
  shareOfPrompt: number
  note: string
}

/** 一次运行里某个模型的 token 与金额。 */
export interface AgentTraceModelCost {
  model: string
  uncachedPromptTokens: number
  cachedPromptTokens: number
  completionTokens: number
  /** `null` = **算不出来**（没价格），与 0 完全不是一回事 */
  amount: string | null
  amountMicros: number | null
  amountReason: string | null
  priceRule?: string
  priceSource?: string
}

/**
 * 金额。
 *
 * ⚠️ `amount === null` 时**绝不能显示 ¥0.00** —— 那会被读成"这次不花钱"，
 * 而真相是"我们不知道它花多少"。`amountReason` 里有能照着做的说明。
 */
export interface AgentTraceCost {
  pricingConfigured: boolean
  currency: string
  uncachedPromptTokens: number
  cachedPromptTokens: number
  completionTokens: number
  totalTokens: number
  byModel: Record<string, AgentTraceModelCost>
  unpricedModels: string[]
  amount: string | null
  amountMicros: number | null
  amountReason: string | null
}

/**
 * 五层的**到场清单**：每一层有没有数据、以及**这一层该不该有数据**。
 *
 * `expected=false` 表示"这一层本来就不该有内容"（例如一次没调工具的运行在第 4、5 层）。
 * 没有这个布尔的话，「空」与「漏记」在页面上长得完全一样，而它们的含义正好相反。
 */
export interface AgentTraceLayer {
  index: number
  name: string
  key: string
  storage: string
  expected: boolean
}

/** `GET /api/agent/runs/{id}/trace` 的响应 —— 五层完整还原。 */
export interface AgentTrace {
  ok: boolean
  error?: string
  run: {
    id: string
    question: string
    status: string
    stopReason: string
    failureCause: string
    failureAdvice: string
    finalAnswer: string
    totalTokens: number
    toolCalls: number
    elapsedMs: number
    node: string
    /** 业务归因键；空串 = 未标注 */
    intent: string
    /** 租户；空串 = 未标注租户（第 6 件加上来的第二个归因维度） */
    tenantId: string
    /** 工作区目录；空串 = 这次运行没有权限上下文 */
    workspaceDir?: string
    /** 档位枚举名；空串 = 未记录 */
    permissionLevel?: string
    /** 档位的中文标签；空串 = 未记录 */
    permissionLabel?: string
    createdAt: string
    updatedAt: string
  }
  conversation: AgentTraceConversation
  steps: AgentTraceStep[]
  retrieval: AgentTraceRetrievalSummary
  cost: AgentTraceCost
  approvals: AgentRunApprovalRow[]
  layers: AgentTraceLayer[]
  notes: string[]
}

/** 模型侧闸门的快照。`available=false` 表示这一层**没装上**，不是"没有流量"。 */
export interface ModelGateSnapshot {
  available: boolean
  maxConcurrent: number
  inFlight?: number
  peakInFlight?: number
  acquired?: number
  rejected?: number
  averageWaitMs?: number
  maxWaitMs?: number
  note?: string
}

/** 每把工具一行的闸门 + 熔断状态 */
export interface ToolLimitRow {
  toolName: string
  maxConcurrent: number
  inFlight: number
  peakInFlight: number
  acquired: number
  rejected: number
  averageWaitMs: number
  maxWaitMs: number
  /** `CLOSED` / `OPEN` / `HALF_OPEN` */
  circuitState: string
  windowSize: number
  failureRate: number
  circuitOpenCount: number
  shortCircuited: number
}

/** `GET /api/agent/limits` 的响应 */
export interface AgentLimitsResponse {
  config: {
    maxConcurrentModelRequests: number
    maxConcurrentToolRequests: number
    acquireTimeoutMs: number
    circuitEnabled: boolean
    failureRateThreshold: number
    minimumCalls: number
    windowSize: number
    openDurationMs: number
  }
  modelGate: ModelGateSnapshot
  tools: ToolLimitRow[]
  totals: {
    toolsTracked: number
    totalRejected: number
    totalShortCircuited: number
    openCircuits: number
  }
  note: string
}

// ---------------------------------------------------------------------------
//  调用函数
// ---------------------------------------------------------------------------

/**
 * **跑一个任务**。
 *
 * **它会真的调模型并花钱**，所以界面上必须有明确的动作（按钮 + 提示成本），
 * 不做任何自动触发。
 *
 * <h2>两个必填的执行前提：工作区目录与权限档位（本次新增）</h2>
 * 后端在**动模型之前**校验它们（目录必须真实存在且确实是目录）。
 * 校验失败时后端不会开跑、不花钱，返回的是 `{ok:false, error:"…"}` ——
 * 所以这里的失败提示是能照着改的那种（"工作区目录不存在：…"）。
 *
 * <p>目录当前不改变任何工具的行为（本项目没有文件工具），
 * 它现在的作用是：必填校验、权限判定的依据、以及落库的审计字段。
 * 完整判据见 {@link AgentPermissionLevelValue}。
 *
 * @param workspaceDir 工作区目录（**必填**）。可以是绝对路径，也可以是相对路径
 *            （相对路径按 Java 进程的工作目录解析）。
 * @param permissionLevel 权限档位；省略或传空则后端按**仅可查看**算（最严的一档）
 * @param intent 业务归因键（阶段四成本看板）：这次运行为哪个业务花的钱。
 *            留空则看板上归到「未标注」那一行 —— **不会被并进任何一个业务**，
 *            因为"猜一个业务"会让那个业务的成本虚高，而虚高的部分无从发现。
 *
 *            <p>它由**调用方**给而不是后端自己推：后端只能看到问题文本，
 *            而"这段文本属于哪个业务"是业务侧才知道的事（同一个问题可能被两个业务问）。
 * @param tenantId 租户（阶段四第 6 件）：这次运行花的是**谁**的钱。
 *            留空则看板上归到「未标注租户」那一行。
 *
 *            <p>⚠️ 它与 `intent` 是**两个维度、两列**，不是同一个字段的两种叫法 ——
 *            拼接（`京东-客服问答`）会让"按租户看一共多少"退化成前缀匹配，
 *            而一次拼写不一致就让同一个租户裂成两行（两行加起来才是真相，没人会去加）。
 *
 *            <p>它同样由调用方给：后端看不出来"这次是替谁跑的"。
 * @param executorId 执行者（本次新增）：这次运行代表谁。
 *
 *            <p>后端拿它算**这次能注入哪些技能**：执行者的角色 → 这些角色的目录前缀 →
 *            落在这些目录下的技能；name + desc 拼进系统提示词，正文由模型按需取。
 *            填错（账号表里没有 / 已停用）时后端在**花钱之前**回 `{ok:false}`。
 *
 *            <p>留空（`null`）不是错误：没有执行者就没有技能那一节。
 *            **刻意不给一个默认值** —— 默认成"第一个人"会让一次运行读到它不该读的技能，
 *            而那种错在页面上完全看不出来（技能确实是注入了，只是按别人的权限注入的）。
 */
export function askAgent(
  question: string,
  workspaceDir: string,
  permissionLevel: AgentPermissionLevelValue,
  intent = '',
  tenantId = '',
  conversationId = AGENT_CONVERSATION_ID,
  executorId: number | null = null,
): Promise<AgentAskResponse> {
  return postJson<AgentAskResponse>(
    '/api/agent/ask',
    {
      question,
      intent,
      tenantId,
      [AGENT_CONVERSATION_FIELD]: conversationId,
      // 字段名与后端 AskRequest 逐字对应。**工作区进请求体而不是 query**：
      // 路径里有反斜杠、空格、中文与冒号，URL 转义规则会静默改写它们 ——
      // 现象是"我填的目录它说没有"，而没人会想到是转义
      workspaceDir,
      permissionLevel,
      // 同理进请求体：它是 `agent_user.id`，而界面这一侧唯一的来源是页头那条模拟登录记录
      executorId,
    },
    '跑 Agent 任务',
  )
}

// ---------------------------------------------------------------------------
//  运行权限（本次新增）
//
//  唯一事实来源：ai.agent.entity.agent.AgentPermissionLevel
//               + ai.agent.service.tool.workspace.AgentPermissionPolicy
// ---------------------------------------------------------------------------

/**
 * 三档权限的**取值**。它必须与后端 `AgentPermissionLevel` 的枚举名逐字一致 ——
 * 后端对认不出的档位是**拒绝**（不是降级），所以拼错一个字母会得到一条
 * "permissionLevel 的取值不认识"的错误，而不是一个看起来正常的运行。
 */
export type AgentPermissionLevelValue = 'READ_ONLY' | 'WORKSPACE_WRITE' | 'FULL_ACCESS'

/**
 * 界面上的三档，以及**每一档到底会发生什么**。
 *
 * <h2>为什么每一档都带一句"会发生什么"，而不是只写三个名字</h2>
 * 这三档里有两档在**当前功能下行为完全相同**（仅可查看 / 工作区内修改：本项目的工具没有路径参数，
 * 判不出"写在工作区内还是外"）。只给三个名字的话，使用者会以为"工作区内修改"已经放宽了什么，
 * 而它没有 —— 于是他会选一个自己以为更宽的档位，然后对"怎么还在弹审批"感到困惑。
 *
 * <p>所以文案里把差异**如实说明**：这一档与上一档的差别，要等真的有了文件工具才成立。
 * 「完全权限」那一档是真的不同（写操作直接执行、不产生审核请求），所以它的说明也最重。
 */
export const AGENT_PERMISSION_LEVELS: {
  value: AgentPermissionLevelValue
  label: string
  /** 单选按钮下面那行小字 */
  hint: string
}[] = [
  {
    value: 'READ_ONLY',
    label: '仅可查看',
    hint: '只读工具（检索 / 列目录 / 读章节 / 查库）直接执行；任何写操作都要人点头',
  },
  {
    value: 'WORKSPACE_WRITE',
    label: '工作区内修改',
    hint: '写操作仍然要人点头 —— 本项目的工具没有路径参数，判不出「写在工作区内还是外」，所以这一档与「仅可查看」当前行为相同',
  },
  {
    value: 'FULL_ACCESS',
    label: '完全权限',
    hint: '⚠️ 写操作直接执行，不产生审核请求（建工单 / 改数据库都不会停下来等人）',
  },
]

/** 默认档位：最严的一档。与后端"档位缺失按仅可查看算"是同一个取向。 */
export const DEFAULT_AGENT_PERMISSION: AgentPermissionLevelValue = 'READ_ONLY'

/**
 * 请求体里工作区目录的字段名。
 *
 * <p>抽成常量与 {@link AGENT_CONVERSATION_FIELD} 同一条理由：它同时出现在"发请求"
 * 与界面自检里，拼错一处不会报错 —— 只会让后端认为"没传工作区"，
 * 而那种现象（400 或"工作区目录是必填的"）看起来像界面没填，不像字段名写错。
 */
export const AGENT_WORKSPACE_FIELD = 'workspaceDir'

/**
 * 请求体里权限档位的字段名（同上）。
 */
export const AGENT_PERMISSION_FIELD = 'permissionLevel'

/**
 * 界面默认填的工作区目录。
 *
 * <h2>为什么不给空串</h2>
 * 后端把它当必填，所以空串的后果是"点一次跑一次报错"。给一个**能改的默认值**
 * 至少让第一次点得动，而人看到它之后会顺手改成自己那条工作目录 ——
 * 与 `intent` 默认「客服问答」、`tenantId` 默认「示例租户」是同一条取舍。
 *
 * <p>取值是 `.`（Java 进程的工作目录）：它是**唯一一个在任何机器上都存在**的目录，
 * 而写死一个绝对路径会在别人的机器上必然失败。后端会把它规范化成 "." 并原样回显。
 */
export const DEFAULT_AGENT_WORKSPACE = '.'

/** 历史运行列表。 */
export function getAgentRuns(limit = 20): Promise<AgentRunsResponse> {
  return getJson<AgentRunsResponse>(`/api/agent/runs?limit=${limit}`, 'Agent 运行列表')
}

/** 一次运行的完整过程。 */
export function getAgentRunDetail(runId: string): Promise<AgentRunDetail> {
  return getJson<AgentRunDetail>(`/api/agent/runs/${encodeURIComponent(runId)}`, 'Agent 运行详情')
}

/**
 * **本次页面会话的会话 id**（阶段四第 2 件：Agent trace 的第一层）。
 *
 * <h2>为什么生成在前端，而不是后端自动生成</h2>
 * 因为"这两个问题属于同一段对话"是<b>只有调用方才知道的事实</b>。
 * 后端只看得到问题文本，它无从判断两次 `/ask` 是不是同一段对话 ——
 * 硬猜（比如"同一个 IP 就是同一段会话"）会让两段无关的对话被并成一段，
 * 而那种错在页面上表现为"这段对话里怎么有个我没问过的问题"。
 *
 * <p>界面上发起任务是同一个人在同一页里连续问的，所以这一段会话就是
 * "这个页面从打开到关掉"。后端那边**刻意不自动生成**（见 `AskRequest.conversationId`
 * 的注释）：curl 直接调时没有会话信息，界面必须能把它显示成"没有会话信息"
 * 而不是伪装成一段只有一次运行的对话。
 *
 * <h2>为什么是模块级常量而不是每次请求新建一个</h2>
 * 每次请求新建 = 每次运行都是一段独立会话，这一层就白做了。
 * 这一层要回答的正是"这段对话里一共跑了几次、谁挂了、一共花了多少"。
 *
 * <p>⚠️ 已知边界：**刷新页面就会换一个新的会话 id**（模块级变量随页面重载重置）。
 * 也就是说"刷新前后的两次提问"在 trace 里是两段对话。
 * 这是刻意的取舍 —— 要跨刷新保持就得落 sessionStorage，
 * 而那种持久化会让"清掉浏览器数据"与"换一段对话"变成两个不同的动作，
 * 复杂度大于它解决的问题。真正的多轮对话应当由后端的对话记忆那条链路承担
 * （它有自己的 conversationId，与本字段是两件事）。
 */
export const AGENT_CONVERSATION_ID =
  typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? `web-${crypto.randomUUID()}`
    : `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

/** 一次运行的<b>五层 trace</b>（阶段四第 2 件）。 */
export function getAgentTrace(runId: string): Promise<AgentTrace> {
  return getJson<AgentTrace>(`/api/agent/runs/${encodeURIComponent(runId)}/trace`, 'Agent trace')
}

/** 四层保护的当前状态。 */
export function getAgentLimits(): Promise<AgentLimitsResponse> {
  return getJson<AgentLimitsResponse>('/api/agent/limits', 'Agent 限额状态')
}

/**
 * 请求体里的会话 id 字段名。
 *
 * <p>抽成常量是因为它同时出现在"发请求"与自检里 —— 拼错一处不会报错，
 * 只会让会话那一层悄悄变成"没有会话信息"（而后端对这种缺失是**如实显示**的，
 * 所以现象是"会话那一块说没有会话信息"，看不出是前端字段名写错了）。
 */
export const AGENT_CONVERSATION_FIELD = 'conversationId'

// ---------------------------------------------------------------------------
//  HITL：人工确认（第九轮）
// ---------------------------------------------------------------------------

/**
 * 现在有哪些写操作在等人确认。
 *
 * **它会被轮询**（每隔 `AGENT_APPROVAL_POLL_MS`），理由见那个常量的注释：
 * 有人在等它，所以"界面没刷新"的后果是任务白白超时。
 *
 * <p>超时用只读的那个值（30 秒）而不是 `/ask` 的 180 秒：它只查一次库，
 * 而如果它慢到 30 秒，轮询本身已经失去意义了。
 */
export function getAgentApprovals(): Promise<AgentApprovalsResponse> {
  return getJson<AgentApprovalsResponse>('/api/agent/approvals', '待确认的写操作')
}

/**
 * 批准或拒绝。
 *
 * <h2>这个调用的超时给多长，以及为什么它必须 ≥ 审批时限</h2>
 * 后端在"有线程正在等"时是**当场唤醒**它并在**同一个请求里**把任务跑完的
 * （见 `AgentApprovalController.decide` 的注释）—— 所以这个请求的耗时可能是
 * 「一次完整的收尾」而不是"写一行库"。
 *
 * <p>反过来说：**如果这里给太短**，我们会看到"批准了但界面显示失败"，
 * 而后端其实正在成功返回的路上 —— 那正是阶段一踩过的那类假故障
 * （钉住实验 19~26 秒 vs 前端 20 秒超时）。所以它复用一个足够宽的额度。
 *
 * <p>但**不能**用 `/ask` 的 180 秒：一旦超过审批时限（默认 120 秒），
 * 那个等待的线程已经按超时收尾了，这里再等就是纯等一个不会来的结果。
 * 取 `AGENT_APPROVAL_DECIDE_TIMEOUT_MS` = 150 秒，等于"时限 120 秒 + 30 秒余量"。
 */
export const AGENT_APPROVAL_DECIDE_TIMEOUT_MS = 150_000

export function decideAgentApproval(
  approvalId: string,
  approve: boolean,
  decidedBy: string,
  note: string,
): Promise<AgentApprovalDecisionResponse> {
  return postJson<AgentApprovalDecisionResponse>(
    `/api/agent/approvals/${encodeURIComponent(approvalId)}/decide`,
    // `approve` 是**必填的布尔**：后端不给默认值（缺字段时 Jackson 按 false 处理 = 拒绝）。
    // 一个"缺字段就当批准"的默认值会在一次漏传字段的调用里静默放行一个写操作 ——
    // 那是这一层最不能有的默认值，所以前端也显式传
    { approve, decidedBy, note },
    '人工确认',
    AGENT_APPROVAL_DECIDE_TIMEOUT_MS,
  )
}

/** 审批闸门的当前状态（开关、时限、待批数）。 */
export function getAgentApprovalStatus(): Promise<AgentApprovalStatusResponse> {
  return getJson<AgentApprovalStatusResponse>('/api/agent/approvals/status', '人工确认状态')
}

/** 某次运行里经历过的全部人工确认（历史，不是待办）。 */
export function getAgentRunApprovals(runId: string): Promise<{ approvals: AgentRunApprovalRow[]; count: number; pending: number }> {
  return getJson(`/api/agent/runs/${encodeURIComponent(runId)}/approvals`, '这次运行的人工确认记录')
}

// ---------------------------------------------------------------------------
//  内部工具
// ---------------------------------------------------------------------------

async function getJson<T>(path: string, what: string, timeoutMs = AGENT_READ_TIMEOUT_MS): Promise<T> {
  const response = await request(path, what, 'GET', timeoutMs)
  return parseBody<T>(response, what)
}

async function postJson<T>(path: string, body: unknown, what: string, timeoutMs = AGENT_ASK_TIMEOUT_MS): Promise<T> {
  const response = await request(path, what, 'POST', timeoutMs, JSON.stringify(body))
  return parseBody<T>(response, what)
}

/** 200 但不是 JSON（例如被代理换成了首页 HTML）时，"解析失败"才是真相。 */
async function parseBody<T>(response: Response, what: string): Promise<T> {
  const text = await response.text().catch(() => '')
  try {
    return JSON.parse(text) as T
  }
  catch {
    throw new DiagError(`拿「${what}」时后端返回的不是 JSON：${text.slice(0, 200) || '(空响应体)'}`)
  }
}
