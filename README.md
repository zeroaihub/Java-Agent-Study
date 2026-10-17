<div align="center">

# Java Agent Study

**从 Java 开发者到 AI Agent 工程师的 30 天实战之路**

一个面向企业级落地的 AI Agent 系统学习项目，涵盖 LLM API、Function Calling、Memory、RAG、Workflow、MCP、Multi-Agent 等核心能力，最终构建完整的 **ZeroHub AI Agent Platform**。

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-green)
![Modules](https://img.shields.io/badge/Modules-17-blue)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

---

## 项目简介

本项目是一个 **Java 开发者系统转型 AI Agent 工程师** 的完整学习路线与实战源码集合。

> 目标不是研究大模型算法，而是成为能够独立设计、开发、部署和商业化 AI Agent 的高级工程师。

每一天的学习都以 **独立模块** 形式存在，可独立运行、独立理解，最终汇聚为一个可商业化的 AI Agent 平台。

### 最终目标

- AI Agent 架构师
- 开发自己的 AI SaaS
- 建立自己的 AI Agent 平台
- 接企业 AI 项目
- 创业

---

## 技术栈

### 已引入并实际使用

| 类别 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 4.1.0 |
| AI 框架 | Spring AI 2.0.0（`spring-ai-starter-model-openai`） |
| 构建工具 | Maven（单模块工程） |
| Web | Spring MVC + WebFlux（`WebClient` 手写调用链路） |
| 浏览器自动化 | Playwright Java 1.49.0 |
| 文档处理 | Apache POI 5.3.0（docx / xlsx / pptx）、Apache PDFBox 3.0.4 |
| 邮件 | spring-boot-starter-mail（SMTP） |
| 工具库 | Lombok、fastjson2、Jackson |
| LLM 接口 | OpenAI Compatible API（LM Studio / DeepSeek / 通义等） |
| 前端 | `static/` 下手工 HTML + JS 演示页（chat / stream） |

### 文档中规划、代码尚未落地

以下技术在各模块 `docs/` 中被反复引用，但**当前仓库中并没有实际依赖或实现**，阅读时请注意区分"教程目标"与"当前代码"：

- PostgreSQL + pgvector（向量检索目前是**内存实现** `InMemoryVectorStore`，重启即丢）
- Redis（会话/记忆存储目前是 `InMemorySessionStore`）
- Spring Modulith（模块物理隔离尚未引入，所有 Day 模块共享同一 Spring 上下文）
- LangChain4j
- Docker / Kubernetes（**仓库中无任何 Dockerfile、docker-compose、K8s 清单**）
- Testcontainers（**全仓库目前 0 个测试文件**）

---

## 项目结构

```
Java-Agent-Study/
├── src/main/java/com/zero/ai/agentstudy/
│   ├── back/                        # 早期实验代码（API原理/流式/结构化输出 8 个 Demo）
│   ├── day01foundation/             # Day1  - AI基础 & 第一个LLM Demo
│   ├── day02llmapi/                 # Day2  - LLM API & 企业级Chat服务
│   ├── day3funcall/                 # Day3  - Function Calling & Tool Calling
│   ├── day4memory/                  # Day4  - Memory & 会话管理（8 章渐进）
│   ├── day05rag/                    # Day5  - RAG & 企业知识库
│   ├── day06workflow/               # Day6  - Workflow Engine
│   ├── day07mcp/                    # Day7  - MCP协议
│   ├── day08multiagent/             # Day8  - Multi-Agent协作
│   ├── day09browseragent/           # Day9  - Browser Agent & 浏览器自动化
│   ├── day10planningagent/          # Day10 - Planning Agent & 自主规划
│   ├── day11humanintheloop/         # Day11 - Human-in-the-loop 人工审批
│   ├── day12longrunningagent/       # Day12 - Long Running Agent 长生命周期运行时
│   ├── day13officeagent/            # Day13 - AI Office Agent 办公自动化
│   ├── day14wechatagent/            # Day14 - Enterprise AI WeChat Agent
│   ├── day15knowledgeplatform/      # Day15 - 企业知识平台（13 模块 + 14 章，全链路可跑）
│   ├── day16knowledgeagentv2/       # Day16 - 企业知识智能体 V2（演示）
│   ├── day17codeagent/              # Day17 - Code Agent（19 包 + 20 章 + 18 个可运行 Demo）
│   └── AgentStudyApplication.java
├── src/main/resources/
│   ├── application.yml              # 统一配置（所有模块共享）
│   └── static/                      # 前端演示页面
├── website/                         # 占位目录（暂未使用）
├── pom.xml
└── README.md
```

每个 Day 模块内部结构：

```
dayXX/
├── controller/     # API 入口
├── service/        # 业务逻辑
├── config/         # 配置类
├── dto/            # 数据传输对象
├── entity/         # 实体
├── tool/           # Agent 工具
└── docs/           # 学习文档
    ├── README.md
    ├── ARCHITECTURE.md
    ├── TODO.md
    └── chapters/   # 逐章学习记录
```

> 较新的模块（Day11 起）普遍采用 **DDD + 六边形架构**，包结构演进为 `domain/ → application/ → infrastructure/ → web/`，并大量使用强类型 ID 与领域事件。

---

## 学习路线（30 天）

### 已完成（Day01 – Day14）

| Day | 主题 | 核心内容 | 状态 |
|-----|------|----------|------|
| 01 | Foundation | AI发展、LLM原理、Java AI环境、第一个LLM Demo | ✅ |
| 02 | LLM API | Chat Completion、Streaming(SSE)、Spring AI、企业级Chat服务 | ✅ |
| 03 | Function Calling | Tool Calling、Java Tool注册、Agent调用工具 | ✅ |
| 04 | Memory | Chat Memory、Long Memory、Session、User Profile、Memory Agent | ✅ |
| 05 | RAG | Embedding、Chunk、Vector Store、Retriever、企业知识库 | ✅ |
| 06 | Workflow | Workflow Engine、Node、Context、Executor、企业Workflow | ✅ |
| 07 | MCP | MCP Client/Server、Protocol、Tool Registry、Workflow + MCP | ✅ |
| 08 | Multi-Agent | Coordinator、Planner、Research/Writer/Reviewer Agent、共享Memory | ✅ |
| 09 | Browser Agent | Playwright、网页自动操作、浏览器Tool | ✅ |
| 10 | Planning Agent | Goal拆解、DAG调度、Reflection、Self-Correction | ✅ |
| 11 | Human-in-the-loop | 审批状态机、中断/恢复、检查点、多级会签、反馈学习 | ✅ |
| 12 | Long Running Agent | Agent Runtime、生命周期状态机、任务队列、死信、崩溃恢复 | ✅ |
| 13 | AI Office Agent | Document IR、Word/Excel/PPT/PDF 渲染、邮件、日历、模板 | ✅ |
| 14 | WeChat Agent | 企微 Webhook 验签、幂等、消息路由、命令、通知、长流程编排 | ✅ |

### 骨架 / 演示阶段（Day15 – Day16）

> 这两个模块**文档先行、代码待补**：教程已完整，`docs/` 里描述的多数模块与 API 尚未实现。

| Day | 主题 | 核心内容 | 状态 |
|-----|------|----------|------|
| 15 | Knowledge Platform | 多租户、Document Pipeline、Hybrid Search、Reranker、ACL | ✅ 13 个模块全部实现（第 1~14 章），11 个可运行 Demo；缺 `API.md` 等配套文档 |
| 16 | Knowledge Agent V2 | 检索规划、RRF 融合、引用溯源、接地校验、评测、可观测 | 🧩 以独立可运行的 `ChapterNNDemo` 演示类为主，无 Controller |

### 已完成（Day17）

| Day | 主题 | 核心内容 | 状态 |
|-----|------|----------|------|
| 17 | Code Agent | VFS 唯一写入口、工具系统、静态分析与 RepoMap、上下文预算、SEARCH/REPLACE 补丁、命令沙箱、编译 / 测试验证、自修复三重刹车、安全护栏、规则评审、测试生成、GitOps、Pass@k 评测、Trace 与预算护栏、编排与 REST API | ✅ 19 个包全部实现：20 章教程 + 18 个可运行 Demo + 8 份配套文档（README / ARCHITECTURE / API / DATABASE / DEPLOY / SECURITY / EVALUATION / .env.example） |

### 规划中（Day18 – Day30）

| Day | 主题 | 核心内容 | 状态 |
|-----|------|----------|------|
| 18 | AI IDE | 智能开发环境 | 🔲 |
| 19 | SQL Agent | 自然语言转SQL | 🔲 |
| 20 | Data Analysis Agent | 数据分析 | 🔲 |
| 21 | AI Customer Service | 智能客服 | 🔲 |
| 22 | AI Search Agent | 智能搜索 | 🔲 |
| 23 | Agent Memory Optimization | 记忆优化 | 🔲 |
| 24 | Multi-Modal Agent | 多模态 | 🔲 |
| 25 | Agent Observability | 可观测性 | 🔲 |
| 26 | Agent Security | 安全 | 🔲 |
| 27 | Agent Gateway | 网关 | 🔲 |
| 28 | Docker + K8s | 容器化部署 | 🔲 |
| 29 | AI Trading Agent | Binance + Freqtrade | 🔲 |
| 30 | ZeroHub Platform | 最终平台整合 | 🔲 |

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- [LM Studio](https://lmstudio.ai/)（本地模型推理）或任意 OpenAI 兼容 API 服务

### 1. 克隆项目

```bash
git clone git@github.com:zeroaihub/Java-Agent-Study.git
cd Java-Agent-Study
```

### 2. 配置模型服务

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  ai:
    openai:
      base-url: http://127.0.0.1:1234   # LM Studio 或你的 API 地址
      api-key: sk-1234                   # 本地模型可随意填写
      chat:
        options:
          model: your-model-name         # 替换为你加载的模型
```

Day05 RAG 还需单独配置 Embedding 模型（与聊天模型不同）：

```yaml
rag:
  embedding:
    base-url: http://127.0.0.1:1234
    api-key: sk-1234
    model: text-embedding-nomic-embed-text-v1.5
```

### 3. 启动项目

```bash
mvn spring-boot:run
```

### 4. 访问接口

项目启动后访问 `http://localhost:8080`，各模块 API 路径见下方接口速查表。

前端演示页面：
- 聊天界面：`http://localhost:8080/chat.html`
- 流式输出：`http://localhost:8080/stream.html`

---

## 接口速查表

所有模块**共享同一个 Spring 上下文与 8080 端口**，启动时全部接口一次性可用。

| 模块 | 前缀 | 说明 |
|------|------|------|
| back | `/bot`、`/demo1` ~ `/demo8` | 早期实验：API 原理、流式、Token 计费、结构化输出 |
| Day01 | `/api/day01/chat` | 最简对话 |
| Day02 | `/api/day02` | Chat / 流式 / 多轮 |
| Day03 | `/day3` | 工具调用、Agent 循环 |
| Day04 | `/api/day4/chapter1` ~ `chapter8` | 记忆 8 章逐章接口 |
| Day05 | `/api/day05/rag/mycode` | ingest / ask / clear |
| Day06 | `/day6/travel` | 旅行工作流 |
| Day07 | `/api/mcp`、`/api/mcp/workflow` | MCP 工具列表 / 调用 / 工作流 |
| Day08 | `/day08/content` | 多智能体写作流水线 |
| Day09 | `/day09/browser` | 打开/点击/填表/截图/抽取 |
| Day10 | `/api/day10/planning` | 自主规划 run / demo |
| Day11 | `/day11/approvals`、`/day11/multi-approvals`、`/day11/feedbacks`、`/day11/approval-center`、`/day11/erp` | 审批、多级会签、反馈、审批看板、ERP 实战 Demo |
| Day12 | `/api/agent` | 长任务触发、会话查询、指标、死信、调度暂停恢复 |
| Day14 | `/api/webhook`、`/api/notifications`、`/admin`、`/api/agent` | 企微回调、通知、管理后台、用例编排 |

> **注意**：Day12 与 Day14 都使用了 `/api/agent` 前缀（方法级子路径不同，不会冲突，但联调时需留意）。Day13、Day15、Day16 当前**没有对外 HTTP 接口**。

---

## 模块说明

### Day01 - Foundation（AI 基础）

AI 发展史、LLM 核心概念、Java AI 开发环境搭建、使用 Spring AI `ChatClient` 完成第一个 LLM 对话 Demo。

### Day02 - LLM API（大模型接口）

深入 Chat Completion API 协议，实现流式输出（SSE）、多轮对话、Token 计费、结构化输出，构建企业级 Chat 服务。

### Day03 - Function Calling（工具调用）

实现 LLM 自主调用 Java 方法：Tool 注册中心、参数解析、执行引擎、Assistant 多轮工具调用循环。

### Day04 - Memory（记忆系统）

8 个章节渐进式实现：基础对话记忆 → 滑动窗口 → Token 截断 → 摘要压缩 → 长期记忆 → 用户画像 → 记忆检索 → 完整 Memory Agent。

### Day05 - RAG（检索增强生成）

从零实现 RAG 全链路：文档分块 → Embedding 向量化 → 向量存储 → 相似度检索 → Prompt 注入 → LLM 生成。

模块内**存在两套并行实现**，学习时请注意区分：

- **顶层教学版**（`day05rag/service/`）：纯 Java、自带 `main()`，使用 `HashEmbeddingClient`（哈希伪向量）+ `EchoLlmClient`，**零依赖即可跑通**，用于理解原理。
- **mycode 实战版**（`day05rag/mycode/`）：Spring Bean，调用真实 `/v1/embeddings` 与 `ChatModel`，带 Top-3 截断、相似度阈值 0.2、无命中时明确"不编造"。

本模块未使用 Spring AI 的 `VectorStore`，向量化与向量库均为自研。

### Day06 - Workflow（工作流引擎）

设计并实现 Agent Workflow 引擎：节点抽象、上下文传递、指数退避重试、耗时统计、执行日志。执行模式为**顺序责任链**（非 DAG），节点可返回 `SUSPENDED` 挂起。

### Day07 - MCP（模型上下文协议）

实现 MCP Client/Server 通信协议（手写 JSON-RPC）、Tool Registry 动态注册、Workflow 与 MCP 联动。当前传输层为 `InProcessTransport` **进程内直连**，尚未实现真实的 stdio / HTTP 跨进程通信。

### Day08 - Multi-Agent（多智能体协作）

构建 Coordinator 调度器 + Planner 规划器 + 多专职 Agent（Research / Writer / Reviewer / Translator），实现共享 Memory 与协作消息传递。

### Day09 - Browser Agent（浏览器智能体）

基于 Playwright 实现浏览器自动化：`BrowserSession` 会话管理、`ContextPool` 连接池、`PlaywrightEngine` 执行引擎、`BrowserTools` 工具集（以 `@Tool` 暴露给模型）。

### Day10 - Planning Agent（规划智能体）

Agent 从「被驱动」走向「自主」的分水岭。实现完整 Planning Engine：Goal 目标建模 → `LlmPlanner` 动态规划 → `DagScheduler` 依赖调度（含环检测）→ `StepExecutor` 逐步执行 → `ToolSelector` 工具选择 → `LlmReflector` 反思与自我修正（CONTINUE / RETRY_STEP / REPLAN / ABORT 四分支）→ 预算护栏。

### Day11 - Human-in-the-loop（人机协同）

给「自动执行」链路装上安全阀门，是一层**横切能力**：任何节点都可在执行前/执行中挂起，交人类决策后恢复。

核心是 `ApprovalStateMachine`（`EnumMap` 转移表驱动）+ 可插拔 `RiskPolicy`，配套：

- `InterruptManager` / `ResumeEngine` —— 中断与精确恢复
- `CheckpointManager` —— 执行现场快照、可回滚可续跑
- `multilevelapproval/` —— 多级会签、超时策略
- `feedbackengine/` —— 人类决策沉淀为可学习数据（FewShot）
- `erpdemo/` —— 「批量删除测试订单」实战场景，演示拦截不可逆操作

### Day12 - Long Running Agent（长生命周期 Agent）

把「一次性 ChatBot」升级为**有状态、可恢复、可调度、可观测的运行时**。借鉴操作系统进程管理与工作流引擎范式：

`AgentStateMachine`（生命周期状态机）+ 任务队列 + 重试策略 + 事件总线 + `Checkpoint` 崩溃恢复 + `DeadLetterQueue` + 定时调度 + 指标监控。附带「GitHub Trending 每日巡检」完整示例。

### Day13 - AI Office Agent（办公自动化）

把前 12 天的技术原语拼装成**能产生业务价值的产品**：输入一句自然语言，输出可交付的 Word / Excel / PPT / PDF / 邮件 / 会议纪要。

设计亮点是 **Document IR（文档中间表示）**：

- `DocumentIR` + `sealed interface Block`（Heading / Paragraph / List / Table / Chart / Image / PageBreak）—— 内容与格式彻底解耦
- 一份 IR 由 `WordRenderer`（POI）、`ExcelRenderer`（POI）、`PptRenderer`（XSLF）、`PdfRenderer`（PDFBox）分别投影
- LLM 侧用 `ChatClient.entity(DocumentIR.class)` **一步产出结构化文档树**
- 出站端口化：`ModelPort` / `MailSender` / `FileStorage` / `CalendarPort` / `TemplateEngine` / `TaskRepository`

### Day14 - Enterprise AI WeChat Agent（企业微信智能体）

给 Agent 装上「企业 IM 入口」，只用企业微信官方 API / 群机器人 Webhook，不碰个人微信 hook。规模最大的模块（90 个文件，11 个子包），工程严格度最高：

- `wechatcore` —— 消息领域模型、渠道端口 `MessageChannel`、`IdempotencyStore`
- `wechatsession` —— 会话管理 + 状态机 + 过期调度
- `wechatrouter` —— 责任链路由（命令 → 多模态 → Chat → Echo，按 order 排序）
- `wechatcommand` —— 命令系统（`/help`、`/reset`、`/whoami`）
- `wechatnotify` —— 通知中心（企微 / 邮件 / Webhook 三种出站）
- `wechatworkflow` —— 长流程编排，支持步骤挂起与 `approve` 续跑
- `wechatapi` —— 入站适配器，**HMAC-SHA256 验签 + eventId 幂等去重**
- 终极场景 `MeetingSummaryOrchestrator`：企微消息 → 规划 → 生成纪要 → 上传归档 → 存知识库 → 人工审批 → 发通知

已知占位：真实企微 AES 加解密回调未实现（仅通用 HMAC 验签）；`ChatHandler` 为 TODO，尚未接入 LLM。

### Day15 - Enterprise AI Knowledge Platform（企业知识平台）

把 Day05 的「玩具 RAG」升级为**多租户、可商业化 SaaS 的企业知识平台**。

**当前进度：第 1~14 章全部完成，13 个业务模块全部实现，并配了 11 个零依赖可运行的 Demo。**
完整链路可离线跑通：`上传 → 解析 → 切分 → 向量化 → 双索引入库 → 权限收敛 → 混合召回 → 精排 → 去冗余 → 结果`。

| 模块 | 章节 | 要点 |
| --- | --- | --- |
| `core/` | 第 1 章 | 强类型 ID、`TenantContext`、统一异常/结果/事件 |
| `document/` | 第 3 章 | `Document` 聚合根、`DocumentStateMachine`（`EnumMap` 转移表）、版本、领域事件 |
| `parser/` | 第 4 章 | 策略模式 + `ParserRegistry`，真实解析 **PDF / DOCX / XLSX / PPTX / HTML / MD / TXT**，OCR 走端口 |
| `chunk/` | 第 5 章 | 定长 / 语义 / 结构化三种策略，`Chunk` 自带租户与知识库维度（供检索下推） |
| `embedding/` | 第 6 章 | `EmbeddingVector` 携带 model/dimension，**跨模型比较 fail-fast**（向量漂移防线）；批处理 + 批内去重缓存 |
| `retriever/` | 第 7 章 | 向量 + BM25 双索引、**RRF 融合**（k=60）、虚拟线程并行、优雅降级、`KnowledgeIndexer` 保双写一致 |
| `reranker/` | 第 8 章 | 直通 / 启发式 / CrossEncoder 三种实现 + **MMR 多样性去重** |
| `workspace/` | 第 9 章 | Tenant → Workspace → KnowledgeBase 三层，租户只从上下文取，仓储复合键防越权 |
| `acl/` | 第 10 章 | 默认拒绝、跨租户校验（防 IDOR）、**权限前置到召回**（`resolveReadableScope`） |
| `sync/` | 第 11 章 | SHA-256 内容指纹、ADD/UPDATE/**DELETE** 三动作、先出计划再执行 |
| `browser/` | 第 12 章 | 抓取失败是返回值不是异常，与上传汇入同一条入库链路 |
| `api/`、`application/` | 第 13 章 | `KnowledgePlatformFacade` 门面收口、**先删再写**、`reingest` 表达更新语义、citation 溯源 |
| `admin/` | 第 14 章 | 职责分离、授权留痕、平台统计、**索引一致性监控** |

第 4 章 Demo 会用 POI/PDFBox 先生成真实的 docx/xlsx/pptx/pdf 再解析回来，完整闭环；
第 13 章 Demo 会跑通「入库 → 权限隔离 → 删除同步 → 更新不同步」四个关键场景。

**尚未产出**：`API.md` / `DATABASE.md` / `DEPLOY.md` / `TESTING.md` / `adr/` / `ROADMAP.md` / `TODO.md`。
生产化替换清单见 **第 14 章第六节**（10 条，优先级：租户来源 → 持久化与索引 → 真实 Embedding + minScore）。

### Day16 - Enterprise Knowledge Agent V2（企业知识智能体 V2）

在 Day15 之上补齐企业 RAG 的 10 个致命短板：Knowledge Routing、Query Rewrite、Hybrid Search、Reranker、权限前置、Citation / Grounding / Verification、Knowledge Gap、版本、Evaluation、Observability。

当前进度：以**独立可运行的 `ChapterNNDemo` 演示类**为主，覆盖

- `hybrid/` —— `HybridRetriever` 用 **RRF 融合**（k=60）合并多路召回
- `grounding/` —— 引用抽取 + 接地判定 + 置信度（用词项重合度替代 NLI 模型）
- `acl/` `security/` `tenant/` —— `AccessPolicy`、`TenantGuard`、`PiiRedactor`、`PromptInjectionGuard`（防注入 + PII 脱敏）
- `eval/` —— `Evaluator` + HitRate / AnswerRelevancy 指标
- `metering/` `observability/` `perf/` —— 配额护栏、Trace 采集、并行检索与租户隔离缓存

`docs/` 是本模块最完整的部分（README / ARCHITECTURE / API / DATABASE / DEPLOY / EVALUATION / SECURITY + 20 章）。

### Day17 - Code Agent（代码生成与执行）

把 LLM 从「聊天玩具」推进到「能真正改动代码库并通过验证的工程角色」：**读仓库 → 选址 → 生成补丁 → 安全审查 → 应用 → 编译 → 测试 → 自修复 → 评审 → 变更集 → PR**。

当前进度：**19 个包全部实现**，20 章教程 + **18 个零依赖可运行 Demo**（`Chapter03Demo` ~ `Chapter20Demo`，全部 `exit=0`）+ 8 份配套文档。

- `repo/` —— `VirtualFileSystem` 唯一写入口；`Path.startsWith` 根校验（不用字符串前缀）+ 内容指纹（SHA-256）
- `tools/` —— 五个工具（list/read/search/write/run），**工具失败是返回值不是异常**
- `analysis/` —— 零依赖解析器（等长空格清洗）+ 符号表 + 依赖图 + RepoMap（102 行 → 552 token）
- `context/` —— 四路加权打分 + `FULL → SIGNATURE → 丢弃` 三级降级，输出预留强制 > 0
- `patch/` —— SEARCH/REPLACE 补丁，四级匹配，**打不上必须明确失败**；脏输出 / Unified Diff 双解析
- `sandbox/` —— 白名单 + 参数黑名单 + 超时 + 环境隔离；`timedOut` 与 `exitCode` 分离
- `verify/` —— `javax.tools.JavaCompiler` 结构化诊断（整仓 438ms / 片段 29ms）+ 沙箱内 `-ea` 跑测试
- `iterate/` —— 自修复三重刹车（轮次 / 错误签名 / 内容指纹），实测省 80% 成本
- `safety/`、`review/`、`testgen/`、`gitops/`、`eval/`、`observability/`、`metering/` —— 安全护栏、只评审增量的规则评审、测试生成、变更集与 PR 草稿、Pass@k 评测、Trace 与预算护栏
- `orchestrator/`、`api/` —— 12 步流水线 + REST API（**租户只从 Header 注入**，Body 不含身份字段）

真实可复现的数据：整文件重写 vs 补丁 **191 vs 88 token（省 53.9%）**；`Pass@1=80% → Pass@2=100%`（`maxRepairRounds=3` 的依据）；编译验证是瓶颈（占步骤耗时 83%）。

---

## 当前实现状态说明

这一节是为了让阅读代码的人**准确分辨"教程目标"与"当前代码"**，避免被文档误导。

1. **共享上下文**：18 个模块不是 Maven 多模块工程，而是同一工程下按包名划分，共享一份 `application.yml` 与 8080 端口。
2. **存储均为内存实现**：向量库（`InMemoryVectorStore`）、会话（`InMemorySessionStore`）、审批仓储、知识库全部是 `Map` 实现，**进程重启即丢失**。
3. **零测试**：仓库中尚无 `src/test`，但 `pom.xml` 已引入 `spring-boot-starter-test`。
4. **存在 Mock / 占位实现**：`EchoLlmClient`、`HashEmbeddingClient`、`MockLlmClient`、`TrendingStubClient`、`MockMessageChannel`、`InProcessTransport`、`HashingEmbeddingProvider`，以及 Day16 用「词项重合度」替代 NLI 模型。这些是为「零依赖跑通」刻意设计的，**不是生产实现**。
5. **概念存在重复实现**：状态机三处独立实现（Day11 `ApprovalStateMachine`、Day12 `AgentStateMachine`、Day06 / Day14 的 `WorkflowState`）；工作流引擎三套（Day06 / Day10 / Day14）；Embedding 三套。尚未抽取公共抽象。
6. **命名风格不统一**：`day3funcall`（缺 0）与 `day05rag`、`day11humanintheloop` 混用。
7. **待清理项**：`website/` 仅为占位空目录；`src/main/resources/tempfile_*.mermaid` 为误提交的临时文件。

---

## 学习文档

每个模块的 `docs/` 目录下包含完整学习文档：

- **README.md** — 完整教程（5000+ 字），可脱离聊天记录独立学习
- **ARCHITECTURE.md** — 系统架构图、流程图、模块说明、设计决策
- **TODO.md** — 必做练习 / 进阶挑战 / 企业级挑战
- **chapters/** — 逐章节 Markdown 学习记录
- Day14 额外提供 `API.md` / `DATABASE.md` / `DEPLOY.md` / `TESTING.md` / `ROADMAP.md` / `CHANGELOG.md` / `ADR/`（架构决策记录）
- Day16 额外提供 `EVALUATION.md` / `SECURITY.md`
- Day17 额外提供 `API.md` / `DATABASE.md` / `DEPLOY.md` / `SECURITY.md` / `EVALUATION.md` / `.env.example`

---

## 设计理念

- **Java First** — 所有核心实现均为 Java / Spring Boot，Python 仅辅助阅读
- **企业级** — 不是玩具 Demo，而是面向生产环境的工程实践
- **渐进式** — 每天一个独立模块，由浅入深，前后衔接
- **可运行** — 所有代码均可独立编译运行，即学即用
- **文档完备** — 每个模块都配自包含教程，可脱离聊天记录独立学习
- **商业化导向** — 最终目标是构建可落地的 AI Agent 平台

---

## 适合人群

- 有 Java / Spring Boot 经验，想转型 AI Agent 方向的开发者
- 想系统学习 AI Agent 工程化落地的后端工程师
- 对 AI Agent 架构设计感兴趣的技术负责人
- 希望构建自己 AI SaaS / Agent 平台的创业者

---

## 许可证

[MIT License](LICENSE)

---

<div align="center">

**如果这个项目对你有帮助，请给一个 Star ⭐**

*Built with Java, Spring Boot & Spring AI*

</div>
