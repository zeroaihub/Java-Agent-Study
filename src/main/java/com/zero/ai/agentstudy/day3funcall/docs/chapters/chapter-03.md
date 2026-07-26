# 第三章：Tool 设计原则

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3 · 第三章
>
> 教学框架（五段式）：① 为什么学 → ② 是什么 → ③ 怎么用 → ④ 用在哪 → ⑤ 避坑与优化

> 说明：本文档中的代码块是"教学示例"，编辑器可能把 Markdown 里的 JSON/Java 片段误报为语法错误，**不影响项目编译**，可忽略。

---

## ① 为什么学（核心价值）

### 一个血泪教训

某团队做 AI 客服，Tool 这样设计的：

```java
// 反例：Tool 返回自然语言
@Tool(description = "查询订单")
public String queryOrder(String orderId) {
    return "您的订单已经到达杭州，预计明天送达哦～";  // ← 返回一句人话
}
```

结果出了两个大问题：

1. **LLM 二次加工出错**：LLM 拿到"预计明天送达哦～"，又改写成"大概后天到"，语义漂移。
2. **无法程序化处理**：产品经理想加个"如果已签收就弹评价框"，但 Tool 返回的是一句话，代码没法判断"是否已签收"。

改成返回结构化 JSON `{"status":"IN_TRANSIT","city":"杭州","eta":"2024-06-13"}` 后，问题全部解决：LLM 照实翻译，前端能精确判断状态。

### 为什么 Tool 设计是分水岭

| 设计好 | 设计差 |
|--------|--------|
| LLM 选得准、传参对 | LLM 频繁选错工具 |
| 结果可程序化处理 | 结果只能"看"，不能"用" |
| 一个工具一件事，易维护 | 一个工具干十件事，改一处崩全部 |
| 几十个工具也井井有条 | 十个工具就乱成一团 |

**一句话价值**：Tool 设计能力，直接决定你的 Agent 能不能规模化（从 3 个工具扩展到 100 个）。

---

## ② 是什么（概念与原理）

### 原则一：Tool 为什么返回 JSON（而非自然语言）

核心原因有三：

1. **机器可解析**：JSON 有明确字段，代码能精确取值（`result.status`），自然语言不行。
2. **LLM 更可控**：给 LLM 结构化数据，它只负责"翻译成人话"，不会二次编造。
3. **可组合**：一个 Tool 的 JSON 输出，能直接作为下一个 Tool 的输入（Workflow 基础）。

```
自然语言："您的订单到杭州了"     → LLM 可能改写成"到江浙一带" → 语义漂移
结构化  ：{"city":"杭州","status":"IN_TRANSIT"} → LLM 照实翻译 → 精准
```

### 原则二：Tool 为什么不要返回自然语言

**分工原则**：LLM 负责"表达"，Tool 负责"提供事实"。

如果 Tool 也返回自然语言，就相当于"两个人都在说话"，LLM 会对 Tool 的话再加工，导致：
- 语义漂移（改写走样）
- 幻觉放大（LLM 基于模糊描述再脑补）
- 无法程序化（前端/后续逻辑拿不到结构化字段）

**记住**：Tool 是"数据源"，不是"发言人"。

### 原则三：Tool 单一职责（SRP）

一个 Tool 只做一件事，且把这件事做好。

```
反例（万能工具）：
  manageOrder(action, orderId, ...)   // action=query/cancel/refund/...
  问题：LLM 难以判断 action 传什么；改退款逻辑可能影响查询

正例（单一职责）：
  queryOrder(orderId)       // 只查
  cancelOrder(orderId)      // 只取消
  refundOrder(orderId)      // 只退款
  好处：LLM 语义匹配清晰；各自独立演进、独立测试
```

### 原则四：Tool 设计规范（六要素）

一个企业级 Tool 应该定义清楚：

