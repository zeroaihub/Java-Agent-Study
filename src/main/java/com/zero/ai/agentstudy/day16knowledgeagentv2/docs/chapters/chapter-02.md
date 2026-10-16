# Chapter 02 · Knowledge Agent 总体架构与模块划分

> 本章目标：讲清 Knowledge Agent V2 的**分层架构、模块职责边界、依赖方向**，用 Hexagonal「端口—适配器」把推理链的每个节点抽象成可插拔的 Port，并用可运行代码把「链式编排」跑通。

---

## 一、为什么要学（Why）

Chapter 01 我们用一个 `SkeletonAgentPipeline` 把请求骨架跑通了。但如果你就这么一路写下去，会掉进企业项目最常见的坑：**所有逻辑堆在一个类里**。

想象一下三个月后：Router、Query 改写、检索、Rerank、ACL、Citation、Verifier 全挤在一个几千行的 `AgentServiceImpl` 里。这时候：

- 想换一个 Reranker 算法做 A/B 测试 → 得改核心类，牵一发动全身；
- 想给检索层加缓存 → 找不到干净的插入点；
- 新人想理解「一次请求经过哪些步骤」→ 读一天代码都理不清；
- 想单测某一个节点 → 无法脱离整条链单独测。

**架构的价值，就是在你还没出问题时，先把边界划清楚。** 本章要回答的核心问题是:

> 如何把「Router → Query → Retrieval → Rerank → ACL → Citation → Verifier」这条链,拆成**职责单一、依赖单向、可独立替换**的模块?

答案就是 **DDD + Hexagonal Architecture + Spring Modulith** 的组合拳。

---

## 二、是什么（What）

### 2.1 四层同心圆:Clean Architecture 心智

Knowledge Agent V2 遵循 Clean Architecture 的同心圆模型,**依赖只能由外向内**:

```
┌──────────────────────────────────────────────────┐
│  接入层 api / admin（Controller、DTO、鉴权）          │
│  ┌────────────────────────────────────────────┐  │
│  │  应用层 pipeline（编排推理链，不含业务规则）      │  │
│  │  ┌──────────────────────────────────────┐  │  │
│  │  │  领域层 router/query/retrieval/...      │  │  │
│  │  │  （端口 Port + 领域规则，纯业务）          │  │  │
│  │  │  ┌────────────────────────────────┐  │  │  │
│  │  │  │  内核 core（值对象/异常/上下文）    │  │  │  │
│  │  │  │  零框架依赖，最稳定                 │  │  │  │
│  │  │  └────────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
        基础设施 adapter（pgvector/Redis/LLM）在最外圈,实现内层端口
```

**关键规则**:内层不知道外层的存在。`core` 不 import 任何 Spring;领域层只依赖 `core` 与自己定义的端口接口;数据库、LLM 这些「脏东西」全在最外圈,通过实现端口接口注入进来。

### 2.2 Hexagonal:每个模块都是「端口 + 适配器」

推理链的每个节点,都被抽象成一个**端口(Port,即接口)**,放在领域层;真实实现(**Adapter**)放在基础设施层。这样「换实现」不影响「用它的人」。

| 模块 | 端口(Port) | 职责 | 后续章节 |
| --- | --- | --- | --- |
| router | `KnowledgeRouter` | 判断问题该去哪个知识库 | Ch03 |
| query | `QueryPlanner` | 改写 / 拆解 / 生成多路 query | Ch04-05 |
| retrieval | `HybridRetriever` | 向量 + 关键词 + 元数据召回 | Ch06 |
| reranker | `Reranker` | 把候选重排为最相关 Top-N | Ch07 |
| acl | `AccessController` | 权限下推,过滤越权内容 | Ch08 |
| citation | `CitationBuilder` | 为答案构建引用溯源 | Ch09 |
| verifier | `AnswerVerifier` | 验证答案是否被证据支撑 | Ch10-11 |
| governance | `KnowledgeGovernor` | Gap / Version / Update 治理 | Ch12-13 |
| observability | `AgentTracer` | 全链路 Trace / Metrics | Ch16 |

### 2.3 Spring Modulith:用包结构守住边界

光靠「口头约定」守不住依赖方向,时间一长必然腐化。Spring Modulith 让你用**包结构 = 模块边界**,并能在测试中**自动校验依赖方向没有被违反**。

Day16 的包结构:

