# Day16 · Enterprise Knowledge Agent V2（企业级知识智能体 V2）

> 从「会搜索的知识库」到「会思考、可信任、可治理的企业知识智能体」。
>
> 本项目是 ZeroHub AI Agent Platform 的**企业知识智能核心**，在 Day15「Enterprise AI Knowledge Platform」的基础上，将知识平台升级为真正能被多个 Agent 统一调用的 **Enterprise Knowledge Agent V2**。

---

## 目录

1. [项目介绍](#一项目介绍)
2. [为什么需要 Knowledge Agent V2](#二为什么需要-knowledge-agent-v2)
3. [核心能力](#三核心能力20-项)
4. [系统架构](#四系统架构)
5. [模块划分](#五模块划分)
6. [技术栈](#六技术栈)
7. [快速启动](#七快速启动)
8. [配置说明](#八配置说明)
9. [数据库设计](#九数据库设计)
10. [API 概览](#十api-概览)
11. [测试策略](#十一测试策略)
12. [部署](#十二部署)
13. [监控与可观测性](#十三监控与可观测性)
14. [企业案例](#十四企业案例)
15. [性能优化](#十五性能优化)
16. [安全](#十六安全)
17. [商业化方向](#十七商业化方向)
18. [课程章节导航](#十八课程章节导航)

---

## 一、项目介绍

**Enterprise Knowledge Agent V2** 是一个可投入企业生产、支持多租户 SaaS、可商业化的企业级知识智能体。它不是「又一个 RAG Demo」，而是一个具备**自主推理、检索规划、权限治理、引用溯源、答案验证、知识治理**能力的完整 Agent。

传统 RAG 的流程是：`用户问题 → 向量检索 → 拼 Prompt → LLM 回答`。这套流程在 Demo 阶段够用，但一旦进入企业环境，就会立刻暴露出致命短板：

- 用户问「报销标准是多少」，系统却在研发知识库里检索——**缺少 Knowledge Routing**；
- 用户表达不准确（「那个休假的规定」），向量检索召回一堆噪声——**缺少 Query Rewrite / Decomposition**；
- 只有向量检索，专有名词、编号、精确匹配全部失效——**缺少 Hybrid Search**；
- 召回了 50 条候选，直接塞给 LLM，噪声淹没答案——**缺少 Reranker**；
- 「先检索再判断权限」，A 部门看到 B 部门的机密文档——**知识泄漏**；
- LLM 一本正经胡编，没有任何来源——**缺少 Citation / Grounding / Verification**；
- 知识库没有答案时也硬答，用户被误导——**缺少 Knowledge Gap 机制**；
- 文档更新了，旧答案还在污染——**缺少 Knowledge Version / Update**；
- 无法衡量检索质量与答案可信度——**缺少 Evaluation / Confidence**；
- 出问题无法定位是哪一步坏了——**缺少 Observability**。

Day16 的使命，就是把上述 10 个短板逐一补齐，构建一个**企业可信任**的 Knowledge Agent。

---

## 二、为什么需要 Knowledge Agent V2

企业知识场景与 C 端问答有本质区别：

| 维度 | C 端 RAG Demo | 企业级 Knowledge Agent |
| --- | --- | --- |
| 知识库数量 | 单一 | 多知识库 / 多域 / 多租户 |
| 权限 | 无 | Tenant / Workspace / Role / Document / Chunk 多级 ACL |
| 答案要求 | 差不多就行 | 必须有据可依、可溯源、可验证 |
| 知识更新 | 静态 | 增量索引、版本切换、缓存失效 |
| 可信度 | 靠感觉 | Confidence Score + Grounding + Verification |
| 反馈 | 无 | Feedback Loop 驱动持续优化 |
| 治理 | 无 | Knowledge Gap、审计日志、评估流水线 |
| 可观测 | 无 | 全链路 Trace（Metrics / Tracing / Logging） |

**商业价值**：一个可信的企业 Knowledge Agent，能直接替代企业内部大量的「找文档、问同事、翻手册」的低效沟通，是 Glean、Microsoft Copilot、Google Enterprise Search 这类产品的核心。它同时是所有下游 Agent（客服 / 办公 / SQL / 搜索）共享的「知识底座」。

---

## 三、核心能力（20 项）

| # | 能力 | 说明 | 章节 |
| --- | --- | --- | --- |
| 1 | Knowledge Routing | 自动判断使用哪个知识库 | Ch03 |
| 2 | Multi Knowledge Base | 多知识库 / Workspace / Tenant / 域 | Ch02 |
| 3 | Query Rewrite | 改写 / 扩展 / 分解 / 归一化 | Ch04 |
| 4 | Multi Query Retrieval | 一问多查，提高 Recall | Ch05 |
| 5 | Hybrid Retrieval | Vector + Keyword/BM25 + Metadata 融合 | Ch06 |
| 6 | Reranking | 召回与排序分离，Top-K 精排 | Ch07 |
| 7 | Permission-aware Retrieval | 检索阶段权限过滤，杜绝泄漏 | Ch08 |
| 8 | Citation | 文档 / 章节 / 页码 / Chunk 级引用 | Ch09 |
| 9 | Answer Grounding | 答案必须被证据支持 | Ch10 |
| 10 | Knowledge Verification | SUPPORTED / PARTIAL / UNSUPPORTED | Ch11 |
| 11 | Knowledge Confidence | 综合置信度，非 LLM 自评概率 | Ch11 |
| 12 | Feedback | 👍👎 驱动检索/Prompt/知识优化 | Ch14 |
| 13 | Knowledge Gap | 发现知识缺口并驱动补充 | Ch12 |
| 14 | Knowledge Update | 增量索引 / 缓存失效 | Ch13 |
| 15 | Knowledge Version | 文档/知识库/索引版本 | Ch13 |
| 16 | Evaluation | Recall/Precision/MRR/NDCG/Faithfulness | Ch15 |
| 17 | Observability | Micrometer + OpenTelemetry 全链路 | Ch16 |
| 18 | Cache | Query/Embedding/Retrieval/Rerank 缓存| Ch17 |
| 19 | Memory 联动 | Memory ≠ Knowledge Base | Ch19 |
| 20 | Agent Router | Knowledge Agent 是 ZeroHub 的一个 Agent | Ch02 |

---

## 四、系统架构

```
                    User
                     │
                     ▼
                Agent Gateway
                     │
                     ▼
                Agent Router
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
     Knowledge     Browser     Office
       Agent        Agent       Agent
          │
          ▼
    Query Understanding
          │
          ▼
    Knowledge Router
          │
          ▼
    Query Rewrite / Decomposition
          │
          ▼
   Multi Query Retrieval
          │
      ┌───┴────┐
      ▼        ▼
   Vector    Keyword(BM25)
      │        │
      └───┬────┘
          ▼
      Fusion (RRF)
          │
          ▼
       Reranker
          │
          ▼
      ACL Filter（权限感知）
          │
          ▼
      Evidence
          │
          ▼
         LLM
          │
          ▼
       Verifier
          │
      ┌───┴────┐
      ▼        ▼
   PASS      FAIL → Re-Retrieve（有限重试）→ 仍失败 → Human-in-the-loop
      │
      ▼
   Citation
      │
      ▼
     User（可信答案 + 来源 + 版本 + 置信度）
```

详细数据流 / 权限流 / 验证流见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

---

## 五、模块划分

采用 Spring Modulith + Hexagonal，依赖方向严格单向（越底层越稳定）：

| 模块 | 职责 | 为什么这样划分 |
| --- | --- | --- |
| `core` | 领域内核：强类型 ID、统一结果/错误码/异常、AgentContext | 零框架依赖，最稳定，所有模块依赖它 |
| `router` | Agent Router + Knowledge Router | 路由决策独立，便于替换策略（规则/LLM/嵌入） |
| `query` | Query Rewrite / Decomposition / Multi-Query | 查询理解独立，可单测、可缓存 |
| `retrieval` | Hybrid：Vector + Keyword/BM25 + Metadata + Fusion | 召回独立，可插拔多种检索后端 |
| `reranker` | 候选重排 Top-K | 召回与排序分离（Ch07 详述） |
| `acl` | 权限感知过滤（Tenant/Workspace/Role/Doc/Chunk） | 安全边界必须独立、可审计 |
| `citation` | 引用与证据组织 | 溯源是可信答案的前提 |
| `verifier` | Grounding + Verification + Confidence | 答案质量守门员 |
| `memory` | 短期/长期记忆联动（Memory ≠ Knowledge） | 记忆与知识来源不同，必须解耦 |
| `tenant` | 多租户上下文与隔离 | SaaS 基石 |
| `feedback` | 用户反馈采集 | 驱动闭环优化 |
| `evaluation` | Recall/MRR/NDCG/Faithfulness 流水线 | 质量可度量才能持续改进 |
| `observability` | Metrics / Tracing / Logging | 生产可运维 |
| `admin` / `api` | 管理端 + 对外 API | 应用层，依赖上述所有模块 |

> **划分原则**：每个模块对应一个**清晰的领域能力边界**，对外只暴露端口（接口），实现细节内聚。这样才能做到「换掉 Reranker 不影响 Retrieval」「换掉向量库不影响 ACL」。

---

## 六、技术栈

- **语言/框架**：Java 21 LTS、Spring Boot 4.1.0、Spring AI 2.0.0、Maven
- **存储**：PostgreSQL 17 + pgvector、Redis 8
- **模型接入**：OpenAI Compatible API、MCP
- **部署**：Docker / Docker Compose / Kubernetes
- **可观测**：Micrometer + OpenTelemetry
- **测试**：JUnit 5 + Testcontainers、Playwright
- **CI**：GitHub Actions
- **架构**：DDD + Clean/Hexagonal + Spring Modulith

---

## 七、快速启动

```bash
# 1. 启动依赖（PostgreSQL + pgvector + Redis）
docker compose -f docs/deploy/docker-compose.yml up -d

# 2. 配置模型（OpenAI Compatible）
cp .env.example .env   # 填入 API_KEY / BASE_URL

# 3. 运行（复用 agent-study 统一工程）
mvn spring-boot:run

# 4. 提问（会走完整 Agent 推理链）
curl -X POST http://localhost:8080/api/v2/knowledge/ask \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: tenant-001' \
  -H 'X-User-Id: user-zhang' \
  -d '{"question":"公司的 Java 服务上线流程是什么？"}'
```

返回示例（节选）：

```json
{
  "success": true,
  "data": {
    "answer": "根据《Java 服务生产发布规范 V3.1》……",
    "confidence": 0.86,
    "verification": "SUPPORTED",
    "citations": [
      {"documentTitle": "Java 服务生产发布规范", "version": "V3.1", "section": "4.2 灰度发布", "page": 12}
    ],
    "knowledgeBase": "研发知识库",
    "traceId": "trace-xxxx"
  }
}
```

---

## 八、配置说明

| 配置项 | 说明 | 默认 |
| --- | --- | --- |
| `agent.router.strategy` | 路由策略：`rule` / `embedding` / `llm` | `embedding` |
| `agent.query.multi-query.count` | 多查询生成数量 | `3` |
| `agent.retrieval.hybrid.vector-weight` | 向量检索融合权重 | `0.6` |
| `agent.retrieval.hybrid.keyword-weight` | 关键词检索融合权重 | `0.4` |
| `agent.reranker.top-k` | 重排后保留数量 | `5` |
| `agent.verifier.min-confidence` | 最低可信阈值，低于则触发重检索 | `0.5` |
| `agent.verifier.max-retry` | 重检索最大次数 | `2` |
| `agent.cache.retrieval.ttl` | 检索缓存 TTL（秒），多租户隔离 key | `300` |

> 敏感配置（API Key、密码、Token）一律走环境变量 / Secret，禁止入库。详见 [SECURITY.md]。

---

## 九、数据库设计

核心表（详见 DATABASE.md）：`tenant`、`workspace`、`knowledge_base`、`document`、`document_version`、`chunk`、`chunk_acl`、`knowledge_gap`、`query_log`、`retrieval_log`、`citation`、`feedback`、`evaluation`。

关键设计：
- 所有业务表带 `tenant_id`，并建立 `(tenant_id, ...)` 复合索引，从数据库层强制租户隔离；
- `chunk` 使用 pgvector 存储 embedding，配合 `chunk_acl` 实现**检索期权限过滤**；
- `document_version` 支持版本切换与回滚，`index_version` 保证「旧知识不长期污染」。

---

## 十、API 概览

详见 API.md。核心端点：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v2/knowledge/ask` | Agent 问答（完整推理链） |
| POST | `/api/v2/knowledge/feedback` | 提交 👍👎 反馈 |
| GET | `/api/v2/knowledge/citations/{queryId}` | 查询引用来源 |
| GET | `/api/v2/knowledge/gaps` | 知识缺口列表（管理端） |
| POST | `/api/v2/knowledge/evaluate` | 触发评估流水线 |

---

## 十一、测试策略

Unit / Integration（Testcontainers）/ Retrieval Test / Permission Test / Evaluation Test / Agent Test / E2E。详见 TESTING.md。

**权限测试是重中之重**：必须覆盖「A 租户/角色绝不可检索到 B 的 Chunk」的负向用例。

---

## 十二、部署

支持 Docker Compose（本地/单机）与 Kubernetes（多实例水平扩展）。无状态设计，会话与缓存下沉 Redis，向量与元数据下沉 PostgreSQL。详见 DEPLOY.md。

---

## 十三、监控与可观测性

接入 Micrometer + OpenTelemetry，形成完整 Agent Trace：

```
UserQuery → QueryRewrite → KnowledgeRouter → Retriever(Vector|Keyword)
         → Reranker→ ACLFilter → LLM → Verifier → Answer
```

每一步都是一个 Span，携带 traceId、耗时、命中数、置信度。详见 OBSERVABILITY.md。

---

## 十四、企业案例

- **企业内部知识助手**：Glean 式全公司知识检索；
- **研发知识库**：上线流程、编码规范、架构决策；
- **财务知识库**：报销标准、发票政策；
- **客服知识库**：退款政策、SOP；
- **法律/金融知识库**：合同条款、合规要求；
- 对标 Microsoft Copilot、Google Enterprise Search、Notion AI、Confluence。

---

## 十五、性能优化

多级缓存（Query/Embedding/Retrieval/Rerank）、召回与排序分离、向量索引（HNSW/IVFFlat）、批量 Embedding、并发多查询、连接池与 Redis Pipeline。详见 PERFORMANCE.md。

---

## 十六、安全

多租户隔离、检索期 ACL、审计日志、最小权限、敏感信息脱敏、缓存 key 带租户前缀防泄漏、Prompt 注入防护。详见 SECURITY.md。

---

## 十七、商业化方向

作为 ZeroHub AI Agent Platform 的知识底座，向下游 Agent（客服/办公/搜索/SQL/Coding/数据分析）以统一 API 提供**可信知识服务**，按租户/调用量/知识规模计费，构成 SaaS 商业闭环。详见 ROADMAP.md。

---

## 十八、课程章节导航

| 章节 | 主题 |
| --- | --- |
| [Ch01](./chapters/chapter-01.md) | 企业为什么需要 Knowledge Agent V2 + 总体架构 |
| [Ch02](./chapters/chapter-02.md) | Knowledge Agent 总体架构与模块划分 |
| [Ch03](./chapters/chapter-03.md) | Knowledge Routing 知识路由 |
| [Ch04](./chapters/chapter-04.md) | Query Rewrite 与 Query Decomposition |
| [Ch05](./chapters/chapter-05.md) | Multi Query Retrieval |
| [Ch06](./chapters/chapter-06.md) | Hybrid Search 混合检索 |
| [Ch07](./chapters/chapter-07.md) | Reranker 重排序 |
| [Ch08](./chapters/chapter-08.md) | Permission-aware Retrieval |
| [Ch09](./chapters/chapter-09.md) | Citation 与 Evidence |
| [Ch10](./chapters/chapter-10.md) | Answer Grounding |
| [Ch11](./chapters/chapter-11.md) | Knowledge Verification 与 Confidence |
| [Ch12](./chapters/chapter-12.md) | Knowledge Gap |
| [Ch13](./chapters/chapter-13.md) | Knowledge Version 与 Update |
| [Ch14](./chapters/chapter-14.md) | Feedback Loop |
| [Ch15](./chapters/chapter-15.md) | Knowledge Evaluation |
| [Ch16](./chapters/chapter-16.md) | Observability |
| [Ch17](./chapters/chapter-17.md) | 企业级性能优化与缓存 |
| [Ch18](./chapters/chapter-18.md) | 多租户 SaaS |
| [Ch19](./chapters/chapter-19.md) | 完整 Knowledge Agent 集成实战 |
| [Ch20](./chapters/chapter-20.md) | 企业挑战与商业化 |

> 本课程采用**逐章教学**：每章产出对应 `docs/chapters/chapter-xx.md` 后暂停，等待你输入「继续」再进入下一章。