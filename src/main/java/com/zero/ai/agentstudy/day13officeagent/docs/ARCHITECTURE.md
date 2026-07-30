# Day13 · AI Office Agent 架构设计（ARCHITECTURE）

> 本文档面向"要把 Office Agent 做成生产级、可 SaaS 化系统"的架构师。每一处设计都会解释 **为什么这样设计**、**如何横向扩展**、**如何支持 SaaS**。全文使用 ASCII 图。

---

## 一、设计哲学：三条不可动摇的原则

1. **内容与格式解耦（IR 优先）**：LLM 只负责产出"结构化内容"（Document IR），格式渲染是纯工程问题。任何时候都不允许让 LLM 直接吐 Word/PPT 的二进制或底层 API 调用。
2. **端口与适配器（Hexagonal）**：领域核心不依赖任何具体技术（POI、SMTP、Playwright）。所有外部世界通过"端口（Port）"接入，具体实现是"适配器（Adapter）"。这让我们能随时替换 Word 引擎、邮件服务商、模型厂商。
3. **编排即一等公民（Orchestration First）**：办公任务是天然的多步流程。我们复用 Day6/Day10 的 Workflow + Planning，把"一句话 → 一整套交付"建模为可观测、可审批、可重放的流程。

---

## 二、整体架构图（分层视图）

```
┌───────────────────────────────────────────────────────────────────────────┐
│                          客户端 / ZeroHub Platform                          │
│              (Web UI / IM Bot / OpenAPI / 定时任务 / Webhook)               │
└───────────────────────────────┬───────────────────────────────────────────┘
                                 │ REST / SSE
┌───────────────────────────────▼───────────────────────────────────────────┐
│  officeapi  (入站适配器层 · Inbound Adapter)                                │
│  ─ OfficeAgentController / DTO / 全局异常 / 鉴权 / 租户解析(TenantContext)  │
└───────────────────────────────┬───────────────────────────────────────────┘
                                 │ 调用用例
┌───────────────────────────────▼───────────────────────────────────────────┐
│  officeworkflow  (应用编排层 · Application / Orchestration)                 │
│                                                                             │
│   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────────┐            │
│   │ Planner  │──▶│ Workflow │──▶│ Executor │──▶│ HumanApproval │            │
│   │ (Day10)  │   │ Engine   │   │ (Tool)   │   │ Gate (Day11)  │            │
│   └──────────┘   │ (Day6)   │   └────┬─────┘   └──────────────┘            │
│                  └──────────┘        │                                      │
└──────────────────────────────────────┼─────────────────────────────────────┘
                                        │ 通过端口(Port)调用领域能力
┌───────────────────────────────────────▼─────────────────────────────────────┐
│  officecore  (领域核心 · Domain)                                             │
│                                                                              │
│   Document IR ── OfficeTask ── OfficeContext ── DomainService                │
│   (统一文档中间表示)   (任务聚合)   (办公上下文/租户)   (纯领域逻辑)          │
│                                                                              │
│   领域端口(Ports)：DocumentRenderer / MailSender / FileStorage /            │
│                    KnowledgeStore / OcrEngine / ModelPort                     │
└───────────────────────────────────────┬─────────────────────────────────────┘
                                      │ 端口 ← 实现 → 适配器
┌───────────────────────────────────────▼─────────────────────────────────────┐
│  出站适配器层 · Outbound Adapters                                             │
│                                                                              │
│  officeword   officeexcel  officeppt   officepdf   officemail                │
│  (POI/docx4j) (POI)        (POI/XSLF)  (PDFBox)    (JavaMail)                 │
│                                                                              │
│  officecalendar  officetask  officetemplate  officesummary                   │
│  (iCal4j)        (JPA)       (模板引擎)       (LLM 摘要)                      │
│                                                                              │
│  Browser(Day9·Playwright)  RAG/Knowledge(Day5·pgvector)  Model(Spring AI 2)  │
└───────────────────────────────────────┬─────────────────────────────────────┘
                                         │
┌───────────────────────────────────────▼─────────────────────────────────────┐
│  基础设施 · Infrastructure                                                    │
│  PostgreSQL17 + pgvector  │  Redis8  │  对象存储(S3/MinIO)  │  SMTP  │  LLM   │
└──────────────────────────────────────────────────────────────────────────────┘
```

