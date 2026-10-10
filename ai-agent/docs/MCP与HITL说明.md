# MCP Server 与人工确认（HITL）· 阶段三第 6、7 件说明

> 触发：**2026-09-16（第九轮）** 「人工确认节点（HITL）」+「把工具封成 MCP Server」同时完成。
> 本文件只讲**怎么用、怎么读、已知局限**；功能点与代码索引见 `docs/项目功能点与代码索引.md` 的 4.6 / 4.7。

---

## 一、为什么这两件必须一起做

这一件从第六轮起就挂着一条警告，本轮就是去兑现它的：

> ⚠️ 现在三把工具全是只读的，这条没有真实场景可验证：
> 要做出可信的样子，必须先有至少一个『写』工具。**这一条与第 4 件是绑定的，别单独做。**

原因是**验收口径**：HITL 唯一有意义的验收不是"代码能跑"，
而是「**人拒绝之后，那个副作用真的没有发生**」。这句话需要一个**能被观察的副作用**才成立 ——
而只读工具没有副作用，所以拿它测审批，无论断言写得多漂亮，
测的都只是"文本里出现了'拒绝'两个字"。

于是本轮加了一把写工具 `propose_review`（建一条人工审查工单），HITL 才有东西可审批。
而 MCP 恰好要把**同一把**写工具挡在门外 ——
两件事共用同一份风险声明（`ToolHints`），于是"对外只读、对内受监管地写"成为一条**可断言的边界**。

---

## 二、HITL：从模型提出到人点头的完整链路

### 2.1 五道门禁，审批在第五道

```
①白名单 → ②并发闸门/熔断 → ③JSON 解析 → ④JSR-380 校验 → ⑤人工确认 → 执行
```

**审批为什么必须在校验之后**：让人批准一份 `{"query":"分块","topK":"5"}` 是没法批的。
人看到的参数必须**就是会被执行的那份** —— 否则他批的是 A、执行的是 B。
所以 `agent_tool_approval.arguments` 这一列**不做任何加工**（不打码、不摘要），
只由存储层兜一个 4000 字符的硬上限，且**超限时会留下明确的截断标记**。

**审批为什么必须在执行之前**：放在工具内部（让工具自己问）就等于把闸门交给被监管方。
哪天有人新写一把高危工具却忘了问，没有任何东西会发现。

### 2.2 哪把工具需要审批：一条推导，不是一个开关

判据在 `ToolHints`（四个字段用 MCP 的语义命名，于是同一份声明能同时驱动三件事）：

| 风险声明 | 要不要审批 | 典型形态 |
|---|---|---|
| `readOnlyHint=true` | **不用** | 检索、列目录、读章节 |
| 非只读 + `idempotentHint=true` | **不用** | "提交一个已经存在的东西"——重复执行没有额外副作用 |
| 非只读 + 非幂等 | **必须审批**（兜底） | "创建一条记录"——模型重试一次就多一条 |

两条容易被写错的地方：

- **兜底那一条不能写成"看 `destructiveHint`"**：一个忘了声明破坏性的创建类工具
  会**静默地免掉审批**，而"忘了声明"是默认情况、不是例外情况。
- **默认值是"只读"是刻意的保守选择**，但它的失效方向是"漏放"。
  兜住它的是 `ToolRegistry` 启动自检（有写工具却一把都不需审批时**打 WARN**）。
  ⚠️ 只是 WARN 而不是拒绝启动 —— 因为"幂等写免审批"是合法状态。
  ⚠️ 原先还有一条测试盯着这件事（新增写工具却忘了声明时，它会红）；
  那批后端测试已在 2026-09-18 整体删除，**现在只剩这条 WARN 在守着**。

### 2.3 超时 = 拒绝（本模块最重要的取向决定）

| 取向 | 失效方向 |
|---|---|
| 超时 = **通过** | 没人管事时系统照常写数据。而"没人管事"恰恰是审批流没接上最常见的形态 —— **闸门在最需要它的时候自动失效，且没有任何人注意到** |
| 超时 = **拒绝**（本实现） | 没人管事时任务停下来。代价是有人抱怨"怎么又卡住了"，而**这个抱怨正是应该发生的事** |

一句话：**审批的默认值必须是"不做"，因为"做了"是不可逆的。**
配置层也照这条守：`ai.agent.approval.timeout` 在**构造期**拒绝 0 与负数。

### 2.4 闸门是阻塞的 —— 这是虚拟线程的用武之地

