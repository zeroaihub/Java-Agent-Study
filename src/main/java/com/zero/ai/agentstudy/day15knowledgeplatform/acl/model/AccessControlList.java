package com.zero.ai.agentstudy.day15knowledgeplatform.acl.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 访问控制列表（ACL）—— 挂在知识库上的授权集合。
 *
 * <p><b>核心设计决策：默认拒绝（Deny by Default）。</b>
 * 不在 ACL 里的主体，一律无权访问。
 * 与之相对的是「默认允许 + 黑名单」，那是安全设计的反面教材——
 * 只要有一处漏配，机密就泄漏了，而漏配是必然会发生的事。
 * <b>默认拒绝保证了：出错的后果是"用户抱怨没权限"，而不是"机密泄漏"。</b>
 * 前者是服务可用性问题，后者是要上新闻的合规事故。</p>
 *
 * <p><b>为什么 ACL 挂在知识库上，而不是文档上？</b>
 * 见 {@link com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.KnowledgeBase} 的说明——
 * 权限粒度越细，维护成本与检索开销越高。
 * 知识库级授权覆盖了 90% 的场景，剩下 10% 用「受限知识库 + 文档级元数据过滤」解决。</p>
 *
 * @param tenantId        租户
 * @param knowledgeBaseId 受保护的知识库
 * @param userRoles       用户 ID → 角色
 * @param groupRoles      组名 → 角色
 * @param publicRole      公开角色；非 NONE 表示「租户内所有人可见」
 * @author ZeroAi
 */
public record AccessControlList(TenantId tenantId,
                                KnowledgeBaseId knowledgeBaseId,
                                Map<String, Role> userRoles,
                                Map<String, Role> groupRoles,
                                Role publicRole) {

    public AccessControlList {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        userRoles = new LinkedHashMap<>(userRoles == null ? Map.of() : userRoles);
        groupRoles = new LinkedHashMap<>(groupRoles == null ? Map.of() : groupRoles);
        publicRole = publicRole == null ? Role.NONE : publicRole;
    }

    public static AccessControlBuilder builder(TenantId tenantId, KnowledgeBaseId kbId) {
        return new AccessControlBuilder(tenantId, kbId);
    }

    /**
     * 计算某主体在该知识库上的<b>有效角色</b>。
     *
     * <p><b>为什么是"取最高"而不是"取第一个匹配"？</b>
     * 因为一个人可能同时通过多条途径获得授权：他个人被授予 READER，
     * 但他所在的"法务组"被授予 ADMIN。此时有效权限应该是 ADMIN。
     * 取最高角色符合直觉，也符合绝大多数企业的授权实践；
     * 反过来的「取最低」会导致"加了个组，反而权限变少了"的诡异现象。</p>
     *
     * <p><b>租户校验必须放在最前面。</b>
     * 主体的租户与 ACL 的租户不一致时，直接返回 NONE——
     * 而不是抛异常，因为这里是<b>判定</b>而非<b>断言</b>，
     * 判定函数应该"回答有没有权限"，而不是"拒绝执行"。
     * 这样它在检索过滤（对大量知识库逐一判定）时也能安全使用。</p>
     */
    public Role effectiveRole(Principal principal) {
        if (principal == null) {
            return Role.NONE;
        }
        // 跨租户一律无权——这是 IDOR 漏洞的防线
        if (!this.tenantId.equals(principal.tenantId())) {
            return Role.NONE;
        }

        Role best = publicRole;

        Role byUser = userRoles.get(principal.userId());
        if (byUser != null && byUser.level() > best.level()) {
            best = byUser;
        }
        for (String group : principal.groups()) {
            Role byGroup = groupRoles.get(group);
            if (byGroup != null && byGroup.level() > best.level()) {
                best = byGroup;
            }
        }
        return best;
    }

    /**
     * 判定某主体是否拥有某项权限。
     */
    public boolean hasPermission(Principal principal, Permission permission) {
        KnowledgeException.requireNonNull(permission, "permission");
        return effectiveRole(principal).implies(permission.minimumRole());
    }

    /**
     * 为某用户授权。返回新的 ACL 实例（不可变）。
     */
    public AccessControlList grantUser(String userId, Role role) {
        KnowledgeException.requireNonNull(userId, "userId");
        KnowledgeException.requireNonNull(role, "role");
        Map<String, Role> next = new LinkedHashMap<>(userRoles);
        next.put(userId, role);
        return new AccessControlList(tenantId, knowledgeBaseId, next, groupRoles, publicRole);
    }

    /**
     * 为某组授权。返回新的 ACL 实例。
     */
    public AccessControlList grantGroup(String group, Role role) {
        KnowledgeException.requireNonNull(group, "group");
        KnowledgeException.requireNonNull(role, "role");
        Map<String, Role> next = new LinkedHashMap<>(groupRoles);
        next.put(group, role);
        return new AccessControlList(tenantId, knowledgeBaseId, userRoles, next, publicRole);
    }

    /**
     * 撤销某用户的所有权限。
     */
    public AccessControlList revokeUser(String userId) {
        Map<String, Role> next = new LinkedHashMap<>(userRoles);
        next.remove(userId);
        return new AccessControlList(tenantId, knowledgeBaseId, next, groupRoles, publicRole);
    }

    public Optional<Role> roleOf(String userId) {
        return Optional.ofNullable(userRoles.get(userId));
    }

    /**
     * 便捷构造器。
     *
     * @author ZeroAi
     */
    public static final class AccessControlBuilder {

        private final TenantId tenantId;
        private final KnowledgeBaseId kbId;
        private final Map<String, Role> users = new LinkedHashMap<>();
        private final Map<String, Role> groups = new LinkedHashMap<>();
        private Role publicRole = Role.NONE;

        private AccessControlBuilder(TenantId tenantId, KnowledgeBaseId kbId) {
            this.tenantId = tenantId;
            this.kbId = kbId;
        }

        public AccessControlBuilder grantUser(String userId, Role role) {
            if (userId == null || userId.isBlank()) {
                throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "userId 不能为空");
            }
            users.put(userId, role);
            return this;
        }

        public AccessControlBuilder grantGroup(String group, Role role) {
            if (group == null || group.isBlank()) {
                throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "group 不能为空");
            }
            groups.put(group, role);
            return this;
        }

        public AccessControlBuilder publicRole(Role role) {
            this.publicRole = role == null ? Role.NONE : role;
            return this;
        }

        public AccessControlList build() {
            return new AccessControlList(tenantId, kbId, users, groups, publicRole);
        }
    }
}
