# 架构设计文档（ARCHITECTURE）

> 项目：Day14 Enterprise AI WeChat Agent
> 目标：作为 ZeroHub AI Agent Platform 的一块基石，提供企业微信场景下的智能体能力。

---

## 1. 架构总览

本项目采用 **六边形架构（Hexagonal / Ports & Adapters）+ 领域驱动设计（DDD）+ Spring Modulith 模块化** 的组合心法，用 Java 21 与 Spring Boot 4 实现。

核心理念：

- **领域（domain）是内核**，不依赖任何框架注解与外部技术；
- **应用层（application）编排用例**，调用领域能力，不写领域规则；
- **适配器（web / infrastructure）在边缘**，翻译外部协议与技术细节；
- 依赖方向永远由外向内：`web/infrastructure -> application -> domain`。

```
        入站适配器 (Driving)                     出站适配器 (Driven)
   +-------------------------+           +----------------------------+
   | AgentController         |           | Repository 实现             |
   | WebhookController       |           | NotificationSender 实现     |
   | NotificationController  |           | (Mock / JDBC / HTTP ...)    |
   | AdminController         |           +----------------------------+
   +-----------+-------------+                        ^
               |  调用用例                             | 实现端口
               v                                       |
   +===================================================+============+
   |                    application (用例编排)                       |
   |  MeetingSummaryOrchestrator / SessionService /                 |
   |  WorkflowEngine / NotificationCenter / CommandDispatcher ...   |
   +===================================================+============+
               |  调用领域能力                          | 定义端口
               v                                       |
   +---------------------------------------------------+------------+
   |                        domain (领域内核)                        |
   |  model: ConversationSession / WorkflowTask / Notification ...  |
   |  port:  Repository / Sender 接口(不依赖 Spring)                 |
   +----------------------------------------------------------------+
```

---

## 2. 模块划分（Spring Modulith）

包根：`com.zero.ai.agentstudy.day14wechatagent`

| 模块 | 职责 | 关键类 |
| --- | --- | --- |
| `wechatcore` | 通用内核：消息模型、渠道类型、入站消息 | `ChannelType` / `InboundMessage` |
| `wechatsession` | 会话管理与多轮上下文 | `SessionService` / `ConversationSession` |
| `wechatrouter` | 消息路由分发 | `MessageRouter` |
| `wechatcommand` | 命令识别与分发 | `CommandDispatcher` |
| `wechatmemory` | 记忆与 RAG 检索 | `MemoryStore` / `RagRetriever` |
| `wechatworkflow` | 规划与工作流执行引擎 | `WorkflowEngine` / `Planner` / `StepExecutor` |
| `wechatnotify` | 通知中心与可靠送达 | `NotificationCenter` / `NotificationSender` |
| `wechatapi` | 对外 REST API 网关 | `ApiResponse` / `NotificationController` / `WebhookController` |
| `wechatadmin` | 运维管理后台 | `AdminDashboardService` / `AdminController` |
| `wechatagent` | 顶层用例编排（终极场景） | `MeetingSummaryOrchestrator` / `AgentController` |
| `docs` | 教程与工程文档 | 本目录 |

**模块依赖规则**：`wechatagent` 是被依赖树的根，只依赖别人；`wechatcore` 是通用内核，被大家依赖但不反向依赖任何业务模块。禁止循环依赖。

---

## 3. 分层约定

每个业务模块内部统一分层：

```
wechatxxx/
  ├── domain/
  │   ├── model/     领域模型（实体、值对象、枚举）—— 不依赖 Spring
  │   └── port/      领域端口（Repository / Sender 接口）—— 不依赖 Spring
  ├── application/   应用服务（用例编排、@Service）
  ├── infrastructure/ 出站适配器（端口实现、@Component / @Repository）
  └── web/           入站适配器（Controller、@RestController）
```

**铁律：领域端口不依赖 Spring 注解**。实现类才加 `@Component/@Repository/@Service`，通过构造注入连接。这保证领域内核可脱离框架单独测试与演进。

---

## 4. 核心流程：会议纪要全链路

终极场景的端到端链路（详见第十章教程）：

```
企微消息 -> WebhookController(验签) -> AgentController(校验翻译)
   -> MeetingSummaryOrchestrator.handle
        (1) SessionService.getOrCreate + touch      识别身份 + 会话
        (2) new ExecutionContext(...)               组装黑板
        (3) WorkflowEngine.planAndRun               规划 + 执行
             Planner.plan -> [生成纪要, 上传, 存库, 发通知(需审批)]
        -> 命中审批步骤 -> 挂起 SUSPENDED -> 返回 pendingStepId
   -> 人工 POST /approve -> WorkflowEngine.approve -> 从挂起点续跑
        -> NotifySendExecutor -> NotificationCenter -> 送达项目组
   -> 任务 COMPLETED，管理后台可查全程
```

---

## 5. 关键架构决策

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 架构风格 | 六边形 + DDD | 领域内核与技术细节解耦，易测试易演进 |
| 模块化 | Spring Modulith | 单体内保持模块边界，未来可拆微服务 |
| 用例落点 | 独立 `wechatagent` 编排模块 | 业务编排横跨多模块，不属任何单一领域 |
| 流程协调 | 编排（Orchestration） | 需强顺序、可挂起审批、可观测、可人工干预 |
| 并发模型 | 虚拟线程（Virtual Threads） | 承载阻塞式外部调用（LLM/浏览器）而不拖垮吞吐 |
| 中断-恢复 | 状态驱动执行（取下一个 PENDING 步骤） | 天然支持审批挂起后从断点续跑，无需游标 |
| 多租户 | `tenantId` 贯穿全链路 | 数据隔离与合规红线 |
| 响应格式 | 统一 `ApiResponse`（业务码独立 HTTP 码） | 监控友好、避免误重试与误摘除 |

---

## 6. 横切关注点

- **多租户隔离**：`tenantId` 从入口贯穿到每次仓储读写、每条通知，缺失即在校验期拦截。
- **幂等**：入口按 `(tenantId, conversationId, 指令指纹)` 去重；通知按 `dedupeKey` 去重。
- **可观测性**：`ExecutionContext` 携带 `traceId`，每步日志带上它；配合死信看板排障。
- **可靠送达**：通知失败进死信队列，管理后台支持人工重投。
- **人工干预**：高影响动作（发通知）通过 Human Approval 挂起等待人工放行/驳回。

---

## 7. 技术栈

| 领域 | 选型 |
| --- | --- |
| 语言/运行时 | Java 21（Virtual Threads） |
| 框架 | Spring Boot 4.1.0 / Spring Modulith |
| AI | Spring AI 2.0.0 / OpenAI Compatible API / MCP |
| 存储 | PostgreSQL 17 / pgvector / Redis 8 |
| 浏览器自动化 | Playwright |
| 部署 | Docker / Kubernetes / GitHub Actions |

> 注：本教学版大量能力以 Mock 实现落地并标注 TODO，生产化时逐个替换为真实实现。

---

## 8. 演进方向

1. Mock Planner -> LLM function-calling 规划；
2. 内存仓储 -> PostgreSQL + pgvector 持久化（含挂起态持久化）；
3. office/browser/knowledge Executor -> 真实文档生成 / Playwright / 向量知识库；
4. 审批 -> 接入真实企微审批流；
5. 长链路异步化 + 全链路 traceId 与指标埋点；
6. 接入真实企微回调，从教学项目长成生产基石。