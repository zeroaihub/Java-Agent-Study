package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 基于词哈希的确定性向量化实现（教学占位）。
 *
 * <p>真实生产会调用 OpenAI text-embedding 或本地 BGE 模型。为让教程<strong>无需外部依赖即可运行</strong>，
 * 这里用「词袋 + 哈希映射」构造一个确定性的低维向量，满足 RAG 演示所需的两条关键性质：</p>
 * <ol>
 *     <li><strong>确定性</strong>：相同文本永远得到相同向量（便于测试与复现）。</li>
 *     <li><strong>词共现相似</strong>：共享越多词的文本，向量越接近（余弦相似度越高）。</li>
 * </ol>
 *
 * <p>做法：把文本按非字母数字切词、小写化，每个词哈希到某个维度并累加词频，
 * 最后做 L2 归一化。<strong>它不理解语义</strong>（近义词无法关联），仅用于教学演示；
 * 切换到真实模型时，只需替换本实现类，应用层零改动——这正是端口/适配器的价值。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class HashingEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(HashingEmbeddingProvider.class);

    /** 向量维度。维度越高冲突越少，教学场景取 256 足够。 */
    private static final int DIMENSION = 256;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        if (text == null || text.isBlank()) {
            return vector;
        }
        // 按非字母数字（含中文按单字）切分，小写化后逐词累加词频到哈希维度
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}\\p{IsHan}]+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            int idx = Math.floorMod(token.hashCode(), DIMENSION);
            vector[idx] += 1.0f;
        }
        normalize(vector);
        log.trace("向量化完成: tokens={}, dim={}", tokens.length, DIMENSION);
        return vector;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    /**
     * L2 归一化：把向量缩放为单位长度，使后续余弦相似度只反映方向差异。
     *
     * @param vector 待归一化向量（原地修改）
     */
    private void normalize(float[] vector) {
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm == 0.0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}