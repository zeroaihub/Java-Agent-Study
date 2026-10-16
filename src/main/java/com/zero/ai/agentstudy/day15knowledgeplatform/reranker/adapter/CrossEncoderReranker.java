package com.zero.ai.agentstudy.day15knowledgeplatform.reranker.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.port.Reranker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cross-Encoder 重排序器（接真实精排模型服务）。
 *
 * <p>调用一个符合 OpenAI 风格 {@code POST /rerank} 的服务，
 * 一次性把 query 与所有候选文本提交上去，拿回每条的相关性分数。</p>
 *
 * <pre>
 *   POST /rerank
 *   {"model":"bge-reranker-v2-m3",
 *    "query":"报销流程是怎样的",
 *    "documents":["...", "..."]}
 *
 *   → {"results":[{"index":0,"relevance_score":0.93}, ...]}
 * </pre>
 *
 * <p><b>三个必须做对的工程细节</b>：</p>
 *
 * <p><b>1. 超时必须设，且要短。</b>
 * 精排在主链路的关键路径上，它的延迟直接叠加到用户等待时间上。
 * 这里给 2 秒连接超时 + 5 秒请求超时。超时后不能无限等——
 * 用户宁愿拿到未经精排的结果，也不要一个转圈的加载动画。</p>
 *
 * <p><b>2. 必须优雅降级。</b>
 * 精排服务挂了，检索服务必须照样能用。
 * {@link #fallback} 就是为此准备的：主 Reranker 失败时自动退到它。
 * <b>降级不是可选项，而是生产系统的必备能力</b>——
 * 任何一个外部依赖都可能挂，你的系统不能跟着一起挂。</p>
 *
 * <p><b>3. 候选数必须限制。</b>
 * Cross-Encoder 是 O(n) 次模型推理，n 从 20 涨到 200，延迟就涨 10 倍。
 * 工业界公认的甜点区是 <b>top 50 召回 → top 5 精排</b>。
 * 这个数字不是拍的：再多，延迟受不了；再少，容易漏掉正确答案
 * （召回阶段的第 51 名可能才是真正对的那个）。</p>
 *
 * @author ZeroAi
 */
public class CrossEncoderReranker implements Reranker {

    public static final String NAME = "cross-encoder";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String model;
    private final Reranker fallback;

    /**
     * @param endpoint 重排服务地址，如 {@code http://localhost:1234/v1/rerank}
     * @param model    模型名，如 {@code bge-reranker-v2-m3}
     * @param fallback 降级用的重排序器，通常为 {@link HeuristicReranker} 或 {@link PassThroughReranker}
     */
    public CrossEncoderReranker(String endpoint, String model, Reranker fallback) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "rerank endpoint 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "rerank model 不能为空");
        }
        this.endpoint = endpoint;
        this.model = model;
        this.fallback = fallback;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        KnowledgeException.requireNonNull(query, "query");
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        try {
            return callRerankService(query, candidates, topN);
        } catch (Exception ex) {
            // 精排失败绝不能让整个检索失败——降级到备用策略
            if (fallback == null) {
                throw new KnowledgeException(KnowledgeErrorCode.RERANK_FAILED,
                        "调用重排服务失败且未配置降级策略：" + ex.getMessage(), ex);
            }
            return fallback.rerank(query, candidates, topN);
        }
    }

    @SuppressWarnings("unchecked")
    private List<RetrievedChunk> callRerankService(String query, List<RetrievedChunk> candidates, int topN)
            throws Exception {
        List<String> documents = new ArrayList<>(candidates.size());
        for (RetrievedChunk rc : candidates) {
            documents.add(rc.chunk().text());
        }
        String body = objectMapper.writeValueAsString(
                Map.of("model", model, "query", query, "documents", documents));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new KnowledgeException(KnowledgeErrorCode.RERANK_FAILED,
                    "重排服务返回 " + response.statusCode() + "：" + response.body());
        }

        Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> results = (List<Map<String, Object>>) parsed.get("results");
        if (results == null) {
            throw new KnowledgeException(KnowledgeErrorCode.RERANK_FAILED,
                    "重排服务响应缺少 results 字段");
        }

        List<Scored> scored = new ArrayList<>(results.size());
        for (Map<String, Object> r : results) {
            int index = ((Number) r.get("index")).intValue();
            double score = ((Number) r.get("relevance_score")).doubleValue();
            if (index < 0 || index >= candidates.size()) {
                continue; // 防御：忽略越界索引，不让它污染整批结果
            }
            scored.add(new Scored(candidates.get(index), score));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<RetrievedChunk> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, scored.size()); i++) {
            Scored s = scored.get(i);
            out.add(new RetrievedChunk(s.original().chunk(), s.score(),
                    s.original().ranks(), s.original().matchedBy()));
        }
        return List.copyOf(out);
    }

    private record Scored(RetrievedChunk original, double score) {
    }
}
