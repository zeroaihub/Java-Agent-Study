package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParserRegistry;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.ParsingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter.NullOcrEngine;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.io.ByteArrayOutputStream;

/**
 * 第 4 章 Demo：knowledge-parser 多格式解析。
 *
 * <p>零依赖可运行：{@code java ... Chapter04Demo}。</p>
 *
 * <p>本 Demo 先用 POI / PDFBox <b>凭空生成</b> docx / xlsx / pptx / pdf 四种真实文件，
 * 再用我们写的解析器把它们解析回来，完整闭环验证「解析链路真的能跑通」，
 * 而不是对着一段假文本空谈。</p>
 *
 * @author ZeroAi
 */
public class Chapter04Demo {

    public static void main(String[] args) throws Exception {
        section("第 4 章 · knowledge-parser 多格式解析");

        ParsingService parsing = new ParsingService(ParserRegistry.defaults(new NullOcrEngine()));
        System.out.println("已注册格式: " + ParserRegistry.defaults(new NullOcrEngine()).supportedFormats());

        // ---------- 1. Markdown ----------
        step("1. Markdown（保留 # 标记，供结构化切分使用）");
        show(parsing.parseBytes(DocumentFormat.MD, """
                # 差旅报销制度
                ## 适用范围
                本制度适用于全体正式员工。
                ## 报销标准
                总监级别每晚上限 800 元。
                """.getBytes()));

        // ---------- 2. HTML ----------
        step("2. HTML（剔除 script/style，抽 title）");
        show(parsing.parseBytes(DocumentFormat.HTML, """
                <html><head><title>年假政策</title>
                <style>body{color:red}</style>
                <script>var a=1;</script></head>
                <body><h1>年假政策</h1><p>入职满一年可享 10 天年假。</p>
                <!-- 内部注释不入库 --></body></html>
                """.getBytes()));

        // ---------- 3. Word ----------
        step("3. Word（POI 生成 → 解析，含标题样式与表格）");
        show(parsing.parseBytes(DocumentFormat.DOCX, buildDocx()));

        // ---------- 4. Excel ----------
        step("4. Excel（行序列化为「列名=值」，保证语义可被检索命中）");
        show(parsing.parseBytes(DocumentFormat.XLSX, buildXlsx()));

        // ---------- 5. PPT ----------
        step("5. PPT（按幻灯片分页，保留页码便于溯源）");
        show(parsing.parseBytes(DocumentFormat.PPTX, buildPptx()));

        // ---------- 6. PDF ----------
        step("6. PDF（PDFBox 生成 → 抽取文本层）");
        show(parsing.parseBytes(DocumentFormat.PDF, buildPdf()));

        // ---------- 7. 图片 / OCR ----------
        step("7. 图片（无 OCR 时必须显式失败，不能静默产出空文档）");
        try {
            parsing.parseBytes(DocumentFormat.IMAGE, new byte[]{1, 2, 3});
            System.out.println("  ✗ 竟然解析成功了");
        } catch (KnowledgeException ex) {
            System.out.println("  ✓ 已拦截 " + ex.getErrorCode().code() + ": " + ex.getMessage());
        }

        // ---------- 8. 不支持的格式 ----------
        step("8. 未注册格式");
        try {
            parsing.parseBytes(DocumentFormat.UNKNOWN, "x".getBytes());
        } catch (KnowledgeException ex) {
            System.out.println("  ✓ 已拦截 " + ex.getErrorCode().code() + ": " + ex.getMessage());
        }

        System.out.println("\n第 4 章 Demo 结束。");
    }

    private static void show(ParsedDocument parsed) {
        System.out.println("  字符数=" + parsed.charCount() + " 页数=" + parsed.pageCount()
                + " OCR=" + parsed.ocrApplied());
        System.out.println("  元数据=" + parsed.metadata().asMap());
        System.out.println("  正文预览:");
        for (String line : parsed.text().split("\n")) {
            if (!line.isBlank()) {
                System.out.println("    | " + line);
            }
        }
    }

    // ---------------- 造文件 ----------------

    private static byte[] buildDocx() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph h1 = doc.createParagraph();
            h1.setStyle("Heading1");
            h1.createRun().setText("员工报销管理制度");

            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("本制度自 2026 年 1 月 1 日起执行。");

            XWPFParagraph h2 = doc.createParagraph();
            h2.setStyle("Heading2");
            h2.createRun().setText("住宿标准");

            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("级别");
            table.getRow(0).getCell(1).setText("每晚上限");
            table.getRow(1).getCell(0).setText("总监");
            table.getRow(1).getCell(1).setText("800");
            table.getRow(2).getCell(0).setText("经理");
            table.getRow(2).getCell(1).setText("500");

            doc.write(bos);
        }
        return bos.toByteArray();
    }

    private static byte[] buildXlsx() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("2026年销售数据");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("区域");
            header.createCell(1).setCellValue("季度");
            header.createCell(2).setCellValue("销售额");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("华东");
            r1.createCell(1).setCellValue("2026-Q1");
            r1.createCell(2).setCellValue(3980);

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("华南");
            r2.createCell(1).setCellValue("2026-Q1");
            r2.createCell(2).setCellValue(2560);

            wb.write(bos);
        }
        return bos.toByteArray();
    }

    private static byte[] buildPptx() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("ZeroHub 知识平台\n企业知识中台建设方案");

            XSLFSlide slide2 = ppt.createSlide();
            XSLFTextBox box2 = slide2.createTextBox();
            box2.setText("第一期目标：接入 PDF / Word / Excel / PPT 四类文档。");

            ppt.write(bos);
        }
        return bos.toByteArray();
    }

    private static byte[] buildPdf() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                // PDFBox 3.x 起，标准 14 字体改为 Standard14Fonts.FontName 枚举，
                // 不再提供 PDType1Font.HELVETICA 这类静态常量。
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(60, 720);
                cs.showText("ZeroHub Knowledge Platform");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(0, -24);
                cs.showText("Hybrid search = Vector + BM25 + Reranker.");
                cs.newLineAtOffset(0, -18);
                cs.showText("ACL filtering must happen before recall.");
                cs.endText();
            }
            doc.save(bos);
        }
        return bos.toByteArray();
    }

    private static void section(String title) {
        System.out.println("==================================================");
        System.out.println(title);
        System.out.println("==================================================");
    }

    private static void step(String text) {
        System.out.println("\n--- " + text + " ---");
    }
}
