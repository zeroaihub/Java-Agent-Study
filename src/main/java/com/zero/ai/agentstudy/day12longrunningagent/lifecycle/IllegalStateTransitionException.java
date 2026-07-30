package com.zero.ai.agentstudy.day12longrunningagent.lifecycle;

/**
 * 非法状态流转异常。
 *
 * <p>当调用方试图执行一个状态机不允许的流转（例如从 {@code COMPLETED} 跳回
 * {@code RUNNING}）时抛出。这是保证"状态正确性"的最后一道防线。</p>
 *
 * @author ZeroAi
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final AgentState from;
    private final AgentState to;

    public IllegalStateTransitionException(AgentState from, AgentState to) {
        super("非法状态流转: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public AgentState getFrom() {
        return from;
    }

    public AgentState getTo() {
        return to;
    }
}