package com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.port.ChunkStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义切分策略（按句边界切，再贪心合并）。
 *
 * <p>定长切分最大的问题是不尊重语言边界，可能在句子中间拦腰斩断。
 * 本策略反过来做：<b>先按句拆分，再贪心地把句子打包到接近 chunkSize 为止</b>，
 * 保证每个切片的边界都落在句号/问号/换行上。</p>
 *
 * <p>这样切出来的 Chunk 读起来是完整的几句话，
 * 送给 LLM 时上下文更连贯，答案质量明显优于定长切分。</p>
 *
 * <p><b>代价</b>：切片大小不均匀（有的 200 字、有的 480 字），
 * 且对没有标点的文本（如日志、代码）效果退化——此时应改用定长或结构化策略。</p>
 *
 * @author ZeroAi
 */
public class SemanticChunkStrategy implements ChunkStrategy {

    /** 句子分隔符：中英文句末标点 + 连续换行 */
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？；!?;])\\s*|\\n{2,}");

    private final int maxChunkSize;
    private final int minChunkSize;

    public SemanticChunkStrategy() {
        this(500, 80);
    }

    public SemanticChunkStrategy(int maxChunkSize, int minChunkSize) {
        this.maxChunkSize = maxChunkSize;
        this.minChunkSize = minChunkSize;
    }

    @Override
    public String name() {
        return "semantic(max=" + maxChunkSize + ", min=" + minChunkSize + ")";
    }

    @Override
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String[] sentences = SENTENCE_BOUNDARY.split(text);
        StringBuilder current = new StringBuilder();

        for (String raw : sentences) {
            String sentence = raw.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            // 单句就超过上限：只能单独成片（否则永远合不进去）
            if (sentence.length() > maxChunkSize) {
                flush(chunks, current);
                chunks.add(sentence);
                continue;
            }
            if (current.length() + sentence.length() > maxChunkSize) {
                flush(chunks, current);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(sentence);
        }
        flush(chunks, current);

        // 收尾：把过短的尾片并入前一片，避免产生「碎片 Chunk」
        mergeTooSmall(chunks);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }

    private void mergeTooSmall(List<String> chunks) {
        if (chunks.size() < 2) {
            return;
        }
        String last = chunks.get(chunks.size() - 1);
        if (last.length() < minChunkSize) {
            String prev = chunks.get(chunks.size() - 2);
            chunks.set(chunks.size() - 2, prev + " " + last);
            chunks.remove(chunks.size() - 1);
        }
    }
}
