package com.zero.ai.agentstudy.day08multiagent.service;

import com.zero.ai.agentstudy.day08multiagent.agent.coordinator.Coordinator;
import com.zero.ai.agentstudy.day08multiagent.agent.message.Task;
import com.zero.ai.agentstudy.day08multiagent.dto.ContentRequest;
import com.zero.ai.agentstudy.day08multiagent.dto.ContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ContentService —— 内容生产的应用服务层。
 *
 * <p>教学要点（分层职责）：Service 是「用例编排层」，负责把外部 DTO 转换成
 * 内部领域对象（{@link Task}），再委托 {@link Coordinator} 执行多 Agent 协作，
 * 最后把结果返回给 Controller。它不含具体业务算法，只做「翻译 + 转交」。</p>
 *
 * <p>为什么要这一层而不让 Controller 直接调 Coordinator？</p>
 * <ul>
 *   <li>隔离 Web 层与领域层：Controller 只管 HTTP，Service 管业务用例；</li>
 *   <li>便于将来加事务、缓存、鉴权等横切逻辑，不污染 Controller。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    /** 多 Agent 协调者（依赖抽象编排，Service 不关心内部细节） */
    private final Coordinator coordinator;

    /**
     * 生产一篇内容：DTO → Task → 协作 → 响应。
     *
     * @param request 内容请求（主题 + 要求）
     * @return 内容生产结果
     */
    public ContentResponse produce(ContentRequest request) {
        // 1) 入参校验：主题不能为空
        if (request == null || request.getTopic() == null || request.getTopic().isBlank()) {
            return ContentResponse.fail("主题(topic)不能为空", null);
        }

        // 2) DTO → 领域对象 Task（自动生成 taskId）
        Task task = Task.of(request.getTopic(), request.getRequirement());
        log.info("[ContentService] 收到内容生产请求，主题={}", task.getTopic());

        // 3) 委托 Coordinator 执行多 Agent 协作流水线
        ContentResponse response = coordinator.coordinate(task);

        // 4) 直接返回（日志已由各 Agent 记录并随响应带回）
        log.info("[ContentService] 生产完成，成功={}", response.isSuccess());
        return response;
    }
}