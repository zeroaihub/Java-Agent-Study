package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.OcrEngine;

import java.util.Map;

/**
 * 图片解析器（走 OCR）。
 *
 * <p>扫描合同、发票、白板照片、截图——这些在企业里大量存在，
 * 且往往是<b>唯一副本</b>（原件已销毁），OCR 是它们进入知识库的唯一通道。</p>
 *
 * <p>本类自己不实现识别，而是委托 {@link OcrEngine} 端口。
 * 这样更换 OCR 供应商时这个适配器一行都不用改。</p>
 *
 * @author ZeroAi
 */
public class ImageParser implements DocumentParser {

    private final OcrEngine ocrEngine;

    public ImageParser(OcrEngine ocrEngine) {
        this.ocrEngine = ocrEngine;
    }

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.IMAGE;
    }

    @Override
    public ParsedDocument parse(byte[] content, Metadata source) {
        if (ocrEngine == null || !ocrEngine.available()) {
            throw KnowledgeException.of(KnowledgeErrorCode.OCR_FAILED,
                    "图片类文档需要 OCR，但当前没有可用的 OcrEngine 实现");
        }
        String text;
        try {
            text = ocrEngine.recognize(content);
        } catch (Exception ex) {
            throw KnowledgeException.wrap(KnowledgeErrorCode.OCR_FAILED, ex);
        }
        if (text == null || text.isBlank()) {
            throw KnowledgeException.of(KnowledgeErrorCode.OCR_FAILED, "OCR 未识别出任何文字");
        }
        Metadata enriched = Metadata.of(Map.of("parser", "ocr", "ocrApplied", true));
        if (source != null) {
            enriched = source.merge(enriched);
        }
        return ParsedDocument.of(text, 1, true, enriched);
    }
}
