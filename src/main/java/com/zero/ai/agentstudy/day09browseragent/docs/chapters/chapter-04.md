# 第 4 章 · 原子动作门面 BrowserActionService

> 本章目标：吃透 `BrowserActionService`——它把 Playwright 的底层 API，封装成 10 个"语义清晰、资源安全、自动等待"的原子动作（打开/读文本/读HTML/点击/输入/登录/截图/等待/下载/上传）。这一层是 Browser Agent 的"能力层"，上层的 Tool、Controller、Workflow 全部只面向它。

---

## 一、为什么需要一个"门面"层（Why）

设想一下，如果没有这一层封装，会发生什么？

上层的 LLM Tool、REST Controller、工作流节点，全都要**直接调用 Playwright 的原生 API**：自己去 `pool.acquire()`、自己 `newPage()`、自己 `navigate()`、自己 `waitForLoadState()`、自己记得 `close()`……

这会带来三个灾难：

1. **重复代码泛滥**：每个调用点都要写一遍"借会话→操作→归还"的样板，改一处要改十处；
2. **资源泄漏遍地**：只要有一个调用点忘了 `close()`，会话就泄漏，日积月累必然 OOM；
3. **无法替换底层**：哪天想 Playwright 换成别的引擎，所有调用点都要重写。

**门面模式（Facade）** 就是为了解决这些问题：**用一个统一的高层接口，把底层的复杂性和资源管理全部藏起来**。上层只说"我要点击这个按钮"，不用关心"怎么借会话、怎么等待、怎么归还"。

一句话：`BrowserActionService` 就是 Browser Agent 的"能力清单"，也是隔离底层引擎的"防火墙"。

---

## 二、是什么：门面的设计定位（What）

看类头注释，设计定位写得很清楚：

```java
/**
 * BrowserActionService —— 浏览器「原子操作」的统一门面（Facade）。
 * 本类是 Browser Agent 的「能力层」，把 Playwright 的底层 API
 * 封装成一组语义清晰、可被 Spring AI Tool 直接调用的高层动作。
 * 每个方法都遵循「借会话 → 操作 → try-with-resources 自动归还」的资源安全范式。
 */
@Slf4j
@Service
public class BrowserActionService {
    private final BrowserContextPool pool;      // 会话来源
    private final BrowserProperties props;      // 配置（目录、超时等）

    public BrowserActionService(BrowserContextPool pool, BrowserProperties props) {
        this.pool = pool;
        this.props = props;
    }
}
```

它依赖两样东西：
- **`BrowserContextPool pool`**：会话池，所有动作都从这里借会话（第 5 章详讲）；
- **`BrowserProperties props`**：配置，主要用到截图目录、下载目录。

**三个设计特征**（贯穿全部 10 个方法）：

1. **语义化**：方法名就是意图——`openPage`/`click`/`fill`/`download`，一看就懂；
2. **资源安全**：每个方法都用 `try (BrowserSession session = pool.acquire())` 包裹，**自动借、自动还**；
3. **自动等待**：依赖 Playwright 的内置等待机制（`waitForLoadState`、`click` 自带等待），**杜绝 `Thread.sleep`**。

---

## 三、怎么用：10 个原子动作逐一拆解（How）

### 3.1 统一范式：借会话 → 操作 → 自动归还

先记住这个所有方法共用的骨架，后面就不重复解释了：

```java
public String someAction(...) {
    try (BrowserSession session = pool.acquire()) {   // ① 借会话（可能阻塞等待）
        Page page = session.getPage();                // ② 拿到标签页
        // ③ 在 page 上执行具体操作
        return result;
    }                                                 // ④ 离开 try 块，close() 自动归还会话
}
```

关键在第 ④ 步：因为 `BrowserSession implements AutoCloseable`（第 2 章讲过），`try-with-resources` 会在块结束时**自动调用 `close()`**，把会话归还给池。**无论正常返回还是抛异常，会话都保证被归还**——这就是不会泄漏的语法级保证。

### 3.2 动作①：`openPage` 打开网页

