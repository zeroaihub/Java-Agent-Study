package com.zero.ai.agentstudy.day15knowledgeplatform.embedding.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.text.TextTokenizer;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.port.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征哈希向量模型（Hashing Trick / Signed Random Projection）。
 *
 * <p><b>它是什么</b>：一种真实的、无需训练的向量化方法。把每个 token 用两个独立哈希函数
 * 映射到 {@code [0, dimension)} 的一个下标与一个正负号，按子线性词频加权累加，
 * 最后 L2 归一化。这个方法有正式名字——<b>the hashing trick</b>，
 * 是 Vowpal Wabbit 的默认特征编码方式，scikit-learn 的 {@code HashingVectorizer} 亦然。</p>
 *
 * <p><b>为什么在教程里用它</b>：</p>
 * <ol>
 *     <li><b>零依赖、可离线跑通</b>——不需要 LM Studio、不需要 API Key、不需要网络；</li>
 *     <li><b>确定性</b>——同样的文本永远得到同样的向量，Demo 输出可复现、可写进教材；</li>
 *     <li><b>它诚实地暴露了一个真相</b>——见下方「它不能做什么」。</li>
 * </ol>
 *
 * <p><b>它不能做什么（重要，务必理解）</b>：
 * 它刻画的是<b>字面（surface）相似</b>，不是<b>语义相似</b>。
 * 「支持远程办公吗」与「弹性工作制」在字面上毫无重叠，它算出的相似度接近 0；
 * 而真正的稠密模型（bge / nomic / text-embedding-3）能把它们映射到相近的向量。
 * <b>这正是为什么生产环境必须用真实 Embedding 模型，也正说明了
 * 「只靠向量」同样不够——这正是第 7 章 Hybrid Search 存在的理由。</b></p>
 *
 * <p><b>哈希冲突的处理</b>：冲突不可避免，但 signed hashing（带符号哈希）保证了
 * 两个不同 token 撞到同一维时，它们的贡献在期望上相互抵消而非叠加放大，
 * 因此高维下（如 256 维）内积的期望仍近似于真实相似度。这是 Johnson-Lindenstrauss
 * 引理的直接应用：随机投影保距离。</p>
 *
 * @author ZeroAi
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    /** 默认维度。256 维对 Demo 规模绰绰有余，且便于打印观察。 */
    public static final int DEFAULT_DIMENSION = 256;

    private static final String MODEL_ID = "hashing-trick-v1";

    private final int dimension;

    public HashingEmbeddingModel() {
        this(DEFAULT_DIMENSION);
    }

    public HashingEmbeddingModel(int dimension) {
        if (dimension <= 0) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "向量维度必须为正");
        }
        this.dimension = dimension;
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public EmbeddingVector embed(String text) {
        KnowledgeException.requireNonNull(text, "text");
        // 注意这里用 modelId() 而不是 MODEL_ID 常量：
        // 否则子类覆写 modelId() 后，产出的向量仍带着父类的模型标识，
        // 「模型锁版本」的校验就会形同虚设——
        // 看似换了模型，实际两个模型的向量被判定为同源，可以互相比较。
        return new EmbeddingVector(modelId(), dimension, vectorize(text), true);
    }

    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        KnowledgeException.requireNonNull(texts, "texts");
        List<EmbeddingVector> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }

    /**
     * 核心：文本 → 定长稠密向量（已 L2 归一化）。
     *
     * <p>三步：分词 → 统计词频 → 按哈希投影累加 → 归一化。</p>
     */
    private float[] vectorize(String text) {
        float[] vec = new float[dimension];

        // 1) 分词
        List<String> tokens = TextTokenizer.tokenize(text);

        // 2) 词频统计（子线性缩放：1 + log(tf)，抑制长文档里重复词的支配效应）
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }

        // 3) 带符号哈希投影
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            byte[] digest = sha256(e.getKey());
            int h1 = readInt(digest, 0);
            int h2 = readInt(digest, 4);
            int index = Math.floorMod(h1, dimension);
            float sign = (h2 & 1) == 0 ? 1.0f : -1.0f;
            float weight = (float) (1.0 + Math.log(e.getValue()));
            vec[index] += sign * weight;
        }

        // 4) L2 归一化：此后余弦相似度 == 点积
        return EmbeddingVector.l2Normalize(vec);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 强制实现的算法，理论上不可达
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED, "哈希算法不可用", ex);
        }
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24)
                | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8)
                | (b[offset + 3] & 0xFF);
    }
}
