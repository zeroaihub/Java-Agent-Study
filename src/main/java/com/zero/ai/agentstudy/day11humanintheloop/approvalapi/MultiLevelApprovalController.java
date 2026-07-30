package com.zero.ai.agentstudy.day11humanintheloop.approvalapi;

import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.ApprovalView;
import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.DecisionRequest;
import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.SubmitMultiLevelRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.RiskLevel;
import com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval.ApprovalChain;
import com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval.ApprovalLevel;
import com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval.MultiLevelApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多级会签 REST 控制器——把 {@link MultiLevelApprovalService} 的会签能力暴露成 HTTP 端点。
 *
 * <p>它与 {@code ApprovalController}（单级审批）是「同层的两个入口」，职责单一、互不干扰：
 * 本控制器只处理「需要多人逐级会签」的场景，路径前缀独立为 {@code /day11/multi-approvals}，
 * 避免把单级、多级揉进一个「上帝控制器」。</p>
 *
 * <p><b>薄控制器原则：</b>本类只做三件事——① 把 DTO 翻译成领域对象（把扁平的 LevelSpec 组装成
 * {@code ApprovalChain}）；② 委托 {@link MultiLevelApprovalService} 执行；③ 回查最新请求并投影成
 * {@link ApprovalView}。业务规则（级号连续校验、权限校验、状态流转）全在领域层，控制器不掺和。</p>
 *
 * <p><b>为什么变更后要「回查」？</b>Service 的 approve/reject 只返回 {@code ApprovalStatus} 枚举，
 * 而前端需要完整的最新详情（含审计链、当前级），故统一在动作后 {@link #reload(String)} 回查投影。</p>
 */
@RestController
@RequestMapping("/day11/multi-approvals")
public class MultiLevelApprovalController {

    private final MultiLevelApprovalService multiLevelService;

    public MultiLevelApprovalController(MultiLevelApprovalService multiLevelService) {
        this.multiLevelService = multiLevelService;
    }

    /**
     * 提交一条多级会签请求。
     *
     * <p>Controller 把前端扁平的 {@code LevelSpec} 列表组装成领域层的 {@code ApprovalChain}，
     * 级号连续性、审批人非空、超时为正等校验由 {@code ApprovalChain / ApprovalLevel} 的紧凑
     * 构造器兜底——若不合法会抛 {@code IllegalArgumentException}，被全局异常处理器转成 400。</p>
     */
    @PostMapping
    public ApprovalView submit(@RequestBody SubmitMultiLevelRequest body) {
        AgentAction action = new AgentAction(
                body.taskId(),
                body.actionType(),
                body.description(),
                body.params(),
                body.amount());

        RiskLevel risk = RiskLevel.valueOf(body.riskLevelOrDefault());
        ApprovalChain chain = toChain(body);

        ApprovalRequest request = multiLevelService.submit(action, risk, chain);
        return ApprovalView.from(request);
    }

    /**
     * 审批当前级（会签向前推进一级；若已是最后一级则整体通过）。
     */
    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable("id") String id,
                                @RequestBody DecisionRequest body) {
        multiLevelService.approve(id, body.operatorOrAnonymous(), body.comment());
        return reload(id);
    }

    /**
     * 驳回（任一级审批人驳回即整体进入 REJECTED 终态）。
     */
    @PostMapping("/{id}/reject")
    public ApprovalView reject(@PathVariable("id") String id,
                               @RequestBody DecisionRequest body) {
        multiLevelService.reject(id, body.operatorOrAnonymous(), body.comment());
        return reload(id);
    }

    /**
     * 查询审批请求的最新详情（含审计链）。
     */
    @GetMapping("/{id}")
    public ApprovalView query(@PathVariable("id") String id) {
        return reload(id);
    }

    /**
     * 查询「此刻轮到哪一级、由谁批」——供前端渲染「当前待办审批人」。
     *
     * <p>若请求已终态，则返回一个 {@code terminal=true} 的提示体，而非抛错。</p>
     */
    @GetMapping("/{id}/current-level")
    public Map<String, Object> currentLevel(@PathVariable("id") String id) {
        return multiLevelService.currentLevel(id)
                .map(level -> Map.<String, Object>of(
                        "terminal", false,
                        "level", level.level(),
                        "roleName", level.roleName(),
                        "approvers", level.approvers(),
                        "timeoutSeconds", level.timeoutSeconds()))
                .orElseGet(() -> Map.of(
                        "terminal", true,
                        "message", "该请求已进入终态，无待批层级"));
    }

    /**
     * 把提交 DTO 里的扁平层级定义翻译成领域层的审批链。
     */
    private ApprovalChain toChain(SubmitMultiLevelRequest body) {
        List<SubmitMultiLevelRequest.LevelSpec> specs = body.levels();
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("多级会签至少要定义一级审批层级");
        }
        List<ApprovalLevel> levels = specs.stream()
                .map(s -> new ApprovalLevel(
                        s.level(),
                        s.roleName(),
                        s.approvers(),
                        s.timeoutSeconds()))
                .toList();
        return new ApprovalChain(body.chainIdOrDefault(), levels);
    }

    /**
     * 回查最新请求并投影成视图。请求不存在时抛 {@code IllegalArgumentException}（→ 400）。
     */
    private ApprovalView reload(String id) {
        ApprovalRequest request = multiLevelService.query(id)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + id));
        return ApprovalView.from(request);
    }
}