package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievedChunk;
import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievalSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并行检索器（Chapter 17）。
 *
 * <p>混合检索要同时打向量库和关键词库，串行等于两次网络往返相加。用虚拟线程把多个检索源
 * <b>并发发起</b>，总耗时收敛到最慢的那一路——这是延迟优化最立竿见影的一招。</p>
 *
 * <p>虚拟线程（Java 21）极其轻量，为「每个 I/O 任务一根线程」而生，非常适合这种
 * 「多路 I/O 扇出」场景，无需维护固定大小的线程池。</p>
 *
 * @author ZeroAi
 */
public class ParallelRetriever {

    private final List<RetrievalSource> sources;

    public ParallelRetriever(List<RetrievalSource> sources) {
        this.sources = List.copyOf(AgentException.requireNonNull(sources, "sources"));
        AgentException.requireState(!this.sources.isEmpty(),
                AgentErrorCode.INVALID_ARGUMENT, "至少需要一个检索源");
    }

    /**
     * 并发查询所有检索源，返回「源名 → 命中列表」按源保留的结果。
     *
     * @param ctx   请求上下文
     * @param query 查询文本
     * @param topK  每个源的返回条数
     * @return 各源结果（顺序与 sources 一致）
     */
    public List<List<RetrievedChunk>> retrieveAll(AgentContext ctx, String query, int topK) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<RetrievedChunk>>> futures = new ArrayList<>();
            for (RetrievalSource source : sources) {
                futures.add(executor.submit(() -> source.search(ctx, query, topK)));
            }

            List<List<RetrievedChunk>> results = new ArrayList<>();
            for (Future<List<RetrievedChunk>> future : futures) {
                results.add(join(future));
            }
            return results;
        }
    }

    private List<RetrievedChunk> join(Future<List<RetrievedChunk>> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new AgentException(AgentErrorCode.RETRIEVAL_FAILED,
                    "并行检索某一路失败: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(AgentErrorCode.RETRIEVAL_FAILED, "并行检索被中断", e);
        }
    }
}