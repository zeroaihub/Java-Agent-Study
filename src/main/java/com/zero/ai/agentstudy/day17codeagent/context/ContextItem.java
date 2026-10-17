package com.zero.ai.agentstudy.day17codeagent.context;

/**
 * 一个被选入上下文的条目。
 *
 * @param path        文件相对路径
 * @param content     实际放入上下文的内容
 * @param mode        放入方式：SIGNATURE（只有签名）/ FULL（全文）/ SNIPPET（片段）
 * @param tokens      估算 token 数
 * @param score       相关度得分（越高越相关）
 * @param reason      为什么选中它（会一并讲给模型，也在日志里便于回溯）
 * @author ZeroAi
 */
public record ContextItem(
        String path,
        String content,
        Mode mode,
        int tokens,
        double score,
        String reason
) {

    public enum Mode {
        /** 只放签名摘要，最省 token。 */
        SIGNATURE,
        /** 放某个行区间片段。 */
        SNIPPET,
        /** 放全文，最贵但信息最全。 */
        FULL
    }

    @Override
    public String toString() {
        return String.format("%s [%s, %d token, score=%.2f] %s",
                path, mode, tokens, score, reason);
    }

    /** 渲染成给 LLM 看的片段。 */
    public String render() {
        String label = switch (mode) {
            case SIGNATURE -> "（签名摘要）";
            case SNIPPET -> "（关键片段）";
            case FULL -> "（完整文件）";
        };
        return "=== " + path + " " + label + " ===\n" + content;
    }
}
