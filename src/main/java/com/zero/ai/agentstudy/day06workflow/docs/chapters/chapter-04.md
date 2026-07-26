# 第四章：把业务拆成节点（旅行规划的责任链落地）

> 前三章讲了理论。本章开始动手：以「旅行规划 Agent」为例，把一个完整业务
> 拆成 5 个独立节点，亲手实现责任链的每一环。

---

## 第一部分：为什么学（核心价值）

新手写 Agent 常犯的错：把「解析城市→查天气→查酒店→生成计划→输出」全塞进一个大方法。
结果是：
- 改一处怕影响全部；
- 想复用「查天气」到别的 Agent，抠不出来；
- 出错不知道卡在哪一步。

**节点化拆解**就是把这个大方法切成 5 段，每段是一个只做一件事的类。
这正是单一职责原则(SRP)的落地，也是责任链模式的基础。

---

## 第二部分：是什么（5 个节点的职责划分）

| 顺序 | 节点 | 单一职责 | 读 Context | 写 Context |
|---|---|---|---|---|
| 1 | `InputCityNode` | 从自然语言解析城市 | userInput | city |
| 2 | `WeatherNode` | 查天气 | city | weather |
| 3 | `HotelNode` | 查酒店（多源聚合） | city | hotels |
| 4 | `PlanNode` | 综合数据生成计划 | city/weather/hotels | plan |
| 5 | `OutputNode` | 汇总成 Markdown | 全部 | output |

关键约定：**节点之间不互相调用，只通过 Context 交换数据**。
InputCityNode 把 city 放进 Context，WeatherNode 再取出来——它们彼此不认识。

---

## 第三部分：怎么用（核心代码）

所有节点都实现同一个接口 `WorkflowNode`（见 `workflow/core/WorkflowNode.java`）：

```java
public interface WorkflowNode {
    String name();                       // 节点名，用于日志
    NodeResult execute(WorkflowContext context);  // 干活
    default int maxRetries() { return 0; }        // 重试次数
}
```

以 `WeatherNode` 为例，看「节点只编排、工具才干活」：

```java
@Component
@RequiredArgsConstructor
public class WeatherNode implements WorkflowNode {
    private final WeatherService weatherService;  // 真正查天气的工具

    public String name() { return "WeatherNode"; }

    public NodeResult execute(WorkflowContext context) {
        String city = context.getString(ContextKeys.CITY);   // 读上游数据
        if (city == null) return NodeResult.fail("缺少 city");
        String weather = weatherService.query(city);          // 调工具
        context.put(ContextKeys.WEATHER, weather);            // 写回结果
        return NodeResult.success(city + " 天气: " + weather); // 汇报
    }

    public int maxRetries() { return 2; }  // 天气接口不稳，允许重试
}
```

注意三步走：**读 Context → 调工具 → 写 Context + 返回 NodeResult**。这是所有节点的统一范式。

---

## 第四部分：Python 参考

Python 里等价写法（用抽象基类）：

```python
from abc import ABC, abstractmethod

class WorkflowNode(ABC):
    @abstractmethod
    def name(self) -> str: ...
    @abstractmethod
    def execute(self, ctx: dict) -> "NodeResult": ...

class WeatherNode(WorkflowNode):
    def __init__(self, weather_service):
        self.weather_service = weather_service
    def name(self): return "WeatherNode"
    def execute(self, ctx):
        city = ctx.get("city")
        if not city:
            return NodeResult.fail("缺少 city")
        ctx["weather"] = self.weather_service.query(city)
        return NodeResult.success(f"{city} 天气 ok")
```

LangGraph 里，每个节点就是一个函数 `def weather_node(state): ... return state`，思想完全一致。

---

## 第五部分：用在哪 + 避坑优化

**用在哪**：任何「多步骤业务流程」都能这样拆——订单流程、审批流程、数据 ETL、多轮对话 Agent。

**常见坑**：
1. **节点里直接 new 下一个节点调用** → 责任链变成硬编码调用链，失去可插拔性。下一步交给引擎。
2. **Context key 用魔法字符串** → 拼错不报错、排查困难。用 `ContextKeys` 常量集中管理。
3. **节点又查数据又格式化** → 违反 SRP。查数据交工具，格式化交 OutputNode。
4. **节点内吞异常返回 success** → 掩盖问题。该失败就返回 `NodeResult.fail`，交引擎决策。

**优化方向**：节点无状态（不存实例字段的业务数据），才能被引擎并发/复用。本例节点都符合。

---

## 面试问题

1. 为什么节点之间要通过 Context 通信，而不是直接调用？
2. 「节点」和「工具」的职责边界是什么？为什么要分开？
3. 如果要新增一个「查机票」能力，需要改哪些文件？（答案：新增一个 FlightNode，改配置里的节点顺序，其余零改动）

---

## 练习答案（参考）

> 练习：给旅行流程加一个「预算估算节点」，读取 hotels 算出人均花费。
> 参考：新建 `BudgetNode implements WorkflowNode`，`execute` 里 `context.get(HOTELS, List.class)`
> 解析价格求和，`context.put("budget", ...)`，返回 `NodeResult.success`。
> 然后在 `TravelWorkflowConfig` 的 List 中把它插到 PlanNode 之前即可——引擎无需改动。

---

> 下一章：这 5 个节点还是散的，谁来按顺序驱动它们、处理失败？答案是 **WorkflowEngine**。