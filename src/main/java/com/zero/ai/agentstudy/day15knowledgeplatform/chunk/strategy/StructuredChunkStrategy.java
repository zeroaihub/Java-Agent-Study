package com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.port.ChunkStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化切分策略（按标题层级切）。
 *
 * <p>企业文档（制度、手册、Wiki、API 文档）天然有标题层级。
 * 按标题切分是<b>召回质量最高</b>的策略，原因有二：</p>
 * <ol>
 *     <li><b>语义完整</b>：一个二级标题下的内容讲的是同一件事，不会像定长切分那样把两个主题混在一片。</li>
 *     <li><b>可携带章节路径</b>：每片能带上「3.2 报销标准 > 差旅」这样的路径，
 *         检索命中后用户一眼就知道答案出自哪一节——这是「可溯源」的基础。</li>
 * </ol>
 *
 * <p>为什么结构化切分值得单独做？因为第 4 章的 Word 解析器把标题样式转成了 {@code #} 标记、
 * Markdown 天然带 {@code #}，这两条链路在本策略汇合，
 * 让 Word / Markdown / HTML 文档都能享受结构化切分。</p>
 *
 * <p><b>兜底</b>：某节过长（超过 maxChunkSize）时，内部回退为定长切分，
 * 避免一个巨大的 Chunk 撑爆上下文。</p>
 *
 * @author ZeroAi
 */
public class StructuredChunkStrategy implements ChunkStrategy {

    private static final Pattern HEADING =
            Pattern.compile("(?m)^\\s{0,3}(#{1,6})\\s+(.*)$");

    private final int maxChunkSize;
    private final int overlap;

    public StructuredChunkStrategy() {
        this(800, 50);
    }

    public StructuredChunkStrategy(int maxChunkSize, int overlap) {
        this.maxChunkSize = maxChunkSize;
        this.overlap = overlap;
    }

    @Override
    public String name() {
        return "structured(max=" + maxChunkSize + ")";
    }

    @Override
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        Matcher matcher = HEADING.matcher(text);
        List<int[]> headingPositions = new ArrayList<>();
        List<String> headingTitles = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();

        while (matcher.find()) {
            headingPositions.add(new int[]{matcher.start(), matcher.end()});
            headingLevels.add(matcher.group(1).length());
            headingTitles.add(matcher.group(2).trim());
        }

        // 没有标题 → 退化成语义切分，至少不会切出垃圾
        if (headingPositions.isEmpty()) {
            return new SemanticChunkStrategy(maxChunkSize, 80).split(text);
        }

        // 标题之前的内容（前言/摘要）单独成片
        String preamble = text.substring(0, headingPositions.get(0)[0]).trim();
        if (!preamble.isEmpty()) {
            addChunk(chunks, preamble, "前言");
        }

        for (int i = 0; i < headingPositions.size(); i++) {
            int sectionStart = headingPositions.get(i)[0];
            int sectionEnd = (i + 1 < headingPositions.size())
                    ? headingPositions.get(i + 1)[0]
                    : text.length();
            String section = text.substring(sectionStart, sectionEnd).trim();
            String path = buildPath(headingTitles, headingLevels, i);
            addChunk(chunks, section, path);
        }
        return chunks;
    }

    /**
     * 构造章节路径，例如「第一章 > 3.2 报销标准」。
     */
    private static String buildPath(List<String> titles, List<Integer> levels, int index) {
        StringBuilder path = new StringBuilder();
        int currentLevel = levels.get(index);
        List<String> stack = new ArrayList<>();
        stack.add(titles.get(index));
        for (int j = index - 1; j >= 0; j--) {
            if (levels.get(j) < currentLevel) {
                stack.add(0, titles.get(j));
                currentLevel = levels.get(j);
            }
        }
        for (int k = 0; k < stack.size(); k++) {
            if (k > 0) {
                path.append(" > ");
            }
            path.append(stack.get(k));
        }
        return path.toString();
    }

    private void addChunk(List<String> chunks, String section, String path) {
        if (section.length() <= maxChunkSize) {
            chunks.add(section);
            return;
        }
        // 过长章节回退定长切分，但每片都带上章节路径前缀，保证可溯源
        FixedSizeChunkStrategy fallback = new FixedSizeChunkStrategy(maxChunkSize, overlap);
        List<String> pieces = fallback.split(section);
        for (String piece : pieces) {
            chunks.add("[章节: " + path + "]\n" + piece);
        }
    }
}
