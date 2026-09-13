# AI Agent 对话台（前端）

对接 Spring Boot 后端的流式对话页面。技术栈：**React 19 + Vite 8 + TypeScript + Ant Design 6**。

## 跑起来

```bash
# 1) 起后端（另开一个终端，需要 JDK 21）
cd ../ai-agent && run.cmd

# 2) 起前端
pnpm install
pnpm dev            # http://127.0.0.1:5173
```

不想配 API Key / 不想烧 token 时，用假后端验证流式渲染：

```bash
pnpm mock           # 终端 A：SSE 假后端 → http://127.0.0.1:8081
pnpm dev:mock       # 终端 B：前端代理指向假后端
```

## 目录

```
src/
├── api/
│   ├── sse.ts               # SSE 帧解析器 + 帧级超时看门狗（本项目最容易出 bug 的地方，注释最密）
│   └── chat.ts              # POST /api/chat/stream + 三道超时策略
├── hooks/useChatStream.ts   # 流式状态机：消息列表、增量合并、停止、新会话、一键重试
├── components/
│   ├── MessageBubble.tsx    # Markdown 渲染 + 代码高亮 + token 用量 + 超时重试按钮
│   └── Composer.tsx         # 输入框（Enter 发送 / Shift+Enter 换行 / 温度滑块）
├── types.ts                 # 与后端 DTO 一一对应的类型
└── App.tsx
```

## 与后端的契约

`POST /api/chat/stream`，请求体 `{"conversationId":"...","message":"...","temperature":0.3}`，
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
> 我们实测过 Spring 写出的原始字节（`data:` 后确实没有分隔空格），详见根目录台账的踩坑知识库。

## 几个刻意的设计决定

1. **用 `fetch` + `ReadableStream`，不用 `EventSource`**：`EventSource` 只支持 GET，`message` 就得塞进 URL（长度限制 + 明文进访问日志），还不能带自定义 header，且断线会自动重连导致重复请求。
2. **增量渲染按 `requestAnimationFrame` 合并**：一个 token 一次 `setState` 会让长回复重渲染上百次，叠加 Markdown 解析直接卡顿。现在每帧最多渲染一次。
3. **Markdown 渲染做双保险**：`markdown-it` 关掉 `html`（第一层），渲染结果再过 `DOMPurify`（第二层）。模型输出是不可信输入，`dangerouslySetInnerHTML` 不能裸用。
4. **`conversationId` 每次「新会话」都换**：后端按它做对话记忆，沿用旧 ID 会把上一轮上下文带进来。
5. **`crypto.randomUUID` 有兜底**：它只在安全上下文（https / localhost）可用，内网 `http://192.168.x.x` 部署时会 undefined。
6. **`highlight.js/lib/common`**：引默认入口会把 190+ 种语言全打进包，产物从 1.6MB 掉到 600KB 级别。

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

超时后气泡里会给一个**「重试本轮」**按钮（重发提问，不是让模型接着说）。
HTTP 4xx / 业务错误**不给**这个按钮：同样的请求再来一遍结果一样，给了反而误导用户。

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
