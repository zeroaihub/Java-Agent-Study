package com.zero.ai.agentstudy.day16knowledgeagentv2.acl;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievedChunk;

import java.util.List;

/**
 * 权限感知过滤（Chapter 07）。
 *
 * <p>企业知识库最容易出的事故不是「答错」，而是「让不该看的人看到了不该看的内容」。
 * 因此 ACL 过滤必须发生在<b>检索之后、送入 LLM 之前</b>——即使检索召回了高相关片段，
 * 只要用户无权访问，就必须剔除，绝不能进入上下文。</p>
 *
 * <p>规则：片段的 {@code aclLabels} 与用户角色求交集，只要有交集即放行；管理员放行全部。
 * 这是「白名单」策略——<b>默认拒绝，显式放行</b>，安全兜底方向正确。</p>
 *
 * @author ZeroAi
 */
public class AccessPolicy {

    /**
     * 过滤出当前用户有权查看的片段。
     *
     * @param ctx    请求上下文（携带用户角色）
     * @param chunks 检索/重排后的候选片段
     * @return 仅保留用户有权访问的片段，顺序不变
     */
    public List<RetrievedChunk> filter(AgentContext ctx, List<RetrievedChunk> chunks) {
        if (ctx.isAdmin()) {
            return chunks; // 管理员可见全部（生产环境需二次审计，谨慎授予）
        }
        return chunks.stream()
                .filter(chunk -> canAccess(ctx, chunk))
                .toList();
    }

    /**
     * 判断单个片段是否可访问：无标签视为公开；有标签则需与用户角色有交集。
     */
    public boolean canAccess(AgentContext ctx, RetrievedChunk chunk) {
        if (chunk.aclLabels().isEmpty()) {
            return true; // 无 ACL 标签 = 公开片段
        }
        for (String label : chunk.aclLabels()) {
            if (ctx.hasRole(label)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计被拒绝的片段数（用于可观测性/审计：多少召回因权限被拦）。
     */
    public long deniedCount(AgentContext ctx, List<RetrievedChunk> chunks) {
        return chunks.stream().filter(c -> !canAccess(ctx, c)).count();
    }
}