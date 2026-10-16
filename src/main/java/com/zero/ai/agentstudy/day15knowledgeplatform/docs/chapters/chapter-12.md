# 第 12 章：knowledge-browser —— 网页采集与连接器生态

> 配套代码：`browser/`
> 可运行 Demo：`demo/Chapter11Demo.java`（第 12 章部分）

---

## 一、这一章要解决的问题

到目前为止，知识的唯一来源是**用户上传文件**。
但企业里的知识远不止文件：

- 产品帮助中心（网页）；
- 内部 Wiki / Confluence；
- 行业资讯、竞品动态；
- 政策法规网站；
- GitHub README、API 文档站。

如果每一类来源都要人工下载、再上传，
那"知识平台"就退化成了"网盘"。

本章交付的能力：**给一个 URL，自动抓取 → 解析 → 汇入入库链路**。

```
URL → WebFetcher.fetch() → HTML → HtmlParser → ParsedDocument → 第 3~7 章的入库链路
```

---

## 二、核心设计一：WebFetcher 端口 —— 两条路线的取舍

```java
public interface WebFetcher {
    FetchResult fetch(String url);
}
```

网页抓取有两个截然不同的实现路线，代价差一个数量级：

| | HTTP 客户端（JDK HttpClient） | 无头浏览器（Playwright） |
| --- | --- | --- |
| **能拿到的** | 服务器返回的**原始 HTML** | **渲染后**的 DOM |
| **JS 渲染的页面** | 拿不到（只有空壳 div） | 能拿到 |
| **速度** | 快（几十毫秒） | 慢（1~3 秒，要加载完整页面） |
| **资源占用** | 极小 | 大（每个页面一个浏览器上下文） |

> **生产上的正确做法是「先 HTTP，失败再浏览器」的降级链：**
> 90% 的页面（文档站、Wiki、新闻）是服务端渲染的，HTTP 直接就能拿到；
> 只有确认拿不到内容时（拿到空壳、内容长度过短）才启用浏览器。
> 一刀切全用 Playwright，成本会高 30 倍以上，而且极易被目标站点限速。

### 2.1 为什么用端口隔离

因为两条路线的**依赖差异巨大**（后者要装浏览器、吃内存）。
抽象成端口后，零依赖环境（比如本项目）只用 JDK 实现就能跑通，
需要渲染能力时再挂 Playwright 适配器，**业务代码一行不改**。

---

## 三、核心设计二：失败是返回值，不是异常

```java
public record FetchResult(String url, boolean success, String html,
                          int statusCode, String error) {
    public static FetchResult fail(String url, int statusCode, String error) { ... }
}
```

**为什么不抛异常？**

因为采集面对的是**不可控的外部世界**：404、超时、反爬、证书错误
都是**正常的业务情况**，不是异常。

如果每次抓取失败都抛异常，批量采集 1000 个页面时，
**一个 404 就会让整批任务中断**——这显然不是我们想要的。

> **把「预期内的失败」建模为返回值，把「预期外的错误」留给异常。**

这是本模块最重要的设计决策，也是它与普通 HTTP 客户端最大的区别。

---

## 四、核心设计三：JdkWebFetcher 的三个必备工程点

### 4.1 User-Agent 必须设置

JDK HttpClient 的默认 UA 是 `Java-http-client/21`，
大量站点（尤其是文档站与 CDN）会**直接拒绝或返回反爬页面**。

设置一个正常的浏览器 UA 是采集的基本礼仪，也是能拿到内容的前提。

### 4.2 超时必须设置

不设超时的 HTTP 客户端是**生产事故的经典源头**：
一个不响应的站点会挂住你的线程，累积几十个就把整个应用的线程池耗尽。

这里设：连接超时 5 秒、响应超时 15 秒。

### 4.3 必须控制重定向与响应体大小

```java
.followRedirects(HttpClient.Redirect.NEVER)   // 由业务层决定是否跟随
```

- **重定向**：跟随重定向可能掉进"无限跳转"陷阱；
- **响应体大小**：不限制的话，一个 2GB 的"文件下载链接"就能把内存打爆。
  这里限制 5MB，并只接受 HTML/XML/纯文本，拒绝图片、视频、二进制。

### 4.4 一个细节：异常信息可能为 null

```java
} catch (java.net.ConnectException ex) {
    String detail = ex.getMessage() == null ? "目标端口无响应或被拒绝" : ex.getMessage();
    return FetchResult.fail(url, 0, "无法连接：" + detail);
}
```

`ConnectException.getMessage()` 常常是 null，
直接拼接会得到「无法连接：null」这种毫无信息量的报错。

这种小细节在排障时很要命——**你看到 `null`，完全不知道是端口没开、
被防火墙拦了、还是 DNS 的问题。**

---

## 五、核心设计四：CrawlService —— 接入端千变万化，处理端只有一条

```java
public CrawlResult crawl(String url) {
    WebFetcher.FetchResult fetched = fetcher.fetch(url);
    if (!fetched.success()) {
        return CrawlResult.failure(url, fetched.statusCode(), fetched.error());
    }
    Metadata meta = Metadata.of(Map.of("sourceType", "web", "sourceUrl", url, ...));
    ParsedDocument parsed = htmlParser.parse(fetched.html().getBytes(UTF_8), meta);
    return CrawlResult.success(url, fetched.statusCode(), parsed);
}
```

