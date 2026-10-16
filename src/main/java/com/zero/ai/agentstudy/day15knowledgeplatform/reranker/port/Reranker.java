package com.zero.ai.agentstudy.day15knowledgeplatform.reranker.port;

import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;

import java.util.List;

/**
 * 重排序器端口。
 *
 * <p><b>为什么检索之后还要重排？</b>
 * 因为召回（Recall）与精排（Precision）是两件目标不同、成本也不同的事：</p>
 *
 * <table>
 *   <caption>两阶段检索的分工</caption>
 *   <tr><th></th><th>召回阶段（Retriever）</th><th>精排阶段（Reranker）</th></tr>
 *   <tr><td>目标</td><td>别漏掉（高召回）</td><td>排得准（高精度）</td></tr>
 *   <tr><td>候选量</td><td>百万级</td><td>几十条</td></tr>
 *   <tr><td>算法</td><td>双塔向量 / BM25（可预计算、可索引）</td><td>Cross-Encoder（必须在线算）</td></tr>
 *   <tr><td>耗时</td><td>毫秒级</td><td>十到百毫秒级</td></tr>
 * </table>
 *
 * <p>关键在于：<b>向量检索是「双塔（Bi-Encoder）」结构</b>——
 * query 和 chunk 各自独立编码成向量，两者从不"见面"，最后只算一个余弦。
 * 这种结构让 chunk 向量可以<b>离线预计算并建索引</b>，所以能撑起百万级检索；
 * 但代价是它无法捕捉 query 与 chunk 之间的<b>细粒度交互</b>。</p>
 *
 * <p>而 <b>Cross-Encoder</b> 把 query 和 chunk <b>拼在一起</b>送进模型
 * （{@code [CLS] query [SEP] chunk [SEP]}），让每一对 token 都能互相注意力。
 * 精度高得多，但每一对都必须重新算一遍，<b>无法预计算、无法建索引</b>。
 * 所以它只能用于几十条候选的精排。</p>
 *
 * <p>这就是工业界标准的「<b>召回-精排（Retrieve & Rerank）</b>」两阶段架构：
 * 先用便宜的方法从百万里捞出 50 条，再用贵的方法从 50 条里挑出最好的 5 条。
 * <b>用 5% 的成本，买到 80% 的质量提升。</b></p>
 *
 * @author ZeroAi
 */
public interface Reranker {

    /**
     * 重排序器名称。
     */
    String name();

    /**
     * 对候选结果重排序。
     *
     * @param query      原始查询文本（Cross-Encoder 必须与候选文本一起编码）
     * @param candidates 召回阶段的候选（通常 20~100 条）
     * @param topN       最终返回条数
     * @return 重排后的结果，按相关性降序
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
