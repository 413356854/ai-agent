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
│   ├── sse.ts               # SSE 帧解析器（本项目最容易出 bug 的地方，注释最密）
│   └── chat.ts              # POST /api/chat/stream + fetch + ReadableStream
├── hooks/useChatStream.ts   # 流式状态机：消息列表、增量合并、停止、新会话
├── components/
│   ├── MessageBubble.tsx    # Markdown 渲染 + 代码高亮 + token 用量
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
| `error` | JSON `{"message":"..."}` | 红色 Alert 展示，不打断页面 |

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
