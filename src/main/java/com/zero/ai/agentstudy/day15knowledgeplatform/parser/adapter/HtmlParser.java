package com.zero.ai.agentstudy.day15knowledgeplatform.parser.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument;
import com.zero.ai.agentstudy.day15knowledgeplatform.parser.port.DocumentParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTML 解析器。
 *
 * <p>处理三件事：抽 {@code <title>}、剥标签、还原常见实体。
 * 脚本与样式必须优先剔除——否则切分出来的 Chunk 里会混进大段 CSS/JS，
 * 严重污染向量语义，这是网页采集场景最常见的坑。</p>
 *
 * <p>生产环境建议替换为 jsoup（解析更严谨、支持选择器），
 * 这里用正则是为了保持零新增依赖、且足够讲清「剥标签」这件事。</p>
 *
 * @author ZeroAi
 */
public class HtmlParser implements DocumentParser {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style\\b.*?</style>");
    private static final Pattern COMMENT = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|div|br|li|tr|h[1-6]|section)\\b[^>]*>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.HTML;
    }

    @Override
    public ParsedDocument parse(byte[] content, Metadata source) {
        String html = new String(content, StandardCharsets.UTF_8);

        String title = extractTitle(html);

        String text = html;
        text = SCRIPT.matcher(text).replaceAll(" ");
        text = STYLE.matcher(text).replaceAll(" ");
        text = COMMENT.matcher(text).replaceAll(" ");
        // 块级标签换成换行，避免整页粘成一坨
        text = BLOCK.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        text = BLANK_LINES.matcher(text).replaceAll("\n\n").trim();

        Metadata enriched = Metadata.of(Map.of("parser", "html"));
        if (title != null) {
            enriched = enriched.put("title", title);
        }
        if (source != null) {
            enriched = source.merge(enriched);
        }
        return ParsedDocument.of(text, -1, false, enriched);
    }

    private static String extractTitle(String html) {
        var m = TITLE.matcher(html);
        if (m.find()) {
            String t = m.group(1).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    private static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
