# 第 3 章 · 环境搭建与第一个自动化

> 本章目标：把第 2 章学到的概念真正跑起来。你将完成三件事——① 在 `pom.xml` 里引入 Playwright 依赖；② 安装浏览器内核；③ 写并跑通"打开网页 → 读标题 → 截图"的第一个自动化脚本。跑通这一步，你就正式踏进了 Browser Agent 的大门。

---

## 一、为什么这一步值得单独一章（Why）

浏览器自动化和普通 Java 库有一个重大区别：**它不是"加个 jar 包就能用"，而是需要一个真实的浏览器内核**。这个内核是几十上百 MB 的二进制程序，必须单独下载安装。

无数新手卡在这里：Maven 依赖加好了，代码也写对了，一运行却报 `Executable doesn't exist at .../chrome-linux/chrome`。原因就是**只加了 Java 绑定，没装浏览器内核**。

所以本章要把"依赖 + 内核 + 配置 + 第一个脚本"这条链路一次性走通，让你彻底告别"环境跑不起来"的挫败感。**能跑通第一个脚本，比读一百页文档都更能建立信心。**

---

## 二、是什么：Playwright 的两个组成部分（What）

要让 Playwright 在 Java 里工作，需要两样东西，缺一不可：

| 组成 | 是什么 | 怎么获取 | 大小 |
| --- | --- | --- | --- |
| **Java 绑定（jar）** | `com.microsoft.playwright:playwright` 这个 Maven 依赖，提供 `Browser`/`Page` 等 API | Maven 自动下载 | 几 MB |
| **浏览器内核（二进制）** | 真实的 Chromium/Firefox/WebKit 可执行程序 | 运行安装命令下载 | 每个约 100~300MB |

用做菜类比：
- **Java 绑定** = 菜谱（告诉你怎么做；
- **浏览器内核** = 食材（真正下锅的东西）。

只有菜谱没食材，当然做不出菜。这就是为什么单加 Maven 依赖不够、还必须装内核。

---

## 三、怎么用：一步步搭好环境（How）

### 3.1 第一步：加入 Maven 依赖

打开项目根目录的 `pom.xml`，在 `<dependencies>` 里加入 Playwright（Day09 中已经加好，这里解释它）：

```xml
<!-- ============ Day09 Browser Agent: Playwright Java ============ -->
<!-- Playwright 官方 Java 绑定，提供 Browser/Context/Page/Locator 等 API，
     首次运行会自动下载 Chromium/Firefox/WebKit 浏览器内核 -->
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.49.0</version>
</dependency>
```

**为什么锁定版本 `1.49.0` 而不用 `LATEST`？**
因为 Playwright 的 Java 绑定版本和它要下载的浏览器内核版本是**强绑定**的。锁死版本能保证团队每个人、每台 CI 机器下载的内核完全一致，避免"我这能跑你那不能跑"的版本漂移问题。企业项目里，**依赖版本必须显式锁定**是铁律。

### 3.2 第二步：安装浏览器内核

依赖加好后，执行安装命令下载内核。有两种常见方式：

**方式 A：用 Maven 插件命令（推荐，跨平台一致）**
```bash
mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install"
```