```
day16knowledgeagentv2
├── core          ← 内核,谁都能依赖它,它不依赖任何人
├── router        ← 依赖 core
├── query         ← 依赖 core
├── retrieval     ← 依赖 core
├── reranker      ← 依赖 core
├── acl           ← 依赖 core
├── citation      ← 依赖 core
├── verifier      ← 依赖 core
├── governance    ← 依赖 core
├── observability ← 依赖 core
├── pipeline      ← 依赖以上所有端口(应用层编排)
└── api           ← 依赖 pipeline(接入层)
```

### 2.4 依赖方向铁律

```
api → pipeline → {router, query, retrieval, reranker, acl, citation, verifier, governance} → core
```

**箭头永远单向向左,绝不允许反向或循环。** 这是整个架构的生命线——一旦出现循环依赖,模块就无法独立测试与替换。

---

## 三、怎么用（How）

> 本章用「端口抽象 + 链式编排」重构 Chapter 01 的骨架,代码可独立编译运行。

### 3.1 定义推理链的处理阶段:PipelineStage

每个节点都实现同一个统一的阶段接口,这样 pipeline 就能像流水线一样把它们串起来。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.pipeline;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

/**
 * 推理链的一个处理阶段（Hexagonal 端口的统一形态）。
 *
 * <p>Router / Query / Retrieval / Rerank / ACL / Citation / Verifier
 * 都实现本接口,从而可被 pipeline 以统一方式编排、插拔、排序。</p>
 *
 * @param <I> 阶段输入类型
 * @param <O> 阶段输出类型
 */
public interface PipelineStage<I, O> {

    /**
     * 处理当前阶段。
     *
     * @param input   上一阶段的输出
     * @param context 贯穿全链路的上下文
     * @return 本阶段的输出,交给下一阶段
     */
    O process(I input, AgentContext context);

    /** 阶段名称,用于日志与 Trace 埋点。 */
    String name();
}
```

### 3.2 用泛型串起链路:StageChain

我们用一个极简的链式编排器把多个阶段串起来。它只做一件事:**按顺序执行,并在每步打埋点**。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.pipeline;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

/**
 * 两个阶段的串联组合子:先执行 first,再把结果喂给 second。
 *
 * <p>通过 andThen 不断串联,即可拼出完整推理链,且每一步都可独立替换。</p>
 */
public record StageChain<I, M, O>(
        PipelineStage<I, M> first,
        PipelineStage<M, O> second
) implements PipelineStage<I, O> {

    @Override
    public O process(I input, AgentContext context) {
        long t0 = System.nanoTime();
        M mid = first.process(input, context);
        logStep(context, first.name(), t0);

        long t1 = System.nanoTime();
        O out = second.process(mid, context);
        logStep(context, second.name(), t1);
        return out;
    }

    @Override
    public String name() {
        return first.name() + " → " + second.name();
    }

    private void logStep(AgentContext ctx, String stage, long startNanos) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("[%s] stage=%s cost=%dms%n", ctx.traceId(), stage, costMs);
    }

    /** 便捷组合:this 之后再接一个阶段。 */
    public <R> StageChain<I, O, R> andThen(PipelineStage<O, R> next) {
        return new StageChain<>(this, next);
    }
}
```

### 3.3 实现两个示例阶段

我们先实现两个「桩阶段」演示编排:一个模拟 Query 改写,一个模拟检索。真实实现将在 Ch04、Ch06 替换。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.pipeline;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.List;

/** 桩:Query 改写阶段——把口语化问题补全为可检索的规范化 query。 */
class QueryRewriteStage implements PipelineStage<String, String> {
    @Override
    public String process(String question, AgentContext context) {
        // 真实实现会调用 LLM 做改写/扩展(Ch04),这里仅演示
        return question.replace("那个", "").trim() + "（规范化）";
    }

    @Override
    public String name() {
        return "QueryRewrite";
    }
}

/** 桩:检索阶段——按 tenant 返回若干候选文档 id。 */
class RetrievalStage implements PipelineStage<String, List<String>> {
    @Override
    public List<String> process(String query, AgentContext context) {
        // 真实实现会做 Hybrid Search + 权限下推(Ch06/Ch08),这里仅演示
        String tenant = context.tenantId().value();
        return List.of(tenant + ":doc-1", tenant + ":doc-2");
    }

