# API · Enterprise Knowledge Agent V2

本文件描述 Knowledge Agent 对外的 REST 接口契约。所有接口:身份由网关校验后以 `AgentContext` 注入(Ch18);请求必带租户上下文(Ch15);响应统一包裹错误码(对应 `AgentErrorCode`)。

---

## 一、通用约定

### 1.1 认证与租户

- 认证:请求头 `Authorization: Bearer <JWT>`,由网关校验(应用不接受客户端自报身份);
- 租户/工作区/用户/角色由网关从 JWT 解析,注入服务端 `AgentContext`,**客户端无法伪造**;
- 链路:响应头 `X-Trace-Id` 回传本次 `traceId`,便于关联 Ch14 链路与 Ch18 审计。

### 1.2 统一响应包裹

成功:

```json
{ "success": true, "data": { }, "traceId": "..." }
```

失败:

```json
{ "success": false, "error": { "code": "KA-5001", "message": "无权访问该文档" }, "traceId": "..." }
```

### 1.3 错误码(节选,详见 AgentErrorCode)

| Code | 含义 | HTTP |
| --- | --- | --- |
| `KA-1001` | 参数非法(INVALID_ARGUMENT) | 400 |
| `KA-5001` | 无权访问(ACCESS_DENIED) | 403 |
| `KA-6002` | 答案未接地(ANSWER_UNGROUNDED) | 200(降级) |
| `KA-6003` | 校验失败(VERIFICATION_FAILED) | 200(降级) |
| `KA-7001` | 知识缺口(KNOWLEDGE_GAP_DETECTED) | 200(降级) |
| `KA-9001` | 配额超限(QUOTA_EXCEEDED) | 429 |
| `KA-9002` | 安全拦截(SECURITY_BLOCKED) | 400 |

---

## 二、问答接口

### 2.1 POST /api/v1/knowledge/ask — 提问

**Request**

```json
{
  "question": "公司年假政策是什么?",
  "workspaceId": "ws-01",
  "topK": 5,
  "stream": false
}
```

**Response(200)**

```json
{
  "success": true,
  "data": {
    "answer": "员工每年享有 10 天带薪年假。",
    "grounded": true,
    "confidence": 0.92,
    "citations": [
      { "documentId": "uuid", "chunkId": "uuid", "title": "员工手册", "snippet": "..." }
    ],
    "verification": { "passed": true, "reason": "ok" }
  },
  "traceId": "..."
}
```

**降级响应**:检索无结果或未接地时,`answer` 为拒答提示,`error.code` 提示原因(如 `KA-7001` 知识缺口),HTTP 仍 200——**宁可拒答不可幻觉**(Ch09/Ch10/Ch11)。

**流式**:`stream=true` 时改用 SSE(`text/event-stream`),逐 token 返回,降低首字延迟 TTFT(Ch17)。

### 2.2 POST /api/v1/knowledge/feedback — 反馈(Ch12)

```json
{ "traceId": "...", "helpful": false, "comment": "答案过时" }
```

响应 `{ "success": true }`;反馈异步回流用于评估与改进(Ch12/Ch13)。

---

## 三、知识管理接口(需管理员角色)

### 3.1 POST /api/v1/documents — 导入文档

```json
{
  "workspaceId": "ws-01",
  "title": "员工手册 2026",
  "sourceUri": "s3://...",
  "aclLabels": ["EMPLOYEE", "HR"],
  "content": "..."
}
```

响应返回 `documentId`;服务端异步分块 + 向量化 + 写 ES 索引(Ch05),完成后可检索。

### 3.2 DELETE /api/v1/documents/{id} — 删除文档

级联删除分块与向量,并清理相关缓存(Ch18 被遗忘权支撑)。

### 3.3 GET /api/v1/documents?workspaceId=ws-01 — 列表

仅返回**当前租户 + 有权限**的文档(Ch07 ACL + Ch15 隔离)。

---

## 四、运营接口(需运营/审计角色)

### 4.1 GET /api/v1/usage?dimension=LLM_TOKENS&period=2026-08 — 用量查询(Ch16)

```json
{ "success": true, "data": { "dimension": "LLM_TOKENS", "used": 128000, "quota": 500000 } }
```

### 4.2 GET /api/v1/audit?from=...&to=... — 审计查询(Ch18)

按当前租户返回审计事件列表(谁、何时、对什么资源、做了什么、结果)。

---

## 五、运维端点(Ch14/Ch19,仅内网)

| 端点 | 用途 |
| --- | --- |
| `GET /actuator/health/liveness` | 存活探针(查自身) |
| `GET /actuator/health/readiness` | 就绪探针(查依赖) |
| `GET /actuator/prometheus` | 指标抓取(Prometheus) |

> 运维端点只在内网/受限暴露,不对公网开放(Ch18 最小暴露面)。

---

## 六、限流与配额(Ch16)

- **配额**:按租户套餐(Plan)对各维度(REQUESTS/LLM_TOKENS/RETRIEVALS/STORAGE_CHUNKS)设上限,超限返回 `KA-9001`(429);
- **速率**:按 `tenant + user` 令牌桶限流,防突发;
- 执行前 pre-check(先判额度再执行),漏配默认最严(Ch16)。

---

## 七、版本与兼容

- 路径带版本 `/api/v1/`;破坏性变更升 `v2` 并保留 `v1` 过渡期;
- 响应字段只增不删(向后兼容);
- 契约变更同步更新本文件并纳入 CI 契约测试。