# Day09 Browser Agent · 架构设计文档

本文说明模块的分层架构、Playwright 对象模型、浏览器生命周期、并发模型与 Spring AI 集成方式，帮助你在动手写代码前建立完整的心智模型。

---

## 一、分层架构

模块采用严格的分层设计，每一层只依赖下一层，职责单一、便于测试与替换。

```
┌────────────────────────────────────────────────────────────┐
│  接入层  BrowserController                                    │
│  REST 入口：/day09/browser/action/*  +  /agent/run           │
└───────────────┬──────────────────────────┬──────────────────┘
                │                           │
        原子动作调用                   自然语言调用
                │                           │
                ▼                           ▼
┌───────────────────────┐   ┌──────────────────────────────────┐
│  编排层                │   │  Agent 层                         │
│  直接用 ActionService  │   │  BrowserAgentService              │
│                       │   │  ChatClient + Tool Calling        │
└───────────┬───────────┘   └──────────────┬───────────────────┘
            │                              │
            │                              ▼
            │               ┌──────────────────────────────────┐
            │               │  工具层 BrowserTools (@Tool)      │
            │               └──────────────┬───────────────────┘
            │                              │
            └──────────────┬───────────────┘
                           ▼
        ┌──────────────────────────────────────────┐
        │  门面层 BrowserActionService              │
        │  openPage/getText/click/fill/login/...    │
        └──────────────────┬───────────────────────┘
                           │ acquire() / try-with-resources
                    ▼
        ┌──────────────────────────────────────────┐
        │  资源层 BrowserContextPool                │
        │  Semaphore 限流 + 借/还 BrowserSession    │
        └──────────────────┬───────────────────────┘
                           │
                           ▼
        ┌──────────────────────────────────────────┐
        │  引擎层 PlaywrightEngine                  │
        │  Playwright 驱动进程 + Browser 浏览器进程 │
        └──────────────────────────────────────────┘
```

**为什么这样分层？**

- **门面层（ActionService）** 屏蔽 Playwright 细节，向上只暴露"打开/点击/读取"这样的业务动词，工具层和编排层都不需要知道 Context/Page 的存在；
- **工具层（BrowserTools）** 只做一件事：把门面动作翻译成大模型能理解的 `@Tool`，加上截断、格式化等"给模型看"的处理；
- **资源层（Pool）** 把"贵资源的借还与并发"这个横切关注点独立出来，任何调用方都通过它拿会话，保证全局一致的限流与回收策略。

---

## 二、Playwright 对象模型

Playwright 的四层对象是理解一切的基础：

```
Playwright（驱动进程，JVM 与浏览器通信的桥）
  └── Browser（一个真实的浏览器进程，如 Chromium）
        ├── BrowserContext #1（隔离会话：独立 Cookie/Storage/缓存）
        │     └── Page（一个标签页）
        ├── BrowserContext #2（另一个完全隔离的会话）
        │     └── Page
        └── ...
```

类比理解：

| Playwright 概念 | 类比 | 关键特性 |
| --- | --- | --- |
| Playwright | 汽车厂 | 进程级，整个应用一个即可 |
| Browser | 一辆车 | 进程重、启动慢，应复用 |
| BrowserContext | 一位独立司机 | 轻量、隔离，各自的登录态互不干扰 |
| Page | 司机开的一段路 | 一个标签页，可开多个 |

**核心洞察：Browser 贵（复用），Context 轻（隔离并发），Page 是操作对象。**

---

## 三、浏览器生命周期

```
应用启动
   │
   ▼
@PostConstruct init()            ← fail-fast：起不来就让应用挂掉
   │  Playwright.create()
   │  browserType.launch(...)     ← 启动 Browser 进程（含 --no-sandbox）
   ▼
Browser 就绪（常驻，全局唯一）
   │
   │  ┌──────── 每个请求 ────────┐
   │  │ pool.acquire()          │
   │  │   permits.tryAcquire()  │ ← 拿不到许可就超时拒绝
   │  │   browser.newContext()  │ ← 创建隔离会话
   │  │   context.newPage()     │
   │  │        ↓ 执行动作         │
   │  │   session.close()       │ ← try-with-resources 自动触发
   │  │     context.close()     │ ← 销毁会话，清除登录态
   │  │     permits.release()   │ ← 归还许可
   │  └─────────────────────────┘
   │
   ▼
@PreDestroy destroy()            ← 先关 Browser，再关 Playwright
   │  browser.close()
   │  playwright.close()
   ▼
应用关闭
```

