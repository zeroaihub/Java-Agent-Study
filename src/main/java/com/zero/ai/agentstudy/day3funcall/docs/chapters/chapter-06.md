# 第六章：多个 Tool 协同工作（Weather + Email）

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3 · 第六章
>
> 教学框架（五段式）：① 为什么学 → ② 是什么 → ③ 怎么用 → ④ 用在哪 → ⑤ 避坑与优化

> 本章代码已实现并编译通过：
> - `day3funcall/tool/EmailTool.java`（写操作工具）
> - 协同入口：`day3funcall/controller/Day3Controller.java` 的 `GET /day3/workflow`

---

## ① 为什么学（核心价值）

第五章的多工具是“**并行独立**”：查天气和查时间互不相干，LLM 分别调用即可。

但真实业务往往是“**串行依赖**”：

> “查一下杭州天气，**把结果**发邮件给老板”

这里第二步（发邮件）依赖第一步（查天气）的输出。这就从“多工具选择”升级为“**多工具编排**”——也就是 **Workflow（工作流）的雏形**。

**一句话价值**：掌握工具协同，你就理解了 Agent 如何把“零散能力”串成“完整业务流程”，这是从“能调工具”到“能办事”的关键一跃。

---

## ② 是什么（概念与原理）

### 并行 vs 串行（两种协同）

```
并行（第五章）：意图之间无依赖
  用户"天气+时间" → [getWeather, getCurrentTime] 同时调 → 合并结果

串行（本章）：后一步依赖前一步输出
  用户"查天气并发邮件"
    → 第1步 getWeather("杭州") → {"temp":22,"desc":"多云"}
    → LLM 把天气组织成邮件正文
    → 第2步 sendEmail(to, subject, content="杭州今天多云22℃") → {"status":"SENT"}
```

### 核心原理：LLM 的多轮 Agent Loop

串行协同靠的是**多轮工具调用循环**（回顾你第二章画的图）：

```
用户："查杭州天气，把结果发邮件到 boss@example.com"
   │
   ▼ 第 1 轮
LLM 判断：先得知道天气 → tool_calls: getWeather("杭州")
   │
   ▼ Spring AI 执行 WeatherTool
返回 {"city":"杭州","temp":22,"desc":"多云","humidity":60}
   │
   ▼ 结果回传 LLM
   ▼ 第 2 轮
LLM 判断：现在有天气了，可以发邮件 → 组织正文
   tool_calls: sendEmail(
       to="boss@example.com",
       subject="杭州今日天气",
       content="杭州今天多云，气温22℃，湿度60%")
   │
   ▼ Spring AI 执行 EmailTool
返回 {"messageId":"msg_a1b2","status":"SENT"}
   │
   ▼ 结果回传 LLM
   ▼ 第 3 轮
LLM 判断：任务完成 → finish_reason=stop
   最终回答："已查询杭州天气（多云22℃）并发送邮件至 boss@example.com。"
```

**关键认知**：Spring AI 会自动执行这个多轮循环，你不需要手写“先调A再调B”的编排代码——**编排逻辑由 LLM 动态决定**。这正是 Agent 与传统硬编码 Workflow 的本质区别。

### Agent 编排 vs 传统 Workflow 编排

| 维度 | 传统 Workflow（如 Activiti/BPMN） | Agent 工具编排 |
|------|-----------------------------------|----------------|
| 流程定义 | 开发者硬编码 A→B→C | LLM 根据意图动态决定 |
| 灵活性 | 改流程要改代码 | 改需求只改 prompt/工具 |
| 适应性 | 只能走预设路径 | 能应对没预设过的组合 |
| 可控性 | 强（确定） | 较弱（需约束/校验） |

---

## ③ 怎么用（实战演练）

### EmailTool（写操作工具，注意幂等与校验）

```java
@Component
public class EmailTool {
    @Tool(description = "发送邮件给指定收件人。当用户需要把信息/通知/报告发送到某个邮箱时使用")
    public String sendEmail(
            @ToolParam(description = "收件人邮箱地址，如 user@example.com") String to,
            @ToolParam(description = "邮件主题") String subject,
            @ToolParam(description = "邮件正文内容") String content) {
        // 1. 参数校验：邮箱格式、主题非空
        if (to == null || !to.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$"))
            return "{\"code\":\"INVALID_PARAM\",\"message\":\"收件人邮箱格式不正确\"}";
        // 2. 真实项目调 JavaMailSender；写操作要幂等，防 LLM 重复调用重复发送
        String messageId = "msg_" + UUID.randomUUID().toString().substring(0, 8);
        // 3. 返回结构化 JSON
        return "{\"messageId\":\"" + messageId + "\",\"status\":\"SENT\",...}";
    }
}
```

### 协同入口（同时挂 Weather + Email）

```java
@GetMapping("/workflow")
public String workflow(@RequestParam String msg) {
    // 只需把两个工具都挂上，串行编排由 LLM 自动完成
    return day3AgentService.chat(msg, weatherTool, emailTool);
}
```

**注意**：你没有写任何“先查天气再发邮件”的顺序代码——顺序是 LLM 根据 `msg` 语义自己推断的。这就是 Agent 编排的魅力。

### 运行验证（离线对照日志）

