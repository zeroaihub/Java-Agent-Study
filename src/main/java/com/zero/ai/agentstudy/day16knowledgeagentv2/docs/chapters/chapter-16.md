# Chapter 16 · SaaS 化与计量计费

> 企业知识库 Agent V2 · 第 16 章
> 上一章把 `tenantId` 升级为**隔离边界**,解决了"多个租户如何安全共存"。本章解决下一个问题:**多个租户如何被公平地经营**——用量计量、配额限流、套餐分级、成本归集。这是把技术平台变成**可持续商业产品**的关键一跃。

---

## 1. 为什么学(Why)

### 1.1 不计量的 SaaS,是在替用户烧自己的钱

知识库 Agent 的每一次请求都在**真实花钱**:向量检索消耗 CPU/IO,Rerank 调用模型,生成阶段消耗大模型 token(这是最贵的一项)。如果不计量:

- **成本失控**:某个租户写了个死循环脚本,一夜之间打了几百万次请求,大模型账单爆炸,而你毫不知情;
- **无法定价**:你不知道每个租户消耗了多少资源,只能"拍脑袋"收固定月费,重度用户占轻度用户的便宜,最终亏损;
- **无法止损**:滥用发生时,没有配额闸门,只能眼睁睁看着账单飙升。

**一句话:能计量,才能定价;能定价,才能盈利;有配额,才能止损。**

### 1.2 计量与限流是一体两面

- **计量(Metering)**:记录"谁用了多少"——事后账单的依据。
- **限流/配额(Quota & Rate Limit)**:控制"谁最多能用多少"——事前的闸门。

二者共享同一份"用量"数据:计量负责**累加**,配额负责**判断累加值是否越界**。所以它们通常在同一个组件里实现。

### 1.3 与前面章节的关系

- **依赖 Ch15**:计量必须**按 tenant 归集**——没有租户隔离,计量就是一笔糊涂账。
- **复用 Ch14**:用量本质是一种业务指标,可观测性的 Metrics 骨架天然能承载它;但**计费级**的计量要求**精确、可审计、不丢数据**,比可观测性指标(允许采样、可丢失)要求更高。
- **服务 Ch18/19**:配额超限是一种需要被审计(Ch18)、被监控告警(Ch19)的事件。

---

## 2. 是什么(What)

### 2.1 计量三要素:What / How much / Whose

一条计量记录回答三个问题:

- **What**:计量什么维度?——请求数、token 数、检索次数、存储的片段数。
- **How much**:用了多少?——数值 + 单位。
- **Whose**:算谁的?——tenantId(必须)+ 可选 userId(用于租户内部再分摊)。

企业知识库 Agent 的典型**计量维度**:

| 维度 | 单位 | 成本来源 |
|------|------|----------|
| `REQUESTS` | 次 | 整体调度、固定开销 |
| `LLM_TOKENS` | token | 大模型生成(**最贵**) |
| `RETRIEVALS` | 次 | 向量库 / ES 查询 |
| `STORAGE_CHUNKS` | 个 | 知识库存储占用 |

### 2.2 配额与限流:三种闸门

- **配额(Quota)**:周期内的总量上限(如"专业版每月 100 万 token")。跨越长周期,累计判断。
- **速率限制(Rate Limit)**:短时间窗口的频率上限(如"每秒最多 10 次请求"),防突发打爆。
- **并发限制(Concurrency)**:同时进行的请求数上限,保护后端资源。

本章聚焦最贴近商业的**周期配额**,并给出速率限制的思路。

### 2.3 套餐分级(Plan)

不同套餐 = 不同配额组合。企业 SaaS 典型三档:

| 套餐 | 月请求配额 | 月 token 配额 | 超限策略 |
|------|-----------|--------------|----------|
| FREE | 1,000 | 100,000 | 硬拦截(拒绝) |
| PRO | 100,000 | 10,000,000 | 硬拦截 + 可购买加量包 |
| ENTERPRISE | 无上限 | 按合同 | 软限(告警不拦截) |

关键设计:**套餐是数据,不是代码**。新增套餐、调整配额应改配置/数据库,而非改代码重新发版。

### 2.4 本章交付物

