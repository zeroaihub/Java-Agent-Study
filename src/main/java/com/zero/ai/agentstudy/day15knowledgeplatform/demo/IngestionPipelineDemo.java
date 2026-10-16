package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.ChunkingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentService;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.InMemoryDocumentRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.infrastructure.InMemoryDomainEventPublisher;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParserRegistry;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParsingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.NullOcrEngine;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;

import java.util.List;
import java.util.Map;

/**
 * 端到端 Demo：上传 → 解析 → 切分。
 *
 * <p>把第 3、4、5 章串成一条真实的 Ingestion 流水线，
 * 演示文档状态如何随流水线推进、事件如何发出、Chunk 如何带着租户与元数据产出。</p>
 *
 * <p>零依赖可运行：{@code java ... IngestionPipelineDemo}。</p>
 *
 * @author ZeroAi
 */
public class IngestionPipelineDemo {

    private static final byte[] DOCX = buildDocx();

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("Day15 端到端：上传 → 解析 → 切分");
        System.out.println("==================================================");

        InMemoryDomainEventPublisher events = new InMemoryDomainEventPublisher();
        DocumentService documents = new DocumentService(new InMemoryDocumentRepository(), events);
        ParsingService parsing = new ParsingService(ParserRegistry.defaults(new NullOcrEngine()));
        ChunkingService chunking = new ChunkingService();

        TenantId tenant = TenantId.of("acme");
        WorkspaceId ws = WorkspaceId.newId();
        KnowledgeBaseId kb = KnowledgeBaseId.newId();

        TenantContext.runWithTenant(tenant, () -> {
            // 1. 上传
            Document doc = documents.upload(ws, kb, "员工报销管理制度.docx", DOCX,
                    Metadata.of(Map.of("department", "finance", "source", "manual-upload")));
            System.out.println("\n[1] 上传: " + doc.id() + " 状态=" + doc.status());

            // 2. 解析
            ParsedDocument parsed = parsing.parse(doc);
            System.out.println("[2] 解析: 状态=" + doc.status()
                    + " 字符数=" + parsed.charCount());

            // 3. 切分
            List<Chunk> chunks = chunking.chunkAuto(doc, parsed);
            System.out.println("[3] 切分: 状态=" + doc.status()
                    + " 切片数=" + chunks.size()
                    + " 总Token≈" + chunks.stream().mapToInt(Chunk::tokenEstimate).sum());

            // 4. 入库
            documents.advanceTo(doc.id(),
                    com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus.EMBEDDING);
            documents.advanceTo(doc.id(),
                    com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus.EMBEDDED);
            documents.advanceTo(doc.id(),
                    com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus.INDEXED);
            System.out.println("[4] 入库: 状态=" + doc.status());

            System.out.println("\n切片明细:");
            for (Chunk c : chunks) {
                System.out.println("  [" + c.sequence() + "] " + c.preview(50));
            }
            System.out.println("\n元数据示例: " + chunks.get(0).metadata().asMap());
            System.out.println("已发出领域事件: " + events.count() + " 条");
            return null;
        });

        System.out.println("\n端到端 Demo 结束。");
    }

    private static byte[] buildDocx() {
        ByteArrayOutputStreamHolder holder = new ByteArrayOutputStreamHolder();
        try {
            org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                    new org.apache.poi.xwpf.usermodel.XWPFDocument();
            var h1 = doc.createParagraph();
            h1.setStyle("Heading1");
            h1.createRun().setText("员工报销管理制度");
            var p = doc.createParagraph();
            p.createRun().setText("本制度自 2026 年 1 月 1 日起执行，适用于全体正式员工。");
            var h2 = doc.createParagraph();
            h2.setStyle("Heading2");
            h2.createRun().setText("住宿标准");
            var p2 = doc.createParagraph();
            p2.createRun().setText("总监级别每晚上限 800 元，经理级别 500 元，员工级别 350 元。");
            var h3 = doc.createParagraph();
            h3.setStyle("Heading2");
            h3.createRun().setText("审批流程");
            var p3 = doc.createParagraph();
            p3.createRun().setText("单次报销低于 1000 元由直属主管审批，高于 10000 元需分管副总审批。");
            doc.write(holder.bos());
            doc.close();
        } catch (Exception ex) {
            throw new IllegalStateException("构造演示用 docx 失败", ex);
        }
        return holder.bos().toByteArray();
    }

    /** 仅为避免在静态方法里反复创建流的样板代码。 */
    private static final class ByteArrayOutputStreamHolder {
        private final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();

        java.io.ByteArrayOutputStream bos() {
            return bos;
        }
    }
}
