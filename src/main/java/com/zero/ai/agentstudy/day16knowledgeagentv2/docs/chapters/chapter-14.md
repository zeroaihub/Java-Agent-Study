# Chapter 14 · Observability 可观测性

> 企业知识库 Agent V2 · 第 14 章
> 上一章我们用**离线评估**在上线前守门,用**线上反馈**在上线后探测。但反馈是"用户主动告诉你哪里不好",而绝大多数问题用户**不会反馈**——他们只是默默流失。本章补上运营闭环最后一块:**可观测性(Observability)**,让系统自己"说话"。

---

## 1. 为什么学(Why)

### 1.1 一个凌晨三点的故障

某天凌晨,知识库 Agent 的 P99 延迟从 800ms 飙到 12s。用户第二天早上开始投诉"AI 卡死"。你打开日志,面对的是几百万行 `System.out.println`,无从下手:

- 是**检索**慢?还是**重排序**慢?还是**大模型生成**慢?
- 是**所有租户**都慢?还是**某个大租户**拖垮了共享资源?
- 是**这次上线**引入的?还是**外部依赖**(pgvector / Elasticsearch)抖动?

如果没有可观测性,你只能靠**猜**+**重启**。而有了可观测性,你在告警响应的 5 分钟内就能定位到:"`rerank` 阶段 P99 从 200ms 涨到 9s,只发生在 tenant=acme,因为它的知识库昨晚导入了 50 万个新片段。"

### 1.2 可观测性 ≠ 监控

很多人把"加几个监控"等同于可观测性,这是误区。二者的本质区别:

| 维度 | 监控(Monitoring) | 可观测性(Observability) |
|------|------------------|--------------------------|
| 回答的问题 | **系统是否健康?**(已知问题) | **系统为什么不健康?**(未知问题) |
| 前提 | 你**预先知道**要监控什么 | 你**事先不知道**会出什么问题 |
| 手段 | 固定阈值告警 | 高维度、可下钻、可关联的数据 |
| 类比 | 汽车仪表盘的"故障灯" | 修车师傅的"诊断电脑" |

企业级 Agent 面对的是**未知的未知**(unknown unknowns):你无法预测用户会问什么刁钻问题、哪个租户会突然暴涨、大模型某天会不会抽风。所以我们要的不是"故障灯",而是"诊断电脑"。

### 1.3 可观测性三支柱与推理链的天然契合

可观测性的三支柱是 **Metrics(指标)、Logging(日志)、Tracing(链路追踪)**。而我们前 13 章构建的推理链——`Router→Query→Retrieval→Rerank→ACL→Citation→Verifier`——天然就是一条**多阶段流水线**,每个阶段都值得被观测:

- **Metrics**:每个阶段的耗时、成功率、命中率(可聚合、可告警)。
- **Logging**:关键决策点的结构化日志(为什么路由到这个知识库?为什么这句话没通过验证?)。
- **Tracing**:一次请求穿过所有阶段的完整链路(还记得贯穿全链路的 `traceId` 吗?它就是为此埋下的伏笔)。

**本章目标**:把 `traceId` 从"日志里的一个字符串",升级为"贯穿三支柱、可下钻、可归因"的可观测性骨架。

---

## 2. 是什么(What)

### 2.1 核心概念:Span 与 Trace

一次用户请求 = 一条 **Trace**(链路),它由多个 **Span**(跨度)组成,每个 Span 对应推理链的一个阶段:

```
Trace (traceId=abc123)
├── Span: router        [0ms   → 15ms ]  decision=KB_PRODUCT
├── Span: query-plan    [15ms  → 40ms ]  subQueries=3
├── Span: retrieval     [40ms  → 240ms]  hits=20
├── Span: rerank        [240ms → 440ms]  topScore=0.91
├── Span: acl-filter    [440ms → 445ms]  filtered=2
├── Span: citation      [445ms → 460ms]  citations=4
└── Span: verify        [460ms → 780ms]  status=SUPPORTED
```

每个 Span 记录:**阶段名、起止时间(→耗时)、状态、关键属性(tags)**。整条 Trace 让你一眼看出**时间花在哪、哪一步出了错**。

