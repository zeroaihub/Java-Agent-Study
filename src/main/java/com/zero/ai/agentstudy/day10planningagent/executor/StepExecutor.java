package com.zero.ai.agentstudy.day10planningagent.executor;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import com.zero.ai.agentstudy.day10planningagent.executor.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 步骤执行器（动手）：选择工具执行单个步骤，内置重试 + 线性退避。
 * 失败绝不向主循环抛异常，而是返回 StepResult.failure。
 */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final ToolSelector toolSelector;

    @Value("${zero.planning.max-retry-per-step:2}")
    private int maxRetry;

    @Value("${zero.planning.retry-backoff-ms:500}")
    private long backoffMs;

    public StepExecutor(ToolSelector toolSelector) {
        this.toolSelector = toolSelector;
    }

    public StepResult execute(PlanStep step, PlanningContext ctx) {
        Tool tool;
        try {
            tool = toolSelector.select(step);
        } catch (Exception e) {
            step.markFailed("工具选择失败: " + e.getMessage());
            return StepResult.failure("工具选择失败: " + e.getMessage());
        }

        String lastError = null;
        // 总尝试次数 = 1 + maxRetry
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            step.markRunning();
            try {
                String output = tool.execute(step, ctx);
                step.markDone(output);
                log.info("步骤 {} 执行成功（工具={}, 第{}次尝试）", step.id(), tool.name(), attempt + 1);
                return StepResult.success(output);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("步骤 {} 第{}次尝试失败: {}", step.id(), attempt + 1, lastError);
                if (attempt < maxRetry) {
                    sleep(backoffMs * (attempt + 1)); // 线性退避
                    step.retryWith(); // 重置为 PENDING 以便下次 markRunning
                }
            }
        }
        step.markFailed(lastError);
        return StepResult.failure(lastError);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}