package com.zero.ai.agentstudy.day14wechatagent.wechatagent.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.application.SessionService;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model.ConversationSession;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.application.WorkflowEngineDay14;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStatus;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 会议纪要全链路编排器（第十章 · 终极场景 · 顶层编排服务）。
 *
 * <p>这是整个 Day14 平台的「总指挥」——它把前九章打造的所有能力串成一条完整的
 * 端到端智能体流水线，还原开篇那个场景：</p>
 *
 * <blockquote>
 * 用户在企业微信里发一句「帮我整理今天所有会议内容，并生成会议纪要，
 * 发送给项目组，同时把重要内容保存到知识库」。
 * </blockquote>
 *
 * <p><strong>为什么需要一个独立的编排层？</strong>前九章每个模块都只对自己那一段能力负责
 * （会话、路由、规划、执行、通知……），谁都不该知道「完整业务流程长什么样」。
 * 把「业务编排」这一关注点单独放进 {@code wechatagent} 模块，符合六边形架构里
 * 「应用服务负责用例编排、领域服务负责领域规则」的分工——编排器是<strong>用例（Use Case）</strong>的落点。</p>
 *
 * <p><strong>完整链路（本类职责）：</strong></p>
 * <pre>
 *   企微消息进来
 *        |
 *   1. 识别身份 + 取/建会话      (SessionService.getOrCreate)
 *        |
 *   2. 组装执行上下文黑板         (ExecutionContext: tenantId/channel/conversation/user)
 *        |
 *   3. Planning + 顺序执行        (WorkflowEngine.planAndRun)
 *        |     KeywordPlanner 拆解为: 生成纪要 -> 上传归档 -> 存知识库 -> [审批] -> 发通知
 *        |
 *   4. 命中审批步骤则挂起          (返回 SUSPENDED，等待人工放行)
 *        |
 *   5. 人工放行后从挂起点续跑      (WorkflowEngine.approve)
 *        |
 *   6. 通知步骤经通知中心送达      (NotifySendExecutor -> NotificationCenter)
 * </pre>
 *
 * <p><strong>编排器只做编排，不写业务规则</strong>：它调用各模块的应用服务/引擎，
 * 自己不实现「怎么生成纪要」「怎么发通知」——那些是被编排模块的职责。这保证了
 * 顶层编排的稳定性与各模块的独立演进。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Service
