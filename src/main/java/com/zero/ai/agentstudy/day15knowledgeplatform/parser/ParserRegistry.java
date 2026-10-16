package com.zero.ai.agentstudy.day15knowledgeplatform.parser;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.OcrEngine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 解析器注册表（策略路由）。
 *
 * <p>把「格式 → 解析器」的映射集中在一处，避免业务代码里出现
 * {@code if (format == PDF) ... else if (format == DOCX) ...} 这种会无限膨胀的分支。
 * 新增格式只需 {@link #register(DocumentParser)}，路由逻辑一行不改。</p>
 *
 * @author ZeroAi
 */
public class ParserRegistry {

    private final Map<DocumentFormat, DocumentParser> parsers = new EnumMap<>(DocumentFormat.class);

    /**
     * 注册一个解析器。同名格式后注册者覆盖先注册者（便于测试时替换实现）。
     */
    public ParserRegistry register(DocumentParser parser) {
        parsers.put(parser.supportedFormat(), parser);
        return this;
    }

    /**
     * 按格式路由解析器。
     *
     * @throws KnowledgeException 无对应解析器时抛 {@link KnowledgeErrorCode#UNSUPPORTED_FILE_TYPE}
     */
    public DocumentParser resolve(DocumentFormat format) {
        DocumentParser parser = parsers.get(format);
        if (parser == null) {
            throw KnowledgeException.of(KnowledgeErrorCode.UNSUPPORTED_FILE_TYPE,
                    "没有能处理 " + format + " 的解析器，已注册: " + parsers.keySet());
        }
        return parser;
    }

    public boolean supports(DocumentFormat format) {
        return parsers.containsKey(format);
    }

    public Set<DocumentFormat> supportedFormats() {
        return Collections.unmodifiableSet(parsers.keySet());
    }

    /**
     * 构造一套开箱即用的默认解析器组合。
     *
     * <p>PDF 与图片共用同一个 {@link OcrEngine}：PDF 抽不到文本层时会尝试 OCR 兜底。</p>
     */
    public static ParserRegistry defaults(OcrEngine ocrEngine) {
        ParserRegistry registry = new ParserRegistry();
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.PlainTextParser(
                DocumentFormat.TXT));
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.PlainTextParser(
                DocumentFormat.MD));
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.HtmlParser());
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.PdfParser(ocrEngine));
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.WordParser());
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.ExcelParser());
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.PptParser());
        registry.register(new com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.ImageParser(ocrEngine));
        return registry;
    }
}
