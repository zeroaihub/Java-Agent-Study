package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.PageRequest;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentService;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentStateMachine;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.InMemoryDocumentRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus;
import com.zero.ai.agentstudy.day15knowledgeplatform.infrastructure.InMemoryDomainEventPublisher;

import java.util.Map;

/**
 * 第 3 章 Demo：knowledge-document 文档域。
 *
 * <p>零依赖可运行：{@code java ... Chapter03Demo}。演示：</p>
 * <ol>
 *     <li>上传文档 → 发出 DocumentUploadedEvent</li>
 *     <li>状态机按流水线推进，非法流转被拦截</li>
 *     <li>跨租户访问被聚合根拒绝</li>
 *     <li>重新上传生成新版本</li>
 *     <li>删除触发 DocumentDeletedEvent（删除同步的起点）</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter03Demo {

    public static void main(String[] args) {
        section("第 3 章 · knowledge-document 文档域");

        InMemoryDomainEventPublisher events = new InMemoryDomainEventPublisher();
        DocumentService service = new DocumentService(new InMemoryDocumentRepository(), events);

        TenantId acme = TenantId.of("acme");
        TenantId rival = TenantId.of("rival");
        WorkspaceId ws = WorkspaceId.newId();
        KnowledgeBaseId kb = KnowledgeBaseId.newId();

        // ---------- 1. 上传 ----------
        step("1. 上传文档（租户上下文强制在场）");
        Document doc = TenantContext.runWithTenant(acme, () ->
                service.upload(ws, kb, "报销制度.md",
                        "# 报销制度\n\n差旅报销标准详见第三章。".getBytes(),
                        Metadata.of(Map.of("department", "finance"))));
        System.out.println("  上传成功: " + doc);
        System.out.println("  格式=" + doc.format() + " 版本=" + doc.version()
                + " 校验和=" + doc.checksum());

        // ---------- 2. 状态机 ----------
        step("2. 状态机推进（UPLOADED → ... → INDEXED）");
        for (DocumentStatus target : new DocumentStatus[]{
                DocumentStatus.PARSING, DocumentStatus.PARSED,
                DocumentStatus.CHUNKING, DocumentStatus.CHUNKED,
                DocumentStatus.EMBEDDING, DocumentStatus.EMBEDDED,
                DocumentStatus.INDEXED}) {
            TenantContext.runWithTenant(acme, () -> service.advanceTo(doc.id(), target));
            System.out.println("  → " + target.description());
        }

        step("3. 非法流转被拦截");
        try {
            TenantContext.runWithTenant(acme, () ->
                    service.advanceTo(doc.id(), DocumentStatus.PARSED));
            System.out.println("  ✗ 竟然成功了，状态机失效！");
        } catch (KnowledgeException ex) {
            System.out.println("  ✓ 已拦截: " + ex.getMessage());
        }
        System.out.println("  INDEXED 允许的下一步: "
                + DocumentStateMachine.allowedTransitions(DocumentStatus.INDEXED));

        // ---------- 4. 跨租户 ----------
        step("4. 跨租户访问防护");
        try {
            TenantId finalAcme = acme;
            TenantContext.runWithTenant(rival, () -> {
                service.get(doc.id()).ifPresent(d -> d.assertBelongsTo(TenantId.of("rival")));
                return null;
            });
            System.out.println("  rival 未取到文档（返回 empty，不暴露资源存在性）");
        } catch (KnowledgeException ex) {
            System.out.println("  ✓ 已拦截: " + ex.getMessage());
        }
        System.out.println("  同租户可查: " + TenantContext.runWithTenant(acme, () ->
                service.get(doc.id()).isPresent()));

        // ---------- 5. 版本 ----------
        step("5. 重新上传 → 新版本");
        Document v2 = doc.nextVersion("# 报销制度\n\n差旅报销标准已上调。".getBytes(), "报销制度.md");
        System.out.println("  原版本=" + doc.version() + " → 新版本=" + v2.version()
                + "，状态回到 " + v2.status());
        System.out.println("  ID 保持不变: " + doc.id().equals(v2.id()));

        // ---------- 6. 删除 ----------
        step("6. 删除触发级联清理事件");
        TenantContext.runWithTenant(acme, () -> {
            service.delete(doc.id());
            return null;
        });
        System.out.println("  已发出事件数: " + events.count());
        System.out.println("  DocumentDeletedEvent: "
                + events.publishedEventsOfType(
                com.zero.ai.agentstudy.day15knowledgeplatform.document.event.DocumentDeletedEvent.class).size());

        // ---------- 7. 无租户 ----------
        step("7. 缺少租户上下文");
        try {
            service.get(DocumentId.newId());
        } catch (KnowledgeException ex) {
            System.out.println("  ✓ 已拦截: " + ex.getErrorCode().code() + " "
                    + (ex.getErrorCode() == KnowledgeErrorCode.TENANT_REQUIRED ? ex.getMessage() : ""));
        }

        // ---------- 8. 分页 ----------
        step("8. 分页查询知识库");
        TenantContext.runWithTenant(acme, () -> {
            service.upload(ws, kb, "a.txt", "aaa".getBytes(), Metadata.empty());
            service.upload(ws, kb, "b.txt", "bbb".getBytes(), Metadata.empty());
            var page = service.list(kb, PageRequest.of(0, 2));
            System.out.println("  总数=" + page.totalElements() + " 当前页=" + page.content().size()
                    + " 是否有下一页=" + page.hasNext());
            return null;
        });

        System.out.println("\n第 3 章 Demo 结束。");
    }

    private static void section(String title) {
        System.out.println("==================================================");
        System.out.println(title);
        System.out.println("==================================================");
    }

    private static void step(String text) {
        System.out.println("\n--- " + text + " ---");
    }
}
