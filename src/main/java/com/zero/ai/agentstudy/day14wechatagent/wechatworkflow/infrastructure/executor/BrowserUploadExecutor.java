package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.infrastructure.executor;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.StepResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.StepExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「上传文件」步骤执行器（Mock）。
 *
 * <p>认领 action = {@code browser.upload}。真实系统会调用 Browser Agent（Playwright）
 * 打开企业网盘/文档系统，把纪要内容保存为文件并上传。因涉及外部写操作，规划器已把该步骤
 * 标记为 {@code requiresApproval=true}，须经人工审批放行后才执行。</p>
 *
 * <p>本 Mock 从黑板读取上一步生成的纪要内容，模拟上传后返回一个文件 URL。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class BrowserUploadExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserUploadExecutor.class);

    /** 黑板 key：上传后的文件 URL。 */
    public static final String KEY_FILE_URL = "upload.fileUrl";

    @Override
    public boolean supports(String action) {
        return "browser.upload".equals(action);
    }

    @Override
    public StepResult execute(WorkflowStep step, ExecutionContext context) {
        // 依赖上一步产出：会议纪要
        String summary = context.get(OfficeSummarizeExecutor.KEY_SUMMARY)
                .map(String::valueOf)
                .orElse("");
        if (summary.isBlank()) {
            return StepResult.failure("黑板缺少会议纪要内容，无法上传");
        }

        log.info("[Executor:browser.upload] tenantId={} 模拟通过 Browser Agent 上传纪要文件", context.tenantId());

        // 模拟上传成功，返回文件 URL
        String fileUrl = "https://docs.zerohub.example/meeting/" + context.conversationId() + ".md";
        context.put(KEY_FILE_URL, fileUrl);

        return StepResult.success("已上传会议纪要文件: " + fileUrl);
    }
}