package com.zero.ai.agentstudy.day12longrunningagent.lifecycle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Agent 生命周期状态机。
 *
 * <p>集中定义"允许的状态流转规则"，并对外提供校验与流转能力。所有状态变更都必须
 * 经过本状态机，禁止业务代码直接修改状态字段，以此杜绝非法流转导致的重复执行、
 * 数据错乱等严重问题。</p>
 *
 * <p>合法流转规则（与 ARCHITECTURE.md 中的状态机图一致）：</p>
 * <pre>
 *   CREATED   -> RUNNING, CANCELLED
 *   RUNNING   -> RUNNING(推进下一步), SUSPENDED, RETRYING, WAITING, COMPLETED, FAILED, CANCELLED
 *   SUSPENDED -> RUNNING(resume), CANCELLED, FAILED
 *   RETRYING  -> RUNNING(重试执行), FAILED, CANCELLED
 *   WAITING   -> RUNNING(下一次触发), CANCELLED
 *   COMPLETED -> (终态, 无出边)
 *   FAILED    -> (终态, 无出边)
 *   CANCELLED -> (终态, 无出边)
 * </pre>
 *
 * <p>本类是无状态的、线程安全的，可作为 Spring 单例 Bean 使用。</p>
 *
 * @author ZeroAi
 */
public final class AgentStateMachine {

    /**
     * 状态流转规则表：key 为源状态，value 为该状态允许流转到的目标状态集合。
     */
    private static final Map<AgentState, Set<AgentState>> TRANSITIONS = new EnumMap<>(AgentState.class);

    static {
        TRANSITIONS.put(AgentState.CREATED, EnumSet.of(
                AgentState.RUNNING,
                AgentState.CANCELLED));

        TRANSITIONS.put(AgentState.RUNNING, EnumSet.of(
                AgentState.RUNNING,      // 推进到下一步仍是 RUNNING（自环）
                AgentState.SUSPENDED,
                AgentState.RETRYING,
                AgentState.WAITING,
                AgentState.COMPLETED,
                AgentState.FAILED,
                AgentState.CANCELLED));

        TRANSITIONS.put(AgentState.SUSPENDED, EnumSet.of(
                AgentState.RUNNING,      // resume
                AgentState.CANCELLED,
                AgentState.FAILED));

        TRANSITIONS.put(AgentState.RETRYING, EnumSet.of(
                AgentState.RUNNING,      // 重试再次进入执行
                AgentState.FAILED,
                AgentState.CANCELLED));

        TRANSITIONS.put(AgentState.WAITING, EnumSet.of(
                AgentState.RUNNING,      // 下一次定时触发
                AgentState.CANCELLED));

        // 终态无任何出边
        TRANSITIONS.put(AgentState.COMPLETED, EnumSet.noneOf(AgentState.class));
        TRANSITIONS.put(AgentState.FAILED, EnumSet.noneOf(AgentState.class));
        TRANSITIONS.put(AgentState.CANCELLED, EnumSet.noneOf(AgentState.class));
    }

    /**
     * 判断从 from 流转到 to 是否合法。
     *
     * @param from 源状态
     * @param to   目标状态
     * @return true 表示合法
     */
    public boolean canTransit(AgentState from, AgentState to) {
        if (from == null || to == null) {
            return false;
        }
        Set<AgentState> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 校验并执行流转：合法则返回目标状态，非法则抛出 {@link IllegalStateTransitionException}。
     *
     * <p>该方法本身不持有任何 Session 状态，只做"规则校验"。真正的状态写入由调用方
     * （如 AgentRuntime）在校验通过后落地到 Session / 持久化存储。</p>
     *
     * @param from 源状态
     * @param to   目标状态
     * @return 目标状态 to（校验通过）
     * @throws IllegalStateTransitionException 当流转非法时
     */
    public AgentState transit(AgentState from, AgentState to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
        return to;
    }

    /**
     * 获取某状态所有合法的目标状态集合（只读视图，防御性拷贝）。
     *
     * @param from 源状态
     * @return 合法目标状态集合
     */
    public Set<AgentState> allowedTargets(AgentState from) {
        Set<AgentState> allowed = TRANSITIONS.get(from);
        return allowed == null ? EnumSet.noneOf(AgentState.class) : EnumSet.copyOf(allowed);
    }
}