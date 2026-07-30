package com.zero.ai.agentstudy.day13officeagent.officeppt.adapter;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.Block;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ChartBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentFormat;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.HeadingBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ImageBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ListBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.PageBreakBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ParagraphBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.Run;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.TableBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.DocumentRenderer;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PPT 渲染适配器（PptRenderer）——{@link DocumentRenderer} 出站端口的 Apache POI（XSLF）实现。
 *
 * <p>它把与格式无关的 {@link DocumentIR} 语义树投影成一份 .pptx 演示文稿。PPT 的抽象单位是
 * "幻灯片（Slide）"而非"页面流"，因此渲染策略与 Word/Excel 截然不同：这里采用"按标题切页"
 * 的分页策略——每遇到一级/二级标题就开一张新幻灯片，把标题作为页标题，后续叙述块作为该页
 * 的正文，直到下一个标题。这正是"一份 IR、多形态输出"的第三种投影。</p>
 *
 * <p>与前两个渲染器一致，所有对 POI 的依赖都被隔离在本适配器内部；对 {@link Block} 密封接口
 * 做无 {@code default} 的穷尽 {@code switch}，保证新增块类型时编译期即报错。</p>
 *
 * @author zero
 */
@Component
public class PptRenderer implements DocumentRenderer {

    /** 16:9 幻灯片宽度（EMU）。 */
    private static final int SLIDE_WIDTH = 12192000;
    /** 16:9 幻灯片高度（EMU）。 */
    private static final int SLIDE_HEIGHT = 6858000;
    /** 正文区左边距（点）。 */
    private static final int BODY_LEFT = 40;
    /** 正文区顶部起始（点），为标题留白。 */
    private static final int BODY_TOP = 90;

    @Override
    public DocumentFormat format() {
        return DocumentFormat.PPT;
    }

