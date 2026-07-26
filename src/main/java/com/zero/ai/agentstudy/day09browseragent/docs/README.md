# Day09 · 企业级 Browser Agent（浏览器智能体）

> 《30天打造商业级AI Agent平台(Java版)》· 第九天
> 技术栈：Java 17 + Spring Boot 3 + Spring AI + Playwright Java + Maven
> 定位：ZeroHub AI Agent Platform 的「浏览器操作」能力模块

---

## 一、这一天我们要解决什么问题

到 Day08 为止，我们的 Agent 已经会：调用大模型（Day02）、检索知识库（Day05 RAG）、按流程编排任务（Day06 Workflow）、调用外部工具（Tool Calling）。但它始终**活在文本世界里**——它能"说"，却不能"做"。

真实企业场景里，大量任务发生在浏览器中：

- 运营同学每天登录后台，手动导出报表；
- 客服要去第三方系统查订单，复制粘贴到工单里；
- 采购要在多个供应商网站比价、下单；
- 测试要反复走一遍注册-登录-下单的回归路径。

这些系统**没有 API**，或者 API 权限申请要走一个月流程。人能做的事，凭什么 Agent 不能做？

**Browser Agent 的使命：给大模型装上一双"手"和一双"眼睛"，让它能像人一样操作浏览器。** 你用一句自然语言"打开 xxx 网站，登录后把订单列表读出来"，Agent 就自动完成打开、输入、点击、读取的全过程。

这一天，我们要从零构建一个**企业级、可并发、资源安全**的 Browser Agent 模块，而不是一个跑一次就崩的玩具脚本。

---

## 二、你将学到的核心能力

对应学员必须掌握的 16 项浏览器能力，本模块全部落地：

| 能力 | 实现位置 | 说明 |
| --- | --- | --- |
| 打开网页 | `BrowserActionService.openPage` | 导航到 URL 并等待加载 |
| 点击元素 | `click` | 基于选择器点击，内置自动等待 |
| 输入文本 | `fill` | 向输入框填值 |
| 模拟登录 | `login` | 填账号密码 + 点击登录的组合动作 |
| 获取文本 | `getText` | 提取页面纯文本 |
| 获取 HTML | `getHtml` | 提取页面完整 HTML |
| 截图 | `screenshot` | 整页截图存盘 |
| 等待页面/元素 | `waitForSelector` | 显式等待元素出现 |
| 下载文件 | `download` | 事件驱动捕获下载 |
| 上传文件 | `upload` | 设置 input file |
| Headless 模式 | `BrowserProperties.headless` | 无头/有头可配置 |
| Cookie/Session 管理 | `BrowserContext` 隔离 | 每 Context 独立会话态 |
| Browser Tool 封装 | `BrowserTools` | 原子动作封装为可调用工具 |
| Spring AI Tool 封装 | `@Tool` 注解 | 让大模型能自动调用 |
| 并发资源池 | `BrowserContextPool` | Semaphore 控制并发 |
| 自然语言驱动 | `BrowserAgentService` | LLM 编排工具完成任务 |

学完你会真正理解：

1. **Playwright 的对象层次**：Playwright（驱动进程）→ Browser（浏览器进程）→ BrowserContext（隔离会话）→ Page（标签页）；
2. **为什么不能"每次请求新开一个浏览器"**：内存爆炸、启动慢，正确做法是「一个 Browser + 多 Context」；
3. **资源池与并发控制**：如何用 Semaphore 限流、如何保证 Context 用完必被回收；
4. **Spring AI Tool Calling**：如何把 Java 方法暴露成大模型能调用的工具；
5. **企业级资源安全**：try-with-resources、fail-fast、许可不泄漏。

---

## 三、模块整体架构一览

