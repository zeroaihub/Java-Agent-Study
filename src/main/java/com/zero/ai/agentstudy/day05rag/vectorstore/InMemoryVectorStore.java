package com.zero.ai.agentstudy.day05rag.vectorstore;

import com.zero.ai.agentstudy.day05rag.entity.Chunk;
import com.zero.ai.agentstudy.day05rag.entity.SearchResult;
import com.zero.ai.agentstudy.day05rag.util.VectorMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryVectorStore —— 内存版向量库（暴力检索实现）。
 *
 * <p>用途：教学与原型验证。数据存在内存的 ConcurrentHashMap 中，程序重启即丢失。</p>
 *
 * <p>为什么用 ConcurrentHashMap：① 按 id 增删改 O(1)，便于 deleteById；
 * ② 线程安全，支持并发读写；检索时遍历 values 做暴力打分。</p>
 *
 * <p>检索策略：暴力（Brute-force）——把查询向量和每一条都算余弦相似度，排序取 Top-K。
 * O(N) 复杂度，数据量大时应换 pgvector / Milvus 的 ANN 索引（实现同一 VectorStore 接口即可）。</p>
 *
 * @author ZeroAi
 */
public class InMemoryVectorStore implements VectorStore {

    /** id -> chunk，线程全 */
    private final ConcurrentHashMap<String, Chunk> store = new ConcurrentHashMap<>();

    @Override
    public void save(Chunk chunk) {
        if (chunk == null || chunk.getId() == null) {
            throw new IllegalArgumentException("chunk 及其 id 不能为空");
        }
        store.put(chunk.getId(), chunk);
    }

    @Override
    public void saveAll(List<Chunk> chunks) {
        if (chunks == null) {
            return;
        }
        for (Chunk c : chunks) {
            save(c);
        }
    }

    /**
     * 暴力 Top-K 检索：对库中每条算余弦相似度 → 降序排序 → 取前 topK。
     */
    @Override
    public List<SearchResult> search(float[] queryVector, int topK) {
        if (queryVector == null || topK <= 0) {
            return new ArrayList<>();
        }
        return store.values().stream()
                // 逐条算相似度，包装成 SearchResult
                .map(chunk -> new SearchResult(
                        chunk, VectorMath.cosineSimilarity(queryVector, chunk.getVector())))
                // 按得分从高到低排序
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed())
                // 取前 K 条
                .limit(topK)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }

    @Override
    public int size() {
        return store.size();
    }

    /**
     * 演示入口：存入 3 条 chunk，用查询向量检索 Top-2，观察排序与得分。
     * （此处向量为手工构造的示意向量，真实场景由 Embedding 模型生成）
     */
    public static void main(String[] args) {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();

        // 手工构造三条示意记录（真实场景 vector 来自 Embedding 模型）
        vectorStore.save(new Chunk("c1", "员工年假为10天", new float[]{0.9f, 0.1f, 0.0f})
                .addMetadata("source", "员工手册.pdf"));
        vectorStore.save(new Chunk("c2", "报销需30天内提交", new float[]{0.1f, 0.9f, 0.0f})
                .addMetadata("source", "财务制度.pdf"));
        vectorStore.save(new Chunk("c3", "带薪休假政策说明", new float[]{0.85f, 0.15f, 0.0f})
                .addMetadata("source", "员工手册.pdf"));

        System.out.println("库中记录数: " + vectorStore.size());

        // 模拟「年假相关」的问题向量，方向接近 c1/c3
        float[] queryVector = {0.88f, 0.12f, 0.0f};
        List<SearchResult> results = vectorStore.search(queryVector, 2);

        System.out.println("查询向量 Top-2 命中：");
        for (SearchResult r : results) {
            System.out.println("  " + r);
        }
        // 预期：c1、c3 得分最高（都是年假/休假主题），c2（报销）被排除
    }
}