    @Override
    public byte[] render(DocumentIR ir) {
        if (ir == null) {
            throw new IllegalArgumentException("待渲染的 DocumentIR 不能为 null");
        }
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            ppt.setPageSize(new java.awt.Dimension(
                    (int) (SLIDE_WIDTH / 9525.0), (int) (SLIDE_HEIGHT / 9525.0)));

            // 封面页：用文档元数据
            createTitleSlide(ppt, ir.metadata().title(), ir.metadata().author());

         // 把块列表切成"以标题为界"的段，每段一张幻灯片
            SlideCursor cursor = new SlideCursor(ppt);
            for (Block block : ir.blocks()) {
                renderBlock(ppt, cursor, block);
            }

            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("渲染 PPT 文档失败", e);
        }
    }

    /** 生成封面幻灯片。 */
    private void createTitleSlide(XMLSlideShow ppt, String title, String author) {
        XSLFSlide slide = ppt.createSlide();
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(60, 200, ptWidth(ppt) - 120, 200));
        XSLFTextParagraph tp = box.addNewTextParagraph();
        XSLFTextRun tr = tp.addNewTextRun();
        tr.setText(title == null || title.isBlank() ? "未命名文档" : title);
        tr.setFontSize(40d);
        tr.setBold(true);
        if (author != null && !author.isBlank()) {
            XSLFTextParagraph ap = box.addNewTextParagraph();
            XSLFTextRun ar = ap.addNewTextRun();
            ar.setText("作者：" + author);
            ar.setFontSize(18d);
        }
    }

    /**
     * 对块类型做穷尽分派——Block 是 sealed，编译器保证覆盖所有子类型。
     */
    private void renderBlock(XMLSlideShow ppt, SlideCursor cursor, Block block) {
        switch (block) {
            case HeadingBlock h -> cursor.newSlideWithTitle(ppt, plainText(h.runs()));
            case ParagraphBlock p -> cursor.appendBullet(ppt, plainText(p.runs()), 0);
            case ListBlock l -> renderList(ppt, cursor, l);
            case TableBlock t -> renderTable(ppt, cursor, t);
            case ChartBlock c -> renderChart(ppt, cursor, c);
            case ImageBlock i -> renderImage(ppt, cursor, i);
            case PageBreakBlock pb -> cursor.forcePageBreak();
        }
    }

    /** 列表：每个列表项一条项目符号，按 indent 缩进。 */
    private void renderList(XMLSlideShow ppt, SlideCursor cursor, ListBlock list) {
        int index = 1;
        for (ListBlock.ListItem item : list.items()) {
            String prefix = list.ordered() ? (index++ + ". ") : "• ";
            cursor.appendBullet(ppt, prefix + plainText(item.runs()), item.indent());
        }
    }

    /** 表格：每个表格独占一张新幻灯片（PPT 上表格是主角）。 */
    private void renderTable(XMLSlideShow ppt, SlideCursor cursor, TableBlock table) {
        if (table.columnCount() == 0) {
            return;
        }
        XSLFSlide slide = cursor.newSlideWithTitle(ppt,
                table.caption().isBlank() ? "表格" : table.caption());
        XSLFTable poiTable = slide.createTable();
        poiTable.setAnchor(new Rectangle2D.Double(40, BODY_TOP, ptWidth(ppt) - 80, 40));

        XSLFTableRow headerRow = poiTable.addRow();
        for (TableBlock.TableCell cell : table.header()) {
            fillCell(headerRow.addCell(), plainText(cell.runs()), true);
        }
        for (List<TableBlock.TableCell> row : table.rows()) {
            XSLFTableRow poiRow = poiTable.addRow();
            for (TableBlock.TableCell cell : row) {
                fillCell(poiRow.addCell(), plainText(cell.runs()), false);
            }
        }
        for (int c = 0; c < table.columnCount(); c++) {
            poiTable.setColumnWidth(c, (double) (ptWidth(ppt) - 80) / table.columnCount());
        }
    }

    /** 图表：暂以"标题 + 数据要点"文本降级呈现，原生图表在后续深化。 */
    private void renderChart(XMLSlideShow ppt, SlideCursor cursor, ChartBlock chart) {
        cursor.newSlideWithTitle(ppt,
                chart.title().isBlank() ? "图表" : chart.title());
        cursor.appendBullet(ppt, "图表类型：" + chart.chartType(), 0);
        for (ChartBlock.Series s : chart.series()) {
            cursor.appendBullet(ppt, s.name() + "：" + s.values(), 1);
        }
    }

    /** 图片：新开一页居中放置图片。 */
    private void renderImage(XMLSlideShow ppt, SlideCursor cursor, ImageBlock image) {
        if (image.size() == 0) {
            return;
        }
        XSLFSlide slide = cursor.newSlideWithTitle(ppt,
                image.altText().isBlank() ? "图片" : image.altText());
        XSLFPictureData pd = ppt.addPicture(image.data(), pictureType(image.mediaType()));
        XSLFPictureShape shape = slide.createPicture(pd);
        int w = image.widthPx() > 0 ? image.widthPx() : 480;
        int h = image.heightPx() > 0 ? image.heightPx() : 320;
        shape.setAnchor(new Rectangle(60, BODY_TOP, w, h));
    }

    // ============ 辅助方法 ============

    private void fillCell(XSLFTableCell cell, String text, boolean bold) {
        XSLFTextParagraph p = cell.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text);
        r.setBold(bold);
        r.setFontSize(bold ? 14d : 12d);
        cell.setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private String plainText(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        for (Run run : runs) {
            sb.append(run.text());
        }
        return sb.toString();
    }

    private double ptWidth(XMLSlideShow ppt) {
        return ppt.getPageSize().getWidth();
    }

    private PictureData.PictureType pictureType(String mediaType) {
        return switch (mediaType == null ? "" : mediaType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> PictureData.PictureType.JPEG;
            case "image/gif" -> PictureData.PictureType.GIF;
            case "image/bmp" -> PictureData.PictureType.BMP;
            default -> PictureData.PictureType.PNG;
        };
    }

    /**
     * 幻灯片游标——维护"当前正在填充的幻灯片"与"下一条正文的纵向位置"，
     * 封装 PPT"按标题切页、正文逐条追加"的分页逻辑。
     */
    private static final class SlideCursor {
        private XSLFSlide current;
        private int bodyTop = BODY_TOP;
        private final List<XSLFTextBox> bodyBoxes = new ArrayList<>();

        SlideCursor(XMLSlideShow ppt) {
            // 首张内容页（在封面之后），无标题占位
            this.current = ppt.createSlide();
            this.bodyTop = BODY_TOP;
        }

        /** 开一张新幻灯片并写入页标题，返回新幻灯片。 */
        XSLFSlide newSlideWithTitle(XMLSlideShow ppt, String title) {
            current = ppt.createSlide();
            bodyTop = BODY_TOP;
            bodyBoxes.clear();
            XSLFTextBox titleBox = current.createTextBox();
            titleBox.setAnchor(new Rectangle2D.Double(
                    40, 20, ppt.getPageSize().getWidth() - 80, 60));
            XSLFTextParagraph tp = titleBox.addNewTextParagraph();
            XSLFTextRun tr = tp.addNewTextRun();
            tr.setText(title);
            tr.setFontSize(28d);
            tr.setBold(true);
            return current;
        }

        /** 向当前幻灯片正文追加一条带缩进的项目符号文本。 */
        void appendBullet(XMLSlideShow ppt, String text, int indent) {
            XSLFTextBox box = current.createTextBox();
            box.setAnchor(new Rectangle2D.Double(
                    BODY_LEFT + indent * 24, bodyTop,
                    ppt.getPageSize().getWidth() - 80 - indent * 24, 30));
            XSLFTextParagraph p = box.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText(text);
            r.setFontSize(indent > 0 ? 16d : 18d);
            bodyBoxes.add(box);
            bodyTop += 34;
        }

        /** 显式分页：下一个块从新页开始（这里简单地把下一条正文顶回顶部前置新页由标题触发）。 */
        void forcePageBreak() {
            bodyTop = BODY_HEIGHT_LIMIT;
        }

        private static final int BODY_HEIGHT_LIMIT = 500;
    }
}