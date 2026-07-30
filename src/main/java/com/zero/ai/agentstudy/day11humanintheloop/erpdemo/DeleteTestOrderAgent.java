package com.zero.ai.agentstudy.day11humanintheloop.erpdemo;

import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.ApprovalEngine;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 删除测试订单的 Agent（Chapter 09 实战主角）。
 *
 * <p>它把前八章的能力串成一条完整的人机协同流水线，演示 HITL 的黄金准则：
 * <b>「高危操作，机器只负责规划与执行，决策权始终握在人手里」</b>。</p>
 *
 * <p>整条流水线分三步：</p>
 * <ol>
 *   <li><b>规划（plan）</b>：Agent 扫描 ERP，找出所有待清理的测试订单，把「我想删这些单」
 *       封装成一个 {@link AgentAction}（类型 {@code ORDER_DELETE}），
 *       提交给 {@link ApprovalEngine}。引擎会用风险策略判定这是 HIGH 风险，落库为 PENDING。
 *       <b>此时一张订单都还没删</b>——Agent 在这里「主动停下来等人」。</li>
 *   <li><b>审批（人工）</b>：人通过 REST / 控制台 approve 或 reject。这一步不在本类里，
 *       由审批引擎和 Controller 负责。</li>
 *   <li><b>执行（execute）</b>：只有当审批请求进入「通过」终态后，
 *       {@link #executeIfApproved(String)} 才会真正调用仓储做软删除。
 *       任何未通过的状态都会被拒绝执行，从代码层面堵死「绕过审批直接删」的后门。</li>
 * </ol>
 *
 * <p><b>为什么执行要单独一步、还要再查一次状态？</b>因为审批是异步的——Agent 提交后
 * 可能过了几小时人才来点。执行时必须以「审批引擎里的最新状态」为准，绝不能凭 Agent
 * 自己的记忆。这是分布式系统「不要相信过期快照」的基本纪律。</p>
 */
@Service
public class DeleteTestOrderAgent {

    /** 本 Agent 产生的动作统一用这个类型，方便风险策略识别为高危。 */
    public static final String ACTION_TYPE = "ORDER_DELETE";

    private final ErpOrderRepository orderRepository;
    private final ApprovalEngine approvalEngine;

    public DeleteTestOrderAgent(ErpOrderRepository orderRepository, ApprovalEngine approvalEngine) {
        this.orderRepository = orderRepository;
        this.approvalEngine = approvalEngine;
    }

    /**
     * 第一步：规划删除动作并提交审批。
     *
     * <p>Agent 扫描出所有未删除的测试订单，把订单号列表塞进动作参数，提交给审批引擎。
     * 返回的审批请求处于 PENDING，<b>数据尚未发生任何变更</b>。</p>
     *
     * @return 已落库的审批请求（PENDING）
     * @throws IllegalStateException 没有任何测试订单可删时，不制造无意义的审批
     */
    public ApprovalRequest planDeletion() {
        List<ErpOrder> targets = orderRepository.findActiveTestOrders();
        if (targets.isEmpty()) {
            throw new IllegalStateException("当前没有可清理的测试订单，无需发起审批");
        }

        List<String> orderIds = targets.stream().map(ErpOrder::getOrderId).toList();
        String taskId = "erp-clean-" + UUID.randomUUID().toString().substring(0, 8);
        String description = "批量删除 " + orderIds.size() + " 张测试订单：" + String.join(", ", orderIds);

        AgentAction action = new AgentAction(
                taskId,
                ACTION_TYPE,
                description,
                Map.of("orderIds", orderIds, "reason", "清理测试环境残留订单"),
                null
        );

        return approvalEngine.submit(action);
    }

    /**
     * 第三步：审批通过后执行删除。
     *
     * <p>本方法是整个流程唯一「真正动数据」的入口。它先回查审批引擎里的<b>最新状态</b>，
     * 只有处于通过终态（{@link ApprovalStatus#APPROVED} 单级 /
     * {@link ApprovalStatus#FINAL_APPROVED} 多级）时才执行；否则一律拒绝并说明原因。</p>
     *
     * @param requestId 审批请求 ID
     * @return 执行结果（含被删除的订单号）
     * @throws IllegalArgumentException 审批请求不存在
     * @throws IllegalStateException    审批尚未通过 / 已被拒绝 / 已终止
     */
    @SuppressWarnings("unchecked")
    public DeletionResult executeIfApproved(String requestId) {
        ApprovalRequest request = approvalEngine.query(requestId)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + requestId));

        ApprovalStatus status = request.getStatus();
        boolean approved = status == ApprovalStatus.APPROVED
                || status == ApprovalStatus.FINAL_APPROVED;
        if (!approved) {
            throw new IllegalStateException(
                    "审批尚未通过，禁止执行删除。当前状态=" + status.name());
        }

        Object raw = request.getAction().paramOrDefault("orderIds", List.of());
        List<String> orderIds = (raw instanceof List<?> list)
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        List<String> deleted = orderRepository.deleteByIds(orderIds);
        return new DeletionResult(requestId, status.name(), deleted, orderRepository.countActive());
    }

    /**
     * 删除执行结果（读模型 / 出参投影）。
     *
     * @param requestId       审批请求 ID
     * @param approvalStatus  执行时的审批状态
     * @param deletedOrderIds 实际被删除的订单号
     * @param remainingActive 删除后剩余的未删除订单总数
     */
    public record DeletionResult(
            String requestId,
            String approvalStatus,
            List<String> deletedOrderIds,
            long remainingActive
    ) {
    }
}