### 2.2 关键指标(Metrics)设计

不是所有东西都值得做指标。企业 Agent 的**黄金指标**分三类:

1. **延迟(Latency)**:每阶段 P50/P95/P99 耗时——定位性能瓶颈。
2. **流量与错误(Traffic & Errors)**:每阶段 QPS、错误率、各 `AgentErrorCode` 分布——定位稳定性问题。
3. **质量(Quality)**:检索命中率、验证通过率、知识缺口率、反馈负面率——这是**知识库 Agent 独有**的业务指标,复用了 Ch10~Ch13 的成果。

> **避坑预告**:所有指标都必须带 **tenant 维度**(多租户),否则"平均值"会骗你——大租户的抖动被小租户的正常值稀释,你根本看不见。

### 2.3 结构化日志(Structured Logging)

抛弃 `System.out.println("检索失败")`。企业级日志必须是**结构化的键值对**(JSON),且**每条都带 traceId**:

```json
{"ts":"...","level":"WARN","traceId":"abc123","tenant":"acme","stage":"verify","event":"SENTENCE_UNSUPPORTED","sentence":"...","confidence":0.42}
```

这样才能在 ELK / Loki 里**按 traceId 聚合一整条链路**,或**按 tenant+stage+event 下钻**统计。

### 2.4 本章交付物

一个**零框架依赖**的可观测性骨架(生产环境替换为 Micrometer + OpenTelemetry):

- `SpanRecord`:单个阶段的观测记录(record)。
- `TraceCollector`:一次请求的链路收集器,收口所有 Span。
- `MetricsRegistry`:指标注册表端口(可插拔:内存版 → Micrometer)。
- `InMemoryMetricsRegistry`:演示用内存实现。
- `ObservableStage`:装饰器,把任意推理阶段"包裹"成可观测的。
- `Chapter14Demo`:模拟一次完整请求,产出 Trace + Metrics + 结构化日志。

---

## 3. 怎么用(How)

> 包路径:`com.zero.ai.agentstudy.day16knowledgeagentv2.observability`
> 依赖:仅 `AgentContext`(Ch02)与 JDK,零框架依赖。

### 3.1 SpanRecord:单个阶段的观测记录

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 一个 Span:推理链中某个阶段的观测快照。
 *
 * @param stage      阶段名,如 "retrieval" / "rerank" / "verify"
 * @param startNanos 阶段开始的纳秒时间戳(System.nanoTime)
 * @param endNanos   阶段结束的纳秒时间戳
 * @param ok         该阶段是否成功
 * @param tags       关键属性(如 hits=20, topScore=0.91),用于下钻
 */
