# Chapter 05 · Multi Query Retrieval 多路检索

> 本章目标：把 Ch04 产出的 `QueryPlan`（一组子查询）真正跑起来——用 **Java 21 虚拟线程** 并行执行多路检索，再把多路结果**去重、合并、聚合**成统一候选集，交给 Ch06 融合排序。

---

## 一、为什么要学（Why）

Ch04 把「一个烂问题」变成了「一组好查询」。但如果我们**串行**地一条条检索：

```
子查询1 → 检索(120ms)
子查询2 → 检索(120ms)
子查询3 → 检索(120ms)
                        总耗时 ≈ 360ms  ❌ 用户等到花儿都谢了
```

而且检索目标可能是**多个知识库**（Ch03 路由出 2~3 个库），每个库还要各查一遍。子查询数 × 知识库数，串行下来延迟会爆炸。

真实企业场景里，一次问答背后常常是「3 条子查询 × 2 个知识库 = 6 次检索」。串行 700ms+，并行只需 ~120ms。**多路检索的核心命题就是：又多又快，且结果干净。**

三个必须解决的问题：
1. **并发**：6 次检索怎么并行跑，且不被 I/O 阻塞拖垮线程池？→ 虚拟线程。
2. **去重**：不同子查询召回了同一篇文档，怎么合并、分数怎么处理？
3. **健壮**：某一路检索超时/失败，不能让整次问答挂掉。→ 单路降级。

---

## 二、是什么（What）

### 2.1 多路检索的数据流

```
QueryPlan (来自 Ch04)
   │  subQueries = [q1, q2, q3]
   ▼
┌──────────── Fan-out(虚拟线程并行) ────────────┐
│   q1 → 检索  │   q2 → 检索  │   q3 → 检索      │
│   ↓          │   ↓          │   ↓              │
│  [doc片段]   │  [doc片段]   │  [doc片段]        │
└──────────────────────────────────────────────┘
   │  Fan-in(汇聚)
   ▼
去重合并(同一 chunk 取最高分 / 累加分)
   ▼
统一候选集 List<RetrievedChunk> → 交给 Ch06 RRF 融合
```

### 2.2 为什么是虚拟线程而不是线程池？

检索是**典型 I/O 密集**操作（网络请求向量库、等 DB 返回）。传统做法用固定线程池，问题是：
- 线程池大小难调：太小并发不足，太大内存爆炸；
- 每个平台线程约 1MB 栈，几百并发就吃光内存。

**Java 21 虚拟线程**：每个任务一个虚拟线程，阻塞时自动让出载体线程，成本极低（可轻松开几万个）。写的是**同步阻塞式代码**，跑的是**异步高并发**——这正是多路检索的完美场景。

```java
// 一行拿到「每任务一线程」的执行器,I/O 阻塞不占用平台线程
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // 提交 N 个检索任务,各自阻塞等 I/O,互不影响
}
```

### 2.3 去重合并策略

同一篇文档 chunk 被多条子查询召回，是常态。合并策略有两种主流：

| 策略 | 做法 | 适用 |
| --- | --- | --- |
| **Max Score** | 同一 chunk 保留各路中的最高分 | 简单，本章采用 |
| **RRF 融合** | 基于**排名**而非分数融合，跨检索器可比 | 混合检索，Ch06 详讲 |

本章先用 Max Score 讲清「合并」这件事；Ch06 再升级为 RRF，解决「向量分数和关键词分数不可比」的问题。

---

## 三、怎么用（How）

> 实现 `RetrievedChunk`（检索结果片段）、`MultiQueryRetriever`（多路检索器），全部可编译，复用 core 既有 API（`AgentContext`、`KnowledgeBaseId`、`AgentException`）。

### 3.1 检索结果片段:RetrievedChunk

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;

/**
 * 检索到的文档片段(候选证据)。
 *
 * @param chunkId 片段唯一 ID(去重依据)
 * @param source  来源知识库
 * @param content 片段正文
 * @param score   相关性分数 [0,1],越大越相关
 */
public record RetrievedChunk(String chunkId, KnowledgeBaseId source, String content, double score) {

    public RetrievedChunk {
        AgentException.requireText(chunkId, "chunkId");
        AgentException.requireNonNull(source, "source");
        AgentException.requireText(content, "content");
        if (score < 0.0 || score > 1.0) {
            throw new AgentException(AgentErrorCode.INVALID_ARGUMENT, "score 必须在 [0,1]");
        }
    }

