package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

import java.util.List;

/**
 * 段落块（ParagraphBlock）。
 *
 * <p>文档中最常见的正文单元。段落由若干 {@link Run} 组成，实现"同一段落内混排不同样式"；
 * {@code alignment} 控制整段的对齐方式。段落是"文档主体信息"的主要载体——周报里的
 * 结论描述、纪要里的讨论要点，都会落到 ParagraphBlock 上。</p>
 *
 * @param runs      段落的富文本片段列表，永不为 {@code null}
 * @param alignment 段落对齐方式，永不为 {@code null}
 * @author zero
 */
public record ParagraphBlock(List<Run> runs, Alignment alignment) implements Block {

    public ParagraphBlock {
        runs = runs == null ? List.of() : List.copyOf(runs);
        alignment = alignment == null ? Alignment.LEFT : alignment;
    }

    /** 快捷工厂：左对齐纯文本段落。 */
    public static ParagraphBlock of(String text) {
        return new ParagraphBlock(List.of(Run.of(text)), Alignment.LEFT);
    }

    /** 快捷工厂：由多个 Run 构造左对齐段落。 */
    public static ParagraphBlock of(List<Run> runs) {
        return new ParagraphBlock(runs, Alignment.LEFT);
    }

    @Override
    public BlockType type() {
        return BlockType.PARAGRAPH;
    }

    /** 拼接所有 Run 的纯文本。 */
    public String plainText() {
        return runs.stream().map(Run::text).reduce("", String::concat);
    }

    /**
     * 段落对齐方式枚举。
     */
    public enum Alignment {
        /** 左对齐（默认）。 */
        LEFT,
        /** 居中。 */
        CENTER,
        /** 右对齐。 */
        RIGHT,
        /** 两端对齐。 */
        JUSTIFY
    }
}