public record SpanRecord(
        String stage,
        long startNanos,
        long endNanos,
        boolean ok,
        Map<String, Object> tags) {

    public SpanRecord {
        Objects.requireNonNull(stage, "stage 不能为 null");
        if (endNanos < startNanos) {
            throw new IllegalArgumentException("endNanos 不能早于 startNanos");
        }
        // 防御性拷贝 + 不可变,tags 允许为空
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** 该阶段耗时(毫秒)。 */
    public long costMs() {
        return Duration.ofNanos(endNanos - startNanos).toMillis();
    }
}
```

### 3.2 TraceCollector:一次请求的链路收集器

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.AgentContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 收口一次请求(一个 traceId)里所有阶段的 Span。
 * 非线程安全:一次请求内串行调用;跨请求各自持有独立实例。
 */
public final class TraceCollector {

    private final String traceId;
    private final String tenant;
    private final List<SpanRecord> spans = new ArrayList<>();

    private TraceCollector(String traceId, String tenant) {
        this.traceId = traceId;
        this.tenant = tenant;
    }

    /** 从 AgentContext 派生:traceId 与租户维度天然继承。 */
    public static TraceCollector fromContext(AgentContext ctx) {
        Objects.requireNonNull(ctx, "AgentContext 不能为 null");
        return new TraceCollector(ctx.traceId(), ctx.tenantId().value());
    }

    public void add(SpanRecord span) {
        spans.add(Objects.requireNonNull(span, "span 不能为 null"));
    }

    public String traceId() {
        return traceId;
    }

    public String tenant() {
        return tenant;
    }

    public List<SpanRecord> spans() {
        return List.copyOf(spans);
    }

    /** 整条链路总耗时(所有 Span 的最早开始到最晚结束)。 */
    public long totalCostMs() {
        if (spans.isEmpty()) {
            return 0;
        }
        long minStart = spans.stream().mapToLong(SpanRecord::startNanos).min().orElse(0);
        long maxEnd = spans.stream().mapToLong(SpanRecord::endNanos).max().orElse(0);
        return (maxEnd - minStart) / 1_000_000;
    }

    /** 打印一条可读的 Trace(演示用;生产环境导出到 Jaeger/Tempo)。 */
    public void printTrace() {
        System.out.printf("Trace(traceId=%s, tenant=%s, total=%dms)%n",
                traceId, tenant, totalCostMs());
        for (SpanRecord s : spans) {
            System.out.printf("  ├─ %-12s %5dms  ok=%-5s %s%n",
                    s.stage(), s.costMs(), s.ok(), s.tags());
        }
    }
}
```

### 3.3 MetricsRegistry:指标注册表端口 + 内存实现

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

/**
 * 指标注册表端口。生产环境用 Micrometer 的 MeterRegistry 适配。
 * 关键设计:每个指标都必须带 tenant 与 stage 维度,支持下钻。
 */
public interface MetricsRegistry {

    /** 记录一次阶段耗时(用于 timer / histogram)。 */
    void recordLatency(String tenant, String stage, long costMs);

    /** 计数一次阶段结果(ok=true 成功,false 失败)。 */
    void incr(String tenant, String stage, boolean ok);
}
```

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 演示用内存实现:按 (tenant, stage) 聚合。
 * 生产环境请勿在内存里无界累加——用 Micrometer 导出到 Prometheus。
 */
public final class InMemoryMetricsRegistry implements MetricsRegistry {

    private record Key(String tenant, String stage) {}

    private final Map<Key, LongAdder> totalCost = new ConcurrentHashMap<>();
    private final Map<Key, LongAdder> count = new ConcurrentHashMap<>();
    private final Map<Key, LongAdder> errors = new ConcurrentHashMap<>();

    @Override
    public void recordLatency(String tenant, String stage, long costMs) {
        Key k = new Key(tenant, stage);
        totalCost.computeIfAbsent(k, x -> new LongAdder()).add(costMs);
        count.computeIfAbsent(k, x -> new LongAdder()).increment();
    }

    @Override
    public void incr(String tenant, String stage, boolean ok) {
        if (!ok) {
            errors.computeIfAbsent(new Key(tenant, stage), x -> new LongAdder()).increment();
        }
    }

    /** 打印聚合快照(演示用)。 */
    public void printSnapshot() {
        System.out.println("=== Metrics Snapshot (avg latency & error count) ===");
        totalCost.forEach((k, adder) -> {
            long n = count.getOrDefault(k, new LongAdder()).sum();
            long err = errors.getOrDefault(k, new LongAdder()).sum();
            long avg = n == 0 ? 0 : adder.sum() / n;
            System.out.printf("  tenant=%s stage=%-12s avgMs=%d errors=%d%n",
                    k.tenant(), k.stage(), avg, err);
        });
    }
}
```

### 3.4 ObservableStage:把任意阶段包裹成可观测的

这是本章的**点睛之笔**——用**装饰器模式**,让业务代码**零侵入**地获得可观测能力:

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 装饰器:包裹一个阶段的业务逻辑,自动产出 Span + 上报 Metrics + 结构化日志。
 * 业务代码只需写"这个阶段做什么",观测能力由本类横切注入。
 */
public final class ObservableStage {

    private final TraceCollector trace;
    private final MetricsRegistry metrics;

    public ObservableStage(TraceCollector trace, MetricsRegistry metrics) {
        this.trace = trace;
        this.metrics = metrics;
    }

    /**
     * 执行并观测一个阶段。
     *
     * @param stage 阶段名
     * @param tags  关键属性(命中数、分数等)
     * @param body  业务逻辑
     */
    public <T> T run(String stage, Map<String, Object> tags, Supplier<T> body) {
        long start = System.nanoTime();
        boolean ok = true;
        try {
            return body.get();
        } catch (RuntimeException e) {
            ok = false;
            // 结构化错误日志:带 traceId + tenant + stage,可在 ELK 下钻
            System.out.printf(
                    "{\"level\":\"ERROR\",\"traceId\":\"%s\",\"tenant\":\"%s\",\"stage\":\"%s\",\"event\":\"STAGE_FAILED\",\"error\":\"%s\"}%n",
                    trace.traceId(), trace.tenant(), stage, e.getMessage());
            throw e;
        } finally {
            long end = System.nanoTime();
            SpanRecord span = new SpanRecord(stage, start, end, ok, tags);
            trace.add(span);
            metrics.recordLatency(trace.tenant(), stage, span.costMs());
            metrics.incr(trace.tenant(), stage, ok);
        }
    }
}
```

### 3.5 Chapter14Demo:模拟一次完整请求

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.Map;
import java.util.Set;

/** 演示:一次请求穿过推理链,自动产出 Trace + Metrics + 日志。 */
public final class Chapter14Demo {

    public static void main(String[] args) {
        AgentContext ctx = AgentContext.of(
                TenantId.of("tenant-acme"),
                WorkspaceId.of("ws-01"),
                UserId.of("user-42"),
                Set.of("EMPLOYEE"));

        MetricsRegistry metrics = new InMemoryMetricsRegistry();
        TraceCollector trace = TraceCollector.fromContext(ctx);
        ObservableStage obs = new ObservableStage(trace, metrics);

        // 模拟推理链各阶段(用 sleep 模拟耗时,tags 记录关键属性)
        obs.run("router", Map.of("decision", "KB_PRODUCT"), () -> sleep(15));
        obs.run("query-plan", Map.of("subQueries", 3), () -> sleep(25));
        obs.run("retrieval", Map.of("hits", 20), () -> sleep(200));
        obs.run("rerank", Map.of("topScore", 0.91), () -> sleep(200));
        obs.run("acl-filter", Map.of("filtered", 2), () -> sleep(5));
        obs.run("citation", Map.of("citations", 4), () -> sleep(15));
        obs.run("verify", Map.of("status", "SUPPORTED"), () -> sleep(320));

        System.out.println();
        trace.printTrace();
        System.out.println();
        ((InMemoryMetricsRegistry) metrics).printSnapshot();
    }

    private static Object sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
```

**运行输出示意**:

```
Trace(traceId=<uuid>, tenant=acme, total=780ms)
  ├─ router        15ms  ok=true  {decision=KB_PRODUCT}
  ├─ query-plan    25ms  ok=true  {subQueries=3}
  ├─ retrieval    200ms  ok=true  {hits=20}
  ├─ rerank       200ms  ok=true  {topScore=0.91}
  ├─ acl-filter     5ms  ok=true  {filtered=2}
  ├─ citation      15ms  ok=true  {citations=4}
  ├─ verify       320ms  ok=true  {status=SUPPORTED}

=== Metrics Snapshot (avg latency & error count) ===
  tenant=acme stage=retrieval    avgMs=200 errors=0
  tenant=acme stage=verify       avgMs=320 errors=0
  ...
```

一眼看出:`verify` 与 `retrieval`/`rerank` 是耗时大头——这就是优化的靶心。

---

## 4. 真实项目落地

### 4.1 别自己造轮子:标准栈

本章的手写骨架只为讲清原理。生产环境请直接对接业界标准:

| 支柱 | 演示实现 | 生产标准 |
|------|----------|----------|
| Metrics | `InMemoryMetricsRegistry` | **Micrometer** → Prometheus → Grafana |
| Tracing | `TraceCollector` | **OpenTelemetry** → Jaeger / Tempo |
| Logging | `System.out.printf` | **Logback/Log4j2 JSON** → Loki / ELK |

Spring Boot 4 + Micrometer + OpenTelemetry 的组合,只需引入 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`,`traceId` 会**自动注入** MDC 与 Span,你在 `ObservableStage` 里做的事,框架会自动帮你做。

### 4.2 三支柱靠 traceId 关联

可观测性的威力不在三支柱各自强大,而在**它们能被 traceId 串起来**:

```
告警(Metrics: verify P99 突增)
   │  按 tenant 下钻 → acme
   ▼
查 Trace(Tracing: 找到几条慢 traceId)
   │  拿 traceId
   ▼
查日志(Logging: WHERE traceId=xxx)→ 看到 verify 因 NLI 模型超时重试 3 次
```

这就是**从"什么坏了"到"为什么坏"再到"具体哪行"**的完整下钻路径。设计时务必保证 `traceId` 三处一致——这正是我们从 Ch02 就让它贯穿全链路的原因。

### 4.3 业务质量指标进 Grafana

把 Ch10~Ch13 的成果做成**质量看板**:验证通过率(Ch10)、知识缺口率(Ch11)、反馈负面率(Ch12)、评估分趋势(Ch13),按 tenant 分面板。运营团队每天看这块板子,就能在用户投诉前发现"某租户知识库质量在下滑"。

### 4.4 告警要分级

- **P0(电话叫醒)**:错误率 > 5%、P99 > 5s。
- **P1(工作时间处理)**:某租户知识缺口率环比涨 50%。
- **P2(看板观察)**:平均反馈负面率缓慢上升。

---

## 5. 避坑与优化

### 5.1 高基数(High Cardinality)是指标杀手

**坑**:把 `traceId`、`userId`、`query` 文本当作指标的 label(标签)。指标的 label 组合数会爆炸(每个 traceId 都建一条时间序列),直接压垮 Prometheus。

**对**:高基数信息放 **Tracing/Logging**(它们本就为高基数设计);指标 label 只用**低基数、有限枚举**的维度:`tenant`、`stage`、`errorCode`、`status`。

### 5.2 观测本身不能拖垮业务

**坑**:同步、阻塞地把每个 Span 写到远程后端,观测反而成了新的延迟来源。

**对**:观测数据**异步批量**上报;用**采样**(如 P99 慢请求 100% 采样、正常请求 1% 采样)控制成本;`ObservableStage` 里绝不做重 IO。

### 5.3 日志分级与脱敏

**坑**:把用户 query 原文、检索到的知识内容全量打进日志——既是**性能灾难**,又是**合规风险**(可能含 PII / 商业机密)。

**对**:日志只记**结构化元数据**(命中数、分数、状态、错误码);敏感内容脱敏或只记引用 ID;区分 DEBUG(开发)/ INFO(关键决策)/ WARN(降级)/ ERROR(失败)。

### 5.4 别只测"平均值"

**坑**:只看平均延迟。平均值会掩盖长尾——平均 200ms 可能藏着 P99=8s 的糟糕体验。

**对**:永远用 **P50/P95/P99 分位数**,而非平均值。用户的真实体验在长尾里。

### 5.5 可观测性要"左移"

**坑**:等上线出事了才想起加埋点。

**对**:埋点是**功能的一部分**,写业务代码时就用 `ObservableStage` 包起来。可观测性左移到开发阶段,而不是运维阶段的补救。

---

## 本章小结

- **可观测性 ≠ 监控**:前者回答"为什么坏"(未知的未知),后者回答"是否坏"(已知问题)。
- **三支柱**:Metrics(可聚合告警)、Logging(可下钻细节)、Tracing(可串联全链路),靠 **traceId** 关联——这正是 `AgentContext.traceId()` 从 Ch02 埋下的伏笔。
- **装饰器模式**让观测能力**零侵入**注入推理链;所有指标必须带 **tenant 维度**。
- **避坑核心**:控制指标基数、异步采样上报、日志脱敏分级、用分位数而非平均值、埋点左移。
- 生产环境:Micrometer + OpenTelemetry + Loki/ELK,别自己造轮子。

至此,运营治理闭环(评估→反馈→可观测性)全部打通。下一章进入**多租户隔离(Multi-Tenant)**——把一直贯穿代码的 `tenantId` 从"一个字段"升级为"系统级的隔离边界"。