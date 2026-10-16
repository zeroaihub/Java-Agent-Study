package com.zero.ai.agentstudy.day16knowledgeagentv2.security;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 提示词注入防护（Chapter 18）。
 *
 * <p>用户输入会拼进提示词送给 LLM，攻击者会试图用「忽略以上指令」「你现在是…」等话术
 * 劫持模型，越权套取系统提示或绕过安全约束。本守卫在入口做启发式检测，命中高危模式即拦截。</p>
 *
 * <p>注意：启发式规则是第一道而非唯一防线，需与「系统提示与用户输入分区」「输出侧校验」
 * 配合使用。规则要持续迭代，避免误伤正常提问。</p>
 *
 * @author ZeroAi
 */
public class PromptInjectionGuard {

    private static final List<Pattern> SUSPICIOUS = List.of(
            Pattern.compile("忽略(以上|之前|前面).{0,6}(指令|规则|提示)"),
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|above)\\s+instructions"),
            Pattern.compile("你现在是|扮演.{0,6}(管理员|系统)"),
            Pattern.compile("(?i)system\\s*prompt"),
            Pattern.compile("泄露|打印.{0,6}(系统提示|prompt)")
    );

    /**
     * 检测输入是否疑似提示词注入。
     *
     * @param userInput 用户原始输入
     * @return true 表示疑似注入
     */
    public boolean isSuspicious(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        for (Pattern p : SUSPICIOUS) {
            if (p.matcher(userInput).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 断言输入安全，命中则抛 OPERATION_NOT_ALLOWED。
     */
    public void assertSafe(String userInput) {
        if (isSuspicious(userInput)) {
            throw new AgentException(
                    AgentErrorCode.OPERATION_NOT_ALLOWED,
                    "检测到疑似提示词注入，已拦截");
        }
    }
}