```
访问:
  /day3/workflow?msg=查一下杭州天气，把结果发邮件到 boss@example.com

预期控制台日志（按顺序）:
  [Day3Agent] 收到用户输入: 查一下杭州天气...
  [WeatherTool] 被调用, city=杭州                    ← 第1轮
  [EmailTool] 被调用, to=boss@example.com, subject=... ← 第2轮
  [EmailTool] 邮件已发送(模拟), messageId=msg_xxxx
  [Day3Agent] 最终回答: 已查询杭州天气并发送邮件...

看到 WeatherTool 先、EmailTool 后，就证明串行协同成功。
```

### Python 对照（读开源用）

```python
# LangGraph 里，串行协同通常用 StateGraph 显式定义节点与边，
# 或用 create_react_agent 让 LLM 自主决定调用顺序（类似 Spring AI 的自动循环）
from langgraph.prebuilt import create_react_agent
agent = create_react_agent(llm, tools=[get_weather, send_email])
agent.invoke({"messages": [("user", "查杭州天气并发邮件给 boss@example.com")]})
```

---

## ④ 用在哪（应用场景）

工具协同 = 把企业里“跨系统的操作流程”自动化：

| 业务流程 | 工具协同链 |
|---------|-----------|
| **AI 办公助手：写日报** | 查考勤 → 查任务完成度 → 汇总 → 发邮件给主管 |
| **AI 客服：自动退款** | 查订单 → 校验退款政策 → 发起退款 → 发通知短信 |
| **AI 运维：故障告警** | 查监控指标 → 判断阈值 → 查负责人 → 发告警到企业微信 |
| **AI 量化：条件下单** | 查行情 → 算指标 → 风控校验 → 下单 → 记录日志 |

### 案例拆解：AI 运维告警 Agent

```
用户/定时触发："检查订单服务的 CPU，超过 80% 就通知值班人"

Agent 编排（4 个工具串行）：
  ① queryMetric("order-service","cpu")  → {"cpu":85}
  ② （LLM 判断 85 > 80，需要告警）
  ③ queryOnCall("order-service")        → {"name":"张三","phone":"..."}
  ④ sendAlert(to=张三, msg="CPU 85% 超阈值") → {"status":"SENT"}
汇总："订单服务 CPU 达 85%，已告警值班人张三。"
```

**价值**：把人肉“看监控→找人→通知”的流程，压缩成一句话触发的自动化 Agent。

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **写操作工具不幂等** → LLM 可能重复调用 sendEmail，导致重复发送。用业务唯一键去重。
2. **依赖信息缺失时硬发** → 天气没查到就发空邮件。前一步失败（返回 error）时，LLM 应中止或追问，工具设计要能表达失败。
3. **让 LLM 编排关键交易流程** → 涉及资金/不可逆操作，纯靠 LLM 顺序不可靠，应加确定性校验/审批环节。
4. **正文由 LLM 自由生成不受控** → 可能夹带幻觉。关键通知建议用模板，工具接收字段而非整段正文。
5. **多轮循环无上限** → LLM 反复调工具停不下来。要设最大轮次上限，防止死循环烧 token。

### 性能与工程化优化

- **最大轮次限制**：给 Agent Loop 设 maxIterations（如 5 轮），超出强制结束。
- **写操作加确认**：高风险工具（转账/退款）在执行前插入人工确认或二次校验。
- **失败可恢复**：前置工具失败返回明确 error，让 LLM 能决策“重试/换路径/告知用户”。
- **审计日志**：串行链路每一步都记 traceId，串起来能还原“LLM 为什么这么编排”。
- **正文模板化**：通知类邮件用固定模板 + 变量填充，减少 LLM 自由发挥带来的风险。

### 面试高频问题

- **Q：多工具串行协同靠什么实现？**
  A：靠 LLM 的多轮 Agent Loop——每轮根据已有结果决定下一个工具，框架自动执行并回传，直到 finish_reason=stop。
- **Q：Agent 工具编排和传统 Workflow 引擎的区别？**
  A：传统 Workflow 流程硬编码、走固定路径；Agent 编排由 LLM 动态决定顺序，灵活但可控性较弱，关键流程需加确定性约束。
- **Q：写操作工具要注意什么？**
  A：幂等（防重复调用）、参数校验、失败返回结构化 error、高风险操作加确认，全链路审计日志。

### 最佳实践清单

- ✅ 写操作工具幂等 + 参数校验 + 失败返 error JSON。
- ✅ Agent Loop 设最大轮次上限，防死循环。
- ✅ 高风险操作加人工确认/二次校验。
- ✅ 通知正文模板化，关键字段结构化传参。
- ✅ 串行链路全程审计日志（traceId）。

---

## 本章总结

- **一句话**：多工具协同 = LLM 多轮 Agent Loop 动态编排，前一步输出喂给后一步，这就是 Workflow 雏形。
- **核心区别**：编排顺序由 LLM 动态决定（非硬编码），灵活但需约束。
- **本章成果**：EmailTool + `/day3/workflow` 已实现，可验证“查天气→发邮件”串行协同。

---

### 本章练习（离线自测）

1. 启动访问 `/day3/workflow?msg=查一下杭州天气，把结果发邮件到 boss@example.com`，确认日志中 WeatherTool 先于 EmailTool。
2. 思考：如果查天气失败（返回 error），你希望 LLM 怎么做？（提示：中止并告知用户，而不是发一封“天气未知”的邮件——这需要 EmailTool/编排层能感知前置失败。）

---

> 下一章 → [第七章：企业最佳实践](chapter-07.md)（如何管理几十上百个工具、工具目录设计、权限、日志、异常处理）