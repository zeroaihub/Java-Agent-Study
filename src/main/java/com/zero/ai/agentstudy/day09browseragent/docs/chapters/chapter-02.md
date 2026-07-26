# 第 2 章 · Playwright 与 Browser 核心概念

> 本章目标：彻底搞懂 Playwright 的对象模型（Playwright → Browser → BrowserContext → Page），理解每一层"是什么、为什么存在、生命周期多长、成本多高"。这是后面所有代码的地基——地基不稳，后面越写越乱。

---

## 一、为什么要先学对象模型（Why）

很多人学浏览器自动化，上来就抄一段"打开网页、点击按钮"的代码跑通了，然后就以为自己会了。等到真正做企业级服务时，就会遇到一连串诡异问题：

- 为什么并发一上来内存就爆？
- 为什么 A 用户登录后，B 用户请求居然也是登录状态？
- 为什么应用关闭后，服务器上还残留着一堆 chrome 僵尸进程？
- 为什么有时候点击报错"元素不可见"，有时候又好了？

这些问题的根源，**全都在于不理解 Playwright 的对象模型和各层的生命周期**。

打个比方：如果你不知道"数据库连接"是昂贵资源、需要连接池管理，你就会写出"每次查询新建一个连接"的灾难代码。浏览器自动化里，Browser 就是那个"昂贵连接"。**先建立正确的心智模型，才能写出正确的代码。** 所以本章我们一行业务代码都不急着写，先把概念钉死。

---

## 二、是什么：四层对象模型（What）

Playwright 的核心是一个**四层嵌套**的对象模型，从外到内、从重到轻：

```
Playwright（驱动进程 / 工厂）
   │  Playwright.create()  ——启动一个 Node 驱动子进程
   │
   └── Browser（浏览器进程）
         │  browserType.launch()  ——启动一个真实的 Chromium/Firefox/WebKit 进程
         │
         ├── BrowserContext #1（隔离会话 = 一个独立的"隐身窗口"）
         │     │  browser.newContext()  ——毫秒级创建，独立 Cookie/Storage/缓存
         │     │
         │     ├── Page（标签页）      ——context.newPage()
         │     └── Page（另一个标签页）
         │
         └── BrowserContext #2（另一个完全隔离的会话）
               └── Page
```

我们用一个"出租车公司"的类比，把这四层一次性讲透：

| Playwright 概念 | 类比 | 说明 |
| --- | --- | --- |
| **Playwright** | 出租车公司总部 | 整个应用只要一个。它管理和真实浏览器通信的底层驱动进程。 |
| **Browser** | 一辆出租车 | 买车（启动进程）很贵，一辆车可以载很多趟客人（复用）。 |
| **BrowserContext** | 一位乘客的独立行程 | 每位乘客上车都是全新的：没有前一位乘客的行李（Cookie）、没有他的目的地记忆（登录态）。行程结束就清空。 |
| **Page** | 行程中经过的一个路段 | 一次行程里可以经过多个路段（一个 Context 可以开多个标签页）。 |

### 2.1 Playwright（进程级驱动）

`Playwright.create()` 会在后台启动一个 **Node.js 驱动子进程**，Java 代码通过这个进程和真实浏览器通信（Playwright 底层是用 Node 实现的，Java版是一层桥接）。

- **成本**：较高，会 fork 一个子进程；
- **数量**：整个应用**只需要一个**；
- **生命周期**：应用启动时创建，应用关闭时销毁；
- **在我们的代码里**：`PlaywrightEngine` 用一个字段 `private Playwright playwright;` 持有它。

### 2.2 Browser（浏览器进程）

`browserType.launch()` 会启动一个**真实的浏览器进程**（比如一个 Chromium 进程）。

- **成本**：**非常高**。启动要几百毫秒到数秒，每个进程占用约 100~300MB 内存；
- **数量**：整个应用**只需要一个**，供所有请求共享；
- **生命周期**：应用启动时创建，应用关闭时销毁；
- **线程安全**：Playwright 官方规定，**Browser 可以跨线程共享**（这正是我们敢做成单例的依据）；
- **在我们的代码里**：`PlaywrightEngine` 用 `private Browser browser;` 持有它，通过 `browser()` 方法对外提供。

> 🔑 **核心结论：Browser 贵，所以必须复用。这是"不能每请求新开浏览器"的根本原因。**

### 2.3 BrowserContext（隔离会话）

`browser.newContext()` 创建一个**完全隔离的浏览器会话**，你可以把它想象成 Chrome 的"隐身窗口"——它有**自己独立的 Cookie、localStorage、sessionStorage、缓存**。

- **成本**：**极低**，毫秒级创建；
- **数量**：**每个请求/任务一个**，可以有很多个并发存在；
- **隔离性**：这是它存在的全部意义——Context A 里登录的账号，Context B 完全看不到。**这就是我们实现多用户会话隔离的关键武器**；
- **在我们的代码里**：`BrowserContextPool.createSession()` 里 `browser.newContext(options)` 创建，用完 `context.close()` 销毁。

