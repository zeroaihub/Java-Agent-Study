package com.zero.ai.agentstudy.day08multiagent.controller;

import com.zero.ai.agentstudy.day08multiagent.dto.ContentRequest;
import com.zero.ai.agentstudy.day08multiagent.dto.ContentResponse;
import com.zero.ai.agentstudy.day08multiagent.service.ContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ContentController —— 「AI 内容生产平台 V1」的 HTTP 入口。
 *
 * <p>教学要点：Controller 只负责「接收 HTTP 请求 → 委托 Service → 返回结果」，
 * 不写任何业务逻辑（薄 Controller 原则）。真正的多 Agent 协作在下层完成。</p>
 *
 * <p>提供两个端点，方便不同场景调用：</p>
 * <ul>
 *   <li>{@code POST /day08/content/produce}：标准 JSON 入参（推荐）；</li>
 *   <li>{@code GET  /day08/content/quick}：快捷入参，浏览器直接可测。</li>
 * </ul>
 *
 * <p>示例：</p>
 * <pre>
 *   curl -X POST http://localhost:8080/day08/content/produce \
 *        -H "Content-Type: application/json" \
 *        -d '{"topic":"2024年最值得推荐的AI编程工具","requirement":"面向Java初学者，800字"}'
 * </pre>
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/day08/content")
@RequiredArgsConstructor
public class ContentController {

    /** 内容生产应用服务 */
    private final ContentService contentService;

    /**
     * 标准端点：以 JSON 提交主题与要求，触发多 Agent 协作生产文章。
     *
     * @param request 内容请求
     * @return 内容生产结果（含文章、评分、意见、全链路日志）
     */
    @PostMapping("/produce")
    public ContentResponse produce(@RequestBody ContentRequest request) {
        log.info("[Controller] POST /produce，主题={}", request == null ? null : request.getTopic());
        return contentService.produce(request);
    }

    /**
     * 快捷端点：通过 URL 参数触发，方便浏览器/命令行快速体验。
     *
     * @param topic       文章主题
     * @param requirement 额外要求（可选）
     * @return 内容生产结果
     */
    @GetMapping("/quick")
    public ContentResponse quick(@RequestParam String topic,
                                 @RequestParam(required = false) String requirement) {
        log.info("[Controller] GET /quick，主题={}", topic);
        return contentService.produce(ContentRequest.builder()
                .topic(topic)
                .requirement(requirement)
                .build());
    }
}