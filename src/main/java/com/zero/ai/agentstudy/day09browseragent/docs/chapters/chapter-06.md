# 第 6 章 · Spring AI Tool 封装

> 本章目标：打通 Browser Agent 的"任督二脉"——让大模型（LLM）能自主调用浏览器能力。你将理解：LLM 为什么"没有手"？`@Tool` / `@ToolParam` 如何把 Java 方法变成 LLM 能理解的工具？description 该怎么写才能让模型正确调用？内容为什么要截断？以及 `day09ChatClient` 的独立配置策略。这一章是 Day03（Tool Calling）在浏览器场景的完整落地。

---

## 一、为什么需要 Tool 封装（Why）

前五章我们造出了一台强大的"浏览器执行引擎"：能开页面、点按钮、填表单、截图、上传下载，还能扛并发。但它现在有一个致命短板——**只能被 Java 代码调用**。

想象这个场景：用户对着聊天框说"帮我打开京东首页看看标题是什么"。这句话到了 LLM 这里，LLM 能理解意图，但它**没有"手"**——它只能输出文本，不能真的去操作浏览器。

**LLM 的本质是"文字接龙机器"**：输入文本，输出文本。它不能执行代码、不能访问网络、不能操作浏览器。那它怎么调用我们的 `BrowserActionService.openPage()` 呢？

答案就是 **Tool Calling（工具调用）机制**：

1. 我们把每个浏览器动作，描述成一个"函数签名 + 自然语言说明"；
2. 框架（Spring AI）把这些描述转成 JSON Schema，连同用户问题一起发给 LLM；
3. LLM 推理后，如果判断"需要打开网页"，它**不会自己动手**，而是返回一个"工具调用请求"（比如 `openWebPage(url="https://jd.com")`）；
4. 框架收到请求，反射调用我们真实的 Java 方法，拿到结果；
5. 框架把结果回填给 LLM，LLM 据此生成最终的自然语言回答。

这就是 **"大脑（LLM）—手（Tool）"的闭环**。LLM 负责"想",Tool 负责"做"。没有 Tool 封装，再强的浏览器引擎对 LLM 也是"看得见摸不着"。

---

## 二、是什么：`@Tool` 与 `@ToolParam`（What）

Spring AI 提供了两个核心注解，让 Java 方法秒变 LLM 工具：

| 注解 | 作用位置 | 作用 |
| --- | --- | --- |
| `@Tool` | 方法上 | 声明"这是一个可被 LLM 调用的工具",`description` 告诉模型"这工具是干什么的、什么时候用" |
| `@ToolParam` | 参数上 | 描述每个参数的含义、格式、约束，帮助模型正确填参 |

看 [`BrowserTools`](../../tool/BrowserTools.java:26) 的类定位注释，把这个衔接点讲得很清楚：

```java
/**
 * BrowserTools —— 把浏览器能力暴露为 Spring AI 的 Tool，供 LLM「Tool Calling」调用。
 * LLM 本身没有「手」，只能输出文本。通过 @Tool 注解，我们把每个浏览器动作
 * 描述成一个「函数签名 + 自然语言说明」，Spring AI 会自动生成 JSON Schema 交给模型。
 * 模型在推理时若判断「需要打开网页 / 点击 / 截图」，就会返回一个 Tool 调用请求，
 * 框架据此反射调用本类方法，再把结果回填给模型——这就是 Browser Agent 的「大脑-手」闭环。
 */
@Slf4j
@Component
public class BrowserTools {
    private final BrowserActionService actionService;

    public BrowserTools(BrowserActionService actionService) {
        this.actionService = actionService;
    }
    // ... 9 个 @Tool 方法
}
```

**关键设计**：`BrowserTools` 是一层极薄的"翻译层"，它不做任何浏览器逻辑，只是把 `BrowserActionService`（第4章的门面）的能力，用 `@Tool` 注解重新包装、暴露给 LLM。真正干活的还是门面。

**分层再回顾**：
```
LLM（大脑）
  ↓ Tool Calling
BrowserTools（工具翻译层，@Tool 面向意图）
  ↓ 委托
BrowserActionService（门面层，原子动作 + 借还池）
  ↓ 借用
BrowserContextPool（并发资源池）
  ↓ 共享
PlaywrightEngine（Browser 进程）
```

