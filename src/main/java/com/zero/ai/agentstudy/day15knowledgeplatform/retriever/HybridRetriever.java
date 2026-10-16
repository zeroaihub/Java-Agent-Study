package com.zero.ai.agentstudy.day15knowledgeplatform.retriever;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.fusion.ReciprocalRankFusion;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器（Hybrid Retriever）—— 向量 + 关键词 + RRF 融合。
 *
 * <p>本类是「组合模式（Composite Pattern）」的典型应用：
 * 它本身实现了 {@link Retriever} 接口，内部又持有多个 {@link Retriever}。
 * 因此对调用方而言，「一路检索」和「多路混合」<b>是同一种东西</b>——
 * 你可以在混合检索之上再套一层混合检索，也可以随时把某一路摘掉。</p>
 *
 * <p><b>一个必须回答的问题：多路检索要并行吗？</b>
 * <b>要。</b>向量检索是 CPU/内存密集（O(n·d) 浮点运算），
 * BM25 是内存密集（倒排表遍历），两者瓶颈不同，串行执行等于让总延迟等于两者之和。
 * 用 Java 21 的虚拟线程（{@code Executors.newVirtualThreadPerTaskExecutor()}）
 * 可以零成本地把它们并行化——虚拟线程极轻，创建百万个都不心疼，
 * 而且不需要像平台线程池那样纠结池子大小。
 * <b>总延迟从 (T_dense + T_sparse) 降到 max(T_dense, T_sparse)。</b></p>
 *
 * <p><b>另一个必须回答的问题：某一路失败了怎么办？</b>
 * <b>降级，而不是整体失败。</b>
 * 如果 BM25 索引损坏导致抛异常，向量检索的结果依然有价值——
 * 用户拿到的检索质量下降了，但服务没挂。
 * 这就是「优雅降级（Graceful Degradation）」：
 * <b>宁可给一个不完美的结果，也不要给一个错误页面。</b>
 * 当然，降级必须被记录和告警，否则它会悄悄变成永久状态。</p>
 *
 * @author ZeroAi
 */
public class HybridRetriever implements Retriever {

    public static final String NAME = "hybrid";

    private final List<Retriever> delegates;
    private final ReciprocalRankFusion fusion;
    private final boolean parallel;

    /** 降级记录：哪些检索器在上次检索中失败了（生产应接入告警）。 */
    private final List<String> lastFailures = new ArrayList<>();

    public HybridRetriever(List<Retriever> delegates) {
        this(delegates, new ReciprocalRankFusion(), true);
    }

    public HybridRetriever(List<Retriever> delegates, ReciprocalRankFusion fusion, boolean parallel) {
        KnowledgeException.requireNonNull(delegates, "delegates");
        if (delegates.isEmpty()) {
            throw new KnowledgeException(
                    com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode.INVALID_ARGUMENT,
                    "至少需要一路检索器");
        }
        this.delegates = List.copyOf(delegates);
        this.fusion = KnowledgeException.requireNonNull(fusion, "fusion");
        this.parallel = parallel;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievedChunk> retrieve(SearchQuery query) {
        KnowledgeException.requireNonNull(query, "query");
        Map<String, List<RetrievedChunk>> perRetriever = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        if (parallel) {
            // 虚拟线程：I/O 与 CPU 混合型任务的理想选择，无需手动调线程池大小
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                List<java.util.concurrent.Future<RetrieverResult>> futures = new ArrayList<>();
                for (Retriever r : delegates) {
                    futures.add(executor.submit(() -> safeRetrieve(r, query)));
                }
                for (var f : futures) {
                    RetrieverResult rr = f.get();
                    if (rr.failure() != null) {
                        failures.add(rr.retrieverName() + ": " + rr.failure());
                    } else {
                        perRetriever.put(rr.retrieverName(), rr.results());
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new KnowledgeException(
                        com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode.RETRIEVAL_FAILED,
                        "检索被中断", ex);
            } catch (Exception ex) {
                throw new KnowledgeException(
                        com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode.RETRIEVAL_FAILED,
                        "并行检索失败：" + ex.getMessage(), ex);
            }
        } else {
            for (Retriever r : delegates) {
                RetrieverResult rr = safeRetrieve(r, query);
                if (rr.failure() != null) {
                    failures.add(rr.retrieverName() + ": " + rr.failure());
                } else {
                    perRetriever.put(rr.retrieverName(), rr.results());
                }
            }
        }

        synchronized (this) {
            lastFailures.clear();
            lastFailures.addAll(failures);
        }

        if (perRetriever.isEmpty()) {
            throw new KnowledgeException(
                    com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode.RETRIEVAL_FAILED,
                    "所有检索器均失败：" + String.join("; ", failures));
        }
        return fusion.fuse(perRetriever);
    }

    private RetrieverResult safeRetrieve(Retriever r, SearchQuery query) {
        try {
            return new RetrieverResult(r.name(), r.retrieve(query), null);
        } catch (Exception ex) {
            return new RetrieverResult(r.name(), List.of(), ex.getMessage());
        }
    }

    /**
     * 上次检索中降级的检索器列表。为空表示全部成功。
     */
    public synchronized List<String> lastFailures() {
        return List.copyOf(lastFailures);
    }

    private record RetrieverResult(String retrieverName,
                                   List<RetrievedChunk> results,
                                   String failure) {
    }
}
