package com.zero.ai.agentstudy.day17codeagent.patch;

/**
 * 补丁应用结果。
 *
 * <p>与工具调用同理：<b>补丁失败是返回值，不是异常</b>。因为「打不上」是常态，
 * 处理方式是把失败原因（加上原文片段）回传给模型让它重写补丁，而不是中断任务。
 * 这正是自修复循环（Chapter 13）得以成立的前提。</p>
 *
 * @param success  是否成功应用
 * @param filePath 目标文件
 * @param content  成功时的新文件内容；失败时为 null
 * @param reason   失败原因
 * @param hint     给模型的修复建议
 * @param matchCount 实际匹配到的片段次数（诊断用）
 * @param strategy 最终生效的匹配策略（精确 / 空白容错）
 * @author ZeroAi
 */
public record PatchResult(
        boolean success,
        String filePath,
        String content,
        String reason,
        String hint,
        int matchCount,
        MatchStrategy strategy
) {

    public enum MatchStrategy {
        /** 逐字节精确匹配。 */
        EXACT,
        /** 去掉行尾空白后匹配。 */
        IGNORE_TRAILING_WHITESPACE,
        /** 逐行 trim 后匹配。 */
        IGNORE_INDENT,
        /** 归一化所有连续空白后匹配（最后一招，风险最高）。 */
        NORMALIZE_WHITESPACE
    }

    public static PatchResult ok(String filePath, String content, int matchCount, MatchStrategy strategy) {
        return new PatchResult(true, filePath, content, "应用成功", null, matchCount, strategy);
    }

    public static PatchResult fail(String filePath, String reason, String hint, int matchCount) {
        return new PatchResult(false, filePath, null, reason, hint, matchCount, null);
    }

    /** 是否用了有损容错（评审时应当提示人工复核）。 */
    public boolean usedFuzzyMatch() {
        return success && strategy != MatchStrategy.EXACT;
    }

    @Override
    public String toString() {
        if (success) {
            return "PatchResult[OK " + filePath + " 匹配 " + matchCount + " 处, 策略=" + strategy + "]";
        }
        return "PatchResult[FAIL " + filePath + " " + reason
                + (hint == null ? "" : " | 建议：" + hint) + "]";
    }
}