**方式 B：只装 Chromium（体积最小，够用）**
```bash
mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

执行后你会看到类似输出（下载进度）：
```
Downloading Chromium 131.x ... 130 MB [====================] 100%
Chromium 131.x downloaded to /Users/you/Library/Caches/ms-playwright/chromium-xxxx
```

**内核装到哪了？** 默认缓存目录：
- macOS：`~/Library/Caches/ms-playwright/`
- Linux：`~/.cache/ms-playwright/`
- Windows：`%USERPROFILE%\AppData\Local\ms-playwright\`

> 💡 **企业/容器小贴士**：可以用环境变量 `PLAYWRIGHT_BROWSERS_PATH` 指定内核安装位置，便于在 Docker 镜像里预装内核、加速冷启动。第 8 章会详讲容器化。

### 3.3 第三步：了解配置项

Day09 用 `BrowserProperties` 统一管理所有可调参数，前缀是 `day09.browser`。**关键设计：所有配置项都有默认值**，即使你的 `application.yml` 里一个字都不写，模块也能开箱即用：

```java
@Data
@Component
@ConfigurationProperties(prefix = "day09.browser")
public class BrowserProperties {
    private boolean headless = true;                  // 无头模式（服务器必须 true）
    private String browserType = "chromium";          // 内核类型
    private int poolSize = 4;                          // 会话池大小（并发能力）
    private double defaultTimeoutMs = 30_000;          // 操作超时
    private double navigationTimeoutMs = 45_000;       // 导航超时
    private String screenshotDir = "./target/day09-screenshots"; // 截图目录
    private String downloadDir = "./target/day09-downloads";     // 下载目录
    private String userAgent = "";                     // UA（空则用默认）
    private int viewportWidth = 1280;                  // 视口宽
    private int viewportHeight = 800;                  // 视口高
    private boolean ignoreHttpsErrors = false;         // 忽略证书错误
    private long acquireTimeoutMs = 10_000;            // 借用会话超时
}
```

逐个说明几个高频参数：

- **`headless`（无头模式）**：`true` 表示浏览器在后台运行、看不到界面，服务器和 CI 环境**必须为 true**（服务器根本没有显示器）；本地调试想"亲眼看浏览器怎么操作"时可以设成 `false`。
- **`browserType`**：`chromium`/`firefox`/`webkit` 三选一，对应第 2 章讲的 `resolveBrowserType()`。
- **`poolSize`**：会话池大小，直接决定并发能力，第 5 章会深入讲。默认 4，够本地开发。
- **`defaultTimeoutMs` vs `navigationTimeoutMs`**：前者管 click/fill/waitFor 等普通操作，后者专管页面跳转（导航通常更慢，所以默认给了更长的 45 秒）。
- **`screenshotDir` / `downloadDir`**：默认放在 `./target/` 下，`target` 是 Maven 构建目录，`mvn clean` 时会自动清理，避免测试文件污染工作区。

想覆盖默认值，在 `application.yml` 里这样写即可：
```yaml
day09:
  browser:
    headless: false      # 本地调试时打开界面
    browser-type: chromium
    pool-size: 4
```

### 3.4 第四步：第一个自动化脚本

现在写一个最小可运行的脚本，验证环境是否 OK。这段代码不依赖 Spring，直接 main 方法就能跑（用于快速验证内核安装成功）：

```java
import com.microsoft.playwright.*;

public class FirstAutomation {
    public static void main(String[] args) {
        // 1. 启动 Playwright 驱动进程（try-with-resources 自动关闭）
        try (Playwright playwright = Playwright.create()) {

            // 2. 启动 Chromium 浏览器（headless=false 让你亲眼看到）
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(false));

            // 3. 创建隔离会话 + 标签页
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // 4. 打开网页（navigate 会自动等待页面加载完成）
            page.navigate("https://example.com");

            // 5. 读取页面标题并打印
            System.out.println("页面标题 = " + page.title());

            // 6. 截图保存
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(java.nio.file.Paths.get("first-shot.png"))
                .setFullPage(true));
            System.out.println("截图已保存到 first-shot.png");

            // 7. 关闭浏览器（Playwright 由 try-with-resources 关闭）
            browser.close();
        }
    }
}
```

运行这段代码，如果你看到：
- 控制台打印 `页面标题 = Example Domain`
- 目录下多了一张 `first-shot.png`

**恭喜，你的环境完全 OK 了！** 第 2 章的四层对象模型，你刚刚亲手全部用了一遍：`Playwright.create()`（第一层）→ `launch()`（第二层）→ `newContext()`（第三层）→ `newPage()`（第四层）。

### 3.5 从"裸脚本"到"Spring 托管"的过渡

上面的 main 方法脚本很适合验证环境，但它有个致命问题：**每次运行都新建又关闭一个 Browser**。前两章反复强调，Browser 昂贵、要复用。所以在真正的服务里，我们不会这么写。

Day09 的做法是：把 `Playwright.create()` 和 `launch()` 交给 Spring 容器托管——`PlaywrightEngine` 用 `@PostConstruct` 在应用启动时初始化一次，全应用共享。你在裸脚本里手写的那几步，在真实项目里变成了：

```java
// 裸脚本（每次都开关，仅用于验证）：
Playwright playwright = Playwright.create();
Browser browser = playwright.chromium().launch(...);

