package com.zero.ai.agentstudy.day15knowledgeplatform.retriever;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.text.TextTokenizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 倒排索引（稀疏检索的经典实现）。
 *
 * <p><b>BM25 是什么</b>：Okapi BM25（Best Matching 25）是信息检索领域公认最强健的
 * 词频类排序算法，Elasticsearch / Lucene 在 5.0 之后的默认相似度就是 BM25。
 * 它的公式是：</p>
 *
 * <pre>
 *                    IDF(qi) · f(qi, D) · (k1 + 1)
 *   score(D, Q) = Σ ─────────────────────────────────
 *                   f(qi, D) + k1 · (1 - b + b · |D|/avgdl)
 *
 *   其中 IDF(qi) = ln(1 + (N - df(qi) + 0.5) / (df(qi) + 0.5))
 * </pre>
 *
 * <p><b>三个组件各自的直觉</b>：</p>
 * <ul>
 *     <li><b>IDF（逆文档频率）</b>：出现在越少文档里的词越有区分度。
 *         「的」「公司」IDF 接近 0，「pgvector」IDF 很高。<b>这是关键词检索的灵魂。</b></li>
 *     <li><b>TF 饱和（k1）</b>：词频对得分的贡献是<b>次线性</b>的。
 *         出现 10 次的词不会比出现 5 次的词得分翻倍——防止长文档靠堆砌关键词刷分。
 *         k1 越大越看重词频，一般取 1.2。</li>
 *     <li><b>长度归一（b）</b>：长文档天然包含更多词，需要惩罚。
 *         b=1 完全按长度归一，b=0 不归一，一般取 0.75。
 *         <b>切片（Chunk）场景尤其重要</b>——切片长度天然不均，不归一会让长切片系统性占优。</li>
 * </ul>
 *
 * <p><b>为什么向量检索之后还需要 BM25？</b>
 * 因为向量检索的三个硬伤：
 * <ol>
 *     <li><b>精确 token 弱</b>：产品型号 {@code X-2000}、错误码 {@code KP-4001}、方法名
 *         {@code cosineSimilarity}，向量会把它们泛化掉，BM25 能精确命中；</li>
 *     <li><b>低频专有名词弱</b>：训练语料里没见过的新词，向量表示质量差；</li>
 *     <li><b>可解释性差</b>：向量相似度 0.83 说不清"为什么相关"，
 *         BM25 能明确告诉你"命中了 pgvector 这个词，IDF=4.2"——<b>运维排障时这是救命的</b>。</li>
 * </ol>
 * 反过来 BM25 也不行：同义改写、跨语言、语义泛化它一概不会。
 * <b>所以两者必须 Hybrid，不是二选一。</b></p>
 *
 * @author ZeroAi
 */
public class Bm25Index {

    /** k1：词频饱和参数，越大越看重词频。Lucene 默认 1.2。 */
    public static final double DEFAULT_K1 = 1.2;
    /** b：长度归一参数，0 不归一，1 完全归一。Lucene 默认 0.75。 */
    public static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;

    /**
     * 倒排表：term → (切片索引 → 词频)。
     * 用切片在 {@link #documents} 中的下标作为内部 ID，避免字符串比较开销。
     */
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();

    /** 已索引的切片（含其作用域维度，供过滤）。 */
    private final List<Entry> documents = new ArrayList<>();

    /** 切片原文。保留它，才能在删除时为「重建倒排表」提供原料。 */
    private final List<String> texts = new ArrayList<>();

    /** 每个切片的长度（token 数），用于长度归一。 */
    private final List<Integer> docLengths = new ArrayList<>();

    private long totalLength;

    public Bm25Index() {
        this(DEFAULT_K1, DEFAULT_B);
    }

