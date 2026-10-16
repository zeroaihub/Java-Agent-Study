package com.zero.ai.agentstudy.day15knowledgeplatform.application;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Permission;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.ChunkingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentService;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.EmbeddingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParsingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.RerankService;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.KnowledgeIndexer;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.RetrievalService;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.WorkspaceService;

import java.util.List;
import java.util.Set;

/**
 * 知识平台门面（Facade）—— <b>跨模块的编排者</b>。
 *
 * <p>前面 12 章建了 12 个各司其职的模块。但模块是给"理解"用的，
 * 调用方需要的不是 12 个模块，而是两个动作：<b>喂进去</b> 和 <b>问出来</b>。</p>
 *
 * <pre>
 *   入库：Document → 解析 → 切分 → 向量化 → 写双索引
 *   检索：查询 → 权限定范围 → 混合召回 → 精排 → 去冗余 → 结果
 * </pre>
 *
 * <p><b>门面模式的价值就在这里</b>：它把复杂的内部协作封装成两个方法，
 * 调用方（Controller、其它 Agent、定时任务）不需要知道内部有 12 个模块，
 * 更不需要知道它们的调用顺序。
 * 一旦把这套顺序散落到各个 Controller 里，
 * 就会出现「A 接口少做了删除同步」「B 接口忘了权限过滤」这类问题——
 * 而且由于每个接口看起来都能跑通，这些问题往往会潜伏很久。</p>
 *
 * <p><b>注意本类没有 {@code @Service} 注解</b>：它不依赖 Spring，
 * 构造注入全部依赖，因此可以在没有容器的环境里被 Demo 直接 new 出来运行。
 * 由 {@code api/PlatformConfiguration} 负责把它装配成 Spring Bean。</p>
 *
 * @author ZeroAi
 */
public class KnowledgePlatformFacade {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final ParsingService parsingService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final KnowledgeIndexer indexer;
    private final RetrievalService retrievalService;
    private final RerankService rerankService;
    private final WorkspaceService workspaceService;
    private final AclService aclService;

    public KnowledgePlatformFacade(DocumentService documentService,
                                   DocumentRepository documentRepository,
                                   ParsingService parsingService,
                                   ChunkingService chunkingService,
                                   EmbeddingService embeddingService,
                                   KnowledgeIndexer indexer,
                                   RetrievalService retrievalService,
                                   RerankService rerankService,
                                   WorkspaceService workspaceService,
                                   AclService aclService) {
        this.documentService = KnowledgeException.requireNonNull(documentService, "documentService");
        this.documentRepository = KnowledgeException.requireNonNull(documentRepository, "documentRepository");
        this.parsingService = KnowledgeException.requireNonNull(parsingService, "parsingService");
        this.chunkingService = KnowledgeException.requireNonNull(chunkingService, "chunkingService");
        this.embeddingService = KnowledgeException.requireNonNull(embeddingService, "embeddingService");
        this.indexer = KnowledgeException.requireNonNull(indexer, "indexer");
        this.retrievalService = KnowledgeException.requireNonNull(retrievalService, "retrievalService");
        this.rerankService = KnowledgeException.requireNonNull(rerankService, "rerankService");
        this.workspaceService = KnowledgeException.requireNonNull(workspaceService, "workspaceService");
        this.aclService = KnowledgeException.requireNonNull(aclService, "aclService");
    }

    // ------------------------------------------------------------------
    // 入库：一条完整的文档处理流水线
    // ------------------------------------------------------------------

    /**
     * 摄取一份文档：上传 → 解析 → 切分 → 向量化 → 写入双索引。
     *
     * <p><b>这里最重要的一处工程处理：先删再写。</b>
     * 在处理开始前，先调用 {@code removeDocument} 清掉该文档的历史切片。
     * 为什么？因为文档可能是<b>重新上传的新版本</b>：
     * 新版本切出 8 片，旧版本是 12 片，如果只做增量覆盖，
     * 那多出来的 4 片旧内容会永远留在索引里——
     * 于是用户检索时，会同时看到新版本第 3 段和已被删除的旧版本第 9 段。
     * <b>这就是"更新不同步"制造的幽灵知识，它和删除不同步一样危险，
     * 而且更容易发生（毕竟更新比删除频繁得多）。</b></p>
     *
     * @return 摄取结果；失败时 {@code success=false}
     */
    public IngestResult ingest(TenantId tenantId, WorkspaceId workspaceId,
                               KnowledgeBaseId kbId, String filename, byte[] content,
                               Metadata metadata) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(kbId, "kbId");
        KnowledgeException.requireNonNull(filename, "filename");

        long start = System.nanoTime();

