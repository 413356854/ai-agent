# ai-agent（后端）

Spring Boot 4.1.1 + Spring AI 2.0.1 + Java 21。当前能力：SSE 流式对话、对话记忆、**超时与重试**。

## 跑起来

```cmd
run.cmd              :: 启动应用（自动切 JDK 21 + Maven）
run.cmd test         :: 跑测试（21 个）
run.cmd compile      :: 只编译
run.cmd tree         :: 依赖树
```

启动前需要环境变量 `OPENAI_API_KEY`（缺失会**拒绝启动**并打印排查步骤，见 `ApiKeyStartupCheck`）。

> **`run.cmd` 不支持透传额外参数**（内部 `call mvn ... spring-boot:run` 没写 `%*`）。
> 想临时覆盖配置（排查超时/重试时很常用）直接用 Maven：
>
> ```cmd
> mvn spring-boot:run "-Dspring-boot.run.arguments=--ai.chat.timeout.idle=400ms"
> ```

## 超时与重试

配置全部在 `application.properties` 的 `ai.chat.*`，绑定到 `ai.agent.config.ChatCallProperties`；
"该不该重试、等多久"的判定在 `ai.agent.chat.support.ChatRetryPolicy`（纯函数，可单测）。

| 配置 | 默认 | 说明 |
|---|---|---|
| `ai.chat.timeout.first-token` | 20s | 等**第一个增量**的上限（建连 + 排队 + 思考都算） |
| `ai.chat.timeout.idle` | 30s | 相邻两个增量之间的最大间隔 |
| `ai.chat.retry.max-attempts` | 2 | 最大**重试**次数（不含首次），故最坏情况共 3 次调用 |
| `ai.chat.retry.initial-backoff` | 500ms | 首次退避，之后按 `multiplier` 指数增长 |
| `ai.chat.retry.max-backoff` | 5s | 单次退避上限 |
| `ai.chat.retry.jitter` | 0.2 | 抖动系数，避免所有客户端在同一毫秒一起重试（重试墙） |
| `ai.chat.retry.retryable-status-codes` | 429,500,502,503,504 | **只重试这些**；4xx 一律不重试 |

### 三条不能违反的规则（都有测试钉死）

1. **只重试"首个增量之前"的失败。** SSE 响应头一旦发出就收不回来；已经吐了半句再重发，
   用户会看到两段拼接的回答，模型侧上下文也会错乱。闸门是"有没有产出过内容"。
   → `ChatControllerRetryTest#doesNotRetryAfterContentAlreadyEmitted`
2. **重试前必须摘掉失败尝试留下的孤儿用户消息。** `MessageChatMemoryAdvisor` 在发起调用
   **之前**就写用户消息，而回答只在流**正常完成**时才写。所以每次超时重试都会在记忆里留一条
   没有回答的用户消息，实测第 3 次尝试的 prompt 里同一句话出现了 3 遍。
   → `ChatControllerRetryTest#dropsOrphanedUserMessageBeforeRetry`
3. **4xx 绝不重试。** 请求本身有问题时重试多少次结果都一样，只会浪费配额并掩盖真正的配置错误
   （例如 Key 没读到导致的 401）。
   → `ChatRetryPolicyTest#retriesRateLimitAndServerErrorsOnly`

### Reactor 的三个坑（改超时逻辑前必读）

| 坑 | 现象 | 正确做法 |
|---|---|---|
| `timeout` 对"一个信号都没有"的流不生效 | 把超时放进 `switchOnFirst` 分支里，模型挂起时永远不触发，页面停在「正在思考…」 | 超时套在**源**上 |
| `switchOnFirst(signal, flux)` 的 `flux` **已含首元素** | 再 `concat(signal.get(), flux)` 会把首个增量发两遍 | 直接用 `flux` |
| 两个阈值合并成一个 `timeout` | `idle` 配小时会连带把首字等待也掐短 | 源上 `firstToken`，分支内 `idle` |

对应回归测试：`#timeoutMustWrapSourceNotSwitchOnFirst`、`#switchOnFirstSecondArgumentAlreadyIncludesFirstElement`、`#workingTimeoutComposition`。

## 与前端的时间预算

前端的超时必须**大于**后端的重试预算，否则会出现"后端还在重试、前端已报超时"的假故障：

```
后端首字超时 20s + 各次退避 ≈1.1s  <  前端首字节超时 30s  <  前端整体超时 180s
```

前端这三个值在 `web/src/api/chat.ts`。改一边必须回头看另一边。

## SSE 事件契约

`POST /api/chat/stream`，请求体 `{"conversationId":"...","message":"...","temperature":0.3}`：

| 事件 | data | 何时发 |
|---|---|---|
| `content` | `{"delta":"..."}`（**JSON，不是纯文本**，原因见 `ChatController` 注释） | 每个增量 |
| `done` | `{"promptTokens":..,"completionTokens":..,"totalTokens":..,"model":".."}` | 流正常结束 |
| `error` | `{"message":"..."}` | 失败（**重试耗尽后才发一条**，不会每个失败尝试都发） |

## 测试

21 个，其中 20 个是失败路径（这是本次的重点：之前所有验证都跑在 happy path 上）：

- `ChatControllerRetryTest`（13）—— 重试次数、不重复内容、超时文案、有增量后不重试、
  记忆不被污染、以及上面那三个 Reactor 陷阱的回归测试
- `ChatRetryPolicyTest`（7）—— 退避与抖动包络、上限截断、"4xx 绝不重试"红线

## 已知未验证项

- **空闲超时在真实模型上没能自然复现**：DeepSeek 的 chunk 间隔实测远小于 400ms，把 idle 压到
  1ms 又会让首个 chunk 就超时。该路径目前只有替身测试覆盖（`#workingTimeoutComposition` 场景二）。
- **Nginx `proxy_buffering` 未实测**：本地没有代理环境，已决定延后到上正式环境时做
  （清单见根目录 `学习进度台账.md`）。
- **开发期 Vite 代理的超时**：已在 `vite.config.ts` 显式设为 0，但没跑到那个时长去验证。
