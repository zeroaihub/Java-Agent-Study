package com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.port;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;

import java.util.List;
import java.util.Set;

/**
 * 向量存储端口（出站端口）。
 *
 * <p>抽象成端口的意义：<b>今天用内存，明天换 pgvector / Milvus / Qdrant，
 * 检索层代码一行不改。</b>唯一的差别是性能、持久化与运维成本。</p>
 *
 * <p><b>这个接口里最重要的设计是：过滤条件（tenant / knowledgeBase）作为一等参数传入，
 * 而不是先查全量再在应用层过滤。</b>
 * 这是「权限与作用域前置」原则在存储层的落地，原因有二：</p>
 * <ol>
 *     <li><b>正确性</b>：近似最近邻（ANN）索引返回的是「全局 Top-K」。
 *         如果你先取全局 Top-100 再过滤掉 95 条无权访问的，
 *         真正属于你且排在 101 名的正确结果就永远找不回来了——<b>召回率凭空损失</b>。</li>
 *     <li><b>安全</b>：后置过滤意味着无权数据曾短暂存在于应用内存中，
 *         日志、异常栈、序列化缓存任何一处疏忽都会造成泄漏。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public interface VectorStore {

    /**
     * 写入或覆盖一批向量。
     *
     * <p>幂等：以 {@link EmbeddedChunk#stableKey()} 为业务主键，
     * 重复写入同一文档不会产生重复向量。</p>
     */
    void upsert(List<EmbeddedChunk> chunks);

    /**
     * 删除某文档的全部向量（<b>删除同步的关键操作</b>）。
     *
     * @return 实际删除的向量条数
     */
    int deleteByDocument(TenantId tenantId, DocumentId documentId);

    /**
     * 删除某知识库的全部向量。
     */
    int deleteByKnowledgeBase(TenantId tenantId, KnowledgeBaseId knowledgeBaseId);

    /**
     * 过滤条件下的相似度检索。
     *
     * @param tenantId        租户（强制，不可为空）
     * @param knowledgeBaseIds 检索作用域；为空表示不限知识库（仍需按租户隔离）
     * @param query           查询向量
     * @param topK            返回条数
     * @return 命中结果，按相似度降序
     */
    List<VectorMatch> search(TenantId tenantId,
                             Set<KnowledgeBaseId> knowledgeBaseIds,
                             EmbeddingVector query,
                             int topK);

    /**
     * 当前存储的向量总数。
     */
    long count();

    /**
     * 检索命中项。
     *
     * @param chunk   命中的已向量化切片
     * @param score   相似度得分（已归一化向量的余弦相似度，值域 [-1, 1]）
     * @author ZeroAi
     */
    record VectorMatch(EmbeddedChunk chunk, double score) {

        @Override
        public String toString() {
            return String.format("VectorMatch{score=%.4f, %s}", score, chunk.stableKey());
        }
    }
}
