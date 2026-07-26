# 第 5 章 · 生命周期与并发资源池

> 本章目标：吃透 Browser Agent 的"并发心脏"——`BrowserContextPool`。你将理解：为什么必须要池？Semaphore 如何限流？会话如何借出与归还？许可泄漏为什么致命、如何防范？应用关闭时如何优雅清理残留会话？这一章决定了你的 Browser Agent 是"能扛并发的生产服务"还是"一压就崩的玩具"。

---

## 一、为什么需要资源池（Why）

回顾前几章的核心结论：**Browser 进程昂贵（100~300MB/个，启动要数秒），必须全应用单例复用；Context 轻量隔离，每任务一个。**

但这里有个新问题：Context 虽轻，也不能**无限**创建。设想一个爆款场景——瞬间来了 1000 个请求，每个都创建一个 Context，会怎样？

- 1000 个 Context = 1000 个隔离会话，每个都占用内存和 CPU；
- 单个 Browser 进程扛不住上千个并发标签页，内存飙升直接 OOM；
- 服务器被拖垮，所有请求一起失败。

**所以我们需要一个"闸门"，控制"同一时刻最多允许多少个 Context 并发存在"。** 这个闸门就是**资源池 + 信号量（Semaphore）**。

用类比理解：Browser 是一个"停车场"，Context 是"车位"。停车场容量有限（poolSize），车位满了后来的车必须**排队等待**，不能硬塞。资源池就是这个"停车场管理员"。

看类头注释，把这个动机写得很清楚：

```java
/**
 * BrowserContextPool —— 浏览器会话「资源池」，是 Day09 并发能力的核心。
 * 为什么需要池？如果每来一个 Agent 请求就 launch 一个新 Browser，
 * 内存和进程数会瞬间爆炸（每个 Chromium 实例约 100~300MB）。企业级方案是：
 * 用信号量(Semaphore)控制最大并发数，共享同一个 Browser 进程，
 * 每个请求借一个隔离的 BrowserContext 使用，用完归还。
 */
```

---

## 二、是什么：池的三个核心成员（What）

`BrowserContextPool` 内部有三个关键字段：

```java
@Slf4j
@Component
public class BrowserContextPool {
    private final PlaywrightEngine engine;   // ① Browser 来源
    private final BrowserProperties props;   // ② 配置（池大小、超时等）

    /** ③ 并发许可：最多同时存在 poolSize 个活动 Context */
    private final Semaphore permits;

    /** ④ 活动会话登记表（sessionId -> Session），用于管理与强制关闭 */
    private final ConcurrentHashMap<String, BrowserSession> activeSessions = new ConcurrentHashMap<>();

    public BrowserContextPool(PlaywrightEngine engine, BrowserProperties props) {
        this.engine = engine;
        this.props = props;
        this.permits = new Semaphore(props.getPoolSize(), true);  // 关键：公平信号量
    }
}
```

### 2.1 Semaphore（信号量）——限流的核心

`Semaphore` 是 Java 并发包提供的"许可发放器"。你在构造时告诉它"总共有多少张许可"（`poolSize`），之后：
- 线程要干活前先 `acquire()` **领一张许可**（没许可就阻塞等待）；
- 干完活 `release()` **归还许可**（后面排队的线程就能领到）。

**它保证同一时刻最多只有 `poolSize` 个线程持有许可**，也就是最多 `poolSize` 个 Context 并发存在。这正是我们要的"闸门"。

**`new Semaphore(poolSize, true)` 的第二个参数 `true` 是什么？**
它表示**公平模式(fair)**——排队的线程按"先到先得"的顺序获得许可，避免某些请求一直抢不到许可被"饿死"。企业服务追求可预测的延迟，公平锁虽然略有性能开销，但换来的是**没有请求被无限期饿死**，这个权衡是值得的。

### 2.2 ConcurrentHashMap（活动会话登记表）

`activeSessions` 记录"当前有哪些会话正在使用中"（sessionId → Session）。它的作用：
- **监控**：随时知道当前活动会话数（`activeCount()`）；
- **强制清理**：应用关闭时，遍历它把所有残留会话强制销毁（`shutdown()`）。

用 `ConcurrentHashMap` 是因为**多线程会并发地借/还会话**，普通 HashMap 在并发下会数据错乱甚至死循环，必须用线程安全的容器。

### 2.3 简化池模型：用完销毁而非复用

注释里点明了本实现的策略选择：

```java
/**
 * 本实现采用「按需创建 + 用完销毁」的简化池模型（每次归还销毁 Context，
 * 释放信号量许可）。这样能天然避免「上一个用户的登录态污染下一个用户」的问题。
 * 若要极致性能，可改为「归还时清空 Cookie 后复用」。
 */
```