每一层职责单一、边界清晰——这正是企业级架构的美感。

---

## 三、怎么用：9 个工具逐一拆解（How）

`BrowserTools` 暴露了 9 个工具。我们逐一分析，重点看 **description 怎么写、参数怎么描述、内容为什么截断**。

### 3.1 openWebPage —— 打开网页

```java
@Tool(description = "打开指定网页并返回页面标题。当用户想访问某个网址、查看某个页面是否可达时使用。")
public String openWebPage(
        @ToolParam(description = "要打开的完整网址，必须以 http://或 https:// 开头") String url) {
    log.info("[Day09][Tool] openWebPage url={}", url);
    return actionService.openPage(url);
}
```

**description 写作三要素**（这是本章最重要的工程心法）：
1. **做什么**："打开指定网页并返回页面标题"——一句话说清功能；
2. **什么时候用**："当用户想访问某个网址、查看某个页面是否可达时使用"——给模型判断依据，这是"面向意图"；
3. **参数约束**：`@ToolParam` 里"必须以 http:// 或 https:// 开头"——防止模型传"jd.com"这种不完整 URL。

**记住：description 就是写给 LLM 看的"说明书"。写得越清楚，模型调用得越准。** 这是 Prompt Engineering 在 Tool 层的体现。

### 3.2 readPageText —— 读正文（含截断）

```java
@Tool(description = "获取指定网页的可见正文文本（已去除HTML标签）。当用户想了解页面内容、总结网页、提取信息时使用。")
public String readPageText(@ToolParam(description = "目标网页完整网址") String url) {
    log.info("[Day09][Tool] readPageText url={}", url);
    String text = actionService.getText(url);
    // 控制返回长度，避免超出模型上下文
    return text.length() > 3000 ? text.substring(0, 3000) + "...(已截断)" : text;
}
```

**为什么要截断到 3000 字？** 这是本工具最关键的工程细节：

- LLM 有**上下文窗口限制**（Token 上限）。一个电商页面的正文可能上万字；
- 如果把全文塞给 LLM，轻则烧钱（Token 计费）、变慢，重则**超出上下文直接报错**；
- 所以截断到 3000 字，并加"...(已截断)"提示——既能让模型了解页面主旨，又不撑爆上下文。

**截断是"喂 LLM"场景的必备防护**。第4章的门面本身返回完整内容，截断放在 Tool 层做——因为"喂给模型"这个约束只在 Tool 层才有意义。

### 3.3 readPageHtml —— 读 HTML（截断 5000）

```java
@Tool(description = "获取指定网页渲染后的完整HTML源码。当用户需要分析页面结构、DOM、或做数据抓取时使用。")
public String readPageHtml(@ToolParam(description = "目标网页完整网址") String url) {
    String html = actionService.getHtml(url);
    return html.length() > 5000 ? html.substring(0, 5000) + "...(已截断)" : html;
}
```

HTML 截断阈值是 5000（比正文的 3000 大）——因为 HTML 含大量标签，信息密度低，需要更多字符才能表达同样的结构信息。**不同内容类型用不同截断阈值,是精细化的体现。**

### 3.4 clickElement —— 点击

```java
@Tool(description = "在网页上点击某个元素。当用户想点击按钮、链接、菜单等时使用。")
public String clickElement(
        @ToolParam(description = "网页网址") String url,
        @ToolParam(description = "要点击元素的选择器，如 '#login-btn' 或 'text=登录'") String selector) {
    return actionService.click(url, selector);
}
```

注意 selector 参数的描述**给了两个例子**：`'#login-btn'`（CSS 选择器）和 `'text=登录'`（Playwright 文本选择器）。**给例子是帮助 LLM 正确填参的有效手段**——模型看到例子，就知道该生成什么格式。

### 3.5 typeText —— 输入文本

```java
@Tool(description = "在网页输入框中填入文本。当用户想在搜索框、表单里输入内容时使用。")
public String typeText(
        @ToolParam(description = "网页网址") String url,
        @ToolParam(description = "输入框选择器，如 '#search' 或 'input[name=q]'") String selector,
        @ToolParam(description = "要输入的文本内容") String text) {
    return actionService.fill(url, selector, text);
}
```

