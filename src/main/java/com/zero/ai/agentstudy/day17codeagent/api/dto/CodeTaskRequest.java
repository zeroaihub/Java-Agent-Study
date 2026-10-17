package com.zero.ai.agentstudy.day17codeagent.api.dto;

/**
 * 提交编码任务的请求体。
 *
 * <p><b>刻意没有 tenantId / userId 字段</b>：租户与权限主体必须由网关注入、从
 * {@code CodeTaskContext} 取，绝不接受客户端自称。
 * 加一个 {@code tenantId} 入参，就等于开了一扇「任意指定租户」的门。</p>
 *
 * @param requirement     自然语言需求（必填）
 * @param branch          目标分支，默认 main
 * @param maxRepairRounds 自修复最大轮次，默认 3
 * @param dryRun          只出补丁不落盘，默认 false
 * @param testClassName   用于验收的测试类（可选）
 * @author ZeroAi
 */
public record CodeTaskRequest(
        String requirement,
        String branch,
        Integer maxRepairRounds,
        Boolean dryRun,
        String testClassName
) {

    public String effectiveRequirement() {
        return requirement == null ? "" : requirement.strip();
    }

    public String effectiveBranch() {
        return (branch == null || branch.isBlank()) ? "main" : branch.strip();
    }

    public int effectiveMaxRepairRounds() {
        if (maxRepairRounds == null) {
            return 3;
        }
        // 上限保护：客户端不能要求无限重试
        return Math.min(5, Math.max(0, maxRepairRounds));
    }

    public boolean effectiveDryRun() {
        return Boolean.TRUE.equals(dryRun);
    }
}