- `UsageDimension`:计量维度枚举(REQUESTS / LLM_TOKENS / RETRIEVALS / STORAGE_CHUNKS)。
- `UsageRecord`:一条不可变计量记录(tenant + 维度 + 数量 + 时间)。
- `Plan`:套餐定义(名称 + 各维度配额),配置化。
- `UsageMeter`:计量器端口(记录用量 + 查询累计)。
- `InMemoryUsageMeter`:演示用内存实现(按 tenant+维度累加)。
- `QuotaGuard`:配额闸门(用量 + 套餐 → 放行 / 拒绝)。
- `Chapter16Demo`:模拟 FREE 租户逐步用满配额直到被拦截。

---

## 3. 怎么用(How)

> 包路径:`com.zero.ai.agentstudy.day16knowledgeagentv2.billing`
> 复用 `core.model.TenantId`(Ch02),零框架依赖。

### 3.1 UsageDimension:计量维度枚举

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.billing;

/** 可计量的用量维度。每个维度对应一种成本来源。 */
public enum UsageDimension {

    REQUESTS("次", "请求数"),
    LLM_TOKENS("token", "大模型消耗 token"),
    RETRIEVALS("次", "向量/ES 检索"),
    STORAGE_CHUNKS("个", "知识库存储片段");

    private final String unit;
    private final String desc;

    UsageDimension(String unit, String desc) {
        this.unit = unit;
        this.desc = desc;
    }

    public String unit() {
        return unit;
    }

    public String desc() {
        return desc;
    }
}
```

### 3.2 UsageRecord:不可变计量记录

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.billing;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条计量记录。计费级数据必须不可变、可审计。
 *
 * @param tenantId  归属租户(必须)
 * @param dimension 计量维度
 * @param amount    数量(必须 > 0)
 * @param at        发生时间
 */
public record UsageRecord(
        TenantId tenantId,
        UsageDimension dimension,
        long amount,
        Instant at) {

    public UsageRecord {
        Objects.requireNonNull(tenantId, "tenantId 不能为 null");
        Objects.requireNonNull(dimension, "dimension 不能为 null");
        if (amount <= 0) {
            throw new IllegalArgumentException("计量 amount 必须 > 0,实际=" + amount);
        }
        at = at == null ? Instant.now() : at;
    }

    public static UsageRecord of(TenantId tenant, UsageDimension dim, long amount) {
        return new UsageRecord(tenant, dim, amount, Instant.now());
    }
}
```

### 3.3 Plan:套餐(配置化配额)

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.billing;

import java.util.EnumMap;
import java.util.Map;

/**
 * 套餐:各维度的周期配额。quota 值为 -1 表示"无上限"(软限)。
 * 生产环境应从配置中心/数据库加载,而非硬编码。
 */
public record Plan(String name, Map<UsageDimension, Long> quotas) {

    public Plan {
        quotas = quotas == null ? Map.of() : Map.copyOf(quotas);
    }

    /** 取某维度配额;未配置视为 0(默认最严,防止漏配变无限)。 */
    public long quotaOf(UsageDimension dim) {
        return quotas.getOrDefault(dim, 0L);
    }

    public boolean isUnlimited(UsageDimension dim) {
        return quotaOf(dim) < 0;
    }

    // --- 预置套餐工厂(演示;生产从配置加载) ---

    public static Plan free() {
        Map<UsageDimension, Long> q = new EnumMap<>(UsageDimension.class);
        q.put(UsageDimension.REQUESTS, 1_000L);
        q.put(UsageDimension.LLM_TOKENS, 100_000L);
        return new Plan("FREE", q);
    }

    public static Plan enterprise() {
        Map<UsageDimension, Long> q = new EnumMap<>(UsageDimension.class);
        q.put(UsageDimension.REQUESTS, -1L);   // 无上限
        q.put(UsageDimension.LLM_TOKENS, -1L);
        return new Plan("ENTERPRISE", q);
    }
}
```

### 3.4 UsageMeter:计量器端口 + 内存实现

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.billing;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

/** 计量器端口。生产环境:写入 Kafka → 流式聚合 → 计费库。 */
public interface UsageMeter {

    /** 记录一次用量(累加)。 */
    void record(UsageRecord record);

    /** 查询某租户某维度的当前周期累计用量。 */
    long currentUsage(TenantId tenant, UsageDimension dim);
}
```

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.billing;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 演示用内存实现:按 (tenant, dimension) 累加。生产不可用(丢数据、不可审计)。 */
public final class InMemoryUsageMeter implements UsageMeter {