```java
public String openPage(String url) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);                            // 导航（自动等页面加载）
        page.waitForLoadState(LoadState.NETWORKIDLE);  // 再等到"网络空闲"
        String title = page.title();
        log.info("[Day09][Action] 打开页面成功 url={}, title={}", url, title);
        return title;
    }
}
```

**为什么 `navigate` 之后还要 `waitForLoadState(NETWORKIDLE)`？**
`navigate` 默认只等到 `load` 事件（HTML 加载完），但现代网页大量内容是 JS 异步渲染的。`NETWORKIDLE` 表示"网络连续 500ms 没有新请求"，此时页面基本渲染完毕。**不等这一步，你可能读到还没渲染出来的空页面。**

### 3.3 动作②③：`getText` / `getHtml` 读取内容

```java
public String getText(String url) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return page.innerText("body");   // 渲染后的可见文本
    }
}

public String getHtml(String url) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return page.content();           // JS 执行后的完整 DOM
    }
}
```

这两个方法的差异非常重要：

| 方法 | 返回 | 适合场景 |
| --- | --- | --- |
| `getText` → `innerText("body")` | **去标签后的可见正文** | 喂给 LLM 阅读理解（token 少、干净） |
| `getHtml` → `content()` | **渲染后的完整 HTML** | 需要精确解析结构、提取属性/链接 |

注释里点出了关键：`content()` 返回的是 **JS 执行后的 DOM**，不是原始 HTTP 响应——这意味着**动态渲染的内容（如 Vue/React 页面）也能拿到**，这正是浏览器自动化相比"直接 HTTP 请求"的核心优势。

### 3.4 动作④：`click` 点击

```java
public String click(String url, String selector) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        page.click(selector);                          // 内置"自动等待可交互"
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return page.title();
    }
}
```

**`page.click(selector)` 最大的优点：自动等待。** Playwright 的 click 会自动等到元素满足"可见、稳定、可接收事件、未被遮挡"这几个条件才点击。所以**你永远不需要在 click 前写 `Thread.sleep(2000)`**——那是 Selenium 时代的坏习惯，既慢又不可靠。

`selector` 支持多种写法：`"#submit"`（CSS id）、`".btn"`（class）、`"text=登录"`（按文本匹配）。

### 3.5 动作⑤：`fill` 输入文本

```java
public String fill(String url, String selector, String text) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        page.fill(selector, text);          // 先清空再输入
        return page.inputValue(selector);   // 返回输入后的 value 做校验
    }
}
```

`fill` 会**先清空输入框再填入**（区别于 `type` 是逐字追加）。返回 `inputValue` 是一个贴心设计——**把"输入后的实际值"返回给调用方做校验**，确认真的填进去了。

### 3.6 动作⑥：`login` 组合动作（登录）

```java
public String login(String url, String userSelector, String username,
                    String passSelector, String password, String submitSelector) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        page.fill(userSelector, username);     // 填账号
        page.fill(passSelector, password);     // 填密码
        page.click(submitSelector);            // 点登录
        page.waitForLoadState(LoadState.NETWORKIDLE);
        String body = page.innerText("body");
        log.info("[Day09][Action] 登录流程执行完成 url={}, user={}", url, username);
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
```

这是**"多步操作组合"的典型示例**：填账号 → 填密码 → 点登录，三步在一个会话里连续完成。注释也诚实地指出：真实登录往往还要处理验证码、跳转、二次确认，这里给的是**可扩展的骨架**。

注意结尾 `body.length() > 500 ? substring(0,500) : body`——**截断到 500 字**，避免返回超长文本。这是内容截断策略的一个体现（第 6 章还会看到 3000/5000 的截断）。

---

### 3.7 动作⑦：`screenshot` 全屏截图

```java
public String screenshot(String url) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);

        Path dir = ensureDir(props.getScreenshotDir());                       // 确保目录存在
        Path file = dir.resolve("shot_" + LocalDateTime.now().format(TS) + ".png"); // 时间戳命名
        page.screenshot(new Page.ScreenshotOptions().setPath(file).setFullPage(true));
        log.info("[Day09][Action] 截图已保存 {}", file.toAbsolutePath());
        return file.toAbsolutePath().toString();
    }
}
```