写操作走到门禁 ⑤ 时，`AgentApprovalService.await` 会**把当前线程停住**等人。
固定线程池时代，等 8 个人就把池子占满了；虚拟线程时代，等 800 个也只是一个数字
（被阻塞时它释放载体线程）。所以这里不需要"回调 + 状态机"那套复杂写法。

**这个选择带来的两个可观测后果**（都要知道）：

1. **好人路径**：人在页面上一点"批准"，那个**还没结束的 HTTP 请求直接接着跑完** ——
   不需要"先存起来、再恢复一次"。
2. **待批路径不会被阻塞闸门产生**：阻塞闸门要么等到批准、要么等到拒绝、要么等到超时，
   **永远不会把"待批"这个中间状态交回循环**。
   所以 `AgentLoop` 里那段挂起代码（写检查点 + 返回 `WAITING_FOR_APPROVAL`）
   要用一个**不阻塞的桩闸门**才测得出来 —— 那个桩闸门原先写在后端测试里，
   那批测试已在 2026-09-18 整体删除，想再验一次得自己手写一个。
   > 这条本身就是一个可复用的教训：**一个分支如果没有任何测试能走到，它就等于没被实现过。**
   > ⚠️ 按这条教训的判据，这一段路径现在**没有任何东西能走到它**：
   > 没有自动化覆盖，也没人在真后端上手工走过。

### 2.5 批准之后怎么"接着跑"（而不是重跑）

批准发生在**上一次 HTTP 请求**里，执行发生在**恢复之后的新请求**里。
所以"已批准"必须是**从库里读回来、显式传进去**的事实（`ApprovalGrant`），不能留在内存里。

匹配用的是 **"工具名 + 参数"**，不是 `tool_call_id`：

- 恢复之后模型会**重新提出**那个调用，而协议层会给它一个**新的** id
  （由上游生成，不由我们决定）→ 按 id 匹配必然匹配不上；
- "同一个工具 + 逐字节相同的参数"才是"人批的那一次"的真实身份 ——
  这个观察与 `DuplicateActionDetector` 用的是同一个事实。

**参数改了就必须重新审批。** 这不是苛刻，是审批制度的底线：
人批准的是"创建一条标题为 X 的工单"，模型回头提出标题为 Y，那是另一件事。

`ResumableAgentService.resumeAfterApproval` 里有**三道核对**，缺一条都会造成"批的是 A、执行的是 B"：

1. 那条审批记录的状态**真的是 APPROVED**（不是"调用方说它批了"）；
2. `approval.runId()` 与传入的 runId 一致；
3. 检查点里**悬空的那次调用**与审批记录**逐字匹配** —— 对不上就**拒绝继续**，而不是猜。

### 2.6 流程全景

```
模型提出 propose_review
        │
        ├─ ① 闸门判定：需要审批（非幂等的写）
        │      落一条 PENDING 到 agent_tool_approval
        │      当前线程停下来等（默认 120 秒）
        │
        ├─ ② 人在界面上点「批准」
        │      POST /api/agent/approvals/{id}/decide  {"approve":true,"decidedBy":"张三"}
        │      → 状态落 APPROVED（先落库，再唤醒线程）
        │      → 如果那个请求线程还在等：**当场唤醒，任务在同一个请求里接着跑完**
        │      → 如果没人在等（进程重启过）：**自动 resumeAfterApproval** 从检查点接着跑
        │
        ├─ ②' 人点「拒绝」/ 超时
        │      → 状态落 DENIED / TIMED_OUT，工具**不执行**，工单一条都不建
        │      → 拒绝理由原样回填给模型，且明确告诉它"不要再重新发起"
        │
        └─ ③ 审计
               工单里带上 approval_id → 从一条不该存在的工单能直接倒查到哪次审批放行了它
```

---

## 三、MCP Server：对外只给"读"

### 3.1 怎么连

| 项 | 值 |
|---|---|
| 传输 | **Streamable HTTP**（MCP 2025-06-18 规范的主推传输） |
| 端点 | `POST /mcp`（默认端口 8080） |
| 服务器自报 | `name=ai-agent-tools`，`version=0.0.1` |
| 协议要求 | `Accept: application/json, text/event-stream`；`initialize` 之后**必须**带上响应头里的 `Mcp-Session-Id` |

验证脚本（**真发 HTTP**，不是看日志）：

```powershell
pwsh -File scripts/verify-mcp.ps1 -BaseUrl http://127.0.0.1:8080
```

