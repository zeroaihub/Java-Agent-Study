package com.zero.ai.agentstudy.day08multiagent.dto;

import com.zero.ai.agentstudy.day08multiagent.entity.AgentExecutionLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ContentResponse —— 「内容生产」接口的返回 DTO。
 *
 * <p>教学要点：这是整个 Multi-Agent 系统对外交付的「成品」。它不仅给出最终文章，
 * 还把<b>全链路执行日志</b>一并返回，让调用方能看到「PlannerAgent 做了什么、
 * ReviewerAgent 打了几分、每步耗时多少」，这就是可观测性对外的价值体现。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code success}：本次生产是否成功；</li>
 *   <li>{@code article}：最终 Markdown 文章正文；</li>
 *   <li>{@code score}：评审得分（0~1）；</li>
 *   <li>{@code review}：评审意见；</li>
 *   <li>{@code logs}：全链路执行日志（每个 Agent 的执行记录）；</li>
 *   <li>{@code message}：附加说明（失败原因等）。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentResponse {

    /** 整体是否成功 */
    private boolean success;

    /** 最终生成的 Markdown 文章 */
    private String article;

    /** 评审得分（0~1） */
    private Double score;

    /** 评审意见 */
    private String review;

    /** 全链路执行日志 */
    private List<AgentExecutionLog> logs;

    /** 附加说明 */
    private String message;

    /**
     * 构造成功结果。
     *
     * @param article 最终文章
     * @param score   评审得分
     * @param review  评审意见
     * @param logs    执行日志
     * @return 响应
     */
    public static ContentResponse ok(String article, Double score, String review,
                                     List<AgentExecutionLog> logs) {
        return ContentResponse.builder()
                .success(true)
                .article(article)
                .score(score)
                .review(review)
                .logs(logs)
                .message("ok")
                .build();
    }

    /**
     * 构造失败结果（仍带上已产生的日志，便于排查）。
     *
     * @param message 失败原因
     * @param logs    已产生的执行日志
     * @return 响应
     */
    public static ContentResponse fail(String message, List<AgentExecutionLog> logs) {
        return ContentResponse.builder()
                .success(false)
                .message(message)
                .logs(logs)
                .build();
    }
}