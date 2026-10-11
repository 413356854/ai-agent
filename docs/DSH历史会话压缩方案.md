# DSH 历史会话压缩（compaction）方案与全链路核实

**被解析的代码库**：`C:\Users\ljf\Desktop\dsh\deepseek-harness`
**核实时的 revision**：`d743267388641bc76f17c45ce8b4c231aed1d32c`（release-0.2.1-alpha.2），工作区干净（`git status` 无改动）
**本机的已安装副本**：`C:\Users\ljf\AppData\Local\Programs\DeepSeek Harness\resources\app.asar`（打包成 asar，不是可读目录树）—— 所以下面所有结论都以源码仓库为准。

本文只写 DSH 侧的事实。与工作区里 `ai-agent/` 的对照见最后一节（第 9 节）。

---

## 0. 结论速览

DSH 的历史会话压缩不是"把上下文窗口裁短"，而是**在一个 append-only 的事件日志上，把一段旧位置替换成一条摘要消息**。

四层机制，从便宜到贵：

| 层 | 包 | 花不花模型钱 | 什么时候跑 |
|---|---|---|---|
| ① 工具结果裁剪 | `dsh-compaction-tool-result-pruner` | 不花 | 只在已经触发压缩之后 |
| ② 摘要式压缩 | `dsh-compaction-basic`（注册 `ctx.compaction`） | **花一次额外请求** | 压力越阈值 / 供应商报超窗 / 人打 `/compact` |
| ③ 图片卸载 | `dsh-compaction-image-offload` | 不花 | 只在图片路由拒绝请求时 |
| ④ 计费与判定 | `dsh-token-meter`（`ctx.tokenMeter`） | 不花 | 每次判定都读它 |

真正的"压缩"只有 ② 花模型调用。它的产物是**一条新的 `user/message`**，旧的那几十条事件仍在日志里，只是不再进入模型可见的历史（下称"表面/surface"）。

**关键设计点**

1. **事务用日志事件记锁**：`compaction/start` 先落盘 → 再摘要 → 再落 `compaction/summary` + 替换消息 → 最后 `compaction/end`。崩在中途会留下"没有配对的 start"，这是一个**可检测的孤儿锁**，而不是一条谎称成功的 end。（`compaction-basic/src/region.ts:210`、`:237`、`:245`）
2. **摘要请求是原请求的前缀**：把会话自己的 system 提示词、工具声明、被压缩区间逐字节重放，压缩指令只作为**最后一条 user 消息**追加 —— 这样供应商的 KV 缓存前缀能复用，只有指令和摘要输出是未命中的部分。（`compaction-basic/src/summarizer.ts:25-31`、`region.ts:544-563`）
3. **替换是一次 deep-freeze 的 splice**：`surfaceOp: { op: 'replace', startSeq, endSeq }`，模型可见历史由 `deriveMessages()` 从这个表面折叠出来，重放同一份日志得到同一份历史。（`core/session/src/surface.ts:570`）
4. **没有模型可见的 compact 工具**：压缩只能由自动压力、超窗恢复、人打 `/compact` 触发。（`compaction/compaction/README.md:159`，源码里没有注册 tool）

---

## 1. 换一条历史，需要付出什么

先看一次自动压缩**总共写了什么**。走的是 `compactSurfaceRegion()`（`compaction-basic/src/region.ts:173-276`）：

| 顺序 | 写的事件 | 只在日志 | 改模型可见历史 | 代码位置 |
|---|---|---|---|---|
| 1 | `compaction/start { compactionId, sourceCommandId?, turn }` | ✅ | — | `region.ts:210` |
| 2 | （不写日志）调 `ctx.llm.stream()` 生成摘要 | — | — | `summarizer.ts:163` |
| 3 | `compaction/summary { compactionId, summary, rawOutput?, llmStreamCall, shadowedRange, shadowedSeqs, shadowedTokenCount, provider, model, maxTokens?, usage? }` | ✅ | — | `region.ts:491-505` |
| 4 | `user/message`（摘要正文，`surfaceOp: { op:'replace', startSeq, endSeq }`，`sourceEventSeqs: [start, summary, ...被遮蔽的 seq]`） | — | ✅ 唯一一次 | `region.ts:506-509` |
| 5 | `compaction/end { compactionId, sourceCommandId?, turn, error? }` | ✅ | — | `region.ts:237` / 失败时 `:245` |