**为什么这样分层？**
- `officeapi` 只做协议转换与租户识别，不含业务逻辑——方便未来接入 IM Bot、OpenAPI 等多种入口。
- `officeworkflow` 是"大脑"，负责把目标拆成步骤并驱动执行；它不知道 Word 怎么生成，只知道"调用 DocumentRenderer 端口"。
- `officecore` 是"心脏"，只有纯领域模型与端口定义，**零技术依赖**，可被单元测试完全覆盖。
- 各 `officeXxx` 引擎是"手脚"，是端口的具体实现，可独立替换、独立扩展。

---

## 三、Office Engine（办公引擎）设计

Office Engine 是所有"格式渲染"引擎的统称。它们统一实现领域端口 `DocumentRenderer`：

```
                    ┌─────────────────────────────┐
                    │   «Port» DocumentRenderer    │
                    │  render(DocumentIR) : File   │
                    │  supports(Format) : boolean  │
                    └──────────────┬──────────────┘
                                   │ implements
     ┌──────────────┬─────────────┼─────────────┬──────────────┐
     ▼              ▼             ▼             ▼              ▼
 WordRenderer  ExcelRenderer  PptRenderer  PdfRenderer  MarkdownRenderer
 (Apache POI)  (Apache POI)   (POI XSLF)   (PDFBox)     (纯文本)
```

**为什么用统一端口 + 多实现？**
- 新增格式（如 CSV、HTML）只需新增一个 Renderer，不动核心。这是开闭原则（OCP）的直接体现。
- 编排层用 `DocumentRendererRegistry.of(format)` 按需路由，实现"一份 IR，多格式导出"。

**扩展点**：每个 Renderer 内部可再插拔"主题/主题色/字体"策略，支撑企业 VI 规范。

---

## 四、Template Engine（模板引擎）设计

企业文档 90% 有固定模板。模板引擎把"固定骨架"与"动态数据"分离：

```
   模板定义(.docx/.pptx 带占位符)  +  数据模型(Map/DTO)
                 │                          │
                 └────────────┬─────────────┘
                              ▼
                    TemplateEngine.merge()
                              │
                              ▼
                        Document IR  ──▶  Renderer  ──▶  最终文件
```

- **占位符语法**：`${company.name}`、`#{foreach items}` 等。
- **为什么先转 IR 再渲染？** 因为模板合并后仍可被审批、被二次编辑、被换格式导出，保持了一致的中间层。
- **成本收益**：模板路径几乎不消耗 LLM Token（只填数据），是控制成本的关键手段。

---

## 五、Document Pipeline（文档流水线）

文档从"意图"到"交付物"要经过一条标准流水线：

```
 意图(Instruction)
      │
      ▼
 ① Perceive 感知     ── 解析输入文件 / OCR / RAG 检索上下文
      │
      ▼
 ② Plan 规划         ── Planner 拆解为有序 Step (读Excel→分析→生成Word→...)
      │
      ▼
 ③ Generate 生成     ── LLM 产出 Document IR（结构化，非二进制）
      │
      ▼
 ④ Render 渲染       ── DocumentRenderer 把 IR 渲染成 Word/PPT/PDF
      │
      ▼
 ⑤ Review 审批       ── Human Approval Gate（对外动作前置拦截）
      │
      ▼
 ⑥ Deliver 交付      ── 邮件发送 / Browser 上传 / 写入知识库
      │
      ▼
 ⑦ Observe 观测      ── Trace + Metric + 审计日志（贯穿全程）
```

