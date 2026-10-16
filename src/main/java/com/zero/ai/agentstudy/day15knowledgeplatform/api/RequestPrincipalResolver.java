package com.zero.ai.agentstudy.day15knowledgeplatform.api;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 请求主体解析器：从请求中构建 {@link Principal}（访问主体）。
 *
 * <p><b>与 {@link TenantContextFilter} 一样，本类也是一处"演示用实现"</b>：
 * 用户身份来自请求头，客户端可以伪造。
 * 生产环境必须由 Spring Security 从 JWT / OAuth2 / 会话中解析出已认证的主体。</p>
 *
 * <p><b>但有一个关键区别必须强调</b>：
 * 即便身份是伪造的，<b>租户隔离依然生效</b>——
 * 因为 {@link Principal#tenantId()} 强制取自 {@link TenantContext}，
 * 而 {@link com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList#effectiveRole}
 * 的第一件事就是校验主体租户与资源租户一致。
 * <b>这是"纵深防御"设计的价值：单点的伪造不足以突破整个防线。</b></p>
 *
 * @author ZeroAi
 */
@Component
public class RequestPrincipalResolver {

    public static final String USER_HEADER = "X-User-Id";
    public static final String GROUPS_HEADER = "X-User-Groups";

    /**
     * 解析当前请求的访问主体。
     */
    public Principal resolve(HttpServletRequest request) {
        TenantId tenantId = TenantContext.require();

        String userId = request.getHeader(USER_HEADER);
        if (userId == null || userId.isBlank()) {
            throw KnowledgeException.of(KnowledgeErrorCode.ACCESS_DENIED,
                    "缺少用户身份（演示实现要求携带 " + USER_HEADER + " 请求头）");
        }

        Set<String> groups = new LinkedHashSet<>();
        String groupsHeader = request.getHeader(GROUPS_HEADER);
        if (groupsHeader != null && !groupsHeader.isBlank()) {
            for (String g : groupsHeader.split(",")) {
                String trimmed = g.trim();
                if (!trimmed.isEmpty()) {
                    groups.add(trimmed);
                }
            }
        }
        return new Principal(tenantId, userId.trim(), groups);
    }
}