实测输出结尾是 `合计 23 项：通过 23，失败 0`。

其中 4 项专门盯 `query_sql`：它读到的必须是**当前这个库**。
判据只能落在真实返回上 —— 工具描述里给模型的"先查目录"那句话一旦写死过库名
（H2 的 `PUBLIC`），换到 MySQL 之后模型会查到一个空目录、然后报告"库里没有表"，
而库里有。所以脚本真的调一次"列出当前库的表"并断言结果里有 `agent_run`。

### 3.2 对外提供的工具（4 把，全是只读）

| 工具 | 它做什么 |
|---|---|
| `search_docs` | 在项目语料里做关键词检索，返回最相关的若干片段 |
| `list_documents` | 列出知识库里有哪些文档、各自规模与主要章节 |
| `read_section` | 读出指定文档里某一节的完整正文 |
| `query_sql` | 对**应用自己的库**执行一条读语句（表名自己查 `INFORMATION_SCHEMA`） |

⚠️ **五把文件工具（`read_file` / `glob` / `grep` / `write_file` / `edit_file`）与命令执行工具
（`run_command`）都不在对外列表里，连只读那三把文件工具也不在**。
它们不是"写操作"才被挡，而是**需要工作区**：
工作区是"一次运行"的属性，而外部 MCP 链路没有运行这个对象 ——
`ToolCallContext.mcpClient()` 里没有任何目录，这些工具在那条链路上没有可解析路径的根，
暴露出去只会"每次都回一句没有工作区"（对客户端就是"这个工具坏了"）。
判据是标记接口 `WorkspaceBoundTool`，不是一份写死的名单；
挡掉时 `McpToolExposure` 会记一条日志写明理由。

⚠️ **`query_sql` 让这条边界的含义变了，必须看清**：前三把只能看到**项目语料**，
而这一把能看到**整个运行库** —— `agent_run` / `agent_step`（含每步的完整对话检查点！）/
`agent_tool_invocation` / `agent_tool_approval` / `prompt_version` / `retrieval_hit`。
"只读"保证的是"改不了"，它**不保证**"看不到"。所以：

- 能访问 `/mcp` 端口的人（目前**没有鉴权**）就能把这些数据读走；
- 如果那个库同时存着别的业务数据（例如把 `ai.agent.store.url` 指向一个共用库），
  这条暴露面会一起把它带出去；
- 要关掉它，改 `McpToolExposure` 的暴露路由即可（那是**唯一**决定"对外暴露什么"的地方）。

每把都带 MCP 的 `annotations`，由 `ToolHints` 单一来源驱动。实测 `search_docs` 的：

```json
{"title":"search_docs","readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}
```

真启动时的自检日志（它逐把比对"服务器实际装的"与"我们打算暴露的"）：

```
MCP Server 自检通过：对外提供 4 把只读工具 [list_documents、query_sql、read_section、search_docs]；
刻意不暴露的写工具 [propose_review、run_command、write_file、execute_sql、edit_file]（写操作只能由本应用自己的模型链路发起，并经过人工确认）
```

（这一行里写工具的清单会随新增的写工具变长，而"对外提供的 4 把"是注册表里只读工具的全部 ——
注册表当时是 13 把：4 把只读对外 + 3 把只读但需要工作区 + 1 把需要执行者 + 5 把写。）

### 3.3 刻意不暴露：写操作

`propose_review` 与 `execute_sql` **都不在** MCP 的工具列表里。理由不是省事 ——
**MCP 调用的对面是另一个系统**，它既看不到本项目的待办界面、也没法替人点"批准"。
暴露它们只有两种结局：要么外部调用永远卡在等人上（对调用方是"这个工具坏了"），
要么我们为了让外部能用而给它开后门 —— 而**那道门一开，审批就形同虚设**。

`execute_sql` 把这条规矩的代价抬高了：它是一把"影响面由模型生成的 SQL 决定"的写工具，
对它的审批**只有人**能做（`ToolHints.writeRequiringApproval()` → 必然触发人工确认），
所以它比 `propose_review` 更没有开放外部调用的余地 ——
外部客户端连"这条 SQL 会改什么"都没法让人看一眼。

规矩是**硬失败**而不是过滤：

```
McpToolExposure.exposedCallbacks()  在遇到非只读工具时抛 IllegalStateException
McpToolExposure.exposeReadOnlyTools()  装配层用这个：按声明显式路由，挡掉的记一条日志
```

