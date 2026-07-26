package com.zero.ai.agentstudy.day05rag.mycode.embedding;

/**
 * HashEmbeddingClient —— 离线哈希降级实现（纯教学演示用，可完全离线运行）。
 *
 * <p><b>为什么存在？</b>
 * 真正的 Embedding 需要调用本地 LM Studio 或云端 API（见同包下的
 * {@link EmbeddingClient}）。但在没有网络、没有加载模型的演示环境下，
 * 想先把「文本 → 向量 → 检索」的链路跑通，可以用这个「字符哈希散列到
 * 定长桶 + L2 归一化」的确定性算法：<b>同样的文本永远得到同样的向量</b>。</p>
 *
 * <p><b>重要局限</b>：它<b>不懂语义</b>——同义词不会靠近、近义句不会相似，
 * 因此召回质量不代表真实效果，仅用于验证流程能否跑通。真正做 RAG 请使用
 * {@link EmbeddingClient}（真实调用 LM Studio）。</p>
 *
 * <p>注意：本类是<b>独立的教学对照实现</b>，不参与 Spring 容器装配
 * （没有 {@code @Component}），也不被 RagService 引用，避免与真实实现冲突。</p>
 *
 * @author ZeroAi
 */
public class HashEmbeddingClient {

    /** 向量维度：与常见 bge 模型对齐，方便对照（纯演示，可任意设定）。 */
    private static final int DIM = 1024;

    /**
     * 把文本散列成一个确定性的定长向量。
     *
     * <p>算法：遍历每个字符，用其 code point 与位置做简单散列，落到
     * {@code [0, DIM)} 的某个桶并累加权重，最后做 L2 归一化，得到单位向量。</p>
     */
    public float[] embed(String text) {
        float[] vector = new float[DIM];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        for (int i = 0; i < text.length(); i++) {
            int code = text.charAt(i);
            // 简单散列：字符值 * 31 + 位置，取模落桶
            int bucket = Math.abs((code * 31 + i)) % DIM;
            vector[bucket] +=1.0f + (code % 7) * 0.1f;
        }
        // L2 归一化，让向量落在单位球面上，方便后续余弦相似度比较
        double norm = 0.0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
if (norm > 0) {
            for (int i = 0; i < DIM; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    /** 向量维度。 */
    public int dimension() {
        return DIM;
    }
}