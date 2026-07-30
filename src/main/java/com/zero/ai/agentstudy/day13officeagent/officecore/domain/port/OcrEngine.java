package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import java.util.List;

/**
 * OCR 识别引擎端口（OcrEngine）——入站数据感知阶段使用的出站端口。
 *
 * <p>感知阶段面对的输入未必是结构化的——可能是扫描版合同、拍照的发票、图片版报表。
 * OcrEngine 抽象"从图片提取文字"的能力，实现可以是本地 Tesseract、云 OCR 服务或
 * 多模态大模型。领域拿到识别后的文本，再走后续规划与生成。</p>
 *
 * @author zero
 */
public interface OcrEngine {

    /**
     * 识别图片中的文字。
     *
     * @param imageBytes 图片字节
     * @param mediaType  图片 MIME 类型
     * @return 识别结果
     */
    OcrResult recognize(byte[] imageBytes, String mediaType);

    /**
     * OCR 识别结果值对象。
     *
     * @param fullText   拼接后的整页文本
     * @param regions    分区块的识别结果（含坐标与置信度）
     * @author zero
     */
    record OcrResult(String fullText, List<TextRegion> regions) {

        public OcrResult {
            fullText = fullText == null ? "" : fullText;
            regions = regions == null ? List.of() : List.copyOf(regions);
        }
    }

    /**
     * 文本区块值对象。
     *
     * @param text       区块文本
     * @param confidence 置信度（0~1）
     * @param x          左上角 X 坐标
     * @param y          左上角 Y 坐标
     * @param width      宽度
     * @param height     高度
     * @author zero
     */
    record TextRegion(String text, double confidence, int x, int y, int width, int height) {
    }
}