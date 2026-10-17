# Day17 · API（接口文档）

## 一、通用约定

- 路由前缀：`/api/v2/code`
- 租户 / 用户 / 角色 **只能由网关通过 Header 注入**，请求体不接受这些字段
- 所有响应携带 `traceId`，可用于查询全链路

| Header | 必填 | 说明 |
| --- | --- | --- |
| `X-Tenant-Id` | 是 | 租户 ID，网关验签后注入 |
| `X-User-Id` | 是 | 用户 ID |
| `X-Roles` | 否 | 逗号分隔角色；缺则按只读处理 |
| `X-Repo-Id` | 是 | 目标仓库 |

---

## 二、提交编码任务

```
POST /api/v2/code/tasks
```

**请求体**

```json
{
  "requirement": "请给 Money 增加 multiply 方法，支持乘以一个整数倍率",
  "branch": "feature/agent-multiply",
  "maxRepairRounds": 3,
  "dryRun": false,
  "testClassName": "demo.order.MoneyTest"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `requirement` | string | 是 | 自然语言需求 |
| `branch` | string | 否 | 目标分支，默认 `main` |
| `maxRepairRounds` | int | 否 | 自修复轮次，默认 3，上限 5 |
| `dryRun` | boolean | 否 | 只出补丁不落盘 |
| `testClassName` | string | 否 | 验收用的测试类全限定名 |

> `maxRepairRounds` 在服务端被 `Math.min(5, max(0, n))` 夹紧——客户端不能要求无限重试。

**响应**

```json
{
  "taskId": "task-9947",
  "traceId": "trace-7ce42c31-421c-40f3-9518-43daefba6792",
  "status": "SUCCEEDED",
  "mergeable": true,
  "changedFiles": ["src/main/java/demo/order/Money.java"],
  "addedLines": 4,
  "removedLines": 0,
  "riskLevel": "中",
  "compiled": true,
  "testSummary": null,
  "reviewBlockers": [],
  "concerns": ["改动触及核心源码，建议由该模块的负责人复核"],
  "pullRequestBody": "# [修改] ...",
  "elapsedMs": 638,
  "tokens": 1514
}
```

**status 取值**

| 值 | 含义 |
| --- | --- |
| `SUCCEEDED` | 编译 + 测试全通过 |
| `TEST_FAILED` | 编译通过，测试未通过 |
| `COMPILE_FAILED` | 编译未通过（自修复已用尽） |
| `PATCH_FAILED` | 补丁一个都没落地 |
| `REJECTED_BY_SAFETY` | 被安全策略拒绝 |
| `BUDGET_EXCEEDED` | 超出预算 |
| `UNPARSEABLE` | 模型输出无法解析为补丁 |

**mergeable** 只有在「状态成功」且「没有 BLOCKER 级评审问题」时才为 true。

---

## 三、查询任务

```
GET /api/v2/code/tasks/{taskId}
```

教学实现未持久化任务，返回 `NOT_STORED`。生产应改为从 `code_task` 表读取。

---

## 四、查询链路追踪

```
GET /api/v2/code/tasks/{taskId}/trace
```

返回步骤列表（名称 / 状态 / 耗时 / token / 摘要）与瓶颈步骤。数据结构对应 `observability.AgentStep`。

---

## 五、触发评测

```
POST /api/v2/code/evaluate
```

```json
{
  "tasks": [
    {"taskId": "T-multiply", "requirement": "给 Money 增加 multiply 方法"},
    {"taskId": "T-pay", "requirement": "给 OrderService 增加 pay 方法"}
  ],
  "testClassName": null,
  "maxRepairRounds": 3,
  "samplesPerTask": 5
}
```

返回 `EvalReport`：样本数、Pass@1、Pass@3、补丁应用率、编译通过率、测试通过率、平均 token、平均修复轮次、失败归因分布。

---

## 六、错误码

统一使用 `CodeResult` 结构：`{success, data, code, message, hint}`。

| 段 | 模块 |
| --- | --- |
| CA-0xxx | 通用 / 核心 |
| CA-1xxx | Repository / VFS |
| CA-2xxx | Tools |
| CA-3xxx | Analysis / Context |
| CA-4xxx | Patch |
| CA-5xxx | Generator |
| CA-6xxx | Sandbox |
| CA-7xxx | Verify / Iterate |
| CA-8xxx | Safety / Review |
| CA-9xxx | Eval / Observability / Metering |

常见码：

| 码 | 含义 | 典型场景 |
| --- | --- | --- |
| CA-1004 | 路径穿越 | 模型输出 `../../etc/passwd` |
| CA-2005 | 工具被拒绝 | 无写权限调用 `write_file` |
| CA-4002 | 补丁未应用 | SEARCH 片段与当前文件不符 |
| CA-4004 | 补丁目标不唯一 | 同一片段出现 3 处 |
| CA-6001 | 沙箱超时 | 死循环 |
| CA-7001 | 编译失败 | 语法 / 类型错误 |
| CA-7004 | 修复轮次用尽 | 自修复未收敛 |
| CA-8001 | 危险命令 | 参数命中黑名单 |
| CA-8002 | 疑似密钥泄露 | 补丁里出现 `sk-proj-...` |
| CA-9002 | 预算超支 | token 超过上限 |

---

## 七、curl 示例

```bash
curl -X POST http://localhost:8080/api/v2/code/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: tenant-acme' \
  -H 'X-User-Id: zhangsan' \
  -H 'X-Roles: CODE_WRITER,CODE_EXECUTOR' \
  -H 'X-Repo-Id: order-service' \
  -d '{"requirement":"请给 Money 增加 multiply 方法"}'
```

无写权限时返回 403：

```json
{"success": false, "code": "CA-0004",
 "message": "当前用户 intern 没有写权限，无法执行编码任务"}
```
