# Chapter 01 · 企业为什么需要 Knowledge Agent V2

> 本章目标：讲清楚「知识库 → 知识智能体」这一跃迁背后的**企业动机**，建立 Day16 的总体心智模型，并用可运行代码把「一次 Agent 请求」的骨架跑起来。

---

## 一、为什么要学（Why）

如果你已经学完 Day5 的 RAG 基础、Day15 的企业知识平台，你可能会问：**「RAG 不是已经能回答问题了吗？为什么还要搞一个 Agent？」**

答案是：**能回答 ≠ 能被企业信任。**

在 Demo 阶段，RAG 的流程只有一条直线：

```
用户问题 → 向量检索 Top-K → 拼 Prompt → LLM 生成答案
```

这条直线在真实企业里会立刻断裂。我们看几个**真实会被老板投诉**的场景：

| 场景 | Demo RAG 的表现 | 企业的真实诉求 |
| --- | --- | --- |
| 财务同事问「差旅报销上限」 | 在「研发 Wiki」里检索，答非所问 | 先判断「这个问题属于哪个知识库」（Routing） |
| 用户问「那个休假的规定」 | 口语化 query 召回噪声 | 先把问题改写、拆解成可检索的子问题（Query Rewrite） |
| 问「JIRA-2024 这个需求的验收标准」 | 向量检索对编号无感，召回失败 | 关键词 + 向量混合检索（Hybrid Search） |
| 实习生问「高管薪酬结构」 | 先检索到机密文档、再想办法过滤 | 权限**下推**到检索层，越权数据根本不进入候选（ACL） |
| 问一个知识库里根本没有的问题 | LLM 一本正经地编一段 | 识别 Knowledge Gap，明确说「我不知道」 |
| 老板问「这个答案是从哪来的」 | 无法回答 | 每句话都能溯源到具体文档、具体段落（Citation） |

**结论**：企业需要的不是「一个会搜索的接口」，而是一个**会思考（推理规划）、可信任（引用+验证）、可治理（权限+版本+评估）**的智能体。这就是 Knowledge Agent V2 的存在意义，也是本课程 20 章要逐一攻克的目标。

---

## 二、是什么（What）

### 2.1 从 RAG Pipeline 到 Agent 推理链

Knowledge Agent V2 把「一条直线」升级为「一条带决策点的推理链」：

```
                         ┌─────────────────────────────────────────────┐
   用户问题 + AgentContext │  Router → Query → Retrieval → Rerank → ACL   │
   ───────────────────────▶│      → Citation → Verifier → 决策            │
                         └─────────────────────────────────────────────┘
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                    ▼                         ▼                         ▼
              SUPPORTED                   PARTIAL                  UNSUPPORTED
            （带引用回答）           （补充检索/降级回答）      （知识缺口/转人工）
```

每一个箭头节点，都对应本课程的一章：

- **Router（Ch03）**：这个问题该去哪个知识库？
- **Query（Ch04-05）**：问题要不要改写、拆解、生成多路 query？
- **Retrieval（Ch06）**：向量 + 关键词 + 元数据混合召回。
- **Rerank（Ch07）**：把召回的 50 条重排为最相关的 5 条。
- **ACL（Ch08）**：权限下推，越权内容不进候选。
- **Citation（Ch09）**：答案的每一句都能溯源。
- **Verifier（Ch10-11）**：答案是否真的被证据支撑（Grounding / Verification）。

### 2.2 核心设计原则

本课程贯穿三条原则，请记牢：

1. **上下文贯穿全链路**：一个不可变的 `AgentContext`（租户、工作空间、用户、角色、traceId）随请求一路传递，任何一步都不能「丢租户」或「改租户」。
2. **权限前置，而非后置**：ACL 下推到检索，而不是「先检索再过滤」——后者是知识泄漏的头号根源。
3. **可信优先于流畅**：宁可说「知识库中未找到」，也不允许编造。置信度用**证据覆盖度**衡量，而非 LLM 的自我打分。

### 2.3 本章的代码地基

Day16 的领域内核（`core`）已经就位，它零框架依赖，是所有模块最稳定的地基：

- [`AgentContext`](../../core/model/AgentContext.java) —— 贯穿推理链的不可变上下文；
- [`AgentResult`](../../core/common/AgentResult.java) —— 统一 API 返回；
- [`AgentException`](../../core/common/AgentException.java) / [`AgentErrorCode`](../../core/common/AgentErrorCode.java) —— 统一异常与错误码；
- 强类型 ID：`TenantId` / `WorkspaceId` / `KnowledgeBaseId` / `UserId`。

本章我们**先不接 LLM**，而是用一个「桩」把 Agent 请求的骨架跑通，让你对「一次请求如何流经推理链」有肌肉记忆。

