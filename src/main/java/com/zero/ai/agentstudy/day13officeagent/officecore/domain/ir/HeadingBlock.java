package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

import java.util.List;

/**
 * 标题块（HeadingBlock）。
 *
 * <p>表达一个多级标题（H1~H6）。标题的富文本内容以 {@link Run} 列表承载，
 * 从而支持"标题中局部样式不同"的场景（例如标题里嵌入一个不同颜色的关键词）。
 * {@code level} 决定渲染时的层级——在 Word 中映射为内置标题样式，在 Markdown 中
 * 映射为 {@code #} 数量，在 PPT 中映射为标题占位符层级，是"目录/大纲"能自动生成的基础。</p>
 *
 * @param level 标题级别，取值 1~6
 * @param runs  标题的富文本片段，永不为 {@code null}
 * @author zero
 */
public record HeadingBlock(int level, List<Run> runs) implements Block {

    public HeadingBlock {
        if (level < 1 || level > 6) {
            throw new IllegalArgumentException("标题级别必须在 1~6 之间，实际为：" + level);
        }
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    /** 快捷工厂：由纯文本构造一个标题块。 */
    public static HeadingBlock of(int level, String text) {
        return new HeadingBlock(level, List.of(Run.of(text)));
    }

    @Override
    public BlockType type() {
        return BlockType.HEADING;
    }

    /** 拼接所有 Run 的纯文本，便于生成目录或做全文检索。 */
    public String plainText() {
        return runs.stream().map(Run::text).reduce("", String::concat);
    }
}