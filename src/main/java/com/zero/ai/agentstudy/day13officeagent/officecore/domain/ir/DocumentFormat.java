package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

/**
 * 文档目标格式枚举。
 *
 * <p>Document IR 与格式解耦的关键：同一份 {@link DocumentIR} 可以被渲染成本枚举中的任意一种格式。
 * 新增一种格式（如 HTML、CSV），只需在此新增枚举值并提供对应的 Renderer 适配器，
 * 领域核心与生成逻辑一行都不用改——这就是"内容与格式解耦"在类型层面的体现。</p>
 *
 * @author zero
 */
public enum DocumentFormat {

    /** Microsoft Word 文档（.docx），由 officeword 模块的 POI 适配器渲染。 */
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),

    /** Microsoft Excel 工作簿（.xlsx），由 officeexcel 模块的 POI 适配器渲染。 */
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    /** Microsoft PowerPoint 演示文稿（.pptx），由 officeppt 模块的 POI XSLF 适配器渲染。 */
    PPT("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),

    /** PDF 文档（.pdf），由 officepdf 模块的 PDFBox 适配器渲染。 */
    PDF("pdf", "application/pdf"),

    /** Markdown 文本（.md），由 officecore 内置的纯文本渲染器渲染。 */
    MARKDOWN("md", "text/markdown");

    private final String extension;
    private final String mimeType;

    DocumentFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    /** 文件扩展名（不含点），用于生成文件名。 */
    public String extension() {
        return extension;
    }

    /** MIME 类型，用于 HTTP 响应头与邮件附件声明。 */
    public String mimeType() {
        return mimeType;
    }
}