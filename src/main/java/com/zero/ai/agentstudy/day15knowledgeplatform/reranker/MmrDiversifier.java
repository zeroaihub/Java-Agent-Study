package com.zero.ai.agentstudy.day15knowledgeplatform.reranker;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.text.TextTokenizer;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MMR 多样性重排（Maximal Marginal Relevance）。
 *
 * <p><b>它解决什么问题？</b>
 * 「Top-5 里 4 条说的是同一件事」。
 * 这在企业知识库里极其常见：同一段政策被写在 5 份文档里（制度原文、部门细则、
 * 培训材料、FAQ、邮件通知），措辞几乎一样。
 * 纯按相关性排序，Top-5 会被这份重复内容占满，
 * 而真正不同的其他角度（比如「差旅标准」与「报销流程」）一条都进不来。</p>
 *
 * <p>结果是：<b>LLM 拿到的上下文里 80% 是冗余信息，而 Token 预算已耗尽。</b>
 * 用户问「报销要注意什么」，得到的是同一个句子的五种说法，
 * 而「发票开具要求」「审批时效」这些真正有用的信息全被挤掉了。</p>
 *
 * <p><b>MMR 的公式</b>：</p>
 *
 * <pre>
 *   MMR = argmax [ λ · Relevance(d) − (1 − λ) · max Sim(d, s) ]
 *           d∈R\S                              s∈S
 * </pre>
 *
 * <p>直觉非常朴素：<b>每选一条，就惩罚掉和已选内容太像的候选。</b>
 * λ 控制「相关」与「多样」的权衡：λ=1 退化成纯相关性排序，λ=0 退化成纯多样性。</p>
 *
 * <p><b>λ 怎么取？</b>
 * 业界常用 0.5~0.7。我们的默认 0.7——因为<b>知识检索场景下相关性优先</b>：
 * 用户来是找答案的，不是为了看百花齐放的观点。
 * 但对「总结这个知识库」「这个主题有哪些说法」这类探索性问题，
 * 应该把 λ 降到 0.5 左右。</p>
 *
 * <p><b>注意</b>：这里的相似度用 Jaccard（token 集合交并比）计算，零依赖。
 * 生产环境可以用向量余弦，更准但更慢；对于"去重复文本"这个目标，
 * Jaccard 已经足够好，而且它是<b>字面重叠度</b>，对识别"复制粘贴的重复内容"反而更灵敏。</p>
 *
 * @author ZeroAi
 */
public final class MmrDiversifier {

    /** 默认 λ：偏向相关性。 */
    public static final double DEFAULT_LAMBDA = 0.7;

    private final double lambda;

    public MmrDiversifier() {
        this(DEFAULT_LAMBDA);
    }

    public MmrDiversifier(double lambda) {
        if (lambda < 0.0 || lambda > 1.0) {
            throw new IllegalArgumentException("lambda 必须在 [0,1] 之间");
        }
        this.lambda = lambda;
    }

    /**
     * 对候选做多样性重排。
     *
     * <p><b>为什么第一个必选相关性最高的？</b>
     * 因为 MMR 的贪心需要一个起点，而"最相关的那条"是唯一合理的起点。
     * 如果随便挑一条作为种子，整个序列都会被带偏——
     * 这是贪心算法的固有特性：第一步的错会一路传递下去。</p>
     *
     * @param candidates 候选（应已按相关性降序）
     * @param topN       返回条数
     * @return 兼顾相关性与多样性的结果
     */
    public List<RetrievedChunk> diversify(List<RetrievedChunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        List<RetrievedChunk> pool = new ArrayList<>(candidates);
        List<RetrievedChunk> selected = new ArrayList<>(Math.min(topN, pool.size()));

        // 预计算 token 集合，避免在 O(n²) 的相似度计算里反复分词
        List<Set<String>> poolTokenSets = new ArrayList<>(pool.size());
        for (RetrievedChunk rc : pool) {
            poolTokenSets.add(new HashSet<>(TextTokenizer.tokenize(rc.chunk().text())));
        }

        // 已选项的 token 集合单独保存。
        // 注意：不能靠「回 pool 里查下标」来取已选项的分词结果——
        // 已选项在选中时就被从 pool 中移除了，回查必然失败，
        // 结果就是多样性惩罚恒为 0，MMR 悄悄退化成纯相关性排序。
        // 这是一类非常隐蔽的 bug：程序照常运行、结果看起来也没错，
        // 只是 MMR 完全没起作用。
        List<Set<String>> selectedTokenSets = new ArrayList<>();

        // 归一化相关性到 [0,1]，让它与 Jaccard 相似度处于同一量纲（否则 λ 失去意义）
        double maxScore = pool.stream().mapToDouble(RetrievedChunk::score).max().orElse(1.0);
        double minScore = pool.stream().mapToDouble(RetrievedChunk::score).min().orElse(0.0);
        double span = Math.max(1e-9, maxScore - minScore);

        // 贪心：每轮选 MMR 分数最高的
        while (selected.size() < topN && !pool.isEmpty()) {
            int bestIdx = -1;
            double bestMmr = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < pool.size(); i++) {
                double relevance = (pool.get(i).score() - minScore) / span;
                double maxSim = 0.0;
                for (Set<String> selectedTokens : selectedTokenSets) {
                    maxSim = Math.max(maxSim, jaccard(poolTokenSets.get(i), selectedTokens));
                }
                double mmr = lambda * relevance - (1 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            selectedTokenSets.add(poolTokenSets.remove(bestIdx));
            selected.add(pool.remove(bestIdx));
        }
        return List.copyOf(selected);
    }

    /**
     * Jaccard 相似度：|A ∩ B| / |A ∪ B|。
     */
    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = a.size() <= b.size() ? b : a;
        for (String s : smaller) {
            if (larger.contains(s)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }
}
