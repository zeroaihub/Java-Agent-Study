package com.zero.ai.agentstudy.day11humanintheloop.approvalengine;

import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository.ApprovalRepository;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalDecision;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalTransition;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.RiskLevel;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.spi.RiskPolicy;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.statemachine.ApprovalStateMachine;

import java.util.Map;
import java.util.Optional;

/**
 * 审批引擎默认实现。
 *
 * <p>它是「编排者」：把风险策略、状态机、仓储三个协作者组合起来完成审批用例。
 * 本类不自己发明任何状态流转规则——所有流转都委托给 {@link ApprovalStateMachine}，
 * 从而保证「规则只有一个来源」。</p>
 *
 * <p><b>并发说明：</b>教学实现用「加载 → 变更 → 保存」的朴素方式。真实分布式环境下，
 * approve/reject 等写操作必须加分布式锁（如 Redisson，按 requestId 加锁），否则两个
 * 审批人同时点会产生竞态。锁的接入点就在每个写方法的开头，本章先留出扩展位。</p>
 */
public class DefaultApprovalEngine implements ApprovalEngine {

    private final RiskPolicy riskPolicy;
    private final ApprovalStateMachine stateMachine;
    private final ApprovalRepository repository;

    public DefaultApprovalEngine(RiskPolicy riskPolicy,
                                 ApprovalStateMachine stateMachine,
                                 ApprovalRepository repository) {
        this.riskPolicy = riskPolicy;
        this.stateMachine = stateMachine;
        this.repository = repository;
    }

    @Override
    public ApprovalRequest submit(AgentAction action) {
        RiskLevel level = riskPolicy.evaluate(action);
        int levels = decideLevels(level);
        ApprovalRequest request = (levels <= 1)
                ? ApprovalRequest.single(action, level)
                : ApprovalRequest.multiLevel(action, level, levels);
        repository.save(request);
        return request;
    }

    /**
     * 风险等级 -> 需要几级审批。
     * <p>约定：NONE 也建一级（便于统一流程与留痕）；LOW 一级；HIGH 两级会签。
     * 真实项目里这里可以做成可配置的策略。</p>
     */
    private int decideLevels(RiskLevel level) {
        return switch (level) {
            case NONE, LOW -> 1;
            case HIGH -> 2;
        };
    }

    @Override
    public ApprovalStatus approve(String requestId, String approver, String comment) {
        ApprovalRequest request = require(requestId);
        ApprovalDecision decision = ApprovalDecision.approve(approver, comment);
        ApprovalStatus status = stateMachine.fire(request, decision);
        repository.save(request);
        return status;
    }

    @Override
    public ApprovalStatus reject(String requestId, String approver, String comment) {
        ApprovalRequest request = require(requestId);
        ApprovalDecision decision = ApprovalDecision.reject(approver, comment);
        ApprovalStatus status = stateMachine.fire(request, decision);
        repository.save(request);
        return status;
    }

    @Override
    public ApprovalStatus modify(String requestId, String approver, String comment,
                             Map<String, Object> modifiedParams) {
        ApprovalRequest request = require(requestId);
        ApprovalDecision decision = ApprovalDecision.modify(approver, comment, modifiedParams);
        ApprovalStatus status = stateMachine.fire(request, decision);
        repository.save(request);
        return status;
    }

    @Override
    public ApprovalStatus resubmit(String requestId, String operator) {
        ApprovalRequest request = require(requestId);
        ApprovalDecision decision = new ApprovalDecision(
                operator, ApprovalTransition.RESUBMIT, "重新提交审批", Map.of(), null);
        ApprovalStatus status = stateMachine.fire(request, decision);
        repository.save(request);
        return status;
    }

    @Override
    public ApprovalStatus abort(String requestId, String operator, String reason) {
        ApprovalRequest request = require(requestId);
        ApprovalDecision decision = new ApprovalDecision(
                operator, ApprovalTransition.ABORT, reason, Map.of(), null);
        ApprovalStatus status = stateMachine.fire(request, decision);
        repository.save(request);
        return status;
    }

    @Override
    public Optional<ApprovalRequest> query(String requestId) {
        return repository.findById(requestId);
    }

    /**
     * 加载并校验请求存在，不存在抛异常。
     */
    private ApprovalRequest require(String requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + requestId));
    }
}