package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.infrastructure.executor;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.StepResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.StepExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「生成会议纪要」步骤执行器（Mock）。
 *
 * <p>认领 action = {@code office.summarize}。在真实系统中，这里会调用 Office Agent
 * （Day 之前章节的能力）读取会议原始内容、调用 LLM 总结、生成结构化纪要。
 * 本 Mock 直接返回一段模拟纪要文本，并写入黑板，供后续「上传/保存/发送」步骤复用。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class OfficeSummarizeExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(OfficeSummarizeExecutor.class);

    /** 黑板 key：会议纪要内容。 */
    public static final String KEY_SUMMARY = "meeting.summary";

    @Override
    public boolean supports(String action) {
        return "office.summarize".equals(action);
    }

    @Override
    public StepResult execute(WorkflowStep step, ExecutionContext context) {
        log.info("[Executor:office.summarize] tenantId={} 开始生成会议纪要", context.tenantId());

        // 模拟调用 Office Agent + LLM 生成纪要
        String summary = """
                # 会议纪要（自动生成）
                - 参会人：项目组全体
                - 议题一：Day14 企业微信 Agent 进度对齐
                - 议题二：Workflow 编排引擎设计评审
                - 结论：本周完成 wechatworkflow 模块，下周进入通知中心
                - 待办：@张三 补充 API 文档；@李四 准备演示环境
                """;

        // 写入黑板，供后续步骤消费
        context.put(KEY_SUMMARY, summary);

        return StepResult.success("已生成会议纪要，长度 " + summary.length() + " 字符");
    }
}