    private record Key(String tenant, UsageDimension dim) {}

    private final Map<Key, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public void record(UsageRecord r) {
        Key k = new Key(r.tenantId().value(), r.dimension());
        counters.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(r.amount());
    }

    @Override
    public long currentUsage(TenantId tenant, UsageDimension dim) {
        AtomicLong c = counters.get(new Key(tenant.value(), dim));
        return c == null ? 0L : c.get();
    }
}
```

### 3.5 QuotaGuard:配额闸门

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.billing;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.Objects;

/** 配额闸门:在扣费/执行【之前】判断是否越界。 */
public final class QuotaGuard {

    private final UsageMeter meter;

    public QuotaGuard(UsageMeter meter) {
        this.meter = Objects.requireNonNull(meter);
    }

    /** 决策结果:是否放行 + 原因。 */
    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "OK");
        }
        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * 判断"再消耗 amount"是否会超配额。
     * 先判断(pre-check),放行后调用方再实际执行并 record。
     */
    public Decision check(TenantId tenant, Plan plan, UsageDimension dim, long amount) {
        if (plan.isUnlimited(dim)) {
            return Decision.allow();
        }
        long used = meter.currentUsage(tenant, dim);
        long quota = plan.quotaOf(dim);
        if (used + amount > quota) {
            return Decision.deny(String.format(
                    "配额超限[%s]:套餐=%s 已用=%d 本次=%d 上限=%d",
                    dim, plan.name(), used, amount, quota));
        }
        return Decision.allow();
    }
}
```

### 3.6 Chapter16Demo:FREE 租户用满配额

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.billing;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

/** 演示:FREE 租户逐步消耗 token,直到配额闸门拦截。 */
public final class Chapter16Demo {