三个参数各司其职：url（在哪个页面）、selector（哪个输入框）、text（输入什么）。参数拆分清晰，模型不易混淆。

### 3.6 loginWebsite —— 登录（6 参数）

```java
@Tool(description = "登录一个网站。当用户提供账号密码并要求登录某网站时使用。")
public String loginWebsite(
        @ToolParam(description = "登录页网址") String url,
        @ToolParam(description = "用户名输入框选择器") String userSelector,
        @ToolParam(description = "用户名") String username,
        @ToolParam(description = "密码输入框选择器") String passSelector,
        @ToolParam(description = "密码") String password,
        @ToolParam(description = "登录按钮选择器") String submitSelector) {
    return actionService.login(url, userSelector, username, passSelector, password, submitSelector);
}
```

这是参数最多的工具（6 个）。即便如此，每个参数都有清晰的 `@ToolParam` 说明。**参数多不可怕，可怕的是描述不清让模型乱填。** 这里把"选择器"和"值"成对排列，逻辑清晰。

### 3.7 captureScreenshot —— 截图

```java
@Tool(description = "对指定网页全屏截图并保存，返回截图文件路径。当用户想保存页面快照、留证时使用。")
public String captureScreenshot(@ToolParam(description = "目标网页网址") String url) {
    return actionService.screenshot(url);
}
```

返回的是**文件路径**（不是图片本身）——因为 LLM 处理不了二进制图片，但能理解"截图已保存到 xxx 路径"这个文本结果。

### 3.8 downloadFile / uploadFile —— 下载与上传

```java
@Tool(description = "从网页下载文件，返回本地保存路径。当用户想下载页面上的文件、报表、附件时使用。")
public String downloadFile(
        @ToolParam(description = "网页网址") String url,
        @ToolParam(description = "触发下载的元素选择器") String triggerSelector) {
    return actionService.download(url, triggerSelector);
}

@Tool(description = "向网页上传本地文件。当用户想在页面的文件上传框里提交本地文件时使用。")
public String uploadFile(
        @ToolParam(description = "网页网址") String url,
        @ToolParam(description = "文件上传框(input[type=file])选择器") String fileSelector,
        @ToolParam(description = "本地文件的绝对路径") String localPath) {
    return actionService.upload(url, fileSelector, localPath);
}
```

上传的 localPath 特意标注"**绝对路径**"——因为相对路径依赖工作目录，容易出错。用约束描述引导模型给出正确格式。

### 3.9 ChatClient 的独立配置

工具定义好了，还需要一个 ChatClient 把工具"挂"上去。看 [`Day09ChatClientConfig`](../../config/Day09ChatClientConfig.java:17)：

```java
@Configuration
public class Day09ChatClientConfig {

    @Bean("day09ChatClient")
    public ChatClient day09ChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是 ZeroHub 平台的浏览器自动化助手。")
                .build();
    }
}
```

**为什么用独立 Bean 名 `day09ChatClient`？** 因为前八天的模块也定义了各自的 ChatClient Bean。如果都叫默认名，Spring 启动时会因为"一个类型多个 Bean"而报错或注入混乱。**用独立 Bean 名 + 注入时 `@Qualifier` 精确指定，是多模块共存的隔离策略**（沿用 Day02 起的约定）。

`defaultSystem(...)` 设置了系统提示词，给这个 ChatClient 一个"人设"——浏览器自动化助手。这会影响模型的回答风格和工具调用倾向。

---

## 四、真实项目：ZeroHub 的 Tool 描述规范

在 ZeroHub，Tool 的 description 质量直接决定 Agent 的可用性。团队沉淀了一套"Tool 描述规范"：

