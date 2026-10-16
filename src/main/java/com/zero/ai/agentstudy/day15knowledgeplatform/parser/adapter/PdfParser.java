package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.OcrEngine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.util.Map;

/**
 * PDF 解析器（Apache PDFBox）。
 *
 * <p>PDF 是企业知识库里占比最高、也最麻烦的格式，麻烦在两点：</p>
 * <ol>
 *     <li><b>有文本层的 PDF</b>：PDFBox 的 {@link PDFTextStripper} 直接抽。</li>
 *     <li><b>扫描件型 PDF（纯图片）</b>：文本层为空，必须逐页渲染成图后走 OCR。</li>
 * </ol>
 *
 * <p>本类的关键设计是<b>自动降级</b>：抽到空文本时，若 OCR 可用则尝试 OCR 兜底，
 * 两者都拿不到内容才抛 {@link KnowledgeErrorCode#PARSE_FAILED}。
 * 这样「扫描件」不会静默变成一篇空文档混进知识库。</p>
 *
 * @author ZeroAi
 */
public class PdfParser implements DocumentParser {

    private final OcrEngine ocrEngine;

    public PdfParser(OcrEngine ocrEngine) {
        this.ocrEngine = ocrEngine;
    }

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.PDF;
    }

    @Override
    public ParsedDocument parse(byte[] content, Metadata source) {
        int pageCount = -1;
        String text;
        try (PDDocument document = Loader.loadPDF(content)) {
            pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            // 按页输出，保留页码顺序，便于后续按页溯源
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        } catch (Exception ex) {
            throw KnowledgeException.wrap(KnowledgeErrorCode.PARSE_FAILED, ex);
        }

        boolean ocrApplied = false;
        if (text == null || text.isBlank()) {
            // 没有文本层 —— 大概率是扫描件，尝试 OCR 兜底
            if (ocrEngine != null && ocrEngine.available()) {
                text = ocrEngine.recognize(content);
                ocrApplied = true;
            } else {
                throw KnowledgeException.of(KnowledgeErrorCode.PARSE_FAILED,
                        "PDF 未抽取到文本层（疑似扫描件），且未接入可用的 OCR 引擎，无法入库");
            }
        }

        Metadata enriched = Metadata.of(Map.of("parser", "pdfbox", "pageCount", pageCount));
        if (source != null) {
            enriched = source.merge(enriched);
        }
        return ParsedDocument.of(text, pageCount, ocrApplied, enriched);
    }
}
