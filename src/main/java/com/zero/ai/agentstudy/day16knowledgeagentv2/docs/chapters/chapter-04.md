# Chapter 04 · Query Rewrite 与 Query Decomposition

> 本章目标：讲清用户问题为何「不能直接拿去检索」，掌握 **Query 规范化、改写、扩展、分解** 四类查询优化技术，并用可运行代码实现一个可插拔的 `QueryPlanner`，把「烂 query」变成「好 query」。

---

## 一、为什么要学（Why）

路由(Ch03)决定了「问题去哪个库」，但**用户的原始问题几乎从来不是好的检索输入**。看几个真实例子：

| 用户实际输入 | 问题所在 | 直接检索的后果 |
| --- | --- | --- |
| 「那个休假的规定是啥」 | 口语化、有指代词「那个」 | 向量召回一堆噪声 |
| 「报销」 | 太短，信息量不足 | 召回宽泛，Top-K 全是边角料 |
| 「入职要办工牌吗，还有社保怎么弄」 | **一句话两个子问题** | 检索被两个话题稀释，两个都答不好 |
| 「去年的年假政策和今年比有啥变化」 | 隐含时间对比，需拆解 | 单次检索无法覆盖「去年+今年」 |
| 「JYU 的报销标准」 | 缩写/黑话(JYU=某事业部) | 检索不到，因为文档里写的是全称 |

**核心矛盾**：用户用「人话」提问，而向量库/关键词库需要「规范化、完整、单一意图」的查询。Query 优化就是这两者之间的**翻译层**。

这一步的收益极高：一个好的 Query Rewrite 往往能把检索召回率提升 20%~40%，而成本只是一次轻量 LLM 调用。**它是整条推理链里性价比最高的优化点之一。**

---

## 二、是什么（What）

### 2.1 四类查询优化技术

```
原始问题
   │
   ▼
[1] Normalization 规范化 —— 去指代、纠错、展开缩写、统一术语
   │
   ▼
[2] Rewrite 改写 —— 补全上下文,变成语义完整的查询
   │
   ▼
[3] Expansion 扩展 —— 生成同义词/近义表达,提升召回
   │
   ▼
[4] Decomposition 分解 —— 一个复合问题 → 多个原子子问题
   │
   ▼
一组「可检索的规范化查询」→ 交给 Multi-Query Retrieval(Ch05)
```

### 2.2 逐一拆解

**① Normalization（规范化）**
- 消解指代：「那个休假规定」→「休假规定」
- 展开缩写：「JYU 报销标准」→「京东云事业部 报销标准」
- 统一术语：用户说「年假」，文档写「带薪年休假」→ 归一
- 纠错：「报箱标准」→「报销标准」

**② Rewrite（改写）**
- 结合对话历史补全：上一句问「年假几天」，这句问「那病假呢」→ 改写成「病假有几天」
- 让残缺问题语义完整：「报销」→「差旅费用的报销标准是什么」

**③ Expansion（扩展）**
- 生成多种表达：「怎么请年假」→ 同时生成「年假申请流程」「如何提交年休假」「年假审批步骤」
- 目的：向量检索对表达方式敏感，多种表达能覆盖更多相关文档

**④ Decomposition（分解）**
- 复合问题拆原子：「入职要办工牌吗，社保怎么弄」→ ①「入职是否需要办理工牌」 ②「入职社保如何办理」
- 每个子问题独立检索，各自拿到最相关证据，最后汇总回答

### 2.3 什么时候用哪种？

不是每个问题都要跑完四步——那样又慢又贵。**用一个轻量分类器/规则先判断问题类型**：

| 问题特征 | 应用技术 |
| --- | --- |
| 短、含指代、有缩写 | Normalization + Rewrite |
| 表达单一但可能召回不足 | Expansion |
| 明显包含多个「和/还有/以及」子问题 | Decomposition |
| 简单明确的问题 | 直接透传，不优化 |

### 2.4 与后续节点的衔接

Query Planner 的输出是**一组查询**（1 到 N 条），交给 Ch05 的 Multi-Query Retrieval 并行检索，再由 Ch06 的 RRF 融合。所以本章的产物是「查询计划(QueryPlan)」，而非单条字符串。

---

## 三、怎么用（How）

> 本章实现可插拔的 `QueryPlanner` 端口 + 一个规则版实现，代码可独立编译运行。真实实现会把规则替换为 LLM 调用。

### 3.1 定义查询计划:QueryPlan

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.query;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.List;

