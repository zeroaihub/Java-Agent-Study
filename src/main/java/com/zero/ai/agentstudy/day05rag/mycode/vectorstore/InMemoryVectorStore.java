package com.zero.ai.agentstudy.day05rag.mycode.vectorstore;

import com.zero.ai.agentstudy.day05rag.mycode.entity.EmbeddedChunk;
import com.zero.ai.agentstudy.day05rag.mycode.util.SimilarityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * InMemoryVectorStore：最简单的内存向量库。
 *
 * <p>为什么先用内存版？
 * RAG 的核心逻辑（存向量 + 按相似度检索）与「用什么数据库」无关。
 * 内存版零依赖，能让你先把 RAG 的闭环完整跑通、看清本质。
 * 等理解了，再把这一层换成 pgvector / Milvus，其他层完全不用改
 * —— 这正是「每层只做一件事、可替换」的企业分层价值。
 *
 * <p>它做两件事：
 * <ol>
 *   <li>save：把「原文 + 向量」存进来。</li>
 *   <li>search：给定一个查询向量，返回相似度最高的 Top-K 条。</li>
 * </ol>
 *
 * <p>局限（第五章展开）：内存版是「暴力全量扫描」，数据量大时慢、且重启丢失。
 * 生产环境用向量数据库，靠 HNSW/IVF 等索引做近似最近邻，才能支撑海量数据。
 */
@Slf4j
@Component
public class InMemoryVectorStore {

    /**
     * 存储所有已向量化的块。用并发安全的 List，避免写入与检索并发出问题。
     */
    private final List<EmbeddedChunk> store = new CopyOnWriteArrayList<>();

    // 过滤掉相似度太低的。注意：中文「短问题」与「长文档块」的余弦相似度普遍偏低
    // （常见落在 0.3~0.5），0.55 这个阈值过高，会把本应命中的块直接过滤掉，
    // 表现为「明明有相关内容却召回不到 / 相关性很低」。这里放宽到 0.2。
    private static final double MIN_SCORE = 0.2;

    /**
     * 保存一批向量化后的块。
     */
    public void saveAll(List<EmbeddedChunk> chunks) {
        store.addAll(chunks);
        log.info("[VectorStore] 新增 {} 条，当前共 {} 条", chunks.size(), store.size());
    }

    /**
     * 相似检索：返回与查询向量最相近的 Top-K 条。
     *
     * <p>算法（内存暴力检索）：
     * <ol>
     *   <li>遍历库里每一条，算它与 queryVector 的余弦相似度。</li>
     *   <li>按相似度从高到低排序。</li>
     *   <li>取前 topK 条返回。</li>
     * </ol>
     *
     * @param queryVector 查询向量（通常是「问题」的向量）
     * @param topK        取前几条
     * @return 命中结果（含相似度分数），已按分数降序
     */
    public List<Hit> search(float[] queryVector, int topK) {
        List<Hit> hits = new ArrayList<>(store.size());
        for (EmbeddedChunk ec : store) {
            double score = SimilarityUtil.cosine(queryVector, ec.getEmbedding());
            if (score < MIN_SCORE) continue; // 过滤掉相似度太低的
            hits.add(new Hit(ec, score));
        }
        // 按相似度降序，取前 topK
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(topK, hits.size()));
    }

    /**
     * 当前库里的总条数
     */
    public int size() {
        return store.size();
    }

    /**
     * 清空（便于反复测试）
     */
    public void clear() {
        store.clear();
        log.info("[VectorStore] 已清空");
    }

    /**
     * 一条检索命中结果：命中的块 + 它与查询的相似度分数。
     */
    public record Hit(EmbeddedChunk chunk, double score) {
    }
}