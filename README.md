<div align="center">

# Java Agent Study

**从 Java 开发者到 AI Agent 工程师的 30 天实战之路**

一个面向企业级落地的 AI Agent 系统学习项目，涵盖 LLM API、Function Calling、Memory、RAG、Workflow、MCP、Multi-Agent 等核心能力，最终构建完整的 **ZeroHub AI Agent Platform**。

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-green)
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

| 类别 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 4.1.0 / Spring AI 2.0.0 |
| AI 框架 | Spring AI / LangChain4j |
| 构建工具 | Maven |
| 数据库 | PostgreSQL + pgvector |
| 缓存 | Redis |
| 容器化 | Docker |
| 浏览器自动化 | Playwright |
| 协议 | MCP (Model Context Protocol) |
| LLM 接口 | OpenAI Compatible API (LM Studio / DeepSeek / 通义等) |
| 辅助语言 | Python（仅用于阅读开源项目） |

---

## 项目结构

```
Java-Agent-Study/
├── src/main/java/com/zero/ai/agentstudy/
│   ├── day01foundation/       # Day1  - AI基础 & 第一个LLM Demo
│   ├── day02llmapi/           # Day2  - LLM API & 企业级Chat服务
│   ├── day3funcall/           # Day3  - Function Calling & Tool Calling
│   ├── day4memory/            # Day4  - Memory & 会话管理
│   ├── day05rag/              # Day5  - RAG & 企业知识库
│   ├── day06workflow/         # Day6  - Workflow Engine
│   ├── day07mcp/              # Day7  - MCP协议
│   ├── day08multiagent/       # Day8  - Multi-Agent协作
│   ├── day09browseragent/     # Day9  - Browser Agent & 浏览器自动化
│   ├── day10planningagent/    # Day10 - Planning Agent & 自主规划
│   ├── back/                  # 早期实验代码（API原理/流式/结构化输出）
│   └── AgentStudyApplication.java
├── src/main/resources/
│   ├── application.yml        # 统一配置
│   └── static/                # 前端演示页面
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

---

## 学习路线（30 天）

### 已完成

| Day | 主题 | 核心内容 | 状态 |
|-----|------|----------|------|
| 01 | Foundation | AI发展、LLM原理、Java AI环境、第一个LLM Demo | ✅ |
| 02 | LLM API | Chat Completion、Streaming、Spring AI、企业级Chat服务 | ✅ |
| 03 | Function Calling | Tool Calling、Java Tool注册、Agent调用工具 | ✅ |
| 04 | Memory | Chat Memory、Long Memory、Session、User Profile、Memory Agent | ✅ |
| 05 | RAG | Embedding、Chunk、Vector Store、Retriever、企业知识库 | ✅ |
| 06 | Workflow | Workflow Engine、Node、Context、Executor、企业Workflow | ✅ |
| 07 | MCP | MCP Client/Server、Protocol、Tool Registry、Workflow + MCP | ✅ |
| 08 | Multi-Agent | Coordinator、Planner、Research/Writer/Reviewer Agent、共享Memory | ✅ |
| 09 | Browser Agent | Playwright、网页自动操作、浏览器Tool | ✅ |
| 10 | Planning Agent | Goal拆解、DAG调度、Reflection、Self-Correction、自主规划引擎 | ✅ |

### 进行中 / 规划中

| Day | 主题 | 核心内容 | 状态 |
|-----|------|----------|------|
| 11 | Human-in-the-loop | 人工审批、Agent中断恢复 | 🔲 |
| 12 | Long Running Agent | 事件驱动、状态恢复 | 🔲 |
| 13 | AI Office Agent | 办公自动化 | 🔲 |
| 14 | AI WeChat Agent | 微信机器人 | 🔲 |
| 15 | AI Knowledge Platform | 知识平台 | 🔲 |
| 16 | 企业知识库 V2 | 知识库Agent进阶 | 🔲 |
| 17 | Code Agent | 代码生成与执行 | 🔲 |
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
git clone git@github.com:your-username/Java-Agent-Study.git
cd Java-Agent-Study
```

