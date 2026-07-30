package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

import java.util.List;

/**
 * 表格块（TableBlock）。
 *
 * <p>承载二维结构化数据——这是"从 Excel 读数据 → 写进周报/PDF"这条链路的核心载体。
 * 表格由一行表头（{@code header}）与若干数据行（{@code rows}）组成，每个单元格是一个
 * {@link TableCell}，单元格内容同样以 {@link Run} 富文本表达，从而支持"负增长单元格标红"
 * 这类业务高亮诉求。</p>
 *
 * <p>约束：所有数据行的列数必须与表头列数一致，构造时即校验，避免残缺表格流入渲染阶段
 * 才在 POI/PDFBox 层抛出难以定位的异常。</p>
 *
 * @param header  表头单元格列表，永不为 {@code null}
 * @param rows    数据行列表，每行是一个单元格列表
 * @param caption 表格标题/说明，可为空字符串
 * @author zero
 */
public record TableBlock(List<TableCell> header, List<List<TableCell>> rows, String caption)
        implements Block {

    public TableBlock {
        header = header == null ? List.of() : List.copyOf(header);
        rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
        caption = caption == null ? "" : caption;
        int columnCount = header.size();
    for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).size() != columnCount) {
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 行列数(" + rows.get(i).size()
                                + ")与表头列数(" + columnCount + ")不一致");
            }
        }
    }

    @Override
    public BlockType type() {
        return BlockType.TABLE;
    }

    /** 列数（以表头为准）。 */
    public int columnCount() {
        return header.size();
    }

    /** 数据行数（不含表头）。 */
    public int rowCount() {
        return rows.size();
    }

    /**
     * 表格单元格值对象。
     *
     * @param runs     单元格富文本内容
     * @param colspan  跨列数，默认 1
     * @author zero
     */
    public record TableCell(List<Run> runs, int colspan) {

        public TableCell {
            runs = runs == null ? List.of() : List.copyOf(runs);
            colspan = Math.max(1, colspan);
        }

        /** 快捷工厂：纯文本单元格。 */
        public static TableCell of(String text) {
            return new TableCell(List.of(Run.of(text)), 1);
        }
    }
}