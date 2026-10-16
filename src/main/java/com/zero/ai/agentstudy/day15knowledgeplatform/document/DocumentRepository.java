package com.zero.ai.agentstudy.day15knowledgeplatform.document;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.Page;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.PageRequest;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储端口（出站端口）。
 *
 * <p>领域层只声明「我需要能存取文档」，不关心底层是 PostgreSQL、MongoDB 还是内存 Map。
 * 这就是六边形架构的核心收益：<b>换存储不改领域代码</b>。</p>
 *
 * <p>注意所有方法签名都带 {@link TenantId} —— 租户不是「可选参数」，
 * 而是<b>强制契约</b>。想绕过租户？编译期就过不了。</p>
 *
 * @author ZeroAi
 */
public interface DocumentRepository {

    /**
     * 保存（新增或更新）文档。
     */
    Document save(Document document);

    /**
     * 按租户 + 文档 ID 查询。跨租户查询一律返回 empty，而不是抛异常——
     * 「查不到」比「报错暴露了资源存在」更安全（避免资源枚举攻击）。
     */
    Optional<Document> findById(TenantId tenantId, DocumentId documentId);

    /**
     * 分页查询某知识库下的文档。
     */
    Page<Document> findByKnowledgeBase(TenantId tenantId, KnowledgeBaseId knowledgeBaseId, PageRequest pageRequest);

    /**
     * 按状态查询，供流水线调度器捞取「待处理」文档。
     */
    List<Document> findByStatus(TenantId tenantId, DocumentStatus status);

    /**
     * 物理删除。
     */
    void deleteById(TenantId tenantId, DocumentId documentId);

    /**
     * 统计知识库内文档数。
     */
    long countByKnowledgeBase(TenantId tenantId, KnowledgeBaseId knowledgeBaseId);
}
