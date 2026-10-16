package com.zero.ai.agentstudy.day15knowledgeplatform.browser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.browser.port.WebFetcher;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 JDK 11+ {@link HttpClient} 的网页抓取实现（零第三方依赖）。
 *
 * <p><b>三个必须做对的工程点</b>：</p>
 *
 * <p><b>1. User-Agent 必须设置。</b>
 * JDK HttpClient 的默认 UA 是 {@code Java-http-client/21}，
 * 大量站点（尤其是文档站与 CDN）会直接拒绝或返回反爬页面。
 * 设置一个正常的浏览器 UA 是采集的基本礼仪，也是能拿到内容的前提。</p>
 *
 * <p><b>2. 超时必须设置。</b>
 * 不设超时的 HTTP 客户端是生产事故的经典源头：
 * 一个不响应的站点会挂住你的线程，累积几十个就把整个应用的线程池耗尽。
 * 这里设连接超时 5 秒、响应超时 15 秒。</p>
 *
 * <p><b>3. 必须控制重定向与响应体大小。</b>
 * 跟随重定向可能掉进"无限跳转"陷阱；
 * 不限制响应体大小，一个 2GB 的"文件下载链接"就能把内存打爆。
 * 这里 {@code followRedirects(NEVER)} 由业务层决定是否跟，
 * 并用 {@code BodyHandlers.ofString()} 配合显式大小检查。</p>
 *
 * @author ZeroAi
 */
public class JdkWebFetcher implements WebFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** 单个页面最大字节数（5MB）。超过则拒绝，防止内存被异常响应打爆。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 允许的内容类型。只接受 HTML/XML/纯文本，拒绝图片、视频、二进制。 */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "text/html", "text/plain", "application/xhtml", "application/xml", "text/xml");

    private final HttpClient httpClient;
    private final String userAgent;

    public JdkWebFetcher() {
        this("Mozilla/5.0 (compatible; ZeroHubKnowledgeBot/1.0; +https://zerohub.example/bot)");
    }

    public JdkWebFetcher(String userAgent) {
        KnowledgeException.requireNonNull(userAgent, "userAgent");
        this.userAgent = userAgent;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public FetchResult fetch(String url) {
        KnowledgeException.requireNonNull(url, "url");
        if (!url.regionMatches(true, 0, "http", 0, 4)) {
            return FetchResult.fail(url, 0, "仅支持 http/https 协议：" + url);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            // 重定向：交给调用方决定（这里返回 3xx，业务层可自行跟随并记录最终 URL）
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location")
                        .orElse("(未提供)");
                return FetchResult.fail(url, status, "重定向至 " + location);
            }
            if (status != 200) {
                return FetchResult.fail(url, status, "HTTP " + status);
            }

            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("text/html").toLowerCase(Locale.ROOT);
            if (ALLOWED_PREFIXES.stream().noneMatch(contentType::startsWith)) {
                return FetchResult.fail(url, status, "不支持的内容类型：" + contentType);
            }

            String html = response.body();
            if (html == null || html.isBlank()) {
                return FetchResult.fail(url, status, "响应体为空");
            }
            if (html.length() > MAX_BYTES) {
                return FetchResult.fail(url, status,
                        "响应体过大：" + html.length() + " 字符，超过限制 " + MAX_BYTES);
            }
            return FetchResult.ok(url, html, status);

        } catch (java.net.http.HttpConnectTimeoutException ex) {
            return FetchResult.fail(url, 0, "连接超时");
        } catch (java.net.http.HttpTimeoutException ex) {
            return FetchResult.fail(url, 0, "读取超时");
        } catch (java.net.ConnectException ex) {
            // ConnectException 的 getMessage() 常常是 null，
            // 直接拼接会得到「无法连接：null」这种毫无信息量的报错。
            String detail = ex.getMessage() == null ? "目标端口无响应或被拒绝" : ex.getMessage();
            return FetchResult.fail(url, 0, "无法连接：" + detail);
        } catch (java.net.UnknownHostException ex) {
            return FetchResult.fail(url, 0, "域名无法解析：" + ex.getMessage());
        } catch (Exception ex) {
            String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return FetchResult.fail(url, 0, ex.getClass().getSimpleName() + "：" + detail);
        }
    }

    /**
     * 批量抓取（串行，遵守 robots 与限速是调用方的责任）。
     *
     * <p>这里刻意做成串行：<b>采集必须限速</b>。
     * 并发抓取 100 个页面，对目标站点就是一次小规模的 DDoS，
     * 轻则被封 IP，重则构成违法。生产环境应加：
     * 单域名并发限制、请求间隔、robots.txt 遵守、失败退避。</p>
     */
    public List<FetchResult> fetchAll(List<String> urls) {
        KnowledgeException.requireNonNull(urls, "urls");
        return urls.stream().map(this::fetch).toList();
    }
}
