# 企业级 AI 知识平台 · 架构设计文档（ARCHITECTURE）

> 版本：v1.0 · 适用：Java 21 / Spring Boot 4.1 / Spring AI 2.0
>
> 本文是整个平台的「地图」。任何人接手代码前，都应先读本文，建立对 14 个模块边界、依赖方向与数据流的宏观认知，再深入某个模块细节。

---

## 一、架构总览

平台采用 **六边形架构（Hexagonal / Ports & Adapters）+ DDD 战术建模 + Spring Modulith 模块化** 三位一体。核心思想是：**业务领域在中心，技术设施在外围，依赖永远从外指向内。**

```
                       ┌──────────────────────────────────────┐
                       │              接入层 (Adapters-In)       │
                       │   REST API / ZeroHub Tool / MCP Server │
                       └───────────────────┬────────────────────┘
                                           │ 调用
                       ┌───────────────────▼────────────────────┐
                       │            应用层 (Application)          │
                       │   用例编排 / 事务边界 / 事件发布            │
                       └───────────────────┬────────────────────┘
                                           │ 依赖
                       ┌───────────────────▼────────────────────┐
                       │              领域层 (Domain)             │
                       │  聚合根 / 值对象 / 领域服务 / 端口(Port)   │
                       └───────────────────┬────────────────────┘
                                           │ 依赖(接口)
                       ┌───────────────────▼────────────────────┐
                       │         基础设施层 (Adapters-Out)         │
                       │  pgvector / PG / Redis / OSS / LLM API   │
                       └──────────────────────────────────────────┘
```

**依赖规则（Dependency Rule）**：外层可依赖内层，内层绝不依赖外层。领域层通过定义**端口接口**声明「我需要一个向量存储」，由基础设施层提供**适配器实现**——这就是依赖倒置（DIP）。

---

## 二、14 个模块全景

| # | 模块 | 职责 | 依赖 | 层次 |
| --- | --- | --- | --- | --- |
| 1 | **knowledge-core** | 领域内核：ID、租户、事件、结果/异常、通用值对象 | 无（零框架） | Domain 内核 |
| 2 | **document** | 文档聚合：上传、元信息、生命周期状态机 | core | Domain |
| 3 | **parser** | 多格式解析：PDF/Office/图片 OCR → 纯文本 | core, document | Domain+Infra |
| 4 | **chunk** | 智能切分：结构感知、重叠窗口、Token 预算 | core, document | Domain |
| 5 | **metadata** | 元数据抽取与富化：语言、标题、部门、标签 | core, chunk | Domain |
| 6 | **embedding** | 向量化：Spring AI 批量嵌入、重试、维度校验 | core, chunk | Domain+Infra |
| 7 | **vector-store** | 向量存储：pgvector 接入、Schema 迁移、CRUD | core, embedding | Infra |
| 8 | **retrieval** | 混合检索：Vector + BM25 + RRF 融合 | core, vector-store | Domain+Infra |
| 9 | **reranker** | 重排：Cross-Encoder 精排 Top-K | core, retrieval | Domain+Infra |
| 10 | **workspace** | 空间与知识库层级、成员、ACL 权限模型 | core | Domain |
| 11 | **acl-retrieval** | 权限感知检索：把 ACL 前置到召回阶段 | core, retrieval, workspace | Domain |
| 12 | **sync** | 增量/删除同步、版本管理、事件驱动级联 | core, document, vector-store | Application |
| 13 | **observability** | 可观测性：Micrometer 指标、OTel 链路、审计 | core | Infra |
| 14 | **integration** | 集成层：REST API + ZeroHub Tool 适配 | 应用层全部 | Adapters-In |

> **单向依赖铁律**：上表「依赖」列只能出现比自己更靠内核的模块，绝不允许反向。例如 `core` 不能依赖任何人；`retrieval` 不能依赖 `reranker`（反而是 reranker 依赖 retrieval 的产物）。

---

## 三、核心数据流

### 3.1 写入流水线（Ingestion Pipeline）

从「用户上传一个文件」到「可被检索」，数据流经如下管线，各阶段通过**领域事件**解耦：

```
上传文件
  │  DocumentUploadedEvent
  ▼
[document] 落库元信息 + 存原文件(OSS) → 状态: UPLOADED
  │  DocumentIngestedEvent
  ▼
[parser] 按格式路由解析器 → 纯文本(+OCR) → 状态: PARSED
  ▼
[chunk] 结构感知切分 → List<Chunk> → 状态: CHUNKED
  ▼
[metadata] 富化每个 Chunk 的元数据(部门/语言/标签)
  ▼
[embedding] 批量向量化 → float[] → 状态: EMBEDDED
  ▼
[vector-store] 写入 pgvector(带 tenant/workspace/acl 元数据) → 状态: INDEXED
  │  DocumentIndexedEvent
  ▼
✅ 可检索
```