三条硬约束（都在写之前/写之后立即校验）：

- **摘要必须真的更小**：`framedSummaryTokenCount >= shadowedRouteTokenCount` 就抛错、不提交（`region.ts:417-422`）。比的是"路由价"（当前 route 的图像计价规则下的价），因为要回答的是"下一跳请求压力降没降"。
- **替换区间两侧必须工具配对平衡**：`toolPairingBalancedBefore/After`，不允许把一个 assistant 的 tool-call 和它的 tool/result 拆开（`region.ts:349-355`）。
- **`sourceEventSeqs` 必须覆盖每一个被遮蔽的节点**，否则 session 层直接拒绝（`core/session/src/surface.ts:361-364`）。

---

## 2. 什么时候压：三种入口

| 入口 | 触发点 | 阈值 | 记忆尾 | 稳定性要求 | owner |
|---|---|---|---|---|---|
| 自动压力 | `agent/pre-step` 串行监听（请求派生之前） | 完整策略 | 保留 | 整个表面不许变 | 当前 turn 号 |
| 超窗恢复 | `agent/request-error`，仅 `CONTEXT_WINDOW_EXCEEDED` | **绕过阈值**，做一次最大化的头部缩减 | `retainTokens = 0` | 整个表面不许变 | 当前 turn 号 |
| 人工 `/compact` | `ctx.compaction.compactNow()` | 无阈值，只要"有用" | `retainTokens = 0` | 只要求**选中区间**不变 | `turn: null` |

代码：`compaction-basic/src/index.ts:158-176`（压力）、`:190-234`（超窗）、`:383-435`（人工）。

### 2.1 阈值到底是多少（`config.ts:153-217`）

```
messageBudgetTokens  = W − O                    (W=适配器 contextWindow, O=本次请求预留的输出上限)
pressureBudgetTokens = messageBudgetTokens − B  (B = headroomTokens)
thresholdTokens      = floor( min( W × thresholdRatio, pressureBudgetTokens ) )
retainTokens         = retainRatio × messageBudgetTokens    或  显式 retainTokens
```

默认值：`thresholdRatio = 0.8`、`retainRatio = 0.16`、`headroomTokens = 65_536`、`maxTokens = headroomTokens`、`compactionRetries = 1`、`maxOverflowRetries = 1`、`auto = true`（`config.ts:20`、`:23`、`:75-76`、`:105-108`）。

**用仓库自带的 DeepSeek 默认参数代入**（`W = 1_000_000`，`O = 256_000`，见 `llm-deepseek/src/defaults.ts:6,8`，`models.ts` 的两个目录模型都不覆盖）：

| 量 | 值 | 含义 |
|---|---|---|
| `messageBudgetTokens` | 744 000 | 可用于消息的预算 |
| `pressureBudgetTokens` | 678 464 | 再扣掉 65 536 的压缩余量 |
| `W × 0.8` | 800 000 | 比例口径 |
| **`thresholdTokens`** | **678 464** | 取两者较小 → 约 67.8 万 token 时开始压 |
| **`retainTokens`** | **119 040** | 尾部约 11.9 万 token 原样保留 |

也就是说：**压缩发生在上下文窗口的 67.8% 处，而不是 100% 处** —— 留出的 32% 是给"压缩本身那次请求 + 会话继续长"的。这是本项目里"宁可早压"的显式取舍。

派生规则与失败规则：

- 保留预算必须**严格小于**阈值，否则 `retainTokens must be less than threshold tokens` 直接拒绝（`config.ts:198-204`）。
- `W − O − B ≤ 0` 时抛 `TargetPressureConfigError`；自动路径对同一个 target 只警告一次，然后**跳过主动压缩**，但超窗恢复仍然可用（`index.ts:167-173`）。
- `compactionRetries` 是**语义重试**：压完重新量，还超阈值就再压一轮；`compactionRetries + 1` 次之后仍超阈值 → 抛错（`index.ts:330-346`）。
- `maxOverflowRetries` 用 WeakMap 计数，`agent/status → idle` 或下一条 `assistant/message` 落盘时清零（`index.ts:178-188`）。

