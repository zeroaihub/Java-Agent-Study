# Chapter 17 · 性能优化与虚拟线程

> 企业知识库 Agent V2 · 第 17 章
> 平台能隔离(Ch15)、能经营(Ch16)、能观测(Ch14)了。但 Ch14 那次凌晨故障提醒我们:**延迟就是体验,延迟就是成本**。本章用 Java 21 **虚拟线程** + **并行编排** + **多级缓存**,把推理链的延迟压到极致——同时守住"可观测"和"不串租户"两条底线。

---

## 1. 为什么学(Why)

### 1.1 知识库 Agent 天生"慢"且"重 IO"

回顾我们的推理链:`Router→Query→Retrieval→Rerank→ACL→Citation→Verifier`。它的延迟构成有两个残酷事实:

1. **几乎每一步都是 IO 密集**:向量库查询、ES 查询、Rerank 模型调用、大模型生成——CPU 大部分时间在**等网络返回**,而不是计算。
2. **串行累加**:如果每步都串行等待,总延迟 = 各步之和。Ch14 的例子里 780ms,其中检索 200ms + 重排 200ms + 验证 320ms 是大头。

传统解法是用线程池并行 IO,但线程池有个老问题:**平台线程(OS 线程)昂贵**,一个约占 1MB 栈内存,几千并发就撑不住。于是你被迫用复杂的响应式编程(Reactor/WebFlux)——代码难读、难调试、难维护。

### 1.2 Java 21 虚拟线程:用同步的代码写出异步的性能

**虚拟线程(Virtual Threads,JEP 444)** 是 Java 21 的杀手锏:

- 虚拟线程极其**轻量**(几百字节),可以创建**百万级**;
- 当虚拟线程遇到阻塞 IO(等网络),JVM 自动把它从底层 OS 线程上**卸载(unmount)**,让 OS 线程去跑别的虚拟线程;
- 你写的是**同步阻塞**风格的代码(简单、好懂、好调试),获得的却是**异步非阻塞**的吞吐。

一句话:**虚拟线程让"每个请求一个线程"(thread-per-request)这个最直观的模型重新变得高性能**,无需响应式的心智负担。

### 1.3 性能优化的黄金法则:先测量,再优化

Ch14 不是白学的。**没有可观测性数据支撑的优化都是耍流氓**:

- 不知道瓶颈在哪就优化 = 蒙;
- 优化了不测 = 不知道有没有效果,甚至可能变慢。

所以本章的每个优化,都以 Ch14 的 Span 耗时为依据(定位靶心),优化后再用同样的指标验证。

---

## 2. 是什么(What)

### 2.1 三大优化手段

本章聚焦三个投入产出比最高的方向:

1. **并行化独立步骤**:推理链里**互不依赖**的步骤可以并发跑。最典型的是 Ch05 的**混合检索**——向量检索(pgvector)和关键词检索(ES)本可**同时发起**,总耗时取二者较大值,而非之和。
2. **多级缓存**:相同/相似的 query 不必重算。缓存 embedding、缓存检索结果、缓存最终答案——但必须**带 tenant 前缀**(Ch16 的 `TenantCacheKey`),否则串租户。
3. **虚拟线程承载并发**:用 `Executors.newVirtualThreadPerTaskExecutor()` 或 `StructuredTaskScope`,让每个并发子任务跑在虚拟线程上,轻量且天然适配 IO 阻塞。

### 2.2 结构化并发(Structured Concurrency)

Java 21 的 **StructuredTaskScope(JEP 453,预览)** 让并发任务有清晰的**生命周期边界**:

```
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var vecTask = scope.fork(() -> vectorSearch(q));   // 子任务1
    var bm25Task = scope.fork(() -> keywordSearch(q)); // 子任务2
    scope.join();          // 等两个都完成
    scope.throwIfFailed();  // 任一失败则整体失败
    merge(vecTask.get(), bm25Task.get());
}
```

优势:**任一子任务失败,其余自动取消**(不泄漏);父任务等待所有子任务(不悬挂);异常传播清晰。比裸 `Future` + 线程池安全得多。

> 注:StructuredTaskScope 在 Java 21 是预览特性。为保证本章代码在标准 Java 21 直接可编译运行,下面用**稳定的** `newVirtualThreadPerTaskExecutor()` + `Future` 实现同样的并行,并在正文说明如何迁移到结构化并发。

### 2.3 缓存的三个层次与失效

