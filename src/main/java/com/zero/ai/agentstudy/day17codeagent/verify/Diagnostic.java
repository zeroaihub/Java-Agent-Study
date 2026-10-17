package com.zero.ai.agentstudy.day17codeagent.verify;

/**
 * 一条编译 / 测试诊断信息。
 *
 * <p><b>为什么必须结构化</b>：如果只把 javac 的原始输出字符串丢回给 LLM，模型常常读不懂
 * 该改哪一行；而如果丢给 IDE，则需要 file / line / column / message 齐全。
 * 结构化之后还能做一件重要的事——<b>判断两轮错误是否相同</b>（自修复循环里的「无进展」检测）。</p>
 *
 * @param kind    诊断类别
 * @param file    文件相对路径
 * @param line    行号（1-based，不可用时为 -1）
 * @param column  列号
 * @param code    编译器给出的诊断码，如 {@code compiler.err.expected}
 * @param message 可读消息
 * @author ZeroAi
 */
public record Diagnostic(
        Kind kind,
        String file,
        long line,
        long column,
        String code,
        String message
) {

    public enum Kind {
        ERROR, WARNING, NOTE, OTHER;

        public String display() {
            return switch (this) {
                case ERROR -> "错误";
                case WARNING -> "警告";
                case NOTE -> "提示";
                case OTHER -> "其他";
            };
        }
    }

    /** 是否为阻断性错误。 */
    public boolean isError() {
        return kind == Kind.ERROR;
    }

    /** 供自修复循环去重比较：忽略列号抖动，只看 文件+行+消息。 */
    public String signature() {
        return file + ":" + line + ":" + message;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(kind.display()).append("] ");
        if (file != null && !file.isBlank()) {
            sb.append(file);
            if (line > 0) {
                sb.append(':').append(line);
            }
            sb.append(" ");
        }
        sb.append(message);
        if (code != null && !code.isBlank()) {
            sb.append(" (").append(code).append(')');
        }
        return sb.toString();
    }

    /** 转成喂给 LLM 的一句话，控制在 200 字符内。 */
    public String forPrompt() {
        String base = kind.display() + " " + file + " 第 " + line + " 行：" + message;
        return base.length() > 200 ? base.substring(0, 200) + "…" : base;
    }
}