    public static void main(String[] args) {
        TenantId tenant = TenantId.of("tenant-startup");
        Plan plan = Plan.free(); // token 配额 100_000

        UsageMeter meter = new InMemoryUsageMeter();
        QuotaGuard guard = new QuotaGuard(meter);

        // 模拟 3 次请求,每次消耗 40_000 token
        for (int i = 1; i <= 3; i++) {
            long cost = 40_000;
            QuotaGuard.Decision d =
                    guard.check(tenant, plan, UsageDimension.LLM_TOKENS, cost);
            if (d.allowed()) {
                // 放行:执行业务(此处略)后,如实计量
                meter.record(UsageRecord.of(tenant, UsageDimension.LLM_TOKENS, cost));
                System.out.printf("第%d次请求 放行,累计=%d token%n",
                        i, meter.currentUsage(tenant, UsageDimension.LLM_TOKENS));
            } else {
                System.out.printf("第%d次请求 拒绝 => %s%n", i, d.reason());
            }
        }
    }
}
```

**运行输出示意**:

```
第1次请求 放行,累计=40000 token
第2次请求 放行,累计=80000 token
第3次请求 拒绝 => 配额超限[LLM_TOKENS]:套餐=FREE 已用=80000 本次=40000 上限=100000
```

第 3 次因 80000+40000 > 100000 被闸门在**执行前**拦截,避免了超额消耗大模型成本。

---

## 4. 真实项目落地

### 4.1 计量数据管道:精确 vs 高吞吐的平衡

计费级计量对**准确性**要求极高(少算 = 亏钱,多算 = 客诉),同时请求量巨大。典型架构:

```
请求 → 埋点(轻量,写本地/异步) → Kafka(削峰、不丢) → 流式聚合(Flink) → 计费库(PostgreSQL)
```

- 埋点**不能阻塞主流程**(异步),但**不能丢数据**(至少一次投递 + 去重)。
- 用 Kafka 解耦,即使计费库宕机,用量事件也在队列里不丢。
- 聚合层按 tenant + dimension + 周期滚动汇总,支撑账单与实时配额查询。

### 4.2 配额判断:pre-check 与最终一致

- **强一致场景**(硬拦截):配额计数放 Redis,`INCRBY` 原子递增 + 判断,超限即拒。适合 FREE/PRO 硬闸门。
- **最终一致场景**(软限):异步聚合用量,配额略有延迟,超限后告警+事后补扣。适合 ENTERPRISE 大客户,体验优先。
- **令牌桶做速率限制**:Redis + Lua 实现原子令牌桶,控制 QPS,与周期配额是两层不同的闸门。

### 4.3 token 计量的精度问题

大模型 token 是最贵维度,但**难精确预估**:请求前不知道会生成多少 token。实践:

- **pre-check 用预算上限**(如按 max_tokens 预扣),响应后按**实际用量**回补差额。
- 输入 token 可提前算(tokenizer),输出 token 只能事后统计——所以计量点要放在**响应完成后**如实上报。

### 4.4 套餐与计费的解耦

- **套餐(Plan)= 配额规则**,存元数据表,可热更新;
- **订阅(Subscription)= 租户绑定了哪个套餐 + 有效期 + 加量包**;
- **账单(Invoice)= 周期结束按用量 × 单价出账**。
- 三者分离,让"改价、换套餐、送额度"都不需要动核心代码。

---

## 5. 避坑与优化

### 5.1 计量绝不能阻塞或拖慢主流程

**坑**:同步把每次用量写数据库,数据库一慢,用户请求跟着卡。

**对**:埋点异步化(本地缓冲 + 批量刷),用消息队列削峰。主流程只做一次内存计数 + 投递,微秒级。

### 5.2 配额漏配不能变成"无限"

**坑**:某维度忘了在 Plan 里配额度,代码默认返回 `Long.MAX_VALUE` 或直接放行——等于免费无限用。

**对**:**默认最严**。本章 `Plan.quotaOf` 未配置返回 `0`(而非无限),漏配表现为"完全不可用",立刻被发现,而不是"悄悄免费"。

### 5.3 分布式下的计数竞态

**坑**:多实例并发 `读用量→判断→写用量`,存在竞态,配额被击穿(如 10 个实例同时读到 99,999,各自放行)。

**对**:配额计数用 Redis 原子操作(`INCRBY` 后判断返回值),或数据库行锁 / 乐观锁。绝不在应用内存里做跨实例的配额判断。

### 5.4 计量与可观测性别混用

**坑**:直接拿 Ch14 的可观测性指标当账单依据。可观测性允许**采样和丢失**(1% 采样),用来算账会算错。

**对**:**计费级计量独立管道**,要求精确、不丢、可审计、可对账;可观测性指标只用于监控趋势。两者数据源可共享埋点,但存储与保证级别不同。

### 5.5 超限的用户体验

**坑**:配额一满就冷冰冰地报错 429,用户一脸懵。

**对**:超限响应要**可读**(明确告知哪个维度超限、当前套餐、如何升级),提前在 80%/90% 用量时**预警**,并给出"升级套餐 / 购买加量包"的引导。止损的同时不伤体验。

---

## 本章小结

- **能计量才能定价,有配额才能止损**——计量与限流是 SaaS 商业化的一体两面,共享同一份用量数据。
- 计量三要素 **What/How much/Whose**;知识库 Agent 核心维度是 `LLM_TOKENS`(最贵)、`REQUESTS`、`RETRIEVALS`、`STORAGE_CHUNKS`。
- **套餐是数据不是代码**:`Plan` 配置化,改价换档不发版;闸门 `QuotaGuard` 在执行**前** pre-check。
- 生产管道:异步埋点 → Kafka(不丢)→ 流式聚合 → 计费库;硬配额用 Redis 原子计数防竞态。
- 避坑核心:计量不阻塞主流程、漏配默认最严(不变无限)、防分布式竞态击穿、计费计量独立于可观测性、超限体验友好且提前预警。

平台能隔离、能经营了,接下来要让它**跑得快**——下一章 **Chapter 17 · 性能优化与虚拟线程**,用 Java 21 虚拟线程 + 缓存 + 并行检索,把推理链的延迟压到极致。