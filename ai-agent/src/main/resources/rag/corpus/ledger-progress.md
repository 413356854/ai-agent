# 学习进度台账

> **单一事实来源**：进度只在这里详细记录，其余文档只保留状态列并指向本文件，避免多处重复维护导致不一致。
> 最后更新：**2026-09-14**（阶段一 8/8 收尾：虚拟线程改造 + Token 预算校验）
> 周计划正文见 `Java开发者-AI-Agent转型路线.md`；资料见 `Java转型-AI-Agent学习资料清单.md`

---

## 一、总览

| 阶段 | 主题 | 状态 | 完成度 |
|---|---|---|---|
| 阶段一 | 大模型对接层 | 🟢 **收尾完成** | 8 个学习点完成 7 个（Function Calling 边界随阶段三做） |
| 阶段二 | RAG | ⬜ 未开始 | 0% |
| 阶段三 | Agent + MCP | ⬜ 未开始 | 0% |
| 阶段四 | 工程化 | ⬜ 未开始 | 0% |

**当前产出**：一个**可运行**的 Spring Boot 4.1.1 + Spring AI 2.0.1 项目骨架，配一个**三种模式对照的界面** ——
SSE 流式对话、对话记忆、**超时与重试**、**结构化输出 + JSON 校验 + 回灌重试**、
**虚拟线程异步改造**、**Token 预算校验**均已实测跑通；
后端 **51 个自动化测试**，界面 **29 项断言**（共 36 个，7 个是 mock 专有路径；真/假后端两条路径都跑过，控制台零错误零警告）。

**本轮的量化结论（两条，都是自己测出来的）**

| 学习点 | 一句话结论 | 关键数字 |
|---|---|---|
| 7 · 虚拟线程 | 它**不加速单个请求**，消除的是"线程不够所以排队" | 2000 并发吞吐 **472 → 1192.6 req/s**；JVM 平台线程 **223 → 32**；单请求延迟几乎不变 |
| 2 · Token 预算 | 系数必须**用真实 usage 标定**，教科书取值高估 1.6–1.9 倍 | 实测中文 **0.609 token/字**、英文 **6.6 字符/token**、固定开销 **236 tokens**；校准后误差 **11.5%–21.5%** |

---

## 二、阶段一 · 大模型对接层（8 个学习点，完成 7 个）

> **本轮新增完成**：学习点 7（异步调用改造 / 虚拟线程）、学习点 2（Token 预算校验）。
> 阶段一验收三条（超时 / 重试 / JSON 校验失败）此前已达成，本轮把两个"同属阶段一但不影响验收口径"的坑补齐了。

### ✅ 已完成

| # | 学习点 | 状态 | 证据 |
|---|---|---|---|
| 1 | Token / 上下文窗口 / Temperature / Max Tokens 概念 | ✅ | 已能读取真实 token 用量（见下） |
| 3 | Spring AI 跑通 Hello World | ✅ | 真实调用 `deepseek-flash` 返回 `[收到]` |
| 4 | **SSE 流式输出** | ✅ | 端点跑通 + 换行转义已实测修 bug；**Nginx `proxy_buffering` 实测延后到正式环境部署时做**（本地无代理环境，不阻塞验收） |
| — | 环境与骨架搭建 | ✅ | 见「基础设施」 |

**实测数据（不是"跑通了"，是"有数字"）**

| 验证项 | 结果 |
|---|---|
| 流式增量事件 | 100+ 个 `event:content`，逐字返回（证明真流式，非攒批） |
| 首字延迟 / 总耗时 | 4.98 秒（含模型思考） |
| Token 用量（单轮） | prompt 243 / completion 494 / **total 737** |
| **对话记忆证据** | 同 `conversationId` 第 2 轮 **promptTokens 从 242 → 299**（+57 即被带入的历史） |
| 记忆正确性 | 第 2 轮正确答出第 1 轮告知的名字 |

**前后端贯通后的复测（真实模型，2026-09-14）**

| 验证项 | 结果 |
|---|---|
| 后端 SSE 契约（POST 通路） | 76 个 `content` 帧、首字 **3706ms**、总耗时 3992ms，正文 154 字 |
| Token 用量 | prompt 250 / completion 249 / **total 499**，model `deepseek-flash` |
| 对话记忆复测 | 同会话第 2 轮 **promptTokens 250 → 338**（+88） |
| 页面 + 真模型全链路 | 浏览器点发送 → 4.19s 内首屏出现增量，正文 681 字，页面显示 `prompt 249 / completion 416 / total 665`，无控制台报错 |
| 前端页面（假后端可复现） | **20 项断言**通过（增量阶梯 16→25→30→…→110 字，证明逐帧渲染）—— 这是当时只测流式那一条路径时的数字，后来脚本扩到 36 个断言 |

**已产出的代码**

| 文件 | 作用 |
|---|---|
| `ai-agent/src/main/java/ai/agent/controller/ChatController.java` | SSE 端点，三种事件：`content` / `done` / `error`；GET + POST 两条通路共用同一份组装逻辑 |
| `ai-agent/src/main/java/ai/agent/chat/dto/ChatRequest.java` | POST 请求体（message 走 body，不进 URL） |
| `ai-agent/src/main/java/ai/agent/chat/service/ChatService.java` | 对话服务**接口**（契约） |
| `ai-agent/src/main/java/ai/agent/chat/service/impl/ChatServiceImpl.java` | 流式+非流式，token 用量提取（实现） |
| `ai-agent/src/main/java/ai/agent/config/ChatClientConfig.java` | ChatClient 装配（记忆 + 日志 advisor） |
| `ai-agent/src/main/java/ai/agent/config/ApiKeyStartupCheck.java` | **启动自检**：API Key 没读到（或仍是占位符原文）就拒绝启动，避免"带着 `${OPENAI_API_KEY}` 去请求然后 401" |
| `web/src/api/sse.ts` | 前端 SSE 帧解析器（fetch + ReadableStream 手切帧） |
| `web/src/hooks/useChatStream.ts` | 流式状态机（rAF 合并增量、停止生成、新会话） |
| `web/mock/sse-server.mjs` | 本地 SSE 假后端：不配 Key 也能验证流式渲染 |
| `ai-agent/src/main/resources/prompts/system-agent.st` | **外置系统提示词**（对应「prompt 不要硬编码」） |
| `ai-agent/src/main/resources/application.properties` | key 环境变量注入、base-url、model、temperature |
| `ai-agent/src/main/java/ai/agent/config/VirtualThreadConfig.java` | **虚拟线程装配**（学习点 7）：容器层 `VirtualThreadExecutor` + 业务层执行器 |
| `ai-agent/src/main/java/ai/agent/chat/async/ChatExecutionService.java` | 三种执行模式（virtual / platform / auto）**共用一份业务代码** |
| `ai-agent/src/main/java/ai/agent/controller/AsyncChatController.java` | `/api/chat/async`（虚拟线程）+ `/sync`（压测对照基线） |
| `ai-agent/src/main/java/ai/agent/diag/ThreadDiagnostics.java` | 线程账目：`isVirtual()` / 平台线程数 / 虚拟线程数 / 并发峰值 |
| `ai-agent/src/main/java/ai/agent/controller/ThreadDiagnosticsController.java` | 举证端点：当前线程 / 统计 / 配置 / 载体复用 / **钉住对照** |
| `ai-agent/src/main/java/ai/agent/token/TokenBudgetGuard.java` | **Token 预算校验**（学习点 2）：放行 / 裁剪 / 拒绝三条路径 |
| `ai-agent/src/main/java/ai/agent/token/impl/CharacterClassTokenEstimator.java` | 按字符类别估算（ASCII / CJK / 其他），系数**实测校准** |
| `ai-agent/src/main/java/ai/agent/controller/TokenCalibrationController.java` | **校准端点**：同一次调用里同时给出估算值与真实 usage |
| `ai-agent/src/main/java/ai/agent/web/ErrorResponseAdvice.java` | 让业务错误文案真正到达客户端（见踩坑库 #26） |
| `web/src/components/ThreadBudgetPanel.tsx` | 界面第三模式「线程与预算」：线程判定 / 账目 / 载体复用 / 钉住对照 / 预算切分 |
| `web/verify-ui.mjs` | 界面自检 **29 项断言**，真/假后端两条路径都跑过（`CHECK_EXPECT_REFLECTION=0` 打真后端） |

> 前端选型与联调说明见 `web/README.md`（React 19 + Vite 8 + TS + antd 6）。

### ⬜ 待完成

| # | 学习点 | 说明 |
|---|---|---|
| 5 | Function Calling 边界 | 唯一未动手的学习点 —— **它是阶段三 Agent 的地基，随阶段三一起做更顺** |

> 学习点 2（Token 预算）与学习点 7（虚拟线程）本轮已完成，详见下面两节。

**结构化输出的实测数据（2026-09-14，真实模型 `deepseek-flash`）**

