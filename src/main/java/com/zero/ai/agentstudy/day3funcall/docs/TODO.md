# Day3 动手任务清单：Function Calling & Agent Assistant V1

> 按难度分级：⭐ 必做（打基础）｜⭐⭐ 进阶（练工程）｜⭐⭐⭐ 企业挑战（拔高）。
> 建议顺序完成，每完成一项在 `[ ]` 中打勾。

---

## ⭐ 必做（跑通六大入口）

- [ ] **启动服务**：确认本地大模型（OpenAI 兼容接口）就绪，`mvn spring-boot:run` 启动，端口 8080。
- [ ] **单工具**：浏览器访问 `/day3/weather?msg=北京今天天气怎么样`，观察控制台 `[WeatherTool] 被调用`。
- [ ] **多工具自动选择**：访问 `/day3/agent?msg=现在几点？顺便算下 12 加 8`，确认 LLM 自动选中 Time 与 Calculator 两个工具。
- [ ] **多工具协同**：访问 `/day3/workflow?msg=查一下杭州天气，把结果发邮件到 boss@example.com`，观察 Weather → Email 串行两轮调用。
- [ ] **工具目录**：访问 `/day3/tools` 查看分组（assistant/office/all）及工具数。
- [ ] **分组挂载**：访问 `/day3/group?group=office&msg=现在几点？发个通知邮件`，确认只挂载了 office 组工具。
- [ ] **收官 Agent**：访问 `/day3/assistant?msg=北京天气如何？现在几点？算 88 乘以 9`，验证三意图一次全部命中。
- [ ] **能力边界**：访问 `/day3/assistant?msg=帮我订一张明天去上海的机票`，确认「小智」礼貌拒绝而非幻觉硬答。

---

## ⭐⭐ 进阶（工程化打磨）

- [ ] **读懂三阶段协议**：对照日志，理解「发 tools 菜单 → LLM 返回 tool_calls → 执行工具回传 → 综合作答」的完整 Agent Loop。
- [ ] **改工具描述做实验**：把 `WeatherTool03` 的 `@Tool(description=...)` 改模糊，观察 LLM 是否还能正确路由，体会「描述即路由」。
- [ ] **参数校验兜底**：给 `/day3/workflow` 传一个非法邮箱，确认 `EmailTool03` 返回 `INVALID_PARAM` 而非炸穿 Loop。
- [ ] **加一个新工具（结课练习）**：新建一个「汇率查询」`@Tool` 方法（可参考 `mytest/StockTool.java`），挂到 assistant 组，体验「只加一个方法就扩展 Agent 能力」。
- [ ] **system prompt 调优**：修改 `AgentAssistantV1` 的 `SYSTEM_PROMPT`，收紧/放宽行为边界，观察回答风格与拒答行为变化。
- [ ] **分组治理验证**：给 `ToolRegistry03` 新增一个业务分组，通过 `/day3/group` 取用，理解「只把当前场景工具交给 LLM」的价值。

---

## ⭐⭐⭐ 企业挑战（生产级演进 V2）

- [ ] **多轮记忆**：给 `AgentAssistantV1` 接入 ChatMemory（可复用 Day2 `ConversationStore` 思路），支持「那上海呢」这类上下文追问。
- [ ] **动态工具治理**：让 `AgentAssistantV1` 从 `ToolRegistry03` 按场景/权限动态取工具，替代硬编码挂三工具。
- [ ] **权限校验**：为工具增加执行前硬校验，敏感/写操作（如发邮件）加二次确认。
- [ ] **可观测**：用 AOP 切面为所有 `@Tool` 方法打 traceId 日志，接 Micrometer 采集调用次数/耗时/成功率。
- [ ] **Loop 上限**：显式配置 `maxIterations`，构造死循环场景验证防护生效。
- [ ] **超时熔断降级**：接入 Resilience4j，对工具调用做超时+熔断，失败时返回降级话术。
- [ ] **写操作幂等**：为 `EmailTool03` 基于业务唯一键实现幂等，防止 LLM 重复调用导致重复发送。
- [ ] **流式输出**：把 `ask()` 改造成 SSE 流式（复用 Day2 stream 思路），提升体验。

---

## 自检清单（提交前对照）

- [ ] 六个入口均能正常返回，控制台能看到对应 `[XxxTool] 被调用` 日志。
- [ ] 多工具协同（workflow）能看到 Weather → Email 的串行多轮调用。
- [ ] 工具均返回结构化 JSON，非法参数返回 `INVALID_PARAM` 而非抛异常。
- [ ] `AgentAssistantV1` 有 try-catch 兜底，异常时对外仍有友好回复。
- [ ] 理解一个完整单 Agent 的四要素：system prompt（人设/边界）+ 工具集 + Agent Loop + 异常兜底。