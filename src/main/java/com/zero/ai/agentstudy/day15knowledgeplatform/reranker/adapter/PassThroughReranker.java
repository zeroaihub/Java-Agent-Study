package com.zero.ai.agentstudy.day15knowledgeplatform.reranker.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.port.Reranker;

import java.util.List;

/**
 * 直通重排序器（Null Object 模式）。
 *
 * <p>它什么也不做：直接截断返回前 topN 条。</p>
 *
 * <p><b>为什么需要一个"什么都不做"的实现？</b>
 * 这是 <b>Null Object 模式</b> 的经典应用，它消灭了调用方里成片的
 * {@code if (reranker != null)} 判断。
 * 更重要的是，它给了我们一个<b>基线（baseline）</b>：
 * 想知道重排序到底带来了多少提升？跑一遍直通、跑一遍真正的重排序，
 * 对比 NDCG@5 —— <b>没有基线的优化都是自嗨</b>。</p>
 *
 * <p>它也是降级时的安全选择：当 Cross-Encoder 服务不可用时，
 * 自动切到直通，服务照样能用，只是质量回到召回阶段的水平。</p>
 *
 * @author ZeroAi
 */
public class PassThroughReranker implements Reranker {

    public static final String NAME = "pass-through";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        if (candidates.size() <= topN) {
            return List.copyOf(candidates);
        }
        return List.copyOf(candidates.subList(0, topN));
    }
}
