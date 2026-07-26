# Day6 架构文档：Workflow Agent 系统设计

> 本文档描述 Day6 Workflow Agent（Travel Agent）的整体架构、模块关系、状态流转与设计取舍。

---

## 一、系统架构图

```
                          ┌─────────────────────────┐
        HTTP 请求  ───────▶│   TravelController       │  (controller 层)
        城市名             └────────────┬────────────┘
                                        │ 调用
                                        ▼
                          ┌─────────────────────────┐
                       │   TravelAgentService     │  (service 层：编排入口)
                          │  1. 组装 Workflow 定义    │
                          │  2. 初始化 Context        │
                          │  3. 交给 Engine 执行      │
                          └────────────┬────────────┘
                                        │
                                        ▼
                    ┌───────────────────────────────────────┐
                    │        WorkflowEngine (engine 层)       │
                    │  调度 Node、传递 Context、重试/超时/日志 │
                    └───────┬───────────────────────┬────────┘
                            │ 依次执行               │ 读写
                            ▼                        ▼
              ┌──────────────────────────┐   ┌──────────────────┐
              │  WorkflowNode 实现集合     │   │  WorkflowContext │
              │  (node 层)                │◀─▶│  (context 层)    │
              │  · InputCityNode          │   │  共享数据袋      │
              │  · WeatherNode  ─调─▶Tool  │   └──────────────────┘
              │  · HotelNode    ─调─▶Tool  │
              │  · PlanNode     ─调─▶LLM/规则│
              │  · OutputNode             │
              └──────────────────────────┘
                            │ 每步产生
                            ▼
                    ┌──────────────────┐
                    │ WorkflowExecutionLog │ (model 层：可回溯)
                    └──────────────────┘
```

---

## 二、模块关系图

```
controller ──▶ service ──▶ workflow.engine ──▶ workflow.core (抽象)
                              │                     ▲
                              │                     │ 实现
                              ├──▶ workflow.node ───┘
                              ├──▶ workflow.context
                              ├──▶ workflow.executor
                              └──▶ workflow.model
                                        │
node ──▶ tool (WeatherTool / HotelTool) │
node ──▶ (可选) day05rag / day03 tool    │ 复用前几天成果
```

**依赖方向原则（依赖倒置 DIP）：**
- `engine` 只依赖 `core` 里的抽象接口（`WorkflowNode`、`NodeResult`），不依赖具体 Node。
- 具体 `node` 实现 `core` 接口，可任意增删，符合开闭原则（OCP）。
- `context` 是纯数据，不依赖任何业务，避免循环依赖。

---

## 三、Workflow 状态流转图

```
        ┌──────────┐
        │  CREATED │  工作流实例创建，Context 初始化
        └────┬─────┘
             ▼
        ┌──────────┐   next()      ┌──────────┐
        │ RUNNING  │──────────────▶│ 执行 Node │
        └────┬─────┘◀──────────────└────┬─────┘
             │        NodeResult=CONTINUE │
             │                            │
   NodeResult=FAILED           NodeResult=SUCCESS(最后一个)
   且重试用尽                            │
             ▼                            ▼
        ┌──────────┐                ┌──────────┐
        │  FAILED  │                │COMPLETED │
        └──────────┘                └──────────┘

   (可选) 任意 RUNNING 状态可进入 SUSPENDED（Human-in-the-loop），
          人工确认后回到 RUNNING。
```

**单个 Node 内部状态：**

```
 进入 ──▶ [执行 Action] ──成功──▶ 返回 SUCCESS/CONTINUE(next)
              │
            异常
              ▼
        [是否可重试?]──是──▶ 等待 backoff ──▶ 重新执行(次数-1)
              │
              否 / 次数用尽
              ▼
         返回 FAILED
```

---

## 四、Agent 执行流程（Travel Agent）

```
1. 用户 POST /day06/travel?city=北京
2. TravelAgentService 构建 Workflow：
      InputCityNode → WeatherNode → HotelNode → PlanNode → OutputNode
3. WorkflowEngine 逐节点执行：
      · InputCityNode : 校验城市写入 Context
      · WeatherNode   : 调 WeatherTool，天气写入 Context（失败重试 2 次）
      · HotelNode     : 调 HotelTool，酒店列表写入 Context
      · PlanNode      : 读 Context 天气→条件分支（晴/雨）生成计划
      · OutputNode    : 汇总成 Markdown 写入 Context.result
4. 引擎收集每步 ExecutionLog（耗时/输入/输出/状态）
5. Service 返回 { markdown 行程, 执行日志 }
```

---

## 五、为什么这样设计

| 设计决策 | 原因 |
|----------|------|
| Node 独立、单一职责 | 可插拔、可测试、可复用，符合 SRP |
| Engine 只依赖抽象 | 新增 Node 不改引擎，符合 OCP/DIP |
| Context 显式共享 | 状态可持久化，为断点恢复打基础 |
| NodeResult 统一结构 | 引擎可统一处理成功/失败/跳转 |
| 重试/超时放在引擎 | 横切关注点集中管理，Node 只写业务 |
| 执行日志独立模型 | 可观测性，问题可回溯 |

---

## 六、企业为什么这样设计

1. **解耦**：业务方只写 Node，平台方维护 Engine，两边独立演进。
2. **配置化**：流程定义可外置到 DB/JSON，产品经理拖拽即可改流程，无需发版。
3. **可观测**：每个 Node 的 I/O 入日志/链路追踪，线上问题可定位到具体步骤。
4. **容错**：重试、超时、降级统一在引擎，避免每个业务各写一套。
5. **可恢复**：Context 持久化后，宕机可从上次成功节点续跑，保证幂等与一致性。

---

## 七、后续如何扩展

- **并行节点**：Engine 支持一个节点 fan-out 到多个子节点并发执行后 join。
- **条件路由升级**：把 if 判断抽成 `Condition` 接口 + 表达式引擎（SpEL）。
- **持久化**：把 Context / ExecutionLog 存入 DB（对应 entity 层），支持恢复。
- **异步**：Engine 提供 `executeAsync` 返回 `CompletableFuture`。
- **可视化**：把 Workflow 定义序列化为 JSON，前端用图形库渲染 DAG。
- **版本管理**：Workflow 定义带 version，运行中实例锁定旧版本，新实例用新版本。
- **对接框架思想**：借鉴 LangGraph 的「图 + 状态」与 Spring AI 的 Advisor 链。