// 真实项目（Spring 单例托管，只初始化一次）：
@Component
public class PlaywrightEngine {
    @PostConstruct public void init() { /* create + launch，全应用一次 */ }
    public Browser browser() { return browser; }  // 大家共享同一个
}
```

**同样的四层模型，裸脚本用完即弃，Spring 托管则长期复用。** 下一章开始，我们就完全进入 Spring 托管的世界。

---

## 四、真实项目：ZeroHub 的环境标准化

在 ZeroHub 平台中，"环境搭建"这件事不能靠每个开发者手动敲命令，必须**标准化、可复现**。我们的实践清单：

| 环节 | 本地开发 | CI / 生产（Docker） |
| --- | --- | --- |
| Playwright 依赖 | pom 锁定 `1.49.0` | 同左，版本严格一致 |
| 内核安装 | 首次手动 `install chromium` | Dockerfile 里 `RUN ... install --with-deps chromium` 预装进镜像 |
| headless | 可设 false 调试 | **强制 true**（无显示器） |
| 启动参数 | 可省略 | **必须** `--no-sandbox --disable-dev-shm-usage` |
| 内核路径 | 默认缓存目录 | `PLAYWRIGHT_BROWSERS_PATH` 固定，避免每次冷启动重下 |

**核心理念：环境即代码。** 把依赖版本、内核安装、启动参数全部写进 pom 和 Dockerfile，任何人 `clone` 下来都能一键复现，杜绝"在我机器上能跑"的团队协作噩梦。第 8 章会给出完整的 Dockerfile 模板。

---

## 五、避坑清单（至少 10 条）

1. **只加 Maven 依赖不装内核 = 必然报错**。看到 `Executable doesn't exist` 就是没装内核，运行 `install` 命令即可。

2. **Playwright 版本必须锁定**，不要用 `LATEST`。Java 绑定和内核版本强绑定，漂移会导致内核下载不匹配。

3. **服务器/CI 必须 headless=true**。服务器没有显示器，`headless=false` 会直接启动失败。

4. **容器里内核要预装进镜像**，不要等运行时再下载。运行时下载既慢又可能因网络失败，导致容器启动超时。

5. **首次内核下载很慢且吃网络**，国内环境可考虑设置 `PLAYWRIGHT_DOWNLOAD_HOST` 指向国内镜像源加速。

6. **不要把截图/下载目录设在项目源码目录**。放 `./target/` 下，`mvn clean` 能自动清理，避免临时文件被误提交进 Git。

7. **别忘了 `install` 也要装系统依赖**。Linux 上用 `install --with-deps` 会顺带装 Chromium 需要的系统库（如字体、图形库），否则内核装了也跑不起来。

8. **裸脚本验证完就该丢弃**，不要把"每次 new 一个 Browser"的写法带进真实服务，那是并发灾难的源头。

9. **`navigate` 的超时和普通操作超时是分开的**。页面加载慢时应调 `navigationTimeoutMs`，而不是 `defaultTimeoutMs`，两者混淆会导致超时设置不生效。

10. **配置项全默认值是有意为之**。不要为了"看起来完整"而在 yml 里把所有项都写死，那样反而降低了模块的开箱即用性；只覆盖你真正要改的项。

11. **本地看不到浏览器界面别慌**，先确认 `headless` 是不是 true；想调试就临时设 false。

12. **磁盘空间要留够**。三种内核全装约 500MB+，CI 机器磁盘紧张时用 `install chromium` 只装一个。

---

## 六、本章小结

- Playwright 需要**两样东西**：Java 绑定（Maven 依赖）+ 浏览器内核（单独安装的二进制），缺一不可。
- 依赖版本必须**锁定**（本项目 `1.49.0`），内核用 `install` 命令下载，容器里要预装进镜像。
- `BrowserProperties`（前缀 `day09.browser`）统一管理所有配置，**全部带默认值**，开箱即用。
- 第一个 main 脚本帮你验证环境，但真实项目里 Playwright/Browser 由 `PlaywrightEngine` **单例托管、长期复用**。
- 记住核心理念：**环境即代码**——把版本、内核、参数写进 pom 和 Dockerfile，一键复现。

> 下一章（第 4 章）我们进入核心：`BrowserActionService`——把"打开、点击、输入、读取、截图、等待、下载、上传、登录"这 10 个原子动作，用统一、安全、自动等待的方式封装起来，为上层 Tool 打好基础。

---
