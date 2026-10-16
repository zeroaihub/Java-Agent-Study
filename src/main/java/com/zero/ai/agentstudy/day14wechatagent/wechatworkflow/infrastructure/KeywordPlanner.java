package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowTask;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.Planner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词规则版规划器（Mock 实现，用于教学/测试）。
 *
 * <p>它<strong>不调用 LLM</strong>，而是用简单的关键词匹配把自然语言目标分解成步骤，
 * 好处是零依赖、结果可预测、便于单测。核心是演示「Planning Agent 把一句话拆成有序步骤」
 * 这一思想，而非分解算法本身。</p>
 *
 * <p><strong>生产期替换方案：</strong>实现一个 {@code LlmPlanner}，用 Spring AI 的
 * function-calling / structured-output 让大模型输出结构化步骤 JSON，再映射为
 * {@link WorkflowStep} 列表。规划器是端口，替换实现对引擎完全透明。</p>
 *
 * <p>规则映射（命中即追加对应步骤，保持顺序）：</p>
 * <ul>
 *     <li>含「会议 / 纪要 / 整理」-> office.summarize（生成会议纪要）</li>
 *     <li>含「上传 / 文件 / 附件」-> browser.upload（上传文件，需审批）</li>
 *     <li>含「知识库 / 保存 / 存档」-> knowledge.save（存入知识库）</li>
 *     <li>含「发送 / 通知 / 发给 / 推送」-> notify.send（推送消息）</li>
 * </ul>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class KeywordPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(KeywordPlanner.class);

    @Override
    public WorkflowTask plan(String goal, ExecutionContext context) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        List<WorkflowStep> steps = new ArrayList<>();
        int seq = 1;

        if (containsAny(goal, "会议", "纪要", "整理", "总结")) {
           steps.add(WorkflowStep.of(stepId(seq++), "生成会议纪要", "office.summarize"));
        }
        if (containsAny(goal, "上传", "文件", "附件", "文档")) {
            // 上传属敏感操作，设为需人工审批的关键步骤
            steps.add(WorkflowStep.approvalStep(stepId(seq++), "上传文件(需审批)", "browser.upload"));
        }
        if (containsAny(goal, "知识库", "保存", "存档", "归档")) {
            steps.add(WorkflowStep.of(stepId(seq++), "保存到知识库", "knowledge.save"));
        }
        if (containsAny(goal, "发送", "通知", "发给", "推送", "告知")) {
            steps.add(WorkflowStep.of(stepId(seq++), "推送企业消息", "notify.send"));
        }

        // 兜底：若无任何关键词命中，至少生成一个「回复」步骤，避免空任务
        if (steps.isEmpty()) {
            steps.add(WorkflowStep.of(stepId(seq), "直接回复", "notify.send"));
            log.warn("[Planner] 目标未命中任何规则，降级为单步回复: {}", goal);
        }

        WorkflowTask task = WorkflowTask.create(goal, context, steps);
        log.info("[Planner] 目标[{}] 分解为 {} 个步骤", goal, steps.size());
        return task;
    }

    /**
     * 生成步骤 id（顺序编号）。
     *
     * @param seq 序号
     * @return 形如 step-1 的 id
     */
    private String stepId(int seq) {
        return "step-" + seq;
    }

    /**
     * 判断文本是否包含任一关键词。
     *
     * @param text     待检文本
     * @param keywords 关键词
     * @return 命中任一即 true
     */
    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}