| 验证项 | 结果 |
|---|---|
| 正常路径 | 1 次调用通过校验，**4755ms**，confidence 0.75，结构完整 |
| 「不确定」协议 | 问"我们公司订单表 order_status 枚举值" → 模型答「我不确定…建议查阅 DDL 注释」，**confidence 0.2**（自评校准合理，没有编造） |
| **回灌重试真的生效** | 把 `min-confidence` 临时设成 0.99（模型不可能满足）→ 第 1 轮 confidence 0.95 被校验拦下 → **第 2 轮改成 0.99 通过**，总耗时 4288ms |
| 回灌能被模型理解 | 模型第 2 轮的 `reasoningContent` 原文：**"The issue: confidence below configured lower bound. I need to raise confidence... Raise confidence to like 0.99."** —— 这条推理链就是"反馈闭环在起作用"的直接证据，比任何断言都有说服力 |
| 轮次成本 | 第 1 轮 prompt 474 / completion 159 / total **633**；第 2 轮（带上前次输出与错误）prompt 845 / completion 127 / total **972**。两轮都命中提示词缓存（cacheRead 256→384） |
| 自动化测试 | 当时 **30 个全绿**（结构化 9 + 控制器重试 13 + 策略 7 + 上下文 1）；**本轮又加了 21 个**（预算守卫 12 + 估算器 9），现在**共 51 个** |
| JSON 截断识破 | 替身注入未闭合 JSON，真实报错：`Unexpected end-of-input: was expecting closing quote for a string value` → 回灌后修好 |

**新增代码（结构化输出）**

| 文件 | 作用 |
|---|---|
| `ai-agent/src/main/java/ai/agent/chat/structured/StructuredAnswer.java` | 目标结构（record）+ JSR-380 约束；`degraded()` 为兜底答案 |
| `ai-agent/src/main/java/ai/agent/chat/structured/StructuredResult.java` | 结果 + **完整 attempts 留痕**（每轮原始输出/错误清单/耗时），让"回灌了几轮才成功"可观测 |
| `ai-agent/src/main/java/ai/agent/chat/structured/StructuredChatService.java` | 契约：全有或全无（与流式对话的失败语义不同，故独立接口） |
| `ai-agent/src/main/java/ai/agent/chat/structured/impl/StructuredChatServiceImpl.java` | 解析 → JSR-380 校验 → 把"字段+原因+原始输出"回灌 → 最多 N 轮 → 兜底 |
| `ai-agent/src/main/java/ai/agent/controller/StructuredChatController.java` | `POST /api/chat/structured`，返回体带 attempts 作为验收证据 |
| `ai-agent/src/main/java/ai/agent/chat/structured/support/MinConfidence*.java` | 置信度下界的**可配**校验器（阈值来自配置，顺带可确定性复现校验失败） |
| `ai-agent/src/test/java/ai/agent/chat/structured/impl/StructuredChatServiceImplTest.java` | 9 个注入测试（截断/缺字段/越界/空数组/兜底/受配置控制） |
| `web/src/api/structured.ts` | 结构化端点调用（一刀切 60s 超时 —— 与流式的三道超时不同，因为没有长连接可切帧） |
| `web/src/hooks/useStructuredChat.ts` | 结构化状态机：一问一答成对留痕、无停止按钮（取消拿不到半成品） |
| `web/src/components/StructuredResultCard.tsx` | 结果卡：答案 + 置信度条 + 关键词 + **回灌过程时间线**（默认展开） |
| `web/mock/sse-server.mjs` | 假后端增加结构化端点，**故意造一个"回灌过一轮"的结果**供 UI 验证 |
| `web/verify-ui.mjs` | 界面自检脚本（Playwright + 系统 Chrome，共 36 个断言、单路径执行 29 项，`pnpm check:ui`）——**真后端与假后端两条路径都已跑通**（打真后端要 `CHECK_EXPECT_REFLECTION=0`） |

**结构化输出的界面对接（2026-09-14 已完成，可点可用）**

结构化输出**不再只能 curl 调**：页面上加了模式切换，「SSE 流式 / 结构化输出」两套视图并列对照
（**后来变成三种**，第三种是后面加的「线程与预算」，见下）。

| 界面元素 | 作用 |
|---|---|
| 模式切换（Segmented） | 同一界面切换不同契约；来回切**不丢记录**（三个 hook 都挂在 App 上） |
| 结果卡头部徽标 | 一眼看出「1 轮通过」还是「2 轮（1 轮不合格 → 回灌修正）」 |
| 置信度条 + 关键词标签 | 显示自评置信度（<0.5 变红）与模型给的关键词 |
| **回灌过程时间线**（默认展开） | 每一轮的**模型原始输出 + 校验问题清单 + 耗时** |
| 「已降级」标记 | 模型几轮都改不对时，明确告诉用户这是兜底内容而非模型答案 |

> **设计要点**：这张卡片的主角不是答案，是**那条链路**。结构化输出真正值得看的东西是
> 「模型第一次错在哪、回灌之后改对没有、最后是不是降级了」—— 只渲染最终答案的话，
> 「一次就成功」和「回灌两轮才救回来」长得一模一样，这个功能的全部价值就被藏起来了。

界面上还显式标注了一句：**置信度是「模型自己报的」，不是系统评估的可信度**，防止被当成质量指标误用。

**界面验证（假后端 29 项断言全过，控制台零错误零警告；该节数字为加入「线程与预算」面板后的复测结果）**

> 说明：`verify-ui.mjs` 里一共 **36 个 `expect(...)`**，其中有 7 个是只在特定路径下才执行的
> （"回灌细节已展开"、"出现已降级标记"等开关由 `CHECK_EXPECT_REFLECTION` 控制），
> 所以**单条路径实际执行 29 项**。写"36 项"是虚报，写"29 项"才是真正跑过的数量 ——
> 台账里的数字一律按实际执行数记。

| 验证对象 | 结果 |
|---|---|
| **真实后端 + 真实模型** | 29 项全过（跳过 7 项 mock 专有断言）；结构化答案 366~390 字符、**1 轮通过**；钉住实验 synchronized **3202ms** vs ReentrantLock **0ms** |
| **假后端**（`pnpm mock`） | 29+7 项全过；含回灌（2 轮）与降级（3 轮 + 已降级标记）两条路径；载体复用固定 16 任务 / 4 载体 |
| 布局体检 | 横向溢出 **0px**、卡片不超视口、原始输出块自动换行 |
| 控制台 | **零错误零警告**（修掉了 antd 6 的 `Timeline.children` 废弃告警） |
| 模式往返 | 切走再切回，原有记录仍在（现在是三种模式互切） |
| **线程与预算面板** | 12 项：virtual 判定落到"改造已生效"、请求线程名、线程账目、载体复用结论+逐条采样、**钉住两组并排可见**、预算切分 |

> 当前模型**看不了截图**，所以自检脚本刻意用**几何断言**代替"肉眼看布局"：
> `documentElement.scrollWidth - clientWidth > 0` 即判定横向溢出。
> 这比"我看了一眼截图，感觉没问题"更可靠，而且能进 CI。

**人工实测（2026-09-14，浏览器 + 真实模型，8/8 正常）**

前面的数据都是脚本（curl / 断言）跑出来的；这一轮是**人在页面上真用了一遍**，属于不同性质的证据 ——
脚本只能证明"接口返回了正确的字节"，证明不了"用户看到的东西是对的"。

| 功能 | 结果 |
|---|---|
| 多轮对话／对话记忆 | ✅ 第 2 轮能正确记得第 1 轮的内容 |
| 流式逐字渲染 | ✅ 逐字出现，不是整段一次性蹦出来 |
| Markdown 与代码高亮 | ✅ 渲染正常 |
| token 用量显示 | ✅ 正常 |
| 「停止生成」按钮 | ✅ 正常 |
| 「新会话」按钮 | ✅ 正常 |
| temperature 滑块 | ✅ 正常 |
| **超时时的「重试本轮」按钮** | ✅ 正常（**这条尤其关键**：它只在超时后才出现，说明超时链路在浏览器里真被触发过，而不只是单测里断言过） |

**超时与重试的实测数据（2026-09-14，真实模型 + 替身）**

| 验证项 | 结果 |
|---|---|
| 正常路径（默认配置） | 90 个 `content` 帧，总耗时 **3389ms**，prompt 242 / completion 198 / **total 440** |
| 记忆复测 | 同会话第 2 轮 **promptTokens 242 → 340**（+98） |
| **首字超时 → 重试 → 报错** | 人为把 `first-token` 设为 1ms：**0 个 content 帧**、1 个 `error` 事件，后端日志确认**恰好 3 次调用**（1 次原始 + 2 次重试），总耗时 628ms |
| 超时报错文案 | `模型超过 1毫秒没有返回任何内容（首字超时），已重试 2 次仍失败，请稍后重试。` |
| **有增量后失败 → 不重试** | 替身验证：吐出"第一段"后永久静默，`error` 文案为「回答中断」，服务只被调用 **1** 次 |
| **重试不污染对话记忆** | 日志确认每次重试前都摘掉孤儿用户消息；第 2 次尝试的 prompt 里 `UserMessage{你好}` 只有 **1** 条（修复前是 2、3 条） |
| idle 超时不再误杀首字等待 | 配 `idle=400ms` 跑长回答：首字约 1.6s（> 400ms）仍正常完成，**175 帧 / 5536ms** |
| 自动化测试 | **21 个全绿**（`ChatControllerRetryTest` 13 + `ChatRetryPolicyTest` 7 + 上下文 1） |

**新增代码**