| 要素 | 说明 | 示例 |
|------|------|------|
| **name** | 动词+名词，见名知意 | `queryStockPrice` |
| **description** | 写清"何时用我"，给 LLM 看 | "查询指定股票的实时价格。用户问股价/行情时用" |
| **参数** | 每个参数有类型 + 描述 + 是否必填 | `code: string, 股票代码, 必填` |
| **返回** | 结构化 JSON，字段固定 | `{"code","price","time"}` |
| **异常** | 失败也返回 JSON，不抛异常 | `{"error":"股票代码不存在"}` |
| **幂等** | 写操作可重复调用不出错 | 退款用 requestId 去重 |

### 图解：好 Tool vs 坏 Tool

```
┌──────────────── 坏 Tool ────────────────┐
│ manageEverything(type, data)            │
│  · 名字模糊 → LLM 不知何时调             │
│  · 参数万能 → LLM 不知传什么             │
│  · 返回自然语言 → 无法程序化              │
│  · 一改全崩 → 难维护                     │
└─────────────────────────────────────────┘

┌──────────────── 好 Tool ────────────────┐
│ queryStockPrice(code: 股票代码)          │
│  · 名字清晰 → LLM 一看就懂               │
│  · 参数明确 → LLM 好传参                 │
│  · 返回 {"code","price","time"} → 可组合  │
│  · 单一职责 → 独立演进/测试              │
└─────────────────────────────────────────┘
```

---

## ③ 怎么用（实战演练）

### Java：一个规范的企业级 Tool

```java
@Component
public class StockTool {

    /**
     * name 由方法名决定：queryStockPrice（动词+名词，清晰）
     * description 写清"何时用我"
     */
    @Tool(description = "查询指定股票的实时价格与涨跌幅。当用户询问股价、行情、某只股票多少钱时使用")
    public String queryStockPrice(
            @ToolParam(description = "股票代码，如 600519（贵州茅台）") String code) {

        // 1. 参数校验前置
        if (code == null || !code.matches("\\d{6}")) {
            // 2. 异常也返回结构化 JSON，不抛异常
            return "{\"error\":\"股票代码格式错误，应为6位数字\"}";
        }

        // 3. 真正查询（这里 mock）
        // 4. 返回结构化 JSON，字段固定
        return "{\"code\":\"" + code + "\",\"name\":\"贵州茅台\","
             + "\"price\":1680.50,\"changePct\":1.23,\"time\":\"2024-06-12 15:00\"}";
    }
}
```

### Python 对照（读开源用）

```python
from langchain_core.tools import tool
import json, re

@tool
def query_stock_price(code: str) -> str:
    """查询指定股票的实时价格与涨跌幅。当用户询问股价、行情时使用。code: 6位股票代码"""
    if not re.match(r"^\d{6}$", code or ""):
        return json.dumps({"error": "股票代码格式错误"})
    return json.dumps({"code": code, "name": "贵州茅台",
                       "price": 1680.50, "changePct": 1.23})
```

### 设计一个 Tool 的思考清单

1. 这个 Tool 只做一件事吗？（否 → 拆分）
2. 名字动词+名词、见名知意吗？
3. description 写清"何时用我"了吗？
4. 每个参数有类型+描述吗？
5. 返回是结构化 JSON 吗？字段固定吗？
6. 失败时返回 error JSON 而非抛异常吗？
7. 如果是写操作，幂等吗？

---

## ④ 用在哪（应用场景）

### 企业 Tool 设计案例：AI 量化交易的 StockTool 族

真实项目里，一个"股票"领域会拆成一族单一职责的 Tool：

| Tool | 职责 | 返回 JSON 关键字段 |
|------|------|-------------------|
| `queryStockPrice` | 查实时价 | code, price, changePct |
| `queryKline` | 查 K 线 | code, days, points[] |
| `queryFinance` | 查财报 | code, pe, roe, revenue |
| `placeOrder` | 下单（写，幂等） | orderId, status |
| `queryPosition` | 查持仓 | positions[] |

**LLM 的角色**：用户说"帮我看看茅台能不能买"，LLM 自动编排 `queryStockPrice` + `queryKline` + `queryFinance`，拿到结构化数据后综合分析。每个 Tool 各司其职，价格由 Tool 保证精确。