---

## 3. 压哪一段

`selectCompactableRange()`（`region.ts:117-155`），纯读、不写：

1. **起点不能是 system 提示词**：若表面第 0 个节点是 `system/message`，起点从下标 1 开始（`region.ts:131`）。压缩永远不动 system 提示词、工具声明、会话前缀 —— 这是"压不动的东西"的清单（`compaction-basic/README.md:244`）。
2. **从尾往前累加到 `retainTokens`**，得到"必须原样保留"的起点 `keepFromIdx`（`region.ts:133-140`）。
3. **向前吸附到工具配对的平衡点**：`while (!toolPairingBalancedBefore(...)) keepFromIdx--`（`region.ts:143-147`）。
4. 若 `keepFromIdx <= firstIdx` → 返回 `null`（没什么可压，**一个字节都不写**）。

配对边界本身有缓存：`tool-pairing.ts` 按"当前表面顺序"维护每次切点的余额缓存，`replaceGeneration` 变化时重建；缺失 seq 或孤儿 `tool/result` 一律判为表面损坏。

**先剪后压**：一旦确认越阈值，先调可选的 `ctx.toolResultPruner.pruneSession()`，**再重新量一次**；如果剪完已经低于阈值就直接返回，不花摘要钱（`index.ts:292-327`）。裁剪本身（`compaction-tool-result-pruner/src/index.ts:136-179`）逐条把超预算的工具结果换成"头 4096 + 标记 + 尾 1024"（字符数，按 Unicode 码点切，不切代理对），并在替换事件**紧邻之前**写一条 `compaction/prune` 影子价格事件。基础配置里这个预算就是 8192/4096/1024（`bundle/base/cordis.patch.yml:421-425`）。

---

## 4. 摘要那一次模型调用

### 4.1 送什么进去

`summarizeWithLlm()`（`summarizer.ts:120-181`），一次 `ctx.llm.stream()`：

| 字段 | 值 | 依据 |
|---|---|---|
| `provider` / `model` | 显式配置对 → 否则**最后一条已落盘请求**的 route → 否则 `AgentOptions` 对 | `summarizer.ts:127-142` |
| `messages` | `[system节点重放, ...被压缩区间的消息]` + 最后追加一条 user 指令 | `region.ts:544-563`、`summarizer.ts:145-151` |
| `tools` | 表面第 0 节点那次请求头里的活动工具声明 | `region.ts:548-560` |
| `toolHistory` | `session.toolHistory()` | `summarizer.ts:156` |
| `maxTokens` | `config.maxTokens`（默认 65 536） | `summarizer.ts:158` |
| `sessionId` | `agent.session.id` | `summarizer.ts:159` |
| **`purpose`** | **`'compaction'`** | `summarizer.ts:160` |
| `signal` | 调用方的取消信号，逐层透传 | `summarizer.ts:161` |

空内容的 system 头不产生消息，但仍留在被压缩区间之外（`region.ts:552` + `deriveEventMessage` 对空内容返回 `null`）。

### 4.2 提示词全文

压缩指令（`summarizer.ts:32-67`，原文见 `compaction-basic/README.md` 的 "Compaction instruction"）。它要求输出固定 8 段 Markdown：Primary Request and Intent / Key Technical Concepts / Files and Code / Errors and Fixes / Pending Jobs / Current Work / Next Step / Critical Context；空段写 `(none)` 但不许省段；要求保留精确路径、命令、报错串、标识符、数值、签名；**不许提到"这是一次摘要"**；若对话里已经有 `<compacted-summary>` 块，那是**上一次的检查点**，要合并事实、丢掉过期的，而不是原样抄一遍。

落盘时再包一层框架（`summarizer.ts:188-194`）：

```
<CHECKPOINT_PREAMBLE 一行>\n\n<compacted-summary>\n{摘要}\n</compacted-summary>
```

preamble 的作用是把这条消息定性为"已确立的背景"，并明确要求**不要复述、不要回应这条检查点**（`summarizer.ts:70-71`）。

### 4.3 为什么这样摆（KV 缓存）

