package com.zero.ai.agentstudy.day15knowledgeplatform.parser.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;

/**
 * 解析结果（值对象，不可变）。
 *
 * <p>解析器的统一产出物。无论输入是 PDF、Word、Excel 还是一张扫描件，
 * 到了这一步都变成<b>纯文本 + 元数据</b>，下游的 chunk / embedding 模块只见这一种结构。</p>
 *
 * <p>这就是管线化的价值：<b>上游格式千变万化，下游只认一个契约</b>。</p>
 *
 * @param text        解析出的纯文本
 * @param charCount   字符数
 * @param pageCount   页数/工作表数/幻灯片数（无法获取时为 -1）
 * @param ocrApplied  是否经过 OCR
 * @param metadata    解析器富化出的元数据（语言、标题、来源等）
 * @author ZeroAi
 */
public record ParsedDocument(String text,
                             int charCount,
                             int pageCount,
                             boolean ocrApplied,
                             Metadata metadata) {

    public ParsedDocument {
        KnowledgeException.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.PARSE_FAILED, "解析结果为空文本");
        }
        if (metadata == null) {
            metadata = Metadata.empty();
        }
    }

    public static ParsedDocument of(String text) {
        return new ParsedDocument(text, text.length(), -1, false, Metadata.empty());
    }

    public static ParsedDocument of(String text, int pageCount, boolean ocrApplied, Metadata metadata) {
        return new ParsedDocument(text, text.length(), pageCount, ocrApplied, metadata);
    }

    /**
     * 是否为空/仅空白（防御：有些 PDF 是纯图片扫描，PDFBox 会抽出空串）。
     */
    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
