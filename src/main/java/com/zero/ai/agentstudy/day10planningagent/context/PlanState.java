package com.zero.ai.agentstudy.day10planningagent.context;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Planning 任务的生命周期状态机。
 * 用 EnumMap 定义合法转移表，非法转移 fail-fast。
 */
public enum PlanState {
    NEW,
    PLANNING,
    READY,
    EXECUTING,
    REFLECTING,
    RE_PLANNING,
    WAITING_HUMAN,
    SUCCEEDED,
    FAILED;

    private static final Map<PlanState, Set<PlanState>> TRANSITIONS = new EnumMap<>(PlanState.class);

    static {
        TRANSITIONS.put(NEW, EnumSet.of(PLANNING, FAILED));
        TRANSITIONS.put(PLANNING, EnumSet.of(READY, FAILED));
        TRANSITIONS.put(READY, EnumSet.of(EXECUTING, FAILED));
        TRANSITIONS.put(EXECUTING, EnumSet.of(REFLECTING, FAILED));
        TRANSITIONS.put(REFLECTING, EnumSet.of(
                EXECUTING, RE_PLANNING, WAITING_HUMAN, SUCCEEDED, FAILED));
        TRANSITIONS.put(RE_PLANNING, EnumSet.of(READY, FAILED));
        TRANSITIONS.put(WAITING_HUMAN, EnumSet.of(EXECUTING, RE_PLANNING, FAILED));
        TRANSITIONS.put(SUCCEEDED, EnumSet.noneOf(PlanState.class));
        TRANSITIONS.put(FAILED, EnumSet.noneOf(PlanState.class));
    }

    public boolean canTransitionTo(PlanState target) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(PlanState.class)).contains(target);
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}