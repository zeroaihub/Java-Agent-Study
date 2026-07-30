package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

import java.util.List;

/**
 * 图表块（ChartBlock）。
 *
 * <p>把结构化数据以可视化图表呈现——"根据销售数据生成周报并制作 PPT"这个终极场景里，
 * 销售趋势折线图、区域占比饼图就落在 ChartBlock 上。图表块只描述"数据与图表语义"
 * （类型、类目、系列），不绑定任何具体绘图库；渲染阶段由适配器决定用 POI 原生图表、
 * JFreeChart 生成图片，还是交给前端 ECharts。这是"内容与格式解耦"在可视化维度的体现。</p>
 *
 * @param chartType  图表类型
 * @param title      图表标题
 * @param categories 类目轴标签（如月份、区域）
 * @param series     数据系列列表
 * @author zero
 */
public record ChartBlock(ChartType chartType, String title,
                         List<String> categories, List<Series> series) implements Block {

    public ChartBlock {
        chartType = chartType == null ? ChartType.BAR : chartType;
        title = title == null ? "" : title;
        categories = categories == null ? List.of() : List.copyOf(categories);
        series = series == null ? List.of() : List.copyOf(series);
    }

    @Override
    public BlockType type() {
        return BlockType.CHART;
    }

    /**
     * 图表类型枚举。
     */
    public enum ChartType {
        /** 柱状图。 */
        BAR,
        /** 折线图。 */
        LINE,
        /** 饼图。 */
        PIE,
        /** 面积图。 */
        AREA,
        /** 散点图。 */
        SCATTER
    }

    /**
     * 数据系列值对象。
     *
     * <p>一个系列代表一条曲线或一组柱子，其 {@code values} 应与外层 {@code categories}
 * 一一对应。例如系列"华东区"对应各月份的销售额。</p>
     *
     * @param name   系列名称
     * @param values 系列数值，与类目一一对应
     * @author zero
     */
    public record Series(String name, List<Double> values) {

        public Series {
            name = name == null ? "" : name;
            values = values == null ? List.of() : List.copyOf(values);
        }

        /** 快捷工厂。 */
        public static Series of(String name, List<Double> values) {
            return new Series(name, values);
        }
    }
}