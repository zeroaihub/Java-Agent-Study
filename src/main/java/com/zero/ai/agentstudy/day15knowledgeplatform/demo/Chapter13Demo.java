package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.InMemoryAclRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Role;
import com.zero.ai.agentstudy.day15knowledgeplatform.application.KnowledgePlatformFacade;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.ChunkingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.DomainEventPublisher;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentService;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.InMemoryDocumentRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.EmbeddingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.adapter.HashingEmbeddingModel;
import com.zero.ai.agentstudy.day15knowledgeplatform.infrastructure.InMemoryDomainEventPublisher;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParserRegistry;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParsingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.NullOcrEngine;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.MmrDiversifier;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.RerankService;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.adapter.HeuristicReranker;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.Bm25Index;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.HybridRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.KnowledgeIndexer;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.RetrievalService;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.DenseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.SparseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.fusion.ReciprocalRankFusion;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.adapter.InMemoryVectorStore;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.InMemoryKnowledgeBaseRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.InMemoryWorkspaceRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.WorkspaceService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第 13 章 Demo：统一 API 背后的完整链路。
 *
 * <p>本章把前 12 章的模块<b>一次性串起来</b>，通过门面
 * {@link KnowledgePlatformFacade} 完成一次真实的
 * 「上传 → 解析 → 切分 → 向量化 → 入库 → 权限检索 → 删除同步」全流程。</p>
 *
 * <p>Controller 只是薄薄一层，真正的编排都在这个门面里。
 * 这也是为什么本 Demo 不需要启动 Spring 也能把 API 的核心逻辑跑一遍。</p>
 *
 * @author ZeroAi
 */
public class Chapter13Demo {

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  第 13 章：knowledge-api —— 统一 API 背后的完整链路");
        System.out.println("=".repeat(78));

        TenantId tenant = TenantId.of("acme");
        KnowledgeBaseId policyKb = KnowledgeBaseId.of("kb-policy");
        KnowledgeBaseId secretKb = KnowledgeBaseId.of("kb-secret");
        WorkspaceId ws = WorkspaceId.of("ws-corp");

        // ---------- 装配 ----------
        var vectorStore = new InMemoryVectorStore();
        var bm25 = new Bm25Index();
        var indexer = new KnowledgeIndexer(vectorStore, bm25);
        var embedding = new EmbeddingService(new HashingEmbeddingModel(256));
        var parsing = new ParsingService(ParserRegistry.defaults(new NullOcrEngine()));
        var chunking = new ChunkingService();
        DocumentRepository docRepo = new InMemoryDocumentRepository();
        DomainEventPublisher publisher = new InMemoryDomainEventPublisher();
        var documentService = new DocumentService(docRepo, publisher);

        Retriever retriever = new HybridRetriever(List.of(
                new DenseRetriever(vectorStore, embedding),
                new SparseRetriever(bm25, indexer)), new ReciprocalRankFusion(), true);
        var retrieval = new RetrievalService(retriever);
        var rerank = new RerankService(new HeuristicReranker(), new MmrDiversifier(), true);

        var workspaceService = new WorkspaceService(
                new InMemoryWorkspaceRepository(), new InMemoryKnowledgeBaseRepository());
        var aclRepository = new InMemoryAclRepository();
        var aclService = new AclService(aclRepository);

        var facade = new KnowledgePlatformFacade(documentService, docRepo, parsing, chunking,
                embedding, indexer, retrieval, rerank, workspaceService, aclService);

        // ---------- 权限配置 ----------
        // 制度库：租户内公开可读
        aclRepository.save(AccessControlList.builder(tenant, policyKb)
                .publicRole(Role.READER).grantGroup("finance", Role.MANAGER).build());
        // 机密库：只有 core 组可读
        aclRepository.save(AccessControlList.builder(tenant, secretKb)
                .grantGroup("core", Role.READER).build());

        Principal alice = Principal.of(tenant, "alice", "finance");
        Principal dave = Principal.of(tenant, "dave", "sales");

        // ---------- 1. 入库 ----------
        printSection("[1] 入库：文档经完整流水线进入双索引");