注释写得很直白（`summarizer.ts:25-31`、`region.ts:532-542`）：把会话自己的 system + 工具 + 被压区间原样放在前面，压缩指令放在最后 → 这次调用是上一条请求的**真前缀**，供应商侧只有指令和输出是冷的。代价是：**把摘要模型换成别的 provider/model，或者压的不是头部区间，就放弃这份复用**（`compaction-basic/README.md:233`）。

### 4.4 失败怎么办

- `ctx.llm.stream()` 的终态 `error` / `aborted` → 抛 `LlmError`；`max-tokens` → 抛 `MAX_TOKENS`（"摘要被截断，检查点不完整"）（`summarizer.ts:197-211`）。
- 摘要里出现图片 → 抛 `UNSUPPORTED_CONTENT`，不让它悄悄消失（`summarizer.ts:214-220`）。
- 摘要文本全空白 → 抛错（`summarizer.ts:169-171`）。
- 抛错后先判取消、再判选中区间是否还稳定，然后走**同步瀑布** `compaction/summary-error`：监听者必须**先落一个持久化的输入改动**才能返回 `true` 请求重试，否则 `next()` 放行（`region.ts:396-409`、`index.ts:442-447`）。当前唯一的监听者是图片卸载（`compaction-image-offload/src/index.ts:33-40`）。
- 自动路径最终仍失败 → `ctx.logger.warn(...)` 后**带着超预算的历史继续本回合**（`index.ts:166-173`）；如果裁剪已经落盘，就从那份已裁剪的表面继续。

---

## 5. 从 agent 任务记录到模型调用，再回到记录

这是"全流程"这一段。核心事实一句话：**模型输入不是被维护在内存里的一个 messages 数组，而是从同一份 append-only 日志投影出来的**。

```
① Agent.turn()             core/agent-loop/src/agent.ts:307   session.append('turn/start')
② Agent.preStep()          core/agent-loop/src/agent.ts:274   waterfall('agent/pre-step')
      ├─ session-checkpoint-policy   session-checkpoint-policy/src/index.ts:79   flush(会话)
      └─ compaction-basic           compaction-basic/src/index.ts:164           compactIfNeeded(...,'pressure')
③ Agent.step()             core/agent-loop/src/agent.ts:410   prepareRequest() → waterfall('agent/request')
④ buildRequest()           core/agent-loop/src/agent.ts:688   session.deriveMessages()  ← 模型历史的唯一来源
⑤ loopCtx.llm.stream(req)  core/agent-loop/src/agent.ts:453
⑥ LlmRuntime.stream()      llm/llm/src/index.ts:1143          waterfall('llm/stream')
      └─ session-checkpoint-policy   session-checkpoint-policy/src/index.ts:64-68
                                     await ctx.sessions.flush(session)  ← 先落盘，再发请求
⑦ adapterStream()          llm/llm/src/index.ts:1041          adapter.prepareCall / dispatch
⑧ HTTP 出站                llm-deepseek/src/adapter.ts:124-135
      headers: x-deepseek-harness-session-id, x-deepseek-harness-compact: 1 (purpose==='compaction')
⑨ 流回写                   core/agent-loop/src/agent.ts:537-546  session.append('assistant/message', …)
```

**④ 是全链路的关键点。** `deriveMessages()`（`core/session/src/index.ts:984-1005`）只遍历 `surface.nodes`，把每个节点投影成消息；压缩替换就是让一段旧节点从 `nodes` 里消失、让摘要那条 `user/message` 出现在它们的位置上。

- 折叠规则在 `core/session/src/surface.ts:563-585`：`replace` 就是 `nodes.splice(startIdx, endIdx-startIdx+1, 新seq)`，并让 `replaceGeneration` / `contentGeneration` 各 +1。
- **原始日志一个字都没删**：被遮蔽的事件仍在日志里，重放同一份日志会得到同一个表面 → 压缩是确定性的（`compaction/compaction/README.md:97`）。
- `deriveMessages()` 有缓存：纯尾部增长是 O(新增节点)，一旦发生替换或消息投影（`contentGeneration` 变化）就整体重建（`core/session/src/index.ts:988-992`）。
- 压缩边界处的"节点 0 保护"是**表面层强制的**，不只靠压缩自己守规矩：替换若覆盖节点 0 且节点 0 是 `system/message`，那么替换事件本身也必须是只覆盖该节点的 `system/message`，否则抛错（`surface.ts:493-507`）。

