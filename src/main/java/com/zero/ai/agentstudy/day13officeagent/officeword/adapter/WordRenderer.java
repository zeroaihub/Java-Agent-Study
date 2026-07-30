package com.zero.ai.agentstudy.day13officeagent.officeword.adapter;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.Block;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ChartBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentFormat;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentMetadata;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.HeadingBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ImageBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ListBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.PageBreakBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ParagraphBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.Run;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.TableBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.TextStyle;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.DocumentRenderer;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Word 渲染适配器（WordRenderer）——{@link DocumentRenderer} 出站端口的 Apache POI 实现。
 *
 * <p>它把与格式无关的 {@link DocumentIR} 语义树翻译成一份可用 Microsoft Word 打开的 .docx
 * 二进制。这里是六边形架构\"适配器层\"的典型样例：领域内核只认识 IR 与端口接口，所有对
 * Apache POI（XWPF）的依赖都被隔离在本类内部，未来若要换成 docx4j，只需新增一个实现，
 * 领域与 Pipeline 一行都不用改。</p>
 *
 * <p>渲染的核心是对 {@link Block} 密封接口做 {@code switch} 模式匹配穷尽处理：每种块被翻译成
 * 对应的 Word 结构（标题样式、段落、表格、列表、分页符、图片）。因为 Block 是 sealed，
 * 一旦 IR 层新增块类型，这里会立即编译报错，强制补齐分支——把\"漏渲染一种块\"从线上事故
 * 提前到编译期。</p>
 *
 * @author zero
 */