⚠️ 这两个入口的分工是第一版写错之后改出来的：
把"过滤"与"硬拒"写在同一个方法里，会让后者变成**永远执行不到的死代码**。

实测拒绝的形状（真发一次 `tools/call name=propose_review`）：

```json
{"jsonrpc":"2.0","id":5,"error":{"code":-32602,
 "message":"Unknown tool: invalid_tool_name",
 "data":"Tool not found: propose_review"}}
```

### 3.4 ⚠️ 三层防护，缺一层都不够

| 层 | 做什么 | 它单独为什么不够 |
|---|---|---|
| ① 配置 | `spring.autoconfigure.exclude` 排掉 `ToolCallbackConverterAutoConfiguration` | **"排除一个不存在的类"Spring 是不报错的** —— 哪天 Spring AI 改包名，我们会安静地回到坑里 |
| ② 代码 | 排除之后暴露什么由 `McpExposureConfig` 显式决定 | 单独看它只是接线，没有任何东西证明它真的生效了 |
| ③ 断言 | **启动时真的去问 MCP 服务器"你现在装了哪些工具"**，与预期逐把比对；不一致**拒绝启动** | —— 这一层才是"证明意图真的生效" |

**为什么需要 ①**：`AgentTool` **既是** `ToolCallback` **又是 bean**（刻意的设计），
而它的 `call(String)` 被**故意实现成抛异常**（堵死绕过校验的第二条路径）。
Spring AI 的 `ToolCallbackConverterAutoConfiguration` 会收集容器里**全部** `ToolCallback` bean
注册成 MCP 工具 —— 两者相撞的表现是 **`tools/list` 正常、`tools/call` 必炸**。

**为什么还需要 ③**：见「四、已知局限」第 1 条 —— 那是我实际踩到的。

---

## 四、已知局限（不粉饰）

1. **MCP 端点没有鉴权。** `/mcp` 对任何能访问该端口的人开放。
   本地演示够用，**上生产必须加一层**（网关鉴权 / token）。
   ⚠️ 这与"写操作不对外"是**两件不同的事**：前者管"谁能读"，后者管"能改什么" —— 本轮只做了后者。
   ⚠️ **"只读"只保证改不了，不保证看不到**：对外暴露的 `query_sql` 能读到整个运行库
   （含 `agent_step.transcript` 里每一步的完整对话检查点）。见 3.2 那一节。
2. **`ai.agent.approval.timeout` 的 120 秒没有被"真人"验证过。**
   本机没有真人会去点批准按钮，所以端到端只验证到"8 秒超时按拒绝处理"这条路径。
   "人点了批准、任务当场接着跑完"原先靠后端测试的一个唤醒用例覆盖，那批测试已在
   2026-09-18 整体删除 —— **这条路现在既没在真后端上走过，也没有自动化守着**。
   生产上这个值该与值班响应时间对齐。
3. ~~**界面还没接 HITL**~~ → ✅ **已在第九轮同轮补完。**
   第五种模式面板顶部新增「待我确认」卡片（参数原样 / 风险声明 / 已等与剩余 /
   决定人 / 批准 / 拒绝要理由），运行详情里新增「人工确认（HITL）」一节
   （第几步、等了多久、谁批的）。**界面自检实测：假后端 165 项全过、真后端 121 项执行 120 过。**
   界面侧的设计取舍与踩过的 4 个坑见 `web/README.md` 的「第五种模式的另一半」一节。
4. **工单存储是一个 JSONL 文件**（`./data/review-tickets.jsonl`），不是真的工单系统。
   它是"外部系统"的**替身**，选它的理由是"可以用肉眼核对"。
   生产上替换面就是 `FileTicketStore` 一个类。
   顺带它是刻意与 Agent 运行库**分开**的：工单是业务数据、运行记录是过程数据，
   清掉过程记录不该顺手清掉业务数据。
5. **幂等窗口是"标题 + 级别"**，所以"同标题同级别但内容不同"的两个工单会被去重成一个。
   这是"防重复副作用"与"允许改内容重提"之间的取舍，当前选了前者。
6. **进程重启后无法从界面补批**：孤儿待批会被**作废**（`EXPIRED`），
   而不是"继续等人" —— 因为对应的运行可能已经不处于等待状态，留着只会让人困惑。
   要恢复那次任务，走 `/api/agent/runs` 的恢复路径。

---

## 五、配置速查

