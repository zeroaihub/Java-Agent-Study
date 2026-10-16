package com.zero.ai.agentstudy.day15knowledgeplatform.api.dto;

import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;

import java.util.List;

/**
 * 检索响应 DTO。
 *
 * <p><b>最重要的字段是 {@code citations}</b>。
 * 每一条结果都必须能回答「这句话出自哪份文档的哪一段」——
 * 这是企业知识平台区别于聊天机器人的<b>生死线</b>：</p>
 * <ul>
 *     <li><b>可信</b>：用户能点开原文核对，而不是盲信 AI 的转述；</li>
 *     <li><b>可审计</b>：出了问题时能追溯「这次回答基于哪些材料」；</li>
 *     <li><b>可纠偏</b>：答案错了，用户能立刻定位到是<b>原文错了</b>
 *         还是<b>模型理解错了</b>——这两者的修复方式完全不同。</li>
 * </ul>
 * <b>没有溯源的 AI 回答，在法律与合规场景下等于没有回答。</b></p>
 *
 * @param query       查询文本
 * @param hits        命中结果
 * @param readableKbCount 实际生效的权限范围大小（诊断信息）
 * @param reranker    使用的重排序器
 * @param elapsedMs   总耗时
 * @param note        备注（如无权访问提示）
 * @author ZeroAi
 */
public record SearchResponse(String query,
                             List<Hit> hits,
                             int readableKbCount,
                             String reranker,
                             long elapsedMs,
                             String note) {

    /**
     * 单条命中结果（面向前端的投影）。
     *
     * @param citation  溯源标识：{@code 文档ID#切片序号}
     * @param documentId 文档 ID
     * @param sequence  切片序号
     * @param title     文档标题
     * @param text      切片正文
     * @param score     最终得分
     * @param matchedBy 命中了哪些检索器（{@code dense} / {@code sparse}）
     * @param ranks     各路检索器中的排名（诊断用）
     * @author ZeroAi
     */
    public record Hit(String citation,
                      String documentId,
                      int sequence,
                      String title,
                      String text,
                      double score,
                      List<String> matchedBy,
                      java.util.Map<String, Integer> ranks) {

        public static Hit from(RetrievedChunk rc) {
            return new Hit(
                    rc.citation(),
                    rc.chunk().documentId().value(),
                    rc.chunk().sequence(),
                    rc.chunk().metadata().getString("documentTitle").orElse("(无标题)"),
                    rc.chunk().text(),
                    rc.score(),
                    rc.matchedBy(),
                    rc.ranks());
        }
    }

    public static SearchResponse from(com.zero.ai.agentstudy.day15knowledgeplatform.application
                                              .KnowledgePlatformFacade.SearchOutcome outcome) {
        List<Hit> hits = outcome.results().stream().map(Hit::from).toList();
        return new SearchResponse(outcome.query(), hits, outcome.readableScope().size(),
                outcome.rerankerName(), outcome.totalElapsedMs(), outcome.note());
    }
}