    public Bm25Index(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    /**
     * 一条已索引的切片（携带作用域维度，用于检索时前置过滤）。
     *
     * @param tenantId        租户
     * @param knowledgeBaseId 知识库
     * @param documentId      文档
     * @param chunkId         切片 ID
     * @author ZeroAi
     */
    public record Entry(TenantId tenantId, KnowledgeBaseId knowledgeBaseId,
                        DocumentId documentId, String chunkId) {
    }

    /**
     * 索引一条切片。
     *
     * @param entry 切片的作用域标识
     * @param text  切片文本
     * @return 内部 ID
     */
    public int add(Entry entry, String text) {
        KnowledgeException.requireNonNull(entry, "entry");
        KnowledgeException.requireNonNull(text, "text");
        List<String> tokens = TextTokenizer.tokenize(text);
        int docId;
        synchronized (this) {
            docId = documents.size();
            documents.add(entry);
            texts.add(text);
            docLengths.add(tokens.size());
            totalLength += tokens.size();
            for (String t : tokens) {
                postings.computeIfAbsent(t, k -> new HashMap<>())
                        .merge(docId, 1, Integer::sum);
            }
        }
        return docId;
    }

    /**
     * 删除某文档的全部切片，并<b>重建倒排表</b>。
     *
     * <p><b>为什么必须重建，而不能“原地删行”？</b>
     * 因为倒排表里的 docId 是数组下标。直接把 {@code documents} 的某几行删掉，
     * 后面所有切片的下标都会前移，而倒排表里存的还是旧下标——
     * 结果就是<b>张冠李戴</b>：检索「报销」命中的切片，点进去是「考勤制度」。
     * 这类 bug 不会抛异常，只会静默返回错误知识，是最危险的一类。</p>
     *
     * <p><b>工程权衡</b>：重建是 O(n) 的。真实的 Lucene 用「墓碑标记 + 后台段合并」
     * 来摊平这个成本——删除时只是打个墓碑，检索时跳过，等后台合并段时再真正回收。
     * 我们这里为了零依赖选择了重建，代价是删除期间需要加锁；
     * 生产环境请直接用 Lucene / Elasticsearch，它们已经把这个问题解决得很好了。</p>
     *
     * @return 删除的切片数
     */
    public int removeByDocument(TenantId tenantId, DocumentId documentId) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(documentId, "documentId");
        synchronized (this) {
            int removed = 0;
            List<Entry> keepEntries = new ArrayList<>();
            List<String> keepTexts = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                Entry e = documents.get(i);
                boolean hit = e.tenantId().equals(tenantId) && e.documentId().equals(documentId);
                if (hit) {
                    removed++;
                } else {
                    keepEntries.add(e);
                    keepTexts.add(texts.get(i));
                }
            }
            if (removed == 0) {
                return 0;
            }
            // 全量重建：清空后按保留下来的文本重新分词入库
            documents.clear();
            texts.clear();
            docLengths.clear();
            postings.clear();
            totalLength = 0;
            for (int i = 0; i < keepTexts.size(); i++) {
                documents.add(keepEntries.get(i));
                texts.add(keepTexts.get(i));
            }
            for (String t : keepTexts) {
                List<String> tokens = TextTokenizer.tokenize(t);
                docLengths.add(tokens.size());
                totalLength += tokens.size();
                int docId = docLengths.size() - 1;
                for (String token : tokens) {
                    postings.computeIfAbsent(token, k -> new HashMap<>())
                            .merge(docId, 1, Integer::sum);
                }
            }
            return removed;
        }
    }

    /**
     * 检索。
     *
     * @param tenantId         租户（强制过滤）
     * @param knowledgeBaseIds 作用域；为空表示不限知识库
     * @param query            查询文本
     * @param topK             返回条数
     * @return 命中结果，按 BM25 得分降序
     */
    public List<Match> search(TenantId tenantId, java.util.Set<KnowledgeBaseId> knowledgeBaseIds,
                              String query, int topK) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(query, "query");
        if (topK <= 0) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "topK 必须为正");
        }
        boolean scopeLimited = knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty();

        List<String> queryTerms = TextTokenizer.tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int n = documents.size();
        double avgdl = n == 0 ? 0.0 : (double) totalLength / n;

        // docId → 累积得分。只对命中至少一个查询词的切片计分（稀疏检索的天性）。
        Map<Integer, Double> scores = new HashMap<>();
        for (String term : queryTerms) {
            Map<Integer, Integer> posting = postings.get(term);
            if (posting == null || posting.isEmpty()) {
                continue; // 该词从未在任何文档出现过，IDF 贡献为 0
            }
            double idf = idf(n, posting.size());
            for (Map.Entry<Integer, Integer> e : posting.entrySet()) {
                int docId = e.getKey();
                Entry meta = documents.get(docId);

                // ---- 作用域前置过滤 ----
                // 注意：过滤必须发生在计分时，而不是计分后再筛。
                // 否则无权文档仍会参与 avgdl/IDF 的统计，污染全局统计量。
                if (!meta.tenantId().equals(tenantId)) {
                    continue;
                }
                if (scopeLimited && !knowledgeBaseIds.contains(meta.knowledgeBaseId())) {
                    continue;
                }

                int tf = e.getValue();
                int dl = docLengths.get(docId);
                double norm = k1 * (1 - b + b * (avgdl == 0 ? 0 : dl / avgdl));
                double score = idf * (tf * (k1 + 1)) / (tf + norm);
                scores.merge(docId, score, Double::sum);
            }
        }

        List<Match> out = new ArrayList<>(scores.size());
        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            out.add(new Match(documents.get(e.getKey()), e.getKey(), e.getValue()));
        }
        out.sort((a, b2) -> Double.compare(b2.score(), a.score()));
        if (out.size() > topK) {
            return List.copyOf(out.subList(0, topK));
        }
        return List.copyOf(out);
    }

    /**
     * IDF：带平滑的对数形式，保证 df 很大时得分为正而非负数。
     *
     * <p>标准 IDF 是 {@code ln(N/df)}，当 df &gt; N/2 时会变成负数——
     * 这意味着「命中一个常见词反而扣分」。Lucene 采用的这个平滑版本
     * {@code ln(1 + (N - df + 0.5)/(df + 0.5))} 恒为正，语义更合理：
     * 命中总比不命中好，只是贡献大小不同。</p>
     */
    private double idf(int totalDocs, int docFreq) {
        return Math.log(1 + (double) (totalDocs - docFreq + 0.5) / (docFreq + 0.5));
    }

    /**
     * 已索引切片总数。
     */
    public int size() {
        return documents.size();
    }

    /**
     * 检索命中项。
     *
     * @param entry   命中切片的作用域标识
     * @param docId   内部 ID
     * @param score   BM25 得分（无上界，只在同一索引内可比较）
     * @author ZeroAi
     */
    public record Match(Entry entry, int docId, double score) {
    }
}
