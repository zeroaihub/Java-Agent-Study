package com.zero.ai.agentstudy.day10planningagent.context;

/**
 * 预算护栏被突破时抛出（步数/重规划次数/超时），用于防止死循环。
 */
public class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(String message) {
        super(message);
    }
}