package com.zero.ai.agentstudy.day15knowledgeplatform.chunk;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.ChunkId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.port.ChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy.FixedSizeChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy.SemanticChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy.StructuredChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分应用服务。
 *
 * <p>职责：把解析得到的纯文本切成 Chunk，并<b>装配检索所需的全部上下文</b>——
 * 租户、工作空间、文档 ID、序号、章节路径、Token 估算。</p>
 *
 * <p>这里体现了一个关键设计：<b>切片必须自带租户与权限维度</b>。
 * 很多人切完只存 {text, embedding}，等检索时才发现无法按租户/部门过滤，
 * 只能全库扫描后再在应用层丢弃——既慢又危险。
 * 我们在切片生成时就把这些维度焊死在 Chunk 上，检索时直接下推为过滤条件。</p>
 *
 * @author ZeroAi
 */
public class ChunkingService {

    private final ChunkStrategy defaultStrategy;

    public ChunkingService() {
        this(new SemanticChunkStrategy());
    }

    public ChunkingService(ChunkStrategy defaultStrategy) {
        this.defaultStrategy = KnowledgeException.requireNonNull(defaultStrategy, "chunkStrategy");
    }

    /**
     * 按指定策略切分。
     *
     * @param document 所属文档（提供租户/空间/文档 ID 等上下文）
     * @param parsed   解析结果
     * @param strategy 切分策略
     * @return Chunk 列表，按序编号
     */
    public List<Chunk> chunk(Document document, ParsedDocument parsed, ChunkStrategy strategy) {
        document.requireActive();
        document.markChunking();
        try {
            List<Chunk> chunks = doChunk(document, parsed, strategy);
            document.markChunked();
            return chunks;
        } catch (KnowledgeException ex) {
            document.markFailed(ex.getMessage());
            throw ex;
        }
    }

    /**
     * 按文档格式自动选择策略（推荐入口）。
     *
     * <p>选择逻辑：有标题结构的（MD / HTML / Word / PPT）用结构化切分；
     * 纯文本用语义切分；其余（Excel / 无结构的 PDF）用定长切分。</p>
     */
    public List<Chunk> chunkAuto(Document document, ParsedDocument parsed) {
        return chunk(document, parsed, selectStrategy(document.format()));
    }

    /**
     * 只切文本，不触碰文档状态（供测试与 Demo 使用）。
     */
    public List<Chunk> chunkText(Document document, ParsedDocument parsed, ChunkStrategy strategy) {
        return doChunk(document, parsed, strategy);
    }

    private List<Chunk> doChunk(Document document, ParsedDocument parsed, ChunkStrategy strategy) {
        if (parsed == null || parsed.isBlank()) {
            throw KnowledgeException.of(KnowledgeErrorCode.CHUNK_FAILED,
                    "解析结果为空，无法切分文档 " + document.id());
        }
        List<String> pieces = strategy.split(parsed.text());
        if (pieces.isEmpty()) {
            throw KnowledgeException.of(KnowledgeErrorCode.CHUNK_FAILED,
                    "切分结果为空，请检查文本与策略参数");
        }

        List<Chunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            Metadata meta = document.metadata()
                    .merge(parsed.metadata())
                    .put("chunkStrategy", strategy.name())
                    .put("sequence", i)
                    .put("totalChunks", pieces.size())
                    .put("documentTitle", document.title())
                    .put("documentVersion", document.version().toString());
            chunks.add(new Chunk(
                    ChunkId.newId(),
                    document.tenantId(),
                    document.workspaceId(),
                    document.knowledgeBaseId(),
                    document.id(),
                    i,
                    piece,
                    piece.length(),
                    TokenEstimator.estimate(piece),
                    meta));
        }
        return chunks;
    }

    /**
     * 策略选择：把「哪种文档该用哪种切法」固化下来。
     */
    public static ChunkStrategy selectStrategy(DocumentFormat format) {
        return switch (format) {
            case MD, HTML, DOCX, PPTX -> new StructuredChunkStrategy();
            case TXT -> new SemanticChunkStrategy();
            case PDF, XLSX, IMAGE, UNKNOWN -> new FixedSizeChunkStrategy();
        };
    }
}