**为什么是流水线而不是一个大函数？**
- 每个阶段可独立重试、独立降级、独立观测。
- 阶段之间用 `WorkflowContext` 传递数据，天然支持"断点续跑"（复用 Day12 Long Running 能力）。

---

## 六、复用 Day1~Day12 的能力（不重复造轮子）

| 能力域 | 复用来源 | 在 Office 场景的角色 |
|--------|----------|----------------------|
| Browser Agent | Day9（Playwright） | 下载/上传文件、抓取网页数据 |
| Planning Agent | Day10 | 把复合目标拆成办公步骤 |
| Workflow | Day6 | 驱动流水线各阶段有序执行 |
| Multi-Agent | Day8 | 分析师 Agent + 撰稿 Agent 协同 |
| Memory | Day4 | 记住用户偏好、历史模板选择 |
| Knowledge Base | Day5（RAG/pgvector） | 检索历史文档、保存产出 |
| Human-in-the-loop | Day11 | 对外发送/上传前的人工审批 |
| Long Running | Day12 | 大批量文档生成的断点续跑 |
| MCP Tool | Day7 | 以标准协议暴露办公工具 |

> 架构上，这些能力都以"端口"形式接入 `officecore`，编排层按需调用。**本课程不再讲它们的原理，只讲如何在办公流水线中集成与编排。**

---

## 七、Runtime（运行时）与并发模型

- **Virtual Threads（虚拟线程）**：办公任务大量是 I/O 密集（读文件、调 LLM、发邮件），用 Java 21 虚拟线程承载，单机可支撑海量并发任务而无需庞大线程池。
- **Structured Concurrency（结构化并发）**：一个办公目标内的并行子任务（如"同时生成 Word 和 PPT"）用 `StructuredTaskScope` 管理，保证要么全部成功、要么统一取消，避免线程泄漏。

```
 OfficeTask (虚拟线程承载)
   └── StructuredTaskScope
         ├── fork: 生成 Word  (虚拟线程)
         ├── fork: 生成 PPT   (虚拟线程)
         └── fork: 生成 PDF   (虚拟线程)
         join() → 任一失败则整体回滚
```

**为什么？** 办公场景"一个目标多个并行产物"极常见，结构化并发让并行既高效又安全。

---

## 八、可观测性（Observability）

```
 每个 Pipeline 阶段
   ├── Trace  (OpenTelemetry Span)   → 全链路追踪：这份周报卡在哪一步？
   ├── Metric (Micrometer)           → 计数/耗时/Token 用量 → 成本与容量规划
   └── Audit  (审计日志)             → 谁、在什么租户下、对谁发了什么 → 合规
```

审计日志是企业级的强制项：对外发送邮件、上传文件必须留痕，满足合规审计。

---

## 九、如何横向扩展（Scalability）

```
                        ┌──────────────┐
       LB / Gateway ───▶│  officeapi   │ (无状态, 可水平扩容 N 实例)
                        └──────┬───────┘
                               │ 任务入队
                        ┌──────▼───────┐
                        │  Task Queue  │ (Redis Stream / MQ)
                        └──────┬───────┘
                               │ 消费
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
        Worker-1          Worker-2          Worker-N   (办公引擎, 可按负载弹性扩缩)
              │                │                │
              └────────────────┴────────────────┘
                               ▼
              共享: PostgreSQL / Redis / 对象存储 / LLM 网关
```

**扩展要点：**
1. **无状态 API**：`officeapi` 不保存会话状态，状态全部落 Redis/PG，可任意水平扩容。
2. **任务队列解耦**：耗时的文档生成异步化，API 快速返回 `taskId`，Worker 池按负载弹性伸缩（K8s HPA）。
3. **引擎可拆分**：CPU 密集的 PDF/PPT 渲染可拆为独立 Worker 池，与 I/O 密集的邮件发送隔离扩容。
4. **LLM 网关**：所有模型调用经统一网关，做限流、缓存、成本核算、多厂商路由。