```properties
# ---- HITL ----
ai.agent.approval.enabled=true          # 总开关。关掉时写操作全部放行，且 describe() 会明确说不拦
ai.agent.approval.timeout=120s          # 等人时限；不允许 0/负数（构造期抛）。超时按**拒绝**处理
ai.agent.approval.default-decider=unknown  # 没填决定人时记谁（空值在审计时等于没有）

# ---- 写工具（本项目选定的"外部系统"）----
ai.review.ticket-file=./data/review-tickets.jsonl   # 与 Agent 运行库**分开**
ai.review.deduplication-window=10m      # 同标题+同级别在此窗口内只建一条（重复副作用的第二道防线）
ai.review.max-detail-chars=1000         # 详情截断上限（超限会留下明确标记）

# ---- MCP ----
# ⚠️ 下面这一行删掉不会报错，但会埋一个雷：见「3.4 三层防护」
spring.autoconfigure.exclude=\
  org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration

spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.name=ai-agent-tools
spring.ai.mcp.server.version=0.0.1
spring.ai.mcp.server.protocol=STREAMABLE      # ⚠️ 与下一行是**两个不同的维度**，少一行就是 404
spring.ai.mcp.server.type=SYNC                # API 形态（SYNC/ASYNC）
spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp
spring.ai.mcp.server.instructions=...          # 给**对方的模型**读的：有什么、没有什么
```

### ⚠️ `protocol` 与 `type` 的区别（本轮真实踩到）

| 配置 | 管什么 | 取值 |
|---|---|---|
| `spring.ai.mcp.server.type` | 服务端 **API 形态** | `SYNC` / `ASYNC` |
| `spring.ai.mcp.server.protocol` | **传输协议** | `SSE` / `STREAMABLE` / `STATELESS` |

两个名字很像但是完全不同的维度。**只写 `type` 时**：应用照常启动、
日志说 `Registered tools: 3`、启动自检也通过，**但 `/mcp` 返回 404** ——
因为 streamable 的 WebMvc 自动配置带着一个 `EnabledStreamableServerCondition`，
它要求 `protocol == STREAMABLE`，不满足时**路由压根不注册**。

这就是为什么必须有 `scripts/verify-mcp.ps1`：**光看启动日志会得出错误结论。**

---

## 六、相关端点

| 端点 | 回答什么 |
|---|---|
| `GET /api/agent/approvals` | "现在有什么在等我批"（含 `enabled` 与 `note` —— 空列表的两种含义必须能分开） |
| `POST /api/agent/approvals/{id}/decide` | "我批 / 我不批"；body `{"approve":bool,"decidedBy":"…","note":"理由"}` |
| `GET /api/agent/approvals/status` | 闸门当前状态（开关、时限、待批数） |
| `GET /api/agent/runs/{runId}/approvals` | "这一次运行里，人一共被问过几次、结果如何、各等了多久" |
| `POST /api/agent/ask` | 跑一个任务；返回值里多一种结局 `waitingApproval` + 待批 id |
| `GET /api/agent/runs/{runId}` | 运行详情，含 `approvals` 与每条审批的 `waitedMs` |
| `POST /mcp` | MCP 协议端点（Streamable HTTP） |

**关于 `decide` 的返回值**：最重要的字段是 `awakened` / `resumed`。
前者说"有没有唤醒一个正在等的线程"（有 → 任务当场继续），
后者说"有没有真的从检查点接着跑起来"。两者都是 false 且 `runId` 非空时，
说明决定已记录、但任务还没能继续 —— 界面必须**明确提示**这件事，
否则"点了批准、显示成功、任务却停着"与"批准根本没生效"在使用者眼里完全一样。

---

## 七、命令执行工具（`run_command`）怎么用、边界在哪

**它做什么**：在工作区目录下执行一条 shell 命令，把**合并后的输出**（stdout + stderr）与退出码带回来。
`cd` / `dir` 类"读"也走它，但它的风险声明是写（原因见下）。调用举例：
`{"command":"mvn -q test","timeoutSeconds":120,"purpose":"跑单元测试确认这次改动"}`。

### 7.1 它为什么是写工具

审批判据来自 `ToolHints`（2.2），而本工具声明 `writeRequiringApproval()`。
理由与 `execute_sql` 完全同族：**一条命令的副作用在哪儿，从命令字符串里看不出来**
（`type ..\..\x` 就是一个反例）。工作区只是它的**工作目录**，不是沙箱边界 ——
进程用的是本应用自己的操作系统权限，能碰到的范围与它一致。