> 🔑 **核心结论：Context 轻且隔离，所以用它来承载"每个请求的独立会话"，实现并发和防污染。**

### 2.4 Page（标签页）

`context.newPage()` 在一个 Context 里打开一个**标签页**，它才是真正承载 DOM、执行 `navigate`/`click`/`fill` 等操作的对象。

- **成本**：低；
- **数量**：一个 Context 里可以开多个 Page（多标签场景）；
- **在我们的代码里**：`BrowserSession` 持有一个 `Page page` 字段，所有原子动作都作用在它上面。

---

## 三、怎么用：把概念对应到我们的代码（How）

现在把上面四层，一一对应到 Day09 已经写好的代码里。这样你就能看到"抽象概念"是如何"落到具体类"的。

### 3.1 Playwright + Browser → `PlaywrightEngine`

`PlaywrightEngine` 是这两层的**唯一持有者**：

```java
@Slf4j
@Component
public class PlaywrightEngine {
    private Playwright playwright;   // 第一层
    private Browser browser;         // 第二层

    @PostConstruct
    public void init() {
        this.playwright = Playwright.create();          // 启动驱动进程
        BrowserType browserType = resolveBrowserType(); // chromium/firefox/webkit
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(props.isHeadless())
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage"));
        this.browser = browserType.launch(launchOptions); // 启动浏览器进程
    }
}
```

这里有三个关键设计，逐一解释：

**① 为什么用 `@PostConstruct` 而不是懒加载？**
因为要"快速失败(fail-fast)"。如果浏览器内核没装、驱动启动失败，我们希望**应用启动时就报错崩溃**，而不是等到线上第一个用户请求进来才炸。启动时崩，运维立刻能发现；运行时才炸，可能已经影响了用户。

**② `resolveBrowserType()` 怎么选内核？**
```java
private BrowserType resolveBrowserType() {
    return switch (props.getBrowserType().toLowerCase()) {
        case "firefox" -> playwright.firefox();
        case "webkit"  -> playwright.webkit();
        default        -> playwright.chromium();
    };
}
```
通过配置 `day09.browser.browser-type` 就能切换 chromium/firefox/webkit，**一套代码三种浏览器**，这正是 Playwright 相比 Selenium 的优势。

**③ `--no-sandbox --disable-dev-shm-usage` 是什么？**
这是**容器化部署的保命参数**：
- `--no-sandbox`：容器里通常以 root 运行，Chromium 的沙箱机制在 root 下会拒绝启动，必须关掉；
- `--disable-dev-shm-usage`：容器默认 `/dev/shm`（共享内存）只有 64MB，Chromium 渲染大页面时会用尽导致崩溃，加这个参数让它改用磁盘临时目录。

不加这两个参数，本地开发一切正常，一上 Docker 就崩——这是最经典的"我本地明明能跑"惨案。

### 3.2 应用关闭时的资源释放

```java
@PreDestroy
public void destroy() {
    try { if (browser != null) browser.close(); }        // ① 先关浏览器进程
    catch (Exception e) { log.warn("关闭 Browser 异常: {}", e.getMessage()); }
    try { if (playwright != null) playwright.close(); }   // ② 再关驱动进程
    catch (Exception e) { log.warn("关闭 Playwright 异常: {}", e.getMessage()); }
}
```

**关闭顺序很重要：先关 Browser，再关 Playwright。** 因为 Browser 是 Playwright 驱动出来的"孩子"，先把父进程（Playwright）关了，孩子（Browser）可能变成无人管理的僵尸进程。就像关电脑要先关应用程序再关系统，反过来容易出问题。

每个 `close()` 都单独 try-catch，是为了**保证即使前一个关闭失败，后一个仍会尝试关闭**，最大程度避免进程泄漏。

### 3.3 Context + Page → `BrowserSession`

一个"会话"由一个 Context + 一个 Page 组成，我们用 `BrowserSession` 把它们打包：

```java
@Getter
public class BrowserSession implements AutoCloseable {
    private final BrowserContext context;   // 第三层：隔离会话
    private final Page page;                // 第四层：标签页
    private final String sessionId;
    private final Consumer<BrowserSession> releaseCallback; // 归还池的回调

    public void dispose() {
        try { context.close(); }            // 关 Context，Page 会随之关闭
        catch (Exception ignored) {}
    }

    @Override
    public void close() {
        if (releaseCallback != null) releaseCallback.accept(this); // 归还给池
        else dispose();
    }
}
```

两个关键点：

**① 为什么 `dispose()` 只关 Context 不关 Page？**
因为 **Page 是 Context 的子对象，关闭 Context 会自动关闭它下面所有的 Page**。就像关掉一个"隐身窗口"，窗口里所有标签页自然都关了。我们只需要管最外层的 Context 即可。

**② `implements AutoCloseable` 有什么用？**
这样 `BrowserSession` 就能配合 Java 的 `try-with-resources` 语法自动释放：
```java
try (BrowserSession session = pool.acquire()) {
   session.getPage().navigate(url);
    // ... 用完这里，close() 自动被调用，会话自动归还
}
```
后面第 4、5 章你会看到，**所有原子动作都用这个语法包裹**，这是保证"绝不泄漏会话"的语法级保险。

