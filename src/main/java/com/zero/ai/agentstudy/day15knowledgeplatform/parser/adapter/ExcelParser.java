package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Excel（.xlsx）解析器（Apache POI）。
 *
 * <p>Excel 的难点不是「读出来」，而是<b>读出来之后还能被语义检索命中</b>。</p>
 *
 * <p>如果直接把单元格丢进向量库，会得到一堆孤立的数字：{@code 3980}、{@code 2025-Q1}……
 * 用户问「一季度华东区销售额是多少」，向量根本匹配不上——因为它不知道
 * 这串数字属于「销售额」、属于「华东区」、属于「一季度」。</p>
 *
 * <p>因此本类做<b>行序列化</b>：把一行拼成 {@code 列名=值} 的完整句子，
 * 让每个 Chunk 自带语义上下文。这是表格类知识能否被检索命中的关键。
 * 进阶做法（本项目未展开）是为每行生成一句自然语言摘要再入库。</p>
 *
 * <p>另一个必用细节：数值必须走 {@link DataFormatter} 按「显示值」读取，
 * 直接 {@code getNumericCellValue()} 会读出 {@code 3980.0} 这种原始值，
 * 与用户看到的 {@code 3,980} 不一致。</p>
 *
 * @author ZeroAi
 */
public class ExcelParser implements DocumentParser {

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.XLSX;
    }

    @Override
    public ParsedDocument parse(byte[] content, Metadata source) {
        StringBuilder sb = new StringBuilder();
        int sheetCount = 0;
        int rowCount = 0;

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            for (Sheet sheet : workbook) {
                sheetCount++;
                String sheetName = sheet.getSheetName();
                sb.append("\n## 工作表：").append(sheetName).append('\n');

                java.util.List<String> headers = new java.util.ArrayList<>();
                boolean headerResolved = false;

                for (Row row : sheet) {
                    if (isBlankRow(row)) {
                        continue;
                    }
                    if (!headerResolved) {
                        headers = readRow(row);
                        headerResolved = true;
                        sb.append("列名：").append(String.join(" | ", headers)).append('\n');
                        continue;
                    }
                    java.util.List<String> values = readRow(row);
                    sb.append(serializeRow(sheetName, headers, values)).append('\n');
                    rowCount++;
                }
            }
        } catch (Exception ex) {
            throw KnowledgeException.wrap(KnowledgeErrorCode.PARSE_FAILED, ex);
        }

        Metadata enriched = Metadata.of(Map.of(
                "parser", "poi-xssf",
                "sheetCount", sheetCount,
                "rowCount", rowCount));
        if (source != null) {
            enriched = source.merge(enriched);
        }
        return ParsedDocument.of(sb.toString().trim(), sheetCount, false, enriched);
    }

    /**
     * 把一行拼成带列名的句子：{@code [工作表] 列1=值1；列2=值2；...}
     */
    private static String serializeRow(String sheetName,
                                       java.util.List<String> headers,
                                       java.util.List<String> values) {
        StringBuilder sb = new StringBuilder("[").append(sheetName).append("] ");
        for (int i = 0; i < values.size(); i++) {
            String col = i < headers.size() && !headers.get(i).isBlank()
                    ? headers.get(i)
                    : "第" + (i + 1) + "列";
            String val = values.get(i);
            if (val.isBlank()) {
                continue;
            }
            if (sb.length() > sheetName.length() + 3) {
                sb.append("；");
            }
            sb.append(col).append('=').append(val);
        }
        return sb.toString();
    }

    private static java.util.List<String> readRow(Row row) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            out.add(cell == null ? "" : FORMATTER.formatCellValue(cell).trim());
        }
        return out;
    }

    private static boolean isBlankRow(Row row) {
        if (row == null) {
            return true;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && !FORMATTER.formatCellValue(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
