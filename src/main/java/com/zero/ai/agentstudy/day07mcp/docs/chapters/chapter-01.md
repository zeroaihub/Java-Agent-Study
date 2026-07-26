# 第一章：为什么需要 MCP？

> 五段式教学模板：为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化。
> 本章只解决**认知**问题，不写业务代码。学完请回答章末问题：“MCP 和 HTTP 分别解决什么问题？”

---

## 第一部分：为什么学（核心价值）

### 1. 为什么会出现 MCP？

回忆 Day03 的 Function Calling：你在 `ToolRegistry` 里注册 `WeatherTool`、`CalculatorTool`，
模型返回一个“想调用 get_weather(city=北京)”的意图，你的代码去执行。**这套东西只在你这个应用里有效。**

问题来了：
- 换一个框架（Spring AI → LangChain4j），Tool 要按新格式重写。
- 换一个客户端（你的 Agent → Cursor / Claude Desktop），Tool 又要重写。
- 别的团队想复用你的“查天气”能力，只能拷贝代码。

这就是典型的 **M×N 集成爆炸**：M 个应用 × N 个工具 = M×N 份适配代码。
MCP 的出现，就是把它变成 **M+N**：应用实现一次 Client，工具实现一次 Server，任意组合。

### 2. 为什么 Function Calling 还不够？

| 维度 | Function Calling | MCP |
|------|------------------|-----|
| 定位 | 模型的一种**能力**（能请求调函数） | 应用与工具之间的**协议标准** |
| 复用 | 绑定框架/进程，难跨应用复用 | 跨应用、跨语言、跨厂商复用 |
| 解耦 | 工具常和 Agent 编译在一起 | 工具是独立进程/服务 |
| 覆盖 | 只有“调函数” | Tool + Resource + Prompt + Sampling |

一句话：**Function Calling 让模型“会调工具”，MCP 让工具“能被所有人调”。** 二者是互补关系。

### 3. 为什么 Agent 需要统一协议？

没有统一协议时，Agent 生态就像早期的充电器：诺基亚一个口、苹果一个口、安卓一个口，换设备就得换线。
USB-C 出现后，一根线通吃。MCP 就是 **AI 工具世界的 USB-C**：
Agent 只认协议，不认具体工具的实现语言和部署位置。

### 4. MCP 解决了哪些企业痛点？

- **复用难**：一份工具，处处能用，不再重复造轮子。
- **治理难**：工具独立成 Server，可以统一加权限、审计、限流、监控。
- **迭代难**：工具升级只发布 Server，Agent 不用重新部署。
- **协作难**：不同团队各自维护自己的 MCP Server，通过协议协作，边界清晰。

### 5. 为什么越来越多框架支持 MCP？

因为**网络效应**：Claude Desktop、Cursor、Cline、Windsurf、Zed 等客户端支持后，
只要你写一个 MCP Server，这些客户端都能直接用你的工具；反过来，你的 Agent 支持 MCP 后，
社区成百上千的现成 Server（文件系统、GitHub、数据库、浏览器……）你都能直接接入。
支持 MCP = 白捡整个生态。

### 6. MCP 会不会成为“AI 时代的 HTTP”？

很有可能。类比：
- HTTP 之前，网络应用各说各话；HTTP 之后，浏览器和服务器有了通用语言，Web 生态爆发。
- MCP 之前，AI 应用和工具各说各话；MCP 之后，Agent 和工具有了通用语言。

当然它还年轻（2024 年底才发布），能否成为唯一标准仍需观察，但方向和定位是清晰的。

---

## 第二部分：是什么（概念 + 底层原理）

本章先建立骨架认知，细节留到第二章深挖。

```
        MCP 一句话定义
+------------------------------------------+
| MCP = 一套基于 JSON-RPC 的开放协议，用于   |
|       标准化 AI 应用与外部工具/数据的连接  |
+------------------------------------------+

核心角色（先记住三个）：
  Host    宿主应用（内置模型），持有多个 Client
  Client  由 Host 创建，1:1 连接一个 Server，负责“说协议”
  Server  独立进程，暴露 Tool/Resource/Prompt

四大原语（先记住 Tool）：
  Tool      模型可调用的动作（本 Day 重点）
  Resource  应用可读取的数据
  Prompt    用户可复用的提示词模板
  Sampling  Server 反向请求模型生成
```

它和你熟悉的产品的关系：
- **Claude Desktop / Cursor / Codex**：本身是 Host，通过配置连接各种 MCP Server。
- **Spring AI / LangChain4j**：提供 MCP Client（和 Server）的 Java 实现，让你的 Java Agent 一行配置接入 MCP。

