package com.zero.ai.agentstudy.day11humanintheloop.approvalcenter;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业审批中心控制器——全模块的「运营总览」HTTP 门面。
 *
 * <p>这是 Day 11 的收官端点：它不发起审批、不推进流转，只把 {@link ApprovalCenterService}
 * 聚合出的仪表盘快照暴露成一个只读接口。运营/管理员打开控制台首页，第一屏看的就是它。</p>
 *
 * <p><b>端点一览：</b></p>
 * <pre>
 *   GET /day11/approval-center/dashboard   审批中心总览（状态分布 / 风险分布 / 待办摘要）
 * </pre>
 *
 * <p><b>为什么只有一个 GET：</b>审批中心是读侧聚合。所有「写」操作（提交、审批、驳回、
 * 执行删除）都已经在前九章的各自 Controller 里各司其职。收官章节要做的不是再造轮子，
 * 而是把散落的能力聚成一块「一眼看全局」的屏。职责单一，才好维护。</p>
 */
@RestController
@RequestMapping("/day11/approval-center")
public class ApprovalCenterController {

    private final ApprovalCenterService approvalCenterService;

    public ApprovalCenterController(ApprovalCenterService approvalCenterService) {
        this.approvalCenterService = approvalCenterService;
    }

    /**
     * 审批中心总览。
     *
     * <p>返回实时聚合的仪表盘快照：总数、各状态计数、风险分布、终态数，以及最近待办摘要。
     * 每次调用都重新聚合，保证数据实时——教学内存实现下开销可忽略；生产环境如量大可加
     * 短 TTL 缓存或改为预聚合。</p>
     *
     * @return 仪表盘快照
     */
    @GetMapping("/dashboard")
    public ApprovalDashboard dashboard() {
        return approvalCenterService.buildDashboard();
    }
}