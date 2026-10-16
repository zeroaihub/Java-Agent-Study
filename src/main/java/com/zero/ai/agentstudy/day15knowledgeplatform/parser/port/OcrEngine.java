package com.zero.ai.agentstudy.day15knowledgeplatform.parser.port;

/**
 * OCR 引擎端口（出站端口）。
 *
 * <p>扫描件、截图、图片型 PDF 里没有文本层，PDFBox/POI 抽出来会是空串。
 * 这类文档必须走 OCR 才能变成可检索的知识。</p>
 *
 * <p>为什么抽成端口而不是直接调某家云的 SDK？因为 OCR 供应商的选择高度易变：
 * 今天用腾讯云、明天换阿里云、后天要做私有化部署用 PaddleOCR。
 * 抽成端口后，换供应商只是换一个适配器实现，领域代码不动。</p>
 *
 * @author ZeroAi
 */
public interface OcrEngine {

    /**
     * 是否可用（未配置密钥 / 未部署服务时应返回 false）。
     */
    boolean available();

    /**
     * 识别图片中的文字。
     *
     * @param imageBytes 图片原始字节
     * @return 识别出的文本
     */
    String recognize(byte[] imageBytes);
}
