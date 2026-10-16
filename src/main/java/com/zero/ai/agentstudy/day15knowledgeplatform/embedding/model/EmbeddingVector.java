package com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.util.Arrays;

/**
 * 向量值对象（不可变）。
 *
 * <p>为什么不让向量裸奔成 {@code float[]}？因为向量检索有一条铁律：
 * <b>只有同一个模型、同一个维度、同一个归一化方式产生的向量才能互相比较</b>。
 * 裸数组丢掉了这些上下文，一旦有人把 768 维的 bge 向量和 1024 维的 nomic 向量
 * 塞进同一个库，余弦相似度会静默算出垃圾结果——不会报错，只会让检索悄悄变烂。</p>
 *
 * <p>所以向量必须携带三个属性：
 * <ol>
 *     <li><b>model</b>：产出它的模型标识（含版本），用于「模型锁版本」校验；</li>
 *     <li><b>dimension</b>：维度，写入前与查询前双重校验；</li>
 *     <li><b>normalized</b>：是否已 L2 归一化，决定后续用点积还是余弦。</li>
 * </ol>
 * 存储层在写入前会校验 model 与 dimension 与当前知识库配置一致，不一致直接拒绝——
 * 这就是「向量漂移」的第一道防线。</p>
 *
 * @param model      产出该向量的模型标识（如 {@code nomic-embed-text-v1.5}）
 * @param dimension  维度
 * @param values     向量分量
 * @param normalized 是否已做 L2 归一化
 * @author ZeroAi
 */
public record EmbeddingVector(String model, int dimension, float[] values, boolean normalized) {

    public EmbeddingVector {
        KnowledgeException.requireNonNull(model, "embeddingModel");
        if (dimension <= 0) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "向量维度必须为正");
        }
        KnowledgeException.requireNonNull(values, "values");
        if (values.length != dimension) {
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                    "向量维度不一致：声明 " + dimension + "，实际 " + values.length);
        }
        // 防御性拷贝：record 的不可变性不能只靠约定，数组是可变引用
        values = Arrays.copyOf(values, values.length);
    }

    /**
     * 构造已归一化的向量（生产路径：模型输出后统一 L2 归一化再入库）。
     */
    public static EmbeddingVector of(String model, float[] raw) {
        return new EmbeddingVector(model, raw.length, l2Normalize(raw), true);
    }

    /**
     * L2 归一化：v / ||v||。
     *
     * <p>归一化后，余弦相似度退化为点积，检索时省掉一次开方与除法，
     * 在百万级向量的暴力扫描里这是肉眼可见的性能差异。</p>
     *
     * <p>零向量的处理很关键：直接返回零向量而不是除零得到 NaN。
     * NaN 一旦进入向量库，任何与它的相似度都是 NaN，排序会彻底错乱且极难排查。</p>
     */
    public static float[] l2Normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum <= 0.0) {
            return new float[v.length];
        }
        double norm = Math.sqrt(sum);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    /**
     * 余弦相似度（已归一化时等价于点积）。
     */
    public double cosineSimilarity(EmbeddingVector other) {
        requireComparable(other);
        double dot = 0.0;
        float[] a = this.values;
        float[] b = other.values;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        // 未归一化的兜底：除以模长
        if (!this.normalized || !other.normalized) {
            double na = 0.0;
            double nb = 0.0;
            for (int i = 0; i < a.length; i++) {
                na += (double) a[i] * a[i];
                nb += (double) b[i] * b[i];
            }
            if (na <= 0.0 || nb <= 0.0) {
                return 0.0;
            }
            return dot / (Math.sqrt(na) * Math.sqrt(nb));
        }
        return dot;
    }

    /**
     * 可比性校验：不同模型或不同维度的向量禁止比较。
     *
     * <p>这是 fail-fast 原则在向量层的应用。<b>宁可启动就崩，也不要线上静默返回垃圾。</b></p>
     */
    public void requireComparable(EmbeddingVector other) {
        KnowledgeException.requireNonNull(other, "otherVector");
        if (this.dimension != other.dimension) {
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                    "向量维度不一致，无法比较：" + this.dimension + " vs " + other.dimension);
        }
        if (!this.model.equals(other.model)) {
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                    "向量来自不同模型，无法比较：" + this.model + " vs " + other.model
                            + "。更换 Embedding 模型必须全量重建向量（见第 6 章「模型锁版本」）");
        }
    }

    /**
     * 防御性拷贝后返回分量，防止外部修改破坏不可变性。
     */
    public float[] values() {
        return Arrays.copyOf(values, values.length);
    }

    @Override
    public String toString() {
        return "EmbeddingVector{" + model + ", dim=" + dimension
                + ", norm=" + normalized + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EmbeddingVector other)) {
            return false;
        }
        return dimension == other.dimension
                && normalized == other.normalized
                && model.equals(other.model)
                && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        int result = model.hashCode();
        result = 31 * result + dimension;
        result = 31 * result + Arrays.hashCode(values);
        return result;
    }
}
