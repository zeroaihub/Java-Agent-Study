package com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval;

import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository.ApprovalRepository;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalDecision;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalTransition;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.statemachine.ApprovalStateMachine;

import java.util.Map;

/**
 * 审批超时处理器（Approval Timeout Handler）——负责「到点没人批」的兜底逻辑。
 *
 * <p>审批不能无限期挂着：如果一个高危动作卡在 PENDING 三天没人管，既拖累业务、又
 * 埋着安全隐患。本处理器就是那个「定时来巡逻的哨兵」：发现某个请求超时了，就按预先
 * 配置的 {@link TimeoutPolicy} 决定它的命运——拒绝、升级、还是自动通过。</p>
 *
 * <p><b>如何被驱动？</b> 本类只暴露「处理单个请求」和「扫描全部」两个纯方法，不含定时器。
 * 真实项目里由 Spring 的 {@code @Scheduled} 或 Quartz 定时调用 {@link #sweep(TimeoutPolicy)}，
 * 把「什么时候扫」（调度）和「扫到了怎么办」（策略）解耦。教学中我们直接手动调用。</p>
 *
 * <p><b>为什么超时也要走状态机？</b> 因为「超时 → TIMEOUT」本身就是一条合法流转边，
 * 让它和 approve/reject 走同一套 {@code fire}，才能保证审计链完整、状态一致，
 * 而不是绕过状态机偷偷改状态。</p>
 */
public class ApprovalTimeoutHandler {

    private final ApprovalStateMachine stateMachine;
    private final ApprovalRepository repository;

    public ApprovalTimeoutHandler(ApprovalStateMachine stateMachine,
                                  ApprovalRepository repository) {
        this.stateMachine = stateMachine;
        this.repository = repository;
    }

    /**
     * 扫描全部请求，对已超时且仍处于非终态的请求施加超时策略。
     *
     * @param policy 超时策略
     * @return 本轮实际处理（发生状态变更）的请求数
     */
    public int sweep(TimeoutPolicy policy) {
        int handled = 0;
        // 超时只可能发生在等待中的请求（PENDING）；MODIFIED 也可能挂着，一并纳入扫描
        for (ApprovalRequest request : repository.findByStatus(ApprovalStatus.PENDING)) {
            if (handleIfExpired(request, policy)) {
                handled++;
            }
        }
        for (ApprovalRequest request : repository.findByStatus(ApprovalStatus.MODIFIED)) {
            if (handleIfExpired(request, policy)) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * 判断单个请求是否超时，超时则按策略处理。
     *
     * @param request 审批请求
     * @param policy  超时策略
     * @return true=发生了超时处理；false=未超时或已终态，无动作
     */
    public boolean handleIfExpired(ApprovalRequest request, TimeoutPolicy policy) {
        // 已经是终态（已批 / 已拒 / 已终止）就不用管了
        if (request.getStatus().isTerminal()) {
            return false;
        }
        // 没到期不处理
        if (!request.isExpired()) {
            return false;
        }
        applyPolicy(request, policy);
        repository.save(request);
        return true;
    }

    /**
     * 按策略把超时请求推向对应的结局。
     *
     * <p>三种策略的落地：</p>
     * <ul>
     *   <li>REJECT：先 TIMEOUT 记录超时事件，再等价于驳回终态（本模型 TIMEOUT 即终态，安全）。</li>
     *   <li>ESCALATE：记录一条超时升级的审计，状态仍走 TIMEOUT 终态，由上层重新发起「兜底审批链」
     *       （本教学模型不做自动重开，升级动作留给业务层，此处只把语义记进审计）。</li>
     *   <li>AUTO_APPROVE：直接补一条系统 APPROVE，驱动状态机推进（多级会签下会自动一路 FINALIZE）。</li>
     * </ul>
     */
    private void applyPolicy(ApprovalRequest request, TimeoutPolicy policy) {
        switch (policy) {
            case REJECT, ESCALATE -> {
                // 记录一条系统超时决策，走 TIMEOUT 合法边进入终态
                ApprovalDecision timeoutDecision = new ApprovalDecision(
                        "SYSTEM",
                        ApprovalTransition.TIMEOUT,
                        policy == TimeoutPolicy.REJECT ? "审批超时，自动拒绝" : "审批超时，转人工升级兜底",
                        Map.of(),
                        null);
                stateMachine.fire(request, timeoutDecision);
            }
            case AUTO_APPROVE -> {
                // 用系统身份补批当前级，状态机会自动判断 NEXT_LEVEL / FINALIZE
                // 注意：多级会签下，一次 AUTO_APPROVE 只推进一级；若要一路放行需循环
                while (!request.getStatus().isTerminal()
                        && request.getStatus() == ApprovalStatus.PENDING) {
                    ApprovalDecision autoApprove = ApprovalDecision.approve(
                            "SYSTEM", "审批超时，低风险自动通过");
                    stateMachine.fire(request, autoApprove);
                }
            }
        }
    }

    /**
     * 给定请求当前的目标结局标签（用于日志 / 前端提示，不改状态）。
     */
    public String describeOutcome(TimeoutPolicy policy) {
        return switch (policy) {
            case REJECT -> "到期将自动拒绝";
            case ESCALATE -> "到期将升级至兜底审批人";
            case AUTO_APPROVE -> "到期将自动通过";
        };
    }
}