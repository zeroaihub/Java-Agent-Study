package com.zero.ai.agentstudy.day15knowledgeplatform.document.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.util.Locale;

/**
 * 文档格式。
 *
 * <p>企业里真正占大头的从来不是 TXT/MD，而是 PDF 与 Office 三件套。
 * Day05 的教学版 RAG 只吃纯文本，这是它与生产系统最大的差距之一；
 * Day15 的 parser 模块必须把这几种格式全部吃下。</p>
 *
 * @author ZeroAi
 */
public enum DocumentFormat {

    PDF("pdf", "application/pdf", false),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", false),
    MD("md", "text/markdown", true),
    HTML("html", "text/html", true),
    TXT("txt", "text/plain", true),
    /** 扫描件/截图，纯文本解析拿不到内容，必须走 OCR */
    IMAGE("image", "image/*", false),
    UNKNOWN("unknown", "application/octet-stream", false);

    private final String extension;
    private final String mimeType;
    /** 是否为纯文本格式（无需第三方解析库，直接读字节即可） */
    private final boolean plainText;

    DocumentFormat(String extension, String mimeType, boolean plainText) {
        this.extension = extension;
        this.mimeType = mimeType;
        this.plainText = plainText;
    }

    public String extension() {
        return extension;
    }

    public String mimeType() {
        return mimeType;
    }

    /**
     * 是否可直接按文本读取（无需 POI / PDFBox / OCR）。
     */
    public boolean isPlainText() {
        return plainText;
    }

    /**
     * 是否需要先做 OCR 才能拿到文本。
     */
    public boolean requiresOcr() {
        return this == IMAGE;
    }

    /**
     * 从文件名推断格式。
     *
     * @param filename 文件名，可带路径
     * @return 推断出的格式；无法识别时返回 {@link #UNKNOWN}，由调用方决定是否拒绝上传
     */
    public static DocumentFormat fromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return UNKNOWN;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return UNKNOWN;
        }
        String ext = lower.substring(dot + 1);
        for (DocumentFormat format : values()) {
            if (format.extension.equals(ext)) {
                return format;
            }
        }
        // 图片走统一 IMAGE 格式，由 OCR 引擎按具体类型处理
        if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("bmp") || ext.equals("gif") || ext.equals("tiff")) {
            return IMAGE;
        }
        return UNKNOWN;
    }

    /**
     * 严格解析：无法识别时抛出 {@link KnowledgeErrorCode#UNSUPPORTED_FILE_TYPE}。
     */
    public static DocumentFormat requireSupported(String filename) {
        DocumentFormat format = fromFilename(filename);
        if (format == UNKNOWN) {
            throw KnowledgeException.of(KnowledgeErrorCode.UNSUPPORTED_FILE_TYPE,
                    "不支持的文件类型: " + filename);
        }
        return format;
    }
}