---

## 第三部分：怎么用（实战预告）

本章是认知章，不写代码。但请先在脑子里“预演”一遍我们后面要做的事：

```
第三章：写 McpServer —— 能回应 initialize / tools.list / tools.call
第四章：写 McpClient —— 能 connect / listTools / callTool
第五章：写三个 Tool —— 天气 / 时间 / 计算器
第六章：把 Day06 Workflow 改成经 Client 调用 Server
第八章：MCP Agent V1 —— 自动发现 + 自动调用 + 调用链日志
```

现在你只需要理解：**我们要做的，就是把 Day03 那套“进程内 Tool”升级成“协议化、可复用、可治理”的 MCP 工具体系。**

---

## 第四部分：用在哪（真实项目）

| 场景 | MCP 在哪一步发挥作用 |
|------|----------------------|
| **AI IDE**（Cursor/Cline） | 通过 MCP Server 读代码库、跑命令、查文档 |
| **AI 办公** | 连接日历/邮件/文档 MCP Server，帮你排会、写周报 |
| **AI 知识库** | 用 Resource 原语读取企业文档，做 RAG 上下文 |
| **AI 浏览器** | 浏览器操作封装成 MCP Server（打开、点击、抓取） |
| **AI 客服** | 订单/物流/退款各是一个 MCP Server，客服 Agent 聚合调用 |
| **AI 数据库助手** | 数据库查询封装为 MCP Server，Agent 用自然语言查数据 |
| **AI 运维 Agent** | 监控/发布/回滚封装为 MCP Server，统一权限与审计 |
| **AI 量化交易 Agent** | 行情/下单/风控各为 Server，Agent 编排交易策略 |

共同点：**把“能力”做成独立 MCP Server，Agent 只负责编排和决策。**

---

## 第五部分：避坑与优化（本章相关）

本章是认知阶段，先记住三个最常见的**认知误区**：

1. **误区：MCP 要取代 Function Calling。**
   正解：不取代，是互补。FC 是模型能力，MCP 是连接协议，两者一起用。
2. **误区：一个 Server 塞进所有工具就行。**
   正解：企业里按业务域拆 Server，降低耦合和爆炸半径（第七章详解）。
3. **误区：MCP 只能连本地进程。**
   正解：本地用 stdio，远程用 HTTP+SSE，传输层可替换（第二章详解）。

---

## 核心知识速记

- MCP = 基于 JSON-RPC 的开放协议，标准化 AI 应用 ↔ 工具/数据 的连接。
- 价值 = 把 M×N 集成降为 M+N，带来复用、解耦、统一治理。
- 角色 = Host（宿主）/ Client（1:1 连 Server）/ Server（暴露能力）。
- 原语 = Tool / Resource / Prompt / Sampling。
- FC 是能力，MCP 是协议；HTTP 统一 Web，MCP 想统一 AI 工具。

---

## 常见面试题

1. MCP 是什么？它和 Function Calling 是什么关系？
2. MCP 解决了什么核心问题？（答：M×N 集成爆炸 → M+N）
3. Host / Client / Server 三者的关系是什么？（Client 与 Server 是几对几？）
4. 为什么说 MCP 可能成为“AI 时代的 HTTP”？
5. 企业为什么要把工具拆成独立 MCP Server？

---

## 本章练习答案

> **练习：MCP 和 HTTP 分别解决什么问题？**
>
> **参考答案：**
> - **HTTP** 解决的是“**浏览器/客户端 与 Web 服务器之间如何通信**”的问题。它统一了请求-响应的报文格式（方法、头、体、状态码），
>   让任意浏览器都能访问任意网站，催生了整个 Web 生态。
> - **MCP** 解决的是“**AI 应用（Agent）与 外部工具/数据 之间如何连接**”的问题。它基于 JSON-RPC 统一了
>   工具发现（tools/list）、工具调用（tools/call）、资源读取、提示词复用的格式，让任意 Agent 都能使用任意 MCP 工具，
>   把 M×N 的集成成本降为 M+N。
> - **共同点**：两者都是“**通用连接协议**”，通过标准化通信格式来打破碎片化、催生生态繁荣。
> - **不同层次**：HTTP 是传输/应用层的通用网络协议；MCP 更上层，专注于“AI 应用 ↔ 上下文/工具”的语义化连接，
>   并且 MCP 的传输层本身可以架在 stdio 或 HTTP 之上（也就是说，MCP 甚至可以“跑在 HTTP 上面”）。