        // DocumentService 内部从 TenantContext 取租户（见第 3 章的设计约定：
        // 租户不由调用方传入）。因此这里必须先建立租户上下文再调用。
        return TenantContext.runWithTenant(tenantId, () -> {
            Document document;
            try {
                document = documentService.upload(workspaceId, kbId, filename, content, metadata);
            } catch (KnowledgeException ex) {
                return IngestResult.failure(null, "上传失败", ex.getMessage());
            }

            try {
                // 先清理历史切片（更新/重跑场景，见方法注释）
                indexer.removeDocument(tenantId, document.id());

                // 1) 解析（内部推进 PARSING → PARSED）
                ParsedDocument parsed = parsingService.parse(document);

                // 2) 切分（内部推进 CHUNKING → CHUNKED，并按文档格式自动选策略）
                List<Chunk> chunks = chunkingService.chunkAuto(document, parsed);

                // 3) 向量化
                documentService.advanceTo(document.id(), DocumentStatus.EMBEDDING);
                List<EmbeddedChunk> embedded = embeddingService.embedAll(chunks);
                documentService.advanceTo(document.id(), DocumentStatus.EMBEDDED);

                // 4) 写入双索引（向量库 + 倒排索引）
                indexer.index(embedded);
                documentService.advanceTo(document.id(), DocumentStatus.INDEXED);

                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                return IngestResult.success(document.id(), chunks.size(), elapsedMs);

            } catch (KnowledgeException ex) {
                // 流水线失败：状态机已把文档置为 FAILED 并留下原因，这里只负责如实上报
                return IngestResult.failure(document.id(), "入库失败", ex.getMessage());
            }
        });
    }

    /**
     * 重新摄取一份<b>已存在</b>的文档（更新场景）。
     *
     * <p><b>为什么需要它，而不能直接用 {@link #ingest}？</b>
     * 因为 {@code ingest} 每次都会创建一个新的 {@link Document}（新的 DocumentId）。
     * 用它来做「更新」，结果是：新版本的切片进来了，
     * 旧版本的切片还留在索引里——两个版本同时在库，用户检索时新旧内容混着出现。
     * 而且旧切片<b>永远不会被清理</b>，因为它们的 DocumentId 与新文档不同，
     * 没有任何机制会把它们关联起来。</p>
     *
     * <p>正确的「更新」语义是：<b>同一个 DocumentId，版本号 +1，先清旧切片再写新的</b>。
     * 这正是本方法做的三件事：</p>
     * <ol>
     *     <li>取出已有文档，生成新版本（{@link Document#nextVersion}）；</li>
     *     <li>按 DocumentId 清除全部历史切片——这一步是关键，
     *         它保证新旧版本的切片数不一致时也不会残留；</li>
     *     <li>重跑整条流水线。</li>
     * </ol>
     *
     * @return 摄取结果
     */
    public IngestResult reingest(TenantId tenantId, DocumentId documentId,
                                 String filename, byte[] content) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(documentId, "documentId");

        return TenantContext.runWithTenant(tenantId, () -> {
            Document existing = documentRepository.findById(tenantId, documentId)
                    .orElseThrow(() -> KnowledgeException.of(KnowledgeErrorCode.DOCUMENT_NOT_FOUND,
                            "文档不存在：" + documentId));
            existing.assertBelongsTo(tenantId);

            // ① 生成新版本（ID 不变，版本 +1，状态回到 UPLOADED）
            Document next = existing.nextVersion(content, filename);
            documentRepository.save(next);

            // ② 清除该文档的全部历史切片（新旧版本的切片都在同一批 key 下）
            indexer.removeDocument(tenantId, documentId);

            try {
                // ③ 重跑流水线
                ParsedDocument parsed = parsingService.parse(next);
                List<Chunk> chunks = chunkingService.chunkAuto(next, parsed);
                documentService.advanceTo(documentId, DocumentStatus.EMBEDDING);
                List<EmbeddedChunk> embedded = embeddingService.embedAll(chunks);
                documentService.advanceTo(documentId, DocumentStatus.EMBEDDED);
                indexer.index(embedded);
                documentService.advanceTo(documentId, DocumentStatus.INDEXED);
                return IngestResult.success(documentId, chunks.size(), 0L);
            } catch (KnowledgeException ex) {
                return IngestResult.failure(documentId, "重新入库失败", ex.getMessage());
            }
        });
    }

    /**
     * 删除文档，并<b>同步清理全部索引</b>（第 11 章删除同步的落地）。
     */
    public KnowledgeIndexer.DeleteReport deleteDocument(TenantId tenantId, DocumentId documentId) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(documentId, "documentId");
        return indexer.removeDocument(tenantId, documentId);
    }

    // ------------------------------------------------------------------
    // 检索：权限 → 召回 → 精排 → 去冗余
    // ------------------------------------------------------------------

    /**
     * 权限感知检索。
     *
     * <p>完整链路：<b>权限定范围 → 混合召回 → RRF 融合 → 精排 → MMR 去冗余</b>。</p>
     *
     * <p><b>顺序不能颠倒，每一步的理由</b>：</p>
     * <ol>
     *     <li><b>权限在最前</b>：见 {@link AclService} 的说明。
     *         后置过滤会让近似索引的召回率凭空归零，且无权数据已进入内存。</li>
     *     <li><b>召回在中</b>：只在"有权看的"范围里检索，
     *         过滤条件直接下推到向量库与倒排索引，又准又快。</li>
     *     <li><b>精排在后</b>：精排成本高，必须先把候选从百万级收敛到几十条。</li>
     *     <li><b>MMR 最后</b>：它需要可信的相关性分数作为输入，
     *         所以必须排在精排之后。</li>
     * </ol>
     *
     * @param principal 访问主体（决定能看到什么）
     * @param queryText 查询文本
     * @param scope     请求的知识库范围；为空表示不限（仍按权限收敛）
     * @param topN      返回条数
     * @return 检索结果
     */
    public SearchOutcome search(Principal principal, String queryText,
                                Set<KnowledgeBaseId> scope, int topN) {
        KnowledgeException.requireNonNull(principal, "principal");
        if (queryText == null || queryText.isBlank()) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT, "查询文本不能为空");
        }
        if (topN <= 0) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT, "topN 必须为正");
        }

        long start = System.nanoTime();

        // ① 权限：算出「能读哪些知识库」
        Set<KnowledgeBaseId> readable = aclService.resolveReadableScope(principal, scope);
        if (readable.isEmpty()) {
            // 无权访问任何知识库 —— 返回空结果，而不是抛异常。
            // 「没权限」是一个正常的业务结果，不是系统错误。
            return SearchOutcome.empty(queryText, 0, "当前主体无任何可读知识库");
        }

        // ② 召回：只在可读范围内检索
        SearchQuery query = new SearchQuery(principal.tenantId(), queryText, readable,
                Math.max(topN * 4, 20), Metadata.empty());
        RetrievalService.SearchReport report = retrievalService.search(query);

        // ③ 精排 + ④ 去冗余
        List<RetrievedChunk> finalResults = rerankService.rerank(queryText, report.results(), topN);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new SearchOutcome(queryText, finalResults, readable, report, elapsedMs,
                rerankService.rerankerName(), null);
    }

    /**
     * 校验主体对某知识库是否有检索权限（供 Controller 做前置校验与提示）。
     */
    public boolean canSearch(Principal principal, KnowledgeBaseId kbId) {
        return aclService.hasPermission(principal, kbId, Permission.SEARCH);
    }

    public WorkspaceService workspaceService() {
        return workspaceService;
    }

    public KnowledgeIndexer indexer() {
        return indexer;
    }

    // ------------------------------------------------------------------
    // 结果对象
    // ------------------------------------------------------------------

    /**
     * 摄取结果。
     *
     * @author ZeroAi
     */
    public record IngestResult(boolean success, DocumentId documentId, String stage,
                               String message, int chunkCount, long elapsedMs) {

        public static IngestResult success(DocumentId id, int chunkCount, long elapsedMs) {
            return new IngestResult(true, id, "完成", "入库成功", chunkCount, elapsedMs);
        }

        public static IngestResult failure(DocumentId id, String stage, String message) {
            return new IngestResult(false, id, stage, message, 0, 0L);
        }

        @Override
        public String toString() {
            return success
                    ? "IngestResult{成功, " + documentId + ", 切片=" + chunkCount + ", " + elapsedMs + "ms}"
                    : "IngestResult{失败, " + stage + ": " + message + "}";
        }
    }

    /**
     * 检索结果（含诊断信息）。
     *
     * @param query         查询文本
     * @param results       最终结果
     * @param readableScope 实际生效的权限范围（诊断用）
     * @param report        召回阶段报告（诊断用）
     * @param totalElapsedMs 总耗时
     * @param rerankerName  使用的重排序器
     * @param note          备注（如"无权访问"）
     * @author ZeroAi
     */
    public record SearchOutcome(String query,
                                List<RetrievedChunk> results,
                                Set<KnowledgeBaseId> readableScope,
                                RetrievalService.SearchReport report,
                                long totalElapsedMs,
                                String rerankerName,
                                String note) {

        public static SearchOutcome empty(String query, long elapsedMs, String note) {
            return new SearchOutcome(query, List.of(), Set.of(), null, elapsedMs, "-", note);
        }

        public boolean hasResult() {
            return results != null && !results.isEmpty();
        }

        @Override
        public String toString() {
            return "SearchOutcome{hits=" + (results == null ? 0 : results.size())
                    + ", scope=" + readableScope.size()
                    + ", reranker=" + rerankerName
                    + ", elapsed=" + totalElapsedMs + "ms"
                    + (note == null ? "" : ", note=" + note) + "}";
        }
    }
}
