# Day01 · AI 基础与第一个 AI Demo

> 《30天打造商业级AI Agent平台（Java版）》第一天学习模块。
> 从零理解 LLM 与 Agent，并用 Spring AI 跑通第一个可运行的聊天接口。

---

## 一、本模块目标

- 理解 AI Agent 工程师定位、LLM 核心原理（Token / Context Window / Temperature 等）
- 掌握 Agent 核心公式：`Agent = LLM + Memory + Tools + Workflow`
- 搭建 Java AI 开发环境（JDK21 + Maven + Spring Boot + Spring AI）
- 用 Spring AI 的 `ChatClient` 完成第一个可运行的 AI Demo
- 建立企业级 AI 应用架构的初步认知

---

## 二、目录结构

```
day01foundation/
├── docs/chapters/          # 六章教材（Markdown）
│   ├── chapter-01.md       # 第一章：为什么 Agent 需要 Tool
│   ├── chapter-02.md       # 第二章：LLM 核心原理
│   ├── chapter-03.md       # 第三章：Agent 核心公式与 Agent Loop
│   ├── chapter-04.md       # 第四章：搭建 Java AI 开发环境
│   ├── chapter-05.md       # 第五章：完成第一个 AI Demo（本模块源码来源）
│   └── chapter-06.md       # 第六章：企业级 AI 应用架构
├── common/                 # 通用组件
│   ├── Result.java             # 统一返回包装
│   └── GlobalExceptionHandler.java  # 全局异常处理（限定本模块 controller）
├── config/
│   └── ChatClientConfig.java   # ChatClient Bean（含默认 System Prompt）
├── dto/
│   ├── ChatRequest.java        # 请求体：message / systemPrompt / temperature
│   └── ChatResponse.java       # 响应体：answer / model / costMs
├── service/
│   └── ChatService.java        # 五大功能核心实现
└── controller/
    └── ChatController.java     # POST /api/day01/chat
```

---

## 三、快速开始

### 1. 配置 API Key

在 `src/main/resources/application.yml` 中确认 OpenAI/DeepSeek 配置，并设置环境变量：

```bash
export OPENAI_API_KEY=你的密钥
```

### 2. 启动应用

```bash
mvn spring-boot:run
```

### 3. 调用接口

```bash
curl -X POST http://localhost:8080/api/day01/chat \
     -H "Content-Type: application/json" \
     -d '{"message":"你好，请介绍一下你自己"}'
```

带 System Prompt 与温度的调用：

```bash
curl -X POST http://localhost:8080/api/day01/chat \
     -H "Content-Type: application/json" \
     -d '{"message":"写一句诗","systemPrompt":"你是唐代诗人","temperature":1.2}'
```

---

## 四、五大功能

| # | 功能 | 实现要点 |
|---|------|----------|
| 1 | 普通聊天 | `chatClient.prompt().user(msg).call().content()` |
| 2 | 动态 System Prompt | 请求传 `systemPrompt` 覆盖默认人设 |
| 3 | 动态温度 | `OpenAiChatOptions.builder().temperature()` 运行时注入 |
| 4 | 异常处理 | `try-catch` 抛出，全局异常处理器兜底返回 500 |
| 5 | 日志与耗时 | 记入参、出参长度、耗时（回答不记全文） |

---

## 五、相关文档

- [ARCHITECTURE.md](ARCHITECTURE.md) · 模块架构说明
- [TODO.md](TODO.md) · 后续待办与练习清单