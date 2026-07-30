package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

/**
 * 图片块（ImageBlock）。
 *
 * <p>承载嵌入文档的图片——公司 Logo、产品截图、由 {@link ChartBlock} 预渲染成的图表图片，
 * 或 OCR 识别前的原始扫描图。为了让 IR 保持"可序列化、可跨进程传递"，图片以字节数组内联
 * 携带（{@code data}），而非依赖某个本地文件路径；同时保留 {@code mediaType} 以便渲染器
 * 正确写入图片头。{@code widthPx}/{@code heightPx} 为期望显示尺寸，0 表示按原始尺寸。</p>
 *
 * @param data      图片二进制数据
 * @param mediaType MIME 类型，如 {@code image/png}
 * @param altText   替代文本，用于无障碍与图片加载失败兜底
 * @param widthPx   期望显示宽度（像素），0 表示原始尺寸
 * @param heightPx  期望显示高度（像素），0 表示原始尺寸
 * @author zero
 */
public record ImageBlock(byte[] data, String mediaType, String altText,
                         int widthPx, int heightPx) implements Block {

    public ImageBlock {
        data = data == null ? new byte[0] : data.clone();
        mediaType = mediaType == null ? "image/png" : mediaType;
        altText = altText == null ? "" : altText;
        widthPx = Math.max(0, widthPx);
        heightPx = Math.max(0, heightPx);
    }

    /** 快捷工厂：按原始尺寸嵌入 PNG 图片。 */
    public static ImageBlock ofPng(byte[] data, String altText) {
        return new ImageBlock(data, "image/png", altText, 0, 0);
    }

    @Override
    public BlockType type() {
        return BlockType.IMAGE;
    }

    /** 防御性拷贝：对外暴露的字节数组副本。 */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /** 图片字节数。 */
    public int size() {
        return data.length;
    }
}