**⑨ 回写之后**，`assistant/message` 落盘会同时：把一个 `agent/request-error` 的超窗重试计数清零（`compaction-basic/src/index.ts:184-188`），并成为 token meter 的下一个 usage 锚点（`token-meter/src/index.ts:292-321`）。

### 5.1 计费口径（决定"压不压"的那个数）

| 概念 | 含义 | 代码 |
|---|---|---|
| `nodes[].tokens` | 该节点在**当前 route 计价规则**下的请求压力（图片按适配器声明的视觉价） | `token-meter/src/route-pricing.ts`、`types.ts:44` |
| `nodes[].heuristicTokens` | 与 route 无关的固定启发式价，用于**影子价格** | `types.ts:50` |
| `totalTokens` | `max(0, baseline.tokens + surfaceDeltaTokens)` | `token-meter/src/index.ts:187` |
| `baseline.kind='usage'` | 上一条成功调用的 provider usage 与新请求包一致时复用它 | `index.ts:158-171` |
| `baseline.kind='estimated'` | 否则自己算：工具声明 + 表面 + 角色开销 | `index.ts:172-181` |

固定启发式：`4 字符 = 1 token`，每个块 +4，每条消息角色框 +4（`token-meter/src/estimate.ts:13,16,19`）。这是**已知会低估中文/JSON Schema** 的地方，仓库自己把它列为未决项（`compaction-basic/README.md:257`）。

"影子价格协议"值得单独记一句：替换事件必须**紧跟在**一条计价事件之后（摘要压缩是 `compaction/summary`，纯裁剪是 `compaction/prune`），那条事件给出被替换区间的启发式价 —— 这样纯消费者做 O(1) 折叠时不需要保留每个节点的价格（`docs/persistence-catalog.md:381-401`）。

---

## 6. 落到"任务记录"上：持久化与展示

| 环节 | 事实 | 依据 |
|---|---|---|
| 事件是否已知类型 | `compaction/start`、`/end`、`/summary`、`/prune` 都在 `KNOWN_SESSION_EVENT_TYPES` 里 → 可持久化、重放时不会被当未知事件忽略 | `core/session/src/known-event-types.ts:32-35` |
| 持久化 schema | 四个事件的 payload 都有稳定 SHA256 条目 | `docs/persistence-catalog.md:34-37` |
| 格式迁移 | v2→v3 迁移会**重写** `compaction/summary.shadowedRange/shadowedSeqs` 和 `compaction/prune` 的区间，并校验保护字段 | `session/session-format-v2-to-v3/tests/*` |
| 人工路径的落盘时机 | `compactNow()` 在括号闭合后立刻 `ctx.sessions.flush()`，失败映射为 `ManualCompactionError('persistence')` | `compaction-basic/src/index.ts:410-413`、`region.ts:253-272` |
| 自动路径的落盘时机 | 不在压缩里 flush；由下一个请求边界（`llm/stream` 检查点）或 `agent/pre-step` 的 flush 落盘 | `session-checkpoint-policy/src/index.ts:64-82` |
| 对话界面 | `compaction` 节点：只对 `sourceCommandId === undefined` 的自动压缩建节点，位置取检查点 `user/message` 的 seq | `client/ui-chat/src/client/conversation-nodes/compaction.ts:31-57` |
| 轨迹界面（"任务记录"） | `trajectory-compaction` 节点把 start/summary/end **装配成一次 `purpose:'compaction'` 的请求视图**，字段直接从 `compaction/summary` 取（provider/model/maxTokens/usage/summary/rawOutput），`status` 由有无 `compaction/end` 及其 `error` 推出 | `client/ui-trajectory/src/client/trajectory-compaction-definition.ts:40-78` |
| 识别检查点消息 | `user/message.source.kind === 'compact-checkpoint'` 带 `compactionId` | `compaction/compaction/src/checkpoint.ts`（无 cordis 依赖的子路径导出） |
| 重放 | `llm-replay` 能把这次摘要调用还原成一次流：靠 `llmStreamCall: true` + `rawOutput` 成对出现；只有标记没有 `rawOutput` 直接抛错 | `test-support/llm-replay/src/index.ts:453-490` |
| 统计 | `product-analytics` 只监听 `compaction/start`，按它计数 | `client/product-analytics/src/index.ts:40` |

