package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * PPT（.pptx）解析器（Apache POI XSLF）。
 *
 * <p>PPT 的知识密度低但业务价值高（汇报材料、产品deck、培训课件）。
 * 解析要点是<b>保留幻灯片边界</b>：一页 slide 就是一个天然的知识单元，
 * 检索时以「页」为单位返回，用户能直接定位到是哪一页讲的。</p>
 *
 * <p>只取 {@link XSLFTextShape} 的文本，图形、图片、图表一律跳过——
 * 图表里的数据如果要入库，正确做法是读取底层 Excel 数据而非渲染后的形状。</p>
 *
 * @author ZeroAi
 */
public class PptParser implements DocumentParser {

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.PPTX;
    }

    @Override
    public ParsedDocument parse(byte[] content, Metadata source) {
        StringBuilder sb = new StringBuilder();
        int slideCount = 0;

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(content))) {
            for (XSLFSlide slide : ppt.getSlides()) {
                slideCount++;
                StringBuilder page = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            page.append(text.trim()).append('\n');
                        }
                    }
                }
                if (page.length() == 0) {
                    continue;
                }
                sb.append("\n## 第 ").append(slideCount).append(" 页\n").append(page);
            }
        } catch (Exception ex) {
            throw KnowledgeException.wrap(KnowledgeErrorCode.PARSE_FAILED, ex);
        }

        Metadata enriched = Metadata.of(Map.of("parser", "poi-xslf", "slideCount", slideCount));
        if (source != null) {
            enriched = source.merge(enriched);
        }
        return ParsedDocument.of(sb.toString().trim(), slideCount, false, enriched);
    }
}