---

## 十、如何支持 SaaS（多租户）

SaaS 化的核心是"隔离"。我们在四个层面做隔离：

```
 请求 ──▶ officeapi ──▶ 解析 TenantContext(租户ID + 用户ID + 权限)
                            │
       ┌────────────────────┼─────────────────────────────┐
       ▼                    ▼                             ▼
  数据隔离              资源隔离                       计量隔离
  ─────────            ─────────                     ─────────
  PG: tenant_id 行级   模板/知识库按租户分区          按租户统计 Token/文档数
  隔离 (RLS)           对象存储按租户前缀             → 计费 & 配额
  pgvector: 按租户过滤  邮件配置按租户                → 超额限流
```

**四层隔离设计：**
1. **数据隔离**：所有表带 `tenant_id`，PostgreSQL 行级安全（RLS）+ 应用层 `TenantContext` 双重保障；pgvector 检索强制带租户过滤，杜绝跨租户数据泄漏。
2. **资源隔离**：模板、知识库、对象存储、SMTP 配置均按租户命名空间隔离。
3. **计量隔离**：按租户统计 Token 消耗、生成文档数，作为计费与配额依据。
4. **配置隔离**：每个租户可配置自己的模型厂商、审批策略、VI 主题。

**私有化交付**：因为领域核心零技术依赖、外部全走端口，整套系统可打包为单机/私有云镜像，替换为客户内网的模型、SMTP、存储即可交付政企客户。

---

## 十一、Human Approval（人工审批）在架构中的位置

审批是一道"闸门（Gate）"，插在"生成"与"对外交付"之间：

```
 生成完成 ──▶ [是否对外/高风险?] ──否──▶ 直接交付
                     │
                    是
                     ▼
              Approval Gate  (状态存 Redis, 复用 Day11)
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
     Approve      Reject       Timeout
        │            │            │
        ▼            ▼            ▼
     继续交付      终止流程     按策略降级/挂起
```

**为什么把审批做成独立 Gate？** 因为"是否需要审批"是可配置的租户策略，不应硬编码在业务里。发内部邮件可自动放行，发客户合同必须人工审批。

---

## 十二、最终目标场景的架构落地

回到那句终极指令，看它如何在架构中流转：

```
"根据昨天销售数据生成周报，做 PPT，发给销售总监，存知识库"
        │
 officeapi 接收 + 解析租户
        │
 officeworkflow: Planner 拆解为 7 步
        │
   ┌────┴──────────────────────────────────────────────┐
   ▼                                                    ▼
 ① officeexcel 读昨日销售 Excel        ② LLM 分析数据 → 洞察
   │                                                    │
   ▼                                                    ▼
 ③ 生成 Document IR ──▶ officeword 渲染周报.docx
   │                └──▶ officeppt 渲染汇报.pptx
   │                └──▶ officepdf 导出周报.pdf
   ▼
 ④ Human Approval Gate（发给总监前审批）
   │ approve
   ▼
 ⑤ officemail 发邮件给销售总监
 ⑥ Browser(Day9) 上传文件到共享盘
 ⑦ Knowledge(Day5) 存入知识库
   │
   ▼
 全程 Trace/Metric/Audit（可观测）→ 返回执行报告
```

---

## 十三、小结

本架构的每一处，都指向同一个目标：**让"一句话完成一整套办公交付"既能跑通，又能规模化、SaaS 化、生产化。**

- 用 IR 解耦内容与格式 → 可维护、可扩展。
- 用六边形端口隔离技术 → 可替换、可测试、可私有化。
- 用 Workflow + Planning 编排 → 可观测、可审批、可续跑。
- 用租户隔离 + 队列 + 虚拟线程 → 可横向扩展、可 SaaS。

> 下一步进入代码：从 `officecore` 的 Document IR 与领域模型开始（chapter-03）。先回复 **"继续"** 进入 chapter-02（架构与 Pipeline 原理详解）。