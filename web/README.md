# AI Agent 对话台（前端）

对接 Spring Boot 后端的对话页面，**三种模式**：SSE 流式对话 / 结构化输出 / 线程与预算（诊断取证）。
技术栈：**React 19 + Vite 8 + TypeScript + Ant Design 6**。

## 跑起来

```bash
# 1) 起后端（另开一个终端，需要 JDK 21）
cd ../ai-agent && run.cmd

# 2) 起前端
pnpm install
pnpm dev            # http://127.0.0.1:5173
```

不想配 API Key / 不想烧 token 时，用假后端验证渲染（**两种模式都支持**）：

```bash
pnpm mock           # 终端 A：假后端 → http://127.0.0.1:8081
pnpm dev:mock       # 终端 B：前端代理指向假后端
```

假后端的结构化端点会故意造一个"**回灌过一轮**"的结果（第 1 轮 `confidence` 写成百分制被校验拦下），
因为那才是这个功能真正值得看的样子；提问里带「降级」二字则返回 `degraded` 结果，用来看降级态。

## 界面自检（浏览器真跑一遍）

```bash
pnpm mock           # 终端 A（或先起真后端）
pnpm dev            # 终端 B
pnpm check:ui       # 终端 C：打 http://127.0.0.1:5173，19 项断言
```

打**真后端**时（模型一次就答对、也不会因为提问里带「降级」二字而真降级）：

```bash
CHECK_EXPECT_REFLECTION=0 UI_URL=http://127.0.0.1:5199 pnpm check:ui
```

`CHECK_EXPECT_REFLECTION=0` 的含义是"别断言回灌/降级这两条路径"——假后端里那两种结果是写死的，
断言才有确定性；真实模型没有"必然回灌""必然降级"的输入，硬断言必然误报。
降级路径由后端单测覆盖（`StructuredChatServiceImplTest#fallsBackAfterExhaustingAttempts`）。

为什么要有这个脚本，而不是"我自己点一遍就算验证"：点一遍只证明"我这次看到的对"，
证明不了"下次改动后还是对的"；而且有几个问题**肉眼看不出来** —— 横向溢出、
Ant Design 的 deprecation 警告、控制台报错。当前模型看不了截图，所以脚本刻意用
**几何断言**代替"肉眼看布局"（`documentElement.scrollWidth - clientWidth > 0` 就说明有横向溢出）。

截图落在 `ui-check-out/`（已 gitignore）。默认用系统已装的 Chrome（`channel: 'chrome'`），
因为本机 `ms-playwright` 里的内核版本与当前 playwright 不匹配，直接 launch 会报
"Executable doesn't exist"。

## 目录

```
src/
├── api/
│   ├── sse.ts               # SSE 帧解析器 + 帧级超时看门狗（本项目最容易出 bug 的地方，注释最密）
│   ├── chat.ts              # POST /api/chat/stream + 三道超时策略
│   ├── structured.ts        # POST /api/chat/structured + 一刀切超时（无流可切帧）
│   └── diag.ts              # /api/diag/threads/* 与 /api/chat/async：诊断 + 非流式对话
├── hooks/
│   ├── useChatStream.ts     # 流式状态机：rAF 合并增量、停止、新会话、一键重试
│   ├── useStructuredChat.ts # 结构化状态机：一问一答成对留痕、无停止按钮
│   └── useThreadDiag.ts     # 诊断状态机：每组实验一个 Remote 态 + 一把"实验锁"
├── components/
│   ├── MessageBubble.tsx    # 流式气泡：Markdown + 代码高亮 + token 用量 + 超时重试按钮
│   ├── StructuredResultCard.tsx # 结构化结果卡：答案 + 置信度 + 关键词 + 回灌过程时间线
│   ├── ThreadBudgetPanel.tsx    # 线程与预算面板：当前线程 / 线程账目 / 载体复用 / 钉住对照 / 预算切分
│   └── Composer.tsx         # 输入区（两种对话模式共用，按模式开关停止按钮与 temperature）
├── types.ts                 # 与后端 DTO 一一对应的类型（对话两种模式的类型在这里）
└── App.tsx                  # 模式切换 + 三套视图
```

