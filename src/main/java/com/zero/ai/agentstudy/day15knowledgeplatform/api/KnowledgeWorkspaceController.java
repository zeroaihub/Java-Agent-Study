package com.zero.ai.agentstudy.day15knowledgeplatform.api;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeResult;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.WorkspaceService;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.KnowledgeBase;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.Workspace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作空间 / 知识库 管理 API。
 *
 * <p><b>一个重要提醒</b>：本 Controller 只做「空间与知识库的创建与查看」，
 * 而<b>授权</b>被刻意放在了管理端（第 14 章的 AdminController）。
 * 这不是随意的划分——<b>创建资源与授予权限必须是两个独立的动作</b>，
 * 由不同的权限门槛保护。
 * 如果创建者自动成为管理员，那么任何一个有"创建知识库"权限的人，
 * 都能给自己授予"查看机密知识库"的权限，
 * <b>权限体系就此失效</b>。
 * 这条原则叫<b>职责分离（Separation of Duties）</b>，
 * 是权限设计里最基础也最容易被违反的一条。</p>
 *
 * @author ZeroAi
 */
@RestController
@RequestMapping("/api/knowledge/workspaces")
public class KnowledgeWorkspaceController {

    private final WorkspaceService workspaceService;

    public KnowledgeWorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 创建工作空间。
     */
    @PostMapping
    public KnowledgeResult<Map<String, Object>> createWorkspace(
            @RequestParam("name") String name,
            @RequestParam(value = "description", defaultValue = "") String description) {
        TenantContext.require();
        Workspace ws = workspaceService.createWorkspace(name, description, Metadata.empty());
        return KnowledgeResult.ok(Map.of(
                "workspaceId", ws.id().value(),
                "name", ws.name()));
    }

    /**
     * 列出当前租户的工作空间。
     */
    @GetMapping
    public KnowledgeResult<List<Map<String, Object>>> listWorkspaces(
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived) {
        TenantContext.require();
        List<Map<String, Object>> out = workspaceService.listWorkspaces(includeArchived).stream()
                .map(ws -> Map.<String, Object>of(
                        "workspaceId", ws.id().value(),
                        "name", ws.name(),
                        "archived", ws.isArchived()))
                .toList();
        return KnowledgeResult.ok(out);
    }

    /**
     * 创建知识库。
     */
    @PostMapping("/{workspaceId}/knowledge-bases")
    public KnowledgeResult<Map<String, Object>> createKnowledgeBase(
            @org.springframework.web.bind.annotation.PathVariable("workspaceId") String workspaceId,
            @RequestParam("name") String name,
            @RequestParam(value = "description", defaultValue = "") String description) {
        TenantContext.require();
        KnowledgeBase kb = workspaceService.createKnowledgeBase(
                WorkspaceId.of(workspaceId), name, description, Metadata.empty());
        return KnowledgeResult.ok(Map.of(
                "knowledgeBaseId", kb.id().value(),
                "name", kb.name(),
                "workspaceId", kb.workspaceId().value()));
    }

    /**
     * 列出工作空间内的知识库。
     */
    @GetMapping("/{workspaceId}/knowledge-bases")
    public KnowledgeResult<List<Map<String, Object>>> listKnowledgeBases(
            @org.springframework.web.bind.annotation.PathVariable("workspaceId") String workspaceId) {
        TenantContext.require();
        List<Map<String, Object>> out = workspaceService
                .listKnowledgeBases(WorkspaceId.of(workspaceId)).stream()
                .map(kb -> Map.<String, Object>of(
                        "knowledgeBaseId", kb.id().value(),
                        "name", kb.name(),
                        "embeddingModel", kb.embeddingModel() == null ? "(未写入)" : kb.embeddingModel(),
                        "documentCount", kb.documentCount()))
                .toList();
        return KnowledgeResult.ok(out);
    }
}
