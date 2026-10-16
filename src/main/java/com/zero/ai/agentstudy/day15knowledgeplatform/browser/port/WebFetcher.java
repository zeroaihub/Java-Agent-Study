package com.zero.ai.agentstudy.day15knowledgeplatform.browser.port;

/**
 * 网页抓取端口。
 *
 * <p><b>为什么抽象成端口，而不是直接写代码抓取？</b>
 * 因为网页抓取有两个截然不同的实现路线，适用于不同场景，
 * 而它们的代价差了一个数量级：</p>
 *
 * <table>
 *   <caption>两种抓取路线的取舍</caption>
 *   <tr><th></th><th>HTTP 客户端（JDK HttpClient）</th><th>无头浏览器（Playwright）</th></tr>
 *   <tr><td>能拿到的</td><td>服务器返回的<b>原始 HTML</b></td><td><b>渲染后</b>的 DOM</td></tr>
 *   <tr><td>JS 渲染的页面</td><td>拿不到（只有空壳 div）</td><td>能拿到</td></tr>
 *   <tr><td>速度</td><td>快（几十毫秒）</td><td>慢（1~3 秒，要加载完整页面）</td></tr>
 *   <tr><td>资源占用</td><td>极小</td><td>大（每个页面一个浏览器上下文）</td></tr>
 * </table>
 *
 * <p><b>生产上的正确做法是「先 HTTP，失败再浏览器」的降级链</b>：
 * 90% 的页面（文档站、Wiki、新闻）是服务端渲染的，HTTP 直接就能拿到；
 * 只有确认拿不到内容时（拿到空壳、内容长度过短）才启用浏览器。
 * 一刀切全用 Playwright，成本会高 30 倍以上，而且极易被目标站点限速。</p>
 *
 * @author ZeroAi
 */
public interface WebFetcher {

    /**
     * 抓取页面 HTML。
     *
     * @param url 目标地址
     * @return 抓取结果；失败时 {@code success=false} 并携带原因，不抛异常
     */
    FetchResult fetch(String url);

    /**
     * 抓取结果。
     *
     * <p><b>为什么不抛异常而是返回结果对象？</b>
     * 因为采集面对的是<b>不可控的外部世界</b>：404、超时、反爬、证书错误
     * 都是<b>正常的业务情况</b>，不是异常。
     * 如果每次抓取失败都抛异常，批量采集 1000 个页面时，
     * 一个 404 就会让整批任务中断——这显然不是我们想要的。
     * <b>把"预期内的失败"建模为返回值，把"预期外的错误"留给异常。</b></p>
     *
     * @param url      目标地址
     * @param success  是否成功
     * @param html     页面 HTML（成功时非空）
     * @param statusCode HTTP 状态码
     * @param error    失败原因（成功时为 null）
     * @author ZeroAi
     */
    record FetchResult(String url, boolean success, String html, int statusCode, String error) {

        public static FetchResult ok(String url, String html, int statusCode) {
            return new FetchResult(url, true, html, statusCode, null);
        }

        public static FetchResult fail(String url, int statusCode, String error) {
            return new FetchResult(url, false, null, statusCode, error);
        }
    }
}
