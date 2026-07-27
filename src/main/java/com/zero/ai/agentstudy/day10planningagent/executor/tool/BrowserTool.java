package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 浏览器工具：抓取 GitHub Trending 页面 HTML。
 * 抓取失败会抛异常，交由 StepExecutor 重试机制处理。
 */
@Component
public class BrowserTool implements Tool {

    @Value("${zero.planning.trending-url:https://github.com/trending?since=daily}")
    private String trendingUrl;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() { return "browser"; }

    @Override
    public String description() { return "抓取网页 HTML（默认 GitHub Trending 页面）"; }

    @Override
    public String execute(PlanStep step, PlanningContext ctx) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trendingUrl))
                .header("User-Agent", "Mozilla/5.0 (PlanningAgent Day10)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("抓取失败，HTTP " + resp.statusCode());
        }
        return extractRepoArticles(resp.body());
    }

    /** 粗提取 trending 仓库区块并截断，减小喂给 LLM 的体积。 */
    private String extractRepoArticles(String html) {
        int start = html.indexOf("<article");
        int end = html.lastIndexOf("</article>");
        String slice;
        if (start >= 0 && end > start) {
            slice = html.substring(start, Math.min(end + 10, html.length()));
        } else {
            slice = html;
        }
        return slice.length() > 20000 ? slice.substring(0, 20000) : slice;
    }
}