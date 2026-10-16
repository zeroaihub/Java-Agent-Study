package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.OcrEngine;

/**
 * 空 OCR 适配器（Null Object 模式）。
 *
 * <p>默认实现：明确告知「未接入 OCR 服务」，而不是返回空串让上游误以为解析成功。</p>
 *
 * <p><b>为什么不用 null？</b> 如果 {@code OcrEngine} 缺失时返回 {@code null}，
 * 每个调用点都要写 {@code if (ocr != null)}，一旦漏写就是 NPE。
 * Null Object 把「没有这个能力」建模成一个<b>行为明确的对象</b>，调用方无需判空。</p>
 *
 * <p>接入真实 OCR 时（腾讯云 / 阿里云 / PaddleOCR），实现 {@link OcrEngine} 替换本类即可。</p>
 *
 * @author ZeroAi
 */
public class NullOcrEngine implements OcrEngine {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String recognize(byte[] imageBytes) {
        throw KnowledgeException.of(KnowledgeErrorCode.OCR_FAILED,
                "未接入 OCR 服务：当前使用 NullOcrEngine。"
                        + "请实现 OcrEngine 接口接入腾讯云/阿里云/PaddleOCR 后重试。");
    }
}
