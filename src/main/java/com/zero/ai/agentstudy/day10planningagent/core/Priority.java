package com.zero.ai.agentstudy.day10planningagent.core;

/**
 * 任务优先级。weight 越大越优先被调度。
 */
public enum Priority {
    LOW(1),
    MEDIUM(5),
    HIGH(10);

    private final int weight;

    Priority(int weight) { this.weight = weight; }

    public int weight() { return weight; }
}