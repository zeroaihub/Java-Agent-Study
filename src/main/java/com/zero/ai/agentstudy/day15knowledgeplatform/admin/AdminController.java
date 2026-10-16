package com.zero.ai.agentstudy.day15knowledgeplatform.admin;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Role;
import com.zero.ai.agentstudy.day15knowledgeplatform.api.RequestPrincipalResolver;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeResult;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端 API：授权、统计、审计。
 *
 * <p><b>本 Controller 必须与业务 API 严格隔离</b>，理由有两层：</p>
 *
 * <p><b>第一层是权限隔离</b>：授权操作要求 MANAGE 权限，
 * 远高于业务接口的 SEARCH / WRITE。
 * 如果授权接口和普通接口混在一起、共用一套权限校验，
 * 就很容易出现"为了图方便而放宽整体权限"的情况。</p>
 *
 * <p><b>第二层是职责隔离（Separation of Duties）</b>：
 * <b>能创建知识库的人，不应该能给自己授权。</b>
 * 否则任何有创建权限的人都能通过"创建 → 自授权"把权限体系架空。
 * 现实中的对应措施是：创建资源走业务系统，授权走独立的审批流程。</p>
 *
 * <p><b>第三个必须做对的点：授权操作必须留痕。</b>
 * 每一次授权都记录操作者、目标、角色。
 * 没有审计日志的权限系统，在出现泄漏事故时无法追溯——
 * 你既不知道是谁开的口子，也不知道开给了谁、什么时候开的。
 * 这里返回完整的 ACL 快照作为凭证，生产环境应写入独立的审计表且不可修改。</p>
 *
 * @author ZeroAi
 */
@RestController
@RequestMapping("/api/knowledge/admin")
public class AdminController {

    private final AclService aclService;
    private final AclRepository aclRepository;
    private final PlatformStatsService statsService;
    private final RequestPrincipalResolver principalResolver;

    public AdminController(AclService aclService,
                           AclRepository aclRepository,
                           PlatformStatsService statsService,
                           RequestPrincipalResolver principalResolver) {
        this.aclService = aclService;
        this.aclRepository = aclRepository;
        this.statsService = statsService;
        this.principalResolver = principalResolver;
    }

    /**
     * 授权：把某角色授予用户或组。
     *
     * <p>要求操作者对该知识库拥有 MANAGE 权限。</p>
     */
    @PostMapping("/acl/grant")
    public KnowledgeResult<Map<String, Object>> grant(
            @RequestParam("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "group", required = false) String group,
            @RequestParam("role") String role,
            HttpServletRequest http) {

        Principal operator = principalResolver.resolve(http);
        KnowledgeBaseId kbId = KnowledgeBaseId.of(knowledgeBaseId);

        if ((userId == null || userId.isBlank()) && (group == null || group.isBlank())) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT,
                    "userId 与 group 不能同时为空");
        }

        Role parsedRole;
        try {
            parsedRole = Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT,
                    "未知角色：" + role + "，可选值：" + java.util.Arrays.toString(Role.values()));
        }

        AccessControlList updated = aclService.grant(operator, kbId, userId, group, parsedRole);

        // 审计凭证：返回操作者、目标与生效后的 ACL 快照
        return KnowledgeResult.ok(Map.of(
                "operator", operator.userId(),
                "knowledgeBaseId", knowledgeBaseId,
                "grantedTo", userId != null && !userId.isBlank() ? "user:" + userId : "group:" + group,
                "role", parsedRole.name(),
                "aclSnapshot", updated.toString()));
    }

    /**
     * 查看某知识库的 ACL。
     */
    @GetMapping("/acl")
    public KnowledgeResult<Map<String, Object>> viewAcl(
            @RequestParam("knowledgeBaseId") String knowledgeBaseId,
            HttpServletRequest http) {
        Principal operator = principalResolver.resolve(http);
        KnowledgeBaseId kbId = KnowledgeBaseId.of(knowledgeBaseId);
        aclService.requirePermission(operator, kbId,
                com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Permission.MANAGE);

        AccessControlList acl = aclRepository
                .findByKnowledgeBase(operator.tenantId(), kbId)
                .orElseThrow(() -> KnowledgeException.of(KnowledgeErrorCode.RESOURCE_NOT_FOUND,
                        "知识库 " + kbId + " 尚未配置 ACL（默认拒绝所有访问）"));

        return KnowledgeResult.ok(Map.of(
                "knowledgeBaseId", knowledgeBaseId,
                "publicRole", acl.publicRole().name(),
                "userRoles", acl.userRoles(),
                "groupRoles", acl.groupRoles()));
    }

    /**
     * 平台统计（含索引一致性检查）。
     */
    @GetMapping("/stats")
    public KnowledgeResult<Map<String, Object>> stats() {
        return KnowledgeResult.ok(statsService.snapshot());
    }
}
