package com.zero.ai.agentstudy.day15knowledgeplatform.parser;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;

/**
 * 解析应用服务。
 *
 * <p>把「取原文 → 路由解析器 → 解析 → 回写文档状态与元数据」编排起来。
 * 它同时演示了一个重要的生产细节：<b>解析失败必须把文档状态推到 FAILED 并留下原因</b>，
 * 否则文档会永远停在 PARSING，重试捞不到、清理也清不掉，成为僵尸数据。</p>
 *
 * @author ZeroAi
 */
public class ParsingService {

    private final ParserRegistry registry;

    public ParsingService(ParserRegistry registry) {
        this.registry = com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException
                .requireNonNull(registry, "parserRegistry");
    }

    /**
     * 解析一份文档。
     *
     * @param document 待解析文档，状态需可推进到 PARSING
     * @return 解析结果
     */
    public ParsedDocument parse(Document document) {
        document.requireActive();
        document.markParsing();
        try {
            ParsedDocument parsed = parseContent(document);
            document.markParsed();
            document.enrichMetadata(parsed.metadata());
            return parsed;
        } catch (KnowledgeException ex) {
            document.markFailed(ex.getMessage());
            throw ex;
        }
    }

    /**
     * 只解析内容，不改变文档状态（供测试与 Demo 直接调用）。
     */
    public ParsedDocument parseContent(Document document) {
        DocumentFormat format = document.format();
        DocumentParser parser = registry.resolve(format);
        Metadata source = document.metadata()
                .put("documentId", document.id().toString())
                .put("title", document.title())
                .put("version", document.version().toString());
        return parser.parse(document.content(), source);
    }

    /**
     * 单独解析一段字节（不需要先建 Document），用于网页采集等场景。
     */
    public ParsedDocument parseBytes(DocumentFormat format, byte[] content) {
        DocumentParser parser = registry.resolve(format);
        return parser.parse(content, Metadata.empty());
    }

    /**
     * 判断某格式是否受支持。
     */
    public boolean supports(DocumentFormat format) {
        return registry.supports(format);
    }

    /**
     * 无解析能力时的统一异常。
     */
    public static KnowledgeException unsupported(String filename) {
        return KnowledgeException.of(KnowledgeErrorCode.UNSUPPORTED_FILE_TYPE, "无法解析的文件: " + filename);
    }

    /**
     * 便捷方法：按租户解析（租户上下文校验由调用方保证）。
     */
    public ParsedDocument parseFor(TenantId tenantId, Document document) {
        document.assertBelongsTo(tenantId);
        return parse(document);
    }
}