这是一个**重要的工程权衡**：

| 策略 | 优点 | 缺点 |
| --- | --- | --- |
| **用完销毁**（本实现） | 天然隔离，绝不会有登录态污染，代码简单 | 每次都新建 Context，有轻微创建开销 |
| **清空后复用** | 省去创建开销，性能更好 | 要小心清理 Cookie/Storage，清不干净就污染 |

本实现选"用完销毁"，是因为**Context 创建本就极快（毫秒级），而安全隔离更重要**。教学和绝大多数并发场景，这个选择都是对的。第 8 章会讨论何时该升级为"复用"模式。

---

## 三、怎么用：借出与归还的完整链路（How）

### 3.1 借出会话：`acquire()`

这是全池最关键的方法，我们逐段拆解：

```java
public BrowserSession acquire() {
    // 第一段：领许可（限流闸门）
    try {
        boolean got = permits.tryAcquire(props.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        if (!got) {
            throw new IllegalStateException("浏览器池繁忙，获取会话超时（poolSize="
                    + props.getPoolSize() + "），请稍后重试或增大 pool-size");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();     // 恢复中断标志
        throw new IllegalStateException("获取浏览器会话被中断", e);
    }

    // 第二段：创建会话（拿到许可后才创建）
    try {
        BrowserSession session = createSession();
        activeSessions.put(session.getSessionId(), session);
        log.info("[Day09][Pool] 借出会话 {}，当前活动数={}", session.getSessionId(), activeSessions.size());
        return session;
    } catch (RuntimeException e) {
        // 关键：创建失败必须归还许可，否则许可泄漏会导致池永久缩水
        permits.release();
        throw e;
    }
}
```

**第一段——带超时的领许可：**
`tryAcquire(timeout, MILLISECONDS)` 尝试在 `acquireTimeoutMs`（默认 10 秒）内领到许可。
- 领到了 → 返回 true，继续；
- 超时没领到 → 返回 false，抛出"池繁忙"异常。

**为什么用带超时的 `tryAcquire` 而不是死等的 `acquire()`？**
如果用无限期死等，当池长期满载时，请求线程会**永久挂起**，最终拖垮整个服务（线程池被占满）。带超时则是"等 10 秒还借不到就明确失败"，让调用方（和用户）**快速得到反馈**，可以重试或降级。**快速失败远好于无限挂起**，这是高并发系统的黄金准则。

`InterruptedException` 的处理也很讲究：先 `Thread.currentThread().interrupt()` **恢复中断标志**（因为 catch 会吞掉中断状态），再抛异常。这是 Java 并发编程的标准礼仪，不这么做会导致上层无法感知线程被中断。

**第二段——创建会话，以及最关键的"许可泄漏防范"：**
拿到许可后才 `createSession()`，成功则登记进 `activeSessions` 并返回。

注意那个 `catch (RuntimeException e)`：

```java
} catch (RuntimeException e) {
    permits.release();   // 创建失败必须归还许可！
    throw e;
}
```

**这行 `permits.release()` 是整个池最容易被忽视、却最致命的一行。** 想象一下：如果 `createSession()` 因为浏览器崩溃抛了异常，而我们**没有归还刚才领到的许可**，会发生什么？

- 这张许可就"凭空消失"了——领了没还；
- 池的可用许可从 4 变成了 3；
- 再失败几次，许可耗尽变成 0，**整个池永久死锁**，所有后续请求全部超时；
- 这就是"许可泄漏"，比会话泄漏更隐蔽、更致命。

**黄金法则：领了许可后，无论成功失败，都必须保证有对应的 release。** 成功时由归还流程 release，失败时由这个 catch 补偿 release。缺了任何一条路径，都是定时炸弹。

### 3.2 创建会话：`createSession()`

```java
private BrowserSession createSession() {
    Browser browser = engine.browser();   // 拿到共享的 Browser
    Browser.NewContextOptions options = new Browser.NewContextOptions()
            .setViewportSize(new ViewportSize(props.getViewportWidth(), props.getViewportHeight()))
            .setIgnoreHTTPSErrors(props.isIgnoreHttpsErrors());
    if (props.getUserAgent() != null && !props.getUserAgent().isBlank()) {
        options.setUserAgent(props.getUserAgent());   // 配了 UA 才设置
    }

    BrowserContext context = browser.newContext(options);
    // 统一默认超时，避免每处操作手动传超时
    context.setDefaultTimeout(props.getDefaultTimeoutMs());
    context.setDefaultNavigationTimeout(props.getNavigationTimeoutMs());

    Page page = context.newPage();
    String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);

    return new BrowserSession(sessionId, context, page, this::release);  // 注入归还回调
}
```