## 两种模式的差别（这就是要对照的东西）

| | SSE 流式 | 结构化输出 |
|---|---|---|
| 端点 | `POST /api/chat/stream` | `POST /api/chat/structured` |
| 传输 | `text/event-stream`，逐字推 | 普通 JSON，一次返回 |
| 回答 | 自由文本 | 固定结构 `{answer, confidence, keywords}` |
| 正确性保障 | 无（模型说什么就是什么） | **JSR-380 校验 + 回灌重答 + 兜底** |
| 失败语义 | 吐多少算多少，失败发 `error` 事件 | **全有或全无**，失败给降级结果 |
| 停止按钮 | 有（可以只要半截） | **没有**（取消拿不到半成品，还白烧已跑的 token） |
| temperature | 用户可调 | 固定 0.0（后端钉死，故界面不给滑块） |
| 超时 | 首字节 30s / 空闲 30s / 整体 180s | 一刀切 60s（可能要跑多轮回灌） |

**为什么把两种模式做成同一个界面里的切换**：它们的差别本身就是这个项目要讲的东西 ——
同样一次模型调用，一个逐字推自由文本，一个同步等结构化结果。放一起才能直接对比
"同样的提问，两种契约下拿到的东西有什么不同"。
两个 hook 都挂在 `App` 上（不是按模式条件挂载），所以来回切模式**不会丢掉已有记录**。

## 第三种模式：线程与预算（运行时取证）

这一页不放"回答"，放的是**只有改造真的生效才成立的运行时事实**。它对应后端的两个学习点：

| 部件 | 端点 | 它证的是什么 |
|---|---|---|
| 当前线程 | `GET /api/diag/threads` | `virtual` 是**容器层是否换上虚拟线程**的唯一直接证据（来自 `Thread.isVirtual()`，不是读配置）。配置写错了 Boot 会静默忽略，所以这里把"配置意图"和"运行时事实"并排显示 |
| 线程账目 | `GET /api/diag/threads/stats`、`POST .../stats/reset` | 平台线程数（`ThreadMXBean`，**不含虚拟线程**）与业务口径的并发峰值。重置按钮是为连续做对照实验准备的：peak 是单调递增的，上一组会把这一组顶住 |
| 载体复用 | `GET /api/diag/threads/carriers` | 16 个虚拟线程只落到少数几个 `ForkJoinPool-1-worker-*` 上 —— "并发数 ≫ 线程数"长什么样 |
| **钉住对照** | `GET /api/diag/threads/pin` | 本页最值钱的一块：两组实验除阻塞方式外完全相同，`synchronized` 会把载体线程钉住导致探针延迟冲到秒级，`ReentrantLock` 则是毫秒级 |
| 预算切分 | `GET /api/diag/threads/config` | `窗口 × (1-安全边际) = 可用预算`，再切出一块给输出，剩下的才是输入上限；三段按真实 token 数按比例画成一条色带 |
| 非流式对话 | `GET /api/chat/async`（+ `/sync` 对照） | 预算校验在这条链路上真的跑过；同时把非流式端点的回答和耗时摆出来 |

两个实测坑（都体现在代码里）：

- **`carriers` 的 `virtualThread` 字段实测是空字符串**：`Executors.newVirtualThreadPerTaskExecutor()`
  不给线程起名。所以它既不能当 React key（16 条样本会撞成同一个 key，控制台直接吐重复 key 警告），
  也不一定有内容可显示——面板上虚拟线程那一栏只计数，复用的证据靠"每个载体背了几个任务"表达。