```
                       ┌─────────────────────────────┐
   自然语言指令  ──────▶│   BrowserAgentService       │  LLM 大脑
                       │   (ChatClient + Tools)      │
                       └──────────────┬──────────────┘
                                      │ Tool Calling
                       ┌──────────────▼──────────────┐
                       │        BrowserTools          │  @Tool 工具层
                       │  openWebPage/click/type...   │
                       └──────────────┬──────────────┘
                                      │ 调用原子动作
                       ┌──────────────▼──────────────┐
                       │    BrowserActionService      │  门面：原子操作
                       │  openPage/getText/login...   │
                       └──────────────┬──────────────┘
                                      │ acquire() 借用会话
                       ┌──────────────▼──────────────┐
                       │     BrowserContextPool       │  资源池 + Semaphore
                       │  借/还 BrowserSession        │
                       └──────────────┬──────────────┘
                                      │ 创建 Context/Page
                       ┌──────────────▼──────────────┐
                       │      PlaywrightEngine        │  生命周期唯一持有者
                       │  Playwright → Browser 进程   │
                       └─────────────────────────────┘
```

REST 入口 `BrowserController` 同时暴露两类接口：

- `/day09/browser/action/*`：直接调用原子动作（调试/集成用）；
- `/day09/browser/agent/run`：传自然语言，交给 LLM 自动编排。

详细架构与生命周期图见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

---

## 四、核心代码怎么运转（关键类讲解）

### 1. PlaywrightEngine —— 整个模块的地基

它是**唯一**持有 Playwright 驱动进程和 Browser 浏览器进程的地方，用 `@PostConstruct` 在应用启动时 fail-fast 初始化：启动失败就直接让应用起不来，而不是等到第一次请求才炸。

```java
@PostConstruct
public void init() {
    this.playwright = Playwright.create();
    BrowserType browserType = resolveBrowserType();
    this.browser = browserType.launch(new BrowserType.LaunchOptions()
            .setHeadless(properties.isHeadless())
            .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
}
```

`--no-sandbox --disable-dev-shm-usage` 是**容器化部署的必备参数**，否则在 Docker 里 Chromium 会因权限或共享内存不足而崩溃。

### 2. BrowserContextPool —— 并发的核心

真正的企业级难点在这里。它做三件事：

- **限流**：`Semaphore permits` 控制最多几个会话同时存在；
- **借用**：`acquire()` 阻塞获取许可，超时抛异常（宁可拒绝，也不能无限堆积）；
- **归还**：会话用完销毁 Context，避免登录态污染下一个任务。

```java
public BrowserSession acquire() {
    boolean got = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
    if (!got) throw new IllegalStateException("浏览器会话池繁忙，请稍后重试");
    try {
        BrowserContext context = engine.newContext();
        Page page = context.newPage();
        return new BrowserSession(id, context, page, this::release);
    } catch (RuntimeException e) {
        permits.release(); // 创建失败必须归还许可，否则许可泄漏
        throw e;
    }
}
```

> ⚠️ 这里 `catch` 中的 `permits.release()` 是最容易被漏掉、也最致命的一行：一旦创建 Context 失败又没归还许可，池子会越用越小，最终永久卡死。

### 3. BrowserSession —— try-with-resources 的关键

它实现了 `AutoCloseable`，`close()` 时销毁 Context 并回调归还池。于是所有调用方都能写成：

```java
try (BrowserSession session = pool.acquire()) {
    session.page().navigate(url);
    // ... 操作
} // 自动关闭 Context + 归还许可，绝不泄漏
```

### 4. BrowserTools —— 让大模型能"看懂"的工具

用 Spring AI 的 `@Tool` 注解把原子动作暴露给大模型。`description` 必须**面向意图**写清"什么时候用、参数是什么"，因为大模型就是靠这段描述决定要不要调用它：

```java
@Tool(description = "打开指定网址的网页。当需要访问某个网站时使用，参数 url 是完整网址。")
public String openWebPage(String url) { ... }
```

读取文本/HTML 时做了**长度截断**（3000/5000 字），避免整页塞进模型上下文导致超长和成本飙升。

### 5. BrowserAgentService —— 一句话完成任务

```java
String result = chatClient.prompt()
        .system("你是浏览器操作助手，可以调用工具完成网页任务")
        .user(instruction)
        .tools(browserTools)
        .call().content();
```

