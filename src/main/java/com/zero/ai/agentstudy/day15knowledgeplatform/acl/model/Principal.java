package com.zero.ai.agentstudy.day15knowledgeplatform.acl.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.util.Set;

/**
 * 主体（访问者）—— 携带身份与所属组。
 *
 * <p><b>为什么同时支持「用户」和「组」两种授权方式？</b>
 * 因为企业里授权的现实是：<b>80% 的授权应该给组，而不是给人。</b>
 * 给组授权意味着新人入职自动获得应有的知识权限，
 * 离职自动失去——不需要任何人记得去改配置。
 * 直接给人授权是例外（临时外部顾问、跨部门协作）。</p>
 *
 * <p><b>一个必须写进代码的教训</b>：principal 必须携带 {@code tenantId}，
 * 且鉴权时<b>必须校验主体所属租户与资源所属租户一致</b>。
 * 漏掉这一步，攻击者用 A 公司的账号去访问 ID 恰好相同的 B 公司资源，
 * 就能成功——这是多租户系统里最常见的一类越权漏洞，
 * 它的名字叫 <b>IDOR（Insecure Direct Object Reference）</b>，
 * 常年挂在 OWASP Top 10 里。</p>
 *
 * @param tenantId 主体所属租户
 * @param userId   用户唯一标识
 * @param groups   用户所属组（部门、项目组、角色组等）
 * @author ZeroAi
 */
public record Principal(TenantId tenantId, String userId, Set<String> groups) {

    public Principal {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        if (userId == null || userId.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "userId 不能为空");
        }
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    public static Principal of(TenantId tenantId, String userId, String... groups) {
        return new Principal(tenantId, userId, groups == null ? Set.of() : Set.of(groups));
    }

    @Override
    public String toString() {
        return "Principal{" + tenantId + "/" + userId + ", groups=" + groups + "}";
    }
}
