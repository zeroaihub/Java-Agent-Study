package com.zero.ai.agentstudy.day13officeagent.officepdf.adapter;

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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * PDF 渲染适配器（PdfRenderer）——{@link DocumentRenderer} 出站端口的 Apache PDFBox（3.x）实现。
 *
 * <p>PDF 与 Word/Excel/PPT 最大的不同在于：它没有"块流"或"表格模型"这类高层抽象，一切都要
 * 我们自己在画布上"排版"——手动维护光标 {@code cursorY}、计算行高、判断是否触底换页、按内容
 * 宽度折行。因此本适配器额外承担了一个"极简排版引擎"的角色。这恰好凸显了 IR 抽象的价值：
 * 无论底层多原始，上层拿到的始终是同一份与格式无关的 {@link DocumentIR}。</p>
 *
 * <p>注意：PDFBox 3.x 的 API 与 2.x 差异显著——字体通过 {@link Standard14Fonts.FontName} 构造，
 * {@code PDPageContentStream} 的 {@code showText} 对非 WinAnsi 字符（如中文）会抛异常，因此本
 * 实现在写文本前会做字符可编码性降级处理，保证在缺少 CJK 字体时也不至于整体渲染失败。</p>
 *
 * @author zero
 */
@Component
public class PdfRenderer implements DocumentRenderer {

    private static final float MARGIN = 50f;
    private static final float LEADING = 16f;
    private static final float BODY_FONT_SIZE = 11f;

