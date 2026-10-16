package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.infrastructure.executor;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.StepResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.StepExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「推送企业消息」步骤执行器（Mock）。
 *
 * <p>认领 action = {@code notify.send}。真实系统会调用企业微信消息通道（第二章的
 * MessageChannel / 第八章的通知中心），把纪要与文件链接以 Markdown 消息推送给项目组。
 * 本 Mock 汇总黑板中的纪要与文件 URL，模拟发送并返回回执。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class NotifySendExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(NotifySendExecutor.class);

    @Override
    public boolean supports(String action) {
        return "notify.send".equals(action);
    }

    @Override
    public StepResult execute(WorkflowStep step, ExecutionContext context) {
        String summary = context.get(OfficeSummarizeExecutor.KEY_SUMMARY)
                .map(String::valueOf)
                .orElse("（无纪要正文）");
        String fileUrl = context.getString(BrowserUploadExecutor.KEY_FILE_URL, "（无附件）");

        String message = "已为您整理会议纪要并归档：\n附件: " + fileUrl
                + "\n摘要预览: " + summary.lines().findFirst().orElse("");

        log.info("[Executor:notify.send] tenantId={} conversationId={} 模拟推送企业微信消息",
                context.tenantId(), context.conversationId());

        return StepResult.success("已推送企业消息给项目组: " + message.length() + " 字符");
    }
}