### 2. 配置模型服务

编辑 `src/main/resources/application.yml`：

```yaml
ai:
  provider:
    base-url: http://127.0.0.1:1234   # LM Studio 或你的 API 地址
    api-key: sk-1234                   # 本地模型可随意填写
    model: your-model-name             # 替换为你加载的模型
```

### 3. 启动项目

```bash
mvn spring-boot:run
```

### 4. 访问接口

项目启动后访问 `http://localhost:8080`，各模块 API 路径见对应模块文档。

前端演示页面：
- 聊天界面：`http://localhost:8080/chat.html`
- 流式输出：`http://localhost:8080/stream.html`

---

## 模块说明

### Day01 - Foundation（AI 基础）

AI 发展史、LLM 核心概念、Java AI 开发环境搭建、使用 Spring AI ChatClient 完成第一个 LLM 对话 Demo。

### Day02 - LLM API（大模型接口）

深入 Chat Completion API 协议，实现流式输出（SSE）、多轮对话、Token 计费、结构化输出，构建企业级 Chat 服务。

### Day03 - Function Calling（工具调用）

实现 LLM 自主调用 Java 方法：Tool 注册中心、参数解析、执行引擎、Assistant 多轮工具调用循环。

### Day04 - Memory（记忆系统）

8 个章节渐进式实现：基础对话记忆 → 滑动窗口 → Token 截断 → 摘要压缩 → 长期记忆 → 用户画像 → 记忆检索 → 完整 Memory Agent。

### Day05 - RAG（检索增强生成）

从零实现 RAG 全链路：文档分块 → Embedding 向量化 → 向量存储 → 相似度检索 → Prompt 注入 → LLM 生成，构建企业知识库问答。

### Day06 - Workflow（工作流引擎）

设计并实现 Agent Workflow 引擎：节点抽象、上下文传递、条件分支、并行执行、工具节点集成。

### Day07 - MCP（模型上下文协议）

实现 MCP Client/Server 通信协议、Tool Registry 动态注册、Workflow 与 MCP 联动，支持跨进程工具调用。

### Day08 - Multi-Agent（多智能体协作）

构建 Coordinator 调度器 + Planner 规划器 + 多专职 Agent（Research / Writer / Reviewer），实现共享 Memory 与 Workflow + MCP + Multi-Agent 联合编排。

### Day09 - Browser Agent（浏览器智能体）

基于 Playwright 实现浏览器自动化：BrowserSession 会话管理、ContextPool 连接池、PlaywrightEngine 执行引擎、BrowserTools 工具集，让 Agent 能操作真实网页完成信息提取与自动操作。

### Day10 - Planning Agent（规划智能体）

Agent 从「被驱动」走向「自主」的分水岭。实现完整 Planning Engine：Goal 目标建模 → LlmPlanner 动态规划 → DagScheduler 依赖调度 → StepExecutor 逐步执行 → ToolSelector 工具选择 → Reflector 反思与自我修正 → PlanningContext 状态管理，构建企业级自主规划引擎。

---

## 学习文档

每个模块的 `docs/` 目录下包含完整学习文档：

- **README.md** — 完整教程（5000+ 字），可脱离聊天记录独立学习
- **ARCHITECTURE.md** — 系统架构图、流程图、模块说明、设计决策
- **TODO.md** — 必做练习 / 进阶挑战 / 企业级挑战
- **chapters/** — 逐章节 Markdown 学习记录

---

## 设计理念

- **Java First** — 所有核心实现均为 Java / Spring Boot，Python 仅辅助阅读
- **企业级** — 不是玩具 Demo，而是面向生产环境的工程实践
- **渐进式** — 每天一个独立模块，由浅入深，前后衔接
- **可运行** — 所有代码均可独立编译运行，即学即用
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