| 缓存对象 | 命中收益 | 失效难点 |
|----------|----------|----------|
| Embedding | 省一次 embedding 模型调用 | query 文本一致即可复用,几乎不失效 |
| 检索结果 | 省向量库+ES 查询 | **知识库更新**时须失效对应租户缓存 |
| 最终答案 | 省整条链 | 失效最难:知识更新、权限变化都影响 |

关键原则:**缓存收益越大,失效越难**。答案缓存要谨慎(权限/知识一变就可能返回错误答案),而 embedding 缓存几乎无风险。

### 2.4 本章交付物

- `ParallelRetriever`:用虚拟线程并行发起向量检索 + 关键词检索,合并结果。
- `RetrievalSource`:检索源端口(模拟 vector / bm25 两个源)。
- `TenantAwareCache<V>`:带 tenant 前缀的简单缓存(复用 Ch16 的 key 策略思路)。
- `TimedStage`:轻量计时工具(呼应 Ch14,验证优化效果)。
- `Chapter17Demo`:对比"串行 vs 并行"检索耗时,并演示缓存二次命中。

---

## 3. 怎么用(How)

> 包路径:`com.zero.ai.agentstudy.day16knowledgeagentv2.perf`
> 复用 `core.model.TenantId`(Ch02),Java 21 稳定 API,零框架依赖。

### 3.1 RetrievalSource:检索源端口

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import java.util.List;

/** 一个检索源(向量库 / ES 等)。生产环境是真实的 IO 调用。 */
public interface RetrievalSource {

    String name();

    /** 检索(模拟一次带网络延迟的 IO)。 */
    List<String> search(String query);
}
```

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import java.util.List;

/** 演示用:用 sleep 模拟 IO 延迟的检索源。 */
public final class SlowRetrievalSource implements RetrievalSource {

    private final String name;
    private final long latencyMs;
    private final List<String> data;

    public SlowRetrievalSource(String name, long latencyMs, List<String> data) {
        this.name = name;
        this.latencyMs = latencyMs;
        this.data = List.copyOf(data);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> search(String query) {
        sleep(latencyMs); // 模拟阻塞 IO——虚拟线程会在此被卸载
        return data;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 3.2 ParallelRetriever:虚拟线程并行检索

这是本章核心——把 Ch05 的串行混合检索改为并行:

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并行检索器:用虚拟线程同时发起多个检索源,总耗时 ≈ 最慢的那个,而非累加。
 * 每个源跑在一个虚拟线程上,阻塞 IO 时自动让出底层 OS 线程。
 */
public final class ParallelRetriever {

    private final List<RetrievalSource> sources;

    public ParallelRetriever(List<RetrievalSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /** 并行检索所有源并合并去重。 */
    public List<String> retrieve(String query) {
        // 每个任务一个虚拟线程;try-with-resources 保证 executor 关闭
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (RetrievalSource src : sources) {
                futures.add(executor.submit(() -> src.search(query)));
            }
            // 合并各源结果(去重保序)
            List<String> merged = new ArrayList<>();
            for (Future<List<String>> f : futures) {
                for (String hit : f.get()) {
                    if (!merged.contains(hit)) {
                        merged.add(hit);
                    }
                }
            }
            return merged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并行检索被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("并行检索失败: " + e.getCause(), e);
        }
    }

    /** 对照组:串行检索(总耗时为各源之和)。 */
    public List<String> retrieveSerial(String query) {
        List<String> merged = new ArrayList<>();
        for (RetrievalSource src : sources) {
            for (String hit : src.search(query)) {
                if (!merged.contains(hit)) {
                    merged.add(hit);
                }
            }
        }
        return merged;
    }
}
```

> **迁移到结构化并发**:把 `newVirtualThreadPerTaskExecutor()` + `Future` 换成 `StructuredTaskScope.ShutdownOnFailure`,能获得"任一源失败即取消其余"的自动取消语义。API 稳定后推荐迁移。

### 3.3 TenantAwareCache:带租户隔离的缓存

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 简单的租户隔离缓存:key 强制带 tenant 前缀,杜绝跨租户命中(呼应 Ch15/Ch16)。
 * 生产环境替换为 Redis(带 TTL、容量淘汰)。
 */
public final class TenantAwareCache<V> {

    private final Map<String, V> store = new ConcurrentHashMap<>();

    private String key(TenantId tenant, String rawKey) {
        Objects.requireNonNull(tenant, "TenantId 不能为 null");
        return "tenant:" + tenant.value() + ":" + rawKey;
    }