    @Override
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    @Override
    public byte[] render(DocumentIR ir) {
        if (ir == null) {
            throw new IllegalArgumentException("待渲染的 DocumentIR 不能为 null");
        }
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            RenderContext ctx = new RenderContext(doc);
            ctx.newPage();

            for (Block block : ir.blocks()) {
                renderBlock(ctx, block);
            }
            ctx.close();

            doc.getDocumentInformation().setTitle(ir.metadata().title());
            doc.getDocumentInformation().setAuthor(ir.metadata().author());

            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("渲染 PDF 文档失败", e);
        }
    }

    /**
     * 对块类型做穷尽分派——Block 是 sealed，编译器保证覆盖所有子类型。
     */
    private void renderBlock(RenderContext ctx, Block block) {
        try {
            switch (block) {
                case HeadingBlock h -> renderHeading(ctx, h);
                case ParagraphBlock p -> renderParagraph(ctx, p);
                case ListBlock l -> renderList(ctx, l);
                case TableBlock t -> renderTable(ctx, t);
                case ChartBlock c -> renderChart(ctx, c);
                case ImageBlock i -> renderImage(ctx, i);
                case PageBreakBlock pb -> ctx.newPage();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("渲染 PDF 块失败：" + block.type(), e);
        }
    }

    /** 标题：按级别递减字号，加粗，前后留白。 */
    private void renderHeading(RenderContext ctx, HeadingBlock heading) throws IOException {
        float size = Math.max(12f, 22f - (heading.level() - 1) * 2f);
        ctx.ensureSpace(size + 8f);
        ctx.moveDown(6f);
        ctx.writeLine(heading.plainText(), size, true);
        ctx.moveDown(4f);
    }

    /** 段落：按行宽自动折行输出。 */
    private void renderParagraph(RenderContext ctx, ParagraphBlock paragraph) throws IOException {
        ctx.writeWrapped(plainText(paragraph.runs()), BODY_FONT_SIZE, false);
        ctx.moveDown(4f);
    }

    /** 列表：逐项输出，前缀符号 + 缩进。 */
    private void renderList(RenderContext ctx, ListBlock list) throws IOException {
        int index = 1;
        for (ListBlock.ListItem item : list.items()) {
            String prefix = list.ordered() ? (index++ + ". ") : "• ";
            String indent = "    ".repeat(item.indent());
            ctx.writeWrapped(indent + prefix + plainText(item.runs()), BODY_FONT_SIZE, false);
        }
        ctx.moveDown(4f);
    }

    /** 表格：以"制表符对齐的文本行"降级呈现（PDF 无原生表格模型）。 */
    private void renderTable(RenderContext ctx, TableBlock table) throws IOException {
        if (table.columnCount() == 0) {
            return;
        }
        if (!table.caption().isBlank()) {
            ctx.writeLine(table.caption(), BODY_FONT_SIZE, true);
        }
        ctx.writeLine(joinRow(table.header()), BODY_FONT_SIZE, true);
        for (List<TableBlock.TableCell> row : table.rows()) {
            ctx.writeWrapped(joinRow(row), BODY_FONT_SIZE, false);
        }
        ctx.moveDown(4f);
    }

    /** 图表：以"标题 + 数据要点"文本降级呈现。 */
    private void renderChart(RenderContext ctx, ChartBlock chart) throws IOException {
        ctx.writeLine("[图表] " + chart.title() + "（" + chart.chartType() + "）",
                BODY_FONT_SIZE, true);
        for (ChartBlock.Series s : chart.series()) {
            ctx.writeWrapped("  " + s.name() + ": " + s.values(), BODY_FONT_SIZE, false);
        }
        ctx.moveDown(4f);
    }

    /** 图片：解码为 PDImageXObject 并按尺寸绘制，触底自动换页。 */
    private void renderImage(RenderContext ctx, ImageBlock image) throws IOException {
        if (image.size() == 0) {
            return;
        }
        BufferedImage bimg = ImageIO.read(new ByteArrayInputStream(image.data()));
        if (bimg == null) {
            ctx.writeWrapped("[图片无法解码：" + image.altText() + "]", BODY_FONT_SIZE, false);
            return;
        }
        PDImageXObject pdImg = LosslessFactory.createFromImage(ctx.doc, bimg);
        float w = image.widthPx() > 0 ? image.widthPx() : bimg.getWidth();
        float h = image.heightPx() > 0 ? image.heightPx() : bimg.getHeight();
        float maxW = PDRectangle.A4.getWidth() - 2 * MARGIN;
        if (w > maxW) {
            float scale = maxW / w;
            w = maxW;
            h = h * scale;
        }
        ctx.ensureSpace(h + 8f);
        ctx.drawImage(pdImg, w, h);
        ctx.moveDown(8f);
    }

    // ============ 辅助方法 ============

    private String plainText(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        for (Run run : runs) {
            sb.append(run.text());
        }
        return sb.toString();
    }

    private String joinRow(List<TableBlock.TableCell> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append("  |  ");
            }
            sb.append(plainText(cells.get(i).runs()));
        }
        return sb.toString();
    }

    /**
     * 极简排版上下文——封装"当前页、内容流、光标 Y 坐标、换页、折行、可编码性降级"，
     * 把 PDFBox 底层的手动画布操作收敛到一处，让 {@code renderXxx} 方法只关心语义。
     */
    private static final class RenderContext {
        private final PDDocument doc;
        private final PDType1Font font =
                new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font boldFont =
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPage page;
        private PDPageContentStream stream;
        private float cursorY;

        RenderContext(PDDocument doc) {
            this.doc = doc;
        }

        /** 新开一页并把光标复位到顶部。 */
        void newPage() throws IOException {
            close();
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            cursorY = page.getMediaBox().getHeight() - MARGIN;
        }

        /** 若剩余空间不足以容纳 need 高度，则换页。 */
        void ensureSpace(float need) throws IOException {
            if (cursorY - need < MARGIN) {
                newPage();
            }
        }

        /** 光标下移（增加已用高度）。 */
        void moveDown(float delta) {
            cursorY -= delta;
        }

        /** 写一整行文本（不折行），必要时换页。 */
        void writeLine(String text, float size, boolean bold) throws IOException {
            ensureSpace(LEADING);
            stream.beginText();
            stream.setFont(bold ? boldFont : font, size);
            stream.newLineAtOffset(MARGIN, cursorY);
            stream.showText(sanitize(text));
            stream.endText();
            cursorY -= LEADING;
        }

        /** 按可用宽度自动折行写文本。 */
        void writeWrapped(String text, float size, boolean bold) throws IOException {
            float maxWidth = page.getMediaBox().getWidth() - 2 * MARGIN;
            String safe = sanitize(text);
            PDType1Font f = bold ? boldFont : font;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < safe.length(); i++) {
                line.append(safe.charAt(i));
                float w = f.getStringWidth(line.toString()) / 1000 * size;
                if (w > maxWidth) {
                    String toWrite = line.substring(0, line.length() - 1);
                    emit(toWrite, f, size);
                    line = new StringBuilder().append(safe.charAt(i));
                }
            }
            if (line.length() > 0) {
                emit(line.toString(), f, size);
            }
        }

        private void emit(String text, PDType1Font f, float size) throws IOException {
            ensureSpace(LEADING);
            stream.beginText();
            stream.setFont(f, size);
            stream.newLineAtOffset(MARGIN, cursorY);
            stream.showText(text);
            stream.endText();
            cursorY -= LEADING;
        }

        /** 在当前光标处绘制图片。 */
        void drawImage(PDImageXObject img, float w, float h) throws IOException {
            cursorY -= h;
            stream.drawImage(img, MARGIN, cursorY, w, h);
        }

        /**
         * 可编码性降级：Standard14 字体（WinAnsi 编码）无法编码中文等字符，
         * 遇到不可编码字符时替换为 '?'，避免 showText 抛异常导致整篇渲染失败。
         * 生产中应改为加载真实 CJK 字体（PDType0Font.load）。
         */
        private String sanitize(String text) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                sb.append(c < 128 ? c : '?');
            }
            return sb.toString();
        }

        /** 关闭当前内容流。 */
        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}