几个关键点：

- **统一浏览器指纹**：viewport（视口）、UA、忽略证书错误都在这里统一设置。UA 只在配置了非空值时才设，否则用浏览器默认——这就是第 3 章"配置全默认值"的落地；
- **在 Context 层设默认超时**：`setDefaultTimeout` / `setDefaultNavigationTimeout` 一次设置，该 Context 下所有操作都生效，**避免第 4 章每个方法都手动传超时**；
- **sessionId** = `"sess-" + UUID前8位`，用于日志追踪，短小便于阅读；
- **注入 `this::release` 回调**：这是点睛之笔——把池的 `release` 方法作为回调塞进 `BrowserSession`。这样当上层 `try-with-resources` 结束调用 `session.close()` 时，`BrowserSession` 就会**自动回调到池的 release**，形成闭环。上层完全不知道池的存在，却能自动归还。

### 3.3 归还会话：`release()`

```java
private void release(BrowserSession session) {
    activeSessions.remove(session.getSessionId());   // ① 从登记表移除
    session.dispose();                               // ② 销毁 Context（连带关闭 Page）
    permits.release();                               // ③ 归还许可，唤醒排队者
    log.info("[Day09][Pool] 归还并销毁会话 {}，当前活动数={}", session.getSessionId(), activeSessions.size());
}
```

三步顺序清晰：移除登记 → 销毁资源 → 归还许可。第 ③ 步 `permits.release()` 归还许可后，**正在 `acquire()` 里排队等待的线程就会被唤醒**，拿到许可继续干活。这就是"停车场有车开走，排队的车立刻能进"的效果。

回顾第 2 章的 `BrowserSession.close()`：

```java
@Override
public void close() {
    if (releaseCallback != null) releaseCallback.accept(this);  // 回调到池的 release
    else dispose();
}
```

现在整条链路闭合了：
```
上层 try-with-resources 结束
  → session.close()
    → releaseCallback.accept(this)   （就是 pool 注入的 this::release）
      → pool.release(session)
        → 移除登记 + dispose() + permits.release()
```

**上层只写了个 `try (...)`，底层就完成了"销毁会话 + 归还许可 + 唤醒排队者"的全套动作。** 这就是良好封装的美感。

### 3.4 监控与优雅关闭

```java
public int activeCount() {
    return activeSessions.size();   // 当前活动会话数，供监控用
}

@PreDestroy
public void shutdown() {
    log.info("[Day09][Pool] 关闭池，清理 {} 个残留会话", activeSessions.size());
    activeSessions.values().forEach(BrowserSession::dispose);   // 强制销毁所有残留
    activeSessions.clear();
}
```

- **`activeCount()`**：暴露当前活动会话数，可接入监控告警（"活动数长期贴近 poolSize"就是该扩容的信号）；
- **`@PreDestroy shutdown()`**：应用关闭时，**强制销毁所有还没归还的残留会话**。为什么需要？因为可能有请求正在执行到一半应用就关闭了，这些"在途会话"如果不清理，就会变成泄漏的 Context/进程。这是"优雅关闭"的最后一道保险。

注意关闭的**层级配合**：`BrowserContextPool.shutdown()` 先清理所有 Context，然后 `PlaywrightEngine.destroy()` 再关 Browser 和 Playwright。**先清子资源（Context），再关父资源（Browser）**，和第 2 章讲的关闭顺序原则完全一致。

---

## 四、真实项目：ZeroHub 的池容量规划

在 ZeroHub 电商 Agent 平台里，Browser Agent 承担商品比价、竞品截图、自动下单等重活。池的容量规划是一门大学问，下面是团队踩坑后沉淀的经验表：

| 维度 | 配置/策略 | 背后原因 |
| --- | --- | --- |
| poolSize（并发数） | 生产 8，压测调优得出 | 单 Browser 进程稳定支撑 8~10 个 Context；再多内存吃紧、页面响应变慢 |
| 服务器内存 | 每实例预留 4GB | Browser 基础 ~200MB + 8 个 Context 各 ~150MB + JVM 堆，留足余量防 OOM |
| acquireTimeoutMs | 生产 8000ms | 太短则高峰误杀正常请求；太长则用户干等。8s 是"快速失败"与"容忍抖动"的平衡点 |
| 公平锁 fair=true | 开启 | 比价任务耗时不均，公平锁避免长任务饿死短任务，保证 P99 延迟可控 |
| 池策略 | 用完销毁 Context | 电商站点会写 Cookie/localStorage，复用会串数据。销毁保绝对隔离，安全第一 |
| 水平扩展 | 多 Pod 部署 | 单机池到顶后，靠 K8s 多副本 + 负载均衡横向扩容，而非无限调大单机 poolSize |
| 监控指标 | activeCount / acquire 超时率 | activeCount 长期贴近 poolSize = 该扩容；超时率上升 = 容量不足预警 |

