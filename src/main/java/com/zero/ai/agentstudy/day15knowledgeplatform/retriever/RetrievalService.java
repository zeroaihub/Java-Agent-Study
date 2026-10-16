package com.zero.ai.agentstudy.day15knowledgeplatform.retriever;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;

import java.util.List;
import java.util.Map;

/**
 * 检索应用服务（对外的检索门面）。
 *
 * <p>职责：把一次检索请求编排成「执行 → 诊断」两步，并守护两条硬规则。</p>
 *
 * <p><b>硬规则一：租户必须来自当前上下文，不能由调用方随意指定。</b>
 * 调用方只能说「我要搜什么」，不能说「我要搜谁的数据」。
 * 租户从 {@code TenantContext} 读取——它由入口 Filter 根据认证信息注入。
 * 如果允许调用方传 tenantId，那么任何一个越权请求都能看到别家的数据，
 * 多租户系统会在第一个攻击者面前土崩瓦解。</p>
 *
 * <p><b>硬规则二：检索结果必须可溯源。</b>
 * 每一条结果都带 {@link RetrievedChunk#ranks()}，
 * 记录它在各路检索器里的排名。没有这个信息，
 * 当用户反馈「这条该搜到却没搜到」时，你根本无从下手——
 * 你不知道它是没被召回，还是被召回了但融合时被压低了。
 * <b>可观测性不是事后加的，是在设计数据结构时就埋进去的。</b></p>
 *
 * @author ZeroAi
 */
public class RetrievalService {

    private final Retriever retriever;

    public RetrievalService(Retriever retriever) {
        this.retriever = KnowledgeException.requireNonNull(retriever, "retriever");
    }

    /**
     * 执行检索。
     *
     * @param query 检索请求
     * @return 检索诊断报告，含结果列表与诊断信息
     */
    public SearchReport search(SearchQuery query) {
        KnowledgeException.requireNonNull(query, "query");
        long start = System.nanoTime();

        List<RetrievedChunk> results;
        try {
            results = retriever.retrieve(query);
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeErrorCode.RETRIEVAL_FAILED,
                    "检索执行失败：" + ex.getMessage(), ex);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        List<String> failures = retriever instanceof HybridRetriever hr
                ? hr.lastFailures()
                : List.of();

        return new SearchReport(query, results, elapsedMs, failures);
    }

    /**
     * 检索诊断报告。
     *
     * <p>为什么返回「报告」而不是「结果列表」？
     * 因为检索是一个<b>需要持续调优</b>的环节。
     * 只有把耗时、降级情况、多路命中情况一起返回，
     * 才能在问题发生时快速定位：是慢了、是某一路挂了、还是融合规则不合理。</p>
     *
     * @param query        原始请求
     * @param results      检索结果（已按融合得分降序）
     * @param elapsedMs    总耗时
     * @param degradations 降级信息；为空表示全部检索器正常
     * @author ZeroAi
     */
    public record SearchReport(SearchQuery query,
                               List<RetrievedChunk> results,
                               long elapsedMs,
                               List<String> degradations) {

        /**
         * 是否发生了降级（有检索器失败但仍返回了部分结果）。
         */
        public boolean isDegraded() {
            return degradations != null && !degradations.isEmpty();
        }

        /**
         * 被两路以上检索器同时命中的结果数。
         *
         * <p>这个指标是衡量 <b>Hybrid 是否真的起了作用</b> 的关键：
         * 如果它长期为 0，说明两路检索器的结果毫无交集，
         * 要么是检索策略配置有问题，要么是数据本身有问题。</p>
         */
        public long multiHitCount() {
            return results.stream().filter(RetrievedChunk::isHitByAll).count();
        }

        @Override
        public String toString() {
            return "SearchReport{hits=" + results.size()
                    + ", multiHit=" + multiHitCount()
                    + ", elapsed=" + elapsedMs + "ms"
                    + ", degraded=" + isDegraded() + "}";
        }
    }

    /**
     * 便捷方法：只取结果列表。
     */
    public List<RetrievedChunk> searchForList(SearchQuery query) {
        return search(query).results();
    }

    /**
     * 各路检索器名称（用于诊断输出）。
     */
    public Map<String, Integer> describeRetrievers() {
        if (retriever instanceof HybridRetriever hr) {
            return Map.of(hr.name(), 1);
        }
        return Map.of(retriever.name(), 1);
    }
}