三个细节：
- **`setFullPage(true)`**：截整个页面（包括需要滚动才能看到的部分），而不只是当前视口——这对长页面存证很重要；
- **时间戳文件名** `shot_20260725_143022_888.png`（格式 `yyyyMMdd_HHmmss_SSS`，含毫秒）：**避免并发时文件名冲突互相覆盖**；
- **返回绝对路径**：方便调用方（尤其是 LLM）知道文件确切位置。

### 3.8 动作⑧：`waitForSelector` 显式等待

```java
public boolean waitForSelector(String url, String selector, double timeout) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        try {
            page.waitForSelector(selector,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)  // 等到"可见"
                            .setTimeout(timeout));
            return true;
        } catch (Exception e) {
            log.warn("[Day09][Action] 等待选择器超时 selector={}", selector);
            return false;   // 超时不抛异常，而是返回 false
        }
    }
}
```

两个要点：
- **`setState(VISIBLE)`**：等元素"可见"而不只是"存在于 DOM"。很多元素先渲染进 DOM 但 `display:none`，只等"存在"会误判；
- **超时不抛异常，返回 boolean**：这是有意的设计——"元素有没有出现"本身就是调用方想知道的结果，用 `true/false` 表达比抛异常更自然，调用方可以据此走不同分支。

虽然大部分操作靠自动等待，但当你需要**明确判断"某个异步内容到底出没出现"**时，`waitForSelector` 就是精确控制的工具。

### 3.9 动作⑨：`download` 事件驱动下载

```java
public String download(String url, String triggerSelector) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        // 关键：waitForDownload 在 Runnable 内触发点击，同时捕获下载事件
        Download download = page.waitForDownload(() -> page.click(triggerSelector));
        Path dir = ensureDir(props.getDownloadDir());
        Path target = dir.resolve(download.suggestedFilename());  // 用网站建议的文件名
        download.saveAs(target);
        log.info("[Day09][Action] 文件下载完成 {}", target.toAbsolutePath());
        return target.toAbsolutePath().toString();
    }
}
```

这里体现了 Playwright 的**事件驱动范式**，是本章最容易写错的地方：

**为什么点击要写在 `waitForDownload(() -> ...)` 里面？**
因为下载是异步事件。如果你先 `click()` 再去"等下载"，下载事件可能在你开始等之前就已经触发了，导致**错过事件、永久阻塞**。正确做法是**先挂上监听器，再在监听器内部触发点击**——`waitForDownload` 保证监听已就绪后才执行那个 Runnable（点击），这样绝不会漏掉事件。

`download.suggestedFilename()` 用网站建议的原始文件名保存，`saveAs` 落盘到配置的下载目录。

### 3.10 动作⑩：`upload` 上传文件

```java
public String upload(String url, String fileSelector, String localPath) {
    try (BrowserSession session = pool.acquire()) {
        Page page = session.getPage();
        page.navigate(url);
        page.setInputFiles(fileSelector, Paths.get(localPath));  // 直接给 file input 赋值
        log.info("[Day09][Action] 文件上传完成 file={}", localPath);
        return "已上传: " + localPath;
    }
}
```

**上传的正确姿势是 `setInputFiles`，而不是"模拟点击选择框再操作系统文件对话框"。** 系统文件对话框是操作系统级弹窗，浏览器自动化根本控制不了它。`setInputFiles` 直接给 `<input type="file">` 元素设置文件路径，绕过了对话框，既简单又可靠。

### 3.11 私有工具：`ensureDir`

```java
private Path ensureDir(String dir) {
    Path path = Paths.get(dir);
    try {
        Files.createDirectories(path);   // 递归创建，目录已存在也不报错
    } catch (Exception e) {
        throw new IllegalStateException("创建目录失败: " + dir, e);
    }
    return path;
}
```

截图和下载前都调它，**确保目标目录存在**。`Files.createDirectories` 会递归创建多级目录，且目录已存在时不报错（幂等）。这避免了"目录不存在导致保存失败"的低级错误。

