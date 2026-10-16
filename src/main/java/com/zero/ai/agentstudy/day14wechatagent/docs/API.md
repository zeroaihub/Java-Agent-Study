# API 接口文档（API）

> 项目：Day14 Enterprise AI WeChat Agent
> 基址：`http://localhost:8080`
> 统一响应体：`ApiResponse<T>`（`code` 为业务码，独立于 HTTP 状态码）

---

## 0. 统一响应约定

所有 REST 接口统一返回 `ApiResponse<T>`：

```json
{
  "code": 0,
  "message": "OK",
  "data": { },
  "timestamp": "2026-08-04T10:00:00Z"
}
```

**业务码（`code`）与 HTTP 状态码是两套体系：**

| 业务码 | 常量 | 含义 |
| --- | --- | --- |
| 0 | `CODE_SUCCESS` | 成功 |
| 1 | `CODE_BIZ_ERROR` | 业务错误 |
| 400 | `CODE_INVALID_PARAM` | 参数校验失败 |

**全局异常映射（`GlobalExceptionHandler`）：**

| 异常 | HTTP 状态 | 业务码 | 说明 |
| --- | --- | --- | --- |
| `MethodArgumentNotValidException` | 400 | 400 | 字段校验失败，返回具体字段错误 |
| `IllegalArgumentException` | 400 | 1 | 业务参数非法 |
| `Exception`（兜底） | 500 | 1 | 不泄露堆栈，仅日志记录 |

---

## 1. 智能体接口（AgentController · 终极场景）

基路径：`/api/agent`

### 1.1 下达指令，触发会议纪要全链路

`POST /api/agent/instruct`

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| tenantId | string | 是 | 租户标识 |
| userId | string | 是 | 企微用户 ID |
| conversationId | string | 是 | 会话标识（单聊为用户、群聊为群 ID） |
| instruction | string | 是 | 自然语言指令 |

```bash
curl -X POST http://localhost:8080/api/agent/instruct \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId": "acme",
    "userId": "u_1001",
    "conversationId": "group_dev",
    "instruction": "帮我整理今天所有会议内容，生成纪要并发给项目组，重要内容存知识库"
  }'
```

**响应（命中审批挂起）：**

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "taskId": "task-abc123",
    "status": "SUSPENDED",
    "pendingStepId": "step-notify-04",
    "message": "任务已挂起，等待人工审批步骤：发送通知",
    "steps": [
      {"stepId": "step-summary-01", "name": "生成会议纪要", "status": "SUCCEEDED"},
      {"stepId": "step-upload-02",  "name": "上传归档",     "status": "SUCCEEDED"},
      {"stepId": "step-kb-03",      "name": "存入知识库",   "status": "SUCCEEDED"},
      {"stepId": "step-notify-04",  "name": "发送通知",     "status": "WAITING_APPROVAL"}
    ]
  }
}
```

### 1.2 人工审批放行

`POST /api/agent/{taskId}/steps/{stepId}/approve`

从挂起点续跑，跑完剩余步骤。

```bash
curl -X POST http://localhost:8080/api/agent/task-abc123/steps/step-notify-04/approve
```

**响应（续跑完成）：** `data.status` 变为 `COMPLETED`，通知已送达。

### 1.3 人工驳回

`POST /api/agent/{taskId}/steps/{stepId}/reject`

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| reason | string | 是 | 驳回原因 |

```bash
curl -X POST http://localhost:8080/api/agent/task-abc123/steps/step-notify-04/reject \
  -H 'Content-Type: application/json' \
  -d '{"reason": "纪要内容需要修改后再发"}'
```

**响应：** `data.status` 变为 `FAILED`，通知不会发出。

---

## 2. 通知接口（NotificationController）

基路径：`/api/notifications`

### 2.1 发送通知

`POST /api/notifications`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| tenantId | string | 是 | 租户 |
| target | string | 是 | 接收方（群 ID / 用户 ID） |
| content | string | 是 | 消息内容（支持 Markdown） |
| dedupeKey | string | 否 | 幂等键，重复 key 不重复发送 |

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId": "acme",
    "target": "group_dev",
    "content": "# 会议纪要\n- 决议一\n- 决议二",
    "dedupeKey": "meeting-20260804"
  }'
```

**响应：** 返回 `NotificationView`，含通知 ID 与状态（`SENT` / `DEAD`）。

---

## 3. Webhook 接口（WebhookController）

基路径：`/api/webhook`

### 3.1 接收企微回调

`POST /api/webhook/wecom`

| Header | 说明 |
| --- | --- |
| `X-Signature` | 签名，用于验签防伪造 |

**返回码设计（防无限重试）：**

| 场景 | 返回 | 说明 |
| --- | --- | --- |
| 处理成功 | 200 success | 正常受理 |
| 重复回调 | 200 success | 已处理过，直接返回成功避免无限重试 |
| 验签失败 | 401 | 拒绝伪造请求 |

---

## 4. 管理后台接口（AdminController）

基路径：`/api/admin`

### 4.1 死信看板聚合

`GET /api/admin/dashboard?tenantId=acme`

**响应：** 返回死信统计聚合（按渠道/时间），供运维查看失败通知并人工重投。

### 4.2 死信重投

`POST /api/admin/notifications/{id}/retry`

将指定死信通知重新投递。

---

## 5. 错误响应示例

**参数校验失败（HTTP 400，业务码 400）：**

```json
{
  "code": 400,
  "message": "指令不能为空",
  "data": null,
  "timestamp": "2026-08-04T10:00:00Z"
}
```

**服务器内部错误（HTTP 500，业务码 1）：**

```json
{
  "code": 1,
  "message": "服务器内部错误，请稍后重试",
  "data": null,
  "timestamp": "2026-08-04T10:00:00Z"
}
```

---

## 6. 约定与最佳实践

- **业务码优先判断**：客户端应先看 `code` 判断业务成败，`HTTP 状态`用于传输层判断。
- **幂等**：涉及副作用的接口（发通知、下指令）建议携带幂等键。
- **多租户**：所有接口必须带 `tenantId`，越权访问在服务层拦截。
- **Markdown**：通知内容支持 Markdown，企微侧渲染。
- **鉴权**：生产环境 `/api/admin` 与 `/api/agent` 需接入鉴权（本教学版省略）。