/**
 * 查询计划:Query 优化后的产物。
 *
 * <p>一个原始问题经过规范化/改写/扩展/分解后,可能变成多条子查询。
 * 该计划将交给 Multi-Query Retrieval(Ch05)并行检索。</p>
 *
 * @param original    用户原始问题
 * @param subQueries  优化后的子查询列表(至少一条)
 * @param decomposed  是否发生了分解(用于后续答案聚合策略)
 */
public record QueryPlan(String original, List<String> subQueries, boolean decomposed) {

    public QueryPlan {
        AgentException.requireText(original, "original");
        if (subQueries == null || subQueries.isEmpty()) {
            throw new AgentException(
                    com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode.INVALID_ARGUMENT,
                    "subQueries 至少要有一条"
            );
        }
        subQueries = List.copyOf(subQueries);
    }

    /** 单查询计划(未分解)的便捷构造。 */
    public static QueryPlan single(String original, String rewritten) {
        return new QueryPlan(original, List.of(rewritten), false);
    }
}
```

### 3.2 定义查询规划端口:QueryPlanner

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.query;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

/**
 * 查询规划端口(Hexagonal Port)。
 *
 * <p>把用户「人话」问题翻译为「可检索的规范化查询计划」。
 * 实现可以是规则版、LLM 版或两者混合。</p>
 */
public interface QueryPlanner {

    /**
     * 生成查询计划。
     *
     * @param question 用户原始问题
     * @param context  上下文(可用于结合对话历史改写)
     * @return 查询计划(可能含多条子查询)
     */
    QueryPlan plan(String question, AgentContext context);
}
```

### 3.3 实现规则版查询规划器:RuleBasedQueryPlanner

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.query;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则版查询规划器:演示规范化 → 分解 → 扩展的完整流程。
 *
 * <p>真实生产会把规则替换为一次 LLM 调用(给定 few-shot,输出 JSON 查询计划),
 * 但规则版足以讲清「查询优化在做什么」,且零成本、可解释。</p>
 */
public class RuleBasedQueryPlanner implements QueryPlanner {

    /** 缩写词典:黑话 → 全称。真实实现从配置中心加载。 */
    private static final Map<String, String> ABBREVIATIONS = Map.of(
            "JYU", "京东云事业部",
            "年假", "带薪年休假"
    );

    /** 指代词,规范化时剔除。 */
    private static final List<String> PRONOUNS = List.of("那个", "这个", "那种", "这种");

    /** 分解分隔符:出现这些词往往意味着复合问题。 */
    private static final List<String> SPLITTERS = List.of("，还有", "，以及", "，然后", "?还有");

    @Override
    public QueryPlan plan(String question, AgentContext context) {
        AgentException.requireText(question, "question");

        // 1. 规范化:去指代 + 展开缩写 + 统一术语
        String normalized = normalize(question);

        // 2. 分解:复合问题拆成原子子问题
        List<String> atoms = decompose(normalized);
        boolean decomposed = atoms.size() > 1;

        // 3. 扩展:仅在单问题且过短时做同义扩展,避免子查询爆炸
        List<String> subQueries = new ArrayList<>();
        if (!decomposed && normalized.length() <= 6) {
            subQueries.addAll(expand(normalized));
        } else {
            subQueries.addAll(atoms);
        }

        return new QueryPlan(question, subQueries, decomposed);
    }

    private String normalize(String q) {
        String r = q;
        for (String p : PRONOUNS) {
            r = r.replace(p, "");
        }
        for (var e : ABBREVIATIONS.entrySet()) {
            r = r.replace(e.getKey(), e.getValue());
        }
        return r.trim();
    }

    private List<String> decompose(String q) {
        for (String sp : SPLITTERS) {
            if (q.contains(sp)) {
                List<String> parts = new ArrayList<>();
                for (String part : q.split(java.util.regex.Pattern.quote(sp))) {
                    if (!part.isBlank()) {
                        parts.add(part.trim());
                    }
                }
                return parts;
            }
        }
        return List.of(q);
    }

    private List<String> expand(String q) {
        // 演示:为过短查询补充常见检索表达。真实实现由 LLM 生成同义改写。
        return List.of(q, q + "标准", q + "流程");
    }
}
```

### 3.4 跑起来

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.query;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.Set;

public class Chapter04Demo {

    public static void main(String[] args) {
        var ctx = AgentContext.of(TenantId.of("t1"), WorkspaceId.of("w1"),
                UserId.of("u1"), Set.of("EMPLOYEE"));
        var planner = new RuleBasedQueryPlanner();

        // 场景 1:含指代 + 缩写 → 规范化
        print(planner.plan("那个 JYU 的年假规定", ctx));

        // 场景 2:过短 → 扩展
        print(planner.plan("报销", ctx));

        // 场景 3:复合问题 → 分解
        print(planner.plan("入职要办工牌吗，还有社保怎么弄", ctx));
    }

    private static void print(QueryPlan plan) {
        System.out.println("原始: " + plan.original());
        System.out.println("  分解: " + plan.decomposed());
        System.out.println("  子查询: " + plan.subQueries());
        System.out.println("----");
    }
}
```

