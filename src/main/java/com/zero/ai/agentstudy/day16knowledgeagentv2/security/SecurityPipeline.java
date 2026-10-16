package com.zero.ai.agentstudy.day16knowledgeagentv2.security;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全流水线（Chapter 18）：把 AI 特有的三道安全关卡串成入口守卫。
 *
 * <p>顺序固定：</p>
 * <ol>
 *     <li><b>注入防护</b>：拦截提示词注入，命中即拒并留审计；</li>
 *     <li><b>PII 脱敏</b>：对将要落日志/审计的内容打码；</li>
 *     <li><b>审计留痕</b>：无论放行还是拦截，都记录一条可追溯事件。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class SecurityPipeline {

    private final PromptInjectionGuard injectionGuard = new PromptInjectionGuard();
    private final List<AuditEvent> auditLog = new ArrayList<>();

    /**
     * 处理用户输入：拦截注入、留痕，放行则返回脱敏后的可记录文本。
     *
     * @param ctx       请求上下文
     * @param userInput 用户原始输入
     * @return 脱敏后的输入（可安全用于日志/后续处理展示）
     */
    public String guardInput(AgentContext ctx, String userInput) {
        if (injectionGuard.isSuspicious(userInput)) {
            auditLog.add(AuditEvent.of(ctx, "INJECTION_BLOCKED", userInput));
            throw new AgentException(
                    AgentErrorCode.OPERATION_NOT_ALLOWED,
                    "检测到疑似提示词注入，已拦截");
        }
        String redacted = PiiRedactor.redact(userInput);
        auditLog.add(AuditEvent.of(ctx, "QUERY", userInput));
        return redacted;
    }

    public List<AuditEvent> auditLog() {
        return List.copyOf(auditLog);
    }
}