**关键设计：状态机 + 事件**。文档实体持有明确状态（UPLOADED → PARSED → CHUNKED → EMBEDDED → INDEXED / FAILED），每一步失败可断点续跑，避免整条流水线重来。

### 3.2 检索流水线（Query Pipeline）

```
用户 Query (带 tenantId + userId)
  │
  ▼
[acl-retrieval] 解析用户可见的 workspace/kb 范围 → 生成 ACL Filter
  ▼
[retrieval] 并行执行:
        ├── 向量召回 (pgvector, 带 tenant+acl filter)  → TopN_vec
        └── BM25 关键词召回 (带同样 filter)            → TopN_bm25
        ▼ RRF 融合 (Reciprocal Rank Fusion)
     融合候选集 TopM
  ▼
[reranker] Cross-Encoder 精排 → TopK (K < M)
  ▼
返回带引用来源(citation)的结果 → 供上层 Agent 生成答案
```

**关键设计：ACL 前置**。权限过滤发生在**召回之前**（下推为向量库 metadata filter），而非召回之后再过滤，从根本上杜绝「先检索到无权数据、再在应用层丢弃」的越权风险与性能浪费。

---

## 四、多租户隔离模型

平台采用 **共享数据库 + 行级租户隔离（Shared DB, Row-level Tenancy）** 策略：

```
Tenant (租户/企业)
  └── Workspace (工作空间/团队)
        └── KnowledgeBase (知识库)
              └── Document
                    └── Chunk → Vector
```

- 每一行数据、每一条向量都携带 `tenant_id`（+ `workspace_id` / `kb_id`）。
- 所有查询强制注入 `tenant_id` 过滤（由 `TenantContext` 保障租户始终在场）。
- ACL 在 `workspace` 层定义成员与角色（Owner/Editor/Viewer），在 `acl-retrieval` 层落地为检索过滤条件。

> **为什么不用「一租户一库」？** SaaS 场景租户数可达数千，独立库运维成本极高；行级隔离 + 强制过滤 + 强类型租户上下文，在成本与安全间取得最佳平衡。超大客户可后续升级为独立 Schema。

---

## 五、技术选型与理由

| 关注点 | 选型 | 理由 |
| --- | --- | --- |
| 语言/运行时 | Java 21 (Virtual Threads) | 高并发 IO（解析/嵌入/检索）用虚拟线程，简化异步代码 |
| 框架 | Spring Boot 4.1 + Spring Modulith | 模块边界显式化、可做架构守护测试 |
| AI 能力 | Spring AI 2.0 | 统一 Embedding/Chat 抽象，屏蔽厂商差异 |
| 向量库 | PostgreSQL + pgvector | 与业务库同栈，支持向量 + 标量混合过滤，运维简单 |
| 关键词检索 | PG 全文检索 / BM25 | 与 pgvector 同库，便于混合检索与统一事务 |
| 缓存/限流 | Redis | 热点 Query 缓存、嵌入结果缓存、分布式限流 |
| 可观测性 | Micrometer + OpenTelemetry | 指标 + 分布式链路，生产级监控标配 |

---

## 六、架构守护（Architecture Fitness）

为防止「依赖腐化」，规划以下守护手段（将在后续章节以 Spring Modulith 测试落地）：

1. **模块依赖测试**：断言 `core` 不依赖任何业务模块；断言无环形依赖。
2. **分层测试**：断言领域层不 import 任何 `org.springframework.web.*`。
3. **命名约定测试**：端口接口以 `Port` 结尾，适配器以 `Adapter` 结尾。

---

## 七、演进路线（摘要）

- **v1.0（本课程）**：单体 Modulith，14 模块单进程内清晰分层。
- **v2.0**：按模块边界拆分为独立服务（parser/embedding 计算密集型可独立伸缩）。
- **v3.0**：多模态（图表/音视频）、Agentic RAG（自主多轮检索）、知识图谱融合。

> 详细路线见 `docs/ROADMAP.md`（第 14 章产出）。

---

## 八、如何阅读后续章节

- 想理解**某个模块内部**：直接读对应 `docs/chapters/chapter-0X.md`。
- 想理解**模块间协作**：回到本文第三节数据流。
- 想理解**某个设计为何如此**：查 `docs/adr/`（架构决策记录）。