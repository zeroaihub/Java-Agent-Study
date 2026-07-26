# Day02：大模型 API 调用与 AI 应用工程基础

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day02
>
> 导师：拥有 20 年经验的 AI Agent 首席架构师 · 面向从 Java/Spring 转型 AI Agent 工程师的你
>
> 一句话定位：**Day1 解决「为什么做 Agent」的认知，Day2 解决「怎么用工程手段把大模型接进企业系统」的能力。**

---

## 一、今日学习目标

Day1 我们打通了第一个 LLM Demo，理解了 Agent 概念。但那只是「能跑」。
Day2 的目标是从「能跑」升级到「工程化、可上线、可商用」，具体分三个维度：

### 1. 理论能力（脑子里要有地图）

学完你要能清晰讲出：

- **LLM API 是什么**：它不是一个函数，而是一个**跨网络的远程推理服务**。
- **大模型服务架构**：请求从你的 Java 应用出发，要经过哪些环节才能拿到答案。
- **请求 / 响应流程**：一次 `POST` 到底发生了什么，谁在算，钱花在哪。
- **Chat Completion 机制**：为什么现在主流都是「对话补全」，而不是老的「文本补全」。
- **Message 设计**：`system` / `user` / `assistant` 三种角色各自的职责与优先级。
- **System / User / Assistant Prompt**：如何用三层消息控制 AI 的「人设 + 输入 + 历史」。
- **Token 消耗**：为什么你说的每个字都在花钱，怎么估算、怎么省。
- **Streaming 机制**：ChatGPT 为什么能一个字一个字往外蹦，背后是 SSE。

### 2. 工程能力（手上要能落地）

完成一个**企业级 AI Chat 服务（AI Chat Service V1）**，支持：

| 能力 | 说明 |
| --- | --- |
| 普通聊天 | `POST /chat`，一问一答 |
| System Prompt | 给 AI 设定固定人设（如「Java 专家」） |
| 多轮上下文 | 用 `conversationId` 隔离并记忆每个用户的会话 |
| 流式输出 | SSE 接口，前端逐字渲染 |
| 参数配置 | 支持运行时调 `temperature` / `maxTokens` |
| 异常处理 | API 失败、超时、限流、参数错误统一兜底 |
| 日志记录 | 记录耗时、Token、脱敏后的入参出参 |

### 3. 架构能力（心里要有分层）

理解企业 AI 应用的标准调用链：

```
用户
 ↓
Web 接口（Controller）
 ↓
业务服务（Service）
 ↓
LLM Client（封装模型调用）
 ↓
模型服务（OpenAI / 通义 / DeepSeek ...）
 ↓
返回结果
```

**核心思想：把「模型调用」当成一次远程 RPC 来治理**——限流、重试、超时、监控、成本，一个都不能少。

---

## 二、LLM API 体系全景

### 2.1 为什么 API 是 AI 应用的入口

一句话：**大模型太大，不可能塞进你的 Jar 包里。**

一个主流大模型的参数动辄数百 GB 到 TB 级，推理需要成千上万张 GPU。
你不可能在一台业务服务器上跑它，只能把它部署在模型厂商的推理集群里，
通过 **HTTP API** 远程调用。所以：

> **对 99% 的企业和工程师来说，「用 AI」= 「调 LLM API」。**

这就是为什么本训练营从 Day2 开始，把 API 调用当作**所有 AI 应用的地基**。

### 2.2 一次 API 调用的完整链路

```
Java 应用
   ↓  ①带 API Key 的 HTTPS 请求
API Gateway（鉴权 / 限流 / 路由）
   ↓  ②转发到模型服务
Model Service（Chat Completion 服务）
   ↓  ③排队 + 组 batch
模型推理（GPU 前向计算，逐 Token 生成）
   ↓  ④生成结果
Response（JSON 或 SSE 流）
   ↓
返回 Java 应用
```

- **API Gateway**：负责鉴权（校验你的 API Key）、限流（RPM/TPM）、路由（把请求发到合适的模型集群）。
- **Model Service**：把你的 `messages` 拼成模型能理解的输入，调度 GPU 推理。
- **推理层**：真正「烧钱」的地方，逐个 Token 计算生成。
- **Response**：一次性返回（JSON）或流式返回（SSE）。

### 2.3 核心概念速查表

| 概念 | 是什么 | Java 工程师类比 |
| --- | --- | --- |
| API Key | 你的身份凭证 | 数据库密码 / AK-SK |
| model | 选哪个模型 | 选哪个数据库实例 |
| messages | 对话上下文数组 | 请求体 DTO |
| temperature | 随机性（0~2） | 无直接类比，越大越发散 |
| max_tokens | 最多生成多少 Token | 分页 size 上限 |
| stream | 是否流式返回 | SSE / WebFlux 流 |
| Token | 文本计费/长度单位 | 字节，但按「词块」算 |
| RPM / TPM | 每分钟请求数 / Token 数 | QPS 限流 |

---

## 三、请求流程详解

### 3.1 请求体长什么样

Chat Completion 的标准请求：

```json
{
  "model": "gpt-4o-mini",
  "messages": [
    { "role": "system", "content": "你是一位资深 Java 架构师，回答专业、简洁。" },
    { "role": "user",   "content": "什么是 Spring 的 IOC？" }
  ],
  "temperature": 0.7,
  "max_tokens": 1024,
  "stream": false
}
```

