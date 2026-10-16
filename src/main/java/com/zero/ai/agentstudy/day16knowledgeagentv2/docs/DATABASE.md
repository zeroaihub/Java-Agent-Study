# DATABASE · Enterprise Knowledge Agent V2

本文件描述知识库 Agent 的数据存储设计:PostgreSQL 17 + pgvector 主库、Elasticsearch/OpenSearch 全文索引、Redis 缓存/计数。核心贯穿约束——**所有表带 `tenant_id`,并用行级安全(RLS)兜底防串租户**(见 Ch15/Ch18)。

---

## 一、存储职责划分

| 存储 | 职责 | 对应章节 |
| --- | --- | --- |
| **PostgreSQL + pgvector** | 文档/分块/向量、ACL、审计、计量记录 | Ch05/Ch07/Ch16/Ch18 |
| **Elasticsearch / OpenSearch** | BM25 全文检索(混合检索的稀疏侧) | Ch05 |
| **Redis** | 多级缓存、配额原子计数、速率限制 | Ch16/Ch17 |

应用层无状态,状态全部落在上述存储(呼应 Ch19)。

---

## 二、核心表结构(PostgreSQL)

### 2.1 knowledge_document — 文档元数据

```sql
CREATE TABLE knowledge_document (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    title        TEXT NOT NULL,
    source_uri   TEXT,
    acl_labels   TEXT[] NOT NULL DEFAULT '{}',  -- Ch07:访问标签
    version      INT  NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_doc_tenant ON knowledge_document (tenant_id, workspace_id);
```

### 2.2 knowledge_chunk — 分块 + 向量

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_chunk (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    document_id  UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    ordinal      INT  NOT NULL,             -- 分块在文档内的序号
    content      TEXT NOT NULL,
    embedding    vector(1024),              -- bge-m3 维度,按模型调整
    acl_labels   TEXT[] NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 租户过滤索引(所有查询必带 tenant_id)
CREATE INDEX idx_chunk_tenant ON knowledge_chunk (tenant_id, workspace_id);
-- 向量近邻索引(HNSW,cosine 距离)
CREATE INDEX idx_chunk_embedding ON knowledge_chunk
    USING hnsw (embedding vector_cosine_ops);
```

> **向量检索必须 pre-filter tenant**(Ch15):`WHERE tenant_id = :t ORDER BY embedding <=> :q LIMIT k`,让租户过滤下推,避免先算全库近邻再过滤。

### 2.3 audit_event — 审计日志(Ch18)

```sql
CREATE TABLE audit_event (
    id         BIGSERIAL PRIMARY KEY,
    trace_id   TEXT NOT NULL,
    tenant_id  TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    action     TEXT NOT NULL,
    resource   TEXT,
    success    BOOLEAN NOT NULL,
    detail     TEXT,
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant_at ON audit_event (tenant_id, at DESC);
```

> 审计表**只追加**(应用层禁止 UPDATE/DELETE);生产建议加哈希链或落 WORM 存储保证不可篡改。

### 2.4 usage_record — 计量记录(Ch16)

```sql
CREATE TABLE usage_record (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  TEXT NOT NULL,
    dimension  TEXT NOT NULL,   -- REQUESTS / LLM_TOKENS / RETRIEVALS / STORAGE_CHUNKS
    amount     BIGINT NOT NULL CHECK (amount > 0),
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_usage_tenant_dim_at ON usage_record (tenant_id, dimension, at);
```

> 高频写入建议异步批量落库(Ch16:埋点→Kafka→聚合),此表用于对账,实时配额判断走 Redis 原子计数。

---

## 三、行级安全(RLS)——串租户的最后防线

即使应用层漏写 `WHERE tenant_id`,RLS 也在数据库层兜底(纵深防御 Ch18):

```sql
-- 开启并强制 RLS(对表 owner 也生效)
ALTER TABLE knowledge_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_chunk FORCE ROW LEVEL SECURITY;

-- 每次连接由应用设置当前租户:SET app.current_tenant = 'tenant-acme';
CREATE POLICY tenant_isolation ON knowledge_chunk
    USING (tenant_id = current_setting('app.current_tenant', true));
```

对 `knowledge_document` / `audit_event` / `usage_record` 施加同样策略。

---

## 四、Redis 键设计(带 tenant 前缀,Ch16/Ch17)

| 用途 | Key 模式 | 说明 |
| --- | --- | --- |
| 配额计数 | `quota:{tenant}:{dimension}:{period}` | INCRBY 原子累加,周期到期自动过期 |
| 速率限制 | `rate:{tenant}:{user}` | 滑动窗口/令牌桶 |
| 检索缓存 | `cache:{tenant}:retrieval:{queryHash}` | 短 TTL,知识更新按 tenant 前缀失效 |
| 答案缓存 | `cache:{tenant}:answer:{queryHash}` | TTL 短,权限/内容变更需失效 |

> **所有 key 强制 `tenant` 前缀**——缓存是最隐蔽的串租户源头(Ch15 红线)。

---

## 五、数据生命周期与合规(Ch18)

- **被遗忘权(GDPR)**:按 `tenant_id`(+可选 `user_id`)级联删除 document→chunk→向量,并清理 Redis 对应前缀;
- **数据驻留**:按租户指定库实例/region,配合多租户隔离策略(Silo/Bridge/Pool 见 Ch15);
- **加密**:传输 TLS,存储层 KMS 托管密钥;
- **审计留存**:`audit_event` 按合规要求保留(常见 1~7 年),到期归档而非删除。

---

## 六、迁移与索引建议

- 用 Flyway/Liquibase 管理 schema 版本,迁移脚本纳入 CI(Ch19);
- 向量索引 HNSW 在大数据量下构建慢,建议先灌数据再建索引;
- `usage_record` / `audit_event` 为高写入表,按时间分区(月分区)便于归档与查询;
- 定期 `ANALYZE`,并监控 pgvector 近邻查询的召回率与延迟(接入 Ch14 指标)。