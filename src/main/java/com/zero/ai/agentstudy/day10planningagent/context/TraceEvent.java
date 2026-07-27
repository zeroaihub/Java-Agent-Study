package com.zero.ai.agentstudy.day10planningagent.context;

import java.time.Instant;

/**
 * 可观测性轨迹事件。记录主循环每个关键节点，便于回放与排查。
 *
 * @param at    发生时间
 * @param phase 阶段（PLAN/SCHEDULE/EXECUTE/REFLECT/REPLAN/DONE...）
 * @param detail 详情
 */
public record TraceEvent(Instant at, String phase, String detail) {

    public static TraceEvent of(String phase, String detail) {
        return new TraceEvent(Instant.now(), phase, detail);
    }
}