**核心心法**：poolSize 不是越大越好，它受限于**单个 Browser 进程的承载力**。真正的扩容手段是"多进程/多 Pod"，而不是把单机池调到天上去。

---

## 五、避坑清单（≥12 条）

1. **许可泄漏最致命**：领了许可（tryAcquire 成功）后，若后续 createSession 抛异常却没 `permits.release()`，许可就凭空消失，反复几次池永久死锁。牢记"领了必还"。

2. **别用死等 `acquire()`**：无参 `acquire()` 会无限阻塞，池满时线程永久挂起、无法感知、无法降级。**必须用带超时的 `tryAcquire(timeout)`**，超时快速失败。

3. **InterruptedException 必须恢复中断标志**：catch 到中断后要先 `Thread.currentThread().interrupt()` 再抛，否则上层无法感知线程被中断，破坏并发协作礼仪。

4. **release 必须放在归还回调里，别指望上层手动调**：靠上层记得调 release 一定会漏。用 `this::release` 注入 + `AutoCloseable.close()` + try-with-resources，让归还**自动发生**。

5. **归还三步顺序不能乱**：先 `activeSessions.remove`（移除登记）→ 再 `dispose`（销毁资源）→ 最后 `permits.release`（归还许可）。先归还许可再销毁,会让新线程拿到许可却抢在旧资源释放前创建，瞬时超额。

6. **ConcurrentHashMap 不能换成 HashMap**：多线程同时 put/remove 普通 HashMap 会死循环或数据错乱。activeSessions 必须用并发安全容器。

7. **poolSize 别盲目调大**：受限于单 Browser 进程承载力（约 8~10）。调太大不会更快，只会 OOM。要扩容请加 Pod。

8. **公平锁 vs 非公平锁要想清楚**：任务耗时差异大时用 fair=true 防饿死；追求极致吞吐且任务均匀时可用非公平锁。本项目选公平锁保 P99。

9. **@PreDestroy shutdown 不能省**：应用关闭时若不强制 dispose 残留会话，"在途会话"的 Context/进程会泄漏，反复重启后系统进程数暴涨。

10. **关闭层级不能反**：先关 Context（子）再关 Browser（父）。先关 Browser 会导致其下 Context 关闭时报错或残留。

11. **createSession 里 UA 要判空再设**：直接 `setUserAgent("")` 会覆盖成空 UA 导致部分站点拒绝访问。必须"配了非空值才设"。

12. **默认超时设在 Context 层，别每处手写**：`setDefaultTimeout` 一次设置全 Context 生效，避免第 4 章每个动作手动传超时、漏传导致死等。

13. **activeCount 要接监控**：长期贴近 poolSize 是扩容信号，acquire 超时率上升是容量告警。裸奔无监控 = 出事才知道。

---

## 六、本章小结

本章我们吃透了 Browser Agent 的"并发心脏"`BrowserContextPool`：

- **为什么要池**：Browser 昂贵、Context 不能无限创建，需要"停车场闸门"用 Semaphore 限流；
- **三核心成员**：Semaphore（公平锁限流）、ConcurrentHashMap（并发安全登记表）、用完销毁策略（保绝对隔离）；
- **借出 acquire**：带超时 tryAcquire 快速失败 + InterruptedException 恢复中断 + **许可泄漏 catch 补偿 release**；
- **创建 createSession**：统一浏览器指纹 + Context 层设默认超时 + 注入 `this::release` 回调；
- **归还 release**：移除登记 → 销毁 → 归还许可唤醒排队者，与 `BrowserSession.close()` 形成自动闭环；
- **优雅关闭**：@PreDestroy 强制清残留，先清子资源后关父资源。

**一句话记住本章**：好的资源池让上层只写一行 `try (...)`，底层就自动完成"限流、借出、隔离、归还、唤醒"的全套并发调度——而"领了许可必归还"是永不死锁的黄金铁律。

> **下一章预告**：有了原子动作门面（第4章）和并发资源池（第5章），我们的 Browser Agent 已经具备了"稳定的执行引擎"。但它现在还只能被 Java 代码调用——LLM 该怎么"看见"并"调用"这些能力呢？**第 6 章《Spring AI Tool 封装》**将揭晓：如何用 `@Tool` / `@ToolParam` 把浏览器动作暴露成大模型能理解、能自主调用的"工具",让自然语言真正驱动浏览器。