| 文件 | 作用 |
|---|---|
| `ai-agent/src/main/java/ai/agent/config/ChatCallProperties.java` | `ai.chat.*` 超时/重试配置绑定（`Duration` + 可重试状态码集合） |
| `ai-agent/src/main/java/ai/agent/chat/support/ChatRetryPolicy.java` | 纯函数策略：是否可重试（429/5xx/超时）+ 指数退避含抖动与上限 |
| `ai-agent/src/test/java/ai/agent/controller/ChatControllerRetryTest.java` | 13 个失败路径测试（重试/超时/不重复/不污染记忆/Reactor 陷阱回归） |
| `ai-agent/src/test/java/ai/agent/chat/support/ChatRetryPolicyTest.java` | 7 个策略单测（含"4xx 绝不重试"红线） |
| `web/src/api/sse.ts` | 帧级看门狗（`waitForRead` 与 `read()` 赛跑 —— body 流不响应 abort） |
| `web/src/api/chat.ts` | 三道前端超时（首字节 30s / 空闲 30s / 整体 180s）+ 超时归类 |
| `web/src/hooks/useChatStream.ts` | 错误分类 + `retry()` 一键重试（重发提问，不是让模型接着说） |
| `web/src/components/MessageBubble.tsx` | 超时才给「重试本轮」按钮（4xx/业务错误不给 —— 重试没用会误导） |

> **阶段一验收标准**（原文）：一个流式对话接口，能正确处理超时、重试、JSON 校验失败。
> **当前状态：✅ 三条已全部达成** ——
> 流式对话接口（人工实测 8 项通过）· 超时 ✅ · 重试 ✅ · JSON 校验失败 ✅（结构化输出 + 回灌兜底）
>
> **并且两个"同属阶段一、但不影响验收口径"的坑也已经补齐**：学习点 7（虚拟线程异步改造）与
> 学习点 2（Token 预算校验）—— 见下面两节，两条都有量化数字，不是"我做了"而是"我测出来是这么多"。

### ✅ 学习点 7 · 虚拟线程异步改造（2026-09-14 完成）

**先回答那个"顺带要回答的问题"：流式端点走 Reactor（非阻塞），为什么还要改造非流式端点？**

因为两者的线程模型根本不同，而**区别不在"哪个更先进"，在"等待的时候谁被占着"**：

| | 流式端点 `/api/chat/stream` | 非流式端点 `/api/chat`、`/api/chat/async` |
|---|---|---|
| 模型调用 | `stream()` → WebClient（Netty 事件循环） | `.call()` → **同步阻塞** |
| 等 token 时 | **没有任何线程被占用**（来一个 chunk 才有一个回调） | **全程占着一个线程干等** |
| 并发上限 | 连接数（几百个容器线程也能挂几千条连接） | **= 线程数**（Tomcat 默认 200） |
| 改造必要性 | 不需要 | **必须** |

而且非流式那条路上还有个放大器：`/api/chat/structured` 是"最多 3 轮回灌"的循环，
**一个 HTTP 请求最坏要占住线程 3 次模型调用的时长**（实测第 2 轮 4.3 秒）。
所以同样 200 个线程，它能扛的并发比流式端点低一个数量级。

**改造的方式：不改业务代码，只换"谁来等"**

```java
// 改造前：容器线程从进来到模型吐完，全程被占
public String call(...) { return chatClient.prompt()...call().content(); }

// 改造后：容器线程立刻还回线程池，等待发生在虚拟线程上
public CompletableFuture<String> chat(...) { return executionService.chatAsync(...); }
```

**四件事不是一个开关**（这是本节最容易踩的坑）：

| 层次 | 做法 | 不做的后果 |
|---|---|---|
| ① 容器层 | Tomcat 连接器换 `VirtualThreadExecutor` | 并发上限仍是 `server.tomcat.threads.max`（默认 200） |
| ② 业务层 | `Executors.newVirtualThreadPerTaskExecutor()` | 阻塞点不在请求线程上时（异步/定时/批量）容器层帮不上忙 |
| ③ 超时层 | 第一道超时必须在这次改造**之前**就做好 | 虚拟线程让"阻塞代价"极低 → "忘了设超时"的代价极高 |
| ④ 依赖库层 | 排查 `synchronized` / ThreadLocal | 见下面的钉住实验 |

**量化对比（同一台机器、同一个二进制、同一个 400ms 替身、8 核）**

| 配置 | 并发 | 成功 | 吞吐 | p50 | p99 | JVM 平台线程峰值 | 业务并发峰值 |
|---|---|---|---|---|---|---|---|
| **A · 虚拟线程（改造后）** | 2000 | 2000 | **1192.6 req/s** | 1442ms | **1676ms** | **32** | 321 |
| C · 平台线程（改造前，容器 200 线程） | 2000 | 2000 | 472 req/s | 4154ms | **4236ms** | **223** | **199**（= 容器线程数） |
| A · 虚拟线程 | 400 | 400 | 678 req/s | 576ms | 590ms | 32 | 400 |
| C · 平台线程 | 400 | 400 | 361 req/s | 1103ms | 1107ms | 223 | 199 |
| B · 容器虚拟线程 + 业务固定 8 线程池 | 400 | **16** / 失败 **384** | 16.9 req/s | 947ms | 947ms | 39 | **8**（= 池大小） |

**怎么读这三组数字**（这里容易得出错误结论）：

1. **单请求延迟几乎没变**（400 并发下 1103ms → 576ms 里，替身本身占 400ms，剩下的差是排队）。
   所以正确的说法是：**虚拟线程不加速单个请求，它消除的是"因为线程不够而排队"**。
   2000 并发时 p99 从 **4236ms 降到 1676ms**，降的全是排队时间。
2. **B 组的 384 个失败是最有说服力的一条**：容器层换了虚拟线程、业务层却用固定 8 线程池，
   于是 400 并发里只有 8 个真正在跑，其余全部被拒（`SynchronousQueue` 无缓冲）。
   **它说明"只改一半"等于没改** —— 改造必须落到真正阻塞的那一层。
3. **JVM 平台线程 223 → 32** 才是虚拟线程的本质承诺：并发数上去了，操作系统线程数不跟着涨。
   223 这个数字还说明改造前**光是线程栈就要 223MB**（每线程约 1MB），外加上下文切换开销。

**载体线程复用（`/api/diag/threads/carriers`）**

```
虚拟线程数=16  用到不同载体线程数=5   载体并行度=8
虚拟机线程 #63 → ForkJoinPool-1-worker-6
虚拟机线程 #64 → ForkJoinPool-1-worker-1
...
16 / 5：虚拟线程不是 1:1 映射到操作系统线程的 —— 阻塞时从载体"下车"，就绪时再"上车"
```

> ⚠️ 这个端点第一版是**错的**：我直接存了 `Thread.currentThread().toString()`，
> 而它的格式是 `VirtualThread[#63]/runnable@ForkJoinPool-1-worker-6` ——
> **虚拟线程自己的编号也混在里面**，于是 16 个样本去重得到"16 个不同载体"，
> 而机器只有 8 个并行度，明显自相矛盾。必须切到 `@` 之后那一段才是载体名。修好后是 5–9 个。

**`synchronized` 钉住载体线程（本页最值钱的一条，`/api/diag/threads/pin`）**

| 对照组 | 探针延迟（提交空任务 → 开始执行的**最坏**延迟） |
|---|---|
| 占位任务用 `synchronized` 阻塞 | **6405ms**（holders=16）/ **3202ms**（holders=12）/ **801ms**（holders=8） |
| 占位任务用 `ReentrantLock` 阻塞 | **0 ~ 1ms**（三组都是这个量级） |

> 三组数字是不同 holders 下各测一次的结果，放在一起正好说明了这个实验的性质：
> **占位任务越多，载体被耗得越彻底，新任务排得越久**（801 → 3202 → 6405），
> 而对照组始终是 0~1ms。这也解释了为什么端点的默认 holders 最后定在 12 ——
> 8 个（= 载体数）就已经把延迟从 0 抬到 800ms 以上，对比足够醒目，
> 但 16 那个数字要跑 26 秒，作为界面默认值太慢（见踩坑库 #38）。
> 顺便：这说明实测数字**必须带上参数**才有意义，"6405ms"单独拿出来是不可复现的。

两组实验里占位任务的**数量、时长、持锁行为完全相同**，唯一差别是阻塞方式，所以差异只能归因于 pinning：
JDK 21 下虚拟线程在 `synchronized` 里阻塞时**无法卸载**，会把载体线程一起钉住；载体只有 CPU 核数那么多，
于是十几个 `synchronized` 就能把虚拟线程的并发能力打回平台线程时代（JDK 24 的 JEP 491 才取消这个限制）。

> **工程结论是一句可执行的话**：虚拟线程化之前，先排查存量代码里**包着 IO 的 `synchronized`**，
> 换成 `ReentrantLock`。这类问题不报错、不打日志，只在高并发时表现为"虚拟线程好像没什么用"。
>
> ⚠️ 这个实验也失败过一次，失败原因比结论更值得记：第一版是"占位任务全部启动 → 睡一会儿 → 提交探针"，
> 结果两组都是 **0ms**，看不出任何差异。因为 `CompletableFuture.runAsync` 在**提交时**就选好了载体，
> 只有提交那一刻载体恰好全被占住才会记到延迟 —— 而那个状态窗口极窄，"先启动再观察"根本抓不住。
> 改成**分批投放 + 每批立即连续探针取最大值**拿到了 6405ms。
> **教训：测量并发状态时，"先启动再观察"很容易滑过去，必须把被测状态持续住，或者直接在结果上做对照。**

**新增代码**

