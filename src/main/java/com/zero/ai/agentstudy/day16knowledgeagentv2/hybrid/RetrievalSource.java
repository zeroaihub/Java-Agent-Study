package com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.List;

/**
 * 检索源端口（Chapter 05，六边形架构的「端口」）。
 *
 * <p>无论是向量检索（pgvector）、关键词检索（Elasticsearch BM25），还是未来接入的
 * 图检索，对上层而言都只是「给一个查询，返回一批带分数的片段」。领域层只依赖此接口，
 * 具体存储实现是可插拔的适配器。</p>
 *
 * @author ZeroAi
 */
public interface RetrievalSource {

    /**
     * 检索源名称（用于日志、指标区分不同来源）。
     */
    String name();

    /**
     * 执行检索。
     *
     * @param ctx   请求上下文（携带租户，实现必须据此隔离数据）
     * @param query 查询文本
     * @param topK  返回条数
     * @return 按各自打分降序的命中片段
     */
    List<RetrievedChunk> search(AgentContext ctx, String query, int topK);
}