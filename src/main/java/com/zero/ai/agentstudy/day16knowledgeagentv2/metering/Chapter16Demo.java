package com.zero.ai.agentstudy.day16knowledgeagentv2.metering;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.Set;

/**
 * Chapter 16 演示：配额、计量与限流。
 *
 * <p>用一个每日上限为 3 的小配额，演示前 3 次放行、第 4 次触发限制。</p>
 *
 * @author ZeroAi
 */
public class Chapter16Demo {

    public static void main(String[] args) {
        AgentContext ctx = AgentContext.of(
                TenantId.of("acme"),
                WorkspaceId.of("ws-1"),
                UserId.of("u-1001"),
                Set.of("EMPLOYEE"));

        // 每日 3 次、每分钟 100 次（速率足够，只演示日配额）
        QuotaGuard guard = new QuotaGuard(new Quota(3, 100));

        for (int i = 1; i <= 5; i++) {
            try {
                guard.checkAndConsume(ctx);
                System.out.println("第 " + i + " 次请求：放行，今日已用 "
                        + guard.usedToday(ctx.tenantId()));
            } catch (AgentException e) {
                System.out.println("第 " + i + " 次请求：拒绝 [" + e.getErrorCode().code()
                        + "] " + e.getMessage());
            }
        }
    }
}