package com.zero.ai.agentstudy.day15knowledgeplatform.reranker;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.port.Reranker;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排应用服务：把「重排 + 去多样」编排成一条可配置的管线。
 *
 * <p><b>为什么把 MMR 放在重排之后，而不是之前？</b>
 * 因为两者的目标不同、输入要求也不同：</p>
 * <ul>
 *     <li><b>重排（Reranker）</b>只看「这条与 query 有多相关」，
 *         它对每条候选独立打分，看不到其它候选；
 *         它需要候选已按相关性粗排过（因为候选是从召回阶段截断来的）。</li>
 *     <li><b>MMR</b> 需要「每条候选之间的相似度」，它是全局的、迭代的；
 *         它要求输入的 score 已经是<b>可信的相关性分数</b>，
 *         否则"用相关性减去冗余度"这个公式就失去意义。</li>
 * </ul>
 * <b>先精排拿到可信的相关性分数，再用 MMR 在此基础上打散冗余</b>——
 * 顺序反了，MMR 就是在给一堆噪声做去重。</p>
 *
 * <p><b>关于候选数（candidateSize）与返回数（topN）的比例</b>：
 * 默认 50 → 5，即 10:1。这个比例的来历是：
 * <ul>
 *     <li>召回 50 条：足以覆盖绝大多数情况下的正确答案（召回率通常在 95% 以上）；</li>
 *     <li>精排后取 5 条：5 条约 1500 字，恰好是塞进 Prompt 又不挤占生成空间的甜点区。</li>
 * </ul>
 * 需要更多上下文时，可以放宽到 10 条，但要警惕
 * <b>"中间迷失（Lost in the Middle）"</b>现象：
 * 研究表明 LLM 对长上下文中<b>首尾</b>位置的信息利用最好，
 * 中间部分容易被忽略。<b>塞得越多，不等于用得越好。</b></p>
 *
 * @author ZeroAi
 */
public class RerankService {

    /** 默认精排候选数。 */
    public static final int DEFAULT_CANDIDATE_SIZE = 50;

    /**
     * MMR 候选池相对返回条数的倍数。
     *
     * <p>池子太小（等于 topN）→ MMR 无内容可替换，形同虚设；
     * 池子太大 → 放进来的都是低相关性结果，为了多样性牺牲了准确性。
     * 2~3 倍是经验上的甜点区。</p>
     */
    public static final int DEFAULT_MMR_POOL_FACTOR = 3;

    private final Reranker reranker;
    private final MmrDiversifier diversifier;
    private final boolean enableDiversification;

    public RerankService(Reranker reranker) {
        this(reranker, new MmrDiversifier(), true);
    }

    /**
     * @param reranker               重排序器
     * @param diversifier            多样性处理器
     * @param enableDiversification  是否启用 MMR；关闭时只做精排（便于 A/B 对比）
     */
    public RerankService(Reranker reranker, MmrDiversifier diversifier, boolean enableDiversification) {
        this.reranker = KnowledgeException.requireNonNull(reranker, "reranker");
        this.diversifier = diversifier;
        this.enableDiversification = enableDiversification;
    }

    /**
     * 执行精排。
     *
     * @param query      查询文本
     * @param candidates 召回候选
     * @param topN       最终返回条数
     * @return 精排后的结果
     */
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        KnowledgeException.requireNonNull(query, "query");
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        // 第一步：精排。候选先截断到 candidateSize，控制精排成本。
        List<RetrievedChunk> toRerank = candidates.size() > DEFAULT_CANDIDATE_SIZE
                ? new ArrayList<>(candidates.subList(0, DEFAULT_CANDIDATE_SIZE))
                : new ArrayList<>(candidates);

        // 第二步：多样性打散。
        if (!enableDiversification || diversifier == null || toRerank.size() <= 1) {
            return reranker.rerank(query, toRerank, topN);
        }

        // ★ 关键：精排阶段必须保留一个「比 topN 更大」的候选池，
        // 再交给 MMR 从中挑出 topN 条。
        //
        // 如果直接 rerank(query, candidates, topN) 把结果截成 topN 条再去做 MMR，
        // MMR 就只能在「已经定死的 topN 条」里重新排序——
        // 那 5 条里若有 3 条是重复内容，MMR 再怎么打散也变不出第 4 条新信息，
        // 结果看起来「MMR 跑过了但毫无变化」。
        //
        // 正确的分工是：精排负责「把最相关的排在前面」（保留较多候选），
        // MMR 负责「从这批相关候选里挑出 topN 条彼此不重复的」。
        int poolSize = Math.min(toRerank.size(), Math.max(topN * 2, DEFAULT_MMR_POOL_FACTOR * topN));
        List<RetrievedChunk> rerankedPool = reranker.rerank(query, toRerank, poolSize);
        return diversifier.diversify(rerankedPool, topN);
    }

    /**
     * 当前使用的重排序器名称（用于日志与诊断）。
     */
    public String rerankerName() {
        return reranker.name();
    }
}