@Component
public class WordRenderer implements DocumentRenderer {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.WORD;
    }

    @Override
    public byte[] render(DocumentIR ir) {
        if (ir == null) {
            throw new IllegalArgumentException("待渲染的 DocumentIR 不能为 null");
        }
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            applyMetadata(doc, ir.metadata());

            for (Block block : ir.blocks()) {
                renderBlock(doc, block);
            }

            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("渲染 Word 文档失败", e);
        }
    }

    /**
     * 把文档元数据写进 .docx 的文档属性（Core Properties）。
     */
    private void applyMetadata(XWPFDocument doc, DocumentMetadata meta) {
        var core = doc.getProperties().getCoreProperties();
        core.setTitle(meta.title());
        core.setCreator(meta.author());
    }

    /**
     * 对块类型做穷尽分派——Block 是 sealed，编译器保证覆盖所有子类型。
     */
    private void renderBlock(XWPFDocument doc, Block block) {
        switch (block) {
            case HeadingBlock h -> renderHeading(doc, h);
            case ParagraphBlock p -> renderParagraph(doc, p);
            case TableBlock t -> renderTable(doc, t);
            case ListBlock l -> renderList(doc, l);
            case ChartBlock c -> renderChartAsTable(doc, c);
            case ImageBlock i -> renderImage(doc, i);
            case PageBreakBlock pb -> renderPageBreak(doc, pb);
        }
    }

    /** 标题：映射为 Word 内置标题样式 Heading1~Heading6，并按级别递减字号。 */
    private void renderHeading(XWPFDocument doc, HeadingBlock heading) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + heading.level());
        int size = Math.max(14, 28 - (heading.level() - 1) * 2);
        for (Run run : heading.runs()) {
            XWPFRun r = p.createRun();
            r.setText(run.text());
            r.setBold(true);
            r.setFontSize(size);
            applyColor(r, run.style());
        }
    }

    /** 段落：逐 Run 写入并应用富文本样式，整段应用对齐方式。 */
    private void renderParagraph(XWPFDocument doc, ParagraphBlock paragraph) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(toWordAlignment(paragraph.alignment()));
        for (Run run : paragraph.runs()) {
            writeStyledRun(p, run);
        }
    }

    /** 表格：表头加粗，数据行逐单元格写入富文本。 */
    private void renderTable(XWPFDocument doc, TableBlock table) {
        if (table.columnCount() == 0) {
            return;
        }
        if (!table.caption().isBlank()) {
            XWPFParagraph cap = doc.createParagraph();
            XWPFRun r = cap.createRun();
            r.setText(table.caption());
            r.setItalic(true);
        }
        XWPFTable poiTable = doc.createTable(1, table.columnCount());
        XWPFTableRow headerRow = poiTable.getRow(0);
        List<TableBlock.TableCell> header = table.header();
        for (int c = 0; c < header.size(); c++) {
            writeCell(headerRow.getCell(c), header.get(c).runs(), true);
        }
        for (List<TableBlock.TableCell> row : table.rows()) {
            XWPFTableRow poiRow = poiTable.createRow();
            for (int c = 0; c < row.size(); c++) {
                XWPFTableCell cell = poiRow.getCell(c);
                if (cell == null) {
                    cell = poiRow.addNewTableCell();
                }
                writeCell(cell, row.get(c).runs(), false);
            }
        }
    }

    /** 列表：有序列表用递增编号，无序列表用项目符号，按 indent 缩进。 */
    private void renderList(XWPFDocument doc, ListBlock list) {
        int index = 1;
        for (ListBlock.ListItem item : list.items()) {
            XWPFParagraph p = doc.createParagraph();
            p.setIndentationLeft(360 + item.indent() * 360);
            XWPFRun prefix = p.createRun();
            prefix.setText(list.ordered() ? (index++ + ". ") : "• ");
            for (Run run : item.runs()) {
                writeStyledRun(p, run);
            }
        }
    }

    /**
     * 图表：Word 端暂以\"数据表格 + 标题\"降级呈现（真正的原生图表在 ch05 深化）。
     * 这样即便当前不生成矢量图，业务数据也不丢失。
     */
    private void renderChartAsTable(XWPFDocument doc, ChartBlock chart) {
        XWPFParagraph title = doc.createParagraph();
        XWPFRun tr = title.createRun();
        tr.setText("[图表] " + chart.title());
        tr.setBold(true);
    }

    /** 图片：把内联字节写进文档，按期望尺寸缩放（0 表示默认尺寸）。 */
    private void renderImage(XWPFDocument doc, ImageBlock image) {
        if (image.size() == 0) {
            return;
        }
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        try (ByteArrayInputStream in = new ByteArrayInputStream(image.data())) {
            int w = image.widthPx() > 0 ? image.widthPx() : 400;
            int h = image.heightPx() > 0 ? image.heightPx() : 300;
            r.addPicture(in, pictureType(image.mediaType()), image.altText(),
                    Units.pixelToEMU(w), Units.pixelToEMU(h));
        } catch (Exception e) {
            r.setText("[图片渲染失败：" + image.altText() + "]");
        }
    }

    /** 分页符。 */
    private void renderPageBreak(XWPFDocument doc, PageBreakBlock ignored) {
        XWPFParagraph p = doc.createParagraph();
        p.setPageBreak(true);
    }

    // ============ 辅助方法 ============

    private void writeStyledRun(XWPFParagraph p, Run run) {
        XWPFRun r = p.createRun();
        r.setText(run.text());
        TextStyle style = run.style();
        r.setBold(style.bold());
        r.setItalic(style.italic());
        if (style.underline()) {
            r.setUnderline(org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE);
        }
        if (style.fontSize() != null) {
            r.setFontSize(style.fontSize());
        }
        applyColor(r, style);
    }

    private void writeCell(XWPFTableCell cell, List<Run> runs, boolean bold) {
        XWPFParagraph p = cell.getParagraphs().isEmpty()
                ? cell.addParagraph() : cell.getParagraphs().get(0);
        for (Run run : runs) {
            XWPFRun r = p.createRun();
            r.setText(run.text());
            r.setBold(bold || run.style().bold());
            applyColor(r, run.style());
        }
    }

    private void applyColor(XWPFRun r, TextStyle style) {
        if (style.colorHex() != null && !style.colorHex().isBlank()) {
            r.setColor(style.colorHex());
        }
    }

  private ParagraphAlignment toWordAlignment(ParagraphBlock.Alignment alignment) {
        return switch (alignment) {
            case LEFT -> ParagraphAlignment.LEFT;
            case CENTER -> ParagraphAlignment.CENTER;
            case RIGHT -> ParagraphAlignment.RIGHT;
            case JUSTIFY -> ParagraphAlignment.BOTH;
        };
    }

    private int pictureType(String mediaType) {
        return switch (mediaType == null ? "" : mediaType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> Document.PICTURE_TYPE_JPEG;
            case "image/gif" -> Document.PICTURE_TYPE_GIF;
            case "image/bmp" -> Document.PICTURE_TYPE_BMP;
            default -> Document.PICTURE_TYPE_PNG;
        };
    }
}