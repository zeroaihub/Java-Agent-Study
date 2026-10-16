# 数据库设计文档（DATABASE）

> 项目：Day14 Enterprise AI WeChat Agent
> 存储：PostgreSQL 17（关系数据）+ pgvector（向量检索）+ Redis 8（缓存/会话）
> 说明：本教学版以内存仓储落地并标注 TODO，本文档给出生产化的目标 Schema。

---

## 1. 存储选型概览

| 存储 | 承载数据 | 理由 |
| --- | --- | --- |
| PostgreSQL 17 | 会话、工作流任务/步骤、通知、死信 | 强一致、事务、可审计 |
| pgvector | 知识库向量嵌入 | RAG 语义检索 |
| Redis 8 | 会话热缓存、幂等键、限流计数 | 高频读写、TTL 过期 |

**多租户策略**：所有业务表统一携带 `tenant_id` 列，并在其上建索引；查询强制带 `tenant_id` 谓词，杜绝越权。

---

## 2. 核心表结构

### 2.1 会话表 `conversation_session`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigserial | PK | 主键 |
| tenant_id | varchar(64) | not null | 租户 |
| channel_type | varchar(32) | not null | 渠道：WECOM/WEBHOOK/MOCK... |
| conversation_id | varchar(128) | not null | 会话标识 |
| status | varchar(32) | not null | 会话状态 |
| turn_count | int | not null default 0 | 多轮计数 |
| last_active_at | timestamptz | not null | 最近活跃时间 |
| created_at | timestamptz | not null | 创建时间 |

唯一约束：`(tenant_id, channel_type, conversation_id)`
索引：`idx_session_last_active (tenant_id, last_active_at)` 用于过期清扫。

### 2.2 工作流任务表 `workflow_task`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| task_id | varchar(64) | PK | 任务 ID |
| tenant_id | varchar(64) | not null | 租户 |
| goal | text | not null | 原始指令/目标 |
| status | varchar(32) | not null | CREATED/RUNNING/SUSPENDED/COMPLETED/FAILED |
| conversation_id | varchar(128) | | 关联会话 |
| context_json | jsonb | | ExecutionContext 黑板快照 |
| created_at | timestamptz | not null | 创建时间 |
| updated_at | timestamptz | not null | 更新时间 |

索引：`idx_task_status (tenant_id, status)` —— 查挂起任务（SUSPENDED）用于审批列表。

> **关键**：`SUSPENDED` 任务必须持久化，否则进程重启即丢失待审批任务。

### 2.3 工作流步骤表 `workflow_step`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | varchar(64) | PK | 步骤 ID |
| task_id | varchar(64) | FK -> workflow_task | 所属任务 |
| seq | int | not null | 步骤顺序 |
| name | varchar(128) | not null | 步骤名 |
| action | varchar(64) | not null | 动作类型：office.summarize... |
| status | varchar(32) | not null | PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED/WAITING_APPROVAL |
| requires_approval | boolean | not null default false | 是否需人工审批 |
| output | text | | 步骤产出 |
| error | text | | 失败原因 |

索引：`idx_step_task_status (task_id, status)` —— 取「下一个 PENDING 步骤」，支撑续跑。

### 2.4 通知表 `notification`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | varchar(64) | PK | 通知 ID |
| tenant_id | varchar(64) | not null | 租户 |
| target | varchar(128) | not null | 接收方 |
| content | text | not null | 内容（Markdown） |
| status | varchar(32) | not null | PENDING/SENT/DEAD |
| dedupe_key | varchar(128) | | 幂等键 |
| retry_count | int | not null default 0 | 重试次数 |
| created_at | timestamptz | not null | 创建时间 |

唯一约束：`(tenant_id, dedupe_key)`（dedupe_key 非空时）—— 幂等去重。
索引：`idx_notify_status (tenant_id, status)` —— 死信看板聚合。

### 2.5 死信表 `notification_dead_letter`

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | varchar(64) | PK | 死信 ID |
| notification_id | varchar(64) | FK | 关联通知 |
| tenant_id | varchar(64) | not null | 租户 |
| reason | text | | 进入死信原因 |
| created_at | timestamptz | not null | 创建时间 |
| retried_at | timestamptz | | 人工重投时间 |

---

## 3. 知识库向量表（pgvector）

### 3.1 `knowledge_chunk`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | bigserial PK | 主键 |
| tenant_id | varchar(64) | 租户 |
| source | varchar(256) | 来源（会议纪要/文档） |
| content | text | 原文分块 |
| embedding | vector(1536) | 向量嵌入 |
| created_at | timestamptz | 创建时间 |

向量索引（HNSW，余弦距离）：

```sql
CREATE INDEX idx_kc_embedding ON knowledge_chunk
  USING hnsw (embedding vector_cosine_ops);
```

检索示例（Top-K 语义召回）：

```sql
SELECT id, content, 1 - (embedding <=> :query_vec) AS score
FROM knowledge_chunk
WHERE tenant_id = :tenant
ORDER BY embedding <=> :query_vec
LIMIT :k;
```

---

## 4. Redis 键设计

| 键 | 类型 | TTL | 用途 |
| --- | --- | --- | --- |
| `session:{tenant}:{channel}:{conv}` | Hash | 30min | 会话热缓存 |
| `idem:{tenant}:{key}` | String | 24h | 幂等键去重 |
| `ratelimit:{tenant}:{api}` | String(计数) | 1min | 限流计数 |
| `webhook:seen:{msgId}` | String | 10min | 回调去重（防重复处理） |

---

## 5. 建表 DDL 片段（示意）

```sql
CREATE TABLE workflow_task (
    task_id         VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    goal            TEXT        NOT NULL,
    status          VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(128),
    context_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_status ON workflow_task (tenant_id, status);
```

---

## 6. 设计要点

- **挂起态持久化**：`workflow_task.status = SUSPENDED` + `workflow_step.status = WAITING_APPROVAL` 必须落库，续跑靠「取下一个 PENDING 步骤」，无需游标列。
- **幂等**：通知与入口指令均用唯一约束 + Redis 双保险去重。
- **多租户**：所有查询强制带 `tenant_id`，越权在应用层与索引层双重拦截。
- **黑板快照**：`context_json` 用 jsonb 存储 ExecutionContext，便于审计与续跑恢复。
- **可审计**：所有表带 `created_at/updated_at`，通知与死信保留全生命周期记录。