package com.zero.ai.agentstudy.day15knowledgeplatform.api;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.InMemoryAclRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.application.KnowledgePlatformFacade;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.ChunkingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentService;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.InMemoryDocumentRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.EmbeddingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.adapter.HashingEmbeddingModel;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.port.EmbeddingModel;
import com.zero.ai.agentstudy.day15knowledgeplatform.infrastructure.InMemoryDomainEventPublisher;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParserRegistry;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParsingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.NullOcrEngine;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.MmrDiversifier;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.RerankService;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.adapter.HeuristicReranker;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.port.Reranker;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.Bm25Index;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.HybridRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.KnowledgeIndexer;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.RetrievalService;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.DenseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.SparseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.adapter.InMemoryVectorStore;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.port.VectorStore;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.InMemoryKnowledgeBaseRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.InMemoryWorkspaceRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.KnowledgeBaseRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.WorkspaceRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.WorkspaceService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Day15 知识平台的 Spring 装配。
 *
 * <p><b>本类是「六边形架构」与 Spring 的接缝处，意义重大</b>：
 * 前面 13 章写的所有类都<b>没有</b>一个 Spring 注解，
 * 它们是纯粹的领域代码，可以在没有容器的环境里被 new 出来直接跑
 * （各章的 Demo 就是这么做的）。
 * 装配成 Bean 这件事，<b>全部集中在这一个文件里完成</b>。</p>
 *
 * <p>这样划分的收益是实实在在的：</p>
 * <ol>
 *     <li><b>领域代码可测试</b>：不需要启动 Spring 上下文，
 *         单测可以毫秒级运行（本项目所有 Demo 均如此）；</li>
 *     <li><b>替换实现只改一处</b>：把 {@code InMemoryVectorStore} 换成
 *         pgvector 实现，只改这个文件的 {@link #vectorStore()} 一个方法；</li>
 *     <li><b>依赖方向清晰</b>：领域不依赖框架，框架依赖领域。
 *         这是 Clean Architecture 的核心诉求。</li>
 * </ol>
 *
 * <p><b>当前全部使用内存实现</b>，进程重启即丢失。
 * 生产环境需要替换的实现清单见下方各 {@code @Bean} 方法的注释。</p>
 *
 * @author ZeroAi
 */
@Configuration
public class PlatformConfiguration {

    // ---------------- 基础设施（内存实现，生产需替换） ----------------

    /**
     * 向量存储。<b>生产替换点</b>：改为 pgvector / Milvus / Qdrant 实现。
     */
    @Bean
    public VectorStore vectorStore() {
        return new InMemoryVectorStore();
    }

    /**
     * BM25 倒排索引。<b>生产替换点</b>：改为 Elasticsearch / Lucene。
     */
    @Bean
    public Bm25Index bm25Index() {
        return new Bm25Index();
    }

    /**
     * 索引编排器（保证双索引一致）。
     */
    @Bean
    public KnowledgeIndexer knowledgeIndexer(VectorStore vectorStore, Bm25Index bm25Index) {
        return new KnowledgeIndexer(vectorStore, bm25Index);
    }

    @Bean
    public DocumentRepository documentRepository() {
        return new InMemoryDocumentRepository();
    }

    @Bean
    public WorkspaceRepository workspaceRepository() {
        return new InMemoryWorkspaceRepository();
    }

    @Bean
    public KnowledgeBaseRepository knowledgeBaseRepository() {
        return new InMemoryKnowledgeBaseRepository();
    }

    @Bean
    public AclRepository aclRepository() {
        return new InMemoryAclRepository();
    }

    // ---------------- 领域服务 ----------------

    @Bean
    public DocumentService documentService(DocumentRepository repository) {
        return new DocumentService(repository, new InMemoryDomainEventPublisher());
    }

    /**
     * Embedding 模型。<b>生产替换点</b>：
     * 改为 {@code new SpringAiEmbeddingModel(springAiModel, "text-embedding-3-small")}，
     * 当前默认使用零依赖的哈希实现，可在无网络、无 API Key 的环境下启动。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new HashingEmbeddingModel();
    }

    @Bean
    public EmbeddingService embeddingService(EmbeddingModel embeddingModel) {
        return new EmbeddingService(embeddingModel);
    }

    @Bean
    public ParsingService parsingService() {
        // 图片解析依赖 OCR 引擎，这里注入 Null 实现：
        // 未配置 OCR 时明确报错，而不是静默返回空文本（第 4 章的设计决策）
        return new ParsingService(ParserRegistry.defaults(new NullOcrEngine()));
    }

    @Bean
    public ChunkingService chunkingService() {
        return new ChunkingService();
    }

    @Bean
    public WorkspaceService workspaceService(WorkspaceRepository workspaceRepository,
                                             KnowledgeBaseRepository knowledgeBaseRepository) {
        return new WorkspaceService(workspaceRepository, knowledgeBaseRepository);
    }

    @Bean
    public AclService aclService(AclRepository aclRepository) {
        return new AclService(aclRepository);
    }

    // ---------------- 检索链路 ----------------

    /**
     * 混合检索器：向量 + BM25，虚拟线程并行 + RRF 融合。
     */
    @Bean
    public Retriever retriever(VectorStore vectorStore,
                               EmbeddingService embeddingService,
                               Bm25Index bm25Index,
                               KnowledgeIndexer indexer) {
        List<Retriever> delegates = List.of(
                new DenseRetriever(vectorStore, embeddingService),
                new SparseRetriever(bm25Index, indexer));
        return new HybridRetriever(delegates);
    }

    @Bean
    public RetrievalService retrievalService(Retriever retriever) {
        return new RetrievalService(retriever);
    }

    /**
     * 重排序器。<b>生产替换点</b>：
     * 若有 Cross-Encoder 服务，改为
     * {@code new CrossEncoderReranker(endpoint, model, new HeuristicReranker())}，
     * 它自带降级能力——模型服务不可用时自动退回启发式精排。
     */
    @Bean
    public Reranker reranker() {
        return new HeuristicReranker();
    }

    @Bean
    public RerankService rerankService(Reranker reranker) {
        return new RerankService(reranker, new MmrDiversifier(), true);
    }

    // ---------------- 门面 ----------------

    @Bean
    public KnowledgePlatformFacade knowledgePlatformFacade(DocumentService documentService,
                                                           DocumentRepository documentRepository,
                                                           ParsingService parsingService,
                                                           ChunkingService chunkingService,
                                                           EmbeddingService embeddingService,
                                                           KnowledgeIndexer knowledgeIndexer,
                                                           RetrievalService retrievalService,
                                                           RerankService rerankService,
                                                           WorkspaceService workspaceService,
                                                           AclService aclService) {
        return new KnowledgePlatformFacade(documentService, documentRepository, parsingService,
                chunkingService, embeddingService, knowledgeIndexer, retrievalService,
                rerankService, workspaceService, aclService);
    }

    @Bean
    public RequestPrincipalResolver requestPrincipalResolver() {
        return new RequestPrincipalResolver();
    }

    /**
     * 平台统计服务（管理端数据源）。
     */
    @Bean
    public com.zero.ai.agentstudy.day15knowledgeplatform.admin.PlatformStatsService platformStatsService(
            KnowledgeIndexer knowledgeIndexer, EmbeddingService embeddingService) {
        return new com.zero.ai.agentstudy.day15knowledgeplatform.admin.PlatformStatsService(
                knowledgeIndexer, embeddingService);
    }

    /**
     * 注册租户上下文过滤器。
     *
     * <p><b>为什么用 Filter 而不是 Interceptor？</b>
     * 因为 Filter 在 Servlet 层面工作，比 Interceptor 更早执行、更晚结束，
     * 能完整覆盖请求的生命周期——包括那些不经过 Spring MVC 的请求
     * （如异步派发、错误派发）。
     * 租户上下文必须在最早处建立、在最晚处清理，Filter 是唯一正确的位置。</p>
     */
    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter() {
        FilterRegistrationBean<TenantContextFilter> bean =
                new FilterRegistrationBean<>(new TenantContextFilter());
        bean.addUrlPatterns("/api/knowledge/*");
        bean.setOrder(FilterRegistrationBean.LOWEST_PRECEDENCE - 100);
        return bean;
    }
}