---

## 三、怎么用（How）

> 技术栈：Java 21、Spring Boot 4.1.0、Spring AI 2.0.0。本章代码不依赖数据库与外部模型，可独立编译运行。

### 3.1 定义推理链的最小抽象：AgentPipeline

我们先为「推理链」定义一个最小接口，后续每一章都会往里填充真实的节点实现。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.chapter01;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

/**
 * Agent 推理链的最小抽象。
 *
 * <p>Chapter 01 只定义骨架：输入「问题 + 上下文」，输出「答案」。
 * 后续章节会把 Router / Query / Retrieval / Rerank / ACL / Citation / Verifier
 * 逐个作为节点插入这条链。</p>
 */
public interface AgentPipeline {

    /**
     * 执行一次完整的知识问答。
     *
     * @param question 用户原始问题
     * @param context  贯穿全链路的不可变上下文
     * @return 结构化的回答
     */
    AgentAnswer ask(String question, AgentContext context);
}
```

### 3.2 定义结构化回答：AgentAnswer

企业级回答绝不能只返回一个字符串，它必须携带**可信度**与**溯源信息**。本章先定义骨架字段，后续章节逐步填充。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.chapter01;

import java.util.List;

/**
 * 结构化的 Agent 回答（Chapter 01 骨架版）。
 *
 * @param answer     最终答案文本
 * @param status     答案状态：SUPPORTED / PARTIAL / UNSUPPORTED
 * @param confidence 置信度 [0.0, 1.0]，基于证据覆盖度而非 LLM 自评
 * @param citations  引用来源（本章为占位，Ch09 起填充真实证据）
 * @param traceId    全链路追踪 ID，与请求 AgentContext 一致
 */
public record AgentAnswer(
        String answer,
        AnswerStatus status,
        double confidence,
        List<String> citations,
        String traceId
) {

    /** 答案可信度状态。 */
    public enum AnswerStatus {
        /** 有充分证据支撑。 */
        SUPPORTED,
        /** 部分支撑，需补充或降级。 */
        PARTIAL,
        /** 无证据支撑，命中知识缺口。 */
        UNSUPPORTED
    }

    /** 构建一个「知识缺口」回答的便捷方法。 */
    public static AgentAnswer knowledgeGap(String traceId) {
        return new AgentAnswer(
                "抱歉，当前知识库中未找到与该问题相关的可信信息。",
                AnswerStatus.UNSUPPORTED,
                0.0,
                List.of(),
                traceId
        );
    }
}
```

### 3.3 实现一个「骨架版」推理链

Chapter 01 用一个桩实现把链路跑通：它接收上下文、校验租户、返回一个占位答案。**重点不在答案质量，而在于让你看清「上下文如何贯穿」。**

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.chapter01;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.List;

/**
 * Chapter 01 骨架版推理链。
 *
 * <p>它还没有真正的检索与 LLM，只演示「一次请求如何流经推理链」：
 * 校验上下文 → 记录 traceId → 返回结构化答案。后续章节会把每个节点替换为真实实现。</p>
 */
public class SkeletonAgentPipeline implements AgentPipeline {

    @Override
    public AgentAnswer ask(String question, AgentContext context) {
        // 1. 前置校验：问题与上下文都不能为空——fail fast
        AgentException.requireText(question, "question");
        AgentException.requireNonNull(context, "context");

        // 2. 多租户隔离的第一道关：任何数据访问都必须绑定租户
        //    这里仅演示「上下文如何被读取」，真实检索会把 tenantId 下推到查询条件
        var tenantId = context.tenantId();
        var traceId = context.traceId();

        System.out.printf(
                "[%s] 收到请求 tenant=%s user=%s question=%s%n",
                traceId, tenantId.value(), context.userId().value(), question
        );

        // 3. 骨架阶段：直接返回一个占位答案，携带 traceId 保证可追踪
        //    真实链路会在此处依次调用 Router → ... → Verifier
        return new AgentAnswer(
                "（骨架占位）已收到问题：" + question,
                AgentAnswer.AnswerStatus.PARTIAL,
                0.5,
                List.of(),
                traceId
        );
    }
}
```

### 3.4 跑起来：一个可运行的 main

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.chapter01;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.Set;

/**
 * Chapter 01 演示入口：把一次 Agent 请求跑通。
 */
public class Chapter01Demo {

    public static void main(String[] args) {
        // 1. 构造贯穿全链路的上下文（多租户 + 权限主体 + 追踪）
        AgentContext context = AgentContext.of(
                TenantId.of("tenant-acme"),
                WorkspaceId.of("ws-hr"),
                UserId.of("user-1001"),
                Set.of("EMPLOYEE")
        );

        // 2. 执行骨架推理链
        AgentPipeline pipeline = new SkeletonAgentPipeline();
        AgentAnswer answer = pipeline.ask("差旅报销的上限是多少？", context);

        // 3. 打印结构化结果
        System.out.println("---- 回答 ----");
        System.out.println("status     = " + answer.status());
        System.out.println("confidence = " + answer.confidence());
        System.out.println("traceId    = " + answer.traceId());
        System.out.println("answer     = " + answer.answer());
    }
}
```