    /** 用新分数复制一份(合并时保留最高分)。 */
    public RetrievedChunk withScore(double newScore) {
        return new RetrievedChunk(chunkId, source, content, newScore);
    }
}
```

### 3.2 单路检索端口:VectorSearchPort

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;

import java.util.List;

/**
 * 单路向量检索端口(Hexagonal Port)。
 *
 * <p>屏蔽底层是 pgvector / Milvus / ES 的差异,上层只面向该接口编程。</p>
 */
public interface VectorSearchPort {

    /**
     * 在指定知识库中检索一条查询的 Top-K 片段。
     *
     * @param kb    目标知识库
     * @param query 单条子查询
     * @param topK  返回条数上限
     */
    List<RetrievedChunk> search(KnowledgeBaseId kb, String query, int topK);
}
```

### 3.3 多路检索器:MultiQueryRetriever

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 多路检索器:把「多条子查询 × 多个知识库」用虚拟线程并行检索,再去重合并。
 */
public class MultiQueryRetriever {

    private final VectorSearchPort searchPort;
    private final int topKPerQuery;

    public MultiQueryRetriever(VectorSearchPort searchPort, int topKPerQuery) {
        this.searchPort = searchPort;
        this.topKPerQuery = topKPerQuery;
    }

    /**
     * 并行检索所有 (子查询 × 知识库) 组合,合并去重后返回候选集。
     *
     * @param subQueries      Ch04 产出的子查询
     * @param targetBases     Ch03 路由出的目标知识库
     * @param context         贯穿上下文(此处用于 traceId 日志,权限已在路由前置)
     */
    public List<RetrievedChunk> retrieve(List<String> subQueries,
                                         List<KnowledgeBaseId> targetBases,
                                         AgentContext context) {
        // 1. 构造所有检索任务:笛卡尔积 (子查询 × 知识库)
        List<Callable<List<RetrievedChunk>>> tasks = new ArrayList<>();
        for (String q : subQueries) {
            for (KnowledgeBaseId kb : targetBases) {
                tasks.add(() -> safeSearch(kb, q));
            }
        }

        // 2. Fan-out:虚拟线程并行执行,I/O 阻塞不占平台线程
        List<RetrievedChunk> all = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<RetrievedChunk>>> futures = new ArrayList<>();
            for (Callable<List<RetrievedChunk>> t : tasks) {
                futures.add(executor.submit(t));
            }
            // 3. Fan-in:汇聚各路结果
            for (Future<List<RetrievedChunk>> f : futures) {
                try {
                    all.addAll(f.get());
                } catch (ExecutionException e) {
                    // 单路失败已在 safeSearch 兜底,这里理论上不会到
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 4. 去重合并:同一 chunkId 保留最高分
        return mergeByMaxScore(all);
    }

    /** 单路检索兜底:某一路失败返回空,不拖垮整次问答。 */
    private List<RetrievedChunk> safeSearch(KnowledgeBaseId kb, String query) {
        try {
            return searchPort.search(kb, query, topKPerQuery);
        } catch (Exception e) {
            // 生产:打点 + 日志记录 traceId,这里降级为空结果
            return List.of();
        }
    }

    /** 按 chunkId 去重,保留最高分;用 LinkedHashMap 保持稳定顺序。 */
    private List<RetrievedChunk> mergeByMaxScore(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> best = new LinkedHashMap<>();
        for (RetrievedChunk c : chunks) {
            best.merge(c.chunkId(), c,
                    (oldC, newC) -> newC.score() > oldC.score() ? newC : oldC);
        }
        return new ArrayList<>(best.values());
    }
}
```

### 3.4 跑起来

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.List;
import java.util.Set;

public class Chapter05Demo {

    public static void main(String[] args) {
        // 假的向量检索:模拟不同子查询召回,含一个重复 chunk(c-100)
        VectorSearchPort fakePort = (kb, query, topK) -> {
            simulateIoLatency();
            return switch (query) {
                case "带薪年休假申请流程" -> List.of(
                        new RetrievedChunk("c-100", kb, "年假申请需提前3天在OA提交", 0.82),
                        new RetrievedChunk("c-101", kb, "年假审批由直属主管完成", 0.71));
                case "年假天数" -> List.of(
                        new RetrievedChunk("c-100", kb, "年假申请需提前3天在OA提交", 0.90), // 同一chunk更高分
                        new RetrievedChunk("c-102", kb, "工龄满10年享15天年假", 0.88));
                default -> List.of();
            };
        };

        var ctx = AgentContext.of(TenantId.of("t1"), WorkspaceId.of("w1"),
                UserId.of("u1"), Set.of("EMPLOYEE"));
        var retriever = new MultiQueryRetriever(fakePort, 5);

        long start = System.currentTimeMillis();
        List<RetrievedChunk> result = retriever.retrieve(
                List.of("带薪年休假申请流程", "年假天数"),   // 来自 Ch04 的子查询
                List.of(KnowledgeBaseId.of("kb-hr")),        // 来自 Ch03 的目标库
                ctx);
        long cost = System.currentTimeMillis() - start;

        System.out.println("候选数(去重后): " + result.size());
        result.forEach(c ->
                System.out.printf("  %s  score=%.2f  %s%n", c.chunkId(), c.score(), c.content()));
        System.out.println("并行耗时约: " + cost + "ms(2路各~100ms,并行≈100ms)");
    }

    private static void simulateIoLatency() {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

**运行输出（示意）:**

```
候选数(去重后): 3
  c-100  score=0.90  年假申请需提前3天在OA提交
  c-101  score=0.71  年假审批由直属主管完成
  c-102  score=0.88  工龄满10年享15天年假
并行耗时约: 105ms(2路各~100ms,并行≈100ms)
```

**看关键点**：
- `c-100` 被两条子查询召回，合并后**保留了更高分 0.90**（而非 0.82），去重生效。
- 两路检索各 sleep 100ms，串行应 200ms+，**并行只花 ~105ms**——虚拟线程的威力。
- `VectorSearchPort` 是端口，测试用 lambda 桩，生产换真实 pgvector 实现，上层零改动。

---

## 四、真实项目中的应用

在 ZeroHub 平台里，`MultiQueryRetriever` 的生产要点：

1. **组合爆炸控制**：子查询 × 知识库可能几十个组合。设上限（如总任务 ≤12），超出则按路由置信度剪枝。
2. **超时兜底**：给每路检索加超时（如单路 300ms），用 `Future.get(timeout)` 或结构化并发 `StructuredTaskScope` 统一取消慢任务，返回已到的结果。
3. **权限已前置**：注意 `targetBases` 已是 Ch03 权限过滤后的库，这里**不再做权限判断**——职责单一。
4. **可观测**：每路检索埋点（哪个库、哪条 query、耗时、命中数），挂到 traceId 上，便于排查「为什么这次召回差」。

```java
// 生产:Java 21 结构化并发,超时统一取消(预览特性示意)
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var subtasks = tasks.stream().map(scope::fork).toList();
    scope.joinUntil(Instant.now().plusMillis(300)); // 全局超时
    // 收集已完成结果...
}
```

---

## 五、避坑与优化

### 坑 1:用传统 `newFixedThreadPool` 跑 I/O 检索
**后果**：线程池被阻塞任务占满，后续请求排队，吞吐骤降。
**做法**：I/O 密集用虚拟线程 `newVirtualThreadPerTaskExecutor()`；CPU 密集才用固定池。

### 坑 2:一路检索失败拖垮整次问答
**后果**：某个库抖动，`Future.get()` 抛异常，整个回答挂掉。
**做法**：单路 `safeSearch` 兜底为空结果 + 全局超时，**部分结果也能出答案**。

### 坑 3:去重只看内容不看 ID
**后果**：用 content 做 key，稍有格式差异就去重失败，候选集里全是重复。
**做法**：用稳定的 `chunkId` 去重；chunkId 由「文档ID+分片序号」生成，保证同一片段 ID 一致。

### 坑 4:直接把各路分数相加当合并分
**后果**：被多路召回的 chunk 分数虚高，挤掉真正相关的。
**做法**：本章用 Max Score；跨检索器场景务必用 Ch06 的 **RRF**（基于排名），避免分数不可比。

### 优化建议
- **原始问题也占一路**：把 Ch04 保留的原始问题也加入检索，防改写失误。
- **结果预算**：合并后先按分数截断到 Top-N（如 20）再进 Rerank，避免把大量候选喂给昂贵的重排模型。

---

## 本章小结

- 多路检索 = **Fan-out 并行 + Fan-in 汇聚 + 去重合并**，解决「又多又快又干净」。
- Java 21 **虚拟线程**是 I/O密集检索的最佳载体：同步写法、异步性能、极低成本。
- 单路必须**兜底降级 + 全局超时**，部分结果也能出答案，杜绝一路拖垮全局。
- 去重用稳定 `chunkId`，合并本章用 Max Score，跨检索器留给 Ch06 的 RRF。
- `VectorSearchPort` 端口化，测试用桩、生产换真实库，上层无感。

---

> **下一章预告**：Chapter 06 进入 **Hybrid Retrieval 与 RRF 融合**——向量检索(语义)与关键词检索(BM25)各有所长，如何用 **Reciprocal Rank Fusion** 把「分数不可比」的两路结果公平融合成一个高质量候选集。
>
> 👉 输入「继续」进入 Chapter 06。