package com.zero.ai.agentstudy.day05rag.mycode.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * EmbeddingClient：文本 → 向量。真实调用本地 LM Studio 的 Embedding 接口。
 *
 * <p>为什么需要这个类？
 * RAG 的一切都建立在「向量」之上：文档要转向量才能存，问题要转向量才能查。
 * 这个类把「调用 Embedding 模型」这件事收拢到一处，上层（索引流、问答流）
 * 只管调用，不关心底层用的是 LM Studio、OpenAI 还是别的服务。
 *
 * <p>接口协议：LM Studio 完全兼容 OpenAI 的 <code>POST /v1/embeddings</code>：
 * <pre>
 * 请求: { "model": "qwen3-embedding-0.6b-dwq", "input": ["文本1", "文本2"] }
 * 响应: { "data": [ { "embedding": [0.1, 0.2, ...] }, ... ] }
 * </pre>
 * 这是「真实 RAG」而非假 RAG 的关键：向量来自真正的语义模型，
 * 因此语义相近的句子向量才会真的接近。
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final WebClient webClient;

    /** Embedding 模型名（LM Studio 中加载的模型 id） */
    private final String model;

    public EmbeddingClient(
            @Value("${rag.embedding.base-url:http://127.0.0.1:1234}") String baseUrl,
            @Value("${rag.embedding.api-key:sk-1234}") String apiKey,
            @Value("${rag.embedding.model:text-embedding-nomic-embed-text-v1.5}") String model) {
        this.model = model;
        // WebClient 默认响应体内存缓冲上限只有 256KB（262144 字节），
        // 批量向量化时返回的 JSON 很容易超过该限制，从而抛出 DataBufferLimitException。
        // 这里将上限调大到 16MB，保证批量 embedding 的响应能被完整缓冲。
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("[Embedding] 初始化完成 baseUrl={} model={}", baseUrl, model);
    }

    /** 启动时打印提示，帮助排查「忘了在 LM Studio 加载 embedding 模型」的常见坑。 */
    @PostConstruct
    public void ready() {
        log.info("[Embedding] 请确保 LM Studio 已加载 Embedding 模型: {}（与聊天模型是两个不同的模型）", model);
    }

    /**
     * 把单条文本向量化。
     *
     * @param text 输入文本
     * @return 向量（float 数组）
     */
    public float[] embed(String text) {
        return embed(List.of(text)).get(0);
    }

    /**
     * 批量把多条文本向量化（一次 HTTP 请求，效率更高）。
     *
     * @param texts 输入文本列表
     * @return 与输入顺序一致的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        // 1. 组装 OpenAI 兼容的请求体
        Map<String, Object> body = Map.of(
                "model", model,
                "input", texts
        );

        // 2. 发起真实 HTTP 调用（阻塞等待，Demo 场景足够）
        JsonNode resp;
        try {
            resp = webClient.post()
                    .uri("/v1/embeddings")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "调用 LM Studio Embedding 接口失败，请检查：1) LM Studio 是否已启动 2) 是否已加载 Embedding 模型 [" + model + "]。原因: " + e.getMessage(), e);
        }

        // 3. 解析响应：data[i].embedding
        if (resp == null || !resp.has("data") || !resp.get("data").isArray()) {
            throw new IllegalStateException("Embedding 响应格式异常: " + resp);
        }
        JsonNode data = resp.get("data");
        List<float[]> result = new java.util.ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode arr = item.get("embedding");
            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                vec[i] = (float) arr.get(i).asDouble();
            }
            result.add(vec);
        }
        log.debug("[Embedding] 向量化 {} 条文本，维度={}", result.size(),
                result.isEmpty() ? 0 : result.get(0).length);
        return result;
    }
}