| 规范 | 要求 | 反例（会导致模型误用） |
| --- | --- | --- |
| 功能一句话 | description 开头一句说清"做什么" | "处理网页"（太模糊，模型不知道具体功能） |
| 触发条件 | 明确"什么时候用" | 缺失触发条件（模型不知道何时该调用） |
| 参数给例子 | selector 等复杂参数给格式示例 | "选择器"（模型不知道 CSS 还是 XPath） |
| 格式约束 | URL 标注 http 开头、路径标注绝对路径 | 无约束（模型传 "jd.com" 导致失败） |
| 返回可读 | 返回文本而非二进制 | 直接返回图片字节（模型无法处理） |
| 内容截断 | 喂模型的长文本必须截断 | 全文塞入（超上下文、烧钱、变慢） |

**核心心法**：写 Tool description 时，**把自己想象成 LLM**——只看这段文字，你能判断出"这工具是干什么、什么时候用、参数怎么填"吗？如果不能，就是写得不够好。

---

## 五、避坑清单（≥12 条）

1. **description 别写给人看，要写给 LLM 看**：不要写实现细节，要写"做什么、何时用"。模型看不到你的代码，只看 description。

2. **必须写触发条件**："当用户想...时使用"这句不能省。缺了它，模型不知道何时该调用这个工具。

3. **复杂参数一定给例子**：selector 这类参数,给 `'#id'` / `'text=xxx'` 示例，模型才知道该生成什么格式。

4. **喂 LLM 的长文本必须截断**：不截断轻则烧钱变慢，重则超上下文直接报错。截断阈值按内容类型区分（正文 3000/HTML 5000）。

5. **截断放 Tool 层，别放门面层**："喂模型"这个约束只在 Tool 层有意义。门面应返回完整内容，供非 LLM 场景使用。

6. **返回值必须是模型能处理的文本**：截图返回路径而非图片字节；下载返回路径而非文件内容。二进制模型处理不了。

7. **ChatClient 用独立 Bean 名**：多模块共存时，默认 Bean 名会冲突。用 `day09ChatClient` + `@Qualifier` 精确注入。

8. **BrowserTools 保持"薄"**：它只是翻译层，不写浏览器逻辑。所有实际操作委托给门面，职责单一。

9. **URL 参数标注格式约束**：注明"必须 http:// 开头"，否则模型可能传不完整 URL 导致 openPage 失败。

10. **文件路径标注"绝对路径"**：相对路径依赖工作目录易错，明确要求绝对路径。

11. **别把敏感操作暴露成无约束 Tool**：如登录工具涉及账号密码，生产环境要考虑脱敏日志、权限控制,不能裸奔。

12. **@Tool 方法要加日志**：每个工具入口 `log.info` 记录调用参数,便于排查"模型到底调了什么、传了什么参"。这是 Agent 可观测性的第一道防线。

13. **defaultSystem 人设要匹配场景**：系统提示词影响模型的工具调用倾向。"浏览器自动化助手"的人设让模型更倾向使用浏览器工具。

---

## 六、本章小结

本章我们打通了 LLM 与浏览器能力的"大脑-手"闭环：

- **为什么要 Tool 封装**：LLM 没有"手"，只能输出文本。Tool Calling 让模型能"请求"框架代它执行浏览器动作；
- **@Tool / @ToolParam**：把 Java 方法变成 LLM 能理解的工具，description 是写给模型的"说明书"；
- **description 三要素**：做什么 + 什么时候用 + 参数约束（面向意图）；
- **内容截断**：喂 LLM 的长文本必须截断（正文 3000/HTML 5000），防止超上下文；
- **BrowserTools 是薄翻译层**：委托门面干活，自己只做 @Tool 包装；
- **day09ChatClient 独立配置**：独立 Bean 名 + @Qualifier，实现多模块隔离共存。

**一句话记住本章**：Tool 封装的本质是"给 LLM 写一份它能读懂的能力说明书"——description 写得多好，Agent 就有多聪明。

> **下一章预告**：现在我们有了工具（第6章）和 ChatClient，但还缺一个"编排大脑"把它们串起来响应用户的自然语言。**第 7 章《自然语言 Agent 编排》**将揭晓：`BrowserAgentService` 如何用 `chatClient.prompt().tools()` 把 `BrowserTools` 挂载到对话中，让"打开京东看看有什么活动"这样的大白话，自动变成一连串浏览器操作，并通过 `BrowserController` 暴露成 REST 接口。