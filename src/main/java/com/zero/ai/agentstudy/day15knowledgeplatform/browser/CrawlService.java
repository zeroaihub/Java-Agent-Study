package com.zero.ai.agentstudy.day15knowledgeplatform.browser;

import com.zero.ai.agentstudy.day15knowledgeplatform.browser.port.WebFetcher;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParsingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.HtmlParser;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网页采集服务：抓取 → 解析 → 产出可供入库的纯文本。
 *
 * <p>本类是「采集」这条支线的编排者。它把第 4 章的解析能力
 * 与第 12 章的抓取能力接在一起，形成一条完整的
 * <b>URL → 正文 → ParsedDocument</b> 的流水线。</p>
 *
 * <pre>
 *   URL → WebFetcher.fetch() → HTML → HtmlParser.parse() → ParsedDocument → （第 3~7 章的入库链路）
 * </pre>
 *
 * <p><b>一个非常重要的设计观察</b>：采集与「上传文件」最终汇入的是<b>同一条</b>
 * 解析、切分、向量化流水线。这是「连接器生态」的价值所在——
 * 无论知识来自 PDF、Confluence 还是网页，下游处理完全一致。
 * Glean 的核心竞争力就在这里：<b>接入端千变万化，处理端只有一条。</b></p>
 *
 * @author ZeroAi
 */
public class CrawlService {

    private final WebFetcher fetcher;
    private final DocumentParser htmlParser;

    public CrawlService(WebFetcher fetcher) {
        this(fetcher, new HtmlParser());
    }

    public CrawlService(WebFetcher fetcher, DocumentParser htmlParser) {
        this.fetcher = KnowledgeException.requireNonNull(fetcher, "webFetcher");
        this.htmlParser = KnowledgeException.requireNonNull(htmlParser, "htmlParser");
    }

    /**
     * 采集单个 URL。
     *
     * <p><b>为什么抓取失败时返回 {@code success=false} 而不是抛异常？</b>
     * 见 {@link WebFetcher.FetchResult} 的说明——
     * 采集 100 个页面时，其中 3 个 404 是<b>正常情况</b>，
     * 不该让另外 97 个陪葬。</p>
     *
     * @param url 目标地址
     * @return 采集结果
     */
    public CrawlResult crawl(String url) {
        KnowledgeException.requireNonNull(url, "url");
        WebFetcher.FetchResult fetched = fetcher.fetch(url);
        if (!fetched.success()) {
            return CrawlResult.failure(url, fetched.statusCode(), fetched.error());
        }
        try {
            Metadata meta = Metadata.of(Map.of(
                    "sourceType", "web",
                    "sourceUrl", url,
                    "crawledAt", java.time.Instant.now().toString()));
            ParsedDocument parsed = htmlParser.parse(
                    fetched.html().getBytes(StandardCharsets.UTF_8), meta);
            return CrawlResult.success(url, fetched.statusCode(), parsed);
        } catch (Exception ex) {
            return CrawlResult.failure(url, fetched.statusCode(),
                    "解析失败：" + ex.getMessage());
        }
    }

    /**
     * 批量采集。
     *
     * <p>串行执行是刻意的，理由见 {@link com.zero.ai.agentstudy.day15knowledgeplatform.browser.adapter.JdkWebFetcher#fetchAll}：
     * <b>采集必须限速</b>，对目标站点保持友好。</p>
     */
    public List<CrawlResult> crawlAll(List<String> urls) {
        KnowledgeException.requireNonNull(urls, "urls");
        List<CrawlResult> out = new ArrayList<>(urls.size());
        for (String url : urls) {
            out.add(crawl(url));
        }
        return List.copyOf(out);
    }

    /**
     * 采集结果。
     *
     * @param url        目标地址
     * @param success    是否成功
     * @param statusCode HTTP 状态码
     * @param parsed     解析结果（成功时非空）
     * @param error      失败原因
     * @author ZeroAi
     */
    public record CrawlResult(String url, boolean success, int statusCode,
                              ParsedDocument parsed, String error) {

        public static CrawlResult success(String url, int statusCode, ParsedDocument parsed) {
            return new CrawlResult(url, true, statusCode, parsed, null);
        }

        public static CrawlResult failure(String url, int statusCode, String error) {
            return new CrawlResult(url, false, statusCode, null, error);
        }

        /**
         * 正文预览（失败时返回错误原因）。
         */
        public String preview(int maxLength) {
            if (!success || parsed == null) {
                return "失败：" + error;
            }
            String t = parsed.text().replaceAll("\\s+", " ").trim();
            return t.length() <= maxLength ? t : t.substring(0, maxLength) + "…";
        }

        @Override
        public String toString() {
            return success
                    ? "CrawlResult{" + url + ", HTTP " + statusCode + ", " + preview(40) + "}"
                    : "CrawlResult{" + url + ", 失败: " + error + "}";
        }
    }
}
