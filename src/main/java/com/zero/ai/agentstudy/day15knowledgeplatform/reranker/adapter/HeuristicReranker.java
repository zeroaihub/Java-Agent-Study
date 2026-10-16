package com.zero.ai.agentstudy.day15knowledgeplatform.reranker.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.text.TextTokenizer;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.port.Reranker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 启发式重排序器（零依赖、可离线运行）。
 *
 * <p><b>它的定位</b>：在没有 Cross-Encoder 服务时，用一组可解释的<b>人工特征</b>
 * 做精排。它不是 Cross-Encoder 的替代品（精度差得远），但它有三个价值：</p>
 * <ol>
 *     <li><b>零依赖可跑通</b>——不需要部署任何模型服务；</li>
 *     <li><b>完全可解释</b>——每一条结果都能说清"为什么排这么高"，
 *         这在排查 bad case 时极其重要；</li>
 *     <li><b>它揭示了精排的本质</b>——所谓精排，就是「用更丰富的特征，
 *         对一个更小的候选集做更精细的打分」。</li>
 * </ol>
 *
 * <p><b>它用的特征及权重（这是可调的，不是圣经）</b>：</p>
 * <ul>
 *     <li><b>查询词覆盖率（权重最高，1.0）</b>：查询里的词有多少比例在候选文本中出现。
 *         这是最朴素也最有效的相关性信号。</li>
 *     <li><b>融合分（0.35）</b>：召回阶段 RRF 的得分，作为先验。
 *         它不是 0，因为召回阶段的排序本身包含信息。</li>
 *     <li><b>精确短语命中（0.6）</b>：整个查询串作为连续子串出现。
 *         比"部分词命中"强得多——用户搜"报销流程"，
 *         文本里正好有"报销流程"这四个连续的字，几乎必然相关。</li>
 *     <li><b>标题命中（0.4）</b>：查询词出现在文档标题里。
 *         标题是作者写的摘要，命中标题的信号强度远高于正文。</li>
 *     <li><b>多路命中加成（0.25）</b>：向量与 BM25 都召回的，加分。
 *         这是 Hybrid Search 独有的免费信号。</li>
 *     <li><b>长度惩罚（-0.3 ~ 0）</b>：见下方说明。</li>
 * </ul>
 *
 * <p><b>长度惩罚为什么要做成"两端惩罚"？</b>
 * 太短的切片（&lt; 30 字）往往是页眉页脚、目录碎片、表格标题，
 * 它看起来"词都命中了"，但信息量为零，给用户看了也白看；
 * 太长的切片（&gt; 800 字）则会被塞进 Prompt，挤占宝贵的上下文预算，
 * 而且主题发散、相关性被稀释。
 * 所以我们惩罚两端，偏爱「刚好把一件事说清楚」的中等长度切片。</p>
 *
 * @author ZeroAi
 */
public class HeuristicReranker implements Reranker {

    public static final String NAME = "heuristic";

    // ---- 可调权重 ----
    private static final double W_COVERAGE = 1.0;
    private static final double W_FUSION = 0.35;
    private static final double W_EXACT_PHRASE = 0.6;
    private static final double W_TITLE_HIT = 0.4;
    private static final double W_MULTI_HIT = 0.25;

    // ---- 长度惩罚参数 ----
    private static final int TOO_SHORT = 30;
    private static final int TOO_LONG = 800;

    private final boolean keepRrfScore;

    /**
     * @param keepRrfScore true 表示重排后仍保留 RRF 原始分（便于对比），
     *                     false 表示把 score 替换为重排分
     */
    public HeuristicReranker(boolean keepRrfScore) {
        this.keepRrfScore = keepRrfScore;
    }

    public HeuristicReranker() {
        this(false);
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

        Set<String> queryTerms = new LinkedHashSet<>(TextTokenizer.tokenize(query));
        String normalizedQuery = query.replaceAll("\\s+", "").toLowerCase();

        List<Scored> scored = new ArrayList<>(candidates.size());
        // 融合分归一化所需的最大最小值
        double maxFusion = candidates.stream().mapToDouble(RetrievedChunk::score).max().orElse(1.0);
        double minFusion = candidates.stream().mapToDouble(RetrievedChunk::score).min().orElse(0.0);
        double fusionSpan = Math.max(1e-9, maxFusion - minFusion);

        for (RetrievedChunk rc : candidates) {
            String text = rc.chunk().text();
            Set<String> textTerms = new HashSet<>(TextTokenizer.tokenize(text));

            // 特征 1：查询词覆盖率
            int hit = 0;
            for (String qt : queryTerms) {
                if (textTerms.contains(qt)) {
                    hit++;
                }
            }
            double coverage = queryTerms.isEmpty() ? 0.0 : (double) hit / queryTerms.size();

            // 特征 2：融合分（归一化到 0~1）
            double fusionNorm = (rc.score() - minFusion) / fusionSpan;

            // 特征 3：精确短语命中
            String flat = text.replaceAll("\\s+", "").toLowerCase();
            boolean exactPhrase = !normalizedQuery.isEmpty() && flat.contains(normalizedQuery);

            // 特征 4：标题命中
            boolean titleHit = false;
            String title = rc.chunk().metadata().getString("documentTitle").orElse("");
            if (!title.isEmpty()) {
                Set<String> titleTerms = new HashSet<>(TextTokenizer.tokenize(title));
                titleHit = queryTerms.stream().anyMatch(titleTerms::contains);
            }

            // 特征 5：多路命中
            boolean multiHit = rc.isHitByAll();

            // 特征 6：长度惩罚（两端惩罚）
            int len = text.length();
            double lengthPenalty = 0.0;
            if (len < TOO_SHORT) {
                lengthPenalty = -0.3 * (1.0 - (double) len / TOO_SHORT);
            } else if (len > TOO_LONG) {
                lengthPenalty = -0.2 * Math.min(1.0, (double) (len - TOO_LONG) / TOO_LONG);
            }

            double score = W_COVERAGE * coverage
                    + W_FUSION * fusionNorm
                    + (exactPhrase ? W_EXACT_PHRASE : 0.0)
                    + (titleHit ? W_TITLE_HIT : 0.0)
                    + (multiHit ? W_MULTI_HIT : 0.0)
                    + lengthPenalty;

            double finalScore = keepRrfScore ? rc.score() : score;
            scored.add(new Scored(rc, score, finalScore, coverage, exactPhrase, titleHit));
        }

        scored.sort(Comparator.comparingDouble(Scored::rankScore).reversed());
        List<RetrievedChunk> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, scored.size()); i++) {
            Scored s = scored.get(i);
            out.add(new RetrievedChunk(s.original().chunk(), s.finalScore(),
                    s.original().ranks(), s.original().matchedBy()));
        }
        return List.copyOf(out);
    }

    private record Scored(RetrievedChunk original,
                          double rankScore,
                          double finalScore,
                          double coverage,
                          boolean exactPhrase,
                          boolean titleHit) {
    }
}