| 文件 | 作用 |
|---|---|
| `ai-agent/src/main/java/ai/agent/config/VirtualThreadConfig.java` | 两端装配：容器层 `VirtualThreadExecutor` + 业务层 `newVirtualThreadPerTaskExecutor` |
| `ai-agent/src/main/java/ai/agent/chat/async/ChatExecutionService.java` | 三种执行模式（virtual / platform / auto）共用**一份业务代码**，只换"谁来等" |
| `ai-agent/src/main/java/ai/agent/controller/AsyncChatController.java` | `/api/chat/async`（CompletableFuture）+ `/sync`（对照基线） |
| `ai-agent/src/main/java/ai/agent/diag/ThreadDiagnostics.java` | 线程账目：`isVirtual()` / 平台线程数 / 虚拟线程数 / 并发峰值 |
| `ai-agent/src/main/java/ai/agent/controller/ThreadDiagnosticsController.java` | 举证端点：当前线程 / 统计 / 配置 / 载体复用 / **钉住对照** |
| `web/src/components/ThreadBudgetPanel.tsx` | 界面第三模式「线程与预算」：线程判定 hero / 账目 / 载体复用 / 钉住对照 / 预算切分 |

> **关于压测与校准脚本**：那两组实验当时是脚本跑出来的（一个 PowerShell 写的并发压测器、
> 一个长度扫描脚本），**脚本属于一次性工具，已经删掉** —— 它们硬编码了本机的 JDK / Maven 路径，
> 换台机器跑不了，留着反而是"看起来能复现、实际不能"的负担。
> 上表这些**端点才是留下来的能力**：`/api/diag/threads/*` 负责出线程模型的证据，
> `/api/diag/token/calibrate` 负责出估算误差的证据，两者都是随应用长期存在的，
> 上面的数字随时可以用它们重新量一遍。

### ✅ 学习点 2 · Token 预算校验（2026-09-14 完成）

**要回答的问题只有一个**：这次请求发出去，会不会超窗口？

```
contextWindow × (1 - safetyMargin)  ≥  estimatedInputTokens + maxOutputTokens
└────────── 可用预算 ──────────┘      └────────── 这次要花的额度 ──────────┘
```

**为什么必须在发请求之前算**：失败成本极不对称 ——
自己算是一次本地计算（微秒级、0 成本）；等模型报错则**先花一次往返的钱和几秒**。
更糟的是有些供应商**不报错而是静默截断输入**：请求成功、回答也回来了，
只是模型**根本没看到**前半段上下文，表现是"越聊越傻"，而排查方向会跑到提示词和模型能力上去。

**三条不同的处理路径**（这是本模块的全部业务逻辑）

| 路径 | 触发条件 | 动作 |
|---|---|---|
| ① 放行 | 估算值 ≤ 预算 | 什么都不做（绝大多数请求走这里，零成本） |
| ② 裁剪后放行 | 历史太长但本次提问不大 | **丢掉最旧的几轮对话**，保住"这次能答"；至少保留 2 条（不裁到 0 = 不让模型失忆） |
| ③ 拒绝 | 连"系统提示词 + 本次提问"都放不下 | 返回 400 + **账目数字与建议**（裁剪救不了：该裁的是提问本身，但那是用户的意图） |

**系数是校准出来的，不是拍脑袋定的（这一步最值钱）**

方法：对**同一段文本做长度扫描**（重复 1/5/20/50/200 次），用真实 usage 拟合出「边际密度」和「固定开销」。
单点样本做不到这件事 —— 短文本里固定开销占比太大，密度根本量不出来。

| 扫描对象 | 字符数 | 真实 promptTokens | 拟合结果 |
|---|---|---|---|
| 中文 ×1 / ×5 / ×20 / ×50 / ×200 | 23 / 115 / 460 / 1150 / 4600 | 250 / 306 / 516 / 936 / 3036 | 边际 **0.609 token/字** |
| 英文 ×1 / ×5 / ×20 / ×50 / ×200 | 99 / 495 / 1980 / 4950 / 19800 | 252 / 312 / 537 / 987 / 3237 | 边际 **0.152 token/字符（= 6.6 字符/token）** |
| 两种扫描反推的固定开销 | — | 236 / 237 tokens | **系统提示词 + 消息包装 ≈ 236 tokens** |

**校准前后对比（同样四段文本）**

| 样本 | 校准前估算 | 校准后估算 | 真实 | 校准前误差 | 校准后误差 |
|---|---|---|---|---|---|
| 纯中文 ×20（460 字） | 779 | 584 | 516 | +51.0% | **+13.2%** |
| 纯中文 ×200（4600 字） | 4919 | 3689 | 3036 | +62.0% | **+21.5%** |
| 纯英文 ×20（1980 字符） | 885 | 599 | 537 | +64.8% | **+11.5%** |
| 纯英文 ×200（19800 字符） | 5977 | 3839 | 3237 | +84.6% | **+18.6%** |

> **第一版系数是"教科书式"的，实测高估 1.6–1.9 倍**：
> 我按常识填了「英文 3.5 字符/token、中文 1 字 1 token」，看着完全合理，
> 实测却是英文 **6.6 字符/token**、中文 **0.609 token/字**。
> 这类偏差**不会让任何功能报错**，只会让系统一直做多余的裁剪 ——
> 所以我把实测密度写成了回归测试（`TokenEstimatorTest#estimateMatchesMeasuredDensity`），
> 下次有人想"调大一点更保险"时会先看到这组真实数字。
>
> **收敛后的误差全部是正数（偏保守）**，这是刻意的：低估 = 超窗没拦住（要花钱、可能被静默截断），
> 高估 = 历史多裁一点，代价不对称。系统提示词单独看也很准：估算 232 vs 实测 236（偏差 1.7%）。

**三条路径的实测验证**

| 验证项 | 结果 |
|---|---|
| 拒绝路径（三个端点） | `/api/chat/async`、`/api/chat/structured`、`/api/chat/stream` **全部 400**，且**不产生任何模型调用** |
| 拒绝文案 | 「上下文预算超限：本次提问约 2592 tokens（含系统提示词 232），可用预算 1075…建议：把问题拆小」+ 结构化字段 `neededTokens/allowedTokens/overBy` |
| 流式端点的时机 | 400 在**流开始之前**返回 —— 不能等流中途报错（SSE 响应头一旦发出就收不回来，用户已看到半句话） |
| 裁剪路径（真实模型，16 轮） | 账目从 260 涨到 2177 → **超过 2150 后裁掉最旧 2 条、保留 22 条** → 之后稳定在 2040/2150 |
| 预算账目可观测 | 每轮一行日志「需要=xx 可用=xx 历史条数=xx 系统提示词=232」，能看出"对话从第几轮开始变贵" |
| 自动化测试 | 51 个全绿（其中 Token 相关 21 个：预算守卫 12 + 估算器 9） |

**新增代码**

| 文件 | 作用 |
|---|---|
| `ai-agent/src/main/java/ai/agent/token/TokenEstimator.java` | 契约（可替换成真分词器而不动业务代码） |
| `ai-agent/src/main/java/ai/agent/token/impl/CharacterClassTokenEstimator.java` | 按字符类别（ASCII / CJK / 其他）估算，系数可配 |
| `ai-agent/src/main/java/ai/agent/token/TokenBudgetGuard.java` | 三条路径的实现：放行 / 裁剪（走仓储 `saveAll` 整段替换）/ 拒绝 |
| `ai-agent/src/main/java/ai/agent/token/TokenBudgetExceededException.java` | 400 + 账目数字 |
| `ai-agent/src/main/java/ai/agent/controller/TokenCalibrationController.java` | **校准端点**：同一次调用里同时返回估算值与真实 usage |
| `ai-agent/src/main/java/ai/agent/web/ErrorResponseAdvice.java` | 把 `ResponseStatusException` 的 reason 送回客户端（见踩坑库 #26） |
| `ai-agent/src/test/java/ai/agent/token/TokenBudgetGuardTest.java` | 12 个边界测试（窗口调小以让边界可达） |
| `ai-agent/src/test/java/ai/agent/token/TokenEstimatorTest.java` | 9 个估算器测试（含实测密度的回归钉子） |

---

## 三、基础设施（已完成，踩坑记录）

这些不是学习点，但**每个都真实阻塞过进度**，记录下来避免重复踩。

| 项 | 状态 | 关键结论 |
|---|---|---|
| JDK 21 安装 | ✅ | 本机原为 JDK 17；已装 Microsoft OpenJDK **21.0.12.1** 到 `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`。**未改全局 JAVA_HOME** |
| 项目生成 | ✅ | groupId/包名 `ai.agent`，artifactId `ai-agent` |
| Boot 版本修正 | ✅ | Initializr 生成 `4.1.1.RELEASE` **在 Maven Central 不存在**，手工改为 `4.1.1` |
| **Servlet 栈缺失** | ✅ | Initializr **没给 `spring-boot-starter-web`**（AI starter 只带 WebClient）。缺它 `@RestController` 会 404，已补 |
| Maven wrapper | ⚠️ 不可用 | `mvnw.cmd` 下载发行版报 TLS 错误；改用本机 Maven 3.9.11 |
| API Key 安全 | ✅ | 已从明文改回 `${OPENAI_API_KEY}`；**建议轮换该 key** |
| **API Key 读取** | ✅ | 变量设在**系统级**（`sk-****`，长度 35）。但 Windows 的环境块是**进程启动时从父进程继承的快照** —— 设完必须**重开终端 / 重启 IDEA** 才读得到，否则表现是"明明配了却 401"。`run.cmd` 已改为**缺 Key 直接拒绝启动**（fail fast），且只报告"是否读到"不打印内容 |
| 启动脚本 | ✅ | `ai-agent/run.cmd`（自动切 JDK 21 + Maven） |
| 前端工程 | ✅ | `web/`：React 19 + Vite 8 + TS + antd 6，pnpm 管理；开发期 `/api` 由 Vite 代理到 8080（**不需要配 CORS**） |

