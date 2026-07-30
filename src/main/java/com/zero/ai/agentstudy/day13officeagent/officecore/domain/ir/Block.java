package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

/**
 * 文档块（Block）密封接口——Document IR 的核心抽象。
 *
 * <p>一份文档在中间表示（IR）层被建模为"块的有序列表"。每个块表达一个独立的
 * 语义单元：标题、段落、表格、列表、图表、图片、分页符。块只描述"内容与结构"，
 * 不关心"最终渲染成 Word 还是 PDF"——渲染由适配层的 {@code DocumentRenderer} 负责。
 * 这正是"内容与格式解耦"的落地点。</p>
 *
 * <p>采用 Java 21 密封接口（sealed interface），把"合法的块类型"约束在编译期，
 * 使渲染器能用 {@code switch} 模式匹配做穷尽处理，新增块类型时编译器会强制所有渲染器补全分支。</p>
 *
 * @author zero
 */
public sealed interface Block
        permits HeadingBlock, ParagraphBlock, TableBlock, ListBlock,
                ChartBlock, ImageBlock, PageBreakBlock {

    /**
     * 块类型判别标签，便于日志、序列化与调试。
     *
     * @return 块类型枚举
     */
    BlockType type();

    /**
     * 块类型枚举，与各具体块一一对应。
     */
    enum BlockType {
        HEADING,
        PARAGRAPH,
        TABLE,
        LIST,
        CHART,
        IMAGE,
        PAGE_BREAK
    }
}