**一个非常重要的设计观察**：

采集与「上传文件」最终汇入的是**同一条**解析、切分、向量化流水线。

这是**「连接器生态」**的价值所在——
无论知识来自 PDF、Confluence、网页还是邮件，下游处理完全一致。

> **Glean 的核心竞争力就在这里：接入端千变万化，处理端只有一条。**
> 这让新增一个数据源的成本，从"重写一套 RAG"降低到"写一个抓取器"。

### 5.1 采集必须限速

```java
public List<CrawlResult> crawlAll(List<String> urls) {
    List<CrawlResult> out = new ArrayList<>(urls.size());
    for (String url : urls) {
        out.add(crawl(url));    // 串行，刻意如此
    }
    return List.copyOf(out);
}
```

**串行是刻意的。** 并发抓取 100 个页面，
对目标站点就是一次小规模的 DDoS——轻则被封 IP，重则构成违法。

生产环境应加：**单域名并发限制、请求间隔、robots.txt 遵守、失败退避**。

---

## 六、运行 Demo

```bash
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter11Demo
```

真实输出（第 12 章部分）：

```
第 12 章：网页采集
  https://example.com                            失败：连接超时
  https://this-domain-does-not-exist-xyz123.com  失败：无法连接：目标端口无响应或被拒绝
  not-a-valid-url                                失败：仅支持 http/https 协议：not-a-valid-url
```

> **说明**：本 Demo 需要网络访问。若处于离线环境，抓取会失败——
> **但失败路径本身正是要演示的内容之一**：
> 三种不同的失败原因都被正确归类为"结果对象"，
> 而不是抛异常中断整批任务。

---

## 七、避坑指南

| 坑 | 后果 | 对策 |
| --- | --- | --- |
| 抓取失败抛异常 | 一个 404 中断整批任务 | 失败建模为返回值 |
| 不设 UA | 被站点拒绝或返回反爬页 | 设置正常浏览器 UA |
| 不设超时 | 线程被挂住，耗尽线程池 | 连接 5s / 响应 15s |
| 自动跟随重定向 | 掉进无限跳转陷阱 | `followRedirects(NEVER)`，业务层决定 |
| 不限制响应体 | 一个 2GB 文件打爆内存 | 限制大小 + 白名单 Content-Type |
| 异常信息直接拼接 | 得到「无法连接：null」 | null 时给出可读的兜底文案 |
| 并发无限抓取 | 对目标站点构成 DDoS，被封 IP | 串行 + 单域名并发限制 + 请求间隔 |
| 一刀切用无头浏览器 | 成本高 30 倍，易被限速 | 先 HTTP，拿不到内容再降级到浏览器 |

---

## 八、面试题

**Q1：网页采集为什么不能"失败就抛异常"？**

> 因为采集面对的是不可控的外部世界：
> 404、超时、反爬、证书错误都是**正常的业务情况**，不是异常。
>
> 如果每次失败都抛异常，批量采集 1000 个页面时，
> 一个 404 就会让整批任务中断——
> 那 997 个本来能成功的页面也跟着失败了。
>
> 正确做法是**把「预期内的失败」建模为返回值**（携带失败原因与状态码），
> 把「预期外的错误」留给异常。这样调用方可以按失败原因分类处理：
> 404 就跳过，超时就重试，403 就换策略。

**Q2：什么时候该用无头浏览器，什么时候用 HTTP 客户端？**

> **默认用 HTTP 客户端，只在拿不到内容时降级到无头浏览器。**
>
> - HTTP 客户端：几十毫秒，资源占用极小，能拿到服务端返回的原始 HTML；
> - 无头浏览器：1~3 秒，要加载完整页面并执行 JS，资源占用大。
>
> 90% 的页面（文档站、Wiki、新闻）是服务端渲染的，HTTP 足够。
> 只有 SPA 应用（Vue/React 渲染的内容）才需要浏览器。
>
> 判断"是否需要降级"的经验规则：
> HTTP 拿到的文本长度过短，或包含大量 `<script>` 而无正文。

**Q3：采集器为什么值得抽象成端口？**

> 因为不同数据源的接入方式差异巨大，但它们**汇入的是同一条处理流水线**。
>
> 抽象成端口后：
> 1. 新增数据源只需实现 `WebFetcher`（或对应的抓取器），
>    下游的解析、切分、向量化、检索**完全复用**；
> 2. 零依赖环境可以用 JDK 实现跑通，
>    需要渲染能力时再挂 Playwright 适配器；
> 3. 这就是「连接器生态」——Glean 的核心竞争力正是
>    "接入端千变万化，处理端只有一条"。
>
> 它把"新增一个数据源"的成本，
> 从"重写一套 RAG"降低到"写一个抓取器"。

---

## 九、本章小结

- **采集失败是返回值，不是异常**——一个 404 不该中断整批任务。
- **UA、超时、重定向、响应体大小** 是 HTTP 采集的四个必备防护。
- **两条抓取路线**（HTTP / 无头浏览器）通过端口隔离，按需降级。
- **采集与上传汇入同一条入库链路**，这就是连接器生态的价值。
- **采集必须限速**，对目标站点保持友好。

下一章，我们把前面 12 章的模块**串成两个方法**，对外提供统一 API。

---

> **第 12 章结束。** 输入「继续」，进入 **第 13 章：knowledge-api —— 统一 API 与门面模式**。
