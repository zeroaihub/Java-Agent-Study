package com.zero.ai.agentstudy.day13officeagent.officeexcel.adapter;

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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Excel 渲染适配器（ExcelRenderer）——{@link DocumentRenderer} 出站端口的 Apache POI 实现。
 *
 * <p>把 {@link DocumentIR} 翻译成 .xlsx 工作簿。与 Word 渲染器的差别在于：Excel 是\"二维网格\"
 * 而非\"流式文档\"，因此渲染策略以<b>表格与图表数据</b>为一等公民——{@link TableBlock} 直接落成
 * 工作表的行列，{@link ChartBlock} 的类目/系列落成数据区（原生图表在 ch05 深化）；标题、段落、
 * 列表等\"叙述性块\"则降级为单列文本行，保证信息不丢失。</p>
 *
 * <p>为了让一份包含多张表的 IR 更易读，每遇到一个 TableBlock 就新建一张工作表（Sheet），
 * 其余叙述性内容统一写进名为 \"正文\" 的首个工作表。</p>
 *
 * @author zero
 */
@Component
public class ExcelRenderer implements DocumentRenderer {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.EXCEL;
    }

    @Override
    public byte[] render(DocumentIR ir) {
        if (ir == null) {
            throw new IllegalArgumentException("待渲染的 DocumentIR 不能为 null");
        }
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headerStyle = buildHeaderStyle(wb);
            Sheet narrative = wb.createSheet("正文");
            int[] narrativeRow = {0};
            int[] tableIndex = {1};

            for (Block block : ir.blocks()) {
                renderBlock(wb, narrative, narrativeRow, tableIndex, block, headerStyle);
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("渲染 Excel 文档失败", e);
        }
    }

    /**
     * 穷尽分派——Block 是 sealed，编译器保证覆盖所有子类型。
     */
    private void renderBlock(Workbook wb, Sheet narrative, int[] row, int[] tableIndex,
                             Block block, CellStyle headerStyle) {
        switch (block) {
            case TableBlock t -> renderTableSheet(wb, t, tableIndex, headerStyle);
            case HeadingBlock h -> writeNarrative(narrative, row, h.plainText(), true);
            case ParagraphBlock p -> writeNarrative(narrative, row, p.plainText(), false);
            case ListBlock l -> renderList(narrative, row, l);
            case ChartBlock c -> renderChartData(wb, c, tableIndex, headerStyle);
            case ImageBlock i -> writeNarrative(narrative, row, "[图片] " + i.altText(), false);
            case PageBreakBlock pb -> { /* Excel 无分页符语义，忽略 */ }
        }
    }

    /** 表格：每个 TableBlock 独占一张工作表，表头加粗，自动列宽。 */
    private void renderTableSheet(Workbook wb, TableBlock table, int[] tableIndex,
                                  CellStyle headerStyle) {
        if (table.columnCount() == 0) {
            return;
        }
        String name = table.caption().isBlank()
                ? "表格" + tableIndex[0] : sanitizeSheetName(table.caption());
        tableIndex[0]++;
        Sheet sheet = wb.createSheet(uniqueName(wb, name));

        Row headerRow = sheet.createRow(0);
        List<TableBlock.TableCell> header = table.header();
        for (int c = 0; c < header.size(); c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(plain(header.get(c).runs()));
            cell.setCellStyle(headerStyle);
        }

        int r = 1;
        for (List<TableBlock.TableCell> row : table.rows()) {
            Row poiRow = sheet.createRow(r++);
            for (int c = 0; c < row.size(); c++) {
                Cell cell = poiRow.createCell(c);
                setCellValue(cell, plain(row.get(c).runs()));
            }
        }
        for (int c = 0; c < header.size(); c++) {
            sheet.autoSizeColumn(c);
        }
    }

    /**
     * 图表数据：把类目与各系列落成一张\"数据表\"，首列为类目，其后每列一个系列。
     * 真正的原生图表绘制在 ch05 深化。
     */
    private void renderChartData(Workbook wb, ChartBlock chart, int[] tableIndex,
                                 CellStyle headerStyle) {
        String name = chart.title().isBlank()
                ? "图表数据" + tableIndex[0] : sanitizeSheetName(chart.title());
        tableIndex[0]++;
        Sheet sheet = wb.createSheet(uniqueName(wb, name));

        Row header = sheet.createRow(0);
        Cell corner = header.createCell(0);
        corner.setCellValue("类目");
        corner.setCellStyle(headerStyle);
        List<ChartBlock.Series> seriesList = chart.series();
        for (int s = 0; s < seriesList.size(); s++) {
            Cell cell = header.createCell(s + 1);
            cell.setCellValue(seriesList.get(s).name());
            cell.setCellStyle(headerStyle);
        }

        List<String> categories = chart.categories();
        for (int i = 0; i < categories.size(); i++) {
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(categories.get(i));
            for (int s = 0; s < seriesList.size(); s++) {
                List<Double> values = seriesList.get(s).values();
                if (i < values.size()) {
                    row.createCell(s + 1).setCellValue(values.get(i));
                }
            }
        }
        for (int c = 0; c <= seriesList.size(); c++) {
            sheet.autoSizeColumn(c);
        }
    }

    /** 列表：逐项写进正文工作表，有序列表加编号。 */
    private void renderList(Sheet narrative, int[] row, ListBlock list) {
        int index = 1;
        for (ListBlock.ListItem item : list.items()) {
            String prefix = list.ordered() ? (index++ + ". ") : "• ";
            writeNarrative(narrative, row, prefix + plain(item.runs()), false);
        }
    }

    // ============ 辅助方法 ============

    private void writeNarrative(Sheet sheet, int[] row, String text, boolean bold) {
        Row poiRow = sheet.createRow(row[0]++);
        Cell cell = poiRow.createCell(0);
        cell.setCellValue(text);
        if (bold) {
            Workbook wb = sheet.getWorkbook();
            CellStyle style = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            style.setFont(font);
            cell.setCellStyle(style);
        }
    }

    /** 数值形态的字符串写成数字单元格，否则写成文本，便于后续公式计算。 */
    private void setCellValue(Cell cell, String value) {
        try {
            cell.setCellValue(Double.parseDouble(value.replace(",", "").replace("%", "")));
        } catch (NumberFormatException e) {
            cell.setCellValue(value);
        }
    }

    private CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private String plain(List<Run> runs) {
        return runs.stream().map(Run::text).reduce("", String::concat);
    }

    /** Excel 工作表名不得含 : \ / ? * [ ] 且不超过 31 字符。 */
    private String sanitizeSheetName(String raw) {
        String cleaned = raw.replaceAll("[:\\\\/?*\\[\\]]", "_");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    /** 保证工作表名唯一。 */
    private String uniqueName(Workbook wb, String base) {
        String name = base;
        int suffix = 1;
        while (wb.getSheet(name) != null) {
            String tail = "_" + suffix++;
            int max = 31 - tail.length();
            name = (base.length() > max ? base.substring(0, max) : base) + tail;
        }
        return name;
    }
}