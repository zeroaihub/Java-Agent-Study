package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Word（.docx）解析器（Apache POI）。
 *
 * <p>两个易踩的坑：</p>
 * <ol>
 *     <li><b>只取段落会漏掉表格</b>：企业文档里大量内容在表格中（参数表、价目表、对照表），
 *         只遍历 {@code getParagraphs()} 会静默丢失这些数据。</li>
 *     <li><b>标题层级丢失</b>：段落文本本身不含「这是几级标题」的信息，
 *         必须读 {@code getStyle()} 才能还原大纲，而大纲正是结构化切分的依据。</li>
 * </ol>
 *
 * <p>因此本类在段落前补上 {@code #/##/###} 标记，
 * 让 Word 文档也能享受第 5 章结构化切分的好处。</p>
 *
 * @author ZeroAi
 */
public class WordParser implements DocumentParser {

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.DOCX;
    }

    @Override
    public ParsedDocument parse(byte[] content, Metadata source) {
        StringBuilder sb = new StringBuilder();
        int paragraphCount = 0;
        int tableCount = 0;

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {

            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String marker = headingMarker(p.getStyle());
                if (!marker.isEmpty()) {
                    sb.append(marker).append(' ');
                }
                sb.append(text.trim()).append('\n');
                paragraphCount++;
            }

            for (XWPFTable table : doc.getTables()) {
                tableCount++;
                sb.append("\n[表格 ").append(tableCount).append("]\n");
                for (int r = 0; r < table.getNumberOfRows(); r++) {
                    var row = table.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    StringBuilder line = new StringBuilder();
                    for (int c = 0; c < row.getTableCells().size(); c++) {
                        if (c > 0) {
                            line.append(" | ");
                        }
                        line.append(row.getCell(c).getText().trim());
                    }
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                }
            }
        } catch (Exception ex) {
            throw KnowledgeException.wrap(KnowledgeErrorCode.PARSE_FAILED, ex);
        }

        Metadata enriched = Metadata.of(Map.of(
                "parser", "poi-xwpf",
                "paragraphCount", paragraphCount,
                "tableCount", tableCount));
        if (source != null) {
            enriched = source.merge(enriched);
        }
        return ParsedDocument.of(sb.toString().trim(), -1, false, enriched);
    }

    /**
     * 把 Word 的标题样式映射为 Markdown 井号标记。
     */
    private static String headingMarker(String style) {
        if (style == null) {
            return "";
        }
        String s = style.toLowerCase();
        for (int level = 1; level <= 6; level++) {
            if (s.equals("heading" + level) || s.equals("标题 " + level) || s.endsWith("heading " + level)) {
                return "#".repeat(level);
            }
        }
        return "";
    }
}