**环境基线（可复现）**

| 组件 | 版本 |
|---|---|
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1（BOM 统一管理，14 个模块） |
| Spring Framework | 7.0.9 |
| Java | 21.0.12.1（Microsoft OpenJDK） |
| Tomcat | 11.0.24（内嵌） |
| 模型 | `deepseek-flash` @ `https://api.deepseek.com` |
| Maven | 3.9.11 |

---

## 四、踩坑知识库（编码中发现的真实问题）

这一节是**面试素材**——都是"只有真做过才知道"的东西。

| # | 问题 | 根因 | 结论 |
|---|---|---|---|
| 1 | `options(...)` 编译失败 | 它接收 **`ChatOptions.Builder`**，不是构建好的 `ChatOptions` | 传 `.builder().temperature(t)`，**不要 `.build()`** |
| 2 | 启动报 `At least one credential source must be specified` | 没设 API Key 时 OpenAI starter 的 `OpenAiAudioSpeechModel` 直接建不出来 | 必须配 `${OPENAI_API_KEY}` |
| 3 | 配了 `logging.level` 但 advisor 无日志 | **光配级别不够，必须显式把 `SimpleLoggerAdvisor` 加入 advisor 链** | 官方文档要求两步 |
| 4 | `@RestController` 会 404 | 从 Initializr 生成 Spring AI 项目时**不含 Servlet 容器** | 必须补 `spring-boot-starter-web` |
| 5 | 记忆 advisor 抛 `IllegalArgumentException` | **2.0 起 `ChatMemory.CONVERSATION_ID` 每次调用必传，无默认值** | `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))` |
| 6 | `base-url` 在 `OpenAiChatOptions` 里读出 `null` | 它挂在 `OpenAiApi` 层面，非请求级 options | **不影响功能**，别误判为配置失效 |
| 7 | **`.cmd` 脚本里的中文会把脚本解析炸掉** | `cmd.exe` 按**控制台 OEM 代码页（本机 GBK）**解析 `.cmd`，UTF-8 中文被错读后，某些字节会变成 `&` `\|` `(` 等**cmd 语法字符**，直接打乱整个脚本 | `.cmd` 一律**只写 ASCII**，中文说明另放 md |
| 8 | **`exit /b 1` 在嵌套 if 块里丢了退出码** | 实测：`if ( if ( ... exit /b 1 ) )` 这种嵌套块，错误信息**打印了**但退出码仍是 **0**（fail-fast 白做）；改成**扁平 `goto` 标签**后退出码正常为 1 | 批处理宁可用 `goto` 拉平，别嵌套括号块；写完必须**实测退出码**，别只看输出 |
| 9 | **环境变量"配了却读不到"，且报错极具误导性** | `spring.ai.openai.api-key=${OPENAI_API_KEY}` 在变量缺失时**不报错**：Boot 把占位符**原文**当值绑进去，请求真的带着字符串 `${OPENAI_API_KEY}` 发出去。报错长这样：`401: Authentication Fails, Your api key: ****KEY} is invalid` —— 遮码后的 `KEY}` 就是占位符的结尾，**不是"key 填错了"，是进程没读到环境变量**。Windows 的环境块是进程启动时从父进程继承的**快照** | 改完环境变量必须**重开终端 / 重启 IDEA**（IDEA 尤其容易忘）。已加 `ApiKeyStartupCheck` 启动自检：没读到就**拒绝启动**并打印排查步骤，不再浪费一次 401 往返 |
| 10 | Spring Boot 4 容器里没有 `com.fasterxml...ObjectMapper` bean | Boot 4 自动配置的是 **Jackson 3（`tools.jackson`）**；Jackson 2 只是被 Spring AI 传递依赖带进来 | 不要按老教程注入 `ObjectMapper`；要 JSON 就把 `Map`/对象交给 SSE writer 自己编码 |
| 11 | **`Flux.timeout(...)` 对"一个信号都没有"的流不生效** | `timeout` 只在**收到第一个信号之后**才开始计时。所以把超时放进 `switchOnFirst` 的分支里是无效的 —— 分支在源发出首个信号前根本不会执行，而"模型挂起"恰恰就是"没有首个信号" | 超时必须**套在源上**。实测：配 100ms，流却挂到测试框架的 5 秒上限。回归测试 `#timeoutMustWrapSourceNotSwitchOnFirst` |
| 12 | **`switchOnFirst(signal, flux)` 的 `flux` 参数已经包含首元素** | 官方签名看不出这点，直觉以为是"剩余元素" | 分支里**不要**再 `Flux.concat(Flux.just(signal.get()), flux)` —— 会把第一个增量发两遍（实测输出"第一段第一段"）。回归测试 `#switchOnFirstSecondArgumentAlreadyIncludesFirstElement` |
| 13 | **`ChatMemory.add()` 是追加合并，不是替换** | 它内部先 `findByConversationId()` 读旧消息、与新消息合并后再 `saveAll` 存回 | 要"整段替换/删掉某条"必须用 `ChatMemoryRepository.saveAll(...)`。实测拿裁剪后的列表去调 `add()`：清理前 1 条、清理后 **2 条**，越清越多 |
| 14 | **重试会把同一句用户消息写进对话记忆多次** | `MessageChatMemoryAdvisor` 在**发起调用之前**就写用户消息（`before()` 阶段），而回答只在**流正常完成**时才写（`ChatClientMessageAggregator` 只在 onComplete 回调）。所以"首个增量迟迟不来"这种超时会在记忆里留下孤儿用户消息，重试再写一条 | 每次重试前摘掉本次提问的孤儿消息。**时机必须是"每次重试前"，不能是"发现重复时才清"** —— 第一次失败后只有 1 条、判不出重复，等第 2 次尝试再写就来不及了。实测修复后第 2、3 次尝试的 prompt 里 `UserMessage{你好}` 都只有 1 条 |
| 15 | **`WebClient` 不可用 → 改用 `RestClientResponseException` 判状态码** | Spring AI 2.0.1 的 classpath 里**没有** `org.springframework.ai.retry.TransientAiException`（旧教程里的瞬时异常类型已消失），但 `spring-web` 在，HTTP 非 2xx 会抛 `RestClientResponseException` 子类 | 判"是否可重试"直接读 `RestClientResponseException.getStatusCode()`，别 import 那个不存在的类 |
| 16 | **Reactor 的重试/超时包装类 import 不到** | `Exceptions$RetryExhaustedException` 与 `Exceptions$ReactiveException` 都是**包级私有**（`final class`，无 public），业务代码写 `instanceof` 会编译失败："不是公共的，无法从外部程序包中对其进行访问" | 用 Reactor 给的 public 入口：`Exceptions.isRetryExhausted(e)` 与 `Exceptions.unwrap(e)` |
| 17 | **`.cmd` 脚本吞掉额外命令行参数** | `run.cmd` 内部是 `call mvn ... spring-boot:run`，**没有把 `%*` 透传**给 Maven。于是 `run.cmd --ai.chat.timeout.idle=1ms` 会静默什么都不做（参数被丢掉，Maven 照常以默认配置启动） | 要覆盖配置就用 `mvn spring-boot:run "-Dspring-boot.run.arguments=--ai.chat.timeout.idle=1ms"`。**排查"配置改了没生效"时先确认参数真的传到了** |
| 18 | **Dev server 的代理也有超时** | Vite `server.proxy` 的默认超时远短于一次长回答，SSE 是长连接，到点会被直接掐断，现象是"回答说到一半就断了"，而且**只在开发环境复现** | 在 `vite.config.ts` 里显式写 `proxyTimeout: 0, timeout: 0`（已加）。这与 Nginx 的 `proxy_buffering`/`proxy_read_timeout` 是同一类坑，只是"本地正常、线上不正常"的镜像版本 |
| 19 | **DeepSeek 不支持 `response_format=json_schema`（strict 模式）** | 直接请求会返回 `{"error":{"message":"This response_format type is unavailable now"}}`。**只有 `json_object` 可用**，而它唯一的保证是"输出是合法 JSON"——**不保证字段齐全、类型正确、取值合理** | 所以"让模型输出结构化数据"这件事，**正确性只能靠应用侧兜**：JSR-380 校验 + 把具体错误回灌让模型自己改。这也是本项目 `structured` 模块存在的理由。别以为用了"JSON 模式"就万事大吉 |
| 20 | **包装类型 vs 基本类型：`double` 会让"字段缺失"变成 0.0** | 目标结构里写 `double confidence` 时，模型漏掉该字段 → 反序列化成 `0.0`（一个看起来完全合法的值）→ 校验器再也发现不了"模型根本没给" | DTO / 结构化输出的字段用**包装类型**（`Double`）+ `@NotNull`。这是"解析成功 ≠ 数据可用"最典型的例子 |
| 21 | **自定义校验器一旦带依赖，就不能再用默认工厂** | `Validation.buildDefaultValidatorFactory()` 只会调无参构造器，而需要从配置读阈值的校验器必须构造器注入 → 报"找不到构造器"。Spring 环境没问题（Boot 的 `SpringConstraintValidatorFactory` 负责注入），但**单元测试里会直接炸** | 测试里自己提供 `ConstraintValidatorFactory` 手工装配（见 `StructuredChatServiceImplTest#buildValidator`）。记住：**校验器带依赖 ⇒ 单测必须自带工厂** |
| 22 | **Vite dev server 会被 Windows 文件锁整个搞挂** | 编辑工具/编辑器落盘是"写临时文件 → 原子重命名"，那个**瞬间出现又消失**的 `.xxx.tmpdir` 目录会让 chokidar 抛 `EBUSY: resource busy or locked`，而 **Vite 没有捕获它 —— 进程直接退出**（实测连挂两次） | 在 `vite.config.ts` 写 `watch.ignored: ['**/.*.tmpdir/**', '**/*.tmp']`。现象是"**改一个文件，前端服务就没了**"，极易被误判成"我刚写的代码把它写崩了"——记住这个特征，能省半小时 |
| 23 | **antd 6 有一批 props 改名，旧名只报警告不报错** | 已踩到两个：`Alert` 的 `message` → `title`，`Timeline` item 的 `children` → `content`。**不报错、只是 console warning**，所以不看控制台就永远发现不了 | 给界面加了"控制台必须干净"的断言（`pnpm check:ui`）。查证方式是读安装包的 `.d.ts`（`@deprecated` 注释直接写在类型上），比翻文档快 |
| 24 | **纯文本里写 markdown 星号会原样显示** | 我在置信度说明里写了 `**模型自己报的**`，但那处渲染的是**纯文本**不是 markdown → 页面上真的出现了两个星号 | 纯文本要用 JSX 的 `<b>`。同时加了断言钉住（"无字面量星号" + "用了真正的粗体标签"）—— 这类"以为生效了其实没生效"的样式问题，只有断言能防 |
| 25 | **Playwright 自带的浏览器内核版本可能对不上** | 本机 `ms-playwright` 里是 chromium build 1234，而当前 playwright 版本要 1243 → `launch()` 直接抛 `Executable doesn't exist` | 用系统已装的 Chrome：`chromium.launch({ channel: 'chrome' })`。省掉"为了截一张图下载 100MB 内核" |
| 26 | **自己构造的 400 错误文案一个字都到不了客户端** | 预算拒绝时抛 `ResponseStatusException`（带一段"差多少、该怎么办"的人话），客户端只收到 `{"status":400,"error":"Bad Request"}`。**第一反应是去开 `server.error.include-message=always` —— 实测没用**。真因：`ResponseStatusException` 由 `ResponseStatusExceptionResolver` 处理，它**只设状态码**、根本不经过 Boot 的 `BasicErrorController`（`/error`）；而那个开关管的是 `/error` 那条路径的输出，两者不在一条链上 | 自己加 `@RestControllerAdvice` 接住它（见 `ai.agent.web.ErrorResponseAdvice`）。**通用教训：异常对象里有 message ≠ 客户端能看到 message，中间隔着序列化配置；单测断言前者会一路绿灯，而用户看到的是后者 —— 错误信息必须端到端验证** |
| 27 | **Spring 看到两个构造器就去找无参构造器** | `TokenBudgetGuard` 原本是"公开构造器 + 一个包私有重载（供测试注入提示词）"，启动直接失败：`Failed to instantiate [...]: No default constructor found` —— 明明有两个带参构造器，却说找不到无参的。因为 Spring 只在**恰有一个**构造器（或有 `@Autowired` 标注的那个）时才自动装配，看到两个都没标注就退回"找无参构造器" | **只留一个公开构造器**。接着踩了第二层：把参数写成 `Resource` → `No qualifying bean of type 'Resource' available`；改成具体类型 `ClassPathResource` → 报错一模一样。**容器不会"看到 Resource 类型就自己 new 一个"**，必须给它一个来源：加 `@Value` 让属性占位符解析器介入（本项目的做法），或注入 `ResourceLoader` 自己解析 |
| 28 | **`synchronized` 会钉住虚拟线程的载体线程** | JDK 21 下虚拟线程在 `synchronized` 块里阻塞时**无法卸载**，把载体线程一起钉住；载体只有 CPU 核数那么多（本机 8），于是十几个 `synchronized` 就能把并发能力打回平台线程时代。实测：占位任务用 `synchronized` 阻塞时，新任务的排队延迟 **6405ms**；换成 `ReentrantLock`（park 型阻塞，可卸载）后 **0ms** —— 两组实验除阻塞方式外完全相同 | 虚拟线程化之前先排查**包着 IO 的 `synchronized`**，换成 `ReentrantLock`。这类问题不报错、不打日志，只在高并发时表现为"虚拟线程好像没什么用"。JDK 24 的 JEP 491 才取消这个限制 |
| 29 | **虚拟线程改造"只改一半"等于没改** | 压测 C 组（容器虚拟线程 + 业务固定 8 线程池、400 并发）：成功 **16**、失败 **384**、业务并发峰值 **8**（= 池大小）。容器层换了虚拟线程，业务层还在用固定线程池，于是请求全卡在池上 | 改造必须落到**真正阻塞的那一层**。容器的线程池大小与应用自建线程池是**两个独立的天花板**，换了一个不等于换完 |
| 30 | **PowerShell 的 `GetNewClosure()` 会深拷贝捕获的变量** | 压测脚本原本在循环里取一个时间戳、在 `ContinueWith` 回调里相减算延迟。结果**所有请求的延迟都算成 0ms** —— 因为 `GetNewClosure()` 把捕获到的变量**深拷贝一份快照**，而 Stopwatch 在 PowerShell 里按值语义传递，回调里拿到的是"创建闭包那一刻"的秒表副本，它的 `Elapsed` 永远是 0。实测：一个必然 >100ms 的延迟算出 0。（另有一层更浅的坑：变量名叫 `$started` 会撞 PowerShell 自动变量，算术静默出 0） | **有状态的计时/累加必须交给类型系统，不能交给闭包** —— 把压测器整体用 C#（`Add-Type`）重写。**看到"数字漂亮得不真实"时先怀疑变量捕获和命名冲突** |
| 31 | **压测器自己成了瓶颈：`Task.Run` 只有 8 个核心线程** | 压测脚本用 `Task.Run(() => client.GetStringAsync(...).Result)` 造并发，结果 400 并发实际只有 8 个真并发（.NET 线程池核心数 = CPU 核数），任务在池里排队 —— 脚本跑了 **300 秒没跑完**，报的是"客户端超时"，看着像服务端挂了 | 用**真异步**：`GetStringAsync` 直接发起、不包 `Task.Run`（等待期间不占线程）。**压测客户端必须比被测系统更不容易成为瓶颈，否则测出来的是客户端的上限** |
| 32 | **静默失败的样本会被当成数据** | 校准脚本里 50/100 次重复那两行返回 **400**（URL 太长，超出 Tomcat 请求头上限），而脚本的 try/catch 把它吞了、变量里留着上一轮的结果 —— 表格上出现两行**连估算值都完全一样**的数据，看起来像"文本变长了 token 却没变"这种重大发现 | **测量脚本里，失败必须让它响**。一个静默失败的样本比少一个样本危险得多（它会被当成证据）。修法：校准加 POST 端点让文本走 body；脚本里宁可让整条中断。顺便：**GET 端点传长文本是不可靠的** |
| 33 | **调试端点的"观察方式"本身可能测不到要测的东西** | 钉住实验第一版：占位任务全部启动 → 睡一会儿 → 提交探针量延迟。结果两组都是 **0ms**。因为 `CompletableFuture.runAsync` 在**提交那一刻**就选好了载体线程，只有提交时载体恰好全被占住才会记到延迟 —— 那个状态窗口极窄，"先启动再观察"根本抓不住 | 改成**分批投放 + 每批立即连续探针取最大值**，立刻测出 6405ms vs 0ms。**教训：测量并发状态时，"先启动再观察"很容易滑过去，必须把被测状态持续住，或者直接在结果上做对照** |
| 34 | **诊断统计量把"身份"混进了"分类"** | `/carriers` 第一版直接存 `Thread.currentThread().toString()`，而它的格式是 `VirtualThread[#63]/runnable@ForkJoinPool-1-worker-6` —— **虚拟线程自己的编号也在里面**。16 个样本去重得到"16 个不同载体"，而机器只有 8 个并行度，数字自相矛盾才露的馅 | 取载体名必须切到 `@` 之后那一段。**统计"用了几个不同的 X"时，先确认被统计的字符串里没有混进每次都会变的部分** |
| 35 | **默认的"条数窗口"会抢先挡住新的"长度限制"** | 对话记忆默认 `maxMessages=20`（按**条数**），Token 预算是按**长度** —— 量纲不同，谁先触发取决于消息多长。实测：第 11 轮时历史 20 条、需要 2058 tokens，而预算是 2150，**被条数窗口抢先挡住了，Token 预算的裁剪逻辑一次都没跑到** | 想验证新的限制，必须先把旧限制放宽到不再抢先（已把 `ai.chat.memory.max-messages` 做成配置，验证时调到 80）。**看到"有个保护在生效"，不代表"你新加的那个保护在生效"** —— 这是最容易得出"我做的校验没用"这种错误结论的地方 |
| 36 | **测试不该依赖系数的具体取值** | 预算裁剪的测试原本把窗口写死成 700，靠"历史的绝对长度够大"逼出裁剪。估算系数一校准（中文密度 1.0 → 0.75），历史变"便宜"了，同一份数据只裁到 5 条就装得下，断言"必须剩 2 条"直接失败 | 改成**从预算公式反推**：先算出可用额度，再按额度生成"必然装不下"的历史。**校准会动到的常量，不要出现在测试的期望值里** —— 那次失败不是 bug，是测试写错了地方 |
| 37 | **`ResponseStatusException` 的 reason 只进日志不进响应体**（与 #26 同一族的另一半） | 排查时先看的是应用日志 —— 但守卫**自己没打日志**，所以第一现场什么都没有。加了 WARN 之后："预算拒绝（提问本身超限）需要=2827（系统 232 + 提问 2592）可用=1075"，一眼看出钱花在哪 | **静默生效的逻辑必须自己留痕迹**：预算校验拦下请求时，用户只看到 400，日志里要是也没有一行，那"这个请求为什么失败"就无解了 |
| 38 | **前端超时比后端固有耗时还短 ⇒ 正常的实验必然报超时** | 钉住对照实验后端要跑 **19~26 秒**（synchronized 那组要一批批把 12~16 个载体线程耗完，耗时随占位任务数线性增长），而前端给所有诊断端点统一套了 **20 秒**超时 → 真后端上永远报「超过 20 秒仍未返回，已中断」。**实验完全正常，是等待时间配错了** | 给这个端点单独一个 120 秒的超时。**关键教训：假后端掩盖了它** —— 假后端是瞬时返回固定 JSON，20 秒绰绰有余、自检全绿。所以**"契约一致"不等于"耗时一致"，而超时是耗时问题**；假后端能验证契约，验证不了超时。另外：默认参数也从 holders=16 调到 12（实测 16→25.9s / 12→19.2s / 8→13.3s），默认值要照顾交互体验 |
| 39 | **界面自检里不能用固定 sleep 等一个"本来就慢"的实验** | 上一条修好之后，自检脚本里还是 `waitForTimeout(3000)` 等钉住实验的结果 —— 真后端要 19 秒，于是断言失败，而失败表现是"界面没渲染"，看起来像前端 bug | 改成 `locator.waitFor({ state: 'visible', timeout })` 等结果元素真的出现：假后端（瞬时）和真后端（十几秒）都能成立。**"等一个固定时长"在慢操作上一定会有一个环境是错的** |

