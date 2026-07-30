package com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval;

import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository.ApprovalRepository;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalDecision;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.RiskLevel;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.statemachine.ApprovalStateMachine;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多级审批编排器（Multi-Level Approval Service）——把「审批链 + 状态机 + 仓储」编排成
 * 一条可以逐级流转的会签流水线。
 *
 * <p>它和 Chapter 03 的 {@code DefaultApprovalEngine} 是「同层的两个用例入口」：后者管
 * 单级审批，本类专攻「多级会签 + 每级权限校验 + 超时处理」。二者都不发明流转规则，
 * 一律委托 {@link ApprovalStateMachine}，保证「规则单一来源」。</p>
 *
 * <p><b>本类的三件核心工作：</b></p>
 * <ol>
 *   <li>提交：按审批链的级数创建多级 {@link ApprovalRequest}，并记住它绑定的链。</li>
 *   <li>逐级审批：approve 前先用 {@link ApprovalChain#canApproveNow} 校验「这个人有没有资格
 *       批当前这一级」，再委托状态机推进（状态机内部自动判断 NEXT_LEVEL / FINALIZE）。</li>
 *   <li>超时判定：交给 {@link ApprovalTimeoutHandler}，本类只负责把「请求 + 链」喂过去。</li>
 * </ol>
 *
 * <p><b>并发说明：</b>与单级引擎相同，写操作在生产环境需按 requestId 加分布式锁。</p>
 */
public class MultiLevelApprovalService {

    private final ApprovalStateMachine stateMachine;
    private final ApprovalRepository repository;

    /** 记住每个请求绑定的审批链：requestId -> chain。生产环境应随请求持久化。 */
    private final Map<String, ApprovalChain> boundChains = new ConcurrentHashMap<>();

    public MultiLevelApprovalService(ApprovalStateMachine stateMachine,
                                     ApprovalRepository repository) {
        this.stateMachine = stateMachine;
        this.repository = repository;
    }

    /**
     * 按审批链提交一个多级审批请求。
     *
     * @param action 被审批的动作
     * @param risk   风险等级
     * @param chain  审批链（决定几级、每级谁批）
     * @return 新建并落库的审批请求（PENDING，等第 1 级审批）
     */
    public ApprovalRequest submit(AgentAction action, RiskLevel risk, ApprovalChain chain) {
        ApprovalRequest request = ApprovalRequest.multiLevel(action, risk, chain.totalLevels());
        repository.save(request);
        boundChains.put(request.getRequestId(), chain);
        return request;
    }

    /**
     * 审批当前级。
     *
     * <p>流程：①加载请求 → ②权限校验（这个人能不能批当前级）→ ③委托状态机推进
     * （若还有下一级 → 回到 PENDING 等下一位；若都过了 → FINAL_APPROVED）→ ④落库。</p>
     *
     * @param requestId 请求 ID
     * @param approver  审批人
     * @param comment   审批意见
     * @return 变更后的最新状态
     * @throws IllegalArgumentException 请求不存在
     * @throws IllegalStateException    审批人无权批当前级
     */
    public ApprovalStatus approve(String requestId, String approver, String comment) {
        ApprovalRequest request = require(requestId);
        ApprovalChain chain = requireChain(requestId);

        // 权限校验：approvedLevels 决定「当前该批第几级」，只有白名单里的人才放行
        if (!chain.canApproveNow(approver, request.getApprovedLevels())) {
            int currentLevel = request.getApprovedLevels() + 1;
            throw new IllegalStateException(
                    "审批人 [" + approver + "] 无权审批第 " + currentLevel + " 级");
        }

        ApprovalDecision decision = ApprovalDecision.approve(approver, comment);
        ApprovalStatus status = stateMachine.fire(request, decision);
        repository.save(request);
        return status;
    }

    /**
     * 驳回（任一级审批人驳回即整体进入 REJECTED 终态）。
     */
    public ApprovalStatus reject(String requestId, String approver, String comment) {
        ApprovalRequest request = require(requestId);
        ApprovalChain chain = requireChain(requestId);
        if (!chain.canApproveNow(approver, request.getApprovedLevels())) {
            int currentLevel = request.getApprovedLevels() + 1;
            throw new IllegalStateException(
                    "审批人 [" + approver + "] 无权驳回第 " + currentLevel + " 级");
        }
        ApprovalDecision decision = ApprovalDecision.reject(approver, comment);
        ApprovalStatus status = stateMachine.fire(request, decision);
        repository.save(request);
        return status;
    }

    /**
     * 查询「此刻轮到哪一级、由谁批」。供前端渲染「当前待办审批人」。
     *
     * @return 当前待批的层级配置；若请求已终态则返回空
     */
    public Optional<ApprovalLevel> currentLevel(String requestId) {
        ApprovalRequest request = require(requestId);
        ApprovalChain chain = requireChain(requestId);
        if (request.getStatus().isTerminal()) {
            return Optional.empty();
        }
        int idx = request.getApprovedLevels();
        if (idx >= chain.totalLevels()) {
            return Optional.empty();
        }
        return Optional.of(chain.levelFor(idx));
    }

    public Optional<ApprovalRequest> query(String requestId) {
        return repository.findById(requestId);
    }

    /**
     * 取出请求绑定的审批链（供超时处理器使用）。
     */
    public ApprovalChain chainOf(String requestId) {
        return requireChain(requestId);
    }

    private ApprovalRequest require(String requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + requestId));
    }

    private ApprovalChain requireChain(String requestId) {
        ApprovalChain chain = boundChains.get(requestId);
        if (chain == null) {
            throw new IllegalArgumentException("请求未绑定审批链：" + requestId);
        }
        return chain;
    }
}