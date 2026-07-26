package com.zero.ai.agentstudy.day06workflow.controller;

import com.zero.ai.agentstudy.day06workflow.dto.TravelResponse;
import com.zero.ai.agentstudy.day06workflow.service.TravelAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TravelController —— Day6 Workflow 的对外入口（Travel Agent）。
 *
 * <p>教学要点：Controller 只做「收请求、调 Service、返响应」，
 * 不含任何业务逻辑，符合单一职责。它是整条 Workflow 的触发点。</p>
 *
 * <p>体验方式（浏览器直接访问）：
 * {@code http://localhost:8080/day6/travel?input=我想去杭州玩三天}</p>
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/day6")
@RequiredArgsConstructor
public class TravelController {

    private final TravelAgentService travelAgentService;

    /**
     * 旅行规划接口。
     *
     * @param input 用户自然语言需求
     * @return 规划结果（含方案与执行轨迹）
     */
    @GetMapping("/travel")
    public TravelResponse travel(
            @RequestParam(defaultValue = "我想去杭州玩三天") String input) {
        log.info("[TravelController] 收到旅行规划请求: {}", input);
        return travelAgentService.plan(input);
    }
}