        ingest(facade, tenant, ws, policyKb, "差旅报销制度.md", """
                # 差旅报销制度

                ## 报销时限
                员工出差返回后，需在 30 个工作日内提交报销申请。
                逾期未提交的，需书面说明原因并经分管领导批准。

                ## 报销材料
                报销单需附发票原件、行程单与审批记录。
                发票必须开具公司抬头，纳税人识别号须与实际一致。

                ## 审批权限
                单笔 5000 元以下由部门经理审批；
                5000 元及以上需部门经理与财务总监双签。
                """);

        ingest(facade, tenant, ws, policyKb, "发票要求.md", """
                # 发票开具要求

                发票抬头必须与公司全称一致。
                纳税人识别号错误、抬头不符的发票一律不予受理。
                电子发票需提供 PDF 原件，截图无效。
                """);

        ingest(facade, tenant, ws, secretKb, "核心薪酬调整方案.md", """
                # 核心团队薪酬调整方案（机密）

                ## 调整范围
                本次调整覆盖核心技术团队共 42 人。

                ## 调整幅度
                平均上调 18%，其中 P8 及以上额外配发限制性股票。

                ## 保密要求
                本方案在正式发文前仅限核心组成员知悉，严禁外传。
                """);

        System.out.println();
        System.out.println("  索引状态：向量库 " + vectorStore.count() + " 条，"
                + "倒排索引 " + bm25.size() + " 条，反查表 " + indexer.indexedChunkCount() + " 条");

        // ---------- 2. 同一查询，不同权限的人 ----------
        printSection("[2] 同一个问题，不同权限的人看到不同结果");

        searchAs(facade, alice, "报销需要准备什么材料");
        searchAs(facade, dave, "报销需要准备什么材料");

        printSection("[3] 机密知识库的隔离验证");

        searchAs(facade, alice, "薪酬调整幅度是多少");   // alice 在 finance 组，非 core
        searchAs(facade, Principal.of(tenant, "eric", "core"), "薪酬调整幅度是多少");

        System.out.println();
        System.out.println("  ★ 关键观察：");
        System.out.println("    eric（core 组，可读 2 个库）精准命中「平均上调 18%」；");
        System.out.println("    alice（finance 组，只读 1 个库）返回的全是差旅制度里的内容，");
        System.out.println("    ——它们与「薪酬」毫无关系，是检索在她有权的范围内尽力匹配的结果。");
        System.out.println();
        System.out.println("    注意这不是「检索失败」，而是「权限收敛」：");
        System.out.println("    机密库从一开始就不在她的可读集合里，");
        System.out.println("    那段内容从未被读取，连内存都没进过。");
        System.out.println();
        System.out.println("  ★ 这也解释了为什么「权限后置过滤」是错的：");
        System.out.println("    若先全库检索再丢弃无权结果，alice 这一侧要么召回率归零，");
        System.out.println("    要么无权数据已经进了内存——两种后果都不可接受。");

        // ---------- 4. 删除同步 ----------
        printSection("[4] 删除同步：删掉文档后，各索引必须一起清干净");

        // DocumentService 从 TenantContext 取租户，因此必须在租户上下文中调用
        var target = TenantContext.runWithTenant(tenant, () -> documentService
                .list(policyKb, new com.zero.ai.agentstudy.day15knowledgeplatform.core.common.PageRequest(0, 10))
                .content().stream()
                .filter(d -> d.title().contains("发票要求"))
                .findFirst()
                .orElseThrow());

        System.out.println("  删除文档：" + target.title() + " (" + target.id() + ")");
        var report = facade.deleteDocument(tenant, target.id());
        System.out.println("  " + report);
        System.out.println();
        System.out.println("  三个数字必须相等（向量库 / 倒排索引 / 反查表）：");
        System.out.println("    " + (report.isConsistent() ? "✓ 一致" : "✗ 不一致，说明历史上发生过双写失败"));

        System.out.println();
        System.out.println("  删除后再检索「发票抬头」：");
        var after = facade.search(alice, "发票抬头有什么要求", Set.of(), 3);
        System.out.println("    " + after);
        System.out.println();
        System.out.println("  ★ 如果删除不同步，这里会返回一份「已被删除的文档」的内容——");
        System.out.println("    那就是幽灵知识：轻则答案过期，重则泄漏已删除的敏感信息。");

