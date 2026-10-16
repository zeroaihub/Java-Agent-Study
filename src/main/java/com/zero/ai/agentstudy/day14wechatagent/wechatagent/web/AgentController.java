package com.zero.ai.agentstudy.day14wechatagent.wechatagent.web;

import com.zero.ai.agentstudy.day14wechatagent.wechatagent.application.MeetingSummaryOrchestrator;
import com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体入口控制器（第十章 · 终极场景 · 入站适配器）。
 *
 * <p>这是整个平台面向「企业微信用户指令」的统一入口。真实生产环境里，企业微信的消息
 * 回调会先进入 {@code WebhookController}（第九章），验签解密后转成结构化指令，再调用本控制器
 * 的编排入口。为聚焦终极场景的<strong>业务编排</strong>，本控制器直接暴露一个简化的 JSON 入口，
 * 让你能用 curl / Postman 直接触发完整链路，而不必搭建企微回调环境。</p>
 *
 * <p>三个接口对应终极场景的三个交互时刻：</p>
 * <ul>
 *     <li>{@code POST /api/agent/instruct}：用户下达自然语言指令，触发全链路。</li>
 *     <li>{@code POST /api/agent/approve}：Human Approval 人工放行高影响步骤（如发通知）。</li>
 *     <li>{@code POST /api/agent/reject}：人工驳回，终止流程。</li>
 * </ul>
 *
 * <p><strong>Controller 零业务逻辑</strong>：只做校验 + 翻译 + 编排调用，业务全在
 * {@link MeetingSummaryOrchestrator}。响应统一走第九章的 {@link ApiResponse}，异常统一由
 * {@code GlobalExceptionHandler} 兜底。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final MeetingSummaryOrchestrator orchestrator;

    /**
     * 构造控制器。
     *
     * @param orchestrator 会议纪要全链路编排器
     */
    public AgentController(MeetingSummaryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 下达自然语言指令，触发会议纪要全链路。
     *
     * <p>示例请求体：</p>
     * <pre>
     * {
     *   "tenantId": "acme",
     *   "userId": "u_1001",
     *   "conversationId": "group_dev",
     *   "instruction": "帮我整理今天所有会议内容，生成纪要并发给项目组，重要内容存知识库"
     * }
     * </pre>
     *
     * @param request 指令请求
     * @return 智能体处理结果（含任务 ID、状态、各步骤进展；若挂起则含待审批步骤 ID）
     */
    @PostMapping("/instruct")
    public ApiResponse<MeetingSummaryOrchestrator.AgentReply> instruct(
            @Valid @RequestBody InstructRequest request) {
        MeetingSummaryOrchestrator.AgentReply reply = orchestrator.handle(
                request.tenantId(), request.userId(), request.conversationId(), request.instruction());
        return ApiResponse.ok(reply);
    }

    /**
     * 人工审批放行：让挂起的任务从审批步骤续跑。
     *
     * @param taskId 任务 ID
     * @param stepId 待审批步骤 ID
     * @return 续跑后的处理结果
     */
    @PostMapping("/{taskId}/steps/{stepId}/approve")
    public ApiResponse<MeetingSummaryOrchestrator.AgentReply> approve(
            @PathVariable String taskId, @PathVariable String stepId) {
        return ApiResponse.ok(orchestrator.approve(taskId, stepId));
    }

    /**
     * 人工驳回：终止流程。
     *
     * @param taskId  任务 ID
     * @param stepId  待审批步骤 ID
     * @param request 驳回请求（含原因）
     * @return 处理结果
     */
    @PostMapping("/{taskId}/steps/{stepId}/reject")
    public ApiResponse<MeetingSummaryOrchestrator.AgentReply> reject(
            @PathVariable String taskId, @PathVariable String stepId,
            @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(orchestrator.reject(taskId, stepId, request.reason()));
    }

    /**
     * 指令请求 DTO。
     *
     * @param tenantId       租户
     * @param userId         企微用户 ID
     * @param conversationId 会话标识
     * @param instruction    自然语言指令
     */
    public record InstructRequest(
            @NotBlank(message = "租户 ID 不能为空") String tenantId,
            @NotBlank(message = "用户 ID 不能为空") String userId,
            @NotBlank(message = "会话 ID 不能为空") String conversationId,
            @NotBlank(message = "指令不能为空") String instruction) {
    }

    /**
     * 驳回请求 DTO。
     *
     * @param reason 驳回原因
     */
    public record RejectRequest(@NotBlank(message = "驳回原因不能为空") String reason) {
    }
}