---

## 四、真实项目：门面层在 ZeroHub 中的价值

在 ZeroHub 平台里，`BrowserActionService` 这一层带来了三个实实在在的工程收益：

| 收益 | 说明 |
| --- | --- |
| **能力复用** | 同一个 `openPage`，既能被 LLM Tool 调用，也能被 REST 接口、定时任务、工作流节点调用，一次实现处处可用 |
| **底层可替换** | 若未来要把 Playwright 换成其他引擎，只需改这一层内部实现，上层 Tool/Controller 一行不用动 |
| **统一治理** | 日志、超时、目录、截断策略都集中在这一层，想加"全局重试"或"操作审计"，改一处即可全局生效 |

**这就是分层架构的威力：把"会变的底层"和"稳定的上层"隔离开。** 门面层就是那道隔离墙。在企业里，这道墙决定了系统"改一处"还是"改十处"的巨大差别。

---

## 五、避坑清单（至少 10 条）

1. **绝不用 `Thread.sleep` 等页面**。Playwright 的 click/fill/waitForSelector 都内置自动等待，`sleep` 既拖慢速度又不可靠（网络快了浪费、网络慢了失败）。

2. **`navigate` 后要 `waitForLoadState(NETWORKIDLE)`**，尤其读内容前。否则可能读到 JS 还没渲染完的空页面。

3. **下载必须用"先挂监听再点击"的 `waitForDownload(() -> click())` 范式**。先点后等会错过事件导致永久阻塞。

4. **上传用 `setInputFiles`，不要试图操作系统文件对话框**。那是 OS 级弹窗，浏览器自动化控制不了。

5. **截图文件名要带时间戳（含毫秒）**。并发场景下固定文件名会互相覆盖，丢失存证。

6. **`waitForSelector` 要指定 `VISIBLE` 状态**。只等"存在于 DOM"会把 `display:none` 的隐藏元素误判为已出现。

7. **喂给 LLM 优先用 `getText`（innerText）而非 `getHtml`**。HTML 标签会浪费�大量 token 且噪音大，纯文本更干净。

8. **保存文件前先 `ensureDir`**。目录不存在会导致保存直接失败，且报错信息往往不直观。

9. **每个方法都必须用 `try-with-resources` 借会话**。漏掉一个就是一处会话泄漏，长期运行必然 OOM。

10. **返回值要能被调用方校验/使用**。如 `fill` 返回 `inputValue`、`waitForSelector` 返回 boolean、下载返回绝对路径——让调用方拿到"操作是否真的成功"的证据。

11. **login 只是骨架，真实登录要处理验证码/跳转/二次确认**。不要以为填三个框点一下就万能，生产环境要针对具体站点扩展。

12. **超长返回要截断**。login 截 500 字、readPageText 截 3000 字——避免把巨量文本塞进日志或 LLM 上下文，既费钱又可能触发上下文超限。

---

## 六、本章小结

- `BrowserActionService` 是 Browser Agent 的**能力层门面**，把 Playwright 底层 API 封装成 10 个语义化原子动作。
- 三大设计特征：**语义化**（方法名即意图）、**资源安全**（全部 `try-with-resources` 自动借还）、**自动等待**（杜绝 `sleep`）。
- 10 个动作：`openPage`、`getText`、`getHtml`、`click`、`fill`、`login`、`screenshot`、`waitForSelector`、`download`、`upload`。
- 关键范式：下载用**事件驱动**（先挂监听再触发）、上传用 **setInputFiles**、读文本优先 **innerText**、保存前 **ensureDir**、超长内容**截断**。
- 门面层的核心价值：**隔离底层、统一治理、能力复用**——这是分层架构在企业项目里"改一处 vs 改十处"的分水岭。

> 下一章（第 5 章）我们深入 Browser Agent 的并发心脏——`BrowserContextPool` 与生命周期管理：Semaphore 如何限流、会话如何借还、许可泄漏如何防范、应用关闭时如何优雅清理残留会话。

---