于是它在人工确认闸门里的位置是"没有路径参数的写工具"：

| 档位 | 行为 |
|---|---|
| 仅可查看 | 停在人工确认；即使批准放行，工具自己也会以 `not_permitted_by_permission_level` 拒绝（它一个字节都不执行） |
| 工作区内修改 | **要人点头**（本工具不实现 `WorkspaceCallContext`，所以中间那一档不覆盖它） |
| 完全权限 | 直接执行，不产生审核请求（这是这一档的定义） |

> ⚠️ **两个已经存在的配置会让"无人监督地执行命令"成为可能**：
> `ai.agent.task.enabled=true`（定时任务没有人在旁边点批准）与
> `ai.agent.task.permission-level=FULL_ACCESS`（定时任务拿的是完全权限档）。
> 它们不是这个工具引入的（`execute_sql` 在同样的配置下同样不等审批），
> 但不希望自动任务执行命令时，改这两个值即可 —— **不需要改代码**。

### 7.2 它跑在什么 shell 下、在哪个目录

| 平台 | 命令行 | 工作目录 |
|---|---|---|
| Windows | `cmd.exe /d /s /c <命令>`（`ComSpec` 取不到时用 `C:\WINDOWS\system32\cmd.exe`） | **本工作区目录**（参数里没有 `cwd` 字段） |
| Linux / macOS | `/bin/sh -c <命令>` | 同上 |

`/d` 关掉注册表里的 AutoRun（那是"每次起 `cmd` 都会先跑的一段命令"）。
命令**原样交给 shell**，不按空格分词 —— 否则 `echo a & echo b` 会被拆成四条互不相干的参数，
而回填里给出的失败原因会变成"命令不存在"。

### 7.3 超时与输出：两处必须自己兜住

- **超时**：`ai.agent.workspace.command.timeout`（默认 30s）是模型不传 `timeoutSeconds` 时的值；
  模型可以自己给，上限 `max-timeout`（默认 300s），超了直接拒绝并把这个数回填给它。
  到点会**强杀整棵进程树**（本机实测 `ping -n 30` 在 2s 时限下：后代 2 个被强杀、总耗时 2052ms）。
  回填里会写清"输出是被杀之前的、不完整"。
- **输出**：上限复用回填层那一个（`ai.tool.feedback.max-chars`，默认 2000 字符），
  本工具**不新增第二个上限** —— 两层各截一次时，第二次截断不会带标记。
  被截断时回填里给出"一共产生了多少字符"。
- **编码**：`ai.agent.workspace.command.charset`，默认取 JVM 的 `native.encoding`
  （本机实测是 **GBK**，而 `cmd` 的输出就是 GBK 字节；Linux 上取到的是 UTF-8）。
  解码出错时用 `U+FFFD` 替换并回填一句提示，**不用第二套编码兜底**
  （互相兜底会让"这个配置写错了"变成一件看不出来的事）。
  ⚠️ 这个属性必须写成占位符 `${native.encoding:UTF-8}`：写成 SpEL（`#{...}`）在绑定期就失败。

### 7.4 已知边界（不粉饰）

| 边界 | 说明 |
|---|---|
| **工作区不是沙箱** | 命令能读写工作区之外的路径，用的是本应用自己的 OS 权限。真正的保护是人工确认 |
| **一次只跑一条命令** | 要按顺序做几件事就串在一条里（`&&` / `;`）。本工具没有"后台作业"：一条命令最长占住并发闸门的一个名额 `max-timeout` |
| **平台只验过 Windows** | `/bin/sh -c` 那条分支只有代码可读，没有实测 |
| **编码配错不一定被发现** | 只有解出 `U+FFFD` 时才有提示；某段字节恰好全是合法字符时，得到的是**乱码而不是报错** |
| **它不进 MCP 暴露面** | 外部客户端那条链路没有工作区、也没有人点批准（与文件工具同一条判据：`WorkspaceBoundTool`） |
| **退出码的含义不做解释** | 原样回填（实测 `cmd` 的 `exit /b 3` 回 3、命令不存在回 1）。"1 代表什么"由命令自己决定，工具不猜 |

### 7.5 配置速查

```properties
ai.agent.workspace.command.timeout=30s              # 模型不传 timeoutSeconds 时的默认时限（>0）
ai.agent.workspace.command.max-timeout=300s         # 模型能申请到的上限（≥ timeout）
ai.agent.workspace.command.charset=${native.encoding:UTF-8}   # 读子进程输出的编码
```