    /** 读缓存;未命中则用 loader 计算、写入并返回(带日志标记命中与否)。 */
    public V getOrLoad(TenantId tenant, String rawKey, Supplier<V> loader) {
        String k = key(tenant, rawKey);
        V cached = store.get(k);
        if (cached != null) {
            System.out.println("[cache HIT ] " + k);
            return cached;
        }
        System.out.println("[cache MISS] " + k);
        V loaded = loader.get();
        store.put(k, loaded);
        return loaded;
    }

    public Optional<V> peek(TenantId tenant, String rawKey) {
        return Optional.ofNullable(store.get(key(tenant, rawKey)));
    }
}
```

### 3.4 TimedStage:验证优化效果的计时工具

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import java.util.function.Supplier;

/** 轻量计时工具:呼应 Ch14,优化前后用它对比耗时。 */
public final class TimedStage {

    private TimedStage() {}

    /** 执行 body 并打印耗时,返回结果。 */
    public static <T> T measure(String label, Supplier<T> body) {
        long start = System.nanoTime();
        T result = body.get();
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[%-16s] cost=%dms%n", label, ms);
        return result;
    }
}
```

### 3.5 Chapter17Demo:串行 vs 并行 + 缓存命中

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.List;

/** 演示:并行检索显著快于串行;缓存二次命中几乎零耗时。 */
public final class Chapter17Demo {

    public static void main(String[] args) {
        // 两个检索源:向量 200ms,BM25 180ms
        RetrievalSource vector = new SlowRetrievalSource(
                "vector", 200, List.of("chunk-A", "chunk-B"));
        RetrievalSource bm25 = new SlowRetrievalSource(
                "bm25", 180, List.of("chunk-B", "chunk-C"));

        ParallelRetriever retriever = new ParallelRetriever(List.of(vector, bm25));
        String query = "年假政策";

        // 串行:约 200+180=380ms
        List<String> serial = TimedStage.measure(
                "serial", () -> retriever.retrieveSerial(query));
        System.out.println("  结果=" + serial);

        // 并行:约 max(200,180)=200ms
        List<String> parallel = TimedStage.measure(
                "parallel", () -> retriever.retrieve(query));
        System.out.println("  结果=" + parallel);

        // 缓存:第一次 MISS(走并行检索),第二次 HIT(几乎 0ms)
        TenantId tenant = TenantId.of("tenant-acme");
        TenantAwareCache<List<String>> cache = new TenantAwareCache<>();

        List<String> first = TimedStage.measure("cache-1st",
                () -> cache.getOrLoad(tenant, "q:" + query, () -> retriever.retrieve(query)));
        List<String> second = TimedStage.measure("cache-2nd",
                () -> cache.getOrLoad(tenant, "q:" + query, () -> retriever.retrieve(query)));
        System.out.println("  两次结果一致=" + first.equals(second));
    }
}
```

**运行输出示意**:

```
[serial          ] cost=381ms
  结果=[chunk-A, chunk-B, chunk-C]
[parallel        ] cost=203ms
  结果=[chunk-A, chunk-B, chunk-C]
[cache MISS] tenant:tenant-acme:q:年假政策
[cache-1st       ] cost=202ms
[cache HIT ] tenant:tenant-acme:q:年假政策
[cache-2nd       ] cost=0ms
  两次结果一致=true
