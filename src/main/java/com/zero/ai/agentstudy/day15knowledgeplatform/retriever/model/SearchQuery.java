package com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 检索请求。
 *
 * <p>为什么要把一次检索封装成对象，而不是让方法接收 6 个参数？
 * 因为检索的参数会随业务演进不断增加（作用域、过滤、最小分、是否重排、
 * 是否走权限、实验分组…）。用对象承载，新增参数只需加一个带默认值的字段，
 * 所有调用点一行不改——这是<b>参数对象（Parameter Object）</b>模式的价值。</p>
 *
 * @param tenantId          租户（必填，多租户隔离的入口）
 * @param queryText         查询文本
 * @param knowledgeBaseIds  检索作用域；为空表示在该租户下不限知识库
 * @param topK              期望返回条数
 * @param metadataFilter    元数据过滤条件（如 department=finance）
 * @author ZeroAi
 */
public record SearchQuery(TenantId tenantId,
                          String queryText,
                          Set<KnowledgeBaseId> knowledgeBaseIds,
                          int topK,
                          Metadata metadataFilter) {

    /** 默认返回条数。注意这是「召回候选数」，不是最终给用户的条数（那由重排决定）。 */
    public static final int DEFAULT_TOP_K = 20;

    public SearchQuery {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        if (queryText == null || queryText.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "查询文本不能为空");
        }
        if (topK <= 0) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "topK 必须为正");
        }
        knowledgeBaseIds = knowledgeBaseIds == null
                ? Set.of()
                : Set.copyOf(knowledgeBaseIds);
        metadataFilter = metadataFilter == null ? Metadata.empty() : metadataFilter;
    }

    /**
     * 限定知识库的检索。
     */
    public static SearchQuery of(TenantId tenantId, String queryText, KnowledgeBaseId kbId, int topK) {
        Set<KnowledgeBaseId> scope = new LinkedHashSet<>();
        scope.add(kbId);
        return new SearchQuery(tenantId, queryText, scope, topK, Metadata.empty());
    }

    /**
     * 全租户内检索（不限知识库，但仍严格按 tenant 隔离）。
     */
    public static SearchQuery of(TenantId tenantId, String queryText) {
        return new SearchQuery(tenantId, queryText, Set.of(), DEFAULT_TOP_K, Metadata.empty());
    }
}
