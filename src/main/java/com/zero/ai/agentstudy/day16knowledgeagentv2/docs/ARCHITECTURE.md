# ARCHITECTURE · Enterprise Knowledge Agent V2

本文件描述 Day16 的系统架构、模块架构、以及九大核心流程（数据流 / 查询流 / 权限流 / 检索流 / 验证流 / 知识更新流 / 事件流 / 多租户）。

---

## 一、系统架构（ZeroHub 视角）

```
                 ZeroHub AI Agent Platform
                          │
                    Agent Gateway
                          │
                    Agent Router
                          │
   ┌─────────┬───────────┼────────────┬────────────┐
   ▼         ▼           ▼            ▼            ▼
Knowledge  Office     Search     Customer       SQL
 Agent     Agent      Agent      Service        Agent
   │
   ▼
Enterprise Knowledge Agent V2
   │
   ├── Multi Tenant
   ├── ACL（权限感知检索）
   ├── Hybrid Search
   ├── Reranker
   ├── Citation
   ├── Verification / Grounding / Confidence
   ├── Evaluation
   └── Knowledge Governance（Gap / Version / Update）
```

Knowledge Agent 只是 ZeroHub 中的一个 Agent，但它是所有其他 Agent 的**知识底座**。

---

## 二、模块架构（Hexagonal + Spring Modulith）

依赖方向严格单向（箭头表示「依赖于」）：

```
        api / admin
             │
             ▼
          router
             │
             ▼
          query
             │
             ▼
        retrieval
             │
             ▼
         reranker
             │
             ▼
           acl
             │
             ▼
   citation / verifier
             │
             ▼
 memory/feedback/evaluation/observability/tenant
             │
             ▼
           core        ← 零框架依赖，最稳定
```

每个模块对外暴露**端口（Port，即接口）**，实现细节（Adapter）内聚在模块内部，遵循 Hexagonal「端口—适配器」。

---

## 三、查询流（Agent 推理链，核心）

```
User Query + AgentContext(tenant/workspace/user/roles)
   │
   ▼
[Agent Router] 判定该请求归属 Knowledge Agent
   │
   ▼
[Knowledge Router] 判定使用哪个知识库（研发/财务/客服...）
   │
   ▼
[Query Understanding]
   ├── Normalization（归一化）
   ├── Rewrite（改写）
   ├── Expansion（扩展）
   └── Decomposition（分解为子问题）
   │
   ▼
[Multi Query Retrieval] 生成 N 个 query 并行检索
   │
   ├──► [Vector Search]  语义召回
   └──► [Keyword/BM25]   精确召回
   │
   ▼
[Fusion] RRF（Reciprocal Rank Fusion）合并
   │
   ▼
[Reranker] 交叉打分，取 Top-K
   │
   ▼
[ACL Filter] 权限感知过滤（在证据进入 LLM 之前！）
   │
   ▼
[Evidence] 组织证据 + 元数据（doc/version/section/page）
   │
   ▼
[LLM] 基于证据生成答案
   │
   ▼
[Verifier] Grounding + Verification（SUPPORTED/PARTIAL/UNSUPPORTED）
   │
   ├── PASS → [Citation] 附引用 → [Confidence] 计算置信度 → User
   └── FAIL → [Re-Retrieve] 有限重试 → 仍失败 → 记录 Knowledge Gap + Human-in-the-loop
```

---

## 四、权限流（Permission-aware Retrieval）

**核心原则：绝不「先检索再判断权限」，而是「权限条件下推到检索」。**

```
AgentContext(tenant, workspace, user, roles)
   │
   ▼
构造检索约束：
   WHERE tenant_id = :tenant
     AND workspace_id IN (:accessibleWorkspaces)
     AND chunk_id IN (SELECT chunk_id FROM chunk_acl
                      WHERE principal IN (:user, :roles) AND effect='ALLOW')
     AND chunk_id NOT IN (... effect='DENY')
   │
   ▼
只有「用户有权访问的 Chunk」才会进入候选集
   │
   ▼
杜绝知识泄漏：无权的内容从不进入 LLM 上下文
```

DENY 优先于 ALLOW；管理员绕行需审计日志记录。

---

## 五、检索流（Hybrid）

```
Query ──► Embedding ──► Vector Search (pgvector, HNSW) ─┐
     └──► Analyzer  ──► Keyword/BM25 Search ────────────┤
                                                     ▼
                                                     Fusion(RRF)
                                                         │
                                              Metadata Filter（版本/时间/类型）
                                                         ▼
                                                    Candidates
```

召回追求 **Recall**（宁多勿漏），排序追求 **Precision**（宁精勿滥）——因此二者分离。

---

## 六、验证流（Verification）

```
Answer + Evidence
   │
   ▼
逐句判断：该句是否被某条证据支持？
   │
   ├── 全部支持       → SUPPORTED
   ├── 部分支持       → PARTIAL
   └── 无证据支持     → UNSUPPORTED（触发重检索/拒答）
   │
   ▼
Confidence = f(RetrievalScore, RerankScore, EvidenceCoverage, Grounding)
（不采用 LLM 自评概率，因其过度自信）
```

---

## 七、知识更新流（Knowledge Update）

```
文档新增/更新/删除
   │
   ▼
生成新 document_version
   │
   ▼
增量切分 → 增量 Embedding → 增量 Index（新 index_version）
   │
   ▼
Cache Invalidation（按 tenant + kb 维度失效）
   │
   ▼
版本切换（灰度/回滚），旧 chunk 标记失效
   │
   ▼
保证「旧知识不长期污染回答」
```

---

## 八、事件流（Domain Event Driven）

```
DocumentUpdated → (触发) ReindexRequested
AnswerUnsupported → (触发) KnowledgeGapDetected
FeedbackReceived → (触发) EvaluationDatasetAppended
```

领域事件解耦「知识变更」与「重建索引/治理」，符合 Event-Driven。

---

## 九、多租户架构

- `AgentContext` / `TenantContext` 强制租户在场；
- 所有表带 `tenant_id`，复合索引前缀即租户；
- 缓存 key 带 `tenant:workspace` 前缀，防跨租户命中；
- 向量检索、ACL、审计全部以租户为边界。

详见各章节与 DATABASE.md / SECURITY.md。