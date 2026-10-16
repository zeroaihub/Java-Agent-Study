package com.zero.ai.agentstudy.day15knowledgeplatform.document;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.Page;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.PageRequest;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存文档仓储（出站适配器）。
 *
 * <p>用 {@code Map<租户ID, Map<文档ID, Document>>} 的两级结构，
 * <b>让「租户隔离」在数据结构层面就成立</b>——想跨租户取数，
 * 先得拿到别人的一级 key，而一级 key 只有在持有正确 {@link TenantId} 时才能取出。</p>
 *
 * <p>生产替换：实现同一个 {@link DocumentRepository} 接口，
 * 内部换成 MyBatis / JPA 即可，应用层零改动。</p>
 *
 * @author ZeroAi
 */
public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<String, Map<String, Document>> storage = new ConcurrentHashMap<>();

    private Map<String, Document> bucketOf(TenantId tenantId) {
        return storage.computeIfAbsent(tenantId.value(), k -> new ConcurrentHashMap<>());
    }

    @Override
    public Document save(Document document) {
        bucketOf(document.tenantId()).put(document.id().value(), document);
        return document;
    }

    @Override
    public Optional<Document> findById(TenantId tenantId, DocumentId documentId) {
        Map<String, Document> bucket = storage.get(tenantId.value());
        if (bucket == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bucket.get(documentId.value()));
    }

    @Override
    public Page<Document> findByKnowledgeBase(TenantId tenantId,
                                              KnowledgeBaseId knowledgeBaseId,
                                              PageRequest pageRequest) {
        Map<String, Document> bucket = storage.get(tenantId.value());
        if (bucket == null) {
            return Page.empty(pageRequest);
        }
        List<Document> all = new ArrayList<>(bucket.values());
        all.removeIf(d -> !d.knowledgeBaseId().equals(knowledgeBaseId));
        all.sort(Comparator.comparing(Document::createdAt).reversed());

        long total = all.size();
        int offset = (int) Math.min(pageRequest.offset(), total);
        int end = (int) Math.min(offset + pageRequest.size(), total);
        return Page.of(new ArrayList<>(all.subList(offset, end)), pageRequest, total);
    }

    @Override
    public List<Document> findByStatus(TenantId tenantId, DocumentStatus status) {
        Map<String, Document> bucket = storage.get(tenantId.value());
        if (bucket == null) {
            return List.of();
        }
        List<Document> matched = new ArrayList<>();
        for (Document d : bucket.values()) {
            if (d.status() == status) {
                matched.add(d);
            }
        }
        matched.sort(Comparator.comparing(Document::createdAt));
        return matched;
    }

    @Override
    public void deleteById(TenantId tenantId, DocumentId documentId) {
        Map<String, Document> bucket = storage.get(tenantId.value());
        if (bucket != null) {
            bucket.remove(documentId.value());
        }
    }

    @Override
    public long countByKnowledgeBase(TenantId tenantId, KnowledgeBaseId knowledgeBaseId) {
        Map<String, Document> bucket = storage.get(tenantId.value());
        if (bucket == null) {
            return 0L;
        }
        return bucket.values().stream()
                .filter(d -> d.knowledgeBaseId().equals(knowledgeBaseId))
                .count();
    }
}
