package com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port;

import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;

import java.util.List;

/**
 * 检索器端口。
 *
 * <p>把「检索」抽象成接口，是为了让「跑几路、怎么融合」成为<b>可编排的组合</b>，
 * 而不是写死在一个大类里。今天我们有稠密（向量）与稀疏（BM25）两路，
 * 明天要加「知识图谱检索」「SQL 检索」「全文精确匹配」，
 * 都只需新增一个实现类，融合层与编排层无需改动。</p>
 *
 * <p>返回的是带排名的 {@link RetrievedChunk}，而不是裸的 (chunk, score) 对——
 * 因为融合算法需要排名信息，见 {@link RetrievedChunk#ranks()} 的说明。</p>
 *
 * @author ZeroAi
 */
public interface Retriever {

    /**
     * 检索器名称，用于结果溯源与融合时的 key（如 {@code dense}、{@code sparse}）。
     */
    String name();

    /**
     * 执行检索。
     */
    List<RetrievedChunk> retrieve(SearchQuery query);
}
