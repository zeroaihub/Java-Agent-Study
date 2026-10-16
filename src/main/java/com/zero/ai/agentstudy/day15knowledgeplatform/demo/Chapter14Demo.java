package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.InMemoryAclRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Permission;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Role;
import com.zero.ai.agentstudy.day15knowledgeplatform.admin.PlatformStatsService;
import com.zero.ai.agentstudy.day15knowledgeplatform.application.KnowledgePlatformFacade;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.ChunkingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.DomainEventPublisher;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
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
 * 第 14 章 Demo：管理端与全链路复盘。
 *
 * <p>本章做两件事：</p>
 * <ol>
 *     <li><b>管理端视角</b>：授权审计、平台统计、索引一致性检查；</li>
 *     <li><b>全链路复盘</b>：把 Day15 的 14 个模块在一条链路上重新过一遍，
 *         并诚实列出「教学实现」与「生产实现」的差距清单。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter14Demo {

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  第 14 章：knowledge-admin —— 管理端与全链路复盘");
        System.out.println("=".repeat(78));

        TenantId tenant = TenantId.of("acme");
        KnowledgeBaseId policyKb = KnowledgeBaseId.of("kb-policy");
        KnowledgeBaseId secretKb = KnowledgeBaseId.of("kb-secret");
        WorkspaceId ws = WorkspaceId.of("ws-corp");

        var platform = build(tenant, policyKb, secretKb);
        var facade = platform.facade();
        var aclService = platform.aclService();
        var statsService = platform.statsService();
        var indexer = platform.indexer();
        var aclRepository = platform.aclRepository();

        // ---------- 1. 准备数据 ----------
        printSection("[1] 准备：灌入文档");
        facade.ingest(tenant, ws, policyKb, "报销制度.md", """
                # 差旅报销制度
                ## 报销时限
                员工出差返回后需在 30 个工作日内提交报销申请。
                ## 报销材料
                需附发票原件、行程单与审批记录。
                ## 审批权限
                单笔 5000 元以下由部门经理审批，以上需财务总监双签。
                """.getBytes(StandardCharsets.UTF_8), Metadata.empty());
        facade.ingest(tenant, ws, secretKb, "薪酬方案.md", """
                # 核心团队薪酬调整方案（机密）
                ## 调整幅度
                平均上调 18%，P8 及以上额外配发限制性股票。
                本方案正式发文前仅限核心组成员知悉。
                """.getBytes(StandardCharsets.UTF_8), Metadata.empty());
        System.out.println("  已索引切片：" + indexer.indexedChunkCount() + " 条");

        // ---------- 2. 授权审计 ----------
        printSection("[2] 管理端：授权必须留痕，且需要 MANAGE 权限");

        Principal admin = Principal.of(tenant, "admin", "admin-group");
        Principal guest = Principal.of(tenant, "guest", "sales");
        aclRepository.save(AccessControlList.builder(tenant, policyKb)
                .grantUser("admin", Role.ADMIN).publicRole(Role.READER).build());
        aclRepository.save(AccessControlList.builder(tenant, secretKb)
                .grantUser("admin", Role.ADMIN).build());

        System.out.println("  admin（对 policy 库有 ADMIN）把 secret 库的 READER 授给 guest：");
        var aclAfter = aclService.grant(admin, secretKb, "guest", null, Role.READER);
        System.out.println("    ACL 快照：" + aclAfter);
        System.out.println();
        System.out.println("  ★ 审计三要素齐全：谁（admin）→ 对谁（guest）→ 授予什么（READER）。");
        System.out.println("    没有审计日志的权限系统，出事故时既查不到是谁开的口子，");
        System.out.println("    也查不到开给了谁、什么时候开的。");

        System.out.println();
        System.out.println("  再试一次越权授权：让 guest 去授权别人 ——");
        try {
            aclService.grant(guest, secretKb, "hacker", null, Role.ADMIN);
            System.out.println("  ✗ 未拦截！");
        } catch (KnowledgeException ex) {
            System.out.println("  ✓ 已拦截：" + ex.getMessage());
        }

        // ---------- 3. 平台统计 ----------
        printSection("[3] 管理端：平台统计与索引一致性检查");

        System.out.println("  授权生效后，guest 现在可以检索之前无权访问的机密库：");
        var guestOutcome = facade.search(guest, "薪酬调整幅度", Set.of(), 2);
        System.out.println("    " + guestOutcome);
        for (var rc : guestOutcome.results()) {
            System.out.println("      - " + rc.citation() + " | " + rc.chunk().preview(40));
        }

        System.out.println();
        System.out.println("  平台统计快照：");
        for (Map.Entry<String, Object> e : statsService.snapshot().entrySet()) {
            System.out.printf("    %-26s %s%n", e.getKey() + ":", e.getValue());
        }
        System.out.println();
        System.out.println("  ★ 其中 indexConsistent 是最应该被监控的指标。");
        System.out.println("    Hybrid Search 要求同一切片同时存在于两套索引，");
        System.out.println("    而双写天然有不一致风险。不主动监控的话，");
        System.out.println("    不一致会一直潜伏到用户投诉「搜不到某份文档」才暴露。");

        // ---------- 4. 一致性被破坏时 ----------
        printSection("[4] 一致性检查：人为制造不一致，验证能否被发现");

        System.out.println("  只删向量库、不删倒排索引（模拟一次双写失败）：");
        indexer.vectorStore().deleteByDocument(tenant,
                facade.indexer() != null ? firstDocId(platform, tenant, policyKb) : null);
        boolean consistent = statsService.isIndexConsistent();
        System.out.println("  isIndexConsistent() = " + consistent);
        var snap = statsService.snapshot();
        System.out.println("  提示信息：" + snap.get("inconsistencyHint"));
        System.out.println();
        System.out.println("  ★ 一旦不一致被发现，修复手段是重新索引该文档，");
        System.out.println("    而不是试图「手动补齐」某一套索引——补齐只会掩盖问题。");

        // ---------- 5. 全链路复盘 ----------
        printSection("[5] 全链路复盘：Day15 的 14 个模块如何串成一条线");

        System.out.println("""
                  上传 ──▶ document    文档聚合、版本、生命周期状态机、租户隔离
                    │
                    ▼
                  解析 ──▶ parser      PDF/Word/Excel/PPT/HTML/MD/TXT + OCR 端口
                    │
                    ▼
                  切分 ──▶ chunk       定长 / 语义 / 结构化，切片自带租户与知识库维度
                    │
                    ▼
                向量化 ──▶ embedding   批处理 + 去重缓存 + 模型锁版本（写入向量库）
                    │
                    ▼
                  入库 ──▶ retriever   双写：向量库(稠密) + 倒排索引(稀疏 BM25)
                    │
                    ▼
                  检索 ──▶ acl         ① 权限收敛出「可读知识库集合」
                    │      retriever   ② 只在可读范围内做混合召回
                    │      fusion      ③ RRF 融合（只认排名，不认分数）
                    │      reranker    ④ 精排 + MMR 多样性去重
                    ▼
                  结果 ──▶ api         带 citation 的可溯源结果，供各 Agent 调用
                    │
                    ▼
                  运维 ──▶ sync        增量同步 / 删除同步（幽灵知识防护）
                          browser     网页采集连接器
                          admin       授权审计 / 统计 / 一致性检查
                """);

        printSection("[6] 诚实清单：教学实现与生产实现的差距");

        System.out.println("""
          为保证「零依赖即可跑通」，本模块做了如下取舍。
          ★ 这些都不是生产实现，上线前必须逐项替换：

          1. 存储全部为内存  —— 进程重启即丢失。生产需换 PostgreSQL + pgvector。
          2. 向量检索是暴力扫描 —— O(n·d)，万级尚可，百万级必须换 HNSW/IVFFlat 索引。
          3. BM25 删除靠全量重建 —— O(n)。生产应换 Lucene/Elasticsearch，
             它们用「墓碑标记 + 后台段合并」摊平这个成本。
          4. Embedding 默认是 HashingEmbeddingModel —— 字面相似，不是语义相似。
             生产必须换真实稠密模型（bge / nomic / text-embedding-3）。
          5. 分词是无词典的 CJK unigram+bigram —— 生产建议接入带词典的分词器，
             并与离线索引使用同一套分词，否则检索质量会莫名其妙地差。
          6. Reranker 默认是 HeuristicReranker —— 生产应接 Cross-Encoder，
             它会自带降级能力（服务不可用时退回启发式精排）。
          7. 缺少最小相似度阈值 —— 向量检索没有「零命中」概念，
             必须配 minScore，否则「问什么都返回一堆不相关内容」。
          8. 双写非原子 —— 向量库写成功、倒排索引写失败就会永久不一致。
             生产应改为事件驱动 + 最终一致（写库后发事件，消费者更新索引，
             失败进重试队列），并在管理端持续监控 indexConsistent。
          9. 入库是同步的 —— 接口会等到整条流水线跑完。生产应异步化：
             接口只返回文档 ID（202 Accepted），解析/切分/向量化丢进消息队列。
         10. 租户与身份来自请求头 —— 仅用于演示，可被伪造。
             生产必须从 JWT / OAuth2 / 会话等服务端可验证的信息推断。
        """);

        System.out.println("=".repeat(78));
        System.out.println("  第 14 章 Demo 结束 —— Day15 全 14 章完结");
        System.out.println("=".repeat(78));
    }

    private static Platform build(TenantId tenant, KnowledgeBaseId policyKb, KnowledgeBaseId secretKb) {
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
        var statsService = new PlatformStatsService(indexer, embedding);
        return new Platform(facade, aclService, aclRepository, statsService, indexer, documentService);
    }

    private static com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId firstDocId(
            Platform platform, TenantId tenant, KnowledgeBaseId kb) {
        return com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext
                .runWithTenant(tenant, () -> platform.documentService()
                        .list(kb, new com.zero.ai.agentstudy.day15knowledgeplatform.core.common.PageRequest(0, 1))
                        .content().get(0).id());
    }

    private record Platform(KnowledgePlatformFacade facade,
                            AclService aclService,
                            InMemoryAclRepository aclRepository,
                            PlatformStatsService statsService,
                            KnowledgeIndexer indexer,
                            DocumentService documentService) {
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println(title);
        System.out.println("-".repeat(78));
    }
}
