package com.zero.ai.agentstudy.day05rag.vectorstore;

import com.zero.ai.agentstudy.day05rag.entity.Chunk;
import com.zero.ai.agentstudy.day05rag.entity.SearchResult;

import java.util.List;

/**
 * VectorStore —— 向量库能力接口（RAG 的「记忆中枢」抽象）。
 *
 * <p>为什么面向接口：底层实现会从「内存版」升级到 pgvector / Milvus，
 * 但「存 / 查 / 删」这三个能力是稳定的。上层（检索器、Service）只依赖本接口，
 * 换实现无需改上层代码——这就是依赖倒置，也是本项目能平滑升级的关键。</p>
 *
 * @author ZeroAi
 */
public interface VectorStore {

    /**
     * 保存一条记录（存在则覆盖）。
     *
     * @param chunk 待保存的 chunk（需含 id、content、vector）
     */
    void save(Chunk chunk);

    /**
     * 批量保存。
     *
     * @param chunks 待保存的 chunk 列表
     */
    void saveAll(List<Chunk> chunks);

    /**
     * 相似度检索：返回与查询向量最相似的 Top-K 条结果（按得分降序）。
     *
     * @param queryVector 查询向量（须与库中向量同维度、同 Embedding 模型）
     * @param topK        返回条数
     * @return 命中结果列表，按相似度从高到低排序
     */
    List<SearchResult> search(float[] queryVector, int topK);

    /**
     * 按 id 删除一条记录。
     *
     * @param id 记录唯一标识
     */
    void deleteById(String id);

    /**
     * @return 当前库中记录总数
     */
    int size();
}