### 为什么结构化返回是 Workflow 的前提

```
queryStockPrice(600519) → {"price":1680}
        │ price 字段可直接被下一步使用
        ▼
riskCheck(price=1680, budget=100000) → {"canBuy":true,"maxQty":59}
        │ maxQty 字段可直接被下一步使用
        ▼
placeOrder(code=600519, qty=59) → {"orderId":"x","status":"SUCCESS"}
```

如果每一步返回的是自然语言，这条流水线根本串不起来。**结构化 = 可组合 = Workflow 基础。**

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **返回自然语言** → LLM 二次加工语义漂移，前端无法程序化。必返 JSON。
2. **万能工具** → 一个 Tool 塞 N 个功能，LLM 选参困难、维护灾难。坚持单一职责。
3. **description 写实现细节** → 写"调用了 XX 微服务的 YY 接口"没用，要写"用户问什么时候调我"。
4. **抛异常而非返回 error** → 异常会中断 Agent 循环，应返回 `{"error":"..."}` 让 LLM 优雅处理。
5. **返回字段不稳定** → 时有时无的字段让后续逻辑崩溃，字段结构要固定。
6. **参数无描述** → LLM 猜参数含义，传错值。每个参数都要写描述。

### 性能与工程化优化

- **返回精简**：只返 LLM/前端需要的字段，别把整个 DB 行塞回去（省 token）。
- **枚举值标准化**：状态用固定枚举（`IN_TRANSIT`/`DELIVERED`），别用中文描述，便于程序判断。
- **错误码体系**：error JSON 带 code（如 `INVALID_PARAM`/`NOT_FOUND`），便于统一处理。
- **版本兼容**：Tool 返回结构演进时用加字段而非改字段，避免破坏已有逻辑。
- **描述可迭代**：上线后观察 LLM 选错工具的 case，反向优化 description（这是调优主战场）。

### 面试高频问题

- **Q：Tool 为什么返回 JSON 而不是自然语言？**
  A：JSON 机器可解析、LLM 更可控（只翻译不编造）、可组合（作为下一 Tool 输入）。自然语言会导致语义漂移和无法程序化。
- **Q：Tool 设计的单一职责原则怎么理解？**
  A：一个 Tool 只做一件事。好处是 LLM 语义匹配清晰、各工具独立演进和测试、易维护扩展。
- **Q：Tool 执行失败应该抛异常还是返回错误？**
  A：返回结构化 error JSON。抛异常会中断 Agent 循环，返回 error 能让 LLM 优雅告知用户或重试。
- **Q：description 应该写什么？**
  A：写"何时该用我"（面向 LLM的语义线索），而不是实现细节。这是 LLM 选对工具的唯一依据。

### 最佳实践清单

- ✅ Tool 返回结构化 JSON，字段固定，用枚举值。
- ✅ 一个 Tool 只做一件事（单一职责）。
- ✅ name 动词+名词；description 写"何时用我"；每个参数有描述。
- ✅ 失败返回 error JSON（带错误码），不抛异常。
- ✅ 写操作幂等；返回精简；结构演进只加不改。

---

## 本章总结

- **一句话**：Tool 是"数据源"不是"发言人"——返回结构化 JSON，把"说话"留给 LLM。
- **四大原则**：返回 JSON、不返自然语言、单一职责、六要素规范（name/description/参数/返回/异常/幂等）。
- **规模化关键**：好的 Tool 设计让你从 3 个工具平滑扩展到 100 个。

---

## 本章练习

请你**设计一个 StockTool 的数据结构**，包括：

1. 工具名（name）
2. description（写清何时用）
3. 输入参数（类型 + 描述 + 是否必填）
4. 返回的 JSON 结构（列出字段 + 类型 + 含义）
5. 失败时的 error JSON 结构

> 提示：可参考本章 ④ 的 StockTool 族，先设计最核心的"查实时价"这一个即可。

---

> 下一章 → [第四章：Spring AI Tool Calling](chapter-04.md)（`@Tool`/`ToolCallback`/注册/调用/执行流程，一步步跑通 Java Demo）