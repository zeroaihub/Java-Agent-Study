package com.zero.ai.agentstudy.day15knowledgeplatform.embedding;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.port.EmbeddingModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量化应用服务。
 *
 * <p>职责只有一件：<b>把 Chunk 变成 EmbeddedChunk</b>。
 * 但生产中这一步有三个必须解决的工程问题，本类逐一实现：</p>
 *
 * <ol>
 *     <li><b>批处理</b>：一次文档往往切出几百片，逐条调用等于几百次网络往返。
 *         这里按 {@code batchSize} 分批，把 RTT 数量从 O(n) 降到 O(n/batch)。</li>
 *     <li><b>去重缓存</b>：文档里大量切片文本高度重复（页眉、页脚、免责声明），
 *         对同一文本反复调用模型是在烧钱。这里用内容哈希做缓存，
 *         命中直接复用向量。<b>这是 Embedding 阶段最容易捡的一笔钱。</b></li>
 *     <li><b>模型锁版本</b>：所有向量必须来自同一个模型，
 *         由 {@link EmbeddingVector#requireComparable} 在比较时兜底校验。</li>
 * </ol>
 *
 * <p><b>关于重试</b>：这里刻意<b>没有</b>写重试逻辑。
 * 重试属于<b>编排层</b>（第 11 章的 SyncJob / 第 12 章的任务队列）的职责，
 * 而不是领域服务的职责。领域服务失败就明确失败，由编排层决定是重试、
 * 还是进死信队列。把重试塞进领域服务，会导致「一次上层重试 × 内部三次重试」的
 * 指数放大，这是分布式系统里经典的雪崩制造机。</p>
 *
 * @author ZeroAi
 */
public class EmbeddingService {

    /** 默认批大小。OpenAI 官方建议 ≤ 2048 条/批，这里取保守值。 */
    public static final int DEFAULT_BATCH_SIZE = 64;

    private final EmbeddingModel model;
    private final int batchSize;

    /** 文本 → 向量的缓存。仅对同一模型实例有效，模型换了缓存必须失效。 */
    private final Map<String, EmbeddingVector> cache = new ConcurrentHashMap<>();

    /** 统计：累计请求条数、缓存命中数、批量调用次数（生产应接入 Micrometer）。 */
    private long totalEmbedded;
    private long cacheHits;
    private long batchInvocations;

    public EmbeddingService(EmbeddingModel model) {
        this(model, DEFAULT_BATCH_SIZE);
    }

    public EmbeddingService(EmbeddingModel model, int batchSize) {
        KnowledgeException.requireNonNull(model, "embeddingModel");
        if (batchSize < 1) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "batchSize 不能小于 1");
        }
        this.model = model;
        this.batchSize = batchSize;
    }

    /**
     * 当前使用的模型标识，供存储层写入与校验。
     */
    public String modelId() {
        return model.modelId();
    }

    /**
     * 对单个切片向量化。
     */
    public EmbeddedChunk embed(Chunk chunk) {
        KnowledgeException.requireNonNull(chunk, "chunk");
        return new EmbeddedChunk(chunk, embedText(chunk.text()));
    }

    /**
     * 批量向量化切片列表。
     *
     * <p>实现上先按缓存分成「命中」与「未命中」两堆，
     * 未命中的分批调用模型，最后<b>按原始顺序</b>装配结果。</p>
     *
     * <p>最后一步的顺序还原极易写错：缓存命中是乱序的、批量调用是分块的，
     * 如果直接把结果按处理顺序 append，切片和向量就会错位——
     * 表现为「检索到的片段和它标注的章节路径对不上」，
     * 这种 bug 不会报错，只会让溯源功能静默失真。</p>
     */
    public List<EmbeddedChunk> embedAll(List<Chunk> chunks) {
        KnowledgeException.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) {
            return List.of();
        }

        int n = chunks.size();
        EmbeddingVector[] results = new EmbeddingVector[n];
        List<Integer> missIndexes = new ArrayList<>();

        // 1) 查缓存
        for (int i = 0; i < n; i++) {
            String key = cacheKey(chunks.get(i).text());

            EmbeddingVector cached = cache.get(key);
            if (cached != null) {
                results[i] = cached;
                cacheHits++;
            } else {
                missIndexes.add(i);
            }
        }
        totalEmbedded += n;

        // 2) 未命中的分批调用
        for (int from = 0; from < missIndexes.size(); from += batchSize) {
            int to = Math.min(from + batchSize, missIndexes.size());
            List<Integer> batchIndexes = missIndexes.subList(from, to);

            // 2a) 批内二次查缓存 + 按文本去重
            //
            // 这一步很容易被漏掉，而它的收益恰恰最大：
            // 第 1 步的查缓存在「任何一次模型调用之前」一次性完成，
            // 因此同一批内、甚至相邻批之间的重复文本都查不到缓存。
            // 而现实中重复内容（页眉、页脚、免责声明、模板段落）
            // 几乎总是集中出现在同一次调用里。
            // 只做第 1 步的话，缓存命中率会长期为 0——
            // 看起来"功能已实现"，实际上一分钱也没省下。
            Map<String, List<Integer>> pending = new LinkedHashMap<>();
            for (int idx : batchIndexes) {
                String text = chunks.get(idx).text();
                EmbeddingVector cached = cache.get(cacheKey(text));
                if (cached != null) {
                    results[idx] = cached;
                    cacheHits++;
                } else {
                    pending.computeIfAbsent(text, k -> new ArrayList<>()).add(idx);
                }
            }
            if (pending.isEmpty()) {
                continue; // 全部命中缓存，无需调用模型
            }

            // 2b) 只把「去重后」的文本提交给模型：一次调用，多处复用
            List<String> distinctTexts = new ArrayList<>(pending.keySet());
            List<EmbeddingVector> vectors = model.embedAll(distinctTexts);
            if (vectors.size() != distinctTexts.size()) {
                throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                        "Embedding 返回条数不匹配：期望 " + distinctTexts.size()
                                + "，实际 " + vectors.size());
            }
            batchInvocations++;

            for (int k = 0; k < distinctTexts.size(); k++) {
                EmbeddingVector v = vectors.get(k);
                String text = distinctTexts.get(k);
                cache.put(cacheKey(text), v);
                for (int originIdx : pending.get(text)) {
                    results[originIdx] = v;
                }
            }
        }

        // 3) 按原序装配
        List<EmbeddedChunk> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new EmbeddedChunk(chunks.get(i), results[i]));
        }
        return out;
    }

    /**
     * 直接对文本向量化（供查询使用）。
     */
    public EmbeddingVector embedText(String text) {
        KnowledgeException.requireNonNull(text, "text");
        totalEmbedded++;
        String key = cacheKey(text);
        EmbeddingVector cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }
        batchInvocations++;
        EmbeddingVector v = model.embed(text);
        cache.put(key, v);
        return v;
    }

    /**
     * 缓存键：模型标识 + 文本。
     *
     * <p><b>为什么必须把 modelId 拼进去？</b>
     * 因为同一段文本在不同模型下的向量完全不同。如果缓存键只有文本，
     * 一旦换了模型，新请求会命中旧模型的向量——于是库里混进一批「看起来正常、
     * 实则来自另一个向量空间」的脏数据，检索质量缓慢劣化且无人察觉。
     * 把 modelId 拼进 key，换模型即等价于自动换缓存，零成本杜绝该事故。</p>
     */
    private String cacheKey(String text) {
        return model.modelId() + "|" + text;
    }

    // ---------- 运行统计 ----------

    public long totalEmbedded() {
        return totalEmbedded;
    }

    public long cacheHits() {
        return cacheHits;
    }

    public long batchInvocations() {
        return batchInvocations;
    }

    /**
     * 缓存命中率。生产中这个指标低于 10% 就应该检查是否有大量重复内容，
     * 高于 60% 则说明文档高度重复，可考虑先做去重再入库。
     */
    public double cacheHitRate() {
        return totalEmbedded == 0 ? 0.0 : (double) cacheHits / totalEmbedded;
    }

    /**
     * 清空缓存（更换模型或内存压力过大时调用）。
     */
    public void clearCache() {
        cache.clear();
    }
}
