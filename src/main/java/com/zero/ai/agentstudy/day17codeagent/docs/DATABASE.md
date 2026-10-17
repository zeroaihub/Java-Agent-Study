# Day17 · DATABASE（数据库设计）

> 本模块当前**全部为内存实现**（与 Day01~Day16 现状一致）。本文描述的是**生产化时应当落地的表结构**，也是把内存状态换成持久化时的直接依据。

---

## 一、总览

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `code_task` | 任务主表 | tenant_id, repo_id, status, trace_id |
| `patch_record` | 补丁记录（含匹配策略） | task_id, file_path, strategy |
| `compile_record` | 编译结果 | task_id, success, elapsed_ms |
| `compile_diagnostic` | 结构化诊断 | compile_id, file, line, code |
| `test_record` | 测试执行结果 | task_id, test_class, passed |
| `repair_round` | 自修复每轮记录 | task_id, round, stop_reason |
| `change_bundle_item` | 变更集明细 | task_id, path, added, removed, risk |
| `pr_draft` | PR 草稿 | task_id, title, body, risk |
| `trace_step` | 链路步骤 | task_id, name, status, elapsed_ms |
| `budget_usage` | 预算消耗 | task_id, tokens, model_calls |
| `eval_sample` | 评测样本 | task_id, model_id, compiled, test_passed |
| `repo_snapshot` | 仓库快照指纹 | repo_id, fingerprint, file_count |

---

## 二、核心表 DDL

### 2.1 code_task

```sql
CREATE TABLE code_task (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    repo_id       VARCHAR(64)  NOT NULL,
    user_id       VARCHAR(64)  NOT NULL,
    branch        VARCHAR(128) NOT NULL DEFAULT 'main',
    requirement   TEXT         NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    trace_id      VARCHAR(64)  NOT NULL,
    mergeable     BOOLEAN      NOT NULL DEFAULT FALSE,
    elapsed_ms    BIGINT       NOT NULL DEFAULT 0,
    tokens        INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- 租户隔离是 SaaS 的第一道约束，复合索引从数据库层强制
CREATE INDEX idx_code_task_tenant ON code_task (tenant_id, created_at DESC);
CREATE INDEX idx_code_task_trace  ON code_task (trace_id);
```

### 2.2 patch_record

```sql
CREATE TABLE patch_record (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT      NOT NULL REFERENCES code_task(id),
    file_path     VARCHAR(512) NOT NULL,
    applied       BOOLEAN     NOT NULL,
    -- 记录实际生效的匹配策略，精确 / 忽略行尾空白 / 忽略缩进 / 归一化空白
    strategy      VARCHAR(32),
    match_count   INTEGER     NOT NULL DEFAULT 0,
    failure_reason TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_patch_task ON patch_record (task_id);
```

> `strategy` 字段是刻意保留的：用了模糊策略的补丁在评审时应当被重点复核（对应 `PatchResult.usedFuzzyMatch()`）。

### 2.3 compile_diagnostic

```sql
CREATE TABLE compile_diagnostic (
    id            BIGSERIAL PRIMARY KEY,
    compile_id    BIGINT      NOT NULL,
    kind          VARCHAR(16) NOT NULL,   -- ERROR / WARNING / NOTE
    file_path     VARCHAR(512),
    line_no       INTEGER,
    column_no     INTEGER,
    code          VARCHAR(64),            -- compiler.err.expected
    message       TEXT        NOT NULL
);

CREATE INDEX idx_diag_compile ON compile_diagnostic (compile_id);
```

> **为什么要结构化存**：把诊断存成一段原始日志，就没法做「两轮错误是否相同」的比较，而这是自修复循环无进展检测的基础。

### 2.4 repair_round

```sql
CREATE TABLE repair_round (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT      NOT NULL REFERENCES code_task(id),
    round_no      INTEGER     NOT NULL,
    patch_count   INTEGER     NOT NULL DEFAULT 0,
    errors_before INTEGER     NOT NULL DEFAULT 0,
    errors_after  INTEGER     NOT NULL DEFAULT 0,
    compiled      BOOLEAN     NOT NULL DEFAULT FALSE,
    noop          BOOLEAN     NOT NULL DEFAULT FALSE,
    elapsed_ms    BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    UNIQUE (task_id, round_no)
);
```

### 2.5 trace_step

```sql
CREATE TABLE trace_step (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT      NOT NULL REFERENCES code_task(id),
    step_name     VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL,   -- SUCCESS / FAILED / SKIPPED / DEGRADED
    elapsed_ms    BIGINT      NOT NULL DEFAULT 0,
    tokens        INTEGER     NOT NULL DEFAULT 0,
    detail        TEXT,
    seq           INTEGER     NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_trace_task ON trace_step (task_id, seq);
```

### 2.6 eval_sample

```sql
CREATE TABLE eval_sample (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    task_id       VARCHAR(64) NOT NULL,
    model_id      VARCHAR(64) NOT NULL,
    patches_total INTEGER     NOT NULL DEFAULT 0,
    patches_applied INTEGER   NOT NULL DEFAULT 0,
    compiled      BOOLEAN     NOT NULL DEFAULT FALSE,
    test_passed   BOOLEAN     NOT NULL DEFAULT FALSE,
    repair_rounds INTEGER     NOT NULL DEFAULT 0,
    tokens        INTEGER     NOT NULL DEFAULT 0,
    elapsed_ms    BIGINT      NOT NULL DEFAULT 0,
    failure_reason TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_model ON eval_sample (model_id, created_at DESC);
```

> 同一 `task_id` 的多条记录就是 Pass@k 的采样样本。

---

## 三、关键设计决策

### 3.1 所有业务表带 tenant_id + 复合索引

不是「靠应用代码记得过滤」，而是**让数据库层无法写出跨租户查询**。这与 Day15 的做法一致。

### 3.2 诊断结构化而非文本

`compile_diagnostic` 把 `file / line / column / code / message` 分开存，使得：
- 能按文件聚合「哪个文件最容易出错」；
- 能比较两轮错误集合是否相同（无进展检测）；
- 能直接定位到 IDE 里。

### 3.3 记录匹配策略而非只记结果

`patch_record.strategy = 'IGNORE_INDENT'` 是一个重要信号：它说明补丁靠容错才打上，评审时应当人工复核。

### 3.4 快照指纹单独存

`repo_snapshot(fingerprint)` 让「这次改动是否真的改变了东西」变成一个 O(1) 的比较，是自修复循环刹车第 3 条的数据基础。

---

## 四、ER 关系

```
code_task 1 ── N patch_record
         1 ── N compile_record ── N compile_diagnostic
         1 ── N test_record
         1 ── N repair_round
         1 ── N change_bundle_item
         1 ── 1 pr_draft
         1 ── N trace_step
         1 ── 1 budget_usage

eval_sample（独立，task_id 为业务键而非外键）
repo_snapshot（按 repo_id 组织）
```

---

## 五、迁移路径（当前内存实现 → 持久化）

| 当前类 | 目标表 | 说明 |
| --- | --- | --- |
| `VirtualFileSystem`（临时目录） | `repo_snapshot` + 真实 git worktree | 生产应直接操作 git 工作区 |
| `RepairAttempt` | `repair_round` | 字段一一对应 |
| `AgentStep` | `trace_step` | 需补 `seq` |
| `TaskOutcome` | `eval_sample` | 字段一一对应 |
| `ChangeBundle` | `change_bundle_item` | 每个文件一行 |
| `PullRequestDraft` | `pr_draft` | `body` 存 `render()` 结果 |
