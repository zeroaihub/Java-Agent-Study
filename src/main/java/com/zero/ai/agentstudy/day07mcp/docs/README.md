# Day07：MCP（Model Context Protocol）——让 Agent 连接万物

> 《30 天打造商业级 AI Agent 平台（Java 版）》 · Day07
>
> 本文是 Day07 的“总纲”文档。即使你以后完全不看聊天记录，也能仅凭本 README + `docs/chapters/*` 重新学习并复现今天的全部内容。
>
> **重要约定**：Day07 的所有代码只允许放在 `day07mcp` 目录下，绝不修改 Day01–Day06 任何代码。

---

## 目录

1. [今日学习目标](#1-今日学习目标)
2. [MCP 发展历史](#2-mcp-发展历史)
3. [MCP 解决的问题](#3-mcp-解决的问题)
4. [MCP 整体架构](#4-mcp-整体架构)
5. [MCP 协议组成](#5-mcp-协议组成)
6. [Java 实现方式](#6-java-实现方式)
7. [Python 实现方式](#7-python-实现方式)
8. [企业实践](#8-企业实践)
9. [最终项目介绍](#9-最终项目介绍)
10. [今日知识总结](#10-今日知识总结)

---

## 1. 今日学习目标

Day07 只有一个核心目标：**理解并实现一个支持 MCP 协议的 Java Agent**。

拆解成可验证的小目标：

- **认知层**：说清楚 MCP 到底是什么、为什么会出现、它和 Function Calling、HTTP 分别解决什么问题。
- **协议层**：掌握 MCP 的四大原语（Tool / Resource / Prompt / Sampling），理解它建立在 JSON-RPC 2.0 之上的请求-响应模型和生命周期。
- **工程层**：用 Java 从零实现一个最小可用的 MCP Server 和 MCP Client，能够注册工具、发现工具、调用工具、解析结果、处理错误、记录日志。
- **落地层**：实现 Weather MCP Server（天气 / 时间 / 计算器三个 Tool），并把 Day06 的 Workflow 升级为“Workflow → MCP Client → MCP Server → Tool”的解耦架构。
- **平台层**：完成最终项目 **MCP Agent V1**：Agent 自动发现 Tool、自动调用 Tool、新增 Tool 无需修改 Agent 代码、记录完整调用链日志。

学完 Day07，你应该能够回答这几个“灵魂问题”：

- 为什么 Function Calling 已经能调用工具了，还需要 MCP？
- MCP 里的 Client / Server / Transport / JSON-RPC 各自承担什么职责？
- 企业里为什么要把工具从 Agent 进程里“拆出去”变成独立的 MCP Server？
- 为什么说 MCP 有可能成为“AI 时代的 HTTP / USB-C”？

---

## 2. MCP 发展历史

**MCP（Model Context Protocol，模型上下文协议）** 是由 Anthropic 于 **2024 年 11 月** 正式开源发布的一套开放协议，目标是**标准化“大模型应用”与“外部数据、工具、上下文”之间的连接方式**。

一条简化的时间线（帮助建立心智模型）：

- **2023 年**：GPT 系列推出 Function Calling，模型第一次能“结构化地请求调用一个函数”。各大框架（LangChain、Semantic Kernel、Spring AI、LangChain4j）纷纷做了自己的 Tool 抽象。
- **问题浮现**：每个框架、每个厂商的工具定义格式都不一样。一个“查天气”的工具，在 LangChain 里写一遍、在 Spring AI 里写一遍、在 Cursor 里又写一遍，**N 个应用 × M 个工具 = N×M 份适配代码**，无法复用。
- **2024 年 11 月**：Anthropic 提出 MCP，把“应用如何连接工具/数据”抽象成一个统一协议，就像 USB-C 统一了充电口。
- **2025 年**：MCP 生态迅速扩张。Claude Desktop、Cursor、Cline、Windsurf、Zed、Codex 等 AI 客户端陆续支持 MCP；Spring AI、LangChain4j 等 Java/JVM 框架提供了 MCP 支持；OpenAI 也宣布拥抱 MCP。MCP 从“Anthropic 的协议”逐渐变成“行业事实标准候选”。

一句话概括历史意义：**Function Calling 解决了“模型能不能调工具”，MCP 解决了“工具能不能被所有应用复用”。**

---

## 3. MCP 解决的问题

### 3.1 M×N 集成爆炸问题

没有 MCP 之前：

```
应用A ──┐        ┌── 工具1
应用B ──┼── 各写各的适配 ──┼── 工具2
应用C ──┘        └── 工具3
```

每个“应用 × 工具”组合都要写一份定制集成代码。3 个应用 × 3 个工具 = 9 份适配。

有了 MCP 之后（把 N×M 降为 N+M）：

```
应用A ─┐                      ┌─ MCP Server(工具1)
应用B ─┼─ 统一 MCP 协议 ─┼─ MCP Server(工具2)
应用C ─┘                      └─ MCP Server(工具3)
```

只要应用实现一次 MCP Client、工具实现一次 MCP Server，任意应用都能用任意工具。

### 3.2 Function Calling 的三个不够

1. **不够标准**：每个框架的 Tool schema、调用方式、返回格式都不一样，无法跨框架复用。
2. **不够解耦**：工具代码往往和 Agent 编译进同一个进程，工具升级/发布必须重新部署 Agent。
3. **不够完整**：Function Calling 只覆盖“调用函数”，而真实 Agent 还需要读取资源（Resource）、复用提示词模板（Prompt）、反向请求模型采样（Sampling），MCP 把这些都规范化了。

### 3.3 企业痛点

- 工具越来越多（几十上百个），散落在各个团队，缺乏统一注册与发现机制。
- 工具的权限、审计、限流、超时无处统一治理。
- 换一个 AI 客户端（比如从内部 IDE 换到 Cursor），工具全部要重写。

MCP 用“协议 + 独立进程 + 统一原语”一次性回应了这些痛点。

---

## 4. MCP 整体架构

MCP 采用经典的 **Client-Server** 架构，并引入 **Host（宿主）** 概念：

```
+-------------------------------------------------------------+
|                          Host（宿主应用）                    |
|   例：Claude Desktop / Cursor / 你的 Java Agent 平台         |
|                                                             |
|   +-------------------+        +-------------------+        |
|   |   MCP Client A    |        |   MCP Client B    |  ...   |
|   +---------+---------+        +---------+---------+        |
+-------------|--------------------------|--------------------+
              |  JSON-RPC over Transport |
              |  (stdio / HTTP+SSE)      |
      +-------v--------+         +-------v--------+
      |  MCP Server 1  |         |  MCP Server 2  |
      |  (天气/时间)   |         |  (数据库)      |
      |  Tool/Resource |         |  Tool/Resource |
      |  /Prompt       |         |  /Prompt       |
      +----------------+         +----------------+
```

- **Host**：真正面向用户、内置大模型能力的宿主应用。一个 Host 可以持有多个 MCP Client。
- **MCP Client**：由 Host 创建，**与某一个 Server 保持 1:1 连接**，负责协议握手、能力协商、消息收发。
- **MCP Server**：独立进程（或独立服务），对外暴露 Tool / Resource / Prompt 能力。
- **Transport（传输层）**：承载 JSON-RPC 消息。本地常用 **stdio**（标准输入输出），远程常用 **HTTP + SSE / Streamable HTTP**。

关键设计原则：**Client 和 Server 一对一，Host 通过持有多个 Client 来聚合多个 Server 的能力。**

---

## 5. MCP 协议组成

### 5.1 底座：JSON-RPC 2.0

MCP 的所有消息都是 JSON-RPC 2.0 报文，分三类：

- **Request**（有 `id`，期待响应）：如 `tools/list`、`tools/call`。
- **Response**（带相同 `id`，含 `result` 或 `error`）。
- **Notification**（无 `id`，不期待响应）：如 `notifications/initialized`。

一个 `tools/call` 请求示例：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "get_weather",
    "arguments": { "city": "北京" }
  }
}
```

### 5.2 四大原语（Primitives）

| 原语 | 谁定义 | 谁控制 | 作用 |
|------|--------|--------|------|
| **Tool（工具）** | Server | 模型 | 可被模型调用的“动作/函数”，如查天气、发邮件 |
| **Resource（资源）** | Server | 应用 | 可读取的“数据/上下文”，如文件、数据库记录 |
| **Prompt（提示词）** | Server | 用户 | 预置的提示词模板/工作流 |
| **Sampling（采样）** | Client | Server 发起 | Server 反向请求 Host 的模型生成内容 |

Day07 重点掌握 **Tool**，其余作为知识补齐。

### 5.3 生命周期

```
Client                         Server
  |------ initialize --------->|   (声明协议版本、能力)
  |<----- initialize result ---|
  |--- notifications/initialized ->|  (握手完成)
  |------ tools/list --------->|   (发现工具)
  |<----- tools 列表 ----------|
  |------ tools/call --------->|   (调用工具)
  |<----- 调用结果 ------------|
  |------ (关闭连接) --------->|
```

三个阶段：**初始化（能力协商）→ 运行（发现/调用）→ 关闭**。

---

## 6. Java 实现方式

Day07用 **“不依赖外部 MCP SDK、纯手写协议”** 的方式，目的是把协议吃透（企业里也常见自研网关）。目录规范：

```
day07mcp
└── src/main/java/.../day07mcp
    ├── controller     # 对外 REST 入口
    ├── service        # 业务编排
    ├── mcp
    │   ├── protocol    # JSON-RPC 报文模型（Request/Response/Error）
    │   ├── transport   # 传输抽象（本地进程内 Transport）
    │   ├── tool        # Tool 接口与实现
    │   ├── registry    # Tool 注册表
    │   ├── server      # MCP Server（处理 initialize/tools.list/tools.call）
    │   └── client      # MCP Client（发现/调用工具，封装 SDK）
    ├── workflow        # Day06 Workflow 升级为经 MCP 调用
    ├── config          # Spring 配置
    ├── entity / dto    # 领域对象与传输对象
    ├── util            # 工具类
    └── docs            # 学习资料（本目录）
```

生产环境可选：**Spring AI MCP Starter** 或 **LangChain4j MCP 模块**，它们提供了开箱即用的 Client/Server 实现。Day07 先手写理解原理，再在文档中对照官方 SDK。

---

## 7. Python 实现方式

Python 是 MCP 官方 SDK 最成熟的语言。核心是官方 `mcp` 库（FastMCP）：

```python
# pip install mcp
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("weather-server")

@mcp.tool()
def get_weather(city: str) -> str:
    """查询指定城市天气"""
    return f"{city}：晴，26℃"

if __name__ == "__main__":
    mcp.run()  # 默认 stdio 传输
```

对比 Java：Python 用装饰器 `@mcp.tool()` 自动把函数转成 Tool schema；Java 里我们用接口 + 注册表显式注册，本质相同。每一章都会给出 Python 参考实现。

---

## 8. 企业实践

Day07 第七章会系统展开 10 个企业问题，这里先给出结论清单：

1. **不要把所有 Tool 塞进一个 Server**：按业务域拆分（天气域、订单域、数据库域），降低爆炸半径。
2. **Tool 权限**：按调用方身份（tenant / role）做工具级白名单。
3. **认证**：远程传输走 OAuth2 / API Key，stdio 走进程隔离。
4. **日志审计**：记录完整调用链（who / when / tool / args / result / 耗时）。
5. **版本兼容**：协议版本协商 + Tool schema 版本化。
6. **动态发现**：Client 每次连接都 `tools/list`，Server 支持热更新。
7. **超时**：每次 `tools/call` 设置超时与降级。
8. **负载均衡**：远程 Server 前置网关做路由与限流。
9. **多租户**：请求携带 tenant 上下文，Server 做数据隔离。
10. **监控**：暴露调用量、错误率、P99 指标。

---

## 9. 最终项目介绍

**MCP Agent V1**（第八章）目标：

- Agent 启动时通过 MCP Client 连接若干 MCP Server，自动 `tools/list` **发现全部工具**。
- 用户提出请求后，Agent 根据工具描述**自动选择并调用工具**（`tools/call`）。
- **新增一个 Tool，只需在 Server 端注册，Agent 代码零改动**（这是 MCP 解耦价值的直接体现）。
- 记录**完整调用链日志**并可分析整条链路（Agent → Client → Server → Tool → 结果）。

它把前七章的所有能力（协议、Server、Client、Weather 工具、Workflow 解耦、企业实践）串成一个能跑的最小平台。

---

## 10. 今日知识总结

- **一句话记住 MCP**：它是“AI 应用连接工具/数据的 USB-C”，用 JSON-RPC 把 N×M 集成降为 N+M。
- **和 Function Calling 的关系**：FC 是“模型请求调工具”的能力，MCP 是“工具能被所有应用复用”的协议标准，二者互补而非替代。
- **和 HTTP 的类比**：HTTP 标准化了“浏览器 ↔ 服务器”，MCP 标准化了“AI 应用 ↔ 工具/数据”。
- **四大原语**：Tool（模型可调用）、Resource（应用可读取）、Prompt（用户可复用）、Sampling（Server 反向请求模型）。
- **架构三件套**：Host 持有多个 Client，每个 Client 一对一连一个 Server，Transport 承载 JSON-RPC。
- **企业价值**：解耦、复用、统一治理（权限/审计/限流/监控）。

> 下一步：进入 `docs/chapters/chapter-01.md`，从“为什么需要 MCP”开始逐章学习。每讲完一章会暂停，等你确认后再继续。