        // ---------- 5. 更新不同步 ----------
        printSection("[5] 更新不同步：重新上传同一文档，旧切片必须一并清掉");

        // V1：内容较长，会被切成多片
        String v1 = """
                # 版本测试文档

                ## 第一章 总则
                本章用于验证文档更新时的切片清理逻辑。
                它包含足够长的文字，以便被切分策略切成多个切片，
                从而能够明显地观察到「切片数量发生变化」这件事。

                ## 第二章 细则
                第二章同样写入较长的说明文字。
                当文档被更新为更短的版本时，
                旧的、多余的切片必须被一并清理掉，
                否则用户检索时会同时看到新旧两个版本的内容。

                ## 第三章 附则
                第三章提供收尾说明，进一步拉长篇幅。
                """;
        var r1 = facade.ingest(tenant, ws, policyKb, "版本测试.md",
                v1.getBytes(StandardCharsets.UTF_8), Metadata.empty());
        System.out.println("  V1（较长，会切成多片）：");
        System.out.println("    " + r1);
        int afterFirst = indexer.indexedChunkCount();

        var docId = r1.documentId();
        System.out.println("    文档 ID = " + docId + "，版本 = "
                + TenantContext.runWithTenant(tenant, () ->
                documentService.get(docId).orElseThrow().version()));

        // V2：内容大幅缩短，切片数必然减少
        var r2 = facade.reingest(tenant, docId, "版本测试.md",
                "这是更新后的唯一一段内容，比 V1 短得多。".getBytes(StandardCharsets.UTF_8));
        System.out.println("  V2（大幅缩短）：");
        System.out.println("    " + r2);
        int afterSecond = indexer.indexedChunkCount();

        System.out.println("    文档 ID = " + docId + "，版本 = "
                + TenantContext.runWithTenant(tenant, () ->
                documentService.get(docId).orElseThrow().version()));

        System.out.println();
        System.out.println("  索引切片总数：V1 后 " + afterFirst + " → V2 后 " + afterSecond);
        System.out.println("  ★ V2 切片数变少，且总数没有净增加，说明 V1 的旧切片被清掉了。");
        System.out.println("    （ID 未变、版本号 +1，这才是「更新」该有的语义。）");
        System.out.println();
        System.out.println("  反例：如果直接用 ingest 再传一遍，会创建一个全新的 DocumentId，");
        System.out.println("    于是 V1 的切片与新文档的切片根本无法关联，");
        System.out.println("    旧切片将永远留在索引里——这就是「更新不同步」，");
        System.out.println("    它比「删除不同步」更容易发生（毕竟更新比删除频繁得多）。");

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  第 13 章 Demo 结束");
        System.out.println("=".repeat(78));
    }

    private static void ingest(KnowledgePlatformFacade facade, TenantId tenant, WorkspaceId ws,
                               KnowledgeBaseId kb, String filename, String content) {
        var result = facade.ingest(tenant, ws, kb, filename,
                content.getBytes(StandardCharsets.UTF_8),
                Metadata.of(Map.of("source", "chapter13-demo")));
        System.out.printf("  %-24s → %s%n", filename, result);
    }

    private static void searchAs(KnowledgePlatformFacade facade, Principal principal,
                                 String query) {
        System.out.println();
        System.out.println("  【" + principal.userId() + "（组：" + principal.groups() + "）】" + query);
        var outcome = facade.search(principal, query, Set.of(), 3);
        if (!outcome.hasResult()) {
            System.out.println("    无结果。" + (outcome.note() != null ? outcome.note() : ""));
            return;
        }
        for (var rc : outcome.results()) {
            System.out.printf("    - %-22s [%.4f] %s%n",
                    rc.citation(), rc.score(), rc.chunk().preview(36));
        }
        System.out.printf("    （可读知识库 %d 个，耗时 %dms，重排器 %s）%n",
                outcome.readableScope().size(), outcome.totalElapsedMs(), outcome.rerankerName());
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println(title);
        System.out.println("-".repeat(78));
    }
}