---

## 五、下一步计划（下次从这里开始）

> 触发更新：**2026-09-14 虚拟线程改造 + Token 预算校验已完成，阶段一 8 个学习点完成 7 个**。
> **下一步就是进阶段二（RAG）** —— 阶段一唯一遗留的 Function Calling 边界与阶段三一起做更顺。

### ✅ 本轮已清掉的两个"头号坑"

**1. 虚拟线程异步改造（学习点 7）—— 已完成**

- [x] 容器层 + 业务层**两端**都换成虚拟线程（只改一半等于没改，实测见踩坑库 #29）
- [x] **产出量化对比**：2000 并发吞吐 **472 → 1192.6 req/s**、JVM 平台线程 **223 → 32**、p99 **4236ms → 1676ms**
- [x] 回答"流式走 Reactor 为什么还要改非流式"：因为**等 token 时一个占线程一个不占**（见本节表格）
- [x] 顺带实测出更值钱的一条：`synchronized` **钉住载体线程**（探针延迟 6405ms vs 0ms）
- [ ] 遗留（不阻塞）：`/api/chat/structured` **入口**接上了预算校验，但它的执行仍是同步调用
      （结构化输出是"全有或全无"，边流边解析没意义）—— 若将来要扛更高并发，
      可以把它也包进 `ChatExecutionService` 的异步执行（改造点已经现成）

