package com.zero.ai.agentstudy.day15knowledgeplatform.api;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Permission;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.application.KnowledgePlatformFacade;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeResult;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.Page;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.PageRequest;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentService;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档 API：上传、列表、删除。
 *
 * <p><b>本 Controller 最值得学习的一点是：每个写操作之前都做权限校验。</b>
 * 以 {@link #upload} 为例，它先校验当前主体对该知识库有 WRITE 权限，
 * 再执行上传。如果省略这一步，任何登录用户都能往任意知识库里塞文档——
 * 这不只是"数据被污染"，攻击者可以借此向机密知识库注入内容，
 * 再通过检索把它读出来，形成一条完整的越权读通道。</p>
 *
 * <p><b>异步化的说明</b>：
 * 生产环境下 {@code upload} 应该只返回文档 ID 并立即返回（202 Accepted），
 * 把解析/切分/向量化丢到消息队列里异步执行。
 * 原因见第 6 章与第 10 章：一条完整流水线的耗时通常在秒级到分钟级，
 * 同步等待会导致接口超时、连接耗尽。
 * 这里保持同步实现，是为了让整条链路在 Demo 里可以同步观察与验证。</p>
 *
 * @author ZeroAi
 */
@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgePlatformFacade facade;
    private final DocumentService documentService;
    private final RequestPrincipalResolver principalResolver;
    private final com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService aclService;

    public KnowledgeDocumentController(KnowledgePlatformFacade facade,
                                       DocumentService documentService,
                                       RequestPrincipalResolver principalResolver,
                                       com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService aclService) {
        this.facade = facade;
        this.documentService = documentService;
        this.principalResolver = principalResolver;
        this.aclService = aclService;
    }

    /**
     * 上传文档并触发完整入库流水线。
     */
    @PostMapping
    public KnowledgeResult<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                       @RequestParam("workspaceId") String workspaceId,
                                                       @RequestParam("knowledgeBaseId") String knowledgeBaseId,
                                                       HttpServletRequest http) {
        Principal principal = principalResolver.resolve(http);
        KnowledgeBaseId kbId = KnowledgeBaseId.of(knowledgeBaseId);

        // 写权限校验——必须在执行前
        aclService.requirePermission(principal, kbId, Permission.WRITE);

        if (file == null || file.isEmpty()) {
            return KnowledgeResult.fail("INVALID_ARGUMENT", "上传文件为空");
        }

        TenantId tenantId = TenantContext.require();
        Metadata metadata = Metadata.of(Map.of(
                "uploader", principal.userId(),
                "originalFilename",
                file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename()));

        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception ex) {
            return KnowledgeResult.fail("INVALID_ARGUMENT", "读取上传文件失败：" + ex.getMessage());
        }

        var result = facade.ingest(tenantId, WorkspaceId.of(workspaceId), kbId,
                file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename(),
                content, metadata);

        if (!result.success()) {
            return KnowledgeResult.fail("INGEST_FAILED",
                    result.stage() + "：" + result.message());
        }
        return KnowledgeResult.ok(Map.of(
                "documentId", String.valueOf(result.documentId()),
                "chunkCount", result.chunkCount(),
                "elapsedMs", result.elapsedMs()));
    }

    /**
     * 列出知识库内的文档。
     */
    @GetMapping
    public KnowledgeResult<Page<DocumentSummary>> list(
            @RequestParam("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest http) {
        Principal principal = principalResolver.resolve(http);
        KnowledgeBaseId kbId = KnowledgeBaseId.of(knowledgeBaseId);
        aclService.requirePermission(principal, kbId, Permission.SEARCH);

        Page<Document> documents = documentService.list(kbId, PageRequest.of(page, size));
        List<DocumentSummary> summaries = documents.content().stream()
                .map(DocumentSummary::from)
                .toList();
        return KnowledgeResult.ok(new Page<>(summaries, documents.page(),
                documents.size(), documents.totalElements()));
    }

    /**
     * 删除文档（含全部索引的级联清理）。
     */
    @DeleteMapping("/{documentId}")
    public KnowledgeResult<Map<String, Object>> delete(@PathVariable("documentId") String documentId,
                                                       @RequestParam("knowledgeBaseId") String knowledgeBaseId,
                                                       HttpServletRequest http) {
        Principal principal = principalResolver.resolve(http);
        aclService.requirePermission(principal, KnowledgeBaseId.of(knowledgeBaseId), Permission.DELETE);

        TenantId tenantId = TenantContext.require();
        var report = facade.deleteDocument(tenantId, DocumentId.of(documentId));
        documentService.delete(DocumentId.of(documentId));

        return KnowledgeResult.ok(Map.of(
                "documentId", documentId,
                "vectorRemoved", report.vectorRemoved(),
                "bm25Removed", report.bm25Removed(),
                "registryRemoved", report.registryRemoved(),
                "consistent", report.isConsistent()));
    }

    /**
     * 文档摘要视图（不暴露原始字节，避免大对象传输）。
     *
     * @author ZeroAi
     */
    public record DocumentSummary(String documentId, String title, String format,
                                  String status, String version, long sizeInBytes) {

        public static DocumentSummary from(Document doc) {
            return new DocumentSummary(doc.id().value(), doc.title(),
                    doc.format().name(), doc.status().name(),
                    doc.version().toString(), doc.sizeInBytes());
        }
    }
}