- **`pin` 的字段名是 `probeLatencyWith*`，不是 `queueLatencyWith*`**：这个实验返工过一次，
  第一版"先启动占位任务、睡一会儿再量延迟"两组都量到 0ms（`runAsync` 在提交时就选好了载体，
  而"载体刚好被占满"是个很窄的时间窗口）。现在改成"分批投放 + 每批连测多次探针取最大值"，
  指标名也跟着换了。对照组的 0ms 是正常结果，不是数据缺失。

面板和两个对话 hook 一样挂在 `App` 上，所以来回切模式**实验数据不会丢**（钉住实验一次要跑两秒以上，
重跑代价太大）。它自己有一条 defer 规则：**第一次切到这个模式才发请求** ——
虚拟线程计数要遍历所有线程抓栈，用户一直待在流式模式时不该白打。

## 与后端的契约

### 流式：`POST /api/chat/stream`

请求体 `{"conversationId":"...","message":"...","temperature":0.3}`，
响应 `text/event-stream`，三种事件：

| 事件 | data | 前端处理 |
|---|---|---|
| `content` | JSON `{"delta":"..."}`（**不是纯文本**，原因见下） | 拼到当前气泡 |
| `done` | JSON 用量 `{promptTokens, completionTokens, totalTokens, model}` | 显示在气泡下方 |
| `error` | JSON `{"message":"..."}` | 红色 Alert 展示；**超时**类错误额外给「重试本轮」按钮 |

> **为什么 `content` 也要包 JSON**：Spring 写出的是 `data:xxx`（冒号后**没有**分隔空格），
> 而 SSE 规范要求客户端「若值以一个空格开头，吃掉那一个空格」。两条规则一撞，
> 任何以空格开头的增量分片都会**丢一个空格**——实测模板渲染成 `publicFlux`（应为 `public Flux`）。
> 包成 JSON 后 data 以 `{` 开头，空格被引号保护，两种服务端写法都不会再被吃掉。

### 结构化：`POST /api/chat/structured`

请求体 `{"conversationId":"...","question":"..."}`，响应：

```jsonc
{
  "answer":   { "answer": "...", "confidence": 0.92, "keywords": ["SSE", "..."] },
  "attempts": [                                  // 每一轮尝试的完整留痕
    { "index": 1, "rawOutput": "{...}", "parsed": null, "issues": ["字段 confidence：..."], "elapsedMs": 1180 },
    { "index": 2, "rawOutput": "{...}", "parsed": { /* ... */ }, "issues": [], "elapsedMs": 940 }
  ],
  "degraded": false                              // true 时 answer 是后端兜底文案，不是模型给的
}
```

**`attempts` 是这张卡片的主角**：结构化输出真正值得看的东西是「模型第一次错在哪、
回灌之后改对没有、最后是不是降级了」—— 只显示最终答案是看不出这些的，
而这个功能的全部价值恰恰在那条链路上。所以界面上它是默认展开的（只要发生过回灌或降级）。

一个界面上的诚实标注：**置信度是「模型自己报的」，不是系统评估的可信度**。
它只用来触发「不确定」协议（低于阈值会被判不合格并回灌重答），不要当成质量指标看。
文案里显式写了这一点，否则很容易被误用。

## 几个刻意的设计决定

