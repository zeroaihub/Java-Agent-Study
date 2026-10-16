package com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.port.ChunkStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 定长切分策略（带重叠窗口）。
 *
 * <p>最朴素、也最通用的策略：按固定字符数切，相邻切片保留一段重叠（overlap）。</p>
 *
 * <p><b>为什么必须有 overlap？</b> 因为语义会被切缝切断。
 * 假设原文 {@code "报销标准：总监级别每晚上限 800 元"}，
 * 若在「800」和「元」之间断开，前一片是「上限 800」、后一片是「元……」——
 * 两片单独看都失去了关键语义，向量检索都匹配不准。
 * 重叠一段字符能显著提高「边界信息」被完整保留的概率。</p>
 *
 * <p><b>代价</b>：overlap 越大冗余越多，向量库体积与 Embedding 成本上升。
 * 经验值：overlap 取 chunkSize 的 10%~20%。</p>
 *
 * @author ZeroAi
 */
public class FixedSizeChunkStrategy implements ChunkStrategy {

    /** 默认切片大小（字符）。约 350~500 token，是 RAG 常用区间。 */
    public static final int DEFAULT_CHUNK_SIZE = 500;
    /** 默认重叠字符数。 */
    public static final int DEFAULT_OVERLAP = 50;

    private final int chunkSize;
    private final int overlap;

    public FixedSizeChunkStrategy() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public FixedSizeChunkStrategy(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须为正数");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须 >= 0 且 < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public String name() {
        return "fixed-size(size=" + chunkSize + ", overlap=" + overlap + ")";
    }

    @Override
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int step = chunkSize - overlap;
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(start + chunkSize, length);
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end == length) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }
}
