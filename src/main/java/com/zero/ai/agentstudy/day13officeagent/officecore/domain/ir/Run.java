package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

/**
 * 富文本运行片段（Run）值对象。
 *
 * <p>Run 是"一段样式一致的文字"，是 Word / PPT 富文本模型的最小单元。
 * 一个段落（{@link ParagraphBlock}）由若干 Run 组成，从而支持"段落内局部加粗、局部标红"
 * 这类办公文档刚需。例如："本季度增长 <b>32%</b>，超额完成" 会被拆成三个 Run。</p>
 *
 * @param text  文本内容（不含样式）
 * @param style 该片段的文本样式，永不为 {@code null}
 * @author zero
 */
public record Run(String text, TextStyle style) {

    public Run {
        if (text == null) {
            text = "";
        }
        if (style == null) {
            style = TextStyle.normal();
        }
    }

    /** 快捷工厂：普通样式的纯文本 Run。 */
    public static Run of(String text) {
        return new Run(text, TextStyle.normal());
    }

    /** 快捷工厂：加粗强调的 Run。 */
    public static Run strong(String text) {
        return new Run(text, TextStyle.strong());
    }
}