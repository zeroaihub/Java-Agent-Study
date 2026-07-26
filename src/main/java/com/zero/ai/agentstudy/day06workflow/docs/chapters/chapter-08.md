# 第八章（终章）：整合 Travel Agent，跑通全链路

> 前七章：拆节点 → 造引擎 → 加装甲 → 谈架构。本章把 config → service → controller
> 三层串起来，让整个 Travel Agent 变成一个**可访问的接口**。你会看到前面所有概念汇成一条线。

---

## 第一部分：为什么学（核心价值）

再优雅的框架，用户也只关心一件事：**给个 URL，我能不能用**。
本章补上「最后一公里」——把内部的引擎与节点，用标准三层架构包装成 HTTP 接口。
这也是 Java 后端最经典的 `Controller → Service → 领域层` 分层落地。

---

## 第二部分：是什么（三层职责）

```
HTTP 请求
   │
   ▼
TravelController   ← 收参数、调 Service、返 DTO（不含业务逻辑）
   │
   ▼
TravelAgentService ← 组装一次运行：建 Context、放输入、调引擎、转 DTO
   │
   ▼
WorkflowEngine     ← 驱动节点链（前六章的成果）
   │
   ▼
[5 个节点]          ← 读写 Context、调工具
```

外加编排层 `TravelWorkflowConfig`：把 5 个节点装配成有序 List 供 Service 注入。
**每层只干一件事，这就是 SRP 在应用层的体现。**

---

## 第三部分：怎么用（三层代码串讲）

**① 编排层**（`config/TravelWorkflowConfig.java`）——定义流程顺序：
```java
@Bean("travelWorkflowNodes")
public List<WorkflowNode> travelWorkflowNodes(
        InputCityNode a, WeatherNode b, HotelNode c, PlanNode d, OutputNode e) {
    return List.of(a, b, c, d, e);
}
```

**② 服务层**（`service/TravelAgentService.java`）——组装一次运行：
```java
public TravelResponse plan(String userInput) {
    WorkflowContext context = new WorkflowContext();
    context.put(ContextKeys.USER_INPUT, userInput);   // 放入起点数据

    WorkflowResult result = engine.run(travelNodes, context); // 交给引擎

    return new TravelResponse(                          // 转成对外 DTO
            result.getRunId(),
            result.getState().name(),
            result.getOutput() != null ? result.getOutput() : "（未生成方案）",
            result.logsAsText());
}
```
注意 `@Qualifier("travelWorkflowNodes")` —— 精确注入①装配的那条链。

**③ 控制层**（`controller/TravelController.java`）——暴露 HTTP 入口：
```java
@RestController
@RequestMapping("/day6")
public class TravelController {
    @GetMapping("/travel")
    public TravelResponse travel(
            @RequestParam(defaultValue = "我想去杭州玩三天") String input) {
        return travelAgentService.plan(input);
    }
}
```

---

## 第四部分：跑起来 & 预期输出

**启动**（记得用 JDK 17，否则 Lombok 失效）：
```bash
cd agnetstudy-main
JAVA_HOME=/Users/ext.tuyue1/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home \
  mvn spring-boot:run
```

**访问**（浏览器或 curl）：
```bash
curl "http://localhost:8080/day6/travel?input=我想去杭州玩三天"
```

**预期返回**（JSON，字段来自 `TravelResponse`）：
```json
{
  "runId": "run-xxxxxxxx",
  "state": "COMPLETED",
  "plan": "# 杭州 3 天旅行方案\n## 天气：晴 25℃...\n## 推荐酒店：...\n## 行程：Day1...",
  "executionTrace": "[INPUT_CITY] SUCCESS attempt=1 cost=1ms\n[WEATHER] SUCCESS ...\n[OUTPUT] COMPLETED ..."
}
```
- `plan`：`OutputNode` 汇总的 Markdown 方案；
- `executionTrace`：第六章的执行日志，一眼看清每个节点的状态/重试/耗时。

---

## 第五部分：用在哪 + 全景回顾

**一次请求走过的全链路**：
1. Controller 收到 `input`；
2. Service 建 Context、放 `USER_INPUT`；
3. 引擎驱动链：InputCity(解析城市) → Weather(查天气) → Hotel(查酒店) → Plan(生成方案) → Output(汇总 Markdown)；
4. 每个节点「读 Context → 调工具 → 写 Context → 返 NodeResult」；
5. 引擎按状态推进 / 终止，全程记日志；
6. Service 把 `WorkflowResult` 转 `TravelResponse` 返回。

**避坑**：
1. **JDK 版本**：本项目必须 JDK 17，否则 Lombok 注解全失效、编译报错。
2. **端口占用**：8080 被占则改 `application.yml` 的 `server.port`。
3. **PlanNode 是规则实现**：为保证无外网依赖可独立运行。接真实大模型只需把 PlanNode 内部换成 Spring AI 的 `ChatClient`，引擎与其他节点零改动——这正是「面向接口」的红利。

---

## 面试问题

1. Controller / Service / 编排层各自的单一职责是什么？为什么不能把 `engine.run` 直接写进 Controller？
2. `@Qualifier("travelWorkflowNodes")` 解决了什么问题？如果有第二条流程链会怎样？
3. 若要把 PlanNode 从「规则」换成大模型」，需要改动哪些层？为什么改动面这么小？

---

## 练习答案（参考）

> 练习：新增一条「美食推荐」流程，复用同一个引擎。
> 参考：
> 1. 写 `FoodNode implements WorkflowNode`；
> 2. 在 config 加 `@Bean("foodWorkflowNodes")` 装配新链；
> 3. Service 用 `@Qualifier("foodWorkflowNodes")` 注入；
> 4. Controller 加 `/day6/food` 入口。
> **引擎、Context、结果模型全部复用，零改动**——这就是框架化的收益。

---

## Day6 结营总结

你已经从零造出一个**企业级 Workflow 框架 + Travel Agent**：
- 抽象层（接口/枚举/结果模型）→ Context 数据袋 → 工具层（策略模式）
- → 5 个节点（责任链）→ 引擎（状态机 + 重试 + 日志）→ 三层装配 → HTTP 接口。

覆盖设计模式（责任链/策略/状态机）、SOLID（SRP/OCP/DIP）、Spring IoC、可观测性。
**恭喜——你已经能独立设计并落地一个可编排、可扩展、可上线的 Agent Workflow 系统。**