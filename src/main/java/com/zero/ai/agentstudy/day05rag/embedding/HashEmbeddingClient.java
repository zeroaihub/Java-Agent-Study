package com.zero.ai.agentstudy.day05rag.embedding;

/**
 * HashEmbeddingClient —— 离线哈希降级实现（教学演示用，可离线运行）。
 *
 * <p>为什么存在：真正的 Embedding 要调云端 API 或跑本地模型，训练营演示环境
 * 未必有网络/模型。本类用「字符哈希散列到定长桶 + 归一化」的方式，把文本变成
 * 一个确定性的向量：<b>同样的文本永远得到同样的向量</b>，足以演示
 * 「文本→向量→检索」全链路能跑通。</p>
 *
 * <p><b>重要局限</b>：它<b>不懂语义</b>（同义词不会靠近、近义句不会相似），
 * 因此召回质量不代表真实效果。生产必须换成真模型实现——但由于面向接口，
 * Retriever / 入库流程一行都不用改。</p>
 *
 * @author ZeroAi
 */
public class HashEmbeddingClient implements EmbeddingClient {

    /** 向量维度：固定为 64，够演示且计算快 */
    private static final int DIM = 64;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIM];
        if (text == null || text.isEmpty()) {
            return vector; // 空文本返回零向量
        }
        // 以 2-gram（相邻两字符）为特征散列到桶里累加，弱化「纯单字」噪声
        for (int i = 0; i < text.length(); i++) {
            // 单字符特征
            addFeature(vector, feature(text.charAt(i), '\0'));
            // 相邻二元组特征（若存在下一个字符）
            if (i + 1 < text.length()) {
                addFeature(vector, feature(text.charAt(i), text.charAt(i + 1)));
            }
        }
        return normalize(vector);
    }

    @Override
    public int dimension() {
        return DIM;
    }

    /** 由一个或两个字符算出一个稳定的桶索引（0 ~ DIM-1） */
    private int feature(char a, char b) {
        int h = 31 * a + b;
        // 取模到 [0, DIM)，负数取绝对值
        int idx = h % DIM;
        return idx < 0 ? idx + DIM : idx;
    }

    /** 把某个桶的权重 +1 */
    private void addFeature(float[] vector, int idx) {
        vector[idx] += 1.0f;
    }

    /** L2 归一化：让向量长度为 1，便于余弦相似度比较（第三章原理） */
    private float[] normalize(float[] v) {
        double sumSq = 0.0;
        for (float x : v) {
            sumSq += x * x;
        }
        double norm = Math.sqrt(sumSq);
        if (norm == 0) {
            return v; // 全零向量直接返回，避免除以 0
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}