**运行输出（示意）:**

```
原始:  那个 JYU 的年假规定
  分解: false
  子查询: [京东云事业部 的带薪年休假规定]
----
原始: 报销
  分解: false
  子查询: [报销, 报销标准, 报销流程]
----
原始: 入职要办工牌吗，还有社保怎么弄
  分解: true
  子查询: [入职要办工牌吗, 社保怎么弄]
----
```

**看关键点**：同一个 `QueryPlanner` 端口，对不同类型的问题自动选择了不同优化策略——规范化、扩展、分解。产物统一为 `QueryPlan`，直接喂给 Ch05 的多路检索。**规则版和 LLM 版对上层完全透明**，这正是端口抽象的价值。

---

## 四、真实项目中的应用

在 ZeroHub 平台里，`QueryPlanner` 的生产实现是这样的：

1. **LLM 一次调用输出结构化计划**：给模型一段带 few-shot 的 Prompt，要求输出 JSON：`{normalized, subQueries[], decomposed}`。用 Spring AI 的结构化输出直接映射到 `QueryPlan`。
2. **结合对话记忆改写**：从 `AgentContext` 关联的会话历史里取上一轮问题，解决「那它呢/病假呢」这类指代。
3. **术语词典热更新**：缩写、黑话、同义词表放在配置中心，运营可随时补充，无需发版。
4. **降级保护**：LLM 超时或失败时，自动降级为规则版或直接透传原始问题，保证链路不断。

```java
// 生产:LLM 版查询规划(Spring AI 结构化输出示意)
QueryPlan plan;
try {
    plan = chatClient.prompt()
            .user(u -> u.text(PLAN_PROMPT).param("q", question))
            .call()
            .entity(QueryPlan.class);          // 结构化映射
} catch (Exception e) {
    plan = QueryPlan.single(question, question); // 降级:透传,链路不断
}
```

---

## 五、避坑与优化

### 坑 1:对所有问题都跑完整四步
**后果**：简单问题被过度改写，反而引入噪声，延迟成本也上去了。
**做法**：先分类，简单明确的问题直接透传（见 2.3）。

### 坑 2:分解产生过多子查询
**后果**：一个问题拆出 8 条子查询，检索成本 8 倍，还可能相互干扰。
**做法**：限制子查询上限（如 ≤3），超出则合并或只保留最相关的。

### 坑 3:改写「改跑偏」
**后果**：LLM 自作聪明把「年假」改成「离职补偿」，语义漂移，答案全错。
**做法**：改写要**保守**，Prompt 中强调「只做规范化，不改变核心意图」；并保留原始问题用于兜底检索。

### 坑 4:扩展的同义词污染检索
**后果**：扩展出的近义词把不相关文档也召回了。
**做法**：扩展查询的检索结果，在 Ch06 融合时应赋予**较低权重**，原始/规范化查询权重最高。

### 优化建议
- **缓存查询计划**：相同问题的 `QueryPlan` 可缓存，高频问题免去重复 LLM 调用。
- **保留原始问题参与检索**：无论怎么改写，都让原始问题也参与一路检索，避免改写失误导致全盘皆输。

---

## 本章小结

- 用户的「人话」问题不能直接检索，Query 优化是**用户语言 ↔ 检索语言**的翻译层。
- 四类技术：**规范化(去指代/展开缩写)、改写(补全语义)、扩展(同义多表达)、分解(复合拆原子)**。
- 不要对所有问题都跑完整流程，先分类再选策略，简单问题直接透传。
- 产物是统一的 `QueryPlan`（可含多条子查询），交给 Ch05 多路检索。
- 我们实现了可插拔的 `QueryPlanner` 端口 + 规则版实现，规则/LLM 对上层透明；生产版用 LLM 结构化输出并带降级保护。

---

> **下一章预告**：Chapter 05 进入 **Multi Query Retrieval**——`QueryPlan` 里的多条子查询如何并行检索、结果如何去重合并，用 Java 21 虚拟线程把「多路召回」跑得又快又稳。
>
> 👉 输入「继续」进入 Chapter 05。