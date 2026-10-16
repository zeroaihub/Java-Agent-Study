package com.zero.ai.agentstudy.day15knowledgeplatform.parser.port;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;

/**
 * 文档解析器端口（策略接口）。
 *
 * <p>每支持一种格式，就新增一个实现类并声明 {@link #supportedFormat()}，
 * {@code ParserRegistry} 会自动把它纳入路由。新增格式<b>不需要修改任何已有代码</b>——
 * 这就是策略模式对开闭原则的兑现。</p>
 *
 * <p>为什么用「一个解析器只负责一种格式」而不是一个大 if-else？
 * 因为格式解析天然是易变的：PDFBox 升级要改、POI 换 API 要改、OCR 换供应商要改。
 * 把它们隔离在各自的适配器里，任何一处变化都不会波及别处。</p>
 *
 * @author ZeroAi
 */
public interface DocumentParser {

    /**
     * 本解析器支持的格式。
     */
    DocumentFormat supportedFormat();

    /**
     * 解析文档内容。
     *
     * @param content 原始字节
     * @param source 文档已有元数据（用于富化解析结果）
     * @return 解析结果，保证文本非空
     */
    ParsedDocument parse(byte[] content, Metadata source);
}