**2. Token 预算校验（学习点 2）—— 已完成**

- [x] `window ≥ input + max_output` 且预留 **15% 安全边际**（资料给的区间是 10%–20%）
- [x] 超限**先裁最旧历史、再拒绝**，不等模型报错（等报错要多花一次往返的钱）
- [x] 系数用**真实 usage 标定**：中文 0.609 token/字、英文 6.6 字符/token、固定开销 236 tokens；
      校准后误差 **11.5%–21.5%** 且始终偏保守
- [x] 三条路径都实测过：放行 / 裁剪（真实模型 16 轮：2177 > 2150 时裁掉最旧 2 条）/ 拒绝（三个端点 400）

### 🥈 阶段一的加分项（都不阻塞进阶段二）

- **Token 成本计算** —— 数据已经齐了：单轮流式 440–1092 tokens；结构化输出第 1 轮 633、回灌轮 972
  （其中 256→384 命中缓存，缓存价更低）。折成金额就是"每轮对话成本"
- **Prompt 模板变量化** —— `system-agent.st` 仍是静态的，加 `.param()` 演示模板渲染
- **Function Calling 边界**（学习点 5）—— 阶段一唯一未动手的点，**与阶段三一起做**

### 🥇 下一步：进入阶段二（RAG）

- 文档解析 → 语义分块 + Overlap（**别按固定字数硬切**）→ Embedding → 向量库（PG+pgvector 起步）
- 面向 Spring AI `VectorStore` 接口编程，不绑死具体向量库
- **建 30–50 条评测集** —— 阶段二的硬性产出
- ⚠️ **RAG 会显著放大 Token 预算问题**：检索回来的片段是"直接塞进 prompt 的"，
  一批文档片段就可能几千 token，且**用户看不见这部分开销**。
  阶段一做的 `TokenBudgetGuard` 正好是为这个场景准备的 —— 到时候要把"检索预算"也纳入账目。

### 已移除：SSE 的 Nginx `proxy_buffering` 实测 → 延后到上正式环境时实施

**决策（2026-09-14）**：本地没有代理环境，硬搭一台 Nginx 得到的结论与真实生产链路不等价，**性价比低**。

- **不阻塞阶段一验收**：阶段一要求的是「能正确处理超时、重试、JSON 校验失败」，不含代理层行为。
- **上线时的动作清单**（到正式环境再做，别忘）：`proxy_buffering off;` + `proxy_cache off;` + `X-Accel-Buffering: no` 响应头，配置片段见 `web/README.md`；**先故意不关，看一次"假流式"现象**（卡一下然后整段吐出），再关掉对比 —— 那一眼就是面试素材。
- ⚠️ **虚拟线程改造后又多了一条上线注意事项**：并发上限从"200 个线程"松开之后，
  真正会被打挂的下游变成了**模型服务**与**数据库连接池**。
  必须补一层按下游容量定的限流（信号量 / `RateLimiter` / 网关限流），
  否则风险只是从"我们自己排队"转移成了"上游被打挂"—— 后者更难排查、影响面更大。

### 🔧 顺手可做（低优先，但都是真问题）

- **前端产物 1101KB 未分包**（构建警告已提示）：可做动态 `import()` 拆包。接上「线程与预算」面板后从 1072KB 涨到这里，这条更该做了
- **界面自检脚本没进 CI**：`pnpm check:ui` 现在是手动跑的（29 项断言，返回非 0 退出码），可以接进提交前检查或 CI
- **对话记忆是单机内存实现**：`InMemoryChatMemoryRepository` 重启即丢；多实例部署时同一 conversationId 会落到不同实例
- **API Key 轮换**：该 key 曾以明文出现过

---

## 六、风险与提醒

| 风险 | 说明 |
|---|---|
| **阶段一收尾完成** | 8 个学习点完成 7 个，验收三条全部达成。**可以进阶段二了**；唯一遗留的 Function Calling 边界与阶段三一起做 |
| ~~虚拟线程未用~~ | ✅ **已解决**：容器层 + 业务层都换成虚拟线程，2000 并发吞吐 472 → 1192.6 req/s，JVM 平台线程 223 → 32 |
| ~~Token 预算没做~~ | ✅ **已解决**：三条路径（放行 / 裁剪 / 拒绝）都实测过，系数用真实 usage 标定 |
| **虚拟线程放开了并发上限 → 下游成为新的瓶颈** | 这是这次改造新引入的风险，而且比原来更隐蔽：原来线程池满了会**逼着你处理**（请求排队/被拒），现在 10 万个虚拟线程安静地挂着，你会以为系统很闲。**上线前必须补按下游容量定的限流**（模型服务、DB 连接池） |
| **`otherTokensPerChar` 系数没有实测支撑** | Emoji / 西里尔 / 阿拉伯文这些字符用的是 1.5（经验值），因为校准样本里没有这类字符。**这是一个明确标注为"未校准"的系数**，多语言场景上线前要补样本 |
| **Token 估算误差 11.5%–21.5% 是"对 DeepSeek"的** | 系数跟着**分词器**走。换模型（甚至换 DeepSeek 版本）都必须用 `/api/diag/token/calibrate` 重新校准一遍 —— 这一步已经做成端点，几分钟的事 |
| ~~只测通正常路径~~ | ✅ **已改善**：51 个自动化测试（含 21 个预算/估算、21 个失败路径）+ **界面 29 项断言**（含控制台零警告） |
| **空闲超时的真实模型复现** | 空闲超时（默认 30s）在真机**没能自然复现**——DeepSeek 的 chunk 间隔实测远小于 400ms，把 idle 压到 1ms 又会让首个 chunk 就超时。该路径目前只有替身测试覆盖（`#workingTimeoutComposition` 场景二）。**若要上线，必须先想清楚：真实网络抖动是否会触发误判** |
| 虚拟线程未用 | 同步 `call()` 端点在高并发下会打满 Tomcat 线程池（本资料反复强调的头号坑） |
| Key 需轮换 | 该 key 曾以明文出现，建议到 DeepSeek 控制台重新生成 |
| **Nginx 实测延后** | `proxy_buffering` **未经真实验证** → 存在「本地流式正常、上线变攒批」的上线风险。**上正式环境时必须执行第五节的上线动作清单**，不要在部署当天才第一次听说这个坑 |
| 记忆是单机内存实现 | `InMemoryChatMemoryRepository` 重启即丢；多实例部署时同一 conversationId 会落到不同实例（阶段四再解决） |

