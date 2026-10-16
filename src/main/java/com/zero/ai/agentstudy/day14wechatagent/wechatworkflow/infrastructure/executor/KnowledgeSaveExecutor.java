package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.infrastructure.executor;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.StepResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.StepExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「保存到知识库」步骤执行器（Mock）。
 *
 * <p>认领 action = {@code knowledge.save}。真实系统会调用第六章的 KnowledgeService，
 * 把会议纪要切片、向量化后存入企业知识库（RAG），供日后检索问答。
 * 本 Mock 从黑板读取纪要并模拟入库，返回入库条目数。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class KnowledgeSaveExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSaveExecutor.class);

    @Override
    public boolean supports(String action) {
        return "knowledge.save".equals(action);
    }

    @Override
    public StepResult execute(WorkflowStep step, ExecutionContext context) {
        String summary = context.get(OfficeSummarizeExecutor.KEY_SUMMARY)
                .map(String::valueOf)
                .orElse("");
        if (summary.isBlank()) {
            // 无内容可存，跳过而非失败——体现「条件跳过」语义
            return StepResult.skip("黑板无纪要内容，跳过知识库保存");
        }

        // 模拟切片：按行粗略切
        int chunks = (int) summary.lines().filter(l -> !l.isBlank()).count();
        log.info("[Executor:knowledge.save] tenantId={} 模拟入库 {} 个切片", context.tenantId(), chunks);

        return StepResult.success("已保存到知识库，共 " + chunks + " 个切片");
    }
}