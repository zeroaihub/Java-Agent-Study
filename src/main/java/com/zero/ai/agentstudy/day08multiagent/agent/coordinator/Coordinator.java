package com.zero.ai.agentstudy.day08multiagent.agent.coordinator;

import com.zero.ai.agentstudy.day08multiagent.agent.core.Agent;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentContext;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentResult;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import com.zero.ai.agentstudy.day08multiagent.agent.memory.SharedMemory;
import com.zero.ai.agentstudy.day08multiagent.agent.message.Task;
import com.zero.ai.agentstudy.day08multiagent.dto.ContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Coordinator —— 协调者（整个 Multi-Agent 系统的「大脑 / 调度中心」）。
 *
 * <p>教学要点：这是第二章「协调者中心架构」的落地。Coordinator 不干具体活儿
 * （不写大纲、不查素材），它只负责一件事——<b>按既定流程编排 Agent、驱动流水线前进</b>。
 * 本项目 V1 采用最简单也最实用的「顺序流水线」策略：</p>
 *
 * <pre>
 *   PLANNER → RESEARCHER → WRITER → REVIEWER
 * </pre>
 *
 * <p>调度循环（状态机雏形）：依次取出每个角色的 Agent，喂入同一个 {@link AgentContext}，
 * 执行并检查结果；任一环节失败则「快速失败」，携带已产生的日志返回，方便排查。</p>
 *
 * <p>SOLID：Coordinator 依赖 {@link AgentManager}（按角色取 Agent）与 {@link Agent} 抽象，
 * 完全不认识 PlannerAgent 等具体类——将来改流程/换实现，改动都集中在这里。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Coordinator {

    /** 顺序流水线：定义 Agent 的执行顺序（第二章的「协同策略」被固化成一份清单） */
    private static final List<AgentRole> PIPELINE = List.of(
            AgentRole.PLANNER,
            AgentRole.RESEARCHER,
            AgentRole.WRITER,
            AgentRole.REVIEWER
    );

    /** Agent 花名册（按角色取 Agent） */
    private final AgentManager agentManager;

    /**
     * 编排整条流水线，产出最终内容。
     *
     * <p>流程：创建共享黑板与上下文 → 按 PIPELINE 顺序驱动每个 Agent →
     * 任一失败则快速返回 → 全部成功则从黑板取出成品组装 {@link ContentResponse}。</p>
     *
     * @param task 用户的总任务
     * @return 内容生产结果（含最终文章、评分、意见与全链路日志）
     */
    public ContentResponse coordinate(Task task) {
        // 1) 初始化协作现场：黑板 + 上下文
        SharedMemory memory = new SharedMemory();
        AgentContext context = new AgentContext(task, memory);
        log.info("[Coordinator] 开始协作，taskId={}，主题={}", task.getTaskId(), task.getTopic());

        // 2) 调度循环：按流水线顺序逐个驱动 Agent
        for (AgentRole role : PIPELINE) {
            if (!agentManager.has(role)) {
                String msg = "流水线缺少角色实现：" + role;
                log.error("[Coordinator] {}", msg);
                return ContentResponse.fail(msg, context.getLogs());
            }

            Agent agent = agentManager.get(role);
            AgentResult result = agent.execute(context);

            // 3) 快速失败：任一环节失败即终止，带上已产生的日志便于排查
            if (!result.isSuccess()) {
                String msg = role.getDisplayName() + " 环节失败：" + result.getMessage();
                log.error("[Coordinator] {}", msg);
                return ContentResponse.fail(msg, context.getLogs());
            }
        }

        // 4) 全部成功：从黑板取出成品组装响应
        String article = memory.getString(SharedMemory.Keys.DRAFT);
        String review = memory.getString(SharedMemory.Keys.REVIEW);
        Double score = memory.get(SharedMemory.Keys.SCORE, Double.class);

        if (article == null || article.isBlank()) {
            return ContentResponse.fail("流程结束但未产出文章草稿", context.getLogs());
        }

        log.info("[Coordinator] 协作完成，taskId={}，评分={}", task.getTaskId(), score);
        return ContentResponse.ok(article, score, review, context.getLogs());
    }
}