---

## 七、面试可讲素材（已积累）

目前能**带数字**讲出来的：

- 「我用 Spring AI 2.0.1 实现了 SSE 流式对话，实测 100+ 增量事件逐字返回，单轮 737 tokens」
- 「对话记忆我验证了 promptTokens 从 242 涨到 299，用 token 增量证明历史确实被带入了，而不是只看模型回答对不对」
- 「2.0 起 memory advisor 的 conversationId 是必传的，缺了直接抛异常，没有默认值」
- 「从 Initializr 生成的 Spring AI 项目不含 Servlet 容器，`@RestController` 会 404，必须自己补 `spring-boot-starter-web`」
- 「**重试只允许发生在首个增量之前**：SSE 响应头一旦发出就收不回来，已经吐了半句再重发会让用户看到两段拼接的回答。所以我把重试闸门设在"有没有产出过内容"上，并写了测试钉死：吐出内容后再失败，服务只被调用 1 次」
- 「**重试会污染对话记忆，这是个隐蔽的坑**：`MessageChatMemoryAdvisor` 在发起调用前就写用户消息，但回答只在流正常完成时才写。所以每次超时重试都会在记忆里留一条没有回答的孤儿用户消息 —— 实测第 3 次尝试的 prompt 里同一句话出现了 3 遍。修法是在每次重试前摘掉它，而且必须是"每次都摘"而不是"发现重复才摘"」
- 「分清 429/5xx 和 4xx：前者值得退避重试，后者重试多少次结果都一样，只会掩盖真正的配置错误（比如 Key 没读到导致的 401）」
- 「超时不是设一个就完事：**首字超时**和**空闲超时**是两件事，前者覆盖建连+排队+思考，后者覆盖回答中途卡死。而且 Reactor 的 `timeout` 对"一个信号都没有"的流不生效，套错位置等于没设」
- 「SSE 的 body 流**不响应 `abort()`** —— 前端超时不能靠 AbortController，必须和 `reader.read()` 赛跑，否则超时后页面永远停在「正在思考…」」
- 「**这个功能我自己在浏览器里完整用过一遍**：多轮记忆、逐字流式、Markdown+代码高亮、token 用量、停止生成、新会话、temperature、超时重试按钮，8 项全过 —— 自动化测试只能证明接口返回的字节对，证明不了用户看到的东西对」
- 「**DeepSeek 不支持 `response_format=json_schema`**，发过去直接报 "This response_format type is unavailable now"。所以"让模型输出结构化数据"的正确性<b>只能靠应用侧兜</b>：我用 JSR-380 校验 + 把"哪个字段错、错在哪、你上次的原文"一起回灌，最多 3 轮，仍不合格就走兜底答案而不是抛异常」
- 「**回灌重试真的能被模型理解**：我把它自评置信度的下界临时调到 0.99 来强制触发校验失败，第 2 轮模型的推理链里明确写着"confidence below configured lower bound, I need to raise confidence" —— 反馈闭环在起作用是有证据的，不是我说它在起作用」
- 「**`double` 会把"字段缺失"变成 0.0**：结构化输出的字段必须用包装类型 `Double` + `@NotNull`，否则模型漏字段时反序列化出 0.0，一个看着完全合法的值，校验器永远发现不了。这是"解析成功 ≠ 数据可用"最典型的例子」
- 「区分两种重试：**网络层重试**（同一次请求重发，判据是超时/429/5xx）和**回灌重试**（同一个问题、但请求内容变了，判据是校验结果）。它们可以同时生效 —— 一次模型调用可能先过 2 次网络重试，再过 2 轮回灌」
- 「**结构化输出的界面要把"过程"露出来**：我做的结果卡主角不是答案，而是那条链路 —— 头部徽标一眼看出「1 轮通过」还是「2 轮（1 轮不合格 → 回灌修正）」，展开后每轮的模型原始输出和校验问题都在。只渲染最终答案的话，「一次就成功」和「回灌两轮才救回来」长得一模一样，这个功能的全部价值就被藏起来了」
- 「**界面验证用几何断言代替肉眼**：当前模型看不了截图，所以自检脚本直接断言 `documentElement.scrollWidth - clientWidth > 0` 来判定横向溢出，再断言控制台零错误零警告 —— 这比"我看了一眼截图感觉没问题"可靠，而且能进 CI（脚本返回非 0 退出码）」
- 「**Vite dev server 会被 Windows 文件锁整个搞挂**：编辑工具原子替换文件时那个瞬间消失的 `.tmpdir` 让 chokidar 抛 `EBUSY`，而 Vite 没捕获、进程直接退出。现象是"改一个文件，前端服务就没了"，特别容易被误判成"我代码写崩了"」

**虚拟线程与并发改造（本轮新增，都能带数字）**

- 「**虚拟线程不加速单个请求，它消除的是"因为线程不够而排队"**。我做了三组对照压测（400ms 替身、8 核、同一个二进制）：2000 并发下吞吐从 **472 涨到 1192.6 req/s**（2.5 倍），p99 从 **4236ms 降到 1676ms**，但**单请求延迟基本没变** —— 降的全是排队时间。同期 JVM 平台线程峰值从 **223 降到 32**，业务并发峰值从 **199（=容器线程数）** 变成不再受线程数限制」
- 「**改造要落到真正阻塞的那一层，只改一半等于没改**。我特意跑了一组"容器换虚拟线程、业务用固定 8 线程池"的配置：400 并发里只有 **16 个成功、384 个被拒**，业务并发峰值死死卡在 **8**。容器的线程池和应用自建线程池是两个独立的天花板」
- 「**`synchronized` 会毁掉虚拟线程**。JDK 21 下虚拟线程在 `synchronized` 里阻塞时无法卸载载体线程，而载体只有 CPU 核数那么多。我做了对照实验：占位任务用 `synchronized` 阻塞时，新任务的排队延迟是 **6405ms**；换成 `ReentrantLock` 后是 **0ms** —— 两组实验除阻塞方式外完全相同。所以虚拟线程化之前要先排查包着 IO 的 `synchronized`，这类问题不报错不打日志，只表现为"虚拟线程好像没什么用"」
- 「**流式端点和非流式端点的线程模型根本不是一回事**，所以"为什么流式不用改造"这个问题问得不对 —— 该问的是"等待的时候谁被占着"。流式走 WebClient 事件循环，等 token 时**没有线程被占用**；非流式的 `.call()` 全程占一个线程干等。而且结构化输出那条路更糟：一个请求最坏要占住线程 **3 次模型调用的时长**（回灌循环），所以同样 200 个线程，它能扛的并发比流式低一个数量级」
- 「**上线前必须补限流，因为虚拟线程把风险从"我们自己排队"转移成了"上游被打挂"**。原来线程池满了会逼着你处理（排队/被拒），现在 10 万个虚拟线程安静地挂着，你会以为系统很闲 —— 真正的瓶颈变成了模型服务和数据库连接池，这比原来的问题更难排查」

**Token 预算与估算校准（本轮新增，这条最容易被追问）**

- 「**Token 估算的系数必须用真实 usage 标定，教科书取值错得离谱**。我第一版按常识填了"英文 3.5 字符/token、中文 1 字 1 token"，实测出来是英文 **6.6 字符/token**、中文 **0.609 token/字** —— 分别**高估了约 1.9 倍和 1.6 倍**，而且这类偏差不会让任何功能报错，只会让系统一直做多余的裁剪」
- 「**校准方法是长度扫描，不是抽几个样本比一比**。短文本里固定开销占比太大，密度根本量不出来。我对同一段文本做 1/5/20/50/200 次重复，用真实 usage 拟合边际密度：中文 0.609、英文 0.152，两种扫描反推的固定开销都是 **236 tokens**（互相印证）。校准后估算误差从 +35%~+85% 收敛到 **+11.5%~+21.5%，而且全是正数（偏保守）** —— 这是刻意的：低估等于超窗没拦住（要花钱、可能被静默截断），高估只是历史多裁一点，代价不对称」
- 「**预算校验必须在发请求之前做，因为失败成本极不对称**。自己算是一次本地计算（微秒级、0 成本）；等模型报错要先花一次往返的钱和几秒。更糟的是有些供应商**不报错而是静默截断输入** —— 请求成功、回答也回来了，只是模型根本没看到前半段上下文，表现是"越聊越傻"，排查方向全错」
- 「**超预算有三条路径，不是一条拒绝**：放行 / **裁掉最旧的历史后放行**（对话变长是常态，一超限就报错体验很差）/ 拒绝（连提问本身都放不下，裁剪救不了）。而且裁剪必须**整条整条地丢**、至少保留 2 条 —— 全丢掉等于让模型失忆，比报错更难排查。实测真实模型 16 轮对话：需要 2177 > 可用 2150 时裁掉最旧 2 条、保留 22 条，之后稳定在 2040/2150」
- 「**默认的"条数窗口"会抢先挡住新的"长度限制"，这是最容易得出错误结论的地方**。对话记忆默认 20 条（按条数），Token 预算是按长度 —— 量纲不同。我第一次验证时发现"预算裁剪一次都没触发"，差点以为校验没生效，实际是被 20 条的记忆窗口抢先挡住了（第 11 轮时历史正好 20 条、需要 2058 tokens，而预算是 2150）。把窗口调到 80 条之后裁剪立刻出现。**看到"有个保护在生效"，不代表"你新加的那个保护在生效"**」

**还不能讲**（待补）：成本优化（Token 单价折算）。