    @Override
    public String name() {
        return "Retrieval";
    }
}
```

### 3.4 跑起来:把两个阶段编排成链

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.pipeline;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.List;
import java.util.Set;

/** Chapter 02 演示:用 StageChain 编排「改写 → 检索」两个阶段。 */
public class Chapter02Demo {

    public static void main(String[] args) {
        AgentContext ctx = AgentContext.of(
                TenantId.of("tenant-acme"),
                WorkspaceId.of("ws-hr"),
                UserId.of("user-1001"),
                Set.of("EMPLOYEE")
        );

        // 组合链:QueryRewrite → Retrieval,可任意插拔/替换其中任一阶段
        StageChain<String, String, List<String>> chain =
                new StageChain<>(new QueryRewriteStage(), new RetrievalStage());

        List<String> docs = chain.process("那个休假的规定", ctx);

        System.out.println("链路: " + chain.name());
        System.out.println("命中候选: " + docs);
    }
}
```

**运行输出（示意）:**

```
[trace-xxxx] stage=QueryRewrite cost=0ms
[trace-xxxx] stage=Retrieval cost=0ms
链路: QueryRewrite → Retrieval
命中候选: [tenant-acme:doc-1, tenant-acme:doc-2]
```

看清楚了吗?**每个阶段都是独立的 `PipelineStage`,通过 `StageChain` 串联,每一步自动打 Trace 埋点。** 后续任何一章要加节点,只需实现一个 `PipelineStage` 再 `andThen` 接进来,核心编排器一行都不用改——这就是**开闭原则**在架构层的落地。

---

## 四、真实项目中的应用

在 ZeroHub 平台里,这套模块架构带来三个直接收益:

1. **A/B 测试无痛**:想对比两个 Reranker,只需注入不同的 `Reranker` 实现 Bean,pipeline 编排代码零改动。
2. **团队并行开发**:检索组、权限组、验证组各自负责一个模块,只要端口接口约定好,内部实现互不干扰。
3. **依赖方向可校验**:Spring Modulith 提供 `ApplicationModules.verify()`,在 CI 里自动检查有没有人偷偷让 `core` 依赖了 `retrieval`——一旦违反,构建直接失败。

一个典型的 Modulith 依赖校验测试(将在后续章节完整实现):

```java
// 伪代码示意:CI 中自动守护架构边界
var modules = ApplicationModules.of(KnowledgeAgentApplication.class);
modules.verify();   // 若出现循环依赖或反向依赖,测试失败
```

**这意味着架构不再是「文档里的图」,而是「代码里能被机器强制执行的约束」。**

---

## 五、避坑与优化

### 坑 1:让 core 依赖了框架
**后果**:内核被 Spring / JPA 污染,无法独立测试,升级框架时被绑架。
**做法**:`core` 只允许 import JDK。任何 `@Component`、`@Entity` 都不许进 core。

### 坑 2:模块间直接依赖实现类,而非端口
**后果**:`pipeline` 里 `new PgVectorRetriever()`,换实现要改编排代码。
**做法**:pipeline 只依赖 `HybridRetriever` 接口,具体实现由 Spring 注入。

### 坑 3:出现循环依赖却没人发现
**后果**:`retrieval` 依赖 `reranker`,`reranker` 又反向依赖 `retrieval`,模块烂成一团。
**做法**:引入 Spring Modulith 的 `verify()` 到 CI,循环依赖直接让构建失败。

### 坑 4:过早拆微服务
**提醒**:Day16 是**模块化单体(Modular Monolith)**,不是微服务。模块边界清晰后,未来若真需要拆服务,沿模块边界一刀切即可。**先模块化,再谈拆分**,别一上来就上分布式复杂度。

### 优化建议
- **端口先行**:每新增一个节点,先定义端口接口并写好 Javadoc,再写实现。接口是团队协作的契约。
- **阶段无状态**:`PipelineStage` 实现应保持无状态,所有请求态数据走 `AgentContext` 与入参,便于并发与虚拟线程。

---

## 本章小结

- Knowledge Agent V2 采用 **Clean Architecture 四层同心圆 + Hexagonal 端口适配器 + Spring Modulith 模块守护**。
- 推理链每个节点都是一个**端口(Port)**:Router / QueryPlanner / HybridRetriever / Reranker / AccessController / CitationBuilder / AnswerVerifier / KnowledgeGovernor。
- 依赖方向铁律:`api → pipeline → 领域模块 → core`,**永远单向,绝不循环**。
- 我们用 `PipelineStage` + `StageChain` 把节点抽象成可插拔、自动埋点的流水线,落地开闭原则。

---

> **下一章预告**:Chapter 03 进入第一个真实节点 **Knowledge Routing**——当企业有几十个知识库时,如何智能判断「这个问题该去哪个库检索」,包括规则路由、语义路由与多库并行策略。
>
> 👉 输入「继续」进入 Chapter 03。