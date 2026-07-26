# Day6 TODO 清单

> 分三个难度层级。⭐ 必做（掌握核心）｜⭐⭐ 进阶（工程能力）｜⭐⭐⭐ 企业挑战（架构能力）

---

## ⭐ 必做

- [ ] 读完 `docs/README.md`，能回答 5 个学习目标问题
- [ ] 理解 Node / Edge / State / Context / Engine 五个概念
- [ ] 手画一遍 Workflow 状态流转图
- [ ] 实现 `WorkflowNode` 接口和 5 个具体 Node
- [ ] 实现 `WorkflowContext`（共享数据袋）
- [ ] 实现 `WorkflowEngine`，能串行跑完旅行规划流程
- [ ] 跑通 REST 接口 `/day06/travel?city=北京`，看到 Markdown 行程
- [ ] 说清 Workflow 与普通 Java 流程的本质区别

---

## ⭐⭐ 进阶

- [ ] 为 `WeatherNode` 增加「失败重试 2 次 + 指数退避」
- [ ] 为每个 Node 增加超时控制（超过 3s 判失败）
- [ ] 实现「天气驱动的条件分支」：晴天户外方案 / 雨天室内方案
- [ ] 完整记录 `WorkflowExecutionLog`（每步耗时/输入/输出/状态）
- [ ] 用责任链 + 命令模式重构 Node 调度
- [ ] 增加 `HotelNode` 的策略模式（不同酒店数据源可切换）
- [ ] 编写一个单元测试验证「雨天走室内分支」

---

## ⭐⭐⭐ 企业挑战

- [ ] 把 Workflow 定义外置为 JSON/YAML，Engine 动态加载（配置化）
- [ ] 把 Context + ExecutionLog 持久化到 DB，支持断点恢复
- [ ] Engine 支持并行节点（fan-out / join）
- [ ] Engine 支持异步执行，返回 `CompletableFuture`
- [ ] 加入 Human-in-the-loop 节点：流程暂停等待人工审批
- [ ] 为 Workflow 定义加版本号，支持灰度与回滚
- [ ] 用 SpEL 表达式引擎实现动态条件路由
- [ ] 对接 Day5 RAG，新增「检索攻略」节点丰富行程内容
- [ ] 画一张前端可视化 DAG（把定义序列化为 JSON）

---

## 面试高频问题自测

1. Workflow 和 if/else + for 有什么区别？为什么不用后者？
2. 为什么 Engine 不能依赖具体 Node？
3. Context 为什么要显式传递而不用成员变量？
4. 重试为什么放在引擎而不是每个 Node 里？
5. 如何设计支持断点恢复的 Workflow？
6. 并行节点如何保证 Context 线程安全？
7. Workflow 版本管理为什么重要？怎么做？
8. Workflow 用到了哪些设计模式？各解决什么问题？