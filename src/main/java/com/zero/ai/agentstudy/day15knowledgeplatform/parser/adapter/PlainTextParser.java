package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;

import java.nio.charset.StandardCharsets;

/**
 * 纯文本 / Markdown 解析器。
 *
 * <p>Markdown 不做任何「去语法」处理，原样保留标题、列表、代码块标记。
 * 这是刻意的：第 5 章的 {@code StructuredChunkStrategy} 正是靠 {@code #} 标题来切分，
 * 如果在这里就把语法抹平，结构化切分就无从下手。</p>
 *
 * <p><b>设计原则：解析层只负责「拿到文本」，不负责「理解语义」。
 * 格式标记是宝贵的结构信息，越晚丢掉越好。</b></p>
 *
 * @author ZeroAi
 */
public class PlainTextParser implements DocumentParser {

    private final DocumentFormat format;

    public PlainTextParser(DocumentFormat format) {
        if (format != DocumentFormat.TXT && format != DocumentFormat.MD) {
            throw new IllegalArgumentException("PlainTextParser 仅支持 TXT/MD，实际: " + format);
        }
        this.format = format;
    }

    @Override
    public DocumentFormat supportedFormat() {
        return format;
    }

    @Override
    public ParsedDocument parse(byte[] content, Metadata source) {
        String text = new String(content, StandardCharsets.UTF_8);
        Metadata enriched = Metadata.of(java.util.Map.of(
                "parser", "plain-text",
                "charset", "UTF-8",
                "lineCount", countLines(text)));
        if (source != null) {
            enriched = source.merge(enriched);
        }
        return ParsedDocument.of(text, -1, false, enriched);
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
