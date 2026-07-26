package com.zero.ai.agentstudy.day06workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * TravelResponse —— 旅行规划接口的返回体。
 *
 * <p>教学要点：对外接口用独立 DTO，不直接暴露内部领域对象，
 * 是分层架构的基本范（避免内部结构泄露、便于版本演进）。</p>
 *
 * @author ZeroAi
 */
@Data
@AllArgsConstructor
public class TravelResponse {

    /** 运行 ID，便于排查 */
    private String runId;

    /** 流程最终状态，如 COMPLETED / FAILED */
    private String state;

    /** 旅行方案正文（Markdown） */
    private String plan;

    /** 执行轨迹日志（每个节点一行） */
    private String executionTrace;
}