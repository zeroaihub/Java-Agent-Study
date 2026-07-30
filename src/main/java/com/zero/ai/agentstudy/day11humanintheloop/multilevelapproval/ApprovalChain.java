package com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval;

import java.util.List;
import java.util.Objects;

/**
 * 审批链（Approval Chain）——把「若干级 {@link ApprovalLevel}」串成一条完整的会签路线。
 *
 * <p>如果说 {@code ApprovalLevel} 描述「第 N 级的规则」，那么 {@code ApprovalChain} 就是
 * 「整条流水线」：从一级到 N 级，谁先谁后、每级多久超时，一次性定义清楚。</p>
 *
 * <p>它对上层提供两个核心能力：</p>
 * <ul>
 *   <li>按「当前已通过级数」定位「下一个该谁批」（{@link #levelFor(int)}）；</li>
 *   <li>回答「这条链一共几级」（{@link #totalLevels()}），供创建 {@code ApprovalRequest} 时使用。</li>
 * </ul>
 *
 * <p>构造时强制校验：级号必须从 1 开始、连续、不重复、按序排列——避免「配了 1 级和 3 级、
 * 却漏了 2 级」这种运行期才爆炸的坑。</p>
 */
public record ApprovalChain(String chainId, List<ApprovalLevel> levels) {

    /**
     * 紧凑构造器：校验层级完整性（1..N 连续），并做防御性拷贝。
     */
    public ApprovalChain {
        Objects.requireNonNull(chainId, "chainId 不能为空");
        Objects.requireNonNull(levels, "levels 不能为空");
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("审批链至少要有一级");
        }
        // 校验：级号必须严格等于 1,2,3...N，保证「不缺级、不乱序、不重复」
        for (int i = 0; i < levels.size(); i++) {
            int expected = i + 1;
            int actual = levels.get(i).level();
            if (actual != expected) {
                throw new IllegalArgumentException(
                        "审批链级号必须从 1 连续递增，位置 " + i + " 期望级号=" + expected + "，实际=" + actual);
            }
        }
        levels = List.copyOf(levels);
    }

    /**
     * 便捷工厂：直接用可变参数拼一条链。
     */
    public static ApprovalChain of(String chainId, ApprovalLevel... levels) {
        return new ApprovalChain(chainId, List.of(levels));
    }

    /**
     * 一共几级。用于 {@code ApprovalRequest.multiLevel(action, risk, totalLevels())}。
     */
    public int totalLevels() {
        return levels.size();
    }

    /**
     * 根据「已通过级数」取出「接下来该批的那一级」的配置。
     *
     * <p>约定：approvedLevels=0 表示还没人批，下一个该批的是「第 1 级」，即 levels 下标 0。
     * 因此下标 = approvedLevels。</p>
     *
     * @param approvedLevels 已通过的级数（来自 {@code ApprovalRequest.getApprovedLevels()}）
     * @return 下一个该批的层级配置
     * @throws IllegalArgumentException 已经批完却还来问下一级
     */
    public ApprovalLevel levelFor(int approvedLevels) {
        if (approvedLevels < 0 || approvedLevels >= levels.size()) {
            throw new IllegalArgumentException(
                    "没有对应的下一级：approvedLevels=" + approvedLevels + "，总级数=" + levels.size());
        }
        return levels.get(approvedLevels);
    }

    /**
     * 校验「某人此刻是否有权批当前这一级」。
     *
     * @param approver       审批人
     * @param approvedLevels 已通过级数（决定当前该批第几级）
     * @return true=有权批当前级
     */
    public boolean canApproveNow(String approver, int approvedLevels) {
        if (approvedLevels >= levels.size()) {
            return false;
        }
        return levels.get(approvedLevels).canApprove(approver);
    }
}