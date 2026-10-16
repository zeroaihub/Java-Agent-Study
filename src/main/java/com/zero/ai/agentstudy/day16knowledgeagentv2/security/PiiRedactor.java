package com.zero.ai.agentstudy.day16knowledgeagentv2.security;

import java.util.regex.Pattern;

/**
 * PII 脱敏器（Chapter 18）。
 *
 * <p>企业问答会流经日志、审计、可观测系统，若原文携带手机号、邮箱、身份证等个人敏感信息，
 * 就会在多处留痕，构成合规风险。本工具在「写日志 / 落审计」前把常见 PII 打码，做到
 * <b>可用不可见</b>。</p>
 *
 * <p>注意：脱敏应在数据离开处理边界前进行；生产环境的规则需覆盖更多类型（银行卡、地址等）
 * 并结合命名实体识别。</p>
 *
 * @author ZeroAi
 */
public final class PiiRedactor {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dxX](?!\\d)");

    private PiiRedactor() {
    }

    /**
     * 对文本中的手机号、邮箱、身份证做脱敏。
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = PHONE.matcher(text).replaceAll(m -> maskTail(m.group(), 4));
        result = ID_CARD.matcher(result).replaceAll("****************");
        result = EMAIL.matcher(result).replaceAll(m -> {
            String s = m.group();
            int at = s.indexOf('@');
            return s.charAt(0) + "***" + s.substring(at);
        });
        return result;
    }

    private static String maskTail(String s, int keepTail) {
        int mask = Math.max(0, s.length() - keepTail);
        return "*".repeat(mask) + s.substring(mask);
    }
}