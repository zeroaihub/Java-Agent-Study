package com.zero.ai.agentstudy.day05rag.util;

/**
 * 向量数学工具类 —— Day5 RAG 的「数学基石」。
 *
 * <p>为什么需要这个类：</p>
 * <p>RAG 的核心动作是「算两个向量有多像」。无论底层用内存向量库还是 pgvector，
 * 「余弦相似度 / 点积 / 归一化」都是绕不开的基础运算。把它们抽成一个无状态的工具类，
 * 好处是：① 全项目复用；② 易于单元测试；③ 检索器 / 向量库只依赖它，不重复造轮子。</p>
 *
 * <p>本类所有方法都是静态、无副作用的纯函数，线程安全。</p>
 *
 * @author ZeroAi
 */
public final class VectorMath {
    public static void main(String[] args) {
        float[] a = {1, 0.9f, 0};
        float[] b = {0, 1, 0};
        double similarity = cosineSimilarity(a, b);
        System.out.println("Cosine Similarity: " + similarity);
    }
    /** 工具类禁止实例化 */
    private VectorMath() {
    }

    /**
     * 计算两个向量的点积（内积）：Σ(aᵢ × bᵢ)。
     *
     * <p>为什么需要：点积是余弦相似度的分子；当向量已归一化时，点积本身就等于余弦相似度，
     * 很多向量库正是用点积来加速检索。</p>
     *
     * @param a 向量 A
     * @param b 向量 B（长度必须与 A 相同）
     * @return 点积结果
     * @throws IllegalArgumentException 当两个向量维度不一致时（维度不一致说明用了不同的 Embedding 模型，是典型 Bug）
     */
    public static double dot(float[] a, float[] b) {
        checkSameDimension(a, b);
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /**
     * 计算向量的 L2 范数（模长）：√Σ(aᵢ²)。
     *
     * <p>为什么需要：它是余弦相似度的分母部分，用于「消除向长度的影响，只保留方向」。</p>
     *
     * @param a 向量
     * @return 模长；零向量返回 0
     */
    public static double norm(float[] a) {
        double sum = 0.0;
        for (float v : a) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    /**
     * 计算余弦相似度：cos(A,B) = (A·B) / (|A| × |B|)。
     *
     * <p>为什么用余弦：一句话说得长或短不代表意思变了，余弦只看两个向量的「方向」是否一致，
     * 忽略长度，因此最适合「语义相似」的判断。结果范围 [-1, 1]，越接近 1 越相似。</p>
     *
     * <p>边界处理：任一向量为零向量时，方向无意义，返回 0（视为不相似），避免除零异常。</p>
     *
     * @param a 向量 A（如：问题向量）
     * @param b 向量 B（如：某个 Chunk 的向量）
     * @return 余弦相似度，范围 [-1, 1]
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        checkSameDimension(a, b);
        double normA = norm(a);
        double normB = norm(b);
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot(a, b) / (normA * normB);
    }

    /**
     * 归一化：把向量缩放成单位长度（模长为 1），方向不变。
     *
     * <p>为什么需要：归一化之后，两个向量的「点积」就等于「余弦相似度」，
     * 可以省去每次检索重复计算模长，是向量库常见的性能优化手段。</p>
     *
     * @param a 原始向量（不会被修改）
     * @return 归一化后的新向量；若为零向量则原样返回一份拷贝
     */
    public static float[] normalize(float[] a) {
        double n = norm(a);
        float[] result = new float[a.length];
        if (n == 0.0) {
            // 零向量无法归一化，返回拷贝，避免调用方误改原数组
            System.arraycopy(a, 0, result, 0, a.length);
            return result;
        }
        for (int i = 0; i < a.length; i++) {
            result[i] = (float) (a[i] / n);
        }
        return result;
    }

    /**
     * 校验两个向量维度是否一致。
     *
     * <p>为什么单独抽出来：维度不一致是 RAG 最隐蔽也最致命的 Bug——通常意味着
     * 问题和文档用了不同的 Embedding 模型（违反第二章铁律）。这里及早抛异常，快速失败。</p>
     */
    private static void checkSameDimension(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("向量不能为 null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "向量维度不一致: " + a.length + " vs " + b.length
                            + "，通常是问题和文档用了不同的 Embedding 模型");
        }
    }
}