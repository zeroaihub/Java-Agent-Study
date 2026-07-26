package com.zero.ai.agentstudy.day05rag.mycode.util;

/**
 * SimilarityUtil：向量相似度计算工具。
 *
 * <p>RAG 的检索本质是「找出与问题向量最接近的资料向量」。
 * 最常用的度量是 <b>余弦相似度</b>：只看两个向量的方向是否一致，
 * 不受向量长度影响，取值 [-1, 1]，越接近 1 越相似。
 *
 * <p>公式：cos = (A · B) / (|A| * |B|)
 * <ul>
 *   <li>A · B    ：点积（对应位置相乘再求和）</li>
 *   <li>|A|、|B| ：各自的模长（每个分量平方和再开方）</li>
 * </ul>
 */
public final class SimilarityUtil {

    private SimilarityUtil() {
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * @param a 向量 A
     * @param b 向量 B（维度需与 A 相同）
     * @return 余弦相似度，取值 [-1, 1]
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            throw new IllegalArgumentException("向量为空或维度不一致：a="
                    + (a == null ? "null" : a.length)
                    + ", b=" + (b == null ? "null" : b.length));
        }
        double dot = 0.0;   // 点积 A · B
        double normA = 0.0; // |A|^2
        double normB = 0.0; // |B|^2
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0; // 零向量无方向，视为不相似
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}