**为什么 `compaction/summary` 要存 `rawOutput` 和 `llmStreamCall`**：这是"一次额外模型调用"的唯一凭据。日志里只留摘要正文的话，事后没人能证明这次摘要花了多少钱、用的是哪个模型、输出里有没有被裁掉的推理。带上 `provider`/`model`/`maxTokens`/`usage` + 完整输出，等于把这次一次性请求变成可重建的（`docs/persistence-catalog.md:420-443`）。轨迹界面就是直接吃这组字段的（见上表）。

---

## 7. 默认装配（`dsh` base profile）

`packages/bundle/base/cordis.patch.yml`：

| 行 | 挂载 | 配置 |
|---|---|---|
| 343-344 | `dsh-token-meter` | 无配置项（配置里出现任何 key 直接报错） |
| 346-347 | `dsh-compaction-basic` | **不写 config** → 全部默认值（0.8 / 0.16 / 65536 / retries 1 / auto true） |
| 351-352 | `dsh-command-compact` | 提供 `/compact` |
| 420-425 | `dsh-compaction-tool-result-pruner` | `thresholdChars 8192 / headChars 4096 / tailChars 1024` |
| 429-430 | `dsh-compaction-image-offload` | 无配置 |

Web 侧另有 `packages/bundle/web-app/cordis.patch.yml:520-527` 与三个 preset（standard / ptc / cordis）把 `compaction: true` 作为 preset 能力位。`sdk-minimal` 明确**不含**压缩（`packages/bundle/sdk-minimal/README.md:12`）。

---

## 8. 实测 / 证据（已跑过的）

| 项 | 内容 | 位置 |
|---|---|---|
| 端到端 e2e（**需真 key，默认 skip**） | 长会话中途压缩、`compaction/start…end` 成对落盘、有 `compaction/summary`、压缩后仍给出最终答复 | `apps/cli/tests/profiles/headless/tests/compaction.e2e.ts:27-98` |
| Web 端到端（有种子数据，不需 key） | 用 `TokenMeter` 真算影子价，构造完整人工 `/compact` 生命周期，验证模型侧表面被遮蔽而记录侧仍可读 | `apps/web/tests/seeded-history.e2e.ts:79-205`、`:392` |
| 单元/契约 | `compaction-basic` 测试套件近 9 万字节；`tool-pairing`、`manual-compaction`、`compaction-loop-repro` 各自独立 | `packages/compaction/*/tests/` |

**未核（本文不下结论）**：本机没有 `node_modules`（仓库未安装依赖），也没有 `DEEPSEEK_API_KEY`，所以上面第一项 e2e 没跑；第 2.1 节那张阈值表是按源码常量代数代入的结果，不是跑出来的日志。

---

## 9. 与工作区 `ai-agent/` 现状的对照

工作区自己的索引把它记在"明确不在本项目范围"里：

> **上下文压缩：滚动摘要 / 关键事实抽取** | 现在只有两种手段，而且**都是"丢"不是"压"**：按条数窗口（2.2）+ 按 token 从最旧整条裁（2.6）。

按本文核实到的 DSH 实现，两者差在四个可迁移的点上：

| 维度 | `ai-agent/` 现在 | DSH 的做法 |
|---|---|---|
| 手段 | 丢整条（不可逆地不出现在 prompt 里） | 压成一条摘要消息；原文仍在日志里 |
| 判据 | 预估 token vs `context-window × (1−safety-margin)` | 同一份计价服务的两个口径：**触发阈值**与**保留尾部**分开算 |
| 触发点 | 发请求前（`TokenBudgetGuard.prepare()`） | 同样是请求派生之前（`agent/pre-step`），另加"供应商确认超窗"这条恢复路径 |
| 记录 | `agent_step.transcript` 存完整对话 | 压缩本身也是**一等事件**：start/summary/end/prune 四个 payload 全部持久化，含这次摘要调用的模型、上限、usage、完整原始输出 |

其中**"压缩也是一条可审计记录"**这一条与本项目 `AGENTS.md` 的口味最合：它让"这次为什么少了 3 万 token"事后有据可查，而不是表现为"越聊越傻"。
