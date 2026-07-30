package com.zero.ai.agentstudy.day11humanintheloop.humancore.statemachine;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalDecision;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalTransition;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 审批状态机（Approval State Machine）——本模块的"大脑"。
 *
 * <p>它的唯一职责：给定"当前状态 + 想执行的动作"，判断这是否合法，
 * 合法则算出"下一个状态"并驱动 {@link ApprovalRequest} 变更；非法则抛
 * {@link IllegalTransitionException}。</p>
 *
 * <p><b>为什么要中心化？</b> 如果把"什么状态能干什么"这种规则散落在各个 Service 的
 * if/else 里，随着状态越来越多（PENDING、APPROVED、MODIFIED、TIMEOUT...），
 * 代码会迅速腐化成没人敢改的意大利面。把规则收敛到一张"转移表"里，
 * 新增一条规则就是加一行，安全、可测、可视化。</p>
 *
 * <p><b>转移表（核心）：</b></p>
 * <pre>
 *   状态 \ 动作     APPROVE      NEXT_LEVEL   FINALIZE        REJECT     MODIFY     RESUBMIT   TIMEOUT    ABORT
 *   PENDING        APPROVED     -            -               REJECTED   MODIFIED   -          TIMEOUT    ABORTED
 *   APPROVED       -            PENDING      FINAL_APPROVED   -          -          -          -          ABORTED
 *   MODIFIED       -            -            -               REJECTED   -          PENDING    TIMEOUT    ABORTED
 *   (终态)         全部非法
 * </pre>
 *
 * <p>本类无状态、线程安全，可作为 Spring 单例 Bean 注入。</p>
 */
public class ApprovalStateMachine {

    /**
     * 转移表：from 状态 -> (动作 -> to 状态)。
     * 使用 EnumMap，查表 O(1) 且内存紧凑。
     */
    private final Map<ApprovalStatus, Map<ApprovalTransition, ApprovalStatus>> table;

    public ApprovalStateMachine() {
        this.table = buildTable();
    }

    private Map<ApprovalStatus, Map<ApprovalTransition, ApprovalStatus>> buildTable() {
        Map<ApprovalStatus, Map<ApprovalTransition, ApprovalStatus>> t = new EnumMap<>(ApprovalStatus.class);

        // PENDING：等待某一级审批
        Map<ApprovalTransition, ApprovalStatus> pending = new EnumMap<>(ApprovalTransition.class);
        pending.put(ApprovalTransition.APPROVE, ApprovalStatus.APPROVED);
        pending.put(ApprovalTransition.REJECT, ApprovalStatus.REJECTED);
        pending.put(ApprovalTransition.MODIFY, ApprovalStatus.MODIFIED);
        pending.put(ApprovalTransition.TIMEOUT, ApprovalStatus.TIMEOUT);
        pending.put(ApprovalTransition.ABORT, ApprovalStatus.ABORTED);
        t.put(ApprovalStatus.PENDING, pending);

        // APPROVED：某一级通过后，要么进入下一级，要么终审通过，要么被终止
        Map<ApprovalTransition, ApprovalStatus> approved = new EnumMap<>(ApprovalTransition.class);
        approved.put(ApprovalTransition.NEXT_LEVEL, ApprovalStatus.PENDING);
        approved.put(ApprovalTransition.FINALIZE, ApprovalStatus.FINAL_APPROVED);
        approved.put(ApprovalTransition.ABORT, ApprovalStatus.ABORTED);
        t.put(ApprovalStatus.APPROVED, approved);

        // MODIFIED：人工改过参数，等待重新提交或被驳回/超时/终止
        Map<ApprovalTransition, ApprovalStatus> modified = new EnumMap<>(ApprovalTransition.class);
        modified.put(ApprovalTransition.RESUBMIT, ApprovalStatus.PENDING);
        modified.put(ApprovalTransition.REJECT, ApprovalStatus.REJECTED);
        modified.put(ApprovalTransition.TIMEOUT, ApprovalStatus.TIMEOUT);
        modified.put(ApprovalTransition.ABORT, ApprovalStatus.ABORTED);
        t.put(ApprovalStatus.MODIFIED, modified);

        // 终态（FINAL_APPROVED / REJECTED / TIMEOUT / ABORTED）：不允许任何流转
        t.put(ApprovalStatus.FINAL_APPROVED, new EnumMap<>(ApprovalTransition.class));
        t.put(ApprovalStatus.REJECTED, new EnumMap<>(ApprovalTransition.class));
        t.put(ApprovalStatus.TIMEOUT, new EnumMap<>(ApprovalTransition.class));
        t.put(ApprovalStatus.ABORTED, new EnumMap<>(ApprovalTransition.class));

        return t;
    }

    /**
     * 查询：给定状态是否允许某个动作。
     */
    public boolean canFire(ApprovalStatus from, ApprovalTransition transition) {
        return table.getOrDefault(from, Map.of()).containsKey(transition);
    }

    /**
     * 查询：给定状态下所有合法的动作集合（用于前端渲染"可点的按钮"）。
     */
    public Set<ApprovalTransition> allowedTransitions(ApprovalStatus from) {
        Map<ApprovalTransition, ApprovalStatus> row = table.get(from);
        if (row == null || row.isEmpty()) {
            return EnumSet.noneOf(ApprovalTransition.class);
        }
        return EnumSet.copyOf(row.keySet());
    }

    /**
     * 纯计算：给定状态执行动作后应到达的目标状态；非法则抛异常。
     * <p>这是唯一允许"决定下一个状态"的地方。</p>
     */
    public ApprovalStatus next(ApprovalStatus from, ApprovalTransition transition) {
        Map<ApprovalTransition, ApprovalStatus> row = table.getOrDefault(from, Map.of());
        ApprovalStatus to = row.get(transition);
        if (to == null) {
            throw new IllegalTransitionException(from, transition);
        }
        return to;
    }

    /**
     * 核心入口：对一个审批请求施加一次决策。
     *
     * <p>它会：①校验合法性 → ②记录决策（审计链）→ ③在多级会签场景下自动判断
     * 是"进入下一级"还是"终审通过" → ④驱动请求状态变更。</p>
     *
     * @param request  审批请求（会被就地更新）
     * @param decision 本次决策
     * @return 变更后的最新状态
     * @throws IllegalTransitionException 当前状态不允许该动作
     */
    public ApprovalStatus fire(ApprovalRequest request, ApprovalDecision decision) {
        ApprovalStatus from = request.getStatus();
        ApprovalTransition transition = decision.transition();

        // ① 合法性校验
        ApprovalStatus to = next(from, transition);

        // ② 记录审计（先记后改，保证任何异常前历史都在）
        request.recordDecision(decision);

        // ③ 多级会签的特殊处理：一次 APPROVE 之后，要判断是否还需要下一级
        if (transition == ApprovalTransition.APPROVE) {
            request.applyStatus(ApprovalStatus.APPROVED);
            if (request.allLevelsApproved()) {
                // 所有级都过了 -> 终审通过
                request.applyStatus(next(ApprovalStatus.APPROVED, ApprovalTransition.FINALIZE));
            } else {
                // 还有下一级 -> 回到 PENDING 等待下一位审批人
                request.applyStatus(next(ApprovalStatus.APPROVED, ApprovalTransition.NEXT_LEVEL));
            }
            return request.getStatus();
        }

        // ④ 其它动作：直接应用目标状态
        request.applyStatus(to);
        return request.getStatus();
    }
}