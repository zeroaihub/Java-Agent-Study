# 第七章：企业最佳实践

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3 · 第七章
>
> 教学框架（五段式）：① 为什么学 → ② 是什么 → ③ 怎么用 → ④ 用在哪 → ⑤ 避坑与优化

> 本章演示代码已实现并编译通过：
> - 工具目录：`day3funcall/registry/ToolRegistry.java`
> - 分组入口：`Day3Controller` 的 `GET /day3/group`、`GET /day3/tools`
> - 系统提示对话：`Day3AgentService.chatWithSystem(...)`

---

## ① 为什么学（核心价值）

前六章你有 4 个工具。但真实企业级 Agent（AI 客服、办公助手）动辄**几十上百个工具**。此时新问题爆发：

| 规模化痛点 | 具体表现 |
|-----------|---------|
| **工具太多，prompt 爆炸** | 100 个工具全塞给 LLM，token 巨贵，选择还变差 |
| **选择准确率下降** | 工具越多，语义越容易重叠，LLM 越容易选错 |
| **权限失控** | 谁都能调“退款”“转账”工具，出事就是资损/合规事故 |
| **出问题查不到** | LLM 调错工具、传错参，没日志根本无法定位 |
| **异常炸穿** | 一个工具抛异常，整个 Agent 挂掉 |

**一句话价值**：这一章决定你的 Agent 能不能从“demo”走向“生产”。会写工具是入门，能管理上百个工具才是架构师。

---

## ② 是什么（概念与原理）

### 1. 工具目录（Tool Registry / Catalog）

核心思想：**不要把所有工具都丢给 LLM，按场景分组，按需挂载。**

```
                    ┌──────────────────────────┐
                 │      工具目录 Registry     │
                    ├──────────────────────────┤
   场景=assistant → │ [Weather, Time, Calc]     │ → 挂给"日常助手"Agent
   场景=office    → │ [Time, Email]             │ → 挂给"办公"Agent
   场景=finance   → │ [Stock, Order, Refund...] │ → 挂给"金融"Agent
                    └──────────────────────────┘
```

好处：每个场景只暴露相关的 5~10 个工具给 LLM，既省 token 又提升选择准确率。

### 2. 权限管理（谁能调哪个工具）

```
用户请求
   │ 携带身份/角色（如 普通客服 / 高级客服 / 管理员）
   ▼
权限过滤层：根据角色筛选"该用户被允许的工具子集"
   │  普通客服：可查订单，不可发起退款
   │  高级客服：可查订单 + 发起退款
   ▼
只把"有权限的工具"挂给 LLM
```

关键：**权限校验必须在工具执行前，不能指望 LLM 自觉。** LLM 是可被诱导的（prompt 注入），权限是硬边界。

### 3. 日志与可观测性

每次工具调用都应记录一条结构化日志：

```
traceId | 用户 | 工具名 | 入参 | 出参 | 耗时ms | 成功/失败 | 错误码
```

用途：审计（谁在什么时候做了什么）、排障（LLM 为什么选错）、优化（哪些工具最常用/最常失败）。

### 4. 异常处理（工具失败不能炸穿 Agent）

```
工具内部异常
   │
   ▼
捕获 → 转成结构化 error JSON 返回（而非抛出）
   │  {"code":"TIMEOUT","message":"天气服务超时"}
   ▼
LLM 收到 error → 优雅处理（告知用户/重试/换方案）
```

原则：**工具永远返回结果（正常 JSON 或 error JSON），永远不向上抛异常中断 Agent Loop。**

### 图解：企业级 Agent 工具治理架构

```
┌───────────────────────────────────────────────┐
│                用户请求(带身份)                   │
└───────────────────────┬───────────────────────┘
                       ▼
             ┌───────────────────┐
             │   权限过滤层        │  按角色筛选可用工具
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   工具目录 Registry │  按场景分组取用
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   LLM + ChatClient │  只见到"该场景+有权限"的工具
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   日志/审计切面     │  记录每次调用(traceId/入参/耗时)
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   工具执行 + 异常兜底│  失败转 error JSON, 不炸穿
             └───────────────────┘
```

---

## ③ 怎么用（实战演练）

### 工具目录（ToolRegistry）

按场景分组，编排层按 group 取用：

```java
@Component
@RequiredArgsConstructor
public class ToolRegistry {
    private final WeatherTool weatherTool;
    private final TimeTool timeTool;
    private final CalculatorTool calculatorTool;
    private final EmailTool emailTool;

    private final Map<String, List<Object>> groups = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        groups.put("assistant", List.of(weatherTool, timeTool, calculatorTool));
        groups.put("office",    List.of(timeTool, emailTool));
        groups.put("all",       List.of(weatherTool, timeTool, calculatorTool, emailTool));
    }

    public Object[] getToolsByGroup(String group) {
        return groups.getOrDefault(group, groups.get("assistant")).toArray();
    }
}
```

### 分组挂载入口（Controller）

```java
@GetMapping("/group")
public String group(@RequestParam String group, @RequestParam String msg) {
    // 只挂载该场景相关的工具，而不是全部
    return day3AgentService.chat(msg, toolRegistry.getToolsByGroup(group));
}
```

### 系统提示词（约束 Agent 行为）

```java
public String chatWithSystem(String systemPrompt, String userMessage, Object... tools) {
    return chatClient.prompt()
            .system(systemPrompt)   // ← 设定人设/边界，如"只回答业务问题，不得泄露内部信息"
            .user(userMessage)
            .tools(tools)
            .call().content();
}
```

### 权限过滤（伪代码思路）

