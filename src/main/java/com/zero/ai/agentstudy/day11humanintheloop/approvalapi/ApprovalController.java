package com.zero.ai.agentstudy.day11humanintheloop.approvalapi;

import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.ApprovalView;
import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.DecisionRequest;
import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.SubmitApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.ApprovalEngine;
import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository.ApprovalRepository;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审批 REST 控制器——把前七章沉淀的审批引擎能力，暴露成一组 HTTP 端点。
 *
 * <p><b>薄 Controller 原则：</b>本类不写任何业务逻辑，只做三件事：
 * ①把 HTTP 入参 DTO 翻译成领域对象；②委托 {@link ApprovalEngine} 执行用例；
 * ③把领域结果投影成响应 DTO 返回。所有审批规则、状态流转都在下层，Controller
 * 只是「用例的 HTTP 适配器（Inbound Adapter）」。</p>
 *
 * <p><b>端点一览（单级审批）：</b></p>
 * <pre>
 *   POST /day11/approvals                提交审批（创建 PENDING 请求）
 *   GET  /day11/approvals/{id}           查询某审批详情
 *   GET  /day11/approvals/pending        查询全部待办（PENDING）
 *   POST /day11/approvals/{id}/approve   通过
 *   POST /day11/approvals/{id}/reject    驳回
 *   POST /day11/approvals/{id}/modify    修改动作参数
 *   POST /day11/approvals/{id}/resubmit  修改后重新提交
 *   POST /day11/approvals/{id}/abort     主动终止
 * </pre>
 *
 * <p>多级会签、反馈相关端点分别放在 {@code MultiLevelApprovalController} 与
 * {@code FeedbackController}，各自单一职责，避免一个 Controller 膨胀成上帝类。</p>
 */
@RestController
@RequestMapping("/day11/approvals")
public class ApprovalController {

    private final ApprovalEngine approvalEngine;
    private final ApprovalRepository approvalRepository;

    public ApprovalController(ApprovalEngine approvalEngine,
                              ApprovalRepository approvalRepository) {
        this.approvalEngine = approvalEngine;
        this.approvalRepository = approvalRepository;
    }

    /**
     * 提交审批：把前端描述的动作创建为审批请求。
     *
     * <p>引擎会自动用风险策略评估等级、决定审批级数，并落库为 PENDING。</p>
     *
     * @param body 提交审批请求 DTO
     * @return 新建审批请求的视图
     */
    @PostMapping
    public ApprovalView submit(@RequestBody SubmitApprovalRequest body) {
        AgentAction action = new AgentAction(
                body.taskId(),
                body.actionType(),
                body.description(),
                body.params(),
                body.amount()
        );
        ApprovalRequest request = approvalEngine.submit(action);
        return ApprovalView.from(request);
    }

    /**
     * 查询某审批请求详情。
     *
     * @param id 审批请求 ID
     * @return 审批视图；不存在时抛 {@link IllegalArgumentException}（由全局异常处理器转 404）
     */
    @GetMapping("/{id}")
    public ApprovalView query(@PathVariable("id") String id) {
        return approvalEngine.query(id)
                .map(ApprovalView::from)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + id));
    }

    /**
     * 查询全部待办（PENDING 状态）——审批控制台首页的数据源。
     *
     * @return 待办审批视图列表
     */
    @GetMapping("/pending")
    public List<ApprovalView> pending() {
        return approvalRepository.findByStatus(ApprovalStatus.PENDING).stream()
                .map(ApprovalView::from)
                .toList();
    }

    /**
     * 通过（推进一级；多级会签下可能仍是 PENDING 等下一级）。
     */
    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable("id") String id, @RequestBody DecisionRequest body) {
        approvalEngine.approve(id, body.operatorOrAnonymous(), body.comment());
        return reload(id);
    }

    /**
     * 驳回（进入终态 REJECTED）。
     */
    @PostMapping("/{id}/reject")
    public ApprovalView reject(@PathVariable("id") String id, @RequestBody DecisionRequest body) {
        approvalEngine.reject(id, body.operatorOrAnonymous(), body.comment());
        return reload(id);
    }

    /**
     * 人工修改动作参数（进入 MODIFIED，等待重新提交）。
     */
    @PostMapping("/{id}/modify")
    public ApprovalView modify(@PathVariable("id") String id, @RequestBody DecisionRequest body) {
        approvalEngine.modify(id, body.operatorOrAnonymous(), body.comment(), body.modifiedParams());
        return reload(id);
    }

    /**
     * 把 MODIFIED 的请求重新提交审批（回到 PENDING）。
     */
    @PostMapping("/{id}/resubmit")
    public ApprovalView resubmit(@PathVariable("id") String id, @RequestBody DecisionRequest body) {
        approvalEngine.resubmit(id, body.operatorOrAnonymous());
        return reload(id);
    }

    /**
     * 主动终止（进入终态 ABORTED）。
     */
    @PostMapping("/{id}/abort")
    public ApprovalView abort(@PathVariable("id") String id, @RequestBody DecisionRequest body) {
        approvalEngine.abort(id, body.operatorOrAnonymous(), body.comment());
        return reload(id);
    }

    /**
     * 重新加载最新视图。审批引擎的动作方法只返回状态枚举，前端往往还想拿到完整最新详情
     * （含审计链），因此变更后统一回查一次，把最新聚合根投影返回。
     */
    private ApprovalView reload(String id) {
        return approvalEngine.query(id)
                .map(ApprovalView::from)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + id));
    }
}