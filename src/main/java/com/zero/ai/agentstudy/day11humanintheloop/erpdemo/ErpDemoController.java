package com.zero.ai.agentstudy.day11humanintheloop.erpdemo;

import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.ApprovalView;
import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.ApprovalEngine;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ERP 批量删单实战 Controller（Chapter 09）。
 *
 * <p>这是把「HITL 内核 + 审批引擎 + 删单 Agent」串起来给外部演示的门面。它刻意做得薄——
 * 只负责「翻译 HTTP 请求 → 委托 Agent/引擎 → 投影结果」，一行业务逻辑都不写，
 * 业务全在 {@link DeleteTestOrderAgent} 和审批引擎里。异常交给全局异常处理器统一翻译。</p>
 *
 * <p>端点设计对应删单流水线的三步 + 两个查询：</p>
 * <ol>
 *   <li>{@code GET  /day11/erp/orders}                查看当前订单快照（删除前后对比用）</li>
 *   <li>{@code POST /day11/erp/clean-test-orders}     Agent 规划删除并提交审批（返回 PENDING）</li>
 *   <li>{@code GET  /day11/erp/requests/{id}}         查看该审批请求当前状态与审计链</li>
 *   <li>{@code POST /day11/erp/requests/{id}/execute} 审批通过后真正执行删除</li>
 * </ol>
 *
 * <p>注意：审批的 approve / reject 复用 Chapter 08 已有的
 * {@code /day11/approvals/{id}/approve} 等端点，本 Controller 不重复造。这体现了
 * 「删单 Agent 只是审批引擎的一个普通接入方」——它产生的审批请求和其它任何请求一样，
 * 走同一套审批 / 控制台 / 审计流程。</p>
 */
@RestController
@RequestMapping("/day11/erp")
public class ErpDemoController {

    private final ErpOrderRepository orderRepository;
    private final DeleteTestOrderAgent deleteAgent;
    private final ApprovalEngine approvalEngine;

    public ErpDemoController(ErpOrderRepository orderRepository,
                             DeleteTestOrderAgent deleteAgent,
                             ApprovalEngine approvalEngine) {
        this.orderRepository = orderRepository;
        this.deleteAgent = deleteAgent;
        this.approvalEngine = approvalEngine;
    }

    /**
     * 查看当前订单快照。
     * <p>返回所有未删除订单及其是否测试单标记，方便在删除前后对比效果。</p>
     */
    @GetMapping("/orders")
    public Map<String, Object> listOrders() {
        List<Map<String, Object>> orders = orderRepository.findAllActive().stream()
                .map(this::toOrderView)
                .collect(Collectors.toList());
        return Map.of(
                "total", orders.size(),
                "orders", orders
        );
    }

    /**
     * 第一步：Agent 规划删除并提交审批。
     *
     * @return 新建的审批请求视图（PENDING，数据尚未变更）
     */
    @PostMapping("/clean-test-orders")
    public ApprovalView cleanTestOrders() {
        ApprovalRequest request = deleteAgent.planDeletion();
        return ApprovalView.from(request);
    }

    /**
     * 查看某个审批请求的当前状态与审计链。
     *
     * @param id 审批请求 ID
     */
    @GetMapping("/requests/{id}")
    public ApprovalView queryRequest(@PathVariable String id) {
        ApprovalRequest request = approvalEngine.query(id)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + id));
        return ApprovalView.from(request);
    }

    /**
     * 第三步：审批通过后真正执行删除。
     *
     * <p>若审批尚未通过，{@link DeleteTestOrderAgent#executeIfApproved(String)}
     * 会抛 {@link IllegalStateException}，被全局异常处理器翻译成 409，
     * 从流程上确保「没批就删不了」。</p>
     *
     * @param id 审批请求 ID
     * @return 删除执行结果
     */
    @PostMapping("/requests/{id}/execute")
    public DeleteTestOrderAgent.DeletionResult execute(@PathVariable String id) {
        return deleteAgent.executeIfApproved(id);
    }

    /** 把订单实体投影成前端友好的扁平视图。 */
    private Map<String, Object> toOrderView(ErpOrder order) {
        return Map.of(
                "orderId", order.getOrderId(),
                "customer", order.getCustomer(),
                "amount", order.getAmount(),
                "testOrder", order.isTestOrder(),
                "deleted", order.isDeleted()
        );
    }
}