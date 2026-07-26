package com.zero.ai.agentstudy.day05rag.splitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分器 —— Day5 RAG 离线索引流的「第一道工序」。
 *
 * <p>为什么需要这个类：</p>
 * <p>长文档不能整篇 Embedding（会超模型输入上限、语义被稀释、塞不进 Prompt），
 * 必须先切成「大小可控、语义尽量完整」的小块（Chunk）。本类用最经典的
 * 「滑动窗口 + 重叠(overlap)」策略实现字符级切分，是理解切分原理的最小可用实现。</p>
 *
 * <p>工程演进方向：生产级切分器（如 Spring AI 的 TokenTextSplitter、
 * LangChain 的 RecursiveCharacterTextSplitter）会在此基础上增加
 * ①按 token 计数而非字符 ②按「段落→句号→逗号」分隔符优先级递归切，避免劈断句子。
 * 本类先把「窗口滑动 + 重叠」这个核心讲透，后续可平滑替换实现。</p>
 *
 * @author ZeroAi
 */
public class TextSplitter {

    /** 默认块大小（字符数），中文场景 300~500 起步 */
    private static final int DEFAULT_CHUNK_SIZE = 300;

    /** 默认重叠字符数，一般取 chunkSize 的 10%~20% */
    private static final int DEFAULT_OVERLAP = 50;

    private final int chunkSize;
    private final int overlap;

    /**
     * 使用默认参数（chunkSize=300, overlap=50）构造。
     */
    public TextSplitter() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * 自定义参数构造。
     *
     * @param chunkSize 每块最大字符数，必须 > 0
     * @param overlap   相邻块的重叠字符数，必须 >= 0 且 < chunkSize
     * @throws IllegalArgumentException 参数非法时抛出（防止后续滑动窗口死循环）
     */
    public TextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0，当前=" + chunkSize);
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数，当前=" + overlap);
        }
        // 关键验：overlap >= chunkSize 会导致 start 不前进甚至倒退 → 死循环
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap(" + overlap + ") 必须小于 chunkSize(" + chunkSize + ")，否则会死循环");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 把一段长文本切成多个 Chunk。
     *
     * <p>核心算法（滑动窗口 + 重叠）：</p>
     * <pre>
     * start = 0
     * while start < len:
     *     end = min(start + chunkSize, len)
     *     取 [start, end) 为一个 chunk
     *     if end 已到末尾: 结束
     *     start = end - overlap   // 回退 overlap 制造重叠
     * </pre>
     *
     * @param text 待切分的原始文本；为 null 或空白时返回空列表
     * @return 切分后的 Chunk 列表（顺序与原文一致）
     */
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        // 边界：空文本直接返回空列表，避免后续越界
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + chunkSize, length);
            // substring 的 end 是开区间，正好取 [start, end)
            chunks.add(text.substring(start, end));
            // 已经切到文本末尾，结束循环
            if (end == length) {
                break;
            }
            // 下一块起点回退 overlap 个字符，形成重叠区
            start = end - overlap;
        }
        return chunks;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    /**
     * 演示入口：直观感受「分块数量」和「重叠效果」。
     * 可修改 chunkSize / overlap 观察分块变化，对应第四章练习。
     */
    public static void main(String[] args) {
        String text = "员工入职满一年后，年假为10天，可分次休完。"
                + "报销需在费用发生后30天内提交，超期不予受理。"
                + "考勤以打卡记录为准，迟到超过3次将影响绩效。";

        TextSplitter splitter = new TextSplitter(20, 5);
        List<String> chunks = splitter.split(text);

        System.out.println("原文长度: " + text.length()
                + "，chunkSize=" + splitter.getChunkSize()
                + "，overlap=" + splitter.getOverlap());
        System.out.println("共切出 " + chunks.size() + " 块：");
        for (int i = 0; i < chunks.size(); i++) {
            System.out.println("  [块" + (i + 1) + "] " + chunks.get(i));
        }
    }
}