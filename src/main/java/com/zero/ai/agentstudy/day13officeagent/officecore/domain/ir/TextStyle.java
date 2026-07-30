package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

/**
 * 文本样式值对象（Value Object）。
 *
 * <p>描述一段 {@link Run} 的视觉样式：加粗、斜体、下划线、字号、颜色。
 * 它是格式无关的——渲染器（Word/PPT/PDF）各自把这些抽象样式翻译成自己底层 API 的具体设置。</p>
 *
 * <p>设计为不可变 record，并提供若干工厂方法与"链式派生"方法，方便生成层快速构造样式，
 * 例如 {@code TextStyle.normal().withBold().color("FF0000")} 表示"加粗 + 红色"。</p>
 *
 * @param bold      是否加粗
 * @param italic    是否斜体
 * @param underline 是否下划线
 * @param fontSize  字号（磅，pt）；{@code null} 表示使用渲染器/模板默认字号
 * @param colorHex  颜色（6 位十六进制 RGB，不含 #，如 "FF0000"）；{@code null} 表示默认色
 * @author zero
 */
public record TextStyle(
        boolean bold,
        boolean italic,
        boolean underline,
        Integer fontSize,
        String colorHex
) {

    /** 默认普通样式：不加粗、不斜体、无下划线、默认字号与颜色。 */
    public static TextStyle normal() {
        return new TextStyle(false, false, false, null, null);
    }

    /** 强调样式：加粗。常用于关键指标、结论。 */
    public static TextStyle strong() {
        return new TextStyle(true, false, false, null, null);
    }

    /** 派生：在当前样式基础上开启加粗。 */
    public TextStyle withBold() {
        return new TextStyle(true, italic, underline, fontSize, colorHex);
    }

    /** 派生：在当前样式基础上开启斜体。 */
    public TextStyle withItalic() {
        return new TextStyle(bold, true, underline, fontSize, colorHex);
    }

    /** 派生：在当前样式基础上开启下划线。 */
    public TextStyle withUnderline() {
        return new TextStyle(bold, italic, true, fontSize, colorHex);
    }

    /** 派生：设置字号（磅）。 */
    public TextStyle size(int pt) {
        return new TextStyle(bold, italic, underline, pt, colorHex);
    }

    /** 派生：设置颜色（6 位十六进制 RGB，不含 #）。 */
    public TextStyle color(String hex) {
        return new TextStyle(bold, italic, underline, fontSize, hex);
    }
}