### 3.2 响应体长什么样（非流式）

```json
{
  "id": "chatcmpl-xxx",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "IOC 即控制反转……" },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 28, "completion_tokens": 120, "total_tokens": 148 }
}
```

关键字段：

- `choices[0].message.content`：AI 的回答。
- `finish_reason`：为什么停下（`stop` 正常 / `length` 超长被截断）。
- `usage`：**这次花了多少 Token，直接对应账单。**

### 3.3 流式响应（Streaming / SSE）

`stream: true` 时，服务端用 **SSE（Server-Sent Events）** 逐块推送：

```
data: {"choices":[{"delta":{"content":"IOC"}}]}
data: {"choices":[{"delta":{"content":" 即"}}]}
data: {"choices":[{"delta":{"content":"控制反转"}}]}
data: [DONE]
```

每个 `chunk` 只带一小段增量（`delta`），前端拼接后就是「打字机效果」。

---

## 四、企业级 AI 应用架构

### 4.1 分层设计（本项目落地）

```
day02-llm-api
└── src/main/java/.../day02llmapi
    ├── controller   # Web 层：接收 HTTP / SSE 请求
    ├── service      # 业务层：编排逻辑、拼装上下文
    ├── client       # LLM Client：封装模型调用（可换厂商）
    ├── config       # 配置：ChatClient、参数、线程池
    ├── dto          # 出入参对象
    ├── session      # 多轮会话存储
    ├── exception    # 异常定义与兜底
    └── util         # 工具（脱敏、Token 估算等）
```

### 4.2 为什么要有独立的 `client` 层

**永远不要让 Controller/Service 直接感知「我在调 OpenAI」。**
封装一层 `LlmClient`，好处：

- **可替换**：明天换成通义 / DeepSeek，只改 client 实现，业务无感。
- **可治理**：重试、超时、限流、埋点都集中在这一层。
- **可测试**：测试时用假的 `EchoLlmClient` 就能跑通全链路。

这正是你 Java 工程经验的迁移点：**面向接口编程 + 依倒置**。

---

## 五、实战项目介绍：AI Chat Service V1

技术栈：**Java 21 + Spring Boot 4.1.0 + Spring AI 2.0**。

对外提供的接口（示意）：

| 接口 | 方法 | 作用 |
| --- | --- | --- |
| `/api/day02/chat` | POST | 普通聊天（支持 System Prompt / 参数） |
| `/api/day02/chat/stream` | POST | 流式聊天（SSE 逐字返回） |
| `/api/day02/chat/multi` | POST | 多轮对话（带 conversationId） |

最终成果验收清单：

- ✅ Java 成功调用 LLM
- ✅ 企业项目分层结构
- ✅ System Prompt 生效
- ✅ 多轮对话上下文隔离
- ✅ Streaming 打字机效果
- ✅ 统一异常处理

---

## 六、面试重点（Day02 高频考点）

1. **一次 LLM API 调用的完整流程是什么？** —— 从鉴权、限流到推理、返回。
2. **system / user / assistant 三种角色的区别？为什么 System Prompt 优先级最高？**
3. **temperature 和 max_tokens 分别控制什么？** —— 随机性 vs 输出长度上限。
4. **什么是 Token？为什么中文和英文的 Token 数不同？** —— 计费与上下文长度依据。
5. **Streaming 是怎么实现的？SSE 和 WebSocket 有什么区别？**
6. **多轮对话的上下文是如何维护的？** —— 服务端拼 messages，模型本身无状态。
7. **API Key 应该怎么管理？** —— 环境变量 / 配置中心，绝不硬编码进代码。
8. **如何做 Token 成本控制？** —— 限制历史轮数、max_tokens、模型分级、缓存。
9. **调用超时 / 限流怎么处理？** —— 超时时间、重试退避、降级兜底。
10. **为什么要封装独立的 LLM Client 层？** —— 可替换、可治理、可测试。

---

## 七、今日章节安排（严格串行，逐章暂停）

| 章节 | 主题 | 完成后 |
| --- | --- | --- |
| 第一章 | LLM API 为什么是 AI 应用入口 | 暂停，回答「为什么 AI 产品必须通过 API 调用模型？」 |
| 第二章 | Chat Completion 详细原理 | 暂停，解释「一次 AI 请求到底经历了什么？」 |
| 第三章 | Message 和 Prompt 设计 | 暂停，设计一个客服 Agent 的 System Prompt |
| 第四章 | Streaming 流式输出 | 暂停，解释「为什么流式输出提升用户体验？」 |
| 第五章 | Spring AI 实现 Chat 服务 | 完成完整代码 |
| 第六章 | 企业级优化（日志 / 异常 / 配置 / 监控） | 收尾 |

> **学习原则：一次只讲一章，讲完暂停，等你确认并回答章末问题后，再进入下一章。**

---

## 八、目录约定

- 本日所有代码与文档独立于 Day1，禁止修改 Day1 代码。
- Java 包名：`com.zero.ai.agentstudy.day02llmapi`
- 文档目录：`day02llmapi/docs/`（README、ARCHITECTURE、TODO、chapters/）

现在，请从 **第一章：LLM API 为什么是 AI 应用入口** 开始学习。