**生命周期的两个关键原则：**

1. **Browser 与应用同寿**：启动时建，关闭时毁，中间一直复用；
2. **Context 与请求同寿**：请求来时建，请求完（或异常）立即毁，绝不跨请求复用（防污染）。

---

## 四、并发模型

采用「一个 Browser + N 个 Context + Semaphore 限流」。

```
                 Semaphore(pool-size = 4)
                 ┌───┬───┬───┬───┐
   请求A ──借──▶ │● │   │   │   │   拿到许可，创建 Context A
   请求B ──借──▶ │ ● │ ● │   │   │   拿到许可，创建 Context B
   请求C ──借──▶ │ ● │ ● │ ● │   │
   请求D ──借──▶ │ ● │ ● │ ● │ ● │   池满
   请求E ──借──▶ 阻塞等待... 超时则抛「会话池繁忙」
                   │
   请求A 完成 ──还──▶ 释放一个许可，请求E 得以继续
```

**为什么用 Semaphore 而不是线程池？**

- 我们要限制的是**同时存活的 Context 数量**（内存资源），而不是线程数；
- 一个 Context 可能被同一线程持有一段时间，Semaphore 精确表达"最多 N 个并发会话"这个约束；
- 超时机制（`tryAcquire(timeout)`）实现了优雅降级：高峰期宁可快速拒绝，也不让请求无限堆积拖垮整个进程。

**并发安全要点：**

- `activeSessions` 用 `ConcurrentHashMap` 登记在用会话，便于监控与强制回收；
- 创建 Context 失败的异常路径**必须** `release()` 许可，否则许可只借不还，池子会缩水至死锁。

---

## 五、Spring AI 集成

```
用户自然语言指令
   │
   ▼
BrowserAgentService.run(instruction)
   │
   │  chatClient.prompt()
   │     .system("你是浏览器操作助手...")
   │     .user(instruction)
   │     .tools(browserTools)      ← 把 @Tool 方法交给模型
   │     .call().content()
   │
   ▼
大模型（LLM）
   │  分析指令 → 决定调用哪个工具 → 生成工具调用参数
   ▼
Spring AI 框架自动执行对应 @Tool 方法（BrowserTools）
   │  执行结果回传给模型
   ▼
模型基于结果决定：继续调用工具 or 生成最终回答
   │  （多轮循环直到任务完成）
   ▼
返回自然语言总结
```

**关键设计：**

- `@Tool(description=...)` 的描述是模型的"说明书"，写得越清晰、越面向意图，模型调用越准确；
- `@ToolParam` 描述每个参数含义，帮助模型正确填参；
- 工具返回值要**简洁、结构化**（读取内容做截断），既降低成本又提升模型判断质量。

---

## 六、模块隔离策略（不影响前八天代码）

沿用项目统一约定，保证 Day09 完全独立、可插拔：

| 隔离手段 | 做法 |
| --- | --- |
| 独立包 | `com.example.agentstudy.day09browseragent` |
| 独立 Bean 名 | ChatClient 命名为 `day09ChatClient` |
| 依赖注入指定 | 用 `@Qualifier("day09ChatClient")` 精确注入 |
| 配置项隔离 | 前缀 `day09.browser`，全部带默认值 |
| 异常处理隔离 | `Day09ExceptionHandler` 用 `basePackages` 限定只管本包 |
| 统一返回体 | 独立 `R<T>`，不与其他 Day 的 Result 冲突 |

这样即使删掉整个 `day09browseragent` 包，前八天代码依然能正常编译运行。

---

## 七、扩展点

- **StorageState 持久化**：把登录后的 Cookie/Storage 存盘，下次直接复用，免重复登录；
- **Context 预热池**：把"用完即销毁"改为"复用 + 定期回收"，进一步降低创建开销（需权衡污染风险）；
- **Redis 会话中心**：多实例部署时用 Redis 共享登录态与截图元数据；
- **动作重试与幂等**：网络抖动时对导航/点击做有限重试；
- **可观测性**：暴露池使用率、平均借用时长等指标到 Prometheus。