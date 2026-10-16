package com.zero.ai.agentstudy.day15knowledgeplatform.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * 检索请求 DTO。
 *
 * <p><b>DTO 与领域对象的边界必须守死</b>：
 * 这里刻意<b>没有</b> {@code tenantId} 字段——租户只能从认证上下文推断，
 * 绝不接受客户端指定。这是多租户 API 的第一条铁律。</p>
 *
 * @param query 查询文本
 * @param knowledgeBaseIds 检索范围；为空表示不限（仍受权限约束）
 * @param topN 返回条数
 * @author ZeroAi
 */
public record SearchRequest(@NotBlank(message = "query 不能为空") String query,
                            Set<String> knowledgeBaseIds,
                            Integer topN) {

    /**
     * topN 缺省值与上限保护。
     *
     * <p>为什么要有上限？因为 topN 直接影响精排的计算量与返回体大小，
     * 不设上限的话，{@code topN=100000} 会瞬间打满 CPU 与带宽。</p>
     */
    public int effectiveTopN() {
        if (topN == null || topN <= 0) {
            return 5;
        }
        return Math.min(topN, 50);
    }

    @Override
    public Set<String> knowledgeBaseIds() {
        return knowledgeBaseIds == null ? Set.of() : knowledgeBaseIds;
    }
}