public class MeetingSummaryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MeetingSummaryOrchestrator.class);

    private final SessionService sessionService;
    private final WorkflowEngineDay14 workflowEngineDay14;

    /**
     * 构造编排器（Spring 注入前九章的应用服务/引擎）。
     *
     * @param sessionService 会话服务（第三章）
     * @param workflowEngineDay14 工作流引擎（第七章）
     */
    public MeetingSummaryOrchestrator(SessionService sessionService, WorkflowEngineDay14 workflowEngineDay14) {
        this.sessionService = sessionService;
        this.workflowEngineDay14 = workflowEngineDay14;
    }

    /**
     * 处理一条来自企业微信的自然语言指令，跑通会议纪要全链路。
     *
     * <p>这是终极场景的统一入口。它完成「识别 -> 会话 -> 规划执行」三步，
     * 并把结果封装为 {@link AgentReply} 返回给渠道适配器。若流程命中人工审批步骤，
     * 任务会挂起（{@link WorkflowStatus#SUSPENDED}），此时返回的 reply 会携带
     * 待审批步骤 ID，供调用方引导人工在 {@code /api/agent/approve} 放行。</p>
     *
     * @param tenantId       租户标识（多租户隔离）
     * @param userId         发起指令的企微用户 ID
     * @param conversationId 会话标识（单聊为用户、群聊为群 ID）
     * @param instruction    用户的自然语言指令
     * @return 智能体处理结果
     */
    public AgentReply handle(String tenantId, String userId, String conversationId, String instruction) {
        // 1. 识别身份 + 取/建会话（多轮上下文的锚点）
        ConversationSession session = sessionService.getOrCreate(tenantId, ChannelType.WECOM, conversationId);
        sessionService.touch(session);
        log.info("[Agent] 收到指令 tenant={} user={} conv={} turn={} 指令={}",
                tenantId, userId, conversationId, session.getTurnCount(), instruction);

        // 2. 组装执行上下文（贯穿工作流的共享黑板）
        ExecutionContext context = new ExecutionContext(tenantId, ChannelType.WECOM.getCode(), conversationId, userId);

        // 3. Planning + 顺序执行（规划器把指令拆成有序步骤后逐步推进）
        WorkflowTask task = workflowEngineDay14.planAndRun(instruction, context);

        return toReply(task);
    }

    /**
     * 人工审批放行：从挂起点续跑工作流，跑完剩余步骤（通常是「发送通知」）。
     *
     * <p>对应终极场景里「Human Approval 人工确认」环节——发送对外通知这种高影响动作，
     * 需要人点一下「同意」才真正执行。放行后引擎从挂起步骤继续，直到任务完成或再次挂起。</p>
     *
     * @param taskId 任务 ID
     * @param stepId 被审批放行的步骤 ID
     * @return 续跑后的处理结果
     */
    public AgentReply approve(String taskId, String stepId) {
        log.info("[Agent] 人工审批放行 taskId={} stepId={}", taskId, stepId);
        WorkflowTask task = workflowEngineDay14.approve(taskId, stepId);
        return toReply(task);
    }

    /**
     * 人工驳回：终止流程，任务进入 FAILED。
     *
     * @param taskId 任务 ID
     * @param stepId 被驳回的步骤 ID
     * @param reason 驳回原因
     * @return 处理结果
     */
    public AgentReply reject(String taskId, String stepId, String reason) {
        log.info("[Agent] 人工驳回 taskId={} stepId={} 原因={}", taskId, stepId, reason);
        WorkflowTask task = workflowEngineDay14.reject(taskId, stepId, reason);
        return toReply(task);
    }

    /**
     * 把工作流任务的当前状态翻译成对外的智能体回复视图。
     *
     * @param task 工作流任务
     * @return 智能体回复
     */
    private AgentReply toReply(WorkflowTask task) {
        WorkflowStatus status = task.status();

        // 命中审批挂起：返回待审批步骤 ID，引导人工放行
        if (status == WorkflowStatus.SUSPENDED) {
            Optional<WorkflowStep> waiting = task.firstWaitingApproval();
            String pendingStepId = waiting.map(WorkflowStep::id).orElse(null);
            String pendingStepName = waiting.map(WorkflowStep::name).orElse("未知步骤");
            return new AgentReply(task.taskId(), status.name(), pendingStepId,
                    "任务已挂起，等待人工审批步骤：" + pendingStepName,
                    stepDigest(task));
        }

        // 已完成 / 失败：给出终态摘要
        String message = switch (status) {
            case COMPLETED -> "会议纪要全链路已完成，通知已送达项目组。";
            case FAILED -> "任务执行失败，请到管理后台查看死信与详情。";
            default -> "任务处理中，当前状态：" + status.name();
        };
        return new AgentReply(task.taskId(), status.name(), null, message, stepDigest(task));
    }

    /**
     * 生成步骤执行摘要（供回复展示每一步进展）。
     *
     * @param task 工作流任务
     * @return 步骤摘要列表
     */
    private List<StepDigest> stepDigest(WorkflowTask task) {
        return task.steps().stream()
                .map(s -> new StepDigest(s.id(), s.name(), s.status().name()))
                .toList();
    }

    /**
     * 智能体处理结果（对外视图）。
     *
     * @param taskId        工作流任务 ID
     * @param status        任务状态
     * @param pendingStepId 待审批步骤 ID（仅挂起时非空）
     * @param message       人类可读的结果说明
     * @param steps         各步骤执行摘要
     */
    public record AgentReply(String taskId, String status, String pendingStepId,
                             String message, List<StepDigest> steps) {
    }

    /**
     * 单步执行摘要。
     *
     * @param stepId 步骤 ID
     * @param name   步骤名
     * @param status 步骤状态
     */
    public record StepDigest(String stepId, String name, String status) {
    }
}