package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.KnowledgeChunk;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.RetrievalResult;

import java.util.List;

/**
 * 知识库存储与检索端口（Vector Store Port）。
 *
 * <p>负责长期记忆——知识切片的<strong>入库</strong>与<strong>向量检索</strong>。
 * 这是一个领域端口，对应生产环境的向量数据库（pgvector / Milvus / Elasticsearch kNN 等）。
 * 本教程提供内存实现，用余弦相似度做暴力检索（brute-force），语义与真实向量库一致。</p>
 *
 * <p>所有操作都以 {@code tenantId} 为第一入参，确保<strong>多租户数据隔离</strong>：
 * 一个租户的检索绝不会命中另一个租户的知识，这是企业级 RAG 的安全底线。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface KnowledgeStore {

    /**
     * 批量保存知识切片（通常来自同一篇文档的切片集合，已完成向量化）。
     *
     * @param chunks 待入库的切片
     */
    void saveAll(List<KnowledgeChunk> chunks);

    /**
     * 向量相似度检索：返回与查询向量最相关的 topK 个切片。
     *
     * @param tenantId    租户（数据隔离）
     * @param queryVector 查询向量（用户问题向量化后的结果）
     * @param topK        返回条数上限
     * @return 按相似度降序的检索结果
     */
    List<RetrievalResult> search(String tenantId, float[] queryVector, int topK);

    /**
     * 删除指定文档的所有切片（文档更新 / 下线时调用）。
     *
     * @param tenantId   租户
     * @param documentId 文档 ID
     */
    void deleteByDocument(String tenantId, String documentId);

    /**
     * 统计指定租户当前的切片总数（用于容量监控）。
     *
     * @param tenantId 租户
     * @return 切片数
     */
    long count(String tenantId);
}