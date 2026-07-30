package com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval;

import java.util.List;
import java.util.Objects;

/**
 * 审批层级配置（Approval Level）——描述「第 N 级由谁来批、批多久算超时」。
 *
 * <p>在多级会签场景里，每一级往往由不同角色负责：一级是「值班主管」、二级是「部门经理」、
 * 三级是「风控总监」。这个值对象把「一级的规则」固化下来：</p>
 * <ul>
 *   <li>{@link #level}：级号，从 1 开始，与 {@code ApprovalRequest.requiredLevels} 对应。</li>
 *   <li>{@link #roleName}：本级的审批角色名（用于路由到具体审批人 / 审批组）。</li>
 *   <li>{@link #approvers}：本级候选审批人白名单（任一人批准即视为本级通过）。</li>
 *   <li>{@link #timeoutSeconds}：本级的超时时长（秒）。不同级可以有不同时限，
 *       例如一级快审（1 小时），三级慢审（24 小时）。</li>
 * </ul>
 *
 * <p>设计为不可变 record：配置一旦下发就不该被运行时篡改，保证「同一请求的评审规则确定」。</p>
 *
 * @param level          级号（≥1）
 * @param roleName       本级审批角色名
 * @param approvers      本级候选审批人白名单（不可为空）
 * @param timeoutSeconds 本级超时时长（秒，>0）
 */
public record ApprovalLevel(int level,
                            String roleName,
                            List<String> approvers,
                            long timeoutSeconds) {

    /**
     * 紧凑构造器：做参数校验 + 防御性拷贝，保证不可变。
     */
    public ApprovalLevel {
        if (level < 1) {
            throw new IllegalArgumentException("level 至少为 1，实际=" + level);
        }
        Objects.requireNonNull(roleName, "roleName 不能为空");
        Objects.requireNonNull(approvers, "approvers 不能为空");
        if (approvers.isEmpty()) {
            throw new IllegalArgumentException("approvers 至少要有一个候选审批人");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正，实际=" + timeoutSeconds);
        }
        // 冻结列表：防御性拷贝，杜绝外部持有引用后偷改
        approvers = List.copyOf(approvers);
    }

    /**
     * 便捷工厂：单审批人的一级配置。
     */
    public static ApprovalLevel of(int level, String roleName, String approver, long timeoutSeconds) {
        return new ApprovalLevel(level, roleName, List.of(approver), timeoutSeconds);
    }

    /**
     * 判断某人是否有权审批本级。
     */
    public boolean canApprove(String approver) {
        return approvers.contains(approver);
    }
}