1. **用 `fetch` + `ReadableStream`，不用 `EventSource`**：`EventSource` 只支持 GET，`message` 就得塞进 URL（长度限制 + 明文进访问日志），还不能带自定义 header，且断线会自动重连导致重复请求。
2. **增量渲染按 `requestAnimationFrame` 合并**：一个 token 一次 `setState` 会让长回复重渲染上百次，叠加 Markdown 解析直接卡顿。现在每帧最多渲染一次。
3. **Markdown 渲染做双保险**：`markdown-it` 关掉 `html`（第一层），渲染结果再过 `DOMPurify`（第二层）。模型输出是不可信输入，`dangerouslySetInnerHTML` 不能裸用。
4. **`conversationId` 每次「新会话」都换**：后端按它做对话记忆，沿用旧 ID 会把上一轮上下文带进来。
5. **`crypto.randomUUID` 有兜底**：它只在安全上下文（https / localhost）可用，内网 `http://192.168.x.x` 部署时会 undefined。
6. **`highlight.js/lib/common`**：引默认入口会把 190+ 种语言全打进包，产物从 1.6MB 掉到 600KB 级别。
7. **结构化模式的 temperature 滑块直接不显示**（而不是置灰）：后端为了输出稳定把它钉在 0.0，给一个拖了不生效的滑块比不给更糟 —— 用户会以为自己的调整没起作用，然后开始怀疑整个功能。
8. **原始输出用 `pre` + `white-space: pre-wrap`**：结构化输出的 JSON 经常是单行几千字符，不换行会横向撑破卡片、让整页能左右滚动 —— 展示模型原始输出时最容易忽略的一点。
9. **`vite.config.ts` 里关掉了监听对 `.*.tmpdir/` 的遍历**：编辑器/工具落盘时是"写临时文件 → 原子重命名"，Windows 上那个瞬间出现又消失的临时文件会让 chokidar 抛 `EBUSY`，而 **Vite 没有捕获它 —— 整个 dev server 直接退出**。表现是"改一个文件，前端服务就没了"，很容易被误判成代码写崩了。踩过一次，故显式 ignore。

## 超时（三道，必须与后端联动）

| 超时 | 默认值 | 防的是什么 |
|---|---|---|
| 首字节 | 30s | 后端没启动 / 模型一个字都不回 |
| 空闲 | 30s | 回答到一半连接断了（TCP 半开、代理静默丢包时**不会报错，只会没数据**） |
| 整体 | 180s | "每 29 秒来一个字"把页面无限期占住 |

> **超时必须大于后端的重试预算**（后端首字超时 20s + 退避 ≈1.1s）。前端先超时会让用户看到
> "失败"，而后端其实正在成功返回的路上 —— 这种假故障最费排查时间。
> 反过来前端超时也必须存在：后端进程被 kill、防火墙静默丢包时，只能靠前端自己把"卡住"变成"明确失败"。

**实现上有一个坑：fetch 的 body 流不响应 `abort()`。** abort 只中断请求本身（连接、响应头阶段），
流已经开始后 `reader.read()` 正挂起等待时，abort 不会让那个 Promise 落地 ——
靠 `setTimeout(() => controller.abort())` 做超时，结果是超时后页面永远停在「正在思考…」。
所以帧级超时做成了"与 `read()` 赛跑"（见 `src/api/sse.ts` 的 `waitForRead`），
整体超时才用 abort（并单独标记，避免和用户点「停止」混为一谈）。

> 结构化端点**不需要**这套：它没有长连接，超时也是一刀切，所以 `api/structured.ts` 里
> 用最朴素的 `AbortController` + `setTimeout` 就够了。**照抄流式那套反而是错的。**

## 生产部署

```bash
pnpm build        # 产物在 dist/
```

把 `dist/` 内容拷进 `ai-agent/src/main/resources/static/` 即可同源访问（连 CORS 都不用配）。
若走 Nginx 反代 SSE，务必加：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_buffering off;          # 不关的话流式会被攒批，前端看起来像"卡一下然后一次性全出来"
    proxy_read_timeout 300s;
    proxy_set_header Connection '';
    proxy_http_version 1.1;
}
```

> **开发期的 Vite dev server 就是同一条链路上的代理**，它也有超时：`vite.config.ts` 里已显式
> 写成 `proxyTimeout: 0, timeout: 0`。不写的话默认超时会把长回答掐断，
> 现象是"回答说到一半就断了"、而且**只在开发环境复现** —— 这是 Nginx 那类坑的镜像版本。
> 该行为**尚未实测**（没机会跑到那个时长），属于已知未验证项。
