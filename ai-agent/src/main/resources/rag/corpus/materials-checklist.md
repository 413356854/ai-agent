# Java → AI Agent 周计划 · 配套学习资料清单

> 配套 `Java开发者-AI-Agent转型路线.md` 的 10 周计划，逐知识点给出**可直接打开的资料**。
> **所有链接于 2026-09-11 用真实浏览器逐条校验**，✅ = 实测可访问；⚠️ = 被反爬拦截或加载失败（**不是死链**，浏览器里正常打开）。
> 用法：按阶段推进，每个知识点先读 📘 文档打底 → 再看 💻 实战抄代码 → 最后 🎯 产出自己的东西。

---

## ⚠️ 开篇必读：版本基线（2026-09-11 核实）

**先定版本，再学资料** —— 否则你会照着 1.0 的博客写 2.0 的代码，报错一整天。

### Spring AI 稳定版：**2.0.1**

| 项 | 值 |
|---|---|
| **当前稳定版** | **2.0.1**（2026-08-20 发布） |
| GA 基线 | 2.0.0（2026-06-12） |
| **Spring Boot 要求** | **4.0.x 或 4.1.x**（官方原文） |
| **Java 要求** | **Java 21**（最低基线） |
| 发布渠道 | Maven Central，**无需额外配置仓库** |

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>2.0.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### 🔴 最容易踩的坑：Spring AI 2.x 与 Spring Boot 3.x **不兼容**

> 「由于底层架构重构，**Spring AI 2.x 与 Spring Boot 3.x 不兼容，不可混合使用**。」

| 你的情况 | 走哪条线 |
|---|---|
| 公司存量项目是 **Spring Boot 3.x** | 用 **Spring AI 1.x**（1.1.x / 1.0.x 维护线仍在更新），别硬升 4 |
| **新项目 / 自己练手** | 直接上 **2.0.1 + Spring Boot 4.1.x + Java 21**，两年不用再动 |
| 公司已有 Boot 4.x | 直接用 2.0.1 |

### 官方文档入口（选对版本文档）