```

并行把 381ms 压到 203ms;缓存二次命中直接归零。**这就是可观测数据驱动优化的闭环**。

---

## 4. 真实项目落地

### 4.1 虚拟线程的正确打开方式

- **Spring Boot 4 一键开启**:`spring.threads.virtual.enabled=true`,Tomcat/内嵌容器的请求处理线程即虚拟线程,thread-per-request 直接高并发。
- **并发子任务用虚拟线程 executor**:检索、Rerank 调用等 IO 子任务用 `newVirtualThreadPerTaskExecutor()`,用完即弃(每任务一线程,不复用、无需调大小)。
- **别再手工调线程池大小**:虚拟线程池不需要"核心线程数/最大线程数"这套调参,创建成本近乎零。

### 4.2 警惕 Pinning(钉住)

虚拟线程的性能红利有个前提:阻塞时能被**卸载**。但有两种情况会把虚拟线程**钉在** OS 线程上,退化成平台线程:

1. **`synchronized` 块内发生阻塞**(Java 21 已知限制,后续版本改进);
2. **调用了本地方法(JNI)里的阻塞**。

**对策**:热点路径上把 `synchronized` 换成 `ReentrantLock`(它对虚拟线程友好),避免在锁内做 IO。可用 `-Djdk.tracePinnedThreads=full` 诊断。

### 4.3 缓存落地:Redis 多级 + 失效策略

- **本地缓存(Caffeine)+ 分布式缓存(Redis)** 两级:本地扛热点、Redis 保一致。
- **embedding 缓存**:key = tenant + hash(文本),TTL 长(内容不变);
- **检索/答案缓存**:key 带 tenant + query,**知识库更新时按 tenant 主动失效**(发一条失效消息清对应前缀);TTL 短。
- **务必带 tenant 前缀**——这是 Ch15/Ch16 反复强调的红线,缓存是最隐蔽的串租户源头。

### 4.4 用 Ch14 指标验证优化,进 Ch16 成本核算

- 优化前后对比 Ch14 的 stage P99,量化收益;
- 并行检索还能降**成本**:更短的持有时间释放连接资源;缓存命中直接省掉大模型 token(Ch16 的 `LLM_TOKENS`),优化性能的同时优化账单。

### 4.5 流式输出(Streaming)改善"感知延迟"

大模型生成是最慢一环。与其让用户等完整答案 3 秒,不如**边生成边流式返回**(SSE / WebSocket)。总耗时没变,但**首字延迟(TTFT)**从 3s 降到 300ms,用户体验天差地别。这是"感知性能"优化,与真实性能优化同等重要。

---

## 5. 避坑与优化

### 5.1 不是所有步骤都能并行

**坑**:把有依赖的步骤强行并行。推理链里 `Rerank` 依赖 `Retrieval` 的输出,`Verifier` 依赖生成的答案——这些**必须串行**。

**对**:只并行**真正独立**的步骤(如混合检索的多个源、多个子查询的分别检索)。先画依赖图,识别可并行的边界。

### 5.2 虚拟线程不适合 CPU 密集任务

**坑**:以为虚拟线程是"万能加速器",把纯计算任务也丢上去。虚拟线程的红利来自**阻塞时卸载**,CPU 密集任务不阻塞,用它反而没收益,甚至因调度有开销。

**对**:IO 密集(检索、模型调用、DB)用虚拟线程;CPU 密集(向量计算、重排打分)用固定大小的平台线程池(数量 ≈ CPU 核数)。

### 5.3 缓存穿透 / 击穿 / 雪崩

**坑**:热点 key 失效瞬间大量请求穿透到后端;不存在的 key 反复查后端。

**对**:热点 key 失效用**互斥重建**(单飞,singleflight),只让一个请求回源;不存在的结果也短时间缓存空值防穿透;TTL 加随机抖动防同时失效雪崩。

### 5.4 并行放大了错误传播

**坑**:并行发起 3 个检索源,其中一个源慢/挂,整体被拖住或失败。

**对**:每个子任务加**超时**;用结构化并发的"任一失败即取消其余";对非关键源做**降级**(如 BM25 挂了就只用向量结果,而非整体失败)。

### 5.5 优化要基于数据,别过早优化

**坑**:凭直觉优化非瓶颈环节(如把 5ms 的 ACL 优化到 3ms),徒增复杂度。

**对**:永远先看 Ch14 的 stage 耗时找**真正的大头**(通常是检索、Rerank、大模型生成),集中火力。"过早优化是万恶之源"——先测量,再动手。

---

## 本章小结

- 知识库 Agent **IO 密集 + 串行累加**,天然慢;虚拟线程让 **thread-per-request** 重新高性能,用同步代码拿异步吞吐。
- 三大手段:**并行独立步骤**(混合检索总耗时取最大值)、**多级缓存**(带 tenant 前缀)、**虚拟线程承载并发 IO**。
- 结构化并发(`StructuredTaskScope`)提供清晰的并发生命周期与自动取消;本章用稳定的 `newVirtualThreadPerTaskExecutor()` 保证可编译。
- 避坑核心:只并行独立步骤、CPU 密集别用虚拟线程、防缓存穿透击穿雪崩、警惕 Pinning、优化基于 Ch14 数据而非直觉。
- 优化闭环:**Ch14 测量定位 → 优化 → Ch14 验证 → Ch16 成本核算**,性能与成本一并改善。

系统跑得快了,但快而不安全等于零。下一章 **Chapter 18 · 安全加固与合规**,把 ACL(Ch07)、租户隔离(Ch15)、审计等串成企业级安全纵深体系。