**运行输出（示意）：**

```
[trace-xxxx] 收到请求 tenant=tenant-acme user=user-1001 question=差旅报销的上限是多少？
---- 回答 ----
status     = PARTIAL
confidence = 0.5
traceId    = trace-xxxx
answer     = （骨架占位）已收到问题：差旅报销的上限是多少？
```

这段代码虽然简单，但它已经演示了 Day16 最重要的设计：**AgentContext 从入口一路传递，traceId 从请求贯穿到响应**。后续 19 章都在这条链上生长。

---

## 四、真实项目中的应用

在 ZeroHub 这样的企业平台里，Chapter 01 的骨架会长成这样：

1. **入口层**：一个 `@RestController` 接收 `POST /api/v2/agent/ask`，从 JWT / 网关头里解析出租户、用户、角色，构造 `AgentContext`。
2. **编排层**：`AgentPipeline` 由 Spring 管理，内部按顺序编排 Router、Retrieval、Verifier 等 Bean。
3. **返回层**：把 `AgentAnswer` 包装进 [`AgentResult`](../../core/common/AgentResult.java)，统一携带 traceId 返回前端。

一个典型的企业请求会这样流动：

```
API 网关（鉴权/限流）
   → Controller（构造 AgentContext）
      → AgentPipeline.ask(question, ctx)
         → Router 选库 → Query 改写 → 权限下推检索 → Rerank
            → LLM 生成 → Citation 溯源 → Verifier 验证
      ← AgentAnswer（status + confidence + citations + traceId）
   ← AgentResult（统一返回）
```

**关键点**：无论链路多长，`traceId` 始终不变——这让你能在 Grafana / OpenTelemetry 里把一次请求的全部节点串成一条 Trace，出问题时秒级定位是「哪个节点坏了」。

---

## 五、避坑与优化

### 坑 1：把 AgentContext 做成可变对象
**后果**：某个节点中途 `context.setTenantId(...)`，导致跨租户越权。
**做法**：`AgentContext` 必须是 `record`（不可变），需要变更时用 `with...` 派生新实例，绝不原地修改。

### 坑 2：traceId 中途丢失
**后果**：异步 / 虚拟线程切换后日志断链，无法定位问题。
**做法**：traceId 放进 `AgentContext` 随参数传递，或绑定到 MDC；本课程统一采用「随上下文传递」，最可靠。

### 坑 3：回答只返回 String
**后果**：前端无法区分「有把握的答案」和「瞎编的答案」，也无法展示引用。
**做法**：从第一天就用 `AgentAnswer`（status + confidence + citations）结构化返回，为后续 Citation / Verification 预留位置。

### 坑 4：把 Chapter 01 的骨架当成能上线的东西
**提醒**：本章的 `SkeletonAgentPipeline` 只是教学桩，`confidence=0.5` 是硬编码。真实置信度将在 Ch10-11 基于证据覆盖度计算，切勿直接上线。

### 优化建议
- **提前规划扩展点**：`AgentPipeline` 接口不要写死实现，后续每个节点都应是可插拔的 Bean，便于 A/B 测试不同的 Router / Reranker。
- **上下文最小化**：`AgentContext` 只放「贯穿全链路必需」的字段，业务参数走方法入参，避免上下文膨胀。

---

## 本章小结

- 企业需要的是**会思考、可信任、可治理**的知识智能体，而非「会搜索的接口」。
- Knowledge Agent V2 把 RAG 直线升级为**带决策点的推理链**：Router → Query → Retrieval → Rerank → ACL → Citation → Verifier。
- 三条核心原则：**上下文贯穿全链路、权限前置、可信优先于流畅**。
- 我们用 `AgentPipeline` / `AgentAnswer` / `SkeletonAgentPipeline` 把请求骨架跑通，为后续 19 章打好地基。

---

> **下一章预告**：Chapter 02 将展开 Knowledge Agent 的**总体架构与模块划分**，讲清 core / routing / query / retrieval / rerank / acl / citation / verify / governance / observability 各模块的职责边界与依赖方向。
>
> 👉 输入「继续」进入 Chapter 02。