### 3.4 用一段代码验证"隔离性"

概念说得再多，不如亲眼看一次隔离。下面这段伪代码演示了 Context 的隔离威力（原理示意，非项目代码）：

```java
Browser browser = engine.browser();

// 会话 A：登录了账号 alice
BrowserContext ctxA = browser.newContext();
Page pageA = ctxA.newPage();
pageA.navigate("https://example.com/login");
// ... 完成登录，ctxA 里现在有 alice 的 Cookie

// 会话 B：全新，看不到 alice
BrowserContext ctxB = browser.newContext();
Page pageB = ctxB.newPage();
pageB.navigate("https://example.com/profile");
// pageB 显示"未登录"——因为 ctxB 是全新隔离会话，没有 alice 的 Cookie
```

**同一个 Browser 进程，两个 Context 互不干扰。** 这就是我们能在一台服务器上，用一个浏览器进程，安全地服务多个不同用户的技术根基。

---

## 四、真实项目：ZeroHub 平台里的对象模型落地

在我们要构建的 ZeroHub AI Agent 平台中，Browser Agent 是"能操作网页"的一只手。它的资源模型直接决定了平台的并发能力和成本：

| 层级 | ZeroHub 中的实例数 | 对应业务含义 |
| --- | --- | --- |
| Playwright | 1（整个服务） | 一套驱动 |
| Browser | 1（整个服务） | 一个共享浏览器进程，节省内存 |
| BrowserContext | N（每个用户任务一个） | 用户 A 抓价格、用户 B 填表单，互不干扰 |
| Page | 每个任务 1~多个 | 一个任务里可能开多个标签页协作 |

**一句话总结落地策略：Browser 单例复用省成本，Context 每任务独立保隔离。** 这个模型可以让单台 4 核 8G 的机器，稳定支撑数十路并发浏览器任务——如果每个任务都新开一个 Browser，同样的机器可能只能跑三五个就 OOM 了。

---

## 五、避坑清单（至少 10 条）

1. **绝不"每请求新建 Browser"**。Browser 启动慢、吃内存，必须全应用单例复用。新建 Browser 是并发场景第一杀手。

2. **绝不"全局共用一个 Context"图省事**。共用 Context 会导致用户之间登录态互相污染——A 登录后 B 也变成登录态，这是严重的安全事故。

3. **每个任务用完 Context 必须关闭**。Context 虽轻，但不关会累积，最终内存耗尽。永远用 `try-with-resources` 或确保 `close()` 被调用。

4. **不要手动去关 Page 而忘了关 Context**。关 Context 会自动关它下面所有 Page；只关 Page 不关 Context 会导致 Context 泄漏。

5. **关闭顺序：先 Browser 后 Playwright**。反过来可能产生浏览器僵尸进程，长期运行的服务会被拖垮。

6. **容器部署必加 `--no-sandbox --disable-dev-shm-usage`**。不加则"本地能跑、Docker 崩溃"，且报错信息往往晦涩难查。

7. **用 `@PostConstruct` 做 fail-fast 初始化**。别用懒加载把"浏览器没装"的错误拖到线上第一个请求才暴露。

8. **不要假设 Browser 不能跨线程**。Playwright 官方明确 Browser 可跨线程共享，这是单例设计的前提；但**同一个 Page 不要多线程并发操作**。

9. **切换浏览器内核靠配置，不要硬编码 `chromium()`**。用 `resolveBrowserType()` 读配置，方便在 firefox/webkit 上做兼容性测试。

10. **别把 sessionId 当强唯一 ID 用于持久化**。它是 `"sess-" + UUID 前 8 位`，用于日志追踪和池内定位，不保证全局绝对唯一，不要拿去做数据库主键。

11. **理解"内存占用主要在 Context 和 Page"**。Browser 是固定开销，真正随并发线性增长的是 Context/Page 数量，容量规划要盯住并发 Context 数。

12. **不要在 Context 之间传递 Page 对象**。Page 属于它的 Context，跨 Context 使用会抛异常。

---

## 六、本章小结

- Playwright 的对象模型是**四层嵌套**：Playwright（驱动进程）→ Browser（浏览器进程）→ BrowserContext（隔离会话）→ Page（标签页），从外到内越来越轻。
- **Browser 昂贵，全应用单例复用**；**Context 轻量隔离，每任务一个**——这一"重一轻"的对比，是所有资源管理决策的出发点。
- 在 Day09 代码里：`PlaywrightEngine` 持有 Playwright + Browser；`BrowserSession` 打包 Context + Page；`BrowserContextPool`（下一步会细讲）负责按需创建和归还会话。
- 记住三条铁律：**Browser 单例复用**、**Context 每任务独立且用完必关**、**关闭先 Browser 后 Playwright**。

> 下一章（第 3 章）我们将动手搭好环境——安装浏览器内核、配置 `pom.xml` 依赖、跑通第一个"打开网页并截图"的自动化脚本，把本章的概念真正跑起来。

---