```java
// 真实项目：从当前登录用户拿角色，筛选可用工具
List<Object> allowedTools = registry.getToolsByGroup(scene).stream()
        .filter(tool -> permissionService.canUse(currentUser, toolName(tool)))
        .toList();
chatClient.prompt().user(msg).tools(allowedTools.toArray()).call();
```

### 日志切面（AOP 思路）

```java
// 用 @Aspect 环绕所有 @Tool 方法，统一记录 traceId/入参/耗时/结果
@Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
public Object logTool(ProceedingJoinPoint pjp) throws Throwable {
    long t = System.currentTimeMillis();
    try {
        Object r = pjp.proceed();
        log.info("[TOOL] {} args={} cost={}ms ok", pjp.getSignature().getName(),
                 Arrays.toString(pjp.getArgs()), System.currentTimeMillis()-t);
        return r;
    } catch (Exception e) {
        log.error("[TOOL] {} failed", pjp.getSignature().getName(), e);
        return "{\"code\":\"TOOL_ERROR\",\"message\":\"工具执行异常\"}"; // 不炸穿
    }
}
```

### 运行验证

```
查看工具目录:   GET /day3/tools           → {"assistant":3,"office":2,"all":4}
按场景挂载:     GET /day3/group?group=office&msg=把"周会10点"发到 a@b.com
                → 日志显示 [ToolRegistry] 取用分组=office, 工具数=2
```

---

## ④ 用在哪（应用场景）

### 大型 AI 客服平台（上百工具的治理）

```
工具目录按业务域分组：
  订单域:   queryOrder / cancelOrder / modifyAddress ...
  售后域:  applyRefund / queryRefund / uploadEvidence ...
  会员域:   queryPoints / queryLevel / queryCoupon ...
  ...共 120+ 工具

一次会话按"用户问题分类"只挂载对应域的 8~12 个工具：
  用户问退款 → 只挂"售后域"工具 → LLM 选择又快又准

权限：
  普通用户 Agent：只读工具（query*）
  客服坐席 Agent：读 + 部分写（applyRefund）
  管理员 Agent：全部（含 forceRefund）
```

**价值**：这套治理让 Agent 在上百工具下依然稳定、安全、可审计——这正是商业化 Agent 的工程底座。

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **全量挂载工具** → prompt 爆炸、选择变差、成本飙升。必须按场景分组。
2. **靠 LLM 做权限判断** → 可被 prompt 注入绕过。权限必须是代码硬边界，在执行前校验。
3. **工具抛异常** → 中断 Agent Loop，整个会话崩。统一捕获转 error JSON。
4. **无 traceId** → 分布式下无法串联一次会话的多次工具调用，排障困难。
5. **description 大量重叠** → 工具多时 LLM 频繁选错。定期审查、去重、明确边界。

### 性能与工程化优化

- **分组 + 动态挂载**：按意图分类先粗筛场景，再挂该场景工具（可用一个轻量分类模型/规则）。
- **工具级限流/熔断**：外部依赖型工具加限流、熔断，防止被 LLM 高频调用打垮下游。
- **结果缓存**：只读、幂等工具的结果可短期缓存（如天气 5 分钟）。
- **统一超时**：所有工具设合理超时，超时返回 error JSON。
- **指标监控**：统计各工具调用量/成功率/P99 耗时，接入 Grafana 等看板。
- **灰度与版本**：工具升级用新增字段而非改字段；新工具灰度放量观察选择准确率。

### 面试高频问题

- **Q：几十上百个工具怎么管理？**
  A：建工具目录（Registry）按业务域/场景分组，每次会话只挂载相关子集，避免 prompt 膨胀与选择退化。
- **Q：工具权限怎么控制？**
  A：在工具执行前按用户角色做硬校验（代码层），绝不依赖 LLM 自觉，防 prompt 注入绕过。
- **Q：工具异常怎么处理才不影响 Agent？**
  A：工具内部捕获异常并返回结构化 error JSON，永不向上抛，保证 Agent Loop 能继续/优雅收尾。
- **Q：怎么排查 LLM 为什么调错工具？**
  A：全链路 traceId + 结构化日志（工具名/入参/结果/耗时），复盘调用轨迹与 description 匹配情况。

### 最佳实践清单

- ✅ 工具目录按场景分组，按需挂载（非全量）。
- ✅ 权限在执行前硬校验，不信任 LLM。
- ✅ 工具异常统一转 error JSON，不炸穿 Agent。
- ✅ 全链路 traceId + 结构化日志 + 指标监控。
- ✅ 外部依赖工具加超时/限流/熔断；只读工具可缓存。
- ✅ 系统提示词约束 Agent 行为边界。

---

## 本章总结

- **一句话**：会写工具是入门，能治理上百工具（分组/权限/日志/异常）才是架构师。
- **四大支柱**：工具目录（分组按需挂载）、权限（执行前硬校验）、可观测（traceId 日志）、异常兜底（转 error JSON）。
- **本章成果**：ToolRegistry + 分组入口 + 系统提示对话已实现，演示了工具治理的核心骨架。

---

### 本章练习（离线自测）

1. 访问 `GET /day3/tools` 查看工具目录分组；再访问 `GET /day3/group?group=office&msg=...` 观察只挂载了 2 个工具的日志。
2. 思考：你要做一个“AI 财务助手”，会怎么划分工具分组？哪些工具需要管理员权限？（提示：查询类可开放，转账/报销审批类需权限 + 二次确认。）

---

> 下一章 → [第八章：Agent Assistant V1 收官](chapter-08.md)（整合天气/时间/计算三工具，做一个能自动选择工具的完整 Agent，并对代码做企业级点评与优化建议）