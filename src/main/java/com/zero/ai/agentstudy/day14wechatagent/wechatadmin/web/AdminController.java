package com.zero.ai.agentstudy.day14wechatagent.wechatadmin.web;

import com.zero.ai.agentstudy.day14wechatagent.wechatadmin.application.AdminDashboardService;
import com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台 REST 接口（wechatadmin 模块的入站适配器）。
 *
 * <p>与对外 API（{@code /api/**}）区隔，后台接口挂在 {@code /admin/**} 路径下，
 * 便于在网关层对整段路径施加<strong>管理员鉴权</strong>（本教学版未接入鉴权，
 * 生产应加 Spring Security 拦截 {@code /admin/**}）。</p>
 *
 * <p>接口清单：</p>
 * <ul>
 *     <li>GET  /admin/dead-letters/{tenantId}：死信队列看板。</li>
 *     <li>POST /admin/dead-letters/{id}/redeliver：管理员重投死信。</li>
 * </ul>
 *
 * @author ZeroHub AI Agent Platform
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminDashboardService dashboardService;

    /**
     * 构造函数注入看板服务。
     *
     * @param dashboardService 管理后台看板服务
     */
    public AdminController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 死信队列看板：查看某租户的死信概览。
     *
     * @param tenantId 租户 ID
     * @return 死信概览
     */
    @GetMapping("/dead-letters/{tenantId}")
    public ApiResponse<AdminDashboardService.DeadLetterOverview> deadLetters(@PathVariable String tenantId) {
        return ApiResponse.ok(dashboardService.deadLetterOverview(tenantId));
    }

    /**
     * 管理员人工重投一条死信。
     *
     * @param id 死信通知 ID
     * @return 重投后的最新状态
     */
    @PostMapping("/dead-letters/{id}/redeliver")
    public ApiResponse<String> redeliver(@PathVariable String id) {
        log.info("[Admin] 管理员触发死信重投，id={}", id);
        return ApiResponse.ok(dashboardService.redeliver(id));
    }
}