`.tools(browserTools)` 把工具交给大模型，剩下的"先打开、再登录、再读取"由模型自己规划，这就是 Tool Calling 的闭环。

---

## 五、如何运行

### 1. 添加依赖（已在 pom.xml 完成）

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.49.0</version>
</dependency>
```

### 2. 安装浏览器内核（第一次运行必做）

Playwright 需要下载 Chromium/Firefox/WebKit 内核，**这是最容易踩的坑**——不装内核，一运行就报 `Executable doesn't exist`。执行：

```bash
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI \
    -D exec.args="install chromium"
```

或安装全部内核：`-D exec.args="install"`。

### 3. 配置 application.yml

```yaml
day09:
  browser:
    headless: true          # 生产用无头模式；本地调试可设 false 看到浏览器
    browser-type: chromium  # chromium / firefox / webkit
    pool-size: 4            # 最大并发会话数
    acquire-timeout-ms: 10000
    navigation-timeout-ms: 30000
    download-dir: ./downloads
    screenshot-dir: ./screenshots
    viewport-width: 1280
    viewport-height: 800
```

大模型相关配置沿用全局 OpenAI Compatible API（`spring.ai.openai.*`）。

### 4. 启动应用并调用

```bash
mvn spring-boot:run
```

**原子动作接口**（调试用）：

```bash
curl -X POST "http://localhost:8080/day09/browser/action/text" \
     -H "Content-Type: application/json" \
     -d '{"url":"https://example.com"}'
```

**自然语言 Agent 接口**：

```bash
curl -X POST "http://localhost:8080/day09/browser/agent/run" \
     -H "Content-Type: application/json" \
     -d '{"instruction":"打开 https://example.com 并把页面正文读出来"}'
```

### 5. 预期效果

- 原子接口返回结构化 `R<T>` 结果（文本/HTML/截图路径）；
- Agent 接口返回大模型自动编排工具后的自然语言总结；
- headless=false 时可在本地看到浏览器自动操作的全过程。

---

## 六、企业级避坑清单（精选，完整版见各章"避坑"段）

1. **不装浏览器内核就运行** → 报 `Executable doesn't exist`。第一次必须 `install`。
2. **每请求新开 Browser** → 内存爆炸、启动慢。正确：一个 Browser + 多 Context。
3. **Context 用完不销毁** → 登录态/Cookie 污染下一个任务。用完必须 close。
4. **Semaphore 许可泄漏** → 创建 Context 失败的 catch 里忘了 `release()`，池子越用越小最终卡死。
5. **不用 try-with-resources** → 异常路径下 Context 泄漏，浏览器进程句柄耗尽。
6. **Docker 里不加 --no-sandbox** → Chromium 直接崩溃起不来。
7. **整页 HTML 塞进大模型** → 上下文超长、成本飙升。必须截断。
8. **@Tool description 写得含糊** → 大模型不知道何时调用，任务失败。要面向意图写清楚。
9. **同步阻塞等待页面** → 用 `Thread.sleep` 而非 Playwright 自动等待，脆弱又慢。
10. **忽略超时配置** → 页面卡死时线程永久阻塞。navigation/acquire 都要设超时。

---

## 七、本章小结

Browser Agent 是 Agent 从"能说"迈向"能做"的关键一跃。本模块的核心不在于会调用 Playwright 的某个 API，而在于**如何把浏览器这种昂贵、有状态、易泄漏的资源，用企业级的方式管好**：进程复用、会话隔离、并发限流、资源安全、Tool 化封装。

掌握了这套模式，你不仅能做 Browser Agent，也能触类旁通地管理任何"重资源 + 高并发 + 需隔离"的能力（数据库连接、RPA 机器人、GPU 推理会话）。

---

## 八、延伸阅读

- Playwright Java 官方文档：https://playwright.dev/java/
- Spring AI Tool Calling 文档：https://docs.spring.io/spring-ai/reference/
- 架构与生命周期详解：[ARCHITECTURE.md](./ARCHITECTURE.md)
- 逐章教程：[chapters/](./chapters/)
- 练习与挑战：[TODO.md](./TODO.md)