- **[Spring AI Reference（当前 = 2.0.1）](https://docs.spring.io/spring-ai/reference/index.html)** ✅ ← **默认指向稳定版，直接用这个**
- [Spring AI Reference 中文镜像](https://docs.springframework.org.cn/spring-ai/reference/index.html) ✅
- **[Upgrade Notes（升级必读）](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)** ✅ ← **迁移前一定要看**，2.0 的 Breaking Changes 非常多（MCP Java SDK 升到 2.0、Chat Memory Advisors 要求 conversationId、MCP 注解迁入 Spring AI 等）
- [Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) ✅

### 2.0.1 修了什么（和你的学习直接相关）

- **修复 7 个 CVE**
- **新增工具调用次数上限（tool call limits）以阻止失控的 agentic loop** ← 这正是阶段三「三重上限」知识点，**现在框架原生支持了，不用自己造轮子**
- 官方提醒：**Agent 上线前，工具边界和循环终止必须由运行时兜底**

---

## 资源优先级图例

| 标记 | 含义 | 怎么用 |
|---|---|---|
| 📘 | 官方文档 | **首选，永远先看这个**。框架 API 以官方为准 |
| 💻 | 实战代码教程 | 照着跑通，然后改坏它、修好它 |
| 🔧 | 工具/平台 | 工程化阶段用 |
| 🎯 | 项目/源码 | 到最后要产出自己的东西 |

> **一条铁律**：中文教程只用来**降低入门门槛**，遇到 API 细节一律回官方文档核对。中文博客普遍滞后 1–2 个大版本。

---

# 阶段一（1–2 周）大模型对接层

## 1.1 Spring AI 基础与 ChatClient

| 资源 | 类型 | 说明 |
|---|---|---|
| [Chat Client API :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/chatclient.html) ✅ | 📘 | **权威源头**。ChatClient 的 Fluent API、同步/流式模型 |
| [ChatClient API 中文版](https://springdoc.cn/spring-ai/api/chatclient.html) ✅ | 📘 | 上述文档的中文翻译，对照看 |
| [Chat Client API :: Spring AI 参考手册](https://docs.springframework.org.cn/spring-ai/reference/api/chatclient.html) ✅ | 📘 | 另一个中文镜像站（内容更新较及时） |
| [ChatClient API 1.0.0 中文文档](https://www.spring-doc.cn/spring-ai/1.0.0/api_chatclient.html) ✅ | 📘 | 版本化文档，查特定版本行为用 |
| [ChatClient Javadoc (2.0.1)](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/chat/client/ChatClient.html) ✅ | 📘 | **2.0 版 API**，注意与你用的版本对照 |
| [Spring AI ChatClient 详细用法文档](https://juejin.cn/post/7569405389306970150) ✅ | 💻 | 中文实战，Builder 用法 |

## 1.2 结构化输出（三种方案 + 修复循环）

| 资源 | 类型 | 说明 |
|---|---|---|
| [结构化输出 (Structured Output) :: Spring AI 参考](https://docs.springjava.cn/spring-ai/reference/api/structured-output-converter.html) ✅ | 📘 | **核心文档**：转换器在 LLM 调用前后做了什么 |
| [结构化输出 1.1.3 中文文档](https://spring-ai.spring-doc.cn/docs/1.1.3/api_structured-output-converter.html) ✅ | 📘 | `JSON_OBJECT` vs `JSON_SCHEMA` 的选择 |
| [结构化输出 1.0.4 中文文档](https://spring-ai.spring-doc.cn/docs/1.0.4/api_structured-output-converter.html) ✅ | 📘 | 旧版本对照 |
| [Spring AI 结构化输出：让模型返回 Java 类型结果](https://blog.zhijun.io/posts/spring-ai-structured-output) ✅ | 💻 | 三种内置转换器 + **附源码** |
| [Spring AI 2.0 技术专题（结构化输出等）](https://juejin.cn/post/7641976805642059817) ✅ | 💻 | 覆盖 2.0 的 Advisors / Function Calling / RAG / MCP |
| [完整教程：【SpringAI】结构化输出](https://www.cnblogs.com/ljbguanli/p/19589884) ✅ | 💻 | 输出映射到 Java 对象 |

> 🎯 **本知识点产出**：用 Java Record + JSR-380 定义返回结构 → 解析失败时把错误回发重试（≤3 次）→ 超限降级。

## 1.3 SSE 流式输出 + Nginx 配置（**必踩的坑**）

| 资源 | 类型 | 说明 |
|---|---|---|
| [Spring AI 进阶之路02：集成 SSE 实现流式响应（阿里云）](https://developer.aliyun.com/article/1677192) ✅ | 💻 | 后端到前端 EventSource **完整代码** |
| [Java 转 AI 第四课：Spring AI 实现流式输出（SSE vs WebSocket）](https://juejin.cn/post/7668718271143051315) ✅ | 💻 | 不止 Demo：区分 content/done/error 事件、前端正确关闭连接 |
| [Java 后端对接 AI 大模型 SSE 流式输出实战](https://juejin.cn/post/7665594166467395624) ✅ | 💻 | 打字机效果完整实现 |
| [Spring AI 中的 Flux 与 SSE：流式输出完全解析](https://jishuzhan.net/article/2039517720550051841) ✅ | 💻 | 三个关键角色的分工 |
| [JAVA 实现 SSE 与 Nginx 转发](https://blog.windigniter.com/2024/12/java-sse-with-nginx/) ✅ | 💻 | **含 Nginx 配置**，POM + Controller |
| [Nginx 反向代理流式输出延迟？一招解决](https://jishuzhan.net/article/2026105828651106306) ✅ | 💻 | **`proxy_buffering` 问题定位过程** |
| [处理 EventStream 不能流式返回的问题](https://juejin.cn/post/7400192724932624418) ✅ | 💻 | Nginx 缓冲与超时配置 |
| [Spring AI 实战：ChatMemory、SSE 流式与 Function Calling](https://codechina.net/article/weixin_31423955/310845) ✅ | 💻 | 一条线串起三个核心能力 |
| [Spring AI 流式对话实战（Ollama）](https://devpress.csdn.net/v1/article/detail/162866780) ✅ | 💻 | 代码可运行，首字 10s → 0.3s |

> ⚠️ **这一节必须动手验证**：本地直连正常 ≠ 上生产正常。把 Nginx 挂上去，**不关 `proxy_buffering` 看一次现象**，你会记一辈子。

## 1.4 虚拟线程 / 异步（防 Tomcat 线程池打满）

| 资源 | 类型 | 说明 |
|---|---|---|
| [Java 虚拟线程与 AI 高并发调用的最佳组合（GitHub）](https://github.com/Byron4j/SuperJavaAI/blob/main/articles/JAVA%20AI/延伸篇-专项技术突破/286-Java虚拟线程-Virtual-Threads-与AI高并发调用的最佳组合.md) ✅ | 📘 | **专为 AI 场景写的**，10000 并发只需几十 MB |
| [Java 21 虚拟线程最佳实践：从创建到性能对比](https://juejin.cn/post/7650030764785631268) ✅ | 💻 | 原理 + 适用场景 + 性能对比 |
| [Java 21 虚拟线程并发编程最佳实践](https://juejin.cn/post/7651792366891417646) ✅ | 💻 | 5 种创建方式 + 结构化并发建议 |
| [Java21 虚拟线程实战攻克 IO 阻塞（阿里云）](https://developer.aliyun.com/article/1670412) ✅ | 💻 | 万级并发潜力 |
| [Java 21 虚拟线程实战：百万并发](https://cloud.tencent.com/developer/article/2651134) ✅ | 💻 | Project Loom 原理 |
| [VirtualThread 实验项目（GitHub）](https://github.com/Liu-creators/VirtualThread) ✅ | 🎯 | **含线程固定(pinning)问题与 ThreadLocal 内存影响** |

> **坑点**：虚拟线程要留意 **thread pinning**（`synchronized` 块内阻塞会固定载体线程）。上面 GitHub 项目专门测了这个。

## 1.5 LangChain4j（编排层备选）

| 资源 | 类型 | 说明 |
|---|---|---|
| [LangChain4j 官方文档](https://docs.langchain4j.dev/intro/) ✅ | 📘 | 权威源头 |
| [LangChain4j 中文文档](https://docs.langchain4j.info/) ✅ | 📘 | **中文官方镜像**，含教程分类 |
| [LangChain4j 中文文档（备站）](https://langchain4j.cn/) ✅ | 📘 | 另一个镜像 |
| [LangChain4j 中文文档（coderbuffer）](https://langchain4j.coderbuffer.com/) ✅ | 📘 | 20+ LLM / 30+ 向量库支持列表 |
| [LangChain4j 教程 - 核心编程](https://www.hxstrive.com/subject/langchain4j.htm) ✅ | 💻 | 系统化中文教程 |

---

# 阶段二（2–4 周）RAG

## 2.1 Spring AI Alibaba 生态

| 资源 | 类型 | 说明 |
|---|---|---|
| [alibaba/spring-ai-alibaba（GitHub）](https://github.com/alibaba/spring-ai-alibaba) ✅ | 🎯 | **Agentic AI Framework for Java**，内置 Context Engineering + Human In The Loop |
| [Spring AI Alibaba 快速开始](https://java2ai.com/docs/quick-start/) ✅ | 📘 | 官方文档站，含 @ToolParam / ToolContext 说明 |
| [版本说明（SAA 与上游版本对应）](https://agentic-spring-ai.github.io/website/docs/versions/) ✅ | 📘 | **选型与迁移必看**，别踩版本坑 |
| [Spring AI Alibaba 快速开始（阿里云官方）](https://sca.aliyun.com/en/docs/ai/get-started/) ⚠️ | 📘 | 官方站，本次校验时页面崩溃，浏览器可开 |
| [【SpringAIAlibaba 新手村系列】(1) 初识](https://developer.aliyun.com/article/1720012) ✅ | 💻 | ChatModel / ChatClient / 百炼平台 + 流式 |
| [Spring AI Alibaba 教程：集成阿里云大模型](https://juejin.cn/post/7520328716082331682) ✅ | 💻 | 流式响应与多轮对话 |

## 2.2 RAG 全流程（文档 → 切分 → 向量 → 检索 → 生成）

| 资源 | 类型 | 说明 |
|---|---|---|
| [解锁 RAG 与 Spring AI 的实战应用（万字）](https://cloud.tencent.com/developer/article/2626104) ✅ | 💻 | **PGVector 配置 + 混合检索策略 + 性能优化** |
| [Spring AI 进阶之路03：集成 RAG 构建知识库（阿里云）](https://developer.aliyun.com/article/1696033) ✅ | 💻 | Redis 向量库，文档上传→切分→向量化完整链路 |
| [Spring AI 手搓 RAG：文档上传→向量化→检索增强](https://juejin.cn/post/7659936371336855587) ✅ | 💻 | **纯 Spring AI + Java，不用 Python/LangChain/Milvus** |
| [Spring AI 私有知识库实战：做一个不乱答的客服](https://cloud.tencent.com/developer/article/2716969) ✅ | 💻 | **重点讲了「相似度阈值怎么挡住乱答」「来源怎么跟着答案返回」** |
| [Spring AI + Redis 向量库实战](https://juejin.cn/post/7579573664228835337) 🔒 | 💻 | 生产级问答系统（掘金反爬，浏览器可开） |
| [RAG 企业知识库落地：Spring AI + Milvus](https://juejin.cn/post/7656025505838350379) 🔒 | 💻 | 含切分参数调优与准确率提升数据（掘金反爬） |

## 2.3 混合检索 + RRF + Rerank（**拉开差距的部分**）

| 资源 | 类型 | 说明 |
|---|---|---|
| [RAG 混合检索深度解析：BM25 + 向量 + RRF](https://www.smallyoung.cn/docs/028-RAG混合检索与RRF算法深度解析) ✅ | 💻 | **Spring Boot + LangChain4j 完整可运行 Java 代码** |
| [Spring Boot + LangChain4j 实战：BM25 混合检索 + RRF](https://juejin.cn/post/7632264201231368226) ✅ | 💻 | 用 RRF 公平融合两路结果，当天可落地 |
| [RAG 检索不准？BM25 + 向量混合检索、RRF 与 Reranker](https://jishuzhan.net/article/2083380628665286658) ✅ | 💻 | **一句话概括三者分工**（见下） |
| [RAG 系统进阶：混合检索 + 重排序](https://cloud.tencent.com/developer/article/2728930) ✅ | 💻 | 实验数据对比各策略，**可量化的优化方法论** |
| [10 分钟从零搭建本地 RAG：向量 + BM25 + RRF](https://cloud.tencent.com/developer/article/2654879) ✅ | 💻 | 含 GitHub 项目地址 |

> 💡 **背下这句话**（面试直接用）：
> **「向量检索负责理解换一种说法，BM25 负责抓住不能换的词，RRF 负责合并两份不同量纲的排名，Reranker 负责在少量候选中做更贵但更精细的判断。」**

## 2.4 文档解析与 ETL 管道

| 资源 | 类型 | 说明 |
|---|---|---|
| [Spring AI ETL 数据处理管道实战指南](https://juejin.cn/post/7644205077851455526) ✅ | 💻 | **Spring AI 原生 ETL 组件**：Extract-Transform-Load |
| [Spring AI Alibaba 文档智能处理：PDF/Markdown 入库](https://jishuzhan.net/article/2035167022833795073) ✅ | 💻 | **增量更新 + 异步向量化管道**，决定 RAG 天花板 |
| [Spring AI + Docling 企业级文档解析完全指南](https://jishuzhan.net/article/2056397071464894465) ✅ | 💻 | **Layout-Aware 解析器**，PDF 重灾区解决方案 |

> **顺序提醒**：文档解析质量决定 RAG 上限。**先检查进库的内容对不对，再调检索参数**——很多人反过来。

---

# 阶段三（2–4 周）Agent + MCP

## 3.1 MCP —— Java 开发者的先发机会（最高性价比）

| 资源 | 类型 | 说明 |
|---|---|---|
| [Spring AI：MCP 入门（官方文档）](https://docs.springframework.org.cn/spring-ai/reference/guides/getting-started-mcp.html) ✅ | 📘 | **官方指南**，并说明 Spring 帮助维护了官方 MCP Java SDK |
| [MCP Server - MCP Java SDK（官方）](https://java.sdk.modelcontextprotocol.io/latest/server/) ✅ | 📘 | **官方 SDK 站点** |
| [Java MCP 服务端 - MCP 中文站](https://mcp-docs.cn/sdk/java/mcp-server) ✅ | 📘 | 中文文档，含 Spring AI MCP 快速入门 |
| [Java SDK 概览 - MCP 中文站](https://mcp-docs.cn/sdk/java/mcp-overview) ✅ | 📘 | STDIO / SSE / Streamable-HTTP 传输实现说明 |
| [Java SDK - MCP 中文站](https://mcpcn.com/docs/sdk/java/) ✅ | 📘 | Java 17+ / Maven 3.6+ 要求，丰富工具与资源管理 |
| [MCP 服务器 - MCP 中文站](https://mcpcn.com/docs/sdk/java/mcp-server/) ✅ | 📘 | JSON-RPC 消息处理细节 |
| [Spring AI 实战：MCP 协议原理、Server 与 Client 完整搭建](https://cloud.tencent.com/developer/article/2665906) ✅ | 💻 | **旅游规划 Agent 完整案例**（天气查询 + 景点推荐） |
| [spring-mcp-example（GitHub）](https://github.com/zengzeliang/spring-mcp-example) ✅ | 🎯 | **完整 MCP Server + Client 示例代码** |
| [Spring Boot 集成 Spring AI：完成可被大模型调用的 MCP Server](https://www.cnblogs.com/jzssuanfa/p/19552978) ✅ | 💻 | **Stateless Streamable HTTP + token 传递**（生产必需） |
| [Spring AI 开发 MCP Server 和 MCP Client](https://www.cnblogs.com/hibpm/p/18900485) ✅ | 💻 | stdio 与 SSE 两种传输各一个例子 |
| [JAVAer 狂喜！10 分钟用 Spring AI 搭专属 MCP Server](https://juejin.cn/post/7519332294060113946) ✅ | 💻 | 快速上手 |

> 🎯 **本阶段核心产出**：把你熟悉的 **2–3 个企业接口**（订单/工单/审批）封装成 MCP Server。
> 简历价值远高于「学了个框架」——而是「**我把我负责的 X 系统变成了 Agent 能调的工具**」。

## 3.2 手写 ReAct 循环（**不用框架先写一遍**）

| 资源 | 类型 | 说明 |
|---|---|---|
| [用纯 Java 手写最小 ReAct Agent（拿个 offer）](https://nageoffer.com/ai/basics/agent/react-core/react-loop/) ✅ | 💻 | **纯 Java + OkHttp，零框架**，可打断点逐圈观察 |
| [第2章：跑起来一个最小 Agent Loop](https://juejin.cn/post/7668634785267318793) ✅ | 💻 | 「不引入任何 Agent 框架」——核心就是一个 while 循环 |
| [Agent 实战——手写 ReAct 推理循环，从零构建](https://juejin.cn/post/7668188177706483739) ✅ | 💻 | 私募基金问答 Agent，**含提示词陷阱、死循环规避** |
| [ReAct Agent：手写 Thought-Action-Observe 循环](https://jishuzhan.net/article/2035209532142583809) ✅ | 💻 | 8 篇系列，从零构建 RAG + Agent 项目 |
| [400 行 Java 代码手搓 AI Agent（ReAct + Tool Call）](https://paicoding.com/react-vs-toolcall) ⚠️ | 💻 | Java 实现 Agent CLI，含 GLM 集成 |

> 💡 **理解到位的一句话**：
> 「剥开所有包装，Agent 的核心就是一个 **『调用模型 → 解析工具 → 执行工具 → 回填结果 → 继续调用』的 while 循环**。」
> 「跑通了，你对 Agent 的理解**超过 80% 只会调 API 的人**。」

## 3.3 记忆 / 状态 / HITL

- 优先看 [Spring AI Alibaba 官方仓库](https://github.com/alibaba/spring-ai-alibaba) ✅ —— 内置 **Context Engineering + Human In The Loop**，直接看源码比看博客快。
- 配合 [快速开始](https://java2ai.com/docs/quick-start/) ✅ 里的 Agent Framework 部分。

---

# 阶段四（2 周）工程化 —— 这是你拉开差距的地方

## 4.1 可观测性与成本追踪（🔧 首推 Langfuse）

| 资源 | 类型 | 说明 |
|---|---|---|
| [Token & 成本追踪 - Langfuse 官方中文](https://langfuse.com.cn/docs/observability/features/token-and-cost-tracking) ✅ | 📘 | **官方中文文档**，含自定义模型定义 |
| [如何在 Langfuse 中追踪 LLM 成本和 Token](https://langfuse.com.cn/faq/all/costs-tokens-langfuse) ✅ | 📘 | 成本计算方式（自动推断 vs 显式注入） |
| [用 Langfuse 打造终极 LLMOps 体系](https://juejin.cn/post/7639984498776064041) ✅ | 💻 | 可观测性能力：延迟、token、成本 |
| [LLM 可观测性 2026：用 Langfuse 追踪每个 Token（10 分钟上手）](https://www.ainomam.com/zh/post/llm-observability-langfuse-guide-20260802) ✅ | 💻 | **含 v4 架构变化**（trace-centric → observation-centric） |
| [Langfuse 落地指南：部署与 trace 追踪](https://devpress.csdn.net/v1/article/detail/99109223) ✅ | 🔧 | Docker Compose 自托管，无需 GPU |

> 💡 **为什么用它**：「LLM 应用以传统监控无法察觉的方式失败：**返回空结果的提示词、陷入循环的工具调用、静默截断的上下文窗口。**」

## 4.2 评测体系（**最容易卡终面的一块**）

| 资源 | 类型 | 说明 |
|---|---|---|
| [LLM 应用评测实战指南：构建质量保障体系](https://www.jsjson.com/blog/llm-evaluation-guide) ✅ | 📘 | **最系统**：自动化评估、LLM-as-Judge、RAGAS、Prompt 回归测试与 CI/CD |
| [RAG 评测调研：框架、指标和方法（EvalScope）](https://evalscope.readthedocs.io/zh-cn/latest/blog/RAG/RAG_Evaluation.html) ✅ | 📘 | 用 llama-index 评测检索器与生成器 |
| [RAG 评测完整指南：指标、测试和最佳实践](https://cloud.tencent.com/developer/article/2597186) ✅ | 💻 | 检索/生成评估 + 鲁棒性 + 对抗性测试 |
| [LLM-as-a-Judge 与四大主流 RAG 评测框架](https://juejin.cn/post/7663419501435027496) ✅ | 💻 | **TruLens、RAGAS、DeepEval、Phoenix** 对比 |
| [RAG 检索评测实战：9 种架构对比与自动化评估框架](https://jishuzhan.net/article/2078650603688566785) ✅ | 💻 | **怎么构造测试集、不同检索策略差多少** |
| [RAG 系统评测技术全景（百度开发者中心）](https://developer.baidu.com/article/detail.html?id=6922516) ✅ | 📘 | 通用能力评估框架 + 专项工具 |

> 🎯 **本阶段硬性产出**：**30–50 条评测集**（真实问题，非自编）+ 检索指标（recall@k）+ 端到端成功率。
> ⚠️ 注意：RAGAS / DeepEval / Phoenix 都是 **Python 生态**。Java 侧建议**自建轻量评测脚本**（Spring Boot + 断言 + 报告），这反而是面试亮点：「我用 Java 自建了评测流水线」。

## 4.3 Prompt 治理（Nacos 已经原生支持了）

| 资源 | 类型 | 说明 |
|---|---|---|
| [Prompt 管理 - Nacos 官网](https://www.nacos.io/docs/latest/manual/user/ai/prompt-registry/) ⚠️ | 📘 | **官方功能**：集中维护、版本控制、灰度、审核（本次校验页面崩溃，浏览器可开） |
| [通过 Nacos 管理 AI Agent 的 Prompt（阿里云帮助）](https://help.aliyun.com/zh/mse/user-guide/prompt-management) ✅ | 📘 | MSE 托管 Nacos 的集中化配置管理 |
| [管微服务的 Nacos，开始管 AI 了：Agent、Skill、Prompt、MCP](https://juejin.cn/post/7682948530696863798) ✅ | 💻 | **你熟悉的服务发现/版本管理/命名空间隔离全部能复用** |
| [基于 Nacos 实现高效 Prompt 管理](https://devpress.csdn.net/avi/6a21c41b662f9a54cb79a74a.html) ✅ | 💻 | Nacos vs Apollo vs Consul 对比 |

> 💡 **对你最友好的一点**：Nacos 你大概率已经在用了。**Prompt 注册中心 = 你已有的配置中心经验直接迁移**，不需要学新东西。

---

## 推荐先 clone 的 4 个仓库

| 仓库 | 用途 |
|---|---|
| [alibaba/spring-ai-alibaba](https://github.com/alibaba/spring-ai-alibaba) ✅ | Agent 编排 + Context Engineering + HITL，国内首选 |
| [zengzeliang/spring-mcp-example](https://github.com/zengzeliang/spring-mcp-example) ✅ | MCP Server/Client 完整范例，**阶段三直接改这个** |
| [xiaomozhang/java-ai-engineer-roadmap](https://github.com/xiaomozhang/java-ai-engineer-roadmap) ✅ | Java AI 转型路线图 + Spring AI + Ollama 完整 Demo（含 RAG 问答接口） |
| [Liu-creators/VirtualThread](https://github.com/Liu-creators/VirtualThread) ✅ | 虚拟线程 pinning 与 ThreadLocal 内存问题实验 |

---

## 使用建议

1. **每个知识点配置固定**：先 📘 官方文档读 20 分钟 → 再 💻 抄一个 Demo 跑通 → 然后**故意改坏它**（改错参数、断网、塞超长文本）看怎么失败。
2. **不要收藏即学会**。本清单 84 个可访问链接，**真正需要精读的不超过 20 个**，其余是查阅用的备查资料。
3. **中文教程滞后警告（已实测确认）**：Spring AI 稳定版已是 **2.0.1（需 Spring Boot 4.0/4.1 + Java 21）**，但大量中文博客仍停留在 1.0 写法，甚至官方项目页的 Getting Started 示例链接还指向 Spring Boot 3.3.4。**遇到 `@Bean` 报错，第一件事是回 [官方 Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) 核对版本**。
4. **阶段三开始就可以投简历了**，不必等 10 周做完。原话：「先做一个能放进简历里的项目，能讲清楚里面的取舍和坑，再去投几个岗位试试市场反馈。」

---

## 数据来源与校验说明

- **校验方式**：Playwright + 真实 Chrome，逐条 `goto` + 抓取正文长度，判定 `2xx/3xx && body>200 字符` 为通过。
- **校验结果**：128 条候选 → **84 条通过**，44 条失败。
- **失败原因分布**（**重要，不要误判为死链**）：
  - `403 + len=17`（**最多**）→ CSDN / 知乎**反爬拦截**，浏览器正常打开
  - `ERR_SSL_BAD_RECORD_MAC_ALERT` → 本机到该站的 TLS 握手被拦（CSDN、知乎、jishuzhan 部分节点）
  - `Page crashed` / `len=0` → 页面过重或站内渲染失败（阿里云 SCA、Nacos 官网、部分掘金页）
  - 仅 `rsumner.com`（证书过期）为**真实问题**，已从清单剔除
- 原始数据：`resources-batch1.json`、`resources-batch2.json`、`resource-check.json`

*相关文件：`Java开发者-AI-Agent转型路线.md`（周计划正文）、`AI-Agent应用岗知识要点-重要度排序.md`（通用知识优先级）*
